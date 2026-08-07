package com.erik.screenobserver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_MIC = 2002;
    private MediaProjectionManager projectionManager;
    private TextView status;
    private boolean pendingCaptureAfterMic = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Screen Observer Pro 1.2");
        title.setTextSize(27);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Monitorea la pantalla, escucha peticiones por voz y responde usando el contenido detectado. La burbuja flotante es pequeña, arrastrable y completamente opcional.");
        info.setTextSize(15);
        info.setPadding(0, 24, 0, 24);
        root.addView(info);

        Button start = new Button(this);
        start.setText("Iniciar monitoreo + escucha");
        start.setOnClickListener(v -> startWithPermissions());
        root.addView(start);

        Button overlayPermission = new Button(this);
        overlayPermission.setText("Activar permiso de burbuja (opcional)");
        overlayPermission.setOnClickListener(v -> requestOverlayPermission());
        root.addView(overlayPermission);

        Button showOverlay = new Button(this);
        showOverlay.setText("Mostrar burbuja");
        showOverlay.setOnClickListener(v -> sendServiceAction(ScreenCaptureService.ACTION_SHOW_OVERLAY));
        root.addView(showOverlay);

        Button hideOverlay = new Button(this);
        hideOverlay.setText("Ocultar burbuja");
        hideOverlay.setOnClickListener(v -> sendServiceAction(ScreenCaptureService.ACTION_HIDE_OVERLAY));
        root.addView(hideOverlay);

        Button listen = new Button(this);
        listen.setText("Activar / desactivar escucha");
        listen.setOnClickListener(v -> sendServiceAction(ScreenCaptureService.ACTION_TOGGLE_LISTENING));
        root.addView(listen);

        Button stop = new Button(this);
        stop.setText("Detener monitoreo");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            status.setText("Estado: detenido");
        });
        root.addView(stop);

        status = new TextView(this);
        status.setText("Estado: detenido");
        status.setPadding(0, 26, 0, 0);
        root.addView(status);

        TextView hints = new TextView(this);
        hints.setText("Puedes decir: “¿qué ves?”, “¿qué me recomiendas?”, “describe la pantalla”, “oculta la burbuja”, “muestra la burbuja”, “silencio” o “detén el monitoreo”.");
        hints.setTextSize(13);
        hints.setPadding(0, 24, 0, 0);
        root.addView(hints);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
    }

    private void startWithPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingCaptureAfterMic = true;
            status.setText("Estado: solicitando permiso de micrófono...");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startCapture();
    }

    private void startCapture() {
        status.setText("Estado: solicitando permiso de captura...");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            status.setText("Estado: permiso de burbuja ya activo");
            sendServiceAction(ScreenCaptureService.ACTION_SHOW_OVERLAY);
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        startActivity(intent);
        status.setText("Estado: la burbuja es opcional; el monitoreo funciona aunque Android niegue este permiso");
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, ScreenCaptureService.class);
        intent.setAction(action);
        try {
            startService(intent);
        } catch (Exception ignored) {
            status.setText("Estado: inicia primero el monitoreo");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && pendingCaptureAfterMic) {
                pendingCaptureAfterMic = false;
                startCapture();
            } else {
                pendingCaptureAfterMic = false;
                status.setText("Estado: el micrófono es necesario para escuchar tus peticiones");
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            status.setText("Estado: monitoreando y escuchando");
            moveTaskToBack(true);
        } else if (requestCode == REQ_CAPTURE) {
            status.setText("Estado: permiso de captura rechazado");
        }
    }
}
