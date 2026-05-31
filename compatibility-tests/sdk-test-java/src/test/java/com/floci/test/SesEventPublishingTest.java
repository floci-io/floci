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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SES event-publishing compatibility tests using the AWS SDK for Java v2.
 *
 * Drives the entire flow through real SDK clients (SesV2Client / SnsClient /
 * SqsClient): create a configuration set + SNS event destination, send via
 * SesV2Client.SendEmail with ConfigurationSetName, then receive the resulting
 * SES event JSON from a subscribed SQS queue and assert the AWS-format shape.
 */
@DisplayName("SES Event Publishing")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesEventPublishingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EVENT_DEST_NAME = "ed-sns";

    private static SesV2Client ses;
    private static SnsClient sns;
    private static SqsClient sqs;
    private static String csName;
    private static String identity;
    private static String queueUrl;
    private static String queueArn;
    private static String topicArn;
    private static String subscriptionArn;

    @BeforeAll
    static void setup() {
        ses = TestFixtures.sesV2Client();
        sns = TestFixtures.snsClient();
        sqs = TestFixtures.sqsClient();
        String suffix = TestFixtures.uniqueName();
        csName = "sdk-evt-cs-" + suffix;
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
    }

    @AfterAll
    static void cleanup() {
        if (ses != null) {
            try {
                ses.deleteConfigurationSetEventDestination(DeleteConfigurationSetEventDestinationRequest.builder()
                        .configurationSetName(csName)
                        .eventDestinationName(EVENT_DEST_NAME)
                        .build());
            } catch (Exception ignored) {}
            try {
                ses.deleteConfigurationSet(DeleteConfigurationSetRequest.builder()
                        .configurationSetName(csName)
                        .build());
            } catch (Exception ignored) {}
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
        if (sqs != null) {
            try {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            } catch (Exception ignored) {}
            sqs.close();
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
