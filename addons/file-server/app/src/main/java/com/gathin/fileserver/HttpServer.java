package com.gathin.fileserver;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class HttpServer {
    private static final String TAG = "HttpFileServer";
    private final int port;
    private final File rootDir;
    private final Runnable onActivity;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running = false;

    public HttpServer(int port, File rootDir, Runnable onActivity) {
        this.port = port;
        this.rootDir = rootDir;
        this.onActivity = onActivity;
    }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        pool = Executors.newCachedThreadPool();
        running = true;
        pool.execute(this::acceptLoop);
        Log.i(TAG, "HTTP Server started on port " + port);
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        Log.i(TAG, "HTTP Server stopped");
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(() -> handleClient(socket));
            } catch (Exception e) {
                if (running) Log.w(TAG, "Accept error", e);
            }
        }
    }

    private void handleClient(Socket socket) {
        if (onActivity != null) onActivity.run();
        try (socket) {
            socket.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            String line = readLine(in);
            if (line == null || line.isEmpty()) return;

            String[] reqParts = line.split(" ");
            if (reqParts.length < 2) return;
            String method = reqParts[0].toUpperCase(Locale.US);
            String fullUri = reqParts[1];

            Map<String, String> headers = new HashMap<>();
            while (true) {
                String h = readLine(in);
                if (h == null || h.isEmpty()) break;
                int idx = h.indexOf(':');
                if (idx != -1) {
                    headers.put(h.substring(0, idx).trim().toLowerCase(Locale.US), h.substring(idx + 1).trim());
                }
            }

            String path = fullUri;
            String query = "";
            int qIdx = fullUri.indexOf('?');
            if (qIdx != -1) {
                path = fullUri.substring(0, qIdx);
                query = fullUri.substring(qIdx + 1);
            }
            Map<String, String> params = parseQuery(query);

            if (method.equals("GET")) {
                if (path.equals("/") || path.isEmpty()) {
                    handleBrowse(out, params.get("path"), params.get("msg"));
                } else if (path.equals("/download")) {
                    handleDownload(out, params.get("path"));
                } else {
                    send404(out);
                }
            } else if (method.equals("POST")) {
                if (path.equals("/upload")) {
                    handleUpload(in, out, headers);
                } else if (path.equals("/delete")) {
                    handleDelete(in, out, headers);
                } else if (path.equals("/mkdir")) {
                    handleMkdir(in, out, headers);
                } else {
                    send404(out);
                }
            } else {
                sendResponse(out, "405 Method Not Allowed", "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Client handle error", e);
        }
    }

    private void handleBrowse(OutputStream out, String subPath, String msg) throws IOException {
        File target = resolvePath(subPath);
        if (!target.exists() || !target.isDirectory()) target = rootDir;
        String html = WebTemplate.renderDirectory(target, rootDir, msg);
        sendResponse(out, "200 OK", "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void handleDownload(OutputStream out, String subPath) throws IOException {
        File file = resolvePath(subPath);
        if (!file.exists() || !file.isFile()) {
            send404(out);
            return;
        }

        long length = file.length();
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: application/octet-stream\r\n");
        pw.print("Content-Length: " + length + "\r\n");
        pw.print("Content-Disposition: attachment; filename=\"" + file.getName() + "\"\r\n");
        pw.print("Connection: close\r\n\r\n");
        pw.flush();

        byte[] buf = new byte[65536];
        try (InputStream fis = new FileInputStream(file)) {
            int r;
            while ((r = fis.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
        }
    }

    private void handleUpload(InputStream in, OutputStream out, Map<String, String> headers) throws IOException {
        String ctype = headers.get("content-type");
        if (ctype == null || !ctype.contains("multipart/form-data")) {
            sendRedirect(out, "/?msg=Invalid+upload+request");
            return;
        }
        String boundary = "--" + ctype.substring(ctype.indexOf("boundary=") + 9).trim();
        long clen = Long.parseLong(headers.getOrDefault("content-length", "0"));

        byte[] bBytes = boundary.getBytes(StandardCharsets.ISO_8859_1);
        String targetDirRel = "";
        String uploadedFilename = "";

        // Minimal multi-part stream parser
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            chunk.write(b);
            if (chunk.size() >= bBytes.length && endsWith(chunk.toByteArray(), bBytes)) {
                break;
            }
        }

        while (true) {
            String partHeader = readPartHeader(in);
            if (partHeader == null || partHeader.isEmpty()) break;

            if (partHeader.contains("name=\"dir\"")) {
                targetDirRel = readFieldValue(in, bBytes);
            } else if (partHeader.contains("filename=\"")) {
                int fStart = partHeader.indexOf("filename=\"") + 10;
                int fEnd = partHeader.indexOf("\"", fStart);
                uploadedFilename = (fStart != -1 && fEnd != -1) ? partHeader.substring(fStart, fEnd) : "uploaded_file";

                File destDir = resolvePath(targetDirRel);
                if (!destDir.exists()) destDir.mkdirs();
                File destFile = new File(destDir, new File(uploadedFilename).getName());

                writeFileStream(in, destFile, bBytes);
            } else {
                readFieldValue(in, bBytes);
            }
        }

        sendRedirect(out, "/?path=" + URLEncoder.encode(targetDirRel, "UTF-8") + "&msg=Upload+successful");
    }

    private void handleDelete(InputStream in, OutputStream out, Map<String, String> headers) throws IOException {
        long clen = Long.parseLong(headers.getOrDefault("content-length", "0"));
        byte[] body = readBody(in, (int) clen);
        Map<String, String> form = parseQuery(new String(body, StandardCharsets.UTF_8));
        String targetPath = form.get("path");
        String parentRel = "";
        if (targetPath != null) {
            File f = resolvePath(targetPath);
            File parent = f.getParentFile();
            parentRel = parent != null ? getRelativePath(parent, rootDir) : "";
            deleteRecursive(f);
        }
        sendRedirect(out, "/?path=" + URLEncoder.encode(parentRel, "UTF-8") + "&msg=Deleted");
    }

    private void handleMkdir(InputStream in, OutputStream out, Map<String, String> headers) throws IOException {
        long clen = Long.parseLong(headers.getOrDefault("content-length", "0"));
        byte[] body = readBody(in, (int) clen);
        Map<String, String> form = parseQuery(new String(body, StandardCharsets.UTF_8));
        String dir = form.getOrDefault("dir", "");
        String name = form.get("name");
        if (name != null && !name.trim().isEmpty()) {
            File parent = resolvePath(dir);
            File newDir = new File(parent, name.trim());
            newDir.mkdirs();
        }
        sendRedirect(out, "/?path=" + URLEncoder.encode(dir, "UTF-8") + "&msg=Folder+created");
    }

    private File resolvePath(String subPath) {
        if (subPath == null || subPath.trim().isEmpty()) return rootDir;
        String clean = subPath.replace("..", "").replace("//", "/");
        if (clean.startsWith("/")) clean = clean.substring(1);
        return new File(rootDir, clean);
    }

    private void writeFileStream(InputStream in, File dest, byte[] boundary) throws IOException {
        try (OutputStream fos = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int bLen = boundary.length;
            int matchIdx = 0;
            ByteArrayOutputStream potential = new ByteArrayOutputStream();

            int c;
            while ((c = in.read()) != -1) {
                if (c == boundary[matchIdx]) {
                    potential.write(c);
                    matchIdx++;
                    if (matchIdx == bLen) {
                        break; // boundary hit!
                    }
                } else {
                    if (matchIdx > 0) {
                        fos.write(potential.toByteArray());
                        potential.reset();
                        matchIdx = 0;
                    }
                    fos.write(c);
                }
            }
        }
    }

    private String readFieldValue(InputStream in, byte[] boundary) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int matchIdx = 0;
        int c;
        while ((c = in.read()) != -1) {
            if (c == boundary[matchIdx]) {
                matchIdx++;
                if (matchIdx == boundary.length) break;
            } else {
                if (matchIdx > 0) {
                    bos.write(boundary, 0, matchIdx);
                    matchIdx = 0;
                }
                bos.write(c);
            }
        }
        return bos.toString("UTF-8").trim();
    }

    private String readPartHeader(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) for (File c : ch) deleteRecursive(c);
        }
        f.delete();
    }

    private String getRelativePath(File child, File root) {
        String c = child.getAbsolutePath();
        String r = root.getAbsolutePath();
        if (c.equals(r)) return "";
        if (c.startsWith(r)) {
            String sub = c.substring(r.length());
            return sub.startsWith("/") ? sub.substring(1) : sub;
        }
        return "";
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                int next = in.read();
                if (next == '\n' || next == -1) break;
                bos.write(c);
                bos.write(next);
            } else if (c == '\n') {
                break;
            } else {
                bos.write(c);
            }
        }
        if (c == -1 && bos.size() == 0) return null;
        return bos.toString("UTF-8");
    }

    private byte[] readBody(InputStream in, int length) throws IOException {
        byte[] b = new byte[length];
        int read = 0;
        while (read < length) {
            int r = in.read(b, read, length - read);
            if (r == -1) break;
            read += r;
        }
        return b;
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            try {
                if (idx != -1) {
                    m.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"),
                          URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
                } else {
                    m.put(URLDecoder.decode(pair, "UTF-8"), "");
                }
            } catch (Exception ignored) {}
        }
        return m;
    }

    private boolean endsWith(byte[] src, byte[] target) {
        if (src.length < target.length) return false;
        for (int i = 0; i < target.length; i++) {
            if (src[src.length - target.length + i] != target[i]) return false;
        }
        return true;
    }

    private void sendResponse(OutputStream out, String status, String type, byte[] data) throws IOException {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
        pw.print("HTTP/1.1 " + status + "\r\n");
        pw.print("Content-Type: " + type + "\r\n");
        pw.print("Content-Length: " + data.length + "\r\n");
        pw.print("Connection: close\r\n\r\n");
        pw.flush();
        out.write(data);
        out.flush();
    }

    private void sendRedirect(OutputStream out, String target) throws IOException {
        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
        pw.print("HTTP/1.1 303 See Other\r\n");
        pw.print("Location: " + target + "\r\n");
        pw.print("Content-Length: 0\r\n");
        pw.print("Connection: close\r\n\r\n");
        pw.flush();
    }

    private void send404(OutputStream out) throws IOException {
        sendResponse(out, "404 Not Found", "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
    }
}
