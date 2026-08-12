from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
text = path.read_text(encoding="utf-8")

start_token = "        languageAgent = new LocalLanguageAgent(this"
end_token = "        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);"
start = text.find(start_token)
end = text.find(end_token, start)
if start < 0 or end < 0:
    raise SystemExit("patch_v26_anchor_fix: generated language-agent initialization anchors missing")
end += len(end_token)

normalized = '''        languageAgent = new LocalLanguageAgent(this, new LocalLanguageAgent.StatusListener() {
            @Override public void onStatus(String value) {
                main.post(() -> {
                    if (!speaking && !listening) {
                        voiceStatus = value;
                        passiveOverlay();
                    }
                });
            }
        });
        languageAgent.start();
        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);'''

text = text[:start] + normalized + text[end:]
path.write_text(text, encoding="utf-8")
print("patch_v26_anchor_fix: language-agent initialization normalized")
