package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbApiStreamReader;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.CheckpointLifetime;
import io.github.hectorvent.floci.services.rds.proxy.PasswordValidator;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerHandle;
import io.github.hectorvent.floci.services.redshift.container.RedshiftContainerManager;
import io.github.hectorvent.floci.services.redshift.metrics.RedshiftMetricTarget;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.ClusterParameterGroup;
import io.github.hectorvent.floci.services.redshift.model.ClusterSubnetGroup;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshift.model.Integration;
import io.github.hectorvent.floci.services.redshift.model.Parameter;
import io.github.hectorvent.floci.services.redshift.model.Snapshot;
import io.github.hectorvent.floci.services.redshift.model.SnapshotCopyGrant;
import io.github.hectorvent.floci.services.redshift.model.SnapshotSchedule;
import io.github.hectorvent.floci.services.redshift.proxy.RedshiftProxyManager;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.InlineMocks.inlineMock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RedshiftServiceTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void loggingDestinationRegionSurvivesStorageAndRestart(boolean refresh) throws Exception {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("warehouse");
        cluster.setClusterStatus("available");
        when(clusterBackend.get("warehouse")).thenReturn(Optional.of(cluster));
        ObjectMapper mapper = new ObjectMapper();
        EmulatorConfig config = mock(EmulatorConfig.class);
        RedshiftService original = new RedshiftService(sf, cm, config,
                new RegionResolver("us-west-2", "111111111111"), proxyManager, dockerHostResolver,
                credentialBroker, secretsManagerService, mapper, null, null);
        original.enableLogging("warehouse", null, null, "cloudwatch", List.of("connectionlog"));

        Cluster restored = mapper.readValue(mapper.writeValueAsBytes(cluster), Cluster.class);
        when(clusterBackend.get("warehouse")).thenReturn(Optional.of(restored));
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "warehouse", restored)));
        when(cm.getContainer("111111111111", "warehouse")).thenReturn(Optional.of(
                new RedshiftContainerHandle("container", "warehouse", "localhost", 5432)));
        CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
        RedshiftAuditLogPublisher publisher = new RedshiftAuditLogPublisher(logs);
        RedshiftService restarted = new RedshiftService(sf, cm, config, regionResolver,
                proxyManager, dockerHostResolver, credentialBroker, secretsManagerService, mapper, null, publisher);

        restarted.onStart(null);
        if (refresh) {
            restarted.modifyClusterIamRoles("warehouse", List.of(), List.of());
        }
        publisher.publishRegisteredConnection("111111111111", "warehouse", "dev", "alice",
                "authenticated", "192.0.2.1", 54321);

        verify(logs).putLogEventsForAccount(eq("111111111111"),
                eq("/aws/redshift/cluster/warehouse/connectionlog"), eq("warehouse"), anyList(), eq("us-west-2"));
        verify(logs, never()).createLogGroupForAccount(anyString(), anyString(), any(), anyMap(), eq("us-east-1"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void activityParameterUpdatesStayWithinTheCallingAccount(boolean reset) {
        ClusterParameterGroup group = new ClusterParameterGroup("shared", "redshift-1.0", "audit");
        when(parameterGroupBackend.get("shared")).thenReturn(Optional.of(group));
        Cluster owned = new Cluster();
        owned.setClusterIdentifier("owned");
        owned.setClusterParameterGroupName("shared");
        owned.setUserActivityLoggingEnabled(reset);
        Cluster foreign = new Cluster();
        foreign.setClusterIdentifier("foreign");
        foreign.setClusterParameterGroupName("shared");
        foreign.setUserActivityLoggingEnabled(reset);
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "owned", owned),
                new AccountAwareStorageBackend.AccountEntry<>("222222222222", "foreign", foreign)));
        when(clusterBackend.scan(any())).thenReturn(List.of(owned));

        if (reset) {
            service.resetClusterParameterGroup("shared", true, List.of());
        } else {
            service.modifyClusterParameterGroup("shared", List.of(new Parameter("enable_user_activity_logging", "true")));
        }

        assertEquals(!reset, owned.isUserActivityLoggingEnabled());
        assertEquals(reset, foreign.isUserActivityLoggingEnabled());
        verify(clusterBackend, never()).putForAccount(eq("222222222222"), anyString(), any());
    }

    @Test
    void recoveryProxyFailureDoesNotDestructivelyStopAnAdoptedContainer() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("survivor");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setClusterStatus("available");
        cluster.setProxyPort(7107);
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "survivor", cluster)));
        when(cm.adoptOrStart("111111111111", "survivor", "admin", "Secret123"))
                .thenReturn(new RedshiftContainerHandle("surviving-container", "survivor", "localhost", 5432));
        doThrow(new IllegalStateException("proxy bind failed")).when(proxyManager).startProxy(
                anyString(), anyInt(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyString(),
                any(), anyList(), isNull());

        service.onStart(null);

        assertEquals("unavailable", cluster.getClusterStatus());
        verify(cm, never()).stop("111111111111", "survivor");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rebootTeardownFailureKeepsDumpAndMarksClusterFailed(boolean proxyFailure) throws Exception {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("partial-stop");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setClusterStatus("available");
        cluster.setProxyPort(7107);
        when(clusterBackend.get("partial-stop")).thenReturn(Optional.of(cluster));
        AtomicReference<Path> dump = new AtomicReference<>();
        doAnswer(invocation -> {
            Path path = invocation.getArgument(3, Path.class);
            Files.writeString(path, "preserved database");
            dump.set(path);
            return null;
        }).when(cm).takeSnapshot(eq("111111111111"), eq("partial-stop"), eq("admin"), any(Path.class));
        if (proxyFailure) {
            doThrow(new IllegalStateException("proxy stop failed"))
                    .when(proxyManager).stopProxy("111111111111:partial-stop");
        } else {
            doThrow(new IllegalStateException("log close failed after container removal"))
                    .when(cm).stop("111111111111", "partial-stop");
        }
        try {
            assertThrows(AwsException.class, () -> service.rebootCluster("partial-stop"));
            assertTrue(Files.exists(dump.get()), "The pre-reboot dump must survive partial teardown");
            assertEquals("preserved database", Files.readString(dump.get()));
            assertEquals("failed", cluster.getClusterStatus());
            if (proxyFailure) {
                verify(cm, never()).stop(anyString(), anyString());
            } else {
                verify(cm, atLeastOnce()).stop("111111111111", "partial-stop");
            }
        } finally {
            if (dump.get() != null) {
                Files.deleteIfExists(dump.get());
            }
        }
    }


    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slowSnapshotDoesNotBlockOtherClusterMutation(boolean finalSnapshot) throws Exception {
        finalSnapshotCluster();
        Cluster other = new Cluster();
        other.setClusterIdentifier("other-cluster");
        when(clusterBackend.get("other-cluster")).thenReturn(Optional.of(other));
        CountDownLatch dumping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            dumping.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(cm).takeSnapshot(eq("111111111111"), eq("final-cluster"), eq("admin"), any(Path.class));
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<?> snapshot = callers.submit(() -> {
                if (finalSnapshot) {
                    finalSnapshotDelete("FinalClusterSnapshotIdentifier", "final-backup");
                } else {
                    service.createSnapshot("final-backup", "final-cluster");
                }
            });
            try {
                assertTrue(dumping.await(2, TimeUnit.SECONDS));
                Future<Cluster> mutation = callers.submit(() -> service.modifyCluster("other-cluster", "ra3.large",
                        null, null, null, null));
                assertEquals("ra3.large", mutation.get(1, TimeUnit.SECONDS).getNodeType());
            } finally {
                release.countDown();
                snapshot.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void snapshotFlushFailureKeepsCommittedDump() throws Exception {
        finalSnapshotCluster();
        AtomicReference<Path> dump = new AtomicReference<>();
        doAnswer(invocation -> {
            Path path = invocation.getArgument(3, Path.class);
            Files.writeString(path, "committed backup");
            dump.set(path);
            return null;
        }).when(cm).takeSnapshot(eq("111111111111"), eq("final-cluster"), eq("admin"), any(Path.class));
        doThrow(new IllegalStateException("flush failed")).when(snapshotBackend).flush();
        try {
            assertThrows(AwsException.class,
                    () -> finalSnapshotDelete("FinalClusterSnapshotIdentifier", "final-backup"));
            verify(snapshotBackend).putAllAtomically(anyMap());
            assertEquals("committed backup", Files.readString(dump.get()));
            verify(cm, never()).stop(anyString(), anyString());
        } finally {
            if (dump.get() != null) {
                Files.deleteIfExists(dump.get());
            }
        }
    }

    @Test
    void snapshotKeepsSameClusterMutationSerializedWithoutHoldingServiceMonitor() throws Exception {
        finalSnapshotCluster();
        CountDownLatch dumping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attemptingMutation = new CountDownLatch(1);
        doAnswer(invocation -> {
            dumping.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(cm).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<Snapshot> snapshot = callers.submit(() -> service.createSnapshot("backup", "final-cluster"));
            try {
                assertTrue(dumping.await(2, TimeUnit.SECONDS));
                Future<Cluster> mutation = callers.submit(() -> {
                    attemptingMutation.countDown();
                    return service.modifyCluster("final-cluster", "ra3.large", null, null, null, null);
                });
                assertTrue(attemptingMutation.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> mutation.get(150, TimeUnit.MILLISECONDS));
                release.countDown();
                assertNotNull(snapshot.get(2, TimeUnit.SECONDS));
                assertEquals("ra3.large", mutation.get(2, TimeUnit.SECONDS).getNodeType());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void concurrentSnapshotsOnDifferentClustersCannotOverwriteTheSameBackup() throws Exception {
        Cluster source = finalSnapshotCluster();
        when(clusterBackend.get("other-cluster")).thenReturn(Optional.of(source));
        Map<String, Snapshot> committed = new ConcurrentHashMap<>();
        when(snapshotBackend.get(anyString())).thenAnswer(invocation -> Optional.ofNullable(committed.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            committed.putAll(invocation.getArgument(0));
            return null;
        }).when(snapshotBackend).putAllAtomically(anyMap());
        CountDownLatch dumping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(1);
        doAnswer(invocation -> {
            dumping.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(cm).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<Snapshot> first = callers.submit(() -> service.createSnapshot("backup", "final-cluster"));
            try {
                assertTrue(dumping.await(2, TimeUnit.SECONDS));
                Future<AwsException> second = callers.submit(() -> {
                    attempting.countDown();
                    return assertThrows(AwsException.class, () -> service.createSnapshot("backup", "other-cluster"));
                });
                assertTrue(attempting.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(150, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals("final-cluster", first.get(2, TimeUnit.SECONDS).getClusterIdentifier());
                assertEquals("ClusterSnapshotAlreadyExists", second.get(2, TimeUnit.SECONDS).getErrorCode());
                verify(cm, times(1)).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void deleteWithFinalSnapshotCommitsBackupBeforeStoppingCluster() {
        Cluster cluster = finalSnapshotCluster();
        finalSnapshotDelete("FinalClusterSnapshotIdentifier", "final-backup",
                "FinalClusterSnapshotRetentionPeriod", "7");

        ArgumentCaptor<Map<String, Snapshot>> committed = ArgumentCaptor.captor();
        InOrder order = inOrder(cm, snapshotBackend, proxyManager, clusterBackend);
        order.verify(cm).takeSnapshot(eq("111111111111"), eq("final-cluster"), eq("admin"), any(Path.class));
        order.verify(snapshotBackend).putAllAtomically(committed.capture());
        order.verify(snapshotBackend).flush();
        order.verify(proxyManager).stopProxy("111111111111:final-cluster");
        order.verify(cm).stop("111111111111", "final-cluster");
        order.verify(clusterBackend).delete("final-cluster");
        Snapshot snapshot = committed.getValue().get("final-backup");
        assertEquals("final-cluster", snapshot.getClusterIdentifier());
        assertEquals("secret-pw", snapshot.getMasterPassword());
        assertEquals(7, snapshot.getManualSnapshotRetentionPeriod());
        assertEquals("deleting", cluster.getClusterStatus());
    }

    @Test
    void deleteDefaultsToRequiringFinalSnapshotName() {
        finalSnapshotCluster();
        AwsException failure = assertThrows(AwsException.class, () -> finalSnapshotDelete());
        assertEquals("InvalidParameterCombination", failure.getErrorCode());
        verifyNoInteractions(cm, proxyManager);
        verify(clusterBackend, never()).delete(anyString());
    }

    @Test
    void finalSnapshotDefaultsToIndefiniteRetentionAndSurvivesTeardownFailure() {
        Cluster cluster = finalSnapshotCluster();
        doThrow(new IllegalStateException("proxy busy")).when(proxyManager).stopProxy(anyString());
        assertThrows(AwsException.class,
                () -> finalSnapshotDelete("FinalClusterSnapshotIdentifier", "final-backup"));
        ArgumentCaptor<Map<String, Snapshot>> committed = ArgumentCaptor.captor();
        verify(snapshotBackend).putAllAtomically(committed.capture());
        assertEquals(-1, committed.getValue().get("final-backup").getManualSnapshotRetentionPeriod());
        verify(snapshotBackend, never()).delete(anyString());
        verify(cm, never()).stop(anyString(), anyString());
        assertEquals("available", cluster.getClusterStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape", "bad--name", "bad-", "1bad", " "})
    void deleteRejectsMalformedFinalSnapshotName(String name) {
        finalSnapshotCluster();
        assertThrows(AwsException.class, () -> finalSnapshotDelete("FinalClusterSnapshotIdentifier", name));
        verifyNoInteractions(cm, proxyManager);
        verify(clusterBackend, never()).delete(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-2", "3654"})
    void deleteRejectsInvalidRetentionBeforeDumpOrTeardown(String retention) {
        finalSnapshotCluster();
        AwsException failure = assertThrows(AwsException.class, () -> finalSnapshotDelete(
                "FinalClusterSnapshotIdentifier", "final-backup", "FinalClusterSnapshotRetentionPeriod", retention));
        assertEquals("InvalidRetentionPeriodFault", failure.getErrorCode());
        verifyNoInteractions(cm, proxyManager);
        verify(clusterBackend, never()).delete(anyString());
    }

    @Test
    void deleteRejectsSkipWithFinalSnapshotName() {
        finalSnapshotCluster();
        AwsException failure = assertThrows(AwsException.class, () -> finalSnapshotDelete(
                "SkipFinalClusterSnapshot", "true", "FinalClusterSnapshotIdentifier", "final-backup"));
        assertEquals("InvalidParameterCombination", failure.getErrorCode());
        verifyNoInteractions(cm, proxyManager);
    }

    @Test
    void deleteRejectsMalformedSkipAndRetention() {
        finalSnapshotCluster();
        for (String[] option : List.of(new String[]{"SkipFinalClusterSnapshot", "yes"},
                new String[]{"FinalClusterSnapshotRetentionPeriod", "abc"},
                new String[]{"FinalClusterSnapshotRetentionPeriod", "2147483648"})) {
            AwsException failure = assertThrows(AwsException.class, () -> finalSnapshotDelete(option));
            assertEquals("InvalidParameterValue", failure.getErrorCode());
        }
        verifyNoInteractions(cm, proxyManager);
    }

    @Test
    void deleteKeepsClusterWhenDumpFails() {
        Cluster cluster = finalSnapshotCluster();
        doThrow(new AwsException("InternalFailure", "dump failed", 500)).when(cm)
                .takeSnapshot(eq("111111111111"), eq("final-cluster"), eq("admin"), any(Path.class));
        assertThrows(AwsException.class,
                () -> finalSnapshotDelete("FinalClusterSnapshotIdentifier", "final-backup"));
        verifyNoInteractions(proxyManager);
        verify(cm, never()).stop(anyString(), anyString());
        verify(clusterBackend, never()).delete(anyString());
        assertEquals("available", cluster.getClusterStatus());
    }

    @Test
    void deleteKeepsClusterWhenSnapshotCommitFails() {
        Cluster cluster = finalSnapshotCluster();
        doThrow(new IllegalStateException("storage failed")).when(snapshotBackend).putAllAtomically(anyMap());
        assertThrows(RuntimeException.class,
                () -> finalSnapshotDelete("FinalClusterSnapshotIdentifier", "final-backup"));
        verifyNoInteractions(proxyManager);
        verify(cm, never()).stop(anyString(), anyString());
        verify(clusterBackend, never()).delete(anyString());
        assertEquals("available", cluster.getClusterStatus());
    }

    @Test
    void deleteRejectsDuplicateBackupAndUnavailableCluster() {
        Cluster cluster = finalSnapshotCluster();
        when(snapshotBackend.get("existing-backup")).thenReturn(Optional.of(new Snapshot()));
        AwsException duplicate = assertThrows(AwsException.class,
                () -> finalSnapshotDelete("FinalClusterSnapshotIdentifier", "existing-backup"));
        assertEquals("ClusterSnapshotAlreadyExists", duplicate.getErrorCode());
        cluster.setClusterStatus("unavailable");
        AwsException unavailable = assertThrows(AwsException.class,
                () -> finalSnapshotDelete("FinalClusterSnapshotIdentifier", "final-backup"));
        assertEquals("InvalidClusterState", unavailable.getErrorCode());
        verifyNoInteractions(cm, proxyManager);
    }

    private Cluster finalSnapshotCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("final-cluster");
        cluster.setClusterStatus("available");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("secret-pw");
        when(clusterBackend.get("final-cluster")).thenReturn(Optional.of(cluster));
        return cluster;
    }

    private void finalSnapshotDelete(String... options) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ClusterIdentifier", "final-cluster");
        for (int index = 0; index < options.length; index += 2) {
            params.putSingle(options[index], options[index + 1]);
        }
        RedshiftQueryHandler query = new RedshiftQueryHandler(service, credentialBroker,
                mock(EmulatorConfig.class), mock(RedshiftIamDbUserResolver.class), regionResolver);
        query.handle("DeleteCluster", params);
    }

    @Test
    void blankDefaultRoleCreatesWithoutDefaultAndClearsExistingDefault() {
        String role = "arn:aws:iam::111111111111:role/copy";
        when(cm.start(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new RedshiftContainerHandle("container", "cluster", "localhost", 5432));
        for (String blank : List.of("", " \t")) {
            String identifier = blank.isEmpty() ? "empty-default" : "whitespace-default";
            Cluster cluster = service.createCluster(identifier, "dc2.large", "admin", "pw",
                    null, List.of(), List.of(role), blank);
            assertNull(cluster.getDefaultIamRoleArn());
            when(clusterBackend.get(identifier)).thenReturn(Optional.of(cluster));

            service.modifyClusterIamRoles(identifier, List.of(), List.of(), role);
            service.modifyClusterIamRoles(identifier, List.of(), List.of());
            assertEquals(role, cluster.getDefaultIamRoleArn());

            service.modifyClusterIamRoles(identifier, List.of(), List.of(), blank);
            assertNull(cluster.getDefaultIamRoleArn());
            assertEquals(List.of(role), cluster.getIamRoleArns());
            verify(proxyManager).updateIamRoles("111111111111:" + identifier, List.of(role), null);
        }
    }

    @Test
    void modifyDefaultIamRoleValidatesBeforeMutatingAndClearsWhenRemoved() throws Exception {
        String first = "arn:aws:iam::111111111111:role/first";
        String second = "arn:aws:iam::111111111111:role/second";
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setIamRoleArns(List.of(first));
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));

        service.modifyClusterIamRoles("c1", List.of(), List.of(), first);
        assertEquals(first, cluster.getDefaultIamRoleArn());
        verify(proxyManager).updateIamRoles("111111111111:c1", List.of(first), first);

        AwsException missing = assertThrows(AwsException.class,
                () -> service.modifyClusterIamRoles("c1", List.of(), List.of(first), second));
        assertEquals("InvalidParameterValue", missing.getErrorCode());
        assertEquals(List.of(first), cluster.getIamRoleArns());
        assertEquals(first, cluster.getDefaultIamRoleArn());

        service.modifyClusterIamRoles("c1", List.of(second), List.of(), second);
        assertEquals(second, cluster.getDefaultIamRoleArn());
        service.modifyClusterIamRoles("c1", List.of(), List.of(first));
        assertEquals(second, cluster.getDefaultIamRoleArn());

        ObjectMapper mapper = new ObjectMapper();
        Cluster restored = mapper.readValue(mapper.writeValueAsBytes(cluster), Cluster.class);
        assertEquals(second, restored.getDefaultIamRoleArn());
        assertEquals(List.of(second), restored.getIamRoleArns());

        service.modifyClusterIamRoles("c1", List.of(), List.of(second));
        assertNull(cluster.getDefaultIamRoleArn());
        verify(proxyManager).updateIamRoles("111111111111:c1", List.of(), null);
    }

    @Test
    void createClusterRejectsUnassociatedOrMalformedDefaultRoleBeforeStartingContainer() {
        String role = "arn:aws:iam::111111111111:role/copy";
        assertThrows(AwsException.class, () -> service.createCluster("c1", "dc2.large", "admin", "pw",
                null, List.of(), List.of(), role));
        assertThrows(AwsException.class, () -> service.createCluster("c1", "dc2.large", "admin", "pw",
                null, List.of(), List.of("invalid"), "invalid"));
        verifyNoInteractions(cm);
        verify(clusterBackend, never()).put(any(), any());
    }

    @Test
    void createClusterStoresDefaultRoleAndConfiguresProxy() {
        String role = "arn:aws:iam::111111111111:role/copy";
        when(cm.start("111111111111", "c1", "admin", "pw"))
                .thenReturn(new RedshiftContainerHandle("container", "c1", "localhost", 5432));
        Cluster cluster = service.createCluster("c1", "dc2.large", "admin", "pw",
                null, List.of(), List.of(role), role);
        assertEquals(role, cluster.getDefaultIamRoleArn());
        verify(proxyManager).startProxy(eq("111111111111:c1"), anyInt(), eq("localhost"), eq(5432),
                anyString(), eq("admin"), eq("pw"), eq("dev"), any(), eq(List.of(role)), eq(role));
        verify(clusterBackend, atLeastOnce()).flush();
    }

    @Test
    void createClusterPassesNodeTypeToContainerManager() {
        when(cm.start("111111111111", "c-ra3", "admin", "pw"))
                .thenReturn(new RedshiftContainerHandle("c-ra3", "c-ra3", "localhost", 5432));
        service.createCluster("c-ra3", "ra3.xlplus", "admin", "pw",
                null, List.of(), List.of(), null);
        verify(cm).start("111111111111", "c-ra3", "admin", "pw", "dev", "ra3.xlplus");
    }

    private StorageFactory sf;
    private AccountAwareStorageBackend<Cluster> clusterBackend;
    private AccountAwareStorageBackend<Snapshot> snapshotBackend;
    private AccountAwareStorageBackend<String> snapshotDumpBackend;
    private AccountAwareStorageBackend<ClusterParameterGroup> parameterGroupBackend;
    private AccountAwareStorageBackend<ClusterSubnetGroup> subnetGroupBackend;
    private AccountAwareStorageBackend<SnapshotCopyGrant> snapshotCopyGrantBackend;
    private AccountAwareStorageBackend<SnapshotSchedule> snapshotScheduleBackend;
    private AccountAwareStorageBackend<Integration> integrationBackend;
    private RedshiftContainerManager cm;
    private RegionResolver regionResolver;
    private RedshiftProxyManager proxyManager;
    private DockerHostResolver dockerHostResolver;
    private RedshiftCredentialBroker credentialBroker;
    private SecretsManagerService secretsManagerService;
    private RedshiftEventService events;
    private final Set<String> existingStreams = new HashSet<>();
    private RedshiftService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sf = mock(StorageFactory.class);
        clusterBackend = mock(AccountAwareStorageBackend.class);
        snapshotBackend = mock(AccountAwareStorageBackend.class);
        snapshotDumpBackend = mock(AccountAwareStorageBackend.class);
        parameterGroupBackend = mock(AccountAwareStorageBackend.class);
        subnetGroupBackend = mock(AccountAwareStorageBackend.class);
        snapshotCopyGrantBackend = mock(AccountAwareStorageBackend.class);
        snapshotScheduleBackend = mock(AccountAwareStorageBackend.class);
        integrationBackend = mock(AccountAwareStorageBackend.class);
        cm = mock(RedshiftContainerManager.class);
        when(cm.start(any(), any(), any(), any(), any(), any())).thenAnswer(inv ->
                cm.start(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        when(cm.adoptOrStart(any(), any(), any(), any(), any(), any())).thenAnswer(inv ->
                cm.adoptOrStart(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        proxyManager = mock(RedshiftProxyManager.class);
        dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("localhost");

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storageConfig = mock(EmulatorConfig.StorageConfig.class);
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.persistentPath()).thenReturn("target/test-data");

        EmulatorConfig.ServicesConfig servicesConfig =
                mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.RedshiftServiceConfig redshiftConfig =
                mock(EmulatorConfig.RedshiftServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.redshift()).thenReturn(redshiftConfig);
        when(redshiftConfig.proxyBasePort()).thenReturn(7100);
        when(redshiftConfig.proxyMaxPort()).thenReturn(7199);
        when(redshiftConfig.endpointHost()).thenReturn(Optional.empty());

        when(sf.<Cluster>create(eq("redshift"), eq("redshift-clusters.json"), any())).thenReturn(clusterBackend);
        when(sf.<Snapshot>create(eq("redshift"), eq("redshift-snapshots.json"), any())).thenReturn(snapshotBackend);
        when(sf.<ClusterParameterGroup>create(eq("redshift"), eq("redshift-parameter-groups.json"), any())).thenReturn(parameterGroupBackend);
        when(sf.<ClusterSubnetGroup>create(eq("redshift"), eq("redshift-subnet-groups.json"), any())).thenReturn(subnetGroupBackend);
        when(sf.<SnapshotCopyGrant>create(eq("redshift"), eq("redshift-snapshot-copy-grants.json"), any())).thenReturn(snapshotCopyGrantBackend);
        when(sf.<Integration>create(eq("redshift"), eq("redshift-integrations.json"), any())).thenReturn(integrationBackend);
        when(sf.<SnapshotSchedule>create(eq("redshift"), eq("redshift-snapshot-schedules.json"), any())).thenReturn(snapshotScheduleBackend);
        when(clusterBackend.accountId()).thenReturn("111111111111");

        regionResolver = new RegionResolver("us-east-1", "111111111111");

        credentialBroker = new RedshiftCredentialBroker();
        secretsManagerService = mock(SecretsManagerService.class);
        events = mock(RedshiftEventService.class);
        ObjectMapper mapper = new ObjectMapper();
        // Answers DescribeStream the way the native handler does: it throws for an unknown stream.
        DynamoDbOperations streams = call -> {
            String streamArn = call.body().path("StreamArn").asText();
            if (!existingStreams.contains(streamArn)) {
                throw new AwsException("ResourceNotFoundException", "Stream not found: " + streamArn, 400);
            }
            return new DynamoDbOperations.Reply(200, mapper.readTree("{\"StreamDescription\":{\"Shards\":[]}}"),
                    Map.of());
        };

        service = new RedshiftService(sf, cm, config, regionResolver, proxyManager, dockerHostResolver,
                credentialBroker, secretsManagerService, mapper,
                new DynamoDbApiStreamReader(streams, CheckpointLifetime.PROCESS), null, events);
    }

    @Test
    void successfulClusterOperationsRecordTheirLifecycleEvents() {
        when(cm.start(any(), any(), any(), any()))
                .thenReturn(new RedshiftContainerHandle("container", "event-cluster", "localhost", 5432));
        Cluster cluster = service.createCluster("event-cluster", "dc2.large", "admin", "password123");
        when(clusterBackend.get("event-cluster")).thenReturn(Optional.of(cluster));

        service.modifyCluster("event-cluster", null, null, "changedPassword123", "custom-pg", List.of("sg-new"));
        service.rebootCluster("event-cluster");
        service.deleteCluster("event-cluster");

        InOrder recorded = inOrder(events);
        recorded.verify(events).record(eq(RedshiftEventCatalog.CLUSTER_CREATED), eq("event-cluster"), anyString());
        recorded.verify(events).record(eq(RedshiftEventCatalog.ADMIN_CREDENTIALS_UPDATED), eq("event-cluster"), anyString());
        recorded.verify(events).record(eq(RedshiftEventCatalog.CLUSTER_PARAMETER_GROUP_CHANGED), eq("event-cluster"), anyString());
        recorded.verify(events).record(eq(RedshiftEventCatalog.CLUSTER_SECURITY_GROUPS_UPDATED), eq("event-cluster"), anyString());
        recorded.verify(events).record(eq(RedshiftEventCatalog.CLUSTER_REBOOTED), eq("event-cluster"), anyString());
        recorded.verify(events).record(eq(RedshiftEventCatalog.CLUSTER_DELETED), eq("event-cluster"), anyString());
        recorded.verifyNoMoreInteractions();
    }

    @Test
    void creationEventIsRecordedBeforeAQueuedClusterOperationCanRun() throws Exception {
        when(cm.start(any(), any(), any(), any()))
                .thenReturn(new RedshiftContainerHandle("container", "event-cluster", "localhost", 5432));
        AtomicReference<Cluster> stored = new AtomicReference<>();
        when(clusterBackend.get("event-cluster")).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(clusterBackend).put(eq("event-cluster"), any(Cluster.class));
        AtomicReference<Future<?>> queued = new AtomicReference<>();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            doAnswer(invocation -> {
                queued.set(executor.submit(() -> service.rebootCluster("event-cluster")));
                assertThrows(TimeoutException.class, () -> queued.get().get(300, TimeUnit.MILLISECONDS),
                        "A queued reboot ran before the creation event was recorded");
                return null;
            }).when(events).record(eq(RedshiftEventCatalog.CLUSTER_CREATED), eq("event-cluster"), anyString());

            service.createCluster("event-cluster", "dc2.large", "admin", "password123");
            queued.get().get(5, TimeUnit.SECONDS);
        }

        InOrder recorded = inOrder(events);
        recorded.verify(events).record(eq(RedshiftEventCatalog.CLUSTER_CREATED), eq("event-cluster"), anyString());
        recorded.verify(events).record(eq(RedshiftEventCatalog.CLUSTER_REBOOTED), eq("event-cluster"), anyString());
    }

    @Test
    void failedClusterOperationsNeverRecordSuccessEvents() {
        when(cm.start(any(), any(), any(), any())).thenThrow(new RuntimeException("startup failed"));
        assertThrows(AwsException.class,
                () -> service.createCluster("event-cluster", "dc2.large", "admin", "password123"));
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("event-cluster");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("password123");
        cluster.setClusterStatus("available");
        when(clusterBackend.get("event-cluster")).thenReturn(Optional.of(cluster));
        doThrow(new AwsException("InternalFailure", "password change failed", 500))
                .when(cm).alterUserPassword(anyString(), anyString(), anyString(), anyString());
        assertThrows(AwsException.class,
                () -> service.modifyCluster("event-cluster", null, null, "changedPassword123", null, null));
        doThrow(new RuntimeException("dump failed")).when(cm).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
        assertThrows(AwsException.class, () -> service.rebootCluster("event-cluster"));
        doThrow(new RuntimeException("proxy stop failed")).when(proxyManager).stopProxy("111111111111:event-cluster");
        assertThrows(AwsException.class, () -> service.deleteCluster("event-cluster"));

        verifyNoInteractions(events);
    }

    @Test
    void managedPasswordClusterRecordsCreationOnlyAfterTheSecretExists() {
        when(cm.start(eq("111111111111"), eq("event-managed"), eq("admin"), anyString()))
                .thenReturn(new RedshiftContainerHandle("container", "event-managed", "localhost", 5432));
        Secret secret = new Secret();
        secret.setArn("arn:aws:secretsmanager:us-east-1:111111111111:secret:event-managed");
        secret.setCurrentVersionId("version-1");
        when(secretsManagerService.createSecret(eq("redshift/event-managed"), anyString(), isNull(),
                anyString(), isNull(), anyList(), eq("redshift"), eq("us-east-1"))).thenReturn(secret);

        service.createClusterWithManagedMasterPassword("event-managed", "dc2.large", "admin", null,
                List.of(), List.of(), null, "us-east-1");

        InOrder recorded = inOrder(secretsManagerService, events);
        recorded.verify(secretsManagerService).createSecret(eq("redshift/event-managed"), anyString(), isNull(),
                anyString(), isNull(), anyList(), eq("redshift"), eq("us-east-1"));
        recorded.verify(events).record(eq(RedshiftEventCatalog.CLUSTER_CREATED), eq("event-managed"), anyString());
        recorded.verifyNoMoreInteractions();
    }

    @Test
    void managedPasswordClusterRolledBackAfterSecretFailureRecordsNoEvents() {
        when(cm.start(eq("111111111111"), eq("event-failed"), eq("admin"), anyString()))
                .thenReturn(new RedshiftContainerHandle("container", "event-failed", "localhost", 5432));
        when(secretsManagerService.createSecret(eq("redshift/event-failed"), anyString(), isNull(),
                anyString(), isNull(), anyList(), eq("redshift"), eq("us-east-1")))
                .thenThrow(new AwsException("InternalFailure", "secret store unavailable", 500));

        assertThrows(AwsException.class, () -> service.createClusterWithManagedMasterPassword(
                "event-failed", "dc2.large", "admin", null, List.of(), List.of(), null, "us-east-1"));

        verify(clusterBackend).delete("event-failed");
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotEventsReportStartAndTheActualOutcome(boolean fails) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("event-cluster");
        cluster.setMasterUsername("admin");
        when(clusterBackend.get("event-cluster")).thenReturn(Optional.of(cluster));
        if (fails) {
            doThrow(new RuntimeException("snapshot failed"))
                    .when(cm).takeSnapshot(anyString(), anyString(), anyString(), any(Path.class));
            assertThrows(AwsException.class, () -> service.createSnapshot("event-snapshot", "event-cluster"));
        } else {
            service.createSnapshot("event-snapshot", "event-cluster");
        }

        InOrder recorded = inOrder(events);
        recorded.verify(events).recordForAccount(eq("111111111111"), eq("us-east-1"),
                eq(RedshiftEventCatalog.SNAPSHOT_STARTED), eq("event-snapshot"), anyString());
        recorded.verify(events).recordForAccount(eq("111111111111"), eq("us-east-1"),
                eq(fails ? RedshiftEventCatalog.SNAPSHOT_FAILED : RedshiftEventCatalog.SNAPSHOT_COMPLETED),
                eq("event-snapshot"), anyString());
        recorded.verifyNoMoreInteractions();
    }

    @Test
    void parameterGroupModifyAndResetRecordUpdatesAfterPersistence() {
        ClusterParameterGroup group = new ClusterParameterGroup("event-pg", "redshift-1.0", "events");
        when(parameterGroupBackend.get("event-pg")).thenReturn(Optional.of(group));

        service.modifyClusterParameterGroup("event-pg", List.of(new Parameter("max_cursor_result_set_size", "1000")));
        service.resetClusterParameterGroup("event-pg", true, List.of());

        InOrder recorded = inOrder(parameterGroupBackend, events);
        recorded.verify(parameterGroupBackend).flush();
        recorded.verify(events).record(eq(RedshiftEventCatalog.PARAMETER_GROUP_UPDATED), eq("event-pg"), anyString());
        recorded.verify(parameterGroupBackend).flush();
        recorded.verify(events).record(eq(RedshiftEventCatalog.PARAMETER_GROUP_UPDATED), eq("event-pg"), anyString());
        verify(events, times(2)).record(any(), anyString(), anyString());
    }

    @Test
    void invalidParameterGroupResetDoesNotRecordAnUpdate() {
        ClusterParameterGroup group = new ClusterParameterGroup("event-pg", "redshift-1.0", "events");
        when(parameterGroupBackend.get("event-pg")).thenReturn(Optional.of(group));

        assertThrows(AwsException.class,
                () -> service.resetClusterParameterGroup("event-pg", false, List.of("unknown-parameter")));

        verifyNoInteractions(events);
        verify(parameterGroupBackend, never()).flush();
    }

    @Test
    void createDynamoDbZeroEtlIntegrationRequiresExistingProvisionedResources() {
        String streamArn = "arn:aws:dynamodb:us-east-1:111111111111:table/orders/stream/2026-09-18T00:00:00.000";
        String targetArn = "arn:aws:redshift:us-east-1:111111111111:cluster:warehouse";
        existingStreams.add(streamArn);
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("warehouse");
        when(clusterBackend.get("warehouse")).thenReturn(Optional.of(cluster));
        when(integrationBackend.scan(any())).thenReturn(List.of());

        Integration integration = service.createIntegration("orders-to-warehouse", streamArn, targetArn,
                null, null, Map.of(), Map.of(), "us-east-1");

        assertEquals(streamArn, integration.getSourceStreamArn());
        assertEquals("warehouse", integration.getTargetClusterIdentifier());
        assertNotNull(integration.getLandingTableName());
        assertEquals("syncing", integration.getStatus());
        assertFalse(integration.isBackfillCompleted());
    }

    @Test
    void createDynamoDbZeroEtlIntegrationRejectsAMissingStreamWithResourceNotFound() {
        String streamArn = "arn:aws:dynamodb:us-east-1:111111111111:table/orders/stream/2026-09-18T00:00:00.000";

        AwsException error = assertThrows(AwsException.class, () -> service.createIntegration(
                "orders-to-warehouse", streamArn, "arn:aws:redshift:us-east-1:111111111111:cluster:warehouse",
                null, null, Map.of(), Map.of(), "us-east-1"));

        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals("Stream not found: " + streamArn, error.getMessage());
        assertEquals(400, error.getHttpStatus());
        verify(integrationBackend, never()).put(any(), any());
    }

    @Test
    void updateIntegrationRuntimeStoresACopyOfShardProgressOnlyOnSuccess() {
        String integrationArn = "arn:aws:redshift:us-east-1:111111111111:integration:one";
        Integration integration = new Integration();
        integration.setIntegrationArn(integrationArn);
        when(integrationBackend.keysForAccount("111111111111")).thenReturn(Set.of("one"));
        when(integrationBackend.getForAccount("111111111111", "one")).thenReturn(Optional.of(integration));
        Map<String, String> progress = new HashMap<>(Map.of("shard-1", "s1"));

        service.updateIntegrationRuntime("111111111111", integrationArn, progress, true, null);
        progress.put("shard-1", "s2");

        assertEquals(Map.of("shard-1", "s1"), integration.getShardSequenceNumbers());
        assertEquals("active", integration.getStatus());

        service.updateIntegrationRuntime("111111111111", integrationArn, progress, false, "boom");

        assertEquals(Map.of("shard-1", "s1"), integration.getShardSequenceNumbers());
        assertEquals("failed", integration.getStatus());
        assertEquals("boom", integration.getLastError());
        verify(integrationBackend, times(2)).putForAccount("111111111111", "one", integration);
    }

    @Test
    void updateIntegrationBackfillProgressPersistsCheckpointAndFlipsStatusOnCompletion() {
        String streamArn = "arn:aws:dynamodb:us-east-1:111111111111:table/orders/stream/2026-09-18T00:00:00.000";
        String targetArn = "arn:aws:redshift:us-east-1:111111111111:cluster:warehouse";
        existingStreams.add(streamArn);
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("warehouse");
        when(clusterBackend.get("warehouse")).thenReturn(Optional.of(cluster));
        when(integrationBackend.scan(any())).thenReturn(List.of());

        Integration integration = service.createIntegration("orders-to-warehouse", streamArn, targetArn,
                null, null, Map.of(), Map.of(), "us-east-1");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(integrationBackend).put(keyCaptor.capture(), eq(integration));
        String storageKey = keyCaptor.getValue();
        when(integrationBackend.keysForAccount("111111111111")).thenReturn(Set.of(storageKey));
        when(integrationBackend.getForAccount("111111111111", storageKey)).thenReturn(Optional.of(integration));

        service.updateIntegrationBackfillProgress("111111111111", integration.getIntegrationArn(),
                "{\"id\":{\"S\":\"1\"}}", false);
        assertEquals("syncing", integration.getStatus());
        assertEquals("{\"id\":{\"S\":\"1\"}}", integration.getBackfillLastEvaluatedKey());
        assertFalse(integration.isBackfillCompleted());

        service.updateIntegrationBackfillProgress("111111111111", integration.getIntegrationArn(), null, true);
        assertEquals("active", integration.getStatus());
        assertNull(integration.getBackfillLastEvaluatedKey());
        assertTrue(integration.isBackfillCompleted());
    }

    @Test
    void updateIntegrationBackfillProgressOnUnknownIntegrationThrows() {
        when(integrationBackend.keysForAccount("111111111111")).thenReturn(Set.of());

        assertThrows(AwsException.class, () -> service.updateIntegrationBackfillProgress(
                "111111111111", "arn:aws:redshift:us-east-1:111111111111:integration:missing", null, true));
    }

    @Test
    void createDynamoDbZeroEtlIntegrationRejectsServerlessTarget() {
        assertThrows(AwsException.class, () -> service.createIntegration(
                "orders-to-serverless",
                "arn:aws:dynamodb:us-east-1:111111111111:table/orders/stream/2026-09-18T00:00:00.000",
                "arn:aws:redshift-serverless:us-east-1:111111111111:workgroup/analytics",
                null, null, Map.of(), Map.of(), "us-east-1"));
    }

    /** Absolute dump path as {@code createSnapshot} now stores it: under {@code <persistentPath>/redshift-dumps/<accountId>}. */
    private static String dumpPath(String snapshotId) {
        return Paths.get("target/test-data", "redshift-dumps", "111111111111", snapshotId + ".sql")
                .toAbsolutePath().normalize().toString();
    }

    @Test
    void managedMasterPasswordCreatesOwnedSecretAndExposesItsMetadata() {
        when(cm.start(eq("111111111111"), eq("managed-cluster"), eq("admin"), anyString()))
                .thenReturn(new RedshiftContainerHandle("container", "managed-cluster", "localhost", 5432));

        Secret secret = new Secret();
        secret.setArn("arn:aws:secretsmanager:us-east-1:111111111111:secret:redshift-managed");
        secret.setCurrentVersionId("version-1");
        when(secretsManagerService.createSecret(eq("redshift/managed-cluster"), anyString(), isNull(),
                anyString(), eq("arn:aws:kms:us-east-1:111111111111:key/key-1"), anyList(),
                eq("redshift"), eq("us-east-1"))).thenReturn(secret);

        Cluster cluster = service.createClusterWithManagedMasterPassword("managed-cluster", "dc2.large",
                "admin", null, List.of(), List.of(),
                "arn:aws:kms:us-east-1:111111111111:key/key-1", "us-east-1");

        assertEquals(secret.getArn(), cluster.getMasterPasswordSecretArn());
        assertEquals("arn:aws:kms:us-east-1:111111111111:key/key-1", cluster.getMasterPasswordSecretKmsKeyId());
        verify(secretsManagerService).createSecret(eq("redshift/managed-cluster"),
                contains("\"username\":\"admin\""), isNull(), anyString(),
                eq("arn:aws:kms:us-east-1:111111111111:key/key-1"), anyList(), eq("redshift"), eq("us-east-1"));
    }

    @Test
    void managedMasterSecretRotationChangesTheClusterAndProxyPassword() throws Exception {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("managed-cluster");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("OldPassword123");
        cluster.setMasterPasswordSecretArn("arn:aws:secretsmanager:us-east-1:111111111111:secret:redshift-managed");
        cluster.setClusterStatus("available");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "managed-cluster", cluster)));
        when(clusterBackend.getForAccount("111111111111", "managed-cluster")).thenReturn(Optional.of(cluster));

        AtomicReference<String> secretValue = new AtomicReference<>();
        assertTrue(service.supports(cluster.getMasterPasswordSecretArn(), "us-east-1"));
        service.rotate(cluster.getMasterPasswordSecretArn(), "us-east-1", (changeBackend, rollbackBackend) ->
                secretValue.set(changeBackend.get()));

        verify(cm).alterUserPassword(eq("111111111111"), eq("managed-cluster"), eq("admin"),
                argThat(password -> !"OldPassword123".equals(password)));
        verify(proxyManager).updateMasterPassword(eq("111111111111:managed-cluster"),
                argThat(password -> !"OldPassword123".equals(password)));
        assertNotNull(secretValue.get());
        String rotatedPassword = new ObjectMapper().readTree(secretValue.get()).path("password").asText();
        assertEquals(cluster.getMasterPassword(), rotatedPassword);
        assertFalse(rotatedPassword.chars().anyMatch(character -> "'\"/@\\".indexOf(character) >= 0));
    }

    @Test
    void managedRotationReservationBlocksClusterDeletionBeforeMutation() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("managed-cluster");
        cluster.setMasterPasswordSecretArn("arn:aws:secretsmanager:us-east-1:111111111111:secret:redshift-managed");
        cluster.setClusterStatus("available");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "managed-cluster", cluster)));
        when(clusterBackend.getForAccount("111111111111", "managed-cluster")).thenReturn(Optional.of(cluster));

        service.reserve(cluster.getMasterPasswordSecretArn(), "us-east-1");

        assertThrows(AwsException.class, () -> service.deleteCluster("managed-cluster"));
        verify(cm, never()).stop("111111111111", "managed-cluster");

        service.release(cluster.getMasterPasswordSecretArn(), "us-east-1");
    }

    @Test
    void managedMasterPasswordRollsBackClusterWhenSecretCreationFails() {
        when(cm.start(eq("111111111111"), eq("failed-managed-cluster"), eq("admin"), anyString()))
                .thenReturn(new RedshiftContainerHandle("container", "failed-managed-cluster", "localhost", 5432));
        when(secretsManagerService.createSecret(eq("redshift/failed-managed-cluster"), anyString(), isNull(),
                anyString(), isNull(), anyList(), eq("redshift"), eq("us-east-1")))
                .thenThrow(new AwsException("InternalFailure", "secret store unavailable", 500));

        assertThrows(AwsException.class, () -> service.createClusterWithManagedMasterPassword(
                "failed-managed-cluster", "dc2.large", "admin", null, List.of(), List.of(), null, "us-east-1"));

        verify(cm).stop("111111111111", "failed-managed-cluster");
        verify(clusterBackend).delete("failed-managed-cluster");
        assertThrows(AwsException.class, () -> service.describeClusters("failed-managed-cluster"));
    }

    @Test
    void onStartRecreatesContainersAcrossAccounts() {
        Cluster clusterA = new Cluster();
        clusterA.setClusterIdentifier("cluster-a");
        clusterA.setMasterUsername("admin");
        clusterA.setMasterPassword("pw-a");
        clusterA.setClusterStatus("available");
        clusterA.setDefaultIamRoleArn("arn:aws:iam::111111111111:role/default-a");
        clusterA.setIamRoleArns(List.of(clusterA.getDefaultIamRoleArn()));

        Cluster clusterB = new Cluster();
        clusterB.setClusterIdentifier("cluster-b");
        clusterB.setMasterUsername("admin");
        clusterB.setMasterPassword("pw-b");
        clusterB.setClusterStatus("available");
        clusterB.setDefaultIamRoleArn("arn:aws:iam::222222222222:role/default-b");
        clusterB.setIamRoleArns(List.of(clusterB.getDefaultIamRoleArn()));

        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "cluster-a", clusterA),
                new AccountAwareStorageBackend.AccountEntry<>("222222222222", "cluster-b", clusterB)));
        when(cm.getContainer(anyString(), anyString())).thenReturn(Optional.empty());
        when(cm.adoptOrStart(eq("111111111111"), eq("cluster-a"), eq("admin"), eq("pw-a")))
                .thenReturn(new RedshiftContainerHandle("c-a", "cluster-a", "localhost", 5432));
        when(cm.adoptOrStart(eq("222222222222"), eq("cluster-b"), eq("admin"), eq("pw-b")))
                .thenReturn(new RedshiftContainerHandle("c-b", "cluster-b", "localhost", 5433));

        service.onStart(null);

        // Cluster owned by a second, non-default account must also be recovered
        verify(cm).adoptOrStart("111111111111", "cluster-a", "admin", "pw-a");
        verify(cm).adoptOrStart("222222222222", "cluster-b", "admin", "pw-b");
        verify(proxyManager).startProxy(eq("111111111111:cluster-a"), anyInt(), anyString(), anyInt(), anyString(),
                eq("admin"), eq("pw-a"), eq("dev"), any(), eq(clusterA.getIamRoleArns()), eq(clusterA.getDefaultIamRoleArn()));
        verify(proxyManager).startProxy(eq("222222222222:cluster-b"), anyInt(), anyString(), anyInt(), anyString(),
                eq("admin"), eq("pw-b"), eq("dev"), any(), eq(clusterB.getIamRoleArns()), eq(clusterB.getDefaultIamRoleArn()));
        verify(clusterBackend).putForAccount(eq("111111111111"), eq("cluster-a"), any(Cluster.class));
        verify(clusterBackend).putForAccount(eq("222222222222"), eq("cluster-b"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void onStartSkipsClusterWithRunningContainer() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("cluster-a");
        cluster.setClusterStatus("available");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "cluster-a", cluster)));
        when(cm.getContainer("111111111111", "cluster-a"))
                .thenReturn(Optional.of(new RedshiftContainerHandle("c-a", "cluster-a", "localhost", 5432)));

        service.onStart(null);

        verify(cm, never()).adoptOrStart(any(), any(), any(), any());
        verify(clusterBackend, never()).putForAccount(any(), any(), any());
    }

    @Test
    void onStartMarksClusterUnavailableOnStartFailure() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("cluster-a");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("pw-a");
        cluster.setClusterStatus("available");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "cluster-a", cluster)));
        when(cm.getContainer("111111111111", "cluster-a")).thenReturn(Optional.empty());
        when(cm.adoptOrStart(eq("111111111111"), eq("cluster-a"), eq("admin"), eq("pw-a"))).thenThrow(new RuntimeException("docker down"));

        service.onStart(null);

        ArgumentCaptor<Cluster> captor = ArgumentCaptor.forClass(Cluster.class);
        verify(clusterBackend).putForAccount(eq("111111111111"), eq("cluster-a"), captor.capture());
        assertEquals("unavailable", captor.getValue().getClusterStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onStartRestartsTheProxyForAnAdoptedCluster() {
        Cluster persisted = new Cluster();
        persisted.setClusterIdentifier("c1");
        persisted.setMasterUsername("admin");
        persisted.setMasterPassword("Secret123");
        persisted.setClusterStatus("available");
        persisted.setProxyPort(7108);

        AccountAwareStorageBackend.AccountEntry<Cluster> entry = inlineMock(AccountAwareStorageBackend.AccountEntry.class);
        when(entry.value()).thenReturn(persisted);
        when(entry.accountId()).thenReturn("111111111111");
        when(entry.key()).thenReturn("c1");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(entry));
        when(cm.getContainer("111111111111", "c1")).thenReturn(Optional.empty());
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.12");
        when(handle.getPort()).thenReturn(32830);
        when(cm.adoptOrStart("111111111111", "c1", "admin", "Secret123")).thenReturn(handle);

        service.onStart(null);

        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(7108),
                eq("172.17.0.12"), eq(32830), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any(), any(), isNull());
    }

    @Test
    void createCluster() {
        when(clusterBackend.get(anyString())).thenReturn(Optional.empty());
        when(cm.start(any(), any(), any(), any())).thenReturn(new RedshiftContainerHandle("c1", "my-cluster", "localhost", 5432));

        Cluster cluster = service.createCluster("my-cluster", "dc2.large", "admin", "password123");
        assertNotNull(cluster);
        assertEquals("my-cluster", cluster.getClusterIdentifier());
        assertEquals("available", cluster.getClusterStatus());
        assertEquals("localhost", cluster.getEndpoint().getAddress());
        verify(clusterBackend, times(2)).put(eq("my-cluster"), any(Cluster.class));
    }

    @Test
    void createClusterWithVpcMetadata() {
        when(clusterBackend.get(anyString())).thenReturn(Optional.empty());
        when(cm.start(any(), any(), any(), any())).thenReturn(new RedshiftContainerHandle("c1", "my-cluster", "localhost", 5432));

        Cluster cluster = service.createCluster("my-cluster", "dc2.large", "admin", "password123",
                "my-subnet-group", List.of("sg-1", "sg-2"));

        assertEquals("my-subnet-group", cluster.getClusterSubnetGroupName());
        assertEquals(List.of("sg-1", "sg-2"), cluster.getVpcSecurityGroupIds());
    }

    @Test
    void createClusterStartsAProxyAndAdvertisesTheProxyEndpoint() {
        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(clusterBackend.accountId()).thenReturn("111111111111");
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.9");
        when(handle.getPort()).thenReturn(32800);
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123"))).thenReturn(handle);

        Cluster cluster = service.createCluster("c1", "dc2.large", "admin", "Secret123");

        // Endpoint is the proxy, not the container.
        assertEquals("localhost", cluster.getEndpoint().getAddress());
        assertTrue(cluster.getEndpoint().getPort() >= 7100 && cluster.getEndpoint().getPort() <= 7199);
        assertEquals("172.17.0.9", cluster.getContainerHost());
        assertEquals(32800, cluster.getContainerPort());
        assertEquals(cluster.getEndpoint().getPort(), cluster.getProxyPort());

        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(cluster.getProxyPort()),
                eq("172.17.0.9"), eq(32800), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any(), any(), isNull());
    }

    @Test
    void createClusterAlreadyExists() {
        when(clusterBackend.get("existing-cluster")).thenReturn(Optional.of(new Cluster()));

        assertThrows(AwsException.class, () ->
                service.createCluster("existing-cluster", "dc2.large", "admin", "password123"));
    }

    @Test
    void createClusterRemovesMetadataOnFailure() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        // Simulate a concurrent GetClusterCredentials landing while the row exists, then fail
        // container startup so the rollback path runs.
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenAnswer(inv -> {
                    credentialBroker.issue("111111111111", "c1", "analyst", List.of(), 900);
                    throw new RuntimeException("startup failed");
                });

        assertThrows(AwsException.class, () ->
                service.createCluster("c1", "dc2.large", "admin", "password123"));

        verify(clusterBackend).delete("c1");
        verify(clusterBackend, atLeastOnce()).flush();
        // The rollback that removes the cluster row must also drop that credential.
        assertTrue(credentialBroker.resolve("111111111111", "c1", "analyst").isEmpty());
    }

    @Test
    void createClusterKeepsMetadataWhenProxyStopFails() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenThrow(new RuntimeException("startup failed"));
        doThrow(new RuntimeException("proxy stop failed"))
                .when(proxyManager).stopProxy("111111111111:c1");

        assertThrows(AwsException.class, () ->
                service.createCluster("c1", "dc2.large", "admin", "password123"));

        verify(clusterBackend, never()).delete("c1");
        verify(clusterBackend, atLeast(1)).put(eq("c1"), argThat(c -> "failed".equals(c.getClusterStatus())));
        verify(clusterBackend, atLeastOnce()).flush();
    }

    @Test
    void restoreFromClusterSnapshotRemovesMetadataOnFailure() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotIdentifier("snap-1");
        snapshot.setClusterIdentifier("source-c1");
        snapshot.setMasterUsername("admin");
        snapshot.setMasterPassword("password123");
        snapshot.setSqlDump(null);

        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenThrow(new RuntimeException("startup failed"));

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("c1", "snap-1"));

        verify(clusterBackend).delete("c1");
        verify(clusterBackend, atLeastOnce()).flush();
    }

    @Test
    void restoreFromClusterSnapshotKeepsMetadataWhenProxyStopFails() {
        when(clusterBackend.accountId()).thenReturn("111111111111");
        Snapshot snapshot = new Snapshot();
        snapshot.setSnapshotIdentifier("snap-1");
        snapshot.setClusterIdentifier("source-c1");
        snapshot.setMasterUsername("admin");
        snapshot.setMasterPassword("password123");
        snapshot.setSqlDump(null);

        when(clusterBackend.get("c1")).thenReturn(Optional.empty());
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("password123")))
                .thenThrow(new RuntimeException("startup failed"));
        doThrow(new RuntimeException("proxy stop failed"))
                .when(proxyManager).stopProxy("111111111111:c1");

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("c1", "snap-1"));

        verify(clusterBackend, never()).delete("c1");
        verify(clusterBackend, atLeast(1)).put(eq("c1"), argThat(c -> "failed".equals(c.getClusterStatus())));
        verify(clusterBackend, atLeastOnce()).flush();
        InOrder recorded = inOrder(events);
        recorded.verify(events).record(eq(RedshiftEventCatalog.CLUSTER_RESTORE_STARTED), eq("c1"), anyString());
        recorded.verify(events).record(eq(RedshiftEventCatalog.CLUSTER_RESTORE_FAILED), eq("c1"), anyString());
        recorded.verifyNoMoreInteractions();
    }

    @Test
    void describeClusters() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));

        List<Cluster> list = service.describeClusters("test-c");
        assertEquals(1, list.size());
        assertEquals("test-c", list.get(0).getClusterIdentifier());
    }

    @Test
    void describeClustersForAccountUsesTheRequestedAccount() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("test-c");
        when(clusterBackend.getForAccount("222222222222", "test-c")).thenReturn(Optional.of(cluster));

        List<Cluster> list = service.describeClustersForAccount("222222222222", "test-c");

        assertEquals(List.of(cluster), list);
        verify(clusterBackend).getForAccount("222222222222", "test-c");
        verify(clusterBackend, never()).get("test-c");
    }

    @Test
    void deleteCluster() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));

        Cluster deleted = service.deleteCluster("test-c");
        assertEquals("deleting", deleted.getClusterStatus());
        verify(cm).stop("111111111111", "test-c");
        verify(clusterBackend).delete("test-c");
    }

    @Test
    void deleteClusterRevokesItsGetClusterCredentialsCredentials() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));
        credentialBroker.issue("111111111111", "test-c", "analyst", List.of(), 900);

        service.deleteCluster("test-c");

        assertTrue(credentialBroker.resolve("111111111111", "test-c", "analyst").isEmpty());
    }

    @Test
    void deleteClusterAbortsAndKeepsMetadataWhenTheProxyWontStop() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        c.setProxyPort(7107);
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));
        doThrow(new RuntimeException("listener close failed"))
                .when(proxyManager).stopProxy("111111111111:test-c");

        AwsException ex = assertThrows(AwsException.class, () -> service.deleteCluster("test-c"));
        assertEquals("InternalFailure", ex.getErrorCode());

        // Container and metadata are left intact so the deletion can be retried.
        verify(cm, never()).stop(anyString(), anyString());
        verify(clusterBackend, never()).delete(anyString());
    }

    @Test
    void deleteClusterRetrySucceedsOnceTheProxyStops() {
        Cluster c = new Cluster();
        c.setClusterIdentifier("test-c");
        c.setProxyPort(7107);
        when(clusterBackend.get("test-c")).thenReturn(Optional.of(c));
        doThrow(new RuntimeException("listener close failed"))
                .doNothing()
                .when(proxyManager).stopProxy("111111111111:test-c");

        assertThrows(AwsException.class, () -> service.deleteCluster("test-c"));
        Cluster deleted = service.deleteCluster("test-c");

        assertEquals("deleting", deleted.getClusterStatus());
        verify(cm).stop("111111111111", "test-c");
        verify(clusterBackend).delete("test-c");
    }

    @Test
    void rebootClusterDumpsAndRestoresData() throws Exception {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("pw");
        cluster.setClusterStatus("available");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(cm.start(eq("111111111111"), eq("my-cluster"), eq("admin"), eq("pw")))
                .thenReturn(new RedshiftContainerHandle("c-rebooted", "my-cluster", "localhost", 5555));
        doAnswer(invocation -> {
            Path dumpFile = invocation.getArgument(3);
            Files.writeString(dumpFile, "-- dump");
            return null;
        }).when(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));

        Cluster rebooted = service.rebootCluster("my-cluster");

        assertEquals("available", rebooted.getClusterStatus());
        // Endpoint now advertises the auth proxy; the restarted container is tracked separately.
        assertEquals(5555, rebooted.getContainerPort());
        assertTrue(rebooted.getEndpoint().getPort() >= 7100 && rebooted.getEndpoint().getPort() <= 7199);
        verify(cm).stop("111111111111", "my-cluster");
        verify(cm).start("111111111111", "my-cluster", "admin", "pw");
        verify(cm).restoreSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));
    }

    @Test
    void rebootClusterRestartsTheProxyOnTheSamePort() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setDefaultIamRoleArn("arn:aws:iam::111111111111:role/default");
        cluster.setIamRoleArns(List.of(cluster.getDefaultIamRoleArn()));
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        cluster.setEndpoint(new Endpoint("localhost", 7107));
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.11");
        when(handle.getPort()).thenReturn(32820);
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123"))).thenReturn(handle);

        Cluster rebooted = service.rebootCluster("c1");

        assertEquals(7107, rebooted.getEndpoint().getPort());
        assertEquals("localhost", rebooted.getEndpoint().getAddress());
        assertEquals("172.17.0.11", rebooted.getContainerHost());
        verify(proxyManager).stopProxy("111111111111:c1");
        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(7107),
                eq("172.17.0.11"), eq(32820), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any(), eq(cluster.getIamRoleArns()), eq(cluster.getDefaultIamRoleArn()));
    }

    @Test
    void rebootClusterNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.rebootCluster("missing"));
    }

    @Test
    void rebootClusterTearsDownTheProxyWhenRestoreFails() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.11");
        when(handle.getPort()).thenReturn(32820);
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123"))).thenReturn(handle);
        doThrow(new RuntimeException("restore boom"))
                .when(cm).restoreSnapshot(eq("111111111111"), eq("c1"), eq("admin"), any(Path.class));

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        // The proxy started during the reboot must be stopped again on failure, and the
        // replacement container must be stopped too, once before the restart, once in
        // rollback, so it is not left running behind a "failed" cluster.
        verify(proxyManager).startProxy(eq("111111111111:c1"), eq(7107), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), isNull());
        verify(proxyManager, times(2)).stopProxy("111111111111:c1");
        verify(cm, times(2)).stop("111111111111", "c1");
    }

    @Test
    void rebootClusterLeavesTheOriginalContainerAloneWhenTheDumpFails() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        doThrow(new RuntimeException("dump boom"))
                .when(cm).takeSnapshot(eq("111111111111"), eq("c1"), eq("admin"), any(Path.class));

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        // The dump failed before the original was torn down: rollback must not stop the
        // still-running original container or touch its proxy.
        verify(cm, never()).stop(anyString(), anyString());
        verify(proxyManager, never()).stopProxy(anyString());
    }

    @Test
    void rebootClusterRemovesTheReplacementWhenItsStartupThrows() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123")))
                .thenThrow(new RuntimeException("readiness timed out"));

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        // start() can create the container before throwing (readiness check); the
        // original is already gone, so rollback removes anything under the name:
        // once for the original teardown, once for the possible orphan.
        verify(cm, times(2)).stop("111111111111", "c1");

        // The reserved port belongs to the cluster and is not released by reboot rollback;
        // it is released only when the cluster is successfully deleted.
        assertEquals(7107, cluster.getProxyPort());
    }

    @Test
    void rebootClusterKeepsProxyPortWhenRollbackStopFails() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("Secret123");
        cluster.setProxyPort(7107);
        when(clusterBackend.accountId()).thenReturn("111111111111");
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(cm.start(eq("111111111111"), eq("c1"), eq("admin"), eq("Secret123")))
                .thenThrow(new RuntimeException("readiness timed out"));
        
        // The first call stops the proxy during takeSnapshot; the second call happens in rollback.
        doNothing().doThrow(new RuntimeException("stop failed")).when(proxyManager).stopProxy(anyString());

        assertThrows(AwsException.class, () -> service.rebootCluster("c1"));

        assertEquals(7107, cluster.getProxyPort());
    }

    @Test
    void createSnapshot() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("secret-pw");
        cluster.setEndpoint(new Endpoint("localhost", 5439));

        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.empty());
        doNothing().when(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));

        Snapshot snapshot = service.createSnapshot("my-snapshot", "my-cluster");
        assertNotNull(snapshot);
        assertEquals("my-snapshot", snapshot.getSnapshotIdentifier());
        assertEquals("my-cluster", snapshot.getClusterIdentifier());
        assertEquals("available", snapshot.getStatus());
        assertEquals("admin", snapshot.getMasterUsername());
        // Password captured at snapshot time so restore can recover it after the source cluster is gone
        assertEquals("secret-pw", snapshot.getMasterPassword());
        assertEquals(5439, snapshot.getPort());
        assertEquals("arn:aws:redshift:us-east-1:111111111111:snapshot:my-cluster/my-snapshot",
                snapshot.getSnapshotArn());
        assertNotNull(snapshot.getSnapshotCreateTime());
        // sqlDump is now an absolute path scoped by account to avoid collisions across accounts
        assertTrue(snapshot.getSqlDump().contains("111111111111"));
        assertTrue(snapshot.getSqlDump().endsWith("my-snapshot.sql"));
        verify(snapshotBackend).putAllAtomically(Map.of("my-snapshot", snapshot));
        verify(snapshotBackend).flush();
        verify(cm).takeSnapshot(eq("111111111111"), eq("my-cluster"), eq("admin"), any(Path.class));
    }

    @Test
    void createSnapshotClusterNotFound() {
        when(clusterBackend.get("missing-cluster")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.createSnapshot("snap-1", "missing-cluster"));
    }

    @Test
    void createSnapshotAlreadyExists() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(new Snapshot()));

        assertThrows(AwsException.class, () ->
                service.createSnapshot("snap-1", "my-cluster"));
    }

    @Test
    void createSnapshotRejectsTraversalOrMalformedIdentifier() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get(anyString())).thenReturn(Optional.empty());

        String[] bad = {
                "../evil", "../../etc/cron.d/x", "/etc/passwd", "a/b", "a\\b",
                "foo/../bar", "-leading", "trailing-", "double--hyphen", "1startsdigit", "", "has space",
        };
        for (String id : bad) {
            AwsException ex = assertThrows(AwsException.class,
                    () -> service.createSnapshot(id, "my-cluster"), "expected rejection for: " + id);
            assertEquals("InvalidParameterValue", ex.getErrorCode(), id);
            assertEquals(400, ex.getHttpStatus(), id);
        }
        verify(cm, never()).takeSnapshot(any(), any(), any(), any(Path.class));
        verify(snapshotBackend, never()).put(anyString(), any(Snapshot.class));
    }

    @Test
    void modifyClusterUpdatesMetadataAndPassword() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setNodeType("dc2.large");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("old-pw");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster updated = service.modifyCluster("my-cluster", "ra3.xlplus", null, "new-pw", null, null);

        assertEquals("ra3.xlplus", updated.getNodeType());
        assertEquals("new-pw", updated.getMasterPassword());
        verify(cm).alterUserPassword("111111111111", "my-cluster", "admin", "new-pw");
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void modifyClusterUpdatesVpcSecurityGroups() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setMasterUsername("admin");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster updated = service.modifyCluster("my-cluster", null, null, null, "custom-pg", List.of("sg-9"));

        assertEquals("custom-pg", updated.getClusterParameterGroupName());
        assertEquals(List.of("sg-9"), updated.getVpcSecurityGroupIds());
        verify(cm, never()).alterUserPassword(any(), any(), any(), any());
    }

    @Test
    void modifyClusterNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.modifyCluster("missing", "ra3.xlplus", null, null, null, null));
    }

    @Test
    void modifyClusterIamRolesAddsAndRemovesRolesAndUpdatesTheProxy() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setIamRoleArns(List.of("arn:aws:iam::111111111111:role/old", "arn:aws:iam::111111111111:role/keep"));
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");

        Cluster updated = service.modifyClusterIamRoles("c1",
                List.of("arn:aws:iam::111111111111:role/new", "arn:aws:iam::111111111111:role/keep"),
                List.of("arn:aws:iam::111111111111:role/old"));

        assertEquals(List.of("arn:aws:iam::111111111111:role/keep", "arn:aws:iam::111111111111:role/new"),
                updated.getIamRoleArns());
        verify(proxyManager).updateIamRoles("111111111111:c1", updated.getIamRoleArns(), null);
        verify(clusterBackend).put(eq("c1"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void describeLoggingStatusDefaultsToDisabled() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.describeLoggingStatus("my-cluster");

        assertFalse(result.isLoggingEnabled());
        assertNull(result.getLoggingBucketName());
    }

    @Test
    void describeLoggingStatusNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.describeLoggingStatus("missing"));
    }

    @Test
    void enableLogging() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.enableLogging("my-cluster", "my-bucket", "logs/", null, null);

        assertTrue(result.isLoggingEnabled());
        assertEquals("my-bucket", result.getLoggingBucketName());
        assertEquals("logs/", result.getLoggingS3KeyPrefix());
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void modifyClusterIamRolesRejectsMalformedRoleArnWithoutChangingTheCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setIamRoleArns(List.of("arn:aws:iam::111111111111:role/keep"));
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.modifyClusterIamRoles("c1", List.of("not-an-arn"), List.of()));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(List.of("arn:aws:iam::111111111111:role/keep"), cluster.getIamRoleArns());
        verify(proxyManager, never()).updateIamRoles(any(), any(), any());
    }

    @Test
    void modifyClusterIamRolesRejectsNonRoleArn() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.modifyClusterIamRoles("c1", List.of("arn:aws:iam::111111111111:user/bob"), List.of()));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void modifyClusterIamRolesNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.modifyClusterIamRoles("missing", List.of(), List.of()));

        assertEquals("ClusterNotFound", ex.getErrorCode());
    }

    @Test
    void enableLoggingRequiresBucketName() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        assertThrows(AwsException.class, () -> service.enableLogging("my-cluster", null, null, null, null));
    }

    @Test
    void enableLoggingCloudWatchDoesNotRequireBucketName() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.enableLogging("my-cluster", null, null, "cloudwatch", List.of("connectionlog"));
        assertTrue(result.isLoggingEnabled());
        assertEquals("cloudwatch", result.getLoggingDestinationType());
        assertEquals(List.of("connectionlog"), result.getLoggingExports());
    }

    @Test
    void enableLoggingS3TableDoesNotRequireBucketName() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.enableLogging("my-cluster", null, null, "s3table", null, "my-kms-key", "account");
        assertTrue(result.isLoggingEnabled());
        assertEquals("s3table", result.getLoggingDestinationType());
        assertNull(result.getLoggingBucketName());
        assertEquals("my-kms-key", result.getLoggingS3TableKmsKeyId());
        assertEquals("account", result.getLoggingS3TableGranularity());
    }

    @Test
    void enableLoggingRejectsInvalidS3TableGranularity() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.enableLogging("my-cluster", null, null, "s3table", null, null, "daily"));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertFalse(cluster.isLoggingEnabled());
    }

    @Test
    void enableLoggingRejectsS3TableSettingsForOtherDestinations() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.enableLogging("my-cluster", null, null, "s3", null, null, "cluster"));

        assertEquals("InvalidParameterCombination", ex.getErrorCode());
        assertFalse(cluster.isLoggingEnabled());
    }

    @Test
    void disableLoggingClearsS3TableFields() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingDestinationType("s3table");
        cluster.setLoggingS3TableKmsKeyId("my-kms-key");
        cluster.setLoggingS3TableGranularity("account");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.disableLogging("my-cluster");
        assertFalse(result.isLoggingEnabled());
        assertNull(result.getLoggingDestinationType());
        assertNull(result.getLoggingS3TableKmsKeyId());
        assertNull(result.getLoggingS3TableGranularity());
    }

    @Test
    void createClusterAssignsDefaultParameterGroup() {
        when(clusterBackend.get(anyString())).thenReturn(Optional.empty());
        when(cm.start(any(), any(), any(), any())).thenReturn(new RedshiftContainerHandle("c1", "my-cluster", "localhost", 5432));

        Cluster cluster = service.createCluster("my-cluster", "dc2.large", "admin", "password123");
        assertEquals("default.redshift-1.0", cluster.getClusterParameterGroupName());
    }

    @Test
    void modifyClusterStoresMultiAZ() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        assertTrue(service.modifyCluster("my-cluster", null, null, null, null, null, true).isMultiAZ());
    }

    @Test
    void describeDefaultParameterGroupIsSynthesized() {
        when(parameterGroupBackend.get("default.redshift-1.0")).thenReturn(Optional.empty());

        List<ClusterParameterGroup> groups = service.describeClusterParameterGroups("default.redshift-1.0");
        assertEquals(1, groups.size());
        assertEquals("default.redshift-1.0", groups.get(0).getParameterGroupName());
    }

    @Test
    void enableLoggingNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.enableLogging("missing", "my-bucket", null, null, null));
    }

    @Test
    void disableLoggingClearsConfig() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setLoggingEnabled(true);
        cluster.setLoggingBucketName("my-bucket");
        cluster.setLoggingS3KeyPrefix("logs/");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        Cluster result = service.disableLogging("my-cluster");

        assertFalse(result.isLoggingEnabled());
        assertNull(result.getLoggingBucketName());
        assertNull(result.getLoggingS3KeyPrefix());
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
        verify(clusterBackend).flush();
    }

    @Test
    void disableLoggingNotFound() {
        when(clusterBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.disableLogging("missing"));
    }

    @Test
    void describeSnapshots() {
        Snapshot s = new Snapshot("snap-1", "my-cluster", "available", 5439, "admin");
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(s));

        List<Snapshot> list = service.describeSnapshots("snap-1");
        assertEquals(1, list.size());
        assertEquals("snap-1", list.get(0).getSnapshotIdentifier());
    }

    @Test
    void describeSnapshotsNotFound() {
        when(snapshotBackend.get("missing-snap")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.describeSnapshots("missing-snap"));
    }

    @Test
    void deleteSnapshot() {
        Snapshot s = new Snapshot("snap-1", "my-cluster", "available", 5439, "admin");
        s.setSqlDump(dumpPath("snap-1"));
        when(snapshotBackend.get("snap-1")).thenReturn(Optional.of(s));

        Snapshot deleted = service.deleteSnapshot("snap-1");
        assertNotNull(deleted);
        assertEquals("deleted", deleted.getStatus());
        verify(snapshotBackend).delete("snap-1");
        verify(snapshotBackend).flush();
    }

    @Test
    void deleteSnapshotNotFound() {
        when(snapshotBackend.get("missing-snap")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.deleteSnapshot("missing-snap"));
    }

    @Test
    void snapshotRestoreCanManagePasswordAndStoreSubnetGroup() throws Exception {
        useStatefulClusterStorage();
        Snapshot snapshot = new Snapshot("managed-backup", "deleted-source", "available", 5439, "admin");
        snapshot.setMasterPassword("Original123");
        snapshot.setSqlDump(dumpPath("managed-backup"));
        when(snapshotBackend.get("managed-backup")).thenReturn(Optional.of(snapshot));
        when(cm.start("111111111111", "restored-managed", "admin", "Original123"))
                .thenReturn(new RedshiftContainerHandle("restored", "restored-managed", "localhost", 5432));
        Secret secret = new Secret();
        secret.setArn("arn:aws:secretsmanager:us-east-1:111111111111:secret:restored-managed");
        when(secretsManagerService.createSecret(eq("redshift/restored-managed"), anyString(), isNull(),
                anyString(), eq("alias/restore-key"), anyList(), eq("redshift"), eq("us-east-1"))).thenReturn(secret);

        Cluster cluster = service.restoreFromClusterSnapshot("restored-managed", "managed-backup", "ra3.large",
                "restore-subnets", true, "alias/restore-key", "us-east-1");

        assertEquals("restore-subnets", cluster.getClusterSubnetGroupName());
        assertEquals(secret.getArn(), cluster.getMasterPasswordSecretArn());
        assertFalse("Original123".equals(cluster.getMasterPassword()));
        ArgumentCaptor<String> secretValue = ArgumentCaptor.forClass(String.class);
        verify(secretsManagerService).createSecret(eq("redshift/restored-managed"), secretValue.capture(), isNull(),
                anyString(), eq("alias/restore-key"), anyList(), eq("redshift"), eq("us-east-1"));
        assertEquals(cluster.getMasterPassword(), new ObjectMapper().readTree(secretValue.getValue()).path("password").asText());
        verify(cm).alterUserPassword("111111111111", "restored-managed", "admin", cluster.getMasterPassword());
    }

    @Test
    void managedSnapshotRestoreCleansUpWhenSecretCreationFails() {
        useStatefulClusterStorage();
        Snapshot snapshot = new Snapshot("managed-backup", "deleted-source", "available", 5439, "admin");
        snapshot.setMasterPassword("Original123");
        when(snapshotBackend.get("managed-backup")).thenReturn(Optional.of(snapshot));
        when(cm.start("111111111111", "restored-managed", "admin", "Original123"))
                .thenReturn(new RedshiftContainerHandle("restored", "restored-managed", "localhost", 5432));
        when(secretsManagerService.createSecret(eq("redshift/restored-managed"), anyString(), isNull(),
                anyString(), isNull(), anyList(), eq("redshift"), eq("us-east-1")))
                .thenThrow(new AwsException("InternalFailure", "secret storage failed", 500));

        assertThrows(AwsException.class, () -> service.restoreFromClusterSnapshot("restored-managed", "managed-backup",
                "ra3.large", null, true, null, "us-east-1"));

        verify(cm).stop("111111111111", "restored-managed");
        assertTrue(service.describeClusters(null).isEmpty());
        assertTrue(service.getSnapshot("managed-backup").isPresent());
    }

    private void useStatefulClusterStorage() {
        Map<String, Cluster> stored = new HashMap<>();
        when(clusterBackend.get(anyString())).thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
        when(clusterBackend.scan(any())).thenAnswer(invocation -> new ArrayList<>(stored.values()));
        doAnswer(invocation -> {
            stored.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(clusterBackend).put(anyString(), any(Cluster.class));
        doAnswer(invocation -> {
            stored.remove(invocation.getArgument(0));
            return null;
        }).when(clusterBackend).delete(anyString());
    }

    @Test
    void restoreFromClusterSnapshot() {
        Cluster sourceCluster = new Cluster();
        sourceCluster.setClusterIdentifier("source-cluster");
        sourceCluster.setMasterPassword("password123");

        Snapshot snapshot = new Snapshot("my-snapshot", "source-cluster", "available", 5439, "admin");
        snapshot.setSqlDump(dumpPath("my-snapshot"));
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(clusterBackend.get("source-cluster")).thenReturn(Optional.of(sourceCluster));
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("password123")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));

        Cluster cluster = service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large");
        assertNotNull(cluster);
        assertEquals("restored-cluster", cluster.getClusterIdentifier());
        assertEquals("available", cluster.getClusterStatus());
        assertEquals("admin", cluster.getMasterUsername());
        assertEquals("password123", cluster.getMasterPassword());
        assertEquals("dc2.large", cluster.getNodeType());
        assertEquals("localhost", cluster.getEndpoint().getAddress());

        // Restore must use the source cluster's actual password, not a hardcoded one
        verify(cm).start("111111111111", "restored-cluster", "admin", "password123");
        verify(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));
        verify(clusterBackend, times(2)).put(eq("restored-cluster"), any(Cluster.class));
        verify(clusterBackend, times(2)).flush();
    }

    @Test
    void restoreFromClusterSnapshotUsesStoredPasswordAfterSourceClusterDeleted() {
        Snapshot snapshot = new Snapshot("my-snapshot", "deleted-source", "available", 5439, "admin");
        snapshot.setMasterPassword("original-secret");
        snapshot.setSqlDump(dumpPath("my-snapshot"));
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        // Source cluster no longer exists, but the snapshot itself still carries the original password
        when(clusterBackend.get("deleted-source")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("original-secret")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));

        Cluster cluster = service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large");
        assertEquals("original-secret", cluster.getMasterPassword());
        verify(cm).start("111111111111", "restored-cluster", "admin", "original-secret");
    }

    @Test
    void restoreFromClusterSnapshotFallsBackToAdminWhenSourceClusterGone() {
        Snapshot snapshot = new Snapshot("my-snapshot", "deleted-source", "available", 5439, "admin");
        snapshot.setSqlDump(dumpPath("my-snapshot"));
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(clusterBackend.get("deleted-source")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(eq("111111111111"), eq("restored-cluster"), eq("admin"), eq("admin")))
                .thenReturn(new RedshiftContainerHandle("c-new", "restored-cluster", "localhost", 5432));
        doNothing().when(cm).restoreSnapshot(eq("111111111111"), eq("restored-cluster"), eq("admin"), any(Path.class));

        Cluster cluster = service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large");
        assertEquals("admin", cluster.getMasterPassword());
        verify(cm).start("111111111111", "restored-cluster", "admin", "admin");
    }

    @Test
    void restoreFromClusterSnapshotAlreadyExists() {
        when(clusterBackend.get("existing-cluster")).thenReturn(Optional.of(new Cluster()));

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("existing-cluster", "my-snapshot"));
    }

    @Test
    void restoreFromClusterSnapshotNotFound() {
        when(clusterBackend.get("new-cluster")).thenReturn(Optional.empty());
        when(snapshotBackend.get("missing-snapshot")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("new-cluster", "missing-snapshot"));
    }

    @Test
    void restoreFromClusterSnapshotFailure() {
        Snapshot snapshot = new Snapshot("my-snapshot", "source-cluster", "available", 5439, "admin", dumpPath("my-snapshot"));
        when(clusterBackend.get("failed-cluster")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));
        when(cm.start(any(), any(), any(), any())).thenThrow(new RuntimeException("Docker error"));

        assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("failed-cluster", "my-snapshot"));
    }

    @Test
    void restoreFromClusterSnapshotRejectsUntrustedDumpPathBeforeProvisioning() {
        // A dump path persisted outside the account dir (e.g. by pre-validation code) must be
        // rejected up front: no cluster record, no container.
        Snapshot snapshot = new Snapshot("my-snapshot", "source-cluster", "available", 5439, "admin", "/etc/shadow");
        when(clusterBackend.get("restored-cluster")).thenReturn(Optional.empty());
        when(snapshotBackend.get("my-snapshot")).thenReturn(Optional.of(snapshot));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.restoreFromClusterSnapshot("restored-cluster", "my-snapshot", "dc2.large"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        verify(cm, never()).start(any(), any(), any(), any());
        verify(cm, never()).restoreSnapshot(any(), any(), any(), any());
        verify(clusterBackend, never()).put(anyString(), any(Cluster.class));
    }

    @Test
    void deleteClusterStopsTheProxyAndReleasesThePort() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setProxyPort(7105);
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");

        service.deleteCluster("c1");

        verify(proxyManager).stopProxy("111111111111:c1");
    }

    @Test
    void modifyClusterPasswordUpdatesTheProxySnapshot() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("c1");
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("old");
        when(clusterBackend.get("c1")).thenReturn(Optional.of(cluster));
        when(clusterBackend.accountId()).thenReturn("111111111111");

        service.modifyCluster("c1", null, null, "NewSecret1", null, null);

        verify(proxyManager).updateMasterPassword("111111111111:c1", "NewSecret1");
    }

    @Test
    void restoreFromSnapshotStartsAProxyForTheNewCluster() {
        Snapshot snap = new Snapshot();
        snap.setSnapshotIdentifier("s1");
        snap.setClusterIdentifier("src");
        snap.setMasterUsername("admin");
        snap.setMasterPassword("Secret123");
        snap.setSqlDump(null);
        when(snapshotBackend.get("s1")).thenReturn(Optional.of(snap));
        when(clusterBackend.get("restored")).thenReturn(Optional.empty());
        when(clusterBackend.accountId()).thenReturn("111111111111");
        RedshiftContainerHandle handle = mock(RedshiftContainerHandle.class);
        when(handle.getHost()).thenReturn("172.17.0.10");
        when(handle.getPort()).thenReturn(32810);
        when(cm.start(eq("111111111111"), eq("restored"), eq("admin"), eq("Secret123"))).thenReturn(handle);

        Cluster restored = service.restoreFromClusterSnapshot("restored", "s1");

        assertEquals("localhost", restored.getEndpoint().getAddress());
        assertEquals(restored.getEndpoint().getPort(), restored.getProxyPort());
        verify(proxyManager).startProxy(eq("111111111111:restored"), anyInt(),
                eq("172.17.0.10"), eq(32810), eq("localhost"),
                eq("admin"), eq("Secret123"), eq("dev"), any(), any(), isNull());
    }

    @Test
    void createClusterParameterGroup() {
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.empty());

        ClusterParameterGroup pg = service.createClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        assertNotNull(pg);
        assertEquals("my-pg", pg.getParameterGroupName());
        assertEquals("redshift-1.0", pg.getParameterGroupFamily());
        assertEquals("custom pg", pg.getDescription());
        verify(parameterGroupBackend).put(eq("my-pg"), any(ClusterParameterGroup.class));
        verify(parameterGroupBackend).flush();
    }

    @Test
    void createClusterParameterGroupAlreadyExists() {
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(new ClusterParameterGroup()));

        assertThrows(AwsException.class, () ->
                service.createClusterParameterGroup("my-pg", "redshift-1.0", "custom pg"));
    }

    @Test
    void describeClusterParameterGroups() {
        ClusterParameterGroup pg = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(pg));

        List<ClusterParameterGroup> list = service.describeClusterParameterGroups("my-pg");
        assertEquals(1, list.size());
        assertEquals("my-pg", list.get(0).getParameterGroupName());
    }

    @Test
    void describeClusterParameterGroupsNotFound() {
        when(parameterGroupBackend.get("missing-pg")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.describeClusterParameterGroups("missing-pg"));
    }

    @Test
    void deleteClusterParameterGroup() {
        ClusterParameterGroup pg = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(pg));

        ClusterParameterGroup deleted = service.deleteClusterParameterGroup("my-pg");
        assertNotNull(deleted);
        assertEquals("my-pg", deleted.getParameterGroupName());
        verify(parameterGroupBackend).delete("my-pg");
        verify(parameterGroupBackend).flush();
    }

    @Test
    void deleteClusterParameterGroupNotFound() {
        when(parameterGroupBackend.get("missing-pg")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.deleteClusterParameterGroup("missing-pg"));
    }

    @Test
    void modifyClusterParameterGroup() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        ClusterParameterGroup updated = service.modifyClusterParameterGroup("my-pg",
                List.of(new Parameter("max_cursor_result_set_size", "1000")));

        assertEquals("1000", updated.getParameters().stream()
                .filter(p -> "max_cursor_result_set_size".equals(p.getParameterName()))
                .findFirst().orElseThrow().getParameterValue());
        verify(parameterGroupBackend).put(eq("my-pg"), any(ClusterParameterGroup.class));
        verify(parameterGroupBackend).flush();
    }

    @Test
    void modifyClusterParameterGroupAppendsUnknownParameter() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        ClusterParameterGroup updated = service.modifyClusterParameterGroup("my-pg",
                List.of(new Parameter("statement_timeout", "5000")));

        assertTrue(updated.getParameters().stream()
                .anyMatch(p -> "statement_timeout".equals(p.getParameterName()) && "5000".equals(p.getParameterValue())));
    }

    @Test
    void modifyClusterParameterGroupNotFound() {
        when(parameterGroupBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () ->
                service.modifyClusterParameterGroup("missing", List.of(new Parameter("x", "y"))));
    }

    @Test
    void describeClusterParametersReturnsStoredValues() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        group.setParameters(new ArrayList<>(List.of(new Parameter("statement_timeout", "5000"))));
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        List<Parameter> params = service.describeClusterParameters("my-pg");

        assertEquals(1, params.size());
        assertEquals("statement_timeout", params.get(0).getParameterName());
        assertEquals("5000", params.get(0).getParameterValue());
    }

    @Test
    void modifyClusterParameterGroupPreservesMetadata() {
        ClusterParameterGroup group = new ClusterParameterGroup("my-pg", "redshift-1.0", "custom pg");
        when(parameterGroupBackend.get("my-pg")).thenReturn(Optional.of(group));

        // Modify using 2-arg constructor (only name and value) - should preserve description and dataType
        ClusterParameterGroup updated = service.modifyClusterParameterGroup("my-pg",
                List.of(new Parameter("max_cursor_result_set_size", "1000")));

        Parameter modified = updated.getParameters().stream()
                .filter(p -> "max_cursor_result_set_size".equals(p.getParameterName()))
                .findFirst().orElseThrow();
        assertEquals("1000", modified.getParameterValue());
        assertEquals("Maximum cursor result set size", modified.getDescription());
        assertEquals("integer", modified.getDataType());
    }

    @Test
    void resetClusterParameterGroupRestoresOnlySelectedDefaults() {
        ClusterParameterGroup group = new ClusterParameterGroup("reset-pg", "redshift-1.0", "description");
        when(parameterGroupBackend.get("reset-pg")).thenReturn(Optional.of(group));
        service.modifyClusterParameterGroup("reset-pg", List.of(
                new Parameter("max_cursor_result_set_size", "1000"),
                new Parameter("wlm_json_configuration", "custom")));

        service.resetClusterParameterGroup("reset-pg", false, List.of("max_cursor_result_set_size"));

        List<Parameter> parameters = service.describeClusterParameters("reset-pg");
        assertEquals("0", parameters.get(0).getParameterValue());
        assertEquals("engine-default", parameters.get(0).getSource());
        assertEquals("integer", parameters.get(0).getDataType());
        assertEquals("false", parameters.get(1).getParameterValue());
        assertEquals("custom", parameters.get(2).getParameterValue());
        assertEquals("user", parameters.get(2).getSource());
        verify(parameterGroupBackend, times(2)).flush();
    }

    @Test
    void resetAllClusterParametersDropsOverridesOutsideDefaultCatalog() {
        ClusterParameterGroup group = new ClusterParameterGroup("reset-pg", "redshift-1.0", "description");
        group.getTags().put("owner", "analytics");
        when(parameterGroupBackend.get("reset-pg")).thenReturn(Optional.of(group));
        service.modifyClusterParameterGroup("reset-pg", List.of(
                new Parameter("max_cursor_result_set_size", "1000"), new Parameter("custom_parameter", "value")));

        service.resetClusterParameterGroup("reset-pg", true, List.of());

        List<Parameter> parameters = service.describeClusterParameters("reset-pg");
        assertEquals(3, parameters.size());
        assertEquals("0", parameters.get(0).getParameterValue());
        assertEquals("false", parameters.get(1).getParameterValue());
        assertEquals("{}", parameters.get(2).getParameterValue());
        assertTrue(parameters.stream().allMatch(parameter -> "engine-default".equals(parameter.getSource())));
        assertEquals("analytics", group.getTags().get("owner"));
        assertEquals("description", group.getDescription());
    }

    @Test
    void resetClusterParametersValidatesEveryNameBeforeMutating() {
        ClusterParameterGroup group = new ClusterParameterGroup("reset-pg", "redshift-1.0", "description");
        group.getParameters().get(0).setParameterValue("1000");
        when(parameterGroupBackend.get("reset-pg")).thenReturn(Optional.of(group));

        AwsException failure = assertThrows(AwsException.class, () -> service.resetClusterParameterGroup(
                "reset-pg", false, List.of("max_cursor_result_set_size", "missing")));

        assertEquals("InvalidParameterValue", failure.getErrorCode());
        assertEquals("1000", group.getParameters().get(0).getParameterValue());
        verify(parameterGroupBackend, never()).put(anyString(), any());
        verify(parameterGroupBackend, never()).flush();
    }

    @Test
    void resetClusterParameterGroupRejectsMissingGroup() {
        AwsException failure = assertThrows(AwsException.class,
                () -> service.resetClusterParameterGroup("missing", true, List.of()));

        assertEquals("ClusterParameterGroupNotFound", failure.getErrorCode());
        assertEquals(404, failure.getHttpStatus());
    }

    @Test
    void resetClusterParameterGroupRejectsDefaultGroup() {
        AwsException failure = assertThrows(AwsException.class, () -> service.resetClusterParameterGroup(
                RedshiftService.DEFAULT_PARAMETER_GROUP_NAME, true, List.of()));

        assertEquals("InvalidParameterValue", failure.getErrorCode());
        verify(parameterGroupBackend, never()).flush();
    }

    @Test
    void createClusterParameterGroupRejectsReservedDefaultName() {
        AwsException failure = assertThrows(AwsException.class, () -> service.createClusterParameterGroup(
                RedshiftService.DEFAULT_PARAMETER_GROUP_NAME, "redshift-1.0", "custom"));

        assertEquals("InvalidParameterValue", failure.getErrorCode());
        verify(parameterGroupBackend, never()).put(anyString(), any());
        verify(parameterGroupBackend, never()).flush();
    }

    @Test
    void describeLegacyDefaultNamedGroupPreservesStoredParameters() {
        String name = RedshiftService.DEFAULT_PARAMETER_GROUP_NAME;
        ClusterParameterGroup stored = new ClusterParameterGroup(name, "redshift-1.0", "legacy custom group");
        stored.setParameters(List.of(new Parameter("max_cursor_result_set_size", "1000")));
        when(parameterGroupBackend.get(name)).thenReturn(Optional.of(stored));

        assertEquals("legacy custom group", service.describeClusterParameterGroups(name).getFirst().getDescription());
        List<Parameter> parameters = service.describeClusterParameters(name);

        assertEquals(1, parameters.size());
        assertEquals("1000", parameters.getFirst().getParameterValue());
        assertEquals("user", parameters.getFirst().getSource());
        assertEquals("1000", stored.getParameters().getFirst().getParameterValue());
        verify(parameterGroupBackend, never()).flush();
    }

    @Test
    void legacyDefaultNamedGroupCanBeModifiedAndReset() {
        String name = RedshiftService.DEFAULT_PARAMETER_GROUP_NAME;
        ClusterParameterGroup stored = new ClusterParameterGroup(name, "redshift-1.0", "legacy custom group");
        stored.setParameters(List.of(new Parameter("max_cursor_result_set_size", "1000")));
        when(parameterGroupBackend.get(name)).thenReturn(Optional.of(stored));

        service.modifyClusterParameterGroup(name, List.of(new Parameter("max_cursor_result_set_size", "2000")));
        assertEquals("2000", service.describeClusterParameters(name).getFirst().getParameterValue());
        service.resetClusterParameterGroup(name, false, List.of("max_cursor_result_set_size"));

        assertEquals("0", service.describeClusterParameters(name).getFirst().getParameterValue());
        assertEquals("engine-default", service.describeClusterParameters(name).getFirst().getSource());
        assertEquals("legacy custom group", service.describeClusterParameterGroups(name).getFirst().getDescription());
        verify(parameterGroupBackend, times(2)).put(eq(name), any());
        verify(parameterGroupBackend, times(2)).flush();
    }

    @Test
    void implicitDefaultGroupRemainsReadOnly() {
        String name = RedshiftService.DEFAULT_PARAMETER_GROUP_NAME;

        List<Parameter> parameters = service.describeClusterParameters(name);
        AwsException failure = assertThrows(AwsException.class,
                () -> service.modifyClusterParameterGroup(name, List.of(new Parameter("max_cursor_result_set_size", "2000"))));

        assertEquals("0", parameters.getFirst().getParameterValue());
        assertEquals("engine-default", parameters.getFirst().getSource());
        assertEquals("InvalidParameterValue", failure.getErrorCode());
        verify(parameterGroupBackend, never()).put(anyString(), any());
    }

    @Test
    void resetClusterParameterGroupRequiresNamesUnlessResettingAll() {
        AwsException failure = assertThrows(AwsException.class,
                () -> service.resetClusterParameterGroup("reset-pg", false, List.of()));

        assertEquals("InvalidParameterCombination", failure.getErrorCode());
        verify(parameterGroupBackend, never()).flush();
    }

    @Test
    void resetClusterParameterGroupRejectsConflictingOptions() {
        AwsException failure = assertThrows(AwsException.class, () -> service.resetClusterParameterGroup(
                "reset-pg", true, List.of("max_cursor_result_set_size")));

        assertEquals("InvalidParameterCombination", failure.getErrorCode());
        verify(parameterGroupBackend, never()).flush();
    }

    @Test
    void resetClusterParameterGroupRejectsMoreThanTwentyParameters() {
        AwsException failure = assertThrows(AwsException.class, () -> service.resetClusterParameterGroup(
                "reset-pg", false, Collections.nCopies(21, "max_cursor_result_set_size")));

        assertEquals("InvalidParameterValue", failure.getErrorCode());
        verify(parameterGroupBackend, never()).flush();
    }

    @Test
    void describeLegacyClusterParametersInfersSourceWithoutChangingStoredValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClusterParameterGroup group = mapper.readValue("""
                {"parameterGroupName":"legacy", "parameters":[
                    {"parameterName":"max_cursor_result_set_size","parameterValue":"0"},
                    {"parameterName":"wlm_json_configuration","parameterValue":"custom"}
                ]}
                """, ClusterParameterGroup.class);
        when(parameterGroupBackend.get("legacy")).thenReturn(Optional.of(group));

        List<Parameter> parameters = service.describeClusterParameters("legacy");

        assertEquals("engine-default", parameters.get(0).getSource());
        assertEquals("user", parameters.get(1).getSource());
        assertNull(group.getParameters().get(0).getSource());
    }

    @Test
    void resetSourceSurvivesSerialization() throws Exception {
        ClusterParameterGroup group = new ClusterParameterGroup("reset-pg", "redshift-1.0", "description");
        when(parameterGroupBackend.get("reset-pg")).thenReturn(Optional.of(group));
        service.modifyClusterParameterGroup("reset-pg", List.of(new Parameter("max_cursor_result_set_size", "0")));
        assertEquals("user", service.describeClusterParameters("reset-pg").getFirst().getSource());
        service.resetClusterParameterGroup("reset-pg", true, List.of());
        ObjectMapper mapper = new ObjectMapper();

        ClusterParameterGroup reloaded = mapper.readValue(mapper.writeValueAsString(group), ClusterParameterGroup.class);

        assertEquals("engine-default", reloaded.getParameters().getFirst().getSource());
        assertEquals("0", reloaded.getParameters().getFirst().getParameterValue());
    }

    @Test
    void availableMetricTargetsListsOnlyAvailableClustersOfEveryAccountWithTheirRegion() {
        Cluster available = new Cluster();
        available.setClusterIdentifier("warehouse");
        available.setClusterStatus("available");
        available.setSnapshotRegion("eu-west-1");
        Cluster otherAccount = new Cluster();
        otherAccount.setClusterIdentifier("warehouse");
        otherAccount.setClusterStatus("available");
        Cluster creating = new Cluster();
        creating.setClusterIdentifier("new");
        creating.setClusterStatus("creating");
        Cluster deleting = new Cluster();
        deleting.setClusterIdentifier("old");
        deleting.setClusterStatus("deleting");
        when(clusterBackend.scanAllAccountEntries(any())).thenReturn(List.of(
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "warehouse", available),
                new AccountAwareStorageBackend.AccountEntry<>("222222222222", "warehouse", otherAccount),
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "new", creating),
                new AccountAwareStorageBackend.AccountEntry<>("111111111111", "old", deleting)));

        List<RedshiftMetricTarget> targets = service.availableMetricTargets();

        assertEquals(2, targets.size());
        assertEquals("111111111111", targets.get(0).accountId());
        assertEquals("eu-west-1", targets.get(0).region());
        assertEquals("111111111111:warehouse", targets.get(0).proxyKey());
        assertSame(available, targets.get(0).cluster());
        assertEquals("222222222222", targets.get(1).accountId());
        assertEquals("222222222222:warehouse", targets.get(1).proxyKey());
        assertSame(otherAccount, targets.get(1).cluster());
    }

    @Test
    void createAndListTagsForCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        service.createTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster",
                Map.of("env", "test"));

        assertEquals("test", cluster.getTags().get("env"));
        verify(clusterBackend).put(eq("my-cluster"), any(Cluster.class));
    }

    @Test
    void deleteTagsForCluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setTags(new LinkedHashMap<>(Map.of("env", "test", "team", "data")));
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        service.deleteTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster", List.of("env"));

        assertEquals(Map.of("team", "data"), cluster.getTags());
    }

    @Test
    void createTagsRejectsNonArnResourceName() {
        assertThrows(AwsException.class, () ->
                service.createTags("my-cluster", Map.of("env", "test")));
    }

    @Test
    void createTagsRejectsUnknownResourceType() {
        assertThrows(AwsException.class, () ->
                service.createTags("arn:aws:redshift:us-east-1:111111111111:reservednode:foo", Map.of("env", "test")));
    }

    @Test
    void describeTagsRejectsEventSubscriptionArnWithoutName() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.describeTags("arn:aws:redshift:us-east-1:111111111111:eventsubscription:", null, null));

        assertEquals("InvalidParameterValue", e.getErrorCode());
    }

    @Test
    void describeTagsForSpecificResource() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("my-cluster");
        cluster.setTags(new LinkedHashMap<>(Map.of("env", "test")));
        when(clusterBackend.get("my-cluster")).thenReturn(Optional.of(cluster));

        List<RedshiftService.TaggedResource> tagged =
                service.describeTags("arn:aws:redshift:us-east-1:111111111111:cluster:my-cluster", null, null);

        assertEquals(1, tagged.size());
        assertEquals("cluster", tagged.get(0).resourceType());
        assertEquals("env", tagged.get(0).tagKey());
        assertEquals("test", tagged.get(0).tagValue());
    }

    @Test
    void describeTagsScansAllResourcesOfType() {
        Cluster a = new Cluster();
        a.setClusterIdentifier("cluster-a");
        a.setTags(new LinkedHashMap<>(Map.of("env", "prod")));
        Cluster b = new Cluster();
        b.setClusterIdentifier("cluster-b");
        b.setTags(new LinkedHashMap<>());
        when(clusterBackend.scan(any())).thenReturn(List.of(a, b));
        when(snapshotBackend.scan(any())).thenReturn(List.of());
        when(parameterGroupBackend.scan(any())).thenReturn(List.of());
        when(subnetGroupBackend.scan(any())).thenReturn(List.of());

        List<RedshiftService.TaggedResource> tagged = service.describeTags(null, "cluster", null);

        assertEquals(1, tagged.size());
        assertEquals("cluster-a", extractResourceId(tagged.get(0).resourceName()));
    }

    @Test
    void createClusterSubnetGroup() {
        when(subnetGroupBackend.get("my-subnet-group")).thenReturn(Optional.empty());

        ClusterSubnetGroup group = service.createClusterSubnetGroup(
                "my-subnet-group", "test group", "vpc-123", List.of("subnet-1", "subnet-2"));

        assertEquals("my-subnet-group", group.getClusterSubnetGroupName());
        assertEquals(List.of("subnet-1", "subnet-2"), group.getSubnetIds());
        verify(subnetGroupBackend).put(eq("my-subnet-group"), any(ClusterSubnetGroup.class));
        verify(subnetGroupBackend).flush();
    }

    @Test
    void createClusterSubnetGroupAlreadyExists() {
        when(subnetGroupBackend.get("existing")).thenReturn(Optional.of(new ClusterSubnetGroup()));

        assertThrows(AwsException.class, () ->
                service.createClusterSubnetGroup("existing", "d", "vpc-1", List.of("subnet-1")));
    }

    @Test
    void describeClusterSubnetGroups() {
        ClusterSubnetGroup group = new ClusterSubnetGroup("my-group", "d", "vpc-1", List.of("subnet-1"));
        when(subnetGroupBackend.get("my-group")).thenReturn(Optional.of(group));

        List<ClusterSubnetGroup> list = service.describeClusterSubnetGroups("my-group");

        assertEquals(1, list.size());
        assertEquals("my-group", list.get(0).getClusterSubnetGroupName());
    }

    @Test
    void modifyClusterSubnetGroup() {
        ClusterSubnetGroup group = new ClusterSubnetGroup("my-group", "old", "vpc-1", List.of("subnet-1"));
        when(subnetGroupBackend.get("my-group")).thenReturn(Optional.of(group));

        ClusterSubnetGroup updated = service.modifyClusterSubnetGroup("my-group", "new", List.of("subnet-2", "subnet-3"));

        assertEquals("new", updated.getDescription());
        assertEquals(List.of("subnet-2", "subnet-3"), updated.getSubnetIds());
    }

    @Test
    void deleteClusterSubnetGroup() {
        ClusterSubnetGroup group = new ClusterSubnetGroup("my-group", "d", "vpc-1", List.of("subnet-1"));
        when(subnetGroupBackend.get("my-group")).thenReturn(Optional.of(group));

        ClusterSubnetGroup deleted = service.deleteClusterSubnetGroup("my-group");

        assertEquals("my-group", deleted.getClusterSubnetGroupName());
        verify(subnetGroupBackend).delete("my-group");
        verify(subnetGroupBackend).flush();
    }

    @Test
    void deleteClusterSubnetGroupNotFound() {
        when(subnetGroupBackend.get("missing")).thenReturn(Optional.empty());

        assertThrows(AwsException.class, () -> service.deleteClusterSubnetGroup("missing"));
    }

    @Test
    void createsSnapshotCopyGrant() {
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.empty());

        SnapshotCopyGrant grant = service.createSnapshotCopyGrant("my-grant", "key-abc", Map.of());

        assertEquals("my-grant", grant.getSnapshotCopyGrantName());
        assertEquals("key-abc", grant.getKmsKeyId());
        verify(snapshotCopyGrantBackend).put(eq("my-grant"), any(SnapshotCopyGrant.class));
        verify(snapshotCopyGrantBackend).flush();
    }

    @Test
    void createSnapshotCopyGrantDefaultsKmsKeyId() {
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.empty());

        SnapshotCopyGrant grant = service.createSnapshotCopyGrant("my-grant", null, Map.of());

        assertEquals("arn:aws:kms:us-east-1:111111111111:alias/aws/redshift", grant.getKmsKeyId());
    }

    @Test
    void createSnapshotCopyGrantStoresTags() {
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.empty());

        SnapshotCopyGrant grant = service.createSnapshotCopyGrant("my-grant", "key-abc", Map.of("env", "prod"));

        assertEquals(Map.of("env", "prod"), grant.getTags());
    }

    @Test
    void createSnapshotCopyGrantRejectsDuplicateName() {
        when(snapshotCopyGrantBackend.get("existing")).thenReturn(Optional.of(new SnapshotCopyGrant()));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.createSnapshotCopyGrant("existing", "key-abc", Map.of()));
        assertEquals("SnapshotCopyGrantAlreadyExistsFault", ex.getErrorCode());
        verify(snapshotCopyGrantBackend, never()).put(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1grant",                                                          // must start with a letter
            "Grant",                                                           // no uppercase
            "grant--copy",                                                     // no consecutive hyphens
            "grant-",                                                          // no trailing hyphen
            "g123456789012345678901234567890123456789012345678901234567890123" // 64 characters
    })
    void createSnapshotCopyGrantRejectsNamesRedshiftRejects(String name) {
        when(snapshotCopyGrantBackend.get(name)).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.createSnapshotCopyGrant(name, "key-abc", Map.of()));

        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        verify(snapshotCopyGrantBackend, never()).put(any(), any());
        verify(snapshotCopyGrantBackend, never()).flush();
    }

    @Test
    void createSnapshotCopyGrantAcceptsMaximumLengthName() {
        String name = "g12345678901234567890123456789012345678901234567890123456789012";
        assertEquals(63, name.length());
        when(snapshotCopyGrantBackend.get(name)).thenReturn(Optional.empty());

        SnapshotCopyGrant grant = service.createSnapshotCopyGrant(name, "key-abc", Map.of());

        assertEquals(name, grant.getSnapshotCopyGrantName());
        verify(snapshotCopyGrantBackend).put(eq(name), any(SnapshotCopyGrant.class));
        verify(snapshotCopyGrantBackend).flush();
    }

    @Test
    void describeSnapshotCopyGrantsByName() {
        SnapshotCopyGrant grant = new SnapshotCopyGrant("my-grant", "key-abc");
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.of(grant));

        List<SnapshotCopyGrant> list = service.describeSnapshotCopyGrants("my-grant", null, null).items();

        assertEquals(1, list.size());
        assertEquals("my-grant", list.get(0).getSnapshotCopyGrantName());
        verify(snapshotCopyGrantBackend, never()).scan(any());
    }

    @Test
    void describeSnapshotCopyGrantsReturnsAllWhenNameOmitted() {
        when(snapshotCopyGrantBackend.scan(any())).thenReturn(List.of(
                new SnapshotCopyGrant("grant-a", "key-a"),
                new SnapshotCopyGrant("grant-b", "key-b")));

        PaginatedResult<SnapshotCopyGrant> page = service.describeSnapshotCopyGrants(null, null, null);

        assertEquals(2, page.items().size());
        assertNull(page.nextToken());
    }

    @Test
    void describeSnapshotCopyGrantsRejectsMissingGrant() {
        when(snapshotCopyGrantBackend.get("missing")).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeSnapshotCopyGrants("missing", null, null));
        assertEquals("SnapshotCopyGrantNotFoundFault", ex.getErrorCode());
        // DescribeSnapshotCopyGrants documents this fault as 400, not 404.
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void describeSnapshotCopyGrantsPagesInNameOrder() {
        // 25 grants created out of order: paging must follow name order, not insertion order.
        List<SnapshotCopyGrant> stored = new ArrayList<>();
        for (int i = 25; i >= 1; i--) {
            stored.add(new SnapshotCopyGrant(String.format("grant-%02d", i), "key-" + i));
        }
        when(snapshotCopyGrantBackend.scan(any())).thenReturn(stored);

        PaginatedResult<SnapshotCopyGrant> first = service.describeSnapshotCopyGrants(null, 20, null);

        assertEquals(20, first.items().size());
        assertEquals("grant-01", first.items().get(0).getSnapshotCopyGrantName());
        assertEquals("grant-20", first.items().get(19).getSnapshotCopyGrantName());
        assertNotNull(first.nextToken());

        PaginatedResult<SnapshotCopyGrant> second =
                service.describeSnapshotCopyGrants(null, 20, first.nextToken());

        assertEquals(5, second.items().size());
        assertEquals("grant-21", second.items().get(0).getSnapshotCopyGrantName());
        assertEquals("grant-25", second.items().get(4).getSnapshotCopyGrantName());
        assertNull(second.nextToken(), "last page must not carry a marker");
    }

    @Test
    void describeSnapshotCopyGrantsRejectsMalformedMarkerNamingMarker() {
        when(snapshotCopyGrantBackend.scan(any())).thenReturn(List.of(new SnapshotCopyGrant("grant-a", "key-a")));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeSnapshotCopyGrants(null, null, "!!!"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals("Invalid Marker.", ex.getMessage());
    }

    @Test
    void describeSnapshotCopyGrantsOmitsMarkerWhenPageExactlyFits() {
        List<SnapshotCopyGrant> stored = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            stored.add(new SnapshotCopyGrant(String.format("grant-%02d", i), "key-" + i));
        }
        when(snapshotCopyGrantBackend.scan(any())).thenReturn(stored);

        PaginatedResult<SnapshotCopyGrant> page = service.describeSnapshotCopyGrants(null, 20, null);

        assertEquals(20, page.items().size());
        assertNull(page.nextToken());
    }

    @Test
    void describeSnapshotCopyGrantsRejectsMaxRecordsBelowMinimum() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeSnapshotCopyGrants(null, 19, null));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        verify(snapshotCopyGrantBackend, never()).scan(any());
    }

    @Test
    void describeSnapshotCopyGrantsRejectsMaxRecordsAboveMaximum() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeSnapshotCopyGrants(null, 101, null));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
    }

    @Test
    void describeSnapshotCopyGrantsAcceptsMarkerAlongsideName() {
        // AWS documents the two as mutually exclusive but models no error, so Floci filters
        // by name and then paginates rather than rejecting the pair.
        SnapshotCopyGrant grant = new SnapshotCopyGrant("my-grant", "key-abc");
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.of(grant));

        PaginatedResult<SnapshotCopyGrant> page =
                service.describeSnapshotCopyGrants("my-grant", null, null);

        assertEquals(1, page.items().size());
        assertNull(page.nextToken());
    }

    @Test
    void deletesSnapshotCopyGrant() {
        SnapshotCopyGrant grant = new SnapshotCopyGrant("my-grant", "key-abc");
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.of(grant));

        SnapshotCopyGrant deleted = service.deleteSnapshotCopyGrant("my-grant");

        assertEquals("my-grant", deleted.getSnapshotCopyGrantName());
        verify(snapshotCopyGrantBackend).delete("my-grant");
        verify(snapshotCopyGrantBackend).flush();
    }

    @Test
    void deleteSnapshotCopyGrantRejectsMissingGrant() {
        when(snapshotCopyGrantBackend.get("missing")).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class, () -> service.deleteSnapshotCopyGrant("missing"));
        assertEquals("SnapshotCopyGrantNotFoundFault", ex.getErrorCode());
        // DeleteSnapshotCopyGrant documents this fault as 400, not 404.
        assertEquals(400, ex.getHttpStatus());
        verify(snapshotCopyGrantBackend, never()).delete(any());
    }

    @Test
    void createTagsOnSnapshotCopyGrant() {
        SnapshotCopyGrant grant = new SnapshotCopyGrant("my-grant", "key-abc");
        when(snapshotCopyGrantBackend.get("my-grant")).thenReturn(Optional.of(grant));

        service.createTags("arn:aws:redshift:us-east-1:111111111111:snapshotcopygrant:my-grant",
                Map.of("env", "prod"));

        assertEquals(Map.of("env", "prod"),
                service.listTagsForResource("arn:aws:redshift:us-east-1:111111111111:snapshotcopygrant:my-grant"));
    }

    @Test
    void createTagsOnMissingSnapshotCopyGrantIsNotFound() {
        when(snapshotCopyGrantBackend.get("missing")).thenReturn(Optional.empty());

        AwsException ex = assertThrows(AwsException.class,
                () -> service.createTags("arn:aws:redshift:us-east-1:111111111111:snapshotcopygrant:missing",
                        Map.of("env", "prod")));
        // ResourceNotFoundFault, not the grant-specific fault: CreateTags/DeleteTags/
        // DescribeTags list ResourceNotFoundFault (404) for a missing resource and never
        // list SnapshotCopyGrantNotFoundFault, which the model pins at 400.
        assertEquals("ResourceNotFoundFault", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cluster:res", "snapshot:res-cluster/res", "parametergroup:res",
            "subnetgroup:res", "snapshotcopygrant:res", "snapshotschedule:res"})
    void tagOperationsRejectForeignPartitionAccountOrRegionArn(String resource) {
        stubLocalTaggableResources();
        String local = "arn:aws:redshift:us-east-1:111111111111:" + resource;
        String foreignAccount = "arn:aws:redshift:us-east-1:222222222222:" + resource;
        String foreignRegion = "arn:aws:redshift:eu-west-1:111111111111:" + resource;
        // Local region and account, wrong partition: only the partition check rejects it.
        String foreignPartition = "arn:aws-cn:redshift:us-east-1:111111111111:" + resource;

        for (String foreign : List.of(foreignAccount, foreignRegion, foreignPartition)) {
            AwsException create = assertThrows(AwsException.class,
                    () -> service.createTags(foreign, Map.of("env", "prod")), foreign);
            assertEquals("ResourceNotFoundFault", create.getErrorCode());
            assertEquals(404, create.getHttpStatus());
            assertThrows(AwsException.class, () -> service.deleteTags(foreign, List.of("keep")), foreign);
            assertThrows(AwsException.class, () -> service.listTagsForResource(foreign), foreign);
            assertThrows(AwsException.class, () -> service.describeTags(foreign, null, null), foreign);
        }
        assertEquals(Map.of("keep", "me"), service.listTagsForResource(local),
                "a foreign ARN changed the local resource's tags");

        // Control: the same resource is reachable through the caller's own ARN, so the
        // rejections above are about scope, not a stub that never resolves.
        service.createTags(local, Map.of("env", "prod"));
        assertEquals(Map.of("keep", "me", "env", "prod"), service.listTagsForResource(local));
    }

    /** Stubs one local resource of every taggable type, each named "res" and tagged keep=me. */
    private void stubLocalTaggableResources() {
        Map<String, String> current = new LinkedHashMap<>(Map.of("keep", "me"));
        Cluster cluster = new Cluster();
        Snapshot snapshot = new Snapshot();
        ClusterParameterGroup parameterGroup = new ClusterParameterGroup();
        ClusterSubnetGroup subnetGroup = new ClusterSubnetGroup();
        SnapshotCopyGrant grant = new SnapshotCopyGrant("res", "key");
        SnapshotSchedule schedule = new SnapshotSchedule();
        cluster.setTags(current);
        snapshot.setTags(current);
        parameterGroup.setTags(current);
        subnetGroup.setTags(current);
        grant.setTags(current);
        schedule.setTags(current);
        when(clusterBackend.get("res")).thenReturn(Optional.of(cluster));
        when(snapshotBackend.get("res")).thenReturn(Optional.of(snapshot));
        when(parameterGroupBackend.get("res")).thenReturn(Optional.of(parameterGroup));
        when(subnetGroupBackend.get("res")).thenReturn(Optional.of(subnetGroup));
        when(snapshotCopyGrantBackend.get("res")).thenReturn(Optional.of(grant));
        when(snapshotScheduleBackend.get("res")).thenReturn(Optional.of(schedule));
    }

    @Test
    void snapshotScheduleRejectsOutOfRangeCronFields() {
        when(snapshotScheduleBackend.get("invalid-cron")).thenReturn(Optional.empty());

        AwsException failure = assertThrows(AwsException.class, () -> service.createSnapshotSchedule(
                "invalid-cron", null, List.of("cron(60 12 *)"), Map.of()));

        assertEquals("InvalidSchedule", failure.getErrorCode());
    }

    private static String extractResourceId(String arn) {
        String resource = arn.substring(arn.lastIndexOf(':') + 1);
        return resource.contains("/") ? resource.substring(resource.lastIndexOf('/') + 1) : resource;
    }

    // ── passwordValidatorFor / GetClusterCredentials broker wiring ─────────────

    private void seedCluster(String accountId, String clusterId) {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier(clusterId);
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("SecretPass1");
        when(clusterBackend.getForAccount(accountId, clusterId)).thenReturn(Optional.of(cluster));
    }

    @Test
    void passwordValidatorAcceptsMasterPair() {
        seedCluster("acc", "c1");

        PasswordValidator validator =
                service.passwordValidatorForTesting("acc", "c1");

        assertEquals(PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                validator.validate("admin", "SecretPass1"));
    }

    @Test
    void passwordValidatorRejectsEverythingWhenClusterRowIsAbsent() {
        when(clusterBackend.getForAccount("acc", "gone")).thenReturn(Optional.empty());

        PasswordValidator validator = service.passwordValidatorForTesting("acc", "gone");

        assertEquals(PasswordValidator.AuthResult.REJECT, validator.validate("admin", "SecretPass1"));
        assertEquals(PasswordValidator.AuthResult.REJECT, validator.validate("analyst", "anything"));
    }

    @Test
    void passwordValidatorRejectsMasterUserWithWrongPassword() {
        seedCluster("acc", "c1");

        PasswordValidator validator = service.passwordValidatorForTesting("acc", "c1");

        assertEquals(PasswordValidator.AuthResult.REJECT,
                validator.validate("admin", "not-the-master-password"));
    }

    @Test
    void passwordValidatorAcceptsLiveBrokerCredentialAsMasterEquivalent() {
        seedCluster("acc", "c1");
        TempCredential cred = credentialBroker.issue("acc", "c1", "analyst", List.of(), 900);
        PasswordValidator validator =
                service.passwordValidatorForTesting("acc", "c1");

        assertEquals(PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                validator.validate("analyst", cred.password()));
    }

    @Test
    void passwordValidatorRejectsKnownBrokerUserWithWrongPassword() {
        seedCluster("acc", "c1");
        credentialBroker.issue("acc", "c1", "analyst", List.of(), 900);
        PasswordValidator validator =
                service.passwordValidatorForTesting("acc", "c1");

        assertEquals(PasswordValidator.AuthResult.REJECT,
                validator.validate("analyst", "nope"));
    }

    @Test
    void passwordValidatorPassesThroughUnknownUser() {
        seedCluster("acc", "c1");
        PasswordValidator validator =
                service.passwordValidatorForTesting("acc", "c1");

        assertEquals(PasswordValidator.AuthResult.PASSTHROUGH,
                validator.validate("someone-else", "whatever"));
    }

    @Test
    void reservedProxyPortsAreSharedBetweenAllCallersAndReusableOnceReleased() {
        int first = service.reserveProxyPort();
        int second = service.reserveProxyPort();

        assertEquals(7100, first);
        assertEquals(7101, second);
        assertFalse(service.reserveProxyPort(first), "a port already handed out cannot be reserved again");
        assertTrue(service.reserveProxyPort(7150), "a specific free port, such as a persisted one, can be reserved");
        assertEquals(7102, service.reserveProxyPort(), "reserving a specific port does not disturb the sequence");

        service.releaseProxyPort(first);
        assertTrue(service.reserveProxyPort(first));
        assertFalse(service.reserveProxyPort(0), "port 0 is never a real port");
    }

    @Test
    void advertisedEndpointUsesTheConfiguredHostAndTheGivenPort() {
        Endpoint endpoint = service.advertisedEndpoint(7123);

        assertEquals("localhost", endpoint.getAddress());
        assertEquals(7123, endpoint.getPort());
    }
}
