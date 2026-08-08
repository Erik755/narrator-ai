from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v25_review_fixes: expected one {label}, found {count}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# IntentAgent: only deterministic imperative rules may directly actuate the phone.
# Ordinary statements containing words such as "ajustes de wifi" or "sube el volumen"
# must fall through to GENERAL / the language model.
# -----------------------------------------------------------------------------
intent_path = Path("app/src/main/java/com/erik/screenobserver/IntentAgent.java")
intent = intent_path.read_text(encoding="utf-8")
intent = replace_once(
    intent,
    '''    private static boolean has(String normalized, String... options) {
        for (String option : options) if (normalized.contains(normalize(option))) return true;
        return false;
    }
''',
    '''    private static boolean has(String normalized, String... options) {
        for (String option : options) if (normalized.contains(normalize(option))) return true;
        return false;
    }

    private static boolean startsCommand(String normalized, String... options) {
        String value = normalize(normalized);
        for (String option : options) {
            String q = normalize(option);
            if (value.equals(q) || value.startsWith(q + " ")) return true;
        }
        return false;
    }
''',
    "command-prefix helper",
)

prefix_rules = [
    ('if (has(n, "reanuda la escucha", "reanuda escucha", "vuelve a escuchar", "escuchame otra vez",',
     'if (startsCommand(n, "reanuda la escucha", "reanuda escucha", "vuelve a escuchar", "escuchame otra vez",'),
    ('if (has(n, "ajustes de wifi", "configuracion de wifi", "abre wifi", "configura wifi", "redes wifi"))',
     'if (startsCommand(n, "ajustes de wifi", "configuracion de wifi", "abre wifi", "configura wifi", "redes wifi"))'),
    ('if (has(n, "ajustes de bluetooth", "configuracion de bluetooth", "abre bluetooth", "configura bluetooth"))',
     'if (startsCommand(n, "ajustes de bluetooth", "configuracion de bluetooth", "abre bluetooth", "configura bluetooth"))'),
    ('if (has(n, "ajustes de sonido", "configuracion de sonido", "ajustes de audio", "configuracion de audio"))',
     'if (startsCommand(n, "ajustes de sonido", "configuracion de sonido", "ajustes de audio", "configuracion de audio"))'),
    ('if (has(n, "ajustes de pantalla", "configuracion de pantalla", "ajustes de display"))',
     'if (startsCommand(n, "ajustes de pantalla", "configuracion de pantalla", "ajustes de display"))'),
    ('if (has(n, "ajustes de bateria", "configuracion de bateria", "ahorro de bateria"))',
     'if (startsCommand(n, "ajustes de bateria", "configuracion de bateria", "ahorro de bateria"))'),
    ('if (has(n, "ajustes de ubicacion", "configuracion de ubicacion", "ajustes de localizacion"))',
     'if (startsCommand(n, "ajustes de ubicacion", "configuracion de ubicacion", "ajustes de localizacion"))'),
    ('if (has(n, "ajustes de aplicaciones", "configuracion de aplicaciones", "lista de aplicaciones", "administrar aplicaciones"))',
     'if (startsCommand(n, "ajustes de aplicaciones", "configuracion de aplicaciones", "lista de aplicaciones", "administrar aplicaciones"))'),
    ('if (has(n, "ajustes de notificaciones", "configuracion de notificaciones"))',
     'if (startsCommand(n, "ajustes de notificaciones", "configuracion de notificaciones"))'),
    ('if (has(n, "ajustes de seguridad", "configuracion de seguridad"))',
     'if (startsCommand(n, "ajustes de seguridad", "configuracion de seguridad"))'),
    ('if (has(n, "ajustes de accesibilidad", "configuracion de accesibilidad", "abre accesibilidad"))',
     'if (startsCommand(n, "ajustes de accesibilidad", "configuracion de accesibilidad", "abre accesibilidad"))'),
    ('if (has(n, "abre ajustes", "abre configuracion", "abre la configuracion", "ve a ajustes", "ve a configuracion"))',
     'if (startsCommand(n, "abre ajustes", "abre configuracion", "abre la configuracion", "ve a ajustes", "ve a configuracion"))'),
    ('if (has(n, "sube el volumen", "aumenta el volumen", "mas volumen", "volumen arriba"))',
     'if (startsCommand(n, "sube el volumen", "aumenta el volumen", "mas volumen", "volumen arriba"))'),
    ('if (has(n, "baja el volumen", "reduce el volumen", "menos volumen", "volumen abajo"))',
     'if (startsCommand(n, "baja el volumen", "reduce el volumen", "menos volumen", "volumen abajo"))'),
    ('if (has(n, "silencia el telefono", "silencia el celular", "ponlo en silencio", "quita el sonido", "mute"))',
     'if (startsCommand(n, "silencia el telefono", "silencia el celular", "ponlo en silencio", "quita el sonido", "mute"))'),
    ('if (has(n, "activa el sonido", "quita el silencio", "devuelve el sonido", "unmute"))',
     'if (startsCommand(n, "activa el sonido", "quita el silencio", "devuelve el sonido", "unmute"))'),
    ('if (has(n, "desliza a la izquierda", "desliza izquierda", "swipe left", "pasa a la izquierda"))',
     'if (startsCommand(n, "desliza a la izquierda", "desliza izquierda", "swipe left", "pasa a la izquierda"))'),
    ('if (has(n, "desliza a la derecha", "desliza derecha", "swipe right", "pasa a la derecha"))',
     'if (startsCommand(n, "desliza a la derecha", "desliza derecha", "swipe right", "pasa a la derecha"))'),
    ('if (has(n, "pulsa el primero", "toca el primero", "elige el primero", "pulsa la primera opcion"))',
     'if (startsCommand(n, "pulsa el primero", "toca el primero", "elige el primero", "pulsa la primera opcion"))'),
    ('if (has(n, "pulsa el segundo", "toca el segundo", "elige el segundo", "pulsa la segunda opcion"))',
     'if (startsCommand(n, "pulsa el segundo", "toca el segundo", "elige el segundo", "pulsa la segunda opcion"))'),
    ('if (has(n, "pulsa el tercero", "toca el tercero", "elige el tercero", "pulsa la tercera opcion"))',
     'if (startsCommand(n, "pulsa el tercero", "toca el tercero", "elige el tercero", "pulsa la tercera opcion"))'),
    ('if (has(n, "pulsa el ultimo", "toca el ultimo", "elige el ultimo", "ultima opcion"))',
     'if (startsCommand(n, "pulsa el ultimo", "toca el ultimo", "elige el ultimo", "ultima opcion"))'),
    ('if (has(n, "juega blackjack", "juega black jack", "juega esta mano de blackjack",',
     'if (startsCommand(n, "juega blackjack", "juega black jack", "juega esta mano de blackjack",'),
]
for idx, (old, new) in enumerate(prefix_rules):
    intent = replace_once(intent, old, new, f"imperative prefix rule {idx + 1}")
intent_path.write_text(intent, encoding="utf-8")


# -----------------------------------------------------------------------------
# Screen service: conversational semicolon text goes to the LLM; sensitive ordinal
# clicks are blocked; searches only type after a real search control opens; and
# multi-step OPEN_APP waits for the new app before continuing.
# -----------------------------------------------------------------------------
service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")

service = replace_once(
    service,
    '''        final List<IntentAgent.Result> plan = PhoneCommandPlanner.plan(typed, activeSkillState, context);
        if (plan.size() > 1) {
            if (PhoneCommandPlanner.containsSensitive(typed)) {''',
    '''        final List<IntentAgent.Result> plan = PhoneCommandPlanner.plan(typed, activeSkillState, context);
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
            if (PhoneCommandPlanner.containsSensitive(typed)) {''',
    "multi-segment command gating",
)

service = replace_once(
    service,
    '''    private void executeCommandPlan(List<IntentAgent.Result> plan, int index) {
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
''',
    '''    private void executeCommandPlan(List<IntentAgent.Result> plan, int index) {
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
        if (step.type == IntentAgent.Type.OPEN_APP) {
            executePlanOpenApp(plan, index, step);
            return;
        }
        dispatch(step);
        if (!runningState || step.type == IntentAgent.Type.STOP_ASSISTANT) return;
        if (!pendingSensitive.isEmpty() && SystemClock.elapsedRealtime() < pendingSensitiveUntil) {
            speak("La secuencia se detuvo porque una acción requiere confirmación.");
            finishTypedCommand();
            return;
        }
        long delay = PhoneCommandPlanner.recommendedDelayMs(step.type);
        main.postDelayed(() -> executeCommandPlan(plan, index + 1), delay);
    }

    private void executePlanOpenApp(List<IntentAgent.Result> plan, int index, IntentAgent.Result step) {
        final AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        final String requested = step.argument == null ? "" : step.argument.trim();
        final String beforePackage = currentContentPackage();
        if (access == null || requested.isEmpty()) {
            speak("No puedo abrir esa aplicación de forma fiable sin Control de pantalla activo.");
            finishTypedCommand();
            return;
        }
        actionExecutor.execute(() -> {
            final boolean opened = AndroidAppController.launchAppByLabel(access, requested);
            main.post(() -> {
                if (!opened) {
                    speak("No encontré la aplicación " + requested + ". La secuencia se detuvo.");
                    finishTypedCommand();
                    return;
                }
                waitForPlanAppReady(plan, index, requested, beforePackage, 0);
            });
        });
    }

    private void waitForPlanAppReady(List<IntentAgent.Result> plan, int index, String requested,
                                     String beforePackage, int attempt) {
        if (!runningState) return;
        String current = currentContentPackage();
        String currentLabel = IntentAgent.normalize(appLabel(current));
        String wanted = IntentAgent.normalize(requested);
        boolean labelMatches = !currentLabel.isEmpty() && !wanted.isEmpty()
                && (currentLabel.contains(wanted) || wanted.contains(currentLabel));
        boolean packageChanged = current != null && !current.isEmpty()
                && beforePackage != null && !current.equals(beforePackage);
        if (labelMatches || packageChanged) {
            main.postDelayed(() -> executeCommandPlan(plan, index + 1), 180);
            return;
        }
        if (attempt >= 16) {
            speak("Abrí la aplicación, pero no pude confirmar que estuviera lista. Detuve la secuencia para no actuar en la pantalla equivocada.");
            finishTypedCommand();
            return;
        }
        main.postDelayed(() -> waitForPlanAppReady(plan, index, requested, beforePackage, attempt + 1), 250);
    }
''',
    "state-aware command sequencing",
)

service = replace_once(
    service,
    '''            case CLICK_ORDINAL: {
                AgentAccessibilityService ord = AgentAccessibilityService.getInstance();
                boolean last = "last".equalsIgnoreCase(r.argument);
                int ordinal = last ? 1 : parsePositiveInt(r.argument, 1);
                silent(ord != null && ord.clickOrdinal(ordinal, last)
                        ? "Control seleccionado." : "No pude seleccionar ese control por posición.");
                break;
            }''',
    '''            case CLICK_ORDINAL: {
                if (sensitiveScreenContext()) {
                    speak("Por seguridad, en esta pantalla usa el nombre exacto del botón y confirma la acción; no seleccionaré por posición.");
                    break;
                }
                AgentAccessibilityService ord = AgentAccessibilityService.getInstance();
                boolean last = "last".equalsIgnoreCase(r.argument);
                int ordinal = last ? 1 : parsePositiveInt(r.argument, 1);
                silent(ord != null && ord.clickOrdinal(ordinal, last)
                        ? "Control seleccionado." : "No pude seleccionar ese control por posición.");
                break;
            }''',
    "sensitive ordinal guard",
)

service = replace_once(
    service,
    '''        final boolean searchOpened = opened;
        final String q = query.trim();
        main.postDelayed(() -> {
            AgentAccessibilityService a = AgentAccessibilityService.getInstance();
            if (a != null && a.setFocusedText(q)) {
                silent("Búsqueda escrita: " + compact(q, 45) + ".");
            } else if (!searchOpened) {
                speak("No encontré un campo de búsqueda accesible en esta pantalla.");
            } else {
                speak("Abrí la búsqueda, pero no pude escribir en el campo.");
            }
        }, searchOpened ? 420 : 80);''',
    '''        final boolean searchOpened = opened;
        final String q = query.trim();
        if (!searchOpened) {
            speak("No encontré un control de búsqueda accesible en esta pantalla; no escribiré en otro campo.");
            return;
        }
        main.postDelayed(() -> {
            AgentAccessibilityService a = AgentAccessibilityService.getInstance();
            if (a != null && a.setFocusedText(q)) {
                silent("Búsqueda escrita: " + compact(q, 45) + ".");
            } else {
                speak("Abrí la búsqueda, pero no pude escribir en el campo.");
            }
        }, 420);''',
    "safe search field handling",
)

service = replace_once(
    service,
    "    private int parsePositiveInt(String value, int fallback) {",
    '''    private boolean sensitiveScreenContext() {
        String screen = IntentAgent.normalize(lastText);
        return has(screen,
                "desinstalar", "uninstall", "factory reset", "restablecer de fabrica",
                "borrar todos los datos", "eliminar todos los datos", "erase all data",
                "eliminar cuenta", "delete account", "remove account", "formatear",
                "pagar", "comprar", "transferir", "enviar dinero", "depositar", "retirar");
    }

    private int parsePositiveInt(String value, int fallback) {''',
    "sensitive screen helper",
)

# Generated user-facing branding must agree with the APK's 2.5.0 version.
for runtime_path in [
    Path("app/src/main/java/com/erik/screenobserver/MainActivity.java"),
    Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java"),
    Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt"),
]:
    if not runtime_path.exists():
        continue
    value = runtime_path.read_text(encoding="utf-8")
    value = value.replace("Screen Observer Pro 2.4", "Screen Observer Pro 2.5")
    value = value.replace("ScreenObserverPro/2.4", "ScreenObserverPro/2.5")
    runtime_path.write_text(value, encoding="utf-8")

service_path.write_text(service, encoding="utf-8")
print("patch_v25_review_fixes: command intent, sequencing, search, ordinal safety and branding fixed")
