package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.scheduler.ScheduleInvoker;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

@QuarkusTest
class CloudFormationSchedulerScheduleIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String TEMPLATE = """
            {
              "Parameters":{"Name":{"Type":"String"},"Group":{"Type":"String","Default":"default"},
                "Expression":{"Type":"String","Default":"rate(5 minutes)"},
                "Payload":{"Type":"String","Default":"first"}},
              "Resources":{
                "Queue":{"Type":"AWS::SQS::Queue"},
                "Schedule":{"Type":"AWS::Scheduler::Schedule","UpdateReplacePolicy":"%s","Properties":{
                  %s
                  "GroupName":{"Ref":"Group"},"ScheduleExpression":{"Ref":"Expression"},
                  "State":"DISABLED","FlexibleTimeWindow":{"Mode":"OFF"},
                  "Target":{"Arn":{"Fn::GetAtt":["Queue","Arn"]},
                    "RoleArn":{"Fn::Sub":"arn:${AWS::Partition}:iam::${AWS::AccountId}:role/scheduler"},
                    "Input":{"Fn::Join":["",["payload:",{"Ref":"Payload"}]]}}
                }}%s
              },
              "Outputs":{
                "ScheduleRef":{"Value":{"Ref":"Schedule"}},
                "ScheduleArn":{"Value":{"Fn::GetAtt":["Schedule","Arn"]}},
                "QueueUrl":{"Value":{"Ref":"Queue"}}
              }
            }
            """;
    private static final String NAME = "\"Name\":{\"Ref\":\"Name\"},";
    private static final String FAILURE = """
            ,"BadSecret":{"Type":"AWS::SecretsManager::Secret","DependsOn":"Schedule",
              "Properties":{"SecretString":"explicit","GenerateSecretString":{"PasswordLength":32}}}
            """;

    @InjectSpy
    SchedulerService scheduler;

    @Inject
    CloudFormationService cloudFormation;

    @Inject
    ScheduleInvoker invoker;

    @Inject
    SqsService sqs;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void stackCreatesRealScheduleResolvesIntrinsicsUpdatesAndDeletes() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-" + suffix;
        String name = "schedule-" + suffix;
        cloudFormation(stack, "CreateStack", template("Delete", NAME, ""), Map.of("Name", name));
        Map<String, String> outputs = outputs(stack, "CREATE_COMPLETE");
        assertEquals(name, outputs.get("ScheduleRef"));
        String arn = getSchedule(name, "default").then().statusCode(200)
                .body("Target.Input", equalTo("payload:first"))
                .body("State", equalTo("DISABLED"))
                .extract().path("Arn");
        assertEquals(arn, outputs.get("ScheduleArn"));
        assertTrue(arn.endsWith("schedule/default/" + name));

        invoker.invoke(scheduler.getSchedule(name, "default", "us-east-1"), Instant.now());
        List<Message> messages = sqs.receiveMessage(outputs.get("QueueUrl"), 1, 0, 0, "us-east-1");
        assertEquals(1, messages.size());
        assertEquals("payload:first", messages.getFirst().getBody());

        cloudFormation(stack, "UpdateStack", template("Delete", NAME, ""),
                Map.of("Name", name, "Expression", "rate(10 minutes)", "Payload", "second"));
        assertEquals(name, outputs(stack, "UPDATE_COMPLETE").get("ScheduleRef"));
        getSchedule(name, "default").then().statusCode(200)
                .body("ScheduleExpression", equalTo("rate(10 minutes)"))
                .body("Target.Input", equalTo("payload:second"));
        deleteStack(stack);
        getSchedule(name, "default").then().statusCode(404);
    }

    @Test
    void groupMoveKeepsNameAndDeletesOldAddressEvenWithRetain() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-move-" + suffix;
        String name = "move-" + suffix;
        String group = "group-" + suffix;
        createGroup(group);
        cloudFormation(stack, "CreateStack", template("Retain", NAME, ""), Map.of("Name", name));
        Map<String, String> previous = outputs(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", template("Retain", NAME, ""), Map.of("Name", name, "Group", group));
        Map<String, String> updated = outputs(stack, "UPDATE_COMPLETE");

        assertEquals(name, updated.get("ScheduleRef"));
        assertTrue(updated.get("ScheduleArn").endsWith("/" + group + "/" + name));
        assertFalse(previous.get("ScheduleArn").equals(updated.get("ScheduleArn")));
        getSchedule(name, "default").then().statusCode(404);
        getSchedule(name, group).then().statusCode(200);
        deleteStack(stack);
        getSchedule(name, group).then().statusCode(404);
        deleteGroup(group);
    }

    @Test
    void failedGroupMoveRestoresOriginalScheduleAndRemovesDestination() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-move-rb-" + suffix;
        String name = "move-rb-" + suffix;
        String group = "group-rb-" + suffix;
        createGroup(group);
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", name));
        Map<String, String> before = outputs(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue),
                Map.of("Name", name, "Group", group, "Payload", "changed"));
        Map<String, String> after = outputs(stack, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(before.get("ScheduleRef"), after.get("ScheduleRef"));
        assertEquals(before.get("ScheduleArn"), after.get("ScheduleArn"));
        getSchedule(name, "default").then().statusCode(200).body("Target.Input", equalTo("payload:first"));
        getSchedule(name, group).then().statusCode(404);
        deleteStack(stack);
        deleteGroup(group);
        sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
    }

    @Test
    void failedInPlaceUpdateRestoresExpressionAndTarget() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-update-rb-" + suffix;
        String name = "update-rb-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", name));
        outputs(stack, "CREATE_COMPLETE");

        cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue),
                Map.of("Name", name, "Expression", "rate(10 minutes)", "Payload", "changed"));
        outputs(stack, "UPDATE_ROLLBACK_COMPLETE");

        getSchedule(name, "default").then().statusCode(200)
                .body("ScheduleExpression", equalTo("rate(5 minutes)"))
                .body("Target.Input", equalTo("payload:first"));
        deleteStack(stack);
        sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
    }

    @Test
    void nameReplacementCleansOldScheduleAndCanBeRolledBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-name-" + suffix;
        String oldName = "old-" + suffix;
        String newName = "new-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", oldName));
        outputs(stack, "CREATE_COMPLETE");
        cloudFormation(stack, "UpdateStack", externalQueueTemplate("", queue), Map.of("Name", newName));
        assertEquals(newName, outputs(stack, "UPDATE_COMPLETE").get("ScheduleRef"));
        getSchedule(oldName, "default").then().statusCode(404);

        cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue), Map.of("Name", oldName));
        assertEquals(newName, outputs(stack, "UPDATE_ROLLBACK_COMPLETE").get("ScheduleRef"));
        getSchedule(oldName, "default").then().statusCode(404);
        getSchedule(newName, "default").then().statusCode(200);
        deleteStack(stack);
        sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
    }

    @Test
    void generatedNameStaysStableAndLaterCreateFailureRollsItBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-unnamed-" + suffix;
        cloudFormation(stack, "CreateStack", template("Delete", "", ""), Map.of("Name", "unused"));
        String generated = outputs(stack, "CREATE_COMPLETE").get("ScheduleRef");
        assertTrue(generated.startsWith(stack + "-Schedule-"));
        assertTrue(generated.length() <= 64);
        cloudFormation(stack, "UpdateStack", template("Delete", "", ""),
                Map.of("Name", "unused", "Payload", "second"));
        assertEquals(generated, outputs(stack, "UPDATE_COMPLETE").get("ScheduleRef"));
        deleteStack(stack);

        String failedStack = "cfn-schedule-create-rb-" + suffix;
        cloudFormation(failedStack, "CreateStack", template("Delete", NAME, FAILURE), Map.of("Name", generated));
        outputs(failedStack, "ROLLBACK_COMPLETE");
        getSchedule(generated, "default").then().statusCode(404);
        deleteStack(failedStack);
    }

    @Test
    void anotherFailedUpdateAfterRollbackFailureRestoresTheOriginalSchedule() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-retry-" + suffix;
        String name = "retry-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        prepareFailedRollback(stack, name, queue);
        allowOriginalRestore(name);

        cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue),
                Map.of("Name", name, "Expression", "rate(15 minutes)", "Payload", "third"));
        outputs(stack, "UPDATE_ROLLBACK_COMPLETE");

        getSchedule(name, "default").then().statusCode(200)
                .body("ScheduleExpression", equalTo("rate(5 minutes)"))
                .body("Target.Input", equalTo("payload:first"));
        assertFalse(scheduleResource(stack).getAttributes().containsKey("__FlociSchedulerUpdateSnapshot"));
        deleteStack(stack);
        sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
    }

    @Test
    void anotherUpdateCannotMutateTheScheduleWhileItsOriginalRestoreStillFails() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-retry-block-" + suffix;
        String name = "retry-block-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        prepareFailedRollback(stack, name, queue);
        String snapshot = scheduleResource(stack).getAttributes().get("__FlociSchedulerUpdateSnapshot");

        cloudFormation(stack, "UpdateStack", externalQueueTemplate("", queue),
                Map.of("Name", name, "Expression", "rate(15 minutes)", "Payload", "third"));
        outputs(stack, "UPDATE_ROLLBACK_FAILED");

        getSchedule(name, "default").then().statusCode(200)
                .body("ScheduleExpression", equalTo("rate(10 minutes)"))
                .body("Target.Input", equalTo("payload:changed"));
        assertEquals(snapshot, scheduleResource(stack).getAttributes().get("__FlociSchedulerUpdateSnapshot"));
        deleteStack(stack);
        getSchedule(name, "default").then().statusCode(404);
        sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
    }

    @Test
    void anUpdateThatSkipsTheScheduleCannotClearItsFailedRollback() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-skipped-" + suffix;
        String name = "skipped-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        prepareFailedRollback(stack, name, queue);
        String snapshot = scheduleResource(stack).getAttributes().get("__FlociSchedulerUpdateSnapshot");
        String unchanged = externalQueueTemplate("", queue);
        cloudFormation(stack, "UpdateStack", unchanged, Map.of("Name", name));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                        .formParam("Action", "DescribeStacks").formParam("StackName", stack).post("/")
                        .then().statusCode(200)
                        .body(containsString("<StackStatus>UPDATE_COMPLETE_CLEANUP_IN_PROGRESS</StackStatus>"))
                        .body(containsString("Schedule rollback is still pending")));
        assertEquals(snapshot, scheduleResource(stack).getAttributes().get("__FlociSchedulerUpdateSnapshot"));
        assertEquals(unchanged, cloudFormation.describeStacks(stack, "us-east-1").getFirst().getOriginalTemplateBody());
        getSchedule(name, "default").then().statusCode(200)
                .body("ScheduleExpression", equalTo("rate(10 minutes)"))
                .body("Target.Input", equalTo("payload:changed"));
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "UpdateStack").formParam("StackName", stack)
                .formParam("TemplateBody", unchanged).post("/").then().statusCode(400)
                .body(containsString("UPDATE_COMPLETE_CLEANUP_IN_PROGRESS"));
        deleteStack(stack);
        getSchedule(name, "default").then().statusCode(404);
        sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
    }

    @Test
    void failedStackDeletionKeepsThePendingScheduleSnapshotForDeletionRetry() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-delete-retry-" + suffix;
        String name = "delete-retry-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        prepareFailedRollback(stack, name, queue);
        String snapshot = scheduleResource(stack).getAttributes().get("__FlociSchedulerUpdateSnapshot");
        doThrow(new AwsException("InternalServerException", "schedule deletion unavailable", 500))
                .doCallRealMethod().when(scheduler).deleteSchedule(name, "default", "us-east-1");

        cloudFormation(stack, "DeleteStack", null, Map.of());
        outputs(stack, "DELETE_FAILED");

        assertEquals(snapshot, scheduleResource(stack).getAttributes().get("__FlociSchedulerUpdateSnapshot"));
        getSchedule(name, "default").then().statusCode(200)
                .body("ScheduleExpression", equalTo("rate(10 minutes)"))
                .body("Target.Input", equalTo("payload:changed"));

        deleteStack(stack);
        getSchedule(name, "default").then().statusCode(404);
        sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
    }

    @Test
    void failedStackDeletionDoesNotForgetAnExhaustedGroupMoveOrphan() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-orphan-delete-" + suffix;
        String name = "orphan-delete-" + suffix;
        String group = "orphan-group-" + suffix;
        createGroup(group);
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", name));
        outputs(stack, "CREATE_COMPLETE");
        doThrow(new AwsException("InternalServerException", "orphan deletion unavailable", 500))
                .when(scheduler).deleteSchedule(name, group, "us-east-1");

        cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue),
                Map.of("Name", name, "Group", group));
        outputs(stack, "UPDATE_ROLLBACK_FAILED");
        getSchedule(name, "default").then().statusCode(200);
        getSchedule(name, group).then().statusCode(200).body("State", equalTo("DISABLED"));

        cloudFormation(stack, "DeleteStack", null, Map.of());
        outputs(stack, "DELETE_FAILED");
        assertTrue(scheduleResource(stack).getAttributes().containsKey("__FlociReplacementCleanup"));
        getSchedule(name, group).then().statusCode(200);

        doCallRealMethod().when(scheduler).deleteSchedule(name, group, "us-east-1");
        deleteStack(stack);
        getSchedule(name, "default").then().statusCode(404);
        getSchedule(name, group).then().statusCode(404);
        assertTrue(sqs.getQueueAttributes(queue.getQueueUrl(), List.of("QueueArn"), "us-east-1")
                .get("QueueArn").endsWith(":external-" + suffix));
        deleteGroup(group);
        sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
    }

    private void prepareFailedRollback(String stack, String name, Queue queue) {
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", name));
        outputs(stack, "CREATE_COMPLETE");
        doThrow(new AwsException("InternalServerException", "original restore unavailable", 500))
                .when(scheduler).updateSchedule(argThat(request -> name.equals(request.getName())
                        && "rate(5 minutes)".equals(request.getScheduleExpression())), eq("us-east-1"));
        cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue),
                Map.of("Name", name, "Expression", "rate(10 minutes)", "Payload", "changed"));
        outputs(stack, "UPDATE_ROLLBACK_FAILED");
        getSchedule(name, "default").then().statusCode(200)
                .body("ScheduleExpression", equalTo("rate(10 minutes)"));
        assertTrue(scheduleResource(stack).getAttributes().containsKey("__FlociSchedulerUpdateSnapshot"));
    }

    private void allowOriginalRestore(String name) {
        doCallRealMethod().when(scheduler).updateSchedule(argThat(request -> name.equals(request.getName())
                && "rate(5 minutes)".equals(request.getScheduleExpression())), eq("us-east-1"));
    }

    private StackResource scheduleResource(String stack) {
        return cloudFormation.describeStacks(stack, "us-east-1").getFirst().getResources().get("Schedule");
    }

    private static String template(String policy, String name, String failure) {
        return TEMPLATE.formatted(policy, name, failure);
    }

    private String externalQueueTemplate(String failure, Queue queue) {
        // An externally managed target keeps these failure-path tests focused on the schedule's
        // own rollback contract rather than a sibling queue's update lifecycle.
        try {
            ObjectNode template = (ObjectNode) new ObjectMapper().readTree(template("Delete", NAME, failure));
            ObjectNode resources = (ObjectNode) template.get("Resources");
            resources.remove("Queue");
            ObjectNode target = (ObjectNode) resources.path("Schedule").path("Properties").path("Target");
            target.put("Arn", sqs.getQueueAttributes(queue.getQueueUrl(), List.of("QueueArn"), "us-east-1")
                    .get("QueueArn"));
            ((ObjectNode) template.path("Outputs").path("QueueUrl")).put("Value", queue.getQueueUrl());
            return template.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not prepare external-target template", e);
        }
    }

    private static void cloudFormation(String stack, String action, String template, Map<String, String> parameters) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", AUTH).formParam("Action", action).formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        int index = 1;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            request.formParam("Parameters.member." + index + ".ParameterKey", parameter.getKey());
            request.formParam("Parameters.member." + index + ".ParameterValue", parameter.getValue());
            index++;
        }
        request.post("/").then().statusCode(200);
    }

    private static Map<String, String> outputs(String stack, String expectedStatus) {
        assertEquals(expectedStatus, CfnStackWaits.awaitTerminal(stack).status());
        String body = given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "DescribeStacks").formParam("StackName", stack)
                .post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "Outputs", "OutputKey", "OutputValue");
    }

    private static Response getSchedule(String name, String group) {
        return given().queryParam("groupName", group).get("/schedules/" + name);
    }

    private static void createGroup(String group) {
        given().contentType("application/json").body("{}").post("/schedule-groups/" + group)
                .then().statusCode(200);
    }

    private static void deleteGroup(String group) {
        given().delete("/schedule-groups/" + group).then().statusCode(200);
    }

    private static void deleteStack(String stack) {
        cloudFormation(stack, "DeleteStack", null, Map.of());
        CfnStackWaits.awaitStackDeleted(stack);
    }
}
