package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.BulkEmailContent;
import software.amazon.awssdk.services.sesv2.model.BulkEmailEntry;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetEventDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.CreateConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetEventDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteConfigurationSetRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.EmailTemplateContent;
import software.amazon.awssdk.services.sesv2.model.EventDestinationDefinition;
import software.amazon.awssdk.services.sesv2.model.EventType;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageHeader;
import software.amazon.awssdk.services.sesv2.model.MessageTag;
import software.amazon.awssdk.services.sesv2.model.SendBulkEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SnsDestination;
import software.amazon.awssdk.services.sesv2.model.Template;
import software.amazon.awssdk.services.sesv2.model.CloudWatchDestination;
import software.amazon.awssdk.services.sesv2.model.CloudWatchDimensionConfiguration;
import software.amazon.awssdk.services.sesv2.model.DimensionValueSource;
import software.amazon.awssdk.services.sesv2.model.EventBridgeDestination;
import software.amazon.awssdk.services.sesv2.model.KinesisFirehoseDestination;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
import software.amazon.awssdk.services.sns.model.SubscribeRequest;
import software.amazon.awssdk.services.sns.model.UnsubscribeRequest;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.CreateDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.DeleteDeliveryStreamRequest;
import software.amazon.awssdk.services.firehose.model.S3DestinationConfiguration;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.DeleteRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.PutRuleRequest;
import software.amazon.awssdk.services.eventbridge.model.PutTargetsRequest;
import software.amazon.awssdk.services.eventbridge.model.RemoveTargetsRequest;
import software.amazon.awssdk.services.eventbridge.model.Target;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsRequest;
import software.amazon.awssdk.services.cloudwatch.model.Metric;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SES event-publishing compatibility tests using the AWS SDK for Java v2.
 *
 * Drives the entire flow through real SDK clients (SesV2Client + SnsClient /
 * FirehoseClient + S3Client / EventBridgeClient + SqsClient / CloudWatchClient):
 * create a configuration set per destination type, send via SesV2Client.SendEmail
 * with the ConfigurationSetName, then assert the AWS-format event JSON shape at
 * each destination's natural sink — SNS-subscribed SQS, S3 (Firehose flush),
 * EventBridge rule's SQS target, and CloudWatch metric registration.
 */
@DisplayName("SES Event Publishing")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesEventPublishingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EVENT_DEST_NAME = "ed-sns";
    private static final String EVENT_DEST_NAME_FIREHOSE = "ed-firehose";
    private static final String EVENT_DEST_NAME_EB = "ed-eventbridge";
    private static final String EVENT_DEST_NAME_CW = "ed-cloudwatch";

    private static SesV2Client ses;
    private static SnsClient sns;
    private static SqsClient sqs;
    private static FirehoseClient firehose;
    private static S3Client s3;
    private static EventBridgeClient eventBridge;
    private static CloudWatchClient cloudWatch;
    private static String csName;
    private static String csNameFirehose;
    private static String csNameEB;
    private static String csNameCW;
    private static String identity;
    private static String queueUrl;
    private static String queueArn;
    private static String topicArn;
    private static String subscriptionArn;
    private static String firehoseStreamName;
    private static String firehoseStreamArn;
    private static String firehoseBucket;
    private static String ebQueueUrl;
    private static String ebQueueArn;
    private static String ebRuleName;
    private static String ebBusArn;

    @BeforeAll
    static void setup() {
        ses = TestFixtures.sesV2Client();
        sns = TestFixtures.snsClient();
        sqs = TestFixtures.sqsClient();
        firehose = TestFixtures.firehoseClient();
        s3 = TestFixtures.s3Client();
        eventBridge = TestFixtures.eventBridgeClient();
        cloudWatch = TestFixtures.cloudWatchClient();
        String suffix = TestFixtures.uniqueName();
        csName = "sdk-evt-cs-" + suffix;
        csNameFirehose = "sdk-evt-cs-fh-" + suffix;
        csNameEB = "sdk-evt-cs-eb-" + suffix;
        csNameCW = "sdk-evt-cs-cw-" + suffix;
        identity = "sdk-evt-from-" + suffix + "@floci.test";

        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName("sdk-evt-q-" + suffix)
                        .build())
                .queueUrl();
        queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes().get(QueueAttributeName.QUEUE_ARN);

        topicArn = sns.createTopic(CreateTopicRequest.builder()
                        .name("sdk-evt-t-" + suffix)
                        .build())
                .topicArn();

        subscriptionArn = sns.subscribe(SubscribeRequest.builder()
                        .topicArn(topicArn)
                        .protocol("sqs")
                        .endpoint(queueArn)
                        .build())
                .subscriptionArn();

        ses.createEmailIdentity(CreateEmailIdentityRequest.builder()
                .emailIdentity(identity)
                .build());

        ses.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(csName)
                .build());

        ses.createConfigurationSetEventDestination(CreateConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(csName)
                .eventDestinationName(EVENT_DEST_NAME)
                .eventDestination(EventDestinationDefinition.builder()
                        .enabled(true)
                        .matchingEventTypes(EventType.SEND, EventType.DELIVERY, EventType.BOUNCE,
                                EventType.COMPLAINT, EventType.REJECT)
                        .snsDestination(SnsDestination.builder().topicArn(topicArn).build())
                        .build())
                .build());

        // Firehose destination plumbing: S3 bucket + delivery stream + CS + Firehose dest.
        firehoseBucket = "sdk-evt-fh-bucket-" + suffix;
        s3.createBucket(CreateBucketRequest.builder().bucket(firehoseBucket).build());
        firehoseStreamName = "sdk-evt-fh-stream-" + suffix;
        firehoseStreamArn = firehose.createDeliveryStream(CreateDeliveryStreamRequest.builder()
                        .deliveryStreamName(firehoseStreamName)
                        .s3DestinationConfiguration(S3DestinationConfiguration.builder()
                                .bucketARN("arn:aws:s3:::" + firehoseBucket)
                                .roleARN("arn:aws:iam::000000000000:role/sdk-evt-fh-role")
                                .build())
                        .build())
                .deliveryStreamARN();
        ses.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(csNameFirehose)
                .build());
        ses.createConfigurationSetEventDestination(CreateConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(csNameFirehose)
                .eventDestinationName(EVENT_DEST_NAME_FIREHOSE)
                .eventDestination(EventDestinationDefinition.builder()
                        .enabled(true)
                        .matchingEventTypes(EventType.SEND)
                        .kinesisFirehoseDestination(KinesisFirehoseDestination.builder()
                                .iamRoleArn("arn:aws:iam::000000000000:role/sdk-evt-fh-role")
                                .deliveryStreamArn(firehoseStreamArn)
                                .build())
                        .build())
                .build());

        // EventBridge destination plumbing: SQS target + rule on default bus + CS + EB dest.
        ebQueueUrl = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName("sdk-evt-eb-q-" + suffix)
                        .build())
                .queueUrl();
        ebQueueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(ebQueueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        ebRuleName = "sdk-evt-eb-rule-" + suffix;
        eventBridge.putRule(PutRuleRequest.builder()
                .name(ebRuleName)
                .eventPattern("{\"source\":[\"aws.ses\"]}")
                .build());
        eventBridge.putTargets(PutTargetsRequest.builder()
                .rule(ebRuleName)
                .targets(Target.builder().id("1").arn(ebQueueArn).build())
                .build());
        ebBusArn = "arn:aws:events:us-east-1:000000000000:event-bus/default";
        ses.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(csNameEB)
                .build());
        ses.createConfigurationSetEventDestination(CreateConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(csNameEB)
                .eventDestinationName(EVENT_DEST_NAME_EB)
                .eventDestination(EventDestinationDefinition.builder()
                        .enabled(true)
                        .matchingEventTypes(EventType.SEND, EventType.DELIVERY)
                        .eventBridgeDestination(EventBridgeDestination.builder()
                                .eventBusArn(ebBusArn)
                                .build())
                        .build())
                .build());

        // CloudWatch destination plumbing: CS + CW dest with three dimension configs.
        ses.createConfigurationSet(CreateConfigurationSetRequest.builder()
                .configurationSetName(csNameCW)
                .build());
        ses.createConfigurationSetEventDestination(CreateConfigurationSetEventDestinationRequest.builder()
                .configurationSetName(csNameCW)
                .eventDestinationName(EVENT_DEST_NAME_CW)
                .eventDestination(EventDestinationDefinition.builder()
                        .enabled(true)
                        .matchingEventTypes(EventType.SEND, EventType.DELIVERY)
                        .cloudWatchDestination(CloudWatchDestination.builder()
                                .dimensionConfigurations(
                                        CloudWatchDimensionConfiguration.builder()
                                                .dimensionName("ses:configuration-set")
                                                .dimensionValueSource(DimensionValueSource.MESSAGE_TAG)
                                                .defaultDimensionValue("unknown")
                                                .build(),
                                        CloudWatchDimensionConfiguration.builder()
                                                .dimensionName("campaign")
                                                .dimensionValueSource(DimensionValueSource.MESSAGE_TAG)
                                                .defaultDimensionValue("default-campaign")
                                                .build(),
                                        CloudWatchDimensionConfiguration.builder()
                                                .dimensionName("X-Custom-Header")
                                                .dimensionValueSource(DimensionValueSource.EMAIL_HEADER)
                                                .defaultDimensionValue("default-header")
                                                .build())
                                .build())
                        .build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (ses != null) {
            for (String[] pair : new String[][]{
                    {csName, EVENT_DEST_NAME},
                    {csNameFirehose, EVENT_DEST_NAME_FIREHOSE},
                    {csNameEB, EVENT_DEST_NAME_EB},
                    {csNameCW, EVENT_DEST_NAME_CW}}) {
                try {
                    ses.deleteConfigurationSetEventDestination(DeleteConfigurationSetEventDestinationRequest.builder()
                            .configurationSetName(pair[0])
                            .eventDestinationName(pair[1])
                            .build());
                } catch (Exception ignored) {}
                try {
                    ses.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                            .configurationSetName(pair[0])
                            .build());
                } catch (Exception ignored) {}
            }
            try {
                ses.deleteEmailIdentity(DeleteEmailIdentityRequest.builder()
                        .emailIdentity(identity)
                        .build());
            } catch (Exception ignored) {}
            ses.close();
        }
        if (sns != null) {
            try {
                sns.unsubscribe(UnsubscribeRequest.builder().subscriptionArn(subscriptionArn).build());
            } catch (Exception ignored) {}
            try {
                sns.deleteTopic(DeleteTopicRequest.builder().topicArn(topicArn).build());
            } catch (Exception ignored) {}
            sns.close();
        }
        if (eventBridge != null) {
            try {
                eventBridge.removeTargets(RemoveTargetsRequest.builder()
                        .rule(ebRuleName).ids("1").build());
            } catch (Exception ignored) {}
            try {
                eventBridge.deleteRule(DeleteRuleRequest.builder().name(ebRuleName).build());
            } catch (Exception ignored) {}
            eventBridge.close();
        }
        if (sqs != null) {
            try {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            } catch (Exception ignored) {}
            try {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(ebQueueUrl).build());
            } catch (Exception ignored) {}
            sqs.close();
        }
        if (firehose != null) {
            try {
                firehose.deleteDeliveryStream(DeleteDeliveryStreamRequest.builder()
                        .deliveryStreamName(firehoseStreamName).build());
            } catch (Exception ignored) {}
            firehose.close();
        }
        if (s3 != null) {
            try {
                ListObjectsV2Response listed = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(firehoseBucket).build());
                if (!listed.contents().isEmpty()) {
                    s3.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(firehoseBucket)
                            .delete(d -> d.objects(listed.contents().stream()
                                    .map(o -> ObjectIdentifier.builder().key(o.key()).build())
                                    .collect(Collectors.toList())))
                            .build());
                }
            } catch (Exception ignored) {}
            try {
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(firehoseBucket).build());
            } catch (Exception ignored) {}
            s3.close();
        }
        if (cloudWatch != null) {
            cloudWatch.close();
        }
    }

    @Test
    @Order(1)
    void sendToSuccessSimulator_publishesSendAndDelivery() throws Exception {
        drainQueue();
        sendEmailTo("success@simulator.amazonses.com");
        List<JsonNode> events = receiveEvents(2);

        assertThat(events).hasSize(2);
        assertThat(events).anyMatch(e -> "Send".equals(e.path("eventType").asText()));
        JsonNode delivery = events.stream()
                .filter(e -> "Delivery".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        assertThat(delivery.path("mail").path("source").asText()).isEqualTo(identity);
        assertThat(delivery.path("delivery").path("recipients").get(0).asText())
                .isEqualTo("success@simulator.amazonses.com");
        JsonNode tags = delivery.path("mail").path("tags");
        assertThat(tags.path("ses:configuration-set").get(0).asText()).isEqualTo(csName);
        assertThat(tags.path("campaign").get(0).asText()).isEqualTo("launch");
        assertThat(tags.path("env").get(0).asText()).isEqualTo("prod");
        assertThat(delivery.path("mail").path("commonHeaders").path("subject").asText())
                .isEqualTo("evt");
        assertThat(delivery.path("mail").path("commonHeaders").path("to").get(0).asText())
                .isEqualTo("success@simulator.amazonses.com");
        assertThat(delivery.path("mail").path("commonHeaders").path("date").asText()).isNotEmpty();
        boolean hasXMailer = false;
        boolean hasUnsubscribe = false;
        for (JsonNode h : delivery.path("mail").path("headers")) {
            if ("X-Mailer".equals(h.path("name").asText())
                    && "floci".equals(h.path("value").asText())) {
                hasXMailer = true;
            }
            if ("List-Unsubscribe".equals(h.path("name").asText())) {
                hasUnsubscribe = true;
            }
        }
        assertThat(hasXMailer).as("X-Mailer header in event mail.headers").isTrue();
        assertThat(hasUnsubscribe).as("List-Unsubscribe header in event mail.headers").isTrue();
    }

    @Test
    @Order(2)
    void sendToBounceSimulator_publishesSendAndBounce() throws Exception {
        drainQueue();
        sendEmailTo("bounce@simulator.amazonses.com");
        List<JsonNode> events = receiveEvents(2);

        assertThat(events).hasSize(2);
        assertThat(events).anyMatch(e -> "Send".equals(e.path("eventType").asText()));
        JsonNode bounce = events.stream()
                .filter(e -> "Bounce".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        assertThat(bounce.path("bounce").path("bounceType").asText()).isEqualTo("Permanent");
        assertThat(bounce.path("bounce").path("bouncedRecipients").get(0).path("emailAddress").asText())
                .isEqualTo("bounce@simulator.amazonses.com");
    }

    @Test
    @Order(3)
    void sendToRegularAddress_publishesSendOnly() throws Exception {
        drainQueue();
        sendEmailTo("recipient@example.com");
        List<JsonNode> events = receiveEvents(1);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("eventType").asText()).isEqualTo("Send");
    }

    @Test
    @Order(4)
    void sendBulkEmail_perEntryReplacementHeadersOverrideDefault() throws Exception {
        drainQueue();
        ses.sendBulkEmail(SendBulkEmailRequest.builder()
                .fromEmailAddress(identity)
                .configurationSetName(csName)
                .defaultContent(BulkEmailContent.builder()
                        .template(Template.builder()
                                .templateContent(EmailTemplateContent.builder()
                                        .subject("bulk-subject")
                                        .text("bulk body")
                                        .build())
                                .headers(MessageHeader.builder()
                                        .name("X-Mailer").value("default").build())
                                .build())
                        .build())
                .bulkEmailEntries(BulkEmailEntry.builder()
                        .destination(Destination.builder()
                                .toAddresses("success@simulator.amazonses.com").build())
                        .replacementHeaders(MessageHeader.builder()
                                .name("X-Mailer").value("override").build())
                        .build())
                .build());

        List<JsonNode> events = receiveEvents(2);
        JsonNode send = events.stream()
                .filter(e -> "Send".equals(e.path("eventType").asText()))
                .findFirst().orElseThrow();
        String xMailer = null;
        for (JsonNode h : send.path("mail").path("headers")) {
            if ("X-Mailer".equals(h.path("name").asText())) {
                xMailer = h.path("value").asText();
            }
        }
        assertThat(xMailer).as("X-Mailer in event mail.headers (per-entry should override default)")
                .isEqualTo("override");
    }

    @Test
    @Order(5)
    @DisplayName("Firehose destination: 5 sends trigger auto-flush to S3 as NDJSON")
    void firehoseDestination_fiveSends_triggerAutoFlushToS3() throws Exception {
        for (int i = 0; i < 5; i++) {
            ses.sendEmail(SendEmailRequest.builder()
                    .fromEmailAddress(identity)
                    .destination(Destination.builder()
                            .toAddresses("fh-recipient" + i + "@example.com")
                            .build())
                    .configurationSetName(csNameFirehose)
                    .content(EmailContent.builder()
                            .simple(Message.builder()
                                    .subject(Content.builder().data("fh-evt-" + i).build())
                                    .body(Body.builder()
                                            .text(Content.builder().data("hi").build())
                                            .build())
                                    .build())
                            .build())
                    .build());
        }

        ListObjectsV2Response listed = s3.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(firehoseBucket)
                .prefix(firehoseStreamName + "/")
                .build());
        assertThat(listed.contents())
                .as("Firehose should have flushed exactly one S3 object after 5 records")
                .hasSize(1);

        S3Object obj = listed.contents().get(0);
        String body = new String(s3.getObject(GetObjectRequest.builder()
                        .bucket(firehoseBucket).key(obj.key()).build())
                .readAllBytes(), StandardCharsets.UTF_8);
        String[] lines = body.split("\\R");
        assertThat(lines).hasSize(5);
        for (String line : lines) {
            JsonNode event = MAPPER.readTree(line);
            assertThat(event.path("eventType").asText()).isEqualTo("Send");
            assertThat(event.path("mail").path("source").asText()).isEqualTo(identity);
            assertThat(event.path("mail").path("tags").path("ses:configuration-set").get(0).asText())
                    .isEqualTo(csNameFirehose);
        }
    }

    @Test
    @Order(6)
    @DisplayName("EventBridge destination: regular recipient routes Email Send to SQS via rule")
    void eventBridgeDestination_regularRecipient_routesEmailSendToSqs() throws Exception {
        drainEbQueue();
        ses.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(identity)
                .destination(Destination.builder().toAddresses("eb-recipient@example.com").build())
                .configurationSetName(csNameEB)
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("eb-evt").build())
                                .body(Body.builder()
                                        .text(Content.builder().data("hi").build())
                                        .build())
                                .build())
                        .build())
                .build());

        List<JsonNode> envelopes = receiveEbEvents(1);
        assertThat(envelopes).hasSize(1);
        JsonNode env = envelopes.get(0);
        assertThat(env.path("source").asText()).isEqualTo("aws.ses");
        assertThat(env.path("detail-type").asText()).isEqualTo("Email Sent");
        JsonNode detail = env.path("detail");
        assertThat(detail.path("eventType").asText()).isEqualTo("Send");
        assertThat(detail.path("mail").path("source").asText()).isEqualTo(identity);
        assertThat(detail.path("mail").path("tags").path("ses:configuration-set").get(0).asText())
                .isEqualTo(csNameEB);
    }

    @Test
    @Order(7)
    @DisplayName("EventBridge destination: success simulator routes both Email Send and Email Delivery")
    void eventBridgeDestination_successSimulator_routesSendAndDelivery() throws Exception {
        drainEbQueue();
        ses.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(identity)
                .destination(Destination.builder()
                        .toAddresses("success@simulator.amazonses.com").build())
                .configurationSetName(csNameEB)
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("eb-evt-success").build())
                                .body(Body.builder()
                                        .text(Content.builder().data("hi").build())
                                        .build())
                                .build())
                        .build())
                .build());

        List<JsonNode> envelopes = receiveEbEvents(2);
        assertThat(envelopes).hasSize(2);
        assertThat(envelopes).anyMatch(e -> "Email Sent".equals(e.path("detail-type").asText()));
        assertThat(envelopes).anyMatch(e -> "Email Delivered".equals(e.path("detail-type").asText()));
    }

    @Test
    @Order(8)
    @DisplayName("CloudWatch destination: send registers AWS/SES Send metric with resolved dimensions")
    void cloudWatchDestination_registersSendMetricWithResolvedDimensions() {
        ses.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(identity)
                .destination(Destination.builder().toAddresses("cw-recipient@example.com").build())
                .configurationSetName(csNameCW)
                .emailTags(MessageTag.builder().name("campaign").value("launch").build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("cw-evt").build())
                                .body(Body.builder()
                                        .text(Content.builder().data("hi").build())
                                        .build())
                                .headers(MessageHeader.builder()
                                        .name("X-Custom-Header").value("header-value").build())
                                .build())
                        .build())
                .build());

        List<Metric> sendMetrics = cloudWatch.listMetrics(ListMetricsRequest.builder()
                .namespace("AWS/SES")
                .metricName("Send")
                .build())
                .metrics();
        assertThat(sendMetrics)
                .as("expected at least one Send metric in AWS/SES")
                .isNotEmpty();
        assertThat(sendMetrics).anyMatch(m ->
                m.dimensions().stream()
                        .anyMatch(d -> "ses:configuration-set".equals(d.name())
                                && csNameCW.equals(d.value()))
                && m.dimensions().stream()
                        .anyMatch(d -> "campaign".equals(d.name()) && "launch".equals(d.value()))
                && m.dimensions().stream()
                        .anyMatch(d -> "X-Custom-Header".equals(d.name())
                                && "header-value".equals(d.value())));
    }

    private void drainEbQueue() {
        for (int i = 0; i < 5; i++) {
            ReceiveMessageResponse r = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(ebQueueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(0)
                    .build());
            if (r.messages().isEmpty()) {
                return;
            }
            List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>();
            for (int j = 0; j < r.messages().size(); j++) {
                entries.add(DeleteMessageBatchRequestEntry.builder()
                        .id("m" + j)
                        .receiptHandle(r.messages().get(j).receiptHandle())
                        .build());
            }
            sqs.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                    .queueUrl(ebQueueUrl)
                    .entries(entries)
                    .build());
        }
    }

    private List<JsonNode> receiveEbEvents(int expectedAtLeast) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (int attempt = 0; attempt < 10; attempt++) {
            if (events.size() >= expectedAtLeast) {
                break;
            }
            ReceiveMessageResponse r = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(ebQueueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
                    .build());
            if (r.messages().isEmpty()) {
                continue;
            }
            for (var m : r.messages()) {
                events.add(MAPPER.readTree(m.body()));
            }
            List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>();
            for (int j = 0; j < r.messages().size(); j++) {
                entries.add(DeleteMessageBatchRequestEntry.builder()
                        .id("m" + j)
                        .receiptHandle(r.messages().get(j).receiptHandle())
                        .build());
            }
            sqs.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                    .queueUrl(ebQueueUrl)
                    .entries(entries)
                    .build());
        }
        return events;
    }

    private void sendEmailTo(String to) {
        ses.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(identity)
                .destination(Destination.builder().toAddresses(to).build())
                .configurationSetName(csName)
                .emailTags(
                        MessageTag.builder().name("campaign").value("launch").build(),
                        MessageTag.builder().name("env").value("prod").build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("evt").build())
                                .body(Body.builder()
                                        .text(Content.builder().data("hi").build())
                                        .build())
                                .headers(
                                        MessageHeader.builder().name("X-Mailer").value("floci").build(),
                                        MessageHeader.builder().name("List-Unsubscribe")
                                                .value("<mailto:u@example.com>").build())
                                .build())
                        .build())
                .build());
    }

    private void drainQueue() {
        for (int i = 0; i < 5; i++) {
            ReceiveMessageResponse r = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(0)
                    .build());
            if (r.messages().isEmpty()) {
                return;
            }
            deleteBatch(r);
        }
    }

    private List<JsonNode> receiveEvents(int expectedAtLeast) throws Exception {
        List<JsonNode> events = new ArrayList<>();
        for (int attempt = 0; attempt < 10; attempt++) {
            if (expectedAtLeast > 0 && events.size() >= expectedAtLeast) {
                break;
            }
            ReceiveMessageResponse r = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
                    .build());
            if (r.messages().isEmpty()) {
                continue;
            }
            for (var m : r.messages()) {
                JsonNode wrapper = MAPPER.readTree(m.body());
                JsonNode event = MAPPER.readTree(wrapper.path("Message").asText());
                events.add(event);
            }
            deleteBatch(r);
        }
        return events;
    }

    private void deleteBatch(ReceiveMessageResponse r) {
        List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>();
        for (int i = 0; i < r.messages().size(); i++) {
            entries.add(DeleteMessageBatchRequestEntry.builder()
                    .id("m" + i)
                    .receiptHandle(r.messages().get(i).receiptHandle())
                    .build());
        }
        sqs.deleteMessageBatch(DeleteMessageBatchRequest.builder()
                .queueUrl(queueUrl)
                .entries(entries)
                .build());
    }
}
