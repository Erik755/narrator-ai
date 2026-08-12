from pathlib import Path
import runpy

path = Path("app/src/main/java/com/erik/screenobserver/GeminiRemoteAgent.java")
text = path.read_text(encoding="utf-8")
old = 'public static final String FALLBACK_MODEL = "gemini-3.5-flash-lite";'
new = 'public static final String FALLBACK_MODEL = "gemini-2.5-flash";'
if old not in text:
    if new in text:
        print("patch_v26_model_fix: stable fallback already applied")
    else:
        raise SystemExit("patch_v26_model_fix: fallback model anchor missing")
else:
    text = text.replace(old, new, 1)
    path.write_text(text, encoding="utf-8")
    print("patch_v26_model_fix: stable Gemini 2.5 Flash fallback applied")

# Keep the connection-test UI as part of the same generated v2.6 runtime even if
# the workflow only invokes this compatibility patch after patch_v26.py.
runpy.run_path("patch_v26_ui_test.py", run_name="__main__")
