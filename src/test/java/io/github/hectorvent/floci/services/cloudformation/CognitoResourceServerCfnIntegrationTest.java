package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
class CognitoResourceServerCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String IDENTIFIER = "https://api.example.com";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> poolIds = new ArrayList<>();
    private final String stack = "cognito-resource-server-" + Long.toString(System.nanoTime(), 36);
    private boolean createdStack;

    @InjectSpy
    CognitoService cognitoService;

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
        JsonNode described = server(poolA, IDENTIFIER);
        assertFalse(described.has("incarnationId"));
        assertFalse(described.has("IncarnationId"));
        JsonNode listed = cognitoJson("ListResourceServers", "{\"UserPoolId\":\"" + poolA + "\"}")
                .path("ResourceServers").get(0);
        assertFalse(listed.has("incarnationId"));
        assertFalse(listed.has("IncarnationId"));

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
    void updatingAnOutOfBandRecreatedServerRestoresTheTemplateConfiguration() throws Exception {
        String pool = createPool();
        createStack(template(pool, IDENTIFIER, "Original API", "read"));
        deleteServer(pool);
        createServer(pool, "Out of band API", "admin");

        updateStack(template(pool, IDENTIFIER, "Template API", "write"), "UPDATE_COMPLETE");

        assertEquals(IDENTIFIER, output("ServerRef"));
        assertServer(pool, IDENTIFIER, "Template API", "write");
    }

    @Test
    void deletingAStackDeletesTheOutOfBandRecreatedServerAtItsManagedAddress() throws Exception {
        String pool = createPool();
        createStack(template(pool, IDENTIFIER, "Original API", "read"));
        deleteServer(pool);
        createServer(pool, "Out of band API", "admin");

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
        createdStack = false;

        assertServerGone(pool, IDENTIFIER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laterStackOperationsDoNotDeleteAnOldServerAbandonedAfterThreeFailures(boolean anotherUpdate)
            throws Exception {
        String originalPool = createPool();
        String replacementPool = createPool();
        createStack(template(originalPool, IDENTIFIER, "Original API", "read"));
        doThrow(new AwsException("InternalErrorException", "temporary old server delete failure", 500))
                .when(cognitoService).deleteResourceServer(originalPool, IDENTIFIER);
        try {
            updateStack(template(replacementPool, IDENTIFIER, "Replacement API", "write"), "UPDATE_COMPLETE");
            verify(cognitoService, times(3)).deleteResourceServer(originalPool, IDENTIFIER);
            assertServer(originalPool, IDENTIFIER, "Original API", "read");
            assertServer(replacementPool, IDENTIFIER, "Replacement API", "write");
        } finally {
            doCallRealMethod().when(cognitoService).deleteResourceServer(originalPool, IDENTIFIER);
        }

        ObjectNode outsideStack = mapper.createObjectNode().put("UserPoolId", originalPool)
                .put("Identifier", IDENTIFIER).put("Name", "Managed outside stack");
        outsideStack.putArray("Scopes").addObject().put("ScopeName", "read").put("ScopeDescription", "Access");
        cognitoAction("UpdateResourceServer", outsideStack.toString()).then().statusCode(200);
        if (anotherUpdate) {
            updateStack(template(replacementPool, IDENTIFIER, "Later API", "admin"), "UPDATE_COMPLETE");
            assertServer(replacementPool, IDENTIFIER, "Later API", "admin");
            assertServer(originalPool, IDENTIFIER, "Managed outside stack", "read");
        }
        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
        createdStack = false;
        assertServerGone(replacementPool, IDENTIFIER);
        assertServer(originalPool, IDENTIFIER, "Managed outside stack", "read");
        verify(cognitoService, times(3)).deleteResourceServer(originalPool, IDENTIFIER);
    }

    @Test
    void changingOnlyTheConditionClearsAndRestoresOptionalScopes() throws Exception {
        String pool = createPool();
        createStack(conditionalScopeTemplate(pool, false, false));
        assertEquals(0, server(pool, IDENTIFIER).path("Scopes").size());

        updateStack(conditionalScopeTemplate(pool, true, false), "UPDATE_COMPLETE");
        assertServer(pool, IDENTIFIER, "Conditional API", "read");

        updateStack(conditionalScopeTemplate(pool, false, false), "UPDATE_COMPLETE");
        assertEquals(0, server(pool, IDENTIFIER).path("Scopes").size());
        assertEquals(IDENTIFIER, output("ServerRef"));

        updateStack(conditionalScopeTemplate(pool, true, false), "UPDATE_COMPLETE");
        assertServer(pool, IDENTIFIER, "Conditional API", "read");
    }

    @Test
    void changingOnlyTheConditionOmitsOneScopeAndPreservesTheOther() throws Exception {
        String pool = createPool();
        createStack(conditionalScopeTemplate(pool, true, true));
        assertEquals(2, server(pool, IDENTIFIER).path("Scopes").size());
        assertEquals("write", server(pool, IDENTIFIER).path("Scopes").get(1).path("ScopeName").asText());

        updateStack(conditionalScopeTemplate(pool, false, true), "UPDATE_COMPLETE");
        assertServer(pool, IDENTIFIER, "Conditional API", "read");

        updateStack(conditionalScopeTemplate(pool, true, true), "UPDATE_COMPLETE");
        assertEquals(2, server(pool, IDENTIFIER).path("Scopes").size());
        assertEquals("write", server(pool, IDENTIFIER).path("Scopes").get(1).path("ScopeName").asText());
        assertEquals(IDENTIFIER, output("ServerRef"));
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

    @Test
    void deletingAFailedRollbackDeletesARecreatedServerAtThePendingCleanupAddress() throws Exception {
        String originalPool = createPool();
        String replacementPool = createPool();
        createStack(template(originalPool, IDENTIFIER, "Original API", "read"));
        doThrow(new AwsException("InternalErrorException", "temporary replacement delete failure", 500))
                .when(cognitoService).deleteResourceServer(replacementPool, IDENTIFIER);
        try {
            ObjectNode attempted = template(replacementPool, IDENTIFIER, "Failed replacement", "write");
            addFailingDependentResource(attempted);
            updateStack(attempted, "UPDATE_ROLLBACK_FAILED");
            assertServer(originalPool, IDENTIFIER, "Original API", "read");
            assertServer(replacementPool, IDENTIFIER, "Failed replacement", "write");
        } finally {
            doCallRealMethod().when(cognitoService).deleteResourceServer(replacementPool, IDENTIFIER);
        }

        cognitoAction("DeleteResourceServer", mapper.createObjectNode().put("UserPoolId", replacementPool)
                .put("Identifier", IDENTIFIER).toString()).then().statusCode(200);
        assertServerGone(replacementPool, IDENTIFIER);
        String secondStack = stack + "-new-owner";
        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                .formParam("Action", "CreateStack").formParam("StackName", secondStack)
                .formParam("TemplateBody", template(replacementPool, IDENTIFIER, "Second stack API", "admin").toString())
                .post("/").then().statusCode(200);
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(secondStack).status());
            assertServer(replacementPool, IDENTIFIER, "Second stack API", "admin");

            cloudFormation("DeleteStack", null);
            CfnStackWaits.awaitStackDeleted(stack);
            createdStack = false;
            assertServerGone(originalPool, IDENTIFIER);
            assertServerGone(replacementPool, IDENTIFIER);
        } finally {
            given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                    .formParam("Action", "DeleteStack").formParam("StackName", secondStack)
                    .post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(secondStack);
        }
    }

    @Test
    void pendingInPlaceRestorationRecoversTheTemplateAfterOutOfBandRecreation() throws Exception {
        String pool = createPool();
        createStack(template(pool, IDENTIFIER, "Original API", "read"));
        doThrow(new AwsException("InternalErrorException", "temporary restore failure", 500))
                .when(cognitoService).updateResourceServer(eq(pool), eq(IDENTIFIER), eq("Original API"), any());
        try {
            ObjectNode attempted = template(pool, IDENTIFIER, "Failed attempt", "write");
            addFailingDependentResource(attempted);
            updateStack(attempted, "UPDATE_ROLLBACK_FAILED");
        } finally {
            doCallRealMethod().when(cognitoService)
                    .updateResourceServer(eq(pool), eq(IDENTIFIER), eq("Original API"), any());
        }
        deleteServer(pool);
        createServer(pool, "Out of band API", "admin");

        ObjectNode nextAttempt = template(pool, IDENTIFIER, "Next attempt", "write");
        addFailingDependentResource(nextAttempt);
        nextAttempt.withObject("/Resources/Broken/Properties").put("ClientName", "second-broken");
        updateStack(nextAttempt, "UPDATE_ROLLBACK_COMPLETE");
        assertServer(pool, IDENTIFIER, "Original API", "read");
        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
        createdStack = false;
        assertServerGone(pool, IDENTIFIER);
    }

    @Test
    void aFreshReplacementAtAnOldCleanupAddressKeepsItsOwnDeletionResponsibility() throws Exception {
        String originalPool = createPool();
        String replacementPool = createPool();
        createStack(template(originalPool, IDENTIFIER, "Original API", "read"));
        doThrow(new AwsException("InternalErrorException", "temporary replacement delete failure", 500))
                .when(cognitoService).deleteResourceServer(replacementPool, IDENTIFIER);
        try {
            ObjectNode firstAttempt = template(replacementPool, IDENTIFIER, "First attempt", "write");
            addFailingDependentResource(firstAttempt);
            updateStack(firstAttempt, "UPDATE_ROLLBACK_FAILED");
            assertServer(replacementPool, IDENTIFIER, "First attempt", "write");
        } finally {
            doCallRealMethod().when(cognitoService).deleteResourceServer(replacementPool, IDENTIFIER);
        }
        deleteServer(replacementPool);
        doAnswer(invocation -> {
            cognitoService.describeResourceServer(replacementPool, IDENTIFIER);
            throw new AwsException("InternalErrorException", "new replacement delete failure", 500);
        }).when(cognitoService).deleteResourceServer(replacementPool, IDENTIFIER);
        try {
            ObjectNode secondAttempt = template(replacementPool, IDENTIFIER, "Second attempt", "admin");
            addFailingDependentResource(secondAttempt);
            secondAttempt.withObject("/Resources/Broken/Properties").put("ClientName", "second-broken");
            updateStack(secondAttempt, "UPDATE_ROLLBACK_FAILED");
            assertServer(originalPool, IDENTIFIER, "Original API", "read");
            assertServer(replacementPool, IDENTIFIER, "Second attempt", "admin");
        } finally {
            doCallRealMethod().when(cognitoService).deleteResourceServer(replacementPool, IDENTIFIER);
        }
        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack);
        createdStack = false;
        assertServerGone(originalPool, IDENTIFIER);
        assertServerGone(replacementPool, IDENTIFIER);
    }

    @Test
    void aPendingRollbackSurvivesFailedUpdatesUntilItCanBeRestored() throws Exception {
        String pool = createPool();
        createStack(template(pool, IDENTIFIER, "Original API", "read"));
        doThrow(new AwsException("InternalErrorException", "temporary restore failure", 500))
                .when(cognitoService).updateResourceServer(eq(pool), eq(IDENTIFIER), eq("Original API"), any());

        try {
            ObjectNode firstAttempt = template(pool, IDENTIFIER, "First attempt", "write");
            addFailingDependentResource(firstAttempt);
            updateStack(firstAttempt, "UPDATE_ROLLBACK_FAILED");
            assertServer(pool, IDENTIFIER, "First attempt", "write");

            ObjectNode nextAttempt = template(pool, IDENTIFIER, "Next attempt", "admin");
            addFailingDependentResource(nextAttempt);
            nextAttempt.withObject("/Resources/Broken/Properties").put("ClientName", "broken-next");
            updateStack(nextAttempt, "UPDATE_ROLLBACK_FAILED");
            assertServer(pool, IDENTIFIER, "First attempt", "write");

            doCallRealMethod().when(cognitoService)
                    .updateResourceServer(eq(pool), eq(IDENTIFIER), eq("Original API"), any());
            ObjectNode recoveryAttempt = template(pool, IDENTIFIER, "Recovery attempt", "write");
            addFailingDependentResource(recoveryAttempt);
            recoveryAttempt.withObject("/Resources/Broken/Properties").put("ClientName", "broken-recovery");
            updateStack(recoveryAttempt, "UPDATE_ROLLBACK_COMPLETE");
            assertServer(pool, IDENTIFIER, "Original API", "read");
            assertEquals(IDENTIFIER, output("ServerRef"));
        } finally {
            doCallRealMethod().when(cognitoService)
                    .updateResourceServer(eq(pool), eq(IDENTIFIER), eq("Original API"), any());
        }
    }

    @Test
    void skippingAPendingRollbackPreservesRestorationAndBlocksAnotherUpdate() throws Exception {
        String pool = createPool();
        createStack(template(pool, IDENTIFIER, "Original API", "read"));
        doThrow(new AwsException("InternalErrorException", "temporary restore failure", 500))
                .when(cognitoService).updateResourceServer(eq(pool), eq(IDENTIFIER), eq("Original API"), any());

        try {
            ObjectNode firstAttempt = template(pool, IDENTIFIER, "First attempt", "write");
            addFailingDependentResource(firstAttempt);
            updateStack(firstAttempt, "UPDATE_ROLLBACK_FAILED");
            assertServer(pool, IDENTIFIER, "First attempt", "write");

            ObjectNode outputsOnly = firstAttempt.deepCopy();
            outputsOnly.withObject("/Resources").remove("Broken");
            outputsOnly.withObject("/Outputs").putObject("Marker").put("Value", "unchanged-resource");
            cloudFormation("UpdateStack", outputsOnly);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertEquals("UPDATE_COMPLETE_CLEANUP_IN_PROGRESS", stackStatus(),
                            "Skipping the resource must not discard its pending restoration"));
            assertServer(pool, IDENTIFIER, "First attempt", "write");
            assertEquals(IDENTIFIER, output("ServerRef"));

            ObjectNode nextAttempt = template(pool, IDENTIFIER, "Next attempt", "admin");
            String error = given().contentType("application/x-www-form-urlencoded")
                    .header("Authorization", CFN_AUTH).formParam("Action", "UpdateStack")
                    .formParam("StackName", stack).formParam("TemplateBody", nextAttempt.toString())
                    .post("/").then().statusCode(400).extract().asString();
            assertEquals("ValidationError", XmlParser.extractFirst(error, "Code", null));
            assertTrue(XmlParser.extractFirst(error, "Message", "")
                    .contains("UPDATE_COMPLETE_CLEANUP_IN_PROGRESS"));
            assertEquals("UPDATE_COMPLETE_CLEANUP_IN_PROGRESS", stackStatus());
            assertServer(pool, IDENTIFIER, "First attempt", "write");

            cloudFormation("DeleteStack", null);
            CfnStackWaits.awaitStackDeleted(stack);
            createdStack = false;
            assertServerGone(pool, IDENTIFIER);
        } finally {
            doCallRealMethod().when(cognitoService)
                    .updateResourceServer(eq(pool), eq(IDENTIFIER), eq("Original API"), any());
        }
    }

    @Test
    void aNameOnlyChangeSetUpdatesTheExistingServerWithoutReplacement() throws Exception {
        String pool = createPool();
        createStack(template(pool, IDENTIFIER, "Original API", "read"));
        changeSet("CreateChangeSet", "rename-display", template(pool, IDENTIFIER, "Renamed API", "read"));
        Map<String, String> change = resourceChange(changeSet("DescribeChangeSet", "rename-display", null));
        assertEquals("Modify", change.get("Action"));
        assertEquals("False", change.get("Replacement"));

        changeSet("ExecuteChangeSet", "rename-display", null);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertServer(pool, IDENTIFIER, "Renamed API", "read");
        assertEquals(IDENTIFIER, output("ServerRef"));
    }

    @Test
    void deletingAPendingRollbackKeepsTrackingAfterADeleteFailureAndCanBeRetried() throws Exception {
        String pool = createPool();
        createStack(template(pool, IDENTIFIER, "Original API", "read"));
        doThrow(new AwsException("InternalErrorException", "temporary restore failure", 500))
                .when(cognitoService).updateResourceServer(eq(pool), eq(IDENTIFIER), eq("Original API"), any());
        doThrow(new AwsException("InternalErrorException", "temporary delete failure", 500))
                .when(cognitoService).deleteResourceServer(pool, IDENTIFIER);

        try {
            ObjectNode attempted = template(pool, IDENTIFIER, "Attempted API", "write");
            addFailingDependentResource(attempted);
            updateStack(attempted, "UPDATE_ROLLBACK_FAILED");
            assertServer(pool, IDENTIFIER, "Attempted API", "write");

            cloudFormation("DeleteStack", null);
            assertEquals("DELETE_FAILED", CfnStackWaits.awaitTerminal(stack).status());
            assertServer(pool, IDENTIFIER, "Attempted API", "write");

            doCallRealMethod().when(cognitoService).deleteResourceServer(pool, IDENTIFIER);
            cloudFormation("DeleteStack", null);
            CfnStackWaits.awaitStackDeleted(stack);
            createdStack = false;
            assertServerGone(pool, IDENTIFIER);
        } finally {
            doCallRealMethod().when(cognitoService)
                    .updateResourceServer(eq(pool), eq(IDENTIFIER), eq("Original API"), any());
            doCallRealMethod().when(cognitoService).deleteResourceServer(pool, IDENTIFIER);
        }
    }

    @Test
    void stackDeletionDoesNotRepeatExhaustedCleanupBeforeDeletingTheManagedServer() throws Exception {
        String originalPool = createPool();
        String replacementPool = createPool();
        createStack(template(originalPool, IDENTIFIER, "Original API", "read"));
        doThrow(new AwsException("InternalErrorException", "temporary replacement delete failure", 500))
                .when(cognitoService).deleteResourceServer(replacementPool, IDENTIFIER);
        try {
            ObjectNode attempted = template(replacementPool, IDENTIFIER, "Failed replacement", "write");
            addFailingDependentResource(attempted);
            updateStack(attempted, "UPDATE_ROLLBACK_FAILED");
            clearInvocations(cognitoService);

            cloudFormation("DeleteStack", null);
            assertEquals("DELETE_FAILED", CfnStackWaits.awaitTerminal(stack).status());
            verify(cognitoService, times(3)).deleteResourceServer(replacementPool, IDENTIFIER);
            assertServerGone(originalPool, IDENTIFIER);
            assertServer(replacementPool, IDENTIFIER, "Failed replacement", "write");

            doCallRealMethod().when(cognitoService).deleteResourceServer(replacementPool, IDENTIFIER);
            cloudFormation("DeleteStack", null);
            CfnStackWaits.awaitStackDeleted(stack);
            createdStack = false;
            assertServerGone(replacementPool, IDENTIFIER);
        } finally {
            doCallRealMethod().when(cognitoService).deleteResourceServer(replacementPool, IDENTIFIER);
        }
    }

    @Test
    void aPoolOnlyChangeSetReplacesTheServerEvenWhenItsRefStaysTheSame() throws Exception {
        String originalPool = createPool();
        String replacementPool = createPool();
        createStack(template(originalPool, IDENTIFIER, "Original API", "read"));
        changeSet("CreateChangeSet", "move-pool", template(replacementPool, IDENTIFIER, "Original API", "read"));
        Map<String, String> change = resourceChange(changeSet("DescribeChangeSet", "move-pool", null));
        assertEquals("Modify", change.get("Action"));
        assertEquals("True", change.get("Replacement"));

        changeSet("ExecuteChangeSet", "move-pool", null);
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertServerGone(originalPool, IDENTIFIER);
        assertServer(replacementPool, IDENTIFIER, "Original API", "read");
        assertEquals(IDENTIFIER, output("ServerRef"));
    }

    private String createPool() throws Exception {
        String id = cognitoJson("CreateUserPool", "{\"PoolName\":\"" + stack + "\"}")
                .path("UserPool").path("Id").asText();
        poolIds.add(id);
        return id;
    }

    private void deleteServer(String pool) {
        cognitoAction("DeleteResourceServer", mapper.createObjectNode().put("UserPoolId", pool)
                .put("Identifier", IDENTIFIER).toString()).then().statusCode(200);
    }

    private void createServer(String pool, String name, String scope) {
        ObjectNode request = mapper.createObjectNode().put("UserPoolId", pool).put("Identifier", IDENTIFIER).put("Name", name);
        request.putArray("Scopes").addObject().put("ScopeName", scope).put("ScopeDescription", "Access");
        cognitoAction("CreateResourceServer", request.toString()).then().statusCode(200);
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

    private String stackStatus() {
        String xml = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH).formParam("Action", "DescribeStacks")
                .formParam("StackName", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractFirst(xml, "StackStatus", null);
    }

    private String changeSet(String action, String name, ObjectNode template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH).formParam("Action", action)
                .formParam("StackName", stack).formParam("ChangeSetName", name);
        if (template != null) {
            request.formParam("ChangeSetType", "UPDATE").formParam("TemplateBody", template.toString());
        }
        return request.post("/").then().statusCode(200).extract().asString();
    }

    private static Map<String, String> resourceChange(String xml) {
        return XmlParser.extractGroups(xml, "ResourceChange").stream()
                .filter(change -> "Server".equals(change.get("LogicalResourceId")))
                .findFirst().orElseThrow();
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

    private ObjectNode conditionalScopeTemplate(String pool, boolean enabled, boolean entry) {
        ObjectNode template = template(pool, IDENTIFIER, "Conditional API", "read");
        template.putObject("Conditions").putObject("IncludeScopes")
                .putArray("Fn::Equals").add("enabled").add(enabled ? "enabled" : "disabled");
        ObjectNode properties = (ObjectNode) template.path("Resources").path("Server").path("Properties");
        ObjectNode conditional = mapper.createObjectNode();
        JsonNode included = entry
                ? mapper.createObjectNode().put("ScopeName", "write").put("ScopeDescription", "Write access")
                : properties.path("Scopes");
        conditional.putArray("Fn::If").add("IncludeScopes").add(included)
                .add(mapper.createObjectNode().put("Ref", "AWS::NoValue"));
        if (entry) {
            properties.withArray("Scopes").add(conditional);
        } else {
            properties.set("Scopes", conditional);
        }
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
