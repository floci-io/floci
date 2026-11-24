package io.floci.aws.services.codeartifact;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.RunLabelledContainers;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class SidecarReplacementTest {

    enum Format {
        PYPI, NPM, NUGET, CARGO
    }

    private record Manager(BiFunction<String, String, String> ready, Consumer<String> register,
                           Consumer<String> begin, Consumer<String> end, Runnable stop) {}

    @ParameterizedTest
    @EnumSource(Format.class)
    void actualStarterKeepsAnAdmittedPredecessorUntilItsUseEnds(Format format) throws Exception {
        AtomicBoolean healthy = new AtomicBoolean(true);
        HttpServer first = healthServer(healthy);
        HttpServer replacement = healthServer(new AtomicBoolean(true));
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().codeartifact().pypiImage()).thenReturn("pypi-test");
        when(config.services().codeartifact().npmImage()).thenReturn("npm-test");
        when(config.services().codeartifact().nugetImage()).thenReturn("nuget-test");
        when(config.services().codeartifact().cargoImage()).thenReturn("cargo-test");
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().logMaxSize()).thenReturn("10m");
        when(config.docker().logMaxFile()).thenReturn("3");
        ContainerBuilder builder = new ContainerBuilder(
                config, mock(DockerHostResolver.class), mock(EmbeddedDnsServer.class));
        Map<String, String> liveNames = new HashMap<>();
        Map<String, String> createdNames = new HashMap<>();
        String service = switch (format) {
            case PYPI -> "pypiserver";
            case NPM -> "verdaccio";
            case NUGET -> "bagetter";
            case CARGO -> "kellnr";
        };
        String baseName = ContainerStorageHelper.dockerName(config, "floci-" + service + "-repository");
        liveNames.put(baseName, "stale-fixed");
        liveNames.put(baseName + "-11111111-1111-1111-1111-111111111111", "stale-unique");
        liveNames.put(baseName + "-other-repository", "unrelated");
        liveNames.put(baseName + "-22222222-2222-2222-2222-222222222222", "foreign-namespace");
        DockerClient docker = mock(DockerClient.class);
        ListContainersCmd list = mock(ListContainersCmd.class);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.withShowAll(true)).thenReturn(list);
        when(list.withLabelFilter(any(Map.class))).thenReturn(list);
        Map<String, Map<String, String>> liveLabels = new HashMap<>();
        RunLabelledContainers runs = new CodeArtifactContainerRuns(lifecycle, config).containers();
        when(list.exec()).thenAnswer(invocation -> {
            List<Container> inventory = new ArrayList<>();
            liveNames.forEach((name, id) -> {
                Container container = mock(Container.class);
                when(container.getId()).thenReturn(id);
                when(container.getNames()).thenReturn(new String[]{"/" + name});
                Map<String, String> labels = new HashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                        "codeartifact", "repository", null, null));
                labels.putAll(runs.labels());
                labels.put("io.floci.codeartifact.run", "previous-run");
                if (id.equals("foreign-namespace")) {
                    labels.put(ContainerStorageHelper.OWNER_LABEL, "other");
                } else if (id.equals("unrelated")) {
                    labels.put(ContainerStorageHelper.SERVICE_LABEL, "other-service");
                }
                labels.putAll(liveLabels.getOrDefault(id, Map.of()));
                when(container.getLabels()).thenReturn(labels);
                inventory.add(container);
            });
            return inventory;
        });
        doAnswer(invocation -> {
            liveNames.values().remove(invocation.getArgument(0));
            return null;
        }).when(lifecycle).removeIfExistsStrict(anyString(), eq(true));
        AtomicInteger created = new AtomicInteger();
        when(lifecycle.create(any())).thenAnswer(invocation -> {
            ContainerSpec spec = invocation.getArgument(0);
            assertEquals("codeartifact", spec.labels().get(ContainerStorageHelper.SERVICE_LABEL));
            assertEquals("repository", spec.labels().get(ContainerStorageHelper.RESOURCE_ID_LABEL));
            runs.labels().forEach((key, value) -> assertEquals(value, spec.labels().get(key)));
            String id = "container-" + created.incrementAndGet();
            assertFalse(liveNames.containsKey(spec.name()), "Docker cannot create a duplicate live name");
            liveNames.put(spec.name(), id);
            createdNames.put(id, spec.name());
            liveLabels.put(id, spec.labels());
            return id;
        });
        doAnswer(invocation -> {
            liveNames.remove(invocation.getArgument(0));
            return null;
        }).when(lifecycle).removeIfExists(anyString());
        doAnswer(invocation -> {
            liveNames.values().remove(invocation.getArgument(0));
            return null;
        }).when(lifecycle).stopAndRemoveStrict(anyString(), any());
        doAnswer(invocation -> {
            liveNames.values().remove(invocation.getArgument(0));
            return null;
        }).when(lifecycle).stopAndRemove(anyString(), any());
        when(lifecycle.startCreated(anyString(), any())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            int port = (id.equals("container-1") ? first : replacement).getAddress().getPort();
            EndpointInfo endpoint = new EndpointInfo("127.0.0.1", port);
            return new ContainerInfo(id, Map.of(8080, endpoint, 4873, endpoint, 8000, endpoint));
        });
        Manager manager = manager(format, builder, lifecycle, config, runs);
        boolean admitted = false;
        try {
            manager.register().accept("repository");
            manager.begin().accept("repository");
            admitted = true;
            String original = manager.ready().apply("repository", "http://localhost:4566");
            assertFalse(liveNames.containsValue("stale-fixed"), "Interrupted fixed-name start must be reclaimed");
            assertFalse(liveNames.containsValue("stale-unique"), "Interrupted UUID start must be reclaimed");
            assertTrue(liveNames.containsValue("unrelated"));
            assertTrue(liveNames.containsValue("foreign-namespace"));
            assertEquals(original, manager.ready().apply("repository", "http://localhost:4566"));
            assertEquals(1, created.get(), "Healthy reuse must not create a new container");
            healthy.set(false);
            String next = manager.ready().apply("repository", "http://localhost:4566");

            assertNotEquals(original, next);
            assertTrue(liveNames.containsValue("container-1"),
                    "The actual replacement starter must not remove the admitted predecessor by name");
            assertNotEquals(createdNames.get("container-1"), createdNames.get("container-2"));
            manager.end().accept("repository");
            admitted = false;
            assertFalse(liveNames.containsValue("container-1"));
            assertTrue(liveNames.containsValue("container-2"));
        } finally {
            try {
                if (admitted) {
                    manager.end().accept("repository");
                }
                manager.stop().run();
            } finally {
                first.stop(0);
                replacement.stop(0);
            }
        }
        assertEquals(2, liveNames.size(), "Teardown must leave other repositories and namespaces alone");
        assertTrue(liveNames.containsValue("unrelated"));
        assertTrue(liveNames.containsValue("foreign-namespace"));
    }

    private static HttpServer healthServer(AtomicBoolean healthy) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(healthy.get() ? 200 : 503, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static Manager manager(Format format, ContainerBuilder builder,
                                   ContainerLifecycleManager lifecycle, EmulatorConfig config, RunLabelledContainers runs) {
        return switch (format) {
            case PYPI -> {
                PypiserverSidecarManager manager = new PypiserverSidecarManager(builder, lifecycle, config, runs);
                yield new Manager(manager::ensureReady, manager::registerRepository,
                        manager::beginUse, manager::endUse, manager::stopManagedContainers);
            }
            case NPM -> {
                VerdaccioSidecarManager manager = spy(new VerdaccioSidecarManager(builder, lifecycle, config, runs));
                doNothing().when(manager).copyConfig(anyString(), anyLong());
                yield new Manager(manager::ensureReady, manager::registerRepository,
                        manager::beginUse, manager::endUse, manager::stopManagedContainers);
            }
            case NUGET -> {
                BagetterSidecarManager manager = new BagetterSidecarManager(builder, lifecycle, config, runs);
                yield new Manager(manager::ensureReady, manager::registerRepository,
                        manager::beginUse, manager::endUse, manager::stopManagedContainers);
            }
            case CARGO -> {
                KellnrSidecarManager manager = new KellnrSidecarManager(builder, lifecycle, config, runs);
                yield new Manager(manager::ensureReady, manager::registerRepository,
                        manager::beginUse, manager::endUse, manager::stopManagedContainers);
            }
        };
    }
}
