package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import io.github.hectorvent.floci.services.redshiftserverless.model.ScheduledAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedshiftServerlessScheduledActionRecoveryTest {

    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final Instant CREATED = Instant.parse("2026-10-28T12:15:00Z");
    private static final Instant DUE = Instant.parse("2026-10-28T13:00:00Z");
    private static final String SNAPSHOT_NAME = "nightly-2026-10-28-13-00-00";

    @TempDir
    Path directory;

    private final List<HybridStorage<String, ScheduledAction>> backends = new ArrayList<>();
    private StorageFactory factory;
    private RegionResolver regions;
    private RedshiftServerlessService serverless;
    private AccountAwareStorageBackend<RedshiftServerlessSnapshot> snapshots;
    private RedshiftServerlessScheduledActionService service;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        factory = mock(StorageFactory.class);
        when(factory.create(anyString(), anyString(), any(TypeReference.class)))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        AccountAwareStorageBackend<Namespace> namespaces = AccountAwareStorageBackend.inMemory(ACCOUNT);
        Namespace namespace = new Namespace();
        namespace.setNamespaceName("source-ns");
        namespace.setNamespaceId("ns-id");
        namespace.setNamespaceArn("arn:aws:redshift-serverless:us-east-1:" + ACCOUNT + ":namespace/ns-id");
        namespaces.put(REGION + "::source-ns", namespace);
        when(factory.create(eq("redshiftserverless"), eq("redshiftserverless-namespaces.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) namespaces);
        snapshots = AccountAwareStorageBackend.inMemory(ACCOUNT);
        when(factory.create(eq("redshiftserverless"), eq("redshiftserverless-snapshots.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) snapshots);
        regions = mock(RegionResolver.class);
        when(regions.getAccountId()).thenReturn(ACCOUNT);
        serverless = spy(new RedshiftServerlessService(factory, regions, mock(RedshiftServerlessEndpoints.class),
                mock(RedshiftServerlessRuntime.class), mock(EmulatorConfig.class)));
        service = reload();
        ScheduledAction action = new ScheduledAction();
        action.setScheduledActionName("hourly");
        action.setNamespaceName("source-ns");
        action.setRoleArn("arn:aws:iam::" + ACCOUNT + ":role/scheduler");
        action.setScheduleCron("0 * * * ? *");
        action.setTargetNamespaceName("source-ns");
        action.setSnapshotNamePrefix("nightly");
        service.createScheduledAction(action, REGION);
        backends.getFirst().flush();
    }

    @AfterEach
    void stopBackends() {
        backends.forEach(HybridStorage::shutdown);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RedshiftServerlessScheduledActionService reload() {
        HybridStorage<String, ScheduledAction> backend = new HybridStorage<>(directory.resolve("actions.json"),
                new TypeReference<Map<String, ScheduledAction>>() {}, 600_000);
        backends.add(backend);
        backend.load();
        AccountAwareStorageBackend<ScheduledAction> actions = new AccountAwareStorageBackend<>(backend, null, ACCOUNT);
        when(factory.create(eq("redshiftserverless"), eq("redshiftserverless-scheduledactions.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) actions);
        return new RedshiftServerlessScheduledActionService(factory, serverless,
                mock(RedshiftServerlessScheduledActionAuthorizer.class), regions, Clock.fixed(CREATED, ZoneOffset.UTC));
    }

    @Test
    void crashBeforeSnapshotRetriesAfterHybridReloadEvenIfAFlushRanDuringTheAttempt() {
        doAnswer(invocation -> {
            backends.getFirst().flush();
            throw new AssertionError("simulated process crash before snapshot creation");
        }).when(serverless).createSnapshot(anyString(), anyString(), any(), any(), anyString());

        assertThrows(AssertionError.class, () -> service.runScheduledActions(DUE));
        RedshiftServerlessScheduledActionService restarted = reload();
        assertNull(restarted.getScheduledAction("hourly", REGION).getLastClaimedInvocation());
        doCallRealMethod().when(serverless).createSnapshot(anyString(), anyString(), any(), any(), anyString());
        restarted.runScheduledActions(DUE.plusSeconds(60));

        assertEquals(1, snapshots.scan(key -> true).size());
        assertEquals(SNAPSHOT_NAME, snapshots.scan(key -> true).getFirst().getSnapshotName());
        assertEquals(DUE, restarted.getScheduledAction("hourly", REGION).getLastCompletedInvocation());
    }

    @Test
    void crashAfterSnapshotRetriesTheSameNameWithoutCreatingADuplicate() {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new AssertionError("simulated process crash after snapshot creation");
        }).when(serverless).createSnapshot(anyString(), anyString(), any(), any(), anyString());

        assertThrows(AssertionError.class, () -> service.runScheduledActions(DUE));
        RedshiftServerlessScheduledActionService restarted = reload();
        assertNull(restarted.getScheduledAction("hourly", REGION).getLastClaimedInvocation());
        doCallRealMethod().when(serverless).createSnapshot(anyString(), anyString(), any(), any(), anyString());
        restarted.runScheduledActions(DUE.plusSeconds(60));
        restarted.runScheduledActions(DUE.plusSeconds(120));

        assertEquals(1, snapshots.scan(key -> true).size());
        verify(serverless, times(2)).createSnapshot(eq(SNAPSHOT_NAME), eq("source-ns"), any(), any(), eq(REGION));
        assertEquals(DUE, restarted.getScheduledAction("hourly", REGION).getLastClaimedInvocation());
        assertTrue(restarted.getScheduledAction("hourly", REGION).getLastFailure().contains("already exists"));
    }

    @Test
    void completedCheckpointSurvivesHybridFlushAndReload() {
        service.runScheduledActions(DUE);
        backends.getFirst().flush();

        RedshiftServerlessScheduledActionService restarted = reload();
        restarted.runScheduledActions(DUE.plusSeconds(60));

        assertEquals(DUE, restarted.getScheduledAction("hourly", REGION).getLastCompletedInvocation());
        verify(serverless).createSnapshot(eq(SNAPSHOT_NAME), eq("source-ns"), any(), any(), eq(REGION));
        assertEquals(1, snapshots.scan(key -> true).size());
    }
}
