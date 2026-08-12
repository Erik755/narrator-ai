from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/GeminiRemoteAgent.kt")
text = path.read_text(encoding="utf-8")
old = '''        @JvmStatic fun getApiKey(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API, "")?.trim().orEmpty()

        @JvmStatic fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

        @JvmStatic fun saveApiKey(context: Context, value: String) {
            val clean = value.trim()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
                if (clean.isEmpty()) remove(KEY_API) else putString(KEY_API, clean)
            }.apply()
        }'''
new = '''        @JvmStatic fun getApiKey(context: Context): String = GeminiSecretStore.load(context).trim()

        @JvmStatic fun hasApiKey(context: Context): Boolean = GeminiSecretStore.hasKey(context)

        @JvmStatic fun saveApiKey(context: Context, value: String) {
            GeminiSecretStore.save(context, value)
        }'''
count = text.count(old)
if count != 1:
    raise SystemExit(f"patch_v26_secure_key: expected one legacy key block, found {count}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("patch_v26_secure_key: encrypted Gemini key store wired")
