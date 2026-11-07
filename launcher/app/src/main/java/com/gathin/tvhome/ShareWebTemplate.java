package com.gathin.tvhome;

import java.io.File;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

final class ShareWebTemplate {

    static String render(File currentDir, File rootDir, String notice) {
        String relPath = getRelativePath(currentDir, rootDir);
        File[] files = currentDir.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        long freeBytes = rootDir.getFreeSpace();
        long totalBytes = rootDir.getTotalSpace();
        String storageInfo = String.format(Locale.US, "%.1f GB free of %.1f GB",
                freeBytes / 1_073_741_824.0, totalBytes / 1_073_741_824.0);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder rows = new StringBuilder();

        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            boolean isDir = f.isDirectory();
            String fRel = getRelativePath(f, rootDir);
            String sizeStr = isDir ? "-" : formatSize(f.length());
            String dateStr = sdf.format(new Date(f.lastModified()));

            rows.append("<tr><td>");
            if (isDir) {
                rows.append("<a href='/?path=").append(urlEncode(fRel)).append("'>📁 <b>").append(escape(name)).append("</b></a>");
            } else {
                rows.append("📄 ").append(escape(name));
            }
            rows.append("</td><td class='muted'>").append(sizeStr).append("</td>");
            rows.append("<td class='muted'>").append(dateStr).append("</td>");
            rows.append("<td class='actions'>");
            if (!isDir) {
                rows.append("<a class='btn-action btn-dl' href='/download?path=").append(urlEncode(fRel)).append("'>Download</a> ");
            }
            rows.append("<form method='POST' action='/delete' style='display:inline;' onsubmit='return confirm(\"Delete ").append(escape(name)).append("?\");'>")
                .append("<input type='hidden' name='path' value='").append(escape(fRel)).append("'>")
                .append("<button type='submit' class='btn-action btn-del'>Delete</button>")
                .append("</form>");
            rows.append("</td></tr>");
        }

        StringBuilder breadcrumbs = new StringBuilder("<a href='/?path='>Home (/sdcard)</a>");
        if (!relPath.isEmpty()) {
            String[] parts = relPath.split("/");
            StringBuilder cur = new StringBuilder();
            for (String p : parts) {
                if (p.isEmpty()) continue;
                if (cur.length() > 0) cur.append("/");
                cur.append(p);
                breadcrumbs.append(" &rsaquo; <a href='/?path=").append(urlEncode(cur.toString())).append("'>").append(escape(p)).append("</a>");
            }
        }

        String noticeHtml = "";
        if (notice != null && !notice.isEmpty()) {
            noticeHtml = "<div class='notice'>" + escape(notice) + "</div>";
        }

        return "<!DOCTYPE html>\n" +
                "<html lang='en'>\n" +
                "<head>\n" +
                "  <meta charset='utf-8'>\n" +
                "  <meta name='viewport' content='width=device-width, initial-scale=1'>\n" +
                "  <title>Mi TV File Drop</title>\n" +
                "  <style>\n" +
                "    :root { --bg: #0e1022; --card: #181b33; --border: #2c3258; --accent: #39c5cf; --text: #ffffff; --muted: #8b92b2; }\n" +
                "    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text); padding: 20px; }\n" +
                "    .container { max-width: 960px; margin: 0 auto; }\n" +
                "    header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; flex-wrap: wrap; gap: 10px; }\n" +
                "    h1 { margin: 0; font-size: 24px; color: var(--accent); }\n" +
                "    .storage { color: var(--muted); font-size: 14px; }\n" +
                "    .shortcuts { display: flex; gap: 8px; margin-bottom: 20px; flex-wrap: wrap; }\n" +
                "    .shortcut { background: var(--card); border: 1px solid var(--border); padding: 6px 14px; border-radius: 20px; color: var(--text); text-decoration: none; font-size: 13px; font-weight: 500; }\n" +
                "    .shortcut:hover { border-color: var(--accent); }\n" +
                "    .upload-box { background: var(--card); border: 2px dashed var(--border); border-radius: 12px; padding: 24px; text-align: center; margin-bottom: 24px; transition: border-color 0.2s; }\n" +
                "    .upload-box.dragover { border-color: var(--accent); background: rgba(57, 197, 207, 0.05); }\n" +
                "    .breadcrumbs { font-size: 15px; margin-bottom: 16px; color: var(--muted); }\n" +
                "    .breadcrumbs a { color: var(--accent); text-decoration: none; }\n" +
                "    table { width: 100%; border-collapse: collapse; background: var(--card); border-radius: 12px; overflow: hidden; }\n" +
                "    th, td { padding: 12px 16px; text-align: left; border-bottom: 1px solid var(--border); font-size: 14px; }\n" +
                "    th { background: rgba(0,0,0,0.2); color: var(--muted); font-weight: 600; }\n" +
                "    tr:hover td { background: rgba(255,255,255,0.02); }\n" +
                "    a { color: var(--text); text-decoration: none; }\n" +
                "    a:hover { color: var(--accent); }\n" +
                "    .muted { color: var(--muted); }\n" +
                "    .actions { text-align: right; white-space: nowrap; }\n" +
                "    .btn-action { padding: 4px 10px; border-radius: 6px; font-size: 12px; border: none; cursor: pointer; text-decoration: none; font-weight: 500; display: inline-block; }\n" +
                "    .btn-dl { background: rgba(57, 197, 207, 0.2); color: var(--accent); }\n" +
                "    .btn-del { background: rgba(255, 85, 85, 0.2); color: #ff5555; }\n" +
                "    .btn-primary { background: var(--accent); color: #000; padding: 8px 18px; border-radius: 8px; border: none; font-weight: bold; cursor: pointer; }\n" +
                "    .notice { background: rgba(57, 197, 207, 0.15); border: 1px solid var(--accent); padding: 10px 16px; border-radius: 8px; margin-bottom: 16px; }\n" +
                "    .folder-form { display: flex; gap: 8px; margin-top: 16px; }\n" +
                "    .input-text { background: var(--bg); border: 1px solid var(--border); color: #fff; padding: 6px 12px; border-radius: 6px; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class='container'>\n" +
                "    <header>\n" +
                "      <div>\n" +
                "        <h1>📺 CleanYourTV Wireless Drop</h1>\n" +
                "        <span class='storage'>Internal Storage: " + storageInfo + "</span>\n" +
                "      </div>\n" +
                "    </header>\n" +
                "\n" +
                "    <div class='shortcuts'>\n" +
                "      <a class='shortcut' href='/?path=Roms/NES'>🎮 NES Games Folder</a>\n" +
                "      <a class='shortcut' href='/?path=Download'>📥 Downloads</a>\n" +
                "      <a class='shortcut' href='/?path=Movies'>🎬 Movies</a>\n" +
                "      <a class='shortcut' href='/?path='>📁 Root (/sdcard)</a>\n" +
                "    </div>\n" +
                "\n" +
                noticeHtml +
                "\n" +
                "    <div class='upload-box' id='dropZone'>\n" +
                "      <form id='uploadForm' method='POST' action='/upload' enctype='multipart/form-data'>\n" +
                "        <input type='hidden' name='dir' value='" + escape(relPath) + "'>\n" +
                "        <p style='margin:0 0 10px 0; font-size: 16px;'><b>Drag &amp; drop files here to upload to this folder</b></p>\n" +
                "        <input type='file' name='file' id='fileInput' multiple style='display:none;' onchange='document.getElementById(\"uploadForm\").submit();'>\n" +
                "        <button type='button' class='btn-primary' onclick='document.getElementById(\"fileInput\").click();'>Select Files to Upload</button>\n" +
                "      </form>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class='breadcrumbs'>" + breadcrumbs + "</div>\n" +
                "\n" +
                "    <table>\n" +
                "      <thead><tr><th>Name</th><th>Size</th><th>Modified</th><th style='text-align:right;'>Action</th></tr></thead>\n" +
                "      <tbody>\n" +
                rows +
                "      </tbody>\n" +
                "    </table>\n" +
                "\n" +
                "    <form class='folder-form' method='POST' action='/mkdir'>\n" +
                "      <input type='hidden' name='dir' value='" + escape(relPath) + "'>\n" +
                "      <input type='text' name='name' placeholder='New folder name' class='input-text' required>\n" +
                "      <button type='submit' class='btn-primary' style='padding:6px 14px;'>Create Folder</button>\n" +
                "    </form>\n" +
                "  </div>\n" +
                "  <script>\n" +
                "    const zone = document.getElementById('dropZone');\n" +
                "    ['dragenter', 'dragover'].forEach(e => zone.addEventListener(e, (ev) => { ev.preventDefault(); zone.classList.add('dragover'); }));\n" +
                "    ['dragleave', 'drop'].forEach(e => zone.addEventListener(e, (ev) => { ev.preventDefault(); zone.classList.remove('dragover'); }));\n" +
                "    zone.addEventListener('drop', (ev) => {\n" +
                "      const files = ev.dataTransfer.files;\n" +
                "      if (files.length > 0) {\n" +
                "        document.getElementById('fileInput').files = files;\n" +
                "        document.getElementById('uploadForm').submit();\n" +
                "      }\n" +
                "    });\n" +
                "  </script>\n" +
                "</body>\n" +
                "</html>";
    }

    private static String getRelativePath(File child, File root) {
        String c = child.getAbsolutePath();
        String r = root.getAbsolutePath();
        if (c.equals(r)) return "";
        if (c.startsWith(r)) {
            String sub = c.substring(r.length());
            return sub.startsWith("/") ? sub.substring(1) : sub;
        }
        return "";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception ignored) {
            return s;
        }
    }
}
