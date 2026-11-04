package io.github.hectorvent.floci.services.memorydb.container;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryDbContainerNamingTest {

    @Test
    void logicalDefaultNamesKeepLogsAndContainersWhileRuntimeKeysStayScoped() throws Exception {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        ContainerBuilder containers = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containers.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("valkey/valkey:8"));
        ContainerLogStreamer logs = mock(ContainerLogStreamer.class);
        when(logs.generateLogStreamName(anyString())).thenReturn("engine");
        MemoryDbContainerManager manager = manager(containers, lifecycle, logs);
        String defaultIdentity = "000000000000-us-east-1-cache";
        String collisionIdentity = "000000000000-us-east-1-111111111111-eu-west-1-same";
        String foreignIdentity = "111111111111-eu-west-1-same";
        try (ServerSocket backend = new ServerSocket(0);
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> responder = executor.submit(() -> {
                for (int i = 0; i < 3; i++) {
                    try (Socket socket = backend.accept()) {
                        socket.setSoTimeout(5000);
                        socket.getInputStream().read(new byte[64]);
                        socket.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    }
                }
                return null;
            });
            when(lifecycle.createAndStartEphemeral(any())).thenReturn(
                    new ContainerLifecycleManager.ContainerInfo("default-cid", Map.of(6379,
                            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", backend.getLocalPort()))),
                    new ContainerLifecycleManager.ContainerInfo("collision-cid", Map.of(6379,
                            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", backend.getLocalPort()))),
                    new ContainerLifecycleManager.ContainerInfo("foreign-cid", Map.of(6379,
                            new ContainerLifecycleManager.EndpointInfo("127.0.0.1", backend.getLocalPort()))));
            try {
                MemoryDbContainerHandle first = manager.tryStart(defaultIdentity, "valkey/valkey:8", null,
                        "000000000000", "us-east-1", "cache");
                MemoryDbContainerHandle collision = manager.tryStart(collisionIdentity, "valkey/valkey:8", null,
                        "000000000000", "us-east-1", "111111111111-eu-west-1-same");
                MemoryDbContainerHandle foreign = manager.tryStart(foreignIdentity, "valkey/valkey:8", null,
                        "111111111111", "eu-west-1", "same");
                assertEquals(defaultIdentity, first.getClusterName());
                assertEquals(collisionIdentity, collision.getClusterName());
                assertEquals(foreignIdentity, foreign.getClusterName());
                ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
                verify(builder, times(3)).withName(names.capture());
                assertEquals(List.of("floci-aws-memorydb-cache",
                        "floci-aws-memorydb-111111111111-eu-west-1-same",
                        "floci-aws-memorydb-111111111111_eu-west-1_same"), names.getAllValues());
                verify(logs).attach("default-cid", "/aws/memorydb/cluster/cache/engine-log", "engine",
                        "us-east-1", "memorydb:" + defaultIdentity);
                verify(logs).attach("collision-cid",
                        "/aws/memorydb/cluster/111111111111-eu-west-1-same/engine-log", "engine",
                        "us-east-1", "memorydb:" + collisionIdentity);
                verify(logs).attach("foreign-cid",
                        "/aws/memorydb/cluster/111111111111-eu-west-1-same/engine-log", "engine",
                        "eu-west-1", "memorydb:" + foreignIdentity);
                responder.get(5, TimeUnit.SECONDS);
            } finally {
                backend.close();
                manager.stopAll();
            }
        }
        verify(lifecycle).stopAndRemoveStrict("default-cid", null, 5, true);
        verify(lifecycle).stopAndRemoveStrict("collision-cid", null, 5, true);
        verify(lifecycle).stopAndRemoveStrict("foreign-cid", null, 5, true);
    }

    @Test
    void failedCreationRollbackUsesTheSameDefaultAndForeignContainerNames() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        ContainerBuilder containers = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containers.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("valkey/valkey:8"));
        RuntimeException failure = new IllegalStateException("creation failed before registration");
        when(lifecycle.createAndStartEphemeral(any())).thenThrow(failure);
        MemoryDbContainerManager manager = manager(containers, lifecycle, mock(ContainerLogStreamer.class));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> manager.start("000000000000-us-east-1-cache", "valkey/valkey:8", null,
                        "000000000000", "us-east-1", "cache")));
        manager.stopByClusterName("000000000000-us-east-1-cache", "000000000000", "us-east-1", "cache");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> manager.start("111111111111-eu-west-1-cache", "valkey/valkey:8", null,
                        "111111111111", "eu-west-1", "cache")));
        manager.stopByClusterName("111111111111-eu-west-1-cache", "111111111111", "eu-west-1", "cache");
        verify(lifecycle, times(2)).removeIfExistsStrict("floci-aws-memorydb-cache", true);
        verify(lifecycle, times(2)).removeIfExistsStrict("floci-aws-memorydb-111111111111_eu-west-1_cache", true);
    }

    private MemoryDbContainerManager manager(ContainerBuilder builder, ContainerLifecycleManager lifecycle,
                                             ContainerLogStreamer logs) {
        EmulatorConfig config = mock(EmulatorConfig.class, Mockito.RETURNS_DEEP_STUBS);
        when(config.services().memorydb().dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        return new MemoryDbContainerManager(builder, lifecycle, logs, mock(ContainerDetector.class), config,
                new RegionResolver("us-east-1", "000000000000"));
    }
}
