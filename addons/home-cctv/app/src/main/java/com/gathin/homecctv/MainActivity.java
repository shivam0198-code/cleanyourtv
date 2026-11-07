package com.gathin.homecctv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity {
    private static final String TAG = "HomeCCTV_Main";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2);

    // Channels
    private int heroChannel = 1;
    private final int[] gridChannels = new int[] { 2, 3, 4, 5 };
    private int currentSubtype = 0; // 0 = HD, 1 = SD

    // Views - Command Center
    private FrameLayout heroCard;
    private TextureView heroVideo;
    private ImageView heroSnapshot;
    private TextView heroLiveBadge;
    private TextView heroQualityBadge;
    private TextView heroTitle;

    private final FrameLayout[] gridSlots = new FrameLayout[4];
    private final ImageView[] gridImages = new ImageView[4];
    private final TextView[] gridTitles = new TextView[4];

    // Views - Fullscreen
    private FrameLayout fullscreenOverlay;
    private TextureView fullscreenVideo;
    private View fullscreenBadge;
    private TextView fsQualityBadge;
    private TextView fsTitleText;

    // Player
    private ExoPlayer player;
    private boolean isFullscreen = false;
    private boolean isActivityRunning = false;
    private final Runnable hideFsBadgeRunnable = () -> {
        if (fullscreenBadge != null) fullscreenBadge.setVisibility(View.GONE);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        initViews();
        setupRemoteListeners();

        // Start ONVIF fallback probe in background in case IP changed
        OnvifDiscovery.discoverAsync(newIp -> {
            if (!newIp.equals(CctvConfig.ip)) {
                CctvConfig.ip = newIp;
                mainHandler.post(() -> {
                    TextView header = findViewById(R.id.header_status_text);
                    if (header != null) {
                        header.setText("5 Feeds Online • " + newIp);
                    }
                    startHeroPlayer(heroChannel, currentSubtype);
                });
            }
        });
    }

    private void initViews() {
        heroCard = findViewById(R.id.hero_card);
        heroVideo = findViewById(R.id.hero_video);
        heroSnapshot = findViewById(R.id.hero_snapshot);
        heroLiveBadge = findViewById(R.id.hero_live_badge);
        heroQualityBadge = findViewById(R.id.hero_quality_badge);
        heroTitle = findViewById(R.id.hero_title);

        gridSlots[0] = findViewById(R.id.grid_slot_0);
        gridSlots[1] = findViewById(R.id.grid_slot_1);
        gridSlots[2] = findViewById(R.id.grid_slot_2);
        gridSlots[3] = findViewById(R.id.grid_slot_3);

        gridImages[0] = findViewById(R.id.grid_img_0);
        gridImages[1] = findViewById(R.id.grid_img_1);
        gridImages[2] = findViewById(R.id.grid_img_2);
        gridImages[3] = findViewById(R.id.grid_img_3);

        gridTitles[0] = findViewById(R.id.grid_title_0);
        gridTitles[1] = findViewById(R.id.grid_title_1);
        gridTitles[2] = findViewById(R.id.grid_title_2);
        gridTitles[3] = findViewById(R.id.grid_title_3);

        fullscreenOverlay = findViewById(R.id.fullscreen_overlay);
        fullscreenVideo = findViewById(R.id.fullscreen_video);
        fullscreenBadge = findViewById(R.id.fullscreen_badge);
        fsQualityBadge = findViewById(R.id.fs_quality_badge);
        fsTitleText = findViewById(R.id.fs_title_text);

        updateLabels();
        heroCard.requestFocus();
    }

    private void updateLabels() {
        heroTitle.setText(CctvConfig.channelNames[heroChannel - 1]);
        heroQualityBadge.setText(currentSubtype == 0 ? "HD" : "SD");
        for (int i = 0; i < 4; i++) {
            gridTitles[i].setText(CctvConfig.channelNames[gridChannels[i] - 1]);
        }
    }

    private void setupRemoteListeners() {
        // Hero card clicked -> expand to full screen
        heroCard.setOnClickListener(v -> enterFullscreen());

        // Secondary cards clicked -> swap into Hero
        for (int i = 0; i < 4; i++) {
            final int index = i;
            gridSlots[i].setOnClickListener(v -> swapCameraToHero(index));
        }
    }

    private void swapCameraToHero(int gridIndex) {
        int selectedChannel = gridChannels[gridIndex];
        int oldHeroChannel = heroChannel;

        gridChannels[gridIndex] = oldHeroChannel;
        heroChannel = selectedChannel;

        updateLabels();

        // Swap snapshot immediately so transition looks instant
        Bitmap prevHeroBmp = (Bitmap) heroSnapshot.getTag();
        Bitmap targetBmp = (Bitmap) gridImages[gridIndex].getTag();

        if (targetBmp != null) {
            heroSnapshot.setImageBitmap(targetBmp);
            heroSnapshot.setTag(targetBmp);
            heroSnapshot.setVisibility(View.VISIBLE);
        }
        if (prevHeroBmp != null) {
            gridImages[gridIndex].setImageBitmap(prevHeroBmp);
            gridImages[gridIndex].setTag(prevHeroBmp);
        }

        // Restart video player on new channel
        startHeroPlayer(heroChannel, currentSubtype);
    }

    private void enterFullscreen() {
        isFullscreen = true;
        fullscreenOverlay.setVisibility(View.VISIBLE);
        fullscreenBadge.setVisibility(View.VISIBLE);
        fsTitleText.setText(CctvConfig.channelNames[heroChannel - 1]);
        fsQualityBadge.setText(currentSubtype == 0 ? "HD" : "SD");

        mainHandler.removeCallbacks(hideFsBadgeRunnable);
        mainHandler.postDelayed(hideFsBadgeRunnable, 3500);

        if (player != null) {
            player.setVideoTextureView(fullscreenVideo);
        } else {
            startHeroPlayer(heroChannel, currentSubtype);
        }
    }

    private void exitFullscreen() {
        isFullscreen = false;
        mainHandler.removeCallbacks(hideFsBadgeRunnable);
        fullscreenOverlay.setVisibility(View.GONE);

        if (player != null) {
            player.setVideoTextureView(heroVideo);
        }
        heroCard.requestFocus();
    }

    private void startHeroPlayer(int channel, int subtype) {
        releasePlayer();

        String rtspUrl = CctvConfig.getRtspUrl(channel, subtype);
        Log.i(TAG, "Starting RTSP player for ch=" + channel + ", subtype=" + subtype + " -> " + rtspUrl);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(800, 2500, 400, 400)
                .build();

        player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build();

        // Mute audio to save TV audio decoder bandwidth
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build());

        TextureView targetSurface = isFullscreen ? fullscreenVideo : heroVideo;
        player.setVideoTextureView(targetSurface);

        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                heroLiveBadge.setText("● LIVE");
                // Hide snapshot once live video arrives
                heroSnapshot.setVisibility(View.GONE);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.w(TAG, "Player error: " + error.getMessage());
                heroLiveBadge.setText("RETRYING");
                mainHandler.postDelayed(() -> {
                    if (isActivityRunning) {
                        startHeroPlayer(heroChannel, currentSubtype);
                    }
                }, 3000);
            }
        });

        MediaItem mediaItem = MediaItem.fromUri(rtspUrl);
        RtspMediaSource mediaSource = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(mediaItem);

        player.setMediaSource(mediaSource);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isActivityRunning = true;
        startHeroPlayer(heroChannel, currentSubtype);
        startSnapshotLoop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isActivityRunning = false;
        mainHandler.removeCallbacksAndMessages(null);
        releasePlayer();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isActivityRunning = false;
        releasePlayer();
        backgroundExecutor.shutdownNow();
    }

    // Snapshot refresh loop for secondary cameras
    private void startSnapshotLoop() {
        backgroundExecutor.execute(() -> {
            while (isActivityRunning) {
                if (!isFullscreen) {
                    // Update 4 secondary cards
                    for (int i = 0; i < 4; i++) {
                        if (!isActivityRunning || isFullscreen) break;
                        final int slotIndex = i;
                        final int ch = gridChannels[i];
                        Bitmap bmp = DigestHttp.fetchSnapshot(ch);
                        if (bmp != null) {
                            mainHandler.post(() -> {
                                if (isActivityRunning && !isFullscreen) {
                                    gridImages[slotIndex].setImageBitmap(bmp);
                                    gridImages[slotIndex].setTag(bmp);
                                }
                            });
                        }
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isFullscreen) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                exitFullscreen();
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // Next camera
                heroChannel = (heroChannel % CctvConfig.CHANNEL_COUNT) + 1;
                fsTitleText.setText(CctvConfig.channelNames[heroChannel - 1]);
                fullscreenBadge.setVisibility(View.VISIBLE);
                mainHandler.removeCallbacks(hideFsBadgeRunnable);
                mainHandler.postDelayed(hideFsBadgeRunnable, 3500);
                startHeroPlayer(heroChannel, currentSubtype);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                // Previous camera
                heroChannel = (heroChannel == 1) ? CctvConfig.CHANNEL_COUNT : heroChannel - 1;
                fsTitleText.setText(CctvConfig.channelNames[heroChannel - 1]);
                fullscreenBadge.setVisibility(View.VISIBLE);
                mainHandler.removeCallbacks(hideFsBadgeRunnable);
                mainHandler.postDelayed(hideFsBadgeRunnable, 3500);
                startHeroPlayer(heroChannel, currentSubtype);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Toggle HD/SD
                currentSubtype = (currentSubtype == 0) ? 1 : 0;
                fsQualityBadge.setText(currentSubtype == 0 ? "HD" : "SD");
                fullscreenBadge.setVisibility(View.VISIBLE);
                mainHandler.removeCallbacks(hideFsBadgeRunnable);
                mainHandler.postDelayed(hideFsBadgeRunnable, 3500);
                startHeroPlayer(heroChannel, currentSubtype);
                return true;
            }
        } else {
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Toggle HD / SD in Command Center
                currentSubtype = (currentSubtype == 0) ? 1 : 0;
                heroQualityBadge.setText(currentSubtype == 0 ? "HD" : "SD");
                startHeroPlayer(heroChannel, currentSubtype);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
