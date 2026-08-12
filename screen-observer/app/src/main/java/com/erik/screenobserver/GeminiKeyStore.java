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

/** Stores the user's Gemini key encrypted with a non-exportable Android Keystore key. */
public final class GeminiKeyStore {
    private static final String PREFS = "screen_observer_gemini";
    private static final String VALUE = "api_key_ciphertext";
    private static final String ALIAS = "screen_observer_gemini_aes_v1";

    private GeminiKeyStore() { }

    public static boolean save(Context context, String apiKey) {
        if (context == null || apiKey == null || apiKey.trim().length() < 16) return false;
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] encrypted = cipher.doFinal(apiKey.trim().getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + "."
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
            prefs(context).edit().putString(VALUE, packed).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String get(Context context) {
        if (context == null) return "";
        String packed = prefs(context).getString(VALUE, "");
        if (packed == null || packed.isEmpty() || !packed.contains(".")) return "";
        try {
            String[] parts = packed.split("\\.", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean has(Context context) {
        return !get(context).isEmpty();
    }

    public static void clear(Context context) {
        if (context != null) prefs(context).edit().remove(VALUE).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
