from pathlib import Path

path = Path("app/src/main/java/com/erik/screenobserver/ScreenAgentService22.java")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"patch_safety_v23: expected one {label}, found {count}")
    text = text.replace(old, new, 1)


# Android 14+ foreground-service type requirements and background work support.
replace_once(
    "import android.content.pm.PackageManager;\n",
    "import android.content.pm.PackageManager;\nimport android.content.pm.ServiceInfo;\n",
    "ServiceInfo import",
)
replace_once(
    "import java.util.Set;\n",
    "import java.util.Set;\nimport java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;\n",
    "executor imports",
)
replace_once(
    "    private final Handler main = new Handler(Looper.getMainLooper());\n"
    "    private final List<VisionTarget> visionTargets = new ArrayList<>();",
    "    private final Handler main = new Handler(Looper.getMainLooper());\n"
    "    private final List<VisionTarget> visionTargets = new ArrayList<>();\n"
    "    private final ExecutorService actionExecutor = Executors.newSingleThreadExecutor();",
    "action executor",
)
replace_once(
    "    private boolean visionMappingSafe = true;\n"
    "    private boolean cuePending = true;\n"
    "    private int speechErrors = 0;",
    "    private boolean visionMappingSafe = true;\n"
    "    private boolean cuePending = true;\n"
    "    private boolean pendingSensitiveLong = false;\n"
    "    private int speechErrors = 0;",
    "sensitive long-click state",
)
replace_once(
    "    private long ignoreUntil = 0;\n"
    "    private long lastProcess = 0;\n"
    "    private String lastText = \"\";",
    "    private long ignoreUntil = 0;\n"
    "    private long lastProcess = 0;\n"
    "    private long captureGeneration = 1;\n"
    "    private String activeUtteranceId = \"\";\n"
    "    private String lastText = \"\";",
    "capture and TTS generations",
)
replace_once(
    "        createChannel();\n        startForeground(FOREGROUND_ID, notification());",
    "        createChannel();\n"
    "        if (Build.VERSION.SDK_INT >= 29) {\n"
    "            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;\n"
    "            if (Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;\n"
    "            startForeground(FOREGROUND_ID, notification(), type);\n"
    "        } else {\n"
    "            startForeground(FOREGROUND_ID, notification());\n"
    "        }",
    "typed foreground service",
)

# A consumed/revoked projection token must never be dereferenced.
replace_once(
    "        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);\n"
    "        projection = manager.getMediaProjection(result, data);\n"
    "        projection.registerCallback(new MediaProjection.Callback() {",
    "        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);\n"
    "        if (manager == null) {\n"
    "            silent(\"No está disponible el servicio de captura.\");\n"
    "            stopSelf();\n"
    "            return START_NOT_STICKY;\n"
    "        }\n"
    "        projection = manager.getMediaProjection(result, data);\n"
    "        if (projection == null) {\n"
    "            silent(\"La autorización de captura ya no es válida.\");\n"
    "            stopSelf();\n"
    "            return START_NOT_STICKY;\n"
    "        }\n"
    "        projection.registerCallback(new MediaProjection.Callback() {",
    "MediaProjection null guard",
)

# Any resize/configuration change invalidates OCR coordinates already in flight.
replace_once(
    "    private void handleCapturedContentResize(int width, int height) {\n"
    "        if (width <= 0 || height <= 0 || projection == null || virtualDisplay == null) return;\n"
    "        contentW = width;",
    "    private void handleCapturedContentResize(int width, int height) {\n"
    "        if (width <= 0 || height <= 0 || projection == null || virtualDisplay == null) return;\n"
    "        captureGeneration++;\n"
    "        contentW = width;",
    "capture generation on resize",
)
replace_once(
    "    @Override public void onConfigurationChanged(Configuration newConfig) {\n"
    "        super.onConfigurationChanged(newConfig);\n"
    "        updateScreenMetrics();\n"
    "        visionMappingSafe = CaptureGeometry.isDirectScreenMappingSafe(contentW, contentH, screenW, screenH);\n"
    "    }",
    "    @Override public void onConfigurationChanged(Configuration newConfig) {\n"
    "        super.onConfigurationChanged(newConfig);\n"
    "        captureGeneration++;\n"
    "        visionTargets.clear();\n"
    "        updateScreenMetrics();\n"
    "        visionMappingSafe = CaptureGeometry.isDirectScreenMappingSafe(contentW, contentH, screenW, screenH);\n"
    "    }",
    "capture generation on configuration",
)

# Track the current utterance. Old QUEUE_FLUSH callbacks cannot reopen the microphone over new speech.
replace_once(
    "            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {\n"
    "                @Override public void onStart(String id) {\n"
    "                    ttsPendingStart = false;\n"
    "                    speaking = true;\n"
    "                    bargeInterrupted = false;\n"
    "                    voiceStatus = \"respondiendo · puedes interrumpirme\";\n"
    "                    cancelListening();\n"
    "                    if (barge != null) barge.start(ScreenAgentService22.this::interruptSpeech);\n"
    "                    passiveOverlay();\n"
    "                }\n"
    "                @Override public void onDone(String id) { finishSpeech(false); }\n"
    "                @Override public void onError(String id) { finishSpeech(false); }\n"
    "                @Override public void onStop(String id, boolean interrupted) {\n"
    "                    finishSpeech(interrupted || bargeInterrupted);\n"
    "                }\n"
    "            });",
    "            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {\n"
    "                @Override public void onStart(String id) {\n"
    "                    if (!activeUtteranceId.equals(id)) return;\n"
    "                    ttsPendingStart = false;\n"
    "                    speaking = true;\n"
    "                    bargeInterrupted = false;\n"
    "                    voiceStatus = \"respondiendo · puedes interrumpirme\";\n"
    "                    cancelListening();\n"
    "                    if (barge != null) barge.start(ScreenAgentService22.this::interruptSpeech);\n"
    "                    passiveOverlay();\n"
    "                }\n"
    "                @Override public void onDone(String id) {\n"
    "                    if (activeUtteranceId.equals(id)) finishSpeech(false);\n"
    "                }\n"
    "                @Override public void onError(String id) {\n"
    "                    if (activeUtteranceId.equals(id)) finishSpeech(false);\n"
    "                }\n"
    "                @Override public void onStop(String id, boolean interrupted) {\n"
    "                    if (activeUtteranceId.equals(id)) finishSpeech(interrupted || bargeInterrupted);\n"
    "                }\n"
    "            });",
    "TTS callback generation",
)
replace_once(
    "        bargeInterrupted = true;\n"
    "        if (barge != null) barge.stop();\n"
    "        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }",
    "        bargeInterrupted = true;\n"
    "        activeUtteranceId = \"\";\n"
    "        if (barge != null) barge.stop();\n"
    "        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }",
    "barge invalidates TTS id",
)
replace_once(
    "            if (barge != null) barge.stop();\n"
    "            speaking = false;\n"
    "            ttsPendingStart = false;\n"
    "            long delay = interrupted ? 80 : 260;",
    "            if (barge != null) barge.stop();\n"
    "            activeUtteranceId = \"\";\n"
    "            speaking = false;\n"
    "            ttsPendingStart = false;\n"
    "            long delay = interrupted ? 80 : 260;",
    "finish speech invalidates TTS id",
)

# OPEN_APP label enumeration happens off the main thread and requires the active accessibility exemption.
replace_once(
    "            case OPEN_APP:\n"
    "                silent(AndroidAppController.launchAppByLabel(this, r.argument)\n"
    "                        ? \"Abrí \" + r.argument + \".\" : \"No encontré una aplicación llamada \" + r.argument + \".\");\n"
    "                break;",
    "            case OPEN_APP: {\n"
    "                final AgentAccessibilityService access = AgentAccessibilityService.getInstance();\n"
    "                final String requestedApp = r.argument == null ? \"\" : r.argument.trim();\n"
    "                if (access == null) {\n"
    "                    speak(\"Activa Control de pantalla para poder abrir otras aplicaciones de forma fiable.\");\n"
    "                    break;\n"
    "                }\n"
    "                actionExecutor.execute(() -> {\n"
    "                    final boolean opened = AndroidAppController.launchAppByLabel(access, requestedApp);\n"
    "                    main.post(() -> silent(opened ? \"Aplicación abierta.\" : \"No encontré esa aplicación.\"));\n"
    "                });\n"
    "                break;\n"
    "            }",
    "background app launch",
)

# Confirm both ordinary and long destructive actions, including generic OK/Aceptar on destructive dialogs.
replace_once(
    "            case LONG_CLICK:\n                longClick(r.argument);\n                break;",
    "            case LONG_CLICK:\n                longClick(r.argument, false);\n                break;",
    "long click dispatch",
)
replace_once(
    "    private void confirmClick(String target) {\n"
    "        if (!pendingSensitive.isEmpty()\n"
    "                && SystemClock.elapsedRealtime() < pendingSensitiveUntil\n"
    "                && relatedTargets(target, pendingSensitive)) {\n"
    "            String targetToUse = pendingSensitive;\n"
    "            pendingSensitive = \"\";\n"
    "            pendingSensitiveUntil = 0;\n"
    "            click(targetToUse, true);\n"
    "        } else {\n"
    "            silent(\"No hay una acción sensible pendiente con ese nombre.\");\n"
    "        }\n"
    "    }",
    "    private void confirmClick(String target) {\n"
    "        if (!pendingSensitive.isEmpty()\n"
    "                && SystemClock.elapsedRealtime() < pendingSensitiveUntil\n"
    "                && relatedTargets(target, pendingSensitive)) {\n"
    "            String targetToUse = pendingSensitive;\n"
    "            boolean wasLong = pendingSensitiveLong;\n"
    "            pendingSensitive = \"\";\n"
    "            pendingSensitiveLong = false;\n"
    "            pendingSensitiveUntil = 0;\n"
    "            if (wasLong) longClick(targetToUse, true); else click(targetToUse, true);\n"
    "        } else {\n"
    "            silent(\"No hay una acción sensible pendiente con ese nombre.\");\n"
    "        }\n"
    "    }",
    "sensitive confirmation kind",
)
replace_once(
    "    private void click(String target, boolean confirmed) {\n"
    "        if (target == null || target.trim().isEmpty()) {\n"
    "            speak(\"Dime qué botón quieres pulsar.\");\n"
    "            return;\n"
    "        }\n"
    "        if (!confirmed && sensitive(target)) {\n"
    "            pendingSensitive = target;\n"
    "            pendingSensitiveUntil = SystemClock.elapsedRealtime() + 15000;",
    "    private void click(String target, boolean confirmed) {\n"
    "        if (target == null || target.trim().isEmpty()) {\n"
    "            speak(\"Dime qué botón quieres pulsar.\");\n"
    "            return;\n"
    "        }\n"
    "        if (!confirmed && (sensitive(target) || affirmativeOnSensitiveScreen(target))) {\n"
    "            pendingSensitive = target;\n"
    "            pendingSensitiveLong = false;\n"
    "            pendingSensitiveUntil = SystemClock.elapsedRealtime() + 15000;",
    "context-sensitive click confirmation",
)
replace_once(
    "    private void longClick(String target) {\n"
    "        AgentAccessibilityService a = AgentAccessibilityService.getInstance();\n"
    "        if (a == null) {\n"
    "            speak(\"Necesito Control de pantalla activo en Accesibilidad.\");\n"
    "            return;\n"
    "        }\n"
    "        for (String alias : AndroidSkillPack.aliasesForTarget(target)) {\n"
    "            if (a.longClickText(alias)) {\n"
    "                silent(\"Mantuve presionado \" + alias + \".\");\n"
    "                return;\n"
    "            }\n"
    "        }\n"
    "        speak(\"No encontré un control que admita pulsación larga con ese nombre.\");\n"
    "    }",
    "    private void longClick(String target, boolean confirmed) {\n"
    "        if (target == null || target.trim().isEmpty()) {\n"
    "            speak(\"Dime qué control quieres mantener presionado.\");\n"
    "            return;\n"
    "        }\n"
    "        if (!confirmed && (sensitive(target) || affirmativeOnSensitiveScreen(target))) {\n"
    "            pendingSensitive = target;\n"
    "            pendingSensitiveLong = true;\n"
    "            pendingSensitiveUntil = SystemClock.elapsedRealtime() + 15000;\n"
    "            speak(\"Esa acción puede ser destructiva. Si quieres continuar, di: confirma pulsa \" + target + \".\");\n"
    "            return;\n"
    "        }\n"
    "        AgentAccessibilityService a = AgentAccessibilityService.getInstance();\n"
    "        if (a == null) {\n"
    "            speak(\"Necesito Control de pantalla activo en Accesibilidad.\");\n"
    "            return;\n"
    "        }\n"
    "        for (String alias : AndroidSkillPack.aliasesForTarget(target)) {\n"
    "            if (a.longClickText(alias)) {\n"
    "                silent(confirmed ? \"Acción confirmada y ejecutada.\" : \"Pulsación larga ejecutada.\");\n"
    "                return;\n"
    "            }\n"
    "        }\n"
    "        speak(\"No encontré un control que admita pulsación larga con ese nombre.\");\n"
    "    }",
    "long-click safety guards",
)
replace_once(
    "    private boolean relatedTargets(String a, String b) {",
    "    private boolean affirmativeOnSensitiveScreen(String target) {\n"
    "        String t = IntentAgent.normalize(target);\n"
    "        boolean affirmative = has(t, \"aceptar\", \"acepta\", \"ok\", \"si\", \"confirmar\", \"continuar\", \"yes\", \"proceed\");\n"
    "        if (!affirmative) return false;\n"
    "        String screen = IntentAgent.normalize(lastText);\n"
    "        return has(screen, \"desinstalar\", \"uninstall\", \"factory reset\", \"restablecer de fabrica\",\n"
    "                \"borrar todos los datos\", \"eliminar todos los datos\", \"erase all data\",\n"
    "                \"eliminar cuenta\", \"delete account\", \"remove account\", \"formatear\",\n"
    "                \"pagar\", \"comprar\", \"transferir\", \"enviar dinero\");\n"
    "    }\n\n"
    "    private boolean relatedTargets(String a, String b) {",
    "destructive dialog context guard",
)

# Reject OCR completions that were produced for a superseded capture surface.
replace_once(
    "        lastProcess = now;\n"
    "        Bitmap b = imageToBitmap(image);\n"
    "        image.close();\n"
    "        if (b == null) return;\n"
    "        ocr.process(InputImage.fromBitmap(b, 0))\n"
    "                .addOnSuccessListener(t -> {\n"
    "                    b.recycle();\n"
    "                    updateVision(t);",
    "        lastProcess = now;\n"
    "        final long requestGeneration = captureGeneration;\n"
    "        final int requestW = captureW, requestH = captureH;\n"
    "        Bitmap b = imageToBitmap(image);\n"
    "        image.close();\n"
    "        if (b == null) return;\n"
    "        ocr.process(InputImage.fromBitmap(b, 0))\n"
    "                .addOnSuccessListener(t -> {\n"
    "                    b.recycle();\n"
    "                    if (requestGeneration != captureGeneration || requestW != captureW || requestH != captureH) return;\n"
    "                    updateVision(t);",
    "OCR capture generation guard",
)

# TTS watchdog + utterance generation. A speech engine that accepts a request but never starts
# cannot leave listening permanently disabled.
replace_once(
    "        cancelListening();\n"
    "        ttsPendingStart = true;\n"
    "        ignoreUntil = 0;\n"
    "        bargeInterrupted = false;\n"
    "        try {\n"
    "            int result = tts.speak(value, TextToSpeech.QUEUE_FLUSH, null,\n"
    "                    \"screen22_\" + SystemClock.elapsedRealtime());\n"
    "            if (result == TextToSpeech.ERROR) {\n"
    "                ttsPendingStart = false;\n"
    "                ignoreUntil = SystemClock.elapsedRealtime() + 300;\n"
    "                startListening(300);\n"
    "            }\n"
    "        } catch (Exception e) {\n"
    "            ttsPendingStart = false;\n"
    "            ignoreUntil = SystemClock.elapsedRealtime() + 300;\n"
    "            startListening(300);\n"
    "        }",
    "        cancelListening();\n"
    "        ttsPendingStart = true;\n"
    "        ignoreUntil = 0;\n"
    "        bargeInterrupted = false;\n"
    "        final String utteranceId = \"screen23_\" + SystemClock.elapsedRealtime();\n"
    "        activeUtteranceId = utteranceId;\n"
    "        try {\n"
    "            int result = tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, utteranceId);\n"
    "            if (result == TextToSpeech.ERROR) {\n"
    "                if (activeUtteranceId.equals(utteranceId)) activeUtteranceId = \"\";\n"
    "                ttsPendingStart = false;\n"
    "                ignoreUntil = SystemClock.elapsedRealtime() + 200;\n"
    "                cuePending = true;\n"
    "                startListening(200);\n"
    "            } else {\n"
    "                main.postDelayed(() -> {\n"
    "                    if (activeUtteranceId.equals(utteranceId) && ttsPendingStart && !speaking) {\n"
    "                        activeUtteranceId = \"\";\n"
    "                        ttsPendingStart = false;\n"
    "                        ignoreUntil = SystemClock.elapsedRealtime() + 100;\n"
    "                        cuePending = true;\n"
    "                        voiceStatus = \"preparando escucha\";\n"
    "                        passiveOverlay();\n"
    "                        startListening(120);\n"
    "                    }\n"
    "                }, 3000);\n"
    "            }\n"
    "        } catch (Exception e) {\n"
    "            if (activeUtteranceId.equals(utteranceId)) activeUtteranceId = \"\";\n"
    "            ttsPendingStart = false;\n"
    "            ignoreUntil = SystemClock.elapsedRealtime() + 200;\n"
    "            cuePending = true;\n"
    "            startListening(200);\n"
    "        }",
    "TTS start watchdog",
)

replace_once(
    "        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }\n"
    "        if (tts != null) try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }\n"
    "        super.onDestroy();",
    "        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }\n"
    "        actionExecutor.shutdownNow();\n"
    "        if (tts != null) try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }\n"
    "        super.onDestroy();",
    "executor cleanup",
)

path.write_text(text, encoding="utf-8")
print("patch_safety_v23: Codex runtime fixes applied")
