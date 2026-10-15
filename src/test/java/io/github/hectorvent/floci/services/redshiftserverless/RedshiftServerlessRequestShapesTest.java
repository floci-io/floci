package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftServerlessRequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static AwsException rejected(String action, String json) {
        AwsException e = assertThrows(AwsException.class,
                () -> RedshiftServerlessRequestShapes.check(action, parse(json)));
        assertEquals("ValidationException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
        return e;
    }

    @Test
    void aMissingRequiredMemberIsRejected() {
        assertTrue(rejected("GetTrack", "{}").getMessage().contains("trackName is required"));
        assertTrue(rejected("GetTrack", "{\"trackName\":null}").getMessage().contains("trackName is required"));
    }

    @Test
    void aRequiredMemberInsideAListElementIsRejected() {
        AwsException e = rejected("TagResource", "{\"resourceArn\":\"arn:aws:redshift-serverless:us-east-1:"
                + "000000000000:namespace/x\",\"tags\":[{\"key\":\"k\"}]}");

        assertTrue(e.getMessage().contains("tags[0].value is required"), e.getMessage());
    }

    @Test
    void stringPatternLengthAndEnumFollowTheModel() {
        assertTrue(rejected("GetTrack", "{\"trackName\":\"has space\"}").getMessage().contains("pattern"));
        assertTrue(rejected("GetTrack", "{\"trackName\":\"\"}").getMessage().contains("at least 1"));
        assertTrue(rejected("GetTrack", "{\"trackName\":\"" + "a".repeat(257) + "\"}")
                .getMessage().contains("at most 256"));
        assertTrue(rejected("CreateNamespace", "{\"namespaceName\":\"ns-ok\",\"logExports\":[\"nope\"]}")
                .getMessage().contains("logExports[0] must be one of"));
        assertDoesNotThrow(() -> RedshiftServerlessRequestShapes.check("GetTrack", parse("{\"trackName\":\"current\"}")));
    }

    @Test
    void numberRangeFollowsTheModel() {
        AwsException e = rejected("CreateWorkgroup", "{\"workgroupName\":\"wg-ok\",\"namespaceName\":\"ns-ok\","
                + "\"pricePerformanceTarget\":{\"status\":\"ENABLED\",\"level\":101}}");

        assertTrue(e.getMessage().contains("pricePerformanceTarget.level"), e.getMessage());
        assertTrue(e.getMessage().contains("at most 100"), e.getMessage());
    }

    @Test
    void paginationMembersAreLeftToTheServices() {
        assertDoesNotThrow(() -> RedshiftServerlessRequestShapes.check("ListNamespaces", parse("{\"maxResults\":0}")));
        assertDoesNotThrow(() -> RedshiftServerlessRequestShapes.check("ListNamespaces", parse("{\"nextToken\":\"x\"}")));
    }

    @Test
    void aPatternMustMatchTheWholeValue() {
        assertDoesNotThrow(() -> RedshiftServerlessRequestShapes.check("ListWorkgroups",
                parse("{\"ownerAccount\":\"123456789012\"}")));
        assertTrue(rejected("ListWorkgroups", "{\"ownerAccount\":\"x123456789012y\"}")
                .getMessage().contains("ownerAccount"));
    }

    @Test
    void aLengthCountsCodePointsNotUtf16Units() {
        String arn = "arn:aws:redshift-serverless:us-east-1:000000000000:namespace/x";
        String emoji200 = "\uD83D\uDE00".repeat(200);
        String emoji257 = "\uD83D\uDE00".repeat(257);

        assertDoesNotThrow(() -> RedshiftServerlessRequestShapes.check("TagResource", parse(
                "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"key\":\"k\",\"value\":\"" + emoji200 + "\"}]}")));
        assertTrue(rejected("TagResource", "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"key\":\"k\",\"value\":\""
                + emoji257 + "\"}]}").getMessage().contains("at most 256"));
    }

    @Test
    void aMemberOfTheWrongTypeIsLeftForTheHandlerToReport() {
        assertDoesNotThrow(() -> RedshiftServerlessRequestShapes.check("GetTrack", parse("{\"trackName\":7}")));
    }

    @Test
    void unknownActionsAndUnknownMembersAreIgnored() {
        assertDoesNotThrow(() -> RedshiftServerlessRequestShapes.check("NoSuchAction", parse("{}")));
        assertDoesNotThrow(() -> RedshiftServerlessRequestShapes.check("ListNamespaces", parse("{\"extra\":1}")));
        assertDoesNotThrow(() -> RedshiftServerlessRequestShapes.check("ListNamespaces", null));
    }
}
