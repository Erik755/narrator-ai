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
     * OCR coordinates can be mapped directly to device gesture coordinates only when the
     * captured content has essentially the same aspect ratio as the current device screen.
     * App-window sharing often violates this, so visual tapping is disabled there rather
     * than risking a tap on the wrong control. Accessibility actions remain available.
     */
    public static boolean isDirectScreenMappingSafe(int contentWidth, int contentHeight,
                                                    int screenWidth, int screenHeight) {
        if (contentWidth <= 0 || contentHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return false;
        double contentAspect = (double) contentWidth / contentHeight;
        double screenAspect = (double) screenWidth / screenHeight;
        double ratio = contentAspect / screenAspect;
        return Math.abs(Math.log(ratio)) <= 0.10;
    }
}
