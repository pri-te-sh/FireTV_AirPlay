package com.pritesh.airplay;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Full-screen AirPlay: the mirrored screen, or Now Playing (artwork, title, progress) for audio.
 * Back while mirroring ends the session; Back on Now Playing leaves the music playing.
 */
public final class PlayerActivity extends Activity implements Hub.Listener, VideoPlayer.SizeListener, SurfaceHolder.Callback {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Ui u;
    private FrameLayout root;
    private SurfaceView screen;
    private View connecting;
    private TextView connectingText;
    private View nowPlaying;
    private ImageView backdrop, art;
    private TextView title, subtitle, elapsed, remaining, from;
    private View progressTrack, progressFill;
    private int videoW, videoH;
    private SurfaceHolder holder;
    private Hub.Mode shownMode = Hub.Mode.IDLE;
    private View transport;
    private View transportTrack, transportFill;
    private TextView transportElapsed, transportRemaining, transportState;
    private long transportUntil;
    private Bitmap shownArt;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            updateTransport();
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        u = new Ui(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        screen = new SurfaceView(this);
        screen.getHolder().addCallback(this);
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        connecting = buildConnecting();
        root.addView(connecting, new FrameLayout.LayoutParams(-1, -1));
        nowPlaying = buildNowPlaying();
        root.addView(nowPlaying, new FrameLayout.LayoutParams(-1, -1));
        transport = buildTransport();
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        tlp.setMargins(u.px(96), 0, u.px(96), u.px(72));
        root.addView(transport, tlp);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Hub.addListener(this);
        Hub.video.setSizeListener(this);
        onHubChanged();
        ui.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Hub.removeListener(this);
        Hub.video.setSizeListener(null);
        ui.removeCallbacks(tick);
        if (isChangingConfigurations()) return;
        // Without the screen there's nowhere to draw the mirror (and iOS won't resend a keyframe).
        if (Hub.mode() == Hub.Mode.MIRROR) {
            Hub.disconnect();
            finish();
        } else if (Hub.mode() == Hub.Mode.CAST) {
            Cast.stopFromTv();
            finish();
        }
    }

    @Override
    public void onHubChanged() {
        Hub.Mode mode = Hub.mode();
        if (mode == Hub.Mode.IDLE) {
            finish();
            return;
        }
        boolean cast = mode == Hub.Mode.CAST;
        boolean video = cast || mode == Hub.Mode.MIRROR;
        if (mode != shownMode) {
            // A different picture source: forget the old size until the new one reports.
            videoW = videoH = 0;
            screen.setLayoutParams(new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            shownMode = mode;
            route();
        }
        screen.setVisibility(video ? View.VISIBLE : View.GONE);
        nowPlaying.setVisibility(video ? View.GONE : View.VISIBLE);
        boolean waiting = cast ? !Cast.rendering() : video && videoW == 0;
        connecting.setVisibility(waiting ? View.VISIBLE : View.GONE);
        String sender = Hub.sender != null ? Hub.sender : "iPhone";
        connectingText.setText((cast ? "Loading video from " : "Connecting to ") + sender + "…");
        if (!video) bindNowPlaying(sender);
        if (!cast) transport.setVisibility(View.GONE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (Hub.mode() == Hub.Mode.MIRROR) Hub.disconnect();
            if (Hub.mode() == Hub.Mode.CAST) Cast.stopFromTv();
            finish();
            return true;
        }
        if (Hub.mode() == Hub.Mode.CAST) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                    Cast.togglePause();
                    showTransport();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    Cast.skip(-10);
                    showTransport();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    Cast.skip(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 10 : 30);
                    showTransport();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    showTransport();
                    return true;
                default:
                    break;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // Mirroring

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        route();
    }

    /** One surface, two possible sources: the mirror decoder or the video player. */
    private void route() {
        if (holder == null) return;
        if (Hub.mode() == Hub.Mode.CAST) {
            Hub.video.setSurface(null);
            Cast.attach(holder, this);
        } else {
            Cast.detach();
            Hub.video.setSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        holder = null;
        Hub.video.setSurface(null);
        Cast.detach();
    }

    @Override
    public void onVideoSize(int width, int height) {
        ui.post(() -> {
            videoW = width;
            videoH = height;
            if (Hub.mode() == Hub.Mode.MIRROR) connecting.setVisibility(View.GONE);
            fitScreen();
        });
    }

    /** Letterbox/pillarbox the picture: a portrait phone fills the height, centered. */
    private void fitScreen() {
        if (videoW <= 0 || videoH <= 0) return;
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) {
            root.post(this::fitScreen);
            return;
        }
        float scale = Math.min(w / (float) videoW, h / (float) videoH);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.round(videoW * scale), Math.round(videoH * scale), Gravity.CENTER);
        screen.setLayoutParams(lp);
    }

    private View buildConnecting() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        ImageView glyph = new ImageView(this);
        glyph.setImageResource(R.drawable.ic_airplay);
        glyph.setAlpha(0.9f);
        col.addView(glyph, new LinearLayout.LayoutParams(u.px(120), u.px(120)));
        connectingText = u.text(34, Ui.LABEL_2, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = u.px(32);
        col.addView(connectingText, lp);
        return col;
    }

    // Video from apps: transport bar on remote presses

    private View buildTransport() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(u.px(40), u.px(28), u.px(40), u.px(28));
        col.setBackground(u.rounded(0xB31C1C1E, 28));
        transportState = u.text(30, Ui.LABEL, true);
        col.addView(transportState);
        FrameLayout bar = new FrameLayout(this);
        transportTrack = new View(this);
        transportTrack.setBackground(u.rounded(0x40FFFFFF, 4));
        bar.addView(transportTrack, new FrameLayout.LayoutParams(-1, u.px(8)));
        transportFill = new View(this);
        transportFill.setBackground(u.rounded(0xE6FFFFFF, 4));
        bar.addView(transportFill, new FrameLayout.LayoutParams(0, u.px(8)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, u.px(8));
        barLp.topMargin = u.px(20);
        col.addView(bar, barLp);
        FrameLayout times = new FrameLayout(this);
        transportElapsed = u.text(24, Ui.LABEL_2, false);
        transportRemaining = u.text(24, Ui.LABEL_2, false);
        times.addView(transportElapsed, new FrameLayout.LayoutParams(-2, -2, Gravity.START));
        times.addView(transportRemaining, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
        LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(-1, -2);
        timesLp.topMargin = u.px(12);
        col.addView(times, timesLp);
        col.setVisibility(View.GONE);
        return col;
    }

    private void showTransport() {
        transportUntil = SystemClock.elapsedRealtime() + 4000;
        // Let the player apply the key before reading its state back.
        ui.postDelayed(this::updateTransport, 150);
        updateTransport();
    }

    private void updateTransport() {
        if (Hub.mode() != Hub.Mode.CAST) return;
        boolean paused = Cast.rendering() && !Cast.playing();
        boolean show = paused || SystemClock.elapsedRealtime() < transportUntil;
        transport.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        double dur = Cast.duration(), pos = Math.max(0, Cast.position());
        transportState.setText(paused ? "Paused" : "Playing from " + (Hub.sender != null ? Hub.sender : "iPhone"));
        boolean known = dur > 0;
        transportElapsed.setText(clock(pos));
        transportRemaining.setText(known ? "-" + clock(dur - pos) : "Live");
        int w = known ? (int) Math.round(transportTrack.getWidth() * Math.min(1, pos / dur)) : 0;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) transportFill.getLayoutParams();
        if (lp.width != w) {
            lp.width = w;
            transportFill.setLayoutParams(lp);
        }
    }

    // Now Playing

    private View buildNowPlaying() {
        FrameLayout f = new FrameLayout(this);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new ColorDrawable(0x99000000));
        f.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(u.px(160), 0, u.px(160), 0);

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(u.rounded(0xFF2C2C2E, 24));
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), u.px(24));
            }
        });
        art.setClipToOutline(true);
        art.setElevation(u.px(24));
        row.addView(art, new LinearLayout.LayoutParams(u.px(560), u.px(560)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, -2, 1);
        infoLp.leftMargin = u.px(88);
        row.addView(info, infoLp);

        title = u.text(60, Ui.LABEL, true);
        title.setMaxLines(2);
        info.addView(title);
        subtitle = u.text(36, Ui.LABEL_2, false);
        subtitle.setSingleLine(true);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = u.px(12);
        info.addView(subtitle, subLp);

        FrameLayout bar = new FrameLayout(this);
        progressTrack = new View(this);
        progressTrack.setBackground(u.rounded(0x40FFFFFF, 4));
        bar.addView(progressTrack, new FrameLayout.LayoutParams(-1, u.px(8)));
        progressFill = new View(this);
        progressFill.setBackground(u.rounded(0xE6FFFFFF, 4));
        bar.addView(progressFill, new FrameLayout.LayoutParams(0, u.px(8)));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, u.px(8));
        barLp.topMargin = u.px(64);
        info.addView(bar, barLp);

        FrameLayout times = new FrameLayout(this);
        elapsed = u.text(26, Ui.LABEL_3, false);
        remaining = u.text(26, Ui.LABEL_3, false);
        times.addView(elapsed, new FrameLayout.LayoutParams(-2, -2, Gravity.START));
        times.addView(remaining, new FrameLayout.LayoutParams(-2, -2, Gravity.END));
        LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(-1, -2);
        timesLp.topMargin = u.px(14);
        info.addView(times, timesLp);

        f.addView(row, new FrameLayout.LayoutParams(-1, -1));

        from = u.text(26, Ui.LABEL_3, false);
        FrameLayout.LayoutParams fromLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        fromLp.bottomMargin = u.px(64);
        f.addView(from, fromLp);
        return f;
    }

    private void bindNowPlaying(String sender) {
        title.setText(Hub.title != null ? Hub.title : "AirPlay");
        StringBuilder sub = new StringBuilder();
        if (Hub.artist != null) sub.append(Hub.artist);
        if (Hub.album != null) sub.append(sub.length() > 0 ? " — " : "").append(Hub.album);
        subtitle.setText(sub.length() > 0 ? sub : "Playing from " + sender);
        from.setText("AirPlay from " + sender);
        Bitmap a = Hub.artwork;
        if (a != shownArt) {
            shownArt = a;
            art.setImageBitmap(a);
            // A tiny copy scaled back up is a cheap, smooth blur for the backdrop.
            backdrop.setImageBitmap(a == null ? null : Bitmap.createScaledBitmap(a, 16, 16, true));
        }
        updateProgress();
    }

    private void updateProgress() {
        if (nowPlaying.getVisibility() != View.VISIBLE) return;
        double dur = Hub.duration, pos = Hub.position;
        boolean known = dur > 0 && pos >= 0;
        progressTrack.setVisibility(known ? View.VISIBLE : View.INVISIBLE);
        progressFill.setVisibility(known ? View.VISIBLE : View.INVISIBLE);
        elapsed.setVisibility(known ? View.VISIBLE : View.INVISIBLE);
        remaining.setVisibility(known ? View.VISIBLE : View.INVISIBLE);
        if (!known) return;
        double now = Math.min(dur, pos + (SystemClock.elapsedRealtime() - Hub.positionAt) / 1000.0);
        int trackW = progressTrack.getWidth();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) progressFill.getLayoutParams();
        int w = (int) Math.round(trackW * now / dur);
        if (lp.width != w) {
            lp.width = w;
            progressFill.setLayoutParams(lp);
        }
        elapsed.setText(clock(now));
        remaining.setText("-" + clock(dur - now));
    }

    private static String clock(double seconds) {
        int s = (int) Math.max(0, Math.round(seconds));
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
