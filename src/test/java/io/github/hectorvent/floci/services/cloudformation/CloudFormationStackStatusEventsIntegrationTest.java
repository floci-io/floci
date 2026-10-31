package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.CfnRequests;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.EventRuleQueue;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.StepFunctionsExecutions;
import io.github.hectorvent.floci.testing.StepFunctionsRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What a rule on the default bus receives as a stack's status changes, against what CloudFormation
 * sent a rule on 2026-10-30 for a root nesting one stack, through change sets and DeleteStack, each
 * with a token of its own, and through a create and an update that failed in the nested stack: the
 * root's events carry their operation's token, its rollback statuses included, but for
 * {@code REVIEW_IN_PROGRESS}, which has no key; a nested stack's events have no key at all; and the
 * root's {@code CREATE_FAILED} and any {@code UPDATE_FAILED} never reach the bus. A nested stack's
 * failed create is {@code CREATE_FAILED} and then a delete. Each status arrives once.
 *
 * <p>A failure in the root's own resource with the nested stack healthy, and a delete that fails, were
 * not read, and no test here asserts them.
 */
@QuarkusTest
class CloudFormationStackStatusEventsIntegrationTest {

    private static final String DETAIL_TYPE = "CloudFormation Stack Status Change";
    private static final String ALL_STATUS_CHANGES = """
            {"source": ["aws.cloudformation"], "detail-type": ["%s"]}
            """.formatted(DETAIL_TYPE);
    private static final String BUCKET = "stack-status-events-templates";
    private static final String CHILD_KEY = "child.json";
    private static final String CHILD_TEMPLATE = """
            {
              "Parameters": {"Value": {"Type": "String"}},
              "Resources": {
                "ChildTopic": {
                  "Type": "AWS::SNS::Topic",
                  "Properties": {"DisplayName": {"Ref": "Value"}}
                }
              }
            }
            """;
    private static final String FAILING_CHILD_KEY = "failing-child.json";
    private static final String FAILING_CHILD_TEMPLATE = """
            {
              "Parameters": {"Value": {"Type": "String"}},
              "Resources": {
                "Broken": {"Type": "AWS::CloudFormation::Stack", "Properties": {}}
              }
            }
            """;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void uploadChildTemplate() {
        given().when().put("/" + BUCKET).then().statusCode(200);
        given().body(CHILD_TEMPLATE).when().put("/" + BUCKET + "/" + CHILD_KEY).then().statusCode(200);
        given().body(FAILING_CHILD_TEMPLATE).when().put("/" + BUCKET + "/" + FAILING_CHILD_KEY).then().statusCode(200);
    }

    @Test
    void changeSetsAndDeleteCarryEachOperationsTokenOnTheRootAndNoneOnTheNestedStack() throws Exception {
        String root = uniqueName("root");
        String rootId;
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", root, ALL_STATUS_CHANGES)) {
            rootId = createChangeSet(root, "CREATE", "create", rootTemplate("first"));
            executeChangeSet(root, "create", "a");
            CfnStackWaits.awaitStatus(root, "CREATE_COMPLETE");

            createChangeSet(root, "UPDATE", "update", rootTemplate("second"));
            executeChangeSet(root, "update", "b");
            CfnStackWaits.awaitStatus(root, "UPDATE_COMPLETE");

            CfnRequests.cfn("DeleteStack", root).formParam("ClientRequestToken", "c")
                    .when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStatus(rootId, "DELETE_COMPLETE");

            List<JsonNode> events = awaitEvents(queue, rootId, "DELETE_COMPLETE");
            List<JsonNode> rootEvents = naming(events, rootId);
            Map<String, Set<String>> tokens = tokensByStatus(rootEvents);

            assertEquals(Set.of(NO_TOKEN), tokens.get("REVIEW_IN_PROGRESS"));
            assertEquals(Set.of("a"), tokens.get("CREATE_IN_PROGRESS"));
            assertEquals(Set.of("a"), tokens.get("CREATE_COMPLETE"));
            assertEquals(Set.of("b"), tokens.get("UPDATE_IN_PROGRESS"));
            assertEquals(Set.of("b"), tokens.get("UPDATE_COMPLETE"));
            assertEquals(Set.of("c"), tokens.get("DELETE_IN_PROGRESS"));
            assertEquals(Set.of("c"), tokens.get("DELETE_COMPLETE"));
            assertEachStatusOnce(rootEvents);

            List<JsonNode> nested = awaitNested(queue, events, root, "DELETE_COMPLETE");
            assertThat(tokensByStatus(nested).keySet(), hasItems("CREATE_IN_PROGRESS", "CREATE_COMPLETE",
                    "UPDATE_IN_PROGRESS", "UPDATE_COMPLETE", "DELETE_IN_PROGRESS", "DELETE_COMPLETE"));
            assertNoTokenOn(nested);
            assertEachStatusOnce(nested);
        }
    }

    @Test
    void anEventIsInTheShapeRead() throws Exception {
        String stack = uniqueName("shape");
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", stack, ALL_STATUS_CHANGES)) {
            String stackId = createStack(stack, "shape-token", "first");

            JsonNode complete = withStatus(naming(awaitEvents(queue, stackId, "CREATE_COMPLETE"), stackId),
                    "CREATE_COMPLETE").get(0);

            assertEquals("aws.cloudformation", complete.path("source").asText());
            assertEquals(DETAIL_TYPE, complete.path("detail-type").asText());
            assertEquals("000000000000", complete.path("account").asText());
            assertEquals("us-east-1", complete.path("region").asText());
            assertEquals(1, complete.path("resources").size());
            assertEquals(stackId, complete.path("resources").get(0).asText());
            JsonNode detail = complete.path("detail");
            assertEquals(stackId, detail.path("stack-id").asText());
            assertEquals("shape-token", detail.path("client-request-token").asText());
            assertEquals("CREATE_COMPLETE", detail.path("status-details").path("status").asText());
            assertEquals("", detail.path("status-details").path("detailed-status").asText("absent"));
            assertEquals("", detail.path("status-details").path("status-reason").asText("absent"));
        }
    }

    /**
     * The root's topic and its record of the nested stack both reach CREATE_COMPLETE on the root, so a
     * resource's record sent to the bus would show as that status more than once.
     */
    @Test
    void aResourcesProgressSendsNothing() throws Exception {
        String root = uniqueName("resources");
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", root, ALL_STATUS_CHANGES)) {
            String rootId = createStack(root, "resources-token", "first");

            List<JsonNode> events = awaitEvents(queue, rootId, "CREATE_COMPLETE");

            assertEquals(1, withStatus(naming(events, rootId), "CREATE_COMPLETE").size(), () -> "events: " + events);
            assertEquals(1, withStatus(naming(events, rootId), "CREATE_IN_PROGRESS").size(), () -> "events: " + events);
            List<String> stackNames = new ArrayList<>();
            for (JsonNode event : withStatus(events, "CREATE_COMPLETE")) {
                assertEquals(event.path("detail").path("stack-id").asText(), event.path("resources").get(0).asText());
                stackNames.add(event.path("detail").path("stack-id").asText().split("/")[1]);
            }
            assertThat(stackNames, containsInAnyOrder(root + "-Nested", root));
        }
    }

    @Test
    void createUpdateAndDeleteStackCarryTheirTokens() throws Exception {
        String stack = uniqueName("direct");
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", stack, ALL_STATUS_CHANGES)) {
            String stackId = createStack(stack, "direct-create", "first");
            CfnRequests.cfn("UpdateStack", stack)
                    .formParam("TemplateBody", rootTemplate("second"))
                    .formParam("ClientRequestToken", "direct-update")
                    .when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE");
            CfnRequests.cfn("DeleteStack", stack).formParam("ClientRequestToken", "direct-delete")
                    .when().post("/").then().statusCode(200);

            Map<String, Set<String>> tokens =
                    tokensByStatus(naming(awaitEvents(queue, stackId, "DELETE_COMPLETE"), stackId));

            assertEquals(Set.of("direct-create"), tokens.get("CREATE_IN_PROGRESS"));
            assertEquals(Set.of("direct-create"), tokens.get("CREATE_COMPLETE"));
            assertEquals(Set.of("direct-update"), tokens.get("UPDATE_IN_PROGRESS"));
            assertEquals(Set.of("direct-update"), tokens.get("UPDATE_COMPLETE"));
            assertEquals(Set.of("direct-delete"), tokens.get("DELETE_IN_PROGRESS"));
            assertEquals(Set.of("direct-delete"), tokens.get("DELETE_COMPLETE"));
        }
    }

    @Test
    void anOperationGivenNoTokenDoesNotCarryTheLastOnes() throws Exception {
        String stack = uniqueName("no-token");
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", stack, ALL_STATUS_CHANGES)) {
            String stackId = createStack(stack, "had-a-token", "first");
            CfnRequests.cfn("UpdateStack", stack)
                    .formParam("TemplateBody", rootTemplate("second"))
                    .when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE");

            Map<String, Set<String>> tokens =
                    tokensByStatus(naming(awaitEvents(queue, stackId, "UPDATE_COMPLETE"), stackId));

            assertEquals(Set.of("had-a-token"), tokens.get("CREATE_COMPLETE"));
            assertEquals(Set.of(NO_TOKEN), tokens.get("UPDATE_IN_PROGRESS"));
            assertEquals(Set.of(NO_TOKEN), tokens.get("UPDATE_COMPLETE"));
        }
    }

    @Test
    void aPatternOnTheStatusSeesOnlyThatStatus() throws Exception {
        String stack = uniqueName("pattern");
        String pattern = """
                {"source": ["aws.cloudformation"], "detail-type": ["%s"],
                 "detail": {"status-details": {"status": ["UPDATE_COMPLETE"]}}}
                """.formatted(DETAIL_TYPE);
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", stack, pattern)) {
            String stackId = createStack(stack, "pattern-create", "first");
            CfnRequests.cfn("UpdateStack", stack)
                    .formParam("TemplateBody", rootTemplate("second"))
                    .formParam("ClientRequestToken", "pattern-update")
                    .when().post("/").then().statusCode(200);
            CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE");

            List<JsonNode> events = awaitEvents(queue, stackId, "UPDATE_COMPLETE");

            assertEquals(Set.of("UPDATE_COMPLETE"), tokensByStatus(naming(events, stackId)).keySet());
        }
    }

    @Test
    void aStateMachinesTaskNamesTheDeploy() throws Exception {
        String stack = uniqueName("task");
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", stack, ALL_STATUS_CHANGES)) {
            String stackId = createStack(stack, "task-create", "first");
            createChangeSet(stack, "UPDATE", "by-task", rootTemplate("second"));
            ObjectNode parameters = MAPPER.createObjectNode().put("StackName", stack)
                    .put("ChangeSetName", "by-task").put("ClientRequestToken", "swb-d1");
            ObjectNode task = MAPPER.createObjectNode().put("Type", "Task")
                    .put("Resource", "arn:aws:states:::aws-sdk:cloudformation:executeChangeSet")
                    .put("End", true);
            task.set("Parameters", parameters);
            ObjectNode definition = MAPPER.createObjectNode().put("StartAt", "Execute");
            definition.putObject("States").set("Execute", task);
            String stateMachineArn = StepFunctionsRequests.sfn("CreateStateMachine", Map.of(
                            "name", stack, "definition", definition.toString(),
                            "roleArn", "arn:aws:iam::000000000000:role/test-role"))
                    .then().statusCode(200).extract().path("stateMachineArn");
            String executionArn = StepFunctionsRequests.sfn("StartExecution",
                            Map.of("stateMachineArn", stateMachineArn, "input", "{}"))
                    .then().statusCode(200).extract().path("executionArn");
            assertEquals("SUCCEEDED",
                    StepFunctionsExecutions.waitForTerminalState(executionArn).jsonPath().getString("status"));
            CfnStackWaits.awaitStatus(stack, "UPDATE_COMPLETE");

            Map<String, Set<String>> tokens =
                    tokensByStatus(naming(awaitEvents(queue, stackId, "UPDATE_COMPLETE"), stackId));

            assertEquals(Set.of("swb-d1"), tokens.get("UPDATE_COMPLETE"));
        }
    }

    @Test
    void aFailedCreateCarriesItsTokenThroughItsRollback() throws Exception {
        String root = uniqueName("failed-create");
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", root, ALL_STATUS_CHANGES)) {
            String rootId = createChangeSet(root, "CREATE", "create", rootTemplate("first", FAILING_CHILD_KEY));
            executeChangeSet(root, "create", "fc-create");
            CfnStackWaits.awaitStatus(root, "ROLLBACK_COMPLETE");

            List<JsonNode> events = awaitEvents(queue, rootId, "ROLLBACK_COMPLETE");
            List<JsonNode> rootEvents = naming(events, rootId);
            Map<String, Set<String>> tokens = tokensByStatus(rootEvents);

            assertEquals(Set.of(NO_TOKEN), tokens.get("REVIEW_IN_PROGRESS"));
            assertEquals(Set.of("fc-create"), tokens.get("CREATE_IN_PROGRESS"));
            assertEquals(Set.of("fc-create"), tokens.get("ROLLBACK_IN_PROGRESS"));
            assertEquals(Set.of("fc-create"), tokens.get("ROLLBACK_COMPLETE"));
            assertEquals(Set.of("REVIEW_IN_PROGRESS", "CREATE_IN_PROGRESS", "ROLLBACK_IN_PROGRESS", "ROLLBACK_COMPLETE"),
                    tokens.keySet());
            assertEachStatusOnce(rootEvents);
            assertFalse(withStatus(rootEvents, "ROLLBACK_IN_PROGRESS").get(0).path("detail").path("status-details")
                    .path("status-reason").asText().isEmpty());
            List<JsonNode> nested = nestedEvents(events, root);
            assertNoTokenOn(nested);
            assertEquals(Set.of("CREATE_IN_PROGRESS", "CREATE_FAILED", "DELETE_IN_PROGRESS", "DELETE_COMPLETE"),
                    tokensByStatus(nested).keySet());
            assertEachStatusOnce(nested);

            CfnRequests.cfn("DeleteStack", root).formParam("ClientRequestToken", "fc-delete")
                    .when().post("/").then().statusCode(200);
            assertEquals(Set.of("fc-delete"), tokensByStatus(naming(awaitEvents(queue, rootId, "DELETE_COMPLETE"), rootId))
                    .get("DELETE_COMPLETE"));
        }
    }

    @Test
    void aFailedUpdateCarriesItsTokenThroughItsRollback() throws Exception {
        String root = uniqueName("failed-update");
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", root, ALL_STATUS_CHANGES)) {
            String rootId = createStack(root, "fu-create", "first");
            createChangeSet(root, "UPDATE", "update", rootTemplate("second", FAILING_CHILD_KEY));
            executeChangeSet(root, "update", "fu-update");
            CfnStackWaits.awaitStatus(root, "UPDATE_ROLLBACK_COMPLETE");

            List<JsonNode> events = awaitEvents(queue, rootId, "UPDATE_ROLLBACK_COMPLETE");
            Map<String, Set<String>> tokens = tokensByStatus(naming(events, rootId));

            assertEquals(Set.of("fu-create"), tokens.get("CREATE_COMPLETE"));
            assertEquals(Set.of("fu-update"), tokens.get("UPDATE_IN_PROGRESS"));
            assertEquals(Set.of("fu-update"), tokens.get("UPDATE_ROLLBACK_IN_PROGRESS"));
            assertEquals(Set.of("fu-update"), tokens.get("UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS"));
            assertEquals(Set.of("fu-update"), tokens.get("UPDATE_ROLLBACK_COMPLETE"));
            assertEquals(List.of(), withStatus(events, "UPDATE_FAILED"));
            assertEachStatusOnce(naming(events, rootId));
            List<JsonNode> nested = nestedEvents(events, root);
            assertNoTokenOn(nested);
            assertThat(tokensByStatus(nested).keySet(), hasItems("UPDATE_IN_PROGRESS", "UPDATE_ROLLBACK_IN_PROGRESS",
                    "UPDATE_ROLLBACK_COMPLETE_CLEANUP_IN_PROGRESS", "UPDATE_ROLLBACK_COMPLETE"));
            assertEachStatusOnce(nested);
        }
    }

    /** Green with or without the events: it keeps delivery from ever failing the stack. */
    @Test
    void aTargetThatCannotBeDeliveredToLeavesTheStackAlone() {
        String stack = uniqueName("undeliverable");
        String events = "application/x-amz-json-1.1";
        given().header("X-Amz-Target", "AWSEvents.PutRule").contentType(events)
                .body(MAPPER.createObjectNode().put("Name", stack).put("EventPattern", ALL_STATUS_CHANGES).toString())
                .when().post("/").then().statusCode(200);
        ObjectNode targets = MAPPER.createObjectNode().put("Rule", stack);
        targets.putArray("Targets").addObject().put("Id", "1")
                .put("Arn", "arn:aws:sqs:us-east-1:000000000000:no-such-queue-" + stack);
        given().header("X-Amz-Target", "AWSEvents.PutTargets").contentType(events).body(targets.toString())
                .when().post("/").then().statusCode(200);
        try {
            createStack(stack, "undeliverable-token", "first");
        } finally {
            ObjectNode remove = MAPPER.createObjectNode().put("Rule", stack);
            remove.putArray("Ids").add("1");
            given().header("X-Amz-Target", "AWSEvents.RemoveTargets").contentType(events).body(remove.toString())
                    .when().post("/").then().statusCode(200);
            given().header("X-Amz-Target", "AWSEvents.DeleteRule").contentType(events)
                    .body(MAPPER.createObjectNode().put("Name", stack).toString())
                    .when().post("/").then().statusCode(200);
        }
    }

    private static final String NO_TOKEN = "(no key)";

    private static String uniqueName(String prefix) {
        return "status-" + prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String rootTemplate(String childValue) {
        return rootTemplate(childValue, CHILD_KEY);
    }

    private static String rootTemplate(String childValue, String childKey) {
        return """
                {
                  "Resources": {
                    "RootTopic": {
                      "Type": "AWS::SNS::Topic",
                      "Properties": {"DisplayName": "root"}
                    },
                    "Nested": {
                      "Type": "AWS::CloudFormation::Stack",
                      "Properties": {
                        "TemplateURL": "https://s3.amazonaws.com/%2$s/%3$s",
                        "Parameters": {"Value": "%1$s"}
                      }
                    }
                  }
                }
                """.formatted(childValue, BUCKET, childKey);
    }

    private static String createStack(String name, String token, String childValue) {
        String xml = CfnRequests.cfn("CreateStack", name)
                .formParam("TemplateBody", rootTemplate(childValue))
                .formParam("ClientRequestToken", token)
                .when().post("/").then().statusCode(200).extract().asString();
        CfnStackWaits.awaitStatus(name, "CREATE_COMPLETE");
        return XmlPath.from(xml).getString("**.find { it.name() == 'StackId' }");
    }

    private static String createChangeSet(String stack, String type, String changeSet, String template) {
        String xml = CfnRequests.cfn("CreateChangeSet", stack)
                .formParam("ChangeSetName", changeSet)
                .formParam("ChangeSetType", type)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlPath.from(xml).getString("**.find { it.name() == 'StackId' }");
    }

    private static void executeChangeSet(String stack, String changeSet, String token) {
        CfnRequests.cfn("ExecuteChangeSet", stack)
                .formParam("ChangeSetName", changeSet)
                .formParam("ClientRequestToken", token)
                .when().post("/").then().statusCode(200);
    }

    /**
     * The events on the queue once {@code stackId} has sent {@code lastStatus}; the bus delivers apart
     * from the stack operation, so the status DescribeStacks answers can be ahead of the queue. Every
     * event the queue has held by then is returned, whichever stack it names.
     */
    private static List<JsonNode> awaitEvents(EventRuleQueue queue, String stackId, String lastStatus)
            throws Exception {
        List<JsonNode> events = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            events.addAll(queue.drain());
            if (naming(events, stackId).stream().anyMatch(event -> lastStatus.equals(status(event)))) {
                return events;
            }
            Thread.sleep(50);
        }
        fail("No " + DETAIL_TYPE + " event with status " + lastStatus + " for " + stackId + " within ten seconds; "
                + "the queue held: " + events);
        return events;
    }

    /** The nested stack's events, waiting for {@code lastStatus} since delivery runs in order behind the root's. */
    private static List<JsonNode> awaitNested(EventRuleQueue queue, List<JsonNode> events, String root,
                                              String lastStatus) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<JsonNode> nested = nestedEvents(events, root);
            if (nested.stream().anyMatch(event -> lastStatus.equals(status(event)))) {
                return nested;
            }
            events.addAll(queue.drain());
            Thread.sleep(50);
        }
        fail("No " + lastStatus + " event for the nested stack of " + root + " within ten seconds; "
                + "the queue held: " + events);
        return List.of();
    }

    private static List<JsonNode> nestedEvents(List<JsonNode> events, String root) {
        return events.stream()
                .filter(event -> event.path("detail").path("stack-id").asText().contains("/" + root + "-Nested/"))
                .toList();
    }

    private static void assertNoTokenOn(List<JsonNode> nested) {
        assertFalse(nested.isEmpty(), "no event named the nested stack");
        for (JsonNode event : nested) {
            assertFalse(event.path("detail").has("client-request-token"), () -> "nested event: " + event);
        }
    }

    private static List<JsonNode> naming(List<JsonNode> events, String stackId) {
        return events.stream()
                .filter(event -> stackId.equals(event.path("detail").path("stack-id").asText()))
                .toList();
    }

    private static void assertEachStatusOnce(List<JsonNode> events) {
        List<String> statuses = events.stream().map(CloudFormationStackStatusEventsIntegrationTest::status).toList();
        assertEquals(new LinkedHashSet<>(statuses).size(), statuses.size(), () -> "statuses: " + statuses);
    }

    private static List<JsonNode> withStatus(List<JsonNode> events, String status) {
        return events.stream().filter(event -> status.equals(status(event))).toList();
    }

    private static String status(JsonNode event) {
        return event.path("detail").path("status-details").path("status").asText();
    }

    /** Each status seen, with the tokens its events carried, an event with no key counting as {@link #NO_TOKEN}. */
    private static Map<String, Set<String>> tokensByStatus(List<JsonNode> events) {
        Map<String, Set<String>> tokens = new LinkedHashMap<>();
        for (JsonNode event : events) {
            JsonNode detail = event.path("detail");
            tokens.computeIfAbsent(status(event), key -> new LinkedHashSet<>())
                    .add(detail.has("client-request-token") ? detail.path("client-request-token").asText() : NO_TOKEN);
        }
        return tokens;
    }
}
