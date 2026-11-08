package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.eventbridgev2.EventBridgeV2Client;
import software.amazon.awssdk.services.eventbridgev2.model.BusState;
import software.amazon.awssdk.services.eventbridgev2.model.CreateEventBusResponse;
import software.amazon.awssdk.services.eventbridgev2.model.FilterScope;
import software.amazon.awssdk.services.eventbridgev2.model.InvalidInputException;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("EventBridge V2 event buses")
class EventBridgeV2Test {

    private static final Logger LOG = Logger.getLogger(EventBridgeV2Test.class);

    @Test
    @DisplayName("CreateEventBus returns an event-busv2 ARN and refuses a second bus of the same name")
    void createEventBus() {
        String name = TestFixtures.uniqueName("sdk-busv2");
        try (EventBridgeV2Client client = TestFixtures.eventBridgeV2Client()) {
            String arn = null;
            try {
                CreateEventBusResponse created = client.createEventBus(request -> request.name(name)
                        .description("Order events")
                        .storageConfiguration(storage -> storage.retentionPeriodInDays(7))
                        .tags(Map.of("team", "orders")));
                arn = created.eventBusArn();

                assertThat(arn).matches("arn:aws[a-z-]*:events:[a-z0-9-]+:\\d{12}:event-busv2/" + name + "/[a-z0-9]{25}");
                assertThat(created.name()).isEqualTo(name);
                assertThat(created.description()).isEqualTo("Order events");
                assertThat(created.storageConfiguration().retentionPeriodInDays()).isEqualTo(7);
                assertThat(created.state()).isIn(BusState.CREATING, BusState.ACTIVE);
                assertThat(created.creationTime()).isBefore(Instant.now().plusSeconds(60));

                assertThatThrownBy(() -> client.createEventBus(request -> request.name(name)))
                        .isInstanceOf(ResourceAlreadyExistsException.class);
            } finally {
                deleteBestEffort(client, arn);
            }
        }
    }

    @Test
    @DisplayName("CreateEventBus refuses an invalid name")
    void createEventBusRejectsAnInvalidName() {
        try (EventBridgeV2Client client = TestFixtures.eventBridgeV2Client()) {
            assertThatThrownBy(() -> client.createEventBus(request -> request.name("not a valid name")))
                    .isInstanceOf(InvalidInputException.class);
        }
    }

    @Test
    @DisplayName("A subscriber delivers the events its FilterConfiguration matches to an SQS queue")
    void subscriberDeliversFilteredEventsToSqs() throws Exception {
        assumeFalse(TestFixtures.isRealAws(), "Needs a delivery role with sqs:SendMessage on real AWS");
        String suffix = TestFixtures.uniqueName("busv2");
        try (EventBridgeV2Client events = TestFixtures.eventBridgeV2Client();
             SqsClient sqs = TestFixtures.sqsClient()) {
            String queueUrl = sqs.createQueue(request -> request.queueName(suffix)).queueUrl();
            String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            String bus = events.createEventBus(request -> request.name(suffix)).eventBusArn();
            String subscriber = events.createSubscriber(request -> request.name("large-orders").eventBusArn(bus)
                    .invokeConfiguration(invoke -> invoke.targetArn(queueArn)
                            .roleArn("arn:aws:iam::000000000000:role/EventBusDelivery"))
                    .filterConfiguration(filters -> filters.filters(filter -> filter.scope(FilterScope.DATA)
                            .pattern("{\"detail\":{\"total\":[{\"numeric\":[\">\",500]}]}}")))).subscriberArn();
            try {
                events.putEvents(request -> request.eventBusArn(bus).entries(
                        entry -> entry.source("com.shop").detailType("Order Placed").detail("{\"total\":750}"),
                        entry -> entry.source("com.shop").detailType("Order Placed").detail("{\"total\":5}")));

                List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                        .maxNumberOfMessages(10).waitTimeSeconds(2)).messages();
                assertThat(messages).hasSize(1);
                assertThat(messages.get(0).body()).contains("\"total\":750").contains("\"detail-type\":\"Order Placed\"");            } finally {
                events.deleteSubscriber(request -> request.subscriberArn(subscriber));
                events.deleteEventBus(request -> request.eventBusArn(bus));
                sqs.deleteQueue(request -> request.queueUrl(queueUrl));
            }
        }
    }

    private static void deleteBestEffort(EventBridgeV2Client client, String arn) {
        if (arn == null) {
            return;
        }
        try {
            client.deleteEventBus(request -> request.eventBusArn(arn));
        } catch (Exception cleanupError) {
            LOG.warnv(cleanupError, "Best-effort cleanup failed for eventBusArn={0}", arn);
        }
    }
}
