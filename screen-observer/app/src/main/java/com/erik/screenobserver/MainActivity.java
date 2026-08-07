package com.erik.screenobserver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private MediaProjectionManager projectionManager;
    private TextView status;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 70, 40, 40);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Screen Observer Pro");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Analiza la pantalla localmente y entrega descripción y consejos por voz y notificaciones prioritarias. No requiere permiso para mostrarse sobre otras apps, API ni suscripción.");
        info.setTextSize(16);
        info.setPadding(0, 30, 0, 30);
        root.addView(info);

        Button start = new Button(this);
        start.setText("Iniciar monitoreo");
        start.setOnClickListener(v -> startCapture());
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("Detener monitoreo");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            status.setText("Estado: detenido");
        });
        root.addView(stop);

        status = new TextView(this);
        status.setText("Estado: detenido");
        status.setPadding(0, 30, 0, 0);
        root.addView(status);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }

    private void startCapture() {
        status.setText("Estado: solicitando permiso de captura...");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("Estado: monitoreando pantalla");
            moveTaskToBack(true);
        } else if (requestCode == REQ_CAPTURE) {
            status.setText("Estado: permiso de captura rechazado");
        }
    }
}
