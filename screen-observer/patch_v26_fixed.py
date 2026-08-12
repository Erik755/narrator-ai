from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v26_fixed: expected one {label}, found {count}")
    return text.replace(old, new, 1)

# LocalLanguageAgent: Gemini first, current safety gate preserved, local fallback.
llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")

llm = replace_once(
    llm,
    '''    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val modelDir = File(appContext.filesDir, "models")
    @Volatile private var activeModelLabel = "IA local"
''',
    '''    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val gemini = GeminiRemoteAgent(appContext)
    private val modelDir = File(appContext.filesDir, "models")
    @Volatile private var activeModelLabel = "IA local"
''',
    "gemini field",
)

llm = replace_once(
    llm,
    '''            if (closed || ready) return@execute
            val preferred = if (supportsEnhancedModel()) ENHANCED_MODEL else STANDARD_MODEL''',
    '''            if (closed || ready) return@execute
            if (GeminiRemoteAgent.hasApiKey(appContext)) {
                updateStatus("Gemini 3.6 Flash configurado")
                return@execute
            }
            val preferred = if (supportsEnhancedModel()) ENHANCED_MODEL else STANDARD_MODEL''',
    "skip local download when Gemini configured",
)

current_gate = '''        // Explicit, high-confidence device commands are safer and more reliable through
        // the deterministic router. The LLM still handles paraphrases and conversation.
        if (fallback.type != IntentAgent.Type.GENERAL
            && fallback.confidence >= 0.80
            && (!isActionable(fallback.type) || hasReliableSpeech(hypotheses))
        ) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }
        if (!isReady()) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }
'''

gemini_gate = '''        // Explicit, high-confidence commands remain deterministic and retain the
        // speech-reliability safety gate. Gemini handles paraphrases/conversation.
        if (fallback.type != IntentAgent.Type.GENERAL
            && fallback.confidence >= 0.80
            && (!isActionable(fallback.type) || hasReliableSpeech(hypotheses))
        ) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }

        val modelHypotheses = filterHypothesesForModel(hypotheses)
        if (GeminiRemoteAgent.hasApiKey(appContext)) {
            updateStatus("Pensando con Gemini…")
            gemini.interpret(
                modelHypotheses.map { it.text },
                screenText,
                activeSkill,
            ) geminiCallback@ { remote ->
                if (remote == null) {
                    updateStatus(if (isReady()) "Gemini no disponible · usando IA local" else "Gemini no disponible · usando respaldo")
                    if (isReady()) {
                        interpretLocalModel(modelHypotheses, screenText, activeSkill, fallback, callback)
                    } else {
                        callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
                    }
                    return@geminiCallback
                }
                var parsed = Result(remote.type, remote.argument, remote.reply, remote.confidence, true)
                if (isActionable(parsed.type) && !hasReliableSpeech(modelHypotheses)) {
                    parsed = Result(
                        IntentAgent.Type.GENERAL,
                        "",
                        "No estoy lo bastante seguro de la orden. Repítela después de la señal.",
                        0.40,
                        true,
                    )
                }
                updateStatus("Gemini 3.6 Flash listo")
                callback.onResult(parsed)
            }
            return
        }
        if (!isReady()) {
            callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            return
        }
'''
llm = replace_once(llm, current_gate, gemini_gate, "Gemini primary routing")

old_local = '''        val modelHypotheses = filterHypothesesForModel(hypotheses)
        executor.execute {
            if (closed || !isReady()) {
                callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
                return@execute
            }
            try {
                val prompt = buildPrompt(modelHypotheses, screenText, activeSkill)
                // LiteRT-LM 0.14 documents Message as directly printable/toString consumable.
                val raw = conversation!!.sendMessage(prompt).toString()
                var parsed = parseModelResult(raw, fallback)
                if (isActionable(parsed.type) && !hasReliableSpeech(modelHypotheses)) {
                    parsed = Result(
                        IntentAgent.Type.GENERAL,
                        "",
                        "No estoy lo bastante seguro de la orden. Repítela después de la señal.",
                        0.40,
                        true,
                    )
                }
                callback.onResult(parsed)
            } catch (t: Throwable) {
                callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            }
        }
    }
'''
new_local = '''        interpretLocalModel(modelHypotheses, screenText, activeSkill, fallback, callback)
    }

    private fun interpretLocalModel(
        modelHypotheses: List<Hypothesis>,
        screenText: String?,
        activeSkill: String?,
        fallback: IntentAgent.Result,
        callback: Callback,
    ) {
        executor.execute {
            if (closed || !isReady()) {
                callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
                return@execute
            }
            try {
                val prompt = buildPrompt(modelHypotheses, screenText, activeSkill)
                val raw = conversation!!.sendMessage(prompt).toString()
                var parsed = parseModelResult(raw, fallback)
                if (isActionable(parsed.type) && !hasReliableSpeech(modelHypotheses)) {
                    parsed = Result(
                        IntentAgent.Type.GENERAL,
                        "",
                        "No estoy lo bastante seguro de la orden. Repítela después de la señal.",
                        0.40,
                        true,
                    )
                }
                callback.onResult(parsed)
            } catch (t: Throwable) {
                callback.onResult(Result(fallback.type, fallback.argument, "", fallback.confidence, false))
            }
        }
    }
'''
llm = replace_once(llm, old_local, new_local, "local fallback helper")

llm = replace_once(
    llm,
    '''        try { conversation?.close() } catch (_: Throwable) { }
        try { engine?.close() } catch (_: Throwable) { }''',
    '''        try { gemini.close() } catch (_: Throwable) { }
        try { conversation?.close() } catch (_: Throwable) { }
        try { engine?.close() } catch (_: Throwable) { }''',
    "gemini cleanup",
)
llm = llm.replace("ScreenObserverPro/2.5", "ScreenObserverPro/2.6.1")
llm_path.write_text(llm, encoding="utf-8")

# Voice gets app/package/OCR/accessibility context, not merely OCR text.
service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")
service = replace_once(
    service,
    '''                            languageAgent.interpret(matches, conf, lastText, activeSkillState,''',
    '''                            languageAgent.interpret(matches, conf, currentUnderstandingContext(), activeSkillState,''',
    "rich voice context",
)
service = service.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6.1")
service_path.write_text(service, encoding="utf-8")

# Legacy MainActivity also receives the key field; the actual launcher is patched
# separately in patch_v261_launcher_fix.py. Storage is Android-Keystore backed.
main_path = Path("app/src/main/java/com/erik/screenobserver/MainActivity.java")
main = main_path.read_text(encoding="utf-8")
if "import android.text.InputType;" not in main:
    main = main.replace("import android.provider.Settings;\n", "import android.provider.Settings;\nimport android.text.InputType;\n")
if "import android.widget.EditText;" not in main:
    main = main.replace("import android.widget.Button;\n", "import android.widget.Button;\nimport android.widget.EditText;\n")

main = replace_once(
    main,
    '''        root.addView(info);

        accessibilityButton = new Button(this);''',
    '''        root.addView(info);

        TextView geminiInfo = new TextView(this);
        geminiInfo.setText(GeminiRemoteAgent.hasApiKey(this)
                ? "IA principal: Gemini 3.6 Flash · clave configurada"
                : "IA principal opcional: Gemini 3.6 Flash. Pega tu API key para mejorar comprensión y conversación.");
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
                geminiInfo.setText("Gemini 3.6 Flash configurado. Reinicia el asistente para usarlo como IA principal.");
            }
        });
        root.addView(saveGemini);

        accessibilityButton = new Button(this);''',
    "Gemini key UI",
)
main = main.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6.1")
main_path.write_text(main, encoding="utf-8")

print("patch_v26_fixed: Gemini 3.6 hybrid brain applied")
