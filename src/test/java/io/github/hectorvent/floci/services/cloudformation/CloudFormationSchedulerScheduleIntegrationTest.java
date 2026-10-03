package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.scheduler.ScheduleInvoker;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
import static org.mockito.Mockito.doAnswer;
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
    void updateRestoresTemplateAfterScheduleWasRecreatedAtItsAddress() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-recreated-update-" + suffix;
        String name = "recreated-update-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        String template = externalQueueTemplate("", queue);
        cloudFormation(stack, "CreateStack", template, Map.of("Name", name));
        String arn = outputs(stack, "CREATE_COMPLETE").get("ScheduleArn");
        recreateScheduleViaApi(name, "default");
        getSchedule(name, "default").then().statusCode(200).body("Arn", equalTo(arn))
                .body("ScheduleExpression", equalTo("rate(30 minutes)"))
                .body("Target.Input", equalTo("out-of-band"));
        try {
            cloudFormation(stack, "UpdateStack", template,
                    Map.of("Name", name, "Expression", "rate(10 minutes)", "Payload", "updated"));
            assertEquals(arn, outputs(stack, "UPDATE_COMPLETE").get("ScheduleArn"));
            getSchedule(name, "default").then().statusCode(200)
                    .body("ScheduleExpression", equalTo("rate(10 minutes)"))
                    .body("Target.Input", equalTo("payload:updated"))
                    .body("State", equalTo("DISABLED"));
            cloudFormation(stack, "UpdateStack", template, Map.of("Name", name));
            assertEquals(name, outputs(stack, "UPDATE_COMPLETE").get("ScheduleRef"));
            getSchedule(name, "default").then().statusCode(200)
                    .body("ScheduleExpression", equalTo("rate(5 minutes)"))
                    .body("Target.Input", equalTo("payload:first"));
        } finally {
            deleteStack(stack);
            given().delete("/schedules/" + name);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void stackDeletionDeletesScheduleRecreatedAtItsAddress() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-recreated-delete-" + suffix;
        String name = "recreated-delete-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", name));
        String arn = outputs(stack, "CREATE_COMPLETE").get("ScheduleArn");
        recreateScheduleViaApi(name, "default");
        getSchedule(name, "default").then().statusCode(200).body("Arn", equalTo(arn));
        try {
            deleteStack(stack);
            getSchedule(name, "default").then().statusCode(404);
        } finally {
            given().delete("/schedules/" + name);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
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

    @Test
    void deletionRetryDeletesTheRecreatedScheduleAtItsTrackedOrphanAddress() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-old-owner-" + suffix;
        String name = "recreated-" + suffix;
        String group = "recreated-group-" + suffix;
        createGroup(group);
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", name));
        outputs(stack, "CREATE_COMPLETE");
        doThrow(new AwsException("InternalServerException", "orphan deletion unavailable", 500))
                .when(scheduler).deleteSchedule(name, group, "us-east-1");

        cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue),
                Map.of("Name", name, "Group", group));
        outputs(stack, "UPDATE_ROLLBACK_FAILED");
        cloudFormation(stack, "DeleteStack", null, Map.of());
        outputs(stack, "DELETE_FAILED");
        assertTrue(scheduleResource(stack).getAttributes().containsKey("__FlociReplacementCleanup"));

        doCallRealMethod().when(scheduler).deleteSchedule(name, group, "us-east-1");
        recreateScheduleViaApi(name, group);
        getSchedule(name, group).then().statusCode(200).body("Target.Input", equalTo("out-of-band"));

        try {
            deleteStack(stack);
            getSchedule(name, group).then().statusCode(404);
            getSchedule(name, "default").then().statusCode(404);
        } finally {
            given().queryParam("groupName", group).delete("/schedules/" + name);
            deleteGroup(group);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void repeatedFailedReplacementRestartsCleanupAtTheSameAddress() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-reused-debt-" + suffix;
        String originalName = "original-debt-" + suffix;
        String replacementName = "replacement-debt-" + suffix;
        String replacementAddress = "default/" + replacementName;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", originalName));
        outputs(stack, "CREATE_COMPLETE");
        doThrow(new AwsException("InternalServerException", "replacement deletion unavailable", 500))
                .when(scheduler).deleteSchedule(replacementName, "default", "us-east-1");

        cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue),
                Map.of("Name", replacementName, "Payload", "first-failed"));
        outputs(stack, "UPDATE_ROLLBACK_FAILED");
        Instant firstCreation = scheduler.getSchedule(replacementName, "default", "us-east-1").getCreationDate();
        doCallRealMethod().when(scheduler).deleteSchedule(replacementName, "default", "us-east-1");
        given().delete("/schedules/" + replacementName).then().statusCode(200);
        doThrow(new AwsException("InternalServerException", "replacement deletion still unavailable", 500))
                .when(scheduler).deleteSchedule(replacementName, "default", "us-east-1");

        // Change both resources so the engine actually retries the replacement and its failing dependent.
        String repeatedFailure = FAILURE.replace("\"SecretString\":\"explicit\"",
                "\"SecretString\":\"explicit-second\"");
        cloudFormation(stack, "UpdateStack", externalQueueTemplate(repeatedFailure, queue),
                Map.of("Name", replacementName, "Payload", "second-failed"));
        outputs(stack, "UPDATE_ROLLBACK_FAILED");
        assertFalse(firstCreation.equals(scheduler.getSchedule(replacementName, "default", "us-east-1").getCreationDate()));
        StackResource resource = scheduleResource(stack);
        assertEquals(originalName, resource.getPhysicalId());
        JsonNode displaced = new ObjectMapper().readTree(resource.getAttributes().get("__FlociReplacementCleanup"))
                .path("displaced");
        assertEquals(1, displaced.size());
        assertEquals(replacementAddress, displaced.get(0).path("physicalId").asText());
        assertEquals(0, displaced.get(0).path("cleanupAttempts").asInt());
        assertFalse(displaced.get(0).path("retainable").asBoolean());
        getSchedule(originalName, "default").then().statusCode(200).body("Target.Input", equalTo("payload:first"));
        getSchedule(replacementName, "default").then().statusCode(200)
                .body("Target.Input", equalTo("payload:second-failed"));

        doCallRealMethod().when(scheduler).deleteSchedule(replacementName, "default", "us-east-1");
        deleteStack(stack);
        getSchedule(originalName, "default").then().statusCode(404);
        getSchedule(replacementName, "default").then().statusCode(404);
        sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Delete", "Retain"})
    void nameReplacementHandlesTheRecreatedOldAddressAccordingToPolicy(String policy) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-recreated-replace-" + suffix;
        String originalName = "recreated-source-" + suffix;
        String replacementName = "recreated-destination-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        String template = externalQueueTemplate("", queue)
                .replace("\"UpdateReplacePolicy\":\"Delete\"", "\"UpdateReplacePolicy\":\"" + policy + "\"");
        cloudFormation(stack, "CreateStack", template, Map.of("Name", originalName));
        outputs(stack, "CREATE_COMPLETE");
        recreateScheduleViaApi(originalName, "default");
        try {
            cloudFormation(stack, "UpdateStack", template, Map.of("Name", replacementName));
            assertEquals(replacementName, outputs(stack, "UPDATE_COMPLETE").get("ScheduleRef"));
            if ("Retain".equals(policy)) {
                getSchedule(originalName, "default").then().statusCode(200).body("Target.Input", equalTo("out-of-band"));
            } else {
                getSchedule(originalName, "default").then().statusCode(404);
            }
            deleteStack(stack);
            getSchedule(replacementName, "default").then().statusCode(404);
        } finally {
            given().delete("/schedules/" + originalName);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void nameReplacementWorksAfterTheOldAddressWasDeleted() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-gone-replace-" + suffix;
        String originalName = "gone-source-" + suffix;
        String replacementName = "gone-destination-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        String template = externalQueueTemplate("", queue);
        cloudFormation(stack, "CreateStack", template, Map.of("Name", originalName));
        outputs(stack, "CREATE_COMPLETE");
        given().delete("/schedules/" + originalName).then().statusCode(200);
        cloudFormation(stack, "UpdateStack", template, Map.of("Name", replacementName));
        assertEquals(replacementName, outputs(stack, "UPDATE_COMPLETE").get("ScheduleRef"));
        getSchedule(originalName, "default").then().statusCode(404);
        getSchedule(replacementName, "default").then().statusCode(200).body("Target.Input", equalTo("payload:first"));
        deleteStack(stack);
        sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
    }

    @Test
    void replacementCleanupDeletesTheAddressRecreatedWhileDeletionStarts() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-recreate-cleanup-" + suffix;
        String originalName = "cleanup-source-" + suffix;
        String replacementName = "cleanup-destination-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        String template = externalQueueTemplate("", queue);
        cloudFormation(stack, "CreateStack", template, Map.of("Name", originalName));
        outputs(stack, "CREATE_COMPLETE");
        AtomicBoolean recreateBeforeDelete = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (recreateBeforeDelete.compareAndSet(true, false)) {
                recreateScheduleViaApi(originalName, "default");
            }
            return invocation.callRealMethod();
        }).when(scheduler).deleteSchedule(originalName, "default", "us-east-1");
        try {
            cloudFormation(stack, "UpdateStack", template, Map.of("Name", replacementName));
            assertEquals(replacementName, outputs(stack, "UPDATE_COMPLETE").get("ScheduleRef"));
            assertFalse(recreateBeforeDelete.get());
            getSchedule(originalName, "default").then().statusCode(404);
            getSchedule(replacementName, "default").then().statusCode(200);
            deleteStack(stack);
            getSchedule(replacementName, "default").then().statusCode(404);
        } finally {
            doCallRealMethod().when(scheduler).deleteSchedule(originalName, "default", "us-east-1");
            given().delete("/schedules/" + originalName);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void pendingSnapshotRestoresTheRecreatedAddressBeforeAnotherUpdate() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-snapshot-owner-" + suffix;
        String name = "snapshot-foreign-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        prepareFailedRollback(stack, name, queue);
        allowOriginalRestore(name);
        recreateScheduleViaApi(name, "default");

        try {
            cloudFormation(stack, "UpdateStack", externalQueueTemplate("", queue),
                    Map.of("Name", name, "Expression", "rate(15 minutes)", "Payload", "new"));
            outputs(stack, "UPDATE_COMPLETE");
            getSchedule(name, "default").then().statusCode(200)
                    .body("ScheduleExpression", equalTo("rate(15 minutes)"))
                    .body("Target.Input", equalTo("payload:new"));
            assertFalse(scheduleResource(stack).getAttributes().containsKey("__FlociSchedulerUpdateSnapshot"));
            cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue),
                    Map.of("Name", name, "Expression", "rate(20 minutes)", "Payload", "failed"));
            outputs(stack, "UPDATE_ROLLBACK_COMPLETE");
            getSchedule(name, "default").then().statusCode(200)
                    .body("ScheduleExpression", equalTo("rate(15 minutes)"))
                    .body("Target.Input", equalTo("payload:new"));
            deleteStack(stack);
            getSchedule(name, "default").then().statusCode(404);
        } finally {
            given().delete("/schedules/" + name);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void pendingGroupMoveDisablesTheRecreatedDestinationBeforeCleanup() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-move-owner-" + suffix;
        String name = "move-foreign-" + suffix;
        String group = "move-foreign-group-" + suffix;
        createGroup(group);
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", name));
        outputs(stack, "CREATE_COMPLETE");
        doThrow(new AwsException("InternalServerException", "orphan deletion unavailable", 500))
                .when(scheduler).deleteSchedule(name, group, "us-east-1");
        doCallRealMethod().doThrow(new AwsException("InternalServerException", "original restore unavailable", 500))
                .when(scheduler).updateSchedule(argThat(request -> name.equals(request.getName())
                        && "default".equals(request.getGroupName())
                        && "rate(5 minutes)".equals(request.getScheduleExpression())), eq("us-east-1"));
        cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue),
                Map.of("Name", name, "Group", group));
        outputs(stack, "UPDATE_ROLLBACK_FAILED");
        assertTrue(scheduleResource(stack).getAttributes().containsKey("__FlociSchedulerUpdateSnapshot"));
        doCallRealMethod().when(scheduler).deleteSchedule(name, group, "us-east-1");
        allowOriginalRestore(name);
        recreateScheduleViaApi(name, group, "ENABLED");
        AtomicBoolean pausedRecreatedDestination = new AtomicBoolean(false);
        doAnswer(invocation -> {
            ScheduleRequest request = invocation.getArgument(0);
            assertEquals("DISABLED", request.getState());
            assertEquals("out-of-band", request.getTarget().getInput());
            pausedRecreatedDestination.set(true);
            return invocation.callRealMethod();
        }).when(scheduler).updateSchedule(argThat(request -> name.equals(request.getName())
                && group.equals(request.getGroupName())), eq("us-east-1"));

        try {
            cloudFormation(stack, "UpdateStack", externalQueueTemplate("", queue),
                    Map.of("Name", name, "Expression", "rate(15 minutes)", "Payload", "new"));
            outputs(stack, "UPDATE_COMPLETE");
            assertTrue(pausedRecreatedDestination.get());
            getSchedule(name, group).then().statusCode(404);
            getSchedule(name, "default").then().statusCode(200)
                    .body("ScheduleExpression", equalTo("rate(15 minutes)"))
                    .body("Target.Input", equalTo("payload:new"));
            assertFalse(scheduleResource(stack).getAttributes().containsKey("__FlociSchedulerUpdateSnapshot"));
            deleteStack(stack);
        } finally {
            given().queryParam("groupName", group).delete("/schedules/" + name);
            deleteGroup(group);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void conditionalNoValueScalarsCreateGeneratedScheduleWithDefaults() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-no-value-" + suffix;
        Queue queue = sqs.createQueue("conditional-" + suffix, Map.of(), "us-east-1");
        String conditional = conditionalScheduleTemplate(queue, true, true, false);
        try {
            cloudFormation(stack, "CreateStack", conditional,
                    Map.of("Name", "unused-" + suffix, "UseOptions", "false"));
            Map<String, String> output = outputs(stack, "CREATE_COMPLETE");
            String generated = output.get("ScheduleRef");
            assertFalse(generated.equals("unused-" + suffix));
            JsonNode schedule = new ObjectMapper().readTree(getSchedule(generated, "default")
                    .then().statusCode(200).extract().asString());
            assertEquals(output.get("ScheduleArn"), schedule.path("Arn").asText());
            assertEquals("default", schedule.path("GroupName").asText());
            assertEquals("ENABLED", schedule.path("State").asText());
            assertOptionalScalarsAbsent(schedule);
        } finally {
            deleteStack(stack);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void conditionalNoValueNestedTargetAndWindowPropertiesAreOmitted() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-nested-no-value-" + suffix;
        String name = "nested-" + suffix;
        Queue queue = sqs.createQueue("conditional-" + suffix, Map.of(), "us-east-1");
        try {
            cloudFormation(stack, "CreateStack", conditionalScheduleTemplate(queue, false, false, true),
                    Map.of("Name", name, "UseOptions", "false"));
            Map<String, String> output = outputs(stack, "CREATE_COMPLETE");
            JsonNode schedule = new ObjectMapper().readTree(getSchedule(name, "default")
                    .then().statusCode(200).extract().asString());
            assertEquals(name, output.get("ScheduleRef"));
            assertEquals(output.get("ScheduleArn"), schedule.path("Arn").asText());
            assertEquals("OFF", schedule.path("FlexibleTimeWindow").path("Mode").asText());
            assertFalse(schedule.path("FlexibleTimeWindow").has("MaximumWindowInMinutes"));
            assertFalse(schedule.path("Target").has("RetryPolicy"));
            assertFalse(schedule.path("Target").has("DeadLetterConfig"));
            assertFalse(schedule.path("Target").has("Input"));
            assertEquals(sqs.getQueueAttributes(queue.getQueueUrl(), List.of("QueueArn"), "us-east-1")
                    .get("QueueArn"), schedule.path("Target").path("Arn").asText());
            assertTrue(schedule.path("Target").path("RoleArn").asText().endsWith(":role/scheduler"));
        } finally {
            deleteStack(stack);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void conditionalUpdatesRemoveAndRestoreOptionalPropertiesInPlace() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-conditional-update-" + suffix;
        String name = "conditional-update-" + suffix;
        Queue queue = sqs.createQueue("conditional-" + suffix, Map.of(), "us-east-1");
        String conditional = conditionalScheduleTemplate(queue, false, true, true);
        try {
            cloudFormation(stack, "CreateStack", conditional, Map.of("Name", name, "UseOptions", "true"));
            Map<String, String> created = outputs(stack, "CREATE_COMPLETE");
            JsonNode before = new ObjectMapper().readTree(getSchedule(name, "default")
                    .then().statusCode(200).extract().asString());
            assertEquals("conditional", before.path("Description").asText());
            assertEquals(15, before.path("FlexibleTimeWindow").path("MaximumWindowInMinutes").asInt());
            assertEquals(2, before.path("Target").path("RetryPolicy").path("MaximumRetryAttempts").asInt());

            cloudFormation(stack, "UpdateStack", conditional, Map.of("Name", name, "UseOptions", "false"));
            assertEquals(created, outputs(stack, "UPDATE_COMPLETE"));
            JsonNode omitted = new ObjectMapper().readTree(getSchedule(name, "default")
                    .then().statusCode(200).extract().asString());
            assertEquals("ENABLED", omitted.path("State").asText());
            assertOptionalScalarsAbsent(omitted);
            assertFalse(omitted.path("Target").has("Input"));
            assertFalse(omitted.path("Target").has("RetryPolicy"));
            assertFalse(omitted.path("Target").has("DeadLetterConfig"));
            assertEquals("OFF", omitted.path("FlexibleTimeWindow").path("Mode").asText());
            assertFalse(omitted.path("FlexibleTimeWindow").has("MaximumWindowInMinutes"));

            cloudFormation(stack, "UpdateStack", conditional, Map.of("Name", name, "UseOptions", "true"));
            assertEquals(created, outputs(stack, "UPDATE_COMPLETE"));
            JsonNode restored = new ObjectMapper().readTree(getSchedule(name, "default")
                    .then().statusCode(200).extract().asString());
            assertEquals(before.path("Target"), restored.path("Target"));
            assertEquals(before.path("FlexibleTimeWindow"), restored.path("FlexibleTimeWindow"));
            for (String field : List.of("Description", "State", "ScheduleExpressionTimezone", "StartDate",
                    "EndDate", "KmsKeyArn")) {
                assertEquals(before.get(field), restored.get(field), field);
            }
        } finally {
            deleteStack(stack);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void conditionalAddressUpdatesReplaceAndCleanOldSchedules() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-conditional-address-" + suffix;
        String name = "conditional-address-" + suffix;
        String group = "conditional-group-" + suffix;
        Queue queue = sqs.createQueue("conditional-" + suffix, Map.of(), "us-east-1");
        createGroup(group);
        String conditional = conditionalScheduleTemplate(queue, true, false, false);
        try {
            cloudFormation(stack, "CreateStack", conditional,
                    Map.of("Name", name, "Group", group, "UseOptions", "true"));
            Map<String, String> named = outputs(stack, "CREATE_COMPLETE");
            getSchedule(name, group).then().statusCode(200).body("Arn", equalTo(named.get("ScheduleArn")));

            cloudFormation(stack, "UpdateStack", conditional,
                    Map.of("Name", name, "Group", group, "UseOptions", "false"));
            Map<String, String> generated = outputs(stack, "UPDATE_COMPLETE");
            String generatedName = generated.get("ScheduleRef");
            assertFalse(name.equals(generatedName));
            getSchedule(name, group).then().statusCode(404);
            getSchedule(generatedName, "default").then().statusCode(200)
                    .body("Arn", equalTo(generated.get("ScheduleArn")));

            cloudFormation(stack, "UpdateStack", conditional,
                    Map.of("Name", name, "Group", group, "UseOptions", "false", "Payload", "second"));
            assertEquals(generated, outputs(stack, "UPDATE_COMPLETE"));
            getSchedule(generatedName, "default").then().statusCode(200).body("Target.Input", equalTo("payload:second"));

            cloudFormation(stack, "UpdateStack", conditional,
                    Map.of("Name", name, "Group", group, "UseOptions", "true"));
            assertEquals(named, outputs(stack, "UPDATE_COMPLETE"));
            getSchedule(generatedName, "default").then().statusCode(404);
            getSchedule(name, group).then().statusCode(200);
        } finally {
            deleteStack(stack);
            getSchedule(name, group).then().statusCode(404);
            deleteGroup(group);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Target", "FlexibleTimeWindow", "ScheduleExpression", "Target.Arn", "Target.RoleArn",
            "FlexibleTimeWindow.Mode"})
    void requiredPropertiesSelectedAsNoValueStillFail(String property) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-required-no-value-" + suffix;
        String name = "required-" + suffix;
        Queue queue = sqs.createQueue("conditional-" + suffix, Map.of(), "us-east-1");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(conditionalScheduleTemplate(queue, false, false, false));
        ObjectNode props = (ObjectNode) root.path("Resources").path("Schedule").path("Properties");
        String[] path = property.split("\\.");
        ObjectNode parent = path.length == 1 ? props : (ObjectNode) props.get(path[0]);
        String field = path[path.length - 1];
        parent.set(field, conditionalOption(parent.get(field)));
        try {
            cloudFormation(stack, "CreateStack", root.toString(), Map.of("Name", name, "UseOptions", "false"));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("ROLLBACK_COMPLETE", state.status(), property);
            assertTrue(state.reason().toLowerCase(Locale.ROOT).contains(field.toLowerCase(Locale.ROOT)), state.reason());
            getSchedule(name, "default").then().statusCode(404);
        } finally {
            deleteStack(stack);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Name", "GroupName", "State", "StartDate", "EndDate", "ScheduleExpression"})
    void literalEmptyInvalidScalarsAreNotTreatedAsNoValue(String property) throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-empty-control-" + suffix;
        String name = "empty-control-" + suffix;
        Queue queue = sqs.createQueue("conditional-" + suffix, Map.of(), "us-east-1");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(externalQueueTemplate("", queue));
        ((ObjectNode) root.path("Resources").path("Schedule").path("Properties")).put(property, "");
        try {
            cloudFormation(stack, "CreateStack", root.toString(), Map.of("Name", name));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
            assertEquals("ROLLBACK_COMPLETE", state.status(), property);
            assertTrue(state.reason().contains(property), state.reason());
            getSchedule(name, "default").then().statusCode(404);
        } finally {
            deleteStack(stack);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Name", "GroupName", "Description", "Target.Input"})
    void conditionalObjectAndArrayScalarsAreRejected(String property) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (JsonNode invalid : List.of(mapper.createObjectNode(), mapper.createArrayNode())) {
            String suffix = Long.toString(System.nanoTime(), 36);
            String stack = "cfn-schedule-shape-control-" + suffix;
            String name = "shape-control-" + suffix;
            Queue queue = sqs.createQueue("conditional-" + suffix, Map.of(), "us-east-1");
            ObjectNode root = (ObjectNode) mapper.readTree(conditionalScheduleTemplate(queue, false, false, false));
            ObjectNode props = (ObjectNode) root.path("Resources").path("Schedule").path("Properties");
            String[] path = property.split("\\.");
            ObjectNode parent = path.length == 1 ? props : (ObjectNode) props.get(path[0]);
            parent.set(path[path.length - 1], conditionalOption(invalid));
            try {
                cloudFormation(stack, "CreateStack", root.toString(), Map.of("Name", name, "UseOptions", "true"));
                assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status(), property + invalid);
                getSchedule(name, "default").then().statusCode(404);
            } finally {
                deleteStack(stack);
                sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
            }
        }
    }

    @Test
    void literalEmptyDescriptionAndInputRemainPresent() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-empty-allowed-" + suffix;
        String name = "empty-allowed-" + suffix;
        Queue queue = sqs.createQueue("conditional-" + suffix, Map.of(), "us-east-1");
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(externalQueueTemplate("", queue));
        ObjectNode props = (ObjectNode) root.path("Resources").path("Schedule").path("Properties");
        props.put("Description", "");
        ((ObjectNode) props.get("Target")).put("Input", "");
        try {
            cloudFormation(stack, "CreateStack", root.toString(), Map.of("Name", name));
            outputs(stack, "CREATE_COMPLETE");
            JsonNode schedule = mapper.readTree(getSchedule(name, "default").then().statusCode(200).extract().asString());
            assertTrue(schedule.has("Description"));
            assertEquals("", schedule.path("Description").asText());
            assertTrue(schedule.path("Target").has("Input"));
            assertEquals("", schedule.path("Target").path("Input").asText());
        } finally {
            deleteStack(stack);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    private static void assertOptionalScalarsAbsent(JsonNode schedule) {
        for (String field : List.of("Description", "ScheduleExpressionTimezone", "StartDate", "EndDate", "KmsKeyArn")) {
            assertFalse(schedule.has(field), field);
        }
    }

    private String conditionalScheduleTemplate(Queue queue, boolean conditionalAddress,
                                                boolean scalarOptions, boolean nestedOptions) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(externalQueueTemplate("", queue));
        ((ObjectNode) root.path("Parameters")).putObject("UseOptions").put("Type", "String").put("Default", "true");
        root.putObject("Conditions").set("UseOptions", mapper.readTree("""
                {"Fn::Equals":[{"Ref":"UseOptions"},"true"]}
                """));
        ObjectNode props = (ObjectNode) root.path("Resources").path("Schedule").path("Properties");
        props.put("ScheduleExpression", "at(2100-01-01T00:00:00)");
        if (conditionalAddress) {
            props.set("Name", conditionalOption(mapper.readTree("{\"Ref\":\"Name\"}")));
            props.set("GroupName", conditionalOption(mapper.readTree("{\"Ref\":\"Group\"}")));
        }
        if (scalarOptions) {
            for (Map.Entry<String, String> field : Map.of("Description", "conditional", "State", "DISABLED",
                    "ScheduleExpressionTimezone", "UTC", "StartDate", "2099-01-01T00:00:00Z",
                    "EndDate", "2101-01-01T00:00:00Z").entrySet()) {
                props.set(field.getKey(), conditionalOption(mapper.valueToTree(field.getValue())));
            }
            props.set("KmsKeyArn", conditionalOption(mapper.readTree("""
                    {"Fn::Sub":"arn:${AWS::Partition}:kms:${AWS::Region}:${AWS::AccountId}:key/conditional"}
                    """)));
        }
        if (nestedOptions) {
            ObjectNode window = (ObjectNode) props.get("FlexibleTimeWindow");
            window.set("Mode", mapper.readTree("{\"Fn::If\":[\"UseOptions\",\"FLEXIBLE\",\"OFF\"]}"));
            window.set("MaximumWindowInMinutes", conditionalOption(mapper.valueToTree(15)));
            ObjectNode target = (ObjectNode) props.get("Target");
            target.set("Input", conditionalOption(target.get("Input")));
            target.set("RetryPolicy", conditionalOption(mapper.readTree("{\"MaximumRetryAttempts\":2}")));
            target.set("DeadLetterConfig", conditionalOption(mapper.createObjectNode().set("Arn", target.get("Arn"))));
        }
        return root.toString();
    }

    private static JsonNode conditionalOption(JsonNode value) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode noValue = mapper.createObjectNode().put("Ref", "AWS::NoValue");
        return mapper.createObjectNode().set("Fn::If", mapper.createArrayNode().add("UseOptions").add(value).add(noValue));
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

    private static void recreateScheduleViaApi(String name, String group) throws Exception {
        recreateScheduleViaApi(name, group, "DISABLED");
    }

    private static void recreateScheduleViaApi(String name, String group, String state) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode original = mapper.readTree(getSchedule(name, group).then().statusCode(200).extract().asString());
        ObjectNode target = original.path("Target").deepCopy();
        target.put("Input", "out-of-band");
        ObjectNode request = mapper.createObjectNode().put("GroupName", group)
                .put("ScheduleExpression", "rate(30 minutes)").put("State", state);
        request.set("Target", target);
        request.set("FlexibleTimeWindow", mapper.createObjectNode().put("Mode", "OFF"));
        given().queryParam("groupName", group).delete("/schedules/" + name).then().statusCode(200);
        given().contentType("application/json").body(request.toString()).post("/schedules/" + name)
                .then().statusCode(200);
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
