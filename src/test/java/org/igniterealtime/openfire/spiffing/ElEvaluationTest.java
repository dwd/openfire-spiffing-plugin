package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import javax.el.ELProcessor;

import static org.junit.jupiter.api.Assertions.*;

public class ElEvaluationTest {
    public record Sample(String name, String displayMarking) {}

    @Test
    public void testElExpressions() {
        ELProcessor el = new ELProcessor();
        Sample sample = new Sample("test", "MARKING");
        el.setVariable("sample", "sample");
        el.defineBean("sample", sample);

        assertEquals("MARKING", el.eval("sample.displayMarking()"));
        assertEquals(false, el.eval("empty sample.displayMarking()"));
        assertEquals(true, el.eval("not empty sample.displayMarking()"));

        el.setValue("editingPolicy", null);
        el.setValue("format", "XML");
        Object res = el.eval("empty editingPolicy ? (format eq 'XML' ? 'selected' : '') : (format eq editingPolicy.clearanceFormat() ? 'selected' : '')");
        assertEquals("selected", res);
    }
}
