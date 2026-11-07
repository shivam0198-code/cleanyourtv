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
        StringBuilder items = new StringBuilder();

        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            boolean isDir = f.isDirectory();
            String fRel = getRelativePath(f, rootDir);
            String sizeStr = isDir ? "-" : formatSize(f.length());
            String dateStr = sdf.format(new Date(f.lastModified()));
            String icon = isDir ? "📁" : (name.toLowerCase(Locale.ROOT).endsWith(".apk") ? "📦" : "📄");

            items.append("<div class='file-item'>\n")
                 .append("  <div class='file-info'>\n");

            if (isDir) {
                items.append("    <a class='file-link' href='/?path=").append(urlEncode(fRel)).append("'>")
                     .append("<span class='file-icon'>").append(icon).append("</span>")
                     .append("<span class='file-name dir-name'>").append(escape(name)).append("</span></a>\n");
            } else {
                items.append("    <div class='file-link'>")
                     .append("<span class='file-icon'>").append(icon).append("</span>")
                     .append("<span class='file-name'>").append(escape(name)).append("</span></div>\n");
            }

            items.append("    <div class='file-meta'>").append(sizeStr).append(" &bull; ").append(dateStr).append("</div>\n")
                 .append("  </div>\n")
                 .append("  <div class='file-actions'>\n");

            if (!isDir) {
                items.append("    <a class='btn-action btn-dl' href='/download?path=").append(urlEncode(fRel)).append("'>Download</a>\n");
            }

            items.append("    <button type='button' class='btn-action btn-del' onclick=\"deleteItem(this, '")
                 .append(escapeJs(fRel)).append("', '").append(escapeJs(name)).append("')\">Delete</button>\n")
                 .append("  </div>\n")
                 .append("</div>\n");
        }

        if (files.length == 0) {
            items.append("<div class='empty-state'>This folder is empty</div>\n");
        }

        StringBuilder breadcrumbs = new StringBuilder("<a href='/?path='>All Files</a>");
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
                "  <meta name='viewport' content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no'>\n" +
                "  <title>TV Drop</title>\n" +
                "  <style>\n" +
                "    :root {\n" +
                "      --bg: #111214;\n" +
                "      --card: #18191c;\n" +
                "      --card-hover: #202226;\n" +
                "      --border: #282a2e;\n" +
                "      --text: #f2f3f5;\n" +
                "      --muted: #8c909a;\n" +
                "      --accent: #ffffff;\n" +
                "      --btn-bg: #26282d;\n" +
                "      --btn-hover: #31343a;\n" +
                "      --danger-bg: rgba(239, 68, 68, 0.12);\n" +
                "      --danger-text: #ef4444;\n" +
                "    }\n" +
                "    * { box-sizing: border-box; margin: 0; padding: 0; }\n" +
                "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background: var(--bg); color: var(--text); padding: 16px 14px 40px; overflow-x: hidden; -webkit-tap-highlight-color: transparent; }\n" +
                "    .container { max-width: 640px; margin: 0 auto; width: 100%; overflow-x: hidden; }\n" +
                "    header { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 18px; flex-wrap: wrap; gap: 8px; }\n" +
                "    h1 { margin: 0; font-size: 22px; font-weight: 700; letter-spacing: -0.02em; color: var(--text); }\n" +
                "    .storage { color: var(--muted); font-size: 13px; font-weight: 400; }\n" +
                "\n" +
                "    /* Hero APK Installer Card */\n" +
                "    .hero-card { background: var(--card); border: 1px solid var(--border); border-radius: 14px; padding: 16px 18px; margin-bottom: 14px; display: flex; justify-content: space-between; align-items: center; gap: 12px; }\n" +
                "    .hero-title { font-size: 15px; font-weight: 600; color: var(--text); }\n" +
                "    .hero-desc { font-size: 12px; color: var(--muted); margin-top: 2px; }\n" +
                "    .btn-hero { background: var(--accent); color: #111214; border: none; padding: 10px 18px; border-radius: 24px; font-size: 14px; font-weight: 600; cursor: pointer; white-space: nowrap; flex-shrink: 0; transition: opacity 0.15s; }\n" +
                "    .btn-hero:active { opacity: 0.8; }\n" +
                "\n" +
                "    /* Shortcuts */\n" +
                "    .shortcuts { display: flex; gap: 8px; margin-bottom: 16px; overflow-x: auto; padding-bottom: 4px; }\n" +
                "    .shortcut { background: var(--card); border: 1px solid var(--border); padding: 7px 14px; border-radius: 20px; color: var(--text); text-decoration: none; font-size: 13px; font-weight: 500; white-space: nowrap; transition: background 0.15s; }\n" +
                "    .shortcut:active { background: var(--card-hover); }\n" +
                "\n" +
                "    /* Notice */\n" +
                "    .notice { background: rgba(255,255,255,0.06); border: 1px solid var(--border); padding: 10px 14px; border-radius: 10px; margin-bottom: 14px; font-size: 13px; color: var(--text); }\n" +
                "\n" +
                "    /* Drop Zone */\n" +
                "    .upload-box { background: var(--card); border: 1px dashed var(--border); border-radius: 12px; padding: 18px 14px; text-align: center; margin-bottom: 16px; transition: border-color 0.2s, background 0.2s; }\n" +
                "    .upload-box.dragover { border-color: var(--accent); background: var(--card-hover); }\n" +
                "    .btn-upload { background: var(--btn-bg); color: var(--text); border: 1px solid var(--border); padding: 8px 16px; border-radius: 20px; font-size: 13px; font-weight: 500; cursor: pointer; }\n" +
                "\n" +
                "    /* Breadcrumbs */\n" +
                "    .breadcrumbs { font-size: 13px; margin-bottom: 12px; color: var(--muted); white-space: nowrap; overflow-x: auto; }\n" +
                "    .breadcrumbs a { color: var(--text); text-decoration: none; font-weight: 500; }\n" +
                "\n" +
                "    /* Progress Card */\n" +
                "    .progress-card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 14px 16px; margin-bottom: 14px; }\n" +
                "    .progress-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-size: 13.5px; font-weight: 600; }\n" +
                "    .progress-name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 80%; }\n" +
                "    .progress-percent { font-family: monospace; font-size: 14px; color: var(--accent); }\n" +
                "    .progress-bar-bg { width: 100%; height: 6px; background: rgba(255,255,255,0.08); border-radius: 3px; overflow: hidden; margin-bottom: 8px; }\n" +
                "    .progress-bar-fill { height: 100%; background: var(--accent); width: 0%; transition: width 0.12s ease; border-radius: 3px; }\n" +
                "    .progress-stats { font-size: 12px; color: var(--muted); }\n" +
                "\n" +
                "    /* Mobile-first File List (Guaranteed 0 horizontal scroll) */\n" +
                "    .file-list { display: flex; flex-direction: column; gap: 6px; width: 100%; box-sizing: border-box; }\n" +
                "    .file-item { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; align-items: center; justify-content: space-between; gap: 10px; min-width: 0; box-sizing: border-box; }\n" +
                "    .file-info { min-width: 0; flex: 1 1 auto; }\n" +
                "    .file-link { display: flex; align-items: center; gap: 8px; text-decoration: none; color: var(--text); min-width: 0; }\n" +
                "    .file-icon { font-size: 16px; flex-shrink: 0; }\n" +
                "    .file-name { font-size: 13.5px; font-weight: 500; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; color: var(--text); }\n" +
                "    .dir-name { font-weight: 600; }\n" +
                "    .file-meta { font-size: 11.5px; color: var(--muted); margin-top: 3px; margin-left: 24px; }\n" +
                "    .file-actions { display: flex; gap: 6px; flex-shrink: 0; align-items: center; }\n" +
                "    .btn-action { padding: 5px 11px; border-radius: 16px; font-size: 12px; border: none; cursor: pointer; text-decoration: none; font-weight: 500; display: inline-flex; align-items: center; justify-content: center; }\n" +
                "    .btn-dl { background: var(--btn-bg); color: var(--text); border: 1px solid var(--border); }\n" +
                "    .btn-del { background: var(--danger-bg); color: var(--danger-text); border: none; }\n" +
                "    .btn-del:disabled { opacity: 0.5; }\n" +
                "    .empty-state { text-align: center; padding: 30px 10px; color: var(--muted); font-size: 13px; background: var(--card); border: 1px solid var(--border); border-radius: 10px; }\n" +
                "\n" +
                "    /* Create Folder */\n" +
                "    .folder-form { display: flex; gap: 8px; margin-top: 16px; }\n" +
                "    .input-text { background: var(--card); border: 1px solid var(--border); color: #fff; padding: 8px 12px; border-radius: 18px; flex: 1; font-size: 13px; min-width: 0; outline: none; }\n" +
                "    .input-text:focus { border-color: #555; }\n" +
                "    .btn-mkdir { background: var(--btn-bg); color: var(--text); border: 1px solid var(--border); padding: 8px 14px; border-radius: 18px; font-size: 13px; font-weight: 500; cursor: pointer; white-space: nowrap; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class='container'>\n" +
                "    <header>\n" +
                "      <div>\n" +
                "        <h1>TV Drop</h1>\n" +
                "        <span class='storage'>TV Storage: " + storageInfo + "</span>\n" +
                "      </div>\n" +
                "    </header>\n" +
                "\n" +
                "    <div class='hero-card'>\n" +
                "      <div class='hero-text'>\n" +
                "        <div class='hero-title'>Install APK on TV</div>\n" +
                "        <div class='hero-desc'>Instant 1-click install on your TV</div>\n" +
                "      </div>\n" +
                "      <form id='apkForm' method='POST' action='/upload' enctype='multipart/form-data'>\n" +
                "        <input type='hidden' name='dir' value='Download'>\n" +
                "        <input type='file' name='file' id='apkInput' accept='.apk' style='display:none;' onchange='submitUpload(this, \"Download\")'>\n" +
                "        <button type='button' class='btn-hero' id='btnApkHero' onclick='document.getElementById(\"apkInput\").click();'>📦 Choose APK</button>\n" +
                "      </form>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class='progress-card' id='progressCard' style='display:none;'>\n" +
                "      <div class='progress-header'>\n" +
                "        <span id='progressTitle' class='progress-name'>Uploading file...</span>\n" +
                "        <span id='progressPercent' class='progress-percent'>0%</span>\n" +
                "      </div>\n" +
                "      <div class='progress-bar-bg'>\n" +
                "        <div id='progressBarFill' class='progress-bar-fill'></div>\n" +
                "      </div>\n" +
                "      <div id='progressStats' class='progress-stats'>Starting upload...</div>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class='shortcuts'>\n" +
                "      <a class='shortcut' href='/?path=Download'>📥 Downloads</a>\n" +
                "      <a class='shortcut' href='/?path=ROMs'>🎮 ROMs</a>\n" +
                "      <a class='shortcut' href='/?path='>📁 All Files</a>\n" +
                "    </div>\n" +
                "\n" +
                noticeHtml +
                "\n" +
                "    <div class='upload-box' id='dropZone'>\n" +
                "      <form id='uploadForm' method='POST' action='/upload' enctype='multipart/form-data'>\n" +
                "        <input type='hidden' name='dir' value='" + escape(relPath) + "'>\n" +
                "        <p style='margin:0 0 10px 0; font-size: 13px; color: var(--muted);'>Drop files here or upload to this folder</p>\n" +
                "        <input type='file' name='file' id='fileInput' multiple style='display:none;' onchange='submitUpload(this)'>\n" +
                "        <button type='button' class='btn-upload' id='btnUploadGeneric' onclick='document.getElementById(\"fileInput\").click();'>Upload Files</button>\n" +
                "      </form>\n" +
                "    </div>\n" +
                "\n" +
                "    <div class='breadcrumbs'>" + breadcrumbs + "</div>\n" +
                "\n" +
                "    <div class='file-list'>\n" +
                items +
                "    </div>\n" +
                "\n" +
                "    <form class='folder-form' method='POST' action='/mkdir'>\n" +
                "      <input type='hidden' name='dir' value='" + escape(relPath) + "'>\n" +
                "      <input type='text' name='name' placeholder='New folder name' class='input-text' required>\n" +
                "      <button type='submit' class='btn-mkdir'>Create Folder</button>\n" +
                "    </form>\n" +
                "  </div>\n" +
                "  <script>\n" +
                "    function submitUpload(input, overrideDir) {\n" +
                "      if (!input.files || input.files.length === 0) return;\n" +
                "      const file = input.files[0];\n" +
                "      const dir = overrideDir !== undefined ? overrideDir : (input.form && input.form.elements['dir'] ? input.form.elements['dir'].value : '');\n" +
                "\n" +
                "      const card = document.getElementById('progressCard');\n" +
                "      const pTitle = document.getElementById('progressTitle');\n" +
                "      const pPercent = document.getElementById('progressPercent');\n" +
                "      const pBar = document.getElementById('progressBarFill');\n" +
                "      const pStats = document.getElementById('progressStats');\n" +
                "\n" +
                "      if (card) {\n" +
                "        card.style.display = 'block';\n" +
                "        pTitle.textContent = file.name;\n" +
                "        pPercent.textContent = '0%';\n" +
                "        pBar.style.width = '0%';\n" +
                "        pBar.style.background = 'var(--accent)';\n" +
                "        pStats.textContent = 'Starting upload...';\n" +
                "        card.scrollIntoView({ behavior: 'smooth', block: 'center' });\n" +
                "      }\n" +
                "\n" +
                "      const fd = new FormData();\n" +
                "      fd.append('dir', dir);\n" +
                "      fd.append('file', file);\n" +
                "\n" +
                "      const xhr = new XMLHttpRequest();\n" +
                "      const startTime = Date.now();\n" +
                "\n" +
                "      xhr.upload.onprogress = function(e) {\n" +
                "        if (e.lengthComputable) {\n" +
                "          const pct = Math.min(99, Math.round((e.loaded / e.total) * 100));\n" +
                "          pPercent.textContent = pct + '%';\n" +
                "          pBar.style.width = pct + '%';\n" +
                "          const loadedMb = (e.loaded / 1048576).toFixed(1);\n" +
                "          const totalMb = (e.total / 1048576).toFixed(1);\n" +
                "          const elapsed = (Date.now() - startTime) / 1000;\n" +
                "          const speed = elapsed > 0 ? ((e.loaded / 1048576) / elapsed).toFixed(1) : '0';\n" +
                "          if (pct >= 99) {\n" +
                "            pStats.textContent = loadedMb + ' MB / ' + totalMb + ' MB • Writing to TV storage...';\n" +
                "          } else {\n" +
                "            pStats.textContent = loadedMb + ' MB / ' + totalMb + ' MB (' + speed + ' MB/s)';\n" +
                "          }\n" +
                "        }\n" +
                "      };\n" +
                "\n" +
                "      xhr.onload = function() {\n" +
                "        let data = {};\n" +
                "        try { data = JSON.parse(xhr.responseText); } catch(e) {}\n" +
                "        if (xhr.status >= 200 && xhr.status < 400 && data.ok !== false) {\n" +
                "          pBar.style.width = '100%';\n" +
                "          pPercent.textContent = '100%';\n" +
                "          const isApk = file.name.toLowerCase().endsWith('.apk');\n" +
                "          if (isApk) {\n" +
                "            pStats.textContent = '✓ Sent! Check TV screen to install.';\n" +
                "          } else {\n" +
                "            pStats.textContent = '✓ Upload complete!';\n" +
                "            setTimeout(() => location.reload(), 1000);\n" +
                "          }\n" +
                "        } else {\n" +
                "          pStats.textContent = 'Upload failed: ' + (data.error || ('HTTP ' + xhr.status));\n" +
                "          pBar.style.background = '#ef4444';\n" +
                "        }\n" +
                "      };\n" +
                "\n" +
                "      xhr.onerror = function() {\n" +
                "        pStats.textContent = 'Network error during upload.';\n" +
                "        pBar.style.background = '#ef4444';\n" +
                "      };\n" +
                "\n" +
                "      xhr.ontimeout = function() {\n" +
                "        pStats.textContent = 'Upload timed out. TV may be busy.';\n" +
                "        pBar.style.background = '#ef4444';\n" +
                "      };\n" +
                "      xhr.timeout = 180000;\n" +
                "\n" +
                "      xhr.open('POST', '/upload');\n" +
                "      xhr.setRequestHeader('Accept', 'application/json');\n" +
                "      xhr.send(fd);\n" +
                "    }\n" +
                "\n" +
                "    async function deleteItem(btn, relPath, name) {\n" +
                "      if (!confirm('Delete ' + name + '?')) return;\n" +
                "      const item = btn.closest('.file-item');\n" +
                "      btn.disabled = true;\n" +
                "      btn.style.opacity = '0.5';\n" +
                "      try {\n" +
                "        const res = await fetch('/delete', {\n" +
                "          method: 'POST',\n" +
                "          headers: {\n" +
                "            'Content-Type': 'application/x-www-form-urlencoded',\n" +
                "            'X-Requested-With': 'XMLHttpRequest',\n" +
                "            'Accept': 'application/json'\n" +
                "          },\n" +
                "          body: 'path=' + encodeURIComponent(relPath)\n" +
                "        });\n" +
                "        const data = await res.json().catch(() => ({}));\n" +
                "        if (res.ok && data.ok) {\n" +
                "          item.style.transition = 'all 0.22s ease';\n" +
                "          item.style.opacity = '0';\n" +
                "          item.style.transform = 'translateX(20px)';\n" +
                "          setTimeout(() => {\n" +
                "            item.remove();\n" +
                "            const list = document.querySelector('.file-list');\n" +
                "            if (list && list.querySelectorAll('.file-item').length === 0) {\n" +
                "              list.innerHTML = \"<div class='empty-state'>This folder is empty</div>\";\n" +
                "            }\n" +
                "          }, 220);\n" +
                "        } else {\n" +
                "          alert('Could not delete ' + name + (data.error ? ': ' + data.error : ''));\n" +
                "          btn.disabled = false;\n" +
                "          btn.style.opacity = '1';\n" +
                "        }\n" +
                "      } catch (e) {\n" +
                "        alert('Network error while deleting ' + name);\n" +
                "        btn.disabled = false;\n" +
                "        btn.style.opacity = '1';\n" +
                "      }\n" +
                "    }\n" +
                "\n" +
                "    const zone = document.getElementById('dropZone');\n" +
                "    if (zone) {\n" +
                "      ['dragenter', 'dragover'].forEach(e => zone.addEventListener(e, (ev) => { ev.preventDefault(); zone.classList.add('dragover'); }));\n" +
                "      ['dragleave', 'drop'].forEach(e => zone.addEventListener(e, (ev) => { ev.preventDefault(); zone.classList.remove('dragover'); }));\n" +
                "      zone.addEventListener('drop', (ev) => {\n" +
                "        const files = ev.dataTransfer.files;\n" +
                "        if (files.length > 0) {\n" +
                "          const input = document.getElementById('fileInput');\n" +
                "          input.files = files;\n" +
                "          submitUpload(input);\n" +
                "        }\n" +
                "      });\n" +
                "    }\n" +
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

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"");
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception ignored) {
            return s;
        }
    }
}
