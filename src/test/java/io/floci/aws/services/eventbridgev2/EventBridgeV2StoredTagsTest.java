package io.floci.aws.services.eventbridgev2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.services.eventbridgev2.model.EventBusV2;
import io.floci.aws.services.eventbridgev2.model.EventSourceV2;
import io.floci.aws.services.eventbridgev2.model.SubscriberV2;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class EventBridgeV2StoredTagsTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111122223333";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::000000000000:role/Hook";
    private static final String QUEUE = "arn:aws:sqs:us-east-1:000000000000:orders";

    private AccountAwareStorageBackend<EventBusV2> buses;
    private InMemoryStorage<String, SubscriberV2> rawSubscribers;
    private AccountAwareStorageBackend<SubscriberV2> subscribers;
    private EventBridgeV2Service service;
    private String busArn;
    private String subscriberArn;
    private String sourceArn;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        Clock clock = Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"), ZoneOffset.UTC);
        EventBridgeV2EventSources eventSources = new EventBridgeV2EventSources(
                AccountAwareStorageBackend.inMemory(ACCOUNT), mock(SecretsManagerService.class, RETURNS_DEEP_STUBS), regionResolver, clock,
                "http://floci.test:4566", mock(TlsCertificateManager.class), mock(EventBridgeService.class));
        buses = AccountAwareStorageBackend.inMemory(ACCOUNT);
        rawSubscribers = new InMemoryStorage<>();
        subscribers = new AccountAwareStorageBackend<>(rawSubscribers, null, ACCOUNT);
        service = new EventBridgeV2Service(buses, subscribers, regionResolver, mock(EventBridgeV2Delivery.class),
                eventSources, clock, true);
        busArn = service.createEventBus(json("{\"Name\":\"orders\",\"Tags\":{\"b\":\"2\",\"a\":\"1\"}}"), REGION)
                .getArn();
        subscriberArn = service.createSubscriber(json("{\"Name\":\"sub\",\"EventBusArn\":\"" + busArn
                + "\",\"InvokeConfiguration\":{\"TargetArn\":\"" + QUEUE + "\",\"RoleArn\":\"" + ROLE
                + "\"},\"Tags\":{\"team\":\"x\"}}"), REGION).getArn();
        EventSourceV2 source = service.createEventSource(json("{\"ClientToken\":\"t\",\"Name\":\"hook\","
                + "\"EventBusArn\":\"" + busArn + "\",\"Configuration\":{\"WebhookEventsConfiguration\":"
                + "{\"AuthConfiguration\":{\"StaticTokenAuthConfiguration\":{\"TokenHttpHeader\":\"X-Tok\","
                + "\"SecretValue\":\"original-token\"}},\"RoleArn\":\"" + ROLE + "\"}},\"Tags\":{\"src\":\"y\"}}"),
                REGION);
        sourceArn = source.getArn();
    }

    @Test
    void eachTypeAnswersTheTagsOfTheResourceItsArnNames() {
        assertEquals(List.of("b", "a"), List.copyOf(service.storedTags(busArn).keySet()));
        assertEquals(Map.of("b", "2", "a", "1"), service.storedTags(busArn));
        assertEquals(Map.of("team", "x"), service.storedTags(subscriberArn));
        assertEquals(Map.of("src", "y"), service.storedTags(sourceArn));
    }

    @Test
    void anArnWithAnotherIdDoesNotBorrowTheTagsOfTheSameName() {
        assertTrue(service.storedTags(withOtherId(busArn)).isEmpty());
        assertTrue(service.storedTags(withOtherId(subscriberArn)).isEmpty());
        assertTrue(service.storedTags(withOtherId(sourceArn)).isEmpty());
    }

    @Test
    void anArnOfAnotherAccountOrRegionAnswersEmpty() {
        for (String arn : List.of(busArn, subscriberArn, sourceArn)) {
            assertTrue(service.storedTags(arn.replace(":" + ACCOUNT + ":", ":" + OTHER_ACCOUNT + ":")).isEmpty());
            assertTrue(service.storedTags(arn.replace("us-east-1", "us-west-2")).isEmpty());
        }
    }

    @Test
    void theTagsAreReadInTheAccountTheArnNames() {
        EventBusV2 bus = new EventBusV2();
        String otherArn = "arn:aws:events:us-east-1:" + OTHER_ACCOUNT + ":event-busv2/orders/abcdefghijklmnopqrstuvwxy";
        bus.setName("orders");
        bus.setArn(otherArn);
        bus.setTags(Map.of("owner", "other"));
        buses.putForAccount(OTHER_ACCOUNT, REGION + "::orders", bus);
        SubscriberV2 subscriber = new SubscriberV2();
        String otherSubscriberArn = subscriberArn.replace(":" + ACCOUNT + ":", ":" + OTHER_ACCOUNT + ":");
        subscriber.setName("sub");
        subscriber.setArn(otherSubscriberArn);
        subscriber.setEventBusArn(otherArn);
        subscriber.setTags(Map.of("owner", "other"));
        subscribers.putForAccount(OTHER_ACCOUNT, REGION + "::orders::sub", subscriber);

        assertEquals(Map.of("owner", "other"), service.storedTags(otherArn));
        assertEquals(Map.of("b", "2", "a", "1"), service.storedTags(busArn));
        assertEquals(Map.of("owner", "other"), service.storedTags(otherSubscriberArn));
        assertEquals(Map.of("team", "x"), service.storedTags(subscriberArn));
    }

    @Test
    void aRevokedSubscriberKeepsItsTagsAfterItsBusIsDeleted() {
        service.revokeResource(json("{\"Arn\":\"" + subscriberArn + "\"}"), REGION);
        service.revokeResource(json("{\"Arn\":\"" + sourceArn + "\"}"), REGION);
        service.deleteEventBus(busArn, REGION);

        assertEquals(Map.of("team", "x"), service.storedTags(subscriberArn));
    }

    @Test
    void aSubscriberStoredBeforeAccountsWereSeparatedIsReadInPlace() {
        SubscriberV2 legacy = new SubscriberV2();
        String legacyArn = "arn:aws:events:us-east-1:000000000000:subscriber/old/abcdefghijklmnopqrstuvwxy";
        legacy.setName("old");
        legacy.setArn(legacyArn);
        legacy.setEventBusArn(busArn);
        legacy.setTags(Map.of("team", "legacy"));
        rawSubscribers.put(REGION + "::orders::old", legacy);

        assertEquals(Map.of("team", "legacy"), service.storedTags(legacyArn));
        assertTrue(rawSubscribers.keys().contains(REGION + "::orders::old"));
    }

    @Test
    void nothingStoredOrAnotherResourceTypeAnswersEmpty() {
        assertTrue(service.storedTags("arn:aws:events:us-east-1:000000000000:event-busv2/none/abcdefghijklmnopqrstuvwxy")
                .isEmpty());
        assertTrue(service.storedTags("arn:aws:events:us-east-1:000000000000:subscriber/none/abc").isEmpty());
        assertTrue(service.storedTags("arn:aws:events:us-east-1:000000000000:event-bus/default").isEmpty());
        assertTrue(service.storedTags("arn:aws:sqs:us-east-1:000000000000:orders").isEmpty());
        assertTrue(service.storedTags("orders").isEmpty());
    }

    private static String withOtherId(String arn) {
        return arn.substring(0, arn.lastIndexOf('/') + 1) + "zzzzzzzzzzzzzzzzzzzzzzzzz";
    }

    private static JsonNode json(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
