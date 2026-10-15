package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudwatch.dashboards.CloudWatchDashboardsService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A stack whose update rollback failed sits in UPDATE_ROLLBACK_FAILED. AWS refuses to update it;
 * the ways out are ContinueUpdateRollback, optionally skipping the resources that cannot roll
 * back, and DeleteStack. Each test reaches that state as a real deploy does: an update changes a
 * log group, which has no update rollback, then adds a second log group whose name collides with
 * one made outside the stack.
 *
 * <p>The test module has no AWS SDK CloudFormation client, so the Query protocol is driven with
 * form POSTs, as the other CloudFormation integration tests do.
 */
@QuarkusTest
class CloudFormationContinueUpdateRollbackIntegrationTest {

    private static final String SKIPPED = "Resource skipped during UpdateRollback";
    private static final String USER_INITIATED = "User Initiated";
    private static final String FORCED = "One or more resources were skipped during UpdateRollback."
            + " These resources were not rolled back and may have drifted from the template."
            + " Run drift detection (DetectStackDrift) to review their current state before your next update.";
    private static final String STACK_NAME_PATTERN = "[a-zA-Z][-a-zA-Z0-9]*|arn:[-a-zA-Z0-9:/._+]*";
    private static final String SKIP_PATTERN = "[a-zA-Z0-9]+|[a-zA-Z][-a-zA-Z0-9]*\\.[a-zA-Z0-9]+";
    private static final String NULL_STACK_NAME =
            "Value null at 'stackName' failed to satisfy constraint: Member must not be null";
    private static final String CW_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/monitoring/aws4_request";

    @InjectSpy
    CloudWatchDashboardsService dashboardsService;

    @Test
    void updateAndChangeSetAreRefusedWhileDeleteStillWorks() {
        RollbackFailedStack stack = rollbackFailedStack("cur-refuse-");
        try {
            cfn("UpdateStack", stack.name())
                .formParam("TemplateBody", template(stack, 14, false))
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("<Message>Stack:" + stack.id()
                        + " is in UPDATE_ROLLBACK_FAILED state and can not be updated.</Message>"));

            cfn("CreateChangeSet", stack.name())
                .formParam("ChangeSetName", "cur-refuse-cs")
                .formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template(stack, 14, false))
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("<Message>Stack:" + stack.id()
                        + " is in UPDATE_ROLLBACK_FAILED state and can not be updated.</Message>"));

            assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stack.name()).status());

            cfn("DeleteStack", stack.name()).when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStackDeleted(stack.name());
        } finally {
            cleanUp(stack);
        }
    }

    /** A log group cannot roll an update back, so retrying the rollback fails the same way again. */
    @Test
    void continueUpdateRollbackWithoutSkipsFailsAgain() {
        RollbackFailedStack stack = rollbackFailedStack("cur-retry-");
        try {
            cfn("ContinueUpdateRollback", stack.name())
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<ContinueUpdateRollbackResponse"
                        + " xmlns=\"http://cloudformation.amazonaws.com/doc/2010-05-15/\">"
                        + "<ContinueUpdateRollbackResult/><ResponseMetadata><RequestId>"));

            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack.name());
            assertEquals("UPDATE_ROLLBACK_FAILED", state.status(), state.reason());
            assertTrue(state.reason().contains("LogGroup"), state.reason());
            assertEquals("UPDATE_FAILED", resourceStatuses(stack.name()).get("LogGroup"));

            List<Map<String, String>> events = events(stack.id());
            List<Map<String, String>> rollbacks = events.stream()
                    .filter(event -> stack.name().equals(event.get("LogicalResourceId"))
                            && "UPDATE_ROLLBACK_IN_PROGRESS".equals(event.get("ResourceStatus")))
                    .toList();
            assertEquals(2, rollbacks.size(), events.toString());
            assertEquals(USER_INITIATED, rollbacks.get(0).get("ResourceStatusReason"), events.toString());

            // The retry restores nothing it could not restore the first time, so the failed
            // template stays.
            getTemplate(stack.name()).then().statusCode(200).body(containsString(stack.outsideGroup()));
        } finally {
            cleanUp(stack);
        }
    }

    @Test
    void skippingTheLogGroupCompletesTheRollbackAndUnblocksUpdates() {
        RollbackFailedStack stack = rollbackFailedStack("cur-skip-");
        try {
            cfn("ContinueUpdateRollback", stack.id())
                .formParam("ResourcesToSkip.member.1", "LogGroup")
                .formParam("RoleARN", "arn:aws:iam::000000000000:role/ignored")
                .formParam("ClientRequestToken", "cur-skip-token")
            .when()
                .post("/")
            .then()
                .statusCode(200);

            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack.name());
            assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
            cfn("DescribeStackResource", stack.name())
                .formParam("LogicalResourceId", "LogGroup")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<ResourceStatus>UPDATE_COMPLETE</ResourceStatus>"))
                .body(containsString("<ResourceStatusReason>" + SKIPPED + "</ResourceStatusReason>"));

            // Newest first: the skipped resource's UPDATE_COMPLETE follows the second rollback start.
            List<Map<String, String>> events = events(stack.id());
            boolean skipped = false;
            for (Map<String, String> event : events) {
                if (stack.name().equals(event.get("LogicalResourceId"))
                        && "UPDATE_ROLLBACK_IN_PROGRESS".equals(event.get("ResourceStatus"))) {
                    assertEquals(USER_INITIATED, event.get("ResourceStatusReason"), event.toString());
                    break;
                }
                if ("LogGroup".equals(event.get("LogicalResourceId"))
                        && "UPDATE_COMPLETE".equals(event.get("ResourceStatus"))
                        && SKIPPED.equals(event.get("ResourceStatusReason"))
                        && stack.logGroup().equals(event.get("PhysicalResourceId"))) {
                    skipped = true;
                }
            }
            assertTrue(skipped, "no skipped UPDATE_COMPLETE event for LogGroup in " + events);

            // The completed rollback restores the template from before the failed update.
            getTemplate(stack.name()).then().statusCode(200).body(not(containsString(stack.outsideGroup())));

            // The stack is no longer UPDATE_ROLLBACK_FAILED, so a second call is refused.
            assertContinueRefused(stack.name());

            cfn("UpdateStack", stack.name())
                .formParam("TemplateBody", template(stack, 14, false))
            .when()
                .post("/")
            .then()
                .statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack.name()).status());
        } finally {
            cleanUp(stack);
        }
    }

    @Test
    void continueUpdateRollbackIsRefusedOnAStackInAnotherState() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cur-state-" + suffix;
        String group = "/cur-state/" + suffix;
        try {
            cfn("CreateStack", stackName)
                .formParam("TemplateBody", "{\"Resources\":{\"LogGroup\":{\"Type\":\"AWS::Logs::LogGroup\","
                        + "\"Properties\":{\"LogGroupName\":\"" + group + "\"}}}}")
            .when().post("/").then().statusCode(200);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            assertContinueRefused(stackName);

            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        } finally {
            cfn("DeleteStack", stackName).when().post("/");
            logs("DeleteLogGroup", "{\"logGroupName\":\"" + group + "\"}");
        }
    }

    /**
     * AWS accepts a call with a bad skip and fails the rollback again with the reason, touching no
     * resource. What the retry needs is kept, so a later call with a good skip still finishes it.
     */
    @Test
    void aBadSkipFailsTheRollbackAgainWithoutTouchingAResource() {
        RollbackFailedStack stack = rollbackFailedStack("cur-badskip-");
        try {
            assertSkipFails(stack, "Missing",
                    "Resources with logicalIds [Missing] do not belong to stack " + stack.name());
            assertSkipFails(stack, "Stable", "Only the resources in UPDATE_FAILED state are allowed to be skipped");
            assertSkipFails(stack, "Nested.LogGroup", "Stack [Nested] does not exist");

            Map<String, String> statuses = resourceStatuses(stack.name());
            assertEquals("UPDATE_FAILED", statuses.get("LogGroup"), statuses.toString());
            assertEquals("CREATE_COMPLETE", statuses.get("Stable"), statuses.toString());

            cfn("ContinueUpdateRollback", stack.name())
                .formParam("ResourcesToSkip.member.1", "LogGroup")
            .when()
                .post("/")
            .then()
                .statusCode(200);
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack.name());
            assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
            getTemplate(stack.name()).then().statusCode(200).body(not(containsString(stack.outsideGroup())));
        } finally {
            cleanUp(stack);
        }
    }

    /**
     * ContinueUpdateRollback is a real retry: it runs the restore of a resource still owed one
     * again. A dashboard keeps its update snapshot until its restore succeeds, so a restore that
     * fails once is put right by the retry.
     */
    @Test
    void continueUpdateRollbackRetriesARestorationThatThrew() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cur-dashboard-retry-" + suffix;
        String dashboardName = "cur-dashboard-retry-" + suffix;
        try {
            cfn("CreateStack", stackName)
                .formParam("TemplateBody", dashboardTemplate(dashboardName, "Before", false))
            .when().post("/").then().statusCode(200);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            // The create wrote the pre-update body too; only the restores are counted below.
            Mockito.clearInvocations(dashboardsService);
            ArgumentMatcher<String> preUpdateBody = body -> body != null && body.contains("Before");
            Mockito.doThrow(new IllegalStateException("simulated restore failure"))
                    .doCallRealMethod()
                    .when(dashboardsService)
                    .putDashboard(eq(dashboardName), argThat(preUpdateBody), anyMap(), anyString());

            cfn("UpdateStack", stackName)
                .formParam("TemplateBody", dashboardTemplate(dashboardName, "After", true))
            .when().post("/").then().statusCode(200);
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("UPDATE_ROLLBACK_FAILED", state.status(), state.reason());
            assertEquals("UPDATE_FAILED", resourceStatuses(stackName).get("Dashboard"));

            cfn("ContinueUpdateRollback", stackName).when().post("/").then().statusCode(200);
            state = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
            assertEquals("CREATE_COMPLETE", resourceStatuses(stackName).get("Dashboard"));
            verify(dashboardsService, times(2))
                    .putDashboard(eq(dashboardName), argThat(preUpdateBody), anyMap(), anyString());

            String body = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CW_AUTH)
                .formParam("Action", "GetDashboard")
                .formParam("DashboardName", dashboardName)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("GetDashboardResponse.GetDashboardResult.DashboardBody");
            assertTrue(body.contains("Before") && !body.contains("After"), body);
        } finally {
            cfn("DeleteStack", stackName).when().post("/");
        }
    }

    @Test
    void aMissingStackNameIsAValidationError() {
        assertValidationError(cfn("ContinueUpdateRollback", null),
                "1 validation error detected: " + NULL_STACK_NAME);
    }

    @Test
    void anEmptyStackNameFailsBothItsLengthAndItsPattern() {
        assertValidationError(cfn("ContinueUpdateRollback", ""),
                "2 validation errors detected: Value '' at 'stackName' failed to satisfy constraint:"
                        + " Member must have length greater than or equal to 1; Value '' at 'stackName' failed"
                        + " to satisfy constraint: Member must satisfy regular expression pattern: "
                        + STACK_NAME_PATTERN);
    }

    @Test
    void aStackNameOutsideThePatternIsAValidationError() {
        assertValidationError(cfn("ContinueUpdateRollback", "bad_stack!"),
                "1 validation error detected: Value 'bad_stack!' at 'stackName' failed to satisfy constraint:"
                        + " Member must satisfy regular expression pattern: " + STACK_NAME_PATTERN);
    }

    /** One bad member rejects the whole list, before the stack, which does not exist, is looked up. */
    @Test
    void aSkipOutsideThePatternRejectsTheWholeList() {
        assertValidationError(cfn("ContinueUpdateRollback", "cur-no-such-stack")
                        .formParam("ResourcesToSkip.member.1", "CrA")
                        .formParam("ResourcesToSkip.member.2", "bad_id"),
                "1 validation error detected: " + badSkips("[CrA, bad_id]"));
    }

    @Test
    void aMissingStackNameAndABadSkipAreBothReportedStackNameFirst() {
        assertValidationError(cfn("ContinueUpdateRollback", null)
                        .formParam("ResourcesToSkip.member.1", "bad_id"),
                "2 validation errors detected: " + NULL_STACK_NAME + "; " + badSkips("[bad_id]"));
    }

    @Test
    void forceRollbackWithSkipsIsRefusedWithoutTouchingTheStack() {
        RollbackFailedStack stack = rollbackFailedStack("cur-force-skip-");
        try {
            List<Map<String, String>> eventsBefore = events(stack.id());

            assertValidationError(cfn("ContinueUpdateRollback", stack.name())
                            .formParam("ForceRollback", "true")
                            .formParam("ResourcesToSkip.member.1", "LogGroup"),
                    "ForceRollback and ResourcesToSkip are mutually exclusive");

            assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stack.name()).status());
            assertEquals(eventsBefore, events(stack.id()));
        } finally {
            cleanUp(stack);
        }
    }

    @Test
    void aStackThatDoesNotExistIsNamedAsGiven() {
        String stackName = "cur-missing-" + Long.toString(System.nanoTime(), 36);
        assertValidationError(cfn("ContinueUpdateRollback", stackName), "Stack: " + stackName + " does not exist");
    }

    /**
     * ForceRollback skips every UPDATE_FAILED resource, reporting each as a ResourcesToSkip skip
     * is, and the completed rollback says in the stack's reason that resources were skipped.
     */
    @Test
    void forceRollbackSkipsTheFailedResourcesAndUnblocksUpdates() {
        RollbackFailedStack stack = rollbackFailedStack("cur-force-");
        try {
            cfn("ContinueUpdateRollback", stack.name())
                .formParam("ForceRollback", "true")
            .when()
                .post("/")
            .then()
                .statusCode(200);

            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack.name());
            assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
            assertEquals(FORCED, state.reason());
            assertEquals("UPDATE_COMPLETE", resourceStatuses(stack.name()).get("LogGroup"));
            cfn("DescribeStackResource", stack.name())
                .formParam("LogicalResourceId", "LogGroup")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<ResourceStatus>UPDATE_COMPLETE</ResourceStatus>"))
                .body(containsString("<ResourceStatusReason>" + SKIPPED + "</ResourceStatusReason>"));

            // Newest first: the completion, then the skipped log group.
            List<Map<String, String>> events = events(stack.id());
            Map<String, String> completed = events.get(0);
            assertEquals(stack.name(), completed.get("LogicalResourceId"), events.toString());
            assertEquals("UPDATE_ROLLBACK_COMPLETE", completed.get("ResourceStatus"), events.toString());
            assertEquals(FORCED, completed.get("ResourceStatusReason"), events.toString());
            Map<String, String> skipped = events.get(1);
            assertEquals("LogGroup", skipped.get("LogicalResourceId"), events.toString());
            assertEquals("UPDATE_COMPLETE", skipped.get("ResourceStatus"), events.toString());
            assertEquals(SKIPPED, skipped.get("ResourceStatusReason"), events.toString());

            getTemplate(stack.name()).then().statusCode(200).body(not(containsString(stack.outsideGroup())));

            cfn("UpdateStack", stack.name())
                .formParam("TemplateBody", template(stack, 14, false))
            .when()
                .post("/")
            .then()
                .statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack.name()).status());
        } finally {
            cleanUp(stack);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String badSkips(String list) {
        return "Value '" + list + "' at 'resourcesToSkip' failed to satisfy constraint: Member must satisfy"
                + " constraint: [Member must satisfy regular expression pattern: " + SKIP_PATTERN + "]";
    }

    /** AWS rejects a malformed request with ValidationError before it looks the stack up. */
    private static void assertValidationError(RequestSpecification request, String message) {
        String body = request.when().post("/").then().statusCode(400).extract().asString();
        assertEquals("ValidationError", XmlParser.extractFirst(body, "Code", null), body);
        assertEquals(message, XmlParser.extractFirst(body, "Message", null), body);
    }

    private record RollbackFailedStack(String name, String id, String logGroup, String outsideGroup) {
    }

    /**
     * Creates a stack, then updates it so the log group changes and an added log group collides
     * with one created outside the stack. The update fails, the log group cannot roll back, and the
     * stack settles in UPDATE_ROLLBACK_FAILED with the log group UPDATE_FAILED.
     */
    private static RollbackFailedStack rollbackFailedStack(String prefix) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String name = prefix + suffix;
        String logGroup = "/" + prefix + suffix + "/lg";
        String outsideGroup = "/" + prefix + suffix + "/outside";
        logs("CreateLogGroup", "{\"logGroupName\":\"" + outsideGroup + "\"}").then().statusCode(200);

        RollbackFailedStack stack = new RollbackFailedStack(name, null, logGroup, outsideGroup);
        String id = XmlParser.extractFirst(
                cfn("CreateStack", name)
                    .formParam("TemplateBody", template(stack, null, false))
                .when().post("/").then().statusCode(200).extract().asString(),
                "StackId", null);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());

        cfn("UpdateStack", name)
            .formParam("TemplateBody", template(stack, 7, true))
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(name);
        assertEquals("UPDATE_ROLLBACK_FAILED", state.status(), state.reason());
        Map<String, String> statuses = resourceStatuses(name);
        assertEquals("UPDATE_FAILED", statuses.get("LogGroup"), statuses.toString());
        assertEquals("CREATE_COMPLETE", statuses.get("Stable"), statuses.toString());
        return new RollbackFailedStack(name, id, logGroup, outsideGroup);
    }

    /**
     * {@code Stable} never changes. {@code LogGroup} carries {@code retention} when it is set, and
     * {@code Dup}, which depends on it, claims the name of the group made outside the stack.
     */
    private static String template(RollbackFailedStack stack, Integer retention, boolean collide) {
        String retentionProperty = retention == null ? "" : ", \"RetentionInDays\": " + retention;
        String dup = !collide ? "" : """
                ,
                "Dup": {
                  "Type": "AWS::Logs::LogGroup",
                  "DependsOn": "LogGroup",
                  "Properties": {"LogGroupName": "%s"}
                }""".formatted(stack.outsideGroup());
        return """
                {
                  "Resources": {
                    "Stable": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {"LogGroupName": "%s/stable"}
                    },
                    "LogGroup": {
                      "Type": "AWS::Logs::LogGroup",
                      "Properties": {"LogGroupName": "%s"%s}
                    }%s
                  }
                }
                """.formatted(stack.logGroup(), stack.logGroup(), retentionProperty, dup);
    }

    /** AWS validates the skips after accepting the call, and reports a bad one as the failure. */
    private static void assertSkipFails(RollbackFailedStack stack, String skip, String reason) {
        cfn("ContinueUpdateRollback", stack.name())
            .formParam("ResourcesToSkip.member.1", skip)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack.name());
        assertEquals("UPDATE_ROLLBACK_FAILED", state.status(), state.reason());
        assertEquals(reason, state.reason());

        List<Map<String, String>> events = events(stack.id());
        Map<String, String> failed = events.get(0);
        Map<String, String> started = events.get(1);
        assertEquals(stack.name(), failed.get("LogicalResourceId"), events.toString());
        assertEquals("UPDATE_ROLLBACK_FAILED", failed.get("ResourceStatus"), events.toString());
        assertEquals(reason, failed.get("ResourceStatusReason"), events.toString());
        assertEquals("UPDATE_ROLLBACK_IN_PROGRESS", started.get("ResourceStatus"), events.toString());
        assertEquals(USER_INITIATED, started.get("ResourceStatusReason"), events.toString());
    }

    private static void assertContinueRefused(String stackName) {
        cfn("ContinueUpdateRollback", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>ValidationError</Code>"))
            .body(containsString(
                    "<Message>RollbackUpdatedStack cannot be called from current stack status</Message>"));
    }

    /** A text dashboard titled {@code title}, joined when {@code failing} by a resource that cannot provision. */
    private static String dashboardTemplate(String dashboardName, String title, boolean failing) {
        String fail = !failing ? "" : """
                ,
                "ZFail": {"Type": "AWS::CloudFormation::Stack", "DependsOn": "Dashboard", "Properties": {}}""";
        return """
                {
                  "Resources": {
                    "Dashboard": {
                      "Type": "AWS::CloudWatch::Dashboard",
                      "Properties": {
                        "DashboardName": "%s",
                        "DashboardBody": "{\\"widgets\\":[{\\"type\\":\\"text\\",\\"properties\\":{\\"markdown\\":\\"%s\\"}}]}"
                      }
                    }%s
                  }
                }
                """.formatted(dashboardName, title, fail);
    }

    private static Map<String, String> resourceStatuses(String stackName) {
        String body = cfn("DescribeStackResources", stackName).when().post("/")
                .then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "StackResources", "LogicalResourceId", "ResourceStatus");
    }

    private static List<Map<String, String>> events(String stackId) {
        return XmlParser.extractGroups(
                cfn("DescribeStackEvents", stackId).when().post("/").then().statusCode(200).extract().asString(),
                "member");
    }

    private static Response getTemplate(String stackName) {
        return cfn("GetTemplate", stackName).when().post("/");
    }

    /** Best effort and unchecked, so a failed assertion above is not masked. */
    private static void cleanUp(RollbackFailedStack stack) {
        cfn("DeleteStack", stack.name()).when().post("/");
        logs("DeleteLogGroup", "{\"logGroupName\":\"" + stack.logGroup() + "\"}");
        logs("DeleteLogGroup", "{\"logGroupName\":\"" + stack.logGroup() + "/stable\"}");
        logs("DeleteLogGroup", "{\"logGroupName\":\"" + stack.outsideGroup() + "\"}");
    }

    /** A Query request for {@code action}, naming the stack unless {@code stackName} is null. */
    private static RequestSpecification cfn(String action, String stackName) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", action);
        return stackName == null ? request : request.formParam("StackName", stackName);
    }

    private static Response logs(String target, String body) {
        return given()
            .config(RestAssured.config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.TEXT)))
            .header("X-Amz-Target", "Logs_20140328." + target)
            .contentType("application/x-amz-json-1.1")
            .body(body)
        .when()
            .post("/");
    }
}
