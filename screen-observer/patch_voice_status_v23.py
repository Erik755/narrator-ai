from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_voice_status_v23: expected one {label}, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "    private static volatile String activeSkillState = \"\";",
    "    private static volatile String activeSkillState = \"\";\n"
    "    private static volatile String aiStatusState = \"IA local pendiente\";",
    "AI status state",
)

replace_once(
    "            @Override public void onStatus(String value) {\n"
    "                main.post(() -> {\n"
    "                    if (!speaking && !listening) {\n"
    "                        voiceStatus = value;\n"
    "                        passiveOverlay();\n"
    "                    }\n"
    "                });\n"
    "            }",
    "            @Override public void onStatus(String value) {\n"
    "                main.post(() -> {\n"
    "                    aiStatusState = value == null ? \"\" : value;\n"
    "                    if (!speaking && !listening) voiceStatus = aiStatusState;\n"
    "                    passiveOverlay();\n"
    "                });\n"
    "            }",
    "AI status listener",
)

replace_once(
    "                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {\n"
    "                        voiceStatus = \"preparando escucha\";\n"
    "                        startListening(160);\n"
    "                        return;\n"
    "                    }",
    "                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {\n"
    "                        cuePending = true;\n"
    "                        voiceStatus = \"preparando escucha\";\n"
    "                        startListening(160);\n"
    "                        return;\n"
    "                    }",
    "ready cue after timeout",
)

replace_once(
    "                    voiceStatus = \"reintentando escucha\";",
    "                    cuePending = true;\n"
    "                    voiceStatus = \"reintentando escucha\";",
    "ready cue after recognition error",
)

replace_once(
    "        String s = listeningEnabled ? \"🎙 \" + compact(voiceStatus, 38) : \"🎙 Escucha pausada\";\n"
    "        if (!activeSkillState.isEmpty()) s += \"\\nHabilidad: \" + compact(activeSkillState, 26);\n"
    "        a.updateOverlay(s);",
    "        String s = listeningEnabled ? \"🎙 \" + compact(voiceStatus, 38) : \"🎙 Escucha pausada\";\n"
    "        if (!activeSkillState.isEmpty()) s += \"\\nHabilidad: \" + compact(activeSkillState, 24);\n"
    "        if (!aiStatusState.isEmpty()) s += \"\\n\" + compact(aiStatusState, 28);\n"
    "        a.updateOverlay(s);",
    "overlay AI status",
)

replace_once(
    "        activeSkillState = \"\";\n        ttsPendingStart = false;",
    "        activeSkillState = \"\";\n        aiStatusState = \"IA local pendiente\";\n        ttsPendingStart = false;",
    "AI status cleanup",
)

path.write_text(text, encoding="utf-8")
print("patch_voice_status_v23: AI readiness and cue state applied")
