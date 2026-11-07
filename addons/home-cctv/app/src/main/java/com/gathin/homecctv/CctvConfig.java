package com.gathin.homecctv;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

public final class CctvConfig {
    public static String ip = "192.168.1.2";
    public static int rtspPort = 554;
    public static int httpPort = 80;
    public static String user = "admin";
    public static String password = "admin@123";

    public static final int CHANNEL_COUNT = 5;

    public static String[] channelNames = new String[] {
        "Camera 1",
        "Camera 2",
        "Camera 3",
        "Camera 4",
        "Camera 5"
    };

    public static String getRtspUrl(int channel, int subtype) {
        String encPass = encode(password);
        String encUser = encode(user);
        return "rtsp://" + encUser + ":" + encPass + "@" + ip + ":" + rtspPort + 
               "/cam/realmonitor?channel=" + channel + "&subtype=" + subtype;
    }

    public static String getSnapshotUri(int channel) {
        return "/cgi-bin/snapshot.cgi?channel=" + channel;
    }

    public static String getSnapshotUrl(int channel) {
        return "http://" + ip + ":" + httpPort + getSnapshotUri(channel);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
