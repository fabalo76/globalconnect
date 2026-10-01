package one.globalconnect.logging;

import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

public final class DeviceLogControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!DeviceLogStore.ACTION.equals(intent.getAction()) || !isOrderedBroadcast()) return;
        String sender = intent.getStringExtra("senderPackage");
        if (sender == null || !(sender.equals("one.globalconnect.xtmsagent") || sender.startsWith("one.globalconnect.xtmsagent."))
                || context.getPackageManager().checkSignatures(sender, context.getPackageName()) != PackageManager.SIGNATURE_MATCH) return;
        Bundle result = new Bundle();
        result.putString("packageName", context.getPackageName());
        try {
            switch (intent.getStringExtra("operation")) {
                case "enable": result.putLong("expiresAt", DeviceLogStore.enable(context, intent.getIntExtra("hours", 0), intent.getLongExtra("loggingExpiresAt", 0))); break;
                case "stop": DeviceLogStore.stop(context); break;
                case "release":
                    context.revokeUriPermission(Uri.parse("content://" + context.getPackageName() + ".device-logs/collected"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    DeviceLogStore.collected(context).delete(); break;
                case "collect":
                    DeviceLogStore.snapshot(context);
                    Uri uri = Uri.parse("content://" + context.getPackageName() + ".device-logs/collected");
                    context.grantUriPermission(sender, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    result.putString("logsUri", uri.toString()); break;
                default: throw new IllegalArgumentException("LOG_OPERATION_INVALID");
            }
            result.putBoolean("ok", true);
        } catch (Exception error) { result.putBoolean("ok", false); result.putString("code", "LOG_STORAGE_ERROR"); }
        setResultExtras(result);
    }
}
