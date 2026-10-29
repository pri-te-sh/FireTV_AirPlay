package com.pritesh.airplay;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The few styling tokens these screens share. Sizes are in 1920x1080 design pixels, scaled to the
 * window (the Fire TV's UI is 1920x1080 at density 2).
 */
final class Ui {
    static final int LABEL = 0xFFFFFFFF;
    static final int LABEL_2 = 0xB3FFFFFF;
    static final int LABEL_3 = 0x80FFFFFF;
    static final int CARD = 0xFF1C1C1E;
    static final int FOCUS = 0xFFFFFFFF;
    static final int ON_FOCUS = 0xFF000000;

    private final Context c;
    private final float scale;

    Ui(Context c) {
        this.c = c;
        scale = c.getResources().getDisplayMetrics().widthPixels / 1920f;
    }

    int px(float design) {
        return Math.round(design * scale);
    }

    TextView text(float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radius));
        return d;
    }
}
