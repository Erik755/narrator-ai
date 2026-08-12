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

/** Stores the Gemini API key encrypted with a non-exportable Android Keystore key. */
public final class GeminiSecretStore {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "screen_observer_gemini_key_v2";
    private static final String PREFS = "screen_observer_secure";
    private static final String KEY_CIPHER = "gemini_ciphertext";
    private static final String KEY_IV = "gemini_iv";

    private GeminiSecretStore() { }

    public static synchronized void save(Context context, String apiKey) throws Exception {
        String clean = apiKey == null ? "" : apiKey.trim();
        if (clean.isEmpty()) {
            clear(context);
            return;
        }
        if (clean.length() < 16) throw new IllegalArgumentException("Clave demasiado corta");
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    public static synchronized String load(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String encrypted64 = prefs.getString(KEY_CIPHER, "");
            String iv64 = prefs.getString(KEY_IV, "");
            if (encrypted64 == null || encrypted64.isEmpty() || iv64 == null || iv64.isEmpty()) return "";
            KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Base64.decode(iv64, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(encrypted64, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static boolean hasKey(Context context) {
        return !load(context).isEmpty();
    }

    public static synchronized void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove(KEY_CIPHER).remove(KEY_IV).apply();
        try {
            KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
            store.load(null);
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        } catch (Exception ignored) { }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
            if (existing != null) return existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
