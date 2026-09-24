package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test void singlePolicyConvenienceConstructorWrapsItInAOneElementList() {
        var s = Fixtures.settings();
        assertEquals(List.of(s.policy()), s.policies());
        assertEquals(1, s.policies().size());
    }

    @Test void policyAccessorReturnsTheFirstPolicy() {
        var s = Fixtures.settingsWithSecondPolicy();
        assertEquals(2, s.policies().size());
        assertEquals(s.policies().get(0), s.policy());
        assertNotEquals(s.policies().get(0), s.policies().get(1));
    }

    @Test void rejectsAnEmptyOrNullPolicyList() {
        var s = Fixtures.settings();
        assertThrows(IllegalArgumentException.class, () -> new Settings(List.of(), s.clearance(), s.clearanceFormat(),
            s.defaultLabel(), s.labelFormat(), s.outputFormat()));
        assertThrows(IllegalArgumentException.class, () -> new Settings((List<String>) null, s.clearance(), s.clearanceFormat(),
            s.defaultLabel(), s.labelFormat(), s.outputFormat()));
    }

    @Test void rejectsTooManyPolicies() {
        var s = Fixtures.settings();
        var tooMany = new ArrayList<String>();
        for (int i = 0; i <= Settings.MAX_POLICIES; i++) tooMany.add(s.policy());
        assertThrows(IllegalArgumentException.class, () -> new Settings(tooMany, s.clearance(), s.clearanceFormat(),
            s.defaultLabel(), s.labelFormat(), s.outputFormat()));
    }

    @Test void rejectsABlankOrOversizedPolicyInTheList() {
        var s = Fixtures.settings();
        assertThrows(IllegalArgumentException.class, () -> new Settings(List.of(s.policy(), " "), s.clearance(), s.clearanceFormat(),
            s.defaultLabel(), s.labelFormat(), s.outputFormat()));
        assertThrows(IllegalArgumentException.class, () -> new Settings(List.of(s.policy(), "x".repeat(SecureXml.MAX_DOCUMENT + 1)),
            s.clearance(), s.clearanceFormat(), s.defaultLabel(), s.labelFormat(), s.outputFormat()));
    }

    @Test void policyListIsDefensivelyCopiedAndImmutable() {
        var mutable = new ArrayList<String>();
        var s = Fixtures.settings();
        mutable.add(s.policy());
        var settings = new Settings(mutable, s.clearance(), s.clearanceFormat(), s.defaultLabel(), s.labelFormat(), s.outputFormat());
        mutable.add("changed after construction");
        assertEquals(1, settings.policies().size());
        assertThrows(UnsupportedOperationException.class, () -> settings.policies().add("x"));
    }
}
