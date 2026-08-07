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
    private static final String CHANNEL_ADVICE = "screen_observer_advice";
    private static final int FOREGROUND_ID = 7;
    private static final int ADVICE_ID = 8;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private ImageReader reader;
    private TextRecognizer textRecognizer;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private SkillManager skillManager;

    private long lastProcess = 0;
    private long lastAutoSpeak = 0;
    private String lastText = "";
    private String lastAdvice = "Esperando cambios en pantalla.";
    private boolean listeningEnabled = true;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean autoSpeech = true;
    private String pendingDangerousTarget = "";
    private long pendingDangerousUntil = 0;

    @Override public void onCreate() {
        super.onCreate();
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
            if (ui != null) ui.showOverlay(); else speakDirect("Activa primero Control de pantalla en Accesibilidad.");
            refreshForegroundNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE_OVERLAY.equals(action)) {
            if (!ensureRunning()) return START_NOT_STICKY;
            UIControlService ui = UIControlService.getInstance();
            if (ui != null) ui.hideOverlay();
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
            describeControls();
            return START_NOT_STICKY;
        }

        if (projection != null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra("data", Intent.class);
        else data = intent.getParcelableExtra("data");
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, mainHandler);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.max(360, dm.widthPixels / 2);
        int height = Math.max(640, dm.heightPixels / 2);
        int density = Math.max(160, dm.densityDpi / 2);
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, mainHandler);
        projection.createVirtualDisplay(
                "ScreenObserver",
                width,
                height,
                density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(),
                null,
                null);

        UIControlService ui = UIControlService.getInstance();
        if (ui != null) ui.updateOverlay("Asistente activo. Di una orden.");
        startListeningSoon(500);
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
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "MX"));
                tts.setSpeechRate(1.06f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {
                        isSpeaking = true;
                        cancelListening();
                    }

                    @Override public void onDone(String utteranceId) {
                        isSpeaking = false;
                        startListeningSoon(400);
                    }

                    @Override public void onError(String utteranceId) {
                        isSpeaking = false;
                        startListeningSoon(400);
                    }
                });
            }
        });
    }

    private void setupSpeechRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                isListening = true;
                updateOverlay("🎙 Escuchando…");
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { isListening = false; }
            @Override public void onError(int error) {
                isListening = false;
                if (listeningEnabled && !isSpeaking && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    startListeningSoon(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 900 : 450);
                }
            }
            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) handleVoiceCommand(matches.get(0));
                if (!isSpeaking) startListeningSoon(400);
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX");
        speechIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-MX");
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        speechIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
    }

    private final Runnable startListeningRunnable = () -> {
        if (!listeningEnabled || isSpeaking || isListening || speechRecognizer == null || speechIntent == null) return;
        try {
            speechRecognizer.cancel();
            speechRecognizer.startListening(speechIntent);
        } catch (Exception ignored) { }
    };

    private void startListeningSoon(long delayMs) {
        mainHandler.removeCallbacks(startListeningRunnable);
        if (!listeningEnabled || isSpeaking || speechRecognizer == null || speechIntent == null) return;
        mainHandler.postDelayed(startListeningRunnable, delayMs);
    }

    private void cancelListening() {
        mainHandler.removeCallbacks(startListeningRunnable);
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) { }
        }
        isListening = false;
    }

    private void toggleListening() {
        listeningEnabled = !listeningEnabled;
        if (!listeningEnabled) {
            cancelListening();
            updateOverlay("🎙 Escucha pausada");
            speakDirect("Escucha pausada. El análisis de pantalla continúa.");
        } else {
            updateOverlay("🎙 Escucha activa");
            speakDirect("Escucha activada.");
        }
    }

    private void handleVoiceCommand(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String command = normalize(raw);
        updateOverlay("Oí: " + compact(raw, 80));

        if (containsAny(command, "oculta la ventana", "oculta la burbuja", "ocultar ventana", "esconde la ventana", "quita la ventana")) {
            UIControlService ui = UIControlService.getInstance();
            if (ui != null) ui.hideOverlay();
            speakDirect("Ventana oculta. El asistente sigue activo.");
            return;
        }
        if (containsAny(command, "muestra la ventana", "muestra la burbuja", "mostrar ventana", "ensena la ventana")) {
            UIControlService ui = UIControlService.getInstance();
            if (ui != null) {
                ui.showOverlay();
                speakDirect("Ventana visible.");
            } else speakDirect("Activa Control de pantalla en Accesibilidad para usar la ventana flotante.");
            return;
        }
        if (containsAny(command, "deten el monitoreo", "detener monitoreo", "deten el asistente", "para el asistente", "termina el asistente")) {
            speakDirect("Deteniendo el asistente.");
            mainHandler.postDelayed(this::stopSelf, 900);
            return;
        }
        if (containsAny(command, "deja de escuchar", "desactiva escucha", "pausa escucha", "no me escuches")) {
            listeningEnabled = false;
            speakDirect("Dejo de escuchar. El análisis visual continúa.");
            refreshForegroundNotification();
            return;
        }
        if (containsAny(command, "silencio", "no hables", "sin voz", "desactiva avisos de voz")) {
            autoSpeech = false;
            speakDirect("Avisos automáticos por voz desactivados.");
            return;
        }
        if (containsAny(command, "activa avisos de voz", "habla automaticamente", "activa la voz", "vuelve a hablar")) {
            autoSpeech = true;
            speakDirect("Avisos automáticos por voz activados.");
            return;
        }

        String learnTopic = extractAfterAny(command,
                "investiga y aprende ", "investiga sobre ", "aprende habilidad de ", "aprende la habilidad de ", "aprende sobre ", "aprende ");
        if (!learnTopic.isEmpty()) {
            learnSkill(learnTopic);
            return;
        }
        if (containsAny(command, "que habilidades tienes", "cuales son tus habilidades", "lista tus habilidades")) {
            String skills = skillManager.listSkillNames();
            if (skills.isEmpty()) speakDirect("Todavía no tengo habilidades investigadas. Puedes decir: aprende pintura, por ejemplo.");
            else speakDirect("Tengo estas habilidades guardadas: " + skills + ".");
            return;
        }
        String useSkill = extractAfterAny(command, "usa la habilidad ", "activa la habilidad ", "usa habilidad ");
        if (!useSkill.isEmpty()) {
            if (skillManager.setActiveSkill(useSkill)) speakDirect("Habilidad " + useSkill + " activada.");
            else speakDirect("Aún no tengo esa habilidad. Puedes decir: aprende " + useSkill + ".");
            return;
        }
        String forgetSkill = extractAfterAny(command, "olvida la habilidad ", "borra la habilidad ", "olvida habilidad ");
        if (!forgetSkill.isEmpty()) {
            if (skillManager.deleteSkill(forgetSkill)) speakDirect("Habilidad " + forgetSkill + " eliminada.");
            else speakDirect("No encuentro esa habilidad guardada.");
            return;
        }
        String learnedSkill = extractAfterAny(command, "que aprendiste de ", "que sabes de la habilidad ", "fuentes de ");
        if (!learnedSkill.isEmpty()) {
            String notes = skillManager.getSkillNotes(learnedSkill);
            if (notes.isEmpty()) speakDirect("No tengo esa habilidad guardada.");
            else speakDirect("De " + learnedSkill + " tengo estas referencias: " + summarizeNotes(notes, 520));
            return;
        }

        if (containsAny(command, "que botones ves", "que controles ves", "que puedo pulsar", "que puedo tocar")) {
            describeControls();
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
            } else speakDirect("No hay una acción sensible pendiente con ese nombre.");
            return;
        }

        String clickTarget = extractAfterAny(command, "haz clic en ", "pulsa el boton ", "pulsa ", "toca ", "presiona ", "oprime ");
        if (!clickTarget.isEmpty()) {
            if (isSensitiveTarget(clickTarget)) {
                pendingDangerousTarget = clickTarget;
                pendingDangerousUntil = SystemClock.elapsedRealtime() + 15000;
                speakDirect("Ese control puede producir una acción importante. Si quieres ejecutarla, di: confirma pulsa " + clickTarget + ".");
            } else performClick(clickTarget, false);
            return;
        }

        String textToWrite = extractRawAfterAny(raw, "escribe ", "introduce ", "ingresa ");
        if (!textToWrite.isEmpty()) {
            UIControlService ui = UIControlService.getInstance();
            if (ui == null) speakDirect("Activa Control de pantalla en Accesibilidad para escribir.");
            else if (ui.setFocusedText(textToWrite)) speakDirect("Texto introducido.");
            else speakDirect("No encuentro un campo de texto editable. Toca primero el campo y vuelve a decirme qué escribir.");
            return;
        }

        if (containsAny(command, "desplazate abajo", "desplaza hacia abajo", "desliza hacia abajo", "baja la pantalla", "scroll abajo")) {
            performScroll(true);
            return;
        }
        if (containsAny(command, "desplazate arriba", "desplaza hacia arriba", "desliza hacia arriba", "sube la pantalla", "scroll arriba")) {
            performScroll(false);
            return;
        }
        if (containsAny(command, "ve atras", "regresa", "boton atras")) {
            UIControlService ui = UIControlService.getInstance();
            if (ui != null && ui.back()) speakDirect("Listo."); else speakDirect("No pude ejecutar Atrás.");
            return;
        }
        if (containsAny(command, "ve al inicio", "pantalla de inicio", "ve a home")) {
            UIControlService ui = UIControlService.getInstance();
            if (ui != null && ui.home()) speakDirect("Listo."); else speakDirect("No pude ir al inicio.");
            return;
        }
        if (containsAny(command, "abre recientes", "muestra recientes", "aplicaciones recientes")) {
            UIControlService ui = UIControlService.getInstance();
            if (ui != null && ui.recents()) speakDirect("Listo."); else speakDirect("No pude abrir recientes.");
            return;
        }

        if (containsAny(command, "que ves", "describe la pantalla", "que hay en pantalla", "dime que ves", "describe esto")) {
            speakDirect(describe(lastText));
            return;
        }
        if (containsAny(command, "lee la pantalla", "lee esto", "leeme la pantalla")) {
            if (lastText.isEmpty()) speakDirect("No detecto texto legible en este momento.");
            else speakDirect("Leo en pantalla: " + compact(lastText, 520));
            return;
        }
        if (containsAny(command, "que hago", "que debo hacer", "que me recomiendas", "aconsejame", "cual elijo", "que opcion")) {
            speakDirect(adviceWithSkill(raw));
            return;
        }

        speakDirect(answerGeneralRequest(raw));
    }

    private void learnSkill(String topic) {
        topic = topic.trim();
        if (topic.length() < 2) {
            speakDirect("Dime qué habilidad quieres que investigue.");
            return;
        }
        final String skill = topic;
        updateOverlay("Investigando: " + compact(skill, 45));
        speakDirect("Voy a investigar y guardar la habilidad " + skill + ".");
        ResearchEngine.research(skill, new ResearchEngine.Callback() {
            @Override public void onSuccess(String notes, JSONArray sources) {
                skillManager.saveSkill(skill, notes, sources);
                String summary = summarizeNotes(notes, 430);
                updateOverlay("Aprendida: " + skill + "\n" + compact(summary, 120));
                speakDirect("Habilidad " + skill + " guardada. Encontré " + sources.length()
                        + " referencias. Resumen: " + summary);
            }

            @Override public void onError(String message) {
                updateOverlay("No pude aprender: " + skill);
                speakDirect(message);
            }
        });
    }

    private void performClick(String target, boolean confirmed) {
        UIControlService ui = UIControlService.getInstance();
        if (ui == null) {
            speakDirect("Activa Control de pantalla en Accesibilidad para que pueda pulsar controles.");
            return;
        }
        if (ui.clickText(target)) speakDirect((confirmed ? "Confirmado. " : "") + "Pulsé " + target + ".");
        else speakDirect("No encontré un control accesible llamado " + target + ". Puedes preguntarme qué botones veo.");
    }

    private void performScroll(boolean down) {
        UIControlService ui = UIControlService.getInstance();
        if (ui == null) speakDirect("Activa Control de pantalla en Accesibilidad para desplazar la pantalla.");
        else if (ui.scroll(down)) speakDirect("Listo.");
        else speakDirect("Esta pantalla no expone un área desplazable.");
    }

    private void describeControls() {
        UIControlService ui = UIControlService.getInstance();
        if (ui == null) speakDirect("Activa Control de pantalla en Accesibilidad para leer y manejar botones.");
        else speakDirect(ui.listInteractiveElements());
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
                    publishAdvice(describe(text), lastAdvice);
                    updateOverlay(shortOverlay(lastAdvice));
                    long t = SystemClock.elapsedRealtime();
                    if (autoSpeech && t - lastAutoSpeak > 7000 && !isSpeaking) {
                        lastAutoSpeak = t;
                        speakDirect(lastAdvice);
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
        return 1f - ((float) common / max) > 0.10f;
    }

    private String describe(String text) {
        if (text == null || text.isEmpty()) return "No detecto texto legible en este momento.";
        return "Veo en pantalla: " + compact(text, 300);
    }

    private String adviceWithSkill(String request) {
        String base = advise(lastText);
        String relevant = skillManager.findRelevantSkill(request == null ? "" : request);
        if (relevant == null || relevant.isEmpty()) return base;
        String notes = skillManager.getSkillNotes(relevant);
        if (notes.isEmpty()) return base;
        return "Usando la habilidad " + relevant + ": " + summarizeNotes(notes, 300) + ". En esta pantalla: " + base;
    }

    private String advise(String text) {
        if (text == null) text = "";
        String t = normalize(text);
        if (t.contains("error") || t.contains("failed") || t.contains("fallo"))
            return "Apareció un error. Revisa el mensaje completo antes de continuar.";
        if (t.contains("warning") || t.contains("advertencia") || t.contains("alerta"))
            return "Hay una advertencia visible. Conviene leerla antes de continuar.";
        if (t.contains("continuar") || t.contains("continue") || t.contains("siguiente") || t.contains("next"))
            return "Veo una opción para avanzar. Verifica que la información actual sea correcta antes de pulsarla.";
        if (t.contains("cancelar") || t.contains("cancel"))
            return "Hay una opción de cancelar si la acción actual no coincide con tu objetivo.";
        if (text.isEmpty())
            return "No tengo suficiente texto visible para recomendar una acción concreta.";
        return "La pantalla cambió. Puedo describirla, decirte qué controles hay o pulsar un control si me indicas su nombre.";
    }

    private String answerGeneralRequest(String request) {
        String skill = skillManager.findRelevantSkill(request);
        String skillNotes = skill == null ? "" : skillManager.getSkillNotes(skill);
        String screen = lastText.isEmpty() ? "no detecto texto legible" : compact(lastText, 190);
        if (!skillNotes.isEmpty()) {
            return "Entendí: " + request + ". Con la habilidad " + skill + " tengo esta referencia: "
                    + summarizeNotes(skillNotes, 360) + ". En pantalla " + screen + ".";
        }
        return "Entendí: " + request + ". En pantalla " + screen + ". " + advise(lastText);
    }

    private void speakDirect(String value) {
        if (value == null || value.trim().isEmpty() || tts == null) return;
        cancelListening();
        tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "screen_observer_" + SystemClock.elapsedRealtime());
    }

    private void updateOverlay(String text) {
        UIControlService ui = UIControlService.getInstance();
        if (ui != null) ui.updateOverlay(text);
    }

    private void publishAdvice(String description, String advice) {
        String body = description + "\n\n" + advice;
        Notification notification = new Notification.Builder(this, CHANNEL_ADVICE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Screen Observer Pro — análisis")
                .setContentText(compact(advice, 110))
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(ADVICE_ID, notification);
    }

    private Notification buildForegroundNotification() {
        PendingIntent toggleListen = serviceAction(ACTION_TOGGLE_LISTENING, 101);
        PendingIntent show = serviceAction(ACTION_SHOW_OVERLAY, 102);
        PendingIntent controls = serviceAction(ACTION_DESCRIBE_CONTROLS, 103);

        return new Notification.Builder(this, CHANNEL_CAPTURE)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Screen Observer Pro 1.3")
                .setContentText(listeningEnabled ? "Pantalla + voz activas" : "Pantalla activa; escucha pausada")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(0, listeningEnabled ? "Pausar voz" : "Activar voz", toggleListen).build())
                .addAction(new Notification.Action.Builder(0, "Ventana", show).build())
                .addAction(new Notification.Action.Builder(0, "Controles", controls).build())
                .build();
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, ScreenCaptureService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private void refreshForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(FOREGROUND_ID, buildForegroundNotification());
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel capture = new NotificationChannel(
                    CHANNEL_CAPTURE,
                    "Asistente activo",
                    NotificationManager.IMPORTANCE_LOW);
            capture.setDescription("Mantiene activos captura, voz y habilidades");
            nm.createNotificationChannel(capture);

            NotificationChannel advice = new NotificationChannel(
                    CHANNEL_ADVICE,
                    "Consejos de pantalla",
                    NotificationManager.IMPORTANCE_HIGH);
            advice.setDescription("Descripción y recomendaciones detectadas en pantalla");
            nm.createNotificationChannel(advice);
        }
    }

    private boolean isSensitiveTarget(String target) {
        String t = normalize(target);
        return containsAny(t,
                "pagar", "comprar", "transferir", "enviar dinero", "confirmar compra",
                "eliminar cuenta", "borrar cuenta", "borrar datos", "desinstalar", "restablecer", "factory reset");
    }

    private String summarizeNotes(String notes, int max) {
        if (notes == null || notes.isEmpty()) return "sin notas todavía";
        String clean = notes.replaceAll("\\[[^]]+\\]", " ")
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.length() <= max) return clean;
        int cut = clean.lastIndexOf('.', max);
        if (cut < max / 2) cut = max;
        return clean.substring(0, cut).trim() + "…";
    }

    private String shortOverlay(String value) {
        String active = skillManager.getActiveSkillName();
        String prefix = active.isEmpty() ? "" : "Habilidad: " + active + "\n";
        return prefix + compact(value, 150);
    }

    private static String compact(String value, int max) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return compact.length() > max ? compact.substring(0, max) + "…" : compact;
    }

    private static boolean containsAny(String value, String... options) {
        for (String option : options) if (value.contains(option)) return true;
        return false;
    }

    private static String extractAfterAny(String value, String... prefixes) {
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
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            int index = lower.indexOf(prefix.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                String result = raw.substring(index + prefix.length()).trim();
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalized.replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        cancelListening();
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) { }
        }
        if (reader != null) reader.close();
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) { }
        }
        if (textRecognizer != null) textRecognizer.close();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) {
        return null;
    }
}
