package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse.ContainerState;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the checks that never need a real Docker daemon. The parts that actually launch and
 * verify a container are proved end to end in
 * {@code EcsCredentialsProxyDockerIntegrationTest} instead: mocking the exec/callback plumbing
 * that {@link EcsCredentialsProxy#ensureProxyOn} depends on would mostly test the mock, not the
 * behaviour a runtime that silently drops {@code LinkLocalIPs} is meant to be caught by.
 */
class EcsCredentialsProxyTest {

    private DockerClient dockerClient;
    private ContainerBuilder containerBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private DockerHostResolver dockerHostResolver;
    private EmulatorConfig config;
    private EcsCredentialsProxy proxy;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        containerBuilder = mock(ContainerBuilder.class);
        lifecycleManager = mock(ContainerLifecycleManager.class);
        dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.resolve()).thenReturn("host.docker.internal");
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        proxy = new EcsCredentialsProxy(dockerClient, containerBuilder, lifecycleManager, dockerHostResolver, config);
    }

    @Test
    void rejectsABlankNetworkWithoutTouchingDocker() {
        assertThrows(IllegalStateException.class, () -> proxy.ensureProxyOn(null));
        assertThrows(IllegalStateException.class, () -> proxy.ensureProxyOn(""));
        assertThrows(IllegalStateException.class, () -> proxy.ensureProxyOn("   "));

        verify(dockerClient, never()).createContainerCmd(any());
        verify(containerBuilder, never()).newContainer(any());
    }

    @Test
    void twoInstancesSharingANamespaceAndNetworkGetDistinctProxyNames() {
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        EcsCredentialsProxy first = proxy;

        EmulatorConfig otherConfig = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(otherConfig.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(otherConfig.port()).thenReturn(4567);
        EcsCredentialsProxy second = new EcsCredentialsProxy(
                dockerClient, containerBuilder, lifecycleManager, dockerHostResolver, otherConfig);

        // Same daemon, same resource namespace (none), same network: the only thing that could
        // tell the two proxies apart is the owner folded into the name itself. If it were not,
        // the second instance's create() would collide on the first's container name and never
        // be able to establish its own proxy at all, no matter how the removal is scoped.
        assertNotEquals(first.ownerNameSegment(), second.ownerNameSegment());
    }

    @Test
    void skipsRelaunchingAProxyThatIsStillRunningOnTheSameNetwork() {
        stubSuccessfulLaunch("proxy-1");

        proxy.ensureProxyOn("floci-net");
        proxy.ensureProxyOn("floci-net");

        verify(lifecycleManager).createAndStart(any());
        verify(dockerClient).inspectContainerCmd("proxy-1");
        // The image check ran once, for the first launch; the second call short-circuits on
        // isRunning() before it ever reaches the image/launch path again.
        verify(dockerClient).inspectImageCmd(any());
    }

    @Test
    void stopManagedContainersRemovesEveryProxyThisInstanceLaunched() {
        stubSuccessfulLaunch("proxy-1");
        proxy.ensureProxyOn("floci-net");

        proxy.stopManagedContainers();

        verify(lifecycleManager).removeIfExists("proxy-1");
    }

    @Test
    void stopManagedContainersForgetsProxiesSoALaterEnsureRelaunchesInsteadOfShortCircuiting() {
        stubSuccessfulLaunch("proxy-1");
        proxy.ensureProxyOn("floci-net");
        proxy.stopManagedContainers();

        stubSuccessfulLaunch("proxy-2");
        proxy.ensureProxyOn("floci-net");

        // A full relaunch, not the isRunning() short-circuit skipsRelaunchingAProxy... covers:
        // stopManagedContainers must have actually forgotten "floci-net", not just removed the
        // container while still remembering it as the current one.
        verify(lifecycleManager, times(2)).createAndStart(any());
        verify(dockerClient, never()).inspectContainerCmd("proxy-2");
    }

    /** No container already holds the name {@code ensureProxyOn} is about to ask Docker for. */
    private void stubNoStaleContainerByName() {
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of());
    }

    /** A container already holds that exact name, reported with the given labels. */
    private void stubStaleContainerByName(String staleId, Map<String, String> labels) {
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        Container stale = mock(Container.class);
        when(stale.getId()).thenReturn(staleId);
        when(stale.getNames()).thenAnswer(invocation -> new String[] {"/" + ContainerStorageHelper.resourceName(
                config, "ecs-credentials-proxy", null, "floci-net-" + proxy.ownerNameSegment())});
        when(stale.getLabels()).thenReturn(labels);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(stale));
    }

    @Test
    void removesAStaleContainerFromBeforeTheOwnerLabelConventionExisted() {
        stubSuccessfulLaunch("proxy-1");
        stubStaleContainerByName("stale-unlabeled", Map.of("floci.ecs-task-role-credentials-proxy", "true"));

        proxy.ensureProxyOn("floci-net");

        verify(lifecycleManager).removeIfExists("stale-unlabeled");
    }

    @Test
    void removesAStaleContainerThisSameInstanceOwns() {
        stubSuccessfulLaunch("proxy-1");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        stubStaleContainerByName("stale-own", Map.of(
                "floci.ecs-task-role-credentials-proxy", "true", "floci_owner_port", "4566"));

        proxy.ensureProxyOn("floci-net");

        verify(lifecycleManager).removeIfExists("stale-own");
    }

    @Test
    void leavesASameNamedProxyOwnedByAnotherFlociInstanceAlone() {
        stubSuccessfulLaunch("proxy-1");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        stubStaleContainerByName("other-instance-proxy", Map.of(
                "floci.ecs-task-role-credentials-proxy", "true", "floci_owner_port", "4567"));

        proxy.ensureProxyOn("floci-net");

        verify(lifecycleManager, never()).removeIfExists("other-instance-proxy");
    }

    @Test
    void leavesAnUnrelatedSameNamedContainerAlone() {
        stubSuccessfulLaunch("proxy-1");
        // Same name, but not one of our proxies at all: no OWNS_NETWORK_LABEL.
        stubStaleContainerByName("unrelated-container", Map.of());

        proxy.ensureProxyOn("floci-net");

        verify(lifecycleManager, never()).removeIfExists("unrelated-container");
    }

    /**
     * Stubs the whole happy path for a single {@code ensureProxyOn} call to succeed against a
     * container with the given id: the image check, the launch, the running check for a repeat
     * call, and the endpoint-reachability exec/callback plumbing.
     */
    private void stubSuccessfulLaunch(String containerId) {
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(new ContainerSpec("floci/network-helper:local"));
        when(containerBuilder.newContainer(any())).thenReturn(builder);
        when(config.services().ecs().taskRoleCredentials().proxyImage()).thenReturn("floci/network-helper:local");
        when(containerBuilder.resolveImage("floci/network-helper:local")).thenReturn("floci/network-helper:local");
        stubNoStaleContainerByName();
        com.github.dockerjava.api.command.InspectImageCmd inspectImageCmd =
                mock(com.github.dockerjava.api.command.InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(any())).thenReturn(inspectImageCmd);
        when(inspectImageCmd.exec()).thenReturn(mock(com.github.dockerjava.api.command.InspectImageResponse.class));
        when(lifecycleManager.createAndStart(any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo(containerId, Map.of()));
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
        ContainerState running = mock(ContainerState.class);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(inspectResponse.getState()).thenReturn(running);
        when(running.getRunning()).thenReturn(true);

        com.github.dockerjava.api.command.ExecCreateCmd execCreate =
                mock(com.github.dockerjava.api.command.ExecCreateCmd.class, RETURNS_SELF);
        com.github.dockerjava.api.command.ExecCreateCmdResponse execResponse =
                mock(com.github.dockerjava.api.command.ExecCreateCmdResponse.class);
        when(dockerClient.execCreateCmd(containerId)).thenReturn(execCreate);
        when(execCreate.exec()).thenReturn(execResponse);
        when(execResponse.getId()).thenReturn("exec-" + containerId);
        com.github.dockerjava.api.command.ExecStartCmd execStart =
                mock(com.github.dockerjava.api.command.ExecStartCmd.class);
        when(dockerClient.execStartCmd("exec-" + containerId)).thenReturn(execStart);
        when(execStart.exec(any())).thenAnswer(invocation -> {
            com.github.dockerjava.api.async.ResultCallback<com.github.dockerjava.api.model.Frame> callback =
                    invocation.getArgument(0);
            callback.onNext(new com.github.dockerjava.api.model.Frame(
                    com.github.dockerjava.api.model.StreamType.STDOUT,
                    "404".getBytes()));
            callback.onComplete();
            return callback;
        });
    }

    @Test
    void reapSurvivingProxiesRemovesOnlyThisInstancesOwnLeftovers() {
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.port()).thenReturn(4566);
        ListContainersCmd listCmd = mock(ListContainersCmd.class, RETURNS_SELF);
        Container ownLeftover = mock(Container.class);
        when(ownLeftover.getId()).thenReturn("stale-proxy");
        when(ownLeftover.getLabels()).thenReturn(Map.of("floci_owner_port", "4566"));
        // Another Floci process sharing this daemon: same label, different owner. Reaping this
        // one would tear down a proxy a sibling instance is still actively using.
        Container otherInstance = mock(Container.class);
        when(otherInstance.getId()).thenReturn("other-instance-proxy");
        when(otherInstance.getLabels()).thenReturn(Map.of("floci_owner_port", "4567"));
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(ownLeftover, otherInstance));

        proxy.reapSurvivingProxies();

        verify(lifecycleManager).removeIfExists("stale-proxy");
        verify(lifecycleManager, never()).removeIfExists("other-instance-proxy");
    }

    @Test
    void reapSurvivingProxiesToleratesADockerFailure() {
        when(dockerClient.listContainersCmd()).thenThrow(new RuntimeException("daemon unreachable"));

        proxy.reapSurvivingProxies();

        verify(lifecycleManager, never()).removeIfExists(any());
    }
}
