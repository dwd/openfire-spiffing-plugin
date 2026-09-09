package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationServiceTest {
    static final class Store implements ConfigurationService.Store {
        String value;
        int writes;
        boolean fail;
        public String read() { return value; }
        public void write(String value) {
            if (fail) throw new IllegalStateException("storage unavailable");
            this.value = value;
            writes++;
        }
    }

    @Test void settingsRoundTripEscapedXmlAndUnicode() {
        Settings s = Fixtures.settings();
        var input = new Settings(s.policy() + "<!-- <& café -->", s.clearance(), s.clearanceFormat(), s.defaultLabel(), s.labelFormat(), s.outputFormat());
        assertEquals(input, Settings.fromXml(input.toXml()));
    }

    @Test void savesAndLoadsValidatedConfigurationAsOneProperty() {
        var store = new Store();
        var service = new ConfigurationService(store);
        service.reload();
        assertNull(service.current());
        service.save(Fixtures.settings());
        assertEquals(1, store.writes);
        assertEquals(Fixtures.settings(), service.current().settings());
        var restored = new ConfigurationService(store);
        restored.reload();
        assertEquals(service.current().defaultEnvelope().asXML(), restored.current().defaultEnvelope().asXML());
    }

    @Test void invalidUpdatePreservesSavedAndActiveConfiguration() {
        var store = new Store();
        var service = new ConfigurationService(store);
        service.save(Fixtures.settings());
        var before = service.current();
        String persisted = store.value;
        var s = Fixtures.settings();
        assertThrows(IllegalArgumentException.class, () -> service.save(new Settings(s.policy(), s.clearance(), s.clearanceFormat(),
            Fixtures.read("food-label-water"), s.labelFormat(), s.outputFormat())));
        assertSame(before, service.current());
        assertEquals(persisted, store.value);
        assertEquals(1, store.writes);
    }

    @Test void persistenceFailureDoesNotPublishCandidate() {
        var store = new Store();
        var service = new ConfigurationService(store);
        service.save(Fixtures.settings());
        var before = service.current();
        store.fail = true;
        assertThrows(IllegalStateException.class, () -> service.save(Fixtures.settings(LabelFormat.XML)));
        assertSame(before, service.current());
    }

    @Test void invalidOrDeletedPersistedConfigurationFailsClosedAndCanRecover() {
        var store = new Store();
        var service = new ConfigurationService(store);
        service.save(Fixtures.settings());
        store.value = "broken";
        assertThrows(IllegalArgumentException.class, service::reload);
        assertNull(service.current());
        service.save(Fixtures.settings());
        assertNotNull(service.current());
        store.value = null;
        service.reload();
        assertNull(service.current());
    }

    @ParameterizedTest @ValueSource(strings={"<x/>", "<spiffing-settings version='2'/>",
        "<spiffing-settings version='1'><policy>a</policy><policy>b</policy><default-label/></spiffing-settings>",
        "<spiffing-settings version='1'><policy>a</policy><clearance format='BAD'>b</clearance><default-label/></spiffing-settings>"})
    void rejectsCorruptedOrUnknownSettingsSchema(String xml) {
        assertThrows(RuntimeException.class, () -> Settings.fromXml(xml));
    }

    @Test void rejectsBlankAndOversizedSettings() {
        var s = Fixtures.settings();
        assertThrows(IllegalArgumentException.class, () -> new Settings(" ", s.clearance(), s.clearanceFormat(), s.defaultLabel(), s.labelFormat(), s.outputFormat()));
        assertThrows(IllegalArgumentException.class, () -> new Settings("x".repeat(SecureXml.MAX_DOCUMENT + 1), s.clearance(), s.clearanceFormat(), s.defaultLabel(), s.labelFormat(), s.outputFormat()));
        assertThrows(IllegalArgumentException.class, () -> new Settings(s.policy(), s.clearance(), s.clearanceFormat(), "x".repeat(SecureXml.MAX_LABEL + 1), s.labelFormat(), s.outputFormat()));
    }

    @Test void readersSeePreviousCompleteSnapshotUntilSaveFinishes() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new ConfigurationService.Store() {
            public String read() { return Fixtures.settings().toXml(); }
            public void write(String value) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                } catch (InterruptedException e) { throw new AssertionError(e); }
            }
        };
        var service = new ConfigurationService(store);
        service.reload();
        var before = service.current();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var saved = executor.submit(() -> service.save(Fixtures.settings(LabelFormat.XML)));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertSame(before, service.current());
                assertDoesNotThrow(() -> before.check(before.defaultEnvelope()));
            } finally { release.countDown(); }
            saved.get(5, TimeUnit.SECONDS);
            assertEquals(LabelFormat.XML, service.current().settings().outputFormat());
        }
    }
}
