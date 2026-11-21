package io.floci.aws.services.codeartifact;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.RunLabelledContainers;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeArtifactContainerRunsTest {

    private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
    private final ListContainersCmd list = mock(ListContainersCmd.class);
    private final CodeArtifactContainerRuns producer;

    CodeArtifactContainerRunsTest() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        DockerClient docker = mock(DockerClient.class);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.withShowAll(true)).thenReturn(list);
        when(list.withLabelFilter(anyMap())).thenReturn(list);
        producer = new CodeArtifactContainerRuns(lifecycle, config);
    }

    @Test
    void retriesLeftoversWithoutRemovingAnyCurrentFormatOrAdmittedPredecessor() {
        RunLabelledContainers runs = producer.containers();
        assertSame(runs, producer.containers());
        List<Container> inventory = new ArrayList<>();
        for (String format : List.of("pypi", "npm", "nuget", "cargo")) {
            inventory.add(container(format, runs.labels()));
            inventory.add(container(format + "-predecessor", runs.labels()));
        }
        Map<String, String> old = new HashMap<>(runs.labels());
        old.put("io.floci.codeartifact.run", "previous-run");
        inventory.add(container("leftover", old));
        when(list.exec()).thenReturn(inventory);
        doThrow(new IllegalStateException("Docker unavailable")).doNothing()
                .when(lifecycle).removeIfExistsStrict("leftover", true);

        runs.removeLeftovers();
        producer.containers().removeLeftovers();

        verify(lifecycle, times(2)).removeIfExistsStrict("leftover", true);
        for (String format : List.of("pypi", "npm", "nuget", "cargo")) {
            verify(lifecycle, never()).removeIfExistsStrict(format, true);
            verify(lifecycle, never()).removeIfExistsStrict(format + "-predecessor", true);
        }
    }

    @Test
    void inventoryFailureIsRetriedAndForeignOwnerAndServiceArePreserved() {
        RunLabelledContainers runs = producer.containers();
        Map<String, String> foreign = new HashMap<>(runs.labels());
        foreign.put(ContainerStorageHelper.OWNER_LABEL, "another-deployment");
        foreign.put("io.floci.codeartifact.run", "previous-run");
        Container foreignContainer = container("foreign", foreign);
        Container otherService = container("other-service", foreign);
        Map<String, String> other = new HashMap<>(runs.labels());
        other.put(ContainerStorageHelper.SERVICE_LABEL, "batch");
        other.put("io.floci.codeartifact.run", "previous-run");
        when(otherService.getLabels()).thenReturn(other);
        when(list.exec()).thenThrow(new IllegalStateException("Docker unavailable"))
                .thenReturn(List.of(foreignContainer, otherService));

        runs.removeLeftovers();
        runs.removeLeftovers();

        verify(lifecycle, never()).removeIfExistsStrict("foreign", true);
        verify(lifecycle, never()).removeIfExistsStrict("other-service", true);
        verify(list, atLeast(2)).withShowAll(true);
    }

    private static Container container(String id, Map<String, String> runLabels) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        Map<String, String> labels = new HashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                "codeartifact", id, null, null));
        labels.putAll(runLabels);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }
}
