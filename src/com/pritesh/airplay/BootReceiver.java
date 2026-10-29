package com.pritesh.airplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the receiver when the TV boots or the app is updated, so it's always discoverable. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        AirPlayService.start(c);
    }
}
