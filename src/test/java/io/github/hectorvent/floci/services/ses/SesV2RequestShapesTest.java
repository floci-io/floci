package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SesV2RequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STRUCTURE = "Start of structure or map found where not expected.";
    private static final String LIST = "Start of list found where not expected";

    @Test
    void stringMemberNamesTheJsonTokenItGot() {
        assertRejected("SendEmail", "{\"FromEmailAddress\":123}", "NUMBER_VALUE can not be converted to a String");
        assertRejected("SendEmail", "{\"FromEmailAddress\":true}", "TRUE_VALUE can not be converted to a String");
        assertRejected("SendEmail", "{\"FromEmailAddress\":false}", "FALSE_VALUE can not be converted to a String");
        assertRejected("SendEmail", "{\"FromEmailAddress\":{}}", STRUCTURE);
        assertRejected("SendEmail", "{\"FromEmailAddress\":[]}", LIST);
    }

    @Test
    void blobMemberNamesTheBlobType() {
        assertRejected("SendEmail", "{\"Content\":{\"Raw\":{\"Data\":123}}}", "NUMBER_VALUE can not be converted to a Blob");
        assertRejected("SendEmail", "{\"Content\":{\"Raw\":{\"Data\":true}}}", "TRUE_VALUE can not be converted to a Blob");
        assertRejected("SendEmail", "{\"Content\":{\"Raw\":{\"Data\":{}}}}", STRUCTURE);
    }

    @Test
    void structureMemberTakesOnlyAnObject() {
        assertRejected("SendEmail", "{\"Destination\":\"to@example.com\"}", "Expected null");
        assertRejected("SendEmail", "{\"Destination\":123}", "Expected null");
        assertRejected("SendEmail", "{\"Destination\":[]}", LIST);
        assertRejected("SendEmail", "{\"Content\":{\"Simple\":{\"Subject\":\"s\"}}}", "Expected null");
    }

    @Test
    void listMemberTakesOnlyAnArray() {
        assertRejected("SendEmail", "{\"ReplyToAddresses\":\"r@example.com\"}", "Expected list or null");
        assertRejected("SendEmail", "{\"ReplyToAddresses\":123}", "Expected list or null");
        assertRejected("SendEmail", "{\"ReplyToAddresses\":{}}", STRUCTURE);
        assertRejected("SendEmail", "{\"ReplyToAddresses\":[123]}", "NUMBER_VALUE can not be converted to a String");
        assertRejected("SendBulkEmail", "{\"BulkEmailEntries\":[{\"Destination\":[]}]}", LIST);
    }

    @Test
    void mapMemberReadsLikeAStructure() {
        assertAccepted("ListTenantResources", "{\"Filter\":{\"RESOURCE_TYPE\":\"x\"}}");
        assertRejected("ListTenantResources", "{\"Filter\":[]}", LIST);
        assertRejected("ListTenantResources", "{\"Filter\":\"x\"}", "Expected null");
        assertRejected("ListTenantResources", "{\"Filter\":{\"RESOURCE_TYPE\":1}}",
                "NUMBER_VALUE can not be converted to a String");
    }

    @Test
    void firstBadMemberInBodyOrderIsReported() {
        assertRejected("SendEmail", "{\"Destination\":[],\"FromEmailAddress\":123}", LIST);
        assertRejected("SendEmail", "{\"FromEmailAddress\":123,\"Destination\":[]}",
                "NUMBER_VALUE can not be converted to a String");
    }

    @Test
    void nullsUnknownMembersAndUnprobedTypesAreLeftToTheOperation() {
        assertAccepted("SendEmail", "{\"FromEmailAddress\":null,\"Destination\":null,\"Unknown\":5}");
        assertAccepted("SendEmail", "{\"ReplyToAddresses\":[null]}");
        assertAccepted("PutAccountSendingAttributes", "{\"SendingEnabled\":\"maybe\"}");
        assertAccepted("PutConfigurationSetDeliveryOptions", "{\"MaxDeliverySeconds\":\"x\"}");
    }

    @Test
    void unknownOperationIsAServerError() {
        AwsException error = assertThrows(AwsException.class,
                () -> SesV2RequestShapes.check("NoSuchOperation", json("{}")));
        assertEquals("InternalFailure", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
    }

    @Test
    void bodyShapesLeaveOutUriAndQueryMembers() {
        assertAccepted("GetEmailIdentity", "{\"EmailIdentity\":123}");
    }

    private static void assertRejected(String operation, String body, String message) {
        AwsException error = assertThrows(AwsException.class, () -> SesV2RequestShapes.check(operation, json(body)));
        assertEquals("SerializationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(message, error.getMessage());
    }

    private static void assertAccepted(String operation, String body) {
        assertDoesNotThrow(() -> SesV2RequestShapes.check(operation, json(body)));
    }

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }
}
