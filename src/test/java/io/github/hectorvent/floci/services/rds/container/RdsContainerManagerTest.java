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

import com.github.dockerjava.api.model.Bind;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
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
        verify(lifecycleManager).createAndStart(spec.capture());
        Bind bind = spec.getValue().binds().getFirst();
        assertEquals(dbPath.toString(), bind.getPath());
        assertEquals("/var/lib/mysql", bind.getVolume().getPath());
    }

    @Test
    void childContainerNameUsesVolumeIdWhenAvailable() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
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
        verify(lifecycleManager).removeIfExists("floci-rds-volume-a");
        verify(lifecycleManager).createAndStart(spec.capture());
        assertEquals("floci-rds-volume-a", spec.getValue().name());
    }

    @Test
    void childContainerNameFallsBackToInstanceIdWithoutVolumeId() {
        EmulatorConfig config = config(tempDir.resolve("host-root"));
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo(
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

        manager.start("db1", null, DatabaseEngine.MYSQL, "mysql:8.0", "root", "password", "db");

        var spec = org.mockito.ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager).removeIfExists("floci-rds-db1");
        verify(lifecycleManager).createAndStart(spec.capture());
        assertEquals("floci-rds-db1", spec.getValue().name());
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
}
