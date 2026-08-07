package com.erik.screenobserver;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.speech.tts.TextToSpeech;
import android.view.*;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Locale;

public class ScreenCaptureService extends Service {
    private static final String CHANNEL = "screen_observer_capture";
    private MediaProjection projection;
    private ImageReader reader;
    private WindowManager windowManager;
    private TextView overlay;
    private TextToSpeech tts;
    private TextRecognizer recognizer;
    private long lastProcess = 0;
    private String lastText = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Screen Observer Pro")
                .setContentText("Monitoreando la pantalla localmente")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        startForeground(7, notification);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(new Locale("es", "MX"));
        });
        createOverlay();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (projection != null) return START_NOT_STICKY;
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");
        if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY; }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(Looper.getMainLooper()));

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.max(360, dm.widthPixels / 2);
        int height = Math.max(640, dm.heightPixels / 2);
        int density = dm.densityDpi;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImage, new Handler(Looper.getMainLooper()));
        projection.createVirtualDisplay("ScreenObserver", width, height, density,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);
        return START_NOT_STICKY;
    }

    private void onImage(ImageReader imageReader) {
        long now = SystemClock.elapsedRealtime();
        Image image = imageReader.acquireLatestImage();
        if (image == null) return;
        if (now - lastProcess < 650) { image.close(); return; }
        lastProcess = now;
        Bitmap bitmap = imageToBitmap(image);
        image.close();
        if (bitmap == null) return;

        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(result -> {
                    String text = result.getText() == null ? "" : result.getText().trim();
                    if (!meaningfulChange(text)) return;
                    lastText = text;
                    String description = describe(text);
                    String advice = advise(text);
                    updateOverlay(description + "\n\n" + advice);
                    speak(advice);
                });
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
        return 1f - ((float) common / max) > 0.08f;
    }

    private String describe(String text) {
        if (text.isEmpty()) return "Descripción: no detecto texto legible en este momento.";
        String compact = text.replace('\n', ' ').replaceAll("\\s+", " ");
        if (compact.length() > 220) compact = compact.substring(0, 220) + "…";
        return "Descripción: veo texto en pantalla: “" + compact + "”";
    }

    private String advise(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("error") || t.contains("failed") || t.contains("fallo"))
            return "Consejo: apareció un error. Revisa el mensaje completo antes de continuar y evita confirmar acciones irreversibles.";
        if (t.contains("warning") || t.contains("advertencia") || t.contains("alerta"))
            return "Consejo: hay una advertencia visible. Léela antes de continuar.";
        if (t.contains("continue") || t.contains("continuar") || t.contains("next") || t.contains("siguiente"))
            return "Consejo: parece haber una opción para avanzar. Verifica primero que la información actual sea correcta.";
        if (t.contains("cancel") || t.contains("cancelar"))
            return "Consejo: hay una opción de cancelar; úsala si la acción actual no coincide con lo que deseas hacer.";
        if (text.isEmpty()) return "Consejo: mantengo el monitoreo; aún no hay suficiente información textual para recomendar una acción.";
        return "Consejo: la pantalla cambió. Revisa las opciones visibles y prioriza la acción que coincida con tu objetivo; seguiré avisando cuando detecte cambios relevantes.";
    }

    private void createOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new TextView(this);
        overlay.setText("Screen Observer Pro\nEsperando cambios…");
        overlay.setTextColor(0xFFFFFFFF);
        overlay.setTextSize(13);
        overlay.setPadding(22, 16, 22, 16);
        overlay.setBackgroundColor(0xD9000000);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - 40, 760),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        p.y = 90;
        try { windowManager.addView(overlay, p); } catch (Exception ignored) {}
    }

    private void updateOverlay(String value) {
        if (overlay != null) overlay.setText(value);
    }

    private void speak(String value) {
        if (tts != null && value != null) tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "screen_advice");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override public void onDestroy() {
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (recognizer != null) recognizer.close();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (overlay != null && windowManager != null) try { windowManager.removeView(overlay); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
