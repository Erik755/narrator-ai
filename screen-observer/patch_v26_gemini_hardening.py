from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v26_gemini_hardening: expected one {label}, found {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# Generated LocalLanguageAgent: keep Gemini as the cloud brain, pass a recent
# screen frame only when GeminiRemoteAgent decides the current request is visual,
# and keep the local Qwen path as fallback.
# -----------------------------------------------------------------------------
llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")

llm = replace_once(
    llm,
    '''    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val gemini = GeminiRemoteAgent(appContext)
    private val modelDir = File(appContext.filesDir, "models")''',
    '''    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val gemini = GeminiRemoteAgent(appContext)
    @Volatile private var cloudScreenFrame: ByteArray? = null
    private val modelDir = File(appContext.filesDir, "models")''',
    "Gemini screen-frame field",
)

llm = replace_once(
    llm,
    '''    fun getState(): String = state

    fun interpret(''',
    '''    fun getState(): String = state

    fun setCloudScreenFrame(frame: ByteArray?) {
        cloudScreenFrame = frame?.copyOf()
    }

    fun interpret(''',
    "cloud screen-frame setter",
)

llm = replace_once(
    llm,
    '''            gemini.interpret(
                modelHypotheses.map { it.text },
                screenText,
                activeSkill,
            ) geminiCallback@ { remote ->''',
    '''            gemini.interpret(
                modelHypotheses.map { it.text },
                screenText,
                activeSkill,
                cloudScreenFrame,
            ) geminiCallback@ { remote ->''',
    "Gemini multimodal call",
)

llm = llm.replace("Gemini 2.5 Flash", "Gemini 3.6 Flash")
llm = llm.replace("ScreenObserverPro/2.6", "ScreenObserverPro/2.6.1")
llm_path.write_text(llm, encoding="utf-8")


# -----------------------------------------------------------------------------
# Generated capture service: maintain a small local JPEG snapshot for optional
# visual requests. This does NOT upload continuously; the remote agent decides
# whether a user request needs an image and suppresses it on sensitive screens.
# -----------------------------------------------------------------------------
service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")

if "import java.io.ByteArrayOutputStream;" not in service:
    service = service.replace(
        "import org.json.JSONArray;\n\n",
        "import org.json.JSONArray;\n\nimport java.io.ByteArrayOutputStream;\n",
        1,
    )

service = replace_once(
    service,
    '''    private long ignoreUntil = 0;
    private long lastProcess = 0;
    private String lastText = "";''',
    '''    private long ignoreUntil = 0;
    private long lastProcess = 0;
    private long lastGeminiFrameAt = 0;
    private String lastText = "";''',
    "Gemini frame timestamp",
)

service = replace_once(
    service,
    '''        if (b == null) return;
        ocr.process(InputImage.fromBitmap(b, 0))''',
    '''        if (b == null) return;
        maybeCacheGeminiFrame(b);
        ocr.process(InputImage.fromBitmap(b, 0))''',
    "Gemini frame cache hook",
)

frame_method = r'''    private void maybeCacheGeminiFrame(Bitmap bitmap) {
        if (bitmap == null || languageAgent == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastGeminiFrameAt < 1800) return;
        lastGeminiFrameAt = now;

        Bitmap source = bitmap;
        Bitmap scaled = null;
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int longest = Math.max(width, height);
            if (longest > 900) {
                float scale = 900f / longest;
                scaled = Bitmap.createScaledBitmap(
                        bitmap,
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)),
                        true);
                source = scaled;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(220_000);
            if (!source.compress(Bitmap.CompressFormat.JPEG, 62, output)) return;
            byte[] bytes = output.toByteArray();
            if (bytes.length > 0 && bytes.length <= 1_600_000) {
                languageAgent.setCloudScreenFrame(bytes);
            }
        } catch (Throwable ignored) {
        } finally {
            if (scaled != null) {
                try { scaled.recycle(); } catch (Throwable ignored) { }
            }
        }
    }

'''
service = replace_once(
    service,
    "    private Bitmap imageToBitmap(Image image) {\n",
    frame_method + "    private Bitmap imageToBitmap(Image image) {\n",
    "Gemini frame-cache method",
)

# Clear the cached visual context before the model layer is closed.
cleanup = "        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }\n"
if cleanup in service and "languageAgent.setCloudScreenFrame(null);" not in service:
    service = service.replace(
        cleanup,
        "        if (languageAgent != null) try { languageAgent.setCloudScreenFrame(null); } catch (Exception ignored) { }\n" + cleanup,
        1,
    )

service = service.replace("Screen Observer Pro 2.6", "Screen Observer Pro 2.6.1")
service_path.write_text(service, encoding="utf-8")


# -----------------------------------------------------------------------------
# Generated MainActivity: clear secure-key UX, explicit test button, and no need
# to restart the assistant just to use the newly saved Gemini key.
# -----------------------------------------------------------------------------
main_path = Path("app/src/main/java/com/erik/screenobserver/MainActivity.java")
main = main_path.read_text(encoding="utf-8")

old_ui = '''        TextView geminiInfo = new TextView(this);
        geminiInfo.setText(GeminiRemoteAgent.hasApiKey(this)
                ? "IA principal: Gemini 2.5 Flash · clave configurada"
                : "IA principal opcional: Gemini 2.5 Flash. Pega tu API key para mejorar comprensión y conversación.");
        geminiInfo.setTextSize(13);
        geminiInfo.setPadding(0, 8, 0, 4);
        root.addView(geminiInfo);

        EditText geminiKey = new EditText(this);
        geminiKey.setHint(GeminiRemoteAgent.hasApiKey(this)
                ? "Clave guardada · pega otra para reemplazarla" : "Gemini API key");
        geminiKey.setSingleLine(true);
        geminiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(geminiKey);

        Button saveGemini = new Button(this);
        saveGemini.setText("GUARDAR / CAMBIAR CLAVE GEMINI");
        saveGemini.setOnClickListener(v -> {
            String key = geminiKey.getText() == null ? "" : geminiKey.getText().toString().trim();
            if (key.isEmpty()) {
                GeminiRemoteAgent.saveApiKey(this, "");
                geminiInfo.setText("Clave Gemini eliminada. Se usará la IA local/respaldo.");
            } else {
                GeminiRemoteAgent.saveApiKey(this, key);
                geminiKey.setText("");
                geminiInfo.setText("Gemini 2.5 Flash configurado. Reinicia el asistente para usarlo como IA principal.");
            }
        });
        root.addView(saveGemini);
'''

new_ui = '''        TextView geminiInfo = new TextView(this);
        geminiInfo.setText(GeminiRemoteAgent.hasApiKey(this)
                ? "IA principal: Gemini 3.6 Flash · clave cifrada con Android Keystore"
                : "IA principal opcional: Gemini 3.6 Flash. Pega tu API key para mejorar comprensión, conversación y análisis visual.");
        geminiInfo.setTextSize(13);
        geminiInfo.setPadding(0, 8, 0, 4);
        root.addView(geminiInfo);

        EditText geminiKey = new EditText(this);
        geminiKey.setHint(GeminiRemoteAgent.hasApiKey(this)
                ? "Clave ya guardada · pega otra para reemplazarla" : "Gemini API key");
        geminiKey.setSingleLine(true);
        geminiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(geminiKey);

        Button saveGemini = new Button(this);
        saveGemini.setText("GUARDAR / CAMBIAR CLAVE GEMINI");
        saveGemini.setOnClickListener(v -> {
            String key = geminiKey.getText() == null ? "" : geminiKey.getText().toString().trim();
            if (key.isEmpty()) {
                geminiInfo.setText(GeminiRemoteAgent.hasApiKey(this)
                        ? "Ya hay una clave cifrada. Pega otra para reemplazarla o usa BORRAR CLAVE."
                        : "Pega una Gemini API key antes de guardarla.");
            } else {
                GeminiRemoteAgent.saveApiKey(this, key);
                geminiKey.setText("");
                geminiInfo.setText(GeminiRemoteAgent.hasApiKey(this)
                        ? "Clave cifrada con Android Keystore. Gemini 3.6 Flash se usará desde la siguiente instrucción."
                        : "No pude guardar la clave de forma segura.");
            }
        });
        root.addView(saveGemini);

        Button testGemini = new Button(this);
        testGemini.setText("PROBAR CONEXIÓN GEMINI");
        testGemini.setOnClickListener(v -> {
            geminiInfo.setText("Probando Gemini 3.6 Flash…");
            testGemini.setEnabled(false);
            GeminiRemoteAgent.testStoredKey(this, (ok, message) -> runOnUiThread(() -> {
                geminiInfo.setText(message);
                testGemini.setEnabled(true);
            }));
        });
        root.addView(testGemini);

        Button clearGemini = new Button(this);
        clearGemini.setText("BORRAR CLAVE GEMINI");
        clearGemini.setOnClickListener(v -> {
            GeminiRemoteAgent.clearApiKey(this);
            geminiKey.setText("");
            geminiInfo.setText("Clave Gemini eliminada. La IA local queda como respaldo.");
        });
        root.addView(clearGemini);
'''

main = replace_once(main, old_ui, new_ui, "Gemini secure/test UI")
main = main.replace("Screen Observer Pro 2.6", "Screen Observer Pro 2.6.1")
main_path.write_text(main, encoding="utf-8")

print("patch_v26_gemini_hardening: secure Gemini 3.6 multimodal runtime applied")
