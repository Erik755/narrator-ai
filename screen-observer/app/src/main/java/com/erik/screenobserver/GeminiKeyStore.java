package com.erik.screenobserver;

import android.content.Context;

/** Compatibility wrapper around the single Android-Keystore-backed Gemini secret store. */
public final class GeminiKeyStore {
    private GeminiKeyStore() { }

    public static void save(Context context, String apiKey) throws Exception {
        GeminiSecretStore.save(context, apiKey);
    }

    public static String load(Context context) {
        return GeminiSecretStore.load(context);
    }

    public static void clear(Context context) {
        GeminiSecretStore.clear(context);
    }

    public static boolean hasKey(Context context) {
        return GeminiSecretStore.hasKey(context);
    }
}
