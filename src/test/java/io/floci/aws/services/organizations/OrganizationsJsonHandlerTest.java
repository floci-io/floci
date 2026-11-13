package io.floci.aws.services.organizations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrganizationsJsonHandlerTest {

    private static final String OWNER = "100623600101";
    private static final String INVITEE = "100623600109";
    private static final String TAGS = "\"Tags\":[{\"Key\":\"team\",\"Value\":\"red\"},{\"Key\":\"note\",\"Value\":\"\"}]";
    private static final Map<String, String> EXPECTED = Map.of("team", "red", "note", "");

    private final ObjectMapper mapper = new ObjectMapper();
    private OrganizationsService service;
    private OrganizationsJsonHandler handler;
    private String root;

    @BeforeEach
    void setUp() {
        service = new OrganizationsService(mapper, new RegionResolver("us-east-1", OWNER),
                AccountAwareStorageBackend.inMemory(OWNER), AccountAwareStorageBackend.inMemory(OWNER),
                AccountAwareStorageBackend.inMemory(OWNER), AccountAwareStorageBackend.inMemory(OWNER),
                AccountAwareStorageBackend.inMemory(OWNER), AccountAwareStorageBackend.inMemory(OWNER));
        handler = new OrganizationsJsonHandler(service, mapper);
        root = service.createOrganization(OWNER, "ALL").getRoot().getId();
    }

    @Test
    void validTagsReachEveryTaggedOperation() throws Exception {
        String unit = ok("CreateOrganizationalUnit", OWNER,
                "{\"ParentId\":\"" + root + "\",\"Name\":\"Unit\"," + TAGS + "}")
                .path("OrganizationalUnit").path("Id").asText();
        assertEquals(EXPECTED, service.listTagsForResource(OWNER, unit));

        String account = ok("CreateAccount", OWNER,
                "{\"Email\":\"tagged@example.test\",\"AccountName\":\"Tagged\"," + TAGS + "}")
                .path("CreateAccountStatus").path("AccountId").asText();
        assertEquals(EXPECTED, service.listTagsForResource(OWNER, account));

        service.enablePolicyType(OWNER, root, "SERVICE_CONTROL_POLICY");
        String policy = ok("CreatePolicy", OWNER, "{\"Content\":\"{}\",\"Description\":\"\",\"Name\":\"Policy\","
                + "\"Type\":\"SERVICE_CONTROL_POLICY\"," + TAGS + "}")
                .path("Policy").path("PolicySummary").path("Id").asText();
        assertEquals(EXPECTED, service.listTagsForResource(OWNER, policy));

        ok("TagResource", OWNER, "{\"ResourceId\":\"" + root + "\"," + TAGS + "}");
        assertEquals(EXPECTED, service.listTagsForResource(OWNER, root));

        ok("PutResourcePolicy", OWNER, "{\"Content\":\"{}\"," + TAGS + "}");
        assertEquals(EXPECTED, service.describeResourcePolicy(OWNER).tags());

        String handshake = ok("InviteAccountToOrganization", OWNER,
                "{\"Target\":{\"Id\":\"" + INVITEE + "\",\"Type\":\"ACCOUNT\"}," + TAGS + "}")
                .path("Handshake").path("Id").asText();
        ok("AcceptHandshake", INVITEE, "{\"HandshakeId\":\"" + handshake + "\"}");
        assertEquals(EXPECTED, service.listTagsForResource(OWNER, INVITEE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Key\":\"team\",\"Value\":null}",
            "{\"Key\":\"team\"}",
            "{\"Key\":null,\"Value\":\"red\"}",
            "{\"Value\":\"red\"}"})
    void aTagWithANullOrMissingKeyOrValueFailsTheRequest(String tag) throws Exception {
        try (Response response = handler.handle("TagResource",
                mapper.readTree("{\"ResourceId\":\"" + root + "\",\"Tags\":[" + tag + "]}"), OWNER)) {
            assertEquals(400, response.getStatus());
            assertEquals("InvalidInputException", ((AwsErrorResponse) response.getEntity()).type());
        }
        assertEquals(Map.of(), service.listTagsForResource(OWNER, root));
    }

    @Test
    void aRepeatedTagKeyFailsTheRequestWithItsReason() throws Exception {
        try (Response response = handler.handle("TagResource", mapper.readTree("{\"ResourceId\":\"" + root
                + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"red\"},{\"Key\":\"team\",\"Value\":\"blue\"}]}"), OWNER)) {
            assertEquals(400, response.getStatus());
            JsonNode body = (JsonNode) response.getEntity();
            assertEquals("InvalidInputException", body.path("__type").asText());
            assertEquals("DUPLICATE_TAG_KEY", body.path("Reason").asText());
        }
        assertEquals(Map.of(), service.listTagsForResource(OWNER, root));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"team=red\"|Unexpected field type",
            "{\"Key\":\"team\",\"Value\":\"red\"}|Start of structure or map found where not expected"})
    void tagsThatAreNotAListFailEveryTaggedOperation(String tags, String message) throws Exception {
        service.enablePolicyType(OWNER, root, "SERVICE_CONTROL_POLICY");
        Map<String, String> requests = Map.of(
                "CreateOrganizationalUnit", "\"ParentId\":\"" + root + "\",\"Name\":\"Unit\"",
                "CreateAccount", "\"Email\":\"tagged@example.test\",\"AccountName\":\"Tagged\"",
                "CreateGovCloudAccount", "\"Email\":\"gov@example.test\",\"AccountName\":\"Gov\"",
                "CreatePolicy", "\"Content\":\"{}\",\"Name\":\"Policy\",\"Type\":\"SERVICE_CONTROL_POLICY\"",
                "TagResource", "\"ResourceId\":\"" + root + "\"",
                "PutResourcePolicy", "\"Content\":\"{}\"",
                "InviteAccountToOrganization", "\"Target\":{\"Id\":\"" + INVITEE + "\",\"Type\":\"ACCOUNT\"}");
        for (Map.Entry<String, String> request : requests.entrySet()) {
            assertRefused(request.getKey(), "{" + request.getValue() + ",\"Tags\":" + tags + "}", message);
        }
        assertEquals(List.of(), service.listOrganizationalUnitsForParent(OWNER, root));
        assertEquals(List.of(), service.listCreateAccountStatus(OWNER, null));
        assertEquals(1, service.listPolicies(OWNER, "SERVICE_CONTROL_POLICY").size());
        assertEquals(Map.of(), service.listTagsForResource(OWNER, root));
        assertThrows(AwsException.class, () -> service.describeResourcePolicy(OWNER));
        assertEquals(List.of(), service.listHandshakesForOrganization(OWNER, null, null));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "DescribeAccount|{\"AccountId\":100623600101}|NUMBER_VALUE cannot be converted to String",
            "UpdateOrganizationalUnit|{\"OrganizationalUnitId\":\"ou-x\",\"Name\":1.5}"
                    + "|DECIMAL_VALUE cannot be converted to String",
            "ListAccounts|{\"MaxResults\":\"5\"}|STRING_VALUE cannot be converted to Integer",
            "InviteAccountToOrganization|{\"Target\":\"100623600109\"}|Unexpected field type",
            "ListHandshakesForOrganization|{\"Filter\":[]}|UnknownError",
            "CreateAccount|{\"Email\":\"a@example.test\",\"AccountName\":\"A\",\"Tags\":[\"team\"]}"
                    + "|Unexpected value type in payload",
            "CreateAccount|{\"Email\":\"a@example.test\",\"AccountName\":\"A\","
                    + "\"Tags\":[{\"Key\":\"team\",\"Value\":5}]}"
                    + "|NUMBER_VALUE cannot be converted to String"})
    void aMemberOfTheWrongJsonTypeFailsBeforeTheOperationRuns(String action, String request, String message)
            throws Exception {
        assertRefused(action, request, message);
        assertEquals(List.of(), service.listCreateAccountStatus(OWNER, null));
    }

    @Test
    void requestsOfTheModeledTypesStillReachTheirOperations() throws Exception {
        ok("DescribeOrganization", OWNER, "{}");
        assertEquals(1, ok("ListAccounts", OWNER, "{\"MaxResults\":1,\"NextToken\":null,\"Unmodeled\":[]}")
                .path("Accounts").size());
    }

    @Test
    void aModeledOperationFlociDoesNotServeIsUnknownWhateverItsBody() throws Exception {
        try (Response response = handler.handle("ListInboundResponsibilityTransfers",
                mapper.readTree("{\"MaxResults\":\"x\"}"), OWNER)) {
            assertEquals(404, response.getStatus());
            assertEquals("UnknownOperationException", ((AwsErrorResponse) response.getEntity()).type());
        }
    }

    private void assertRefused(String action, String request, String message) throws Exception {
        try (Response response = handler.handle(action, mapper.readTree(request), OWNER)) {
            assertEquals(400, response.getStatus(), action);
            AwsErrorResponse error = (AwsErrorResponse) response.getEntity();
            assertEquals("SerializationException", error.type(), action);
            assertEquals(message, error.message(), action);
        }
    }

    private JsonNode ok(String action, String caller, String request) throws Exception {
        try (Response response = handler.handle(action, mapper.readTree(request), caller)) {
            assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
            return (JsonNode) response.getEntity();
        }
    }
}
