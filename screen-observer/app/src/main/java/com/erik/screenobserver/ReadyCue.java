package com.erik.screenobserver;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/** Short cue emitted only after SpeechRecognizer reports that the microphone is ready. */
public final class ReadyCue {
    private ReadyCue() { }

    public static void signal() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 32);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 65);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { tone.release(); } catch (Exception ignored) { }
            }, 130);
        } catch (Exception ignored) { }
    }
}
