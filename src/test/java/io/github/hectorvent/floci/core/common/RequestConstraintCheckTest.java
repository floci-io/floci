package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Every constraint kind the walker reads, with the arguments it hands a service's messages. */
class RequestConstraintCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
            {"operations": {"Put": "PutInput"},
             "shapes": {
               "PutInput": {"type": "structure", "required": ["name"],
                            "members": {"name": "Name", "items": "Items", "count": "Count", "labels": "Labels",
                                        "kind": "Kind", "quoted": "Quoted"}},
               "Name": {"type": "string", "min": 2, "max": 5, "pattern": "[a-z]+"},
               "Quoted": {"type": "string", "pattern": "[a-z]+", "quotedPattern": "[a-z]*"},
               "Items": {"type": "list", "max": 2, "member": "Item"},
               "Item": {"type": "structure", "required": ["id"], "members": {"id": "Name"}},
               "Count": {"type": "integer", "min": 1, "max": 10},
               "Labels": {"type": "map", "key": "Name", "value": "Name"},
               "Kind": {"type": "string", "enum": ["A", "B"]}
             }}
            """;

    /** Records each violation as one line naming its kind and arguments. */
    private static final RequestConstraintCheck CHECK = RequestConstraintCheck.of(json(MODEL),
            new RequestConstraintCheck.Messages() {
                @Override
                public AwsException type(String path, String type) {
                    return fail("type " + path + " " + type);
                }

                @Override
                public AwsException bounds(String path, String type, Long min, Long max, long size) {
                    return fail("bounds " + path + " " + type + " " + min + ".." + max + " " + size);
                }

                @Override
                public AwsException pattern(String path, String pattern) {
                    return fail("pattern " + path + " " + pattern);
                }

                @Override
                public AwsException enumValue(String path, List<String> values) {
                    return fail("enum " + path + " " + values);
                }

                @Override
                public AwsException required(String path) {
                    return fail("required " + path);
                }

                private AwsException fail(String message) {
                    return new AwsException("Test", message, 400);
                }
            });

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private static String violation(String body) {
        return assertThrows(AwsException.class, () -> CHECK.check("Put", json(body)), body).getMessage();
    }

    @Test
    void eachConstraintKindIsReportedWithItsPath() {
        assertEquals("required Put.name", violation("{}"));
        assertEquals("type Put.name string", violation("{\"name\":7}"));
        assertEquals("bounds Put.name string 2..5 1", violation("{\"name\":\"a\"}"));
        assertEquals("bounds Put.name string 2..5 6", violation("{\"name\":\"abcdef\"}"));
        assertEquals("pattern Put.name [a-z]+", violation("{\"name\":\"AB\"}"));
        // A shape recording the pattern AWS quotes for its member hands that text over instead.
        assertEquals("pattern Put.quoted [a-z]*", violation("{\"name\":\"ab\",\"quoted\":\"AB\"}"));
        assertEquals("bounds Put.count integer 1..10 11", violation("{\"name\":\"ab\",\"count\":11}"));
        assertEquals("type Put.count integer", violation("{\"name\":\"ab\",\"count\":1.5}"));
        assertEquals("enum Put.kind [A, B]", violation("{\"name\":\"ab\",\"kind\":\"C\"}"));
        assertEquals("bounds Put.items list null..2 3",
                violation("{\"name\":\"ab\",\"items\":[{\"id\":\"ab\"},{\"id\":\"ab\"},{\"id\":\"ab\"}]}"));
        assertEquals("required Put.items[].id", violation("{\"name\":\"ab\",\"items\":[{}]}"));
        assertEquals("pattern Put.items[].id [a-z]+", violation("{\"name\":\"ab\",\"items\":[{\"id\":\"A1\"}]}"));
        assertEquals("pattern Put.labels.key [a-z]+", violation("{\"name\":\"ab\",\"labels\":{\"K1\":\"ab\"}}"));
        assertEquals("bounds Put.labels.ab string 2..5 1", violation("{\"name\":\"ab\",\"labels\":{\"ab\":\"x\"}}"));
        assertEquals("type Put structure", violation("[]"));
    }

    @Test
    void aRequestWithinTheConstraintsPasses() {
        assertDoesNotThrow(() -> CHECK.check("Put", json("{\"name\":\"abc\",\"items\":[{\"id\":\"ab\"}],"
                + "\"count\":10,\"labels\":{\"ab\":\"cd\"},\"kind\":\"B\",\"unmodeled\":true,\"items2\":null}")));
        // A null member is treated as absent, as the walker found it before it moved here.
        assertDoesNotThrow(() -> CHECK.check("Put", json("{\"name\":\"abc\",\"count\":null}")));
    }

    @Test
    void aStringIsMeasuredInCodePoints() {
        // One emoji is two UTF-16 units but one code point, so it is under the minimum of 2.
        assertEquals("bounds Put.name string 2..5 1", violation("{\"name\":\"\\uD83D\\uDE00\"}"));
    }
}
