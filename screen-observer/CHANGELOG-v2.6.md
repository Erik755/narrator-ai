## 2.6.0

- Gemini 3.6 Flash as primary language-understanding agent when configured.
- Gemini 3.5 Flash-Lite remote fallback.
- Encrypted runtime API-key storage using Android Keystore AES/GCM.
- No Gemini key is committed or embedded in the APK.
- Structured JSON intent output; existing Android action executor and sensitive-action confirmation remain in control.
- Local Qwen/LiteRT-LM starts lazily only when Gemini is not configured or remote inference fails.
- Gemini key entry/clear controls added to the main activity.
