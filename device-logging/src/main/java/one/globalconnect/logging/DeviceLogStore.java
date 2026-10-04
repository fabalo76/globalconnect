package one.globalconnect.logging;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.*;
import java.util.zip.GZIPOutputStream;

/** Opt-in metadata diagnostics. Callers must never pass transaction payloads or secrets. */
public final class DeviceLogStore {
    public static final int FILE_BYTES = 1024 * 1024;
    public static final int FILE_COUNT = 4;
    public static final String ACTION = "one.globalconnect.logging.CONTROL";
    public static final String PERMISSION = "one.globalconnect.permission.DEVICE_LOG_CONTROL";
    private DeviceLogStore() {}
    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("device-log-session", 0); }
    private static File root(Context c) { return new File(c.getNoBackupFilesDir(), "device-logs"); }
    public static synchronized long enable(Context c, int hours, long expires) {
        if (hours < 1 || hours > 72) throw new IllegalArgumentException("LOG_DURATION_INVALID");
        long duration = Math.min(expires - System.currentTimeMillis(), hours * 3600000L);
        if (duration <= 0 || expires > System.currentTimeMillis() + hours * 3600000L + 60000)
            throw new IllegalArgumentException("LOG_DURATION_INVALID");
        File directory = root(c);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("LOG_STORAGE_ERROR");
        if (!prefs(c).edit().putLong("expires", expires).putLong("elapsed", SystemClock.elapsedRealtime() + duration)
                .putInt("boot", Settings.Global.getInt(c.getContentResolver(), "boot_count", -1)).commit())
            throw new IllegalStateException("LOG_STORAGE_ERROR");
        schedule(c);
        record(c, "session enabled hours=" + hours);
        return expires;
    }
    public static synchronized void stop(Context c) {
        prefs(c).edit().clear().commit();
        ((android.app.AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(alarm(c));
    }
    private static android.app.PendingIntent alarm(Context c) {
        return android.app.PendingIntent.getBroadcast(c, 0, new android.content.Intent(c, DeviceLogExpiryReceiver.class),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }
    static synchronized void schedule(Context c) {
        ((android.app.AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).setAndAllowWhileIdle(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, prefs(c).getLong("elapsed", 0), alarm(c));
    }
    static boolean active(long now, long elapsed, int boot, long expires, long elapsedExpires, int savedBoot) {
        return boot >= 0 && boot == savedBoot && now < expires && elapsed < elapsedExpires;
    }
    public static synchronized boolean enabled(Context c) {
        boolean active = active(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
            Settings.Global.getInt(c.getContentResolver(), "boot_count", -1), prefs(c).getLong("expires", 0),
            prefs(c).getLong("elapsed", 0), prefs(c).getInt("boot", -2));
        if (!active && prefs(c).contains("expires")) stop(c);
        return active;
    }
    public static synchronized long expiresAt(Context c) {
        return enabled(c) ? prefs(c).getLong("expires", 0) : 0;
    }
    public static synchronized void record(Context c, String event) {
        if (!enabled(c)) return;
        try {
            File dir = root(c);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            // Defence in depth; integrations below supply metadata, never arbitrary wire/logcat output.
            String safe = event.replace('\n', ' ').replace('\r', ' ').replaceAll("(?i)(password|secret|token|pin|cvv|track|key)\\s*[:=]\\s*\\S+", "$1=[redacted]")
                .replaceAll("(?<![0-9])[0-9]{12,19}(?![0-9])", "[redacted]");
            if (safe.length() > 2000) safe = safe.substring(0, 2000);
            byte[] line = (java.time.Instant.now() + " " + safe + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            append(dir, line);
        } catch (IOException ignored) { }
    }
    static void append(File dir, byte[] line) throws IOException {
        File file = new File(dir, "log-0.txt");
        if (file.length() + line.length > FILE_BYTES) {
            File oldest = new File(dir, "log-" + (FILE_COUNT - 1) + ".txt");
            if (oldest.exists() && !oldest.delete()) throw new IOException("rotation failed");
            for (int i = FILE_COUNT - 2; i >= 0; i--) {
                File from = new File(dir, "log-" + i + ".txt");
                if (from.exists() && !from.renameTo(new File(dir, "log-" + (i + 1) + ".txt"))) throw new IOException("rotation failed");
            }
        }
        try (FileOutputStream output = new FileOutputStream(file, true)) { output.write(line); }
    }
    public static synchronized File snapshot(Context c) throws IOException {
        File file = collected(c);
        record(c, "diagnostic logs collected");
        File dir = root(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("LOG_STORAGE_ERROR");
        String version;
        try { version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException error) { version = "unknown"; }
        String header = "Global Connect device diagnostics\nPackage: " + c.getPackageName()
            + "\nVersion: " + version + "\nModel: " + android.os.Build.MODEL
            + "\nCollected: " + java.time.Instant.now() + "\nLogging expires: " + expiresAt(c) + "\n\n";
        writeSnapshot(dir, file, header);
        return file;
    }
    static void writeSnapshot(File dir, File file, String header) throws IOException {
        try (GZIPOutputStream output = new GZIPOutputStream(new FileOutputStream(file))) {
            output.write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (int i = FILE_COUNT - 1; i >= 0; i--) {
                File source = new File(dir, "log-" + i + ".txt");
                if (!source.isFile()) continue;
                try (FileInputStream input = new FileInputStream(source)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                }
            }
        }
    }
    static File collected(Context c) { return new File(root(c), "collected.txt.gz"); }
}
