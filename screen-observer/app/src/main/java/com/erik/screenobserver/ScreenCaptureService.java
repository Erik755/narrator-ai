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
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Locale;

public class ScreenCaptureService extends Service {
    private static final String CHANNEL_CAPTURE = "screen_observer_capture";
    private static final String CHANNEL_ADVICE = "screen_observer_advice";
    private static final int FOREGROUND_ID = 7;
    private static final int ADVICE_ID = 8;

    private MediaProjection projection;
    private ImageReader reader;
    private TextToSpeech tts;
    private TextRecognizer recognizer;
    private long lastProcess = 0;
    private String lastText = "";

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        Notification notification = new Notification.Builder(this, CHANNEL_CAPTURE)
                .setContentTitle("Screen Observer Pro")
                .setContentText("Monitoreando la pantalla localmente")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
        startForeground(FOREGROUND_ID, notification);

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("es", "MX"));
                tts.setSpeechRate(1.08f);
            }
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (projection != null) return START_NOT_STICKY;
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra("data", Intent.class);
        } else {
            data = intent.getParcelableExtra("data");
        }
        if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY; }

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopSelf(); }
        }, new Handler(Looper.getMainLooper()));

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int width = Math.max(360, dm.widthPixels / 2);
        int height = Math.max(640, dm.heightPixels / 2);
        int density = Math.max(160, dm.densityDpi / 2);
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
                    bitmap.recycle();
                    String text = result.getText() == null ? "" : result.getText().trim();
                    if (!meaningfulChange(text)) return;
                    lastText = text;
                    String description = describe(text);
                    String advice = advise(text);
                    publishAdvice(description, advice);
                    speak(description + ". " + advice);
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
        if (text.isEmpty()) return "Descripción: no detecto texto legible en este momento";
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
        if (text.isEmpty())
            return "Consejo: mantengo el monitoreo; aún no hay suficiente información textual para recomendar una acción.";
        return "Consejo: la pantalla cambió. Revisa las opciones visibles y prioriza la acción que coincida con tu objetivo; seguiré avisando cuando detecte cambios relevantes.";
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
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(ADVICE_ID, n);
        Toast.makeText(this, advice, Toast.LENGTH_SHORT).show();
    }

    private void speak(String value) {
        if (tts != null && value != null) {
            tts.speak(value, TextToSpeech.QUEUE_FLUSH, null, "screen_advice");
        }
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            NotificationChannel capture = new NotificationChannel(
                    CHANNEL_CAPTURE,
                    "Monitoreo de pantalla",
                    NotificationManager.IMPORTANCE_LOW);
            capture.setDescription("Mantiene activo el análisis local de pantalla");
            nm.createNotificationChannel(capture);

            NotificationChannel advice = new NotificationChannel(
                    CHANNEL_ADVICE,
                    "Consejos de pantalla",
                    NotificationManager.IMPORTANCE_HIGH);
            advice.setDescription("Muestra descripción y recomendaciones detectadas en pantalla");
            advice.enableVibration(true);
            nm.createNotificationChannel(advice);
        }
    }

    @Override public void onDestroy() {
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (recognizer != null) recognizer.close();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }
}
