package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.Subscription;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudFormationSnsAdoptedTopicRollbackIntegrationTest {

    private static final String ACCOUNT_ARN = "arn:aws:sns:us-east-1:000000000000:";
    private static final String OUTSIDE_QUEUE = "arn:aws:sqs:us-east-1:000000000000:outside-queue";
    private static final String INLINE_QUEUE = "arn:aws:sqs:us-east-1:000000000000:inline-queue";

    @Test
    void aRolledBackReplacementLeavesTheTopicItAdoptedAsItWas() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-sns-adopt-" + suffix;
        String own = "own-" + suffix;
        String outside = "outside-" + suffix;
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
             SnsClient sns = AwsTestClients.create(SnsClient.builder(), "test", Region.US_EAST_1)) {
            String outsideArn = sns.createTopic(r -> r.name(outside).attributes(Map.of("DisplayName", "outside"))).topicArn();
            sns.subscribe(r -> r.topicArn(outsideArn).protocol("sqs").endpoint(OUTSIDE_QUEUE));
            Map<String, String> before = sns.getTopicAttributes(r -> r.topicArn(outsideArn)).attributes();

            cfn.createStack(r -> r.stackName(stackName).templateBody(template(own, "", false)));
            CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");

            String inline = ", \"MaximumMessageSize\": 4096, \"Subscription\": "
                    + "[{\"Protocol\": \"sqs\", \"Endpoint\": \"" + INLINE_QUEUE + "\"}]";
            cfn.updateStack(r -> r.stackName(stackName).templateBody(template(outside, inline, true)));
            Stack rolledBack = CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_ROLLBACK_COMPLETE");

            assertEquals(before, sns.getTopicAttributes(r -> r.topicArn(outsideArn)).attributes());
            assertEquals(List.of(OUTSIDE_QUEUE), sns.listSubscriptionsByTopic(r -> r.topicArn(outsideArn))
                    .subscriptions().stream().map(Subscription::endpoint).toList());
            assertEquals(ACCOUNT_ARN + own, rolledBack.outputs().getFirst().outputValue());
            sns.getTopicAttributes(r -> r.topicArn(ACCOUNT_ARN + own));
        }
    }

    /** A named topic; with {@code failing}, a secret that fails on its Ref, so the update rolls back. */
    private static String template(String topicName, String extraTopicProperties, boolean failing) {
        String badSecret = failing ? """
                    , "BadSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "Properties": {"SecretString": {"Ref": "Topic"}, "GenerateSecretString": {"PasswordLength": 8}}
                    }""" : "";
        return """
                {
                  "Resources": {
                    "Topic": {"Type": "AWS::SNS::Topic", "Properties": {"TopicName": "%s"%s}}%s
                  },
                  "Outputs": {"TopicRef": {"Value": {"Ref": "Topic"}}}
                }
                """.formatted(topicName, extraTopicProperties, badSecret);
    }
}
