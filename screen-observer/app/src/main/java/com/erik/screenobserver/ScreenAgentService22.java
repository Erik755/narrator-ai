package com.erik.screenobserver;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Screen Observer Pro 2.6: local intent agent + built-in Android 15/16 operating skill. */
public class ScreenAgentService22 extends Service {
    public static final String ACTION_SHOW_OVERLAY = "com.erik.screenobserver.v22.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.erik.screenobserver.v22.HIDE_OVERLAY";
    public static final String ACTION_TOGGLE_LISTENING = "com.erik.screenobserver.v22.TOGGLE_LISTENING";
    public static final String ACTION_DESCRIBE_CONTROLS = "com.erik.screenobserver.v22.DESCRIBE_CONTROLS";
    public static final String ACTION_TEXT_COMMAND = "com.erik.screenobserver.v24.TEXT_COMMAND";
    private static final String EXTRA_TEXT_COMMAND = "textCommand";

    private static final String CHANNEL = "screen_observer_v22";
    private static final int FOREGROUND_ID = 36;

    private static volatile boolean runningState = false;
    private static volatile boolean listeningState = false;
    private static volatile String voiceStatus = "detenido";
    private static volatile String activeSkillState = "";
    private static volatile String aiStatusState = "IA local pendiente";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<VisionTarget> visionTargets = new ArrayList<>();
    private final ExecutorService actionExecutor = Executors.newSingleThreadExecutor();

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private TextRecognizer ocr;
    private SpeechRecognizer recognizer;
    private Intent recognitionIntent;
    private TextToSpeech tts;
    private BargeInDetector barge;
    private SkillManager skills;
    private LocalLanguageAgent languageAgent;

    private boolean listeningEnabled = true;
    private boolean listening = false;
    private boolean speaking = false;
    private boolean ttsPendingStart = false;
    private boolean ttsReady = false;
    private boolean bargeInterrupted = false;
    private boolean autoLearning = false;
    private boolean visionMappingSafe = true;
    private boolean cuePending = true;
    private boolean pendingSensitiveLong = false;
    private int speechErrors = 0;

    private long ignoreUntil = 0;
    private long lastProcess = 0;
    private long captureGeneration = 1;
    private String activeUtteranceId = "";
    private String lastText = "";
    private String lastContentPackage = "";
    private String learningPackage = "";
    private String learningSkillName = "";
    private String lastLearningSnapshot = "";
    private long learningUntil = 0;
    private long lastLearningObservationAt = 0;
    private int learningObservationCount = 0;
    private String pendingSensitive = "";
    private long pendingSensitiveUntil = 0;
    private int captureW = 1, captureH = 1;
    private int contentW = 1, contentH = 1;
    private int screenW = 1, screenH = 1;
    private int captureDensity = 160;

    public static boolean isRunning() { return runningState; }
    public static boolean isListeningEnabled() { return runningState && listeningState; }
    public static String getVoiceStatus() { return voiceStatus == null ? "" : voiceStatus; }
    public static String getActiveSkillState() { return activeSkillState == null ? "" : activeSkillState; }

    @Override public void onCreate() {
        super.onCreate();
        runningState = true;
        listeningState = true;
        voiceStatus = "preparando micrófono";
        createChannel();
        if (Build.VERSION.SDK_INT >= 29) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(FOREGROUND_ID, notification(), type);
        } else {
            startForeground(FOREGROUND_ID, notification());
        }
        skills = new SkillManager(this);
        activeSkillState = skills.getActiveSkillName();
        languageAgent = new LocalLanguageAgent(this, new LocalLanguageAgent.StatusListener() {
            @Override public void onStatus(String value) {
                main.post(() -> {
                    aiStatusState = value == null ? "" : value;
                    if (!speaking && !listening) voiceStatus = aiStatusState;
                    passiveOverlay();
                });
            }
        });
        languageAgent.start();
        ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        barge = new BargeInDetector(this);
        setupTts();
        setupRecognition();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_SHOW_OVERLAY.equals(action)) {
            AgentAccessibilityService a = AgentAccessibilityService.getInstance();
            if (a != null) a.showOverlay();
            silent(a != null ? "Mini ventana visible." : "Activa Control de pantalla.");
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE_OVERLAY.equals(action)) {
            AgentAccessibilityService a = AgentAccessibilityService.getInstance();
            if (a != null) a.hideOverlay();
            silent("Mini ventana oculta.");
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_LISTENING.equals(action)) {
            toggleListening();
            refreshNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_DESCRIBE_CONTROLS.equals(action)) {
            describeControls(true);
            return START_NOT_STICKY;
        }
        if (ACTION_TEXT_COMMAND.equals(action)) {
            handleTextCommand(intent.getStringExtra(EXTRA_TEXT_COMMAND));
            return START_NOT_STICKY;
        }
        if (projection != null) return START_NOT_STICKY;

        int result = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra("data", Intent.class)
                : intent.getParcelableExtra("data");
        if (result != Activity.RESULT_OK || data == null) {
            silent("Permiso de captura no concedido.");
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            silent("No está disponible el servicio de captura.");
            stopSelf();
            return START_NOT_STICKY;
        }
        projection = manager.getMediaProjection(result, data);
        if (projection == null) {
            silent("La autorización de captura ya no es válida.");
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }

            @Override public void onCapturedContentResize(int width, int height) {
                handleCapturedContentResize(width, height);
            }
        }, main);

        updateScreenMetrics();
        contentW = screenW;
        contentH = screenH;
        int[] target = CaptureGeometry.targetSize(contentW, contentH);
        captureW = target[0];
        captureH = target[1];
        visionMappingSafe = CaptureGeometry.isDirectScreenMappingSafe(contentW, contentH, screenW, screenH);
        createInitialCaptureSurface();

        cuePending = true;
        voiceStatus = "preparando escucha";
        passiveOverlay();
        startListening(120);
        refreshNotification();
        return START_NOT_STICKY;
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        captureGeneration++;
        visionTargets.clear();
        updateScreenMetrics();
        visionMappingSafe = CaptureGeometry.isDirectScreenMappingSafe(contentW, contentH, screenW, screenH);
    }

    private void updateScreenMetrics() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = Math.max(1, dm.widthPixels);
        screenH = Math.max(1, dm.heightPixels);
        captureDensity = Math.max(160, dm.densityDpi);
    }

    private void createInitialCaptureSurface() {
        reader = createReader(captureW, captureH);
        virtualDisplay = projection.createVirtualDisplay(
                "ScreenObserver22",
                captureW,
                captureH,
                captureDensity,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                null);
    }

    private ImageReader createReader(int width, int height) {
        ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImage, main);
        return imageReader;
    }

    /** Keeps OCR geometry aligned after rotation or Android app-window sharing resize. */
    private void handleCapturedContentResize(int width, int height) {
        if (width <= 0 || height <= 0 || projection == null || virtualDisplay == null) return;
        captureGeneration++;
        contentW = width;
        contentH = height;
        updateScreenMetrics();
        int[] target = CaptureGeometry.targetSize(width, height);
        int newW = target[0], newH = target[1];
        visionMappingSafe = CaptureGeometry.isDirectScreenMappingSafe(width, height, screenW, screenH);

        if (newW == captureW && newH == captureH) {
            visionTargets.clear();
            return;
        }

        ImageReader replacement = createReader(newW, newH);
        ImageReader old = reader;
        try {
            virtualDisplay.resize(newW, newH, captureDensity);
            virtualDisplay.setSurface(replacement.getSurface());
            reader = replacement;
            captureW = newW;
            captureH = newH;
            visionTargets.clear();
            if (old != null) old.close();
        } catch (Exception e) {
            replacement.close();
            silent("No pude reajustar la captura; usaré solo controles accesibles.");
            visionMappingSafe = false;
        }
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            ttsReady = true;
            tts.setLanguage(new Locale("es", "MX"));
            tts.setSpeechRate(1.05f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    if (!activeUtteranceId.equals(id)) return;
                    ttsPendingStart = false;
                    speaking = true;
                    bargeInterrupted = false;
                    voiceStatus = "respondiendo · puedes interrumpirme";
                    cancelListening();
                    if (barge != null) barge.start(ScreenAgentService22.this::interruptSpeech);
                    passiveOverlay();
                }
                @Override public void onDone(String id) {
                    if (activeUtteranceId.equals(id)) finishSpeech(false);
                }
                @Override public void onError(String id) {
                    if (activeUtteranceId.equals(id)) finishSpeech(false);
                }
                @Override public void onStop(String id, boolean interrupted) {
                    if (activeUtteranceId.equals(id)) finishSpeech(interrupted || bargeInterrupted);
                }
            });
        });
    }

    private void interruptSpeech() {
        if (!speaking) return;
        bargeInterrupted = true;
        activeUtteranceId = "";
        if (barge != null) barge.stop();
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        speaking = false;
        ttsPendingStart = false;
        ignoreUntil = SystemClock.elapsedRealtime() + 50;
        cuePending = true;
        voiceStatus = "preparando escucha";
        passiveOverlay();
        startListening(70);
    }

    private void finishSpeech(boolean interrupted) {
        main.post(() -> {
            if (barge != null) barge.stop();
            activeUtteranceId = "";
            speaking = false;
            ttsPendingStart = false;
            long delay = interrupted ? 80 : 260;
            ignoreUntil = SystemClock.elapsedRealtime() + delay;
            cuePending = listeningEnabled;
            voiceStatus = listeningEnabled ? "preparando escucha" : "escucha pausada";
            passiveOverlay();
            startListening(delay);
        });
    }

    private void setupRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listeningState = false;
            voiceStatus = "permiso de micrófono pendiente";
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listeningState = false;
            voiceStatus = "reconocimiento de voz no disponible";
            return;
        }
        recognitionIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
        recognitionIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX");
        recognitionIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognitionIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recognitionIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        recognitionIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1050L);
        recognitionIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
        recognitionIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L);
        createRecognizer();
    }

    private void createRecognizer() {
        destroyRecognizer();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle b) {
                    speechErrors = 0;
                    listening = true;
                    listeningState = true;
                    voiceStatus = "🟢 listo · habla ahora";
                    passiveOverlay();
                    if (cuePending) {
                        cuePending = false;
                        ReadyCue.signal();
                    }
                }
                @Override public void onBeginningOfSpeech() {
                    voiceStatus = "escuchando tu petición";
                    passiveOverlay();
                }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() {
                    listening = false;
                    voiceStatus = "procesando";
                    passiveOverlay();
                }
                @Override public void onError(int e) {
                    listening = false;
                    speechErrors++;
                    if (!listeningEnabled || speaking || ttsPendingStart) return;
                    if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        listeningState = false;
                        voiceStatus = "sin permiso de micrófono";
                        passiveOverlay();
                        return;
                    }
                    if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        cuePending = true;
                        voiceStatus = "preparando escucha";
                        startListening(160);
                        return;
                    }
                    cuePending = true;
                    voiceStatus = "reintentando escucha";
                    if (e == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                            || e == SpeechRecognizer.ERROR_CLIENT
                            || e == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                            || speechErrors >= 4) {
                        main.postDelayed(() -> {
                            if (listeningEnabled && !speaking && !ttsPendingStart) {
                                createRecognizer();
                                startListening(450);
                            }
                        }, 500);
                    } else {
                        startListening(e == SpeechRecognizer.ERROR_NETWORK ? 1100 : 500);
                    }
                    passiveOverlay();
                }
                @Override public void onResults(Bundle b) {
                    listening = false;
                    speechErrors = 0;
                    final ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    final float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    if (matches != null && !matches.isEmpty()) {
                        voiceStatus = languageAgent != null && languageAgent.isReady()
                                ? "pensando con IA" : "procesando";
                        passiveOverlay();
                        if (languageAgent != null) {
                            languageAgent.interpret(matches, conf, currentUnderstandingContext(), activeSkillState,
                                    new LocalLanguageAgent.Callback() {
                                @Override public void onResult(LocalLanguageAgent.Result ai) {
                                    main.post(() -> {
                                        if (!runningState) return;
                                        IntentAgent.Result u = new IntentAgent.Result(
                                                ai.getType(), ai.getArgument(), matches.get(0), ai.getConfidence());
                                        if (ai.getUsedModel()
                                                && u.type == IntentAgent.Type.GENERAL
                                                && ai.getReply() != null
                                                && !ai.getReply().trim().isEmpty()) {
                                            speak(ai.getReply());
                                        } else {
                                            dispatch(u);
                                        }
                                        if (!speaking && !ttsPendingStart && listeningEnabled) {
                                            cuePending = true;
                                            voiceStatus = "preparando escucha";
                                            passiveOverlay();
                                            startListening(100);
                                        }
                                    });
                                }
                            });
                            return;
                        }
                        dispatch(IntentAgent.interpret(matches, conf, activeSkillState, lastText));
                    }
                    if (!speaking && !ttsPendingStart && listeningEnabled) {
                        cuePending = true;
                        voiceStatus = "preparando escucha";
                        passiveOverlay();
                        startListening(100);
                    }
                }
                @Override public void onPartialResults(Bundle b) {
                    // Do not expose or repeat an unstable partial transcript.
                    voiceStatus = "escuchando tu petición";
                    passiveOverlay();
                }
                @Override public void onEvent(int t, Bundle b) { }
            });
        } catch (Exception e) {
            recognizer = null;
            listeningState = false;
            voiceStatus = "no pude iniciar el reconocedor";
        }
    }

    private final Runnable listenRunnable = () -> {
        if (!listeningEnabled || speaking || ttsPendingStart || listening
                || recognizer == null || recognitionIntent == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now < ignoreUntil) {
            startListening(Math.min(2000, ignoreUntil - now + 20));
            return;
        }
        try {
            recognizer.startListening(recognitionIntent);
        } catch (Exception e) {
            main.postDelayed(() -> {
                if (listeningEnabled && !speaking && !ttsPendingStart) {
                    createRecognizer();
                    startListening(400);
                }
            }, 400);
        }
    };

    private void startListening(long ms) {
        main.removeCallbacks(listenRunnable);
        if (listeningEnabled && !speaking && !ttsPendingStart
                && recognizer != null && recognitionIntent != null) {
            main.postDelayed(listenRunnable, Math.max(50, Math.min(ms, 2000)));
        }
    }

    private void cancelListening() {
        main.removeCallbacks(listenRunnable);
        if (recognizer != null) try { recognizer.cancel(); } catch (Exception ignored) { }
        listening = false;
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
        }
        recognizer = null;
        listening = false;
    }

    private void toggleListening() {
        listeningEnabled = !listeningEnabled;
        listeningState = listeningEnabled;
        if (!listeningEnabled) {
            cancelListening();
            voiceStatus = "escucha pausada";
        } else {
            if (recognizer == null) createRecognizer();
            cuePending = true;
            voiceStatus = "preparando escucha";
            startListening(100);
        }
        passiveOverlay();
    }

    private boolean typedCommandsAvailable() {
        return runningState;
    }

    private void handleTextCommand(String command) {
        if (!typedCommandsAvailable()) return;
        final String typed = command == null ? "" : command.trim();
        if (typed.isEmpty()) {
            silent("Escribe una instrucción antes de enviarla.");
            return;
        }

        // Text is an independent control channel. Pausing microphone listening must never
        // disable typed commands. We only cancel an in-flight recognizer if one is active.
        if (listening) cancelListening();
        voiceStatus = listeningEnabled ? "⌨ procesando texto" : "⌨ texto activo · micrófono pausado";
        passiveOverlay();

        final String context = currentUnderstandingContext();
        final List<IntentAgent.Result> plan = PhoneCommandPlanner.plan(typed, activeSkillState, context);
        boolean allCommandSteps = plan.size() > 1;
        if (allCommandSteps) {
            for (IntentAgent.Result step : plan) {
                if (step == null || step.type == IntentAgent.Type.GENERAL) {
                    allCommandSteps = false;
                    break;
                }
            }
        }
        if (allCommandSteps) {
            if (PhoneCommandPlanner.containsSensitive(typed)) {
                speak("Por seguridad, envía las acciones sensibles una por una para que pueda confirmarlas.");
                finishTypedCommand();
                return;
            }
            executeCommandPlan(plan, 0);
            return;
        }
        if (plan.size() == 1 && plan.get(0).type != IntentAgent.Type.GENERAL
                && plan.get(0).confidence >= 0.50) {
            dispatch(plan.get(0));
            finishTypedCommand();
            return;
        }

        final ArrayList<String> input = new ArrayList<>();
        input.add(typed);
        final float[] confidence = new float[]{1.0f};
        if (languageAgent != null) {
            languageAgent.interpret(input, confidence, context, activeSkillState,
                    new LocalLanguageAgent.Callback() {
                @Override public void onResult(LocalLanguageAgent.Result ai) {
                    main.post(() -> {
                        if (!runningState) return;
                        IntentAgent.Result result = new IntentAgent.Result(
                                ai.getType(), ai.getArgument(), typed, ai.getConfidence());
                        if (ai.getUsedModel()
                                && result.type == IntentAgent.Type.GENERAL
                                && ai.getReply() != null
                                && !ai.getReply().trim().isEmpty()) {
                            speak(ai.getReply());
                        } else {
                            dispatch(result);
                        }
                        finishTypedCommand();
                    });
                }
            });
            return;
        }
        dispatch(plan.isEmpty()
                ? IntentAgent.interpret(input, confidence, activeSkillState, context)
                : plan.get(0));
        finishTypedCommand();
    }

    private String currentUnderstandingContext() {
        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        String controls = access == null ? "" : access.listInteractiveElements();
        String pkg = currentContentPackage();
        String label = appLabel(pkg);
        return "App: " + compact(label, 80)
                + " | Paquete: " + compact(pkg, 100)
                + " | Pantalla OCR: " + compact(lastText, 1500)
                + " | " + compact(controls, 1000);
    }

    private void finishTypedCommand() {
        if (speaking || ttsPendingStart) return;
        if (listeningEnabled) {
            cuePending = true;
            voiceStatus = "preparando escucha";
            passiveOverlay();
            startListening(120);
        } else {
            listeningState = false;
            voiceStatus = "escucha pausada · texto activo";
            passiveOverlay();
        }
    }

    private void executeCommandPlan(List<IntentAgent.Result> plan, int index) {
        if (plan == null || index >= plan.size()) {
            finishTypedCommand();
            return;
        }
        IntentAgent.Result step = plan.get(index);
        if (step == null || step.type == IntentAgent.Type.GENERAL) {
            speak("Entendí parte de la secuencia, pero una acción quedó ambigua. Escríbela por separado.");
            finishTypedCommand();
            return;
        }
        if (step.type == IntentAgent.Type.OPEN_APP) {
            executePlanOpenApp(plan, index, step);
            return;
        }
        dispatch(step);
        if (!runningState || step.type == IntentAgent.Type.STOP_ASSISTANT) return;
        if (!pendingSensitive.isEmpty() && SystemClock.elapsedRealtime() < pendingSensitiveUntil) {
            speak("La secuencia se detuvo porque una acción requiere confirmación.");
            finishTypedCommand();
            return;
        }
        long delay = PhoneCommandPlanner.recommendedDelayMs(step.type);
        main.postDelayed(() -> executeCommandPlan(plan, index + 1), delay);
    }

    private void executePlanOpenApp(List<IntentAgent.Result> plan, int index, IntentAgent.Result step) {
        final AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        final String requested = step.argument == null ? "" : step.argument.trim();
        final String beforePackage = currentContentPackage();
        if (access == null || requested.isEmpty()) {
            speak("No puedo abrir esa aplicación de forma fiable sin Control de pantalla activo.");
            finishTypedCommand();
            return;
        }
        actionExecutor.execute(() -> {
            final boolean opened = AndroidAppController.launchAppByLabel(access, requested);
            main.post(() -> {
                if (!opened) {
                    speak("No encontré la aplicación " + requested + ". La secuencia se detuvo.");
                    finishTypedCommand();
                    return;
                }
                waitForPlanAppReady(plan, index, requested, beforePackage, 0);
            });
        });
    }

    private void waitForPlanAppReady(List<IntentAgent.Result> plan, int index, String requested,
                                     String beforePackage, int attempt) {
        if (!runningState) return;
        String current = currentContentPackage();
        String currentLabel = IntentAgent.normalize(appLabel(current));
        String wanted = IntentAgent.normalize(requested);
        boolean labelMatches = !currentLabel.isEmpty() && !wanted.isEmpty()
                && (currentLabel.contains(wanted) || wanted.contains(currentLabel));
        boolean packageChanged = current != null && !current.isEmpty()
                && beforePackage != null && !current.equals(beforePackage);
        if (labelMatches || packageChanged) {
            main.postDelayed(() -> executeCommandPlan(plan, index + 1), 180);
            return;
        }
        if (attempt >= 16) {
            speak("Abrí la aplicación, pero no pude confirmar que estuviera lista. Detuve la secuencia para no actuar en la pantalla equivocada.");
            finishTypedCommand();
            return;
        }
        main.postDelayed(() -> waitForPlanAppReady(plan, index, requested, beforePackage, attempt + 1), 250);
    }

    private void dispatch(IntentAgent.Result r) {
        if (r == null) return;
        AgentAccessibilityService a;
        switch (r.type) {
            case HEARING_CHECK:
                speak("Sí. Te escucho.");
                break;
            case HIDE_OVERLAY:
                a = AgentAccessibilityService.getInstance();
                if (a != null) a.hideOverlay();
                silent("Mini ventana oculta.");
                break;
            case SHOW_OVERLAY:
                a = AgentAccessibilityService.getInstance();
                if (a != null) a.showOverlay();
                silent(a != null ? "Mini ventana visible." : "Activa Control de pantalla.");
                break;
            case STOP_ASSISTANT:
                silent("Deteniendo asistente.");
                main.postDelayed(this::stopSelf, 250);
                break;
            case PAUSE_LISTENING:
                listeningEnabled = false;
                listeningState = false;
                cancelListening();
                silent("Escucha pausada. La entrada escrita sigue activa.");
                break;
            case RESUME_LISTENING:
                listeningEnabled = true;
                listeningState = true;
                if (recognizer == null) createRecognizer();
                voiceStatus = "preparando escucha";
                startListening(120);
                silent("Escucha reanudada.");
                break;
            case LEARN_SKILL:
                learn(r.argument);
                break;
            case LEARN_CURRENT_APP:
                learnCurrentApp();
                break;
            case LIST_SKILLS: {
                String s = skills.listSkillNames();
                speak(s.isEmpty() ? "Todavía no tengo habilidades guardadas." : "Tengo estas habilidades: " + s + ".");
                break;
            }
            case USE_SKILL:
                if (skills.setActiveSkill(r.argument)) {
                    activeSkillState = skills.getActiveSkillName();
                    silent("Habilidad activa: " + activeSkillState);
                } else {
                    speak("Todavía no tengo esa habilidad. Puedes pedirme que la aprenda.");
                }
                break;
            case SKILL_INFO: {
                String n = skills.getSkillNotes(r.argument);
                speak(n.isEmpty() ? "No tengo esa habilidad guardada." : summarize(n, 700));
                break;
            }
            case DESCRIBE_CONTROLS:
                describeControls(true);
                break;
            case CONFIRM_CLICK:
                confirmClick(r.argument);
                break;
            case CLICK:
                click(r.argument, false);
                break;
            case CLICK_ORDINAL: {
                if (sensitiveScreenContext()) {
                    speak("Por seguridad, en esta pantalla usa el nombre exacto del botón y confirma la acción; no seleccionaré por posición.");
                    break;
                }
                AgentAccessibilityService ord = AgentAccessibilityService.getInstance();
                boolean last = "last".equalsIgnoreCase(r.argument);
                int ordinal = last ? 1 : parsePositiveInt(r.argument, 1);
                silent(ord != null && ord.clickOrdinal(ordinal, last)
                        ? "Control seleccionado." : "No pude seleccionar ese control por posición.");
                break;
            }
            case LONG_CLICK:
                longClick(r.argument, false);
                break;
            case TYPE_TEXT:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.setFocusedText(r.argument) ? "Texto introducido." : "No encuentro un campo editable enfocado.");
                break;
            case SEARCH:
                searchCurrentApp(r.argument);
                break;
            case SCROLL_DOWN:
                scroll(true);
                break;
            case SCROLL_UP:
                scroll(false);
                break;
            case SWIPE_LEFT:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.swipeLeft() ? "Deslicé a la izquierda." : "No pude hacer ese gesto.");
                break;
            case SWIPE_RIGHT:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.swipeRight() ? "Deslicé a la derecha." : "No pude hacer ese gesto.");
                break;
            case VOLUME_UP:
                adjustVolume(android.media.AudioManager.ADJUST_RAISE);
                break;
            case VOLUME_DOWN:
                adjustVolume(android.media.AudioManager.ADJUST_LOWER);
                break;
            case VOLUME_MUTE:
                adjustVolume(android.media.AudioManager.ADJUST_MUTE);
                break;
            case VOLUME_UNMUTE:
                adjustVolume(android.media.AudioManager.ADJUST_UNMUTE);
                break;
            case BACK:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.back() ? "Atrás ejecutado." : "No pude ejecutar Atrás.");
                break;
            case HOME:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.home() ? "Inicio ejecutado." : "No pude ir al inicio.");
                break;
            case RECENTS:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.recents() ? "Recientes abierto." : "No pude abrir recientes.");
                break;
            case NOTIFICATIONS:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.notifications() ? "Notificaciones abiertas." : "No pude abrir notificaciones.");
                break;
            case QUICK_SETTINGS:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.quickSettings() ? "Ajustes rápidos abiertos." : "No pude abrir ajustes rápidos.");
                break;
            case POWER_MENU:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.powerDialog() ? "Menú de energía abierto." : "No pude abrir el menú de energía.");
                break;
            case LOCK_SCREEN:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.lockScreen() ? "Pantalla bloqueada." : "No pude bloquear la pantalla.");
                break;
            case SCREENSHOT:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.screenshot() ? "Captura solicitada." : "No pude tomar la captura.");
                break;
            case CLOSE_APP:
                closeRequestedApp(r.argument);
                break;
            case OPEN_SETTINGS:
                silent(AndroidAppController.openSettings(this) ? "Ajustes abiertos." : "No pude abrir Ajustes.");
                break;
            case OPEN_SETTINGS_SECTION:
                silent(AndroidAppController.openSettingsSection(this, r.argument)
                        ? "Abrí ajustes de " + r.argument + "." : "No pude abrir esa sección de Ajustes.");
                break;
            case OPEN_URL:
                silent(AndroidAppController.openUrl(this, r.argument)
                        ? "Abrí " + r.argument + "." : "No pude abrir esa dirección.");
                break;
            case BLACKJACK_ADVICE:
                blackjackAdvice(r.raw);
                break;
            case BLACKJACK_PLAY:
                blackjackPlay(r.raw);
                break;
            case OPEN_APP: {
                final AgentAccessibilityService access = AgentAccessibilityService.getInstance();
                final String requestedApp = r.argument == null ? "" : r.argument.trim();
                if (access == null) {
                    speak("Activa Control de pantalla para poder abrir otras aplicaciones de forma fiable.");
                    break;
                }
                actionExecutor.execute(() -> {
                    final boolean opened = AndroidAppController.launchAppByLabel(access, requestedApp);
                    main.post(() -> silent(opened ? "Aplicación abierta." : "No encontré esa aplicación."));
                });
                break;
            }
            case DESCRIBE_SCREEN:
                speak(lastText.isEmpty() ? "No detecto texto legible en este momento." : "Veo en pantalla: " + compact(lastText, 430));
                break;
            case READ_SCREEN:
                speak(lastText.isEmpty() ? "No detecto texto legible." : "Leo: " + compact(lastText, 650));
                break;
            case ADVICE:
                speak(advice(r.raw));
                break;
            case GENERAL:
            default:
                if (r.confidence < .48) {
                    speak("No alcancé a entender la intención. Inténtalo otra vez cuando oigas la señal.");
                } else {
                    speak(generalAnswer(r.raw));
                }
                break;
        }
    }

    private boolean sensitiveScreenContext() {
        String screen = IntentAgent.normalize(lastText);
        return has(screen,
                "desinstalar", "uninstall", "factory reset", "restablecer de fabrica",
                "borrar todos los datos", "eliminar todos los datos", "erase all data",
                "eliminar cuenta", "delete account", "remove account", "formatear",
                "pagar", "comprar", "transferir", "enviar dinero", "depositar", "retirar");
    }

    private int parsePositiveInt(String value, int fallback) {
        try {
            int n = Integer.parseInt(value == null ? "" : value.trim());
            return n > 0 ? n : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private void adjustVolume(int direction) {
        try {
            android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio == null) {
                silent("No pude acceder al audio.");
                return;
            }
            audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction,
                    android.media.AudioManager.FLAG_SHOW_UI);
            silent("Volumen ajustado.");
        } catch (Exception e) {
            silent("No pude cambiar el volumen.");
        }
    }

    private void searchCurrentApp(String query) {
        if (query == null || query.trim().isEmpty()) {
            speak("Dime qué quieres buscar.");
            return;
        }
        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        if (access == null) {
            speak("Necesito Control de pantalla activo para buscar dentro de la aplicación.");
            return;
        }
        boolean opened = false;
        for (String alias : AndroidSkillPack.aliasesForTarget("buscar")) {
            if (access.clickText(alias)) { opened = true; break; }
        }
        final boolean searchOpened = opened;
        final String q = query.trim();
        if (!searchOpened) {
            speak("No encontré un control de búsqueda accesible en esta pantalla; no escribiré en otro campo.");
            return;
        }
        main.postDelayed(() -> {
            AgentAccessibilityService a = AgentAccessibilityService.getInstance();
            if (a != null && a.setFocusedText(q)) {
                silent("Búsqueda escrita: " + compact(q, 45) + ".");
            } else {
                speak("Abrí la búsqueda, pero no pude escribir en el campo.");
            }
        }, 420);
    }

    private void blackjackAdvice(String request) {
        BlackjackEngine.Recommendation rec = BlackjackEngine.recommendFromText(
                (request == null ? "" : request) + " " + lastText);
        if (!rec.known()) {
            speak("No pude leer con seguridad la mano. Escríbeme algo como: tengo 16 contra 10, o deja visibles el total del jugador y la carta del dealer.");
            return;
        }
        speak("Blackjack: " + rec.actionLabelEs() + ". " + rec.reason
                + " Asumo estrategia básica de varias barajas, dealer se planta en 17 suave y doble después de separar.");
    }

    private void blackjackPlay(String request) {
        String context = (request == null ? "" : request) + " " + lastText + " " + currentUnderstandingContext();
        BlackjackEngine.Recommendation rec = BlackjackEngine.recommendFromText(context);
        if (!rec.known()) {
            speak("No puedo jugar esta mano porque no distingo con seguridad tus cartas y la carta del dealer. Puedo aprender la interfaz o puedes escribir la mano.");
            return;
        }
        if (BlackjackEngine.isRealMoneyContext(context)) {
            speak("Detecto un contexto de dinero real. Puedo decirte la jugada de estrategia básica, pero no voy a ejecutar decisiones de apuestas automáticamente. Recomiendo "
                    + rec.actionLabelEs() + ".");
            return;
        }
        if (!BlackjackEngine.isPracticeContext(context)) {
            speak("Puedo recomendar " + rec.actionLabelEs()
                    + ", pero solo ejecuto blackjack automáticamente cuando la pantalla indica práctica, demo o juego gratis.");
            return;
        }
        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        if (access == null) {
            speak("Necesito Control de pantalla activo para jugar la mano de práctica.");
            return;
        }
        for (String label : BlackjackEngine.labelsFor(rec.decision)) {
            if (access.clickText(label)) {
                silent("Blackjack práctica: " + rec.actionLabelEs() + ".");
                return;
            }
        }
        speak("La recomendación es " + rec.actionLabelEs()
                + ", pero no encontré ese botón como control accesible.");
    }

    private boolean transientPackage(String pkg) {
        String n = IntentAgent.normalize(pkg == null ? "" : pkg.replace('.', ' '));
        return n.isEmpty()
                || n.equals(IntentAgent.normalize(getPackageName().replace('.', ' ')))
                || n.contains("inputmethod") || n.contains("keyboard")
                || n.contains("latin ime") || n.contains("gboard");
    }

    private String currentContentPackage() {
        String pkg = AgentAccessibilityService.getActivePackageName();
        if (transientPackage(pkg)) pkg = lastContentPackage;
        return pkg == null ? "" : pkg;
    }

    private String appLabel(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "";
        try {
            android.content.pm.ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = getPackageManager().getApplicationLabel(info);
            return label == null ? pkg : label.toString().trim();
        } catch (Exception e) {
            return pkg;
        }
    }

    private boolean genericCurrentTarget(String requested) {
        String n = IntentAgent.normalize(requested);
        return n.isEmpty() || n.equals("esta app") || n.equals("esta aplicacion")
                || n.equals("la app") || n.equals("la aplicacion") || n.equals("este juego")
                || n.equals("el juego") || n.equals("juego actual") || n.equals("aplicacion actual");
    }

    private void closeRequestedApp(String requested) {
        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        if (access == null) {
            speak("Necesito Control de pantalla activo para salir de la aplicación.");
            return;
        }
        String pkg = currentContentPackage();
        String label = appLabel(pkg);
        if (pkg.isEmpty() || label.isEmpty()) {
            speak("No puedo identificar qué aplicación está abierta.");
            return;
        }
        String wanted = IntentAgent.normalize(requested);
        String labelN = IntentAgent.normalize(label);
        String packageN = IntentAgent.normalize(pkg.replace('.', ' '));
        boolean matches = genericCurrentTarget(requested)
                || labelN.equals(wanted) || labelN.contains(wanted) || wanted.contains(labelN)
                || packageN.contains(wanted);
        if (!matches) {
            speak("Ahora mismo está abierta " + label + ". Para evitar cerrar otra app por error, abre la aplicación que quieras cerrar y vuelve a pedírmelo.");
            return;
        }
        if (access.home()) {
            silent("Salí de " + compact(label, 34) + ".");
        } else {
            speak("No pude volver a la pantalla principal.");
        }
    }

    private void learnCurrentApp() {
        String pkg = currentContentPackage();
        String label = appLabel(pkg);
        if (pkg.isEmpty() || label.isEmpty() || pkg.equals(getPackageName())) {
            speak("No puedo identificar el juego o la aplicación que quieres que aprenda. Déjala visible e inténtalo otra vez.");
            return;
        }

        learningPackage = pkg;
        learningSkillName = label;
        learningUntil = SystemClock.elapsedRealtime() + 5 * 60 * 1000L;
        learningObservationCount = 0;
        lastLearningSnapshot = "";
        lastLearningObservationAt = 0;

        AgentAccessibilityService access = AgentAccessibilityService.getInstance();
        String controls = access == null ? "" : access.listInteractiveElements();
        String previous = skills.getSkillNotes(label);
        JSONArray previousSources = skills.getSkillSourcesArray(label);
        String initial = "[Análisis local de " + label + "]\n"
                + "Paquete: " + pkg + "\n"
                + "Pantalla inicial: " + compact(lastText, 1100) + "\n"
                + "Controles iniciales: " + compact(controls, 900);
        String merged = previous == null || previous.trim().isEmpty()
                ? initial : previous + "\n\n" + initial;
        if (merged.length() > 7000) merged = merged.substring(merged.length() - 7000);
        skills.saveSkill(label, merged, previousSources);
        activeSkillState = skills.getActiveSkillName();
        recordLearningObservation(lastText);
        silent("Analizando " + compact(label, 32) + " durante los próximos minutos…");

        final String skillName = label;
        ResearchEngine.research(label, new ResearchEngine.Callback() {
            @Override public void onSuccess(String notes, JSONArray sources) {
                main.post(() -> {
                    String observed = skills.getSkillNotes(skillName);
                    String combined = observed + "\n\n[Investigación gratuita]\n" + notes;
                    if (combined.length() > 7000) combined = combined.substring(combined.length() - 7000);
                    skills.saveSkill(skillName, combined, sources);
                    activeSkillState = skills.getActiveSkillName();
                    silent("Aprendizaje activo: " + compact(skillName, 34) + ".");
                });
            }

            @Override public void onError(String message) {
                main.post(() -> {
                    // Local observation remains useful even when public sources have no article.
                    activeSkillState = skills.getActiveSkillName();
                    silent("Aprendiendo " + compact(skillName, 34) + " desde la pantalla.");
                });
            }
        });
    }

    private void recordLearningObservation(String text) {
        if (learningPackage.isEmpty() || learningSkillName.isEmpty()) return;
        long now = SystemClock.elapsedRealtime();
        if (now > learningUntil || learningObservationCount >= 20) {
            learningPackage = "";
            return;
        }
        String pkg = currentContentPackage();
        if (!learningPackage.equals(pkg)) return;
        if (now - lastLearningObservationAt < 3500) return;
        String snapshot = compact(text, 1000) + " | Controles/OCR: " + compact(visionSummary(), 500);
        String normalized = IntentAgent.normalize(snapshot);
        if (normalized.length() < 12 || normalized.equals(lastLearningSnapshot)) return;
        if (!lastLearningSnapshot.isEmpty()
                && (normalized.contains(lastLearningSnapshot) || lastLearningSnapshot.contains(normalized))) return;

        String existing = skills.getSkillNotes(learningSkillName);
        String addition = "\n\n[Observación " + (learningObservationCount + 1) + "]\n" + snapshot;
        String combined = existing + addition;
        if (combined.length() > 7000) combined = combined.substring(combined.length() - 7000);
        skills.saveSkill(learningSkillName, combined, skills.getSkillSourcesArray(learningSkillName));
        activeSkillState = skills.getActiveSkillName();
        lastLearningSnapshot = normalized;
        lastLearningObservationAt = now;
        learningObservationCount++;
    }

    private void learn(String topic) {
        if (topic == null || topic.trim().length() < 2) {
            speak("Dime qué habilidad quieres que aprenda.");
            return;
        }
        final String t = topic.trim();
        silent("Investigando: " + compact(t, 40));
        ResearchEngine.research(t, new ResearchEngine.Callback() {
            @Override public void onSuccess(String notes, JSONArray sources) {
                skills.saveSkill(t, notes, sources);
                activeSkillState = skills.getActiveSkillName();
                silent("Habilidad aprendida: " + t);
            }
            @Override public void onError(String message) {
                speak("No pude completar esa investigación ahora.");
            }
        });
    }

    private void confirmClick(String target) {
        if (!pendingSensitive.isEmpty()
                && SystemClock.elapsedRealtime() < pendingSensitiveUntil
                && relatedTargets(target, pendingSensitive)) {
            String targetToUse = pendingSensitive;
            boolean wasLong = pendingSensitiveLong;
            pendingSensitive = "";
            pendingSensitiveLong = false;
            pendingSensitiveUntil = 0;
            if (wasLong) longClick(targetToUse, true); else click(targetToUse, true);
        } else {
            silent("No hay una acción sensible pendiente con ese nombre.");
        }
    }

    private boolean sensitive(String t) {
        String n = IntentAgent.normalize(t);
        return has(n,
                "pagar", "pago", "comprar", "compra", "transferir", "transferencia", "enviar dinero",
                "borrar", "eliminar", "eliminar cuenta", "eliminar todo", "borrar todos los datos", "erase all data",
                "desinstalar", "desinstala", "uninstall", "factory reset", "restablecer de fabrica",
                "restablecimiento de fabrica", "restablecer", "formatear", "resetear");
    }

    private void click(String target, boolean confirmed) {
        if (target == null || target.trim().isEmpty()) {
            speak("Dime qué botón quieres pulsar.");
            return;
        }
        if (!confirmed && (sensitive(target) || affirmativeOnSensitiveScreen(target))) {
            pendingSensitive = target;
            pendingSensitiveLong = false;
            pendingSensitiveUntil = SystemClock.elapsedRealtime() + 15000;
            speak("Esa acción puede ser destructiva. Si de verdad quieres continuar, di: confirma pulsa " + target + ".");
            return;
        }
        AgentAccessibilityService a = AgentAccessibilityService.getInstance();
        if (a == null) {
            speak("Necesito que Control de pantalla esté activo en Accesibilidad.");
            return;
        }
        for (String alias : AndroidSkillPack.aliasesForTarget(target)) {
            if (a.clickText(alias)) {
                silent((confirmed ? "Confirmado. " : "") + "Pulsé " + alias + ".");
                return;
            }
        }

        if (visionMappingSafe) {
            VisionTarget v = findVisionWithAliases(target);
            if (v != null) {
                float x = v.x * ((float) screenW / Math.max(1, captureW));
                float y = v.y * ((float) screenH / Math.max(1, captureH));
                if (a.tap(x, y)) {
                    silent((confirmed ? "Confirmado. " : "") + "Pulsé visualmente " + target + ".");
                    return;
                }
            }
        }

        speak(visionMappingSafe
                ? "No encontré ese control. Puedes preguntarme qué controles veo."
                : "No encontré ese control mediante Accesibilidad. La captura actual no cubre toda la pantalla, así que no haré un toque visual impreciso.");
    }

    private void longClick(String target, boolean confirmed) {
        if (target == null || target.trim().isEmpty()) {
            speak("Dime qué control quieres mantener presionado.");
            return;
        }
        if (!confirmed && (sensitive(target) || affirmativeOnSensitiveScreen(target))) {
            pendingSensitive = target;
            pendingSensitiveLong = true;
            pendingSensitiveUntil = SystemClock.elapsedRealtime() + 15000;
            speak("Esa acción puede ser destructiva. Si quieres continuar, di: confirma pulsa " + target + ".");
            return;
        }
        AgentAccessibilityService a = AgentAccessibilityService.getInstance();
        if (a == null) {
            speak("Necesito Control de pantalla activo en Accesibilidad.");
            return;
        }
        for (String alias : AndroidSkillPack.aliasesForTarget(target)) {
            if (a.longClickText(alias)) {
                silent(confirmed ? "Acción confirmada y ejecutada." : "Pulsación larga ejecutada.");
                return;
            }
        }
        speak("No encontré un control que admita pulsación larga con ese nombre.");
    }

    private boolean affirmativeOnSensitiveScreen(String target) {
        String t = IntentAgent.normalize(target);
        boolean affirmative = has(t, "aceptar", "acepta", "ok", "si", "confirmar", "continuar", "yes", "proceed");
        if (!affirmative) return false;
        String screen = IntentAgent.normalize(lastText);
        return has(screen, "desinstalar", "uninstall", "factory reset", "restablecer de fabrica",
                "borrar todos los datos", "eliminar todos los datos", "erase all data",
                "eliminar cuenta", "delete account", "remove account", "formatear",
                "pagar", "comprar", "transferir", "enviar dinero");
    }

    private boolean relatedTargets(String a, String b) {
        String x = IntentAgent.normalize(a), y = IntentAgent.normalize(b);
        return !x.isEmpty() && !y.isEmpty() && (x.contains(y) || y.contains(x));
    }

    private void scroll(boolean down) {
        AgentAccessibilityService a = AgentAccessibilityService.getInstance();
        silent(a != null && a.scroll(down)
                ? (down ? "Bajé la pantalla." : "Subí la pantalla.")
                : "No pude desplazar esta pantalla.");
    }

    private void describeControls(boolean aloud) {
        AgentAccessibilityService a = AgentAccessibilityService.getInstance();
        String accessible = a == null ? "" : a.listInteractiveElements();
        String visual = visionSummary();
        String result;
        if (accessible.isEmpty() || accessible.contains("No encuentro")) {
            result = visual.isEmpty() ? "No encuentro controles legibles." : "Visualmente detecto: " + visual;
        } else {
            result = accessible + (visual.isEmpty() ? "" : ". También leo: " + visual);
        }
        if (!visionMappingSafe && !visual.isEmpty()) {
            result += ". La captura no coincide con toda la pantalla; usaré esos textos solo como referencia y no para tocar coordenadas.";
        }
        if (aloud) speak(result); else silent(result);
    }

    private String advice(String request) {
        String skill = skills.findRelevantSkill(request == null ? "" : request);
        if ((skill == null || skill.isEmpty()) && !activeSkillState.isEmpty()) skill = activeSkillState;
        String notes = skill == null ? "" : skills.getSkillNotes(skill);
        if (!notes.isEmpty()) {
            return "Usando la habilidad " + skill + ": " + summarize(notes, 420)
                    + ". Dime el objetivo concreto y usaré también los controles visibles de esta pantalla.";
        }
        return lastText.isEmpty()
                ? "Necesito un poco más de contexto visible para recomendar una acción concreta."
                : "Veo el contexto actual. Dime qué objetivo quieres lograr y te indico o ejecuto el siguiente paso.";
    }

    private String generalAnswer(String request) {
        String skill = skills.findRelevantSkill(request);
        if ((skill == null || skill.isEmpty()) && !activeSkillState.isEmpty()) skill = activeSkillState;
        String notes = skill == null ? "" : skills.getSkillNotes(skill);
        if (!notes.isEmpty()) {
            return "Con la habilidad " + skill + ", esto es lo más relevante: " + summarize(notes, 460);
        }
        return "Puedo seguir el contexto de la conversación, responder preguntas o actuar sobre Android cuando me lo pidas.";
    }

    private void onImage(ImageReader source) {
        long now = SystemClock.elapsedRealtime();
        Image image;
        try { image = source.acquireLatestImage(); } catch (Exception e) { return; }
        if (image == null) return;
        if (now - lastProcess < 650) {
            image.close();
            return;
        }
        lastProcess = now;
        final long requestGeneration = captureGeneration;
        final int requestW = captureW, requestH = captureH;
        Bitmap b = imageToBitmap(image);
        image.close();
        if (b == null) return;
        ocr.process(InputImage.fromBitmap(b, 0))
                .addOnSuccessListener(t -> {
                    b.recycle();
                    if (requestGeneration != captureGeneration || requestW != captureW || requestH != captureH) return;
                    updateVision(t);
                    String text = t.getText() == null ? "" : t.getText().trim();
                    if (text.equals(lastText)) return;
                    lastText = text;
                    activateContext(text);
                    recordLearningObservation(text);
                    passiveOverlay();
                })
                .addOnFailureListener(e -> b.recycle());
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane p = image.getPlanes()[0];
            ByteBuffer buffer = p.getBuffer();
            int ps = p.getPixelStride(), rs = p.getRowStride(), pad = rs - ps * image.getWidth();
            Bitmap padded = Bitmap.createBitmap(image.getWidth() + pad / ps, image.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap crop = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            if (padded != crop) padded.recycle();
            return crop;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateVision(Text t) {
        visionTargets.clear();
        if (t == null) return;
        for (Text.TextBlock b : t.getTextBlocks()) {
            for (Text.Line l : b.getLines()) {
                addVision(l.getText(), l.getBoundingBox());
                for (Text.Element e : l.getElements()) {
                    addVision(e.getText(), e.getBoundingBox());
                    if (visionTargets.size() > 150) return;
                }
            }
        }
    }

    private void addVision(String s, Rect r) {
        if (s != null && !s.trim().isEmpty() && r != null)
            visionTargets.add(new VisionTarget(s.trim(), r.exactCenterX(), r.exactCenterY()));
    }

    private VisionTarget findVisionWithAliases(String target) {
        VisionTarget best = null;
        int bestScore = -1;
        for (String alias : AndroidSkillPack.aliasesForTarget(target)) {
            String wanted = IntentAgent.normalize(alias);
            for (VisionTarget v : visionTargets) {
                String label = IntentAgent.normalize(v.label);
                int score = -1;
                if (label.equals(wanted)) score = 1000;
                else if (label.contains(wanted)) score = 700 - Math.abs(label.length() - wanted.length());
                else if (wanted.contains(label) && label.length() >= 3) score = 450 + label.length();
                if (score > bestScore) { bestScore = score; best = v; }
            }
        }
        return best;
    }

    private String visionSummary() {
        Set<String> seen = new HashSet<>();
        StringBuilder b = new StringBuilder();
        for (VisionTarget v : visionTargets) {
            String l = compact(v.label, 28), k = IntentAgent.normalize(l);
            if (k.length() < 2 || seen.contains(k)) continue;
            seen.add(k);
            if (b.length() > 0) b.append("; ");
            b.append(l);
            if (seen.size() >= 10) break;
        }
        return b.toString();
    }

    private void activateContext(String text) {
        String pkg = AgentAccessibilityService.getActivePackageName();
        if (!transientPackage(pkg)) lastContentPackage = pkg;
        String ctx = IntentAgent.normalize(pkg + " " + text);
        if (ctx.isEmpty()) return;

        if (AndroidSkillPack.looksLikeAndroidContext(pkg, text)) {
            skills.setActiveSkill(AndroidSkillPack.SKILL_NAME);
            activeSkillState = skills.getActiveSkillName();
            return;
        }

        String names = skills.listSkillNames();
        String best = "";
        if (!names.isEmpty()) {
            for (String raw : names.split(",")) {
                String name = raw.trim(), n = IntentAgent.normalize(name);
                if (n.equals(IntentAgent.normalize(AndroidSkillPack.SKILL_NAME))) continue;
                if (!n.isEmpty() && (ctx.contains(n)
                        || ("ajedrez".equals(n) && has(ctx, "chess", "lichess", "checkmate", "jaque")))
                        && name.length() > best.length()) best = name;
            }
        }
        if (!best.isEmpty()) {
            skills.setActiveSkill(best);
            activeSkillState = skills.getActiveSkillName();
            return;
        }

        String domain = has(ctx, "chess", "ajedrez", "lichess", "checkmate", "jaque mate") ? "ajedrez" : "";
        if (!domain.isEmpty() && skills.hasSkill(domain)) {
            skills.setActiveSkill(domain);
            activeSkillState = skills.getActiveSkillName();
        } else if (!domain.isEmpty() && !autoLearning) {
            autoLearning = true;
            ResearchEngine.research(domain, new ResearchEngine.Callback() {
                @Override public void onSuccess(String notes, JSONArray sources) {
                    skills.saveSkill(domain, notes, sources);
                    activeSkillState = skills.getActiveSkillName();
                    autoLearning = false;
                }
                @Override public void onError(String m) { autoLearning = false; }
            });
        }
    }

    /** Queues TTS without any sentinel delay; listening resumes only from TTS callbacks. */
    private void speak(String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (tts == null || !ttsReady) {
            silent("La voz todavía se está preparando.");
            startListening(300);
            return;
        }
        cancelListening();
        ttsPendingStart = true;
        ignoreUntil = 0;
        bargeInterrupted = false;
        final String utteranceId = "screen23_" + SystemClock.elapsedRealtime();
        activeUtteranceId = utteranceId;
        try {
            int result = tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            if (result == TextToSpeech.ERROR) {
                if (activeUtteranceId.equals(utteranceId)) activeUtteranceId = "";
                ttsPendingStart = false;
                ignoreUntil = SystemClock.elapsedRealtime() + 200;
                cuePending = true;
                startListening(200);
            } else {
                main.postDelayed(() -> {
                    if (activeUtteranceId.equals(utteranceId) && ttsPendingStart && !speaking) {
                        activeUtteranceId = "";
                        ttsPendingStart = false;
                        ignoreUntil = SystemClock.elapsedRealtime() + 100;
                        cuePending = true;
                        voiceStatus = "preparando escucha";
                        passiveOverlay();
                        startListening(120);
                    }
                }, 3000);
            }
        } catch (Exception e) {
            if (activeUtteranceId.equals(utteranceId)) activeUtteranceId = "";
            ttsPendingStart = false;
            ignoreUntil = SystemClock.elapsedRealtime() + 200;
            cuePending = true;
            startListening(200);
        }
    }

    private void silent(String s) {
        voiceStatus = compact(s, 56);
        passiveOverlay();
    }

    private void passiveOverlay() {
        AgentAccessibilityService a = AgentAccessibilityService.getInstance();
        if (a == null || !a.isOverlayVisible()) return;
        String s = listeningEnabled ? "🎙 " + compact(voiceStatus, 38) : "🎙 Escucha pausada · ⌨ texto activo";
        if (!activeSkillState.isEmpty()) s += "\nHabilidad: " + compact(activeSkillState, 24);
        if (!aiStatusState.isEmpty()) s += "\n" + compact(aiStatusState, 28);
        a.updateOverlay(s);
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivityV22.class);
        PendingIntent po = PendingIntent.getActivity(this, 401, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent li = new Intent(this, ScreenAgentService22.class);
        li.setAction(ACTION_TOGGLE_LISTENING);
        PendingIntent pl = PendingIntent.getService(this, 402, li,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("Screen Observer Pro 2.6")
                .setContentText(listeningEnabled
                        ? "Agente local · Android 15/16 · modo silencioso"
                        : "Agente local · escucha pausada")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(po)
                .setOngoing(true)
                .addAction(0, listeningEnabled ? "Pausar escucha" : "Activar escucha", pl)
                .build();
    }

    private void refreshNotification() {
        NotificationManager n = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (n != null && runningState) n.notify(FOREGROUND_ID, notification());
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager n = getSystemService(NotificationManager.class);
        if (n != null) n.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Asistente de pantalla 2.4", NotificationManager.IMPORTANCE_LOW));
    }

    private static boolean has(String s, String... xs) {
        for (String x : xs) if (s.contains(IntentAgent.normalize(x))) return true;
        return false;
    }

    private static String compact(String s, int max) {
        if (s == null) return "";
        String c = s.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return c.length() > max ? c.substring(0, max) + "…" : c;
    }

    private static String summarize(String s, int max) { return compact(s, max); }

    @Override public void onDestroy() {
        runningState = false;
        listeningState = false;
        voiceStatus = "detenido";
        activeSkillState = "";
        aiStatusState = "IA local pendiente";
        ttsPendingStart = false;
        main.removeCallbacksAndMessages(null);
        if (barge != null) barge.stop();
        cancelListening();
        destroyRecognizer();
        if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        if (virtualDisplay != null) try { virtualDisplay.release(); } catch (Exception ignored) { }
        if (projection != null) try { projection.stop(); } catch (Exception ignored) { }
        if (ocr != null) try { ocr.close(); } catch (Exception ignored) { }
        if (languageAgent != null) try { languageAgent.close(); } catch (Exception ignored) { }
        actionExecutor.shutdownNow();
        if (tts != null) try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent i) { return null; }

    private static final class VisionTarget {
        final String label;
        final float x, y;
        VisionTarget(String label, float x, float y) { this.label = label; this.x = x; this.y = y; }
    }
}
