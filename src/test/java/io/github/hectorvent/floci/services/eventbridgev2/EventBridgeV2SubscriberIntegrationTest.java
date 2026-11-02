package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridgev2.EventBridgeV2Client;
import software.amazon.awssdk.services.eventbridgev2.model.DescribeSubscriberResponse;
import software.amazon.awssdk.services.eventbridgev2.model.FilterScope;
import software.amazon.awssdk.services.eventbridgev2.model.InvalidInputException;
import software.amazon.awssdk.services.eventbridgev2.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceInUseException;
import software.amazon.awssdk.services.eventbridgev2.model.ResourceNotFoundException;
import software.amazon.awssdk.services.eventbridgev2.model.SubscriberState;
import software.amazon.awssdk.services.eventbridgev2.model.SubscriberSummary;
import software.amazon.awssdk.services.eventbridgev2.model.TransformerType;
import software.amazon.awssdk.services.eventbridgev2.model.UpdateSubscriberResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An EventBridge V2 subscriber delivers the events its filters match to an Amazon SQS queue, through the SDKs. */
@QuarkusTest
class EventBridgeV2SubscriberIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::000000000000:role/EventBusDelivery";

    @Test
    void aSubscriberDeliversTheEventsItsFiltersMatchToItsQueue() throws Exception {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            String queueUrl = sqs.createQueue(request -> request.queueName("v2-large-" + suffix)).queueUrl();
            String queueArn = queueArn(sqs, queueUrl);
            String bus = events.createEventBus(request -> request.name("v2-orders-" + suffix)).eventBusArn();
            String subscriber = events.createSubscriber(request -> request.name("large-orders").eventBusArn(bus)
                    .invokeConfiguration(invoke -> invoke.targetArn(queueArn).roleArn(ROLE))
                    .filterConfiguration(filters -> filters.filters(
                            filter -> filter.scope(FilterScope.DATA)
                                    .pattern("{\"detail\":{\"total\":[{\"numeric\":[\">\",500]}]}}"),
                            filter -> filter.scope(FilterScope.SYSTEM_METADATA)
                                    .pattern("{\"aws:Source\":[\"com.shop\"]}")))).subscriberArn();
            assertTrue(subscriber.contains(":subscriber/large-orders/"), subscriber);

            PutEventsResponse published = events.putEvents(request -> request.eventBusArn(bus).entries(
                    entry -> entry.source("com.shop").detailType("Order Placed").detail("{\"total\":750,\"id\":\"o-1\"}"),
                    entry -> entry.source("com.shop").detailType("Order Placed").detail("{\"total\":20,\"id\":\"o-2\"}"),
                    entry -> entry.source("com.other").detailType("Order Placed").detail("{\"total\":900,\"id\":\"o-3\"}")));
            assertEquals(0, published.failedEntryCount());
            assertEquals(3, published.entries().size());

            List<Message> messages = receive(sqs, queueUrl);
            assertEquals(1, messages.size(), messages.toString());
            JsonNode body = MAPPER.readTree(messages.getFirst().body());
            assertEquals("o-1", body.path("detail").path("id").asText());
            assertEquals("com.shop", body.path("source").asText());
            assertEquals("Order Placed", body.path("detail-type").asText());
            assertEquals(published.entries().getFirst().eventId(), body.path("id").asText());

            ResourceInUseException inUse = assertThrows(ResourceInUseException.class,
                    () -> events.deleteEventBus(request -> request.eventBusArn(bus)));
            assertEquals(409, inUse.statusCode());
            events.deleteSubscriber(request -> request.subscriberArn(subscriber));
            events.deleteEventBus(request -> request.eventBusArn(bus));
            assertThrows(ResourceNotFoundException.class, () -> events.putEvents(request -> request.eventBusArn(bus)
                    .entries(entry -> entry.source("com.shop").detailType("Order Placed"))));
            sqs.deleteQueue(request -> request.queueUrl(queueUrl));
        }
    }

    @Test
    void aWithMetadataSubscriberReceivesTheDataAndItsMetadata() throws Exception {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            String queueUrl = sqs.createQueue(request -> request.queueName("v2-meta-" + suffix)).queueUrl();
            String queueArn = queueArn(sqs, queueUrl);
            String bus = events.createEventBus(request -> request.name("v2-meta-" + suffix)).eventBusArn();
            String subscriber = events.createSubscriber(request -> request.name("all").eventBusArn(bus)
                    .invokeConfiguration(invoke -> invoke.targetArn(queueArn).roleArn(ROLE))
                    .transformer(transformer -> transformer.type(TransformerType.WITH_METADATA))).subscriberArn();

            events.putEvents(request -> request.eventBusArn(bus).entries(entry -> entry.source("com.shop")
                    .detailType("Order Placed").detail("{\"id\":\"o-9\"}")
                    .systemMetadata(system -> system.eventGroupId("customer-1"))));

            List<Message> messages = receive(sqs, queueUrl);
            assertEquals(1, messages.size());
            JsonNode body = MAPPER.readTree(messages.getFirst().body());
            assertEquals("o-9", body.path("Data").path("detail").path("id").asText());
            assertTrue(body.path("Metadata").isObject() && body.path("Metadata").isEmpty(), body.toString());
            assertEquals("application/eventbridge+json", body.path("SystemMetadata").path("ContentType").asText());
            assertEquals("customer-1", body.path("SystemMetadata").path("EventGroupId").asText());
            assertEquals("LIVE", body.path("SystemMetadata").path("aws:DeliveryType").asText());

            events.deleteSubscriber(request -> request.subscriberArn(subscriber));
            events.deleteEventBus(request -> request.eventBusArn(bus));
            sqs.deleteQueue(request -> request.queueUrl(queueUrl));
        }
    }

    @Test
    void aStoppedSubscriberDeliversNothing() {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            String queueUrl = sqs.createQueue(request -> request.queueName("v2-stopped-" + suffix)).queueUrl();
            String queueArn = queueArn(sqs, queueUrl);
            String bus = events.createEventBus(request -> request.name("v2-stopped-" + suffix)).eventBusArn();
            String subscriber = events.createSubscriber(request -> request.name("paused").eventBusArn(bus)
                    .state(SubscriberState.STOPPED)
                    .invokeConfiguration(invoke -> invoke.targetArn(queueArn).roleArn(ROLE))).subscriberArn();

            events.putEvents(request -> request.eventBusArn(bus).entries(entry -> entry.source("a").detailType("t")));
            assertEquals(List.of(), receive(sqs, queueUrl));

            events.deleteSubscriber(request -> request.subscriberArn(subscriber));
            events.deleteEventBus(request -> request.eventBusArn(bus));
            sqs.deleteQueue(request -> request.queueUrl(queueUrl));
        }
    }

    @Test
    void aSubscriberIsDescribedListedAndUpdatedInPlace() throws Exception {
        String suffix = suffix();
        try (EventBridgeV2Client events = events(); SqsClient sqs = sqs()) {
            String queueUrl = sqs.createQueue(request -> request.queueName("v2-update-" + suffix)).queueUrl();
            String queueArn = queueArn(sqs, queueUrl);
            String bus = events.createEventBus(request -> request.name("v2-update-" + suffix)).eventBusArn();
            String subscriber = events.createSubscriber(request -> request.name("orders").eventBusArn(bus)
                    .description("all orders")
                    .invokeConfiguration(invoke -> invoke.targetArn(queueArn).roleArn(ROLE))
                    .filterConfiguration(filters -> filters.filters(filter -> filter.scope(FilterScope.DATA)
                            .pattern("{\"source\":[\"com.shop\"]}")))).subscriberArn();

            DescribeSubscriberResponse described = events.describeSubscriber(request -> request
                    .subscriberArn(subscriber));
            assertEquals("orders", described.name());
            assertEquals(bus, described.eventBusArn());
            assertEquals(queueArn, described.invokeConfiguration().targetArn());
            assertEquals("all orders", described.description());
            assertEquals("{\"source\":[\"com.shop\"]}", described.filterConfiguration().filters().getFirst().pattern());
            assertEquals(SubscriberState.RUNNING, described.state());
            assertEquals(Boolean.FALSE, described.revoked());
            // The SDK decodes these only from CBOR timestamps.
            assertNotNull(described.creationTime());
            assertNotNull(described.lastModifiedTime());

            SubscriberSummary listed = events.listSubscribers(request -> request.eventBusArn(bus)).subscribers()
                    .getFirst();
            assertEquals(subscriber, listed.subscriberArn());
            assertEquals(queueArn, listed.targetArn());
            assertEquals("000000000000", listed.subscriberAccountId());
            assertNotNull(listed.creationTime());

            UpdateSubscriberResponse updated = events.updateSubscriber(request -> request.subscriberArn(subscriber)
                    .filterConfiguration(filters -> filters.filters(filter -> filter.scope(FilterScope.DATA)
                            .pattern("{\"source\":[\"com.other\"]}"))));
            assertEquals(subscriber, updated.subscriberArn());
            assertNotNull(updated.lastModifiedTime());
            assertEquals("all orders", events.describeSubscriber(request -> request.subscriberArn(subscriber))
                    .description(), "what the update leaves out is kept");

            events.putEvents(request -> request.eventBusArn(bus).entries(
                    entry -> entry.source("com.shop").detailType("t").detail("{\"id\":\"old\"}"),
                    entry -> entry.source("com.other").detailType("t").detail("{\"id\":\"new\"}")));
            List<Message> messages = receive(sqs, queueUrl);
            assertEquals(1, messages.size(), messages.toString());
            assertEquals("new", MAPPER.readTree(messages.getFirst().body()).path("detail").path("id").asText());

            events.updateSubscriber(request -> request.subscriberArn(subscriber).state(SubscriberState.STOPPED));
            sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(messages.getFirst().receiptHandle()));
            events.putEvents(request -> request.eventBusArn(bus).entries(
                    entry -> entry.source("com.other").detailType("t")));
            assertEquals(List.of(), receive(sqs, queueUrl));

            events.deleteSubscriber(request -> request.subscriberArn(subscriber));
            events.deleteEventBus(request -> request.eventBusArn(bus));
            sqs.deleteQueue(request -> request.queueUrl(queueUrl));
        }
    }

    @Test
    void aFilterWithAWildcardIsRejected() {
        String suffix = suffix();
        try (EventBridgeV2Client events = events()) {
            String bus = events.createEventBus(request -> request.name("v2-wild-" + suffix)).eventBusArn();
            InvalidInputException e = assertThrows(InvalidInputException.class, () -> events.createSubscriber(
                    request -> request.name("wild").eventBusArn(bus)
                            .invokeConfiguration(invoke -> invoke.roleArn(ROLE)
                                    .targetArn("arn:aws:sqs:us-east-1:000000000000:any"))
                            .filterConfiguration(filters -> filters.filters(filter -> filter.scope(FilterScope.DATA)
                                    .pattern("{\"source\":[{\"wildcard\":\"com.*\"}]}")))));
            assertEquals(SubscriberFilters.WILDCARD_MESSAGE, e.awsErrorDetails().errorMessage());
            events.deleteEventBus(request -> request.eventBusArn(bus));
        }
    }

    private static List<Message> receive(SqsClient sqs, String queueUrl) {
        return sqs.receiveMessage(request -> request.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(1))
                .messages();
    }

    private static String queueArn(SqsClient sqs, String queueUrl) {
        return sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private static EventBridgeV2Client events() {
        return EventBridgeV2Client.builder()
                .endpointOverride(endpoint())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static SqsClient sqs() {
        return SqsClient.builder()
                .endpointOverride(endpoint())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static URI endpoint() {
        return URI.create("http://localhost:" + RestAssured.port);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
