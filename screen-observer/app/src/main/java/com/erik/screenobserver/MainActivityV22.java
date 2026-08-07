package com.erik.screenobserver;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivityV22 extends Activity {
    private static final int REQ_CAPTURE = 4201, REQ_MIC = 4202;
    private static final int GREEN = Color.rgb(46,125,50), RED = Color.rgb(198,40,40),
            ORANGE = Color.rgb(239,108,0), BLUE = Color.rgb(21,101,192),
            GRAY = Color.rgb(189,189,189), DARK = Color.rgb(97,97,97);

    private MediaProjectionManager projectionManager;
    private TextView status, accessStatus, skillStatus;
    private Button accessibility, start, overlay, listen, controls, stop;
    private boolean pendingCapture = false, resumed = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() { refresh(); if (resumed) handler.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 44, 34, 34);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Screen Observer Pro 2.2");
        title.setTextSize(27);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Agente local sin costo obligatorio. Incluye habilidad precargada de Android 15/16, comprensión contextual, control por Accesibilidad + OCR y modo silencioso.");
        info.setTextSize(15);
        info.setPadding(0, 16, 0, 14);
        root.addView(info);

        accessibility = new Button(this);
        accessibility.setOnClickListener(v -> openAccessibility());
        root.addView(accessibility);
        accessStatus = new TextView(this);
        accessStatus.setPadding(0, 5, 0, 12);
        root.addView(accessStatus);

        start = new Button(this);
        start.setOnClickListener(v -> startPermissions());
        root.addView(start);

        overlay = new Button(this);
        overlay.setOnClickListener(v -> toggleOverlay());
        root.addView(overlay);

        listen = new Button(this);
        listen.setOnClickListener(v -> send(ScreenAgentService22.ACTION_TOGGLE_LISTENING));
        root.addView(listen);

        controls = new Button(this);
        controls.setOnClickListener(v -> send(ScreenAgentService22.ACTION_DESCRIBE_CONTROLS));
        root.addView(controls);

        stop = new Button(this);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenAgentService22.class));
            status.setText("Estado: detenido");
            handler.postDelayed(this::refresh, 200);
        });
        root.addView(stop);

        status = new TextView(this);
        status.setText("Estado: detenido");
        status.setPadding(0, 18, 0, 0);
        root.addView(status);

        skillStatus = new TextView(this);
        skillStatus.setPadding(0, 8, 0, 0);
        root.addView(skillStatus);

        TextView capabilities = new TextView(this);
        capabilities.setText("Ejemplos: “abre Ajustes”, “abre WhatsApp”, “abre notificaciones”, “abre ajustes rápidos”, “bloquea la pantalla”, “continúa”, “acepta”, “mantén presionado…”, “escribe…”, “qué controles ves”, “aprende ajedrez”.");
        capabilities.setTextSize(13);
        capabilities.setPadding(0, 16, 0, 0);
        root.addView(capabilities);

        TextView openAiNote = new TextView(this);
        openAiNote.setText("OpenAI: ChatGPT Plus no incluye crédito de API. Esta versión mantiene la comprensión local gratuita y no requiere una clave ni pagos adicionales.");
        openAiNote.setTextSize(12);
        openAiNote.setPadding(0, 14, 0, 0);
        root.addView(openAiNote);

        Button appInfo = new Button(this);
        appInfo.setText("ABRIR INFORMACIÓN DE LA APP");
        appInfo.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        root.addView(appInfo);

        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4203);
        }
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void openAccessibility() {
        ComponentName c = new ComponentName(this, AgentAccessibilityService.class);
        Intent d = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        d.putExtra(Intent.EXTRA_COMPONENT_NAME, c);
        try {
            if (d.resolveActivity(getPackageManager()) != null) startActivity(d);
            else startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception ignored) { status.setText("Abre Ajustes → Accesibilidad manualmente."); }
        }
        status.setText("Activa “Screen Observer Pro — Control de pantalla” y regresa.");
    }

    private void toggleOverlay() {
        if (!ScreenAgentService22.isRunning()) {
            status.setText("Inicia primero el asistente.");
            return;
        }
        AgentAccessibilityService a = AgentAccessibilityService.getInstance();
        if (a == null) {
            status.setText("Activa Control de pantalla en Accesibilidad.");
            openAccessibility();
            return;
        }
        send(a.isOverlayVisible() ? ScreenAgentService22.ACTION_HIDE_OVERLAY : ScreenAgentService22.ACTION_SHOW_OVERLAY);
    }

    private void refresh() {
        if (status == null) return;
        boolean access = AgentAccessibilityService.isRunning();
        boolean running = ScreenAgentService22.isRunning();
        boolean listening = ScreenAgentService22.isListeningEnabled();
        AgentAccessibilityService a = AgentAccessibilityService.getInstance();
        boolean ov = a != null && a.isOverlayVisible();

        style(accessibility,
                access ? "1. CONTROL DE PANTALLA: ACTIVO" : "1. ACTIVAR CONTROL DE PANTALLA",
                access ? GREEN : RED, true);
        accessStatus.setText(access ? "Control de pantalla: activo · Accesibilidad + OCR disponibles."
                : "Control de pantalla: pendiente.");
        style(start, running ? "2. ASISTENTE: ACTIVO" : "2. INICIAR MONITOREO + ESCUCHA",
                running ? GREEN : BLUE, !running);
        style(overlay, ov ? "MINI VENTANA: VISIBLE · TOCAR PARA OCULTAR"
                        : "MINI VENTANA: OCULTA · TOCAR PARA MOSTRAR",
                ov ? GREEN : DARK, running);
        style(listen, listening ? "ESCUCHA: ACTIVA · TOCAR PARA PAUSAR"
                        : "ESCUCHA: PAUSADA · TOCAR PARA ACTIVAR",
                listening ? GREEN : ORANGE, running);
        style(controls, "DECIRME QUÉ CONTROLES VE", access && running ? BLUE : GRAY, access && running);
        style(stop, running ? "DETENER ASISTENTE" : "ASISTENTE DETENIDO", running ? RED : GRAY, running);

        status.setText(running ? "Estado: activo · " + ScreenAgentService22.getVoiceStatus() : "Estado: detenido");
        String sk = ScreenAgentService22.getActiveSkillState();
        skillStatus.setText(sk == null || sk.isEmpty()
                ? "Habilidades: Android 15 y 16 precargada."
                : "Habilidad contextual: " + sk + " · Android 15/16 también disponible.");
    }

    private void style(Button b, String text, int bg, boolean enabled) {
        b.setText(text);
        b.setEnabled(enabled);
        b.setBackgroundTintList(ColorStateList.valueOf(bg));
        b.setTextColor(bg == GRAY ? Color.BLACK : Color.WHITE);
    }

    private void startPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingCapture = true;
            status.setText("Solicitando permiso de micrófono…");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startCapture();
    }

    private void startCapture() {
        status.setText("Solicitando permiso para ver la pantalla…");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void send(String action) {
        Intent i = new Intent(this, ScreenAgentService22.class);
        i.setAction(action);
        try {
            startService(i);
            handler.postDelayed(this::refresh, 200);
        } catch (Exception e) {
            status.setText("Inicia primero el asistente.");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] p, int[] g) {
        super.onRequestPermissionsResult(requestCode, p, g);
        if (requestCode == REQ_MIC) {
            boolean ok = g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED;
            if (ok && pendingCapture) {
                pendingCapture = false;
                startCapture();
            } else {
                pendingCapture = false;
                status.setText("Sin micrófono no puedo escuchar órdenes.");
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent s = new Intent(this, ScreenAgentService22.class);
            s.putExtra("resultCode", resultCode);
            s.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
            status.setText("Iniciando asistente…");
            handler.postDelayed(this::refresh, 500);
            moveTaskToBack(true);
        } else if (requestCode == REQ_CAPTURE) {
            status.setText("Permiso de captura rechazado.");
        }
    }
}
