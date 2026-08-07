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
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Screen Observer Pro v2 runtime.
 * Principles:
 * - Never narrates the screen automatically.
 * - Keeps listening while idle.
 * - During TTS, a lightweight barge-in detector lets the user interrupt.
 * - Accessibility is tried first for actions; OCR coordinates are a visual fallback.
 * - Stored skills are activated automatically when the current app/screen matches them.
 */
public class ScreenAgentService extends Service {
    public static final String ACTION_SHOW_OVERLAY = "com.erik.screenobserver.v2.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.erik.screenobserver.v2.HIDE_OVERLAY";
    public static final String ACTION_TOGGLE_LISTENING = "com.erik.screenobserver.v2.TOGGLE_LISTENING";
    public static final String ACTION_DESCRIBE_CONTROLS = "com.erik.screenobserver.v2.DESCRIBE_CONTROLS";

    private static final String CHANNEL_CAPTURE = "screen_observer_v2";
    private static final int FOREGROUND_ID = 27;

    private static volatile boolean runningState = false;
    private static volatile boolean listeningState = false;
    private static volatile String voiceStatus = "detenido";
    private static volatile String activeSkillState = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<VisionTarget> visionTargets = new ArrayList<>();

    private MediaProjection projection;
    private ImageReader reader;
    private TextRecognizer textRecognizer;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private SkillManager skillManager;
    private BargeInDetector bargeInDetector;

    private boolean ttsReady = false;
    private boolean listeningEnabled = true;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean bargeInterrupted = false;
    private int consecutiveSpeechErrors = 0;

    private long lastProcess = 0;
    private long ignoreRecognitionUntil = 0;
    private String lastText = "";
    private String pendingDangerousTarget = "";
    private long pendingDangerousUntil = 0;

    private int captureWidth = 1;
    private int captureHeight = 1;
    private int screenWidth = 1;
    private int screenHeight = 1;
    private boolean autoLearningContext = false;

    public static boolean isRunning() { return runningState; }
    public static boolean isListeningEnabled() { return runningState && listeningState; }
    public static String getVoiceStatus() { return voiceStatus == null ? "" : voiceStatus; }
    public static String getActiveSkillState() { return activeSkillState == null ? "" : activeSkillState; }

    @Override public void onCreate() {
        super.onCreate();
        runningState = true;
        listeningEnabled = true;
        listeningState = true;
        voiceStatus = "preparando micrófono";
        activeSkillState = "";

        createChannels();
        startForeground(FOREGROUND_ID, buildForegroundNotification());

        skillManager = new SkillManager(this);
        activeSkillState = skillManager.getActiveSkillName();
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        bargeInDetector = new BargeInDetector(this);
        setupTts();
        setupSpeechRecognition();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_SHOW_OVERLAY.equals(action)) {
            if (!ensureRunning()) return START_NOT_STICKY;
            AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
            if (ui != null) ui.showOverlay();
            silentStatus(ui != null ? "Mini ventana visible." : "Activa Control de pantalla en Accesibilidad.");
            refreshForegroundNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE_OVERLAY.equals(action)) {
            if (!ensureRunning()) return START_NOT_STICKY;
            AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
            if (ui != null) ui.hideOverlay();
            silentStatus("Mini ventana oculta.");
            refreshForegroundNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_LISTENING.equals(action)) {
            if (!ensureRunning()) return START_NOT_STICKY;
            toggleListening();
            refreshForegroundNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_DESCRIBE_CONTROLS.equals(action)) {
            if (!ensureRunning()) return START_NOT_STICKY;
            describeControls(true);
            return START_NOT_STICKY;
        }

        if (projection != null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra("data", Intent.class);
        else data = intent.getParcelableExtra("data");
        if (resultCode != Activity.RESULT_OK || data == null) {
            silentStatus("No recibí permiso para capturar la pantalla.");
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, mainHandler);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidth = Math.max(1, dm.widthPixels);
        screenHeight = Math.max(1, dm.heightPixels);
        captureWidth = Math.max(360, screenWidth / 2);
        captureHeight = Math.max(640, screenHeight / 2);
        int density = Math.max(160, dm.densityDpi / 2);

        reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, mainHandler);
        projection.createVirtualDisplay(
                "ScreenObserverV2",
                captureWidth,
                captureHeight,
                density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                null);

        voiceStatus = "escuchando";
        updatePassiveOverlay();
        startListeningSoon(300);
        refreshForegroundNotification();
        return START_NOT_STICKY;
    }

    private boolean ensureRunning() {
        if (projection != null) return true;
        stopSelf();
        return false;
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            ttsReady = true;
            tts.setLanguage(new Locale("es", "MX"));
            tts.setSpeechRate(1.05f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    isSpeaking = true;
                    bargeInterrupted = false;
                    voiceStatus = "respondiendo · puedes interrumpirme";
                    cancelListening();
                    if (bargeInDetector != null) {
                        bargeInDetector.start(ScreenAgentService.this::interruptForUserSpeech);
                    }
                    updatePassiveOverlay();
                }

                @Override public void onDone(String utteranceId) {
                    finishSpeaking(false);
                }

                @Override public void onError(String utteranceId) {
                    finishSpeaking(false);
                }

                @Override public void onStop(String utteranceId, boolean interrupted) {
                    finishSpeaking(bargeInterrupted || interrupted);
                }
            });
        });
    }

    private void interruptForUserSpeech() {
        if (!isSpeaking) return;
        bargeInterrupted = true;
        if (bargeInDetector != null) bargeInDetector.stop();
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        isSpeaking = false;
        ignoreRecognitionUntil = SystemClock.elapsedRealtime() + 60;
        voiceStatus = "interrumpido · escuchando";
        updatePassiveOverlay();
        startListeningSoon(80);
    }

    private void finishSpeaking(boolean interrupted) {
        mainHandler.post(() -> {
            if (bargeInDetector != null) bargeInDetector.stop();
            isSpeaking = false;
            long delay = interrupted ? 80 : 650;
            ignoreRecognitionUntil = SystemClock.elapsedRealtime() + delay;
            voiceStatus = listeningEnabled ? (interrupted ? "escuchando" : "listo para escucharte") : "escucha pausada";
            updatePassiveOverlay();
            startListeningSoon(delay);
        });
    }

    private void setupSpeechRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listeningState = false;
            voiceStatus = "permiso de micrófono pendiente";
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listeningState = false;
            voiceStatus = "motor de voz no disponible";
            return;
        }

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 600L);
        speechIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L);
        createSpeechRecognizer();
    }

    private void createSpeechRecognizer() {
        destroySpeechRecognizer();
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    consecutiveSpeechErrors = 0;
                    isListening = true;
                    listeningState = true;
                    voiceStatus = "escuchando";
                    updatePassiveOverlay();
                    refreshForegroundNotification();
                }

                @Override public void onBeginningOfSpeech() {
                    voiceStatus = "te estoy oyendo";
                    updatePassiveOverlay();
                }

                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }

                @Override public void onEndOfSpeech() {
                    isListening = false;
                    voiceStatus = "procesando";
                    updatePassiveOverlay();
                }

                @Override public void onError(int error) {
                    isListening = false;
                    consecutiveSpeechErrors++;
                    if (!listeningEnabled || isSpeaking) return;

                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        listeningState = false;
                        voiceStatus = "sin permiso de micrófono";
                        updatePassiveOverlay();
                        return;
                    }
                    if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                        voiceStatus = "escuchando";
                        startListeningSoon(220);
                        return;
                    }

                    voiceStatus = speechErrorName(error);
                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                            || error == SpeechRecognizer.ERROR_CLIENT
                            || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                            || consecutiveSpeechErrors >= 4) {
                        mainHandler.postDelayed(() -> {
                            if (!listeningEnabled || isSpeaking) return;
                            createSpeechRecognizer();
                            startListeningSoon(550);
                        }, 600);
                    } else {
                        startListeningSoon(error == SpeechRecognizer.ERROR_NETWORK ? 1200 : 550);
                    }
                    updatePassiveOverlay();
                }

                @Override public void onResults(Bundle results) {
                    isListening = false;
                    consecutiveSpeechErrors = 0;
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        String heard = matches.get(0);
                        voiceStatus = "oí: " + compact(heard, 45);
                        handleVoiceCommand(heard);
                    } else {
                        voiceStatus = "escuchando";
                    }
                    if (!isSpeaking) startListeningSoon(280);
                }

                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> partial = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (partial != null && !partial.isEmpty()) {
                        voiceStatus = "oyendo: " + compact(partial.get(0), 42);
                        updatePassiveOverlay();
                    }
                }

                @Override public void onEvent(int eventType, Bundle params) { }
            });
        } catch (Exception e) {
            speechRecognizer = null;
            listeningState = false;
            voiceStatus = "no pude iniciar el reconocedor";
        }
    }

    private final Runnable startListeningRunnable = () -> {
        if (!listeningEnabled || isSpeaking || isListening || speechRecognizer == null || speechIntent == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now < ignoreRecognitionUntil) {
            startListeningSoon(ignoreRecognitionUntil - now + 20);
            return;
        }
        try {
            voiceStatus = "iniciando escucha";
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            mainHandler.postDelayed(() -> {
                if (!listeningEnabled || isSpeaking) return;
                createSpeechRecognizer();
                startListeningSoon(400);
            }, 400);
        }
    };

    private void startListeningSoon(long delayMs) {
        mainHandler.removeCallbacks(startListeningRunnable);
        if (!listeningEnabled || isSpeaking || speechRecognizer == null || speechIntent == null) return;
        mainHandler.postDelayed(startListeningRunnable, Math.max(50, delayMs));
    }

    private void cancelListening() {
        mainHandler.removeCallbacks(startListeningRunnable);
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
        }
        isListening = false;
    }

    private void destroySpeechRecognizer() {
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
        }
        speechRecognizer = null;
        isListening = false;
    }

    private void toggleListening() {
        listeningEnabled = !listeningEnabled;
        listeningState = listeningEnabled;
        if (!listeningEnabled) {
            cancelListening();
            voiceStatus = "escucha pausada";
        } else {
            if (speechRecognizer == null) createSpeechRecognizer();
            voiceStatus = "activando escucha";
            startListeningSoon(180);
        }
        updatePassiveOverlay();
    }

    private void handleVoiceCommand(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String command = normalize(raw);

        if (containsAny(command, "me escuchas", "puedes escucharme", "puedes oirme", "me oyes")) {
            speakOnRequest("Sí, te escucho.");
            return;
        }
        if (containsAny(command, "oculta la ventana", "oculta la burbuja", "esconde la ventana", "quita la ventana")) {
            AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
            if (ui != null) ui.hideOverlay();
            silentStatus("Mini ventana oculta.");
            return;
        }
        if (containsAny(command, "muestra la ventana", "muestra la burbuja", "mostrar ventana")) {
            AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
            if (ui != null) ui.showOverlay();
            silentStatus(ui != null ? "Mini ventana visible." : "Activa Control de pantalla.");
            return;
        }
        if (containsAny(command, "deten el monitoreo", "deten el asistente", "para el asistente", "termina el asistente")) {
            silentStatus("Deteniendo asistente.");
            mainHandler.postDelayed(this::stopSelf, 300);
            return;
        }
        if (containsAny(command, "deja de escuchar", "desactiva escucha", "pausa escucha", "no me escuches")) {
            listeningEnabled = false;
            listeningState = false;
            cancelListening();
            voiceStatus = "escucha pausada";
            updatePassiveOverlay();
            return;
        }

        String learnTopic = extractAfterAny(command,
                "investiga y aprende ", "investiga sobre ", "aprende habilidad de ",
                "aprende la habilidad de ", "aprende sobre ", "aprende ");
        if (!learnTopic.isEmpty()) {
            learnSkill(learnTopic);
            return;
        }

        if (containsAny(command, "que habilidades tienes", "cuales son tus habilidades", "lista tus habilidades")) {
            String skills = skillManager.listSkillNames();
            speakOnRequest(skills.isEmpty() ? "Todavía no tengo habilidades guardadas." : "Tengo estas habilidades: " + skills + ".");
            return;
        }

        String useSkill = extractAfterAny(command, "usa la habilidad ", "activa la habilidad ", "usa habilidad ");
        if (!useSkill.isEmpty()) {
            if (skillManager.setActiveSkill(useSkill)) {
                activeSkillState = skillManager.getActiveSkillName();
                silentStatus("Habilidad activa: " + activeSkillState);
            } else {
                silentStatus("No tengo esa habilidad. Di: aprende " + useSkill);
            }
            return;
        }

        String learnedSkill = extractAfterAny(command, "que aprendiste de ", "que sabes de la habilidad ", "fuentes de ");
        if (!learnedSkill.isEmpty()) {
            String notes = skillManager.getSkillNotes(learnedSkill);
            speakOnRequest(notes.isEmpty() ? "No tengo esa habilidad guardada." : summarizeNotes(notes, 600));
            return;
        }

        if (containsAny(command, "que botones ves", "que controles ves", "que puedo pulsar", "que puedo tocar")) {
            describeControls(true);
            return;
        }

        String confirmTarget = extractAfterAny(command, "confirma pulsa ", "confirma toca ", "confirma presiona ");
        if (!confirmTarget.isEmpty()) {
            if (!pendingDangerousTarget.isEmpty()
                    && SystemClock.elapsedRealtime() < pendingDangerousUntil
                    && normalize(confirmTarget).contains(normalize(pendingDangerousTarget))) {
                performClick(confirmTarget, true);
                pendingDangerousTarget = "";
                pendingDangerousUntil = 0;
            } else {
                silentStatus("No hay una acción sensible pendiente.");
            }
            return;
        }

        String clickTarget = extractAfterAny(command, "haz clic en ", "pulsa el boton ", "pulsa ", "toca ", "presiona ", "oprime ");
        if (!clickTarget.isEmpty()) {
            if (isSensitiveTarget(clickTarget)) {
                pendingDangerousTarget = clickTarget;
                pendingDangerousUntil = SystemClock.elapsedRealtime() + 15000;
                speakOnRequest("Esa acción puede ser importante. Si quieres ejecutarla, di: confirma pulsa " + clickTarget + ".");
            } else {
                performClick(clickTarget, false);
            }
            return;
        }

        String textToWrite = extractRawAfterAny(raw, "escribe ", "introduce ", "ingresa ");
        if (!textToWrite.isEmpty()) {
            AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
            silentStatus(ui != null && ui.setFocusedText(textToWrite)
                    ? "Texto introducido."
                    : "No encuentro un campo editable enfocado.");
            return;
        }

        if (containsAny(command, "desplazate abajo", "desplaza hacia abajo", "desliza hacia abajo", "baja la pantalla")) {
            performScroll(true);
            return;
        }
        if (containsAny(command, "desplazate arriba", "desplaza hacia arriba", "desliza hacia arriba", "sube la pantalla")) {
            performScroll(false);
            return;
        }
        if (containsAny(command, "ve atras", "regresa", "boton atras")) {
            AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
            silentStatus(ui != null && ui.back() ? "Atrás ejecutado." : "No pude ejecutar Atrás.");
            return;
        }
        if (containsAny(command, "ve al inicio", "pantalla de inicio", "ve a home")) {
            AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
            silentStatus(ui != null && ui.home() ? "Inicio ejecutado." : "No pude ir al inicio.");
            return;
        }
        if (containsAny(command, "abre recientes", "muestra recientes", "aplicaciones recientes")) {
            AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
            silentStatus(ui != null && ui.recents() ? "Recientes abierto." : "No pude abrir recientes.");
            return;
        }

        // Screen narration only happens after an explicit request.
        if (containsAny(command, "que ves", "describe la pantalla", "que hay en pantalla", "dime que ves", "describe esto")) {
            speakOnRequest(describe(lastText));
            return;
        }
        if (containsAny(command, "lee la pantalla", "lee esto", "leeme la pantalla")) {
            speakOnRequest(lastText.isEmpty() ? "No detecto texto legible." : "Leo: " + compact(lastText, 600));
            return;
        }
        if (containsAny(command, "que hago", "que debo hacer", "que me recomiendas", "aconsejame", "cual elijo", "que opcion")) {
            speakOnRequest(adviceWithSkill(raw));
            return;
        }

        speakOnRequest(answerGeneralRequest(raw));
    }

    private void learnSkill(String topic) {
        topic = topic.trim();
        if (topic.length() < 2) {
            silentStatus("No entendí qué habilidad quieres aprender.");
            return;
        }
        final String skill = topic;
        silentStatus("Investigando: " + compact(skill, 45));
        ResearchEngine.research(skill, new ResearchEngine.Callback() {
            @Override public void onSuccess(String notes, JSONArray sources) {
                skillManager.saveSkill(skill, notes, sources);
                activeSkillState = skillManager.getActiveSkillName();
                silentStatus("Habilidad aprendida: " + skill + " · " + sources.length() + " fuentes.");
            }
            @Override public void onError(String message) {
                silentStatus("No pude aprender " + skill + ".");
            }
        });
    }

    private void performClick(String target, boolean confirmed) {
        AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
        if (ui == null) {
            silentStatus("Activa Control de pantalla en Accesibilidad.");
            return;
        }
        if (ui.clickText(target)) {
            silentStatus((confirmed ? "Confirmado. " : "") + "Pulsé " + target + ".");
            return;
        }

        VisionTarget visual = findVisionTarget(target);
        if (visual != null) {
            float x = visual.x * ((float) screenWidth / Math.max(1, captureWidth));
            float y = visual.y * ((float) screenHeight / Math.max(1, captureHeight));
            if (ui.tap(x, y)) {
                silentStatus((confirmed ? "Confirmado. " : "") + "Pulsé visualmente " + target + ".");
                return;
            }
        }
        silentStatus("No encontré un control llamado " + target + ".");
    }

    private void performScroll(boolean down) {
        AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
        silentStatus(ui != null && ui.scroll(down)
                ? (down ? "Desplacé hacia abajo." : "Desplacé hacia arriba.")
                : "No pude desplazar esta pantalla.");
    }

    private void describeControls(boolean speak) {
        AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
        String accessible = ui == null ? "" : ui.listInteractiveElements();
        String visual = visionControlSummary();
        String result;
        if (accessible.isEmpty()) result = visual.isEmpty() ? "No encuentro controles legibles." : visual;
        else if (accessible.contains("No encuentro") && !visual.isEmpty()) result = visual;
        else result = accessible + (visual.isEmpty() ? "" : ". Visualmente también detecto: " + visual);
        if (speak) speakOnRequest(result);
        else silentStatus(result);
    }

    private void onImage(ImageReader imageReader) {
        long now = SystemClock.elapsedRealtime();
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;
        if (now - lastProcess < 650) {
            image.close();
            return;
        }
        lastProcess = now;

        Bitmap bitmap = imageToBitmap(image);
        image.close();
        if (bitmap == null) return;

        textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(result -> {
                    bitmap.recycle();
                    updateVisionTargets(result);
                    String text = result.getText() == null ? "" : result.getText().trim();
                    if (!meaningfulChange(text)) return;
                    lastText = text;
                    maybeActivateSkillFromContext(text);
                    // Deliberately no narration, no advice notification, no screen dump.
                    updatePassiveOverlay();
                })
                .addOnFailureListener(e -> bitmap.recycle());
    }

    private void updateVisionTargets(Text result) {
        visionTargets.clear();
        if (result == null) return;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                addVisionTarget(line.getText(), line.getBoundingBox());
                for (Text.Element element : line.getElements()) {
                    addVisionTarget(element.getText(), element.getBoundingBox());
                    if (visionTargets.size() >= 140) return;
                }
            }
        }
    }

    private void addVisionTarget(String text, Rect rect) {
        if (text == null || text.trim().isEmpty() || rect == null) return;
        visionTargets.add(new VisionTarget(text.trim(), rect.exactCenterX(), rect.exactCenterY()));
    }

    private VisionTarget findVisionTarget(String target) {
        String wanted = normalize(target);
        if (wanted.isEmpty()) return null;
        VisionTarget best = null;
        int bestScore = -1;
        for (VisionTarget item : visionTargets) {
            String label = normalize(item.label);
            int score = -1;
            if (label.equals(wanted)) score = 1000;
            else if (label.contains(wanted)) score = 700 - Math.abs(label.length() - wanted.length());
            else if (wanted.contains(label) && label.length() >= 3) score = 450 + label.length();
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private String visionControlSummary() {
        Set<String> seen = new HashSet<>();
        StringBuilder out = new StringBuilder();
        for (VisionTarget target : visionTargets) {
            String label = compact(target.label, 32);
            String key = normalize(label);
            if (key.length() < 2 || seen.contains(key)) continue;
            seen.add(key);
            if (out.length() > 0) out.append("; ");
            out.append(label);
            if (seen.size() >= 10) break;
        }
        return out.toString();
    }

    private void maybeActivateSkillFromContext(String screenText) {
        String context = normalize(AgentAccessibilityService.getActivePackageName() + " " + screenText);
        if (context.isEmpty()) return;

        String skillNames = skillManager.listSkillNames();
        String best = "";
        if (!skillNames.isEmpty()) {
            String[] names = skillNames.split(",");
            for (String rawName : names) {
                String name = rawName.trim();
                if (name.isEmpty()) continue;
                if (contextMatchesSkill(context, name) && name.length() > best.length()) best = name;
            }
        }
        if (!best.isEmpty()) {
            if (!normalize(best).equals(normalize(skillManager.getActiveSkillName()))) {
                skillManager.setActiveSkill(best);
            }
            activeSkillState = skillManager.getActiveSkillName();
            return;
        }

        String discovered = discoverCommonDomain(context);
        if (!discovered.isEmpty() && !skillManager.hasSkill(discovered) && !autoLearningContext) {
            autoLearningContext = true;
            ResearchEngine.research(discovered, new ResearchEngine.Callback() {
                @Override public void onSuccess(String notes, JSONArray sources) {
                    skillManager.saveSkill(discovered, notes, sources);
                    activeSkillState = skillManager.getActiveSkillName();
                    autoLearningContext = false;
                    updatePassiveOverlay();
                }
                @Override public void onError(String message) {
                    autoLearningContext = false;
                }
            });
        } else if (!discovered.isEmpty() && skillManager.hasSkill(discovered)) {
            skillManager.setActiveSkill(discovered);
            activeSkillState = skillManager.getActiveSkillName();
        }
    }

    private boolean contextMatchesSkill(String context, String skill) {
        String s = normalize(skill);
        if (!s.isEmpty() && context.contains(s)) return true;
        if (s.equals("ajedrez") && containsAny(context, "chess", "lichess", "checkmate", "jaque")) return true;
        if (s.equals("pintura") && containsAny(context, "painting", "painter", "paint", "canvas", "brush")) return true;
        if (s.equals("soldadura") && containsAny(context, "welding", "welder", "tig", "mig")) return true;
        return false;
    }

    private String discoverCommonDomain(String context) {
        if (containsAny(context, "chess", "ajedrez", "lichess", "checkmate", "jaque mate")) return "ajedrez";
        if (containsAny(context, "pintura", "painting", "painter", "canvas", "brush")) return "pintura";
        if (containsAny(context, "soldadura", "welding", "welder", " tig ", " mig ")) return "soldadura";
        return "";
    }

    private Bitmap imageToBitmap(Image image) {
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            Bitmap padded = Bitmap.createBitmap(
                    image.getWidth() + rowPadding / pixelStride,
                    image.getHeight(),
                    Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            if (padded != cropped) padded.recycle();
            return cropped;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean meaningfulChange(String text) {
        if (text.equals(lastText)) return false;
        if (lastText.isEmpty()) return true;
        int max = Math.max(text.length(), lastText.length());
        if (max == 0) return false;
        int min = Math.min(text.length(), lastText.length());
        int common = 0;
        while (common < min && text.charAt(common) == lastText.charAt(common)) common++;
        return 1f - ((float) common / max) > 0.08f;
    }

    private String describe(String text) {
        if (text == null || text.isEmpty()) return "No detecto texto legible en este momento.";
        return "Veo en pantalla: " + compact(text, 380);
    }

    private String adviceWithSkill(String request) {
        String skill = skillManager.findRelevantSkill(request == null ? "" : request);
        if ((skill == null || skill.isEmpty()) && !activeSkillState.isEmpty()) skill = activeSkillState;
        String notes = skill == null ? "" : skillManager.getSkillNotes(skill);
        if (!notes.isEmpty()) {
            return "Usando la habilidad " + skill + ": " + summarizeNotes(notes, 360)
                    + ". Para una acción concreta, dime qué quieres lograr o pregúntame qué opción elegir.";
        }
        if (lastText.isEmpty()) return "No tengo suficiente información visible para recomendar una acción concreta.";
        return "Puedo usar el contexto de la pantalla, los controles accesibles y el texto visible. Dime qué objetivo quieres lograr.";
    }

    private String answerGeneralRequest(String request) {
        String skill = skillManager.findRelevantSkill(request);
        if ((skill == null || skill.isEmpty()) && !activeSkillState.isEmpty()) skill = activeSkillState;
        String notes = skill == null ? "" : skillManager.getSkillNotes(skill);
        if (!notes.isEmpty()) {
            return "Con la habilidad " + skill + ", la referencia más útil que tengo ahora es: "
                    + summarizeNotes(notes, 420);
        }
        return "Te escuché. Puedo analizar la pantalla, aprender una habilidad, decirte qué controles veo o ejecutar una acción si me indicas cuál.";
    }

    private void speakOnRequest(String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (tts == null || !ttsReady) {
            silentStatus("La voz todavía se está preparando.");
            startListeningSoon(350);
            return;
        }
        cancelListening();
        ignoreRecognitionUntil = Long.MAX_VALUE;
        bargeInterrupted = false;
        try {
            int result = tts.speak(value, TextToSpeech.QUEUE_FLUSH, null,
                    "screen_agent_" + SystemClock.elapsedRealtime());
            if (result == TextToSpeech.ERROR) {
                ignoreRecognitionUntil = SystemClock.elapsedRealtime() + 350;
                startListeningSoon(350);
            }
        } catch (Exception e) {
            ignoreRecognitionUntil = SystemClock.elapsedRealtime() + 350;
            startListeningSoon(350);
        }
    }

    private void silentStatus(String value) {
        if (value == null) return;
        voiceStatus = compact(value, 58);
        AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
        if (ui != null && ui.isOverlayVisible()) ui.updateOverlay(compact(value, 100));
    }

    private void updatePassiveOverlay() {
        AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
        if (ui == null || !ui.isOverlayVisible()) return;
        String line = listeningEnabled ? "🎙 " + compact(voiceStatus, 38) : "🎙 Escucha pausada";
        if (!activeSkillState.isEmpty()) line += "\nHabilidad: " + compact(activeSkillState, 28);
        ui.updateOverlay(line);
    }

    private Notification buildForegroundNotification() {
        Intent openIntent = new Intent(this, MainActivityV2.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 201, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent listenIntent = new Intent(this, ScreenAgentService.class);
        listenIntent.setAction(ACTION_TOGGLE_LISTENING);
        PendingIntent listenPending = PendingIntent.getService(this, 202, listenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent overlayIntent = new Intent(this, ScreenAgentService.class);
        AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
        boolean overlayVisible = ui != null && ui.isOverlayVisible();
        overlayIntent.setAction(overlayVisible ? ACTION_HIDE_OVERLAY : ACTION_SHOW_OVERLAY);
        PendingIntent overlayPending = PendingIntent.getService(this, 203, overlayIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_CAPTURE)
                : new Notification.Builder(this);
        builder.setContentTitle("Screen Observer Pro 2.0")
                .setContentText(listeningEnabled
                        ? "Modo silencioso · escuchando · interrupción de voz activa"
                        : "Modo silencioso · escucha pausada")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(0, listeningEnabled ? "Pausar escucha" : "Activar escucha", listenPending)
                .addAction(0, overlayVisible ? "Ocultar ventana" : "Mostrar ventana", overlayPending);
        return builder.build();
    }

    private void refreshForegroundNotification() {
        if (!runningState) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(FOREGROUND_ID, buildForegroundNotification());
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_CAPTURE, "Asistente de pantalla v2", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mantiene la captura y escucha del asistente en modo silencioso.");
        nm.createNotificationChannel(channel);
    }

    private String speechErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "error de audio · reintentando";
            case SpeechRecognizer.ERROR_CLIENT: return "reiniciando reconocedor";
            case SpeechRecognizer.ERROR_NETWORK: return "red de voz no disponible · reintentando";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "motor de voz tardó demasiado";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "reconocedor ocupado · reiniciando";
            case SpeechRecognizer.ERROR_SERVER: return "motor de voz no disponible";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "motor de voz desconectado · reiniciando";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS: return "motor de voz limitado · reintentando";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED: return "español no admitido";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "modelo de español no disponible";
            default: return "reintentando escucha";
        }
    }

    private boolean isSensitiveTarget(String target) {
        String t = normalize(target);
        return containsAny(t, "pagar", "pago", "comprar", "compra", "transferir", "transferencia",
                "enviar dinero", "borrar", "eliminar cuenta", "eliminar todo", "restablecer", "formatear");
    }

    private static String summarizeNotes(String value, int max) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return compact.length() > max ? compact.substring(0, max) + "…" : compact;
    }

    private static String compact(String value, int max) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return compact.length() > max ? compact.substring(0, max) + "…" : compact;
    }

    private static boolean containsAny(String value, String... options) {
        if (value == null) return false;
        for (String option : options) if (value.contains(option)) return true;
        return false;
    }

    private static String extractAfterAny(String value, String... prefixes) {
        if (value == null) return "";
        for (String prefix : prefixes) {
            int index = value.indexOf(prefix);
            if (index >= 0) {
                String result = value.substring(index + prefix.length()).trim();
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }

    private static String extractRawAfterAny(String raw, String... prefixes) {
        if (raw == null) return "";
        String normalizedRaw = normalize(raw);
        for (String prefix : prefixes) {
            String normalizedPrefix = normalize(prefix);
            if (!normalizedRaw.startsWith(normalizedPrefix)) continue;
            String[] words = raw.trim().split("\\s+", 2);
            if (words.length == 2) return words[1].trim();
        }
        return "";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
    }

    @Override public void onDestroy() {
        runningState = false;
        listeningState = false;
        voiceStatus = "detenido";
        activeSkillState = "";
        mainHandler.removeCallbacksAndMessages(null);
        if (bargeInDetector != null) bargeInDetector.stop();
        cancelListening();
        destroySpeechRecognizer();
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) { }
            reader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) { }
            projection = null;
        }
        if (textRecognizer != null) {
            try { textRecognizer.close(); } catch (Exception ignored) { }
        }
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
        }
        AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
        if (ui != null) ui.updateOverlay("Asistente detenido.");
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }

    private static final class VisionTarget {
        final String label;
        final float x;
        final float y;
        VisionTarget(String label, float x, float y) {
            this.label = label;
            this.x = x;
            this.y = y;
        }
    }
}
