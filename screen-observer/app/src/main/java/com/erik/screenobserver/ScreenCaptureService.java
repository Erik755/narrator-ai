package com.erik.screenobserver;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Legacy v1 compatibility shell.
 *
 * The launcher and manifest use ScreenAgentService22. Keeping only this tiny API surface
 * lets old source references compile without retaining the obsolete voice/action runtime
 * that was flagged during review. It is not registered as a service in the manifest.
 */
@Deprecated
public class ScreenCaptureService extends Service {
    public static final String ACTION_SHOW_OVERLAY = "com.erik.screenobserver.SHOW_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.erik.screenobserver.HIDE_OVERLAY";
    public static final String ACTION_TOGGLE_LISTENING = "com.erik.screenobserver.TOGGLE_LISTENING";
    public static final String ACTION_DESCRIBE_CONTROLS = "com.erik.screenobserver.DESCRIBE_CONTROLS";

    public static boolean isRunning() { return false; }
    public static boolean isListeningEnabled() { return false; }
    public static String getVoiceStatus() { return "legacy inactivo"; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
