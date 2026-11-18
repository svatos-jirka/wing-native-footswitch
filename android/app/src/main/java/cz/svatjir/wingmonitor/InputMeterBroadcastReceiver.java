package cz.svatjir.wingmonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives meter snapshots sent by the local PC input simulator over ADB. */
public final class InputMeterBroadcastReceiver extends BroadcastReceiver {
    public static final String ACTION = "cz.svatjir.wingmonitor.SIMULATED_METER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String encoded = intent.getStringExtra("meters_b64");
        if (encoded != null && !encoded.isEmpty()) {
            MainActivity.acceptInputMeterBroadcast(encoded);
        }
    }
}
