package one.globalconnect.logging;
import android.content.*;

public final class DeviceLogExpiryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        // A delayed alarm from an earlier session must not cancel a renewed one.
        if (DeviceLogStore.enabled(context)) DeviceLogStore.schedule(context);
        else DeviceLogStore.stop(context);
    }
}
