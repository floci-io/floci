package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("cfn-sns-adopt");

    @Test
    void aRolledBackReplacementLeavesTheTopicItAdoptedAsItWas() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String own = "own-" + suffix;
        String outside = "outside-" + suffix;
        SnsClient sns = stack.client(SnsClient.builder());
        String outsideArn = sns.createTopic(r -> r.name(outside).attributes(Map.of("DisplayName", "outside"))).topicArn();
        try {
            sns.subscribe(r -> r.topicArn(outsideArn).protocol("sqs").endpoint(OUTSIDE_QUEUE));
            Map<String, String> before = sns.getTopicAttributes(r -> r.topicArn(outsideArn)).attributes();

            stack.create(template(own, "", false));

            String inline = ", \"MaximumMessageSize\": 4096, \"Subscription\": "
                    + "[{\"Protocol\": \"sqs\", \"Endpoint\": \"" + INLINE_QUEUE + "\"}]";
            Stack rolledBack = stack.update(template(outside, inline, true), "UPDATE_ROLLBACK_COMPLETE");

            assertEquals(before, sns.getTopicAttributes(r -> r.topicArn(outsideArn)).attributes());
            assertEquals(List.of(OUTSIDE_QUEUE), sns.listSubscriptionsByTopic(r -> r.topicArn(outsideArn))
                    .subscriptions().stream().map(Subscription::endpoint).toList());
            assertEquals(ACCOUNT_ARN + own, CfnStackWaits.output(rolledBack, "TopicRef"));
            sns.getTopicAttributes(r -> r.topicArn(ACCOUNT_ARN + own));
        } finally {
            sns.deleteTopic(r -> r.topicArn(outsideArn));
        }
    }

    /** A named topic; with {@code failing}, a later resource that fails, so the update rolls back. */
    private static String template(String topicName, String extraTopicProperties, boolean failing) {
        return """
                {
                  "Resources": {
                    "Topic": {"Type": "AWS::SNS::Topic", "Properties": {"TopicName": "%s"%s}}%s
                  },
                  "Outputs": {"TopicRef": {"Value": {"Ref": "Topic"}}}
                }
                """.formatted(topicName, extraTopicProperties,
                failing ? CfnStackWaits.failingResourceAfter("Topic") : "");
    }
}
