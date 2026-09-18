package io.github.hectorvent.floci.services.timestreaminfluxdb.container;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.timestreaminfluxdb.container.TimestreamInfluxDbContainerManager.InfluxDbEndpoint;
import io.github.hectorvent.floci.services.timestreaminfluxdb.container.TimestreamInfluxDbContainerManager.InfluxDbSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimestreamInfluxDbContainerManagerTest {

    private ContainerLifecycleManager lifecycleManager;
    private ContainerBuilder.Builder builder;
    private PortAllocator portAllocator;
    private TimestreamInfluxDbContainerManager manager;

    @BeforeEach
    void setUp() {
        lifecycleManager = mock(ContainerLifecycleManager.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ContainerSpec.class));
        portAllocator = mock(PortAllocator.class);
        when(portAllocator.allocate(8086, 8185)).thenReturn(8090);

        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.TimestreamInfluxDbServiceConfig influx = mock(EmulatorConfig.TimestreamInfluxDbServiceConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.services()).thenReturn(services);
        when(config.storage()).thenReturn(storage);
        when(services.timestreamInfluxdb()).thenReturn(influx);
        when(influx.defaultImage()).thenReturn("influxdb:2.7");
        when(influx.hostPortBase()).thenReturn(8086);
        when(influx.hostPortMax()).thenReturn(8185);
        when(influx.dockerNetwork()).thenReturn(Optional.empty());

        manager = new TimestreamInfluxDbContainerManager(containerBuilder, lifecycleManager,
                mock(ContainerDetector.class), portAllocator, config);
    }

    @Test
    void startRunsImageSetupPublishesTheHttpPortAndLabelsTheContainer() {
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("container-id",
                Map.of(8086, new EndpointInfo("localhost", 8090))));

        InfluxDbEndpoint endpoint = manager.start("abc1234567", "000000000000", "us-east-1",
                new InfluxDbSetup("admin", "password123", "acme", "metrics"), Map.of("INFLUXD_LOG_LEVEL", "debug"));

        assertEquals(new InfluxDbEndpoint("container-id", "localhost", 8090), endpoint);
        verify(builder).withEnv("DOCKER_INFLUXDB_INIT_MODE", "setup");
        verify(builder).withEnv("DOCKER_INFLUXDB_INIT_USERNAME", "admin");
        verify(builder).withEnv("DOCKER_INFLUXDB_INIT_PASSWORD", "password123");
        verify(builder).withEnv("DOCKER_INFLUXDB_INIT_ORG", "acme");
        verify(builder).withEnv("DOCKER_INFLUXDB_INIT_BUCKET", "metrics");
        verify(builder).withEnv("INFLUXD_LOG_LEVEL", "debug");
        verify(builder).withPortBinding(8086, 8090);
        verify(builder).withNamedVolume("floci-timestream-influxdb-abc1234567", "/var/lib/influxdb2");
        verify(builder).withNamedVolume("floci-timestream-influxdb-abc1234567-config", "/etc/influxdb2");
        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "timestream-influxdb",
                "io.floci.resource-id", "abc1234567",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
    }

    @Test
    void restartingOnExistingVolumesSkipsSetup() {
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("container-id",
                Map.of(8086, new EndpointInfo("localhost", 8090))));

        manager.start("abc1234567", "000000000000", "us-east-1", null, Map.of());

        verify(builder, never()).withEnv("DOCKER_INFLUXDB_INIT_MODE", "setup");
    }

    @Test
    void failedStartRemovesTheContainerAndReleasesThePort() {
        when(lifecycleManager.createAndStart(any())).thenThrow(new RuntimeException("port already allocated"));

        assertThrows(RuntimeException.class, () -> manager.start("abc1234567", "000000000000", "us-east-1",
                new InfluxDbSetup("admin", "password123", "acme", "metrics"), Map.of()));

        verify(portAllocator).release(8090);
        verify(lifecycleManager, Mockito.times(2)).removeIfExists("floci-timestream-influxdb-abc1234567");
    }

    @Test
    void stopReleasesThePublishedPort() {
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("container-id",
                Map.of(8086, new EndpointInfo("localhost", 8090))));
        manager.start("abc1234567", "000000000000", "us-east-1", null, Map.of());

        manager.stop("abc1234567", "container-id");

        verify(lifecycleManager).stopAndRemove("container-id", null);
        verify(portAllocator).release(8090);
    }

    @Test
    void parameterGroupMembersMapToInfluxdEnvironmentVariables() throws Exception {
        Map<String, String> environment = TimestreamInfluxDbContainerManager.engineEnvironment(new ObjectMapper().readTree("""
                {"fluxLogEnabled":true,"queryConcurrency":10,"influxqlMaxSelectBuckets":5,
                 "storageCacheSnapshotWriteColdDuration":{"durationType":"days","value":2},
                 "httpWriteTimeout":{"durationType":"milliseconds","value":250}}
                """));

        assertEquals(Map.of(
                "INFLUXD_FLUX_LOG_ENABLED", "true",
                "INFLUXD_QUERY_CONCURRENCY", "10",
                "INFLUXD_INFLUXQL_MAX_SELECT_BUCKETS", "5",
                "INFLUXD_STORAGE_CACHE_SNAPSHOT_WRITE_COLD_DURATION", "48h",
                "INFLUXD_HTTP_WRITE_TIMEOUT", "250ms"), environment);
    }
}
