package com.gathin.fileserver;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQ_PERMS = 1001;

    private View dotStatus;
    private TextView textStatus;
    private TextView textWebUrl;
    private TextView textFtpUrl;
    private TextView textInfo;
    private Button btnToggle;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateUi();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dotStatus = findViewById(R.id.dotStatus);
        textStatus = findViewById(R.id.textStatus);
        textWebUrl = findViewById(R.id.textWebUrl);
        textFtpUrl = findViewById(R.id.textFtpUrl);
        textInfo = findViewById(R.id.textInfo);
        btnToggle = findViewById(R.id.btnToggle);

        btnToggle.setOnClickListener(v -> {
            boolean running = FileServerService.isServerRunning();
            Intent intent = new Intent(this, FileServerService.class);
            if (running) {
                intent.setAction(FileServerService.ACTION_STOP);
                startService(intent);
            } else {
                intent.setAction(FileServerService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent);
                } else {
                    startService(intent);
                }
            }
            v.postDelayed(this::updateUi, 300);
        });

        checkPermissions();
        btnToggle.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(stateReceiver, new IntentFilter(FileServerService.ACTION_STATE_CHANGED));
        updateUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_PERMS);
            }
        }
    }

    private void updateUi() {
        boolean running = FileServerService.isServerRunning();
        String ip = getDeviceIp();

        if (running) {
            dotStatus.setBackgroundColor(0xFF4CAF50); // Green
            textStatus.setText(R.string.server_running);
            textWebUrl.setText(String.format("Web Browser: http://%s:8080", ip));
            textFtpUrl.setText(String.format("FTP Address: ftp://%s:2121", ip));
            btnToggle.setText(R.string.stop_server);
            textInfo.setText("Storage root: /sdcard (Games: /sdcard/Roms/NES) • Auto-stop after 20m idle");
        } else {
            dotStatus.setBackgroundColor(0xFFFF5555); // Red
            textStatus.setText(R.string.server_stopped);
            textWebUrl.setText(String.format("Web Browser: http://%s:8080", ip));
            textFtpUrl.setText(String.format("FTP Address: ftp://%s:2121", ip));
            btnToggle.setText(R.string.start_server);
            String err = FileServerService.getLastError();
            if (err != null) {
                textInfo.setText("Error: " + err);
            } else {
                textInfo.setText("Storage root: /sdcard (Games: /sdcard/Roms/NES) • Auto-stop after 20m idle");
            }
        }
    }

    private String getDeviceIp() {
        String fallback = null;
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : interfaces) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                boolean isPreferred = nif.getName().startsWith("wlan") || nif.getName().startsWith("eth");
                List<InetAddress> addrs = Collections.list(nif.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String host = addr.getHostAddress();
                        if (isPreferred) {
                            return host;
                        }
                        if (fallback == null) {
                            fallback = host;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback != null ? fallback : "127.0.0.1";
    }
}
