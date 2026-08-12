from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v26_gemini: expected one {label}, found {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# LocalLanguageAgent becomes a hybrid router: deterministic Android actions first,
# Gemini 3.6 Flash for natural understanding when configured, local Qwen otherwise.
# -----------------------------------------------------------------------------
llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")
llm = replace_once(
    llm,
    '    @Volatile private var activeModelLabel = "IA local"\n',
    '    @Volatile private var activeModelLabel = "IA local"\n    private val cloudAgent = GeminiCloudAgent(appContext)\n',
    "cloud agent field",
)
llm = replace_once(
    llm,
    '''            if (closed || ready) return@execute
            val preferred = if (supportsEnhancedModel()) ENHANCED_MODEL else STANDARD_MODEL''',
    '''            if (closed || ready) return@execute
            if (cloudAgent.isConfigured()) {
                activeModelLabel = "Gemini 3.6 Flash"
                updateStatus("Gemini 3.6 Flash listo")
                return@execute
            }
            val preferred = if (supportsEnhancedModel()) ENHANCED_MODEL else STANDARD_MODEL''',
    "skip local model when Gemini is configured",
)
llm = replace_once(
    llm,
    '    fun isReady(): Boolean = ready && conversation != null\n',
    '    fun isReady(): Boolean = cloudAgent.isConfigured() || (ready && conversation != null)\n',
    "hybrid readiness",
)
old_local_gate = '''        if (!isReady()) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }

        val modelHypotheses = filterHypothesesForModel(hypotheses)
        executor.execute {
            if (closed || !isReady()) {
                callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
                return@execute
            }'''
new_local_gate = '''        val modelHypotheses = filterHypothesesForModel(hypotheses)
        if (cloudAgent.isConfigured()) {
            executor.execute {
                if (closed) {
                    callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
                    return@execute
                }
                try {
                    updateStatus("Pensando con Gemini 3.6 Flash…")
                    val userForCloud = modelHypotheses.mapIndexed { index, h ->
                        if (index == 0) h.text else "Alternativa ${index + 1}: ${h.text}"
                    }.joinToString(" | ")
                    val controls = try {
                        AgentAccessibilityService.getInstance()?.listInteractiveElements() ?: ""
                    } catch (_: Throwable) { "" }
                    val cloud = cloudAgent.interpret(
                        userForCloud,
                        screenText ?: "",
                        activeSkill ?: "",
                        controls,
                    )
                    var parsed = Result(
                        cloud.type,
                        cloud.argument,
                        cloud.reply,
                        cloud.confidence,
                        true,
                    )
                    if (isActionable(parsed.type) && !hasReliableSpeech(modelHypotheses)) {
                        parsed = Result(
                            IntentAgent.Type.GENERAL,
                            "",
                            "No estoy suficientemente seguro de la orden. Repítela después de la señal.",
                            0.40,
                            true,
                        )
                    } else if (isActionable(parsed.type) && parsed.confidence < 0.62) {
                        parsed = Result(
                            IntentAgent.Type.GENERAL,
                            "",
                            "No tengo suficiente certeza para ejecutar eso. Dime la acción de otra forma.",
                            parsed.confidence,
                            true,
                        )
                    }
                    updateStatus("Gemini 3.6 Flash listo")
                    callback.onResult(parsed)
                } catch (_: Throwable) {
                    updateStatus("Gemini no respondió · usando respaldo")
                    callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
                }
            }
            return
        }

        if (!(ready && conversation != null)) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }

        executor.execute {
            if (closed || !(ready && conversation != null)) {
                callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
                return@execute
            }'''
llm = replace_once(llm, old_local_gate, new_local_gate, "hybrid cloud/local inference gate")
llm = llm.replace("ScreenObserverPro/2.5", "ScreenObserverPro/2.6")
llm_path.write_text(llm, encoding="utf-8")


# -----------------------------------------------------------------------------
# Main screen: device-owner API key entry, encrypted storage, clear and test.
# -----------------------------------------------------------------------------
main_path = Path("app/src/main/java/com/erik/screenobserver/MainActivity.java")
main = main_path.read_text(encoding="utf-8")
main = replace_once(
    main,
    "import android.provider.Settings;\nimport android.view.Gravity;",
    "import android.provider.Settings;\nimport android.text.InputType;\nimport android.view.Gravity;",
    "InputType import",
)
main = replace_once(
    main,
    "import android.widget.Button;\nimport android.widget.LinearLayout;\nimport android.widget.TextView;",
    "import android.widget.Button;\nimport android.widget.EditText;\nimport android.widget.LinearLayout;\nimport android.widget.TextView;",
    "EditText import",
)
main = replace_once(
    main,
    "    private TextView status;\n    private TextView accessStatus;",
    "    private TextView status;\n    private TextView accessStatus;\n    private TextView geminiStatus;\n    private EditText geminiKeyInput;",
    "Gemini UI fields",
)
cloud_ui = '''        TextView cloudTitle = new TextView(this);
        cloudTitle.setText("IA REMOTA OPCIONAL · GEMINI 3.6 FLASH");
        cloudTitle.setTextSize(15);
        cloudTitle.setPadding(0, 10, 0, 4);
        root.addView(cloudTitle);

        geminiStatus = new TextView(this);
        geminiStatus.setTextSize(13);
        root.addView(geminiStatus);

        geminiKeyInput = new EditText(this);
        geminiKeyInput.setHint("Pega aquí tu Gemini API key");
        geminiKeyInput.setSingleLine(true);
        geminiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(geminiKeyInput);

        Button saveGemini = new Button(this);
        saveGemini.setText("GUARDAR CLAVE GEMINI");
        saveGemini.setOnClickListener(v -> {
            String key = geminiKeyInput.getText() == null ? "" : geminiKeyInput.getText().toString().trim();
            if (key.isEmpty()) {
                geminiStatus.setText("Gemini: pega una clave antes de guardar.");
                return;
            }
            try {
                GeminiKeyStore.save(this, key);
                geminiKeyInput.setText("");
                geminiStatus.setText("Gemini: configurado. La IA remota ya puede usarse.");
            } catch (Exception e) {
                geminiStatus.setText("Gemini: no pude guardar la clave de forma segura.");
            }
        });
        root.addView(saveGemini);

        LinearLayout cloudButtons = new LinearLayout(this);
        cloudButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button testGemini = new Button(this);
        testGemini.setText("PROBAR GEMINI");
        testGemini.setOnClickListener(v -> {
            geminiStatus.setText("Gemini: probando conexión…");
            new Thread(() -> {
                boolean ok = GeminiCloudAgent.testKey(MainActivity.this);
                runOnUiThread(() -> geminiStatus.setText(ok
                        ? "Gemini: conexión correcta · 3.6 Flash disponible."
                        : "Gemini: la prueba falló. Revisa la clave o la conexión."));
            }, "GeminiKeyTest").start();
        });
        cloudButtons.addView(testGemini, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button clearGemini = new Button(this);
        clearGemini.setText("BORRAR CLAVE");
        clearGemini.setOnClickListener(v -> {
            GeminiKeyStore.clear(this);
            geminiKeyInput.setText("");
            geminiStatus.setText("Gemini: sin configurar. Se usará el respaldo local.");
        });
        cloudButtons.addView(clearGemini, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(cloudButtons);

'''
main = replace_once(
    main,
    "        accessibilityButton = new Button(this);",
    cloud_ui + "        accessibilityButton = new Button(this);",
    "Gemini settings UI",
)
main = replace_once(
    main,
    "        boolean access = UIControlService.isRunning();",
    "        if (geminiStatus != null && !geminiStatus.getText().toString().contains(\"probando\")\n"
    "                && !geminiStatus.getText().toString().contains(\"prueba falló\")\n"
    "                && !geminiStatus.getText().toString().contains(\"conexión correcta\")) {\n"
    "            geminiStatus.setText(GeminiKeyStore.hasKey(this)\n"
    "                    ? \"Gemini: configurado · se prioriza 3.6 Flash.\"\n"
    "                    : \"Gemini: sin configurar · se usa IA local/respaldo.\");\n"
    "        }\n\n"
    "        boolean access = UIControlService.isRunning();",
    "Gemini status refresh",
)
main = main.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6")
main_path.write_text(main, encoding="utf-8")


# Branding in generated runtime.
service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")
service = service.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6")
service = service.replace("ScreenObserverPro/2.5", "ScreenObserverPro/2.6")
service_path.write_text(service, encoding="utf-8")

print("patch_v26_gemini: Gemini 3.6 Flash hybrid brain and encrypted key UI applied")
