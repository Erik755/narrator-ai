package com.erik.screenobserver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
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
    private TextView accessStatus;
    private boolean pendingCaptureAfterMic = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 52, 36, 36);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Screen Observer Pro 1.3");
        title.setTextSize(27);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Asistente de pantalla con voz, memoria de habilidades e interacción mediante Accesibilidad. Aprende temas bajo demanda usando fuentes gratuitas de Wikimedia y conserva lo aprendido en el teléfono.");
        info.setTextSize(15);
        info.setPadding(0, 20, 0, 18);
        root.addView(info);

        Button accessibility = new Button(this);
        accessibility.setText("1. Activar Control de pantalla (Accesibilidad)");
        accessibility.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            status.setText("Estado: activa “Screen Observer Pro — Control de pantalla” y regresa");
        });
        root.addView(accessibility);

        accessStatus = new TextView(this);
        accessStatus.setPadding(0, 6, 0, 14);
        root.addView(accessStatus);

        Button start = new Button(this);
        start.setText("2. Iniciar monitoreo + escucha");
        start.setOnClickListener(v -> startWithPermissions());
        root.addView(start);

        Button showOverlay = new Button(this);
        showOverlay.setText("Mostrar mini ventana");
        showOverlay.setOnClickListener(v -> sendServiceAction(ScreenCaptureService.ACTION_SHOW_OVERLAY));
        root.addView(showOverlay);

        Button hideOverlay = new Button(this);
        hideOverlay.setText("Ocultar mini ventana");
        hideOverlay.setOnClickListener(v -> sendServiceAction(ScreenCaptureService.ACTION_HIDE_OVERLAY));
        root.addView(hideOverlay);

        Button listen = new Button(this);
        listen.setText("Activar / desactivar escucha");
        listen.setOnClickListener(v -> sendServiceAction(ScreenCaptureService.ACTION_TOGGLE_LISTENING));
        root.addView(listen);

        Button controls = new Button(this);
        controls.setText("Decirme qué controles ve");
        controls.setOnClickListener(v -> sendServiceAction(ScreenCaptureService.ACTION_DESCRIBE_CONTROLS));
        root.addView(controls);

        Button stop = new Button(this);
        stop.setText("Detener asistente");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            status.setText("Estado: detenido");
        });
        root.addView(stop);

        status = new TextView(this);
        status.setText("Estado: detenido");
        status.setPadding(0, 22, 0, 0);
        root.addView(status);

        TextView hints = new TextView(this);
        hints.setText("Ejemplos por voz:\n• “Aprende pintura” o “investiga soldadura TIG”.\n• “¿Qué habilidades tienes?” / “usa la habilidad pintura”.\n• “¿Qué ves?” / “¿qué botones ves?”.\n• “Pulsa Continuar”, “toca Aceptar”, “escribe hola”, “desplázate abajo”, “ve atrás”.\n• “Oculta la ventana” / “muestra la ventana”.");
        hints.setTextSize(13);
        hints.setPadding(0, 20, 0, 0);
        root.addView(hints);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }
        updateAccessStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        updateAccessStatus();
    }

    private void updateAccessStatus() {
        if (accessStatus == null) return;
        accessStatus.setText(UIControlService.isRunning()
                ? "Control de pantalla: ACTIVO"
                : "Control de pantalla: pendiente. Es necesario para pulsar, escribir, desplazar y mostrar la mini ventana sin permiso de superposición.");
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

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, ScreenCaptureService.class);
        intent.setAction(action);
        try {
            startService(intent);
        } catch (Exception ignored) {
            status.setText("Estado: inicia primero el asistente");
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
                status.setText("Estado: el micrófono es necesario para recibir órdenes por voz");
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
            status.setText("Estado: asistente activo");
            moveTaskToBack(true);
        } else if (requestCode == REQ_CAPTURE) {
            status.setText("Estado: permiso de captura rechazado");
        }
    }
}
