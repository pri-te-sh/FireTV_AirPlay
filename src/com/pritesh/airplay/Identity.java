package com.pritesh.airplay;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Base64;

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/**
 * Who this receiver is to the phone. Kept stable across restarts so iOS recognises it as the
 * same device: the pairing key, a MAC-style device id (Android hides the real MAC) and UUIDs.
 */
final class Identity {
    private final SharedPreferences prefs;
    private final PairingIdentity pairing;
    private final Context context;

    Identity(Context c) {
        context = c.getApplicationContext();
        prefs = c.getSharedPreferences("identity", Context.MODE_PRIVATE);
        String seed = prefs.getString("seed", null);
        if (seed == null) {
            PairingIdentity fresh = PairingIdentity.generate();
            prefs.edit().putString("seed", Base64.encodeToString(fresh.seed(), Base64.NO_WRAP)).apply();
            pairing = fresh;
        } else {
            pairing = PairingIdentity.fromSeed(Base64.decode(seed, Base64.NO_WRAP));
        }
    }

    PairingIdentity pairing() {
        return pairing;
    }

    /** The name shown in the phone's AirPlay list: the name given to this Fire TV in its settings. */
    String name() {
        String custom = prefs.getString("name", null);
        if (custom != null) return custom;
        String n = Settings.Global.getString(context.getContentResolver(), "device_name");
        return n == null || n.trim().isEmpty() ? "Fire TV" : n.trim();
    }

    /** Locally administered unicast MAC, e.g. "5A:3C:…". */
    String deviceId() {
        String id = prefs.getString("deviceId", null);
        if (id == null) {
            byte[] b = new byte[6];
            new SecureRandom().nextBytes(b);
            b[0] = (byte) ((b[0] & 0xFC) | 0x02);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < b.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.ROOT, "%02X", b[i]));
            }
            id = sb.toString();
            prefs.edit().putString("deviceId", id).apply();
        }
        return id;
    }

    String pairingUuid() {
        return uuid("pi");
    }

    String displayUuid() {
        return uuid("display");
    }

    private String uuid(String key) {
        String u = prefs.getString(key, null);
        if (u == null) {
            u = UUID.randomUUID().toString();
            prefs.edit().putString(key, u).apply();
        }
        return u;
    }
}
