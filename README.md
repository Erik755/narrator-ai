# Narrator AI

![JavaScript](https://img.shields.io/badge/JavaScript-Vite-F7DF1E?logo=javascript&logoColor=black)
![Android](https://img.shields.io/badge/Android-Java%20%7C%20Kotlin-3DDC84?logo=android&logoColor=white)
![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)

AI experiments around **narration and voice**. The repository contains two independent projects:

| Project | Folder | What it does |
|---|---|---|
| **NarradorAI** (web) | `/` (`src/`, `api/`) | Turns a short video into a narrated, subtitled vertical clip for social media — entirely in the browser. |
| **Screen Observer Pro** (Android) | `screen-observer/` | Voice/text assistant that reads the screen and operates the phone through an Accessibility Service, with a local LLM and optional Gemini cloud brain. |

> *Proyecto personal de Erik Sanchez — asistente de voz para Android y narrador de video con IA.*

---

## 1. NarradorAI — AI video narration (web)

### Features
- Upload a video; key frames are analysed by a vision model (NVIDIA NIM, `llama-3.2-11b-vision-instruct`) and a script is written by `llama-3.3-70b-instruct`.
- Selectable tone and language; per-segment script editor and regeneration.
- Text-to-speech with the browser **Web Speech API** (voice picker, live preview).
- **FFmpeg.wasm** pipeline in the browser: vertical 9:16 conversion, subtitle burn-in (SRT), audio overlay and export.
- Project history and settings stored locally (`localStorage`); data export excludes the API key.
- Installable PWA (manifest + service worker).

### Architecture
```
Browser (Vite, vanilla JS modules)
 ├─ components/   setup · upload · config · pipeline · editor · preview · export · history
 ├─ services/     nvidia.js (AI) · tts.js (Web Speech) · ffmpeg.js (FFmpeg.wasm) · storage.js
 └─ utils/        prompts.js · srt.js · helpers.js
        │
        ▼
api/nvidia.js  — Vercel serverless proxy (avoids CORS; forwards the user's own key, stores nothing)
```

### Run locally
```bash
npm ci
npm run dev      # http://localhost:5173
npm run build    # production build in dist/
```
The user enters their own NVIDIA API key in the app at runtime; no key is stored in the repository. The `/api/nvidia` proxy runs on Vercel (or `vercel dev` locally).

---

## 2. Screen Observer Pro — on-device voice agent (Android)

### Features
- **Accessibility Service** that reads visible controls and performs taps, typing and scrolling on request, plus a movable mini window without the overlay permission.
- **Screen capture + OCR** (MediaProjection + ML Kit Text Recognition) to understand what is on screen.
- **Hybrid language layer**: on-device model via Google **LiteRT-LM** (Qwen3 0.6B, downloaded on first use) with an optional **Gemini** REST brain (structured JSON output, conversation memory).
- **Secure key storage**: the Gemini API key is encrypted with **Android Keystore (AES/GCM)** — never hard-coded or committed.
- Deterministic **phone-command planner** for multi-step commands, speech-confidence gating, and explicit confirmation before sensitive/destructive actions. Screen/OCR content is treated as untrusted input.
- Barge-in detection, ready cues and a blackjack *practice* trainer (actions are blocked in real-money contexts).

### Tech stack
Java 21 · Kotlin · Android SDK 36 (min 26) · Accessibility & MediaProjection APIs · ML Kit · LiteRT-LM · Gemini API · Gradle 8.11 / AGP 8.10

### Build
Requirements: JDK 21, Android SDK 36, Gradle 8.11.1.
```bash
cd screen-observer
gradle assembleDebug          # APK in app/build/outputs/apk/debug/
```
Put `sdk.dir=/path/to/Android/sdk` in `screen-observer/local.properties` (git-ignored) if `ANDROID_HOME` is not set.

### Tests
Pure-Java regression suites (intent understanding, phone command matrix, blackjack engine, safety regressions) run without an emulator:
```bash
cd screen-observer
mkdir -p build/local-tests
SRC=app/src/main/java/com/erik/screenobserver
javac -d build/local-tests $SRC/TextNormalizer.java $SRC/BlackjackEngine.java $SRC/IntentAgent.java \
  $SRC/PhoneCommandPlanner.java $SRC/AndroidSkillPack.java $SRC/CaptureGeometry.java \
  $SRC/ConversationEngine.java local-tests/*.java
java -cp build/local-tests PhoneCommandMatrixTest
```
Run the other suites the same way: `AgentUnderstandingTest`, `BlackjackEngineTest`, `V25ReviewRegressionTest`.

---

## License
[MIT](LICENSE) © Erik Sanchez
