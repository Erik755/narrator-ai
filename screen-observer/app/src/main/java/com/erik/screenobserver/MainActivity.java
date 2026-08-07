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

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_MIC = 2002;

    private static final int GREEN = Color.rgb(46, 125, 50);
    private static final int RED = Color.rgb(198, 40, 40);
    private static final int ORANGE = Color.rgb(239, 108, 0);
    private static final int BLUE = Color.rgb(21, 101, 192);
    private static final int GRAY = Color.rgb(189, 189, 189);
    private static final int DARK_GRAY = Color.rgb(97, 97, 97);

    private MediaProjectionManager projectionManager;
    private TextView status;
    private TextView accessStatus;
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
            if (resumed) uiHandler.postDelayed(this, 600);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 48, 36, 36);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Screen Observer Pro 1.4");
        title.setTextSize(27);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView info = new TextView(this);
        info.setText("Asistente de pantalla con voz, habilidades y control mediante Accesibilidad. "
                + "La voz automática está desactivada: solo habla cuando le haces una pregunta.");
        info.setTextSize(15);
        info.setPadding(0, 18, 0, 16);
        root.addView(info);

        accessibilityButton = new Button(this);
        accessibilityButton.setOnClickListener(v -> openAccessibilityControl());
        root.addView(accessibilityButton);

        accessStatus = new TextView(this);
        accessStatus.setPadding(0, 6, 0, 14);
        root.addView(accessStatus);

        startButton = new Button(this);
        startButton.setOnClickListener(v -> startWithPermissions());
        root.addView(startButton);

        overlayButton = new Button(this);
        overlayButton.setOnClickListener(v -> toggleOverlay());
        root.addView(overlayButton);

        listenButton = new Button(this);
        listenButton.setOnClickListener(v -> sendServiceAction(ScreenCaptureService.ACTION_TOGGLE_LISTENING));
        root.addView(listenButton);

        controlsButton = new Button(this);
        controlsButton.setText("DECIRME QUÉ CONTROLES VE");
        controlsButton.setOnClickListener(v -> sendServiceAction(ScreenCaptureService.ACTION_DESCRIBE_CONTROLS));
        root.addView(controlsButton);

        stopButton = new Button(this);
        stopButton.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenCaptureService.class));
            status.setText("Estado: detenido");
            uiHandler.postDelayed(this::refreshUi, 250);
        });
        root.addView(stopButton);

        status = new TextView(this);
        status.setText("Estado: detenido");
        status.setPadding(0, 20, 0, 0);
        root.addView(status);

        TextView route = new TextView(this);
        route.setText("Motorola / Android 16: el primer botón intenta abrir directamente el detalle de "
                + "“Screen Observer Pro — Control de pantalla”. Si Motorola no admite ese acceso directo, "
                + "abre Accesibilidad para seleccionarlo manualmente. Si aparece “Ajuste restringido”, "
                + "abre Información de la app y usa ⋮ → Permitir ajustes restringidos.");
        route.setTextSize(13);
        route.setPadding(0, 18, 0, 0);
        root.addView(route);

        Button appInfo = new Button(this);
        appInfo.setText("ABRIR INFORMACIÓN DE LA APP");
        appInfo.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        root.addView(appInfo);

        TextView hints = new TextView(this);
        hints.setText("Prueba de voz: di “¿me escuchas?”.\n"
                + "También: “¿qué ves?”, “¿qué botones ves?”, “aprende pintura”, "
                + "“pulsa Continuar”, “escribe hola”, “desplázate abajo” o “ve atrás”.");
        hints.setTextSize(13);
        hints.setPadding(0, 18, 0, 0);
        root.addView(hints);

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
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
        ComponentName component = new ComponentName(this, UIControlService.class);
        Intent detail = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        detail.putExtra(Intent.EXTRA_COMPONENT_NAME, component);

        try {
            if (detail.resolveActivity(getPackageManager()) != null) {
                startActivity(detail);
            } else {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {
                status.setText("No pude abrir Accesibilidad. Ábrela desde Ajustes.");
            }
        }

        status.setText("Activa “Screen Observer Pro — Control de pantalla” y regresa.");
    }

    private void toggleOverlay() {
        if (!ScreenCaptureService.isRunning()) {
            status.setText("Estado: inicia primero el monitoreo.");
            return;
        }
        UIControlService ui = UIControlService.getInstance();
        if (ui == null) {
            status.setText("Activa primero Control de pantalla en Accesibilidad.");
            openAccessibilityControl();
            return;
        }
        sendServiceAction(ui.isOverlayVisible()
                ? ScreenCaptureService.ACTION_HIDE_OVERLAY
                : ScreenCaptureService.ACTION_SHOW_OVERLAY);
    }

    private void refreshUi() {
        if (status == null) return;

        boolean access = UIControlService.isRunning();
        boolean running = ScreenCaptureService.isRunning();
        boolean listening = ScreenCaptureService.isListeningEnabled();
        UIControlService ui = UIControlService.getInstance();
        boolean overlay = ui != null && ui.isOverlayVisible();

        style(accessibilityButton,
                access ? "1. CONTROL DE PANTALLA: ACTIVO" : "1. ACTIVAR CONTROL DE PANTALLA",
                access ? GREEN : RED,
                true);

        accessStatus.setText(access
                ? "Control de pantalla: activo."
                : "Control de pantalla: pendiente. Necesario para pulsar, escribir y desplazar.");

        style(startButton,
                running ? "2. MONITOREO: ACTIVO" : "2. INICIAR MONITOREO + ESCUCHA",
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

        style(controlsButton, "DECIRME QUÉ CONTROLES VE",
                access && running ? BLUE : GRAY,
                access && running);

        style(stopButton, running ? "DETENER ASISTENTE" : "ASISTENTE DETENIDO",
                running ? RED : GRAY,
                running);

        if (running) {
            status.setText("Estado: activo · " + ScreenCaptureService.getVoiceStatus());
        } else if (!status.getText().toString().contains("permiso")
                && !status.getText().toString().contains("Activa")
                && !status.getText().toString().contains("pude")) {
            status.setText("Estado: detenido");
        }
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
        Intent intent = new Intent(this, ScreenCaptureService.class);
        intent.setAction(action);
        try {
            startService(intent);
            uiHandler.postDelayed(this::refreshUi, 250);
        } catch (Exception e) {
            status.setText("Estado: inicia primero el asistente.");
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
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
            Intent service = new Intent(this, ScreenCaptureService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("data", data);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("Estado: iniciando asistente...");
            uiHandler.postDelayed(this::refreshUi, 600);
            moveTaskToBack(true);
        } else if (requestCode == REQ_CAPTURE) {
            status.setText("Estado: permiso de captura rechazado.");
        }
    }
}
