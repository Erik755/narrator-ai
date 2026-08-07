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

public class MainActivityV2 extends Activity {
    private static final int REQ_CAPTURE = 3001;
    private static final int REQ_MIC = 3002;

    private static final int GREEN = Color.rgb(46, 125, 50);
    private static final int RED = Color.rgb(198, 40, 40);
    private static final int ORANGE = Color.rgb(239, 108, 0);
    private static final int BLUE = Color.rgb(21, 101, 192);
    private static final int GRAY = Color.rgb(189, 189, 189);
    private static final int DARK_GRAY = Color.rgb(97, 97, 97);

    private MediaProjectionManager projectionManager;
    private TextView status;
    private TextView accessStatus;
    private TextView skillStatus;
    private Button accessibilityButton;
    private Button startButton;
    private Button overlayButton;
    private Button listenButton;
    private Button controlsButton;
    private Button stopButton;

    private boolean pendingCaptureAfterMic = false;
    private boolean resumed = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshUi();
            if (resumed) uiHandler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 44, 34, 34);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Screen Observer Pro 2.0");
        title.setTextSize(27);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Modo silencioso: no describe la pantalla ni habla por cambios automáticos. "
                + "Solo responde por voz cuando se lo pides. Mientras habla, puedes interrumpirla hablando.");
        info.setTextSize(15);
        info.setPadding(0, 16, 0, 14);
        root.addView(info);

        accessibilityButton = new Button(this);
        accessibilityButton.setOnClickListener(v -> openAccessibilityControl());
        root.addView(accessibilityButton);

        accessStatus = new TextView(this);
        accessStatus.setPadding(0, 5, 0, 12);
        root.addView(accessStatus);

        startButton = new Button(this);
        startButton.setOnClickListener(v -> startWithPermissions());
        root.addView(startButton);

        overlayButton = new Button(this);
        overlayButton.setOnClickListener(v -> toggleOverlay());
        root.addView(overlayButton);

        listenButton = new Button(this);
        listenButton.setOnClickListener(v -> sendServiceAction(ScreenAgentService.ACTION_TOGGLE_LISTENING));
        root.addView(listenButton);

        controlsButton = new Button(this);
        controlsButton.setOnClickListener(v -> sendServiceAction(ScreenAgentService.ACTION_DESCRIBE_CONTROLS));
        root.addView(controlsButton);

        stopButton = new Button(this);
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenAgentService.class));
            status.setText("Estado: detenido");
            uiHandler.postDelayed(this::refreshUi, 200);
        });
        root.addView(stopButton);

        status = new TextView(this);
        status.setText("Estado: detenido");
        status.setPadding(0, 18, 0, 0);
        root.addView(status);

        skillStatus = new TextView(this);
        skillStatus.setPadding(0, 8, 0, 0);
        root.addView(skillStatus);

        TextView route = new TextView(this);
        route.setText("Si Motorola bloquea Accesibilidad con “Ajuste restringido”: abre Información de la app, "
                + "toca ⋮ y permite ajustes restringidos. Después vuelve y activa “Screen Observer Pro — Control de pantalla”.");
        route.setTextSize(13);
        route.setPadding(0, 16, 0, 0);
        root.addView(route);

        Button appInfo = new Button(this);
        appInfo.setText("ABRIR INFORMACIÓN DE LA APP");
        appInfo.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        root.addView(appInfo);

        TextView hints = new TextView(this);
        hints.setText("Ejemplos: “¿me escuchas?”, “¿qué ves?”, “¿qué botones ves?”, “aprende ajedrez”, "
                + "“pulsa Continuar”, “escribe hola”, “¿qué me recomiendas?”. "
                + "Las acciones se ejecutan en silencio; las respuestas habladas aceptan interrupción.");
        hints.setTextSize(13);
        hints.setPadding(0, 16, 0, 0);
        root.addView(hints);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3003);
        }
        refreshUi();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        uiHandler.removeCallbacks(refreshRunnable);
        uiHandler.post(refreshRunnable);
    }

    @Override protected void onPause() {
        resumed = false;
        uiHandler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void openAccessibilityControl() {
        ComponentName component = new ComponentName(this, AgentAccessibilityService.class);
        Intent detail = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        detail.putExtra(Intent.EXTRA_COMPONENT_NAME, component);
        try {
            if (detail.resolveActivity(getPackageManager()) != null) startActivity(detail);
            else startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception ignored) { status.setText("No pude abrir Accesibilidad. Ábrela desde Ajustes."); }
        }
        status.setText("Activa “Screen Observer Pro — Control de pantalla” y regresa.");
    }

    private void toggleOverlay() {
        if (!ScreenAgentService.isRunning()) {
            status.setText("Estado: inicia primero el asistente.");
            return;
        }
        AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
        if (ui == null) {
            status.setText("Activa primero Control de pantalla en Accesibilidad.");
            openAccessibilityControl();
            return;
        }
        sendServiceAction(ui.isOverlayVisible()
                ? ScreenAgentService.ACTION_HIDE_OVERLAY
                : ScreenAgentService.ACTION_SHOW_OVERLAY);
    }

    private void refreshUi() {
        if (status == null) return;
        boolean access = AgentAccessibilityService.isRunning();
        boolean running = ScreenAgentService.isRunning();
        boolean listening = ScreenAgentService.isListeningEnabled();
        AgentAccessibilityService ui = AgentAccessibilityService.getInstance();
        boolean overlay = ui != null && ui.isOverlayVisible();

        style(accessibilityButton,
                access ? "1. CONTROL DE PANTALLA: ACTIVO" : "1. ACTIVAR CONTROL DE PANTALLA",
                access ? GREEN : RED,
                true);
        accessStatus.setText(access
                ? "Control de pantalla: activo. Accesibilidad + toque visual disponibles."
                : "Control de pantalla: pendiente. Necesario para pulsar, escribir y desplazar.");

        style(startButton,
                running ? "2. ASISTENTE: ACTIVO" : "2. INICIAR MONITOREO + ESCUCHA",
                running ? GREEN : BLUE,
                !running);

        style(overlayButton,
                overlay ? "MINI VENTANA: VISIBLE · TOCAR PARA OCULTAR"
                        : "MINI VENTANA: OCULTA · TOCAR PARA MOSTRAR",
                overlay ? GREEN : DARK_GRAY,
                running);

        style(listenButton,
                listening ? "ESCUCHA: ACTIVA · TOCAR PARA PAUSAR"
                        : "ESCUCHA: PAUSADA · TOCAR PARA ACTIVAR",
                listening ? GREEN : ORANGE,
                running);

        style(controlsButton,
                "DECIRME QUÉ CONTROLES VE",
                access && running ? BLUE : GRAY,
                access && running);

        style(stopButton,
                running ? "DETENER ASISTENTE" : "ASISTENTE DETENIDO",
                running ? RED : GRAY,
                running);

        if (running) status.setText("Estado: activo · " + ScreenAgentService.getVoiceStatus());
        else if (!status.getText().toString().contains("permiso")
                && !status.getText().toString().contains("Activa")
                && !status.getText().toString().contains("pude")) {
            status.setText("Estado: detenido");
        }

        String skill = ScreenAgentService.getActiveSkillState();
        skillStatus.setText(skill == null || skill.isEmpty()
                ? "Habilidad contextual: ninguna activa."
                : "Habilidad contextual activa: " + skill);
    }

    private void style(Button button, String text, int background, boolean enabled) {
        if (button == null) return;
        button.setText(text);
        button.setEnabled(enabled);
        button.setBackgroundTintList(ColorStateList.valueOf(background));
        button.setTextColor(background == GRAY ? Color.BLACK : Color.WHITE);
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
        status.setText("Estado: solicitando permiso para ver la pantalla...");
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, ScreenAgentService.class);
        intent.setAction(action);
        try {
            startService(intent);
            uiHandler.postDelayed(this::refreshUi, 200);
        } catch (Exception e) {
            status.setText("Estado: inicia primero el asistente.");
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
                status.setText("Estado: sin permiso de micrófono no puedo escuchar órdenes.");
            }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, ScreenAgentService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("Estado: iniciando asistente...");
            uiHandler.postDelayed(this::refreshUi, 500);
            moveTaskToBack(true);
        } else if (requestCode == REQ_CAPTURE) {
            status.setText("Estado: permiso de captura rechazado.");
        }
    }
}
