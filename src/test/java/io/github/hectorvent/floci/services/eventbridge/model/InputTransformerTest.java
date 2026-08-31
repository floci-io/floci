package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InputTransformerTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void fromJson_parsesPathsMapAndTemplate() throws Exception {
        var node = M.readTree("{\"InputPathsMap\":{\"e\":\"$.detail.eventName\"},"
                + "\"InputTemplate\":\"{\\\"e\\\":<e>}\"}");
        InputTransformer t = InputTransformer.fromJson(node);
        assertEquals("$.detail.eventName", t.getInputPathsMap().get("e"));
        assertEquals("{\"e\":<e>}", t.getInputTemplate());
    }

    @Test
    void fromJson_missingOrNonObject_returnsNull() throws Exception {
        assertNull(InputTransformer.fromJson(null));
        assertNull(InputTransformer.fromJson(M.readTree("{}").path("InputTransformer")));
        assertNull(InputTransformer.fromJson(M.readTree("\"not-an-object\"")));
    }
}
