package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.scheduler.ScheduleInvoker;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
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
    void deletionRetryDoesNotDeleteAnotherStacksRecreatedSchedule() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-old-owner-" + suffix;
        String otherStack = "cfn-schedule-new-owner-" + suffix;
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
        Instant orphanCreation = scheduler.getSchedule(name, group, "us-east-1").getCreationDate();
        cloudFormation(stack, "DeleteStack", null, Map.of());
        outputs(stack, "DELETE_FAILED");
        assertTrue(scheduleResource(stack).getAttributes().containsKey("__FlociReplacementCleanup"));

        doCallRealMethod().when(scheduler).deleteSchedule(name, group, "us-east-1");
        given().queryParam("groupName", group).delete("/schedules/" + name).then().statusCode(200);
        cloudFormation(otherStack, "CreateStack", externalQueueTemplate("", queue),
                Map.of("Name", name, "Group", group, "Payload", "foreign"));
        outputs(otherStack, "CREATE_COMPLETE");
        assertFalse(orphanCreation.equals(scheduler.getSchedule(name, group, "us-east-1").getCreationDate()));

        try {
            deleteStack(stack);
            getSchedule(name, group).then().statusCode(200).body("Target.Input", equalTo("payload:foreign"));
            assertEquals(name, outputs(otherStack, "CREATE_COMPLETE").get("ScheduleRef"));
        } finally {
            deleteStack(otherStack);
            deleteGroup(group);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void repeatedFailedReplacementTracksItsNewIncarnationAtTheSameAddress() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-reused-debt-" + suffix;
        String originalName = "original-debt-" + suffix;
        String replacementName = "replacement-debt-" + suffix;
        String replacementAddress = "default/" + replacementName;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", originalName));
        outputs(stack, "CREATE_COMPLETE");
        String originalIncarnation = scheduler.getSchedule(originalName, "default", "us-east-1").getIncarnationId();
        doThrow(new AwsException("InternalServerException", "replacement deletion unavailable", 500))
                .when(scheduler).deleteSchedule(replacementName, "default", "us-east-1");

        cloudFormation(stack, "UpdateStack", externalQueueTemplate(FAILURE, queue),
                Map.of("Name", replacementName, "Payload", "first-failed"));
        outputs(stack, "UPDATE_ROLLBACK_FAILED");
        String retiredIncarnation = scheduler.getSchedule(replacementName, "default", "us-east-1").getIncarnationId();
        assertEquals(retiredIncarnation,
                scheduleResource(stack).getAttributes().get("__FlociSchedulerIncarnation:" + replacementAddress));
        doCallRealMethod().when(scheduler).deleteSchedule(replacementName, "default", "us-east-1");
        given().delete("/schedules/" + replacementName).then().statusCode(200);
        doThrow(new AwsException("InternalServerException", "new incarnation deletion unavailable", 500))
                .when(scheduler).deleteSchedule(replacementName, "default", "us-east-1");

        // Change both resources so the engine actually retries the replacement and its failing dependent.
        String repeatedFailure = FAILURE.replace("\"SecretString\":\"explicit\"",
                "\"SecretString\":\"explicit-second\"");
        cloudFormation(stack, "UpdateStack", externalQueueTemplate(repeatedFailure, queue),
                Map.of("Name", replacementName, "Payload", "second-failed"));
        outputs(stack, "UPDATE_ROLLBACK_FAILED");
        String freshIncarnation = scheduler.getSchedule(replacementName, "default", "us-east-1").getIncarnationId();
        assertFalse(retiredIncarnation.equals(freshIncarnation));
        StackResource resource = scheduleResource(stack);
        assertEquals(originalName, resource.getPhysicalId());
        assertEquals(originalIncarnation,
                resource.getAttributes().get("__FlociSchedulerIncarnation:default/" + originalName));
        assertEquals(freshIncarnation,
                resource.getAttributes().get("__FlociSchedulerIncarnation:" + replacementAddress));
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

    @Test
    void nameReplacementRejectsLegacyCurrentOwnershipBeforeCreatingDestination() {
        for (String proof : List.of("missing", "blank")) {
            String suffix = Long.toString(System.nanoTime(), 36);
            String stack = "cfn-schedule-legacy-current-" + suffix;
            String originalName = "legacy-current-" + suffix;
            String replacementName = "legacy-destination-" + suffix;
            Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
            cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", originalName));
            outputs(stack, "CREATE_COMPLETE");
            StackResource resource = scheduleResource(stack);
            String ownershipKey = "__FlociSchedulerIncarnation:default/" + originalName;
            String originalIncarnation = resource.getAttributes().get(ownershipKey);
            Schedule current = scheduler.getSchedule(originalName, "default", "us-east-1");
            // Simulate the private fields of an older persisted record, not a runtime migration.
            if ("missing".equals(proof)) {
                resource.getAttributes().remove(ownershipKey);
                current.setIncarnationId(null);
            } else {
                resource.getAttributes().put(ownershipKey, " ");
                current.setIncarnationId(" ");
            }
            try {
                cloudFormation(stack, "UpdateStack", externalQueueTemplate("", queue),
                        Map.of("Name", replacementName));
                outputs(stack, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals(originalName, scheduleResource(stack).getPhysicalId());
                getSchedule(originalName, "default").then().statusCode(200)
                        .body("Target.Input", equalTo("payload:first"));
                getSchedule(replacementName, "default").then().statusCode(404);
                assertFalse(scheduleResource(stack).getAttributes().containsKey("__FlociReplacementCleanup"));
            } finally {
                current.setIncarnationId(originalIncarnation);
                scheduleResource(stack).getAttributes().put(ownershipKey, originalIncarnation);
                deleteStack(stack);
                sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
            }
        }
    }

    @Test
    void replacementCleanupPreservesForeignRecreationAfterCurrentOwnershipWasChecked() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-preflight-race-" + suffix;
        String foreignStack = "cfn-schedule-preflight-foreign-" + suffix;
        String originalName = "preflight-current-" + suffix;
        String replacementName = "preflight-destination-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        String template = externalQueueTemplate("", queue);
        cloudFormation(stack, "CreateStack", template, Map.of("Name", originalName));
        outputs(stack, "CREATE_COMPLETE");
        String originalIncarnation = scheduler.getSchedule(originalName, "default", "us-east-1").getIncarnationId();
        AtomicBoolean recreateAfterRead = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Schedule observed = (Schedule) invocation.callRealMethod();
            if (recreateAfterRead.compareAndSet(true, false)) {
                given().delete("/schedules/" + originalName).then().statusCode(200);
                cloudFormation(foreignStack, "CreateStack", template,
                        Map.of("Name", originalName, "Payload", "foreign"));
                outputs(foreignStack, "CREATE_COMPLETE");
            }
            return observed;
        }).when(scheduler).getSchedule(originalName, "default", "us-east-1");

        try {
            cloudFormation(stack, "UpdateStack", template, Map.of("Name", replacementName, "Payload", "owned"));
            assertEquals(replacementName, outputs(stack, "UPDATE_COMPLETE").get("ScheduleRef"));
            assertFalse(recreateAfterRead.get());
            assertFalse(originalIncarnation.equals(
                    scheduler.getSchedule(originalName, "default", "us-east-1").getIncarnationId()));
            getSchedule(originalName, "default").then().statusCode(200)
                    .body("Target.Input", equalTo("payload:foreign"));
            getSchedule(replacementName, "default").then().statusCode(200)
                    .body("Target.Input", equalTo("payload:owned"));
            deleteStack(stack);
            getSchedule(replacementName, "default").then().statusCode(404);
            getSchedule(originalName, "default").then().statusCode(200)
                    .body("Target.Input", equalTo("payload:foreign"));
        } finally {
            doCallRealMethod().when(scheduler).getSchedule(originalName, "default", "us-east-1");
            deleteStack(foreignStack);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void nameReplacementRejectsForeignCurrentOwnershipBeforeCreatingDestination() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-foreign-current-" + suffix;
        String foreignStack = "cfn-schedule-foreign-recreation-" + suffix;
        String originalName = "foreign-current-" + suffix;
        String replacementName = "foreign-destination-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", originalName));
        outputs(stack, "CREATE_COMPLETE");
        given().delete("/schedules/" + originalName).then().statusCode(200);
        cloudFormation(foreignStack, "CreateStack", externalQueueTemplate("", queue),
                Map.of("Name", originalName, "Payload", "foreign"));
        outputs(foreignStack, "CREATE_COMPLETE");
        try {
            cloudFormation(stack, "UpdateStack", externalQueueTemplate("", queue), Map.of("Name", replacementName));
            outputs(stack, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(originalName, scheduleResource(stack).getPhysicalId());
            getSchedule(originalName, "default").then().statusCode(200)
                    .body("Target.Input", equalTo("payload:foreign"));
            getSchedule(replacementName, "default").then().statusCode(404);
            deleteStack(stack);
            getSchedule(originalName, "default").then().statusCode(200)
                    .body("Target.Input", equalTo("payload:foreign"));
        } finally {
            deleteStack(foreignStack);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void stackDeletionDoesNotDeleteItsRecreatedCurrentSchedule() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-current-owner-" + suffix;
        String otherStack = "cfn-schedule-current-foreign-" + suffix;
        String name = "current-foreign-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        cloudFormation(stack, "CreateStack", externalQueueTemplate("", queue), Map.of("Name", name));
        outputs(stack, "CREATE_COMPLETE");
        given().delete("/schedules/" + name).then().statusCode(200);
        cloudFormation(otherStack, "CreateStack", externalQueueTemplate("", queue),
                Map.of("Name", name, "Payload", "foreign"));
        outputs(otherStack, "CREATE_COMPLETE");

        try {
            deleteStack(stack);
            String body = getSchedule(name, "default").then().statusCode(200)
                    .body("Target.Input", equalTo("payload:foreign")).extract().asString();
            assertFalse(body.toLowerCase().contains("incarnation"));
            String list = given().queryParam("ScheduleGroup", "default").queryParam("NamePrefix", name)
                    .get("/schedules").then().statusCode(200).extract().asString();
            assertFalse(list.toLowerCase().contains("incarnation"));
        } finally {
            deleteStack(otherStack);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void pendingSnapshotCannotRestoreOverAnotherStacksRecreatedSchedule() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-snapshot-owner-" + suffix;
        String otherStack = "cfn-schedule-snapshot-foreign-" + suffix;
        String name = "snapshot-foreign-" + suffix;
        Queue queue = sqs.createQueue("external-" + suffix, Map.of(), "us-east-1");
        prepareFailedRollback(stack, name, queue);
        String snapshot = scheduleResource(stack).getAttributes().get("__FlociSchedulerUpdateSnapshot");
        allowOriginalRestore(name);
        given().delete("/schedules/" + name).then().statusCode(200);
        cloudFormation(otherStack, "CreateStack", externalQueueTemplate("", queue),
                Map.of("Name", name, "Expression", "rate(30 minutes)", "Payload", "foreign"));
        outputs(otherStack, "CREATE_COMPLETE");

        try {
            cloudFormation(stack, "UpdateStack", externalQueueTemplate("", queue),
                    Map.of("Name", name, "Expression", "rate(15 minutes)", "Payload", "new"));
            outputs(stack, "UPDATE_ROLLBACK_FAILED");
            getSchedule(name, "default").then().statusCode(200)
                    .body("ScheduleExpression", equalTo("rate(30 minutes)"))
                    .body("Target.Input", equalTo("payload:foreign"));
            assertEquals(snapshot, scheduleResource(stack).getAttributes().get("__FlociSchedulerUpdateSnapshot"));
            deleteStack(stack);
            getSchedule(name, "default").then().statusCode(200).body("Target.Input", equalTo("payload:foreign"));
        } finally {
            deleteStack(otherStack);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
    }

    @Test
    void pendingGroupMoveCannotDisableAnotherStacksRecreatedDestination() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "cfn-schedule-move-owner-" + suffix;
        String otherStack = "cfn-schedule-move-foreign-" + suffix;
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
        given().queryParam("groupName", group).delete("/schedules/" + name).then().statusCode(200);
        String foreignTemplate = externalQueueTemplate("", queue)
                .replace("\"State\":\"DISABLED\"", "\"State\":\"ENABLED\"");
        cloudFormation(otherStack, "CreateStack", foreignTemplate,
                Map.of("Name", name, "Group", group, "Payload", "foreign"));
        outputs(otherStack, "CREATE_COMPLETE");

        try {
            cloudFormation(stack, "UpdateStack", externalQueueTemplate("", queue),
                    Map.of("Name", name, "Expression", "rate(15 minutes)", "Payload", "new"));
            outputs(stack, "UPDATE_ROLLBACK_FAILED");
            getSchedule(name, group).then().statusCode(200)
                    .body("State", equalTo("ENABLED")).body("Target.Input", equalTo("payload:foreign"));
            assertTrue(scheduleResource(stack).getAttributes().containsKey("__FlociSchedulerUpdateSnapshot"));
            deleteStack(stack);
            getSchedule(name, group).then().statusCode(200).body("State", equalTo("ENABLED"));
        } finally {
            deleteStack(otherStack);
            deleteGroup(group);
            sqs.deleteQueue(queue.getQueueUrl(), "us-east-1");
        }
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
