package com.gathin.tvhome;

import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ShareServer {
    private static final String TAG = "ShareServer";
    private final Context context;
    private final int port;
    private final File rootDir;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running = false;

    public interface UploadListener {
        void onUploadProgress(String filename, int percent, long bytesReceived, long totalBytes);
        void onFileUploaded(File file);
    }
    private UploadListener uploadListener;

    public void setUploadListener(UploadListener listener) {
        this.uploadListener = listener;
    }

    ShareServer(Context context, int port, File rootDir) {
        this.context = context;
        this.port = port;
        this.rootDir = rootDir;
    }

    ShareServer(int port, File rootDir) {
        this(null, port, rootDir);
    }

    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        pool = Executors.newCachedThreadPool();
        running = true;
        pool.execute(this::acceptLoop);
        Log.i(TAG, "ShareServer started on port " + port);
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
        Log.i(TAG, "ShareServer stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                if (pool != null && !pool.isShutdown()) {
                    pool.execute(() -> handleClient(socket));
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "Accept error", e);
            }
        }
    }

    private void handleClient(Socket socket) {
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
            Log.w(TAG, "Error handling client", e);
        }
    }

    private void handleBrowse(OutputStream out, String subPath, String notice) throws IOException {
        File dir = resolvePath(subPath);
        if (!dir.exists() || !dir.isDirectory()) {
            dir = rootDir;
        }
        String html = ShareWebTemplate.render(dir, rootDir, notice);
        sendResponse(out, "200 OK", "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void handleDownload(OutputStream out, String subPath) throws IOException {
        File f = resolvePath(subPath);
        if (!f.exists() || f.isDirectory()) {
            send404(out);
            return;
        }

        PrintWriter pw = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), false);
        pw.print("HTTP/1.1 200 OK\r\n");
        pw.print("Content-Type: application/octet-stream\r\n");
        pw.print("Content-Length: " + f.length() + "\r\n");
        pw.print("Content-Disposition: attachment; filename=\"" + f.getName() + "\"\r\n");
        pw.print("Connection: close\r\n\r\n");
        pw.flush();

        try (InputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[16384];
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

        String boundary = null;
        int bIdx = ctype.indexOf("boundary=");
        if (bIdx != -1) {
            boundary = ctype.substring(bIdx + 9).trim();
            if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
                boundary = boundary.substring(1, boundary.length() - 1);
            }
        }
        if (boundary == null || boundary.isEmpty()) {
            sendResponse(out, "400 Bad Request", "application/json", "{\"ok\":false,\"error\":\"Missing boundary\"}".getBytes(StandardCharsets.UTF_8));
            return;
        }

        String partDelimiter = "--" + boundary;
        byte[] targetDelim = ("\r\n" + partDelimiter).getBytes(StandardCharsets.ISO_8859_1);

        MultipartReader mr = new MultipartReader(in);
        String firstLine;
        while ((firstLine = mr.readLine()) != null) {
            if (firstLine.startsWith(partDelimiter)) break;
        }

        long totalContentBytes = 0;
        try {
            totalContentBytes = Long.parseLong(headers.getOrDefault("content-length", "0"));
        } catch (Exception ignored) {}

        String targetDirRel = "";
        File uploadedFile = null;
        boolean isLast = false;

        while (!isLast) {
            Map<String, String> partHeaders = new HashMap<>();
            String hLine;
            while ((hLine = mr.readLine()) != null && !hLine.isEmpty()) {
                int colon = hLine.indexOf(':');
                if (colon != -1) {
                    partHeaders.put(hLine.substring(0, colon).trim().toLowerCase(Locale.US),
                                    hLine.substring(colon + 1).trim());
                }
            }
            if (partHeaders.isEmpty()) break;

            String cd = partHeaders.get("content-disposition");
            if (cd == null) {
                isLast = mr.streamPart(targetDelim, null, null);
                continue;
            }

            if (cd.contains("name=\"dir\"") && !cd.contains("filename=\"")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                isLast = mr.streamPart(targetDelim, bos, null);
                targetDirRel = bos.toString("UTF-8").trim();
            } else if (cd.contains("filename=\"")) {
                int fStart = cd.indexOf("filename=\"") + 10;
                int fEnd = cd.indexOf("\"", fStart);
                String filename = (fStart != -1 && fEnd != -1) ? cd.substring(fStart, fEnd) : "uploaded_file";
                filename = new File(filename).getName();

                File destDir = resolvePath(targetDirRel);
                if (!destDir.exists()) destDir.mkdirs();
                File destFile = new File(destDir, filename);

                final long total = totalContentBytes;
                final String fn = filename;
                final int[] lastPercent = {-1};

                try (FileOutputStream fos = new FileOutputStream(destFile)) {
                    isLast = mr.streamPart(targetDelim, fos, bytesStreamed -> {
                        if (uploadListener != null && total > 0) {
                            int pct = (int) Math.min(99, (bytesStreamed * 100) / total);
                            if (pct != lastPercent[0]) {
                                lastPercent[0] = pct;
                                uploadListener.onUploadProgress(fn, pct, bytesStreamed, total);
                            }
                        } else if (uploadListener != null) {
                            uploadListener.onUploadProgress(fn, -1, bytesStreamed, 0);
                        }
                    });
                }
                if (uploadListener != null) {
                    uploadListener.onUploadProgress(filename, 100, destFile.length(), destFile.length());
                }
                uploadedFile = destFile;
            } else {
                isLast = mr.streamPart(targetDelim, null, null);
            }
        }

        String accept = headers.getOrDefault("accept", "");
        String xrw = headers.getOrDefault("x-requested-with", "");
        if (accept.contains("application/json") || "XMLHttpRequest".equalsIgnoreCase(xrw)) {
            String json = "{\"ok\":true,\"file\":\"" + (uploadedFile != null ? uploadedFile.getName() : "") + "\"}";
            sendResponse(out, "200 OK", "application/json", json.getBytes(StandardCharsets.UTF_8));
        } else {
            sendRedirect(out, "/?path=" + URLEncoder.encode(targetDirRel, "UTF-8") + "&msg=Upload+successful");
        }

        if (uploadedFile != null && uploadListener != null) {
            final File finalFile = uploadedFile;
            uploadListener.onFileUploaded(finalFile);
        }
    }

    private static final class MultipartReader {
        private final InputStream in;
        private final byte[] buf = new byte[65536];
        private int head = 0;
        private int tail = 0;
        private boolean eof = false;

        MultipartReader(InputStream in) {
            this.in = in;
        }

        private boolean fill() throws IOException {
            if (eof) return false;
            if (head > 0) {
                int rem = tail - head;
                if (rem > 0) {
                    System.arraycopy(buf, head, buf, 0, rem);
                }
                head = 0;
                tail = rem;
            }
            int space = buf.length - tail;
            if (space <= 0) return true;
            int r = in.read(buf, tail, space);
            if (r <= 0) {
                eof = true;
                return tail > head;
            }
            tail += r;
            return true;
        }

        public String readLine() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            while (true) {
                if (head >= tail) {
                    if (!fill()) {
                        if (baos.size() == 0) return null;
                        break;
                    }
                }
                byte b = buf[head++];
                if (b == '\n') {
                    break;
                } else if (b == '\r') {
                    if (head >= tail) fill();
                    if (head < tail && buf[head] == '\n') {
                        head++;
                    }
                    break;
                } else {
                    baos.write(b);
                }
            }
            return baos.toString("UTF-8");
        }

        public interface ProgressSink {
            void onProgress(long totalBytesStreamed);
        }

        public boolean streamPart(byte[] delim, OutputStream sink, ProgressSink progress) throws IOException {
            int delimLen = delim.length;
            long bytesStreamed = 0;
            while (true) {
                int match = indexOf(buf, head, tail, delim);
                if (match != -1) {
                    int writeLen = match - head;
                    if (writeLen > 0 && sink != null) {
                        sink.write(buf, head, writeLen);
                        bytesStreamed += writeLen;
                        if (progress != null) progress.onProgress(bytesStreamed);
                    }
                    if (sink != null) sink.flush();

                    head = match + delimLen;
                    while (tail - head < 2 && !eof) {
                        fill();
                    }

                    boolean isLast = false;
                    if (tail - head >= 2 && buf[head] == '-' && buf[head + 1] == '-') {
                        isLast = true;
                        head += 2;
                    }
                    readLine();
                    return isLast;
                }

                int available = tail - head;
                if (available > delimLen - 1) {
                    int writeLen = available - (delimLen - 1);
                    if (sink != null) {
                        sink.write(buf, head, writeLen);
                        bytesStreamed += writeLen;
                        if (progress != null) progress.onProgress(bytesStreamed);
                    }
                    head += writeLen;
                }

                if (!fill()) {
                    int rem = tail - head;
                    if (rem > 0 && sink != null) {
                        sink.write(buf, head, rem);
                        bytesStreamed += rem;
                        if (progress != null) progress.onProgress(bytesStreamed);
                    }
                    if (sink != null) sink.flush();
                    head = tail;
                    return true;
                }
            }
        }

        private static int indexOf(byte[] src, int start, int end, byte[] target) {
            int targetLen = target.length;
            int max = end - targetLen;
            for (int i = start; i <= max; i++) {
                if (src[i] == target[0]) {
                    boolean match = true;
                    for (int j = 1; j < targetLen; j++) {
                        if (src[i + j] != target[j]) {
                            match = false;
                            break;
                        }
                    }
                    if (match) return i;
                }
            }
            return -1;
        }
    }

    private void handleDelete(InputStream in, OutputStream out, Map<String, String> headers) throws IOException {
        long clen = Long.parseLong(headers.getOrDefault("content-length", "0"));
        byte[] body = readBody(in, (int) clen);
        Map<String, String> form = parseQuery(new String(body, StandardCharsets.UTF_8));
        String targetPath = form.get("path");
        String parentRel = "";
        boolean deleted = false;
        if (targetPath != null) {
            File f = resolvePath(targetPath);
            File parent = f.getParentFile();
            parentRel = parent != null ? getRelativePath(parent, rootDir) : "";
            deleted = deleteTarget(f);
        }
        String accept = headers.getOrDefault("accept", "");
        String xrw = headers.getOrDefault("x-requested-with", "");
        if (accept.contains("application/json") || "XMLHttpRequest".equalsIgnoreCase(xrw)) {
            String status = deleted ? "200 OK" : "400 Bad Request";
            String json = "{\"ok\":" + deleted + (deleted ? "" : ",\"error\":\"Could not delete file or folder\"") + "}";
            sendResponse(out, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
        } else {
            sendRedirect(out, "/?path=" + URLEncoder.encode(parentRel, "UTF-8") + "&msg=" + (deleted ? "Deleted" : "Delete+failed"));
        }
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
        String clean = subPath.replace("..", "").replace("//", "/").trim();
        if (clean.startsWith("/storage/emulated/0/")) {
            clean = clean.substring("/storage/emulated/0/".length());
        } else if (clean.startsWith("/sdcard/")) {
            clean = clean.substring("/sdcard/".length());
        } else if (clean.startsWith("sdcard/")) {
            clean = clean.substring("sdcard/".length());
        }
        if (clean.startsWith("/")) clean = clean.substring(1);
        return new File(rootDir, clean);
    }

    private boolean deleteTarget(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) {
                    deleteTarget(c);
                }
            }
        }
        boolean ok = f.delete();
        if (!ok && f.exists()) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"rm", "-rf", f.getAbsolutePath()});
                p.waitFor();
            } catch (Exception ignored) {}
            if (!f.exists()) return true;

            if (context != null) {
                try {
                    Uri uri = MediaStore.Files.getContentUri("external");
                    context.getContentResolver().delete(uri, MediaStore.MediaColumns.DATA + "=?", new String[]{f.getAbsolutePath()});
                } catch (Exception ignored) {}
            }
            ok = !f.exists();
        }
        return ok;
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
