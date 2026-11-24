package com.jhonny.mustmanager;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

/**
 * Alarma suave para la batería baja: el sonido de alarma del usuario (el mismo del reloj de Android),
 * en bucle por el canal de ALARMAS (el "No molestar" de Samsung las deja pasar por defecto),
 * empezando muy bajo y subiendo poco a poco para despertar sin sobresalto.
 * Se para con stop() (botón "Detener alarma") o sola al cabo de un tiempo máximo.
 */
final class AlarmPlayer {

    private static final long RAMP_STEP_MS = 1000;
    private static final float START_VOLUME = 0.05f;
    private static final float RAMP_SECONDS = 30f;      // de casi nada al volumen máximo de alarma en 30 s
    static final long MAX_MS = 5 * 60 * 1000L;            // como mucho 5 min sonando

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private float volume;

    AlarmPlayer(Context ctx) {
        this.ctx = ctx;
    }

    boolean isPlaying() {
        return mp != null;
    }

    /** Empieza a sonar (si no estaba sonando ya) y se para sola tras maxMs. */
    void start(long maxMs) {
        if (mp != null) return;
        Uri uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        if (uri == null) return;
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
            mp.setDataSource(ctx, uri);
            mp.setLooping(true);
            volume = START_VOLUME;
            mp.setVolume(volume, volume);
            mp.prepare();
            mp.start();
            h.postDelayed(ramp, RAMP_STEP_MS);
            h.postDelayed(autoStop, maxMs);
        } catch (Exception e) {
            // Sin sonido de alarma disponible o error del reproductor: queda la notificación normal.
            stop();
        }
    }

    void stop() {
        h.removeCallbacks(ramp);
        h.removeCallbacks(autoStop);
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            mp.release();
            mp = null;
        }
    }

    private final Runnable ramp = new Runnable() {
        @Override
        public void run() {
            if (mp == null) return;
            volume = Math.min(1f, volume + (1f - START_VOLUME) / RAMP_SECONDS);
            try {
                mp.setVolume(volume, volume);
            } catch (Exception ignored) {
            }
            if (volume < 1f) h.postDelayed(this, RAMP_STEP_MS);
        }
    };

    private final Runnable autoStop = new Runnable() {
        @Override
        public void run() {
            stop();
        }
    };
}
