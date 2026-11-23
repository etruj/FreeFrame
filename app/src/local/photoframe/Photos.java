package local.photoframe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The photo folder (Pictures/Frame on the tablet's own storage, or on the SD
 * card when that is chosen and a card is in) and screen-sized copies of each
 * photo kept in the app's cache.
 */
final class Photos {
    private static final String TAG = "FramePhotos";
    private static final String[] EXT = {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"};
    private static final String[] VIDEO_EXT = {".mp4", ".m4v", ".mov", ".webm"};
    static final long MAX_UPLOAD = 80L * 1024 * 1024;
    static final long MAX_VIDEO = 1024L * 1024 * 1024;
    static final int THUMB = 240;
    /** Fingerprints this close (of 256 bits) count as the same picture. Burst shots measured 45 and up. */
    static final int SAME = 20;
    private final java.util.Map<String, long[]> prints = new java.util.HashMap<String, long[]>();
    // Bump when the look of rendered photos changes, so old copies are redone.
    private static final String RENDER_VERSION = "v2";

    /** The folder in use right now; changes with the setting and the card. */
    volatile File dir;
    private final Context context;
    private final File internal;
    private volatile boolean wantSd;
    private File sd;
    private final File cache;
    private final Object renderLock = new Object();

    Photos(Context context, boolean wantSd) {
        this.context = context;
        this.wantSd = wantSd;
        internal = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "Frame");
        cache = new File(context.getCacheDir(), "render");
        internal.mkdirs();
        cache.mkdirs();
        dir = internal;
        refresh();
    }

    // ------------------------------------------------------------ storage

    /** The photo folder on the SD card, or null when no usable card is in. */
    synchronized File sdDir() {
        if (sd != null && sd.isDirectory()
                && Environment.MEDIA_MOUNTED.equals(Environment.getStorageState(sd))) {
            return sd;
        }
        sd = null;
        File[] all = context.getExternalFilesDirs(null);
        for (int i = 1; all != null && i < all.length; i++) {
            File app = all[i];
            if (app == null || !Environment.MEDIA_MOUNTED.equals(Environment.getStorageState(app))) {
                continue;
            }
            // Pictures/Frame on the card where the system lets an app write
            // there; otherwise the app's own folder on the card, which
            // Android always allows.
            int cut = app.getPath().indexOf("/Android/data/");
            if (cut > 0 && writable(new File(app.getPath().substring(0, cut), "Pictures/Frame"))) {
                sd = new File(app.getPath().substring(0, cut), "Pictures/Frame");
            } else if (writable(new File(app, "Frame"))) {
                sd = new File(app, "Frame");
            }
            if (sd != null) {
                return sd;
            }
        }
        return null;
    }

    private static boolean writable(File d) {
        d.mkdirs();
        File probe = new File(d, ".probe");
        try {
            new FileOutputStream(probe).close();
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            probe.delete();
        }
    }

    /** Picks the folder: the card if wanted and present, else the tablet. */
    synchronized void refresh() {
        File s = wantSd ? sdDir() : null;
        dir = s != null ? s : internal;
    }

    void useSd(boolean on) {
        wantSd = on;
        refresh();
    }

    boolean onSd() {
        return dir != internal;
    }

    /** Moves every photo to another folder; returns {moved, left behind}. */
    int[] moveAll(File from, File to) {
        List<File> all = new ArrayList<File>();
        walk(from, all, 0);
        int moved = 0, failed = 0;
        for (File f : all) {
            File target = unique(new File(to, from.toURI().relativize(f.toURI()).getPath()));
            File part = new File(target.getParentFile(), "." + target.getName() + ".part");
            try {
                target.getParentFile().mkdirs();
                copy(f, part, target);
                target.setLastModified(f.lastModified());
                File clip = motion(f);
                if (clip.exists()) {
                    try {
                        copy(clip, new File(target.getParentFile(), clip.getName() + ".part"), motion(target));
                    } catch (IOException e) {
                        Log.w(TAG, "move " + clip + ": " + e);  // the still is what matters
                    }
                }
                delete(f);
                moved++;
            } catch (IOException e) {
                Log.w(TAG, "move " + f + ": " + e);
                part.delete();
                failed++;
            }
        }
        return new int[] {moved, failed};
    }

    private static void copy(File from, File part, File target) throws IOException {
        InputStream in = new java.io.FileInputStream(from);
        OutputStream out = new FileOutputStream(part);
        try {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
        } finally {
            in.close();
            out.close();
        }
        if (part.length() != from.length() || !part.renameTo(target)) {
            part.delete();
            throw new IOException("copy failed");
        }
    }

    /**
     * Where the short clip of a live / motion photo is kept: a hidden file
     * beside the photo, so it never shows up as an item of its own.
     */
    static File motion(File photo) {
        return new File(photo.getParentFile(), "." + photo.getName() + ".mp4");
    }

    /** Saves the clip of a live photo; refuses clips the tablet can't play. */
    /** Takes a finished upload as the clip of a live photo; refuses clips the tablet can't play. */
    void adoptMotion(File photo, File part) throws IOException {
        File target = motion(photo);
        String problem = videoProblem(part);
        if (problem == null) {
            tidy(part);
        }
        if (problem != null || !part.renameTo(needsConversion(part) ? pending(target) : target)) {
            part.delete();
            throw new UnsupportedOperationException("The moving part was left out: " + problem + ".");
        }
    }

    // ------------------------------------------------------------ receiving uploads

    /** Where a resumable upload collects its pieces (same folder, so finishing is a rename). */
    File upFile(String id) {
        return new File(dir, ".up-" + id + ".part");
    }

    /** Writes a whole upload to a new hidden file and returns it. */
    File receive(InputStream in, long length, long max) throws IOException {
        File part = upFile(Long.toString(System.nanoTime(), 36));
        long n = append(part, in, 0, max);
        if (n != length) {
            part.delete();
            throw new IOException("upload cut short at " + n + " of " + length + " bytes");
        }
        return part;
    }

    /**
     * Adds a piece to an upload. Bytes that arrive before a dropped connection
     * are kept: they are the right bytes in the right place, and the sender
     * carries on from where the file ends. Returns the file's new length.
     */
    long append(File part, InputStream in, long have, long max) throws IOException {
        OutputStream out = new FileOutputStream(part, true);
        long total = have;
        try {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > max) {
                    throw new IOException("file too large");
                }
                out.write(b, 0, n);
            }
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " (had " + total + " bytes)", e);
        } finally {
            out.close();
        }
        return total;
    }

    /** Removes uploads abandoned more than a day ago. */
    void dropStaleUploads() {
        File[] files = dir.listFiles();
        long old = System.currentTimeMillis() - 24L * 3600 * 1000;
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith(".up-") && f.lastModified() < old) {
                    f.delete();
                }
            }
        }
    }

    /**
     * An HEVC video waiting to be converted to a file the frame can play:
     * "x.mp4" waits as ".x.mp4.hevc", a live photo's clip ".x.jpg.mp4" as
     * "..x.jpg.mp4.hevc". Hidden, so the slideshow skips it meanwhile.
     */
    static File pending(File target) {
        return new File(target.getParentFile(), "." + target.getName() + ".hevc");
    }

    /** The reverse of pending(). */
    static File pendingTarget(File pending) {
        String n = pending.getName();
        return new File(pending.getParentFile(), n.substring(1, n.length() - ".hevc".length()));
    }

    /** An HEIC photo waiting to become the JPEG target ("x.jpg" waits as ".x.jpg.heic"). */
    static File pendingPhoto(File target) {
        return new File(target.getParentFile(), "." + target.getName() + ".heic");
    }

    /** A photo still being converted from HEIC, by its future name, or null. */
    File awaited(String rel) {
        try {
            File f = new File(dir, rel).getCanonicalFile();
            if (f.getPath().startsWith(dir.getCanonicalPath() + File.separator) && pendingPhoto(f).exists()) {
                return f;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** Videos and HEIC photos waiting for conversion in the current folder. */
    List<File> pendingVideos() {
        List<File> out = new ArrayList<File>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith(".") && (f.getName().endsWith(".hevc") || f.getName().endsWith(".heic"))) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /**
     * Browsers record (when a phone shrinks a 4K video) fragmented MP4 or
     * WebM without an index, which Android 5 can't take thumbnails from.
     * Rewraps such a video as a plain file in place: the pictures are copied
     * as they are, nothing is re-encoded. Leaves the file alone on failure.
     */
    static void tidy(File f) {
        boolean webm = isWebm(f);
        if (!webm && !fragmented(f)) {
            return;
        }
        File out = new File(f.getPath() + ".tidy");
        MediaExtractor ex = new MediaExtractor();
        android.media.MediaMuxer mux = null;
        boolean ok = false;
        try {
            ex.setDataSource(f.getPath());
            int track = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    track = i;
                    break;
                }
            }
            if (track < 0) {
                return;
            }
            if (!webm) {
                // Android 5 can't read the pictures of a fragmented MP4 itself.
                ok = Fmp4.defragment(f, out, ex.getTrackFormat(track)) && videoProblem(out) == null;
                return;
            }
            ex.selectTrack(track);
            mux = new android.media.MediaMuxer(out.getPath(), webm
                    ? android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                    : android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int t = mux.addTrack(ex.getTrackFormat(track));
            mux.start();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8 << 20);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            int n, count = 0;
            while ((n = ex.readSampleData(buf, 0)) >= 0) {
                info.set(0, n, ex.getSampleTime(), (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? android.media.MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
                mux.writeSampleData(t, buf, info);
                ex.advance();
                count++;
            }
            mux.stop();
            ok = count > 0 && videoProblem(out) == null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "tidy " + f, e);
        } finally {
            ex.release();
            if (mux != null) {
                try {
                    mux.release();
                } catch (RuntimeException ignored) {
                }
            }
            if (ok) {
                ok = f.delete() && out.renameTo(f);
            }
            if (!ok) {
                out.delete();
            }
        }
    }

    /** An MP4 whose pictures sit in fragments ("moof" boxes) rather than one index. */
    private static boolean fragmented(File f) {
        try {
            java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r");
            try {
                long pos = 0, len = r.length();
                for (int i = 0; i < 16 && pos + 8 <= len; i++) {
                    r.seek(pos);
                    long size = r.readInt() & 0xffffffffL;
                    byte[] type = new byte[4];
                    r.readFully(type);
                    if ("moof".equals(new String(type, "ISO-8859-1"))) {
                        return true;
                    }
                    if (size == 1) {
                        size = r.readLong();
                    }
                    if (size < 8) {
                        return false;
                    }
                    pos += size;
                }
            } finally {
                r.close();
            }
        } catch (IOException e) {
            // not an MP4 we can read; leave it
        }
        return false;
    }

    /** Whether a file is WebM inside, whatever its name (EBML header). */
    static boolean isWebm(File f) {
        byte[] b = new byte[4];
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                return in.read(b) == 4 && (b[0] & 0xff) == 0x1a && (b[1] & 0xff) == 0x45
                        && (b[2] & 0xff) == 0xdf && (b[3] & 0xff) == 0xa3;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return false;
        }
    }

    /** The frame's web view plays H.264, VP8 and VP9 but not HEVC. */
    static boolean needsConversion(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getPath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                if ("video/hevc".equals(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME))) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            ex.release();
        }
    }

    private static File unique(File target) {
        String name = target.getName();
        int dot = name.lastIndexOf('.');
        File out = target;
        for (int i = 2; out.exists(); i++) {
            out = new File(target.getParentFile(), name.substring(0, dot) + "-" + i + name.substring(dot));
        }
        return out;
    }

    static boolean isPhoto(String name) {
        return endsWith(name, EXT);
    }

    static boolean isVideo(String name) {
        return endsWith(name, VIDEO_EXT);
    }

    private static boolean endsWith(String name, String[] exts) {
        String n = name.toLowerCase(Locale.US);
        for (String e : exts) {
            if (n.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why the tablet could not play a video, or null if it can. The frame
     * plays videos in its web view, which handles H.264, HEVC (H.265), VP8
     * and VP9 up to the size the tablet's decoders take (1080p here).
     */
    static String videoProblem(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getPath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) {
                    continue;
                }
                if (!mime.equals("video/avc") && !mime.equals("video/hevc") && !mime.equals("video/x-vnd.on2.vp8")
                        && !mime.equals("video/x-vnd.on2.vp9")) {
                    return "it is in a format (" + mime.substring(6) + ") this tablet can't play";
                }
                if (mime.equals("video/hevc")) {
                    int profile = hevcProfile(fmt);
                    if (profile == 2) {
                        return "it is a 10-bit (HDR) video, which this tablet can't convert";
                    }
                    if (profile > 3) {
                        return "its HEVC profile (" + profile + ") is one this tablet can't convert";
                    }
                }
                int w = fmt.getInteger(MediaFormat.KEY_WIDTH), h = fmt.getInteger(MediaFormat.KEY_HEIGHT);
                // HEVC goes through the tablet's own decoder for conversion, which takes
                // pictures as stored (phones store upright video sideways plus a rotation
                // tag). The web view's decoders take upright pictures either way.
                boolean exact = mime.equals("video/hevc");
                try {
                    if (new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                            .findDecoderForFormat(MediaFormat.createVideoFormat(mime,
                                    exact ? w : Math.max(w, h), exact ? h : Math.min(w, h))) == null) {
                        return "its picture (" + w + " x " + h + ") is larger than this tablet can play";
                    }
                } catch (RuntimeException ignored) {
                    // can't tell; let it through
                }
                return null;
            }
            return "there is no video in it";
        } catch (IOException | RuntimeException e) {
            return "the tablet can't read it";
        } finally {
            ex.release();
        }
    }

    /**
     * general_profile_idc from the HEVC parameter sets MediaExtractor hands
     * over (1 Main, 2 Main 10, 3 Main Still Picture, 4 range extensions),
     * or 0 if it can't be found. The tablet's chip decodes only 8-bit Main.
     */
    static int hevcProfile(MediaFormat fmt) {
        try {
            java.nio.ByteBuffer csd = fmt.getByteBuffer("csd-0");
            if (csd == null) {
                return 0;
            }
            byte[] b = new byte[csd.remaining()];
            csd.duplicate().get(b);
            for (int i = 0; i + 8 < b.length; i++) {
                if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) {
                    int type = (b[i + 3] >> 1) & 63;
                    if (type == 33) {
                        return b[i + 3 + 2 + 1] & 31;  // NAL header, then one byte, then profile_tier_level
                    }
                    if (type == 32) {
                        return b[i + 3 + 2 + 4] & 31;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    /** All photos, as paths relative to the folder, newest first. */
    List<File> list() {
        refresh();
        List<File> out = new ArrayList<File>();
        walk(dir, out, 0);
        Collections.sort(out, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    private static void walk(File d, List<File> out, int depth) {
        File[] files = d.listFiles();
        if (files == null || depth > 4) {
            return;
        }
        for (File f : files) {
            if (f.getName().startsWith(".")) {
                continue;
            }
            if (f.isDirectory()) {
                walk(f, out, depth + 1);
            } else if ((isPhoto(f.getName()) || isVideo(f.getName())) && f.length() > 0) {
                out.add(f);
            }
        }
    }

    String relative(File f) {
        return dir.toURI().relativize(f.toURI()).getPath();
    }

    /** The photo a relative path names, or null if it is not one of ours. */
    File resolve(String rel) {
        if (rel == null || rel.isEmpty()) {
            return null;
        }
        try {
            File f = new File(dir, rel).getCanonicalFile();
            String root = dir.getCanonicalPath() + File.separator;
            if (!f.getPath().startsWith(root) || !f.isFile()
                    || !(isPhoto(f.getName()) || isVideo(f.getName()))) {
                return null;
            }
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    long freeBytes() {
        StatFs s = new StatFs(dir.getPath());
        return s.getAvailableBytes();
    }

    // ------------------------------------------------------------ upload

    /** Takes a finished upload (a file in the folder) as a photo or video named name. */
    File adopt(File part, String name) throws IOException {
        String base = new File(name == null ? "" : name).getName()
                .replaceAll("[^A-Za-z0-9._ ()-]", "_").trim();
        if (base.isEmpty() || base.startsWith(".")) {
            base = "photo" + base;
        }
        boolean video = isVideo(base);
        boolean heic = !video && Heic.is(part);
        if (heic || base.toLowerCase(Locale.US).matches(".*\\.hei[cf]$")) {
            base = base.replaceAll("(?i)\\.hei[cf]$", "") + ".jpg";  // HEIC photos become JPEGs
        }
        if (!video && !isPhoto(base)) {
            base = base + ".jpg";
        }
        int dot = base.lastIndexOf('.');
        String stem = base.substring(0, dot);
        String ext = base.substring(dot).toLowerCase(Locale.US);
        if (video) {
            ext = ".mp4";  // .mov and .m4v are MP4 inside; converted ones come out as MP4 too
            if (base.toLowerCase(Locale.US).endsWith(".webm")) {
                ext = ".webm";
            }
        }
        File target = unique(new File(dir, stem + ext));
        if (video) {
            String problem = videoProblem(part);
            if (problem != null) {
                part.delete();
                throw new UnsupportedOperationException("This video was not added: " + problem + ".");
            }
            tidy(part);
            if (needsConversion(part)) {
                // Kept aside under a hidden name until FrameApi has converted it.
                if (!part.renameTo(pending(target))) {
                    part.delete();
                    throw new IOException("could not save");
                }
                return target;
            }
        } else if (heic) {
            // Kept aside under a hidden name until FrameApi has converted it to JPEG.
            if (!part.renameTo(pendingPhoto(target))) {
                part.delete();
                throw new IOException("could not save");
            }
            return target;
        } else {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(part.getPath(), o);
            if (o.outWidth <= 0) {
                part.delete();
                throw new UnsupportedOperationException(
                        "The tablet can't read this file. JPEG, PNG, GIF and WebP photos work.");
            }
        }
        if (!part.renameTo(target)) {
            part.delete();
            throw new IOException("could not save");
        }
        return target;
    }

    boolean delete(File f) {
        String prefix = key(f);
        File[] cached = cache.listFiles();
        if (cached != null) {
            for (File c : cached) {
                if (c.getName().startsWith(prefix)) {
                    c.delete();
                }
            }
        }
        motion(f).delete();
        pending(motion(f)).delete();
        pendingPhoto(f).delete();
        return f.delete();
    }

    /** Drops cached copies of photos that are gone or changed. */
    void pruneCache() {
        Set<String> keep = new HashSet<String>();
        for (File f : list()) {
            keep.add(key(f));
        }
        File[] cached = cache.listFiles();
        if (cached == null) {
            return;
        }
        for (File c : cached) {
            int us = c.getName().indexOf('_');
            if (us < 0 || !keep.contains(c.getName().substring(0, us))) {
                c.delete();
            }
        }
    }

    // ------------------------------------------------------------ render

    /** A JPEG of the photo composed to exactly w x h, made once and cached. */
    File rendered(File src, int w, int h, boolean crop) throws IOException {
        File out = new File(cache, key(src) + "_" + w + "x" + h + (crop ? "c" : "") + RENDER_VERSION + ".jpg");
        if (out.exists()) {
            return out;
        }
        synchronized (renderLock) {
            if (out.exists()) {
                return out;
            }
            Bitmap photo = decode(src, w, h);
            if (photo == null) {
                throw new IOException("cannot decode " + src.getName());
            }
            Bitmap frame = compose(photo, w, h, crop);
            photo.recycle();
            File tmp = new File(cache, out.getName() + ".tmp");
            OutputStream os = new FileOutputStream(tmp);
            try {
                frame.compress(Bitmap.CompressFormat.JPEG, crop ? 80 : 88, os);
            } finally {
                os.close();
                frame.recycle();
            }
            if (!tmp.renameTo(out)) {
                throw new IOException("cache write failed");
            }
        }
        return out;
    }

    private static Bitmap decode(File f, int w, int h) {
        if (isVideo(f.getName())) {
            // A still from the video, for its thumbnail.
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getPath());
                return r.getFrameAtTime(1000000);
            } catch (RuntimeException | OutOfMemoryError e) {
                return null;
            } finally {
                r.release();
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) {
            return null;
        }
        int rotate = exifRotation(f);
        int sw = o.outWidth, sh = o.outHeight;
        if (rotate == 90 || rotate == 270) {
            int t = sw;
            sw = sh;
            sh = t;
        }
        // Decode at the smallest power-of-two reduction still at least w x h.
        int sample = 1;
        while (sw / (sample * 2) >= w && sh / (sample * 2) >= h) {
            sample *= 2;
        }
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b;
        try {
            b = BitmapFactory.decodeFile(f.getPath(), o);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "out of memory decoding " + f);
            return null;
        }
        if (b == null || rotate == 0) {
            return b;
        }
        Matrix m = new Matrix();
        m.postRotate(rotate);
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) {
            b.recycle();
        }
        return r;
    }

    private static int exifRotation(File f) {
        try {
            switch (new ExifInterface(f.getPath()).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    /**
     * Photos close to the screen's shape fill it; others are shown whole
     * over a dimmed, blurred copy of themselves (no black bars).
     */
    private static Bitmap compose(Bitmap b, int w, int h, boolean crop) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        float ratio = (b.getWidth() / (float) b.getHeight()) / (w / (float) h);
        if (crop || (ratio >= 0.8f && ratio <= 1.25f)) {
            drawCover(c, b, w, h, p);
            return out;
        }
        // Backdrop: shrink to a tiny copy, blur it, then enlarge in two
        // smoothing steps so no blocks show.
        int tw = Math.max(8, w / 16), th = Math.max(8, h / 16);
        Bitmap tiny = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        drawCover(new Canvas(tiny), b, tw, th, p);
        int[] px = new int[tw * th];
        tiny.getPixels(px, 0, tw, 0, 0, tw, th);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, tw, th, 2);
        }
        tiny.setPixels(px, 0, tw, 0, 0, tw, th);
        Bitmap mid = Bitmap.createScaledBitmap(tiny, w / 4, h / 4, true);
        tiny.recycle();
        Paint dim = new Paint(p);
        ColorMatrix cm = new ColorMatrix();
        cm.setScale(0.55f, 0.55f, 0.55f, 1f);
        dim.setColorFilter(new ColorMatrixColorFilter(cm));
        c.drawBitmap(mid, null, new Rect(0, 0, w, h), dim);
        mid.recycle();
        float s = Math.min(w / (float) b.getWidth(), h / (float) b.getHeight());
        int dw = Math.round(b.getWidth() * s), dh = Math.round(b.getHeight() * s);
        int x = (w - dw) / 2, y = (h - dh) / 2;
        c.drawBitmap(b, null, new Rect(x, y, x + dw, y + dh), p);
        return out;
    }

    /** One horizontal and one vertical box-blur pass over ARGB pixels, edges clamped. */
    private static void boxBlur(int[] px, int w, int h, int r) {
        int[] tmp = new int[px.length];
        int n = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rs = 0, gs = 0, bs = 0;
                for (int k = -r; k <= r; k++) {
                    int c = px[y * w + Math.max(0, Math.min(w - 1, x + k))];
                    rs += (c >> 16) & 255;
                    gs += (c >> 8) & 255;
                    bs += c & 255;
                }
                tmp[y * w + x] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rs = 0, gs = 0, bs = 0;
                for (int k = -r; k <= r; k++) {
                    int c = tmp[Math.max(0, Math.min(h - 1, y + k)) * w + x];
                    rs += (c >> 16) & 255;
                    gs += (c >> 8) & 255;
                    bs += c & 255;
                }
                px[y * w + x] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
            }
        }
    }

    private static void drawCover(Canvas c, Bitmap b, int w, int h, Paint p) {
        float s = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        int cw = Math.round(w / s), ch = Math.round(h / s);
        int x = (b.getWidth() - cw) / 2, y = (b.getHeight() - ch) / 2;
        c.drawBitmap(b, new Rect(x, y, x + cw, y + ch), new Rect(0, 0, w, h), p);
    }

    /** Changes whenever the file is replaced or edited. */
    /**
     * A 256-bit fingerprint of what a photo or video looks like: its thumbnail
     * averaged into 17 x 16 blocks of brightness, one bit per pair of
     * neighbouring blocks (brighter or not). It survives resizing,
     * recompression and conversion, so a photo sent twice matches itself.
     */
    long[] fingerprint(File f) throws IOException {
        String k = key(f);
        synchronized (prints) {
            long[] p = prints.get(k);
            if (p != null) {
                return p;
            }
        }
        Bitmap t = BitmapFactory.decodeFile(rendered(f, THUMB, THUMB, true).getPath());
        if (t == null) {
            throw new IOException("no thumbnail for " + f.getName());
        }
        int w = t.getWidth(), h = t.getHeight();
        int[] px = new int[w * h];
        t.getPixels(px, 0, w, 0, 0, w, h);
        t.recycle();
        double[] cell = new double[17 * 16];
        int[] count = new int[17 * 16];
        for (int y = 0; y < h; y++) {
            int cy = Math.min(15, y * 16 / h);
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x], cx = Math.min(16, x * 17 / w);
                cell[cy * 17 + cx] += ((c >> 16) & 255) * 0.299 + ((c >> 8) & 255) * 0.587 + (c & 255) * 0.114;
                count[cy * 17 + cx]++;
            }
        }
        long[] bits = new long[4];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                double a = cell[y * 17 + x] / Math.max(1, count[y * 17 + x]);
                double b = cell[y * 17 + x + 1] / Math.max(1, count[y * 17 + x + 1]);
                if (b > a) {
                    int i = y * 16 + x;
                    bits[i >> 6] |= 1L << (i & 63);
                }
            }
        }
        synchronized (prints) {
            prints.put(k, bits);
        }
        return bits;
    }

    static int distance(long[] a, long[] b) {
        int d = 0;
        for (int i = 0; i < a.length; i++) {
            d += Long.bitCount(a[i] ^ b[i]);
        }
        return d;
    }

    /** Something else on the frame that looks the same as f (photo for photo, video for video), or null. */
    File lookAlike(File f) {
        try {
            long[] p = fingerprint(f);
            for (File o : list()) {
                if (o.equals(f) || isVideo(o.getName()) != isVideo(f.getName())) {
                    continue;
                }
                try {
                    if (distance(p, fingerprint(o)) <= SAME) {
                        return o;
                    }
                } catch (IOException ignored) {
                    // no thumbnail for that one; it can't be compared
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "fingerprint " + f + ": " + e);
        }
        return null;
    }

    /** Sets of look-alikes on the frame, each oldest first. */
    List<List<File>> lookAlikes() {
        List<File> all = list();
        List<long[]> fp = new ArrayList<long[]>();
        for (File f : all) {
            long[] p = null;
            try {
                p = fingerprint(f);
            } catch (IOException ignored) {
            }
            fp.add(p);
        }
        int[] group = new int[all.size()];
        for (int i = 0; i < group.length; i++) {
            group[i] = i;
        }
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (fp.get(i) != null && fp.get(j) != null
                        && isVideo(all.get(i).getName()) == isVideo(all.get(j).getName())
                        && distance(fp.get(i), fp.get(j)) <= SAME) {
                    int a = root(group, i), b = root(group, j);
                    group[Math.max(a, b)] = Math.min(a, b);
                }
            }
        }
        java.util.Map<Integer, List<File>> sets = new java.util.LinkedHashMap<Integer, List<File>>();
        for (int i = 0; i < all.size(); i++) {
            int r = root(group, i);
            List<File> s = sets.get(r);
            if (s == null) {
                s = new ArrayList<File>();
                sets.put(r, s);
            }
            s.add(all.get(i));
        }
        List<List<File>> out = new ArrayList<List<File>>();
        for (List<File> s : sets.values()) {
            if (s.size() > 1) {
                Collections.sort(s, new Comparator<File>() {
                    @Override
                    public int compare(File a, File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
                out.add(s);
            }
        }
        return out;
    }

    private static int root(int[] g, int i) {
        while (g[i] != i) {
            i = g[i];
        }
        return i;
    }

    private String key(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((f.getPath() + "|" + f.lastModified() + "|" + f.length())
                    .getBytes(HttpServer.UTF8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                b.append(String.format(Locale.US, "%02x", d[i]));
            }
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(f.getPath().hashCode());
        }
    }
}
