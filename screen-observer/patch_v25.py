from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v25: expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# IntentAgent: broaden deterministic Android commands, typed recovery, and blackjack.
# -----------------------------------------------------------------------------
intent_path = Path("app/src/main/java/com/erik/screenobserver/IntentAgent.java")
intent = intent_path.read_text(encoding="utf-8")
intent = replace_once(
    intent,
    "        HEARING_CHECK, HIDE_OVERLAY, SHOW_OVERLAY, STOP_ASSISTANT, PAUSE_LISTENING,",
    "        HEARING_CHECK, HIDE_OVERLAY, SHOW_OVERLAY, STOP_ASSISTANT, PAUSE_LISTENING, RESUME_LISTENING,",
    "resume listening enum",
)
intent = replace_once(
    intent,
    "        CONFIRM_CLICK, CLICK, LONG_CLICK, TYPE_TEXT, SCROLL_DOWN, SCROLL_UP,",
    "        CONFIRM_CLICK, CLICK, CLICK_ORDINAL, LONG_CLICK, TYPE_TEXT, SEARCH, SCROLL_DOWN, SCROLL_UP,\n"
    "        SWIPE_LEFT, SWIPE_RIGHT, VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE, VOLUME_UNMUTE,",
    "expanded interaction enums",
)
intent = replace_once(
    intent,
    "        SCREENSHOT, OPEN_SETTINGS, OPEN_APP, CLOSE_APP, LEARN_CURRENT_APP,",
    "        SCREENSHOT, OPEN_SETTINGS, OPEN_SETTINGS_SECTION, OPEN_URL, OPEN_APP, CLOSE_APP, LEARN_CURRENT_APP,\n"
    "        BLACKJACK_ADVICE, BLACKJACK_PLAY,",
    "settings url blackjack enums",
)
intent = replace_once(
    intent,
    "    private static final Pattern OPEN_APP = Pattern.compile(\n"
    "            \"(?iu)^(?:abre|inicia|lanza|ejecuta)\\\\s+(?:la\\\\s+)?(?:app|aplicacion|aplicación)?\\\\s*(.+)$\");",
    "    private static final Pattern OPEN_APP = Pattern.compile(\n"
    "            \"(?iu)^(?:abre|inicia|lanza|ejecuta)\\\\s+(?:la\\\\s+)?(?:app|aplicacion|aplicación)?\\\\s*(.+)$\");\n"
    "    private static final Pattern SEARCH = Pattern.compile(\n"
    "            \"(?iu)^(?:busca|buscar|encuentra|localiza)\\\\s+(.+)$\");\n"
    "    private static final Pattern OPEN_URL = Pattern.compile(\n"
    "            \"(?iu)^(?:abre|visita|ve a|navega a)\\\\s+((?:https?://|www\\\\.)[^\\\\s]+|[a-z0-9.-]+\\\\.(?:com|org|net|io|mx|es)(?:/[^\\\\s]*)?)$\");",
    "search and url patterns",
)
intent = replace_once(
    intent,
    "            case PAUSE_LISTENING:\n"
    "            case CONFIRM_CLICK:",
    "            case PAUSE_LISTENING:\n"
    "            case RESUME_LISTENING:\n"
    "            case CONFIRM_CLICK:",
    "resume actionable",
)
intent = replace_once(
    intent,
    "            case CLICK:\n"
    "            case LONG_CLICK:\n"
    "            case TYPE_TEXT:\n"
    "            case SCROLL_DOWN:\n"
    "            case SCROLL_UP:",
    "            case CLICK:\n"
    "            case CLICK_ORDINAL:\n"
    "            case LONG_CLICK:\n"
    "            case TYPE_TEXT:\n"
    "            case SEARCH:\n"
    "            case SCROLL_DOWN:\n"
    "            case SCROLL_UP:\n"
    "            case SWIPE_LEFT:\n"
    "            case SWIPE_RIGHT:\n"
    "            case VOLUME_UP:\n"
    "            case VOLUME_DOWN:\n"
    "            case VOLUME_MUTE:\n"
    "            case VOLUME_UNMUTE:",
    "expanded actionables",
)
intent = replace_once(
    intent,
    "            case OPEN_SETTINGS:\n"
    "            case OPEN_APP:\n"
    "            case CLOSE_APP:\n"
    "            case LEARN_CURRENT_APP:",
    "            case OPEN_SETTINGS:\n"
    "            case OPEN_SETTINGS_SECTION:\n"
    "            case OPEN_URL:\n"
    "            case OPEN_APP:\n"
    "            case CLOSE_APP:\n"
    "            case LEARN_CURRENT_APP:\n"
    "            case BLACKJACK_PLAY:",
    "expanded system actionables",
)
intent = replace_once(
    intent,
    "        if (has(n, \"deja de escuchar\", \"no me escuches\", \"pausa la escucha\", \"pausa escucha\", \"desactiva el microfono\", \"apaga el microfono\"))\n"
    "            return r(Type.PAUSE_LISTENING, \"\", raw, .96);",
    "        if (has(n, \"deja de escuchar\", \"no me escuches\", \"pausa la escucha\", \"pausa escucha\", \"desactiva el microfono\", \"apaga el microfono\"))\n"
    "            return r(Type.PAUSE_LISTENING, \"\", raw, .96);\n"
    "        if (has(n, \"reanuda la escucha\", \"reanuda escucha\", \"vuelve a escuchar\", \"escuchame otra vez\",\n"
    "                \"activa el microfono\", \"enciende el microfono\", \"reactiva la escucha\"))\n"
    "            return r(Type.RESUME_LISTENING, \"\", raw, .97);",
    "resume listening phrases",
)
# Insert settings sections before generic Settings so "abre ajustes de wifi" does not collapse to OPEN_SETTINGS.
intent = replace_once(
    intent,
    "        if (has(n, \"abre ajustes\", \"abre configuracion\", \"abre la configuracion\", \"ve a ajustes\", \"ve a configuracion\"))\n"
    "            return r(Type.OPEN_SETTINGS, \"\", raw, .96);",
    "        if (has(n, \"ajustes de wifi\", \"configuracion de wifi\", \"abre wifi\", \"configura wifi\", \"redes wifi\"))\n"
    "            return r(Type.OPEN_SETTINGS_SECTION, \"wifi\", raw, .96);\n"
    "        if (has(n, \"ajustes de bluetooth\", \"configuracion de bluetooth\", \"abre bluetooth\", \"configura bluetooth\"))\n"
    "            return r(Type.OPEN_SETTINGS_SECTION, \"bluetooth\", raw, .96);\n"
    "        if (has(n, \"ajustes de sonido\", \"configuracion de sonido\", \"ajustes de audio\", \"configuracion de audio\"))\n"
    "            return r(Type.OPEN_SETTINGS_SECTION, \"sonido\", raw, .95);\n"
    "        if (has(n, \"ajustes de pantalla\", \"configuracion de pantalla\", \"ajustes de display\"))\n"
    "            return r(Type.OPEN_SETTINGS_SECTION, \"pantalla\", raw, .95);\n"
    "        if (has(n, \"ajustes de bateria\", \"configuracion de bateria\", \"ahorro de bateria\"))\n"
    "            return r(Type.OPEN_SETTINGS_SECTION, \"bateria\", raw, .95);\n"
    "        if (has(n, \"ajustes de ubicacion\", \"configuracion de ubicacion\", \"ajustes de localizacion\"))\n"
    "            return r(Type.OPEN_SETTINGS_SECTION, \"ubicacion\", raw, .95);\n"
    "        if (has(n, \"ajustes de aplicaciones\", \"configuracion de aplicaciones\", \"lista de aplicaciones\", \"administrar aplicaciones\"))\n"
    "            return r(Type.OPEN_SETTINGS_SECTION, \"aplicaciones\", raw, .95);\n"
    "        if (has(n, \"ajustes de notificaciones\", \"configuracion de notificaciones\"))\n"
    "            return r(Type.OPEN_SETTINGS_SECTION, \"notificaciones\", raw, .95);\n"
    "        if (has(n, \"ajustes de seguridad\", \"configuracion de seguridad\"))\n"
    "            return r(Type.OPEN_SETTINGS_SECTION, \"seguridad\", raw, .95);\n"
    "        if (has(n, \"ajustes de accesibilidad\", \"configuracion de accesibilidad\", \"abre accesibilidad\"))\n"
    "            return r(Type.OPEN_SETTINGS_SECTION, \"accesibilidad\", raw, .95);\n"
    "        if (has(n, \"abre ajustes\", \"abre configuracion\", \"abre la configuracion\", \"ve a ajustes\", \"ve a configuracion\"))\n"
    "            return r(Type.OPEN_SETTINGS, \"\", raw, .96);",
    "settings section phrases",
)
# More interaction primitives before generic click matching.
intent = replace_once(
    intent,
    "        m = CONFIRM.matcher(raw.trim());",
    "        if (has(n, \"sube el volumen\", \"aumenta el volumen\", \"mas volumen\", \"volumen arriba\"))\n"
    "            return r(Type.VOLUME_UP, \"\", raw, .96);\n"
    "        if (has(n, \"baja el volumen\", \"reduce el volumen\", \"menos volumen\", \"volumen abajo\"))\n"
    "            return r(Type.VOLUME_DOWN, \"\", raw, .96);\n"
    "        if (has(n, \"silencia el telefono\", \"silencia el celular\", \"ponlo en silencio\", \"quita el sonido\", \"mute\"))\n"
    "            return r(Type.VOLUME_MUTE, \"\", raw, .96);\n"
    "        if (has(n, \"activa el sonido\", \"quita el silencio\", \"devuelve el sonido\", \"unmute\"))\n"
    "            return r(Type.VOLUME_UNMUTE, \"\", raw, .96);\n"
    "        if (has(n, \"desliza a la izquierda\", \"desliza izquierda\", \"swipe left\", \"pasa a la izquierda\"))\n"
    "            return r(Type.SWIPE_LEFT, \"\", raw, .95);\n"
    "        if (has(n, \"desliza a la derecha\", \"desliza derecha\", \"swipe right\", \"pasa a la derecha\"))\n"
    "            return r(Type.SWIPE_RIGHT, \"\", raw, .95);\n"
    "        if (has(n, \"pulsa el primero\", \"toca el primero\", \"elige el primero\", \"pulsa la primera opcion\"))\n"
    "            return r(Type.CLICK_ORDINAL, \"1\", raw, .94);\n"
    "        if (has(n, \"pulsa el segundo\", \"toca el segundo\", \"elige el segundo\", \"pulsa la segunda opcion\"))\n"
    "            return r(Type.CLICK_ORDINAL, \"2\", raw, .94);\n"
    "        if (has(n, \"pulsa el tercero\", \"toca el tercero\", \"elige el tercero\", \"pulsa la tercera opcion\"))\n"
    "            return r(Type.CLICK_ORDINAL, \"3\", raw, .94);\n"
    "        if (has(n, \"pulsa el ultimo\", \"toca el ultimo\", \"elige el ultimo\", \"ultima opcion\"))\n"
    "            return r(Type.CLICK_ORDINAL, \"last\", raw, .94);\n\n"
    "        m = SEARCH.matcher(raw.trim());\n"
    "        if (m.find()) return r(Type.SEARCH, cleanup(m.group(1)), raw, .92);\n\n"
    "        m = OPEN_URL.matcher(raw.trim());\n"
    "        if (m.find()) return r(Type.OPEN_URL, cleanup(m.group(1)), raw, .95);\n\n"
    "        m = CONFIRM.matcher(raw.trim());",
    "volume swipe ordinal search url parsers",
)
# Blackjack-specific commands should win before generic advice/open-app logic.
intent = replace_once(
    intent,
    "        boolean gameContext = normalize(activeSkill).contains(\"ajedrez\")",
    "        boolean blackjackContext = BlackjackEngine.isBlackjackContext(raw + \" \" + activeSkill + \" \" + screenText);\n"
    "        if (has(n, \"juega blackjack\", \"juega black jack\", \"juega esta mano de blackjack\",\n"
    "                \"activa modo blackjack\", \"modo blackjack automatico\", \"juega esta mano\") && blackjackContext)\n"
    "            return r(Type.BLACKJACK_PLAY, raw, raw, .96);\n"
    "        if (blackjackContext && (has(n, \"que hago\", \"que jugada\", \"pido o me planto\", \"que conviene\",\n"
    "                \"aconsejame\", \"recomiendame\", \"blackjack\") || n.matches(\".*\\\\b\\\\d{1,2}\\\\s+(?:contra|vs)\\\\s+(?:a|as|ace|[2-9]|10|j|q|k)\\\\b.*\")))\n"
    "            return r(Type.BLACKJACK_ADVICE, raw, raw, .94);\n\n"
    "        boolean gameContext = normalize(activeSkill).contains(\"ajedrez\")",
    "blackjack intent routing",
)
intent_path.write_text(intent, encoding="utf-8")


# -----------------------------------------------------------------------------
# AndroidAppController: open common Settings sections and URLs without extra permissions.
# -----------------------------------------------------------------------------
controller_path = Path("app/src/main/java/com/erik/screenobserver/AndroidAppController.java")
controller = controller_path.read_text(encoding="utf-8")
controller = replace_once(
    controller,
    "    /**\n     * Finds a launcher app conservatively.",
    '''    public static boolean openSettingsSection(Context context, String section) {
        String s = normalize(section);
        String action;
        if (s.contains("wifi") || s.contains("wi fi")) action = Settings.ACTION_WIFI_SETTINGS;
        else if (s.contains("bluetooth")) action = Settings.ACTION_BLUETOOTH_SETTINGS;
        else if (s.contains("sonido") || s.contains("audio") || s.contains("volumen")) action = Settings.ACTION_SOUND_SETTINGS;
        else if (s.contains("pantalla") || s.contains("display")) action = Settings.ACTION_DISPLAY_SETTINGS;
        else if (s.contains("bateria")) action = Settings.ACTION_BATTERY_SAVER_SETTINGS;
        else if (s.contains("ubicacion") || s.contains("localizacion")) action = Settings.ACTION_LOCATION_SOURCE_SETTINGS;
        else if (s.contains("aplicacion") || s.contains("apps")) action = Settings.ACTION_APPLICATION_SETTINGS;
        else if (s.contains("notificacion")) action = Settings.ACTION_NOTIFICATION_SETTINGS;
        else if (s.contains("seguridad")) action = Settings.ACTION_SECURITY_SETTINGS;
        else if (s.contains("accesibilidad")) action = Settings.ACTION_ACCESSIBILITY_SETTINGS;
        else action = Settings.ACTION_SETTINGS;
        try {
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean openUrl(Context context, String value) {
        if (context == null || value == null || value.trim().isEmpty()) return false;
        String url = value.trim();
        if (!url.matches("(?iu)^[a-z][a-z0-9+.-]*://.*")) url = "https://" + url;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Finds a launcher app conservatively.''',
    "settings sections and URL helpers",
)
controller_path.write_text(controller, encoding="utf-8")


# -----------------------------------------------------------------------------
# Accessibility: horizontal gestures and ordinal control selection.
# -----------------------------------------------------------------------------
a11y_path = Path("app/src/main/java/com/erik/screenobserver/AgentAccessibilityService.java")
a11y = a11y_path.read_text(encoding="utf-8")
a11y = replace_once(
    a11y,
    "    public boolean back() { return performGlobalAction(GLOBAL_ACTION_BACK); }",
    '''    public boolean swipeLeft() { return swipeDirection(true); }
    public boolean swipeRight() { return swipeDirection(false); }

    private boolean swipeDirection(boolean left) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float y = dm.heightPixels * 0.52f;
            float fromX = dm.widthPixels * (left ? 0.78f : 0.22f);
            float toX = dm.widthPixels * (left ? 0.22f : 0.78f);
            Path path = new Path();
            path.moveTo(fromX, y);
            path.lineTo(toX, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 280);
            return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean clickOrdinal(int oneBased, boolean fromEnd) {
        if (oneBased < 1) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        try {
            collectClickableNodes(root, nodes, 40);
            if (nodes.isEmpty()) return false;
            int index = fromEnd ? nodes.size() - oneBased : oneBased - 1;
            if (index < 0 || index >= nodes.size()) return false;
            return clickNodeOrParent(nodes.get(index));
        } finally {
            for (AccessibilityNodeInfo n : nodes) try { n.recycle(); } catch (Exception ignored) { }
            root.recycle();
        }
    }

    private void collectClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int limit) {
        if (node == null || out.size() >= limit) return;
        if (node.isEnabled() && node.isClickable()) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount() && out.size() < limit; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collectClickableNodes(child, out, limit);
            child.recycle();
        }
    }

    public boolean back() { return performGlobalAction(GLOBAL_ACTION_BACK); }''',
    "swipe and ordinal accessibility helpers",
)
a11y_path.write_text(a11y, encoding="utf-8")


# -----------------------------------------------------------------------------
# Android skill aliases: common navigation and blackjack controls.
# -----------------------------------------------------------------------------
skill_path = Path("app/src/main/java/com/erik/screenobserver/AndroidSkillPack.java")
skill = skill_path.read_text(encoding="utf-8")
skill = replace_once(
    skill,
    '        put("atras", "Atrás", "Back", "Regresar", "Volver");',
    '        put("atras", "Atrás", "Back", "Regresar", "Volver");\n'
    '        put("actualizar", "Actualizar", "Refresh", "Recargar", "Reload");\n'
    '        put("pedir", "Pedir", "Hit", "Carta", "Otra carta");\n'
    '        put("plantarse", "Plantarse", "Stand", "Me planto", "Quedarse");\n'
    '        put("doblar", "Doblar", "Double", "Double down", "Doble");\n'
    '        put("separar", "Separar", "Split", "Dividir");',
    "extra control aliases",
)
skill_path.write_text(skill, encoding="utf-8")


# -----------------------------------------------------------------------------
# LocalLanguageAgent: clearer typed/voice contract and richer action semantics.
# -----------------------------------------------------------------------------
llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")
llm = replace_once(
    llm,
    "            Eres el cerebro conversacional de un asistente Android privado que funciona en el teléfono del usuario.",
    "            Eres el cerebro conversacional de un asistente Android privado que funciona en el teléfono del usuario. Recibes voz transcrita o instrucciones escritas.",
    "typed and voice system prompt",
)
llm = replace_once(
    llm,
    '''            Casos importantes: ir, volver o abrir la pantalla principal del celular = HOME.
            "cierra WhatsApp", "sal de WhatsApp" o "cierra esta app" = CLOSE_APP, con el nombre de la app en argument cuando exista.
            "analiza este juego para aprender a usarlo", "aprende a usar este juego" o equivalentes = LEARN_CURRENT_APP.
            Para conversación o preguntas generales usa GENERAL y responde en reply.''',
    '''            Casos importantes: ir, volver o abrir la pantalla principal del celular = HOME.
            "cierra WhatsApp", "sal de WhatsApp" o "cierra esta app" = CLOSE_APP, con el nombre de la app en argument cuando exista.
            "analiza este juego para aprender a usarlo", "aprende a usar este juego" o equivalentes = LEARN_CURRENT_APP.
            Buscar dentro de la app = SEARCH con la consulta en argument. Abrir Wi-Fi/Bluetooth/sonido/pantalla/batería/ubicación = OPEN_SETTINGS_SECTION.
            Deslizar horizontalmente = SWIPE_LEFT o SWIPE_RIGHT. Cambiar audio = VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE o VOLUME_UNMUTE.
            "reanuda la escucha" = RESUME_LISTENING. Una URL o dominio pedido explícitamente = OPEN_URL.
            Blackjack: pedir consejo = BLACKJACK_ADVICE; jugar una mano de práctica = BLACKJACK_PLAY. Nunca inventes cartas que no estén en la entrada o la pantalla.
            Para conversación o preguntas generales usa GENERAL y responde en reply.''',
    "expanded LLM action examples",
)
llm = replace_once(
    llm,
    "                    samplerConfig = SamplerConfig(topK = 20, topP = 0.92, temperature = 0.25),",
    "                    samplerConfig = SamplerConfig(topK = 20, topP = 0.90, temperature = 0.15),",
    "lower action classification temperature",
)
llm = replace_once(
    llm,
    '            append("Voz: ").append(hypothesisText)',
    '            append("Entrada del usuario: ").append(hypothesisText)',
    "neutral input channel label",
)
llm_path.write_text(llm, encoding="utf-8")


# -----------------------------------------------------------------------------
# ScreenAgentService22: text remains active while microphone is paused, deterministic
# plan-first routing, multi-step commands, more Android skills, and blackjack practice.
# -----------------------------------------------------------------------------
service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")

old_text_handler = '''    private void handleTextCommand(String command) {
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
new_text_handler = '''    private void handleTextCommand(String command) {
        final String typed = command == null ? "" : command.trim();
        if (typed.isEmpty()) {
            silent("Escribe una instrucción antes de enviarla.");
            return;
        }

        // Text is an independent control channel. Pausing microphone listening must never
        // disable typed commands. We only cancel an in-flight recognizer if one is active.
        if (listening) cancelListening();
        voiceStatus = listeningEnabled ? "⌨ procesando texto" : "⌨ texto activo · micrófono pausado";
        passiveOverlay();

        final String context = currentUnderstandingContext();
        final List<IntentAgent.Result> plan = PhoneCommandPlanner.plan(typed, activeSkillState, context);
        if (plan.size() > 1) {
            if (PhoneCommandPlanner.containsSensitive(typed)) {
                speak("Por seguridad, envía las acciones sensibles una por una para que pueda confirmarlas.");
                finishTypedCommand();
                return;
            }
            executeCommandPlan(plan, 0);
            return;
        }
        if (plan.size() == 1 && plan.get(0).type != IntentAgent.Type.GENERAL
                && plan.get(0).confidence >= 0.50) {
            dispatch(plan.get(0));
            finishTypedCommand();
            return;
        }

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
    }

    private String currentUnderstandingContext() {
        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        String controls = access == null ? "" : access.listInteractiveElements();
        String pkg = currentContentPackage();
        String label = appLabel(pkg);
        return "App: " + compact(label, 80)
                + " | Paquete: " + compact(pkg, 100)
                + " | Pantalla OCR: " + compact(lastText, 1500)
                + " | " + compact(controls, 1000);
    }

    private void finishTypedCommand() {
        if (speaking || ttsPendingStart) return;
        if (listeningEnabled) {
            cuePending = true;
            voiceStatus = "preparando escucha";
            passiveOverlay();
            startListening(120);
        } else {
            listeningState = false;
            voiceStatus = "escucha pausada · texto activo";
            passiveOverlay();
        }
    }

    private void executeCommandPlan(List<IntentAgent.Result> plan, int index) {
        if (plan == null || index >= plan.size()) {
            finishTypedCommand();
            return;
        }
        IntentAgent.Result step = plan.get(index);
        if (step == null || step.type == IntentAgent.Type.GENERAL) {
            speak("Entendí parte de la secuencia, pero una acción quedó ambigua. Escríbela por separado.");
            finishTypedCommand();
            return;
        }
        dispatch(step);
        if (!runningState || step.type == IntentAgent.Type.STOP_ASSISTANT) return;
        long delay = PhoneCommandPlanner.recommendedDelayMs(step.type);
        main.postDelayed(() -> executeCommandPlan(plan, index + 1), delay);
    }

'''
service = replace_once(service, old_text_handler, new_text_handler, "independent typed command handler")

# Resume listening dispatch.
service = replace_once(
    service,
    '''            case PAUSE_LISTENING:
                listeningEnabled = false;
                listeningState = false;
                cancelListening();
                silent("Escucha pausada.");
                break;''',
    '''            case PAUSE_LISTENING:
                listeningEnabled = false;
                listeningState = false;
                cancelListening();
                silent("Escucha pausada. La entrada escrita sigue activa.");
                break;
            case RESUME_LISTENING:
                listeningEnabled = true;
                listeningState = true;
                if (recognizer == null) createRecognizer();
                voiceStatus = "preparando escucha";
                startListening(120);
                silent("Escucha reanudada.");
                break;''',
    "pause resume dispatch",
)
# Interaction cases before long click.
service = replace_once(
    service,
    "            case LONG_CLICK:\n                longClick(r.argument, false);\n                break;",
    '''            case CLICK_ORDINAL: {
                AgentAccessibilityService ord = AgentAccessibilityService.getInstance();
                boolean last = "last".equalsIgnoreCase(r.argument);
                int ordinal = last ? 1 : parsePositiveInt(r.argument, 1);
                silent(ord != null && ord.clickOrdinal(ordinal, last)
                        ? "Control seleccionado." : "No pude seleccionar ese control por posición.");
                break;
            }
            case LONG_CLICK:
                longClick(r.argument, false);
                break;''',
    "ordinal click dispatch",
)
service = replace_once(
    service,
    "            case SCROLL_DOWN:\n                scroll(true);\n                break;",
    '''            case SEARCH:
                searchCurrentApp(r.argument);
                break;
            case SCROLL_DOWN:
                scroll(true);
                break;''',
    "search dispatch",
)
service = replace_once(
    service,
    "            case BACK:\n                a = AgentAccessibilityService.getInstance();",
    '''            case SWIPE_LEFT:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.swipeLeft() ? "Deslicé a la izquierda." : "No pude hacer ese gesto.");
                break;
            case SWIPE_RIGHT:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.swipeRight() ? "Deslicé a la derecha." : "No pude hacer ese gesto.");
                break;
            case VOLUME_UP:
                adjustVolume(android.media.AudioManager.ADJUST_RAISE);
                break;
            case VOLUME_DOWN:
                adjustVolume(android.media.AudioManager.ADJUST_LOWER);
                break;
            case VOLUME_MUTE:
                adjustVolume(android.media.AudioManager.ADJUST_MUTE);
                break;
            case VOLUME_UNMUTE:
                adjustVolume(android.media.AudioManager.ADJUST_UNMUTE);
                break;
            case BACK:
                a = AgentAccessibilityService.getInstance();''',
    "swipe and volume dispatch",
)
service = replace_once(
    service,
    "            case OPEN_APP:\n                silent(AndroidAppController.launchAppByLabel(this, r.argument)",
    '''            case OPEN_SETTINGS_SECTION:
                silent(AndroidAppController.openSettingsSection(this, r.argument)
                        ? "Abrí ajustes de " + r.argument + "." : "No pude abrir esa sección de Ajustes.");
                break;
            case OPEN_URL:
                silent(AndroidAppController.openUrl(this, r.argument)
                        ? "Abrí " + r.argument + "." : "No pude abrir esa dirección.");
                break;
            case BLACKJACK_ADVICE:
                blackjackAdvice(r.raw);
                break;
            case BLACKJACK_PLAY:
                blackjackPlay(r.raw);
                break;
            case OPEN_APP:
                silent(AndroidAppController.launchAppByLabel(this, r.argument)''',
    "settings url blackjack dispatch",
)
# New helpers before current-app learning helpers.
service = replace_once(
    service,
    "    private boolean transientPackage(String pkg) {",
    '''    private int parsePositiveInt(String value, int fallback) {
        try {
            int n = Integer.parseInt(value == null ? "" : value.trim());
            return n > 0 ? n : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private void adjustVolume(int direction) {
        try {
            android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio == null) {
                silent("No pude acceder al audio.");
                return;
            }
            audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction,
                    android.media.AudioManager.FLAG_SHOW_UI);
            silent("Volumen ajustado.");
        } catch (Exception e) {
            silent("No pude cambiar el volumen.");
        }
    }

    private void searchCurrentApp(String query) {
        if (query == null || query.trim().isEmpty()) {
            speak("Dime qué quieres buscar.");
            return;
        }
        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        if (access == null) {
            speak("Necesito Control de pantalla activo para buscar dentro de la aplicación.");
            return;
        }
        boolean opened = false;
        for (String alias : AndroidSkillPack.aliasesForTarget("buscar")) {
            if (access.clickText(alias)) { opened = true; break; }
        }
        final String q = query.trim();
        main.postDelayed(() -> {
            AgentAccessibilityService a = AgentAccessibilityService.getInstance();
            if (a != null && a.setFocusedText(q)) {
                silent("Búsqueda escrita: " + compact(q, 45) + ".");
            } else if (!opened) {
                speak("No encontré un campo de búsqueda accesible en esta pantalla.");
            } else {
                speak("Abrí la búsqueda, pero no pude escribir en el campo.");
            }
        }, opened ? 420 : 80);
    }

    private void blackjackAdvice(String request) {
        BlackjackEngine.Recommendation rec = BlackjackEngine.recommendFromText(
                (request == null ? "" : request) + " " + lastText);
        if (!rec.known()) {
            speak("No pude leer con seguridad la mano. Escríbeme algo como: tengo 16 contra 10, o deja visibles el total del jugador y la carta del dealer.");
            return;
        }
        speak("Blackjack: " + rec.actionLabelEs() + ". " + rec.reason
                + " Asumo estrategia básica de varias barajas, dealer se planta en 17 suave y doble después de separar.");
    }

    private void blackjackPlay(String request) {
        String context = (request == null ? "" : request) + " " + lastText + " " + currentUnderstandingContext();
        BlackjackEngine.Recommendation rec = BlackjackEngine.recommendFromText(context);
        if (!rec.known()) {
            speak("No puedo jugar esta mano porque no distingo con seguridad tus cartas y la carta del dealer. Puedo aprender la interfaz o puedes escribir la mano.");
            return;
        }
        if (BlackjackEngine.isRealMoneyContext(context)) {
            speak("Detecto un contexto de dinero real. Puedo decirte la jugada de estrategia básica, pero no voy a ejecutar decisiones de apuestas automáticamente. Recomiendo "
                    + rec.actionLabelEs() + ".");
            return;
        }
        if (!BlackjackEngine.isPracticeContext(context)) {
            speak("Puedo recomendar " + rec.actionLabelEs()
                    + ", pero solo ejecuto blackjack automáticamente cuando la pantalla indica práctica, demo o juego gratis.");
            return;
        }
        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        if (access == null) {
            speak("Necesito Control de pantalla activo para jugar la mano de práctica.");
            return;
        }
        for (String label : BlackjackEngine.labelsFor(rec.decision)) {
            if (access.clickText(label)) {
                silent("Blackjack práctica: " + rec.actionLabelEs() + ".");
                return;
            }
        }
        speak("La recomendación es " + rec.actionLabelEs()
                + ", pero no encontré ese botón como control accesible.");
    }

    private boolean transientPackage(String pkg) {''',
    "volume search blackjack helpers",
)
# Overlay status should make the independent text channel visible when mic is paused.
service = replace_once(
    service,
    '        String s = listeningEnabled ? "🎙 " + compact(voiceStatus, 38) : "🎙 Escucha pausada";',
    '        String s = listeningEnabled ? "🎙 " + compact(voiceStatus, 38) : "🎙 Escucha pausada · ⌨ texto activo";',
    "paused text-active overlay status",
)
service_path.write_text(service, encoding="utf-8")

print("patch_v25: independent text commands, expanded phone skills and blackjack applied")
