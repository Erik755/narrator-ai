from pathlib import Path

path = Path('app/src/main/java/com/erik/screenobserver/GeminiRemoteAgent.kt')
text = path.read_text(encoding='utf-8')

old = '''        private const val PREFS = "screen_observer_ai"
        private const val KEY_API = "gemini_api_key"
        private const val MODEL = "gemini-2.5-flash"
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

        @JvmStatic fun getApiKey(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API, "")?.trim().orEmpty()

        @JvmStatic fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

        @JvmStatic fun saveApiKey(context: Context, value: String) {
            val clean = value.trim()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (clean.isEmpty()) remove(KEY_API) else putString(KEY_API, clean)
            }.apply()
        }
'''
new = '''        private const val LEGACY_PREFS = "screen_observer_ai"
        private const val LEGACY_KEY_API = "gemini_api_key"
        private const val MODEL = "gemini-2.5-flash"
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

        @JvmStatic fun getApiKey(context: Context): String {
            val secure = GeminiSecretStore.load(context).trim()
            if (secure.isNotBlank()) return secure

            // One-time migration from the older v2.6 plaintext preference. Once copied into
            // Android Keystore-backed storage, immediately erase the legacy value.
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val legacy = legacyPrefs.getString(LEGACY_KEY_API, "")?.trim().orEmpty()
            if (legacy.isNotBlank()) {
                try {
                    GeminiSecretStore.save(context, legacy)
                    legacyPrefs.edit().remove(LEGACY_KEY_API).apply()
                    return GeminiSecretStore.load(context).trim()
                } catch (_: Throwable) { }
            }
            return ""
        }

        @JvmStatic fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

        @JvmStatic fun saveApiKey(context: Context, value: String) {
            val clean = value.trim()
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            legacyPrefs.edit().remove(LEGACY_KEY_API).apply()
            if (clean.isEmpty()) {
                GeminiSecretStore.clear(context)
            } else {
                GeminiSecretStore.save(context, clean)
            }
        }
'''

if old not in text:
    if 'GeminiSecretStore.load(context)' in text and 'LEGACY_KEY_API' in text:
        print('patch_v26_secure_key: already applied')
    else:
        raise SystemExit('patch_v26_secure_key: Gemini key-storage anchor missing')
else:
    text = text.replace(old, new, 1)
    path.write_text(text, encoding='utf-8')
    print('patch_v26_secure_key: Android Keystore storage + plaintext migration applied')
