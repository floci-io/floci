package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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
                regionResolver,
                null
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
        assertEquals(20, pool.get("SchemaAttributes").size(),
                "DescribeUserPool must always return all 20 Cognito standard attributes");
        assertTrue(schemaNames(pool).contains("email"));

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
    void describeUserPoolWithNoSchemaReturnsAllTwentyStandardAttributes() {
        ObjectNode create = mapper.createObjectNode();
        create.put("PoolName", "no-schema-pool");
        JsonNode created = (JsonNode) handler.handle("CreateUserPool", create, "us-east-1").getEntity();
        String poolId = created.get("UserPool").get("Id").asText();

        ObjectNode describe = mapper.createObjectNode();
        describe.put("UserPoolId", poolId);
        JsonNode body = (JsonNode) handler.handle("DescribeUserPool", describe, "us-east-1").getEntity();
        JsonNode schema = body.get("UserPool").get("SchemaAttributes");

        assertEquals(20, schema.size());
        Set<String> names = schemaNames(body.get("UserPool"));
        List.of("sub", "name", "given_name", "family_name", "middle_name", "nickname",
                "preferred_username", "profile", "picture", "website", "email",
                "email_verified", "gender", "birthdate", "zoneinfo", "locale",
                "phone_number", "phone_number_verified", "address", "updated_at")
                .forEach(n -> assertTrue(names.contains(n), "missing standard attribute: " + n));
    }

    @Test
    void describeUserPoolMergesCustomAttributeAfterStandardOnes() {
        ObjectNode create = mapper.createObjectNode();
        create.put("PoolName", "custom-attr-pool");
        ArrayNode schema = create.putArray("Schema");
        ObjectNode custom = schema.addObject();
        custom.put("Name", "custom:tenant_id");
        custom.put("AttributeDataType", "String");

        JsonNode created = (JsonNode) handler.handle("CreateUserPool", create, "us-east-1").getEntity();
        String poolId = created.get("UserPool").get("Id").asText();

        ObjectNode describe = mapper.createObjectNode();
        describe.put("UserPoolId", poolId);
        JsonNode body = (JsonNode) handler.handle("DescribeUserPool", describe, "us-east-1").getEntity();
        JsonNode schemaNode = body.get("UserPool").get("SchemaAttributes");

        assertEquals(21, schemaNode.size(), "20 standard + 1 custom");
        Set<String> names = schemaNames(body.get("UserPool"));
        assertTrue(names.contains("custom:tenant_id"));
        assertTrue(names.contains("sub"));
        assertTrue(names.contains("email"));
        // custom attribute must be last (after all standard ones)
        assertEquals("custom:tenant_id", schemaNode.get(20).get("Name").asText());
    }

    @Test
    void describeUserPoolExplicitStandardAttributeOverridesDefault() {
        ObjectNode create = mapper.createObjectNode();
        create.put("PoolName", "override-attr-pool");
        ArrayNode schema = create.putArray("Schema");
        ObjectNode emailOverride = schema.addObject();
        emailOverride.put("Name", "email");
        emailOverride.put("AttributeDataType", "String");
        emailOverride.put("Required", true);

        JsonNode created = (JsonNode) handler.handle("CreateUserPool", create, "us-east-1").getEntity();
        String poolId = created.get("UserPool").get("Id").asText();

        ObjectNode describe = mapper.createObjectNode();
        describe.put("UserPoolId", poolId);
        JsonNode body = (JsonNode) handler.handle("DescribeUserPool", describe, "us-east-1").getEntity();
        JsonNode schemaNode = body.get("UserPool").get("SchemaAttributes");

        assertEquals(20, schemaNode.size(), "override should not add a duplicate entry");
        JsonNode emailAttr = StreamSupport.stream(Spliterators.spliteratorUnknownSize(
                schemaNode.elements(), 0), false)
                .filter(n -> "email".equals(n.get("Name").asText()))
                .findFirst()
                .orElseThrow();
        assertTrue(emailAttr.get("Required").asBoolean(), "email must be required per the override");
    }

    // =========================================================================
    // Issue #1306 — DescribeUserPoolClient extended configuration
    // =========================================================================

    @Test
    void describeUserPoolClientReturnsExtendedConfigurationFields() {
        ObjectNode poolReq = mapper.createObjectNode();
        poolReq.put("PoolName", "client-pool");
        JsonNode poolBody = (JsonNode) handler.handle("CreateUserPool", poolReq, "us-east-1").getEntity();
        String poolId = poolBody.get("UserPool").get("Id").asText();

        ObjectNode clientReq = mapper.createObjectNode();
        clientReq.put("UserPoolId", poolId);
        clientReq.put("ClientName", "extended-client");
        clientReq.put("GenerateSecret", true);
        clientReq.put("AllowedOAuthFlowsUserPoolClient", true);
        clientReq.putArray("AllowedOAuthFlows").add("code");
        clientReq.putArray("AllowedOAuthScopes").add("openid").add("email");
        clientReq.putObject("AnalyticsConfiguration")
                .put("ApplicationId", "d70b2ba36a8c4dc5a04a0451a31a1e12")
                .put("ExternalId", "my-external-id")
                .put("RoleArn", "arn:aws:iam::123456789012:role/test-cognitouserpool-role")
                .put("UserDataShared", true);
        clientReq.putArray("CallbackURLs").add("https://example.com").add("http://localhost");
        clientReq.put("DefaultRedirectURI", "https://example.com");
        clientReq.putArray("ExplicitAuthFlows").add("ALLOW_USER_AUTH").add("ALLOW_REFRESH_TOKEN_AUTH");
        clientReq.put("AccessTokenValidity", 6);
        clientReq.put("IdTokenValidity", 7);
        clientReq.putArray("LogoutURLs").add("https://example.com/logout");
        clientReq.put("PreventUserExistenceErrors", "ENABLED");
        clientReq.putArray("ReadAttributes").add("email").add("address");
        clientReq.put("RefreshTokenValidity", 8);
        clientReq.putArray("SupportedIdentityProviders").add("COGNITO").add("Google");
        clientReq.putObject("TokenValidityUnits")
                .put("AccessToken", "hours")
                .put("IdToken", "minutes")
                .put("RefreshToken", "days");
        clientReq.putArray("WriteAttributes").add("family_name").add("email");
        clientReq.putObject("RefreshTokenRotation")
                .put("Feature", "ENABLED")
                .put("RetryGracePeriodSeconds", 30);
        clientReq.put("EnableTokenRevocation", true);

        JsonNode clientBody = (JsonNode) handler.handle("CreateUserPoolClient", clientReq, "us-east-1").getEntity();
        String clientId = clientBody.get("UserPoolClient").get("ClientId").asText();

        ObjectNode describeReq = mapper.createObjectNode();
        describeReq.put("UserPoolId", poolId);
        describeReq.put("ClientId", clientId);

        JsonNode describeBody = (JsonNode) handler.handle("DescribeUserPoolClient", describeReq, "us-east-1").getEntity();
        JsonNode client = describeBody.get("UserPoolClient");

        assertTrue(client.get("AllowedOAuthFlowsUserPoolClient").asBoolean());
        assertEquals("code", client.get("AllowedOAuthFlows").get(0).asText());
        assertEquals("openid", client.get("AllowedOAuthScopes").get(0).asText());
        assertEquals("d70b2ba36a8c4dc5a04a0451a31a1e12", client.get("AnalyticsConfiguration").get("ApplicationId").asText());
        assertEquals("https://example.com", client.get("CallbackURLs").get(0).asText());
        assertEquals("https://example.com", client.get("DefaultRedirectURI").asText());
        assertEquals("ALLOW_USER_AUTH", client.get("ExplicitAuthFlows").get(0).asText());
        assertEquals(6, client.get("AccessTokenValidity").asInt());
        assertEquals(7, client.get("IdTokenValidity").asInt());
        assertEquals("https://example.com/logout", client.get("LogoutURLs").get(0).asText());
        assertEquals("ENABLED", client.get("PreventUserExistenceErrors").asText());
        assertEquals("email", client.get("ReadAttributes").get(0).asText());
        assertEquals(8, client.get("RefreshTokenValidity").asInt());
        assertEquals("COGNITO", client.get("SupportedIdentityProviders").get(0).asText());
        assertEquals("hours", client.get("TokenValidityUnits").get("AccessToken").asText());
        assertEquals("family_name", client.get("WriteAttributes").get(0).asText());
        assertEquals("ENABLED", client.get("RefreshTokenRotation").get("Feature").asText());
        assertTrue(client.get("EnableTokenRevocation").asBoolean());
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

    private Set<String> schemaNames(JsonNode pool) {
        return StreamSupport.stream(
                        Spliterators.spliteratorUnknownSize(pool.get("SchemaAttributes").elements(), 0), false)
                .map(n -> n.get("Name").asText())
                .collect(Collectors.toSet());
    }
}
