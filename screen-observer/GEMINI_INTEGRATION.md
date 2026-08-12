# Screen Observer Pro 2.6 — Gemini integration

- Primary remote understanding model: `gemini-3.6-flash`.
- Remote fallback: `gemini-3.5-flash-lite`.
- API key is entered at runtime by the user and encrypted with Android Keystore; it is never committed or embedded in the APK.
- Requests use the Gemini Developer API `generateContent` endpoint with `x-goog-api-key` and structured JSON output.
- Gemini only interprets intent. Android actions still pass through the app's existing accessibility/safety execution layer.
- If Gemini is unavailable, the app starts/uses the local Qwen/LiteRT-LM agent and deterministic command interpreter.
- Pausing microphone listening does not disable typed mini-window commands.
