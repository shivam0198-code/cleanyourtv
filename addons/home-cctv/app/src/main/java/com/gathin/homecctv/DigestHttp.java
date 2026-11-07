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

    private static String cachedRealm = null;
    private static String cachedNonce = null;

    public static Bitmap fetchSnapshot(int channel) {
        String uri = CctvConfig.getSnapshotUri(channel);
        String fullUrl = CctvConfig.getSnapshotUrl(channel);

        try {
            // If we have cached realm and nonce, try authenticating on 1st request
            if (cachedRealm != null && cachedNonce != null) {
                byte[] data = requestWithAuth(fullUrl, uri, cachedRealm, cachedNonce);
                if (data != null && data.length > 0) {
                    return BitmapFactory.decodeByteArray(data, 0, data.length);
                }
            }

            // Otherwise, make standard request to get 401 and extract nonce
            HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            conn.connect();

            int code = conn.getResponseCode();
            if (code == 401) {
                String authHeader = conn.getHeaderField("WWW-Authenticate");
                conn.disconnect();

                if (authHeader != null && authHeader.contains("Digest")) {
                    cachedRealm = extractParam(authHeader, "realm");
                    cachedNonce = extractParam(authHeader, "nonce");

                    if (cachedRealm != null && cachedNonce != null) {
                        byte[] data = requestWithAuth(fullUrl, uri, cachedRealm, cachedNonce);
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

    private static byte[] requestWithAuth(String fullUrl, String uri, String realm, String nonce) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");

            String ha1 = md5(CctvConfig.user + ":" + realm + ":" + CctvConfig.password);
            String ha2 = md5("GET:" + uri);
            String response = md5(ha1 + ":" + nonce + ":" + ha2);

            String authValue = "Digest username=\"" + CctvConfig.user + "\", realm=\"" + realm +
                    "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\", response=\"" + response + "\"";
            conn.setRequestProperty("Authorization", authValue);

            conn.connect();
            if (conn.getResponseCode() == 200) {
                byte[] data = readBytes(conn.getInputStream());
                conn.disconnect();
                return data;
            } else {
                conn.disconnect();
            }
        } catch (Exception e) {
            // Ignore and retry
        }
        return null;
    }

    private static String extractParam(String header, String param) {
        Pattern p = Pattern.compile(param + "=\"([^\"]+)\"");
        Matcher m = p.matcher(header);
        if (m.find()) {
            return m.group(1);
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
