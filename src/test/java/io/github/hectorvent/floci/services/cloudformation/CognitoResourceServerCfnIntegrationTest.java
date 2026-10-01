package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.XmlParser;
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
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CognitoResourceServerCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String IDENTIFIER = "https://api.example.com";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> poolIds = new ArrayList<>();
    private final String stack = "cognito-resource-server-" + Long.toString(System.nanoTime(), 36);
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
    }

    @Test
    void stackCreatesUpdatesReplacesAndDeletesTheBackingResourceServer() throws Exception {
        String poolA = createPool();
        String poolB = createPool();
        createStack(template(poolA, IDENTIFIER, "Original API", "read"));
        assertEquals(IDENTIFIER, output("ServerRef"));
        assertServer(poolA, IDENTIFIER, "Original API", "read");

        updateStack(template(poolA, IDENTIFIER, "Renamed API", "write"), "UPDATE_COMPLETE");
        assertEquals(IDENTIFIER, output("ServerRef"));
        assertServer(poolA, IDENTIFIER, "Renamed API", "write");

        updateStack(template(poolB, IDENTIFIER, "Moved API", "admin"), "UPDATE_COMPLETE");
        assertEquals(IDENTIFIER, output("ServerRef"));
        assertServer(poolB, IDENTIFIER, "Moved API", "admin");
        assertServerGone(poolA, IDENTIFIER);

        String renamed = "https://new-api.example.com";
        updateStack(template(poolB, renamed, "New API", "read"), "UPDATE_COMPLETE");
        assertEquals(renamed, output("ServerRef"));
        assertServerGone(poolB, IDENTIFIER);
        assertServer(poolB, renamed, "New API", "read");

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
        createdStack = false;
        assertServerGone(poolB, renamed);
    }

    @Test
    void removingScopesFromTheTemplateClearsTheBackingScopes() throws Exception {
        String pool = createPool();
        createStack(template(pool, IDENTIFIER, "Original API", "read"));

        updateStack(template(pool, IDENTIFIER, "No scopes", null), "UPDATE_COMPLETE");

        assertEquals(0, server(pool, IDENTIFIER).path("Scopes").size());
    }

    @Test
    void stackResolvesTheDeclaredUserPoolAndNestedScopeParameter() throws Exception {
        ObjectNode template = template("placeholder", IDENTIFIER, "Example API", "placeholder");
        template.putObject("Parameters").putObject("Scope").put("Type", "String").put("Default", "read");
        ObjectNode resources = (ObjectNode) template.path("Resources");
        ObjectNode pool = resources.putObject("Pool");
        pool.put("Type", "AWS::Cognito::UserPool");
        pool.putObject("Properties").put("UserPoolName", stack);
        ObjectNode properties = (ObjectNode) resources.path("Server").path("Properties");
        properties.putObject("UserPoolId").put("Ref", "Pool");
        ((ObjectNode) properties.path("Scopes").get(0)).putObject("ScopeName").put("Ref", "Scope");
        ((ObjectNode) template.path("Outputs")).putObject("PoolRef").putObject("Value").put("Ref", "Pool");

        createStack(template);

        String poolId = output("PoolRef");
        assertEquals(IDENTIFIER, output("ServerRef"));
        assertServer(poolId, IDENTIFIER, "Example API", "read");
    }

    @Test
    void failingResourceServerUpdateRestoresItsNameAndScopes() throws Exception {
        String pool = createPool();
        createStack(template(pool, IDENTIFIER, "Original API", "read"));
        ObjectNode attempted = template(pool, IDENTIFIER, "Attempted name", "write");
        ObjectNode properties = (ObjectNode) attempted.path("Resources").path("Server").path("Properties");
        properties.withArray("Scopes").addObject().put("ScopeName", "write").put("ScopeDescription", "Duplicate");

        updateStack(attempted, "UPDATE_ROLLBACK_COMPLETE");

        assertServer(pool, IDENTIFIER, "Original API", "read");
        assertEquals(IDENTIFIER, output("ServerRef"));
    }

    @Test
    void aFailedDependentResourceRestoresThePriorInPlaceConfiguration() throws Exception {
        String pool = createPool();
        createStack(template(pool, IDENTIFIER, "Original API", "read"));
        ObjectNode attempted = template(pool, IDENTIFIER, "Attempted name", "write");
        addFailingDependentResource(attempted);

        updateStack(attempted, "UPDATE_ROLLBACK_COMPLETE");

        assertServer(pool, IDENTIFIER, "Original API", "read");
    }

    @Test
    void aFailedDependentResourceDeletesTheReplacementAndRestoresThePriorPool() throws Exception {
        String poolA = createPool();
        String poolB = createPool();
        createStack(template(poolA, IDENTIFIER, "Original API", "read"));
        ObjectNode attempted = template(poolB, IDENTIFIER, "Replacement API", "write");
        addFailingDependentResource(attempted);

        updateStack(attempted, "UPDATE_ROLLBACK_COMPLETE");

        assertServer(poolA, IDENTIFIER, "Original API", "read");
        assertServerGone(poolB, IDENTIFIER);
        assertEquals(IDENTIFIER, output("ServerRef"));
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
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template.toString());
        }
        request.when().post("/").then().statusCode(200);
    }

    private String output(String key) {
        String xml = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stack)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private ObjectNode template(String pool, String identifier, String name, String scope) {
        ObjectNode template = mapper.createObjectNode();
        ObjectNode resource = template.putObject("Resources").putObject("Server");
        resource.put("Type", "AWS::Cognito::UserPoolResourceServer");
        ObjectNode properties = resource.putObject("Properties");
        properties.put("UserPoolId", pool);
        properties.put("Identifier", identifier);
        properties.put("Name", name);
        if (scope != null) {
            properties.putArray("Scopes").addObject().put("ScopeName", scope).put("ScopeDescription", "Access");
        }
        template.putObject("Outputs").putObject("ServerRef").putObject("Value").put("Ref", "Server");
        return template;
    }

    private static void addFailingDependentResource(ObjectNode template) {
        ObjectNode broken = ((ObjectNode) template.path("Resources")).putObject("Broken");
        broken.put("Type", "AWS::Cognito::UserPoolClient");
        broken.put("DependsOn", "Server");
        broken.putObject("Properties").put("UserPoolId", "us-east-1_nonexistent").put("ClientName", "broken");
    }

    private static JsonNode server(String pool, String identifier) throws Exception {
        return cognitoJson("DescribeResourceServer", "{\"UserPoolId\":\"" + pool
                + "\",\"Identifier\":\"" + identifier + "\"}").path("ResourceServer");
    }

    private static void assertServer(String pool, String identifier, String name, String scope) throws Exception {
        JsonNode server = server(pool, identifier);
        assertEquals(identifier, server.path("Identifier").asText());
        assertEquals(name, server.path("Name").asText());
        assertEquals(1, server.path("Scopes").size());
        assertEquals(scope, server.path("Scopes").get(0).path("ScopeName").asText());
    }

    private static void assertServerGone(String pool, String identifier) {
        cognitoAction("DescribeResourceServer", "{\"UserPoolId\":\"" + pool
                + "\",\"Identifier\":\"" + identifier + "\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }
}
