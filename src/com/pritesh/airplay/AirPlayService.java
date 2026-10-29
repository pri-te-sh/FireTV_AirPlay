package com.pritesh.airplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Keeps the receiver running and discoverable in the background, and brings up the player screen
 * when a phone starts mirroring or playing. Started at boot and whenever the app opens.
 */
public final class AirPlayService extends Service implements Hub.Listener {
    private static final String CHANNEL = "airplay";

    private Receiver receiver;
    private Bonjour bonjour;
    private WifiManager.MulticastLock multicast;
    private PowerManager.WakeLock wake;
    private Hub.Mode shown = Hub.Mode.IDLE;
    private static volatile boolean ready;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, AirPlayService.class));
    }

    static boolean isReady() {
        return ready;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "AirPlay", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentTitle("AirPlay receiver")
                .setContentText("Ready for AirPlay")
                .setOngoing(true)
                .build();
        startForeground(1, n);

        multicast = getSystemService(WifiManager.class).createMulticastLock("airplay");
        multicast.setReferenceCounted(false);
        multicast.acquire();

        Identity id = new Identity(this);
        receiver = new Receiver(id);
        Hub.setReceiver(receiver);
        Hub.addListener(this);
        new Thread(() -> {
            try {
                int port = receiver.start();
                bonjour = new Bonjour(this);
                bonjour.register(id, port);
                ready = true;
                Hub.notifyReady();
            } catch (Exception e) {
                Log.e(Receiver.TAG, "Receiver failed to start", e);
            }
        }, "airplay-start").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onHubChanged() {
        Hub.Mode mode = Hub.mode();
        if (mode != Hub.Mode.IDLE && shown == Hub.Mode.IDLE) {
            // A session started: show it over whatever is on screen, like an Apple TV does.
            startActivity(new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        holdWake(mode != Hub.Mode.IDLE);
        shown = mode;
    }

    /** Keep the TV awake (no screensaver) while something is playing. */
    private void holdWake(boolean on) {
        if (on && wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "airplay:session");
            wake.acquire(6 * 60 * 60 * 1000L);
        } else if (!on && wake != null) {
            if (wake.isHeld()) wake.release();
            wake = null;
        }
    }

    @Override
    public void onDestroy() {
        ready = false;
        Hub.removeListener(this);
        Hub.setReceiver(null);
        if (bonjour != null) bonjour.unregister();
        if (receiver != null) receiver.stop();
        if (multicast != null && multicast.isHeld()) multicast.release();
        holdWake(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
