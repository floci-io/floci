package io.floci.aws.services.emrserverless;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLiveness;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EMR Serverless runs' and sessions' kept histories in Docker ({@link EmrServerlessHistories}): a history is its
 * volume, and a Spark History Server reads it in a container of its own, started on demand, reached once it answers,
 * and removed with the histories that are no longer kept.
 */
class EmrServerlessHistoriesTest {

    private static final String RUN = "00f0000000000001";
    private static final String IMAGE = "public.ecr.aws/emr-serverless/spark/emr-7.13.0:latest";

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
    private final ContainerBuilder builder = mock(ContainerBuilder.class);
    private final ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
    // The starts it hands off, run when the test says.
    private final List<Runnable> starts = new ArrayList<>();
    private final EmrServerlessHistories histories = new EmrServerlessHistories(builder, lifecycle, config,
            starts::add);

    @Test
    void aKeptHistorysServerStartsFromTheImageWithItsVolumeAndIsReachedOnceItAnswers() {
        String volume = EmrServerlessHistories.volume(config, RUN);
        String server = ContainerStorageHelper.dockerName(config, "emr-serverless-" + RUN + "-history-ui");
        when(builder.newContainer(IMAGE)).thenReturn(spec);
        when(lifecycle.volumeExists(volume)).thenReturn(true);
        when(lifecycle.probeContainer(server)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        labelled(volume);

        assertEquals(Optional.empty(), histories.server(RUN, "000000000000", "us-east-1", IMAGE, "floci-network"));
        starts.removeFirst().run();

        verify(spec).withName(server);
        verify(spec).withNamedVolume(volume, EmrServerlessHistories.EVENT_LOGS);
        verify(spec).withLoopbackPortBinding(EmrServerlessSparkRunner.SPARK_HISTORY_WEB_PORT, 0);
        verify(spec).withDockerNetwork(Optional.of("floci-network"));
        verify(spec).withEntrypoint(argThat((List<String> entrypoint) ->
                entrypoint.getLast().contains("org.apache.spark.deploy.history.HistoryServer")
                        && entrypoint.getLast().contains("file://" + EmrServerlessHistories.EVENT_LOGS)));
        verify(spec).withLabels(argThat(labels -> RUN.equals(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))
                && EmrServerlessHistories.SERVER_COMPONENT.equals(labels.get(ContainerStorageHelper.COMPONENT_LABEL))
                && ContainerStorageHelper.ownerIdentity(config).equals(
                        labels.get(ContainerStorageHelper.OWNER_LABEL))));
        verify(lifecycle).createAndStart(any());
        verify(lifecycle, never()).removeVolume(anyString());

        // Running, it is reached once its REST API answers.
        when(lifecycle.probeContainer(server)).thenReturn(ContainerLiveness.RUNNING);
        List<List<String>> asked = ContainerExecStubs.completeEveryExec(docker, server, 0L, "{}", "");
        ContainerLifecycleManager.EndpointInfo endpoint = new ContainerLifecycleManager.EndpointInfo("localhost",
                32818);
        when(lifecycle.resolveEndpoint(server, EmrServerlessSparkRunner.SPARK_HISTORY_WEB_PORT, "floci-network"))
                .thenReturn(endpoint);
        assertEquals(Optional.of(endpoint),
                histories.server(RUN, "000000000000", "us-east-1", IMAGE, "floci-network"));
        assertTrue(asked.getFirst().getLast().endsWith("/api/v1/version"), asked.toString());
    }

    @Test
    void aHistoryThatHasGoneStartsNoServer() {
        String server = ContainerStorageHelper.dockerName(config, "emr-serverless-" + RUN + "-history-ui");
        when(lifecycle.probeContainer(server)).thenReturn(ContainerLiveness.NOT_RUNNING);

        assertEquals(Optional.empty(), histories.server(RUN, "000000000000", "us-east-1", IMAGE, null));
        starts.removeFirst().run();

        verify(lifecycle, never()).createAndStart(any());
        verify(builder, never()).newContainer(anyString());
    }

    @Test
    void aServerAskedForBeforeATeardownStartsNoneAfterIt() {
        String server = ContainerStorageHelper.dockerName(config, "emr-serverless-" + RUN + "-history-ui");
        when(lifecycle.volumeExists(EmrServerlessHistories.volume(config, RUN))).thenReturn(true);
        when(lifecycle.probeContainer(server)).thenReturn(ContainerLiveness.NOT_RUNNING);

        histories.server(RUN, "000000000000", "us-east-1", IMAGE, null);
        histories.stopServers();
        starts.removeFirst().run();

        verify(lifecycle, never()).createAndStart(any());
    }

    @Test
    void aServerStartedThroughATeardownGoes() {
        String volume = EmrServerlessHistories.volume(config, RUN);
        String server = ContainerStorageHelper.dockerName(config, "emr-serverless-" + RUN + "-history-ui");
        when(builder.newContainer(IMAGE)).thenReturn(spec);
        when(lifecycle.volumeExists(volume)).thenReturn(true);
        when(lifecycle.probeContainer(server)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        labelled(volume);
        // A shutdown while the container is made, which may miss it.
        doAnswer(created -> {
            histories.stopServers();
            return null;
        }).when(lifecycle).createAndStart(any());

        histories.server(RUN, "000000000000", "us-east-1", IMAGE, null);
        starts.removeFirst().run();

        // As it starts, an earlier one's; by the shutdown; and once it has started, the shutdown's removal having
        // come before the container was there.
        verify(lifecycle, times(3)).removeIfExistsStrict(server);
        verify(lifecycle, never()).removeVolume(anyString());
    }

    @Test
    void aServerWhoseHistoryWentAsItStartedGoesWithTheVolumeDockerMadeForIt() {
        String volume = EmrServerlessHistories.volume(config, RUN);
        String server = ContainerStorageHelper.dockerName(config, "emr-serverless-" + RUN + "-history-ui");
        when(builder.newContainer(IMAGE)).thenReturn(spec);
        when(lifecycle.volumeExists(volume)).thenReturn(true);
        when(lifecycle.probeContainer(server)).thenReturn(ContainerLiveness.NOT_RUNNING);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        // Docker made it again, with no labels, as the container mounted it.
        when(docker.inspectVolumeCmd(volume).exec().getLabels()).thenReturn(null);

        histories.server(RUN, "000000000000", "us-east-1", IMAGE, null);
        starts.removeFirst().run();

        verify(lifecycle, times(2)).removeIfExistsStrict(server);
        verify(lifecycle).removeVolume(volume);
    }

    @Test
    void theHistoriesNotKeptGoWithTheirServers() {
        when(lifecycle.getDockerClient()).thenReturn(docker);
        Container keptServer = container("server-kept", "00fkept000000001");
        Container goneServer = container("server-gone", "00fgone000000001");
        String owner = ContainerStorageHelper.OWNER_LABEL + "=" + ContainerStorageHelper.ownerIdentity(config);
        // Only this Floci's, by its owner label: another sharing the Docker daemon keeps its own.
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(List.of(ContainerStorageHelper.COMPONENT_LABEL
                + "=" + EmrServerlessHistories.SERVER_COMPONENT, owner)).exec()).thenReturn(List.of(keptServer,
                goneServer));
        InspectVolumeResponse keptVolume = volume("volume-kept", "00fkept000000001");
        InspectVolumeResponse goneVolume = volume("volume-gone", "00fgone000000001");
        when(docker.listVolumesCmd().withFilter(eq("label"), eq(List.of(ContainerStorageHelper.COMPONENT_LABEL + "="
                + EmrServerlessHistories.VOLUME_COMPONENT, owner))).exec().getVolumes())
                .thenReturn(List.of(keptVolume, goneVolume));

        histories.removeAllBut("00fkept000000001"::equals);

        verify(lifecycle).removeIfExistsStrict("server-gone");
        verify(lifecycle, never()).removeIfExistsStrict("server-kept");
        verify(lifecycle).removeVolume("volume-gone");
        verify(lifecycle, never()).removeVolume("volume-kept");
    }

    @Test
    void aHistorysVolumeIsLabelledForItsRunAndTheService() {
        Map<String, String> labels = EmrServerlessHistories.labels(config, RUN, "000000000000", "us-east-1",
                EmrServerlessHistories.VOLUME_COMPONENT);

        assertEquals(RUN, labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL));
        assertEquals(EmrServerlessJobRunService.SERVICE, labels.get(ContainerStorageHelper.SERVICE_LABEL));
        assertEquals(EmrServerlessHistories.VOLUME_COMPONENT, labels.get(ContainerStorageHelper.COMPONENT_LABEL));
        assertEquals(ContainerStorageHelper.ownerIdentity(config), labels.get(ContainerStorageHelper.OWNER_LABEL));
    }

    // The volume is the runner's, with its labels.
    private void labelled(String volume) {
        when(docker.inspectVolumeCmd(volume).exec().getLabels()).thenReturn(Map.of(
                ContainerStorageHelper.COMPONENT_LABEL, EmrServerlessHistories.VOLUME_COMPONENT));
    }

    private static Container container(String id, String runId) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, runId));
        return container;
    }

    private static InspectVolumeResponse volume(String name, String runId) {
        InspectVolumeResponse volume = mock(InspectVolumeResponse.class);
        when(volume.getName()).thenReturn(name);
        when(volume.getLabels()).thenReturn(Map.of(ContainerStorageHelper.RESOURCE_ID_LABEL, runId));
        return volume;
    }
}
