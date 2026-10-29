package com.pritesh.airplay;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

/**
 * Advertises the receiver over mDNS with Android's own responder: {@code _airplay._tcp} (video,
 * mirroring) and {@code _raop._tcp} (audio), with the TXT records iOS reads to pick a protocol.
 */
final class Bonjour {
    private final NsdManager nsd;
    private final Listener airplay = new Listener("_airplay._tcp"), raop = new Listener("_raop._tcp");

    Bonjour(Context c) {
        nsd = c.getSystemService(NsdManager.class);
    }

    void register(Identity id, int port) {
        String pk = hex(id.pairing().publicKey());
        String features = Receiver.featuresTxt();

        NsdServiceInfo a = new NsdServiceInfo();
        a.setServiceName(id.name());
        a.setServiceType("_airplay._tcp");
        a.setPort(port);
        a.setAttribute("deviceid", id.deviceId());
        a.setAttribute("features", features);
        a.setAttribute("flags", "0x44");
        a.setAttribute("model", Receiver.MODEL);
        a.setAttribute("pi", id.pairingUuid());
        a.setAttribute("pk", pk);
        a.setAttribute("pw", "false");
        a.setAttribute("rhd", "5.6.0.0");
        a.setAttribute("srcvers", Receiver.SOURCE_VERSION);
        a.setAttribute("vv", "2");
        nsd.registerService(a, NsdManager.PROTOCOL_DNS_SD, airplay);

        NsdServiceInfo r = new NsdServiceInfo();
        r.setServiceName(id.deviceId().replace(":", "") + "@" + id.name());
        r.setServiceType("_raop._tcp");
        r.setPort(port);
        r.setAttribute("am", Receiver.MODEL);
        r.setAttribute("ch", "2");
        r.setAttribute("cn", "0,1,2,3");
        r.setAttribute("da", "true");
        r.setAttribute("et", "0,3,5");
        r.setAttribute("ft", features);
        r.setAttribute("md", "0,1,2");
        r.setAttribute("pk", pk);
        r.setAttribute("sf", "0x44");
        r.setAttribute("sr", "44100");
        r.setAttribute("ss", "16");
        r.setAttribute("sv", "false");
        r.setAttribute("tp", "UDP");
        r.setAttribute("txtvers", "1");
        r.setAttribute("vn", "65537");
        r.setAttribute("vs", Receiver.SOURCE_VERSION);
        nsd.registerService(r, NsdManager.PROTOCOL_DNS_SD, raop);
    }

    void unregister() {
        airplay.unregister();
        raop.unregister();
    }

    private final class Listener implements NsdManager.RegistrationListener {
        private final String type;
        private boolean registered;

        Listener(String type) {
            this.type = type;
        }

        void unregister() {
            if (!registered) return;
            registered = false;
            try {
                nsd.unregisterService(this);
            } catch (IllegalArgumentException ignored) {
            }
        }

        @Override
        public void onServiceRegistered(NsdServiceInfo info) {
            registered = true;
            Log.i(Receiver.TAG, "Advertised " + type + " as \"" + info.getServiceName() + "\"");
        }

        @Override
        public void onRegistrationFailed(NsdServiceInfo info, int error) {
            Log.e(Receiver.TAG, "Advertising " + type + " failed: " + error);
        }

        @Override
        public void onServiceUnregistered(NsdServiceInfo info) {
        }

        @Override
        public void onUnregistrationFailed(NsdServiceInfo info, int error) {
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
