package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.OperationEvent;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EcsParameters;
import software.amazon.awssdk.services.eventbridge.model.Target;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An AWS::Events::Rule target's dead-letter queue, retry policy, role and ECS parameters, read
 * back through the EventBridge SDK client, and the members of the wrong type CloudFormation
 * refuses. Measured on AWS (eu-west-1, 2026-10-18): the target keeps all four, EcsParameters in
 * the EventBridge API's spelling; a Ref to a CommaDelimitedList is a list of subnets and a Ref to a
 * String is refused; a retry attempt count of 4.5 fails template validation, read through
 * DescribeEvents on AWS, and an EnableECSManagedTags of "yes" fails the handler's model
 * validation. Floci reports both on the resource's failed event.
 */
@DisplayName("CloudFormation AWS::Events::Rule targets")
class CloudFormationEventsRuleTargetTest {

    /** Floci finishes these stacks in milliseconds; AWS takes minutes for the queues. */
    private static final Duration DEPLOY_TIMEOUT =
            TestFixtures.isRealAws() ? Duration.ofMinutes(10) : Duration.ofSeconds(10);
    private static final String KEPT_STACK = TestFixtures.uniqueName("compat-cfn-rule-targets");
    private static final String STRING_SUBNETS_STACK = TestFixtures.uniqueName("compat-cfn-rule-subnets");
    private static final String FLOAT_STACK = TestFixtures.uniqueName("compat-cfn-rule-float");
    private static final String BOOLEAN_STACK = TestFixtures.uniqueName("compat-cfn-rule-boolean");
    private static final String INPUT_STACK = TestFixtures.uniqueName("compat-cfn-rule-input");
    private static final String CLUSTER =
            "{\"Fn::Sub\": \"arn:${AWS::Partition}:ecs:${AWS::Region}:${AWS::AccountId}:cluster/compat-absent\"}";
    private static final String ROLE =
            "{\"Fn::Sub\": \"arn:${AWS::Partition}:iam::${AWS::AccountId}:role/compat-absent\"}";
    private static final String TASK_DEFINITION = "{\"Fn::Sub\": \"arn:${AWS::Partition}:ecs:${AWS::Region}"
            + ":${AWS::AccountId}:task-definition/compat-absent:1\"}";

    private static CloudFormationClient cfn;
    private static EventBridgeClient eventBridge;

    @BeforeAll
    static void setup() {
        cfn = TestFixtures.cloudFormationClient();
        eventBridge = TestFixtures.eventBridgeClient();
    }

    @AfterAll
    static void cleanup() {
        if (cfn != null) {
            // DeleteStack answers for a stack that is gone too, so a failure here is a real one.
            for (String stack : List.of(KEPT_STACK, STRING_SUBNETS_STACK, FLOAT_STACK, BOOLEAN_STACK, INPUT_STACK)) {
                cfn.deleteStack(request -> request.stackName(stack));
            }
            cfn.close();
        }
        if (eventBridge != null) {
            eventBridge.close();
        }
    }

    @Test
    @DisplayName("a target keeps its dead-letter queue, retry policy, role and ECS parameters")
    void aTargetKeepsWhatPutTargetsWouldSet() throws InterruptedException {
        cfn.createStack(request -> request.stackName(KEPT_STACK).templateBody("""
                {
                  "Parameters": {"Subnets": {"Type": "CommaDelimitedList",
                    "Default": "subnet-0123456789abcdef0,subnet-0123456789abcdef1"}},
                  "Resources": {
                    "Dlq": {"Type": "AWS::SQS::Queue"},
                    "Queue": {"Type": "AWS::SQS::Queue"},
                    "Delivery": {"Type": "AWS::Events::Rule", "Properties": {
                      "EventPattern": {"source": ["compat.rule.targets"]},
                      "Targets": [{"Id": "Queue", "Arn": {"Fn::GetAtt": ["Queue", "Arn"]},
                        "DeadLetterConfig": {"Arn": {"Fn::GetAtt": ["Dlq", "Arn"]}},
                        "RetryPolicy": {"MaximumRetryAttempts": "4", "MaximumEventAgeInSeconds": 7200}}]}},
                    "RunTask": {"Type": "AWS::Events::Rule", "Properties": {
                      "EventPattern": {"source": ["compat.rule.targets"]},
                      "Targets": [{"Id": "Task", "Arn": %s, "RoleArn": %s,
                        "EcsParameters": {"TaskDefinitionArn": %s, "TaskCount": 2, "LaunchType": "FARGATE",
                          "NetworkConfiguration": {"AwsVpcConfiguration": {"Subnets": {"Ref": "Subnets"},
                            "AssignPublicIp": "DISABLED"}},
                          "PlacementConstraints": [{"Type": "memberOf", "Expression": "attribute:ecs.os-type == linux"}],
                          "TagList": [{"Key": "team", "Value": "jobs"}]}}]}}
                  }
                }
                """.formatted(CLUSTER, ROLE, TASK_DEFINITION)));
        assertThat(TestFixtures.awaitStackTerminal(cfn, KEPT_STACK, DEPLOY_TIMEOUT)).isEqualTo("CREATE_COMPLETE");

        Target delivery = onlyTarget("Delivery");
        String dlqUrl = physicalId("Dlq");
        assertThat(delivery.deadLetterConfig().arn()).endsWith(":" + dlqUrl.substring(dlqUrl.lastIndexOf('/') + 1));
        assertThat(delivery.retryPolicy().maximumRetryAttempts()).isEqualTo(4);
        assertThat(delivery.retryPolicy().maximumEventAgeInSeconds()).isEqualTo(7200);

        Target task = onlyTarget("RunTask");
        assertThat(task.roleArn()).endsWith(":role/compat-absent");
        EcsParameters ecs = task.ecsParameters();
        assertThat(ecs.taskDefinitionArn()).endsWith(":task-definition/compat-absent:1");
        assertThat(ecs.taskCount()).isEqualTo(2);
        assertThat(ecs.networkConfiguration().awsvpcConfiguration().subnets())
                .containsExactly("subnet-0123456789abcdef0", "subnet-0123456789abcdef1");
        assertThat(ecs.placementConstraints()).singleElement().satisfies(constraint -> {
            assertThat(constraint.typeAsString()).isEqualTo("memberOf");
            assertThat(constraint.expression()).isEqualTo("attribute:ecs.os-type == linux");
        });
        assertThat(ecs.tags()).singleElement().satisfies(tag -> {
            assertThat(tag.key()).isEqualTo("team");
            assertThat(tag.value()).isEqualTo("jobs");
        });
    }

    @Test
    @DisplayName("subnets from a String parameter fail the stack")
    void subnetsFromAStringParameterFailTheStack() throws InterruptedException {
        cfn.createStack(request -> request.stackName(STRING_SUBNETS_STACK).templateBody(ecsRule(
                "{\"Subnets\": {\"Type\": \"String\", \"Default\": \"subnet-0123456789abcdef0\"}}",
                "\"NetworkConfiguration\": {\"AwsVpcConfiguration\": {\"Subnets\": {\"Ref\": \"Subnets\"}}}")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, STRING_SUBNETS_STACK, DEPLOY_TIMEOUT))
                .isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(STRING_SUBNETS_STACK)).anyMatch(reason ->
                reason.contains("Property [Subnets] expected type: JSONArray, found: String"));
    }

    @Test
    @DisplayName("a retry attempt count of 4.5 fails template validation")
    void aFractionalRetryAttemptCountFailsTheStack() throws InterruptedException {
        cfn.createStack(request -> request.stackName(FLOAT_STACK).templateBody("""
                {"Resources": {"Rule": {"Type": "AWS::Events::Rule", "Properties": {
                  "EventPattern": {"source": ["compat.rule.targets"]},
                  "Targets": [{"Id": "T", "Arn": %s, "RoleArn": %s, "RetryPolicy": {"MaximumRetryAttempts": 4.5}}]}}}}
                """.formatted(CLUSTER, ROLE)));
        assertThat(TestFixtures.awaitStackTerminal(cfn, FLOAT_STACK, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(FLOAT_STACK)).anyMatch(reason ->
                reason.contains("Property [MaximumRetryAttempts] expected type: Integer, found: Float"));
    }

    @Test
    @DisplayName("an EnableECSManagedTags of \"yes\" fails the handler's model validation")
    void aBooleanThatIsNotTrueOrFalseFailsTheStack() throws InterruptedException {
        cfn.createStack(request -> request.stackName(BOOLEAN_STACK).templateBody(ecsRule("{}",
                "\"EnableECSManagedTags\": \"yes\"")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, BOOLEAN_STACK, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(BOOLEAN_STACK)).anyMatch(reason -> reason.contains(
                "Model validation failed (#/Targets/0/EcsParameters/EnableECSManagedTags:"
                        + " expected type: Boolean, found: String)"));
    }

    /** Measured on AWS (eu-west-1, 2026-10-19): every target member is typed, Input among them. */
    @Test
    @DisplayName("an Input that is an object fails template validation")
    void anInputThatIsAnObjectFailsTheStack() throws InterruptedException {
        cfn.createStack(request -> request.stackName(INPUT_STACK).templateBody("""
                {"Resources": {"Rule": {"Type": "AWS::Events::Rule", "Properties": {
                  "EventPattern": {"source": ["compat.rule.targets"]},
                  "Targets": [{"Id": "T", "Arn": %s, "RoleArn": %s, "Input": {"a": 1}}]}}}}
                """.formatted(CLUSTER, ROLE)));
        assertThat(TestFixtures.awaitStackTerminal(cfn, INPUT_STACK, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(INPUT_STACK)).anyMatch(reason ->
                reason.contains("Property [Input] expected type: String, found: JSONObject"));
    }

    private static String ecsRule(String parameters, String ecsMembers) {
        return """
                {"Parameters": %s,
                 "Resources": {"Rule": {"Type": "AWS::Events::Rule", "Properties": {
                   "EventPattern": {"source": ["compat.rule.targets"]},
                   "Targets": [{"Id": "T", "Arn": %s, "RoleArn": %s,
                     "EcsParameters": {"TaskDefinitionArn": %s, %s}}]}}}}
                """.formatted(parameters, CLUSTER, ROLE, TASK_DEFINITION, ecsMembers);
    }

    private static String physicalId(String logicalId) {
        return cfn.describeStackResource(request -> request.stackName(KEPT_STACK).logicalResourceId(logicalId))
                .stackResourceDetail().physicalResourceId();
    }

    private static Target onlyTarget(String logicalId) {
        List<Target> targets = eventBridge.listTargetsByRule(request -> request.rule(physicalId(logicalId))).targets();
        assertThat(targets).hasSize(1);
        return targets.get(0);
    }

    /** On AWS, template validation's reasons are read through DescribeEvents, the handler's on the stack events. */
    private static List<String> reasons(String stackName) {
        List<String> reasons = new ArrayList<>();
        if (TestFixtures.isRealAws()) {
            cfn.describeEvents(request -> request.stackName(stackName)).operationEvents().stream()
                    .map(OperationEvent::validationStatusReason)
                    .filter(reason -> reason != null)
                    .forEach(reasons::add);
        }
        cfn.describeStackEvents(request -> request.stackName(stackName)).stackEvents().stream()
                .map(StackEvent::resourceStatusReason)
                .filter(reason -> reason != null)
                .forEach(reasons::add);
        return reasons;
    }
}
