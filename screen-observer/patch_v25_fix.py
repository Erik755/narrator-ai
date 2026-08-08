from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v25_fix: expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


# Keep every new device action behind the same model-side speech confidence gate.
llm_path = Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt")
llm = llm_path.read_text(encoding="utf-8")
llm = replace_once(
    llm,
    '''        IntentAgent.Type.OPEN_SETTINGS,
        IntentAgent.Type.OPEN_APP,
        IntentAgent.Type.CLOSE_APP,
        IntentAgent.Type.LEARN_CURRENT_APP -> true''',
    '''        IntentAgent.Type.OPEN_SETTINGS,
        IntentAgent.Type.OPEN_SETTINGS_SECTION,
        IntentAgent.Type.OPEN_URL,
        IntentAgent.Type.OPEN_APP,
        IntentAgent.Type.CLOSE_APP,
        IntentAgent.Type.LEARN_CURRENT_APP,
        IntentAgent.Type.RESUME_LISTENING,
        IntentAgent.Type.CLICK_ORDINAL,
        IntentAgent.Type.SEARCH,
        IntentAgent.Type.SWIPE_LEFT,
        IntentAgent.Type.SWIPE_RIGHT,
        IntentAgent.Type.VOLUME_UP,
        IntentAgent.Type.VOLUME_DOWN,
        IntentAgent.Type.VOLUME_MUTE,
        IntentAgent.Type.VOLUME_UNMUTE,
        IntentAgent.Type.BLACKJACK_PLAY -> true''',
    "model actionable v2.5 intents",
)
llm_path.write_text(llm, encoding="utf-8")


service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")

# Java lambdas require captured locals to be effectively final. Preserve the search-open result.
service = replace_once(
    service,
    '''        boolean opened = false;
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
        }, opened ? 420 : 80);''',
    '''        boolean opened = false;
        for (String alias : AndroidSkillPack.aliasesForTarget("buscar")) {
            if (access.clickText(alias)) { opened = true; break; }
        }
        final boolean searchOpened = opened;
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
    "effectively-final search state",
)

# Typed execution is independent from microphone state by construction. Add an explicit helper
# used by CI to make that invariant visible and difficult to regress accidentally.
service = replace_once(
    service,
    "    private void handleTextCommand(String command) {",
    '''    private boolean typedCommandsAvailable() {
        return runningState;
    }

    private void handleTextCommand(String command) {
        if (!typedCommandsAvailable()) return;''',
    "typed command availability invariant",
)

service_path.write_text(service, encoding="utf-8")
print("patch_v25_fix: model safety and typed-channel runtime fixes applied")
