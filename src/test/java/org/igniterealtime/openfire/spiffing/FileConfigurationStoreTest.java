package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FileConfigurationStoreTest {
    @TempDir Path directory;

    @Test void missingFileIsUnconfiguredAndWritesSurviveRestart() throws Exception {
        var file = directory.resolve("spiffing.xml");
        var store = new FileConfigurationStore(file);
        assertNull(store.read());
        var service = new ConfigurationService(store);
        service.save(Fixtures.settings());
        var restarted = new ConfigurationService(new FileConfigurationStore(file));
        restarted.reload();
        assertEquals(Fixtures.settings(), restarted.current().settings());
        service.save(Fixtures.settings(LabelFormat.XML));
        restarted.reload();
        assertEquals(LabelFormat.XML, restarted.current().settings().outputFormat());
        try (var files = Files.list(directory)) { assertEquals(1, files.count(), "no temporary files retained"); }
    }

    @Test void failedAtomicReplacementCleansTemporaryFile() throws Exception {
        Path file = Files.createDirectory(directory.resolve("spiffing.xml"));
        Files.writeString(file.resolve("keep"), "existing data");
        var service = new ConfigurationService(new FileConfigurationStore(file));
        assertThrows(IllegalStateException.class, () -> service.save(Fixtures.settings()));
        assertNull(service.current());
        assertEquals("existing data", Files.readString(file.resolve("keep")));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void unreadableSettingsFailClosed() throws Exception {
        var service = new ConfigurationService(new FileConfigurationStore(directory));
        assertThrows(IllegalArgumentException.class, service::reload);
        assertNull(service.current());
    }

    @Test void malformedFileFailsClosed() throws Exception {
        var file = directory.resolve("spiffing.xml");
        Files.writeString(file, "<broken>");
        var service = new ConfigurationService(new FileConfigurationStore(file));
        assertThrows(IllegalArgumentException.class, service::reload);
        assertNull(service.current());
    }
}
