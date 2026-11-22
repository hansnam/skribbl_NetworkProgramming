package com.skribbl.client.ui.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight procedural arcade sound generator (no external media assets required).
 * Plays short non-blocking tone cues and fails silently on headless machines without audio devices.
 */
public final class SoundManager {
    private static final AtomicBoolean MUTED = new AtomicBoolean(false);
    private static final float SAMPLE_RATE = 22050f;

    private SoundManager() {
    }

    public static boolean isMuted() {
        return MUTED.get();
    }

    public static void setMuted(boolean muted) {
        MUTED.set(muted);
    }

    public static boolean toggleMute() {
        boolean next = !MUTED.get();
        MUTED.set(next);
        return next;
    }

    public static void playJoin() {
        playNotes(new int[]{523, 659}, new int[]{55, 75}, 0.22);
    }

    public static void playTurnStart() {
        playNotes(new int[]{440, 554, 659}, new int[]{50, 50, 80}, 0.24);
    }

    public static void playTickLow() {
        playNotes(new int[]{880}, new int[]{35}, 0.16);
    }

    public static void playCorrectGuess() {
        playNotes(new int[]{587, 784, 987}, new int[]{55, 55, 110}, 0.28);
    }

    public static void playCloseGuess() {
        playNotes(new int[]{493, 523}, new int[]{60, 70}, 0.22);
    }

    public static void playTurnEnd() {
        playNotes(new int[]{523, 440, 392}, new int[]{60, 60, 100}, 0.22);
    }

    public static void playGameOver() {
        playNotes(new int[]{523, 659, 784, 1046}, new int[]{70, 70, 70, 150}, 0.28);
    }

    public static void playReaction(boolean like) {
        if (like) {
            playNotes(new int[]{659, 880}, new int[]{45, 65}, 0.20);
        } else {
            playNotes(new int[]{330, 262}, new int[]{55, 75}, 0.20);
        }
    }

    private static void playNotes(int[] freqs, int[] durationsMs, double volume) {
        if (MUTED.get()) {
            return;
        }
        Thread.ofVirtual().name("skribbl-sfx").start(() -> {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 8, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                line.start();
                for (int i = 0; i < freqs.length; i++) {
                    int samples = (int) (durationsMs[i] * SAMPLE_RATE / 1000);
                    byte[] buf = new byte[samples];
                    for (int s = 0; s < samples; s++) {
                        double angle = 2.0 * Math.PI * s * freqs[i] / SAMPLE_RATE;
                        double envelope = Math.min(1.0, (samples - s) / (samples * 0.35));
                        buf[s] = (byte) (Math.sin(angle) * 127.0 * volume * envelope);
                    }
                    line.write(buf, 0, buf.length);
                }
                line.drain();
            } catch (Exception ignored) {
                // Headless or busy audio device: ignore safely
            }
        });
    }
}
