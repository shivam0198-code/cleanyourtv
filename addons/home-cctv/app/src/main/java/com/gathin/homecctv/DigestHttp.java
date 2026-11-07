package com.gathin.homecctv;

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

public final class DigestHttp {
    private static final String TAG = "HomeCCTV_Digest";
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    public static Bitmap fetchSnapshot(int channel) {
        String uri = CctvConfig.getSnapshotUri(channel);
        String fullUrl = CctvConfig.getSnapshotUrl(channel);

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");
            conn.connect();

            int code = conn.getResponseCode();
            if (code == 401) {
                String authHeader = conn.getHeaderField("WWW-Authenticate");
                conn.disconnect();

                if (authHeader != null && authHeader.contains("Digest")) {
                    String realm = extractParam(authHeader, "realm");
                    String nonce = extractParam(authHeader, "nonce");
                    String qop = extractParam(authHeader, "qop");
                    String opaque = extractParam(authHeader, "opaque");

                    if (realm != null && nonce != null) {
                        byte[] data = requestWithAuth(fullUrl, uri, realm, nonce, qop, opaque);
                        if (data != null && data.length > 0) {
                            return BitmapFactory.decodeByteArray(data, 0, data.length);
                        }
                    }
                }
            } else if (code == 200) {
                byte[] data = readBytes(conn.getInputStream());
                conn.disconnect();
                if (data != null && data.length > 0) {
                    return BitmapFactory.decodeByteArray(data, 0, data.length);
                }
            } else {
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error fetching snapshot ch=" + channel + ": " + e.getMessage());
        }
        return null;
    }

    private static byte[] requestWithAuth(String fullUrl, String uri, String realm, String nonce, String qop, String opaque) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Connection", "close");

            String ha1 = md5(CctvConfig.user + ":" + realm + ":" + CctvConfig.password);
            String ha2 = md5("GET:" + uri);

            String authValue;
            if (qop != null && !qop.isEmpty()) {
                String nc = "00000001";
                String cnonce = "0a4f113b";
                String actualQop = qop.contains("auth") ? "auth" : qop;
                String response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + actualQop + ":" + ha2);
                authValue = "Digest username=\"" + CctvConfig.user + "\", realm=\"" + realm +
                        "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", qop=" + actualQop +
                        ", nc=" + nc + ", cnonce=\"" + cnonce + "\", response=\"" + response + "\"";
            } else {
                String response = md5(ha1 + ":" + nonce + ":" + ha2);
                authValue = "Digest username=\"" + CctvConfig.user + "\", realm=\"" + realm +
                        "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", response=\"" + response + "\"";
            }
            if (opaque != null) {
                authValue += ", opaque=\"" + opaque + "\"";
            }
            conn.setRequestProperty("Authorization", authValue);

            conn.connect();
            int respCode = conn.getResponseCode();
            if (respCode == 200) {
                byte[] data = readBytes(conn.getInputStream());
                conn.disconnect();
                return data;
            } else {
                Log.w(TAG, "Auth request returned HTTP " + respCode + " WWW-Auth=" + conn.getHeaderField("WWW-Authenticate"));
                conn.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Auth request failed: " + e.getMessage());
        }
        return null;
    }

    private static String extractParam(String header, String param) {
        Pattern p = Pattern.compile(param + "=\"([^\"]*)\"");
        Matcher m = p.matcher(header);
        if (m.find()) {
            return m.group(1);
        }
        Pattern p2 = Pattern.compile(param + "=([^,\\s]+)");
        Matcher m2 = p2.matcher(header);
        if (m2.find()) {
            return m2.group(1);
        }
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

    private static String bytesToHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0F];
        }
        return new String(out);
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(bytes);
        } catch (Exception e) {
            return "";
        }
    }
}
