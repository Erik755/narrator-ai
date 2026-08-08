from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_v24_fix: expected exactly one {label}, found {count}")
    return text.replace(old, new, 1)


service_path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
service = service_path.read_text(encoding="utf-8")

service = replace_once(
    service,
    '''        String initial = "[Análisis local de " + label + "]
"
                + "Paquete: " + pkg + "
"
                + "Pantalla inicial: " + compact(lastText, 1100) + "
"
                + "Controles iniciales: " + compact(controls, 900);''',
    '''        String initial = "[Análisis local de " + label + "]\\n"
                + "Paquete: " + pkg + "\\n"
                + "Pantalla inicial: " + compact(lastText, 1100) + "\\n"
                + "Controles iniciales: " + compact(controls, 900);''',
    "initial learning note newline escaping",
)

service = replace_once(
    service,
    '''                String combined = observed + "

[Investigación gratuita]
" + notes;''',
    '''                String combined = observed + "\\n\\n[Investigación gratuita]\\n" + notes;''',
    "research note newline escaping",
)

service = replace_once(
    service,
    '''        String addition = "

[Observación " + (learningObservationCount + 1) + "]
" + snapshot;''',
    '''        String addition = "\\n\\n[Observación " + (learningObservationCount + 1) + "]\\n" + snapshot;''',
    "observation note newline escaping",
)
service_path.write_text(service, encoding="utf-8")


a11y_path = Path("app/src/main/java/com/erik/screenobserver/AgentAccessibilityService.java")
a11y = a11y_path.read_text(encoding="utf-8")
a11y = replace_once(
    a11y,
    "        int overlayWidth = Math.min(dp(320), Math.max(dp(250), screenWidth - dp(16)));",
    "        int overlayWidth = Math.max(dp(220), Math.min(dp(320), screenWidth - dp(16)));",
    "responsive overlay width",
)
a11y_path.write_text(a11y, encoding="utf-8")


intent_path = Path("app/src/main/java/com/erik/screenobserver/IntentAgent.java")
intent = intent_path.read_text(encoding="utf-8")
intent = replace_once(
    intent,
    '                "analiza este juego", "analiza el juego actual", "analiza este juego y aprende",',
    '                "analiza este juego", "analiza el juego", "analiza un juego", "analiza el juego actual", "analiza este juego y aprende",',
    "broader analyze-game phrases",
)
intent = replace_once(
    intent,
    '                "aprende a usar este juego", "aprende este juego", "estudia este juego",',
    '                "aprende a usar este juego", "aprende a usar el juego", "aprende este juego", "estudia este juego",',
    "broader learn-game phrases",
)
intent_path.write_text(intent, encoding="utf-8")


test_path = Path("local-tests/AgentUnderstandingTest.java")
test = test_path.read_text(encoding="utf-8")
test = replace_once(
    test,
    '        type("aprende a usar este juego", IntentAgent.Type.LEARN_CURRENT_APP, "", "", "");',
    '        type("aprende a usar este juego", IntentAgent.Type.LEARN_CURRENT_APP, "", "", "");\n'
    '        type("analiza el juego", IntentAgent.Type.LEARN_CURRENT_APP, "", "", "");\n'
    '        type("analiza un juego", IntentAgent.Type.LEARN_CURRENT_APP, "", "", "");\n'
    '        type("aprende a usar el juego", IntentAgent.Type.LEARN_CURRENT_APP, "", "", "");',
    "broader game-learning regressions",
)
test_path.write_text(test, encoding="utf-8")

print("patch_v24_fix: generated strings, responsive overlay and broader game phrases fixed")
