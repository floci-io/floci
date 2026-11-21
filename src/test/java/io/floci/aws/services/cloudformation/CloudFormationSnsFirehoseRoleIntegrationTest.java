package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.NotFoundException;
import software.amazon.awssdk.services.sns.model.Subscription;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class CloudFormationSnsFirehoseRoleIntegrationTest {

    private static final String QUEUE = "arn:aws:sqs:us-east-1:000000000000:";

    @RegisterExtension
    final CfnTestStack replacement = new CfnTestStack("sns-sub-replace");

    @Test
    void resolvedFirehoseRoleIsForwardedAndUpdatedWithoutReplacingSubscription() {
        String suffix = UUID.randomUUID().toString();
        String stackName = "sns-firehose-role-" + suffix;
        try (CloudFormationClient cfn = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
             SnsClient sns = AwsTestClients.create(SnsClient.builder(), "test", Region.US_EAST_1)) {
            String topic = sns.createTopic(request -> request.name("sns-firehose-" + suffix)).topicArn();
            boolean created = false;
            try {
                cfn.createStack(request -> request.stackName(stackName).templateBody(template(topic, "first")));
                created = true;
                CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");
                String subscription = subscription(cfn, stackName);
                assertEquals("arn:aws:iam::000000000000:role/first",
                        sns.getSubscriptionAttributes(request -> request.subscriptionArn(subscription))
                                .attributes().get("SubscriptionRoleArn"));

                cfn.updateStack(request -> request.stackName(stackName).templateBody(template(topic, "second")));
                CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");
                assertEquals(subscription, subscription(cfn, stackName));
                assertEquals("arn:aws:iam::000000000000:role/second",
                        sns.getSubscriptionAttributes(request -> request.subscriptionArn(subscription))
                                .attributes().get("SubscriptionRoleArn"));
            } finally {
                try {
                    if (created) {
                        cfn.deleteStack(request -> request.stackName(stackName));
                    }
                } finally {
                    sns.deleteTopic(request -> request.topicArn(topic));
                }
            }
        }
    }

    @Test
    void filterPolicyAndRawDeliveryUpdateAndRemovalKeepSubscriptionIdentity() {
        String suffix = UUID.randomUUID().toString();
        String stackName = "sns-subscription-attributes-" + suffix;
        try (CloudFormationClient cfn = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
             SnsClient sns = AwsTestClients.create(SnsClient.builder(), "test", Region.US_EAST_1)) {
            String topic = sns.createTopic(request -> request.name("sns-attributes-" + suffix)).topicArn();
            boolean created = false;
            try {
                cfn.createStack(request -> request.stackName(stackName)
                        .templateBody(attributeTemplate(topic, ",\"FilterPolicy\":{\"kind\":[\"created\"]}")));
                created = true;
                CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");
                String arn = subscription(cfn, stackName);
                assertEquals("{\"kind\":[\"created\"]}", sns.getSubscriptionAttributes(
                        request -> request.subscriptionArn(arn)).attributes().get("FilterPolicy"));

                cfn.updateStack(request -> request.stackName(stackName).templateBody(attributeTemplate(topic,
                        ",\"FilterPolicy\":{\"kind\":[\"updated\"]},\"RawMessageDelivery\":true")));
                CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");
                assertEquals(arn, subscription(cfn, stackName));
                assertEquals("{\"kind\":[\"updated\"]}", sns.getSubscriptionAttributes(
                        request -> request.subscriptionArn(arn)).attributes().get("FilterPolicy"));
                assertEquals("true", sns.getSubscriptionAttributes(
                        request -> request.subscriptionArn(arn)).attributes().get("RawMessageDelivery"));
                assertEquals(1, sns.listSubscriptionsByTopic(request -> request.topicArn(topic)).subscriptions().size());

                cfn.updateStack(request -> request.stackName(stackName).templateBody(attributeTemplate(topic, "")));
                CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");
                assertEquals(arn, subscription(cfn, stackName));
                assertEquals("", sns.getSubscriptionAttributes(
                        request -> request.subscriptionArn(arn)).attributes().get("FilterPolicy"));
                assertEquals("false", sns.getSubscriptionAttributes(
                        request -> request.subscriptionArn(arn)).attributes().get("RawMessageDelivery"));
            } finally {
                try {
                    if (created) {
                        cfn.deleteStack(request -> request.stackName(stackName));
                    }
                } finally {
                    sns.deleteTopic(request -> request.topicArn(topic));
                }
            }
        }
    }

    /** AWS: Endpoint, Protocol and TopicArn of AWS::SNS::Subscription are "Update requires: Replacement". */
    @Test
    void anEndpointChangeReplacesTheSubscriptionAndRemovesTheOldOneOnCommit() {
        SnsClient sns = replacement.client(SnsClient.builder());
        String old = output(replacement.create(subscriptionStack("first", false)));

        String topic = topicOf(sns, old);
        String fresh = output(replacement.update(subscriptionStack("second", false), "UPDATE_COMPLETE"));

        assertEquals(List.of(fresh), subscriptionArns(sns, topic));
        assertEquals(QUEUE + "second", sns.getSubscriptionAttributes(r -> r.subscriptionArn(fresh))
                .attributes().get("Endpoint"));
        assertThrows(NotFoundException.class, () -> sns.getSubscriptionAttributes(r -> r.subscriptionArn(old)));
    }

    @Test
    void aRolledBackEndpointChangeKeepsTheOldSubscriptionAndDropsTheNewOne() {
        SnsClient sns = replacement.client(SnsClient.builder());
        String old = output(replacement.create(subscriptionStack("first", false)));

        String topic = topicOf(sns, old);
        replacement.update(subscriptionStack("second", true), "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(List.of(old), subscriptionArns(sns, topic));
        assertEquals(QUEUE + "first", sns.getSubscriptionAttributes(r -> r.subscriptionArn(old))
                .attributes().get("Endpoint"));
    }

    private static String output(Stack stack) {
        return CfnStackWaits.output(stack, "SubscriptionArn");
    }

    private static String topicOf(SnsClient sns, String subscriptionArn) {
        return sns.getSubscriptionAttributes(r -> r.subscriptionArn(subscriptionArn)).attributes().get("TopicArn");
    }

    private static List<String> subscriptionArns(SnsClient sns, String topic) {
        return sns.listSubscriptionsByTopic(r -> r.topicArn(topic)).subscriptions().stream()
                .map(Subscription::subscriptionArn).toList();
    }

    /** A topic and a subscription to {@code queue}; with {@code failing}, a later resource that fails. */
    private static String subscriptionStack(String queue, boolean failing) {
        return """
                {"Resources": {
                  "Topic": {"Type": "AWS::SNS::Topic"},
                  "Subscription": {"Type": "AWS::SNS::Subscription", "Properties": {
                    "TopicArn": {"Ref": "Topic"}, "Protocol": "sqs", "Endpoint": "%s%s"}}%s},
                  "Outputs": {"SubscriptionArn": {"Value": {"Ref": "Subscription"}}}
                }
                """.formatted(QUEUE, queue, failing ? CfnStackWaits.failingResourceAfter("Subscription") : "");
    }

    private static String attributeTemplate(String topic, String attributes) {
        return """
                {"Resources":{"Subscription":{"Type":"AWS::SNS::Subscription","Properties":{
                  "TopicArn":"%s","Protocol":"sqs",
                  "Endpoint":{"Fn::Sub":"arn:${AWS::Partition}:sqs:${AWS::Region}:${AWS::AccountId}:test"}%s
                }}}}
                """.formatted(topic, attributes);
    }

    private static String subscription(CloudFormationClient cfn, String stackName) {
        return cfn.describeStackResource(request -> request.stackName(stackName).logicalResourceId("Subscription"))
                .stackResourceDetail().physicalResourceId();
    }

    private static String template(String topic, String roleName) {
        return """
                {"Resources":{"Subscription":{"Type":"AWS::SNS::Subscription","Properties":{
                  "TopicArn":"%s","Protocol":"firehose",
                  "Endpoint":{"Fn::Sub":"arn:${AWS::Partition}:firehose:${AWS::Region}:${AWS::AccountId}:deliverystream/test"},
                  "SubscriptionRoleArn":{"Fn::Sub":"arn:${AWS::Partition}:iam::${AWS::AccountId}:role/%s"}
                }}}}
                """.formatted(topic, roleName);
    }
}
