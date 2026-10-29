package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import io.github.hectorvent.floci.services.redshift.model.RedshiftEvent;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedshiftEventServiceTest {
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:redshift-events";
    private static final BiPredicate<String, String> ALL_SOURCES_EXIST = (type, id) -> true;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SnsService sns;
    private ServiceCallAuthorizer authorizer;
    private MutableClock clock;
    private RedshiftEventService service;
    private StorageFactory factory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagRequestsApplyToTheLatestCloudFormationTags(boolean delete) throws Exception {
        create("watcher", null, List.of(), List.of(), null);
        service.replaceSubscriptionTags("watcher", Map.of("phase", "initial", "remove", "initial"));
        RedshiftService redshift = new RedshiftService(factory, null, null,
                new RegionResolver("us-east-1", "000000000000"), null, null, null, null,
                objectMapper, null, null, service);
        String arn = "arn:aws:redshift:us-east-1:000000000000:eventsubscription:watcher";
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> taggingThread = new AtomicReference<>();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> tagging;
            synchronized (service) {
                tagging = executor.submit(() -> {
                    taggingThread.set(Thread.currentThread());
                    started.countDown();
                    if (delete) {
                        redshift.deleteTags(arn, List.of("remove"));
                    } else {
                        redshift.createTags(arn, Map.of("api", "added"));
                    }
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> tagging.get(300, TimeUnit.MILLISECONDS));
                assertEquals(Thread.State.BLOCKED, taggingThread.get().getState());
                redshift.reconcileEventSubscription("watcher", TOPIC, null, List.of(), List.of(), null, true,
                        Map.of("phase", "updated", "remove", "updated"));
            }
            tagging.get(5, TimeUnit.SECONDS);
        }

        Map<String, String> expected = delete ? Map.of("phase", "updated")
                : Map.of("phase", "updated", "remove", "updated", "api", "added");
        assertEquals(expected, redshift.listTagsForResource(arn));
    }

    @ParameterizedTest
    @CsvSource({"success,active", "NotFound,topic-not-exist", "AuthorizationError,no-permission"})
    void explicitRecordingUsesTheOwnersHistorySubscriptionsAndFailureStatus(String failure, String expectedStatus) {
        create("watcher", null, List.of(), List.of(), null);
        String owner = "565656565656";
        String region = "us-west-2";
        String ownerTopic = "arn:aws:sns:us-west-2:565656565656:snapshots";
        AccountAwareStorageBackend<EventSubscription> subscriptionStore = factory.create("redshift",
                "redshift-event-subscriptions.json", new TypeReference<Map<String, EventSubscription>>() { });
        EventSubscription watcher = new EventSubscription();
        watcher.setCustSubscriptionId("watcher");
        watcher.setCustomerAwsId(owner);
        watcher.setSnsTopicArn(ownerTopic);
        watcher.setStatus("active");
        watcher.setEnabled(true);
        subscriptionStore.putForAccount(owner, region + "/watcher", watcher);
        if (!"success".equals(failure)) {
            doThrow(new AwsException(failure, "delivery failed", 400)).when(sns)
                    .publish(eq(ownerTopic), any(), anyString(), anyString(), eq(region));
        }

        service.recordForAccount(owner, region, RedshiftEventCatalog.SNAPSHOT_COMPLETED, "owner-snapshot", "completed");

        AccountAwareStorageBackend<RedshiftEvent> history = factory.create("redshift", "redshift-events.json",
                new TypeReference<Map<String, RedshiftEvent>>() { });
        assertEquals(1, history.scanForAccount(owner, key -> key.startsWith(region + "/")).size());
        assertTrue(service.describeEvents(null, null, null, null, null, null, null).items().isEmpty());
        assertEquals(expectedStatus, subscriptionStore.getForAccount(owner, region + "/watcher").orElseThrow().getStatus());
        assertEquals("active", service.describeSubscriptions("watcher", null, null, null, null).items().getFirst().getStatus());
        verify(sns).publish(eq(ownerTopic), any(), anyString(), anyString(), eq(region));
        verify(sns, never()).publish(eq(TOPIC), any(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"NotFound,deleted", "NotFound,recreated", "NotFound,retargeted",
            "AuthorizationError,deleted", "AuthorizationError,recreated", "AuthorizationError,retargeted"})
    void failedNotificationDoesNotOverwriteConcurrentSubscriptionChange(String errorCode, String change) throws Exception {
        create("watcher", null, List.of(), List.of(), null);
        String replacementTopic = "arn:aws:sns:us-east-1:000000000000:replacement";
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            publishing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "SNS publish was not released");
            throw new AwsException(errorCode, "Topic delivery failed", 400);
        }).when(sns).publish(eq(TOPIC), any(), anyString(), anyString(), anyString());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> recording = executor.submit(() ->
                    service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created"));
            try {
                assertTrue(publishing.await(5, TimeUnit.SECONDS), "SNS publish did not start");
                switch (change) {
                    case "deleted" -> service.deleteSubscription("watcher");
                    case "recreated" -> {
                        service.deleteSubscription("watcher");
                        create("watcher", null, List.of(), List.of(), null);
                    }
                    case "retargeted" -> service.modifySubscription("watcher", replacementTopic,
                            null, null, null, null, null, ALL_SOURCES_EXIST);
                    default -> throw new IllegalArgumentException(change);
                }
            } finally {
                release.countDown();
            }
            recording.get(5, TimeUnit.SECONDS);
        }

        List<EventSubscription> remaining = service.describeSubscriptions(null, null, null, null, null).items();
        if ("deleted".equals(change)) {
            assertTrue(remaining.isEmpty(), "A late SNS failure must not recreate a deleted subscription");
        } else {
            assertEquals(1, remaining.size());
            assertEquals("active", remaining.getFirst().getStatus());
            assertEquals("retargeted".equals(change) ? replacementTopic : TOPIC, remaining.getFirst().getSnsTopicArn());
        }
    }

    @Test
    void pruningRemovesExpiredHistoryAcrossAccountsAndRegionsDuringAlternatingTraffic() {
        AccountAwareStorageBackend<RedshiftEvent> backend = factory.create("redshift", "redshift-events.json",
                new TypeReference<Map<String, RedshiftEvent>>() { });
        Instant expired = clock.instant().minus(RedshiftEventService.RETENTION).minusSeconds(1);
        for (String scope : List.of("000000000000/us-east-1", "000000000000/us-west-2", "111111111111/us-east-1")) {
            RedshiftEvent event = new RedshiftEvent();
            event.setKey("expired");
            event.setDate(expired);
            int separator = scope.indexOf('/');
            backend.putForAccount(scope.substring(0, separator), scope.substring(separator + 1) + "/expired", event);
        }
        RedshiftEvent recent = new RedshiftEvent();
        recent.setKey("recent");
        recent.setDate(clock.instant());
        backend.putForAccount("111111111111", "us-west-2/recent", recent);
        AtomicReference<String> region = new AtomicReference<>("us-east-1");
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getRegion()).thenAnswer(invocation -> region.get());
        RedshiftEventService alternating = new RedshiftEventService(factory, sns, authorizer, resolver, objectMapper, clock);
        for (int index = 0; index < 200; index++) {
            region.set(index % 2 == 0 ? "us-east-1" : "us-west-2");
            alternating.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        }

        Map<String, RedshiftEvent> stored = backend.scanAllAccountsWithRawKeys();
        assertEquals(201, stored.size());
        assertTrue(stored.values().stream().noneMatch(event -> event.getDate().equals(expired)));
        assertTrue(stored.containsKey("111111111111/us-west-2/recent"));
    }

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("redshift")).thenReturn("memory");
        when(config.storage().persistentPath()).thenReturn("target/test-data");
        when(config.defaultAccountId()).thenReturn("000000000000");
        factory = new StorageFactory(config, access);
        sns = mock(SnsService.class);
        clock = new MutableClock(Instant.parse("2026-10-26T12:00:00Z"));
        when(sns.topicExists(anyString(), anyString())).thenReturn(true);
        authorizer = mock(ServiceCallAuthorizer.class);
        when(authorizer.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Decision.ALLOW);
        service = new RedshiftEventService(factory, sns, authorizer, new RegionResolver("us-east-1", "000000000000"),
                objectMapper, clock);
    }

    @Test
    void describeEventsDefaultsToTheLastHour() {
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        clock.advance(Duration.ofMinutes(30));
        service.record(RedshiftEventCatalog.CLUSTER_REBOOTED, "warehouse", "rebooted");
        clock.advance(Duration.ofMinutes(45));

        assertEquals(List.of("REDSHIFT-EVENT-3000"), ids(service.describeEvents(null, null, null, null, null, null, null)));
        assertEquals(List.of("REDSHIFT-EVENT-2000", "REDSHIFT-EVENT-3000"),
                ids(service.describeEvents(null, null, null, null, 120, null, null)));
    }

    @Test
    void describeEventsFiltersBySourceAndTimeWindow() {
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "analytics", "created");
        service.record(RedshiftEventCatalog.PARAMETER_GROUP_CREATED, "warehouse", "group created");
        Instant start = clock.instant().minusSeconds(60);
        Instant end = clock.instant().plusSeconds(60);

        List<RedshiftEvent> events = service.describeEvents("warehouse", "cluster", start, end, null, null, null).items();

        assertEquals(1, events.size());
        assertEquals("warehouse", events.getFirst().getSourceIdentifier());
        assertTrue(service.describeEvents(null, null, end.plusSeconds(1), end.plusSeconds(2), null, null, null)
                .items().isEmpty());
    }

    @Test
    void describeEventsRejectsAnUnpairedSourceAndOutOfRangePages() {
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> service.describeEvents("warehouse", null, null, null, null, null, null)).getErrorCode());
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> service.describeEvents(null, "cluster", null, null, null, null, null)).getErrorCode());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> service.describeEvents(null, "database", null, null, null, null, null)).getErrorCode());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> service.describeEvents(null, null, null, null, null, 19, null)).getErrorCode());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> service.describeEvents(null, null, null, null, null, 101, null)).getErrorCode());
    }

    @Test
    void describeEventsPagesWithoutRepeatingOrSkipping() {
        for (int i = 0; i < 25; i++) {
            service.record(RedshiftEventCatalog.CLUSTER_CREATED, "cluster-" + i, "created " + i);
        }

        PaginatedResult<RedshiftEvent> first = service.describeEvents(null, null, null, null, null, 20, null);
        PaginatedResult<RedshiftEvent> second = service.describeEvents(null, null, null, null, null, 20,
                first.nextToken());

        assertEquals(20, first.items().size());
        assertEquals(5, second.items().size());
        assertNull(second.nextToken());
        assertEquals("cluster-0", first.items().getFirst().getSourceIdentifier());
        assertEquals("cluster-20", second.items().getFirst().getSourceIdentifier());
    }

    @Test
    void describeEventsReportsAtMostFourteenDays() {
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        clock.advance(Duration.ofDays(13));
        assertEquals(1, service.describeEvents(null, null, null, null, 60 * 24 * 30, null, null).items().size());

        clock.advance(Duration.ofDays(2));

        assertTrue(service.describeEvents(null, null, null, null, 60 * 24 * 30, null, null).items().isEmpty());
    }

    @Test
    void describeEventCategoriesGroupsEventsBySourceType() {
        Map<String, List<RedshiftEventCatalog.EventInfo>> all = service.describeEventCategories(null);

        assertEquals(List.of("cluster", "cluster-parameter-group", "cluster-security-group", "cluster-snapshot",
                "scheduled-action"), List.copyOf(all.keySet()));
        assertTrue(all.get("cluster").stream().anyMatch(info -> "REDSHIFT-EVENT-2000".equals(info.id())));
        assertEquals(List.of("cluster-snapshot"), List.copyOf(service.describeEventCategories("cluster-snapshot").keySet()));
        assertTrue(service.describeEventCategories("scheduled-action").get("scheduled-action").isEmpty());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> service.describeEventCategories("database")).getErrorCode());
    }

    @Test
    void createSubscriptionDefaultsToActiveEnabledWithoutSeverityFilter() {
        EventSubscription subscription = create("all-events", null, List.of(), List.of(), null);

        assertEquals("active", subscription.getStatus());
        assertTrue(subscription.isEnabled());
        assertNull(subscription.getSeverity());
        assertEquals("000000000000", subscription.getCustomerAwsId());
        assertEquals(clock.instant(), subscription.getSubscriptionCreationTime());
    }

    @Test
    void createSubscriptionValidatesItsArguments() {
        assertEquals("InvalidParameterValue", code(() -> create("1-starts-with-digit", null, List.of(), List.of(), null)));
        assertEquals("InvalidParameterValue", code(() -> create("ends-with-hyphen-", null, List.of(), List.of(), null)));
        assertEquals("InvalidParameterValue", code(() -> create("double--hyphen", null, List.of(), List.of(), null)));
        assertEquals("InvalidParameterCombination", code(() -> create("ids-only", null, List.of("warehouse"), List.of(), null)));
        assertEquals("InvalidParameterValue", code(() -> create("bad-type", "database", List.of(), List.of(), null)));
        assertEquals("SubscriptionCategoryNotFound", code(() -> create("bad-category", null, List.of(), List.of("billing"), null)));
        assertEquals("SubscriptionSeverityNotFound", code(() -> create("bad-severity", null, List.of(), List.of(), "WARN")));
        assertEquals("SNSInvalidTopic", code(() -> service.createSubscription("not-a-topic", "arn:aws:sqs:us-east-1:0:q",
                null, List.of(), List.of(), null, null, Map.of(), ALL_SOURCES_EXIST)));
        verifyNoInteractions(sns);
    }

    @Test
    void createSubscriptionRejectsMissingTopicsAndSources() {
        doThrow(new AwsException("NotFound", "Topic does not exist.", 404)).when(sns).getTopicAttributes(anyString(), anyString());
        assertEquals("SNSTopicArnNotFound", code(() -> create("no-topic", null, List.of(), List.of(), null)));

        setUp();
        assertEquals("SourceNotFound", code(() -> service.createSubscription("no-source", TOPIC, "cluster",
                List.of("missing"), List.of(), null, null, Map.of(), (type, id) -> false)));
    }

    @Test
    void createSubscriptionRejectsDuplicatesAndTheTwentyFirst() {
        create("first", null, List.of(), List.of(), null);
        assertEquals("SubscriptionAlreadyExist", code(() -> create("first", null, List.of(), List.of(), null)));

        for (int i = 2; i <= 20; i++) {
            create("subscription-" + i, null, List.of(), List.of(), null);
        }
        assertEquals("EventSubscriptionQuotaExceeded", code(() -> create("one-too-many", null, List.of(), List.of(), null)));
    }

    @Test
    void modifySubscriptionChangesOnlyTheNamedMembers() {
        create("watcher", "cluster", List.of("warehouse"), List.of("management"), "ERROR");

        EventSubscription modified = service.modifySubscription("watcher", null, null, null, null, null, false,
                ALL_SOURCES_EXIST);

        assertFalse(modified.isEnabled());
        assertEquals("cluster", modified.getSourceType());
        assertEquals(List.of("warehouse"), modified.getSourceIdsList());
        assertEquals(List.of("management"), modified.getEventCategoriesList());
        assertEquals("ERROR", modified.getSeverity());
        assertEquals("SubscriptionNotFound", code(() -> service.modifySubscription("missing", null, null, null, null,
                null, null, ALL_SOURCES_EXIST)));
    }

    @Test
    void deleteSubscriptionRemovesIt() {
        create("watcher", null, List.of(), List.of(), null);

        service.deleteSubscription("watcher");

        assertEquals("SubscriptionNotFound", code(() -> service.deleteSubscription("watcher")));
        assertTrue(service.describeSubscriptions(null, null, null, null, null).items().isEmpty());
    }

    @Test
    void describeSubscriptionsFiltersByNameAndTagsAndPages() {
        service.createSubscription("tagged", TOPIC, null, List.of(), List.of(), null, null,
                Map.of("team", "data"), ALL_SOURCES_EXIST);
        create("untagged", null, List.of(), List.of(), null);

        assertEquals(List.of("tagged"), names(service.describeSubscriptions("tagged", null, null, null, null)));
        assertEquals(List.of("tagged"), names(service.describeSubscriptions(null, List.of("team"), null, null, null)));
        assertEquals(List.of("tagged"), names(service.describeSubscriptions(null, null, List.of("data"), null, null)));
        assertEquals(List.of("tagged", "untagged"), names(service.describeSubscriptions(null, null, null, null, null)));
        assertEquals("SubscriptionNotFound", code(() -> service.describeSubscriptions("missing", null, null, null, null)));

        for (int i = 0; i < 18; i++) {
            create(String.format("page-%02d", i), null, List.of(), List.of(), null);
        }
        PaginatedResult<EventSubscription> first = service.describeSubscriptions(null, null, null, 20, null);
        assertEquals(20, first.items().size());
        assertNull(first.nextToken());
    }

    @Test
    void matchingEventsArePublishedToTheTopicWithTheirDetails() throws Exception {
        create("everything", null, List.of(), List.of(), null);

        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "Amazon Redshift cluster 'warehouse' created.");

        ArgumentCaptor<String> message = forClass(String.class);
        verify(sns).publish(eq(TOPIC), eq(null), message.capture(), eq("Amazon Redshift Notification"), eq("us-east-1"));
        JsonNode body = objectMapper.readTree(message.getValue());
        assertEquals("cluster", body.get("Event Source").asText());
        assertEquals("warehouse", body.get("Source ID").asText());
        assertEquals("REDSHIFT-EVENT-2000", body.get("Event ID").asText());
        assertEquals("INFO", body.get("Severity").asText());
        assertEquals("management", body.get("Event Categories").get(0).asText());
        assertEquals("2026-10-26 12:00:00.000", body.get("Event Time").asText());
        assertEquals("Amazon Redshift cluster 'warehouse' created.", body.get("Event Message").asText());
    }

    @Test
    void subscriptionFiltersDecideWhichEventsArePublished() {
        create("clusters-only", "cluster", List.of(), List.of(), null);
        create("one-cluster", "cluster", List.of("analytics"), List.of(), null);
        create("security-only", null, List.of(), List.of("security"), null);
        create("errors-only", null, List.of(), List.of(), "ERROR");
        service.modifySubscription("security-only", null, null, null, null, null, null, ALL_SOURCES_EXIST);

        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");

        // clusters-only matches; one-cluster names another cluster; security-only wants another
        // category; errors-only wants another severity.
        verify(sns, times(1)).publish(anyString(), any(), anyString(), anyString(), anyString());

        service.record(RedshiftEventCatalog.SNAPSHOT_FAILED, "nightly", "failed");

        // The ERROR snapshot event reaches errors-only and nothing else.
        verify(sns, times(2)).publish(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void anEventIsPublishedToTheTopicItWasMatchedAgainstWhenTheSubscriptionChangesMidDelivery() throws Exception {
        create("watcher", "cluster", List.of(), List.of(), null);
        String replacementTopic = "arn:aws:sns:us-east-1:000000000000:replacement";
        CountDownLatch delivering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(sns.topicExists(anyString(), anyString())).thenAnswer(invocation -> {
            delivering.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "Delivery was not released");
            return true;
        });

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> recording = executor.submit(() ->
                    service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created"));
            try {
                assertTrue(delivering.await(5, TimeUnit.SECONDS), "Delivery did not start");
                service.modifySubscription("watcher", replacementTopic, "cluster-snapshot", null, null, null, null,
                        ALL_SOURCES_EXIST);
            } finally {
                release.countDown();
            }
            recording.get(5, TimeUnit.SECONDS);
        }

        verify(sns).publish(eq(TOPIC), any(), anyString(), anyString(), anyString());
        verify(sns, never()).publish(eq(replacementTopic), any(), anyString(), anyString(), anyString());
    }

    @Test
    void theTopicPolicyIsCheckedForRedshiftBeforeEachPublish() {
        create("watcher", null, List.of(), List.of(), null);

        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");

        verify(authorizer).authorize("redshift", "sns:Publish", TOPIC,
                "arn:aws:redshift:us-east-1:000000000000:eventsubscription:watcher", "000000000000");
        verify(sns).publish(eq(TOPIC), any(), anyString(), anyString(), anyString());
    }

    @Test
    void aMissingTopicIsReportedAsMissingRatherThanDenied() {
        create("watcher", null, List.of(), List.of(), null);
        when(sns.topicExists(anyString(), anyString())).thenReturn(false);

        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");

        assertEquals("topic-not-exist",
                service.describeSubscriptions("watcher", null, null, null, null).items().getFirst().getStatus());
        verify(sns, never()).publish(anyString(), any(), anyString(), anyString(), anyString());
        verify(authorizer, never()).authorize(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aTopicPolicyThatDeniesRedshiftBlocksDeliveryAndMarksTheSubscription() {
        create("watcher", null, List.of(), List.of(), null);
        when(authorizer.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Decision.DENY);

        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");

        verify(sns, never()).publish(anyString(), any(), anyString(), anyString(), anyString());
        assertEquals("no-permission",
                service.describeSubscriptions("watcher", null, null, null, null).items().getFirst().getStatus());
        assertEquals(1, service.describeEvents(null, null, null, null, null, null, null).items().size());

        service.record(RedshiftEventCatalog.CLUSTER_DELETED, "warehouse", "deleted");

        verify(authorizer, times(1)).authorize(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void disabledSubscriptionsPublishNothing() {
        service.createSubscription("quiet", TOPIC, null, List.of(), List.of(), null, false, Map.of(), ALL_SOURCES_EXIST);

        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");

        verify(sns, never()).publish(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void explicitInfoSeverityDoesNotReceiveErrors() {
        create("info-only", null, List.of(), List.of(), "INFO");
        service.record(RedshiftEventCatalog.SNAPSHOT_FAILED, "nightly", "failed");
        verify(sns, never()).publish(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void omittedSeverityReceivesBothSeverities() {
        create("all-severities", null, List.of(), List.of(), null);
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        service.record(RedshiftEventCatalog.SNAPSHOT_FAILED, "nightly", "failed");
        verify(sns, times(2)).publish(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void fifoTopicsAreRejectedBeforeCreatingSubscription() {
        when(sns.getTopicAttributes(TOPIC, "us-east-1")).thenReturn(Map.of("FifoTopic", "true"));
        assertEquals("SNSInvalidTopic", code(() -> create("fifo", null, List.of(), List.of(), null)));
        assertTrue(service.describeSubscriptions(null, null, null, null, null).items().isEmpty());
    }

    @Test
    void permissionFailuresUseRedshiftErrorsAndStatus() {
        doThrow(new AwsException("AuthorizationError", "denied", 403))
                .when(sns).getTopicAttributes(TOPIC, "us-east-1");
        assertEquals("SNSNoAuthorization", code(() -> create("denied", null, List.of(), List.of(), null)));
    }

    @Test
    void lostPublishPermissionMarksSubscriptionWithoutFailingOperation() {
        create("watcher", null, List.of(), List.of(), null);
        doThrow(new AwsException("AuthorizationError", "denied", 403))
                .when(sns).publish(anyString(), any(), anyString(), anyString(), anyString());
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        assertEquals("no-permission", service.describeSubscriptions("watcher", null, null, null, null)
                .items().getFirst().getStatus());
        assertEquals(1, service.describeEvents(null, null, null, null, null, null, null).items().size());
    }

    @Test
    void repeatingSourceTypePreservesSourceIds() {
        create("watcher", "cluster", List.of("warehouse"), List.of(), null);
        EventSubscription modified = service.modifySubscription("watcher", null, "cluster", null, null, null,
                false, ALL_SOURCES_EXIST);
        assertEquals(List.of("warehouse"), modified.getSourceIdsList());
    }

    @Test
    void disablingSubscriptionDoesNotRequireItsDeletedSource() {
        create("watcher", "cluster", List.of("warehouse"), List.of(), null);
        EventSubscription modified = service.modifySubscription("watcher", null, null, null, null, null,
                false, (type, id) -> false);
        assertFalse(modified.isEnabled());
    }

    @Test
    void tagKeyAndValueMustMatchTheSameTag() {
        service.createSubscription("wrong-value", TOPIC, null, List.of(), List.of(), null, null,
                Map.of("team", "other"), ALL_SOURCES_EXIST);
        service.createSubscription("wrong-key", TOPIC, null, List.of(), List.of(), null, null,
                Map.of("owner", "data"), ALL_SOURCES_EXIST);
        service.createSubscription("matching", TOPIC, null, List.of(), List.of(), null, null,
                Map.of("team", "data"), ALL_SOURCES_EXIST);
        assertEquals(List.of("matching"), names(service.describeSubscriptions(null,
                List.of("team"), List.of("data"), null, null)));
    }

    @Test
    void eventHistoryAndSubscriptionNamesAreRegional() {
        create("watcher", null, List.of(), List.of(), null);
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        RedshiftEventService west = new RedshiftEventService(factory, sns, authorizer,
                new RegionResolver("us-west-2", "000000000000"), objectMapper, clock);
        assertTrue(west.describeEvents(null, null, null, null, null, null, null).items().isEmpty());
        assertTrue(west.describeSubscriptions(null, null, null, null, null).items().isEmpty());
        west.createSubscription("watcher", TOPIC, null, List.of(), List.of(), null, false, Map.of(),
                ALL_SOURCES_EXIST);
        assertTrue(service.describeSubscriptions("watcher", null, null, null, null).items().getFirst().isEnabled());
    }

    @Test
    void aDeletedTopicMarksTheSubscriptionAndNeverFailsTheOperation() {
        create("watcher", null, List.of(), List.of(), null);
        doThrow(new AwsException("NotFound", "Topic does not exist.", 404))
                .when(sns).publish(anyString(), any(), anyString(), anyString(), anyString());

        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");

        assertEquals("topic-not-exist",
                service.describeSubscriptions("watcher", null, null, null, null).items().getFirst().getStatus());
        assertEquals(1, service.describeEvents(null, null, null, null, null, null, null).items().size());

        service.record(RedshiftEventCatalog.CLUSTER_DELETED, "warehouse", "deleted");

        verify(sns, times(1)).publish(anyString(), any(), anyString(), anyString(), anyString());
    }

    private EventSubscription create(String name, String sourceType, List<String> sourceIds, List<String> categories,
                                     String severity) {
        return service.createSubscription(name, TOPIC, sourceType, sourceIds, categories, severity, null, Map.of(),
                ALL_SOURCES_EXIST);
    }

    private static String code(Runnable action) {
        return assertThrows(AwsException.class, action::run).getErrorCode();
    }

    private static List<String> ids(PaginatedResult<RedshiftEvent> page) {
        return page.items().stream().map(RedshiftEvent::getEventId).toList();
    }

    private static List<String> names(PaginatedResult<EventSubscription> page) {
        return page.items().stream().map(EventSubscription::getCustSubscriptionId).toList();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
