package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.redshift.model.EventSubscription;
import io.github.hectorvent.floci.services.redshift.model.RedshiftEvent;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RedshiftEventStorageTest {

    private static final String ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "222222222222";
    private static final String EAST = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String SUBSCRIPTION = "persistent-watcher";
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void historyAndSubscriptionsSurviveRestartWithoutReplayingNotifications(String mode) {
        SnsService sns = sns();
        String topic = topic(ACCOUNT, EAST);
        String originalKey;
        try (Fixture first = fixture(mode, ACCOUNT, sns)) {
            createSubscription(first.east(), topic);
            first.east().record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created before restart");
            originalKey = history(first.east()).getFirst().getKey();
            verify(sns).publish(eq(topic), eq(null), anyString(), anyString(), eq(EAST));
        }

        clearInvocations(sns);
        try (Fixture restarted = fixture(mode, ACCOUNT, sns)) {
            restarted.factory().loadAll();
            restarted.factory().loadAll();
            List<RedshiftEvent> history = history(restarted.east());
            assertEquals(1, history.size());
            assertEquals(originalKey, history.getFirst().getKey());
            assertEquals(NOW, history.getFirst().getDate());
            assertEquals("REDSHIFT-EVENT-2000", history.getFirst().getEventId());
            assertEquals("created before restart", history.getFirst().getMessage());

            EventSubscription restored = restarted.east().describeSubscriptions(
                    SUBSCRIPTION, null, null, null, null).items().getFirst();
            assertEquals(ACCOUNT, restored.getCustomerAwsId());
            assertEquals(topic, restored.getSnsTopicArn());
            assertEquals(NOW, restored.getSubscriptionCreationTime());
            assertEquals(Map.of("team", "analytics"), restored.getTags());
            assertTrue(restored.isEnabled());
            assertEquals("active", restored.getStatus());
            verifyNoInteractions(sns);

            restarted.east().record(RedshiftEventCatalog.CLUSTER_REBOOTED, "warehouse", "rebooted after restart");
            assertEquals(2, history(restarted.east()).size());
            verify(sns).publish(eq(topic), eq(null), anyString(), anyString(), eq(EAST));
        }

        clearInvocations(sns);
        try (Fixture third = fixture(mode, ACCOUNT, sns)) {
            assertEquals(2, history(third.east()).size());
            assertEquals(2, history(third.east()).stream().map(RedshiftEvent::getKey).distinct().count());
            assertEquals(List.of("REDSHIFT-EVENT-2000", "REDSHIFT-EVENT-3000"), history(third.east()).stream()
                    .map(RedshiftEvent::getEventId).sorted().toList());
            verifyNoInteractions(sns);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void persistedHistoryAndSubscriptionDeliveryStayWithinTheirAccountAndRegion(String mode) {
        SnsService sns = sns();
        String eastTopic = topic(ACCOUNT, EAST);
        String westTopic = topic(ACCOUNT, WEST);
        String otherTopic = topic(OTHER_ACCOUNT, EAST);
        try (Fixture owner = fixture(mode, ACCOUNT, sns)) {
            createSubscription(owner.east(), eastTopic);
            createSubscription(owner.west(), westTopic);
            owner.east().record(RedshiftEventCatalog.CLUSTER_CREATED, "east-cluster", "east owner");
            owner.west().record(RedshiftEventCatalog.CLUSTER_CREATED, "west-cluster", "west owner");
            assertEquals(List.of("east-cluster"), sourceIds(owner.east()));
            assertEquals(List.of("west-cluster"), sourceIds(owner.west()));
        }

        try (Fixture other = fixture(mode, OTHER_ACCOUNT, sns)) {
            assertTrue(history(other.east()).isEmpty());
            assertTrue(history(other.west()).isEmpty());
            assertTrue(other.east().describeSubscriptions(null, null, null, null, null).items().isEmpty());
            createSubscription(other.east(), otherTopic);
            other.east().record(RedshiftEventCatalog.CLUSTER_CREATED, "other-cluster", "other owner");
        }

        clearInvocations(sns);
        try (Fixture restored = fixture(mode, ACCOUNT, sns)) {
            assertEquals(List.of("east-cluster"), sourceIds(restored.east()));
            assertEquals(List.of("west-cluster"), sourceIds(restored.west()));
            assertEquals(eastTopic, restored.east().describeSubscriptions(SUBSCRIPTION, null, null, null, null)
                    .items().getFirst().getSnsTopicArn());
            assertEquals(westTopic, restored.west().describeSubscriptions(SUBSCRIPTION, null, null, null, null)
                    .items().getFirst().getSnsTopicArn());
            restored.east().record(RedshiftEventCatalog.CLUSTER_REBOOTED, "east-cluster", "owner reboot");
            verify(sns, times(1)).publish(anyString(), any(), anyString(), anyString(), anyString());
            verify(sns).publish(eq(eastTopic), eq(null), anyString(), anyString(), eq(EAST));
            assertEquals(1, history(restored.west()).size());
        }

        try (Fixture otherRestored = fixture(mode, OTHER_ACCOUNT, sns)) {
            assertEquals(List.of("other-cluster"), sourceIds(otherRestored.east()));
            assertTrue(history(otherRestored.west()).isEmpty());
            EventSubscription subscription = otherRestored.east().describeSubscriptions(
                    SUBSCRIPTION, null, null, null, null).items().getFirst();
            assertEquals(OTHER_ACCOUNT, subscription.getCustomerAwsId());
            assertEquals(otherTopic, subscription.getSnsTopicArn());
            otherRestored.east().modifySubscription(SUBSCRIPTION, null, null, null, null,
                    null, false, (type, id) -> true);
        }
        try (Fixture otherDisabled = fixture(mode, OTHER_ACCOUNT, sns)) {
            assertFalse(otherDisabled.east().describeSubscriptions(SUBSCRIPTION, null, null, null, null)
                    .items().getFirst().isEnabled());
        }
    }

    private Fixture fixture(String mode, String account, SnsService sns) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(account);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("redshift")).thenReturn(mode);
        when(access.storageFlushInterval("redshift")).thenReturn(60_000L);
        StorageFactory factory = new StorageFactory(config, access);
        ObjectMapper mapper = new ObjectMapper();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        RedshiftEventService east = new RedshiftEventService(factory, sns, allowAll(), new RegionResolver(EAST, account), mapper, clock);
        RedshiftEventService west = new RedshiftEventService(factory, sns, allowAll(), new RegionResolver(WEST, account), mapper, clock);
        return new Fixture(factory, east, west);
    }

    private static ServiceCallAuthorizer allowAll() {
        ServiceCallAuthorizer authorizer = mock(ServiceCallAuthorizer.class);
        when(authorizer.authorize(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Decision.ALLOW);
        return authorizer;
    }

    private static SnsService sns() {
        SnsService sns = mock(SnsService.class);
        when(sns.topicExists(anyString(), anyString())).thenReturn(true);
        when(sns.getTopicAttributes(anyString(), anyString())).thenReturn(Map.of());
        return sns;
    }

    private static void createSubscription(RedshiftEventService service, String topic) {
        service.createSubscription(SUBSCRIPTION, topic, null, List.of(), List.of(), null, true,
                Map.of("team", "analytics"), (type, id) -> true);
    }

    private static List<RedshiftEvent> history(RedshiftEventService service) {
        return service.describeEvents(null, null, null, null, null, null, null).items();
    }

    private static List<String> sourceIds(RedshiftEventService service) {
        return history(service).stream().map(RedshiftEvent::getSourceIdentifier).toList();
    }

    private static String topic(String account, String region) {
        return "arn:aws:sns:" + region + ":" + account + ":redshift-events";
    }

    private record Fixture(StorageFactory factory, RedshiftEventService east,
                           RedshiftEventService west) implements AutoCloseable {
        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
}
