package one.globalconnect.logging;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import static org.junit.Assert.*;

public class DeviceLogCollectionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void compressedCollectionIncludesHistoryInOrderAndLeavesAgentSnapshotUntouched() throws Exception {
        File dir = temporary.newFolder();
        Files.write(new File(dir, "log-1.txt").toPath(), "older\n".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "log-0.txt").toPath(), "latest\n".getBytes(StandardCharsets.UTF_8));
        File agent = new File(dir, "collected.txt.gz");
        Files.write(agent.toPath(), "agent snapshot".getBytes(StandardCharsets.UTF_8));
        File local = temporary.newFile("local.txt.gz");
        DeviceLogStore.writeSnapshot(dir, local, "header\n");
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(local.toPath()))) {
            assertEquals("header\nolder\nlatest\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("agent snapshot", new String(Files.readAllBytes(agent.toPath()), StandardCharsets.UTF_8));
        assertEquals("latest\n", new String(Files.readAllBytes(new File(dir, "log-0.txt").toPath()), StandardCharsets.UTF_8));
    }

    @Test public void collectionAfterStopOrBeforeFirstEventStillHasReadableHeader() throws Exception {
        File output = temporary.newFile("empty.txt.gz");
        DeviceLogStore.writeSnapshot(temporary.newFolder(), output, "Logging disabled\n");
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(output.toPath()))) {
            assertEquals("Logging disabled\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test public void sessionCannotRemainEnabledAfterExpiryOrReboot() {
        assertTrue(DeviceLogStore.active(100, 100, 1, 200, 200, 1));
        assertFalse(DeviceLogStore.active(200, 100, 1, 200, 200, 1));
        assertFalse(DeviceLogStore.active(50, 200, 1, 200, 200, 1));
        assertFalse(DeviceLogStore.active(100, 100, 2, 200, 200, 1));
    }
}
