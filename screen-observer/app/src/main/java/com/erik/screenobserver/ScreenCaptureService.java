package com.erik.screenobserver;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

public class ScreenCaptureService extends Service {
    public static final String ACTION_SHOW_OVERLAY = "com.erik.screenobserver.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.erik.screenobserver.HIDE_OVERLAY";
    public static final String ACTION_TOGGLE_LISTENING = "com.erik.screenobserver.TOGGLE_LISTENING";

    private static final String CHANNEL_CAPTURE = "screen_observer_capture";
    private static final String CHANNEL_ADVICE = "screen_observer_advice";
    private static final int FOREGROUND_ID = 7;
    private static final int ADVICE_ID = 8;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private ImageReader reader;
    private TextToSpeech tts;
    private TextRecognizer textRecognizer;
    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;

    private WindowManager windowManager;
    private LinearLayout overlayRoot;
    private TextView overlayText;
    private WindowManager.LayoutParams overlayParams;

    private long lastProcess = 0;
    private long lastAutoSpeak = 0;
    private String lastText = "";
    private String lastAdvice = "Esperando cambios…";
    private boolean listeningEnabled = true;
    private boolean isListening = false;
    private boolean isSpeaking = false;
    private boolean autoSpeech = true;

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(FOREGROUND_ID, buildForegroundNotification());

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        setupTts();
        setupSpeechRecognition();

        boolean shouldShow = getSharedPreferences("screen_observer", MODE_PRIVATE)
                .getBoolean("overlay_enabled", false);
        if (shouldShow && Settings.canDrawOverlays(this)) createOverlay();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_SHOW_OVERLAY.equals(action)) {
            if (projection == null) { stopSelf(); return START_NOT_STICKY; }
            showOverlay();
            refreshForegroundNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE_OVERLAY.equals(action)) {
            if (projection == null) { stopSelf(); return START_NOT_STICKY; }
            hideOverlay();
            refreshForegroundNotification();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_LISTENING.equals(action)) {
            if (projection == null) { stopSelf(); return START_NOT_STICKY; }
            toggleListening();
            refreshForegroundNotification();
            return START_NOT_STICKY;
        }

        if (projection != null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) data = intent.getParcelableExtra("data", Intent.class);
        else data = intent.getParcelableExtra("data");
        if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY; }

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
        projection.createVirtualDisplay("ScreenObserver", width, height, density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);

        startListeningSoon(500);
        refreshForegroundNotification();
        return START_NOT_STICKY;
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "MX"));
                tts.setSpeechRate(1.08f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { isSpeaking = true; }
                    @Override public void onDone(String utteranceId) {
                        isSpeaking = false;
                        startListeningSoon(450);
                    }
                    @Override public void onError(String utteranceId) {
                        isSpeaking = false;
                        startListeningSoon(450);
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
                updateOverlayStatus("🎙 Escuchando…");
            }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { isListening = false; }
            @Override public void onError(int error) {
                isListening = false;
                if (listeningEnabled && !isSpeaking && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    startListeningSoon(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 900 : 500);
                }
            }
            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) handleVoiceCommand(matches.get(0));
                if (!isSpeaking) startListeningSoon(450);
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

    private void startListeningSoon(long delayMs) {
        mainHandler.removeCallbacks(startListeningRunnable);
        if (!listeningEnabled || isSpeaking || speechRecognizer == null || speechIntent == null) return;
        mainHandler.postDelayed(startListeningRunnable, delayMs);
    }

    private final Runnable startListeningRunnable = () -> {
        if (!listeningEnabled || isSpeaking || isListening || speechRecognizer == null) return;
        try {
            speechRecognizer.cancel();
            speechRecognizer.startListening(speechIntent);
        } catch (Exception ignored) { }
    };

    private void toggleListening() {
        listeningEnabled = !listeningEnabled;
        if (!listeningEnabled) {
            mainHandler.removeCallbacks(startListeningRunnable);
            if (speechRecognizer != null) try { speechRecognizer.cancel(); } catch (Exception ignored) { }
            isListening = false;
            updateOverlayStatus("🎙 Escucha pausada");
            speakDirect("Escucha pausada. Puedes reactivarla desde la app o la notificación.");
        } else {
            updateOverlayStatus("🎙 Escucha activa");
            speakDirect("Escucha activada.");
        }
    }

    private void handleVoiceCommand(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String command = normalize(raw);

        if (containsAny(command, "oculta la burbuja", "ocultar burbuja", "oculta ventana", "quita la ventana", "esconde la burbuja")) {
            hideOverlay();
            speakDirect("Burbuja oculta. El monitoreo continúa.");
            refreshForegroundNotification();
            return;
        }
        if (containsAny(command, "muestra la burbuja", "mostrar burbuja", "muestra ventana", "ensena la burbuja")) {
            if (Settings.canDrawOverlays(this)) {
                showOverlay();
                speakDirect("Burbuja visible.");
            } else {
                speakDirect("Android no ha autorizado la burbuja. Abre Screen Observer Pro y activa el permiso opcional.");
            }
            refreshForegroundNotification();
            return;
        }
        if (containsAny(command, "deten el monitoreo", "detener monitoreo", "para el monitoreo", "termina monitoreo", "detente")) {
            speakDirect("Deteniendo el monitoreo.");
            mainHandler.postDelayed(this::stopSelf, 1200);
            return;
        }
        if (containsAny(command, "deja de escuchar", "desactiva escucha", "pausa escucha", "no me escuches")) {
            listeningEnabled = false;
            speakDirect("Dejo de escuchar. El análisis de pantalla continúa.");
            refreshForegroundNotification();
            return;
        }
        if (containsAny(command, "silencio", "no hables", "sin voz", "desactiva avisos de voz")) {
            autoSpeech = false;
            speakDirect("Avisos automáticos por voz desactivados. Seguiré respondiendo cuando me preguntes.");
            return;
        }
        if (containsAny(command, "activa avisos de voz", "habla automaticamente", "activa la voz", "vuelve a hablar")) {
            autoSpeech = true;
            speakDirect("Avisos automáticos por voz activados.");
            return;
        }
        if (containsAny(command, "que ves", "describe la pantalla", "que hay en pantalla", "dime que ves", "describe esto")) {
            speakDirect(describe(lastText));
            return;
        }
        if (containsAny(command, "lee la pantalla", "lee esto", "leeme la pantalla")) {
            if (lastText.isEmpty()) speakDirect("No detecto texto legible en este momento.");
            else speakDirect("Leo en pantalla: " + compact(lastText, 420));
            return;
        }
        if (containsAny(command, "que hago", "que debo hacer", "que me recomiendas", "aconsejame", "cual elijo", "que opcion")) {
            speakDirect(advise(lastText));
            return;
        }

        speakDirect(answerGeneralRequest(raw));
    }

    private String answerGeneralRequest(String request) {
        if (lastText.isEmpty()) {
            return "Te escuché: " + request + ". En este momento no detecto suficiente texto en la pantalla para responder con seguridad.";
        }
        String lower = normalize(request);
        if (lower.contains("error") || lower.contains("problema") || lower.contains("falla")) {
            return "Según lo que veo en pantalla: " + compact(lastText, 180) + ". " + advise(lastText);
        }
        if (lower.contains("boton") || lower.contains("opcion") || lower.contains("seleccion") || lower.contains("continuar")) {
            return "En la pantalla detecto estas referencias: " + compact(lastText, 220) + ". " + advise(lastText);
        }
        return "Entendí tu petición: " + request + ". Actualmente veo: " + compact(lastText, 180) + ". " + advise(lastText);
    }

    private void onImage(ImageReader imageReader) {
        long now = SystemClock.elapsedRealtime();
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;
        if (now - lastProcess < 700) { image.close(); return; }
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
                    String description = describe(text);
                    lastAdvice = advise(text);
                    publishAdvice(description, lastAdvice);
                    updateOverlayStatus(shortOverlayText(lastAdvice));

                    long t = SystemClock.elapsedRealtime();
                    if (autoSpeech && t - lastAutoSpeak > 5000) {
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
            Bitmap padded = Bitmap.createBitmap(image.getWidth() + rowPadding / pixelStride, image.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            if (padded != cropped) padded.recycle();
            return cropped;
        } catch (Exception e) { return null; }
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
        return "Veo en pantalla: " + compact(text, 260);
    }

    private String advise(String text) {
        if (text == null) text = "";
        String t = normalize(text);
        if (t.contains("error") || t.contains("failed") || t.contains("fallo"))
            return "Apareció un error. Revisa el mensaje completo antes de continuar y evita confirmar una acción irreversible.";
        if (t.contains("warning") || t.contains("advertencia") || t.contains("alerta"))
            return "Hay una advertencia visible. Léela antes de continuar.";
        if (t.contains("continue") || t.contains("continuar") || t.contains("next") || t.contains("siguiente"))
            return "Parece haber una opción para avanzar. Verifica primero que la información actual sea correcta.";
        if (t.contains("cancel") || t.contains("cancelar"))
            return "Hay una opción de cancelar. Úsala si la acción actual no coincide con lo que deseas hacer.";
        if (text.isEmpty())
            return "Mantengo el monitoreo, pero todavía no hay suficiente información textual para recomendar una acción.";
        return "La pantalla cambió. Revisa las opciones visibles y prioriza la que coincida con tu objetivo. Si quieres una recomendación concreta, pregúntame por voz.";
    }

    private void publishAdvice(String description, String advice) {
        String body = description + "\n\n" + advice;
        Notification n = new Notification.Builder(this, CHANNEL_ADVICE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Screen Observer Pro — análisis")
                .setContentText(advice)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
        getSystemService(NotificationManager.class).notify(ADVICE_ID, n);
    }

    private void speakDirect(String value) {
        if (tts == null || value == null || value.trim().isEmpty()) return;
        mainHandler.removeCallbacks(startListeningRunnable);
        if (speechRecognizer != null) try { speechRecognizer.cancel(); } catch (Exception ignored) { }
        isListening = false;
        isSpeaking = true;
        tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "screen_observer_reply_" + SystemClock.elapsedRealtime());
    }

    private void showOverlay() {
        getSharedPreferences("screen_observer", MODE_PRIVATE).edit().putBoolean("overlay_enabled", true).apply();
        if (!Settings.canDrawOverlays(this)) return;
        if (overlayRoot == null) createOverlay();
        else overlayRoot.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        getSharedPreferences("screen_observer", MODE_PRIVATE).edit().putBoolean("overlay_enabled", false).apply();
        if (overlayRoot != null) overlayRoot.setVisibility(View.GONE);
    }

    private void createOverlay() {
        if (!Settings.canDrawOverlays(this) || overlayRoot != null) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        overlayRoot = new LinearLayout(this);
        overlayRoot.setOrientation(LinearLayout.HORIZONTAL);
        overlayRoot.setGravity(Gravity.CENTER_VERTICAL);
        overlayRoot.setPadding(dp(8), dp(4), dp(4), dp(4));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xDD111111);
        bg.setCornerRadius(dp(16));
        overlayRoot.setBackground(bg);

        overlayText = new TextView(this);
        overlayText.setText("🎙 Escuchando…");
        overlayText.setTextColor(Color.WHITE);
        overlayText.setTextSize(12);
        overlayText.setMaxLines(2);
        overlayText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        overlayRoot.addView(overlayText, textLp);

        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(15);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setPadding(dp(4), 0, dp(4), 0);
        close.setOnClickListener(v -> hideOverlay());
        overlayRoot.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));

        overlayParams = new WindowManager.LayoutParams(
                dp(210),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_SECURE,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(12);
        overlayParams.y = dp(90);

        final int[] startX = new int[1];
        final int[] startY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];
        overlayText.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX[0] = overlayParams.x;
                    startY[0] = overlayParams.y;
                    touchX[0] = event.getRawX();
                    touchY[0] = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    overlayParams.x = startX[0] + (int) (event.getRawX() - touchX[0]);
                    overlayParams.y = startY[0] + (int) (event.getRawY() - touchY[0]);
                    try { windowManager.updateViewLayout(overlayRoot, overlayParams); } catch (Exception ignored) { }
                    return true;
                default:
                    return true;
            }
        });

        try { windowManager.addView(overlayRoot, overlayParams); }
        catch (Exception e) { overlayRoot = null; overlayText = null; }
    }

    private void updateOverlayStatus(String text) {
        if (overlayText != null && overlayRoot != null && overlayRoot.getVisibility() == View.VISIBLE) {
            overlayText.setText(text);
        }
    }

    private String shortOverlayText(String value) {
        if (value == null || value.isEmpty()) return "🎙 Escuchando…";
        String v = value.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (v.length() > 92) v = v.substring(0, 92) + "…";
        return "🎙 " + v;
    }

    private Notification buildForegroundNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent listenIntent = new Intent(this, ScreenCaptureService.class).setAction(ACTION_TOGGLE_LISTENING);
        PendingIntent listenPi = PendingIntent.getService(this, 21, listenIntent, piFlags);

        boolean overlayOn = overlayRoot != null && overlayRoot.getVisibility() == View.VISIBLE;
        Intent overlayIntent = new Intent(this, ScreenCaptureService.class)
                .setAction(overlayOn ? ACTION_HIDE_OVERLAY : ACTION_SHOW_OVERLAY);
        PendingIntent overlayPi = PendingIntent.getService(this, 22, overlayIntent, piFlags);

        return new Notification.Builder(this, CHANNEL_CAPTURE)
                .setContentTitle("Screen Observer Pro")
                .setContentText(listeningEnabled ? "Monitoreando • micrófono activo" : "Monitoreando • escucha pausada")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_btn_speak_now,
                        listeningEnabled ? "Pausar escucha" : "Activar escucha",
                        listenPi).build())
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_view,
                        overlayOn ? "Ocultar burbuja" : "Mostrar burbuja",
                        overlayPi).build())
                .build();
    }

    private void refreshForegroundNotification() {
        try { getSystemService(NotificationManager.class).notify(FOREGROUND_ID, buildForegroundNotification()); }
        catch (Exception ignored) { }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel capture = new NotificationChannel(
                    CHANNEL_CAPTURE, "Monitoreo de pantalla", NotificationManager.IMPORTANCE_LOW);
            capture.setDescription("Mantiene activo el análisis de pantalla y la escucha por voz");
            nm.createNotificationChannel(capture);

            NotificationChannel advice = new NotificationChannel(
                    CHANNEL_ADVICE, "Consejos de pantalla", NotificationManager.IMPORTANCE_DEFAULT);
            advice.setDescription("Descripción y recomendaciones detectadas en pantalla");
            nm.createNotificationChannel(advice);
        }
    }

    private String compact(String text, int maxLen) {
        if (text == null) return "";
        String compact = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (compact.length() > maxLen) compact = compact.substring(0, maxLen) + "…";
        return compact;
    }

    private String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "");
    }

    private boolean containsAny(String source, String... terms) {
        for (String term : terms) if (source.contains(normalize(term))) return true;
        return false;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (textRecognizer != null) textRecognizer.close();
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (Exception ignored) { }
        }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (overlayRoot != null && windowManager != null) {
            try { windowManager.removeView(overlayRoot); } catch (Exception ignored) { }
        }
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
