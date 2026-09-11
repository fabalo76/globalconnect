package one.globalconnect.paymentapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootUpReceiver extends BroadcastReceiver {
    private static final String TAG = "BootUpReceiver";
    private static final String ACTION_AUTO_START_AFTER_BOOT =
            "one.globalconnect.paymentapp.ACTION_AUTO_START_AFTER_BOOT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!shouldAutoStart(action)) {
            Log.d(TAG, "Ignoring broadcast action=" + action);
            return;
        }

        // Android 10+ rejects startActivity() from a BOOT_COMPLETED receiver. Ask
        // the device's xTMS HOME launcher to perform the launch once it is resumed.
        String agentPackage = agentPackageFor(context.getPackageName());
        Intent request = new Intent(ACTION_AUTO_START_AFTER_BOOT).setPackage(agentPackage);
        context.sendBroadcast(request);
        Log.i(TAG, "Boot completed; requested payment startup from " + agentPackage);
    }

    static boolean shouldAutoStart(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action);
    }

    static String agentPackageFor(String paymentPackage) {
        return paymentPackage.replace(".paymentapp", ".xtmsagent");
    }
}
