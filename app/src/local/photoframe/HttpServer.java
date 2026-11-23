package local.photoframe;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A very small HTTP server: one request per connection, no keep-alive. */
final class HttpServer {
    static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String TAG = "FrameHttp";

    interface Handler {
        void handle(Request req, Response res) throws Exception;
    }

    static final class Request {
        String method;
        String path;
        final Map<String, String> query = new HashMap<String, String>();
        final Map<String, String> headers = new HashMap<String, String>();
        long contentLength = -1;
        InputStream body;

        String param(String name, String fallback) {
            String v = query.get(name);
            return v == null ? fallback : v;
        }

        /** The request body as text, for small JSON bodies only. */
        String bodyText() throws IOException {
            if (contentLength < 0 || contentLength > 256 * 1024) {
                throw new IOException("body too large");
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = body.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            return new String(buf.toByteArray(), UTF8);
        }
    }

    static final class Response {
        private final OutputStream out;
        private boolean head;
        boolean sent;

        Response(OutputStream out) {
            this.out = out;
        }

        void send(int code, String type, byte[] body, String cache) throws IOException {
            writeHead(code, type, body.length, cache);
            if (!head) {
                out.write(body);
            }
            out.flush();
        }

        void text(int code, String message) throws IOException {
            send(code, "text/plain; charset=utf-8", message.getBytes(UTF8), "no-store");
        }

        void json(Object value) throws IOException {
            send(200, "application/json; charset=utf-8", value.toString().getBytes(UTF8), "no-store");
        }

        void file(File f, String type, String cache) throws IOException {
            file(f, type, cache, null);
        }

        /** Sends a file, or the part of it a Range header asks for (videos). */
        void file(File f, String type, String cache, String range) throws IOException {
            long size = f.length(), from = 0, to = size - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=") && size > 0) {
                try {
                    String[] r = range.substring(6).split("-", 2);
                    if (r[0].isEmpty()) {
                        from = Math.max(0, size - Long.parseLong(r[1].trim()));
                    } else {
                        from = Long.parseLong(r[0].trim());
                        if (r.length > 1 && !r[1].trim().isEmpty()) {
                            to = Math.min(to, Long.parseLong(r[1].trim()));
                        }
                    }
                    partial = true;
                } catch (NumberFormatException e) {
                    from = 0;
                    to = size - 1;
                }
                if (partial && (from > to || from >= size)) {
                    writeHead(416, type, 0, cache, "Content-Range: bytes */" + size + "\r\n");
                    out.flush();
                    return;
                }
            }
            long left = to - from + 1;
            writeHead(partial ? 206 : 200, type, left, cache, "Accept-Ranges: bytes\r\n"
                    + (partial ? "Content-Range: bytes " + from + "-" + to + "/" + size + "\r\n" : ""));
            if (!head) {
                java.io.RandomAccessFile in = new java.io.RandomAccessFile(f, "r");
                try {
                    in.seek(from);
                    byte[] b = new byte[64 * 1024];
                    int n;
                    while (left > 0 && (n = in.read(b, 0, (int) Math.min(b.length, left))) > 0) {
                        out.write(b, 0, n);
                        left -= n;
                    }
                } finally {
                    in.close();
                }
            }
            out.flush();
        }

        private void writeHead(int code, String type, long length, String cache) throws IOException {
            writeHead(code, type, length, cache, "");
        }

        /** Extra header lines for the next response, e.g. Content-Disposition. */
        String headers = "";

        private void writeHead(int code, String type, long length, String cache, String extra)
                throws IOException {
            sent = true;
            extra = extra + headers;
            String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + length + "\r\n"
                    + "Cache-Control: " + cache + "\r\n"
                    + extra
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(UTF8));
        }

        private static String reason(int code) {
            switch (code) {
                case 200: return "OK";
                case 206: return "Partial Content";
                case 416: return "Range Not Satisfiable";
                case 507: return "Insufficient Storage";
                case 400: return "Bad Request";
                case 404: return "Not Found";
                case 405: return "Method Not Allowed";
                case 411: return "Length Required";
                case 413: return "Payload Too Large";
                case 415: return "Unsupported Media Type";
                case 500: return "Internal Server Error";
                default: return "Status";
            }
        }
    }

    private final int port;
    private final Handler handler;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private volatile ServerSocket socket;
    private volatile boolean running;

    HttpServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    /** Listens in the background, retrying until the port is free. */
    void start() {
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        ServerSocket ss = new ServerSocket();
                        ss.setReuseAddress(true);
                        ss.bind(new InetSocketAddress(port), 50);
                        socket = ss;
                        Log.i(TAG, "listening on " + port);
                        while (running) {
                            final Socket s = ss.accept();
                            pool.execute(new Runnable() {
                                @Override
                                public void run() {
                                    serve(s);
                                }
                            });
                        }
                    } catch (IOException e) {
                        if (!running) {
                            return;
                        }
                        Log.w(TAG, "server socket: " + e);
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
            }
        }, "http-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(60 * 1000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            OutputStream out = s.getOutputStream();
            Request req = readHead(in);
            Response res = new Response(out);
            if (req == null) {
                res.text(400, "bad request");
                return;
            }
            res.head = "HEAD".equals(req.method);
            req.body = new Limited(in, Math.max(req.contentLength, 0));
            try {
                handler.handle(req, res);
                if (!res.sent) {
                    res.text(404, "not found");
                }
            } catch (Exception e) {
                Log.w(TAG, req.method + " " + req.path + ": " + e);
                if (!res.sent) {
                    res.text(500, "error: " + e.getMessage());
                }
            }
        } catch (IOException ignored) {
            // client went away
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Request readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0 || buf.size() > 32 * 1024) {
                return null;
            }
            buf.write(c);
            if ((state == 0 || state == 2) && c == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && c == '\n') {
                state++;
            } else {
                state = c == '\r' ? 1 : 0;
            }
        }
        String[] lines = new String(buf.toByteArray(), UTF8).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length < 2) {
            return null;
        }
        Request req = new Request();
        req.method = first[0].toUpperCase(Locale.US);
        String target = first[1];
        int q = target.indexOf('?');
        req.path = decode(q < 0 ? target : target.substring(0, q), false);
        if (q >= 0) {
            for (String pair : target.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    req.query.put(decode(pair.substring(0, eq), true),
                            decode(pair.substring(eq + 1), true));
                }
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                req.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
            }
        }
        String len = req.headers.get("content-length");
        if (len != null) {
            try {
                req.contentLength = Long.parseLong(len);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return req;
    }

    private static String decode(String s, boolean plusIsSpace) {
        try {
            return URLDecoder.decode(plusIsSpace ? s : s.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    /** Reads at most the request body's length from the connection. */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long length) {
            super(in);
            left = length;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int c = super.read();
            if (c >= 0) {
                left--;
            }
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) {
                left -= n;
            }
            return n;
        }
    }
}
