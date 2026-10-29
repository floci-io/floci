package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.ScheduledAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessScheduledActionServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/scheduler";
    private static final String NAMESPACE_ARN = "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":namespace/ns-id";
    private static final Instant BASE_TIME = Instant.parse("2026-10-28T12:15:00Z");

    private final AtomicBoolean failCheckpointWrites = new AtomicBoolean();
    private AccountAwareStorageBackend<ScheduledAction> store;
    private StorageFactory storageFactory;
    private RedshiftServerlessService serverless;
    private RedshiftServerlessScheduledActionAuthorizer authorizer;
    private RegionResolver regionResolver;
    private String namespaceId;
    private Clock clock;
    private RedshiftServerlessScheduledActionService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        namespaceId = "ns-id";
        clock = Clock.fixed(BASE_TIME, ZoneOffset.UTC);
        store = new AccountAwareStorageBackend<>(new InMemoryStorage<String, ScheduledAction>() {
            @Override
            public synchronized void putAllAtomically(Map<String, ScheduledAction> entries) {
                if (failCheckpointWrites.get()) {
                    throw new UncheckedIOException(new IOException("disk full"));
                }
                super.putAllAtomically(entries);
            }
        }, null, ACCOUNT_ID);
        storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-scheduledactions.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);
        serverless = mock(RedshiftServerlessService.class);
        when(serverless.namespaceExists(anyString(), eq(REGION))).thenReturn(true);
        when(serverless.findNamespace(anyString(), eq(REGION))).thenAnswer(invocation -> Optional.of(namespace()));
        authorizer = mock(RedshiftServerlessScheduledActionAuthorizer.class);
        regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        service = newService();
    }

    private RedshiftServerlessScheduledActionService newService() {
        return new RedshiftServerlessScheduledActionService(storageFactory, serverless, authorizer, regionResolver, clock);
    }

    private Namespace namespace() {
        Namespace namespace = new Namespace();
        namespace.setNamespaceId(namespaceId);
        namespace.setNamespaceArn(NAMESPACE_ARN);
        return namespace;
    }

    private ScheduledAction request(String name) {
        ScheduledAction request = new ScheduledAction();
        request.setScheduledActionName(name);
        request.setNamespaceName("source-ns");
        request.setRoleArn(ROLE_ARN);
        request.setScheduleCron("0 * * * ? *");
        request.setTargetNamespaceName("source-ns");
        request.setSnapshotNamePrefix("Nightly");
        request.setRetentionPeriod(7);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("env", "dev");
        request.setSnapshotTags(tags);
        return request;
    }

    private static Instant later() {
        return BASE_TIME.plus(Duration.ofHours(3));
    }

    private static String expectedName(Instant tick) {
        return RedshiftServerlessScheduledActionService.snapshotName("nightly", tick.truncatedTo(ChronoUnit.HOURS));
    }

    @Test
    void cronActionTakesTheLatestDueSnapshotOnceWithItsArguments() {
        service.createScheduledAction(request("hourly"), REGION);
        Instant tick = later();

        service.runScheduledActions(tick);
        service.runScheduledActions(tick.plusSeconds(60));

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("env", "dev");
        verify(serverless, times(1)).createSnapshot(expectedName(tick), "source-ns", 7, tags, REGION);
        assertEquals(tick.truncatedTo(ChronoUnit.HOURS),
                store.getForAccount(ACCOUNT_ID, "us-east-1::hourly").orElseThrow().getLastCompletedInvocation());
    }

    @Test
    void missedInvocationsAreCoalescedIntoTheLatestOne() {
        service.createScheduledAction(request("hourly"), REGION);

        service.runScheduledActions(BASE_TIME.plus(Duration.ofHours(30)));

        verify(serverless, times(1)).createSnapshot(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void oneOffActionRunsOnceAtItsTime() {
        ScheduledAction oneOff = request("once");
        oneOff.setScheduleCron(null);
        Instant at = BASE_TIME.plusSeconds(600);
        oneOff.setScheduleAt(at);
        service.createScheduledAction(oneOff, REGION);

        service.runScheduledActions(at.minusSeconds(1));
        verify(serverless, never()).createSnapshot(anyString(), anyString(), any(), any(), anyString());
        service.runScheduledActions(at.plusSeconds(5));
        service.runScheduledActions(at.plusSeconds(3600));

        verify(serverless, times(1)).createSnapshot(eq(RedshiftServerlessScheduledActionService.snapshotName("nightly", at)),
                eq("source-ns"), eq(7), any(), eq(REGION));
    }

    @Test
    void activeWindowAndDisabledActionsDoNotRun() {
        ScheduledAction early = request("windowed");
        early.setStartTime(BASE_TIME.plus(Duration.ofHours(10)));
        service.createScheduledAction(early, REGION);
        ScheduledAction disabled = request("off");
        disabled.setEnabled(false);
        service.createScheduledAction(disabled, REGION);

        service.runScheduledActions(later());

        verify(serverless, never()).createSnapshot(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void enablingDoesNotReplayWhatWasMissedWhileDisabled() {
        ScheduledAction disabled = request("off");
        disabled.setEnabled(false);
        service.createScheduledAction(disabled, REGION);
        ScheduledAction enable = new ScheduledAction();
        enable.setScheduledActionName("off");
        enable.setEnabled(true);
        service.updateScheduledAction(enable, REGION);

        service.runScheduledActions(BASE_TIME.plusSeconds(60));

        verify(serverless, never()).createSnapshot(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void storedActionWithoutACheckpointStartsCountingWithoutReplay() {
        ScheduledAction legacy = new ScheduledAction(request("legacy"));
        legacy.setScheduledActionUuid("u-1");
        legacy.setOwnerId(namespaceId);
        legacy.setEnabled(true);
        store.put("us-east-1::legacy", legacy);
        Instant tick = later();

        service.runScheduledActions(tick);
        verify(serverless, never()).createSnapshot(anyString(), anyString(), any(), any(), anyString());
        service.runScheduledActions(tick.plus(Duration.ofHours(2)));

        verify(serverless, times(1)).createSnapshot(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void oneFailingActionDoesNotStopOthersAndIsNotRetried() {
        service.createScheduledAction(request("a-failing"), REGION);
        ScheduledAction other = request("b-healthy");
        other.setSnapshotNamePrefix("healthy");
        service.createScheduledAction(other, REGION);
        Instant tick = later();
        when(serverless.createSnapshot(anyString(), anyString(), any(), any(), anyString())).thenAnswer(invocation -> {
            if (invocation.getArgument(0, String.class).startsWith("nightly")) {
                throw ServerlessSupport.conflict("exists");
            }
            return null;
        });

        service.runScheduledActions(tick);
        service.runScheduledActions(tick.plusSeconds(30));

        verify(serverless, times(1)).createSnapshot(startsWith("nightly"), anyString(), any(), any(), anyString());
        verify(serverless, times(1)).createSnapshot(startsWith("healthy"), anyString(), any(), any(), anyString());
        assertNotNull(store.getForAccount(ACCOUNT_ID, "us-east-1::a-failing").orElseThrow().getLastFailure());
    }

    @Test
    void failedCheckpointLeavesTheInvocationEligibleForRetry() {
        service.createScheduledAction(request("hourly"), REGION);
        failCheckpointWrites.set(true);

        service.runScheduledActions(later());

        assertNull(store.getForAccount(ACCOUNT_ID, "us-east-1::hourly").orElseThrow().getLastClaimedInvocation());
        failCheckpointWrites.set(false);
        service.runScheduledActions(later().plusSeconds(60));

        verify(serverless, times(2)).createSnapshot(eq(expectedName(later())), anyString(), any(), any(), eq(REGION));
        assertEquals(later().truncatedTo(ChronoUnit.HOURS),
                store.getForAccount(ACCOUNT_ID, "us-east-1::hourly").orElseThrow().getLastCompletedInvocation());
    }

    @Test
    void claimedInvocationIsNotRepeatedAfterRestart() {
        service.createScheduledAction(request("hourly"), REGION);
        Instant tick = later();
        service.runScheduledActions(tick);

        RedshiftServerlessScheduledActionService restarted = newService();
        restarted.runScheduledActions(tick.plusSeconds(60));

        verify(serverless, times(1)).createSnapshot(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void recreatedNamespaceInvalidatesTheOldAction() {
        service.createScheduledAction(request("hourly"), REGION);
        namespaceId = "ns-id-2";

        service.runScheduledActions(later());

        verify(serverless, never()).createSnapshot(anyString(), anyString(), any(), any(), anyString());
        assertTrue(store.getForAccount(ACCOUNT_ID, "us-east-1::hourly").isEmpty());
    }

    @Test
    void deniedRoleTakesNoSnapshotAndIsNotRetried() {
        service.createScheduledAction(request("hourly"), REGION);
        when(authorizer.refusal(ROLE_ARN, ACCOUNT_ID, NAMESPACE_ARN)).thenReturn(Optional.of("denied"));
        Instant tick = later();

        service.runScheduledActions(tick);
        service.runScheduledActions(tick.plusSeconds(60));

        verify(serverless, never()).createSnapshot(anyString(), anyString(), any(), any(), anyString());
        verify(authorizer, times(1)).refusal(ROLE_ARN, ACCOUNT_ID, NAMESPACE_ARN);
        assertEquals("denied", store.getForAccount(ACCOUNT_ID, "us-east-1::hourly").orElseThrow().getLastFailure());
    }

    @Test
    void anotherAccountsActionIsAuthorizedAsItsOwnAccount() {
        ScheduledAction foreign = new ScheduledAction(request("foreign"));
        foreign.setScheduledActionUuid("u-2");
        foreign.setOwnerId(namespaceId);
        foreign.setEnabled(true);
        foreign.setRoleArn("arn:aws:iam::210987654321:role/scheduler");
        foreign.setActivatedAt(BASE_TIME);
        store.putAllAtomicallyForAccount("210987654321", Map.of("us-east-1::foreign", foreign));
        when(authorizer.refusal(anyString(), eq("210987654321"), anyString())).thenReturn(Optional.of("denied"));

        service.runScheduledActions(later());

        verify(authorizer).refusal(eq("arn:aws:iam::210987654321:role/scheduler"), eq("210987654321"), anyString());
        verify(serverless, never()).createSnapshot(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void readsAndMutationsOfOtherActionsAreNotBlockedByARunningDump() throws Exception {
        service.createScheduledAction(request("hourly"), REGION);
        ScheduledAction other = request("other");
        other.setScheduleCron("0 0 1 1 ? *");
        service.createScheduledAction(other, REGION);
        CountDownLatch dumping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(serverless.createSnapshot(anyString(), anyString(), any(), any(), anyString())).thenAnswer(invocation -> {
            dumping.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> sweep = executor.submit(() -> service.runScheduledActions(later()));
            assertTrue(dumping.await(5, TimeUnit.SECONDS));

            Future<ScheduledAction> read = executor.submit(() -> service.getScheduledAction("hourly", REGION));
            Future<ScheduledAction> mutation = executor.submit(() -> {
                ScheduledAction change = new ScheduledAction();
                change.setScheduledActionName("other");
                change.setDescription("changed");
                return service.updateScheduledAction(change, REGION);
            });
            assertEquals("hourly", read.get(5, TimeUnit.SECONDS).getScheduledActionName());
            assertEquals("changed", mutation.get(5, TimeUnit.SECONDS).getDescription());
            release.countDown();
            sweep.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void snapshotNameStaysWithinTheNamingRules() {
        String name = RedshiftServerlessScheduledActionService.snapshotName("Nightly", Instant.parse("2030-01-07T03:00:00Z"));
        assertEquals("nightly-2030-01-07-03-00-00", name);
        assertTrue(RedshiftServerlessScheduledActionService.snapshotName("a".repeat(400), BASE_TIME).length() <= 255);
    }

    @Test
    void unusableSnapshotNamePrefixIsRejectedOnCreate() {
        ScheduledAction invalid = request("invalid-prefix");
        invalid.setSnapshotNamePrefix("nightly_backup");
        assertThrows(AwsException.class, () -> service.createScheduledAction(invalid, REGION));
    }

    @Test
    void unusableSnapshotNamePrefixIsRejectedOnUpdate() {
        service.createScheduledAction(request("valid-prefix"), REGION);
        ScheduledAction update = new ScheduledAction();
        update.setScheduledActionName("valid-prefix");
        update.setSnapshotNamePrefix("nightly_backup");
        assertThrows(AwsException.class, () -> service.updateScheduledAction(update, REGION));
    }
}
