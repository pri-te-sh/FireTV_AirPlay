package com.pritesh.airplay;

import java.nio.charset.StandardCharsets;

/** Now-playing text from a DMAP item ({@code mlit} with {@code minm}/{@code asar}/{@code asal}). */
final class Dmap {
    String title, artist, album;

    private Dmap() {}

    static Dmap parse(byte[] b) {
        Dmap d = new Dmap();
        d.walk(b, 0, b.length);
        return d;
    }

    private void walk(byte[] b, int from, int to) {
        int i = from;
        while (i + 8 <= to) {
            String tag = new String(b, i, 4, StandardCharsets.US_ASCII);
            int len = (b[i + 4] & 0xFF) << 24 | (b[i + 5] & 0xFF) << 16 | (b[i + 6] & 0xFF) << 8 | (b[i + 7] & 0xFF);
            int start = i + 8;
            if (len < 0 || start + len > to) return;
            switch (tag) {
                case "mlit":
                    walk(b, start, start + len);
                    break;
                case "minm":
                    title = text(b, start, len);
                    break;
                case "asar":
                    artist = text(b, start, len);
                    break;
                case "asal":
                    album = text(b, start, len);
                    break;
                default:
                    break;
            }
            i = start + len;
        }
    }

    private static String text(byte[] b, int at, int len) {
        String s = new String(b, at, len, StandardCharsets.UTF_8).trim();
        return s.isEmpty() ? null : s;
    }
}
