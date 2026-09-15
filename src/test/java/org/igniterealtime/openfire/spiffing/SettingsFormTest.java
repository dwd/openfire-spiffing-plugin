package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsFormTest {
    private Map<String, String> fields() {
        var s = Fixtures.settings();
        return new HashMap<>(Map.of("policy", s.policy(), "clearance", s.clearance(), "label", s.defaultLabel(),
            "clearanceFormat", "XML", "labelFormat", "XML", "outputFormat", "ESS"));
    }

    @ParameterizedTest @CsvSource({"GET,token,token", "POST,,token", "POST,token,", "POST,token,wrong", "POST,'',''"})
    void csrfOrMethodFailureCannotSave(String method, String cookie, String token) {
        assertThrows(IllegalArgumentException.class, () -> SettingsForm.save(method, cookie, token, fields(), s -> fail("must not save")));
    }

    @Test void validSubmissionIsSaved() {
        List<Settings> saved = new ArrayList<>();
        SettingsForm.save("POST", "token", "token", fields(), saved::add);
        // The "enforcementMode" field is absent, as in a form saved before this switch existed; it defaults to "warn".
        assertEquals(List.of(Fixtures.settings(LabelFormat.ESS, EnforcementMode.WARN)), saved);
    }

    @Test void explicitEnforcementModeIsSaved() {
        List<Settings> saved = new ArrayList<>();
        var withMode = fields();
        withMode.put("enforcementMode", "ENFORCE");
        SettingsForm.save("POST", "token", "token", withMode, saved::add);
        assertEquals(List.of(Fixtures.settings(LabelFormat.ESS, EnforcementMode.ENFORCE)), saved);
    }

    @Test void missingOrInvalidFieldsCannotSave() {
        for (String key : fields().keySet()) {
            var missing = fields();
            missing.remove(key);
            assertThrows(IllegalArgumentException.class, () -> SettingsForm.save("POST", "token", "token", missing, s -> fail("must not save")));
        }
        var invalid = fields();
        invalid.put("outputFormat", "ANY");
        assertThrows(IllegalArgumentException.class, () -> SettingsForm.save("POST", "token", "token", invalid, s -> fail("must not save")));
        var invalidMode = fields();
        invalidMode.put("enforcementMode", "ANY");
        assertThrows(IllegalArgumentException.class, () -> SettingsForm.save("POST", "token", "token", invalidMode, s -> fail("must not save")));
    }

    @Test void formUsesPolicyValidationBeforePersistence() {
        var store = new ConfigurationServiceTest.Store();
        var service = new ConfigurationService(store);
        var denied = fields();
        denied.put("label", Fixtures.read("food-label-water"));
        assertThrows(IllegalArgumentException.class, () -> SettingsForm.save("POST", "token", "token", denied, service::save));
        assertEquals(0, store.writes);
        assertNull(service.current());
    }
}
