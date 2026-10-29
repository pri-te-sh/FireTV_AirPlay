package com.pritesh.airplay;

import android.app.Activity;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;

/**
 * The app's own screen: whether the receiver is ready, the name to pick on the phone, and
 * anything that would stop it working (no overlay grant, a VPN in the way). The receiver itself
 * runs in {@link AirPlayService}, so leaving this screen doesn't stop AirPlay.
 */
public final class MainActivity extends Activity implements Hub.Listener {
    private Ui u;
    private TextView status, name, address, notes;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AirPlayService.start(this);
        u = new Ui(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(Color.BLACK);
        col.setPadding(u.px(240), 0, u.px(240), 0);

        ImageView glyph = new ImageView(this);
        glyph.setImageResource(R.drawable.ic_airplay);
        col.addView(glyph, new LinearLayout.LayoutParams(u.px(150), u.px(150)));

        status = u.text(30, Ui.LABEL_2, false);
        col.addView(status, centered(40));

        name = u.text(72, Ui.LABEL, true);
        name.setGravity(Gravity.CENTER);
        col.addView(name, centered(16));

        TextView how = u.text(30, Ui.LABEL_2, false);
        how.setGravity(Gravity.CENTER);
        how.setLineSpacing(0, 1.25f);
        how.setText("On your iPhone or iPad, open Control Center, choose Screen Mirroring, then pick this TV.\n"
                + "For music, tap AirPlay in the Music app or Control Center.");
        col.addView(how, centered(36));

        notes = u.text(26, 0xFFFFD60A, false);
        notes.setGravity(Gravity.CENTER);
        notes.setLineSpacing(0, 1.25f);
        col.addView(notes, centered(48));

        address = u.text(24, Ui.LABEL_3, false);
        col.addView(address, centered(48));

        setContentView(col);
    }

    private LinearLayout.LayoutParams centered(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = u.px(top);
        return lp;
    }

    @Override
    protected void onStart() {
        super.onStart();
        Hub.addListener(this);
        onHubChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Hub.removeListener(this);
    }

    @Override
    public void onHubChanged() {
        // Without the overlay grant the player can't open by itself, so opening the app does it.
        if (Hub.mode() != Hub.Mode.IDLE) {
            startActivity(new android.content.Intent(this, PlayerActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        status.setText(AirPlayService.isReady() ? "Ready for AirPlay" : "Starting AirPlay…");
        name.setText(new Identity(this).name());
        String ip = wifiAddress();
        address.setText(ip == null ? "Not connected to a network" : "Receiver at " + ip + ":7000");

        StringBuilder n = new StringBuilder();
        if (!Settings.canDrawOverlays(this)) {
            n.append("To open by itself when you AirPlay, run once from a computer:\n"
                    + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow\n"
                    + "Until then, open this app after you start AirPlay on your phone.\n");
        }
        if (vpnOn()) {
            n.append("A VPN is on. If your iPhone doesn't list this TV, exclude AirPlay in the VPN's split tunneling.");
        }
        notes.setText(n.toString().trim());
    }

    private String wifiAddress() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null) continue;
            for (LinkAddress a : lp.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLoopbackAddress()) {
                    return a.getAddress().getHostAddress();
                }
            }
        }
        return null;
    }

    private boolean vpnOn() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        for (Network net : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        }
        return false;
    }
}
