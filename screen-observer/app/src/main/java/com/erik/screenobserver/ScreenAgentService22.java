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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Screen Observer Pro 2.2: local intent agent + built-in Android 15/16 operating skill. */
public class ScreenAgentService22 extends Service {
    public static final String ACTION_SHOW_OVERLAY = "com.erik.screenobserver.v22.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.erik.screenobserver.v22.HIDE_OVERLAY";
    public static final String ACTION_TOGGLE_LISTENING = "com.erik.screenobserver.v22.TOGGLE_LISTENING";
    public static final String ACTION_DESCRIBE_CONTROLS = "com.erik.screenobserver.v22.DESCRIBE_CONTROLS";

    private static final String CHANNEL = "screen_observer_v22";
    private static final int FOREGROUND_ID = 36;

    private static volatile boolean runningState = false;
    private static volatile boolean listeningState = false;
    private static volatile String voiceStatus = "detenido";
    private static volatile String activeSkillState = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<VisionTarget> visionTargets = new ArrayList<>();

    private MediaProjection projection;
    private ImageReader reader;
    private TextRecognizer ocr;
    private SpeechRecognizer recognizer;
    private Intent recognitionIntent;
    private TextToSpeech tts;
    private BargeInDetector barge;
    private SkillManager skills;

    private boolean listeningEnabled = true;
    private boolean listening = false;
    private boolean speaking = false;
    private boolean ttsPendingStart = false;
    private boolean ttsReady = false;
    private boolean bargeInterrupted = false;
    private boolean autoLearning = false;
    private int speechErrors = 0;

    private long ignoreUntil = 0;
    private long lastProcess = 0;
    private String lastText = "";
    private String pendingSensitive = "";
    private long pendingSensitiveUntil = 0;
    private int captureW = 1, captureH = 1, screenW = 1, screenH = 1;

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
        startForeground(FOREGROUND_ID, notification());
        skills = new SkillManager(this);
        activeSkillState = skills.getActiveSkillName();
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

        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = m.getMediaProjection(result, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, main);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = Math.max(1, dm.widthPixels);
        screenH = Math.max(1, dm.heightPixels);
        captureW = Math.max(360, screenW / 2);
        captureH = Math.max(640, screenH / 2);
        int density = Math.max(160, dm.densityDpi / 2);
        reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, main);
        projection.createVirtualDisplay("ScreenObserver22", captureW, captureH, density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);

        voiceStatus = "escuchando";
        passiveOverlay();
        startListening(250);
        refreshNotification();
        return START_NOT_STICKY;
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            ttsReady = true;
            tts.setLanguage(new Locale("es", "MX"));
            tts.setSpeechRate(1.05f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    ttsPendingStart = false;
                    speaking = true;
                    bargeInterrupted = false;
                    voiceStatus = "respondiendo · puedes interrumpirme";
                    cancelListening();
                    if (barge != null) barge.start(ScreenAgentService22.this::interruptSpeech);
                    passiveOverlay();
                }
                @Override public void onDone(String id) { finishSpeech(false); }
                @Override public void onError(String id) { finishSpeech(false); }
                @Override public void onStop(String id, boolean interrupted) {
                    finishSpeech(interrupted || bargeInterrupted);
                }
            });
        });
    }

    private void interruptSpeech() {
        if (!speaking) return;
        bargeInterrupted = true;
        if (barge != null) barge.stop();
        try { if (tts != null) tts.stop(); } catch (Exception ignored) { }
        speaking = false;
        ttsPendingStart = false;
        ignoreUntil = SystemClock.elapsedRealtime() + 50;
        voiceStatus = "interrumpido · escuchando";
        passiveOverlay();
        startListening(70);
    }

    private void finishSpeech(boolean interrupted) {
        main.post(() -> {
            if (barge != null) barge.stop();
            speaking = false;
            ttsPendingStart = false;
            long delay = interrupted ? 80 : 550;
            ignoreUntil = SystemClock.elapsedRealtime() + delay;
            voiceStatus = listeningEnabled ? "escuchando" : "escucha pausada";
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
                    voiceStatus = "escuchando";
                    passiveOverlay();
                }
                @Override public void onBeginningOfSpeech() {
                    voiceStatus = "te estoy oyendo";
                    passiveOverlay();
                }
                @Override public void onRmsChanged(float v) { }
                @Override public void onBufferReceived(byte[] b) { }
                @Override public void onEndOfSpeech() {
                    listening = false;
                    voiceStatus = "entendiendo";
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
                        voiceStatus = "escuchando";
                        startListening(220);
                        return;
                    }
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
                    ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
                    if (matches != null && !matches.isEmpty()) {
                        IntentAgent.Result u = IntentAgent.interpret(matches, conf, activeSkillState, lastText);
                        voiceStatus = "entendí: " + u.type.name().toLowerCase(Locale.ROOT);
                        passiveOverlay();
                        dispatch(u);
                    } else {
                        voiceStatus = "escuchando";
                    }
                    if (!speaking && !ttsPendingStart) startListening(250);
                }
                @Override public void onPartialResults(Bundle b) {
                    ArrayList<String> p = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (p != null && !p.isEmpty()) {
                        voiceStatus = "oyendo: " + compact(p.get(0), 36);
                        passiveOverlay();
                    }
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
            voiceStatus = "escuchando";
            startListening(150);
        }
        passiveOverlay();
    }

    private void dispatch(IntentAgent.Result r) {
        if (r == null) return;
        AgentAccessibilityService a;
        switch (r.type) {
            case HEARING_CHECK:
                speak("Sí, te escucho y entendí la prueba.");
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
                silent("Escucha pausada.");
                break;
            case LEARN_SKILL:
                learn(r.argument);
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
            case LONG_CLICK:
                longClick(r.argument);
                break;
            case TYPE_TEXT:
                a = AgentAccessibilityService.getInstance();
                silent(a != null && a.setFocusedText(r.argument) ? "Texto introducido." : "No encuentro un campo editable enfocado.");
                break;
            case SCROLL_DOWN:
                scroll(true);
                break;
            case SCROLL_UP:
                scroll(false);
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
            case OPEN_SETTINGS:
                silent(AndroidAppController.openSettings(this) ? "Ajustes abiertos." : "No pude abrir Ajustes.");
                break;
            case OPEN_APP:
                silent(AndroidAppController.launchAppByLabel(this, r.argument)
                        ? "Abrí " + r.argument + "." : "No encontré una aplicación llamada " + r.argument + ".");
                break;
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
                    speak("Te oí decir: " + compact(r.raw, 80) + ". No entendí bien la intención. Dime qué quieres que haga con la pantalla.");
                } else {
                    speak(generalAnswer(r.raw));
                }
                break;
        }
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
            pendingSensitive = "";
            pendingSensitiveUntil = 0;
            click(targetToUse, true);
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
        if (!confirmed && sensitive(target)) {
            pendingSensitive = target;
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
        VisionTarget v = findVisionWithAliases(target);
        if (v != null) {
            float x = v.x * ((float) screenW / Math.max(1, captureW));
            float y = v.y * ((float) screenH / Math.max(1, captureH));
            if (a.tap(x, y)) {
                silent((confirmed ? "Confirmado. " : "") + "Pulsé visualmente " + target + ".");
                return;
            }
        }
        speak("No encontré ese control. Puedes preguntarme qué controles veo.");
    }

    private void longClick(String target) {
        AgentAccessibilityService a = AgentAccessibilityService.getInstance();
        if (a == null) {
            speak("Necesito Control de pantalla activo en Accesibilidad.");
            return;
        }
        for (String alias : AndroidSkillPack.aliasesForTarget(target)) {
            if (a.longClickText(alias)) {
                silent("Mantuve presionado " + alias + ".");
                return;
            }
        }
        speak("No encontré un control que admita pulsación larga con ese nombre.");
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
            return "Entendí tu petición. Con la habilidad " + skill + ", esto es lo más relevante: " + summarize(notes, 460);
        }
        return "Entendí lo que dijiste. Puedo abrir aplicaciones, manejar controles de Android, analizar la pantalla, aprender habilidades o ejecutar una acción que me indiques.";
    }

    private void onImage(ImageReader source) {
        long now = SystemClock.elapsedRealtime();
        Image image = source.acquireLatestImage();
        if (image == null) return;
        if (now - lastProcess < 650) {
            image.close();
            return;
        }
        lastProcess = now;
        Bitmap b = imageToBitmap(image);
        image.close();
        if (b == null) return;
        ocr.process(InputImage.fromBitmap(b, 0))
                .addOnSuccessListener(t -> {
                    b.recycle();
                    updateVision(t);
                    String text = t.getText() == null ? "" : t.getText().trim();
                    if (text.equals(lastText)) return;
                    lastText = text;
                    activateContext(text);
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
        try {
            int result = tts.speak(value, TextToSpeech.QUEUE_FLUSH, null,
                    "screen22_" + SystemClock.elapsedRealtime());
            if (result == TextToSpeech.ERROR) {
                ttsPendingStart = false;
                ignoreUntil = SystemClock.elapsedRealtime() + 300;
                startListening(300);
            }
        } catch (Exception e) {
            ttsPendingStart = false;
            ignoreUntil = SystemClock.elapsedRealtime() + 300;
            startListening(300);
        }
    }

    private void silent(String s) {
        voiceStatus = compact(s, 56);
        passiveOverlay();
    }

    private void passiveOverlay() {
        AgentAccessibilityService a = AgentAccessibilityService.getInstance();
        if (a == null || !a.isOverlayVisible()) return;
        String s = listeningEnabled ? "🎙 " + compact(voiceStatus, 38) : "🎙 Escucha pausada";
        if (!activeSkillState.isEmpty()) s += "\nHabilidad: " + compact(activeSkillState, 26);
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
        return b.setContentTitle("Screen Observer Pro 2.2")
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
                CHANNEL, "Asistente de pantalla 2.2", NotificationManager.IMPORTANCE_LOW));
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
        ttsPendingStart = false;
        main.removeCallbacksAndMessages(null);
        if (barge != null) barge.stop();
        cancelListening();
        destroyRecognizer();
        if (reader != null) try { reader.close(); } catch (Exception ignored) { }
        if (projection != null) try { projection.stop(); } catch (Exception ignored) { }
        if (ocr != null) try { ocr.close(); } catch (Exception ignored) { }
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
