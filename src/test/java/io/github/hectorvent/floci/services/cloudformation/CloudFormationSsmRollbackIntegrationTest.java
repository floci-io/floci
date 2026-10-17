package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * {@code AWS::SSM::Parameter} through a stack's update lifecycle: a later resource that fails the
 * update rolls an in-place change or a rename back, a committed rename deletes or retains the
 * parameter it displaced, a name held outside the stack is never overwritten or adopted, and a
 * rollback that fails is finished by ContinueUpdateRollback. A failing update adds a Secrets
 * Manager secret that cannot be created, after every parameter.
 */
@QuarkusTest
class CloudFormationSsmRollbackIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @InjectSpy
    SsmService ssmService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** One {@code AWS::SSM::Parameter} of the template, under its logical id. */
    private record Param(String logicalId, String name, String value, Map<String, String> tags, boolean retain) {
        Param(String logicalId, String name, String value) {
            this(logicalId, name, value, Map.of(), false);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUpdateRestoresTheParameterItsTagsAndItsIdentity(boolean rename) throws Exception {
        String stack = unique("ssm-rollback");
        String original = "/" + stack;
        String target = rename ? original + "-new" : original;
        deploy("CreateStack", stack, template(false,
                new Param("Parameter", original, "before", Map.of("team", "old", "dropped", "restore"), false)));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        ssm("AddTagsToResource", Map.of("ResourceType", "Parameter", "ResourceId", original,
                "Tags", List.of(Map.of("Key", "external", "Value", "keep")))).then().statusCode(200);
        // Settings the template does not manage, which the in-place write resets.
        ssm("PutParameter", Map.of("Name", original, "Value", "before", "Type", "String", "Overwrite", true,
                "Description", "set outside the stack", "AllowedPattern", "^[a-z]+$")).then().statusCode(200);

        deploy("UpdateStack", stack, template(true,
                new Param("Parameter", target, "after", Map.of("team", "new", "added", "remove"), false)));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
        assertEquals("before", value(original));
        assertEquals(Map.of("team", "old", "dropped", "restore", "external", "keep"), tags(original));
        Map<String, Object> described = describe(original);
        assertEquals("set outside the stack", described.get("Description"));
        assertEquals("^[a-z]+$", described.get("AllowedPattern"));
        assertEquals(Map.of("ParameterName", original), outputs(stack));
        assertEquals(original, physicalIds(stack).get("Parameter"));
        if (rename) {
            assertMissing(target);
        }
        deleteStack(stack);
        assertMissing(original);
    }

    @Test
    void severalParametersRollBackTogether() throws Exception {
        String stack = unique("ssm-several");
        String base = "/" + stack;
        deploy("CreateStack", stack, template(false,
                new Param("InPlace", base + "/in-place", "one"),
                new Param("Renamed", base + "/renamed", "one"),
                new Param("Untouched", base + "/untouched", "one")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());

        deploy("UpdateStack", stack, template(true,
                new Param("InPlace", base + "/in-place", "two"),
                new Param("Renamed", base + "/renamed-new", "two"),
                new Param("Untouched", base + "/untouched", "one"),
                new Param("Added", base + "/added", "two")));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
        assertEquals("one", value(base + "/in-place"));
        assertEquals("one", value(base + "/renamed"));
        assertMissing(base + "/renamed-new");
        assertMissing(base + "/added");
        assertEquals(1L, version(base + "/untouched"));
        assertEquals(Map.of("InPlaceName", base + "/in-place", "RenamedName", base + "/renamed",
                "UntouchedName", base + "/untouched"), outputs(stack));
        deleteStack(stack);
        assertMissing(base + "/in-place");
        assertMissing(base + "/renamed");
        assertMissing(base + "/untouched");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCommittedRenameAppliesItsReplacePolicy(boolean retain) throws Exception {
        String stack = unique("ssm-replace");
        String original = "/" + stack;
        String target = original + "-new";
        deploy("CreateStack", stack, template(false, new Param("Parameter", original, "before", Map.of(), retain)));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());

        deploy("UpdateStack", stack, template(false, new Param("Parameter", target, "after", Map.of(), retain)));

        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("after", value(target));
        List<String> cleanup = events(stack).stream()
                .filter(event -> original.equals(event.get("PhysicalResourceId")))
                .map(event -> event.get("ResourceStatus"))
                .toList();
        if (retain) {
            assertEquals("before", value(original));
            assertFalse(cleanup.contains("DELETE_IN_PROGRESS"), cleanup.toString());
        } else {
            assertMissing(original);
            assertTrue(cleanup.containsAll(List.of("DELETE_IN_PROGRESS", "DELETE_COMPLETE")), cleanup.toString());
        }
        deleteStack(stack);
        assertMissing(target);
        if (retain) {
            assertEquals("before", value(original));
            ssm("DeleteParameter", Map.of("Name", original)).then().statusCode(200);
        }
    }

    @Test
    void aRenameOntoAParameterHeldOutsideTheStackFailsWithoutAdoptingIt() throws Exception {
        String stack = unique("ssm-collision");
        String original = "/" + stack;
        String target = original + "-foreign";
        deploy("CreateStack", stack, template(false, new Param("Parameter", original, "before")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        ssm("PutParameter", Map.of("Name", target, "Value", "foreign", "Type", "String")).then().statusCode(200);

        deploy("UpdateStack", stack, template(false, new Param("Parameter", target, "after")));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
        assertTrue(updateFailure(stack).contains("already exists"), updateFailure(stack));
        assertEquals("before", value(original));
        assertEquals("foreign", value(target));
        assertEquals(1L, version(target));
        deleteStack(stack);
        assertMissing(original);
        assertEquals("foreign", value(target));
        ssm("DeleteParameter", Map.of("Name", target)).then().statusCode(200);
    }

    @Test
    void aCreateOntoAParameterHeldOutsideTheStackFailsWithoutOverwritingIt() throws Exception {
        String stack = unique("ssm-create-collision");
        String name = "/" + stack;
        ssm("PutParameter", Map.of("Name", name, "Value", "foreign", "Type", "String")).then().statusCode(200);

        deploy("CreateStack", stack, template(false, new Param("Parameter", name, "managed")));

        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("foreign", value(name));
        assertEquals(1L, version(name));
        deleteStack(stack);
        assertEquals("foreign", value(name));
        ssm("DeleteParameter", Map.of("Name", name)).then().statusCode(200);
    }

    @Test
    void aSecondUpdateRollsBackToTheLastCommittedValue() throws Exception {
        String stack = unique("ssm-two-updates");
        String name = "/" + stack;
        deploy("CreateStack", stack, template(false, new Param("Parameter", name, "one")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        deploy("UpdateStack", stack, template(false, new Param("Parameter", name, "two")));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());

        deploy("UpdateStack", stack, template(true, new Param("Parameter", name, "three")));

        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("two", value(name));
        deleteStack(stack);
        assertMissing(name);
    }

    @Test
    void continueUpdateRollbackRetriesARestoreThatFailed() throws Exception {
        String stack = unique("ssm-retry-restore");
        String name = "/" + stack;
        deploy("CreateStack", stack, template(false, new Param("Parameter", name, "before")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        failRestoringOnce(name, "before");

        deploy("UpdateStack", stack, template(true, new Param("Parameter", name, "after")));

        CfnStackWaits.StackState failed = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_ROLLBACK_FAILED", failed.status(), failed.reason());
        assertTrue(failed.reason().contains("Parameter"), failed.reason());
        assertEquals("after", value(name));

        continueUpdateRollback(stack, null);

        CfnStackWaits.StackState retried = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", retried.status(), retried.reason());
        assertEquals("before", value(name));
        deleteStack(stack);
        assertMissing(name);
    }

    @Test
    void continueUpdateRollbackRetriesAReplacementDeleteThatFailed() throws Exception {
        String stack = unique("ssm-retry-delete");
        String original = "/" + stack;
        String target = original + "-new";
        deploy("CreateStack", stack, template(false, new Param("Parameter", original, "before")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        failDeleting(target);
        try {
            deploy("UpdateStack", stack, template(true, new Param("Parameter", target, "after")));

            CfnStackWaits.StackState failed = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_ROLLBACK_FAILED", failed.status(), failed.reason());
            assertEquals(original, physicalIds(stack).get("Parameter"));
            assertEquals("after", value(target));
        } finally {
            Mockito.doCallRealMethod().when(ssmService).deleteParameter(eq(target), anyString());
        }

        continueUpdateRollback(stack, null);

        CfnStackWaits.StackState retried = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", retried.status(), retried.reason());
        assertMissing(target);
        assertEquals("before", value(original));
        deleteStack(stack);
        assertMissing(original);
    }

    /**
     * A skipped resource keeps the state the failed update left it in, and the next update starts
     * from that state: it neither fails over the skipped rollback nor rolls back to before it.
     */
    @Test
    void aSkippedRestoreDoesNotHoldBackTheNextUpdate() throws Exception {
        String stack = unique("ssm-skip-restore");
        String name = "/" + stack;
        deploy("CreateStack", stack, template(false, new Param("Parameter", name, "one")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        failRestoringOnce(name, "one");
        deploy("UpdateStack", stack, template(true, new Param("Parameter", name, "two")));
        assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stack).status());

        continueUpdateRollback(stack, "Parameter");

        CfnStackWaits.StackState skipped = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", skipped.status(), skipped.reason());
        assertEquals("two", value(name));

        deploy("UpdateStack", stack, template(false, new Param("Parameter", name, "three")));
        CfnStackWaits.StackState updated = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());
        assertEquals("three", value(name));

        deploy("UpdateStack", stack, template(true, new Param("Parameter", name, "four")));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("three", value(name));
        deleteStack(stack);
        assertMissing(name);
    }

    @Test
    void aSkippedReplacementDeleteIsLeftToTheNextCommittedUpdate() throws Exception {
        String stack = unique("ssm-skip-delete");
        String original = "/" + stack;
        String target = original + "-new";
        deploy("CreateStack", stack, template(false, new Param("Parameter", original, "one")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        failDeleting(target);
        try {
            deploy("UpdateStack", stack, template(true, new Param("Parameter", target, "two")));
            assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stack).status());
            continueUpdateRollback(stack, "Parameter");
            CfnStackWaits.StackState skipped = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", skipped.status(), skipped.reason());
        } finally {
            Mockito.doCallRealMethod().when(ssmService).deleteParameter(eq(target), anyString());
        }
        assertEquals(original, physicalIds(stack).get("Parameter"));
        assertEquals("two", value(target));

        deploy("UpdateStack", stack, template(false, new Param("Parameter", original, "three")));

        CfnStackWaits.StackState updated = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());
        assertEquals("three", value(original));
        assertMissing(target);
        deleteStack(stack);
        assertMissing(original);
    }

    @Test
    void deleteStackAfterAFailedRenameRollbackDeletesBothParameters() throws Exception {
        String stack = unique("ssm-delete-failed");
        String original = "/" + stack;
        String target = original + "-new";
        deploy("CreateStack", stack, template(false, new Param("Parameter", original, "before")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        failDeleting(target);
        try {
            deploy("UpdateStack", stack, template(true, new Param("Parameter", target, "after")));
            assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stack).status());
        } finally {
            Mockito.doCallRealMethod().when(ssmService).deleteParameter(eq(target), anyString());
        }

        deleteStack(stack);

        assertMissing(original);
        assertMissing(target);
    }

    /** As on AWS the update still completes: the displaced parameter is reported and left in place. */
    @Test
    void aDisplacedParameterThatCannotBeDeletedIsReportedAndAbandoned() throws Exception {
        String stack = unique("ssm-cleanup-failed");
        String original = "/" + stack;
        String target = original + "-new";
        deploy("CreateStack", stack, template(false, new Param("Parameter", original, "before")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        failDeleting(original);
        try {
            deploy("UpdateStack", stack, template(false, new Param("Parameter", target, "after")));

            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
            assertEquals("The following resource(s) could not be deleted during update cleanup: [Parameter ("
                    + original + ")].", state.reason());
            assertTrue(events(stack).stream().anyMatch(event -> original.equals(event.get("PhysicalResourceId"))
                    && "DELETE_FAILED".equals(event.get("ResourceStatus"))));
        } finally {
            Mockito.doCallRealMethod().when(ssmService).deleteParameter(eq(original), anyString());
        }
        assertEquals("before", value(original));

        deploy("UpdateStack", stack, template(false, new Param("Parameter", target, "next")));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("before", value(original));
        deleteStack(stack);
        assertMissing(target);
        assertEquals("before", value(original));
        ssm("DeleteParameter", Map.of("Name", original)).then().statusCode(200);
    }

    @Test
    void deleteStackToleratesAParameterDeletedOutsideTheStack() throws Exception {
        String stack = unique("ssm-gone-delete");
        String name = "/" + stack;
        deploy("CreateStack", stack, template(false, new Param("Parameter", name, "value")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        ssm("DeleteParameter", Map.of("Name", name)).then().statusCode(200);

        deleteStack(stack);
    }

    /**
     * As on AWS, an update in place checks that the parameter exists, so one deleted outside the stack
     * fails the update before anything is written, and the rollback has nothing to undo. A rename
     * never reads the prior parameter, so it succeeds.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUpdateOfAParameterDeletedOutsideTheStack(boolean rename) throws Exception {
        String stack = unique("ssm-gone-update");
        String original = "/" + stack;
        String target = rename ? original + "-new" : original;
        deploy("CreateStack", stack, template(false, new Param("Parameter", original, "before")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        ssm("DeleteParameter", Map.of("Name", original)).then().statusCode(200);

        deploy("UpdateStack", stack, template(false, new Param("Parameter", target, "after")));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        if (rename) {
            assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
            assertEquals("after", value(target));
        } else {
            assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
            assertTrue(updateFailure(stack).contains("not found"), updateFailure(stack));
        }
        assertMissing(original);
        deleteStack(stack);
        assertMissing(target);
    }

    /**
     * An update that fails before it writes leaves the stack's resource as it was, its DeletionPolicy
     * included: dropping Retain in the failed template does not let DeleteStack delete the parameter.
     */
    @Test
    void aFailedUpdateOfADeletedParameterKeepsItsDeletionPolicy() throws Exception {
        String stack = unique("ssm-gone-retain");
        String name = "/" + stack;
        deploy("CreateStack", stack, retainTemplate(name, "before", true));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        ssm("DeleteParameter", Map.of("Name", name)).then().statusCode(200);

        deploy("UpdateStack", stack, retainTemplate(name, "after", false));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
        ssm("PutParameter", Map.of("Name", name, "Value", "recreated", "Type", "String")).then().statusCode(200);
        deleteStack(stack);
        assertEquals("recreated", value(name));
        ssm("DeleteParameter", Map.of("Name", name)).then().statusCode(200);
    }

    /**
     * The rollback reads the parameter before it writes, as AWS's update handler does, so one that is
     * gone by then fails the rollback instead of being re-created; the retry restores it once it is back.
     */
    @Test
    void aRollbackThatFindsTheParameterGoneFailsUntilItIsBack() throws Exception {
        String stack = unique("ssm-gone-rollback");
        String name = "/" + stack;
        deploy("CreateStack", stack, template(false, new Param("Parameter", name, "before")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        // The update's own read sees the parameter; the rollback's read finds it gone.
        Mockito.doCallRealMethod()
                .doThrow(new AwsException("ParameterNotFound", "Parameter " + name + " not found.", 400))
                .when(ssmService).getParameter(eq(name), anyString());
        try {
            deploy("UpdateStack", stack, template(true, new Param("Parameter", name, "after")));

            CfnStackWaits.StackState failed = CfnStackWaits.awaitTerminal(stack);
            assertEquals("UPDATE_ROLLBACK_FAILED", failed.status(), failed.reason());
            assertTrue(updateFailure(stack).contains("not found"), updateFailure(stack));
        } finally {
            Mockito.doCallRealMethod().when(ssmService).getParameter(eq(name), anyString());
        }
        assertEquals("after", value(name));

        continueUpdateRollback(stack, null);

        CfnStackWaits.StackState retried = CfnStackWaits.awaitTerminal(stack);
        assertEquals("UPDATE_ROLLBACK_COMPLETE", retried.status(), retried.reason());
        assertEquals("before", value(name));
        deleteStack(stack);
        assertMissing(name);
    }

    /** The first restore of {@code name} to {@code priorValue} fails; a retry reaches the service. */
    private void failRestoringOnce(String name, String priorValue) {
        Mockito.doThrow(new AwsException("InternalServerError", "simulated restore failure", 500))
                .doCallRealMethod()
                .when(ssmService)
                .putParameter(eq(name), eq(priorValue), any(), any(), eq(true), any(), any(), any(), any(), any(),
                        anyString());
    }

    private void failDeleting(String name) {
        Mockito.doThrow(new AwsException("InternalServerError", "simulated delete failure", 500))
                .when(ssmService)
                .deleteParameter(eq(name), anyString());
    }

    private static String template(boolean fail, Param... params) throws JsonProcessingException {
        Map<String, Object> resources = new LinkedHashMap<>();
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (Param param : params) {
            resources.put(param.logicalId(), Map.of("Type", "AWS::SSM::Parameter",
                    "UpdateReplacePolicy", param.retain() ? "Retain" : "Delete",
                    "Properties", Map.of("Name", param.name(), "Value", param.value(), "Type", "String",
                            "Tags", param.tags())));
            outputs.put(param.logicalId() + "Name", Map.of("Value", Map.of("Ref", param.logicalId())));
        }
        if (fail) {
            resources.put("Failure", Map.of("Type", "AWS::SecretsManager::Secret",
                    "DependsOn", List.of(params).stream().map(Param::logicalId).toList(),
                    "Properties", Map.of("SecretString", "explicit",
                            "GenerateSecretString", Map.of("PasswordLength", 32))));
        }
        return MAPPER.writeValueAsString(Map.of("Resources", resources, "Outputs", outputs));
    }

    private static String retainTemplate(String name, String value, boolean retain) throws JsonProcessingException {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("Type", "AWS::SSM::Parameter");
        if (retain) {
            parameter.put("DeletionPolicy", "Retain");
        }
        parameter.put("Properties", Map.of("Name", name, "Value", value, "Type", "String"));
        return MAPPER.writeValueAsString(Map.of("Resources", Map.of("Parameter", parameter)));
    }

    private static void deploy(String action, String stack, String template) {
        cfn(action, stack).formParam("TemplateBody", template).post("/").then().statusCode(200);
    }

    private static void continueUpdateRollback(String stack, String skip) {
        RequestSpecification request = cfn("ContinueUpdateRollback", stack);
        if (skip != null) {
            request.formParam("ResourcesToSkip.member.1", skip);
        }
        request.post("/").then().statusCode(200);
    }

    private static void deleteStack(String stack) {
        cfn("DeleteStack", stack).post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static Map<String, String> outputs(String stack) {
        String body = cfn("DescribeStacks", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "Outputs", "OutputKey", "OutputValue");
    }

    private static Map<String, String> physicalIds(String stack) {
        String body = cfn("DescribeStackResources", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "StackResources", "LogicalResourceId", "PhysicalResourceId");
    }

    private static List<Map<String, String>> events(String stack) {
        String body = cfn("DescribeStackEvents", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractGroups(body, "member");
    }

    /** Why the {@code Parameter} resource failed the update, from its UPDATE_FAILED event. */
    private static String updateFailure(String stack) {
        return events(stack).stream()
                .filter(event -> "Parameter".equals(event.get("LogicalResourceId"))
                        && "UPDATE_FAILED".equals(event.get("ResourceStatus")))
                .map(event -> event.get("ResourceStatusReason"))
                .findFirst()
                .orElse("");
    }

    private static RequestSpecification cfn(String action, String stack) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action).formParam("StackName", stack);
    }

    private static Response ssm(String operation, Map<String, ?> body) throws JsonProcessingException {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonSSM." + operation).body(MAPPER.writeValueAsString(body)).post("/");
    }

    private static String value(String name) throws JsonProcessingException {
        return ssm("GetParameter", Map.of("Name", name)).then().statusCode(200)
                .extract().jsonPath().getString("Parameter.Value");
    }

    private static long version(String name) throws JsonProcessingException {
        return ssm("GetParameter", Map.of("Name", name)).then().statusCode(200)
                .extract().jsonPath().getLong("Parameter.Version");
    }

    private static Map<String, String> tags(String name) throws JsonProcessingException {
        List<Map<String, String>> tags = ssm("ListTagsForResource",
                Map.of("ResourceType", "Parameter", "ResourceId", name))
                .then().statusCode(200).extract().jsonPath().getList("TagList");
        return tags.stream().collect(Collectors.toMap(tag -> tag.get("Key"), tag -> tag.get("Value")));
    }

    private static Map<String, Object> describe(String name) throws JsonProcessingException {
        List<Map<String, Object>> parameters = ssm("DescribeParameters", Map.of("ParameterFilters",
                List.of(Map.of("Key", "Name", "Option", "Equals", "Values", List.of(name)))))
                .then().statusCode(200).extract().jsonPath().getList("Parameters");
        assertEquals(1, parameters.size(), parameters.toString());
        return parameters.get(0);
    }

    private static void assertMissing(String name) throws JsonProcessingException {
        Response response = ssm("GetParameter", Map.of("Name", name));
        assertEquals(400, response.statusCode(), response.asString());
        assertTrue(response.asString().contains("ParameterNotFound"), response.asString());
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }
}
