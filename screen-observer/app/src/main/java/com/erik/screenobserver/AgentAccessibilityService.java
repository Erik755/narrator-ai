package com.erik.screenobserver;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
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
    private EditText overlayInput;
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

    public boolean longClickText(String target) {
        if (target == null || target.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        String wanted = normalize(target);
        try {
            AccessibilityNodeInfo node = findMatchingNode(root, wanted);
            return node != null && longClickNodeOrParent(node);
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

    public boolean swipeLeft() { return swipeDirection(true); }
    public boolean swipeRight() { return swipeDirection(false); }

    private boolean swipeDirection(boolean left) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            float y = dm.heightPixels * 0.52f;
            float fromX = dm.widthPixels * (left ? 0.78f : 0.22f);
            float toX = dm.widthPixels * (left ? 0.22f : 0.78f);
            Path path = new Path();
            path.moveTo(fromX, y);
            path.lineTo(toX, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 280);
            return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean clickOrdinal(int oneBased, boolean fromEnd) {
        if (oneBased < 1) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        try {
            collectClickableNodes(root, nodes, 40);
            if (nodes.isEmpty()) return false;
            int index = fromEnd ? nodes.size() - oneBased : oneBased - 1;
            if (index < 0 || index >= nodes.size()) return false;
            return clickNodeOrParent(nodes.get(index));
        } finally {
            for (AccessibilityNodeInfo n : nodes) try { n.recycle(); } catch (Exception ignored) { }
            root.recycle();
        }
    }

    private void collectClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int limit) {
        if (node == null || out.size() >= limit) return;
        if (node.isEnabled() && node.isClickable()) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount() && out.size() < limit; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collectClickableNodes(child, out, limit);
            child.recycle();
        }
    }

    public boolean back() { return performGlobalAction(GLOBAL_ACTION_BACK); }
    public boolean home() { return performGlobalAction(GLOBAL_ACTION_HOME); }
    public boolean recents() { return performGlobalAction(GLOBAL_ACTION_RECENTS); }
    public boolean notifications() { return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); }
    public boolean quickSettings() { return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); }
    public boolean powerDialog() { return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG); }
    public boolean lockScreen() {
        return Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }
    public boolean screenshot() {
        return Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
    }

    public String listInteractiveElements() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "No puedo leer los controles de esta pantalla.";
        try {
            List<String> items = new ArrayList<>();
            collectInteractive(root, items, 16);
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

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int overlayWidth = Math.max(dp(220), Math.min(dp(320), screenWidth - dp(16)));
        int innerWidth = Math.max(dp(220), overlayWidth - dp(14));

        TextView body = new TextView(this);
        body.setText("🎙 Listo");
        body.setTextColor(Color.WHITE);
        body.setTextSize(11);
        body.setMaxLines(4);
        body.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(body, new LinearLayout.LayoutParams(innerWidth, WindowManager.LayoutParams.WRAP_CONTENT));

        EditText input = new EditText(this);
        input.setHint("Escribe una instrucción…");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFFBDBDBD);
        input.setTextSize(13);
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setSingleLine(false);
        input.setPadding(dp(8), dp(5), dp(8), dp(5));
        input.setBackgroundColor(0xFF333333);
        input.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnClickListener(v -> enterTypingMode(input));
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitOverlayCommand();
                return true;
            }
            return false;
        });
        root.addView(input, new LinearLayout.LayoutParams(innerWidth, WindowManager.LayoutParams.WRAP_CONTENT));

        Button send = new Button(this);
        send.setText("ENVIAR");
        send.setTextSize(11);
        send.setMinHeight(0);
        send.setPadding(dp(4), 0, dp(4), 0);
        send.setOnClickListener(v -> submitOverlayCommand());
        root.addView(send, new LinearLayout.LayoutParams(innerWidth, dp(38)));
        overlayInput = input;

        overlayParams = new WindowManager.LayoutParams(
                overlayWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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

    private void enterTypingMode(EditText input) {
        if (input == null || overlayRoot == null || overlayParams == null || windowManager == null) return;
        try {
            overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            windowManager.updateViewLayout(overlayRoot, overlayParams);
        } catch (Exception ignored) { }
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }, 80);
    }

    private void exitTypingMode() {
        if (overlayInput != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(overlayInput.getWindowToken(), 0);
            overlayInput.clearFocus();
        }
        if (overlayRoot != null && overlayParams != null && windowManager != null) {
            try {
                overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                windowManager.updateViewLayout(overlayRoot, overlayParams);
            } catch (Exception ignored) { }
        }
    }

    private void submitOverlayCommand() {
        if (overlayInput == null) return;
        String command = overlayInput.getText() == null ? "" : overlayInput.getText().toString().trim();
        if (command.isEmpty()) return;
        if (!ScreenAgentService22.isRunning()) {
            if (overlayText != null) overlayText.setText("Inicia primero el asistente.");
            return;
        }
        Intent commandIntent = new Intent(this, ScreenAgentService22.class);
        commandIntent.setAction("com.erik.screenobserver.v24.TEXT_COMMAND");
        commandIntent.putExtra("textCommand", command);
        try {
            startService(commandIntent);
            overlayInput.setText("");
            if (overlayText != null) overlayText.setText("⌨ Procesando instrucción…");
        } catch (Exception e) {
            if (overlayText != null) overlayText.setText("No pude enviar la instrucción.");
        }
        exitTypingMode();
    }

    private void removeOverlay() {
        if (overlayRoot != null && windowManager != null) {
            try { windowManager.removeView(overlayRoot); } catch (Exception ignored) { }
        }
        overlayRoot = null;
        overlayText = null;
        overlayInput = null;
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

    /**
     * Matches actuation targets only against stable identity fields. State descriptions are
     * intentionally excluded: finding a switch because it is currently "On" and then clicking
     * it would invert the requested state. View IDs are stripped to their resource name so the
     * package prefix cannot accidentally satisfy short targets such as "ok".
     */
    private boolean matchesNode(AccessibilityNodeInfo node, String target) {
        if (node == null || target.isEmpty()) return false;
        String text = normalize(node.getText() == null ? "" : node.getText().toString());
        String desc = normalize(node.getContentDescription() == null ? "" : node.getContentDescription().toString());
        String hint = normalize(node.getHintText() == null ? "" : node.getHintText().toString());
        String rawId = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
        int slash = rawId.lastIndexOf('/');
        String viewId = normalize(slash >= 0 ? rawId.substring(slash + 1).replace('_', ' ') : "");
        return fieldMatches(text, target) || fieldMatches(desc, target) || fieldMatches(hint, target)
                || idMatches(viewId, target);
    }

    private boolean fieldMatches(String field, String target) {
        return !field.isEmpty() && (field.equals(target) || field.contains(target)
                || (field.length() >= 3 && target.contains(field)));
    }

    private boolean idMatches(String field, String target) {
        return !field.isEmpty() && (field.equals(target) || field.contains(target));
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int depth = 0;
        while (current != null && depth < 7) {
            if (current.isClickable() && current.isEnabled()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
            depth++;
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean longClickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        int depth = 0;
        while (current != null && depth < 7) {
            if (current.isEnabled() && current.isLongClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            }
            current = current.getParent();
            depth++;
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
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
        boolean interesting = node.isClickable() || node.isEditable() || node.isCheckable()
                || node.isScrollable() || node.isLongClickable();
        if (interesting) {
            String label = node.getText() == null ? "" : node.getText().toString().trim();
            if (label.isEmpty() && node.getContentDescription() != null) label = node.getContentDescription().toString().trim();
            if (label.isEmpty() && node.getHintText() != null) label = node.getHintText().toString().trim();
            if (label.isEmpty() && node.getViewIdResourceName() != null) {
                String id = node.getViewIdResourceName();
                int slash = id.lastIndexOf('/');
                label = (slash >= 0 ? id.substring(slash + 1) : id).replace('_', ' ');
            }
            if (!label.isEmpty()) {
                String state = "";
                if (node.isCheckable()) state = node.isChecked() ? " [activado]" : " [desactivado]";
                else if (node.isEditable()) state = " [campo de texto]";
                else if (node.isScrollable()) state = " [lista]";
                String item = label + state;
                if (!out.contains(item)) out.add(item);
            }
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
