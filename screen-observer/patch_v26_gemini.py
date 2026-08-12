from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v26_gemini: expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


# Gemini remote agent: use current capable/free-tier models with stable fallbacks.
gemini_path = Path("app/src/main/java/com/erik/screenobserver/GeminiRemoteAgent.java")
gemini = gemini_path.read_text(encoding="utf-8")
gemini = gemini.replace(
    'private static final String[] MODELS = {"gemini-3.6-flash", "gemini-3.5-flash-lite"};',
    'private static final String[] MODELS = {"gemini-3.6-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite"};')
gemini_path.write_text(gemini, encoding="utf-8")


# LocalLanguageAgent becomes a hybrid facade: Gemini first when a key exists,
# otherwise the existing local Qwen/deterministic stack remains available.
llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")
llm = replace_once(
    llm,
    '    private val appContext = context.applicationContext\n    private val executor: ExecutorService = Executors.newSingleThreadExecutor()',
    '    private val appContext = context.applicationContext\n    private val geminiAgent = GeminiRemoteAgent(appContext)\n    private val executor: ExecutorService = Executors.newSingleThreadExecutor()',
    'Gemini agent field',
)
llm = replace_once(
    llm,
    '''    fun start() {
        executor.execute {
            if (closed || ready) return@execute
            val preferred = if (supportsEnhancedModel()) ENHANCED_MODEL else STANDARD_MODEL''',
    '''    fun start() {
        executor.execute {
            if (closed || ready) return@execute
            if (geminiAgent.isConfigured) {
                updateStatus("Gemini listo · nube")
                return@execute
            }
            val preferred = if (supportsEnhancedModel()) ENHANCED_MODEL else STANDARD_MODEL''',
    'skip local model when Gemini configured',
)
llm = replace_once(
    llm,
    '    fun isReady(): Boolean = ready && conversation != null\n\n    fun getState(): String = state',
    '    fun isReady(): Boolean = geminiAgent.isConfigured || (ready && conversation != null)\n\n    fun getState(): String = if (geminiAgent.isConfigured) geminiAgent.state else state',
    'Gemini readiness state',
)
llm = replace_once(
    llm,
    '''        val fallback = IntentAgent.interpret(fallbackTexts, fallbackScores, activeSkill ?: "", screenText ?: "")

        // Explicit, high-confidence device commands are safer and more reliable through''',
    '''        val fallback = IntentAgent.interpret(fallbackTexts, fallbackScores, activeSkill ?: "", screenText ?: "")

        // When the user configured Gemini, every natural-language turn goes through the
        // remote reasoning layer first. The same Android safety executor still performs actions.
        if (geminiAgent.isConfigured) {
            geminiAgent.interpret(fallbackTexts, fallbackScores, screenText ?: "", activeSkill ?: "", callback)
            return
        }

        // Explicit, high-confidence device commands are safer and more reliable through''',
    'Gemini-first interpretation',
)
llm = replace_once(
    llm,
    '''    override fun close() {
        closed = true
        ready = false''',
    '''    override fun close() {
        closed = true
        ready = false
        try { geminiAgent.close() } catch (_: Throwable) { }''',
    'Gemini cleanup',
)
llm = llm.replace('ScreenObserverPro/2.5', 'ScreenObserverPro/2.6')
llm_path.write_text(llm, encoding="utf-8")


# Main UI: API-key configuration is local to the phone and encrypted by Android Keystore.
main_path = Path("app/src/main/java/com/erik/screenobserver/MainActivity.java")
main = main_path.read_text(encoding="utf-8")
main = replace_once(
    main,
    'import android.provider.Settings;\nimport android.view.Gravity;',
    'import android.provider.Settings;\nimport android.text.InputType;\nimport android.view.Gravity;',
    'InputType import',
)
main = replace_once(
    main,
    'import android.widget.Button;\nimport android.widget.LinearLayout;\nimport android.widget.TextView;',
    'import android.widget.Button;\nimport android.widget.EditText;\nimport android.widget.LinearLayout;\nimport android.widget.TextView;',
    'EditText import',
)
anchor = '''        info.setPadding(0, 18, 0, 16);
        root.addView(info);

        accessibilityButton = new Button(this);'''
insert = '''        info.setPadding(0, 18, 0, 16);
        root.addView(info);

        TextView geminiTitle = new TextView(this);
        geminiTitle.setText("IA principal: Gemini");
        geminiTitle.setTextSize(17);
        geminiTitle.setTextColor(Color.BLACK);
        geminiTitle.setPadding(0, 8, 0, 4);
        root.addView(geminiTitle);

        TextView geminiStatus = new TextView(this);
        geminiStatus.setText(GeminiSecretStore.hasKey(this)
                ? "Gemini configurado. La clave está cifrada en este teléfono."
                : "Pega una Gemini API key para usar la IA en la nube. Sin clave se usa el respaldo local.");
        geminiStatus.setTextSize(13);
        root.addView(geminiStatus);

        EditText geminiKey = new EditText(this);
        geminiKey.setHint("Gemini API key");
        geminiKey.setSingleLine(true);
        geminiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(geminiKey);

        Button saveGemini = new Button(this);
        saveGemini.setText("GUARDAR CLAVE GEMINI");
        saveGemini.setOnClickListener(v -> {
            try {
                GeminiSecretStore.save(this, geminiKey.getText().toString());
                geminiKey.setText("");
                geminiStatus.setText("Gemini configurado. Ya puedes usar voz o texto; no hace falta reiniciar la app.");
                status.setText("Gemini activado como IA principal.");
            } catch (Exception e) {
                geminiStatus.setText("No pude guardar la clave. Comprueba que esté completa.");
            }
        });
        root.addView(saveGemini);

        Button clearGemini = new Button(this);
        clearGemini.setText("BORRAR CLAVE GEMINI");
        clearGemini.setOnClickListener(v -> {
            GeminiSecretStore.clear(this);
            geminiKey.setText("");
            geminiStatus.setText("Clave borrada. Se usará el respaldo local.");
        });
        root.addView(clearGemini);

        accessibilityButton = new Button(this);'''
main = replace_once(main, anchor, insert, 'Gemini settings UI')
main = main.replace('Screen Observer Pro 2.5', 'Screen Observer Pro 2.6')
main_path.write_text(main, encoding="utf-8")


# Package version and generated branding.
gradle_path = Path("app/build.gradle")
gradle = gradle_path.read_text(encoding="utf-8")
gradle = gradle.replace("versionCode 12", "versionCode 13")
gradle = gradle.replace("versionName '2.5.0'", "versionName '2.6.0'")
gradle_path.write_text(gradle, encoding="utf-8")

for path in [
    Path("app/src/main/java/com/erik/screenobserver/MainActivity.java"),
    Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java"),
    Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt"),
]:
    if path.exists():
        text = path.read_text(encoding="utf-8")
        text = text.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6")
        text = text.replace("ScreenObserverPro/2.5", "ScreenObserverPro/2.6")
        path.write_text(text, encoding="utf-8")

print("patch_v26_gemini: Gemini-first hybrid agent and encrypted key UI applied")
