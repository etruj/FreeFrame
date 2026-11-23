package local.photoframe;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewraps a fragmented MP4 (what browsers record when a phone shrinks a 4K
 * video) as a plain MP4. Android 5 can read the format of such a file but
 * not its pictures, so this reads the fragment tables itself and hands the
 * pictures, unchanged, to MediaMuxer.
 */
final class Fmp4 {
    private Fmp4() {
    }

    private static final class Sample {
        long offset, time;
        int size;
        boolean sync;
    }

    /** Box header: start, size, type, payload start. */
    private static final class Box {
        long start, size, body;
        String type;
    }

    private static Box box(RandomAccessFile r, long pos, long end) throws IOException {
        if (pos + 8 > end) {
            return null;
        }
        r.seek(pos);
        Box b = new Box();
        b.start = pos;
        b.size = r.readInt() & 0xffffffffL;
        byte[] t = new byte[4];
        r.readFully(t);
        b.type = new String(t, "ISO-8859-1");
        b.body = pos + 8;
        if (b.size == 1) {
            b.size = r.readLong();
            b.body += 8;
        } else if (b.size == 0) {
            b.size = end - pos;
        }
        if (b.size < 8 || pos + b.size > end) {
            return null;
        }
        return b;
    }

    private static Box find(RandomAccessFile r, Box parent, String type) throws IOException {
        long end = parent.start + parent.size;
        for (Box b = box(r, parent.body, end); b != null; b = box(r, b.start + b.size, end)) {
            if (b.type.equals(type)) {
                return b;
            }
        }
        return null;
    }

    /** The video track's id and timescale, and trex defaults: {id, timescale, duration, size, flags}. */
    private static long[] videoTrack(RandomAccessFile r, Box moov) throws IOException {
        long end = moov.start + moov.size;
        long[] out = null;
        for (Box b = box(r, moov.body, end); b != null; b = box(r, b.start + b.size, end)) {
            if (!b.type.equals("trak")) {
                continue;
            }
            Box mdia = find(r, b, "mdia"), tkhd = find(r, b, "tkhd");
            Box hdlr = mdia == null ? null : find(r, mdia, "hdlr");
            Box mdhd = mdia == null ? null : find(r, mdia, "mdhd");
            if (tkhd == null || hdlr == null || mdhd == null) {
                continue;
            }
            r.seek(hdlr.body + 8);
            byte[] h = new byte[4];
            r.readFully(h);
            if (!new String(h, "ISO-8859-1").equals("vide")) {
                continue;
            }
            r.seek(tkhd.body);
            int v = r.readUnsignedByte();
            r.seek(tkhd.body + 4 + (v == 1 ? 16 : 8));
            long id = r.readInt() & 0xffffffffL;
            r.seek(mdhd.body);
            v = r.readUnsignedByte();
            r.seek(mdhd.body + 4 + (v == 1 ? 16 : 8));
            long scale = r.readInt() & 0xffffffffL;
            out = new long[] {id, scale, 0, 0, 0};
            break;
        }
        if (out == null) {
            return null;
        }
        Box mvex = find(r, moov, "mvex");
        if (mvex != null) {
            long mend = mvex.start + mvex.size;
            for (Box b = box(r, mvex.body, mend); b != null; b = box(r, b.start + b.size, mend)) {
                if (b.type.equals("trex")) {
                    r.seek(b.body + 4);
                    if ((r.readInt() & 0xffffffffL) == out[0]) {
                        r.readInt();  // default sample description index
                        out[2] = r.readInt() & 0xffffffffL;
                        out[3] = r.readInt() & 0xffffffffL;
                        out[4] = r.readInt() & 0xffffffffL;
                    }
                }
            }
        }
        return out;
    }

    /** Reads the video samples of one moof. */
    private static long readMoof(RandomAccessFile r, Box moof, long[] track, long nextTime, List<Sample> out)
            throws IOException {
        long end = moof.start + moof.size;
        for (Box traf = box(r, moof.body, end); traf != null; traf = box(r, traf.start + traf.size, end)) {
            if (!traf.type.equals("traf")) {
                continue;
            }
            Box tfhd = find(r, traf, "tfhd");
            if (tfhd == null) {
                continue;
            }
            r.seek(tfhd.body);
            int tf = r.readInt() & 0xffffff;
            long id = r.readInt() & 0xffffffffL;
            if (id != track[0]) {
                continue;
            }
            long base = moof.start;
            long defDur = track[2], defSize = track[3], defFlags = track[4];
            if ((tf & 0x1) != 0) {
                base = r.readLong();
            }
            if ((tf & 0x2) != 0) {
                r.readInt();
            }
            if ((tf & 0x8) != 0) {
                defDur = r.readInt() & 0xffffffffL;
            }
            if ((tf & 0x10) != 0) {
                defSize = r.readInt() & 0xffffffffL;
            }
            if ((tf & 0x20) != 0) {
                defFlags = r.readInt() & 0xffffffffL;
            }
            long time = nextTime;
            Box tfdt = find(r, traf, "tfdt");
            if (tfdt != null) {
                r.seek(tfdt.body);
                int v = r.readUnsignedByte();
                r.seek(tfdt.body + 4);
                time = v == 1 ? r.readLong() : r.readInt() & 0xffffffffL;
            }
            long tend = traf.start + traf.size;
            long data = base;
            for (Box trun = box(r, traf.body, tend); trun != null; trun = box(r, trun.start + trun.size, tend)) {
                if (!trun.type.equals("trun")) {
                    continue;
                }
                r.seek(trun.body);
                int tr = r.readInt() & 0xffffff;
                long count = r.readInt() & 0xffffffffL;
                if ((tr & 0x1) != 0) {
                    data = base + r.readInt();
                }
                long firstFlags = (tr & 0x4) != 0 ? r.readInt() & 0xffffffffL : -1;
                for (long i = 0; i < count; i++) {
                    long dur = (tr & 0x100) != 0 ? r.readInt() & 0xffffffffL : defDur;
                    long size = (tr & 0x200) != 0 ? r.readInt() & 0xffffffffL : defSize;
                    long flags = (tr & 0x400) != 0 ? r.readInt() & 0xffffffffL : defFlags;
                    long cts = (tr & 0x800) != 0 ? r.readInt() : 0;
                    if (i == 0 && firstFlags >= 0) {
                        flags = firstFlags;
                    }
                    Sample s = new Sample();
                    s.offset = data;
                    s.size = (int) size;
                    s.time = (time + cts) * 1000000L / track[1];
                    s.sync = (flags & 0x10000) == 0;  // sample_is_non_sync_sample not set
                    out.add(s);
                    data += size;
                    time += dur;
                }
            }
            nextTime = time;
        }
        return nextTime;
    }

    /** True if f had fragments and was rewritten to out. */
    static boolean defragment(File f, File out, MediaFormat format) throws IOException {
        RandomAccessFile r = new RandomAccessFile(f, "r");
        MediaMuxer mux = null;
        boolean started = false;
        try {
            long len = r.length();
            Box moov = null;
            List<Box> moofs = new ArrayList<Box>();
            for (Box b = box(r, 0, len); b != null; b = box(r, b.start + b.size, len)) {
                if (b.type.equals("moov")) {
                    moov = b;
                } else if (b.type.equals("moof")) {
                    moofs.add(b);
                }
            }
            if (moov == null || moofs.isEmpty()) {
                return false;
            }
            long[] track = videoTrack(r, moov);
            if (track == null || track[1] == 0) {
                return false;
            }
            List<Sample> samples = new ArrayList<Sample>();
            long t = 0;
            for (Box m : moofs) {
                t = readMoof(r, m, track, t, samples);
            }
            if (samples.isEmpty()) {
                return false;
            }
            mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int id = mux.addTrack(format);
            mux.start();
            started = true;
            int biggest = 0;
            for (Sample s : samples) {
                biggest = Math.max(biggest, s.size);
            }
            byte[] buf = new byte[biggest];
            ByteBuffer direct = ByteBuffer.allocateDirect(biggest);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long first = samples.get(0).time;
            String mime = format.getString(MediaFormat.KEY_MIME);
            boolean startCodes = "video/avc".equals(mime) || "video/hevc".equals(mime);
            for (Sample s : samples) {
                if (s.offset + s.size > len || s.size <= 0) {
                    throw new IOException("sample outside the file");
                }
                r.seek(s.offset);
                r.readFully(buf, 0, s.size);
                if (startCodes) {
                    // MP4 stores 4-byte lengths before each unit; MediaMuxer wants start codes.
                    for (int i = 0; i < s.size; ) {
                        if (i + 4 > s.size) {
                            throw new IOException("bad picture data");
                        }
                        int n = ((buf[i] & 0xff) << 24) | ((buf[i + 1] & 0xff) << 16)
                                | ((buf[i + 2] & 0xff) << 8) | (buf[i + 3] & 0xff);
                        if (n < 0 || i + 4 + n > s.size) {
                            throw new IOException("bad picture data");
                        }
                        buf[i] = 0;
                        buf[i + 1] = 0;
                        buf[i + 2] = 0;
                        buf[i + 3] = 1;
                        i += 4 + n;
                    }
                }
                direct.clear();
                direct.put(buf, 0, s.size).flip();
                info.set(0, s.size, Math.max(0, s.time - first), s.sync ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0);
                mux.writeSampleData(id, direct, info);
            }
            mux.stop();
            started = false;
            return true;
        } finally {
            r.close();
            if (mux != null) {
                try {
                    if (started) {
                        mux.stop();
                    }
                } catch (RuntimeException ignored) {
                }
                mux.release();
            }
        }
    }
}
