package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CreateUsagePlan bodies as measured with raw requests against API Gateway in eu-west-1. */
class ApiGatewayRequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STRUCTURE = "Start of structure or map found where not expected.";
    private static final String LIST = "Start of list found where not expected";
    private static final String STRING_TO_INTEGER = "class java.lang.String can not be converted to an Integer";
    private static final String BOOLEAN_TO_INTEGER = "class java.lang.Boolean can not be converted to an Integer";
    private static final String STRING_TO_DOUBLE = "class java.lang.String is not an expected Json type";
    private static final String BOOLEAN_TO_DOUBLE = "class java.lang.Boolean is not an expected Json type";
    private static final String NUMBER_TO_STRING =
            "class com.amazon.coral.value.json.numbers.TruncatingBigNumber can not be converted to an String";
    private static final String BOOLEAN_TO_STRING = "class java.lang.Boolean can not be converted to an String";
    private static final String STAGE = "{\"apiId\":\"a\",\"stage\":\"v1\",\"throttle\":{\"/pets/GET\":%s}}";

    @Test
    void anIntegerMemberTakesAnyJsonNumber() {
        assertAccepted("{\"quota\":{\"limit\":1.5,\"offset\":0,\"period\":\"DAY\"}}");
        assertAccepted("{\"quota\":{\"limit\":1e2,\"offset\":1.9,\"period\":\"WEEK\"}}");
        assertAccepted("{\"quota\":{\"limit\":3000000000,\"period\":\"DAY\"}}");
        assertAccepted("{\"throttle\":{\"burstLimit\":1.5}}");
        assertAccepted("{\"throttle\":{\"burstLimit\":3000000000}}");
        assertAccepted("{\"throttle\":{\"burstLimit\":-1e2}}");
        assertAccepted("{\"apiStages\":[" + STAGE.formatted("{\"burstLimit\":1.5}") + "]}");
    }

    @Test
    void anIntegerMemberRefusesTextEvenWhenItIsNumeric() {
        for (String text : new String[] {"\"100\"", "\"\"", "\"1.5\""}) {
            assertRejected("{\"quota\":{\"limit\":" + text + ",\"period\":\"DAY\"}}", STRING_TO_INTEGER);
            assertRejected("{\"quota\":{\"offset\":" + text + "}}", STRING_TO_INTEGER);
            assertRejected("{\"throttle\":{\"burstLimit\":" + text + "}}", STRING_TO_INTEGER);
            assertRejected("{\"apiStages\":[" + STAGE.formatted("{\"burstLimit\":" + text + "}") + "]}",
                    STRING_TO_INTEGER);
        }
    }

    @Test
    void anIntegerMemberRefusesABoolean() {
        assertRejected("{\"quota\":{\"limit\":true}}", BOOLEAN_TO_INTEGER);
        assertRejected("{\"quota\":{\"offset\":false}}", BOOLEAN_TO_INTEGER);
        assertRejected("{\"throttle\":{\"burstLimit\":true}}", BOOLEAN_TO_INTEGER);
    }

    @Test
    void aDoubleMemberTakesAnyJsonNumberAndNamesWhatElseItGotAsAnUnexpectedType() {
        assertAccepted("{\"throttle\":{\"rateLimit\":5}}");
        assertAccepted("{\"throttle\":{\"rateLimit\":5.5}}");
        assertAccepted("{\"throttle\":{\"rateLimit\":1e1}}");
        for (String text : new String[] {"\"5.5\"", "\"abc\"", "\"1e1\""}) {
            assertRejected("{\"throttle\":{\"rateLimit\":" + text + "}}", STRING_TO_DOUBLE);
            assertRejected("{\"apiStages\":[" + STAGE.formatted("{\"rateLimit\":" + text + "}") + "]}",
                    STRING_TO_DOUBLE);
        }
        assertRejected("{\"throttle\":{\"rateLimit\":true}}", BOOLEAN_TO_DOUBLE);
        assertRejected("{\"throttle\":{\"rateLimit\":false}}", BOOLEAN_TO_DOUBLE);
    }

    @Test
    void aStringMemberRefusesANumberOrABoolean() {
        for (String number : new String[] {"5", "5.5", "1e2"}) {
            assertRejected("{\"name\":" + number + "}", NUMBER_TO_STRING);
            assertRejected("{\"description\":" + number + "}", NUMBER_TO_STRING);
            assertRejected("{\"quota\":{\"period\":" + number + "}}", NUMBER_TO_STRING);
            assertRejected("{\"apiStages\":[{\"apiId\":" + number + ",\"stage\":\"v1\"}]}", NUMBER_TO_STRING);
            assertRejected("{\"apiStages\":[{\"apiId\":\"a\",\"stage\":" + number + "}]}", NUMBER_TO_STRING);
            assertRejected("{\"tags\":{\"k\":" + number + "}}", NUMBER_TO_STRING);
        }
        assertRejected("{\"name\":true}", BOOLEAN_TO_STRING);
        assertRejected("{\"description\":false}", BOOLEAN_TO_STRING);
        assertRejected("{\"quota\":{\"period\":true}}", BOOLEAN_TO_STRING);
        assertRejected("{\"apiStages\":[{\"apiId\":\"a\",\"stage\":true}]}", BOOLEAN_TO_STRING);
        assertRejected("{\"tags\":{\"k\":false}}", BOOLEAN_TO_STRING);
    }

    @Test
    void aStructureTakesOnlyAnObjectAsAMemberOrAnElement() {
        assertRejected("{\"quota\":\"invalid\"}", "Expected null");
        assertRejected("{\"quota\":5}", "Expected null");
        assertRejected("{\"throttle\":5}", "Expected null");
        assertRejected("{\"throttle\":true}", "Expected null");
        assertRejected("{\"apiStages\":[\"x\"]}", "Expected null");
        assertRejected("{\"apiStages\":[" + STAGE.formatted("5") + "]}", "Expected null");
        assertRejected("{\"apiStages\":[" + STAGE.formatted("\"x\"") + "]}", "Expected null");
    }

    @Test
    void aListWhereNoneIsExpectedIsRefused() {
        assertRejected("{\"name\":[]}", LIST);
        assertRejected("{\"quota\":[]}", LIST);
        assertRejected("{\"throttle\":[]}", LIST);
        assertRejected("{\"throttle\":{\"burstLimit\":[]}}", LIST);
        assertRejected("{\"throttle\":{\"rateLimit\":[1]}}", LIST);
        assertRejected("{\"quota\":{\"period\":[\"DAY\"]}}", LIST);
        assertRejected("{\"tags\":[]}", LIST);
        assertRejected("{\"tags\":{\"k\":[]}}", LIST);
        assertRejected("{\"apiStages\":[[]]}", LIST);
        assertRejected("{\"apiStages\":[{\"apiId\":[],\"stage\":\"v1\"}]}", LIST);
        assertRejected("{\"apiStages\":[{\"apiId\":\"a\",\"stage\":\"v1\",\"throttle\":[]}]}", LIST);
        assertRejected("{\"apiStages\":[" + STAGE.formatted("[]") + "]}", LIST);
    }

    @Test
    void anObjectWhereNoneIsExpectedIsRefused() {
        assertRejected("{\"name\":{}}", STRUCTURE);
        assertRejected("{\"description\":{\"a\":1}}", STRUCTURE);
        assertRejected("{\"apiStages\":{}}", STRUCTURE);
        assertRejected("{\"throttle\":{\"burstLimit\":{}}}", STRUCTURE);
        assertRejected("{\"throttle\":{\"rateLimit\":{}}}", STRUCTURE);
        assertRejected("{\"quota\":{\"period\":{}}}", STRUCTURE);
        assertRejected("{\"tags\":{\"k\":{}}}", STRUCTURE);
        assertRejected("{\"apiStages\":[{\"apiId\":{},\"stage\":\"v1\"}]}", STRUCTURE);
    }

    @Test
    void aListOrAMapMemberRefusesAScalar() {
        assertRejected("{\"apiStages\":\"x\"}", "Expected list or null");
        assertRejected("{\"apiStages\":5}", "Expected list or null");
        assertRejected("{\"apiStages\":true}", "Expected list or null");
        assertRejected("{\"tags\":\"x\"}", "Expected map or null");
        assertRejected("{\"tags\":5}", "Expected map or null");
        assertRejected("{\"apiStages\":[{\"apiId\":\"a\",\"stage\":\"v1\",\"throttle\":5}]}", "Expected map or null");
        assertRejected("{\"apiStages\":[{\"apiId\":\"a\",\"stage\":\"v1\",\"throttle\":\"x\"}]}",
                "Expected map or null");
    }

    @Test
    void nullsAndUnknownMembersAreLeftToTheOperation() {
        assertAccepted("{\"name\":null,\"quota\":null,\"throttle\":null,\"apiStages\":null,\"tags\":null}");
        assertAccepted("{\"name\":\"p\",\"unknown\":[1,{}],\"productCode\":5,\"quota\":{\"other\":true}}");
        assertAccepted("{\"apiStages\":[null,{\"apiId\":\"a\",\"stage\":\"v1\",\"throttle\":{\"/pets/GET\":null}}]}");
    }

    // Each pair is sent in both body orders; the member first by name is reported either way.
    @Test
    void topLevelMembersAreReadInNameOrder() {
        assertReportedInEitherOrder("\"apiStages\":\"x\"", "\"description\":5", "Expected list or null");
        assertReportedInEitherOrder("\"description\":5", "\"name\":true", NUMBER_TO_STRING);
        assertReportedInEitherOrder("\"name\":true", "\"quota\":\"x\"", BOOLEAN_TO_STRING);
        assertReportedInEitherOrder("\"quota\":\"x\"", "\"tags\":\"x\"", "Expected null");
        assertReportedInEitherOrder("\"tags\":\"x\"", "\"throttle\":[]", "Expected map or null");
        // botocore lists name before apiStages, so this is not the model's order either.
        assertReportedInEitherOrder("\"apiStages\":\"x\"", "\"name\":true", "Expected list or null");
    }

    @Test
    void nestedMembersAreReadInNameOrder() {
        assertRejected("{\"throttle\":{\"burstLimit\":\"x\",\"rateLimit\":\"x\"}}", STRING_TO_INTEGER);
        assertRejected("{\"throttle\":{\"rateLimit\":\"x\",\"burstLimit\":\"x\"}}", STRING_TO_INTEGER);
        assertRejected("{\"apiStages\":[{\"apiId\":true,\"stage\":5}]}", BOOLEAN_TO_STRING);
        assertRejected("{\"apiStages\":[{\"stage\":5,\"apiId\":true}]}", BOOLEAN_TO_STRING);
        assertRejected("{\"quota\":{\"limit\":\"x\",\"period\":5}}", STRING_TO_INTEGER);
        assertRejected("{\"quota\":{\"period\":5,\"limit\":\"x\"}}", STRING_TO_INTEGER);
    }

    @Test
    void mapEntriesAreReadInKeyOrder() {
        assertRejected("{\"tags\":{\"z\":true,\"a\":5}}", NUMBER_TO_STRING);
        assertRejected("{\"tags\":{\"a\":5,\"z\":true}}", NUMBER_TO_STRING);
        String z = "\"/z/GET\":{\"burstLimit\":true}";
        String a = "\"/a/GET\":{\"burstLimit\":\"s\"}";
        assertRejected("{\"apiStages\":[{\"apiId\":\"a\",\"stage\":\"v1\",\"throttle\":{" + z + "," + a + "}}]}",
                STRING_TO_INTEGER);
        assertRejected("{\"apiStages\":[{\"apiId\":\"a\",\"stage\":\"v1\",\"throttle\":{" + a + "," + z + "}}]}",
                STRING_TO_INTEGER);
    }

    @Test
    void anUnknownOperationIsAServerError() {
        AwsException error = assertThrows(AwsException.class,
                () -> ApiGatewayRequestShapes.check("NoSuchOperation", json("{}")));
        assertEquals("InternalFailure", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
    }

    private static void assertReportedInEitherOrder(String first, String second, String message) {
        assertRejected("{" + first + "," + second + "}", message);
        assertRejected("{" + second + "," + first + "}", message);
    }

    private static void assertRejected(String body, String message) {
        AwsException error = assertThrows(AwsException.class,
                () -> ApiGatewayRequestShapes.check("CreateUsagePlan", json(body)), body);
        assertEquals("SerializationException", error.getErrorCode(), body);
        assertEquals(400, error.getHttpStatus(), body);
        assertEquals(message, error.getMessage(), body);
    }

    private static void assertAccepted(String body) {
        assertDoesNotThrow(() -> ApiGatewayRequestShapes.check("CreateUsagePlan", json(body)), body);
    }

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }
}
