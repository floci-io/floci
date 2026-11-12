package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.eventbridge.RuleScheduler;
import io.floci.aws.services.eventbridge.model.Rule;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationLegacyScheduleIntegrationTest {

    private static final String STACK_NAME = "legacy-schedule-stack";
    private static final String RULE_NAME = "legacy-schedule-rule";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Inject
    CloudFormationService cloudFormationService;

    @Inject
    EventBridgeService eventBridgeService;

    @Inject
    RuleScheduler scheduler;

    @Test
    void anUnrelatedStackUpdateKeepsTheLegacyRuleAndItsTimer() {
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, Region.US_EAST_1);
                EventBridgeClient events = AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, Region.US_EAST_1);
                SqsClient sqs = AwsTestClients.create(SqsClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            cfn.createStack(request -> request.stackName(STACK_NAME).templateBody(template("rate(1 minute)", 10)));
            CfnStackWaits.awaitStatus(STACK_NAME, "CREATE_COMPLETE");
            try {
                // Seed state that an older Floci version accepted, without weakening the API validator.
                String legacyTemplate = template("rate(1 minutes)", 10);
                Stack stack = cloudFormationService.describeStacks(STACK_NAME, REGION).getFirst();
                stack.setTemplateBody(legacyTemplate);
                stack.setOriginalTemplateBody(legacyTemplate);
                Rule stored = eventBridgeService.describeRule(RULE_NAME, null, REGION);
                stored.setScheduleExpression("rate(1 minutes)");
                String originalArn = stored.getArn();
                assertTrue(scheduler.isRunning(originalArn));

                cfn.updateStack(request -> request.stackName(STACK_NAME).templateBody(template("rate(1 minutes)", 20)));
                CfnStackWaits.awaitStatus(STACK_NAME, "UPDATE_COMPLETE");

                assertEquals(originalArn, events.describeRule(request -> request.name(RULE_NAME)).arn());
                assertEquals("rate(1 minutes)", events.describeRule(request -> request.name(RULE_NAME)).scheduleExpression());
                assertTrue(scheduler.isRunning(originalArn));
                String queueUrl = cfn.describeStackResource(request -> request.stackName(STACK_NAME)
                        .logicalResourceId("ChurnQueue")).stackResourceDetail().physicalResourceId();
                assertEquals("20", sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.VISIBILITY_TIMEOUT)).attributes()
                        .get(QueueAttributeName.VISIBILITY_TIMEOUT));

                EventBridgeException error = assertThrows(EventBridgeException.class,
                        () -> events.putRule(request -> request.name(RULE_NAME).scheduleExpression("rate(1 minutes)")));
                assertEquals("ValidationException", error.awsErrorDetails().errorCode());
            } finally {
                cfn.deleteStack(request -> request.stackName(STACK_NAME));
                CfnStackWaits.awaitStackDeleted(STACK_NAME);
            }
        }
    }

    private static String template(String expression, int visibilityTimeout) {
        return """
                {"Resources": {
                  "ScheduledRule": {"Type": "AWS::Events::Rule", "Properties": {
                    "Name": "%s", "ScheduleExpression": "%s"
                  }},
                  "ChurnQueue": {"Type": "AWS::SQS::Queue", "Properties": {
                    "VisibilityTimeout": %d
                  }}
                }}
                """.formatted(RULE_NAME, expression, visibilityTimeout);
    }
}
