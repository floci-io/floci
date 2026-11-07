package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.sns.SnsClient;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudFormationSnsFirehoseRoleIntegrationTest {

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
