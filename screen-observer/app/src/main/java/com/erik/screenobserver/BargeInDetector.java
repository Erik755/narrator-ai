package com.erik.screenobserver;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight energy detector used only while TTS is speaking.
 * It uses VOICE_COMMUNICATION plus AEC/NS when available so the assistant's
 * own speaker output is less likely to trigger a false interruption.
 */
public final class BargeInDetector {
    public interface Listener {
        void onUserVoiceDetected();
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final double RMS_THRESHOLD = 2300.0;
    private static final int REQUIRED_HITS = 3;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private AudioRecord recorder;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private Thread worker;

    public BargeInDetector(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start(Listener listener) {
        stop();
        if (listener == null) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (min <= 0) return;
        int bufferSize = Math.max(min * 2, 4096);

        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    bufferSize);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseEffects();
                recorder.release();
                recorder = null;
                return;
            }

            int sessionId = recorder.getAudioSessionId();
            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    aec = AcousticEchoCanceler.create(sessionId);
                    if (aec != null) aec.setEnabled(true);
                } catch (Exception ignored) { }
            }
            if (NoiseSuppressor.isAvailable()) {
                try {
                    ns = NoiseSuppressor.create(sessionId);
                    if (ns != null) ns.setEnabled(true);
                } catch (Exception ignored) { }
            }

            running.set(true);
            recorder.startRecording();
            worker = new Thread(() -> monitor(listener, bufferSize), "screen-observer-barge-in");
            worker.start();
        } catch (Exception e) {
            stop();
        }
    }

    private void monitor(Listener listener, int bufferSize) {
        short[] samples = new short[Math.max(1024, bufferSize / 2)];
        int hits = 0;
        long startedAt = android.os.SystemClock.elapsedRealtime();

        while (running.get() && recorder != null) {
            int count;
            try {
                count = recorder.read(samples, 0, samples.length);
            } catch (Exception e) {
                break;
            }
            if (count <= 0) continue;

            double sum = 0.0;
            for (int i = 0; i < count; i++) {
                double s = samples[i];
                sum += s * s;
            }
            double rms = Math.sqrt(sum / count);

            // Ignore the first part of the utterance to avoid the initial TTS speaker transient.
            if (android.os.SystemClock.elapsedRealtime() - startedAt < 280) continue;

            if (rms >= RMS_THRESHOLD) {
                hits++;
                if (hits >= REQUIRED_HITS) {
                    if (running.compareAndSet(true, false)) {
                        main.post(listener::onUserVoiceDetected);
                    }
                    break;
                }
            } else {
                hits = Math.max(0, hits - 1);
            }
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) { }
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
        releaseEffects();
        worker = null;
    }

    private void releaseEffects() {
        if (aec != null) {
            try { aec.release(); } catch (Exception ignored) { }
            aec = null;
        }
        if (ns != null) {
            try { ns.release(); } catch (Exception ignored) { }
            ns = null;
        }
    }
}
