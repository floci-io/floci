package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.scheduler.ScheduleInvoker;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Inject
    SchedulerService scheduler;

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

    private static String template(String policy, String name, String failure) {
        return TEMPLATE.formatted(policy, name, failure);
    }

    private String externalQueueTemplate(String failure, Queue queue) {
        // SQS currently cannot roll back an in-place queue update. An externally managed target
        // keeps these failure-path tests focused on the schedule's own rollback contract.
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
