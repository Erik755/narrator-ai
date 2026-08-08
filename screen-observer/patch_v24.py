from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v24: expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# Intent interpreter: deterministic coverage for phrases the small LLM can miss.
# -----------------------------------------------------------------------------
intent_path = Path("app/src/main/java/com/erik/screenobserver/IntentAgent.java")
intent = intent_path.read_text(encoding="utf-8")
intent = replace_once(
    intent,
    "        SCREENSHOT, OPEN_SETTINGS, OPEN_APP,\n        DESCRIBE_SCREEN, READ_SCREEN, ADVICE, GENERAL",
    "        SCREENSHOT, OPEN_SETTINGS, OPEN_APP, CLOSE_APP, LEARN_CURRENT_APP,\n        DESCRIBE_SCREEN, READ_SCREEN, ADVICE, GENERAL",
    "new intent enum values",
)
intent = replace_once(
    intent,
    "    private static final Pattern OPEN_APP = Pattern.compile(\n            \"(?iu)^(?:abre|inicia|lanza|ejecuta)\\\\s+(?:la\\\\s+)?(?:app|aplicacion|aplicación)?\\\\s*(.+)$\");",
    "    private static final Pattern OPEN_APP = Pattern.compile(\n            \"(?iu)^(?:abre|inicia|lanza|ejecuta)\\\\s+(?:la\\\\s+)?(?:app|aplicacion|aplicación)?\\\\s*(.+)$\");\n    private static final Pattern CLOSE_APP = Pattern.compile(\n            \"(?iu)^(?:cierra|cerrar|sal\\\\s+de|salte\\\\s+de)\\\\s+(?:la\\\\s+)?(?:app|aplicacion|aplicación)?\\\\s*(.+)$\");",
    "close-app pattern",
)
intent = replace_once(
    intent,
    "            case OPEN_SETTINGS:\n            case OPEN_APP:\n                return true;",
    "            case OPEN_SETTINGS:\n            case OPEN_APP:\n            case CLOSE_APP:\n            case LEARN_CURRENT_APP:\n                return true;",
    "actionable v2.4 intents",
)
intent = replace_once(
    intent,
    "        Matcher m = LEARN.matcher(raw.trim());\n        if (m.find()) return r(Type.LEARN_SKILL, cleanup(m.group(1)), raw, .93);",
    "        if (has(n,\n                \"analiza este juego\", \"analiza el juego actual\", \"analiza este juego y aprende\",\n                \"aprende a usar este juego\", \"aprende este juego\", \"estudia este juego\",\n                \"observa este juego y aprende\", \"aprende como funciona este juego\",\n                \"analiza esta app y aprende\", \"analiza esta aplicacion y aprende\",\n                \"aprende a usar esta app\", \"aprende a usar esta aplicacion\"))\n            return r(Type.LEARN_CURRENT_APP, \"\", raw, .97);\n\n        Matcher m = LEARN.matcher(raw.trim());\n        if (m.find()) return r(Type.LEARN_SKILL, cleanup(m.group(1)), raw, .93);",
    "learn-current-app phrases",
)
intent = replace_once(
    intent,
    "        if (has(n, \"ve al inicio\", \"ve a inicio\", \"pantalla de inicio\", \"ve a home\", \"abre el inicio\"))\n            return r(Type.HOME, \"\", raw, .91);",
    "        if (has(n, \"ve al inicio\", \"ve a inicio\", \"pantalla de inicio\", \"ve a home\", \"abre el inicio\",\n                \"abre la pantalla principal\", \"ve a la pantalla principal\", \"vete a la pantalla principal\",\n                \"vuelve a la pantalla principal\", \"regresa a la pantalla principal\",\n                \"muestra la pantalla principal\", \"llevame a la pantalla principal\",\n                \"inicio del celular\", \"inicio del telefono\", \"sal al inicio\"))\n            return r(Type.HOME, \"\", raw, .97);",
    "home-screen synonyms",
)
intent = replace_once(
    intent,
    "        m = OPEN_APP.matcher(raw.trim());\n        if (m.find()) return r(Type.OPEN_APP, cleanup(m.group(1)), raw, .86);",
    "        m = CLOSE_APP.matcher(raw.trim());\n        if (m.find()) return r(Type.CLOSE_APP, cleanupTarget(m.group(1)), raw, .96);\n\n        m = OPEN_APP.matcher(raw.trim());\n        if (m.find()) return r(Type.OPEN_APP, cleanup(m.group(1)), raw, .86);",
    "close-app parser",
)
intent_path.write_text(intent, encoding="utf-8")


# -----------------------------------------------------------------------------
# LiteRT/Qwen: known high-confidence device commands take the deterministic path;
# Qwen remains responsible for flexible paraphrases and conversational context.
# -----------------------------------------------------------------------------
llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")
llm = replace_once(
    llm,
    "            Cuando el usuario quiera una acción en el teléfono, clasifícala usando uno de estos tipos exactos: $ACTIONS.\n            Para conversación o preguntas generales usa GENERAL y responde en reply.",
    "            Cuando el usuario quiera una acción en el teléfono, clasifícala usando uno de estos tipos exactos: $ACTIONS.\n            Casos importantes: ir, volver o abrir la pantalla principal del celular = HOME.\n            \"cierra WhatsApp\", \"sal de WhatsApp\" o \"cierra esta app\" = CLOSE_APP, con el nombre de la app en argument cuando exista.\n            \"analiza este juego para aprender a usarlo\", \"aprende a usar este juego\" o equivalentes = LEARN_CURRENT_APP.\n            Para conversación o preguntas generales usa GENERAL y responde en reply.",
    "LLM Android examples",
)
llm = replace_once(
    llm,
    "        val fallback = IntentAgent.interpret(fallbackTexts, fallbackScores, activeSkill ?: \"\", screenText ?: \"\")\n        if (!isReady()) {",
    "        val fallback = IntentAgent.interpret(fallbackTexts, fallbackScores, activeSkill ?: \"\", screenText ?: \"\")\n\n        // Explicit, high-confidence device commands are safer and more reliable through\n        // the deterministic router. The LLM still handles paraphrases and conversation.\n        if (fallback.type != IntentAgent.Type.GENERAL && fallback.confidence >= 0.80) {\n            callback.onResult(Result(fallback.type, fallback.argument, \"\", fallback.confidence, false))\n            return\n        }\n        if (!isReady()) {",
    "deterministic high-confidence fast path",
)
llm = replace_once(
    llm,
    "        IntentAgent.Type.OPEN_SETTINGS,\n        IntentAgent.Type.OPEN_APP -> true",
    "        IntentAgent.Type.OPEN_SETTINGS,\n        IntentAgent.Type.OPEN_APP,\n        IntentAgent.Type.CLOSE_APP,\n        IntentAgent.Type.LEARN_CURRENT_APP -> true",
    "LLM actionable v2.4 intents",
)
llm = llm.replace('ScreenObserverPro/2.3', 'ScreenObserverPro/2.4')
llm_path.write_text(llm, encoding="utf-8")


# -----------------------------------------------------------------------------
# Accessibility mini-window: wider overlay + multiline typed command entry.
# -----------------------------------------------------------------------------
a11y_path = Path("app/src/main/java/com/erik/screenobserver/AgentAccessibilityService.java")
a11y = a11y_path.read_text(encoding="utf-8")
a11y = replace_once(
    a11y,
    "import android.text.TextUtils;\nimport android.view.Gravity;",
    "import android.text.InputType;\nimport android.text.TextUtils;\nimport android.view.Gravity;\nimport android.view.inputmethod.EditorInfo;\nimport android.view.inputmethod.InputMethodManager;",
    "overlay keyboard imports",
)
a11y = replace_once(
    a11y,
    "import android.widget.Button;\nimport android.widget.LinearLayout;\nimport android.widget.TextView;",
    "import android.widget.Button;\nimport android.widget.EditText;\nimport android.widget.LinearLayout;\nimport android.widget.TextView;",
    "EditText import",
)
a11y = replace_once(
    a11y,
    "    private TextView overlayText;\n    private WindowManager.LayoutParams overlayParams;",
    "    private TextView overlayText;\n    private EditText overlayInput;\n    private WindowManager.LayoutParams overlayParams;",
    "overlay input field",
)
old_overlay = '''        TextView body = new TextView(this);
        body.setText("🎙 Listo");
        body.setTextColor(Color.WHITE);
        body.setTextSize(11);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(body, new LinearLayout.LayoutParams(dp(168), WindowManager.LayoutParams.WRAP_CONTENT));

        overlayParams = new WindowManager.LayoutParams(
                dp(184),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);'''
new_overlay = '''        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int overlayWidth = Math.min(dp(320), Math.max(dp(250), screenWidth - dp(16)));
        int innerWidth = Math.max(dp(220), overlayWidth - dp(14));

        TextView body = new TextView(this);
        body.setText("🎙 Listo");
        body.setTextColor(Color.WHITE);
        body.setTextSize(11);
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(body, new LinearLayout.LayoutParams(innerWidth, WindowManager.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(this);
        input.setHint("Escribe una instrucción…");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFFBDBDBD);
        input.setTextSize(13);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setSingleLine(false);
        input.setPadding(dp(8), dp(5), dp(8), dp(5));
        input.setBackgroundColor(0xFF333333);
        input.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnClickListener(v -> enterTypingMode(input));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitOverlayCommand();
                return true;
            }
            return false;
        });
        root.addView(input, new LinearLayout.LayoutParams(innerWidth, WindowManager.LayoutParams.WRAP_CONTENT));

        Button send = new Button(this);
        send.setText("ENVIAR");
        send.setTextSize(11);
        send.setMinHeight(0);
        send.setPadding(dp(4), 0, dp(4), 0);
        send.setOnClickListener(v -> submitOverlayCommand());
        root.addView(send, new LinearLayout.LayoutParams(innerWidth, dp(38)));
        overlayInput = input;

        overlayParams = new WindowManager.LayoutParams(
                overlayWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);'''
a11y = replace_once(a11y, old_overlay, new_overlay, "expanded overlay UI")
helpers = '''    private void enterTypingMode(EditText input) {
        if (input == null || overlayRoot == null || overlayParams == null || windowManager == null) return;
        try {
            overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            windowManager.updateViewLayout(overlayRoot, overlayParams);
        } catch (Exception ignored) { }
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 80);
    }

    private void exitTypingMode() {
        if (overlayInput != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(overlayInput.getWindowToken(), 0);
            overlayInput.clearFocus();
        }
        if (overlayRoot != null && overlayParams != null && windowManager != null) {
            try {
                overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                windowManager.updateViewLayout(overlayRoot, overlayParams);
            } catch (Exception ignored) { }
        }
    }

    private void submitOverlayCommand() {
        if (overlayInput == null) return;
        String command = overlayInput.getText() == null ? "" : overlayInput.getText().toString().trim();
        if (command.isEmpty()) return;
        if (!ScreenAgentService22.isRunning()) {
            if (overlayText != null) overlayText.setText("Inicia primero el asistente.");
            return;
        }
        Intent commandIntent = new Intent(this, ScreenAgentService22.class);
        commandIntent.setAction("com.erik.screenobserver.v24.TEXT_COMMAND");
        commandIntent.putExtra("textCommand", command);
        try {
            startService(commandIntent);
            overlayInput.setText("");
            if (overlayText != null) overlayText.setText("⌨ Procesando instrucción…");
        } catch (Exception e) {
            if (overlayText != null) overlayText.setText("No pude enviar la instrucción.");
        }
        exitTypingMode();
    }

'''
a11y = replace_once(
    a11y,
    "    private void removeOverlay() {",
    helpers + "    private void removeOverlay() {",
    "overlay typing helpers",
)
a11y = replace_once(
    a11y,
    "        overlayRoot = null;\n        overlayText = null;\n        overlayParams = null;",
    "        overlayRoot = null;\n        overlayText = null;\n        overlayInput = null;\n        overlayParams = null;",
    "overlay input cleanup",
)
a11y_path.write_text(a11y, encoding="utf-8")


# -----------------------------------------------------------------------------
# Runtime service: receive typed commands, close/leave current app safely, and
# learn the currently visible game/app from OCR + controls for five minutes.
# -----------------------------------------------------------------------------
service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")
service = replace_once(
    service,
    "    public static final String ACTION_DESCRIBE_CONTROLS = \"com.erik.screenobserver.v22.DESCRIBE_CONTROLS\";",
    "    public static final String ACTION_DESCRIBE_CONTROLS = \"com.erik.screenobserver.v22.DESCRIBE_CONTROLS\";\n    public static final String ACTION_TEXT_COMMAND = \"com.erik.screenobserver.v24.TEXT_COMMAND\";\n    private static final String EXTRA_TEXT_COMMAND = \"textCommand\";",
    "text command action",
)
service = replace_once(
    service,
    "    private String lastText = \"\";\n    private String pendingSensitive = \"\";",
    "    private String lastText = \"\";\n    private String lastContentPackage = \"\";\n    private String learningPackage = \"\";\n    private String learningSkillName = \"\";\n    private String lastLearningSnapshot = \"\";\n    private long learningUntil = 0;\n    private long lastLearningObservationAt = 0;\n    private int learningObservationCount = 0;\n    private String pendingSensitive = \"\";",
    "learning state",
)
service = replace_once(
    service,
    "        if (ACTION_DESCRIBE_CONTROLS.equals(action)) {\n            describeControls(true);\n            return START_NOT_STICKY;\n        }\n        if (projection != null) return START_NOT_STICKY;",
    "        if (ACTION_DESCRIBE_CONTROLS.equals(action)) {\n            describeControls(true);\n            return START_NOT_STICKY;\n        }\n        if (ACTION_TEXT_COMMAND.equals(action)) {\n            handleTextCommand(intent.getStringExtra(EXTRA_TEXT_COMMAND));\n            return START_NOT_STICKY;\n        }\n        if (projection != null) return START_NOT_STICKY;",
    "typed command receiver",
)
service = replace_once(
    service,
    "            case LEARN_SKILL:\n                learn(r.argument);\n                break;",
    "            case LEARN_SKILL:\n                learn(r.argument);\n                break;\n            case LEARN_CURRENT_APP:\n                learnCurrentApp();\n                break;",
    "learn current app dispatch",
)
service = replace_once(
    service,
    "            case OPEN_SETTINGS:\n                silent(AndroidAppController.openSettings(this) ? \"Ajustes abiertos.\" : \"No pude abrir Ajustes.\");\n                break;",
    "            case CLOSE_APP:\n                closeRequestedApp(r.argument);\n                break;\n            case OPEN_SETTINGS:\n                silent(AndroidAppController.openSettings(this) ? \"Ajustes abiertos.\" : \"No pude abrir Ajustes.\");\n                break;",
    "close-app dispatch",
)
text_handler = '''    private void handleTextCommand(String command) {
        final String typed = command == null ? "" : command.trim();
        if (typed.isEmpty()) {
            silent("Escribe una instrucción antes de enviarla.");
            return;
        }
        cancelListening();
        voiceStatus = languageAgent != null && languageAgent.isReady() ? "⌨ pensando con IA" : "⌨ procesando";
        passiveOverlay();

        final ArrayList<String> input = new ArrayList<>();
        input.add(typed);
        final float[] confidence = new float[]{1.0f};
        if (languageAgent != null) {
            languageAgent.interpret(input, confidence, lastText, activeSkillState,
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
                        if (!speaking && !ttsPendingStart && listeningEnabled) {
                            cuePending = true;
                            voiceStatus = "preparando escucha";
                            passiveOverlay();
                            startListening(120);
                        }
                    });
                }
            });
            return;
        }
        dispatch(IntentAgent.interpret(input, confidence, activeSkillState, lastText));
        if (!speaking && !ttsPendingStart && listeningEnabled) {
            cuePending = true;
            voiceStatus = "preparando escucha";
            passiveOverlay();
            startListening(120);
        }
    }

'''
service = replace_once(
    service,
    "    private void dispatch(IntentAgent.Result r) {",
    text_handler + "    private void dispatch(IntentAgent.Result r) {",
    "typed command interpreter",
)
learn_helpers = '''    private boolean transientPackage(String pkg) {
        String n = IntentAgent.normalize(pkg == null ? "" : pkg.replace('.', ' '));
        return n.isEmpty()
                || n.equals(IntentAgent.normalize(getPackageName().replace('.', ' ')))
                || n.contains("inputmethod") || n.contains("keyboard")
                || n.contains("latin ime") || n.contains("gboard");
    }

    private String currentContentPackage() {
        String pkg = AgentAccessibilityService.getActivePackageName();
        if (transientPackage(pkg)) pkg = lastContentPackage;
        return pkg == null ? "" : pkg;
    }

    private String appLabel(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "";
        try {
            android.content.pm.ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = getPackageManager().getApplicationLabel(info);
            return label == null ? pkg : label.toString().trim();
        } catch (Exception e) {
            return pkg;
        }
    }

    private boolean genericCurrentTarget(String requested) {
        String n = IntentAgent.normalize(requested);
        return n.isEmpty() || n.equals("esta app") || n.equals("esta aplicacion")
                || n.equals("la app") || n.equals("la aplicacion") || n.equals("este juego")
                || n.equals("el juego") || n.equals("juego actual") || n.equals("aplicacion actual");
    }

    private void closeRequestedApp(String requested) {
        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        if (access == null) {
            speak("Necesito Control de pantalla activo para salir de la aplicación.");
            return;
        }
        String pkg = currentContentPackage();
        String label = appLabel(pkg);
        if (pkg.isEmpty() || label.isEmpty()) {
            speak("No puedo identificar qué aplicación está abierta.");
            return;
        }
        String wanted = IntentAgent.normalize(requested);
        String labelN = IntentAgent.normalize(label);
        String packageN = IntentAgent.normalize(pkg.replace('.', ' '));
        boolean matches = genericCurrentTarget(requested)
                || labelN.equals(wanted) || labelN.contains(wanted) || wanted.contains(labelN)
                || packageN.contains(wanted);
        if (!matches) {
            speak("Ahora mismo está abierta " + label + ". Para evitar cerrar otra app por error, abre la aplicación que quieras cerrar y vuelve a pedírmelo.");
            return;
        }
        if (access.home()) {
            silent("Salí de " + compact(label, 34) + ".");
        } else {
            speak("No pude volver a la pantalla principal.");
        }
    }

    private void learnCurrentApp() {
        String pkg = currentContentPackage();
        String label = appLabel(pkg);
        if (pkg.isEmpty() || label.isEmpty() || pkg.equals(getPackageName())) {
            speak("No puedo identificar el juego o la aplicación que quieres que aprenda. Déjala visible e inténtalo otra vez.");
            return;
        }

        learningPackage = pkg;
        learningSkillName = label;
        learningUntil = SystemClock.elapsedRealtime() + 5 * 60 * 1000L;
        learningObservationCount = 0;
        lastLearningSnapshot = "";
        lastLearningObservationAt = 0;

        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        String controls = access == null ? "" : access.listInteractiveElements();
        String initial = "[Análisis local de " + label + "]\n"
                + "Paquete: " + pkg + "\n"
                + "Pantalla inicial: " + compact(lastText, 1100) + "\n"
                + "Controles iniciales: " + compact(controls, 900);
        skills.saveSkill(label, initial, new JSONArray());
        activeSkillState = skills.getActiveSkillName();
        recordLearningObservation(lastText);
        silent("Analizando " + compact(label, 32) + " durante los próximos minutos…");

        final String skillName = label;
        ResearchEngine.research(label, new ResearchEngine.Callback() {
            @Override public void onSuccess(String notes, JSONArray sources) {
                String observed = skills.getSkillNotes(skillName);
                String combined = observed + "\n\n[Investigación gratuita]\n" + notes;
                if (combined.length() > 7000) combined = combined.substring(combined.length() - 7000);
                skills.saveSkill(skillName, combined, sources);
                activeSkillState = skills.getActiveSkillName();
                silent("Aprendizaje activo: " + compact(skillName, 34) + ".");
            }

            @Override public void onError(String message) {
                // Local observation is still useful even when public sources have no article.
                activeSkillState = skills.getActiveSkillName();
                silent("Aprendiendo " + compact(skillName, 34) + " desde la pantalla.");
            }
        });
    }

    private void recordLearningObservation(String text) {
        if (learningPackage.isEmpty() || learningSkillName.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (now > learningUntil || learningObservationCount >= 20) {
            learningPackage = "";
            return;
        }
        String pkg = currentContentPackage();
        if (!learningPackage.equals(pkg)) return;
        if (now - lastLearningObservationAt < 3500) return;
        String snapshot = compact(text, 1000) + " | Controles/OCR: " + compact(visionSummary(), 500);
        String normalized = IntentAgent.normalize(snapshot);
        if (normalized.length() < 12 || normalized.equals(lastLearningSnapshot)) return;
        if (!lastLearningSnapshot.isEmpty()
                && (normalized.contains(lastLearningSnapshot) || lastLearningSnapshot.contains(normalized))) return;

        String existing = skills.getSkillNotes(learningSkillName);
        String addition = "\n\n[Observación " + (learningObservationCount + 1) + "]\n" + snapshot;
        String combined = existing + addition;
        if (combined.length() > 7000) combined = combined.substring(combined.length() - 7000);
        skills.saveSkill(learningSkillName, combined, new JSONArray());
        activeSkillState = skills.getActiveSkillName();
        lastLearningSnapshot = normalized;
        lastLearningObservationAt = now;
        learningObservationCount++;
    }

'''
service = replace_once(
    service,
    "    private void learn(String topic) {",
    learn_helpers + "    private void learn(String topic) {",
    "current app learning and close helpers",
)
service = replace_once(
    service,
    "        String pkg = AgentAccessibilityService.getActivePackageName();\n        String ctx = IntentAgent.normalize(pkg + \" \" + text);",
    "        String pkg = AgentAccessibilityService.getActivePackageName();\n        if (!transientPackage(pkg)) lastContentPackage = pkg;\n        String ctx = IntentAgent.normalize(pkg + \" \" + text);",
    "remember content package",
)
service = replace_once(
    service,
    "                    lastText = text;\n                    activateContext(text);\n                    passiveOverlay();",
    "                    lastText = text;\n                    activateContext(text);\n                    recordLearningObservation(text);\n                    passiveOverlay();",
    "ongoing screen learning",
)
service = service.replace("Screen Observer Pro 2.2", "Screen Observer Pro 2.4")
service = service.replace("Asistente de pantalla 2.2", "Asistente de pantalla 2.4")
service_path.write_text(service, encoding="utf-8")


# -----------------------------------------------------------------------------
# Main panel copy and deterministic regression tests.
# -----------------------------------------------------------------------------
activity_path = Path("app/src/main/java/com/erik/screenobserver/MainActivityV22.java")
activity = activity_path.read_text(encoding="utf-8")
activity = activity.replace('Screen Observer Pro 2.3', 'Screen Observer Pro 2.4')
activity = activity.replace(
    'IA local real para comprender lenguaje natural y mantener conversación. Incluye Android 15/16, Accesibilidad + OCR, habilidades y modo silencioso.',
    'IA local para voz o texto. La mini ventana ahora permite escribir instrucciones, además de usar Android 15/16, Accesibilidad + OCR y habilidades.'
)
activity = activity.replace(
    'Habla de forma natural. Ejemplos: “abre WhatsApp”, “entra a los ajustes y luego busca accesibilidad”, “¿qué hago ahora?”, “continúa con eso”, “pulsa Aceptar”, “aprende ajedrez”.',
    'Habla o escribe en la mini ventana. Ejemplos: “abre la pantalla principal”, “cierra WhatsApp”, “analiza este juego para que aprendas a usarlo”, “¿qué hago ahora?”, “pulsa Aceptar”.'
)
activity_path.write_text(activity, encoding="utf-8")

test_path = Path("local-tests/AgentUnderstandingTest.java")
test = test_path.read_text(encoding="utf-8")
test = replace_once(
    test,
    '        type("hay poca privacidad", IntentAgent.Type.GENERAL, "", "", "");',
    '        type("hay poca privacidad", IntentAgent.Type.GENERAL, "", "", "");\n'
    '        type("abre la pantalla principal del celular", IntentAgent.Type.HOME, "", "", "");\n'
    '        type("ve a la pantalla principal", IntentAgent.Type.HOME, "", "", "");\n'
    '        type("cierra WhatsApp", IntentAgent.Type.CLOSE_APP, "WhatsApp", "", "");\n'
    '        type("sal de WhatsApp", IntentAgent.Type.CLOSE_APP, "WhatsApp", "", "");\n'
    '        type("analiza este juego para que aprendas a usarlo", IntentAgent.Type.LEARN_CURRENT_APP, "", "", "");\n'
    '        type("aprende a usar este juego", IntentAgent.Type.LEARN_CURRENT_APP, "", "", "");\n'
    '        type("cierra la burbuja", IntentAgent.Type.HIDE_OVERLAY, "", "", "");',
    "v2.4 phrase regressions",
)
test_path.write_text(test, encoding="utf-8")

print("patch_v24: text overlay, stronger intents, close-app and current-game learning applied")
