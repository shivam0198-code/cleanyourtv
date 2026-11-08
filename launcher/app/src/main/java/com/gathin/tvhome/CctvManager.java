package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.widget.ImageView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
final class CctvManager {
    private static final String TAG = "CctvManager";

    interface Listener {
        void onFirstFrame();
        void onError(String message);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService snapshotExecutor;

    private ExoPlayer player;
    private boolean active = false;

    CctvManager(Context context) {
        this.context = context.getApplicationContext();
    }

    void startHdStream(String ip, String user, String pass, int channel, TextureView surface, Listener listener) {
        if ("admin".equals(user) && "admin@123".equals(pass)) {
            user = "tvviewer";
            pass = "tvviewer123";
        }
        stopSnapshotLoop();
        releasePlayer();

        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_ALL);

        String url = buildRtspUrl(ip, user, pass, channel, 0); // subtype 0 = HD
        Log.i(TAG, "Starting HD stream ch=" + channel + " -> " + url);

        player = new ExoPlayer.Builder(context).build();
        player.setVideoTextureView(surface);

        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build());

        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                Log.i(TAG, "ExoPlayer rendered first frame!");
                if (listener != null) listener.onFirstFrame();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                Log.i(TAG, "ExoPlayer playbackState=" + state);
            }

            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                Log.w(TAG, "ExoPlayer error: " + error.getMessage() + ", cause=" + error.getCause(), error);
                if (listener != null) listener.onError(error.getMessage());
            }
        });

        MediaItem item = MediaItem.fromUri(url);
        RtspMediaSource source = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setDebugLoggingEnabled(true)
                .createMediaSource(item);

        player.setMediaSource(source);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    void startSnapshotLoop(String ip, String user, String pass, int count, ImageView[] targets) {
        stopSnapshotLoop();
        active = true;
        snapshotExecutor = Executors.newSingleThreadExecutor();
        snapshotExecutor.execute(() -> {
            while (active) {
                for (int i = 0; i < count && i < targets.length; i++) {
                    if (!active) break;
                    final int idx = i;
                    final int ch = i + 1;
                    Bitmap bmp = CctvDigest.fetchSnapshot(ip, user, pass, ch);
                    if (bmp != null && active) {
                        mainHandler.post(() -> {
                            if (active && targets[idx] != null) {
                                targets[idx].setImageBitmap(bmp);
                            }
                        });
                    }
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }

    void stopSnapshotLoop() {
        active = false;
        if (snapshotExecutor != null) {
            snapshotExecutor.shutdownNow();
            snapshotExecutor = null;
        }
    }

    private volatile boolean hdStreaming = false;
    private ExecutorService hdExecutor;

    void startHighSpeedStream(String ip, String user, String pass, int channel, ImageView target) {
        stopHighSpeedStream();
        hdStreaming = true;
        hdExecutor = Executors.newSingleThreadExecutor();
        hdExecutor.execute(() -> {
            while (hdStreaming) {
                Bitmap bmp = CctvDigest.fetchSnapshot(ip, user, pass, channel);
                if (bmp != null && hdStreaming) {
                    mainHandler.post(() -> {
                        if (hdStreaming && target != null) {
                            target.setImageBitmap(bmp);
                        }
                    });
                }
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
    }

    void stopHighSpeedStream() {
        hdStreaming = false;
        if (hdExecutor != null) {
            hdExecutor.shutdownNow();
            hdExecutor = null;
        }
    }

    void releasePlayer() {
        stopHighSpeedStream();
        if (player != null) {
            try {
                player.clearVideoSurface();
                player.stop();
                player.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing player", e);
            }
            player = null;
        }
    }

    void releaseAll() {
        stopSnapshotLoop();
        stopHighSpeedStream();
        releasePlayer();
    }

    private static String buildRtspUrl(String ip, String user, String pass, int channel, int subtype) {
        if ("admin".equals(user) && "admin@123".equals(pass)) {
            user = "tvviewer";
            pass = "tvviewer123";
        }
        String u = enc(user);
        String p = enc(pass);
        return "rtsp://" + u + ":" + p + "@" + ip + ":554/cam/realmonitor?channel=" + channel + "&subtype=" + subtype + "&unicast=true";
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
