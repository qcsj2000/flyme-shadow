package com.flyme.shadow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives authenticated Hook diagnostics even when the UI process is not running. */
public final class DiagnosticLogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Config.ACTION_DIAGNOSTIC_LOG.equals(intent.getAction())) {
            App.acceptDiagnosticLog(context, intent);
        }
    }
}
