package com.pritesh.airplay;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** RTSP/1.0 and HTTP/1.1 messages as AirPlay senders use them on the control connection. */
final class Rtsp {
    private Rtsp() {}

    static final class Request {
        String method, uri, protocol;
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        String contentType() {
            String t = header("content-type");
            return t == null ? "" : t;
        }

        String path() {
            int q = uri.indexOf('?');
            return q < 0 ? uri : uri.substring(0, q);
        }

        @Override
        public String toString() {
            return method + " " + uri + " (" + body.length + " bytes)";
        }
    }

    static final class Response {
        final Request request;
        int status = 200;
        String reason = "OK";
        final Map<String, String> headers = new LinkedHashMap<>();
        byte[] body = new byte[0];

        Response(Request request) {
            this.request = request;
        }

        Response status(int code, String text) {
            status = code;
            reason = text;
            return this;
        }

        Response header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        Response body(byte[] bytes, String type) {
            body = bytes;
            if (type != null) headers.put("Content-Type", type);
            return this;
        }
    }

    /** Reads one request; returns null at a clean end of stream. */
    static Request read(InputStream in) throws IOException {
        String line = readLine(in);
        if (line == null) return null;
        while (line.isEmpty()) {
            line = readLine(in);
            if (line == null) return null;
        }
        String[] parts = line.split(" ");
        if (parts.length < 3) throw new IOException("Bad request line: " + line);
        Request r = new Request();
        r.method = parts[0];
        r.uri = parts[1];
        r.protocol = parts[2];
        for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
            int colon = h.indexOf(':');
            if (colon > 0) r.headers.put(h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
        }
        String length = r.header("content-length");
        if (length != null) {
            int n = Integer.parseInt(length);
            r.body = new byte[n];
            for (int off = 0; off < n; ) {
                int got = in.read(r.body, off, n - off);
                if (got < 0) throw new EOFException();
                off += got;
            }
        }
        return r;
    }

    static void write(OutputStream out, Response res) throws IOException {
        Request req = res.request;
        boolean http = req.protocol.startsWith("HTTP");
        StringBuilder sb = new StringBuilder();
        sb.append(http ? "HTTP/1.1" : "RTSP/1.0").append(' ').append(res.status).append(' ').append(res.reason).append("\r\n");
        String cseq = req.header("cseq");
        if (cseq != null) sb.append("CSeq: ").append(cseq).append("\r\n");
        if (!http) sb.append("Server: AirTunes/").append(Receiver.SOURCE_VERSION).append("\r\n");
        for (Map.Entry<String, String> h : res.headers.entrySet()) {
            sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        sb.append("Content-Length: ").append(res.body.length).append("\r\n\r\n");
        ByteArrayOutputStream buf = new ByteArrayOutputStream(sb.length() + res.body.length);
        buf.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        buf.write(res.body);
        out.write(buf.toByteArray());
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
