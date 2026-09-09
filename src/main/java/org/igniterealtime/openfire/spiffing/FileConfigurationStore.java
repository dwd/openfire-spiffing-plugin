package org.igniterealtime.openfire.spiffing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** One atomic file replacement: a failed save must not publish a half-written configuration. */
final class FileConfigurationStore implements ConfigurationService.Store {
    private final Path file;

    FileConfigurationStore(Path file) { this.file = file; }

    @Override public String read() {
        try {
            if (!Files.exists(file)) return null;
            // Limit allocation before decoding. Settings.fromXml also limits character count.
            try (var input = Files.newInputStream(file)) {
                int limit = SecureXml.MAX_DOCUMENT * 24;
                byte[] data = input.readNBytes(limit + 1);
                if (data.length > limit) throw new IllegalArgumentException("Stored settings exceed size limit.");
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read Spiffing settings.", e);
        }
    }

    @Override public void write(String value) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(file.getParent(), ".spiffing-", ".xml");
            Files.writeString(temporary, value, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot atomically save Spiffing settings.", e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Never mask the save result. */ }
            }
        }
    }
}
