from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v26: expected one {label}, found {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# Main activity: user-provided Gemini key, encrypted locally, with a live test.
# -----------------------------------------------------------------------------
main_path = Path("app/src/main/java/com/erik/screenobserver/MainActivity.java")
main = main_path.read_text(encoding="utf-8")

if "import android.text.InputType;" not in main:
    main = main.replace("import android.provider.Settings;\n", "import android.provider.Settings;\nimport android.text.InputType;\n")
if "import android.widget.EditText;" not in main:
    main = main.replace("import android.widget.Button;\n", "import android.widget.Button;\nimport android.widget.EditText;\n")
if "import android.widget.ScrollView;" not in main:
    main = main.replace("import android.widget.LinearLayout;\n", "import android.widget.LinearLayout;\nimport android.widget.ScrollView;\n")

main = replace_once(
    main,
    "    private Button stopButton;\n",
    "    private Button stopButton;\n"
    "    private EditText geminiKeyInput;\n"
    "    private TextView geminiStatus;\n"
    "    private Button geminiSaveButton;\n"
    "    private Button geminiClearButton;\n",
    "Gemini UI fields",
)

main = replace_once(
    main,
    "        root.addView(info);\n",
    '''        root.addView(info);

        TextView geminiInfo = new TextView(this);
        geminiInfo.setText("IA EN LA NUBE (OPCIONAL) · Gemini 3.6 Flash\\n"
                + "Pega tu propia Gemini API key. Se cifra con Android Keystore y no se incluye en el APK. "
                + "Las peticiones visuales pueden enviar a Gemini una captura comprimida cuando la pantalla no parece sensible.");
        geminiInfo.setTextSize(13);
        geminiInfo.setPadding(0, 10, 0, 6);
        root.addView(geminiInfo);

        geminiKeyInput = new EditText(this);
        geminiKeyInput.setHint(GeminiKeyStore.hasKey(this)
                ? "Clave ya guardada · pega otra para reemplazarla"
                : "Pega aquí tu Gemini API key");
        geminiKeyInput.setSingleLine(true);
        geminiKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(geminiKeyInput);

        geminiSaveButton = new Button(this);
        geminiSaveButton.setText("GUARDAR Y PROBAR GEMINI 3.6 FLASH");
        geminiSaveButton.setOnClickListener(v -> saveAndTestGemini());
        root.addView(geminiSaveButton);

        geminiClearButton = new Button(this);
        geminiClearButton.setText("BORRAR CLAVE DE GEMINI");
        geminiClearButton.setOnClickListener(v -> clearGeminiKey());
        root.addView(geminiClearButton);

        geminiStatus = new TextView(this);
        geminiStatus.setPadding(0, 4, 0, 14);
        root.addView(geminiStatus);
''',
    "Gemini settings UI",
)

main = replace_once(
    main,
    "        setContentView(root);\n",
    "        ScrollView scroll = new ScrollView(this);\n"
    "        scroll.addView(root);\n"
    "        setContentView(scroll);\n",
    "scrollable main layout",
)

main = replace_once(
    main,
    "    private void openAccessibilityControl() {\n",
    '''    private void saveAndTestGemini() {
        String entered = geminiKeyInput == null ? "" : geminiKeyInput.getText().toString().trim();
        if (entered.isEmpty()) {
            if (!GeminiKeyStore.hasKey(this)) {
                geminiStatus.setText("Pega una Gemini API key antes de probarla.");
                return;
            }
        } else {
            if (!GeminiKeyStore.save(this, entered)) {
                geminiStatus.setText("No pude cifrar la clave con Android Keystore.");
                return;
            }
            geminiKeyInput.setText("");
            geminiKeyInput.setHint("Clave guardada · ••••");
        }
        geminiSaveButton.setEnabled(false);
        geminiStatus.setText("Probando Gemini 3.6 Flash…");
        GeminiRemoteAgent.testConfigured(this, (ok, message) -> runOnUiThread(() -> {
            if (geminiSaveButton != null) geminiSaveButton.setEnabled(true);
            if (geminiStatus != null) geminiStatus.setText(message);
        }));
    }

    private void clearGeminiKey() {
        GeminiKeyStore.clear(this);
        if (geminiKeyInput != null) {
            geminiKeyInput.setText("");
            geminiKeyInput.setHint("Pega aquí tu Gemini API key");
        }
        if (geminiStatus != null) geminiStatus.setText("Gemini: clave borrada · usaré IA local.");
    }

    private void openAccessibilityControl() {
''',
    "Gemini UI methods",
)

main = replace_once(
    main,
    "        if (status == null) return;\n\n        boolean access",
    "        if (status == null) return;\n"
    "        if (geminiStatus != null && geminiSaveButton != null && geminiSaveButton.isEnabled()) {\n"
    "            geminiStatus.setText(GeminiKeyStore.hasKey(this)\n"
    "                    ? \"Gemini 3.6 Flash: configurado · la IA local queda como respaldo\"\n"
    "                    : \"Gemini: no configurado · funcionando con IA local\");\n"
    "        }\n\n"
    "        boolean access",
    "Gemini refresh status",
)
main = main.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6")
main_path.write_text(main, encoding="utf-8")


# -----------------------------------------------------------------------------
# Service: Gemini-first understanding for ambiguous/natural requests, local fallback,
# visual context only on explicit visual requests, and cloud privacy guards.
# -----------------------------------------------------------------------------
service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")

if "import java.io.ByteArrayOutputStream;" not in service:
    service = service.replace("import org.json.JSONArray;\n\n", "import org.json.JSONArray;\n\nimport java.io.ByteArrayOutputStream;\n")

service = replace_once(
    service,
    "    private LocalLanguageAgent languageAgent;\n",
    "    private LocalLanguageAgent languageAgent;\n"
    "    private GeminiRemoteAgent geminiAgent;\n",
    "Gemini service field",
)

service = replace_once(
    service,
    "    private long lastProcess = 0;\n",
    "    private long lastProcess = 0;\n"
    "    private long lastGeminiFrameAt = 0;\n"
    "    private volatile byte[] latestGeminiFrameJpeg;\n",
    "Gemini frame fields",
)

service = replace_once(
    service,
    "        languageAgent.start();\n        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);",
    '''        languageAgent.start();
        geminiAgent = new GeminiRemoteAgent(this, value -> main.post(() -> {
            if (!speaking && !listening && !ttsPendingStart && value != null && !value.isEmpty()) {
                voiceStatus = value;
                passiveOverlay();
            }
        }));
        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);''',
    "Gemini initialization",
)

# Replace the complete typed handler so later local-patch details cannot bypass Gemini.
start = service.find("    private void handleTextCommand(String command) {")
end = service.find("    private String currentUnderstandingContext()", start)
if start < 0 or end < 0 or end <= start:
    raise SystemExit("patch_v26: could not locate typed handler")
new_typed = r'''    private void handleTextCommand(String command) {
        final String typed = command == null ? "" : command.trim();
        if (typed.isEmpty()) {
            silent("Escribe una instrucción antes de enviarla.");
            return;
        }

        // Text remains independent from microphone state.
        if (listening) cancelListening();
        voiceStatus = listeningEnabled ? "⌨ procesando texto" : "⌨ texto activo · micrófono pausado";
        passiveOverlay();

        final String context = currentUnderstandingContext();
        final List<IntentAgent.Result> plan = PhoneCommandPlanner.plan(typed, activeSkillState, context);
        boolean allCommandSteps = plan.size() > 1;
        if (allCommandSteps) {
            for (IntentAgent.Result step : plan) {
                if (step == null || step.type == IntentAgent.Type.GENERAL) {
                    allCommandSteps = false;
                    break;
                }
            }
        }
        if (allCommandSteps) {
            if (PhoneCommandPlanner.containsSensitive(typed)) {
                speak("Por seguridad, envía las acciones sensibles una por una para que pueda confirmarlas.");
                finishTypedCommand();
                return;
            }
            executeCommandPlan(plan, 0);
            return;
        }
        if (plan.size() == 1 && plan.get(0).type != IntentAgent.Type.GENERAL
                && plan.get(0).confidence >= 0.80) {
            dispatch(plan.get(0));
            finishTypedCommand();
            return;
        }

        if (geminiAgent != null && geminiAgent.isConfigured()) {
            voiceStatus = "⌨ pensando con Gemini";
            passiveOverlay();
            geminiAgent.interpretText(typed, cloudUnderstandingContext(), activeSkillState,
                    geminiScreenForRequest(typed), new GeminiRemoteAgent.Callback() {
                @Override public void onResult(GeminiRemoteAgent.Result remote) {
                    main.post(() -> handleGeminiTurn(remote, typed, true,
                            () -> handleTypedLocalFallback(typed, context, plan)));
                }
            });
            return;
        }
        handleTypedLocalFallback(typed, context, plan);
    }

    private void handleTypedLocalFallback(String typed, String context, List<IntentAgent.Result> plan) {
        final ArrayList<String> input = new ArrayList<>();
        input.add(typed);
        final float[] confidence = new float[]{1.0f};
        if (languageAgent != null) {
            voiceStatus = languageAgent.isReady() ? "⌨ pensando con IA local" : "⌨ procesando";
            passiveOverlay();
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
    }

    private void handleGeminiTurn(GeminiRemoteAgent.Result remote, String raw, boolean typed, Runnable fallback) {
        if (!runningState) return;
        if (remote == null || !remote.isSuccess()) {
            if (fallback != null) fallback.run();
            return;
        }
        if (remote.hasActions()) {
            if (PhoneCommandPlanner.containsSensitive(raw) && remote.actions.size() > 1) {
                speak("Por seguridad, las acciones sensibles deben hacerse una por una.");
                finishTypedCommand();
                return;
            }
            for (IntentAgent.Result action : remote.actions) {
                if (cloudActionBlockedOnSensitiveScreen(action)) {
                    speak("Esta pantalla parece contener información sensible. Para esa acción usa una orden directa y confirma manualmente si Android lo solicita.");
                    finishTypedCommand();
                    return;
                }
            }
            if (remote.actions.size() > 1) {
                executeCommandPlan(remote.actions, 0);
            } else {
                dispatch(remote.actions.get(0));
                finishTypedCommand();
            }
            return;
        }
        if (remote.reply != null && !remote.reply.trim().isEmpty()) {
            speak(remote.reply.trim());
            finishTypedCommand();
            return;
        }
        if (fallback != null) fallback.run();
    }

'''
service = service[:start] + new_typed + service[end:]

# Replace voice result handler with deterministic-fast-path -> Gemini -> local fallback.
start = service.find("                @Override public void onResults(Bundle b) {")
end = service.find("                @Override public void onPartialResults(Bundle b) {", start)
if start < 0 or end < 0 or end <= start:
    raise SystemExit("patch_v26: could not locate speech onResults")
new_results = r'''                @Override public void onResults(Bundle b) {
                    listening = false;
                    speechErrors = 0;
                    final ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    final float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    if (matches != null && !matches.isEmpty()) {
                        final String raw = matches.get(0);
                        final String context = currentUnderstandingContext();
                        final IntentAgent.Result deterministic = IntentAgent.interpret(matches, conf, activeSkillState, context);
                        if (deterministic != null && deterministic.type != IntentAgent.Type.GENERAL
                                && deterministic.confidence >= 0.86) {
                            dispatch(deterministic);
                            finishTypedCommand();
                            return;
                        }

                        if (geminiAgent != null && geminiAgent.isConfigured()) {
                            voiceStatus = "pensando con Gemini";
                            passiveOverlay();
                            geminiAgent.interpret(matches, conf, cloudUnderstandingContext(), activeSkillState,
                                    geminiScreenForRequest(raw), reliableSpeech(conf),
                                    new GeminiRemoteAgent.Callback() {
                                @Override public void onResult(GeminiRemoteAgent.Result remote) {
                                    main.post(() -> handleGeminiTurn(remote, raw, false,
                                            () -> handleVoiceLocalFallback(matches, conf, context)));
                                }
                            });
                            return;
                        }
                        handleVoiceLocalFallback(matches, conf, context);
                        return;
                    }
                    finishTypedCommand();
                }
'''
service = service[:start] + new_results + service[end:]

# Insert voice fallback and cloud-privacy helpers before currentUnderstandingContext.
anchor = "    private String currentUnderstandingContext()"
idx = service.find(anchor)
if idx < 0:
    raise SystemExit("patch_v26: currentUnderstandingContext anchor missing")
helpers = r'''    private void handleVoiceLocalFallback(ArrayList<String> matches, float[] conf, String context) {
        voiceStatus = languageAgent != null && languageAgent.isReady() ? "pensando con IA local" : "procesando";
        passiveOverlay();
        if (languageAgent != null) {
            languageAgent.interpret(matches, conf, context, activeSkillState,
                    new LocalLanguageAgent.Callback() {
                @Override public void onResult(LocalLanguageAgent.Result ai) {
                    main.post(() -> {
                        if (!runningState) return;
                        IntentAgent.Result u = new IntentAgent.Result(
                                ai.getType(), ai.getArgument(), matches.get(0), ai.getConfidence());
                        if (ai.getUsedModel()
                                && u.type == IntentAgent.Type.GENERAL
                                && ai.getReply() != null
                                && !ai.getReply().trim().isEmpty()) {
                            speak(ai.getReply());
                        } else {
                            dispatch(u);
                        }
                        finishTypedCommand();
                    });
                }
            });
            return;
        }
        dispatch(IntentAgent.interpret(matches, conf, activeSkillState, context));
        finishTypedCommand();
    }

    private boolean reliableSpeech(float[] confidence) {
        if (confidence == null || confidence.length == 0) return true;
        float best = -1f;
        for (float value : confidence) {
            if (value >= 0f && value <= 1f) best = Math.max(best, value);
        }
        return best < 0f || best >= 0.30f;
    }

    private String cloudUnderstandingContext() {
        if (!cloudSensitiveScreen()) return currentUnderstandingContext();
        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        String controls = access == null ? "" : access.listInteractiveElements();
        String pkg = currentContentPackage();
        return "App: " + compact(appLabel(pkg), 80)
                + " | Paquete: " + compact(pkg, 100)
                + " | Pantalla sensible: OCR omitido para proteger datos"
                + " | Controles: " + compact(controls, 700);
    }

    private boolean cloudSensitiveScreen() {
        String n = IntentAgent.normalize(lastText);
        return has(n,
                "contraseña", "contrasena", "password", "pin", "otp", "codigo de verificacion",
                "código de verificación", "codigo de seguridad", "cvv", "cvc", "numero de tarjeta",
                "número de tarjeta", "tarjeta de credito", "tarjeta de débito", "tarjeta de debito",
                "transferencia bancaria", "enviar dinero", "confirmar pago", "autorizar pago");
    }

    private boolean cloudActionBlockedOnSensitiveScreen(IntentAgent.Result action) {
        if (!cloudSensitiveScreen() || action == null) return false;
        switch (action.type) {
            case CLICK:
            case CLICK_ORDINAL:
            case CONFIRM_CLICK:
            case LONG_CLICK:
            case TYPE_TEXT:
            case BLACKJACK_PLAY:
                return true;
            default:
                return false;
        }
    }

    private byte[] geminiScreenForRequest(String request) {
        if (cloudSensitiveScreen() || !shouldAttachGeminiScreen(request)) return null;
        byte[] frame = latestGeminiFrameJpeg;
        return frame == null ? null : frame.clone();
    }

    private boolean shouldAttachGeminiScreen(String request) {
        String n = IntentAgent.normalize(request);
        return has(n,
                "pantalla", "que ves", "qué ves", "mira", "analiza", "analice", "juego", "game",
                "aprende esta app", "aprende a usar", "boton", "botón", "control", "arriba", "abajo",
                "este", "esta", "ese", "esa", "blackjack", "black jack", "ajedrez", "carta", "tablero");
    }

'''
service = service[:idx] + helpers + service[idx:]

# Cache a small JPEG at a low cadence. OCR still owns/recycles the original bitmap.
service = replace_once(
    service,
    "        ocr.process(InputImage.fromBitmap(b, 0))\n",
    "        maybeCacheGeminiFrame(b);\n"
    "        ocr.process(InputImage.fromBitmap(b, 0))\n",
    "Gemini frame cache call",
)

anchor = "    private Bitmap imageToBitmap(Image image) {"
idx = service.find(anchor)
if idx < 0:
    raise SystemExit("patch_v26: imageToBitmap anchor missing")
frame_method = r'''    private void maybeCacheGeminiFrame(Bitmap bitmap) {
        long now = SystemClock.elapsedRealtime();
        if (bitmap == null || now - lastGeminiFrameAt < 1800) return;
        lastGeminiFrameAt = now;
        Bitmap source = bitmap;
        Bitmap scaled = null;
        try {
            int w = bitmap.getWidth(), h = bitmap.getHeight();
            int longest = Math.max(w, h);
            if (longest > 900) {
                float scale = 900f / longest;
                scaled = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
                source = scaled;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(220_000);
            source.compress(Bitmap.CompressFormat.JPEG, 62, out);
            byte[] bytes = out.toByteArray();
            if (bytes.length > 0 && bytes.length < 1_600_000) latestGeminiFrameJpeg = bytes;
        } catch (Throwable ignored) {
        } finally {
            if (scaled != null) try { scaled.recycle(); } catch (Throwable ignored) { }
        }
    }

'''
service = service[:idx] + frame_method + service[idx:]

# Cloud agent is lifecycle-bound to the foreground service.
service = replace_once(
    service,
    "        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }\n",
    "        if (geminiAgent != null) try { geminiAgent.close(); } catch (Exception ignored) { }\n"
    "        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }\n",
    "Gemini cleanup",
)

service = service.replace("Screen Observer Pro 2.5", "Screen Observer Pro 2.6")
service = service.replace("ScreenObserverPro/2.5", "ScreenObserverPro/2.6")
service_path.write_text(service, encoding="utf-8")

print("patch_v26: Gemini 3.6 cloud agent, encrypted key UI, visual context and local fallback applied")
