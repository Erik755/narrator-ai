package com.erik.screenobserver;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AgentAccessibilityService extends AccessibilityService {
    private static volatile AgentAccessibilityService instance;
    private static volatile String activePackageName = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private LinearLayout overlayRoot;
    private TextView overlayText;
    private WindowManager.LayoutParams overlayParams;
    private boolean overlayVisible = false;

    public static AgentAccessibilityService getInstance() { return instance; }
    public static boolean isRunning() { return instance != null; }
    public static String getActivePackageName() { return activePackageName == null ? "" : activePackageName; }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        boolean wanted = getSharedPreferences("screen_observer", MODE_PRIVATE)
                .getBoolean("overlay_enabled", true);
        if (wanted) showOverlay();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event != null && event.getPackageName() != null) {
            activePackageName = event.getPackageName().toString();
        }
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        removeOverlay();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public boolean clickText(String target) {
        if (target == null || target.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        String wanted = normalize(target);
        try {
            List<AccessibilityNodeInfo> direct = root.findAccessibilityNodeInfosByText(target.trim());
            if (direct != null) {
                for (AccessibilityNodeInfo node : direct) {
                    if (matchesNode(node, wanted) && clickNodeOrParent(node)) return true;
                }
            }
            AccessibilityNodeInfo fuzzy = findMatchingNode(root, wanted);
            return fuzzy != null && clickNodeOrParent(fuzzy);
        } finally {
            root.recycle();
        }
    }

    public boolean tap(float x, float y) {
        if (x < 0 || y < 0) return false;
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 65);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean setFocusedText(String value) {
        if (value == null) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null || !focused.isEditable()) focused = findEditable(root);
            if (focused == null) return false;
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } finally {
            root.recycle();
        }
    }

    public boolean scroll(boolean down) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            AccessibilityNodeInfo scrollable = findScrollable(root);
            if (scrollable == null) scrollable = root;
            return scrollable.performAction(down
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        } finally {
            root.recycle();
        }
    }

    public boolean back() { return performGlobalAction(GLOBAL_ACTION_BACK); }
    public boolean home() { return performGlobalAction(GLOBAL_ACTION_HOME); }
    public boolean recents() { return performGlobalAction(GLOBAL_ACTION_RECENTS); }

    public String listInteractiveElements() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "No puedo leer los controles de esta pantalla.";
        try {
            List<String> items = new ArrayList<>();
            collectInteractive(root, items, 14);
            if (items.isEmpty()) return "No encuentro controles accesibles. Puedo intentar localizarlos visualmente si tienen texto visible.";
            StringBuilder out = new StringBuilder("Controles visibles: ");
            for (String item : items) {
                if (out.length() > 20) out.append("; ");
                out.append(item);
            }
            return out.toString();
        } finally {
            root.recycle();
        }
    }

    public void showOverlay() {
        mainHandler.post(() -> {
            getSharedPreferences("screen_observer", MODE_PRIVATE).edit()
                    .putBoolean("overlay_enabled", true).apply();
            if (overlayRoot == null) createOverlay();
            overlayVisible = overlayRoot != null;
        });
    }

    public void hideOverlay() {
        mainHandler.post(() -> {
            getSharedPreferences("screen_observer", MODE_PRIVATE).edit()
                    .putBoolean("overlay_enabled", false).apply();
            removeOverlay();
            overlayVisible = false;
        });
    }

    public boolean isOverlayVisible() { return overlayVisible; }

    public void updateOverlay(String text) {
        mainHandler.post(() -> {
            if (overlayText != null && text != null) overlayText.setText(text);
        });
    }

    private void createOverlay() {
        if (overlayRoot != null) return;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xD91E1E1E);
        background.setCornerRadius(dp(13));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(7), dp(4), dp(7), dp(7));
        root.setBackground(background);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Asistente");
        title.setTextColor(Color.WHITE);
        title.setTextSize(11);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(28), 1f));

        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(13);
        close.setMinWidth(0);
        close.setMinHeight(0);
        close.setPadding(0, 0, 0, 0);
        close.setOnClickListener(v -> hideOverlay());
        header.addView(close, new LinearLayout.LayoutParams(dp(31), dp(28)));
        root.addView(header);

        TextView body = new TextView(this);
        body.setText("🎙 Listo");
        body.setTextColor(Color.WHITE);
        body.setTextSize(11);
        body.setMaxLines(3);
        body.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(body, new LinearLayout.LayoutParams(dp(168), WindowManager.LayoutParams.WRAP_CONTENT));

        overlayParams = new WindowManager.LayoutParams(
                dp(184),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(8);
        overlayParams.y = dp(100);

        final int[] startX = new int[1];
        final int[] startY = new int[1];
        final float[] touchX = new float[1];
        final float[] touchY = new float[1];
        header.setOnTouchListener((v, event) -> {
            if (overlayParams == null || windowManager == null) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                startX[0] = overlayParams.x;
                startY[0] = overlayParams.y;
                touchX[0] = event.getRawX();
                touchY[0] = event.getRawY();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                overlayParams.x = startX[0] + (int) (event.getRawX() - touchX[0]);
                overlayParams.y = startY[0] + (int) (event.getRawY() - touchY[0]);
                try { windowManager.updateViewLayout(root, overlayParams); } catch (Exception ignored) { }
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_UP;
        });

        try {
            windowManager.addView(root, overlayParams);
            overlayRoot = root;
            overlayText = body;
            overlayVisible = true;
        } catch (Exception e) {
            overlayRoot = null;
            overlayText = null;
            overlayVisible = false;
        }
    }

    private void removeOverlay() {
        if (overlayRoot != null && windowManager != null) {
            try { windowManager.removeView(overlayRoot); } catch (Exception ignored) { }
        }
        overlayRoot = null;
        overlayText = null;
        overlayParams = null;
    }

    private AccessibilityNodeInfo findMatchingNode(AccessibilityNodeInfo node, String target) {
        if (node == null) return null;
        if (matchesNode(node, target)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findMatchingNode(child, target);
            if (found != null) return found;
            child.recycle();
        }
        return null;
    }

    private boolean matchesNode(AccessibilityNodeInfo node, String target) {
        if (node == null || target.isEmpty()) return false;
        String text = normalize(node.getText() == null ? "" : node.getText().toString());
        String desc = normalize(node.getContentDescription() == null ? "" : node.getContentDescription().toString());
        return (!text.isEmpty() && (text.equals(target) || text.contains(target) || target.contains(text)))
                || (!desc.isEmpty() && (desc.equals(target) || desc.contains(target) || target.contains(desc)));
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int depth = 0;
        while (current != null && depth < 6) {
            if (current.isClickable() && current.isEnabled()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
            depth++;
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() && node.isEnabled()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = findEditable(child);
            if (result != null) return result;
            child.recycle();
        }
        return null;
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo result = findScrollable(child);
            if (result != null) return result;
            child.recycle();
        }
        return null;
    }

    private void collectInteractive(AccessibilityNodeInfo node, List<String> out, int max) {
        if (node == null || out.size() >= max) return;
        boolean interesting = node.isClickable() || node.isEditable() || node.isCheckable();
        if (interesting) {
            String label = node.getText() == null ? "" : node.getText().toString().trim();
            if (label.isEmpty() && node.getContentDescription() != null) {
                label = node.getContentDescription().toString().trim();
            }
            if (!label.isEmpty() && !out.contains(label)) out.add(label);
        }
        for (int i = 0; i < node.getChildCount() && out.size() < max; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collectInteractive(child, out, max);
            child.recycle();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String n = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
    }
}
