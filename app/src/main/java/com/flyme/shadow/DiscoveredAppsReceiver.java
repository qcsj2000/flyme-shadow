package com.flyme.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives authenticated hidden-workspace package discoveries from the launcher hook. */
public final class DiscoveredAppsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Config.ACTION_SYNC_DISCOVERED.equals(intent.getAction())) return;
        App.acceptDiscoveredPackages(context, intent);
    }
}
