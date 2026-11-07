package com.gathin.fileserver;

import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class FtpServer {
    private static final String TAG = "FtpFileServer";
    private final int port;
    private final File rootDir;
    private final Runnable onActivity;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private volatile boolean running = false;

    public FtpServer(int port, File rootDir, Runnable onActivity) {
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
        Log.i(TAG, "FTP Server started on port " + port);
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
        Log.i(TAG, "FTP Server stopped");
    }

    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                pool.execute(() -> handleSession(socket));
            } catch (Exception e) {
                if (running) Log.w(TAG, "FTP Accept error", e);
            }
        }
    }

    private void handleSession(Socket controlSocket) {
        if (onActivity != null) onActivity.run();
        try (controlSocket) {
            controlSocket.setSoTimeout(60000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.UTF_8));

            sendReply(writer, 220, "CleanYourTV File Server ready.");

            File currentDir = rootDir;
            ServerSocket passiveServer = null;
            File renameFrom = null;

            String line;
            while ((line = reader.readLine()) != null) {
                if (onActivity != null) onActivity.run();
                line = line.trim();
                if (line.isEmpty()) continue;

                int space = line.indexOf(' ');
                String cmd = (space != -1 ? line.substring(0, space) : line).toUpperCase(Locale.US);
                String arg = space != -1 ? line.substring(space + 1).trim() : "";

                switch (cmd) {
                    case "USER":
                        sendReply(writer, 331, "User name okay, need password.");
                        break;
                    case "PASS":
                        sendReply(writer, 230, "User logged in, proceed.");
                        break;
                    case "SYST":
                        sendReply(writer, 215, "UNIX Type: L8");
                        break;
                    case "FEAT":
                        writer.write("211-Features:\r\n UTF8\r\n SIZE\r\n MLSD\r\n211 End\r\n");
                        writer.flush();
                        break;
                    case "OPTS":
                        if (arg.equalsIgnoreCase("UTF8 ON")) sendReply(writer, 200, "UTF8 set to on");
                        else sendReply(writer, 200, "OK");
                        break;
                    case "PWD":
                    case "XPWD":
                        String p = getDisplayPath(currentDir);
                        sendReply(writer, 257, "\"" + p + "\" is current directory.");
                        break;
                    case "TYPE":
                        sendReply(writer, 200, "Type set to " + arg);
                        break;
                    case "CWD":
                    case "XCWD":
                        File nextDir = resolvePath(currentDir, arg);
                        if (nextDir.exists() && nextDir.isDirectory()) {
                            currentDir = nextDir;
                            sendReply(writer, 250, "Directory changed to " + getDisplayPath(currentDir));
                        } else {
                            sendReply(writer, 550, "Failed to change directory.");
                        }
                        break;
                    case "CDUP":
                    case "XCUP":
                        if (!currentDir.equals(rootDir)) {
                            File parent = currentDir.getParentFile();
                            if (parent != null && parent.getAbsolutePath().startsWith(rootDir.getAbsolutePath())) {
                                currentDir = parent;
                            } else {
                                currentDir = rootDir;
                            }
                        }
                        sendReply(writer, 250, "Directory changed to " + getDisplayPath(currentDir));
                        break;
                    case "PASV":
                        if (passiveServer != null) {
                            try { passiveServer.close(); } catch (Exception ignored) {}
                        }
                        passiveServer = new ServerSocket(0, 1, controlSocket.getLocalAddress());
                        int pPort = passiveServer.getLocalPort();
                        byte[] addr = controlSocket.getLocalAddress().getAddress();
                        int p1 = (pPort >> 8) & 0xff;
                        int p2 = pPort & 0xff;
                        String pasvReply = String.format(Locale.US,
                                "227 Entering Passive Mode (%d,%d,%d,%d,%d,%d).",
                                addr[0] & 0xff, addr[1] & 0xff, addr[2] & 0xff, addr[3] & 0xff, p1, p2);
                        sendReply(writer, 227, pasvReply.substring(4));
                        break;
                    case "EPSV":
                        if (passiveServer != null) {
                            try { passiveServer.close(); } catch (Exception ignored) {}
                        }
                        passiveServer = new ServerSocket(0, 1, controlSocket.getLocalAddress());
                        sendReply(writer, 229, "Entering Extended Passive Mode (|||" + passiveServer.getLocalPort() + "|)");
                        break;
                    case "LIST":
                    case "NLST":
                        if (passiveServer == null) {
                            sendReply(writer, 425, "Use PASV first.");
                            break;
                        }
                        sendReply(writer, 150, "Opening ASCII mode data connection for file list.");
                        try (Socket dataSocket = passiveServer.accept()) {
                            passiveServer.close();
                            passiveServer = null;
                            BufferedWriter dataWriter = new BufferedWriter(new OutputStreamWriter(dataSocket.getOutputStream(), StandardCharsets.UTF_8));
                            File targetDir = arg.isEmpty() ? currentDir : resolvePath(currentDir, arg);
                            File[] files = targetDir.listFiles();
                            if (files != null) {
                                SimpleDateFormat df = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
                                for (File f : files) {
                                    String type = f.isDirectory() ? "d" : "-";
                                    String date = df.format(new Date(f.lastModified()));
                                    String entry = String.format(Locale.US, "%srwxrwxrwx 1 owner group %12d %s %s\r\n",
                                            type, f.length(), date, f.getName());
                                    dataWriter.write(entry);
                                }
                            }
                            dataWriter.flush();
                        }
                        sendReply(writer, 226, "Transfer complete.");
                        break;
                    case "SIZE":
                        File fSize = resolvePath(currentDir, arg);
                        if (fSize.exists() && fSize.isFile()) {
                            sendReply(writer, 213, String.valueOf(fSize.length()));
                        } else {
                            sendReply(writer, 550, "File not found.");
                        }
                        break;
                    case "RETR":
                        if (passiveServer == null) {
                            sendReply(writer, 425, "Use PASV first.");
                            break;
                        }
                        File fRetr = resolvePath(currentDir, arg);
                        if (!fRetr.exists() || !fRetr.isFile()) {
                            sendReply(writer, 550, "File not found.");
                            try { passiveServer.close(); } catch (Exception ignored) {}
                            passiveServer = null;
                            break;
                        }
                        sendReply(writer, 150, "Opening binary data connection for " + fRetr.getName());
                        try (Socket dataSocket = passiveServer.accept();
                             InputStream fis = new FileInputStream(fRetr);
                             OutputStream dataOut = dataSocket.getOutputStream()) {
                            passiveServer.close();
                            passiveServer = null;
                            byte[] buf = new byte[65536];
                            int r;
                            while ((r = fis.read(buf)) != -1) dataOut.write(buf, 0, r);
                            dataOut.flush();
                        }
                        sendReply(writer, 226, "Transfer complete.");
                        break;
                    case "STOR":
                        if (passiveServer == null) {
                            sendReply(writer, 425, "Use PASV first.");
                            break;
                        }
                        File fStor = resolvePath(currentDir, arg);
                        sendReply(writer, 150, "Opening binary data connection for " + fStor.getName());
                        try (Socket dataSocket = passiveServer.accept();
                             OutputStream fos = new FileOutputStream(fStor);
                             InputStream dataIn = dataSocket.getInputStream()) {
                            passiveServer.close();
                            passiveServer = null;
                            byte[] buf = new byte[65536];
                            int r;
                            while ((r = dataIn.read(buf)) != -1) fos.write(buf, 0, r);
                            fos.flush();
                        }
                        sendReply(writer, 226, "Transfer complete.");
                        break;
                    case "DELE":
                        File fDel = resolvePath(currentDir, arg);
                        if (fDel.exists() && fDel.delete()) {
                            sendReply(writer, 250, "File deleted.");
                        } else {
                            sendReply(writer, 550, "Delete failed.");
                        }
                        break;
                    case "MKD":
                    case "XMKD":
                        File fMkd = resolvePath(currentDir, arg);
                        if (fMkd.mkdirs()) {
                            sendReply(writer, 257, "\"" + getDisplayPath(fMkd) + "\" created.");
                        } else {
                            sendReply(writer, 550, "Create directory failed.");
                        }
                        break;
                    case "RMD":
                    case "XRMD":
                        File fRmd = resolvePath(currentDir, arg);
                        if (fRmd.exists() && fRmd.delete()) {
                            sendReply(writer, 250, "Directory removed.");
                        } else {
                            sendReply(writer, 550, "Remove directory failed.");
                        }
                        break;
                    case "RNFR":
                        renameFrom = resolvePath(currentDir, arg);
                        if (renameFrom.exists()) {
                            sendReply(writer, 350, "Ready for RNTO.");
                        } else {
                            sendReply(writer, 550, "File not found.");
                        }
                        break;
                    case "RNTO":
                        if (renameFrom != null) {
                            File renameTo = resolvePath(currentDir, arg);
                            if (renameFrom.renameTo(renameTo)) {
                                sendReply(writer, 250, "Rename successful.");
                            } else {
                                sendReply(writer, 550, "Rename failed.");
                            }
                            renameFrom = null;
                        } else {
                            sendReply(writer, 503, "Bad sequence of commands.");
                        }
                        break;
                    case "NOOP":
                        sendReply(writer, 200, "OK");
                        break;
                    case "QUIT":
                        sendReply(writer, 221, "Goodbye.");
                        return;
                    default:
                        sendReply(writer, 502, "Command not implemented.");
                        break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "FTP session error", e);
        }
    }

    private File resolvePath(File currentDir, String path) {
        if (path == null || path.isEmpty()) return currentDir;
        if (path.startsWith("/")) {
            String clean = path.replace("..", "").replace("//", "/");
            if (clean.startsWith("/")) clean = clean.substring(1);
            return new File(rootDir, clean);
        }
        String clean = path.replace("..", "");
        return new File(currentDir, clean);
    }

    private String getDisplayPath(File f) {
        String abs = f.getAbsolutePath();
        String rAbs = rootDir.getAbsolutePath();
        if (abs.equals(rAbs)) return "/";
        if (abs.startsWith(rAbs)) {
            return abs.substring(rAbs.length());
        }
        return "/";
    }

    private void sendReply(BufferedWriter writer, int code, String text) throws IOException {
        writer.write(code + " " + text + "\r\n");
        writer.flush();
    }
}
