package one.globalconnect.logging;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

public class DeviceLogStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void boundedRotationPreservesRecentEntries() throws Exception {
        File dir = temporary.newFolder();
        byte[] line = new byte[100000];
        for (int i = 0; i < 100; i++) { java.util.Arrays.fill(line, (byte)i); DeviceLogStore.append(dir, line); }
        File[] files = dir.listFiles(); assertNotNull(files); assertEquals(4, files.length);
        long total = 0;
        for (File file : files) { assertTrue(file.length() <= DeviceLogStore.FILE_BYTES); total += file.length(); }
        assertTrue(total <= 4L * DeviceLogStore.FILE_BYTES);
        byte[] newest = java.nio.file.Files.readAllBytes(new File(dir, "log-0.txt").toPath());
        assertEquals(99, newest[newest.length - 1]);
    }
    @Test public void expiryCannotBeExtendedByClockRollbackOrReboot() {
        assertTrue(DeviceLogStore.active(100, 100, 1, 200, 200, 1));
        assertFalse(DeviceLogStore.active(200, 100, 1, 200, 200, 1));
        assertFalse(DeviceLogStore.active(50, 200, 1, 200, 200, 1));
        assertFalse(DeviceLogStore.active(100, 100, 2, 200, 200, 1));
        assertFalse(DeviceLogStore.active(100, 100, -1, 200, 200, -1));
    }
}
