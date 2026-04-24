package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CognitoJsonHandlerTest {

    private CognitoJsonHandler handler;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        CognitoService service = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "http://localhost:4566",
                regionResolver
        );
        handler = new CognitoJsonHandler(service, mapper);
    }

    @Test
    void signUpReturnsGeneratedSubAsUserSub() {
        ObjectNode poolReq = mapper.createObjectNode();
        poolReq.put("PoolName", "signup-pool");
        JsonNode poolBody = (JsonNode) handler.handle("CreateUserPool", poolReq, "us-east-1").getEntity();
        String poolId = poolBody.get("UserPool").get("Id").asText();

        ObjectNode clientReq = mapper.createObjectNode();
        clientReq.put("UserPoolId", poolId);
        clientReq.put("ClientName", "signup-client");
        JsonNode clientBody = (JsonNode) handler.handle("CreateUserPoolClient", clientReq, "us-east-1").getEntity();
        String clientId = clientBody.get("UserPoolClient").get("ClientId").asText();

        ObjectNode signUpReq = mapper.createObjectNode();
        signUpReq.put("ClientId", clientId);
        signUpReq.put("Username", "test@example.com");
        signUpReq.put("Password", "Password123!");
        ArrayNode attrs = signUpReq.putArray("UserAttributes");
        ObjectNode emailAttr = attrs.addObject();
        emailAttr.put("Name", "email");
        emailAttr.put("Value", "test@example.com");

        Response response = handler.handle("SignUp", signUpReq, "us-east-1");
        assertEquals(200, response.getStatus());

        JsonNode body = (JsonNode) response.getEntity();
        String userSub = body.get("UserSub").asText();
        assertNotEquals("test@example.com", userSub,
                "UserSub must be the generated UUID, not the username");
        assertTrue(userSub.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "UserSub should be a UUID, got: " + userSub);
    }

    @Test
    void createUserPoolReturnsRichResponse() {
        ObjectNode request = mapper.createObjectNode();
        request.put("PoolName", "test-pool");
        ArrayNode schema = request.putArray("Schema");
        ObjectNode attr = schema.addObject();
        attr.put("Name", "email");
        attr.put("AttributeDataType", "String");

        Response response = handler.handle("CreateUserPool", request, "us-east-1");
        assertEquals(200, response.getStatus());

        JsonNode body = (JsonNode) response.getEntity();
        JsonNode pool = body.get("UserPool");

        assertNotNull(pool.get("Id"));
        assertEquals("test-pool", pool.get("Name").asText());
        assertTrue(pool.get("Arn").asText().contains("arn:aws:cognito-idp:us-east-1:000000000000:userpool/"));
        assertEquals("Enabled", pool.get("Status").asText());
        
        // Check mandatory blocks for Terraform
        assertNotNull(pool.get("SchemaAttributes"));
        assertEquals(1, pool.get("SchemaAttributes").size());
        assertEquals("email", pool.get("SchemaAttributes").get(0).get("Name").asText());

        assertNotNull(pool.get("Policies"));
        assertNotNull(pool.get("LambdaConfig"));
        assertNotNull(pool.get("AdminCreateUserConfig"));
        assertNotNull(pool.get("AccountRecoverySetting"));
        assertEquals("ESSENTIALS", pool.get("UserPoolTier").asText());
    }

    @Test
    void createUserPoolResponseDoesNotLeakReservedTag() {
        ObjectNode request = mapper.createObjectNode();
        request.put("PoolName", "pinned-pool");
        ObjectNode tags = request.putObject("UserPoolTags");
        tags.put(ReservedTags.OVERRIDE_ID_KEY, "us-east-1_testpool1");
        tags.put("env", "test");

        Response response = handler.handle("CreateUserPool", request, "us-east-1");
        assertEquals(200, response.getStatus());

        JsonNode body = (JsonNode) response.getEntity();
        JsonNode pool = body.get("UserPool");
        assertEquals("us-east-1_testpool1", pool.get("Id").asText());
        assertEquals("test", pool.get("UserPoolTags").get("env").asText());
        assertFalse(pool.get("UserPoolTags").has(ReservedTags.OVERRIDE_ID_KEY));
    }

    @Test
    void updateAndDescribeUserPoolResponsesDoNotLeakReservedTag() {
        ObjectNode createRequest = mapper.createObjectNode();
        createRequest.put("PoolName", "update-pool");
        JsonNode createBody = (JsonNode) handler.handle("CreateUserPool", createRequest, "us-east-1").getEntity();
        String poolId = createBody.get("UserPool").get("Id").asText();

        ObjectNode updateRequest = mapper.createObjectNode();
        updateRequest.put("UserPoolId", poolId);
        ObjectNode tags = updateRequest.putObject("UserPoolTags");
        tags.put(ReservedTags.OVERRIDE_ID_KEY, "late-id");
        tags.put("env", "test");

        Response updateResponse = handler.handle("UpdateUserPool", updateRequest, "us-east-1");
        assertEquals(200, updateResponse.getStatus());

        JsonNode updateBody = (JsonNode) updateResponse.getEntity();
        JsonNode updatedPool = updateBody.get("UserPool");
        assertEquals("test", updatedPool.get("UserPoolTags").get("env").asText());
        assertFalse(updatedPool.get("UserPoolTags").has(ReservedTags.OVERRIDE_ID_KEY));

        ObjectNode describeRequest = mapper.createObjectNode();
        describeRequest.put("UserPoolId", poolId);
        Response describeResponse = handler.handle("DescribeUserPool", describeRequest, "us-east-1");
        assertEquals(200, describeResponse.getStatus());

        JsonNode describeBody = (JsonNode) describeResponse.getEntity();
        JsonNode describedPool = describeBody.get("UserPool");
        assertEquals("test", describedPool.get("UserPoolTags").get("env").asText());
        assertFalse(describedPool.get("UserPoolTags").has(ReservedTags.OVERRIDE_ID_KEY));
    }

    @Test
    void tagListAndUntagResourceRoundTrip() {
        ObjectNode createRequest = mapper.createObjectNode();
        createRequest.put("PoolName", "tag-pool");
        JsonNode createBody = (JsonNode) handler.handle("CreateUserPool", createRequest, "us-east-1").getEntity();
        JsonNode createdPool = createBody.get("UserPool");
        String resourceArn = createdPool.get("Arn").asText();

        ObjectNode tagRequest = mapper.createObjectNode();
        tagRequest.put("ResourceArn", resourceArn);
        ObjectNode tags = tagRequest.putObject("Tags");
        tags.put("env", "test");
        tags.put("team", "platform");

        Response tagResponse = handler.handle("TagResource", tagRequest, "us-east-1");
        assertEquals(200, tagResponse.getStatus());

        ObjectNode listRequest = mapper.createObjectNode();
        listRequest.put("ResourceArn", resourceArn);
        Response listResponse = handler.handle("ListTagsForResource", listRequest, "us-east-1");
        assertEquals(200, listResponse.getStatus());
        JsonNode listedTags = ((JsonNode) listResponse.getEntity()).get("Tags");
        assertEquals("test", listedTags.get("env").asText());
        assertEquals("platform", listedTags.get("team").asText());

        ObjectNode untagRequest = mapper.createObjectNode();
        untagRequest.put("ResourceArn", resourceArn);
        untagRequest.putArray("TagKeys").add("team");

        Response untagResponse = handler.handle("UntagResource", untagRequest, "us-east-1");
        assertEquals(200, untagResponse.getStatus());

        JsonNode afterUntag = ((JsonNode) handler.handle("ListTagsForResource", listRequest, "us-east-1").getEntity()).get("Tags");
        assertEquals("test", afterUntag.get("env").asText());
        assertFalse(afterUntag.has("team"));
    }

    @Test
    void tagResourceRejectsReservedKey() {
        ObjectNode createRequest = mapper.createObjectNode();
        createRequest.put("PoolName", "tag-pool");
        JsonNode createBody = (JsonNode) handler.handle("CreateUserPool", createRequest, "us-east-1").getEntity();
        String resourceArn = createBody.get("UserPool").get("Arn").asText();

        ObjectNode tagRequest = mapper.createObjectNode();
        tagRequest.put("ResourceArn", resourceArn);
        tagRequest.putObject("Tags").put(ReservedTags.OVERRIDE_ID_KEY, "late-id");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> handler.handle("TagResource", tagRequest, "us-east-1")
        );
        assertEquals("ValidationException", exception.getErrorCode());
    }
}
