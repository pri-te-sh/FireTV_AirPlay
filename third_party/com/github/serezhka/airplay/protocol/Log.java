package com.github.serezhka.airplay.protocol;

/** Minimal slf4j-style logger over android.util.Log (replaces lombok's @Slf4j in this vendored copy). */
public final class Log {
    private final String tag;

    private Log(String tag) { this.tag = tag; }

    public static Log of(Class<?> c) { return new Log("AirPlay/" + c.getSimpleName()); }

    public boolean isDebugEnabled() { return android.util.Log.isLoggable("AirPlay", android.util.Log.DEBUG); }

    public void debug(String f, Object... a) { if (isDebugEnabled()) android.util.Log.d(tag, fmt(f, a)); }
    public void info(String f, Object... a) { android.util.Log.i(tag, fmt(f, a)); }
    public void warn(String f, Object... a) { android.util.Log.w(tag, fmt(f, a)); }
    public void error(String f, Object... a) { android.util.Log.e(tag, fmt(f, a)); }

    private static String fmt(String f, Object[] a) {
        StringBuilder sb = new StringBuilder();
        int i = 0, from = 0, at;
        while ((at = f.indexOf("{}", from)) >= 0 && i < a.length) {
            sb.append(f, from, at).append(a[i++]);
            from = at + 2;
        }
        return sb.append(f.substring(from)).toString();
    }
}
