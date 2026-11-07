package com.gathin.fileserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import java.io.File;

public final class FileServerService extends Service {
    private static final String TAG = "FileServerService";
    private static final String CHANNEL_ID = "file_server_channel";
    private static final int NOTIF_ID = 1001;
    private static final long IDLE_TIMEOUT_MS = 20 * 60 * 1000L; // 20 minutes

    public static final String ACTION_START = "com.gathin.fileserver.START";
    public static final String ACTION_STOP = "com.gathin.fileserver.STOP";
    public static final String ACTION_STATE_CHANGED = "com.gathin.fileserver.STATE_CHANGED";

    private static volatile boolean isRunning = false;
    private static volatile String lastError = null;

    private HttpServer httpServer;
    private FtpServer ftpServer;
    private Handler handler;
    private final Runnable idleShutdown = () -> {
        Log.i(TAG, "Idle timeout reached; shutting down file server.");
        stopSelf();
    };

    public static boolean isServerRunning() {
        return isRunning;
    }

    public static String getLastError() {
        return lastError;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopServers();
            stopSelf();
            return START_NOT_STICKY;
        }

        startServers();
        return START_STICKY;
    }

    private void startServers() {
        if (isRunning) return;
        lastError = null;

        File root = Environment.getExternalStorageDirectory();
        if (!root.exists()) root.mkdirs();

        Runnable onActivity = () -> {
            if (handler != null) {
                handler.removeCallbacks(idleShutdown);
                handler.postDelayed(idleShutdown, IDLE_TIMEOUT_MS);
            }
        };

        try {
            httpServer = new HttpServer(8080, root, onActivity);
            httpServer.start();

            ftpServer = new FtpServer(2121, root, onActivity);
            ftpServer.start();

            isRunning = true;
            onActivity.run(); // Arm idle timer

            startForegroundNotification();
            broadcastState();
            Log.i(TAG, "All servers started successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Failed starting servers", e);
            lastError = e.getMessage();
            stopServers();
            stopSelf();
        }
    }

    private void stopServers() {
        if (handler != null) handler.removeCallbacks(idleShutdown);
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        if (ftpServer != null) {
            ftpServer.stop();
            ftpServer = null;
        }
        isRunning = false;
        broadcastState();
        stopForeground(true);
    }

    private void broadcastState() {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel chan = new NotificationChannel(
                    CHANNEL_ID, "File Server", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(chan);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notif = b.setContentTitle("Mi TV File Server Active")
                .setContentText("Web: http://<tv-ip>:8080 • FTP: port 2121")
                .setSmallIcon(R.drawable.icon)
                .setContentIntent(pi)
                .build();

        startForeground(NOTIF_ID, notif);
    }

    @Override
    public void onDestroy() {
        stopServers();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
