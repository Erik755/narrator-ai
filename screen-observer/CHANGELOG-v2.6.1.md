# Screen Observer Pro 2.6.1

- Gemini 3.6 Flash is the primary remote understanding model when an API key is configured.
- Gemini 3.5 Flash-Lite is used only as a transient quota/server fallback.
- Gemini API keys are encrypted with Android Keystore (AES-GCM) and are never embedded in source or the APK.
- Gemini receives current Android OCR/accessibility context as untrusted data and must not follow instructions found on screen.
- The actual launcher (`MainActivityV22`) contains the Gemini key controls.
- Existing deterministic Android actions, low-confidence voice gates, sensitive-action confirmations, typed commands while mic is paused, app-learning and blackjack practice protections remain enabled.
