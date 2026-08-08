package com.erik.screenobserver;

/** Pure-Java capture sizing/mapping rules used across rotation and app-window resize. */
public final class CaptureGeometry {
    private static final int MAX_LONG_EDGE = 1280;

    private CaptureGeometry() { }

    /** Downscales while preserving aspect ratio; never independently stretches an axis. */
    public static int[] targetSize(int contentWidth, int contentHeight) {
        int w = Math.max(1, contentWidth);
        int h = Math.max(1, contentHeight);
        int longest = Math.max(w, h);
        float scale = longest > MAX_LONG_EDGE ? ((float) MAX_LONG_EDGE / longest) : 1f;
        return new int[]{Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale))};
    }

    /**
     * OCR coordinates are allowed to drive gestures only when captured content is effectively
     * the full display. Similar aspect ratio is not enough: an app-only share can omit system
     * bars and therefore have a different coordinate origin. Accessibility actions remain
     * available whenever this conservative visual-tap path is disabled.
     */
    public static boolean isDirectScreenMappingSafe(int contentWidth, int contentHeight,
                                                    int screenWidth, int screenHeight) {
        if (contentWidth <= 0 || contentHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return false;
        double widthError = Math.abs(contentWidth - screenWidth) / (double) screenWidth;
        double heightError = Math.abs(contentHeight - screenHeight) / (double) screenHeight;
        return widthError <= 0.02 && heightError <= 0.02;
    }
}
