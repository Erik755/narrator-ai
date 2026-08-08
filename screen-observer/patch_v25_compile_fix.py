from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/AndroidAppController.java")
text = path.read_text(encoding="utf-8")
old = "Settings.ACTION_NOTIFICATION_SETTINGS"
new = "Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS"
count = text.count(old)
if count != 1:
    raise SystemExit(f"patch_v25_compile_fix: expected one invalid notification settings action, found {count}")
text = text.replace(old, new, 1)
path.write_text(text, encoding="utf-8")
print("patch_v25_compile_fix: notification settings action corrected")
