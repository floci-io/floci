package io.github.hectorvent.floci.services.rds.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.github.dockerjava.api.model.Bind;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsContainerManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void postgresInitSqlCreatesRdsIamRoleWhenMissing() {
        String sql = RdsContainerManager.postgresIamRoleInitSql();

        assertTrue(sql.contains("pg_roles"));
        assertTrue(sql.contains("rolname = 'rds_iam'"));
        assertTrue(sql.contains("CREATE ROLE rds_iam"));
    }

    @Test
    void postgres18UsesParentDataMount() {
        assertEquals("/var/lib/postgresql",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "postgres:18.4-alpine"));
        assertEquals("/var/lib/postgresql",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "registry.example.com/postgres:18-alpine"));
        assertEquals("/var/lib/postgresql",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES,
                        "postgres:18.4-alpine@sha256:1234567890abcdef"));
        assertEquals("/var/lib/postgresql",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES,
                        "localhost:5000/postgres:18.4-alpine"));
    }

    @Test
    void olderPostgresUsesLegacyDataMount() {
        assertEquals("/var/lib/postgresql/data",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "postgres:16-alpine"));
        assertEquals("/var/lib/postgresql/data",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "postgres:17.6"));
        assertEquals("/var/lib/postgresql/data",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "postgres:latest"));
        assertEquals("/var/lib/postgresql/data",
                RdsContainerManager.engineDefaultDataPath(DatabaseEngine.POSTGRES, "localhost:5000/postgres"));
    }

    @Test
    void containerizedHostPathModeDoesNotCreateHostDataDirectory() {
        Path hostRoot = tempDir.resolve("host-root");
        Path dbPath = hostRoot.resolve("rds").resolve("db1");
        EmulatorConfig config = config(hostRoot);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager, new ContainerLifecycleManager.ContainerInfo(
                "container-id", Map.of(3306, new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");

        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class)),
                lifecycleManager,
                logStreamer,
                containerDetector,
                config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));

        manager.start("db1", "vol1", DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db");

        assertFalse(Files.exists(dbPath));
        var spec = org.mockito.ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(spec.capture());
        Bind bind = spec.getValue().binds().getFirst();
        assertEquals(dbPath.toString(), bind.getPath());
        assertEquals("/var/lib/mysql", bind.getVolume().getPath());
    }

    @Test
    void legacyForeignAccountRuntimeReusesBareHostPath() {
        Path hostRoot = tempDir.resolve("host-root");
        Path dbPath = hostRoot.resolve("rds").resolve("db1");
        EmulatorConfig config = config(hostRoot);
        when(config.defaultAccountId()).thenReturn("000000000000");
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        when(containerDetector.isRunningInContainer()).thenReturn(true);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager,
                new ContainerLifecycleManager.ContainerInfo(
                        "container-id", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, containerDetector, config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));

        String runtimeId = "arn:aws:rds:us-west-2:222222222222:db:db1";
        manager.start(runtimeId, "db1", null,
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db");

        var spec = org.mockito.ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).create(spec.capture());
        assertEquals(dbPath.toString(), spec.getValue().binds().getFirst().getPath());
        assertEquals("floci-rds-db1", spec.getValue().name());
        verify(logStreamer).attachForAccount(
                "222222222222", "container-id", "/aws/rds/instance/db1/error",
                "log-stream", "us-west-2", "rds:" + runtimeId);
    }

    @Test
    void sameNamedResourcesKeepIndependentActiveContainerEntries() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager,
                new ContainerLifecycleManager.ContainerInfo(
                        "container-a", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))),
                new ContainerLifecycleManager.ContainerInfo(
                        "container-b", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3307))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, containerDetector, config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));

        manager.start("arn:aws:rds:us-east-1:111111111111:db:db1", "db1",
                "db-RESOURCE-A", "floci-rds-volume-a", DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db");
        manager.start("arn:aws:rds:us-east-1:222222222222:db:db1", "db1",
                "db-RESOURCE-B", "floci-rds-volume-b", DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db");

        manager.stopAll();

        verify(lifecycleManager, times(2)).stopAndRemoveStrict(
                any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void sameNamedResourcesWithEqualVolumeIdsUseIndependentNamedVolumes() {
        EmulatorConfig config = config(Path.of("data"));
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().mode()).thenReturn("memory");
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager,
                new ContainerLifecycleManager.ContainerInfo(
                        "container-a", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))),
                new ContainerLifecycleManager.ContainerInfo(
                        "container-b", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3307))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, containerDetector, config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
        String runtimeA = "arn:aws:rds:us-east-1:111111111111:db:db1";
        String runtimeB = "arn:aws:rds:us-east-1:222222222222:db:db1";
        String volumeA = "floci-rds-111111111111-us-east-1-db_db1-shared-volume";
        String volumeB = "floci-rds-222222222222-us-east-1-db_db1-shared-volume";

        RdsContainerHandle handleA = manager.start(
                runtimeA, "db1", "db-RESOURCE-A", volumeA, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db");
        manager.start(runtimeB, "db1", "db-RESOURCE-B", volumeB, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db");

        verify(lifecycleManager).ensureVolume(volumeA);
        verify(lifecycleManager).ensureVolume(volumeB);
        manager.stop(handleA);
        manager.removeVolume(runtimeA, "db-RESOURCE-A", volumeA);
        verify(lifecycleManager).removeVolumeStrict(volumeA);
        verify(lifecycleManager, never()).removeVolumeStrict(volumeB);
    }

    @Test
    void competingLegacyHostPathClaimFailsBeforeTouchingDocker() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager,
                new ContainerLifecycleManager.ContainerInfo(
                        "container-a", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, containerDetector, config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));

        manager.start("arn:aws:rds:us-east-1:111111111111:db:same", "same", null,
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db");

        assertThrows(IllegalStateException.class, () -> manager.start(
                "arn:aws:rds:us-east-1:111111111111:cluster:same", "same", null,
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db"));
        verify(lifecycleManager, times(1)).removeIfExistsStrict("floci-rds-same");
        verify(lifecycleManager, times(1)).create(any());
    }

    @Test
    void competingLegacyContainerNameClaimFailsBeforeTouchingDocker() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager,
                new ContainerLifecycleManager.ContainerInfo(
                        "container-a", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
        String sharedLegacyName = "floci-rds-shared-volume";

        manager.start("arn:aws:rds:us-east-1:111111111111:db:first", "first",
                "first-storage", sharedLegacyName, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db");

        assertThrows(IllegalStateException.class, () -> manager.start(
                "arn:aws:rds:us-east-1:111111111111:db:second", "second",
                "second-storage", sharedLegacyName, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db"));
        verify(lifecycleManager, times(1)).removeIfExistsStrict(sharedLegacyName);
        verify(lifecycleManager, times(1)).create(any());
    }

    @Test
    void activeNamedVolumeIsNotRemovedUntilOwnerStops() {
        EmulatorConfig config = config(Path.of("data"));
        when(config.storage().mode()).thenReturn("memory");
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager,
                new ContainerLifecycleManager.ContainerInfo(
                        "container-a", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, containerDetector, config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
        String runtime = "arn:aws:rds:us-east-1:111111111111:db:db1";
        String volume = "floci-rds-legacy-volume";

        RdsContainerHandle handle = manager.start(
                runtime, "db1", "db1", volume, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db");
        assertThrows(IllegalStateException.class, () ->
                manager.removeVolume(runtime, "db1", volume));
        verify(lifecycleManager, never()).removeVolumeStrict(volume);

        manager.stop(handle);
        manager.removeVolume(runtime, "db1", volume);
        verify(lifecycleManager).removeVolumeStrict(volume);
    }

    @Test
    void failedStartReleasesClaimsForSameRuntimeRetry() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any()))
                .thenThrow(new IllegalStateException("Docker unavailable"))
                .thenReturn("container-b");
        when(lifecycleManager.startCreated(
                org.mockito.ArgumentMatchers.eq("container-b"), any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo(
                        "container-b", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, containerDetector, config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));

        String runtimeId = "arn:aws:rds:us-east-1:111111111111:db:legacy";
        assertThrows(IllegalStateException.class, () -> manager.start(
                runtimeId, "legacy", null,
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db"));
        RdsContainerHandle retried = manager.start(
                runtimeId, "legacy", null,
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db");

        assertEquals("container-b", retried.getContainerId());
        verify(lifecycleManager, times(2)).removeIfExistsStrict("floci-rds-legacy");
    }

    @Test
    void postCreateFailureCleansContainerBeforeReleasingOwnership() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager,
                new ContainerLifecycleManager.ContainerInfo(
                        "container-a", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))),
                new ContainerLifecycleManager.ContainerInfo(
                        "container-b", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3307))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        when(logStreamer.attachForAccount(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("log attach failed"))
                .thenReturn(null);
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
        String volume = "floci-rds-legacy-volume";

        assertThrows(IllegalStateException.class, () -> manager.start(
                "arn:aws:rds:us-east-1:111111111111:db:legacy", "legacy",
                "legacy", volume, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db"));
        RdsContainerHandle retried = manager.start(
                "arn:aws:rds:us-east-1:222222222222:db:legacy", "legacy",
                "legacy", volume, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db");

        assertEquals("container-b", retried.getContainerId());
        verify(lifecycleManager).stopAndRemoveStrict(
                org.mockito.ArgumentMatchers.eq("container-a"),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void failedPostCreateCleanupRetainsContainerIdentityForRuntimeRetry() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager,
                new ContainerLifecycleManager.ContainerInfo(
                        "container-a", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        when(logStreamer.attachForAccount(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("log attach failed"));
        doThrow(new IllegalStateException("Docker cleanup failed"))
                .doNothing()
                .when(lifecycleManager).stopAndRemoveStrict(any(), any());
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
        String runtimeId = "arn:aws:rds:us-east-1:111111111111:db:legacy";

        assertThrows(IllegalStateException.class, () -> manager.start(
                runtimeId, "legacy", "legacy", "floci-rds-legacy-volume",
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db"));

        assertEquals("container-a", manager.getActiveHandle(runtimeId).getContainerId());
        manager.stopByRuntimeId(runtimeId);
        assertNull(manager.getActiveHandle(runtimeId));
        verify(lifecycleManager, times(2)).stopAndRemoveStrict(any(), any());
    }

    @Test
    void failedStartCleanupRetainsCreatedContainerIdentityAndOwnership() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any())).thenReturn("created-container");
        when(lifecycleManager.startCreated(
                org.mockito.ArgumentMatchers.eq("created-container"), any()))
                .thenThrow(new IllegalStateException("Docker start failed"));
        doThrow(new IllegalStateException("Docker cleanup failed"))
                .doNothing()
                .when(lifecycleManager).stopAndRemoveStrict(any(), any());
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
        String runtimeId = "arn:aws:rds:us-east-1:111111111111:db:legacy";

        assertThrows(IllegalStateException.class, () -> manager.start(
                runtimeId, "legacy", "legacy", "floci-rds-legacy-volume",
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db"));

        assertEquals("created-container", manager.getActiveHandle(runtimeId).getContainerId());
        assertThrows(IllegalStateException.class, () -> manager.start(
                "arn:aws:rds:us-east-1:222222222222:db:legacy", "legacy",
                "legacy", "floci-rds-legacy-volume", DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db"));
        verify(lifecycleManager, times(1)).create(any());

        manager.stopByRuntimeId(runtimeId);
        assertNull(manager.getActiveHandle(runtimeId));
    }

    @Test
    void failedStaleRemovalRetainsContainerNameAndOwnership() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        doThrow(new IllegalStateException("stale removal failed"))
                .when(lifecycleManager).removeIfExistsStrict("floci-rds-legacy-volume");
        doThrow(new IllegalStateException("cleanup retry failed"))
                .doNothing()
                .when(lifecycleManager).stopAndRemoveStrict(any(), any());
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
        String runtimeId = "arn:aws:rds:us-east-1:111111111111:db:legacy";

        assertThrows(IllegalStateException.class, () -> manager.start(
                runtimeId, "legacy", "legacy", "floci-rds-legacy-volume",
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db"));

        assertEquals("floci-rds-legacy-volume",
                manager.getActiveHandle(runtimeId).getContainerId());
        verify(lifecycleManager, never()).create(any());
        assertThrows(IllegalStateException.class, () -> manager.start(
                "arn:aws:rds:us-east-1:222222222222:db:legacy", "legacy",
                "legacy", "floci-rds-legacy-volume", DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db"));

        manager.stopByRuntimeId(runtimeId);
        assertNull(manager.getActiveHandle(runtimeId));
    }

    @Test
    void inProgressRuntimeClaimRejectsConcurrentDuplicateStart() throws Exception {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        CountDownLatch createEntered = new CountDownLatch(1);
        CountDownLatch allowCreate = new CountDownLatch(1);
        when(lifecycleManager.create(any())).thenAnswer(invocation -> {
            createEntered.countDown();
            if (!allowCreate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out waiting to create");
            }
            return "container-a";
        });
        when(lifecycleManager.startCreated(
                org.mockito.ArgumentMatchers.eq("container-a"), any()))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo(
                        "container-a", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
        String runtimeId = "arn:aws:rds:us-east-1:111111111111:db:db1";

        CompletableFuture<RdsContainerHandle> first = CompletableFuture.supplyAsync(() ->
                manager.start(runtimeId, "db1", "storage-a", "floci-rds-volume-a",
                        DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db"));
        assertTrue(createEntered.await(5, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> manager.start(
                runtimeId, "db1", "storage-b", "floci-rds-volume-b",
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db"));
        allowCreate.countDown();

        RdsContainerHandle handle = first.get(5, TimeUnit.SECONDS);
        verify(lifecycleManager, times(1)).create(any());
        manager.stop(handle);
    }

    @Test
    void stopAllContinuesAfterIndividualCleanupFailure() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager,
                new ContainerLifecycleManager.ContainerInfo(
                        "container-a", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))),
                new ContainerLifecycleManager.ContainerInfo(
                        "container-b", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db2", 3307))));
        doAnswer(invocation -> {
            if ("container-a".equals(invocation.getArgument(0))) {
                throw new IllegalStateException("Docker cleanup failed");
            }
            return null;
        }).when(lifecycleManager).stopAndRemoveStrict(any(), any());
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
        String runtimeA = "arn:aws:rds:us-east-1:111111111111:db:a";
        String runtimeB = "arn:aws:rds:us-east-1:111111111111:db:b";
        manager.start(runtimeA, "a", "storage-a", "floci-rds-volume-a",
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db");
        manager.start(runtimeB, "b", "storage-b", "floci-rds-volume-b",
                DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db");

        assertDoesNotThrow(manager::stopAll);

        verify(lifecycleManager).stopAndRemoveStrict(
                org.mockito.ArgumentMatchers.eq("container-a"), any());
        verify(lifecycleManager).stopAndRemoveStrict(
                org.mockito.ArgumentMatchers.eq("container-b"), any());
        assertEquals("container-a", manager.getActiveHandle(runtimeA).getContainerId());
        assertNull(manager.getActiveHandle(runtimeB));
    }

    @Test
    void failedStopRetainsOwnershipAndActiveHandle() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager,
                new ContainerLifecycleManager.ContainerInfo(
                        "container-a", Map.of(3306,
                        new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, logStreamer, mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));
        String volume = "floci-rds-legacy-volume";
        String runtimeId = "arn:aws:rds:us-east-1:111111111111:db:legacy";
        RdsContainerHandle handle = manager.start(
                runtimeId, "legacy",
                "legacy", volume, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db");
        doThrow(new IllegalStateException("Docker stop failed"))
                .doNothing()
                .when(lifecycleManager).stopAndRemoveStrict(any(), any());

        assertThrows(IllegalStateException.class, () -> manager.stop(handle));
        assertThrows(IllegalStateException.class, () -> manager.start(
                "arn:aws:rds:us-east-1:222222222222:db:legacy", "legacy",
                "legacy", volume, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db"));
        verify(lifecycleManager, times(1)).create(any());

        manager.stopByRuntimeId(runtimeId);
        RdsContainerHandle retried = manager.start(
                "arn:aws:rds:us-east-1:222222222222:db:legacy", "legacy",
                "legacy", volume, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db");

        assertEquals("container-a", retried.getContainerId());
        verify(lifecycleManager, times(2)).create(any());
        verify(lifecycleManager, times(2)).stopAndRemoveStrict(any(), any());
    }

    @Test
    void persistedStorageIdentityRejectsPathTraversal() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class),
                        mock(EmbeddedDnsServer.class)),
                lifecycleManager, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));

        assertThrows(IllegalArgumentException.class, () -> manager.start(
                "arn:aws:rds:us-east-1:111111111111:db:db1", "db1", "../escape",
                "floci-rds-safe", DatabaseEngine.MYSQL, "mysql:8.0",
                "root", "password", "db"));
        verify(lifecycleManager, never()).removeIfExistsStrict(any());
    }

    @Test
    void childContainerNameUsesVolumeIdWhenAvailable() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager, new ContainerLifecycleManager.ContainerInfo(
                "container-id", Map.of(3306, new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");

        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class)),
                lifecycleManager,
                logStreamer,
                containerDetector,
                config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));

        manager.start("db1", "volume-a", DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db");

        var spec = org.mockito.ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).removeIfExistsStrict("floci-rds-volume-a");
        verify(lifecycleManager).create(spec.capture());
        assertEquals("floci-rds-volume-a", spec.getValue().name());
    }

    @Test
    void childContainerNameFallsBackToInstanceIdWithoutVolumeId() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        stubStarts(lifecycleManager, new ContainerLifecycleManager.ContainerInfo(
                "container-id", Map.of(3306, new ContainerLifecycleManager.EndpointInfo("db1", 3306))));
        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        lenient().when(logStreamer.generateLogStreamName(any())).thenReturn("log-stream");

        RdsContainerManager manager = new RdsContainerManager(
                new ContainerBuilder(config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class)),
                lifecycleManager,
                logStreamer,
                containerDetector,
                config,
                new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class));

        RdsContainerHandle handle = manager.start(
                null, "db1", null, DatabaseEngine.MYSQL,
                "mysql:8.0", "root", "password", "db");

        var spec = org.mockito.ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).removeIfExistsStrict("floci-rds-db1");
        verify(lifecycleManager).create(spec.capture());
        assertEquals("floci-rds-db1", spec.getValue().name());
        assertEquals("db1", handle.getRuntimeId());
        manager.stop(handle);
        verify(lifecycleManager).stopAndRemoveStrict(
                org.mockito.ArgumentMatchers.eq("container-id"),
                org.mockito.ArgumentMatchers.isNull());
    }

    private static EmulatorConfig config(Path hostRoot) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.RdsServiceConfig rds = mock(EmulatorConfig.RdsServiceConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.services()).thenReturn(services);
        when(services.rds()).thenReturn(rds);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(rds.dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker()).thenReturn(docker);
        when(docker.resourceNamespace()).thenReturn(Optional.empty());
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        when(config.storage()).thenReturn(storage);
        when(storage.hostPersistentPath()).thenReturn(hostRoot.toString());
        return config;
    }

    private static void stubStarts(
            ContainerLifecycleManager lifecycleManager,
            ContainerLifecycleManager.ContainerInfo... infos) {
        int[] next = {0};
        when(lifecycleManager.create(any())).thenAnswer(invocation ->
                infos[Math.min(next[0]++, infos.length - 1)].containerId());
        when(lifecycleManager.startCreated(any(), any())).thenAnswer(invocation -> {
            String containerId = invocation.getArgument(0);
            for (ContainerLifecycleManager.ContainerInfo info : infos) {
                if (info.containerId().equals(containerId)) {
                    return info;
                }
            }
            return infos[infos.length - 1];
        });
    }
}
