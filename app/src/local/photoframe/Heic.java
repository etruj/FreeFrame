package local.photoframe;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.opengl.GLES20;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an HEIC / HEIF photo (what iPhones and many Android phones take) into
 * a JPEG. Android 5 can't read HEIC, but an HEIC photo is just HEVC pictures
 * (usually a grid of 512 px tiles) in an MP4-style box structure, and the
 * tablet has a hardware HEVC decoder. This reads the boxes, decodes each tile
 * to a texture, draws the tiles side by side onto an off-screen surface at
 * up to maxSide px, reads the pixels back and applies the photo's rotation.
 */
final class Heic {
    private Heic() {
    }

    /** True if the file starts like an HEIF image ("ftyp" with an image brand). */
    static boolean is(File f) {
        try {
            RandomAccessFile r = new RandomAccessFile(f, "r");
            try {
                if (r.length() < 16) {
                    return false;
                }
                byte[] b = new byte[(int) Math.min(64, r.length())];
                r.readFully(b);
                if (b[4] != 'f' || b[5] != 't' || b[6] != 'y' || b[7] != 'p') {
                    return false;
                }
                String brands = new String(b, 8, b.length - 8, "ISO-8859-1");
                return brands.contains("heic") || brands.contains("heix") || brands.contains("mif1")
                        || brands.contains("heim") || brands.contains("heis");
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ boxes

    private static final class Box {
        String type;
        long start, body, end;
    }

    private static List<Box> children(RandomAccessFile r, long from, long end) throws IOException {
        List<Box> out = new ArrayList<Box>();
        long pos = from;
        while (pos + 8 <= end) {
            r.seek(pos);
            long size = r.readInt() & 0xffffffffL;
            byte[] t = new byte[4];
            r.readFully(t);
            Box b = new Box();
            b.type = new String(t, "ISO-8859-1");
            b.start = pos;
            b.body = pos + 8;
            if (size == 1) {
                size = r.readLong();
                b.body += 8;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < 8 || pos + size > end) {
                break;
            }
            b.end = pos + size;
            out.add(b);
            pos = b.end;
        }
        return out;
    }

    private static Box find(List<Box> boxes, String type) {
        for (Box b : boxes) {
            if (b.type.equals(type)) {
                return b;
            }
        }
        return null;
    }

    private static long readN(RandomAccessFile r, int bytes) throws IOException {
        long v = 0;
        for (int i = 0; i < bytes; i++) {
            v = (v << 8) | r.readUnsignedByte();
        }
        return v;
    }

    /** One item's data: where its pieces are (in the file, or in the idat box). */
    private static final class Item {
        String type = "";
        int method;
        long base;
        List<long[]> extents = new ArrayList<long[]>();
        List<Integer> props = new ArrayList<Integer>();
        List<Integer> tiles = new ArrayList<Integer>();
    }

    private static byte[] data(RandomAccessFile r, Item it, Box idat) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long[] e : it.extents) {
            long at = (it.method == 1 ? (idat == null ? 0 : idat.body) : 0) + it.base + e[0];
            long len = e[1];
            if (len == 0) {
                len = r.length() - at;  // to the end of the file
            }
            if (at < 0 || len < 0 || at + len > r.length() || len > (64 << 20)) {
                throw new IOException("HEIC item outside the file");
            }
            byte[] b = new byte[(int) len];
            r.seek(at);
            r.readFully(b);
            out.write(b);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------ converting

    /** Writes the HEIC photo src as a JPEG at most maxSide px on its long side. */
    static void toJpeg(File src, File dst, int maxSide) throws IOException {
        RandomAccessFile r = new RandomAccessFile(src, "r");
        try {
            convert(r, dst, maxSide);
        } catch (RuntimeException e) {
            android.util.Log.w("FrameHeic", "convert " + src, e);
            throw new IOException("the tablet could not convert this HEIC photo ("
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")");
        } finally {
            r.close();
        }
    }

    private static void convert(RandomAccessFile r, File dst, int maxSide) throws IOException {
        Box meta = find(children(r, 0, r.length()), "meta");
        if (meta == null) {
            throw new IOException("not an HEIC photo");
        }
        List<Box> mb = children(r, meta.body + 4, meta.end);  // meta is a full box
        Box pitm = find(mb, "pitm"), iloc = find(mb, "iloc"), iinf = find(mb, "iinf");
        Box iref = find(mb, "iref"), iprp = find(mb, "iprp"), idat = find(mb, "idat");
        if (pitm == null || iloc == null || iinf == null || iprp == null) {
            throw new IOException("this HEIC photo is missing parts");
        }
        Map<Integer, Item> items = new HashMap<Integer, Item>();

        r.seek(pitm.body);
        int pv = r.readUnsignedByte();
        r.skipBytes(3);
        int primary = (int) readN(r, pv == 0 ? 2 : 4);

        // iinf: item types
        r.seek(iinf.body);
        int iv = r.readUnsignedByte();
        r.skipBytes(3);
        long infeStart = iinf.body + 4 + (iv == 0 ? 2 : 4);
        for (Box infe : children(r, infeStart, iinf.end)) {
            if (!infe.type.equals("infe")) {
                continue;
            }
            r.seek(infe.body);
            int v = r.readUnsignedByte();
            r.skipBytes(3);
            if (v < 2) {
                continue;
            }
            int id = (int) readN(r, v == 2 ? 2 : 4);
            r.skipBytes(2);  // protection index
            byte[] t = new byte[4];
            r.readFully(t);
            item(items, id).type = new String(t, "ISO-8859-1");
        }

        // iloc: where each item's data is
        r.seek(iloc.body);
        int lv = r.readUnsignedByte();
        r.skipBytes(3);
        int s1 = r.readUnsignedByte(), s2 = r.readUnsignedByte();
        int offSize = s1 >> 4, lenSize = s1 & 15, baseSize = s2 >> 4, idxSize = lv >= 1 ? s2 & 15 : 0;
        long count = lv < 2 ? readN(r, 2) : readN(r, 4);
        for (long i = 0; i < count; i++) {
            Item it = item(items, (int) readN(r, lv < 2 ? 2 : 4));
            if (lv >= 1) {
                it.method = (int) (readN(r, 2) & 15);
            }
            readN(r, 2);  // data reference index
            it.base = readN(r, baseSize);
            int extents = (int) readN(r, 2);
            for (int e = 0; e < extents; e++) {
                if (idxSize > 0) {
                    readN(r, idxSize);
                }
                long off = readN(r, offSize), len = readN(r, lenSize);
                it.extents.add(new long[] {off, len});
            }
        }

        // iref: which tiles make up a grid
        if (iref != null) {
            r.seek(iref.body);
            int rv = r.readUnsignedByte();
            for (Box ref : children(r, iref.body + 4, iref.end)) {
                if (!ref.type.equals("dimg")) {
                    continue;
                }
                r.seek(ref.body);
                Item from = item(items, (int) readN(r, rv == 0 ? 2 : 4));
                int n = (int) readN(r, 2);
                for (int k = 0; k < n; k++) {
                    from.tiles.add((int) readN(r, rv == 0 ? 2 : 4));
                }
            }
        }

        // iprp: properties (hvcC, ispe, irot, imir) and which item has which
        List<Box> pb = children(r, iprp.body, iprp.end);
        Box ipco = find(pb, "ipco");
        if (ipco == null) {
            throw new IOException("this HEIC photo has no picture settings");
        }
        List<Box> props = children(r, ipco.body, ipco.end);
        for (Box ipma : pb) {
            if (!ipma.type.equals("ipma")) {
                continue;
            }
            r.seek(ipma.body);
            int mv = r.readUnsignedByte();
            int flags = (int) readN(r, 3);
            long entries = readN(r, 4);
            for (long e = 0; e < entries; e++) {
                Item it = item(items, (int) readN(r, mv < 1 ? 2 : 4));
                int n = r.readUnsignedByte();
                for (int k = 0; k < n; k++) {
                    int v = (flags & 1) != 0 ? (int) readN(r, 2) & 0x7fff : r.readUnsignedByte() & 0x7f;
                    if (v > 0) {
                        it.props.add(v);
                    }
                }
            }
        }

        Item main = items.get(primary);
        if (main == null) {
            throw new IOException("this HEIC photo has no main picture");
        }
        List<Integer> tileIds = new ArrayList<Integer>();
        int rows = 1, cols = 1, outW, outH;
        if (main.type.equals("grid")) {
            byte[] g = data(r, main, idat);
            if (g.length < 8) {
                throw new IOException("bad HEIC grid");
            }
            boolean big = (g[1] & 1) != 0;
            rows = (g[2] & 0xff) + 1;
            cols = (g[3] & 0xff) + 1;
            ByteBuffer gb = ByteBuffer.wrap(g, 4, g.length - 4);
            outW = big ? gb.getInt() : gb.getShort() & 0xffff;
            outH = big ? gb.getInt() : gb.getShort() & 0xffff;
            tileIds.addAll(main.tiles);
            if (tileIds.size() != rows * cols) {
                throw new IOException("HEIC grid has " + tileIds.size() + " tiles, expected " + rows * cols);
            }
        } else if (main.type.equals("hvc1")) {
            tileIds.add(primary);
            int[] size = ispe(r, props, main);
            outW = size[0];
            outH = size[1];
        } else {
            throw new IOException("this HEIC photo is coded as " + main.type.trim() + ", which the tablet can't read");
        }

        Item first = items.get(tileIds.get(0));
        if (first == null || !first.type.equals("hvc1")) {
            throw new IOException("this HEIC photo's tiles are not HEVC");
        }
        int[] tile = ispe(r, props, first);
        byte[] hvcc = null;
        for (int p : first.props) {
            Box b = p <= props.size() ? props.get(p - 1) : null;
            if (b != null && b.type.equals("hvcC")) {
                hvcc = new byte[(int) (b.end - b.body)];
                r.seek(b.body);
                r.readFully(hvcc);
            }
        }
        if (hvcc == null || hvcc.length < 23) {
            throw new IOException("this HEIC photo has no HEVC settings");
        }
        int lengthSize = (hvcc[21] & 3) + 1;
        int profile = hvcc[1] & 31, chroma = hvcc[16] & 3, depth = (hvcc[17] & 7) + 8;
        if (profile > 3) {
            throw new IOException("this HEIC photo uses HEVC profile " + profile + ", which the tablet's chip can't decode");
        }
        android.util.Log.i("FrameHeic", "HEIC " + outW + "x" + outH + ", " + rows + "x" + cols + " tiles of "
                + tile[0] + "x" + tile[1] + ", HEVC profile " + profile + ", " + depth + "-bit, chroma " + chroma
                + ", length size " + lengthSize + ", type " + main.type);
        if (depth > 8) {
            throw new IOException("this HEIC photo is " + depth + "-bit (HDR), which the tablet's chip can't decode");
        }
        if (chroma != 1) {
            throw new IOException("this HEIC photo uses a colour format the tablet's chip can't decode");
        }

        // Rotation and mirroring of the main picture.
        int rotate = 0, mirror = -1;
        for (int p : main.props) {
            Box b = p <= props.size() ? props.get(p - 1) : null;
            if (b == null) {
                continue;
            }
            if (b.type.equals("irot")) {
                r.seek(b.body);
                rotate = (r.readUnsignedByte() & 3) * 90;  // anticlockwise
            } else if (b.type.equals("imir")) {
                r.seek(b.body);
                mirror = r.readUnsignedByte() & 1;  // 0: top-bottom axis (left-right flip), 1: left-right axis
            }
        }

        // Halve until it fits: whole pixels per tile, so the tiles meet exactly.
        int div = 1;
        while (Math.max(outW, outH) / div > maxSide && div < 16) {
            div *= 2;
        }
        int w = Math.max(1, outW / div), h = Math.max(1, outH / div), tw = tile[0] / div, th = tile[1] / div;

        Bitmap bmp = decodeTiles(r, items, idat, tileIds, cols, tile, hvcc, lengthSize, w, h, tw, th);
        if (blank(bmp)) {
            bmp.recycle();
            throw new IOException("the tablet's decoder gave back an empty picture");
        }
        Matrix m = new Matrix();
        if (mirror == 0) {
            m.postScale(-1, 1);
        } else if (mirror == 1) {
            m.postScale(1, -1);
        }
        m.postRotate(-rotate);
        if (!m.isIdentity()) {
            Bitmap turned = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            bmp.recycle();
            bmp = turned;
        }
        OutputStream out = new FileOutputStream(dst);
        try {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
        } finally {
            out.close();
            bmp.recycle();
        }
    }


    /**
     * True if the decoder produced nothing: an empty picture (all-zero YUV)
     * comes out as flat green, about (0, 135, 0), with specks at tile edges.
     */
    private static boolean blank(Bitmap b) {
        int green = 0, n = 0;
        for (int y = 0; y < b.getHeight(); y += Math.max(1, b.getHeight() / 40)) {
            for (int x = 0; x < b.getWidth(); x += Math.max(1, b.getWidth() / 40)) {
                int p = b.getPixel(x, y), r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                n++;
                if (r < 24 && bl < 24 && g > 110 && g < 160) {
                    green++;
                }
            }
        }
        return green > n * 8 / 10;
    }

    private static Item item(Map<Integer, Item> items, int id) {
        Item it = items.get(id);
        if (it == null) {
            it = new Item();
            items.put(id, it);
        }
        return it;
    }

    private static int[] ispe(RandomAccessFile r, List<Box> props, Item it) throws IOException {
        for (int p : it.props) {
            Box b = p <= props.size() ? props.get(p - 1) : null;
            if (b != null && b.type.equals("ispe")) {
                r.seek(b.body + 4);
                return new int[] {r.readInt(), r.readInt()};
            }
        }
        throw new IOException("this HEIC photo doesn't say its size");
    }

    /** HEVC parameter sets from an hvcC record, with start codes, for MediaCodec. */
    private static byte[] csd(byte[] hvcc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = 23, arrays = hvcc[22] & 0xff;
        for (int a = 0; a < arrays && pos + 3 <= hvcc.length; a++) {
            int n = ((hvcc[pos + 1] & 0xff) << 8) | (hvcc[pos + 2] & 0xff);
            pos += 3;
            for (int k = 0; k < n && pos + 2 <= hvcc.length; k++) {
                int len = ((hvcc[pos] & 0xff) << 8) | (hvcc[pos + 1] & 0xff);
                pos += 2;
                if (pos + len > hvcc.length) {
                    break;
                }
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(1);
                byte[] nal = new byte[len];
                System.arraycopy(hvcc, pos, nal, 0, len);
                out.write(asMain(nal), 0, len);
                pos += len;
            }
        }
        return out.toByteArray();
    }

    /**
     * Phones label their HEIC pictures "Main Still Picture" (HEVC profile 3).
     * The tablet's decoder accepts that label but returns empty pictures. A
     * Main Still Picture stream is a plain Main stream (one 8-bit 4:2:0
     * picture), so the label in the parameter sets (VPS, SPS) is rewritten to
     * Main: profile 1, the Main compatibility flag set and the Main Still
     * Picture one cleared (Samsung sets it, and the decoder balks at it even
     * with profile 1). Only those bits change, in place.
     */
    static byte[] asMain(byte[] nal) {
        if (nal.length < 8) {
            return nal;
        }
        int type = (nal[0] >> 1) & 63;
        int ptl = type == 32 ? 6 : type == 33 ? 3 : -1;  // after the NAL header and the fields before profile_tier_level
        if (ptl < 0 || ptl + 1 >= nal.length || ((nal[ptl] & 31) != 3 && (nal[ptl + 1] & 0x10) == 0)) {
            return nal;
        }
        nal[ptl] = (byte) ((nal[ptl] & 0xe0) | 1);
        // general_profile_compatibility_flag[1] (Main) on, [3] (Main Still Picture) off
        nal[ptl + 1] = (byte) ((nal[ptl + 1] | 0x40) & ~0x10);
        return nal;
    }

    /** Length-prefixed units (as stored) to start codes (as MediaCodec wants them). */
    private static byte[] annexB(byte[] b, int lengthSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 64);
        int i = 0;
        while (i + lengthSize <= b.length) {
            int n = 0;
            for (int k = 0; k < lengthSize; k++) {
                n = (n << 8) | (b[i + k] & 0xff);
            }
            i += lengthSize;
            if (n < 0 || i + n > b.length) {
                throw new IOException("bad HEIC picture data");
            }
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            byte[] nal = new byte[n];
            System.arraycopy(b, i, nal, 0, n);
            out.write(asMain(nal), 0, n);
            i += n;
        }
        return out.toByteArray();
    }

    private static Bitmap decodeTiles(RandomAccessFile r, Map<Integer, Item> items, Box idat, List<Integer> tileIds,
            int cols, int[] tile, byte[] hvcc, int lengthSize, int w, int h, int tw, int th) throws IOException {
        Transcoder.Egl egl = new Transcoder.Egl(w, h);
        Transcoder.Frames frames = null;
        MediaCodec dec = null;
        try {
            frames = new Transcoder.Frames();
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            MediaFormat f = MediaFormat.createVideoFormat("video/hevc", tile[0], tile[1]);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(csd(hvcc)));
            dec = MediaCodec.createDecoderByType("video/hevc");
            dec.configure(f, frames.surface, null, 0);
            dec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int next = 0, drawn = 0, total = tileIds.size();
            long deadline = System.currentTimeMillis() + 30000;
            boolean ended = false;
            while (drawn < total) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("the tablet's decoder stopped at tile " + drawn + " of " + total);
                }
                if (!ended) {
                    int in = dec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        if (next < total) {
                            Item t = items.get(tileIds.get(next));
                            if (t == null) {
                                throw new IOException("HEIC tile missing");
                            }
                            byte[] d = annexB(data(r, t, idat), lengthSize);
                            ByteBuffer buf = dec.getInputBuffer(in);
                            if (d.length > buf.capacity()) {
                                throw new IOException("HEIC tile too large");
                            }
                            buf.clear();
                            buf.put(d);
                            dec.queueInputBuffer(in, 0, d.length, next * 1000L, 0);
                            next++;
                        } else {
                            dec.queueInputBuffer(in, 0, 0, next * 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            ended = true;
                        }
                    }
                }
                int o = dec.dequeueOutputBuffer(info, 10000);
                if (o >= 0) {
                    boolean picture = info.size > 0 || (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0;
                    if (picture) {
                        // Stamped by hand: this decoder leaves the texture's timestamp at 0.
                        dec.releaseOutputBuffer(o, (info.presentationTimeUs / 1000 + 1) * 1000000L);
                    } else {
                        dec.releaseOutputBuffer(o, false);
                    }
                    if (picture) {
                        int idx = (int) (info.presentationTimeUs / 1000);
                        frames.await();
                        // Place the tile the texture really holds, by its own timestamp.
                        int held = (int) (frames.texture.getTimestamp() / 1000000) - 1;
                        if (held >= 0 && held < total) {
                            idx = held;
                        }
                        int col = idx % cols, row = idx / cols;
                        // Read-back gives GL's bottom row first, so row 0 goes at the bottom,
                        // drawn upside down: the bitmap then comes out upright.
                        frames.draw(col * tw, row * th, tw, th, true);
                        // The GPU draws later than asked; finish now, before the decoder
                        // reuses the picture this tile is being drawn from.
                        GLES20.glFinish();
                        drawn++;
                        deadline = System.currentTimeMillis() + 30000;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 && drawn < total) {
                        throw new IOException("the tablet's decoder gave " + drawn + " of " + total + " tiles");
                    }
                }
            }
            GLES20.glFinish();
            ByteBuffer px = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            px.rewind();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(px);
            return bmp;
        } finally {
            if (dec != null) {
                try {
                    dec.stop();
                } catch (RuntimeException ignored) {
                }
                dec.release();
            }
            if (frames != null) {
                frames.release();
            }
            egl.release();
        }
    }
}
