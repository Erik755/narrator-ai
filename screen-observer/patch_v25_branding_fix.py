from pathlib import Path

paths = [
    Path("app/src/main/java/com/erik/screenobserver/MainActivity.java"),
    Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java"),
    Path("app/src/main/java/com/erik/screenobserver/LocalLanguageAgent.kt"),
]
for path in paths:
    if not path.exists():
        continue
    text = path.read_text(encoding="utf-8")
    text = text.replace("Screen Observer Pro 2.4", "Screen Observer Pro 2.5")
    text = text.replace("ScreenObserverPro/2.4", "ScreenObserverPro/2.5")
    path.write_text(text, encoding="utf-8")
print("patch_v25_branding_fix: generated runtime branding finalized")
