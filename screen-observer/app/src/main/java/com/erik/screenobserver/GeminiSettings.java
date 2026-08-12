package com.erik.screenobserver;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the user's Gemini key only in this app's private storage. */
public final class GeminiSettings {
    private static final String PREFS = "screen_observer_gemini";
    private static final String KEY_API = "api_key";

    private GeminiSettings() { }

    public static void saveApiKey(Context context, String key) {
        if (context == null) return;
        String value = key == null ? "" : key.trim();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_API, value).apply();
    }

    public static String getApiKey(Context context) {
        if (context == null) return "";
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API, "").trim();
    }

    public static boolean isConfigured(Context context) {
        return getApiKey(context).length() >= 20;
    }

    public static void clearApiKey(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_API).apply();
    }
}