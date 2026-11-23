package local.photoframe;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Re-encodes a video the frame's web view can't play (HEVC / H.265, what
 * iPhones and many Android phones record by default) as H.264, using the
 * tablet's own hardware decoder and encoder. Frames go decoder -> texture ->
 * encoder, scaled down on the way. Sound is dropped: the frame plays videos
 * muted anyway.
 */
final class Transcoder {
    private static final long WAIT_US = 10000;
    private static final long STALL_MS = 20000;

    private Transcoder() {
    }

    static void toH264(File src, File dst, int maxSide) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        Egl egl = null;
        Frames frames = null;
        boolean muxing = false;
        try {
            ex.setDataSource(src.getPath());
            MediaFormat in = null;
            for (int i = 0; i < ex.getTrackCount() && in == null; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    ex.selectTrack(i);
                    in = f;
                }
            }
            if (in == null) {
                throw new IOException("there is no video in it");
            }
            int w = in.getInteger(MediaFormat.KEY_WIDTH), h = in.getInteger(MediaFormat.KEY_HEIGHT);
            float s = Math.min(1f, (float) maxSide / Math.max(w, h));
            int ow = Math.max(16, Math.round(w * s / 16) * 16);  // the encoder wants multiples of 16
            int oh = Math.max(16, Math.round(h * s / 16) * 16);
            int fps = 30;
            try {
                fps = Math.max(1, Math.min(60, in.getInteger(MediaFormat.KEY_FRAME_RATE)));
            } catch (RuntimeException ignored) {
                // missing or stored as a float; 30 is close enough for the encoder's rate control
            }

            MediaFormat out = MediaFormat.createVideoFormat("video/avc", ow, oh);
            out.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            out.setInteger(MediaFormat.KEY_BIT_RATE, ow * oh * 4);  // about 3.7 Mbit/s at 720p
            out.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            out.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            enc = MediaCodec.createEncoderByType("video/avc");
            enc.configure(out, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            egl = new Egl(enc.createInputSurface());
            frames = new Frames();
            enc.start();

            dec = MediaCodec.createDecoderByType(in.getString(MediaFormat.KEY_MIME));
            dec.configure(in, frames.surface, null, 0);
            dec.start();

            mux = new MediaMuxer(dst.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int rotation = rotation(src);
            if (rotation != 0) {
                mux.setOrientationHint(rotation);  // upright phone videos stay upright
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, decDone = false, encDone = false;
            int track = -1;
            long lastPts = -1, lastIn = -1, lastProgress = SystemClock.uptimeMillis();
            while (!encDone) {
                if (SystemClock.uptimeMillis() - lastProgress > STALL_MS) {
                    throw new IOException("the tablet's video hardware stopped responding");
                }
                if (!inDone) {
                    int i = dec.dequeueInputBuffer(WAIT_US);
                    if (i >= 0) {
                        int n = ex.readSampleData(dec.getInputBuffer(i), 0);
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                if (!decDone) {
                    int o = dec.dequeueOutputBuffer(info, WAIT_US);
                    if (o >= 0) {
                        boolean render = info.size > 0;
                        boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        long pts = info.presentationTimeUs;
                        if (render && pts <= lastIn) {
                            pts = lastIn + 1000000L / fps;  // broken timestamps: space frames evenly
                        }
                        if (render) {
                            lastIn = pts;
                        }
                        dec.releaseOutputBuffer(o, render);
                        if (render) {
                            frames.await();
                            frames.draw(ow, oh);
                            egl.present(pts * 1000);
                        }
                        if (end) {
                            enc.signalEndOfInputStream();
                            decDone = true;
                        }
                    }
                }
                while (true) {
                    int o = enc.dequeueOutputBuffer(info, decDone ? WAIT_US : 0);
                    if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break;
                    }
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(enc.getOutputFormat());
                        mux.start();
                        muxing = true;
                        continue;
                    }
                    if (o < 0) {
                        continue;  // output buffers changed
                    }
                    ByteBuffer b = enc.getOutputBuffer(o);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;  // already in the format the muxer was given
                    }
                    if (info.size > 0 && muxing && info.presentationTimeUs > lastPts) {
                        b.position(info.offset);
                        b.limit(info.offset + info.size);
                        mux.writeSampleData(track, b, info);
                        lastPts = info.presentationTimeUs;
                        lastProgress = SystemClock.uptimeMillis();
                    }
                    enc.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encDone = true;
                        break;
                    }
                }
            }
            if (lastPts < 0) {
                throw new IOException("no pictures came out of it");
            }
        } catch (RuntimeException e) {
            throw new IOException("the tablet's video hardware could not convert it ("
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")");
        } finally {
            ex.release();
            if (dec != null) {
                try { dec.stop(); } catch (RuntimeException ignored) { }
                dec.release();
            }
            if (enc != null) {
                try { enc.stop(); } catch (RuntimeException ignored) { }
                enc.release();
            }
            if (mux != null) {
                try {
                    if (muxing) {
                        mux.stop();
                    }
                } catch (RuntimeException ignored) {
                    // left half written; the caller deletes it
                }
                mux.release();
            }
            if (frames != null) {
                frames.release();
            }
            if (egl != null) {
                egl.release();
            }
        }
    }

    private static int rotation(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            String v = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            return v == null ? 0 : Integer.parseInt(v);
        } catch (RuntimeException e) {
            return 0;
        } finally {
            r.release();
        }
    }

    /** An EGL context drawing onto the encoder's input surface. */
    static final class Egl {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        final EGLDisplay display;
        final EGLContext context;
        final EGLSurface surface;
        final Surface window;

        Egl(Surface window) {
            this(window, 0, 0);
        }

        /** An off-screen surface of w x h, to read pixels back from (HEIC photos). */
        Egl(int w, int h) {
            this(null, w, h);
        }

        private Egl(Surface window, int w, int h) {
            this.window = window;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(display, v, 0, v, 1)) {
                throw new RuntimeException("no EGL");
            }
            int[] attrs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, window == null ? 8 : 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                window == null ? EGL14.EGL_SURFACE_TYPE : EGL_RECORDABLE_ANDROID,
                window == null ? EGL14.EGL_PBUFFER_BIT : 1,
                EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) || n[0] == 0) {
                throw new RuntimeException("no suitable EGL config");
            }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = window != null
                    ? EGL14.eglCreateWindowSurface(display, configs[0], window, new int[] {EGL14.EGL_NONE}, 0)
                    : EGL14.eglCreatePbufferSurface(display, configs[0],
                            new int[] {EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE}, 0);
            if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
                throw new RuntimeException("EGL setup failed");
            }
        }

        void present(long ns) {
            EGLExt.eglPresentationTimeANDROID(display, surface, ns);
            EGL14.eglSwapBuffers(display, surface);
        }

        void release() {
            // No eglTerminate: the display is shared with the web view in this process.
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            if (window != null) {
                window.release();
            }
        }
    }

    /** The decoder's output, as a texture drawn full-size onto the encoder's surface. */
    static final class Frames implements SurfaceTexture.OnFrameAvailableListener {
        private static final String VS =
                "uniform mat4 uSt;\n"
                + "attribute vec4 aPos;\n"
                + "attribute vec4 aTex;\n"
                + "varying vec2 vTex;\n"
                + "void main() {\n"
                + "  gl_Position = vec4(aPos.xy, 0.0, 1.0);\n"
                + "  vTex = (uSt * vec4(aTex.xy, 0.0, 1.0)).xy;\n"
                + "}\n";
        private static final String FS =
                "#extension GL_OES_EGL_image_external : require\n"
                + "precision mediump float;\n"
                + "varying vec2 vTex;\n"
                + "uniform samplerExternalOES sTex;\n"
                + "void main() {\n"
                + "  gl_FragColor = texture2D(sTex, vTex);\n"
                + "}\n";

        final SurfaceTexture texture;
        final Surface surface;
        private final int tex, program, aPos, aTex, uSt;
        private final float[] matrix = new float[16];
        private final FloatBuffer quad, flipped;
        private boolean ready;

        Frames() {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            tex = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            // With no looper on this thread, frame callbacks arrive on the main thread.
            texture = new SurfaceTexture(tex);
            texture.setOnFrameAvailableListener(this);
            surface = new Surface(texture);

            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VS));
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FS));
            GLES20.glLinkProgram(program);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                throw new RuntimeException("shader link: " + GLES20.glGetProgramInfoLog(program));
            }
            aPos = GLES20.glGetAttribLocation(program, "aPos");
            aTex = GLES20.glGetAttribLocation(program, "aTex");
            uSt = GLES20.glGetUniformLocation(program, "uSt");
            quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(new float[] {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1}).position(0);
            flipped = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            flipped.put(new float[] {-1, -1, 0, 1, 1, -1, 1, 1, -1, 1, 0, 0, 1, 1, 1, 0}).position(0);
        }

        private static int shader(int type, String src) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                throw new RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s));
            }
            return s;
        }

        @Override
        public synchronized void onFrameAvailable(SurfaceTexture st) {
            ready = true;
            notifyAll();
        }

        synchronized void await() throws IOException {
            long end = SystemClock.uptimeMillis() + 3000;
            while (!ready) {
                long left = end - SystemClock.uptimeMillis();
                if (left <= 0) {
                    throw new IOException("the tablet's decoder stopped sending pictures");
                }
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    throw new IOException("interrupted");
                }
            }
            ready = false;
            texture.updateTexImage();
        }

        void draw(int w, int h) {
            draw(0, 0, w, h, false);
        }

        /**
         * Draws the latest picture into the rectangle at x, y (from the bottom
         * left); upsideDown for pixels that will be read back top row first.
         */
        void draw(int x, int y, int w, int h, boolean upsideDown) {
            FloatBuffer quad = upsideDown ? flipped : this.quad;
            GLES20.glViewport(x, y, w, h);
            texture.getTransformMatrix(matrix);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glUniformMatrix4fv(uSt, 1, false, matrix, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        void release() {
            surface.release();
            texture.release();
            GLES20.glDeleteProgram(program);
            GLES20.glDeleteTextures(1, new int[] {tex}, 0);
        }
    }
}
