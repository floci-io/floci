package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
                                        "kind": "Kind", "quoted": "Quoted", "percent": "Percent",
                                        "double": "Double", "wide": "Wide"}},
               "Name": {"type": "string", "min": 2, "max": 5, "pattern": "[a-z]+"},
               "Quoted": {"type": "string", "pattern": "[a-z]+", "quotedPattern": "[a-z]*"},
               "Items": {"type": "list", "max": 2, "member": "Item"},
               "Item": {"type": "structure", "required": ["id"], "members": {"id": "Name"}},
               "Count": {"type": "integer", "min": 1, "max": 10},
               "Percent": {"type": "float", "min": 0, "max": 100},
               "Double": {"type": "double", "min": 0, "max": 100},
               "Wide": {"type": "long", "min": 9007199254740993, "max": 9007199254740995},
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
                public AwsException bounds(String path, String type, Long min, Long max, Number size) {
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
    void individualShapeAndListSizeKeepTheModeledConstraints() {
        assertEquals("enum Kind [A, B]", assertThrows(AwsException.class,
                () -> CHECK.checkShape("Kind", json("\"C\""))).getMessage());
        assertDoesNotThrow(() -> CHECK.checkShape("Kind", json("\"A\"")));
        assertEquals("bounds Put.items list null..2 3", assertThrows(AwsException.class,
                () -> CHECK.checkListSize("Put", "items", 3)).getMessage());
        assertDoesNotThrow(() -> CHECK.checkListSize("Put", "items", 2));
    }

    @Test
    void aStringIsMeasuredInCodePoints() {
        // One emoji is two UTF-16 units but one code point, so it is under the minimum of 2.
        assertEquals("bounds Put.name string 2..5 1", violation("{\"name\":\"\\uD83D\\uDE00\"}"));
    }

    @Test
    void fractionalBoundsAreNotTruncatedAndLongBoundsStayExact() {
        assertDoesNotThrow(() -> CHECK.check("Put", json("{\"name\":\"ab\",\"percent\":99.5,\"double\":0.5}")));
        assertEquals("bounds Put.percent float 0..100 100.1", violation("{\"name\":\"ab\",\"percent\":100.1}"));
        assertEquals("bounds Put.percent float 0..100 -0.1", violation("{\"name\":\"ab\",\"percent\":-0.1}"));
        assertEquals("type Put.percent float", violation("{\"name\":\"ab\",\"percent\":\"50\"}"));
        assertEquals("bounds Put.double double 0..100 100.1", violation("{\"name\":\"ab\",\"double\":100.1}"));
        assertEquals("type Put.count integer", violation("{\"name\":\"ab\",\"count\":2147483648}"));
        assertDoesNotThrow(() -> CHECK.check("Put", json("{\"name\":\"ab\",\"wide\":9007199254740993}")));
        assertEquals("bounds Put.wide long 9007199254740993..9007199254740995 9007199254740992",
                violation("{\"name\":\"ab\",\"wide\":9007199254740992}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"InvalidRequestException", "InvalidParameterException", "ValidationException"})
    void standardMessagesPreserveTheWireFormatForEachServiceCode(String errorCode) {
        RequestConstraintCheck check = RequestConstraintCheck.of(json(MODEL),
                RequestConstraintCheck.standardMessages(errorCode));
        String[][] cases = {
                {"{}", "Invalid parameter at 'name' failed to satisfy constraint: 'Member must not be null'"},
                {"{\"name\":7}", "Invalid parameter at 'name' failed to satisfy constraint: 'Member must be a string'"},
                {"{\"name\":\"a\"}", "Invalid parameter at 'name' failed to satisfy constraint: "
                        + "'Member must have length greater than or equal to 2'"},
                {"{\"name\":\"abcdef\"}", "Invalid parameter at 'name' failed to satisfy constraint: "
                        + "'Member must have length less than or equal to 5'"},
                {"{\"name\":\"AB\"}", "Invalid parameter at 'name' failed to satisfy constraint: "
                        + "'must satisfy regular expression '[a-z]+''"},
                {"{\"name\":\"ab\",\"quoted\":\"AB\"}", "Invalid parameter at 'quoted' failed to satisfy constraint: "
                        + "'must satisfy regular expression '[a-z]*''"},
                {"{\"name\":\"ab\",\"count\":0}", "Invalid parameter at 'count' failed to satisfy constraint: "
                        + "'Member must have value greater than or equal to 1'"},
                {"{\"name\":\"ab\",\"count\":11}", "Invalid parameter at 'count' failed to satisfy constraint: "
                        + "'Member must have value less than or equal to 10'"},
                {"{\"name\":\"ab\",\"percent\":-0.1}", "Invalid parameter at 'percent' failed to satisfy constraint: "
                        + "'Member must have value greater than or equal to 0'"},
                {"{\"name\":\"ab\",\"percent\":100.1}", "Invalid parameter at 'percent' failed to satisfy constraint: "
                        + "'Member must have value less than or equal to 100'"},
                {"{\"name\":\"ab\",\"kind\":\"C\"}", "Invalid parameter at 'kind' failed to satisfy constraint: "
                        + "'Member must satisfy enum value set: [A, B]'"},
                {"{\"name\":\"ab\",\"items\":[{},{},{}]}", "Invalid parameter at 'items' failed to satisfy constraint: "
                        + "'Member must have length less than or equal to 2'"},
                {"{\"name\":\"ab\",\"items\":[{}]}", "Invalid parameter at 'items.id' failed to satisfy constraint: "
                        + "'Member must not be null'"},
                {"{\"name\":\"ab\",\"items\":[{\"id\":\"A1\"}]}", "Invalid parameter at 'items.id' failed to satisfy constraint: "
                        + "'must satisfy regular expression '[a-z]+''"},
                {"{\"name\":\"ab\",\"labels\":{\"K1\":\"ab\"}}", "Invalid parameter at 'labels.key' failed to satisfy constraint: "
                        + "'must satisfy regular expression '[a-z]+''"},
                {"[]", "Invalid parameter at '' failed to satisfy constraint: 'Member must be a structure'"}
        };
        for (String[] example : cases) {
            AwsException failure = assertThrows(AwsException.class, () -> check.check("Put", json(example[0])));
            assertEquals(errorCode, failure.getErrorCode(), example[0]);
            assertEquals(400, failure.getHttpStatus(), example[0]);
            assertEquals(example[1], failure.getMessage(), example[0]);
        }
    }
}
