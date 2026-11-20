package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.StartContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class Ec2StartRequestScopeIntegrationTest {

    @Inject
    RegionResolver resolver;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void backgroundStartUsesOwnerOrCallerAndClearsItsScopeAfterFailure(boolean ownerless) throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor();
             ExecutorService retries = Executors.newSingleThreadExecutor()) {
            DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
            Ec2ContainerManager manager = manager(docker, mock(ContainerLifecycleManager.class), worker, retries);
            Scope caller = new Scope("999900001111", "ap-southeast-1", "aws");
            for (Scope owner : List.of(new Scope("111122223333", "eu-west-1", "aws"),
                    new Scope("444455556666", "us-gov-east-1", "aws-us-gov"))) {
                Instance instance = instance(ownerless ? null : owner.account(), owner.region());
                CompletableFuture<Scope> observed = new CompletableFuture<>();
                when(docker.startContainerCmd(instance.getDockerContainerId())).thenAnswer(invocation -> {
                    observed.complete(scope());
                    throw new IllegalStateException("container start refused");
                });

                RequestScopes.runAs(caller.account(), caller.region(), () -> {
                    manager.start(instance);
                    assertEquals(caller, scope(), "Scheduling must preserve the caller's context");
                });

                Scope expected = new Scope(ownerless ? caller.account() : owner.account(),
                        owner.region(), owner.partition());
                assertEquals(expected, observed.get(5, TimeUnit.SECONDS));
                assertFalse(worker.submit(() -> Arc.container().requestContext().isActive())
                        .get(5, TimeUnit.SECONDS), "A failed start must not leak its context to the next task");
                assertEquals("stopped", instance.getState().getName());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protectedNamespaceLookupUsesOwnerOrCapturedCallerAndClearsFailedScope(boolean ownerless) throws Exception {
        try (ExecutorService worker = Executors.newSingleThreadExecutor();
             ExecutorService retries = Executors.newSingleThreadExecutor()) {
            DockerClient docker = mock(DockerClient.class);
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            Ec2ContainerManager manager = manager(docker, lifecycle, worker, retries);
            Scope caller = new Scope("999900001111", "ap-southeast-1", "aws");
            Scope expected = new Scope(ownerless ? caller.account() : "444455556666",
                    "us-gov-east-1", "aws-us-gov");
            Instance instance = instance(ownerless ? null : expected.account(), expected.region());
            when(docker.startContainerCmd(instance.getDockerContainerId())).thenReturn(mock(StartContainerCmd.class));
            when(lifecycle.isContainerRunning(instance.getDockerContainerId())).thenReturn(true);
            CompletableFuture<Scope> observed = new CompletableFuture<>();
            when(docker.inspectContainerCmd(instance.getDockerContainerId())).thenAnswer(invocation -> {
                observed.complete(scope());
                throw new IllegalStateException("protected namespace lookup refused");
            });

            RequestScopes.runAs(caller.account(), caller.region(), () -> {
                manager.start(instance);
                assertEquals(caller, scope());
            });

            assertEquals(expected, observed.get(5, TimeUnit.SECONDS));
            assertFalse(worker.submit(() -> Arc.container().requestContext().isActive()).get(5, TimeUnit.SECONDS));
            assertEquals("stopped", instance.getState().getName());
        }
    }

    private Ec2ContainerManager manager(DockerClient docker, ContainerLifecycleManager lifecycle,
                                        ExecutorService worker, ExecutorService retries) {
        return new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                mock(DockerHostResolver.class), docker, mock(PortAllocator.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(Ec2MetadataServer.class),
                mock(Ec2PortForwardManager.class), resolver, mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class), mock(ContainerReachableEndpoint.class),
                new Ec2InstanceTypeCatalog(), worker, retries, Duration.ofSeconds(1));
    }

    private Instance instance(String owner, String region) {
        Instance instance = new Instance();
        instance.setInstanceId("i-" + region);
        instance.setDockerContainerId("container-" + region);
        instance.setOwnerId(owner);
        instance.setRegion(region);
        return instance;
    }

    private Scope scope() {
        return new Scope(resolver.getAccountId(), resolver.getRegion(), resolver.getPartition());
    }

    private record Scope(String account, String region, String partition) {}
}
