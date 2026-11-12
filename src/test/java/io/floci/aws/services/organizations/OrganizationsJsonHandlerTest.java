package io.floci.aws.services.organizations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private JsonNode ok(String action, String caller, String request) throws Exception {
        try (Response response = handler.handle(action, mapper.readTree(request), caller)) {
            assertEquals(200, response.getStatus(), String.valueOf(response.getEntity()));
            return (JsonNode) response.getEntity();
        }
    }
}
