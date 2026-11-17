package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The EC2 lifecycle against persisted state. Every test runs a real {@link Ec2Service} and
 * {@link Ec2ContainerManager} over real persistent storage, with only Docker mocked, and judges
 * the outcome by the records on disk and by what DescribeInstances returns, so a state that was
 * changed in memory and never written is a failure.
 */
class Ec2LifecycleRecoveryTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final long WAIT_SECONDS = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private final List<Node> nodes = new ArrayList<>();

    @AfterEach
    void stopNodes() {
        nodes.forEach(node -> node.manager.stop());
    }

    @Test
    void terminationReachesTheStoreAsTerminatedWithTheTimeItHappened() throws Exception {
        seed(instance("i-terminate", InstanceState.running(), "container-terminate"));
        Node node = startNode();

        node.service.terminateInstances(REGION, List.of("i-terminate"));

        awaitPersisted("i-terminate", "terminated");
        assertTrue(persistedTerminatedAt("i-terminate") > 0, "the retention clock must be on disk");
        assertEquals("terminated", describedState(node, "i-terminate"));
        verify(node.docker).removeContainerCmd("container-terminate");
    }

    @Test
    void aLaunchThatFailsIsStampedWhenItTerminates() {
        Instance launching = instance("i-failed-launch", InstanceState.pending(), null);
        Node node = startNode();

        assertTrue(node.manager.cancelLaunch(launching));

        assertEquals("terminated", launching.getState().getName());
        assertTrue(launching.getTerminatedAt() > 0, "a failed launch must start its retention clock");
    }

    @Test
    void anInstanceLeftShuttingDownIsTerminatedOnTheNextStart() throws Exception {
        seed(instance("i-shutting", InstanceState.shuttingDown(), "container-shutting"));

        Node node = startNode();

        awaitPersisted("i-shutting", "terminated");
        assertTrue(persistedTerminatedAt("i-shutting") > 0);
        assertEquals("terminated", describedState(node, "i-shutting"));
        verify(node.docker, timeout(WAIT_SECONDS * 1000)).removeContainerCmd("container-shutting");
    }

    @Test
    void aProcessKilledWhileTearingDownLeavesNothingStuck() throws Exception {
        seed(instance("i-killed", InstanceState.running(), "container-killed"));
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Node first = startNode(remove -> doAnswer(call -> {
            removing.countDown();
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            return null;
        }).when(remove).exec());

        first.service.terminateInstances(REGION, List.of("i-killed"));
        assertTrue(removing.await(WAIT_SECONDS, TimeUnit.SECONDS), "the teardown must be under way");
        awaitPersisted("i-killed", "shutting-down");

        // The first process never gets to finish: a second one starts on the same files.
        Node second = startNode();
        try {
            awaitPersisted("i-killed", "terminated");
            assertTrue(persistedTerminatedAt("i-killed") > 0);
            assertEquals("terminated", describedState(second, "i-killed"));
        } finally {
            release.countDown();
            // Let the first process finish its teardown before the directory it writes to is removed.
            await(() -> "terminated".equals(describedState(first, "i-killed")),
                    "the first process to finish its teardown");
        }
    }

    @Test
    void aLaunchThatNeverCreatedAContainerEndsTerminatedOnTheNextStart() throws Exception {
        seed(instance("i-no-container", InstanceState.pending(), null));

        Node node = startNode();

        awaitPersisted("i-no-container", "terminated");
        assertTrue(persistedTerminatedAt("i-no-container") > 0);
        assertEquals("terminated", describedState(node, "i-no-container"));
    }

    @Test
    void aPendingInstanceWhoseContainerIsRunningIsRunningOnTheNextStart() throws Exception {
        seed(instance("i-pending-up", InstanceState.pending(), "container-pending-up"));

        Node node = startNode(null, true);

        awaitPersisted("i-pending-up", "running");
        assertEquals("running", describedState(node, "i-pending-up"));
        verify(node.docker, never()).removeContainerCmd(anyString());
        verify(node.docker, never()).stopContainerCmd(anyString());
    }

    @Test
    void aPendingInstanceWhoseContainerIsNotRunningIsStoppedNotDiscarded() throws Exception {
        seed(instance("i-pending-down", InstanceState.pending(), "container-pending-down"));

        Node node = startNode();

        awaitPersisted("i-pending-down", "stopped");
        assertEquals("stopped", describedState(node, "i-pending-down"));
        verify(node.docker, never()).removeContainerCmd(anyString());
        verify(node.docker).stopContainerCmd("container-pending-down");
    }

    @Test
    void anInstanceLeftStoppingIsStoppedOnTheNextStart() throws Exception {
        seed(instance("i-stopping", InstanceState.stopping(), "container-stopping"));

        Node node = startNode();

        awaitPersisted("i-stopping", "stopped");
        assertEquals("stopped", describedState(node, "i-stopping"));
        verify(node.docker).stopContainerCmd("container-stopping");
    }

    @Test
    void aTerminatedRecordWrittenWithoutATimeGetsItOnTheNextStart() throws Exception {
        Instance untimed = instance("i-untimed", InstanceState.terminated(), null);
        Instance timed = instance("i-timed", InstanceState.terminated(), null);
        timed.setTerminatedAt(1_700_000_000_000L);
        seed(untimed, timed);

        startNode();

        assertTrue(persistedTerminatedAt("i-untimed") > 0, "an untimed record must start its clock");
        assertEquals(1_700_000_000_000L, persistedTerminatedAt("i-timed"), "a timed record keeps its time");
    }

    @Test
    void aTeardownThatThrowsStillEndsTerminated() throws Exception {
        seed(instance("i-throws", InstanceState.running(), "container-throws"));
        Node node = startNode();
        doThrow(new IllegalStateException("port forwards are gone")).when(node.portForwards).unpublishAll(any());

        node.service.terminateInstances(REGION, List.of("i-throws"));

        awaitPersisted("i-throws", "terminated");
        assertTrue(persistedTerminatedAt("i-throws") > 0);
    }

    @Test
    void activeInstancesAreLeftAloneByAStart() throws Exception {
        seed(instance("i-running", InstanceState.running(), null),
                instance("i-stopped", InstanceState.stopped(), "container-stopped"));

        Node node = startNode();

        assertEquals("running", describedState(node, "i-running"));
        assertEquals("stopped", describedState(node, "i-stopped"));
        assertEquals("running", persistedState("i-running"));
        assertEquals("stopped", persistedState("i-stopped"));
    }

    private Node startNode() {
        return startNode(null, false);
    }

    private Node startNode(Consumer<RemoveContainerCmd> removeBehaviour) {
        return startNode(removeBehaviour, false);
    }

    private Node startNode(Consumer<RemoveContainerCmd> removeBehaviour,
                           boolean containersRunning) {
        Node node = new Node(removeBehaviour, containersRunning);
        nodes.add(node);
        return node;
    }

    /** One Floci process over the shared directory: loads the stored records and runs its startup. */
    private final class Node {
        final DockerClient docker = mock(DockerClient.class);
        final Ec2PortForwardManager portForwards = mock(Ec2PortForwardManager.class);
        final Ec2ContainerManager manager;
        final Ec2Service service;

        Node(Consumer<RemoveContainerCmd> removeBehaviour, boolean containersRunning) {
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
            if (removeBehaviour != null) {
                removeBehaviour.accept(remove);
            }
            when(docker.removeContainerCmd(anyString())).thenReturn(remove);
            when(docker.stopContainerCmd(anyString())).thenReturn(mock(StopContainerCmd.class, RETURNS_SELF));
            EmulatorConfig config = config();
            Ec2ContainerManager real = new Ec2ContainerManager(
                    mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                    mock(DockerHostResolver.class), docker, mock(PortAllocator.class), config,
                    mock(Ec2MetadataServer.class), portForwards, mock(RegionResolver.class),
                    mock(ContainerNetworkReachability.class), mock(VpcNetworkManager.class),
                    mock(ContainerReachableEndpoint.class));
            manager = spy(real);
            doReturn(containersRunning).when(manager).restoreMetadataRegistration(any());
            Ec2ImageCatalog imageCatalog = mock(Ec2ImageCatalog.class);
            service = new Ec2Service(config, manager, portForwards, mock(AmiImageResolver.class), imageCatalog,
                    new Ec2InstanceTypeCatalog(), StorageFactories.open(config, "persistent"));
            service.restoreMetadataRegistrations();
        }
    }

    private EmulatorConfig config() {
        EmulatorConfig config = StorageFactories.config(dir, ACCOUNT);
        when(config.services().ec2().mock()).thenReturn(false);
        return config;
    }

    private void seed(Instance... instances) {
        StorageFactory factory = StorageFactories.open(config(), "persistent");
        try {
            AccountAwareStorageBackend<Instance> store = factory.create(
                    "ec2", "ec2-instances.json", new TypeReference<Map<String, Instance>>() { });
            for (Instance instance : instances) {
                store.put(REGION + "::" + instance.getInstanceId(), instance);
            }
        } finally {
            factory.shutdownAll();
        }
    }

    private static Instance instance(String id, InstanceState state, String containerId) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setRegion(REGION);
        instance.setState(state);
        instance.setDockerContainerId(containerId);
        return instance;
    }

    private static String describedState(Node node, String id) {
        return node.service.describeInstances(REGION, List.of(id), Map.of()).getFirst().getInstances().getFirst()
                .getState().getName();
    }

    private JsonNode persistedRecord(String id) throws IOException {
        JsonNode records = JSON.readTree(Files.readString(dir.resolve("ec2-instances.json")));
        for (Map.Entry<String, JsonNode> entry : records.properties()) {
            if (entry.getKey().endsWith("::" + id)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String persistedState(String id) throws IOException {
        JsonNode record = persistedRecord(id);
        return record == null ? null : record.path("state").path("name").asText();
    }

    private long persistedTerminatedAt(String id) throws IOException {
        JsonNode record = persistedRecord(id);
        return record == null ? -1 : record.path("terminatedAt").asLong();
    }

    private void awaitPersisted(String id, String state) throws Exception {
        await(() -> {
            try {
                return state.equals(persistedState(id));
            } catch (IOException e) {
                return false;
            }
        }, "the stored record of " + id + " to read " + state + " but it reads " + safePersistedState(id));
    }

    private String safePersistedState(String id) {
        try {
            return persistedState(id);
        } catch (IOException e) {
            return "unreadable: " + e.getMessage();
        }
    }

    private static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Timed out waiting for " + description);
    }
}
