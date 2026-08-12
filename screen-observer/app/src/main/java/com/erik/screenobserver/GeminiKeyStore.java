package com.erik.screenobserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores the user's Gemini API key encrypted at rest with Android Keystore. */
public final class GeminiKeyStore {
    private static final String PREFS = "screen_observer_cloud_secrets";
    private static final String PREF_CIPHER = "gemini_key_cipher";
    private static final String PREF_IV = "gemini_key_iv";
    private static final String KEY_ALIAS = "screen_observer_gemini_key_v1";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private GeminiKeyStore() { }

    public static synchronized boolean save(Context context, String value) {
        if (context == null) return false;
        String key = value == null ? "" : value.trim();
        if (key.isEmpty()) {
            clear(context);
            return true;
        }
        try {
            SecretKey secretKey = getOrCreateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encrypted = cipher.doFinal(key.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static synchronized String load(Context context) {
        if (context == null) return "";
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String cipherText = prefs.getString(PREF_CIPHER, "");
            String ivText = prefs.getString(PREF_IV, "");
            if (cipherText == null || cipherText.isEmpty() || ivText == null || ivText.isEmpty()) return "";

            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            java.security.Key stored = keyStore.getKey(KEY_ALIAS, null);
            if (!(stored instanceof SecretKey)) return "";

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, (SecretKey) stored,
                    new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
            byte[] clear = cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP));
            return new String(clear, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean hasKey(Context context) {
        return !load(context).isEmpty();
    }

    public static synchronized void clear(Context context) {
        if (context != null) {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(PREF_CIPHER).remove(PREF_IV).apply();
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Throwable ignored) { }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
