package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
class CognitoIdentityProviderCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261101/us-east-1/cloudformation/aws4_request";
    private static final String SM_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String NAME = "ExampleOIDC";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> poolIds = new ArrayList<>();
    private final List<String> secretIds = new ArrayList<>();
    private final String stack = "cognito-idp-" + Long.toString(System.nanoTime(), 36);
    private boolean createdStack;

    @BeforeAll
    static void configureAwsContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanup() {
        if (createdStack) {
            cloudFormation("DeleteStack", null);
            CfnStackWaits.awaitStackDeleted(stack);
        }
        for (String pool : poolIds) {
            cognitoAction("DeleteUserPool", "{\"UserPoolId\":\"" + pool + "\"}").then().statusCode(200);
        }
        for (String secret : secretIds) {
            given().header("X-Amz-Target", "secretsmanager.DeleteSecret").contentType(SM_CONTENT_TYPE)
                    .body(mapper.createObjectNode().put("SecretId", secret)
                            .put("ForceDeleteWithoutRecovery", true).toString())
                    .when().post("/").then().statusCode(200);
        }
    }

    @Test
    void stackCreatesUpdatesReplacesAndDeletesTheBackingProvider() throws Exception {
        String poolA = createPool();
        String poolB = createPool();
        createStack(template(poolA, NAME, "OIDC", "original-client"));
        assertEquals(NAME, output("ProviderRef"));
        JsonNode created = provider(poolA, NAME);
        assertEquals("OIDC", created.path("ProviderType").asText());
        assertEquals("original-client", created.path("ProviderDetails").path("client_id").asText());
        assertEquals("email", created.path("AttributeMapping").path("email").asText());

        updateStack(template(poolA, NAME, "OIDC", "updated-client"), "UPDATE_COMPLETE");
        assertEquals(NAME, output("ProviderRef"));
        assertClientId(poolA, NAME, "updated-client");

        updateStack(template(poolA, "RenamedOIDC", "OIDC", "updated-client"), "UPDATE_COMPLETE");
        assertEquals("RenamedOIDC", output("ProviderRef"));
        assertProviderGone(poolA, NAME);
        assertClientId(poolA, "RenamedOIDC", "updated-client");

        updateStack(template(poolB, "RenamedOIDC", "OIDC", "moved-client"), "UPDATE_COMPLETE");
        assertEquals("RenamedOIDC", output("ProviderRef"));
        assertProviderGone(poolA, "RenamedOIDC");
        assertClientId(poolB, "RenamedOIDC", "moved-client");

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
        createdStack = false;
        assertProviderGone(poolB, "RenamedOIDC");
    }

    @Test
    void aUserPoolClientListsTheProviderThroughItsRef() throws Exception {
        ObjectNode template = mapper.createObjectNode();
        ObjectNode resources = template.putObject("Resources");
        resources.putObject("Pool").put("Type", "AWS::Cognito::UserPool")
                .putObject("Properties").put("UserPoolName", stack);
        ObjectNode idp = resources.putObject("Idp");
        idp.put("Type", "AWS::Cognito::UserPoolIdentityProvider");
        ObjectNode idpProperties = providerProperties(NAME, "OIDC", "example-client");
        idpProperties.putObject("UserPoolId").put("Ref", "Pool");
        idp.set("Properties", idpProperties);
        ObjectNode client = resources.putObject("Client");
        client.put("Type", "AWS::Cognito::UserPoolClient");
        ObjectNode clientProperties = client.putObject("Properties");
        clientProperties.putObject("UserPoolId").put("Ref", "Pool");
        clientProperties.put("ClientName", "federated");
        clientProperties.putArray("SupportedIdentityProviders").addObject().put("Ref", "Idp");
        ObjectNode outputs = template.putObject("Outputs");
        outputs.putObject("PoolId").putObject("Value").put("Ref", "Pool");
        outputs.putObject("ClientId").putObject("Value").put("Ref", "Client");

        createStack(template);

        String pool = output("PoolId");
        assertClientId(pool, NAME, "example-client");
        JsonNode described = cognitoJson("DescribeUserPoolClient", mapper.createObjectNode()
                .put("UserPoolId", pool).put("ClientId", output("ClientId")).toString()).path("UserPoolClient");
        assertEquals(NAME, described.path("SupportedIdentityProviders").get(0).asText());
    }

    @Test
    void clientSecretResolvesFromASecretsManagerDynamicReference() throws Exception {
        String pool = createPool();
        String secret = stack + "-oidc";
        given().header("X-Amz-Target", "secretsmanager.CreateSecret").contentType(SM_CONTENT_TYPE)
                .body(mapper.createObjectNode().put("Name", secret)
                        .put("SecretString", "{\"clientSecret\":\"resolved-secret\"}").toString())
                .when().post("/").then().statusCode(200);
        secretIds.add(secret);
        ObjectNode template = template(pool, NAME, "OIDC", "example-client");
        ((ObjectNode) template.path("Resources").path("Idp").path("Properties").path("ProviderDetails"))
                .put("client_secret", "{{resolve:secretsmanager:" + secret + ":SecretString:clientSecret}}");

        createStack(template);

        assertEquals("resolved-secret",
                provider(pool, NAME).path("ProviderDetails").path("client_secret").asText());
    }

    @Test
    void changingOnlyTheProviderTypeRollsTheUpdateBack() throws Exception {
        String pool = createPool();
        createStack(template(pool, NAME, "OIDC", "original-client"));

        ObjectNode saml = template(pool, NAME, "SAML", "original-client");
        ((ObjectNode) saml.path("Resources").path("Idp").path("Properties").path("ProviderDetails"))
                .put("MetadataURL", "https://issuer.example.com/saml/metadata");
        updateStack(saml, "UPDATE_ROLLBACK_COMPLETE");

        JsonNode kept = provider(pool, NAME);
        assertEquals("OIDC", kept.path("ProviderType").asText());
        assertEquals("original-client", kept.path("ProviderDetails").path("client_id").asText());
        assertFalse(kept.path("ProviderDetails").has("MetadataURL"));
        assertEquals(NAME, output("ProviderRef"));
    }

    @Test
    void theOriginalProviderUpdatesInPlaceAfterARolledBackTypeAndNameReplacement() throws Exception {
        String pool = createPool();
        createStack(template(pool, NAME, "OIDC", "original-client"));
        ObjectNode saml = template(pool, "ExampleSAML", "SAML", "original-client");
        ((ObjectNode) saml.path("Resources").path("Idp").path("Properties").path("ProviderDetails"))
                .put("MetadataURL", "https://issuer.example.com/saml/metadata");
        addFailingDependentResource(saml);
        updateStack(saml, "UPDATE_ROLLBACK_COMPLETE");
        assertProviderGone(pool, "ExampleSAML");

        updateStack(template(pool, NAME, "OIDC", "updated-client"), "UPDATE_COMPLETE");

        assertEquals(NAME, output("ProviderRef"));
        assertClientId(pool, NAME, "updated-client");
    }

    @Test
    void aFailedDependentResourceRestoresThePriorInPlaceConfiguration() throws Exception {
        String pool = createPool();
        createStack(template(pool, NAME, "OIDC", "original-client"));
        ObjectNode attempted = template(pool, NAME, "OIDC", "attempted-client");
        ObjectNode attemptedProperties = (ObjectNode) attempted.path("Resources").path("Idp").path("Properties");
        attemptedProperties.putObject("AttributeMapping").put("email", "mail");
        attemptedProperties.putArray("IdpIdentifiers").add("example.com");
        addFailingDependentResource(attempted);

        updateStack(attempted, "UPDATE_ROLLBACK_COMPLETE");

        JsonNode restored = provider(pool, NAME);
        assertEquals("original-client", restored.path("ProviderDetails").path("client_id").asText());
        assertEquals("email", restored.path("AttributeMapping").path("email").asText());
        assertEquals(0, restored.path("IdpIdentifiers").size());
    }

    @Test
    void aFailedDependentResourceDeletesTheReplacementAndRestoresThePriorPool() throws Exception {
        String poolA = createPool();
        String poolB = createPool();
        createStack(template(poolA, NAME, "OIDC", "original-client"));
        ObjectNode attempted = template(poolB, NAME, "OIDC", "replacement-client");
        addFailingDependentResource(attempted);

        updateStack(attempted, "UPDATE_ROLLBACK_COMPLETE");

        assertClientId(poolA, NAME, "original-client");
        assertProviderGone(poolB, NAME);
        assertEquals(NAME, output("ProviderRef"));
    }

    private String createPool() throws Exception {
        String id = cognitoJson("CreateUserPool", "{\"PoolName\":\"" + stack + "\"}")
                .path("UserPool").path("Id").asText();
        poolIds.add(id);
        return id;
    }

    private void createStack(ObjectNode template) {
        cloudFormation("CreateStack", template);
        createdStack = true;
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
    }

    private void updateStack(ObjectNode template, String expectedStatus) {
        cloudFormation("UpdateStack", template);
        assertEquals(expectedStatus, CfnStackWaits.awaitTerminal(stack).status());
    }

    private void cloudFormation(String action, ObjectNode template) {
        RequestSpecification request = cfn(action, stack).header("Authorization", CFN_AUTH);
        if (template != null) {
            request.formParam("TemplateBody", template.toString());
        }
        request.when().post("/").then().statusCode(200);
    }

    private String output(String key) {
        String xml = cfn("DescribeStacks", stack).header("Authorization", CFN_AUTH)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private ObjectNode providerProperties(String name, String type, String clientId) {
        ObjectNode properties = mapper.createObjectNode();
        properties.put("ProviderName", name);
        properties.put("ProviderType", type);
        properties.putObject("ProviderDetails")
                .put("client_id", clientId)
                .put("oidc_issuer", "https://issuer.example.com")
                .put("authorize_scopes", "openid email")
                .put("attributes_request_method", "GET");
        properties.putObject("AttributeMapping").put("email", "email");
        return properties;
    }

    private ObjectNode template(String pool, String name, String type, String clientId) {
        ObjectNode template = mapper.createObjectNode();
        ObjectNode resource = template.putObject("Resources").putObject("Idp");
        resource.put("Type", "AWS::Cognito::UserPoolIdentityProvider");
        ObjectNode properties = providerProperties(name, type, clientId);
        properties.put("UserPoolId", pool);
        resource.set("Properties", properties);
        template.putObject("Outputs").putObject("ProviderRef").putObject("Value").put("Ref", "Idp");
        return template;
    }

    private static void addFailingDependentResource(ObjectNode template) {
        ObjectNode broken = ((ObjectNode) template.path("Resources")).putObject("Broken");
        broken.put("Type", "AWS::Cognito::UserPoolClient");
        broken.put("DependsOn", "Idp");
        broken.putObject("Properties").put("UserPoolId", "us-east-1_nonexistent").put("ClientName", "broken");
    }

    private static JsonNode provider(String pool, String name) throws Exception {
        return cognitoJson("DescribeIdentityProvider", "{\"UserPoolId\":\"" + pool
                + "\",\"ProviderName\":\"" + name + "\"}").path("IdentityProvider");
    }

    private static void assertClientId(String pool, String name, String clientId) throws Exception {
        JsonNode provider = provider(pool, name);
        assertEquals(name, provider.path("ProviderName").asText());
        assertEquals(clientId, provider.path("ProviderDetails").path("client_id").asText());
    }

    private static void assertProviderGone(String pool, String name) {
        cognitoAction("DescribeIdentityProvider", "{\"UserPoolId\":\"" + pool
                + "\",\"ProviderName\":\"" + name + "\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }
}
