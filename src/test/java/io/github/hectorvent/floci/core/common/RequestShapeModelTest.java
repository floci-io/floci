package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestShapeModel.Shape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestShapeModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parseReadsOperationsAndEachShapeKind() throws Exception {
        RequestShapeModel model = RequestShapeModel.parse(MAPPER.readTree("""
            {
              "_source": {"generator": "test"},
              "operations": {"Send": "SendRequest"},
              "shapes": {
                "SendRequest": {"type": "structure", "members": {"To": "Addresses", "Tags": "TagMap", "From": "Address"}},
                "Addresses": {"type": "list", "member": "Address"},
                "TagMap": {"type": "map", "value": "Address"},
                "Address": {"type": "string"}
              }
            }
            """));

        assertEquals(Map.of("Send", "SendRequest"), model.operations());
        Shape request = model.shapes().get("SendRequest");
        assertEquals("structure", request.type());
        assertEquals(List.of("To", "Tags", "From"), List.copyOf(request.members().keySet()));
        assertEquals("Address", model.shapes().get("Addresses").element());
        assertEquals("Address", model.shapes().get("TagMap").element());
        Shape scalar = model.shapes().get("Address");
        assertEquals("string", scalar.type());
        assertTrue(scalar.members().isEmpty());
        assertNull(scalar.element());
    }

    @Test
    void loadReadsAClasspathResource() {
        RequestShapeModel model = RequestShapeModel.load("fixtures/core/request-shapes.json", "Test request shapes");

        assertEquals("SendRequest", model.operations().get("Send"));
        assertEquals("Address", model.shapes().get("Addresses").element());
    }

    @Test
    void parsedModelCannotBeChanged() {
        RequestShapeModel model = RequestShapeModel.load("fixtures/core/request-shapes.json", "Test request shapes");

        assertThrows(UnsupportedOperationException.class, () -> model.operations().put("Other", "SendRequest"));
        assertThrows(UnsupportedOperationException.class, () -> model.shapes().remove("Address"));
        assertThrows(UnsupportedOperationException.class,
                () -> model.shapes().get("SendRequest").members().put("Cc", "Addresses"));
    }

    @Test
    void loadFailsForAMissingResource() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RequestShapeModel.load("aws/no-such-shapes.json", "Test request shapes"));

        assertEquals("Test request shapes resource not found: aws/no-such-shapes.json", error.getMessage());
    }
}
