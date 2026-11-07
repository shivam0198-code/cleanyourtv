package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CctvDigest {
    private static final String TAG = "CctvDigest";

    static Bitmap fetchSnapshot(String ip, String user, String pass, int channel) {
        String uri = "/cgi-bin/snapshot.cgi?channel=" + channel;
        String fullUrl = "http://" + ip + ":80" + uri;

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.connect();

            int code = conn.getResponseCode();
            if (code == 200) {
                byte[] data = readBytes(conn.getInputStream());
                conn.disconnect();
                if (data != null && data.length > 0) {
                    return BitmapFactory.decodeByteArray(data, 0, data.length);
                }
            } else if (code == 401) {
                String authHeader = conn.getHeaderField("WWW-Authenticate");
                conn.disconnect();

                if (authHeader != null && authHeader.contains("Digest")) {
                    String realm = extractParam(authHeader, "realm");
                    String nonce = extractParam(authHeader, "nonce");
                    String qop = extractParam(authHeader, "qop");

                    if (realm != null && nonce != null) {
                        byte[] data = requestWithAuth(fullUrl, uri, user, pass, realm, nonce, qop);
                        if (data != null && data.length > 0) {
                            return BitmapFactory.decodeByteArray(data, 0, data.length);
                        }
                    }
                }
            } else {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Snapshot ch=" + channel + " failed: " + e.getMessage());
        }
        return null;
    }

    private static byte[] requestWithAuth(String fullUrl, String uri, String user, String pass, String realm, String nonce, String qop) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");

            String ha1 = md5(user + ":" + realm + ":" + pass);
            String ha2 = md5("GET:" + uri);

            String authValue;
            if (qop != null && !qop.isEmpty()) {
                String nc = "00000001";
                String cnonce = "0a4f113b";
                String actualQop = qop.contains("auth") ? "auth" : qop;
                String response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + actualQop + ":" + ha2);
                authValue = "Digest username=\"" + user + "\", realm=\"" + realm +
                        "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", qop=" + actualQop +
                        ", nc=" + nc + ", cnonce=\"" + cnonce + "\", response=\"" + response + "\"";
            } else {
                String response = md5(ha1 + ":" + nonce + ":" + ha2);
                authValue = "Digest username=\"" + user + "\", realm=\"" + realm +
                        "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", response=\"" + response + "\"";
            }
            conn.setRequestProperty("Authorization", authValue);

            conn.connect();
            int respCode = conn.getResponseCode();
            if (respCode == 200) {
                byte[] data = readBytes(conn.getInputStream());
                conn.disconnect();
                if (data != null && data.length > 0) {
                    Log.i(TAG, "Snapshot ch=" + uri + " success (" + data.length + " bytes)");
                    return data;
                }
            } else {
                Log.w(TAG, "Auth request returned HTTP " + respCode);
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Auth request failed: " + e.getMessage());
        }
        return null;
    }

    private static String extractParam(String header, String param) {
        Pattern p = Pattern.compile(param + "=\"([^\"]+)\"");
        Matcher m = p.matcher(header);
        if (m.find()) return m.group(1);
        Pattern p2 = Pattern.compile(param + "=([^,\\s]+)");
        Matcher m2 = p2.matcher(header);
        if (m2.find()) return m2.group(1);
        return null;
    }

    private static byte[] readBytes(InputStream in) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
