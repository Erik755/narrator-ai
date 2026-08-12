from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/GeminiRemoteAgent.java")
text = path.read_text(encoding="utf-8")
old = 'private static final String[] MODELS = {"gemini-3.5-flash", "gemini-3.5-flash-lite"};'
new = 'private static final String[] MODELS = {"gemini-3.6-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite"};'
if text.count(old) != 1:
    raise SystemExit(f"patch_v26_model_fix: expected one model list, found {text.count(old)}")
text = text.replace(old, new, 1)
text = text.replace(
    "// 3.5 Flash currently has an official Free Tier and is substantially stronger than the\n"
    "    // on-device fallback. Flash-Lite is a lower-cost/lower-capacity fallback for quota spikes.\n    ",
    "// Gemini 3.6 Flash is preferred for comprehension; stable Gemini 2.5 models are fallbacks.\n    ")
path.write_text(text, encoding="utf-8")
print("patch_v26_model_fix: Gemini model order updated")
