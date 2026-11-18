package cz.svatjir.wingmonitor;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives three-button pedal edges from the PC pedal simulator over ADB. */
public final class PedalBroadcastReceiver extends BroadcastReceiver {
    public static final String ACTION = "cz.svatjir.wingmonitor.PEDAL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        int button = intent.getIntExtra("button", 0);
        int pressed = intent.getIntExtra("pressed", -1);
        if (button >= BluetoothFootPedalServer.LEFT && button <= BluetoothFootPedalServer.RIGHT
                && (pressed == 0 || pressed == 1)) {
            MainActivity.acceptPedalBroadcast(button, pressed == 1);
        }
    }
}
