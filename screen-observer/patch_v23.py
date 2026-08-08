from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v23: expected exactly one {label}, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "    private BargeInDetector barge;\n    private SkillManager skills;\n\n    private boolean listeningEnabled = true;",
    "    private BargeInDetector barge;\n    private SkillManager skills;\n    private LocalLanguageAgent languageAgent;\n\n    private boolean listeningEnabled = true;",
    "language-agent field anchor",
)

replace_once(
    "    private boolean autoLearning = false;\n    private boolean visionMappingSafe = true;\n    private int speechErrors = 0;",
    "    private boolean autoLearning = false;\n    private boolean visionMappingSafe = true;\n    private boolean cuePending = true;\n    private int speechErrors = 0;",
    "ready-cue field anchor",
)

replace_once(
    "        skills = new SkillManager(this);\n        activeSkillState = skills.getActiveSkillName();\n        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);",
    "        skills = new SkillManager(this);\n        activeSkillState = skills.getActiveSkillName();\n        languageAgent = new LocalLanguageAgent(this, new LocalLanguageAgent.StatusListener() {\n            @Override public void onStatus(String value) {\n                main.post(() -> {\n                    if (!speaking && !listening) {\n                        voiceStatus = value;\n                        passiveOverlay();\n                    }\n                });\n            }\n        });\n        languageAgent.start();\n        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);",
    "language-agent initialization",
)

replace_once(
    "        voiceStatus = \"escuchando\";\n        passiveOverlay();\n        startListening(250);",
    "        cuePending = true;\n        voiceStatus = \"preparando escucha\";\n        passiveOverlay();\n        startListening(120);",
    "initial listening status",
)

replace_once(
    "    private void interruptSpeech() {\n        if (!speaking) return;\n        bargeInterrupted = true;\n        if (barge != null) barge.stop();\n        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }\n        speaking = false;\n        ttsPendingStart = false;\n        ignoreUntil = SystemClock.elapsedRealtime() + 50;\n        voiceStatus = \"interrumpido · escuchando\";\n        passiveOverlay();\n        startListening(70);\n    }",
    "    private void interruptSpeech() {\n        if (!speaking) return;\n        bargeInterrupted = true;\n        if (barge != null) barge.stop();\n        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }\n        speaking = false;\n        ttsPendingStart = false;\n        ignoreUntil = SystemClock.elapsedRealtime() + 50;\n        cuePending = true;\n        voiceStatus = \"preparando escucha\";\n        passiveOverlay();\n        startListening(70);\n    }",
    "barge-in restart",
)

replace_once(
    "            long delay = interrupted ? 80 : 550;\n            ignoreUntil = SystemClock.elapsedRealtime() + delay;\n            voiceStatus = listeningEnabled ? \"escuchando\" : \"escucha pausada\";\n            passiveOverlay();\n            startListening(delay);",
    "            long delay = interrupted ? 80 : 260;\n            ignoreUntil = SystemClock.elapsedRealtime() + delay;\n            cuePending = listeningEnabled;\n            voiceStatus = listeningEnabled ? \"preparando escucha\" : \"escucha pausada\";\n            passiveOverlay();\n            startListening(delay);",
    "finish-speech restart",
)

replace_once(
    "                @Override public void onReadyForSpeech(Bundle b) {\n                    speechErrors = 0;\n                    listening = true;\n                    listeningState = true;\n                    voiceStatus = \"escuchando\";\n                    passiveOverlay();\n                }\n                @Override public void onBeginningOfSpeech() {\n                    voiceStatus = \"te estoy oyendo\";\n                    passiveOverlay();\n                }",
    "                @Override public void onReadyForSpeech(Bundle b) {\n                    speechErrors = 0;\n                    listening = true;\n                    listeningState = true;\n                    voiceStatus = \"🟢 listo · habla ahora\";\n                    passiveOverlay();\n                    if (cuePending) {\n                        cuePending = false;\n                        ReadyCue.signal();\n                    }\n                }\n                @Override public void onBeginningOfSpeech() {\n                    voiceStatus = \"escuchando tu petición\";\n                    passiveOverlay();\n                }",
    "recognizer-ready callback",
)

replace_once(
    "                @Override public void onEndOfSpeech() {\n                    listening = false;\n                    voiceStatus = \"entendiendo\";\n                    passiveOverlay();\n                }",
    "                @Override public void onEndOfSpeech() {\n                    listening = false;\n                    voiceStatus = \"procesando\";\n                    passiveOverlay();\n                }",
    "end-of-speech status",
)

old_results = '''                @Override public void onResults(Bundle b) {
                    listening = false;
                    speechErrors = 0;
                    ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    if (matches != null && !matches.isEmpty()) {
                        IntentAgent.Result u = IntentAgent.interpret(matches, conf, activeSkillState, lastText);
                        voiceStatus = "entendí: " + u.type.name().toLowerCase(Locale.ROOT);
                        passiveOverlay();
                        dispatch(u);
                    } else {
                        voiceStatus = "escuchando";
                    }
                    if (!speaking && !ttsPendingStart) startListening(250);
                }
                @Override public void onPartialResults(Bundle b) {
                    ArrayList<String> p = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (p != null && !p.isEmpty()) {
                        voiceStatus = "oyendo: " + compact(p.get(0), 36);
                        passiveOverlay();
                    }
                }'''

new_results = '''                @Override public void onResults(Bundle b) {
                    listening = false;
                    speechErrors = 0;
                    final ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    final float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    if (matches != null && !matches.isEmpty()) {
                        voiceStatus = languageAgent != null && languageAgent.isReady()
                                ? "pensando con IA" : "procesando";
                        passiveOverlay();
                        if (languageAgent != null) {
                            languageAgent.interpret(matches, conf, lastText, activeSkillState,
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
                                        if (!speaking && !ttsPendingStart && listeningEnabled) {
                                            cuePending = true;
                                            voiceStatus = "preparando escucha";
                                            passiveOverlay();
                                            startListening(100);
                                        }
                                    });
                                }
                            });
                            return;
                        }
                        dispatch(IntentAgent.interpret(matches, conf, activeSkillState, lastText));
                    }
                    if (!speaking && !ttsPendingStart && listeningEnabled) {
                        cuePending = true;
                        voiceStatus = "preparando escucha";
                        passiveOverlay();
                        startListening(100);
                    }
                }
                @Override public void onPartialResults(Bundle b) {
                    // Do not expose or repeat an unstable partial transcript.
                    voiceStatus = "escuchando tu petición";
                    passiveOverlay();
                }'''
replace_once(old_results, new_results, "speech result/partial callbacks")

replace_once(
    "                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {\n                        voiceStatus = \"escuchando\";\n                        startListening(220);\n                        return;\n                    }",
    "                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {\n                        voiceStatus = \"preparando escucha\";\n                        startListening(160);\n                        return;\n                    }",
    "no-match status",
)

replace_once(
    "            voiceStatus = \"escuchando\";\n            startListening(150);",
    "            cuePending = true;\n            voiceStatus = \"preparando escucha\";\n            startListening(100);",
    "listening-toggle enable",
)

replace_once(
    "            case HEARING_CHECK:\n                speak(\"Sí, te escucho y entendí la prueba.\");\n                break;",
    "            case HEARING_CHECK:\n                speak(\"Sí. Te escucho.\");\n                break;",
    "hearing-check response",
)

replace_once(
    "                if (r.confidence < .48) {\n                    speak(\"Te oí decir: \" + compact(r.raw, 80) + \". No entendí bien la intención. Dime qué quieres que haga con la pantalla.\");\n                } else {\n                    speak(generalAnswer(r.raw));\n                }",
    "                if (r.confidence < .48) {\n                    speak(\"No alcancé a entender la intención. Inténtalo otra vez cuando oigas la señal.\");\n                } else {\n                    speak(generalAnswer(r.raw));\n                }",
    "low-confidence response",
)

replace_once(
    "            return \"Entendí tu petición. Con la habilidad \" + skill + \", esto es lo más relevante: \" + summarize(notes, 460);",
    "            return \"Con la habilidad \" + skill + \", esto es lo más relevante: \" + summarize(notes, 460);",
    "skill general-answer prefix",
)

replace_once(
    "        return \"Entendí lo que dijiste. Puedo abrir aplicaciones, manejar controles de Android, analizar la pantalla, aprender habilidades o ejecutar una acción que me indiques.\";",
    "        return \"Puedo seguir el contexto de la conversación, responder preguntas o actuar sobre Android cuando me lo pidas.\";",
    "generic general answer",
)

replace_once(
    "        if (tts != null) try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }\n        super.onDestroy();",
    "        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }\n        if (tts != null) try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }\n        super.onDestroy();",
    "language-agent cleanup",
)

path.write_text(text, encoding="utf-8")
print("patch_v23: ScreenAgentService22 patched successfully")
