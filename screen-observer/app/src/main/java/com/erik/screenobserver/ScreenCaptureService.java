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
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;

import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

public class ScreenCaptureService extends Service {
    public static final String ACTION_SHOW_OVERLAY = "com.erik.screenobserver.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.erik.screenobserver.HIDE_OVERLAY";
    public static final String ACTION_TOGGLE_LISTENING = "com.erik.screenobserver.TOGGLE_LISTENING";
    public static final String ACTION_DESCRIBE_CONTROLS = "com.erik.screenobserver.DESCRIBE_CONTROLS";

    private static final String CHANNEL_CAPTURE = "screen_observer_capture";
    private static final int FOREGROUND_ID = 7;

    private static volatile boolean runningState = false;
    private static volatile boolean listeningState = false;
    private static volatile String voiceStatus = "detenido";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProjection projection;
    private ImageReader reader;
    private TextRecognizer textRecognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private SkillManager skillManager;

    private long lastProcess = 0;
    private long ignoreRecognitionUntil = 0;
    private String lastText = "";
    private String lastAdvice = "Esperando cambios en pantalla.";
    private boolean listeningEnabled = true;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private int consecutiveSpeechErrors = 0;
    private String pendingDangerousTarget = "";
    private long pendingDangerousUntil = 0;

    public static boolean isRunning() {
        return runningState;
    }

    public static boolean isListeningEnabled() {
        return runningState && listeningState;
    }

    public static String getVoiceStatus() {
        return voiceStatus == null ? "" : voiceStatus;
    }

    @Override public void onCreate() {
        super.onCreate();
        runningState = true;
        listeningEnabled = true;
        listeningState = true;
        voiceStatus = "preparando micrófono";

        createChannels();
        startForeground(FOREGROUND_ID, buildForegroundNotification());

        skillManager = new SkillManager(this);
        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        setupTts();
        setupSpeechRecognition();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_SHOW_OVERLAY.equals(action)) {
            if (!ensureRunning()) return START_NOT_STICKY;
            UIControlService ui = UIControlService.getInstance();
            if (ui != null) {
                ui.showOverlay();
                silentStatus("Mini ventana visible.");
            } else {
                silentStatus("Activa Control de pantalla en Accesibilidad.");
            }
            refreshForegroundNotification();
            return START_NOT_STICKY;
        }

        if (ACTION_HIDE_OVERLAY.equals(action)) {
            if (!ensureRunning()) return START_NOT_STICKY;
            UIControlService ui = UIControlService.getInstance();
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
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra("data", Intent.class);
        } else {
            data = intent.getParcelableExtra("data");
        }

        if (resultCode != Activity.RESULT_OK || data == null) {
            silentStatus("No recibí permiso para capturar la pantalla.");
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                stopSelf();
            }
        }, mainHandler);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.max(360, dm.widthPixels / 2);
        int height = Math.max(640, dm.heightPixels / 2);
        int density = Math.max(160, dm.densityDpi / 2);

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(ScreenCaptureService.this::onImage, mainHandler);
        projection.createVirtualDisplay(
                "ScreenObserver",
                width,
                height,
                density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                null);

        silentStatus("Asistente activo. Estoy escuchando.");
        startListeningSoon(350);
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
            tts.setSpeechRate(1.04f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) {
                    isSpeaking = true;
                    voiceStatus = "respondiendo";
                    cancelListening();
                }

                @Override public void onDone(String utteranceId) {
                    isSpeaking = false;
                    ignoreRecognitionUntil = SystemClock.elapsedRealtime() + 1800;
                    voiceStatus = listeningEnabled ? "esperando para escuchar" : "escucha pausada";
                    startListeningSoon(1800);
                }

                @Override public void onError(String utteranceId) {
                    isSpeaking = false;
                    ignoreRecognitionUntil = SystemClock.elapsedRealtime() + 1800;
                    voiceStatus = listeningEnabled ? "esperando para escuchar" : "escucha pausada";
                    startListeningSoon(1800);
                }
            });
        });
    }

    private void setupSpeechRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            voiceStatus = "permiso de micrófono pendiente";
            listeningState = false;
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatus = "motor de reconocimiento de voz no disponible";
            listeningState = false;
            return;
        }

        createSpeechRecognizer();

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);

        // Do not force offline recognition. On some Motorola devices the Spanish
        // offline model is not installed, which makes the recognizer appear deaf.
        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);

        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 350L);
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
                    updateOverlay("🎙 Escuchando…");
                    refreshForegroundNotification();
                }

                @Override public void onBeginningOfSpeech() {
                    voiceStatus = "te estoy oyendo";
                    updateOverlay("🎙 Te estoy oyendo…");
                }

                @Override public void onRmsChanged(float rmsdB) { }

                @Override public void onBufferReceived(byte[] buffer) { }

                @Override public void onEndOfSpeech() {
                    isListening = false;
                    voiceStatus = "procesando lo que dijiste";
                }

                @Override public void onError(int error) {
                    isListening = false;
                    consecutiveSpeechErrors++;

                    if (!listeningEnabled || isSpeaking) return;

                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        listeningState = false;
                        voiceStatus = "sin permiso de micrófono";
                        updateOverlay("Micrófono sin permiso.");
                        return;
                    }

                    if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            || error == SpeechRecognizer.ERROR_NO_MATCH) {
                        voiceStatus = "escuchando";
                        startListeningSoon(250);
                        return;
                    }

                    voiceStatus = speechErrorName(error);
                    updateOverlay("Micrófono: " + voiceStatus);

                    if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                            || error == SpeechRecognizer.ERROR_CLIENT
                            || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                            || consecutiveSpeechErrors >= 4) {
                        mainHandler.postDelayed(() -> {
                            if (!listeningEnabled || isSpeaking) return;
                            createSpeechRecognizer();
                            startListeningSoon(650);
                        }, 700);
                    } else {
                        startListeningSoon(error == SpeechRecognizer.ERROR_NETWORK ? 1400 : 650);
                    }
                }

                @Override public void onResults(Bundle results) {
                    isListening = false;
                    consecutiveSpeechErrors = 0;

                    ArrayList<String> matches =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                    if (matches != null && !matches.isEmpty()) {
                        String heard = matches.get(0);
                        voiceStatus = "oí: " + compact(heard, 55);
                        updateOverlay("🎙 Oí: " + compact(heard, 70));
                        handleVoiceCommand(heard);
                    } else {
                        voiceStatus = "escuchando";
                    }

                    if (!isSpeaking) startListeningSoon(350);
                }

                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> partial =
                            partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (partial != null && !partial.isEmpty()) {
                        String heard = partial.get(0);
                        voiceStatus = "oyendo: " + compact(heard, 45);
                        updateOverlay("🎙 " + compact(heard, 70));
                    }
                }

                @Override public void onEvent(int eventType, Bundle params) { }
            });
        } catch (Exception e) {
            speechRecognizer = null;
            listeningState = false;
            voiceStatus = "no pude iniciar el reconocedor de voz";
        }
    }

    private final Runnable startListeningRunnable = () -> {
        if (!listeningEnabled || isSpeaking || isListening
                || speechRecognizer == null || speechIntent == null) return;

        long now = SystemClock.elapsedRealtime();
        if (now < ignoreRecognitionUntil) {
            startListeningSoon(ignoreRecognitionUntil - now + 50);
            return;
        }

        try {
            voiceStatus = "iniciando escucha";
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            voiceStatus = "reiniciando escucha";
            mainHandler.postDelayed(() -> {
                if (!listeningEnabled) return;
                createSpeechRecognizer();
                startListeningSoon(500);
            }, 500);
        }
    };

    private void startListeningSoon(long delayMs) {
        mainHandler.removeCallbacks(startListeningRunnable);
        if (!listeningEnabled || isSpeaking || speechRecognizer == null || speechIntent == null) {
            return;
        }
        mainHandler.postDelayed(startListeningRunnable, Math.max(100, delayMs));
    }

    private void cancelListening() {
        mainHandler.removeCallbacks(startListeningRunnable);
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) { }
        }
        isListening = false;
    }

    private void destroySpeechRecognizer() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) { }
            try {
                speechRecognizer.destroy();
            } catch (Exception ignored) { }
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
            silentStatus("🎙 Escucha pausada.");
        } else {
            if (speechRecognizer == null) createSpeechRecognizer();
            voiceStatus = "activando escucha";
            silentStatus("🎙 Escucha activa.");
            startListeningSoon(250);
        }
    }

    private void handleVoiceCommand(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String command = normalize(raw);

        if (containsAny(command,
                "me escuchas", "puedes escucharme", "puedes oirme", "me oyes", "estas escuchando")) {
            speakOnRequest("Sí, te escucho.");
            return;
        }

        if (containsAny(command,
                "oculta la ventana", "oculta la burbuja", "ocultar ventana",
                "esconde la ventana", "quita la ventana")) {
            UIControlService ui = UIControlService.getInstance();
            if (ui != null) ui.hideOverlay();
            silentStatus("Mini ventana oculta.");
            return;
        }

        if (containsAny(command,
                "muestra la ventana", "muestra la burbuja", "mostrar ventana",
                "ensena la ventana")) {
            UIControlService ui = UIControlService.getInstance();
            if (ui != null) {
                ui.showOverlay();
                silentStatus("Mini ventana visible.");
            } else {
                silentStatus("Activa Control de pantalla en Accesibilidad.");
            }
            return;
        }

        if (containsAny(command,
                "deten el monitoreo", "detener monitoreo", "deten el asistente",
                "para el asistente", "termina el asistente")) {
            silentStatus("Deteniendo el asistente.");
            mainHandler.postDelayed(this::stopSelf, 350);
            return;
        }

        if (containsAny(command,
                "deja de escuchar", "desactiva escucha", "pausa escucha", "no me escuches")) {
            listeningEnabled = false;
            listeningState = false;
            cancelListening();
            voiceStatus = "escucha pausada";
            silentStatus("Escucha pausada. El análisis visual continúa.");
            refreshForegroundNotification();
            return;
        }

        if (containsAny(command,
                "silencio", "no hables", "sin voz", "no respondas con voz")) {
            silentStatus("Modo silencioso activo. Solo hablaré cuando me hagas una pregunta.");
            return;
        }

        String learnTopic = extractAfterAny(
                command,
                "investiga y aprende ",
                "investiga sobre ",
                "aprende habilidad de ",
                "aprende la habilidad de ",
                "aprende sobre ",
                "aprende ");

        if (!learnTopic.isEmpty()) {
            learnSkill(learnTopic);
            return;
        }

        if (containsAny(command,
                "que habilidades tienes", "cuales son tus habilidades", "lista tus habilidades")) {
            String skills = skillManager.listSkillNames();
            speakOnRequest(skills.isEmpty()
                    ? "Todavía no tengo habilidades investigadas."
                    : "Tengo estas habilidades guardadas: " + skills + ".");
            return;
        }

        String useSkill = extractAfterAny(command,
                "usa la habilidad ", "activa la habilidad ", "usa habilidad ");
        if (!useSkill.isEmpty()) {
            if (skillManager.setActiveSkill(useSkill)) {
                silentStatus("Habilidad activa: " + useSkill);
            } else {
                silentStatus("No tengo esa habilidad. Di: aprende " + useSkill);
            }
            return;
        }

        String forgetSkill = extractAfterAny(command,
                "olvida la habilidad ", "borra la habilidad ", "olvida habilidad ");
        if (!forgetSkill.isEmpty()) {
            silentStatus(skillManager.deleteSkill(forgetSkill)
                    ? "Habilidad eliminada: " + forgetSkill
                    : "No encuentro esa habilidad.");
            return;
        }

        String learnedSkill = extractAfterAny(command,
                "que aprendiste de ", "que sabes de la habilidad ", "fuentes de ");
        if (!learnedSkill.isEmpty()) {
            String notes = skillManager.getSkillNotes(learnedSkill);
            speakOnRequest(notes.isEmpty()
                    ? "No tengo esa habilidad guardada."
                    : "De " + learnedSkill + " tengo estas referencias: "
                    + summarizeNotes(notes, 520));
            return;
        }

        if (containsAny(command,
                "que botones ves", "que controles ves", "que puedo pulsar", "que puedo tocar")) {
            describeControls(true);
            return;
        }

        String confirmTarget = extractAfterAny(command,
                "confirma pulsa ", "confirma toca ", "confirma presiona ");
        if (!confirmTarget.isEmpty()) {
            if (!pendingDangerousTarget.isEmpty()
                    && SystemClock.elapsedRealtime() < pendingDangerousUntil
                    && normalize(confirmTarget).contains(normalize(pendingDangerousTarget))) {
                performClick(confirmTarget, true);
                pendingDangerousTarget = "";
                pendingDangerousUntil = 0;
            } else {
                silentStatus("No hay una acción sensible pendiente con ese nombre.");
            }
            return;
        }

        String clickTarget = extractAfterAny(
                command,
                "haz clic en ",
                "pulsa el boton ",
                "pulsa ",
                "toca ",
                "presiona ",
                "oprime ");

        if (!clickTarget.isEmpty()) {
            if (isSensitiveTarget(clickTarget)) {
                pendingDangerousTarget = clickTarget;
                pendingDangerousUntil = SystemClock.elapsedRealtime() + 15000;
                silentStatus("Confirmación necesaria. Di: confirma pulsa " + clickTarget);
            } else {
                performClick(clickTarget, false);
            }
            return;
        }

        String textToWrite = extractRawAfterAny(raw, "escribe ", "introduce ", "ingresa ");
        if (!textToWrite.isEmpty()) {
            UIControlService ui = UIControlService.getInstance();
            if (ui == null) {
                silentStatus("Activa Control de pantalla para escribir.");
            } else if (ui.setFocusedText(textToWrite)) {
                silentStatus("Texto introducido.");
            } else {
                silentStatus("No encuentro un campo editable. Toca el campo y vuelve a intentarlo.");
            }
            return;
        }

        if (containsAny(command,
                "desplazate abajo", "desplaza hacia abajo", "desliza hacia abajo",
                "baja la pantalla", "scroll abajo")) {
            performScroll(true);
            return;
        }

        if (containsAny(command,
                "desplazate arriba", "desplaza hacia arriba", "desliza hacia arriba",
                "sube la pantalla", "scroll arriba")) {
            performScroll(false);
            return;
        }

        if (containsAny(command, "ve atras", "regresa", "boton atras")) {
            UIControlService ui = UIControlService.getInstance();
            silentStatus(ui != null && ui.back() ? "Atrás ejecutado." : "No pude ejecutar Atrás.");
            return;
        }

        if (containsAny(command, "ve al inicio", "pantalla de inicio", "ve a home")) {
            UIControlService ui = UIControlService.getInstance();
            silentStatus(ui != null && ui.home() ? "Inicio ejecutado." : "No pude ir al inicio.");
            return;
        }

        if (containsAny(command,
                "abre recientes", "muestra recientes", "aplicaciones recientes")) {
            UIControlService ui = UIControlService.getInstance();
            silentStatus(ui != null && ui.recents()
                    ? "Recientes abierto."
                    : "No pude abrir recientes.");
            return;
        }

        if (containsAny(command,
                "que ves", "describe la pantalla", "que hay en pantalla",
                "dime que ves", "describe esto")) {
            speakOnRequest(describe(lastText));
            return;
        }

        if (containsAny(command, "lee la pantalla", "lee esto", "leeme la pantalla")) {
            speakOnRequest(lastText.isEmpty()
                    ? "No detecto texto legible en este momento."
                    : "Leo en pantalla: " + compact(lastText, 520));
            return;
        }

        if (containsAny(command,
                "que hago", "que debo hacer", "que me recomiendas",
                "aconsejame", "cual elijo", "que opcion")) {
            speakOnRequest(adviceWithSkill(raw));
            return;
        }

        // An unrecognized spoken phrase is still a user request.
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
                silentStatus("Habilidad aprendida: " + skill
                        + " · " + sources.length() + " referencias.");
            }

            @Override public void onError(String message) {
                silentStatus("No pude aprender " + skill + ": " + message);
            }
        });
    }

    private void performClick(String target, boolean confirmed) {
        UIControlService ui = UIControlService.getInstance();
        if (ui == null) {
            silentStatus("Activa Control de pantalla en Accesibilidad.");
            return;
        }

        if (ui.clickText(target)) {
            silentStatus((confirmed ? "Confirmado. " : "") + "Pulsé " + target + ".");
        } else {
            silentStatus("No encontré un control accesible llamado " + target + ".");
        }
    }

    private void performScroll(boolean down) {
        UIControlService ui = UIControlService.getInstance();
        if (ui == null) {
            silentStatus("Activa Control de pantalla en Accesibilidad.");
        } else if (ui.scroll(down)) {
            silentStatus(down ? "Desplacé hacia abajo." : "Desplacé hacia arriba.");
        } else {
            silentStatus("Esta pantalla no expone un área desplazable.");
        }
    }

    private void describeControls(boolean speak) {
        UIControlService ui = UIControlService.getInstance();
        String result = ui == null
                ? "Activa Control de pantalla en Accesibilidad para leer y manejar botones."
                : ui.listInteractiveElements();

        if (speak) speakOnRequest(result);
        else silentStatus(result);
    }

    private void onImage(ImageReader imageReader) {
        long now = SystemClock.elapsedRealtime();
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;

        if (now - lastProcess < 700) {
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
                    String text = result.getText() == null ? "" : result.getText().trim();
                    if (!meaningfulChange(text)) return;

                    lastText = text;
                    lastAdvice = adviceWithSkill("");

                    // Never speak automatically. Only refresh the unobtrusive overlay.
                    UIControlService ui = UIControlService.getInstance();
                    if (ui != null && ui.isOverlayVisible()) {
                        ui.updateOverlay(shortOverlay(lastAdvice));
                    }
                })
                .addOnFailureListener(e -> bitmap.recycle());
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

            Bitmap cropped =
                    Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());

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

        return 1f - ((float) common / max) > 0.10f;
    }

    private String describe(String text) {
        if (text == null || text.isEmpty()) {
            return "No detecto texto legible en este momento.";
        }
        return "Veo en pantalla: " + compact(text, 300);
    }

    private String adviceWithSkill(String request) {
        String base = advise(lastText);
        String relevant = skillManager.findRelevantSkill(request == null ? "" : request);

        if (relevant == null || relevant.isEmpty()) return base;

        String notes = skillManager.getSkillNotes(relevant);
        if (notes.isEmpty()) return base;

        return "Usando la habilidad " + relevant + ": "
                + summarizeNotes(notes, 300)
                + ". En esta pantalla: " + base;
    }

    private String advise(String text) {
        if (text == null) text = "";
        String t = normalize(text);

        if (t.contains("error") || t.contains("failed") || t.contains("fallo")) {
            return "Apareció un error. Revisa el mensaje completo antes de continuar.";
        }
        if (t.contains("warning") || t.contains("advertencia") || t.contains("alerta")) {
            return "Hay una advertencia visible. Conviene leerla antes de continuar.";
        }
        if (t.contains("continuar") || t.contains("continue")
                || t.contains("siguiente") || t.contains("next")) {
            return "Veo una opción para avanzar. Verifica que la información actual sea correcta antes de pulsarla.";
        }
        if (t.contains("cancelar") || t.contains("cancel")) {
            return "Hay una opción de cancelar si la acción actual no coincide con tu objetivo.";
        }
        if (text.isEmpty()) {
            return "No tengo suficiente texto visible para recomendar una acción concreta.";
        }

        return "La pantalla cambió. Puedo describirla, decirte qué controles hay "
                + "o pulsar un control si me indicas su nombre.";
    }

    private String answerGeneralRequest(String request) {
        String skill = skillManager.findRelevantSkill(request);
        String skillNotes = skill == null ? "" : skillManager.getSkillNotes(skill);
        String screen = lastText.isEmpty()
                ? "no detecto texto legible"
                : compact(lastText, 190);

        if (!skillNotes.isEmpty()) {
            return "Entendí: " + request + ". Con la habilidad " + skill
                    + " tengo esta referencia: " + summarizeNotes(skillNotes, 360)
                    + ". En pantalla " + screen + ".";
        }

        return "Entendí: " + request + ". En pantalla " + screen + ". "
                + advise(lastText);
    }

    private void speakOnRequest(String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (tts == null || !ttsReady) {
            silentStatus("Entendí tu pregunta, pero la voz todavía se está preparando.");
            startListeningSoon(500);
            return;
        }

        cancelListening();
        ignoreRecognitionUntil = Long.MAX_VALUE;

        try {
            int result = tts.speak(
                    value,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "screen_observer_" + SystemClock.elapsedRealtime());
            if (result == TextToSpeech.ERROR) {
                isSpeaking = false;
                ignoreRecognitionUntil = SystemClock.elapsedRealtime() + 1200;
                startListeningSoon(1200);
            }
        } catch (Exception e) {
            isSpeaking = false;
            ignoreRecognitionUntil = SystemClock.elapsedRealtime() + 1200;
            startListeningSoon(1200);
        }
    }

    private void silentStatus(String value) {
        if (value == null) return;
        updateOverlay(value);
        voiceStatus = value.length() > 60 ? compact(value, 60) : value;
    }

    private void updateOverlay(String text) {
        UIControlService ui = UIControlService.getInstance();
        if (ui != null) ui.updateOverlay(text);
    }

    private Notification buildForegroundNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                101,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent listenIntent = new Intent(this, ScreenCaptureService.class);
        listenIntent.setAction(ACTION_TOGGLE_LISTENING);
        PendingIntent listenPending = PendingIntent.getService(
                this,
                102,
                listenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent overlayIntent = new Intent(this, ScreenCaptureService.class);
        UIControlService ui = UIControlService.getInstance();
        boolean overlayVisible = ui != null && ui.isOverlayVisible();
        overlayIntent.setAction(overlayVisible ? ACTION_HIDE_OVERLAY : ACTION_SHOW_OVERLAY);
        PendingIntent overlayPending = PendingIntent.getService(
                this,
                103,
                overlayIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder =
                Build.VERSION.SDK_INT >= 26
                        ? new Notification.Builder(this, CHANNEL_CAPTURE)
                        : new Notification.Builder(this);

        builder.setContentTitle("Screen Observer Pro")
                .setContentText(listeningEnabled
                        ? "Pantalla activa · micrófono escuchando · voz solo bajo petición"
                        : "Pantalla activa · escucha pausada")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(0, listeningEnabled ? "Pausar escucha" : "Activar escucha", listenPending)
                .addAction(0, overlayVisible ? "Ocultar ventana" : "Mostrar ventana", overlayPending);

        return builder.build();
    }

    private void refreshForegroundNotification() {
        if (!runningState) return;
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(FOREGROUND_ID, buildForegroundNotification());
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel capture = new NotificationChannel(
                CHANNEL_CAPTURE,
                "Asistente de pantalla",
                NotificationManager.IMPORTANCE_LOW);
        capture.setDescription("Mantiene activo el análisis de pantalla y la escucha del asistente.");
        nm.createNotificationChannel(capture);
    }

    private String speechErrorName(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "error de audio; reintentando";
            case SpeechRecognizer.ERROR_CLIENT:
                return "reiniciando reconocedor";
            case SpeechRecognizer.ERROR_NETWORK:
                return "sin red para el motor de voz; reintentando";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "la red del motor de voz tardó demasiado";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "reconocedor ocupado; reiniciando";
            case SpeechRecognizer.ERROR_SERVER:
                return "motor de voz temporalmente no disponible";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "motor de voz desconectado; reiniciando";
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                return "motor de voz limitó solicitudes; reintentando";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                return "español no admitido por el motor de voz";
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "modelo de español no disponible; usando reconocimiento normal";
            default:
                return "reintentando escucha";
        }
    }

    private boolean isSensitiveTarget(String target) {
        String t = normalize(target);
        return containsAny(
                t,
                "pagar",
                "pago",
                "comprar",
                "compra",
                "transferir",
                "transferencia",
                "enviar dinero",
                "borrar",
                "eliminar cuenta",
                "eliminar todo",
                "restablecer",
                "formatear");
    }

    private static String shortOverlay(String value) {
        if (value == null || value.isEmpty()) return "Pantalla actualizada.";
        return compact(value, 145);
    }

    private static String summarizeNotes(String value, int max) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (compact.length() > max) compact = compact.substring(0, max) + "…";
        return compact;
    }

    private static String compact(String value, int max) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (compact.length() > max) compact = compact.substring(0, max) + "…";
        return compact;
    }

    private static boolean containsAny(String value, String... options) {
        if (value == null) return false;
        for (String option : options) {
            if (value.contains(option)) return true;
        }
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
        String n = Normalizer.normalize(
                        value.toLowerCase(Locale.ROOT),
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9ñ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    @Override public void onDestroy() {
        runningState = false;
        listeningState = false;
        voiceStatus = "detenido";

        mainHandler.removeCallbacksAndMessages(null);
        cancelListening();
        destroySpeechRecognizer();

        if (reader != null) {
            try {
                reader.close();
            } catch (Exception ignored) { }
            reader = null;
        }

        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) { }
            projection = null;
        }

        if (textRecognizer != null) {
            try {
                textRecognizer.close();
            } catch (Exception ignored) { }
        }

        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) { }
        }

        UIControlService ui = UIControlService.getInstance();
        if (ui != null) ui.updateOverlay("Asistente detenido.");

        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) {
        return null;
    }
}
