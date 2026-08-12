from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v26: expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


def replace_method(text: str, signature: str, replacement: str, next_signature: str, label: str) -> str:
    start = text.find(signature)
    if start < 0:
        raise SystemExit(f"patch_v26: missing {label} start")
    end = text.find(next_signature, start)
    if end < 0:
        raise SystemExit(f"patch_v26: missing {label} end")
    return text[:start] + replacement + "\n\n" + text[end:]


# Gemini primary reasoning layer after all v2.5 patches have generated the runtime.
sp = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
s = sp.read_text(encoding="utf-8")

s = replace_once(s,
    "    private LocalLanguageAgent languageAgent;\n",
    "    private LocalLanguageAgent languageAgent;\n    private GeminiAgent geminiAgent;\n",
    "Gemini field")

s = replace_once(s,
    "        languageAgent.start();\n        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);",
    "        languageAgent.start();\n        geminiAgent = new GeminiAgent(this);\n        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);",
    "Gemini init")

voice_block = '''                @Override public void onResults(Bundle b) {
                    listening = false;
                    speechErrors = 0;
                    final ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    final float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    if (matches != null && !matches.isEmpty()) {
                        final boolean reliable = hasReliableSpeechV26(conf);
                        if (geminiAgent != null && geminiAgent.isConfigured()) {
                            voiceStatus = "pensando con Gemini";
                            passiveOverlay();
                            geminiAgent.interpret(matches.get(0), currentUnderstandingContext(), reliable,
                                    new GeminiAgent.Callback() {
                                @Override public void onResult(GeminiAgent.Result ai) {
                                    main.post(() -> {
                                        if (!runningState) return;
                                        IntentAgent.Result result = new IntentAgent.Result(
                                                ai.type, ai.argument, matches.get(0), ai.confidence);
                                        if (result.type == IntentAgent.Type.GENERAL && !ai.reply.isEmpty()) {
                                            speak(ai.reply);
                                        } else {
                                            dispatch(result);
                                        }
                                        restartListeningAfterUnderstanding();
                                    });
                                }
                                @Override public void onError(String message) {
                                    main.post(() -> interpretVoiceLocally(matches, conf));
                                }
                            });
                            return;
                        }
                        interpretVoiceLocally(matches, conf);
                        return;
                    }
                    restartListeningAfterUnderstanding();
                }'''

start = s.find("                @Override public void onResults(Bundle b) {")
end = s.find("                @Override public void onPartialResults(Bundle b) {", start)
if start < 0 or end < 0:
    raise SystemExit("patch_v26: speech callback anchors missing")
s = s[:start] + voice_block + "\n" + s[end:]

helpers = '''    private boolean hasReliableSpeechV26(float[] confidence) {
        if (confidence == null || confidence.length == 0) return true;
        float best = confidence[0];
        return best < 0f || best >= 0.42f;
    }

    private void interpretVoiceLocally(final ArrayList<String> matches, final float[] conf) {
        voiceStatus = languageAgent != null && languageAgent.isReady() ? "pensando localmente" : "procesando";
        passiveOverlay();
        if (languageAgent != null) {
            languageAgent.interpret(matches, conf, currentUnderstandingContext(), activeSkillState,
                    new LocalLanguageAgent.Callback() {
                @Override public void onResult(LocalLanguageAgent.Result ai) {
                    main.post(() -> {
                        if (!runningState) return;
                        IntentAgent.Result result = new IntentAgent.Result(
                                ai.getType(), ai.getArgument(), matches.get(0), ai.getConfidence());
                        if (ai.getUsedModel()
                                && result.type == IntentAgent.Type.GENERAL
                                && ai.getReply() != null
                                && !ai.getReply().trim().isEmpty()) {
                            speak(ai.getReply());
                        } else {
                            dispatch(result);
                        }
                        restartListeningAfterUnderstanding();
                    });
                }
            });
            return;
        }
        dispatch(IntentAgent.interpret(matches, conf, activeSkillState, currentUnderstandingContext()));
        restartListeningAfterUnderstanding();
    }

    private void restartListeningAfterUnderstanding() {
        if (!speaking && !ttsPendingStart && listeningEnabled) {
            cuePending = true;
            voiceStatus = "preparando escucha";
            passiveOverlay();
            startListening(100);
        }
    }
'''
anchor = "    private final Runnable listenRunnable = () -> {"
if anchor not in s:
    raise SystemExit("patch_v26: listenRunnable anchor missing")
s = s.replace(anchor, helpers + "\n" + anchor, 1)

new_text_handler = '''    private void handleTextCommand(String command) {
        final String typed = command == null ? "" : command.trim();
        if (typed.isEmpty()) {
            silent("Escribe una instrucción antes de enviarla.");
            return;
        }

        if (listening) cancelListening();
        voiceStatus = listeningEnabled ? "⌨ entendiendo con IA" : "⌨ texto activo · micrófono pausado";
        passiveOverlay();

        final String context = currentUnderstandingContext();
        final List<IntentAgent.Result> plan = PhoneCommandPlanner.plan(typed, activeSkillState, context);
        boolean allCommands = !plan.isEmpty();
        for (IntentAgent.Result step : plan) {
            if (step == null || step.type == IntentAgent.Type.GENERAL) {
                allCommands = false;
                break;
            }
        }

        if (plan.size() > 1 && allCommands) {
            if (PhoneCommandPlanner.containsSensitive(typed)) {
                speak("Por seguridad, envía las acciones sensibles una por una para que pueda confirmarlas.");
                finishTypedCommand();
                return;
            }
            executeCommandPlan(plan, 0);
            return;
        }

        if (plan.size() == 1 && plan.get(0).type != IntentAgent.Type.GENERAL
                && plan.get(0).confidence >= 0.82) {
            dispatch(plan.get(0));
            finishTypedCommand();
            return;
        }

        if (geminiAgent != null && geminiAgent.isConfigured()) {
            voiceStatus = "⌨ pensando con Gemini";
            passiveOverlay();
            geminiAgent.interpret(typed, context, true, new GeminiAgent.Callback() {
                @Override public void onResult(GeminiAgent.Result ai) {
                    main.post(() -> {
                        if (!runningState) return;
                        IntentAgent.Result result = new IntentAgent.Result(
                                ai.type, ai.argument, typed, ai.confidence);
                        if (result.type == IntentAgent.Type.GENERAL && !ai.reply.isEmpty()) {
                            speak(ai.reply);
                        } else {
                            dispatch(result);
                        }
                        finishTypedCommand();
                    });
                }
                @Override public void onError(String message) {
                    main.post(() -> handleTypedLocally(typed, context, plan));
                }
            });
            return;
        }

        handleTypedLocally(typed, context, plan);
    }

    private void handleTypedLocally(final String typed, final String context,
                                    final List<IntentAgent.Result> plan) {
        final ArrayList<String> input = new ArrayList<>();
        input.add(typed);
        final float[] confidence = new float[]{1.0f};
        if (languageAgent != null) {
            languageAgent.interpret(input, confidence, context, activeSkillState,
                    new LocalLanguageAgent.Callback() {
                @Override public void onResult(LocalLanguageAgent.Result ai) {
                    main.post(() -> {
                        if (!runningState) return;
                        IntentAgent.Result result = new IntentAgent.Result(
                                ai.getType(), ai.getArgument(), typed, ai.getConfidence());
                        if (ai.getUsedModel()
                                && result.type == IntentAgent.Type.GENERAL
                                && ai.getReply() != null
                                && !ai.getReply().trim().isEmpty()) {
                            speak(ai.getReply());
                        } else {
                            dispatch(result);
                        }
                        finishTypedCommand();
                    });
                }
            });
            return;
        }
        dispatch(plan.isEmpty()
                ? IntentAgent.interpret(input, confidence, activeSkillState, context)
                : plan.get(0));
        finishTypedCommand();
    }'''

s = replace_method(s,
    "    private void handleTextCommand(String command) {",
    new_text_handler,
    "    private String currentUnderstandingContext() {",
    "typed handler")

cleanup = "        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }"
if cleanup not in s:
    raise SystemExit("patch_v26: cleanup anchor missing")
s = s.replace(cleanup,
    "        if (geminiAgent != null) try { geminiAgent.close(); } catch (Exception ignored) { }\n" + cleanup,
    1)
s = s.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6")
s = s.replace("ScreenObserverPro/2.5", "ScreenObserverPro/2.6")
sp.write_text(s, encoding="utf-8")


# API-key UI on the generated v2.2/v2.5 activity.
mp = Path("app/src/main/java/com/erik/screenobserver/MainActivityV22.java")
m = mp.read_text(encoding="utf-8")
if "import android.text.InputType;" not in m:
    m = m.replace("import android.provider.Settings;\n", "import android.provider.Settings;\nimport android.text.InputType;\n")
if "import android.widget.EditText;" not in m:
    m = m.replace("import android.widget.Button;\n", "import android.widget.Button;\nimport android.widget.EditText;\n")

anchor = "        root.addView(aiNote);\n"
if anchor not in m:
    raise SystemExit("patch_v26: aiNote anchor missing")
ui = '''
        TextView geminiLabel = new TextView(this);
        geminiLabel.setText("Gemini API · cerebro principal");
        geminiLabel.setTextSize(15);
        geminiLabel.setPadding(0, 18, 0, 4);
        root.addView(geminiLabel);

        TextView geminiStatus = new TextView(this);
        geminiStatus.setText(GeminiKeyStore.has(this)
                ? "Gemini: configurado · clave cifrada en este teléfono."
                : "Gemini: sin clave · se usará la IA local de respaldo.");
        root.addView(geminiStatus);

        EditText geminiKey = new EditText(this);
        geminiKey.setHint("Pega aquí tu API key de Gemini");
        geminiKey.setSingleLine(true);
        geminiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(geminiKey);

        Button saveGemini = new Button(this);
        saveGemini.setText("GUARDAR CLAVE GEMINI");
        saveGemini.setOnClickListener(v -> {
            String key = geminiKey.getText() == null ? "" : geminiKey.getText().toString().trim();
            if (GeminiKeyStore.save(this, key)) {
                geminiKey.setText("");
                geminiStatus.setText("Gemini: configurado · clave cifrada con Android Keystore.");
            } else {
                geminiStatus.setText("No pude guardar la clave. Comprueba que esté completa.");
            }
        });
        root.addView(saveGemini);

        Button testGemini = new Button(this);
        testGemini.setText("PROBAR CONEXIÓN GEMINI");
        testGemini.setOnClickListener(v -> {
            if (!GeminiKeyStore.has(this)) {
                geminiStatus.setText("Primero guarda una API key de Gemini.");
                return;
            }
            geminiStatus.setText("Probando Gemini…");
            GeminiAgent testAgent = new GeminiAgent(this);
            testAgent.interpret("Responde brevemente: conexión correcta", "Prueba de configuración", false,
                    new GeminiAgent.Callback() {
                @Override public void onResult(GeminiAgent.Result result) {
                    runOnUiThread(() -> {
                        geminiStatus.setText("Gemini conectado correctamente.");
                        testAgent.close();
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> {
                        geminiStatus.setText("Falló Gemini: " + message);
                        testAgent.close();
                    });
                }
            });
        });
        root.addView(testGemini);

        Button clearGemini = new Button(this);
        clearGemini.setText("BORRAR CLAVE GEMINI");
        clearGemini.setOnClickListener(v -> {
            GeminiKeyStore.clear(this);
            geminiKey.setText("");
            geminiStatus.setText("Gemini: sin clave · IA local de respaldo activa.");
        });
        root.addView(clearGemini);
'''
m = m.replace(anchor, anchor + ui, 1)
m = m.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6")
m = m.replace("IA conversacional local", "Gemini 2.5 Flash + IA local de respaldo")
mp.write_text(m, encoding="utf-8")

print("patch_v26: Gemini 2.5 Flash primary reasoning + encrypted key UI applied")
