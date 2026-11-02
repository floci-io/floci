package io.github.hectorvent.floci.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.eventbridgev2.model.EventBusV2;
import io.github.hectorvent.floci.services.eventbridgev2.model.SubscriberV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class EventBridgeV2ServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00.123Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String QUEUE = "arn:aws:sqs:us-east-1:000000000000:orders";
    private static final String ROLE = "arn:aws:iam::000000000000:role/EventBusDelivery";

    private EventBridgeV2Service service;
    private EventBridgeV2Delivery delivery;

    @BeforeEach
    void setUp() {
        delivery = mock(EventBridgeV2Delivery.class);
        service = new EventBridgeV2Service(AccountAwareStorageBackend.inMemory(ACCOUNT),
                AccountAwareStorageBackend.inMemory(ACCOUNT), new RegionResolver(REGION, ACCOUNT), delivery,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createEventBusMintsAnActiveBusUnderAnEventBusV2Arn() {
        EventBusV2 bus = service.createEventBus(json("{\"Name\":\"orders\",\"Description\":\"Order events\","
                + "\"EncryptionConfiguration\":{\"KmsKeyIdentifier\":\"alias/orders\"},"
                + "\"StorageConfiguration\":{\"RetentionPeriodInDays\":90},\"Tags\":{\"team\":\"orders\"}}"), REGION);

        assertTrue(bus.getArn().matches("arn:aws:events:us-east-1:000000000000:event-busv2/orders/[a-z0-9]{25}"),
                bus.getArn());
        assertEquals("orders", bus.getName());
        assertEquals("Order events", bus.getDescription());
        assertEquals("alias/orders", bus.getKmsKeyIdentifier());
        assertEquals(90, bus.getRetentionPeriodInDays());
        assertEquals(EventBridgeV2Service.ACTIVE, bus.getState());
        assertEquals(NOW, bus.getCreationTime());
        assertEquals(NOW, bus.getLastModifiedTime());
        assertEquals(Map.of("team", "orders"), bus.getTags());
    }

    @Test
    void createEventBusLeavesOptionalConfigurationUnset() {
        EventBusV2 bus = service.createEventBus(json("{\"Name\":\"minimal\"}"), REGION);
        assertNull(bus.getDescription());
        assertNull(bus.getKmsKeyIdentifier());
        assertNull(bus.getRetentionPeriodInDays());
        assertEquals(Map.of(), bus.getTags());
    }

    @Test
    void createEventBus_existingName_resourceAlreadyExists() {
        service.createEventBus(json("{\"Name\":\"orders\"}"), REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createEventBus(json("{\"Name\":\"orders\"}"), REGION));
        assertEquals("ResourceAlreadyExistsException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
        // A bus name is unique per Region.
        service.createEventBus(json("{\"Name\":\"orders\"}"), "eu-west-1");
    }

    @Test
    void createEventBus_retryWithItsClientToken_returnsTheSameBus() {
        String request = "{\"Name\":\"orders\",\"ClientToken\":\"token-1\",\"Description\":\"d\"}";
        EventBusV2 created = service.createEventBus(json(request), REGION);
        EventBusV2 retried = service.createEventBus(json(request), REGION);
        assertEquals(created.getArn(), retried.getArn());
    }

    @Test
    void createEventBus_clientTokenWithAnotherRequest_idempotentParameterMismatch() {
        service.createEventBus(json("{\"Name\":\"orders\",\"ClientToken\":\"token-1\"}"), REGION);
        AwsException e = assertThrows(AwsException.class, () -> service.createEventBus(
                json("{\"Name\":\"orders\",\"ClientToken\":\"token-1\",\"Description\":\"changed\"}"), REGION));
        assertEquals("IdempotentParameterMismatchException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void createEventBus_retryWithItsFieldsAndTagsReordered_returnsTheSameBus() {
        EventBusV2 created = service.createEventBus(json("{\"Name\":\"orders\",\"ClientToken\":\"token-1\","
                + "\"Description\":\"d\",\"Tags\":{\"team\":\"orders\",\"env\":\"dev\"},"
                + "\"StorageConfiguration\":{\"RetentionPeriodInDays\":7}}"), REGION);
        EventBusV2 retried = service.createEventBus(json("{\"StorageConfiguration\":{\"RetentionPeriodInDays\":7},"
                + "\"Tags\":{\"env\":\"dev\",\"team\":\"orders\"},\"Description\":\"d\",\"ClientToken\":\"token-1\","
                + "\"Name\":\"orders\"}"), REGION);
        assertEquals(created.getArn(), retried.getArn());
    }

    @Test
    void createSubscriber_retryWithItsFieldsReordered_returnsTheSameSubscriber() {
        String bus = bus("orders");
        SubscriberV2 created = service.createSubscriber(json("{\"Name\":\"s\",\"ClientToken\":\"token-1\","
                + "\"EventBusArn\":\"" + bus + "\",\"InvokeConfiguration\":{\"TargetArn\":\"" + QUEUE + "\","
                + "\"RoleArn\":\"" + ROLE + "\"},\"Tags\":{\"a\":\"1\",\"b\":\"2\"}}"), REGION);
        SubscriberV2 retried = service.createSubscriber(json("{\"Tags\":{\"b\":\"2\",\"a\":\"1\"},"
                + "\"InvokeConfiguration\":{\"RoleArn\":\"" + ROLE + "\",\"TargetArn\":\"" + QUEUE + "\"},"
                + "\"EventBusArn\":\"" + bus + "\",\"ClientToken\":\"token-1\",\"Name\":\"s\"}"), REGION);
        assertEquals(created.getArn(), retried.getArn());
    }

    @Test
    void createEventBus_anotherClientToken_resourceAlreadyExists() {
        service.createEventBus(json("{\"Name\":\"orders\",\"ClientToken\":\"token-1\"}"), REGION);
        AwsException e = assertThrows(AwsException.class, () -> service.createEventBus(
                json("{\"Name\":\"orders\",\"ClientToken\":\"token-2\"}"), REGION));
        assertEquals("ResourceAlreadyExistsException", e.getErrorCode());
    }

    @Test
    void eachBusGetsItsOwnId() {
        String first = service.createEventBus(json("{\"Name\":\"a\"}"), REGION).getArn();
        String second = service.createEventBus(json("{\"Name\":\"b\"}"), REGION).getArn();
        assertNotEquals(first.substring(first.lastIndexOf('/')), second.substring(second.lastIndexOf('/')));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"Name\":\"\"}",
            "{\"Name\":\"-starts-with-dash\"}",
            "{\"Name\":\"has space\"}",
            "{\"Name\":7}",
            "{\"Name\":\"ok\",\"Description\":\"line\\nbreak\"}",
            "{\"Name\":\"ok\",\"StorageConfiguration\":{\"RetentionPeriodInDays\":0}}",
            "{\"Name\":\"ok\",\"StorageConfiguration\":{\"RetentionPeriodInDays\":366}}",
            "{\"Name\":\"ok\",\"StorageConfiguration\":[]}",
            "{\"Name\":\"ok\",\"EncryptionConfiguration\":{\"KmsKeyIdentifier\":\"not a key\"}}",
            "{\"Name\":\"ok\",\"ClientToken\":\"\"}",
            "{\"Name\":\"ok\",\"Tags\":{\" padded\":\"v\"}}",
            "{\"Name\":\"ok\",\"Tags\":{\"k\":7}}",
            "{\"Name\":\"ok\",\"Tags\":[]}"
    })
    void createEventBus_invalidRequest_invalidInput(String request) {
        AwsException e = assertThrows(AwsException.class, () -> service.createEventBus(json(request), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void createEventBusAcceptsTheLongestNameAndDescription() {
        String name = "n" + "a".repeat(255);
        EventBusV2 bus = service.createEventBus(json("{\"Name\":\"" + name + "\",\"Description\":\""
                + "d".repeat(512) + "\"}"), REGION);
        assertEquals(name, bus.getName());
        assertThrows(AwsException.class, () -> service.createEventBus(json("{\"Name\":\"" + name + "b\"}"), REGION));
    }

    @Test
    void clearRemovesEveryBus() {
        service.createEventBus(json("{\"Name\":\"orders\"}"), REGION);
        service.clear();
        service.createEventBus(json("{\"Name\":\"orders\"}"), REGION);
    }

    @Test
    void describeEventBusFindsABusByItsArnAlone() {
        String arn = bus("orders");
        assertEquals("orders", service.describeEventBus(json("{\"EventBusArn\":\"" + arn + "\"}"), REGION).getName());

        String sameNameOtherId = arn.substring(0, arn.lastIndexOf('/') + 1) + "zzzzzzzzzzzzzzzzzzzzzzzzz";
        AwsException missing = assertThrows(AwsException.class,
                () -> service.describeEventBus(json("{\"EventBusArn\":\"" + sameNameOtherId + "\"}"), REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        AwsException otherRegion = assertThrows(AwsException.class,
                () -> service.describeEventBus(json("{\"EventBusArn\":\"" + arn + "\"}"), "eu-west-1"));
        assertEquals("ResourceNotFoundException", otherRegion.getErrorCode());
        AwsException absent = assertThrows(AwsException.class, () -> service.describeEventBus(json("{}"), REGION));
        assertEquals("InvalidInputException", absent.getErrorCode());
    }

    @Test
    void listEventBusesPagesTheRegionsBusesInNameOrder() {
        for (String name : List.of("orders-b", "billing", "orders-a", "orders-c")) {
            bus(name);
        }
        service.createEventBus(json("{\"Name\":\"orders-elsewhere\"}"), "eu-west-1");

        PaginatedResult<EventBusV2> first = service.listEventBuses(json("{\"NamePrefix\":\"orders\",\"MaxResults\":2}"),
                REGION);
        assertEquals(List.of("orders-a", "orders-b"), first.items().stream().map(EventBusV2::getName).toList());
        assertNotNull(first.nextToken());

        PaginatedResult<EventBusV2> second = service.listEventBuses(json("{\"NamePrefix\":\"orders\",\"MaxResults\":2,"
                + "\"NextToken\":\"" + first.nextToken() + "\"}"), REGION);
        assertEquals(List.of("orders-c"), second.items().stream().map(EventBusV2::getName).toList());
        assertNull(second.nextToken());

        assertEquals(4, service.listEventBuses(json("{}"), REGION).items().size());
    }

    @Test
    void listEventBusesForAnotherAccountListsNone() {
        bus("orders");
        assertEquals(1, service.listEventBuses(json("{\"EventBusAccountId\":\"" + ACCOUNT + "\"}"), REGION)
                .items().size());
        assertEquals(List.of(), service.listEventBuses(json("{\"EventBusAccountId\":\"111122223333\"}"), REGION)
                .items());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"MaxResults\":0}", "{\"MaxResults\":101}", "{\"NamePrefix\":\"-orders\"}",
            "{\"EventBusAccountId\":\"123\"}", "{\"NextToken\":\"not-a-token\"}"})
    void listEventBuses_invalidRequest_invalidInput(String request) {
        AwsException e = assertThrows(AwsException.class, () -> service.listEventBuses(json(request), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    // ──────────────────────────── Subscribers ────────────────────────────

    @Test
    void createSubscriberOnABusStoresItsConfigurationWithDefaults() {
        String bus = bus("orders");
        SubscriberV2 subscriber = service.createSubscriber(json(subscriberRequest("large-orders", bus,
                ",\"FilterConfiguration\":{\"Filters\":[{\"Scope\":\"DATA\",\"Pattern\":"
                        + "\"{\\\"source\\\":[\\\"shop\\\"]}\"}]}")), REGION);

        assertTrue(subscriber.getArn().matches(
                "arn:aws:events:us-east-1:000000000000:subscriber/large-orders/[a-z0-9]{25}"), subscriber.getArn());
        assertEquals(bus, subscriber.getEventBusArn());
        assertEquals("RUNNING", subscriber.getState());
        assertEquals("UNORDERED", subscriber.getType());
        assertEquals("LATEST", subscriber.getStartingPosition());
        assertEquals(QUEUE, subscriber.getInvokeConfiguration().path("TargetArn").asText());
        assertEquals(NOW, subscriber.getCreationTime());
    }

    @Test
    void createSubscriber_unknownBus_resourceNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.createSubscriber(json(subscriberRequest("s",
                "arn:aws:events:us-east-1:000000000000:event-busv2/none/abcdefghijklmnopqrstuvwxy", "")), REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void createSubscriber_existingNameOnTheBus_resourceAlreadyExists() {
        String bus = bus("orders");
        service.createSubscriber(json(subscriberRequest("s", bus, "")), REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createSubscriber(json(subscriberRequest("s", bus, "")), REGION));
        assertEquals("ResourceAlreadyExistsException", e.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"TargetArn\":\"arn:aws:lambda:us-east-1:000000000000:function:f\",\"RoleArn\":\"ROLE\"}",
            "{\"TargetArn\":\"arn:aws:sqs:us-east-1:111111111111:other\",\"RoleArn\":\"ROLE\"}",
            "{\"TargetArn\":\"QUEUE\",\"RoleArn\":\"ROLE\",\"LambdaParameters\":{}}",
            "{\"TargetArn\":\"QUEUE\",\"RoleArn\":\"not-a-role\"}",
            "{\"TargetArn\":\"QUEUE\"}",
            "{\"TargetArn\":\"QUEUE\",\"RoleArn\":\"ROLE\",\"SqsParameters\":{\"MessageGroupId\":\"{% $uuid() %}\"}}"
    })
    void createSubscriber_unsupportedInvokeConfiguration_invalidInput(String invoke) {
        String bus = bus("orders");
        String request = "{\"Name\":\"s\",\"EventBusArn\":\"" + bus + "\",\"InvokeConfiguration\":"
                + invoke.replace("QUEUE", QUEUE).replace("ROLE", ROLE) + "}";
        AwsException e = assertThrows(AwsException.class, () -> service.createSubscriber(json(request), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"{% $uppercase($events.Data.source) %}\":{\"DataType\":\"String\",\"StringValue\":\"x\"}}",
            "{\"origin\":{\"DataType\":\"String\",\"StringValue\":\"{% $uppercase($events.Data.source) %}\"}}"})
    void createSubscriber_messageAttributeJsonata_invalidInput(String attributes) {
        String bus = bus("orders");
        String request = "{\"Name\":\"s\",\"EventBusArn\":\"" + bus + "\",\"InvokeConfiguration\":{\"TargetArn\":\""
                + QUEUE + "\",\"RoleArn\":\"" + ROLE + "\",\"SqsParameters\":{\"MessageAttributes\":" + attributes + "}}}";
        AwsException e = assertThrows(AwsException.class, () -> service.createSubscriber(json(request), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
        assertTrue(e.getMessage().startsWith("SqsParameters.MessageAttributes"), e.getMessage());
    }

    @Test
    void createSubscriber_jsonataTransformer_invalidInput() {
        String bus = bus("orders");
        AwsException e = assertThrows(AwsException.class, () -> service.createSubscriber(json(subscriberRequest("s",
                bus, ",\"Transformer\":{\"Type\":\"JSONATA\",\"JsonataConfiguration\":{\"Expression\":\"{% 1 %}\"}}")),
                REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
    }

    @Test
    void deleteEventBus_withASubscriber_resourceInUse() {
        String bus = bus("orders");
        SubscriberV2 subscriber = service.createSubscriber(json(subscriberRequest("s", bus, "")), REGION);
        AwsException e = assertThrows(AwsException.class, () -> service.deleteEventBus(bus, REGION));
        assertEquals("ResourceInUseException", e.getErrorCode());
        service.deleteSubscriber(subscriber.getArn(), REGION);
        service.deleteEventBus(bus, REGION);
        assertTrue(service.findEventBus(bus, REGION).isEmpty());
    }

    @Test
    void deleteSubscriber_unknown_resourceNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.deleteSubscriber(
                "arn:aws:events:us-east-1:000000000000:subscriber/s/abcdefghijklmnopqrstuvwxy", REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void deleteWithoutAnArn_invalidInput() {
        AwsException bus = assertThrows(AwsException.class, () -> service.deleteEventBus(json("{}"), REGION));
        assertEquals("InvalidInputException", bus.getErrorCode());
        AwsException subscriber = assertThrows(AwsException.class, () -> service.deleteSubscriber(json("{}"), REGION));
        assertEquals("InvalidInputException", subscriber.getErrorCode());
    }

    @Test
    void createSubscriberStoresAPointInTimeAsEpochSeconds() {
        SubscriberV2 subscriber = service.createSubscriber(json(subscriberRequest("s", bus("orders"),
                ",\"StartingPosition\":\"POINT_IN_TIME\",\"PointInTimeConfiguration\":{\"PointType\":\"TIMESTAMP\","
                        + "\"StartingPoint\":1767225600.25,\"EndPoint\":1767312000}")), REGION);

        JsonNode pointInTime = subscriber.getPointInTimeConfiguration();
        assertTrue(pointInTime.path("StartingPoint").isNumber());
        assertEquals(0, new BigDecimal("1767225600.25").compareTo(pointInTime.path("StartingPoint").decimalValue()));
        assertEquals(0, new BigDecimal("1767312000").compareTo(pointInTime.path("EndPoint").decimalValue()));
        assertEquals("TIMESTAMP", pointInTime.path("PointType").asText());
    }

    @Test
    void createSubscriber_pointInTimeThatIsNotATimestamp_invalidInput() {
        String bus = bus("orders");
        AwsException e = assertThrows(AwsException.class, () -> service.createSubscriber(json(subscriberRequest("s",
                bus, ",\"StartingPosition\":\"POINT_IN_TIME\",\"PointInTimeConfiguration\":{\"PointType\":"
                        + "\"TIMESTAMP\",\"StartingPoint\":\"2026-01-01T00:00:00Z\"}")), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
    }

    @Test
    void updateSubscriberStoresANewSubscriberAndLeavesTheOneAReaderHeldUnchanged() {
        SubscriberV2 created = service.createSubscriber(json(subscriberRequest("s", bus("orders"),
                ",\"Description\":\"d\"")), REGION);
        SubscriberV2 held = service.findSubscriber(created.getArn(), REGION).orElseThrow();

        service.updateSubscriber(json("{\"SubscriberArn\":\"" + created.getArn() + "\",\"State\":\"STOPPED\","
                + "\"Description\":\"changed\"}"), REGION);

        assertEquals("d", held.getDescription());
        assertEquals(EventBridgeV2Service.RUNNING, held.getState());
        SubscriberV2 stored = service.findSubscriber(created.getArn(), REGION).orElseThrow();
        assertEquals("changed", stored.getDescription());
        assertEquals("STOPPED", stored.getState());
        assertEquals(created.getCreationTime(), stored.getCreationTime());
    }

    @Test
    void replaceSubscriberSettingsClearsWhatTheRequestLeavesOut() {
        String bus = bus("orders");
        SubscriberV2 created = service.createSubscriber(json(subscriberRequest("s", bus,
                ",\"Description\":\"d\",\"FilterConfiguration\":{\"Filters\":[{\"Scope\":\"DATA\","
                        + "\"Pattern\":\"{\\\"source\\\":[\\\"a\\\"]}\"}]}")), REGION);
        SubscriberV2 updated = service.replaceSubscriberSettings(created.getArn(),
                json(subscriberRequest("s", bus, ",\"State\":\"STOPPED\"")), REGION);
        assertNull(updated.getDescription());
        assertNull(updated.getFilterConfiguration());
        assertEquals("STOPPED", updated.getState());
        assertEquals(created.getArn(), updated.getArn());
    }

    @Test
    void replaceSubscriberSettings_invalidSetting_leavesTheSubscriberAsItWas() {
        String bus = bus("orders");
        SubscriberV2 created = service.createSubscriber(json(subscriberRequest("s", bus, ",\"Description\":\"d\"")),
                REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.replaceSubscriberSettings(created.getArn(),
                json(subscriberRequest("s", bus, ",\"Description\":\"changed\",\"State\":\"STOPPED\","
                        + "\"RetryPolicy\":{\"MaxRetryAttempts\":999}")), REGION));

        assertEquals("InvalidInputException", e.getErrorCode());
        SubscriberV2 unchanged = service.findSubscriber(created.getArn(), REGION).orElseThrow();
        assertEquals("d", unchanged.getDescription());
        assertEquals(EventBridgeV2Service.RUNNING, unchanged.getState());
        assertNull(unchanged.getRetryPolicy());
    }

    @Test
    void replaceEventBusSettings_invalidSetting_leavesTheBusAsItWas() {
        String arn = service.createEventBus(json("{\"Name\":\"orders\",\"Description\":\"d\"}"), REGION).getArn();

        AwsException e = assertThrows(AwsException.class, () -> service.replaceEventBusSettings(arn,
                json("{\"Name\":\"orders\",\"Description\":\"changed\",\"StorageConfiguration\":"
                        + "{\"RetentionPeriodInDays\":999}}"), REGION));

        assertEquals("InvalidInputException", e.getErrorCode());
        assertEquals("d", service.findEventBus(arn, REGION).orElseThrow().getDescription());
    }

    @Test
    void updateSubscriberChangesWhatTheRequestGivesAndKeepsTheRest() {
        String bus = bus("orders");
        SubscriberV2 created = service.createSubscriber(json(subscriberRequest("s", bus,
                ",\"Description\":\"d\",\"Tags\":{\"team\":\"orders\"},\"FilterConfiguration\":{\"Filters\":[{"
                        + "\"Scope\":\"DATA\",\"Pattern\":\"{\\\"source\\\":[\\\"a\\\"]}\"}]}")), REGION);
        Instant createdAt = created.getCreationTime();

        SubscriberV2 updated = service.updateSubscriber(json("{\"SubscriberArn\":\"" + created.getArn()
                + "\",\"State\":\"STOPPED\",\"ResumePosition\":\"LATEST\",\"InvokeConfiguration\":{\"RoleArn\":"
                + "\"arn:aws:iam::000000000000:role/Other\",\"SqsParameters\":{\"MessageGroupId\":\"g\"}}}"), REGION);

        assertEquals("STOPPED", updated.getState());
        assertEquals("d", updated.getDescription());
        assertEquals("{\"source\":[\"a\"]}", updated.getFilterConfiguration().path("Filters").path(0).path("Pattern")
                .asText());
        assertEquals(Map.of("team", "orders"), updated.getTags());
        assertEquals(QUEUE, updated.getInvokeConfiguration().path("TargetArn").asText(), "the target is kept");
        assertEquals("arn:aws:iam::000000000000:role/Other", updated.getInvokeConfiguration().path("RoleArn").asText());
        assertEquals("g", updated.getInvokeConfiguration().path("SqsParameters").path("MessageGroupId").asText());
        assertEquals(createdAt, updated.getCreationTime());
    }

    @Test
    void updateSubscriber_invalidSetting_leavesTheSubscriberAsItWas() {
        String bus = bus("orders");
        SubscriberV2 created = service.createSubscriber(json(subscriberRequest("s", bus, ",\"Description\":\"d\"")),
                REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.updateSubscriber(json("{\"SubscriberArn\":\""
                + created.getArn() + "\",\"Description\":\"changed\",\"RetryPolicy\":{\"MaxRetryAttempts\":999}}"),
                REGION));

        assertEquals("InvalidInputException", e.getErrorCode());
        SubscriberV2 unchanged = service.findSubscriber(created.getArn(), REGION).orElseThrow();
        assertEquals("d", unchanged.getDescription());
        assertNull(unchanged.getRetryPolicy());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"ResumePosition\":\"FIRST\"", "\"State\":\"PAUSED\"",
            "\"InvokeConfiguration\":{\"RoleArn\":\"arn:aws:iam::000000000000:role/r\",\"TargetArn\":\"" + QUEUE + "\"}",
            "\"FilterConfiguration\":{\"Filters\":[{\"Scope\":\"DATA\",\"Pattern\":\"{\\\"a\\\":[{\\\"wildcard\\\":\\\"*\\\"}]}\"}]}"})
    void updateSubscriber_invalidRequest_invalidInput(String member) {
        SubscriberV2 created = service.createSubscriber(json(subscriberRequest("s", bus("orders"), "")), REGION);
        AwsException e = assertThrows(AwsException.class, () -> service.updateSubscriber(json("{\"SubscriberArn\":\""
                + created.getArn() + "\"," + member + "}"), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
    }

    @Test
    void updateSubscriber_unknown_resourceNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.updateSubscriber(json("{\"SubscriberArn\":"
                + "\"arn:aws:events:us-east-1:000000000000:subscriber/s/abcdefghijklmnopqrstuvwxy\"}"), REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void describeSubscriberFindsASubscriberByItsArn() {
        SubscriberV2 created = service.createSubscriber(json(subscriberRequest("s", bus("orders"), "")), REGION);
        assertEquals("s", service.describeSubscriber(json("{\"SubscriberArn\":\"" + created.getArn() + "\"}"), REGION)
                .getName());
        AwsException absent = assertThrows(AwsException.class, () -> service.describeSubscriber(json("{}"), REGION));
        assertEquals("InvalidInputException", absent.getErrorCode());
    }

    @Test
    void listSubscribersPagesTheRegionsOrOneBussSubscribers() {
        String orders = bus("orders");
        String billing = bus("billing");
        for (String name : List.of("orders-b", "orders-a", "audit")) {
            service.createSubscriber(json(subscriberRequest(name, orders, "")), REGION);
        }
        service.createSubscriber(json(subscriberRequest("orders-c", billing, "")), REGION);

        PaginatedResult<SubscriberV2> first = service.listSubscribers(json("{\"EventBusArn\":\"" + orders
                + "\",\"NamePrefix\":\"orders\",\"MaxResults\":1}"), REGION);
        assertEquals(List.of("orders-a"), first.items().stream().map(SubscriberV2::getName).toList());
        PaginatedResult<SubscriberV2> second = service.listSubscribers(json("{\"EventBusArn\":\"" + orders
                + "\",\"NamePrefix\":\"orders\",\"MaxResults\":1,\"NextToken\":\"" + first.nextToken() + "\"}"), REGION);
        assertEquals(List.of("orders-b"), second.items().stream().map(SubscriberV2::getName).toList());
        assertNull(second.nextToken());

        assertEquals(List.of("orders-c", "audit", "orders-a", "orders-b"), service.listSubscribers(json("{}"), REGION)
                .items().stream().map(SubscriberV2::getName).toList(), "every bus's, by bus then name");

        AwsException e = assertThrows(AwsException.class, () -> service.listSubscribers(json("{\"EventBusArn\":"
                + "\"arn:aws:events:us-east-1:000000000000:event-busv2/none/abcdefghijklmnopqrstuvwxy\"}"), REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    // ──────────────────────────── PutEvents ────────────────────────────

    @Test
    void putEventsDeliversAMatchingEventInTheEventBridgeEnvelope() {
        String bus = bus("orders");
        SubscriberV2 large = service.createSubscriber(json(subscriberRequest("large", bus,
                ",\"FilterConfiguration\":{\"Filters\":[{\"Scope\":\"DATA\",\"Pattern\":"
                        + "\"{\\\"detail\\\":{\\\"total\\\":[{\\\"numeric\\\":[\\\">\\\",500]}]}}\"},"
                        + "{\"Scope\":\"SYSTEM_METADATA\",\"Pattern\":\"{\\\"aws:Source\\\":[\\\"shop\\\"]}\"}]}")),
                REGION);

        List<EventBridgeV2Service.PutEventsResult> results = service.putEvents(json("{\"EventBusArn\":\"" + bus
                + "\",\"Entries\":[{\"Source\":\"shop\",\"DetailType\":\"Order Placed\",\"Detail\":\"{\\\"total\\\":750}\"},"
                + "{\"Source\":\"shop\",\"DetailType\":\"Order Placed\",\"Detail\":\"{\\\"total\\\":10}\"},"
                + "{\"Source\":\"other\",\"DetailType\":\"Order Placed\",\"Detail\":\"{\\\"total\\\":900}\"}]}"), REGION);

        assertEquals(3, results.size());
        assertEquals(List.of("1", "2", "3"), results.stream().map(EventBridgeV2Service.PutEventsResult::sequenceNumber)
                .toList());
        ArgumentCaptor<SubscriberV2> delivered = ArgumentCaptor.forClass(SubscriberV2.class);
        ArgumentCaptor<PublishedEvent> event = ArgumentCaptor.forClass(PublishedEvent.class);
        verify(delivery, times(1)).deliver(delivered.capture(), event.capture());
        assertEquals(large.getArn(), delivered.getValue().getArn());
        JsonNode data = event.getValue().data();
        assertEquals("0", data.path("version").asText());
        assertEquals(results.getFirst().eventId(), data.path("id").asText());
        assertEquals("Order Placed", data.path("detail-type").asText());
        assertEquals("shop", data.path("source").asText());
        assertEquals(ACCOUNT, data.path("account").asText());
        assertEquals(REGION, data.path("region").asText());
        assertEquals("2026-10-28T12:00:00Z", data.path("time").asText());
        assertEquals(750, data.path("detail").path("total").asInt());
        assertEquals("application/eventbridge+json", event.getValue().systemMetadata().path("ContentType").asText());
        assertEquals("1", event.getValue().systemMetadata().path("aws:SequenceNumber").asText());
    }

    @Test
    void putEventsSkipsAStoppedSubscriberAndRefusesAMalformedDetailAlone() {
        String bus = bus("orders");
        service.createSubscriber(json(subscriberRequest("s", bus, ",\"State\":\"STOPPED\"")), REGION);

        List<EventBridgeV2Service.PutEventsResult> results = service.putEvents(json("{\"EventBusArn\":\"" + bus
                + "\",\"Entries\":[{\"Source\":\"a\",\"DetailType\":\"t\",\"Detail\":\"not json\"},"
                + "{\"Source\":\"a\",\"DetailType\":\"t\"}]}"), REGION);

        assertEquals("MalformedDetail", results.getFirst().errorCode());
        assertNull(results.get(1).errorCode());
        verify(delivery, never()).deliver(any(), any());
    }

    @Test
    void putEvents_malformedTime_failsTheRequestWithoutUsingASequenceNumber() {
        String bus = bus("orders");
        service.createSubscriber(json(subscriberRequest("s", bus, "")), REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.putEvents(json("{\"EventBusArn\":\"" + bus
                + "\",\"Entries\":[{\"Source\":\"a\",\"DetailType\":\"t\"},"
                + "{\"Source\":\"a\",\"DetailType\":\"t\",\"Time\":\"yesterday\"}]}"), REGION));
        assertEquals("InvalidInputException", e.getErrorCode());
        verify(delivery, never()).deliver(any(), any());

        List<EventBridgeV2Service.PutEventsResult> results = service.putEvents(json("{\"EventBusArn\":\"" + bus
                + "\",\"Entries\":[{\"Source\":\"a\",\"DetailType\":\"t\"}]}"), REGION);
        assertEquals("1", results.getFirst().sequenceNumber());
    }

    @Test
    void concurrentPutEventsDeliverInSequenceOrder() throws Exception {
        String bus = bus("orders");
        service.createSubscriber(json(subscriberRequest("fifo", bus, ",\"Type\":\"FIFO\"")), REGION);
        List<Long> delivered = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            PublishedEvent event = invocation.getArgument(1);
            delivered.add(Long.parseLong(event.systemMetadata().path("aws:SequenceNumber").asText()));
            Thread.yield();
            return null;
        }).when(delivery).deliver(any(), any());

        int threads = 8;
        int requestsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> requests = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            requests.add(executor.submit(() -> {
                start.await();
                for (int i = 0; i < requestsPerThread; i++) {
                    service.putEvents(json("{\"EventBusArn\":\"" + bus + "\",\"Entries\":["
                            + "{\"Source\":\"a\",\"DetailType\":\"t\"},{\"Source\":\"a\",\"DetailType\":\"t\"}]}"), REGION);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> request : requests) {
            request.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        List<Long> expected = LongStream.rangeClosed(1, threads * requestsPerThread * 2L).boxed().toList();
        assertEquals(expected, delivered);
    }

    @Test
    void putEvents_unknownBus_resourceNotFound() {
        AwsException e = assertThrows(AwsException.class, () -> service.putEvents(json("{\"EventBusArn\":"
                + "\"arn:aws:events:us-east-1:000000000000:event-busv2/none/abcdefghijklmnopqrstuvwxy\","
                + "\"Entries\":[{\"Source\":\"a\",\"DetailType\":\"t\"}]}"), REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    private String bus(String name) {
        return service.createEventBus(json("{\"Name\":\"" + name + "\"}"), REGION).getArn();
    }

    private static String subscriberRequest(String name, String bus, String extra) {
        return "{\"Name\":\"" + name + "\",\"EventBusArn\":\"" + bus + "\",\"InvokeConfiguration\":{\"TargetArn\":\""
                + QUEUE + "\",\"RoleArn\":\"" + ROLE + "\"}" + extra + "}";
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
