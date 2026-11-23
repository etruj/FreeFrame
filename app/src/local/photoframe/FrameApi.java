package local.photoframe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Everything the tablet serves on port 8080. */
final class FrameApi implements HttpServer.Handler {
    private static final String TAG = "FrameApi";
    static final int PORT = 8080;

    private final Context context;
    private final Photos photos;
    private final SharedPreferences prefs;
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    /** HEVC videos are converted one at a time, apart from the quick work above. */
    private final ExecutorService converter = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicInteger converting = new java.util.concurrent.atomic.AtomicInteger();
    private final JSONArray convertProblems = new JSONArray();
    private volatile int screenW = 1024, screenH = 600;
    private volatile String storageNote = "";
    /** What the manager page asked to show next, and a number that changes with each ask. */
    private volatile JSONArray upNext = new JSONArray();
    private volatile int upNextId = 0;

    // The Cozi feed: refetched at most every 10 minutes, last good copy kept
    // on disk so the agenda survives a reboot without Wi-Fi.
    private final File feedFile;
    private final Object feedLock = new Object();
    private String feedText;
    private long feedTried, feedOk;
    private String feedError = "";

    FrameApi(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("frame", Context.MODE_PRIVATE);
        this.photos = new Photos(context, prefs.getBoolean("use_sd", false));
        photos.dropStaleUploads();
        for (File p : photos.pendingVideos()) {
            convert(Photos.pendingTarget(p));  // carry on with conversions cut short by a restart
        }
        this.feedFile = new File(context.getFilesDir(), "calendar.ics");
        if (feedFile.exists()) {
            try {
                feedText = new String(readAll(new FileInputStream(feedFile), 4 << 20), HttpServer.UTF8);
                feedOk = feedFile.lastModified();
            } catch (IOException ignored) {
            }
        }
        background.execute(new Runnable() {
            @Override
            public void run() {
                photos.pruneCache();
            }
        });
    }

    // ------------------------------------------------------------ routes

    @Override
    public void handle(HttpServer.Request req, HttpServer.Response res) throws Exception {
        String p = req.path;
        boolean get = req.method.equals("GET") || req.method.equals("HEAD");
        boolean post = req.method.equals("POST");
        if (get && (p.equals("/") || p.equals("/manage"))) {
            asset(res, "manage.html");
        } else if (get && p.equals("/frame")) {
            asset(res, "frame.html");
        } else if (get && p.equals("/icon.png")) {
            res.send(200, "image/png", readAll(context.getAssets().open("icon.png"), 1 << 20), "max-age=86400");
        } else if (get && p.equals("/api/config")) {
            res.json(config());
        } else if (get && p.equals("/api/photos")) {
            res.json(photoList());
        } else if (get && p.equals("/api/events")) {
            res.json(events());
        } else if (get && p.equals("/api/settings")) {
            res.json(settings());
        } else if (post && p.equals("/api/settings")) {
            saveSettings(new JSONObject(req.bodyText()));
            res.json(settings());
        } else if (post && p.equals("/api/upload")) {
            upload(req, res);
        } else if (get && p.equals("/api/upload")) {
            // How much of a resumable upload the tablet has.
            String id = req.param("id", "");
            File part = id.matches("[a-z0-9]{8,40}") ? photos.upFile(id) : null;
            res.json(new JSONObject().put("have", part != null && part.exists() ? part.length() : 0));
        } else if (post && p.equals("/api/brightness")) {
            setBrightness(new JSONObject(req.bodyText()).optInt("level", 50));
            res.json(new JSONObject().put("brightness", brightness()));
        } else if (post && p.equals("/api/next")) {
            JSONArray asked = new JSONObject(req.bodyText()).optJSONArray("names"), ok = new JSONArray();
            for (int i = 0; asked != null && i < asked.length(); i++) {
                File f = photos.resolve(asked.optString(i));
                if (f != null) {
                    ok.put(photos.relative(f));
                }
            }
            upNext = ok;
            upNextId++;
            res.json(new JSONObject().put("id", upNextId).put("names", ok));
        } else if (get && p.equals("/api/next")) {
            res.json(new JSONObject().put("id", upNextId).put("names", upNext));
        } else if (get && p.equals("/api/duplicates")) {
            JSONArray groups = new JSONArray();
            for (List<File> set : photos.lookAlikes()) {
                JSONArray g = new JSONArray();
                for (File f : set) {
                    g.put(photos.relative(f));
                }
                groups.put(g);
            }
            res.json(new JSONObject().put("groups", groups));
        } else if (get && p.equals("/api/debug")) {
            res.text(200, debugReport());
        } else if (post && p.equals("/api/log")) {
            clientLog(req.bodyText());
            res.text(200, "ok");
        } else if (get && p.equals("/api/log")) {
            File log = new File(context.getFilesDir(), "client.log");
            if (log.exists()) {
                res.file(log, "text/plain; charset=utf-8", "no-store");
            } else {
                res.text(200, "");
            }
        } else if (post && p.equals("/api/delete")) {
            JSONArray names = new JSONObject(req.bodyText()).getJSONArray("names");
            int n = 0;
            for (int i = 0; i < names.length(); i++) {
                File f = photos.resolve(names.getString(i));
                if (f != null && photos.delete(f)) {
                    n++;
                }
            }
            res.json(new JSONObject().put("deleted", n));
        } else if (get && p.startsWith("/img/")) {
            File f = photos.resolve(p.substring(5));
            if (f == null) {
                res.text(404, "no such photo");
                return;
            }
            int w = clamp(req.param("w", "1024"), 160, 2048);
            int h = clamp(req.param("h", "600"), 160, 2048);
            screenW = w;
            screenH = h;
            // Served from this tablet, so the web view need not cache it.
            res.file(photos.rendered(f, w, h, false), "image/jpeg", "no-store");
        } else if (get && p.startsWith("/thumb/")) {
            File f = photos.resolve(p.substring(7));
            if (f == null) {
                res.text(404, "no such photo");
                return;
            }
            res.file(photos.rendered(f, Photos.THUMB, Photos.THUMB, true), "image/jpeg", "max-age=86400");
        } else if (get && p.startsWith("/motion/")) {
            File f = photos.resolve(p.substring(8));
            if (f == null || !Photos.motion(f).exists()) {
                res.text(404, "no such clip");
                return;
            }
            res.file(Photos.motion(f), Photos.isWebm(Photos.motion(f)) ? "video/webm" : "video/mp4",
                    "no-store", req.headers.get("range"));
        } else if (get && p.startsWith("/orig/")) {
            File f = photos.resolve(p.substring(6));
            if (f == null) {
                res.text(404, "no such photo");
                return;
            }
            if (req.param("download", null) != null) {
                // Saved under its own name rather than opened in the browser.
                res.headers = "Content-Disposition: attachment; filename=\""
                        + f.getName().replaceAll("[^A-Za-z0-9._ ()-]", "_") + "\"\r\n";
            }
            res.file(f, mime(f.getName()), "no-store", req.headers.get("range"));
        } else if (post && p.equals("/api/ping")) {
            // Network check from the manager page: read and drop the bytes.
            long n = 0;
            byte[] b = new byte[64 * 1024];
            int r;
            while ((r = req.body.read(b)) > 0) {
                n += r;
            }
            res.json(new JSONObject().put("bytes", n));
        } else if (!get && !post) {
            res.text(405, "method not allowed");
        }
    }

    /**
     * Uploads come whole, or in pieces (id, offset, total) that the manager
     * page resends from where the tablet's copy ends when a phone drops the
     * connection (screen off, Wi-Fi hiccup).
     */
    private void upload(HttpServer.Request req, HttpServer.Response res) throws Exception {
        if (req.contentLength < 0) {
            res.text(411, "length required");
            return;
        }
        String name = req.param("name", "photo.jpg"), motionFor = req.param("motionFor", ""), id = req.param("id", "");
        boolean video = Photos.isVideo(name) || !motionFor.isEmpty();
        long max = video ? Photos.MAX_VIDEO : Photos.MAX_UPLOAD;
        long total = req.contentLength, offset = 0;
        if (!id.isEmpty()) {
            if (!id.matches("[a-z0-9]{8,40}")) {
                res.text(400, "bad upload id");
                return;
            }
            try {
                offset = Long.parseLong(req.param("offset", "0"));
                total = Long.parseLong(req.param("total", "-1"));
            } catch (NumberFormatException e) {
                res.text(400, "bad offset");
                return;
            }
        }
        if (total > max) {
            res.text(413, video ? "This video is too large (1 GB at most)." : "This photo is too large (80 MB at most).");
            return;
        }
        if (offset == 0 && total > photos.freeBytes() - 100L * 1024 * 1024) {
            res.text(507, "There is not enough room left on the frame for this.");
            return;
        }
        File part;
        if (id.isEmpty()) {
            part = photos.receive(req.body, req.contentLength, max);
        } else {
            part = photos.upFile(id);
            long have = part.exists() ? part.length() : 0;
            if (offset != have) {
                res.json(new JSONObject().put("have", have));  // the page carries on from there
                return;
            }
            try {
                have = photos.append(part, req.body, have, max);
            } catch (IOException e) {
                throw new IOException("piece of " + name + " at " + offset + " of " + total + ": " + e.getMessage(), e);
            }
            if (have < total) {
                res.json(new JSONObject().put("have", have));
                return;
            }
            if (have > total) {
                part.delete();
                res.text(400, "the upload got mixed up; try again");
                return;
            }
            Log.i(TAG, "received " + name + " (" + total + " bytes)");
        }
        finish(part, name, motionFor, video, res);
    }

    private void finish(File part, String name, String motionFor, final boolean video, HttpServer.Response res)
            throws Exception {
        if (!motionFor.isEmpty()) {
            // The clip that belongs to a live / motion photo already added.
            File photo = photos.resolve(motionFor);
            if (photo == null) {
                photo = photos.awaited(motionFor);  // its still is an HEIC being converted
            }
            if (photo == null || !Photos.isPhoto(photo.getName())) {
                part.delete();
                res.text(404, "no such photo");
                return;
            }
            try {
                photos.adoptMotion(photo, part);
            } catch (UnsupportedOperationException e) {
                Log.i(TAG, "refused clip for " + motionFor + ": " + e.getMessage());
                res.text(415, e.getMessage());
                return;
            }
            boolean later = Photos.pending(Photos.motion(photo)).exists();
            if (later) {
                convert(Photos.motion(photo));
            }
            res.json(new JSONObject().put("name", motionFor).put("converting", later));
            return;
        }
        final File saved;
        File twin = null;
        try {
            saved = photos.adopt(part, name);
            // A possible double is kept like anything else: the page only warns about it.
            twin = saved.exists() ? photos.lookAlike(saved) : null;
            if (twin != null) {
                Log.i(TAG, "added " + name + ", which looks the same as " + twin.getName());
            }
        } catch (UnsupportedOperationException e) {
            Log.i(TAG, "refused " + name + " (" + part.length() + " bytes): " + e.getMessage());
            res.text(415, e.getMessage());
            return;
        }
        if (!saved.exists() && (Photos.pending(saved).exists() || Photos.pendingPhoto(saved).exists())) {
            convert(saved);
            res.json(new JSONObject().put("name", photos.relative(saved)).put("converting", true));
            return;
        }
        // Make the thumbnail and the screen-sized copy now, off this thread,
        // so the frame never waits on a big photo.
        background.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    photos.rendered(saved, Photos.THUMB, Photos.THUMB, true);
                    if (!video) {
                        photos.rendered(saved, screenW, screenH, false);
                    }
                } catch (IOException e) {
                    Log.w(TAG, "prerender " + saved + ": " + e);
                }
            }
        });
        res.json(new JSONObject().put("name", photos.relative(saved))
                .put("lookAlike", twin == null ? null : photos.relative(twin)));
    }

    // ------------------------------------------------------------ remote look-in

    /**
     * The app's own state and recent log, for looking into problems over the
     * network when ADB is not on (Fire OS turns ADB over Wi-Fi off at every
     * restart). Android lets an app read its own log lines without permission.
     * The private calendar link is masked.
     */
    private String debugReport() {
        StringBuilder sb = new StringBuilder();
        Runtime rt = Runtime.getRuntime();
        sb.append("version ").append(version()).append('\n')
                .append("up ").append(android.os.SystemClock.elapsedRealtime() / 60000).append(" min since boot\n")
                .append("memory ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append(" MB used of ")
                .append(rt.maxMemory() >> 20).append(" MB\n")
                .append("photos folder ").append(photos.dir).append(", ").append(photos.freeBytes() >> 20)
                .append(" MB free, converting ").append(converting.get()).append("\n\n");
        try {
            Process proc = Runtime.getRuntime().exec(new String[] {"logcat", "-d", "-v", "time", "-t", "600"});
            sb.append(new String(readAll(proc.getInputStream(), 1 << 20), HttpServer.UTF8));
            proc.destroy();
        } catch (IOException e) {
            sb.append("log unavailable: ").append(e);
        }
        String text = sb.toString(), link = prefs.getString("ics_url", "");
        return link.isEmpty() ? text : text.replace(link, "[calendar link]");
    }

    // ------------------------------------------------------------ brightness

    /** The tablet's own brightness setting (the top-bar slider), in percent. */
    private int brightness() {
        int v = android.provider.Settings.System.getInt(context.getContentResolver(),
                android.provider.Settings.System.SCREEN_BRIGHTNESS, 128);
        return Math.max(1, Math.round(v * 100 / 255f));
    }

    /** Sets the tablet's own brightness, as the top-bar slider does; takes effect at once. */
    private void setBrightness(int percent) {
        android.content.ContentResolver cr = context.getContentResolver();
        android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        android.provider.Settings.System.putInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS,
                Math.max(1, Math.min(255, Math.round(Math.max(1, Math.min(100, percent)) * 2.55f))));
    }

    /** Whether the frame is in its dim hours right now. */
    private boolean nightNow() {
        int a = clock(prefs.getString("night_start", "22:30")), b = clock(prefs.getString("night_end", "06:30"));
        if (a < 0 || b < 0 || a == b) {
            return false;
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        int t = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
        return a < b ? t >= a && t < b : t >= a || t < b;
    }

    private static int clock(String hhmm) {
        String[] p = hhmm == null ? new String[0] : hhmm.split(":");
        try {
            return p.length == 2 ? Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * What the manager page reports about uploads (browser, file sizes, what
     * failed and why), kept in files/client.log so problems on someone's phone
     * can be looked at afterwards.
     */
    private synchronized void clientLog(String text) {
        if (text.length() > 4000) {
            text = text.substring(0, 4000);
        }
        Log.i("FrameClient", text);
        File log = new File(context.getFilesDir(), "client.log");
        try {
            if (log.length() > 256 * 1024) {
                log.renameTo(new File(context.getFilesDir(), "client.log.1"));
            }
            java.io.OutputStream out = new FileOutputStream(log, true);
            try {
                out.write((new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + " " + text.replace('\n', ' ') + "\n").getBytes(HttpServer.UTF8));
            } finally {
                out.close();
            }
        } catch (IOException e) {
            Log.w(TAG, "client log: " + e);
        }
    }

    /** Converts the HEVC video waiting for target, in the background. */
    private void convert(final File target) {
        converting.incrementAndGet();
        converter.execute(new Runnable() {
            @Override
            public void run() {
                boolean heic = !Photos.pending(target).exists() && Photos.pendingPhoto(target).exists();
                File src = heic ? Photos.pendingPhoto(target) : Photos.pending(target);
                File part = new File(src.getPath() + ".part");
                boolean clip = target.getName().startsWith(".");
                String shown = clip ? target.getName().substring(1, target.getName().length() - 4) : target.getName();
                try {
                    if (!src.exists()) {
                        return;  // deleted while waiting
                    }
                    long t0 = System.currentTimeMillis();
                    if (heic) {
                        Heic.toJpeg(src, part, 2048);
                        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeFile(part.getPath(), o);
                        if (o.outWidth <= 0) {
                            throw new IOException("the converted photo came out unreadable");
                        }
                    } else {
                        Transcoder.toH264(src, part, 1280);
                        String problem = Photos.videoProblem(part);
                        if (problem != null) {
                            throw new IOException(problem);
                        }
                    }
                    if (!src.exists() || (clip && !new File(target.getParentFile(), shown).exists())) {
                        part.delete();  // deleted while converting
                        return;
                    }
                    if (!part.renameTo(target)) {
                        throw new IOException("could not save");
                    }
                    Log.i(TAG, "converted " + target + " in " + (System.currentTimeMillis() - t0) + " ms");
                    File twin = clip ? null : photos.lookAlike(target);
                    if (twin != null) {
                        // Kept; the page warns that it looks like one already there.
                        synchronized (convertProblems) {
                            convertProblems.put(new JSONObject().put("name", photos.relative(target)).put("clip", false)
                                    .put("time", System.currentTimeMillis()).put("lookAlike", photos.relative(twin)));
                        }
                    }
                    if (!clip) {
                        photos.rendered(target, Photos.THUMB, Photos.THUMB, true);
                        if (heic) {
                            photos.rendered(target, screenW, screenH, false);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "convert " + target + ": " + e);
                    part.delete();
                    if (heic) {
                        // Kept for a look (adb pull), in a hidden folder the frame ignores.
                        File keep = new File(target.getParentFile(), ".failed");
                        keep.mkdirs();
                        src.renameTo(new File(keep, target.getName() + ".heic"));
                    }
                    synchronized (convertProblems) {
                        try {
                            convertProblems.put(new JSONObject().put("name", shown).put("clip", clip)
                                    .put("time", System.currentTimeMillis())
                                    .put("reason", e.getMessage() == null ? e.toString() : e.getMessage()));
                            while (convertProblems.length() > 20) {
                                convertProblems.remove(0);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                } finally {
                    src.delete();
                    converting.decrementAndGet();
                }
            }
        });
    }

    private JSONObject photoList() throws Exception {
        JSONArray list = new JSONArray();
        for (File f : photos.list()) {
            list.put(new JSONObject()
                    .put("name", photos.relative(f))
                    .put("video", Photos.isVideo(f.getName()))
                    .put("motion", Photos.motion(f).exists())
                    .put("size", f.length())
                    .put("mtime", f.lastModified()));
        }
        JSONArray problems;
        synchronized (convertProblems) {
            problems = new JSONArray(convertProblems.toString());
        }
        return new JSONObject().put("photos", list).put("free", photos.freeBytes())
                .put("onSd", photos.onSd()).put("converting", converting.get())
                .put("convertProblems", problems);
    }

    // ------------------------------------------------------------ settings

    private JSONObject settings() throws Exception {
        synchronized (feedLock) {
            return new JSONObject()
                    .put("icsUrl", prefs.getString("ics_url", ""))
                    .put("secondsPerPhoto", prefs.getInt("seconds_per_photo", 30))
                    .put("shuffle", prefs.getBoolean("shuffle", true))
                    .put("showClock", prefs.getBoolean("show_clock", true))
                    .put("daysAhead", prefs.getInt("days_ahead", 7))
                    .put("nightStart", prefs.getString("night_start", "22:30"))
                    .put("nightEnd", prefs.getString("night_end", "06:30"))
                    .put("calendarUpdated", feedOk)
                    .put("calendarError", feedError)
                    .put("storage", prefs.getBoolean("use_sd", false) ? "sd" : "internal")
                    .put("sdAvailable", photos.sdDir() != null)
                    .put("onSd", photos.onSd())
                    .put("storageNote", storageNote)
                    .put("folder", photos.dir.getPath())
                    .put("brightness", brightness())
                    .put("nightBrightness", prefs.getInt("night_brightness", 2))
                    .put("nightNow", nightNow());
        }
    }

    private void saveSettings(JSONObject s) {
        SharedPreferences.Editor e = prefs.edit();
        if (s.has("icsUrl")) {
            String url = s.optString("icsUrl").trim();
            if (url.startsWith("webcal://")) {
                url = "https://" + url.substring(9);
            }
            if (!url.equals(prefs.getString("ics_url", ""))) {
                e.putString("ics_url", url);
                synchronized (feedLock) {
                    feedTried = 0;
                    feedText = null;
                    feedOk = 0;
                    feedError = "";
                    feedFile.delete();
                }
            }
        }
        if (s.has("storage")) {
            boolean sd = "sd".equals(s.optString("storage"));
            // The card can only be chosen while one is in.
            if (sd != prefs.getBoolean("use_sd", false) && (!sd || photos.sdDir() != null)) {
                e.putBoolean("use_sd", sd);
                final File from = photos.dir;
                photos.useSd(sd);
                final File to = photos.dir;
                if (!from.equals(to)) {
                    storageNote = "Moving photos\u2026";
                    background.execute(new Runnable() {
                        @Override
                        public void run() {
                            int[] n = photos.moveAll(from, to);
                            storageNote = "Moved " + n[0] + (n[0] == 1 ? " photo." : " photos.")
                                    + (n[1] == 0 ? "" : " " + n[1] + " would not fit and stayed where"
                                    + (n[1] == 1 ? " it was." : " they were."));
                        }
                    });
                }
            }
        }
        if (s.has("secondsPerPhoto")) {
            e.putInt("seconds_per_photo", Math.max(5, Math.min(3600, s.optInt("secondsPerPhoto", 30))));
        }
        if (s.has("shuffle")) {
            e.putBoolean("shuffle", s.optBoolean("shuffle", true));
        }
        if (s.has("showClock")) {
            e.putBoolean("show_clock", s.optBoolean("showClock", true));
        }
        if (s.has("daysAhead")) {
            e.putInt("days_ahead", Math.max(1, Math.min(31, s.optInt("daysAhead", 7))));
        }
        if (s.has("nightBrightness")) {
            e.putInt("night_brightness", Math.max(1, Math.min(100, s.optInt("nightBrightness", 2))));
        }
        for (String k : new String[] {"nightStart", "nightEnd"}) {
            if (s.has(k)) {
                String v = s.optString(k).trim();
                if (v.isEmpty() || v.matches("([01]?\\d|2[0-3]):[0-5]\\d")) {
                    e.putString(k.equals("nightStart") ? "night_start" : "night_end", v);
                }
            }
        }
        e.apply();
    }

    private JSONObject config() throws Exception {
        String ip = wifiAddress();
        return new JSONObject()
                .put("version", version())
                .put("secondsPerPhoto", prefs.getInt("seconds_per_photo", 30))
                .put("shuffle", prefs.getBoolean("shuffle", true))
                .put("showClock", prefs.getBoolean("show_clock", true))
                .put("maxEvents", 9)
                .put("nightStart", prefs.getString("night_start", "22:30"))
                .put("nightEnd", prefs.getString("night_end", "06:30"))
                .put("nightBrightness", prefs.getInt("night_brightness", 2) / 100.0)
                .put("dayBrightness", -1)  // by day the tablet's own brightness slider rules
                .put("now", Ical.localTime(System.currentTimeMillis(), TimeZone.getDefault())
                        + String.format(Locale.US, ":%02d", (System.currentTimeMillis() / 1000) % 60))
                .put("ip", ip == null ? "" : ip)
                .put("manageUrl", ip == null ? "" : "http://" + ip + ":" + PORT + "/");
    }

    private String version() {
        try {
            return String.valueOf(context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime);
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** The tablet's address on the home network, preferring Wi-Fi. */
    static String wifiAddress() {
        try {
            String any = null;
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : all) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (ni.getName().startsWith("wlan")) {
                            return a.getHostAddress();
                        }
                        any = a.getHostAddress();
                    }
                }
            }
            return any;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ calendar

    private JSONObject events() throws Exception {
        String url = prefs.getString("ics_url", "");
        String text = feed(url);
        JSONArray list = text == null ? new JSONArray()
                : Ical.upcoming(text, prefs.getInt("days_ahead", 7), TimeZone.getDefault(),
                        System.currentTimeMillis());
        synchronized (feedLock) {
            return new JSONObject()
                    .put("events", list)
                    .put("configured", !url.isEmpty())
                    .put("fetched", feedOk)
                    .put("error", feedError);
        }
    }

    private String feed(String url) {
        if (url.isEmpty()) {
            return null;
        }
        synchronized (feedLock) {
            long now = System.currentTimeMillis();
            boolean due = now - feedTried > (feedText == null ? 60 * 1000 : 10 * 60 * 1000);
            if (!due) {
                return feedText;
            }
            feedTried = now;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "photoframe/2");
                if (c.getResponseCode() != 200) {
                    throw new IOException("HTTP " + c.getResponseCode());
                }
                String text = new String(readAll(c.getInputStream(), 4 << 20), HttpServer.UTF8);
                if (!text.contains("BEGIN:VCALENDAR")) {
                    throw new IOException("not a calendar feed");
                }
                feedText = text;
                feedOk = now;
                feedError = "";
                FileOutputStream out = new FileOutputStream(feedFile);
                try {
                    out.write(text.getBytes(HttpServer.UTF8));
                } finally {
                    out.close();
                }
            } catch (Exception e) {
                feedError = e.getClass().getSimpleName() + ": " + e.getMessage();
                Log.w(TAG, "calendar fetch failed: " + feedError);
            }
            return feedText;
        }
    }

    // ------------------------------------------------------------ helpers

    private void asset(HttpServer.Response res, String name) throws IOException {
        res.send(200, "text/html; charset=utf-8",
                readAll(context.getAssets().open(name), 1 << 20), "no-store");
    }

    static byte[] readAll(InputStream in, int limit) throws IOException {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
                if (buf.size() > limit) {
                    throw new IOException("too large");
                }
            }
            return buf.toByteArray();
        } finally {
            in.close();
        }
    }

    private static int clamp(String v, int lo, int hi) {
        try {
            return Math.max(lo, Math.min(hi, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return lo;
        }
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".webm")) {
            return "video/webm";
        } else if (Photos.isVideo(n)) {
            return "video/mp4";
        } else if (n.endsWith(".png")) {
            return "image/png";
        } else if (n.endsWith(".gif")) {
            return "image/gif";
        } else if (n.endsWith(".webp")) {
            return "image/webp";
        } else if (n.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }
}
