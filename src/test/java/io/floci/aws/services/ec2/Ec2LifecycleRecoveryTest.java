package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLiveness;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.floci.aws.testing.Ec2LifecycleFixtures.ACCOUNT;
import static io.floci.aws.testing.Ec2LifecycleFixtures.REGION;
import static io.floci.aws.testing.Ec2LifecycleFixtures.await;
import static io.floci.aws.testing.Ec2LifecycleFixtures.config;
import static io.floci.aws.testing.Ec2LifecycleFixtures.instance;
import static io.floci.aws.testing.Ec2LifecycleFixtures.manager;
import static io.floci.aws.testing.Ec2LifecycleFixtures.persistedState;
import static io.floci.aws.testing.Ec2LifecycleFixtures.persistedTerminatedAt;
import static io.floci.aws.testing.Ec2LifecycleFixtures.seed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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

    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path dir;

    private final List<Node> nodes = new ArrayList<>();

    @AfterEach
    void stopNodes() {
        nodes.forEach(node -> node.manager.stop());
    }

    @Test
    void terminationReachesTheStoreAsTerminatedWithTheTimeItHappened() throws Exception {
        seed(config(dir, false, true), ACCOUNT, instance("i-terminate", InstanceState.running(), "container-terminate"));
        Node node = startNode();

        node.service.terminateInstances(REGION, List.of("i-terminate"));

        awaitPersisted("i-terminate", "terminated");
        assertTrue(persistedTerminatedAt(dir, "i-terminate") > 0, "the retention clock must be on disk");
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
        seed(config(dir, false, true), ACCOUNT, instance("i-shutting", InstanceState.shuttingDown(), "container-shutting"));

        Node node = startNode();

        awaitPersisted("i-shutting", "terminated");
        assertTrue(persistedTerminatedAt(dir, "i-shutting") > 0);
        assertEquals("terminated", describedState(node, "i-shutting"));
        verify(node.docker, timeout(WAIT_SECONDS * 1000)).removeContainerCmd("container-shutting");
    }

    @Test
    void aProcessKilledWhileTearingDownLeavesNothingStuck() throws Exception {
        seed(config(dir, false, true), ACCOUNT, instance("i-killed", InstanceState.running(), "container-killed"));
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Node first = startNode(new Options().removing(remove -> doAnswer(call -> {
            removing.countDown();
            release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            return null;
        }).when(remove).exec()));

        first.service.terminateInstances(REGION, List.of("i-killed"));
        assertTrue(removing.await(WAIT_SECONDS, TimeUnit.SECONDS), "the teardown must be under way");
        awaitPersisted("i-killed", "shutting-down");

        // The first process never gets to finish: a second one starts on the same files.
        Node second = startNode();
        try {
            awaitPersisted("i-killed", "terminated");
            assertTrue(persistedTerminatedAt(dir, "i-killed") > 0);
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
        seed(config(dir, false, true), ACCOUNT, instance("i-no-container", InstanceState.pending(), null));

        Node node = startNode();

        awaitPersisted("i-no-container", "terminated");
        assertTrue(persistedTerminatedAt(dir, "i-no-container") > 0);
        assertEquals("terminated", describedState(node, "i-no-container"));
    }

    @Test
    void aPendingInstanceWhoseContainerIsRunningIsRunningOnTheNextStart() throws Exception {
        seed(config(dir, false, true), ACCOUNT, instance("i-pending-up", InstanceState.pending(), "container-pending-up"));

        Node node = startNode(new Options().registered());

        awaitPersisted("i-pending-up", "running");
        assertEquals("running", describedState(node, "i-pending-up"));
        verify(node.docker, never()).removeContainerCmd(anyString());
        verify(node.docker, never()).stopContainerCmd(anyString());
    }

    @Test
    void aPendingInstanceWhoseContainerIsNotRunningIsStoppedNotDiscarded() throws Exception {
        seed(config(dir, false, true), ACCOUNT, instance("i-pending-down", InstanceState.pending(), "container-pending-down"));

        Node node = startNode();

        awaitPersisted("i-pending-down", "stopped");
        assertEquals("stopped", describedState(node, "i-pending-down"));
        verify(node.docker, never()).removeContainerCmd(anyString());
        verify(node.docker).stopContainerCmd("container-pending-down");
    }

    @Test
    void anInstanceLeftStoppingIsStoppedOnTheNextStart() throws Exception {
        seed(config(dir, false, true), ACCOUNT, instance("i-stopping", InstanceState.stopping(), "container-stopping"));

        Node node = startNode();

        awaitPersisted("i-stopping", "stopped");
        assertEquals("stopped", describedState(node, "i-stopping"));
        verify(node.docker).stopContainerCmd("container-stopping");
    }

    @ParameterizedTest
    @EnumSource(value = ContainerLiveness.class, names = {"RUNNING", "UNKNOWN"})
    void aStopThatCannotBeConfirmedLeavesTheInstanceInItsTransitionalState(ContainerLiveness liveness) throws Exception {
        seed(config(dir, false, true),
                ACCOUNT,
                instance("i-pending-unconfirmed", InstanceState.pending(), "container-pending-unconfirmed"),
                instance("i-stopping-unconfirmed", InstanceState.stopping(), "container-stopping-unconfirmed"));

        Node node = startNode(new Options().liveness(liveness).stopFailing());

        verify(node.docker).stopContainerCmd("container-pending-unconfirmed");
        verify(node.docker).stopContainerCmd("container-stopping-unconfirmed");
        assertEquals("pending", persistedState(dir, "i-pending-unconfirmed"),
                "a container that may still be running must not be recorded as stopped");
        assertEquals("stopping", persistedState(dir, "i-stopping-unconfirmed"));
        assertEquals("pending", describedState(node, "i-pending-unconfirmed"));
        assertEquals("stopping", describedState(node, "i-stopping-unconfirmed"));
    }

    @Test
    void anInstanceThatCannotBeSettledDoesNotStopTheOthersBeingSettled() throws Exception {
        seed(config(dir, false, true),
                ACCOUNT,
                instance("i-cannot-settle", InstanceState.stopping(), "container-cannot-settle"),
                instance("i-settles", InstanceState.shuttingDown(), "container-settles"));

        Node node = startNode(new Options().beforeStartup(spied -> doThrow(new IllegalStateException("docker is unreachable"))
                .when(spied).stopForShutdown(argThat(candidate -> "i-cannot-settle".equals(candidate.getInstanceId())))));

        awaitPersisted("i-settles", "terminated");
        assertEquals("stopping", persistedState(dir, "i-cannot-settle"), "it is left as it was, for the next start");
        assertEquals("stopping", describedState(node, "i-cannot-settle"));
    }

    @Test
    void aTerminatedRecordWrittenWithoutATimeGetsItOnTheNextStart() throws Exception {
        Instance untimed = instance("i-untimed", InstanceState.terminated(), null);
        Instance timed = instance("i-timed", InstanceState.terminated(), null);
        timed.setTerminatedAt(1_700_000_000_000L);
        seed(config(dir, false, true), ACCOUNT, untimed, timed);

        startNode();

        assertTrue(persistedTerminatedAt(dir, "i-untimed") > 0, "an untimed record must start its clock");
        assertEquals(1_700_000_000_000L, persistedTerminatedAt(dir, "i-timed"), "a timed record keeps its time");
    }

    @Test
    void aCleanupStepThatThrowsDoesNotSkipTheContainerRemovalAndTheInstanceEndsTerminated() throws Exception {
        seed(config(dir, false, true), ACCOUNT, instance("i-throws", InstanceState.running(), "container-throws"));
        Node node = startNode();
        doThrow(new IllegalStateException("port forwards are gone")).when(node.portForwards).unpublishAll(any());

        node.service.terminateInstances(REGION, List.of("i-throws"));

        awaitPersisted("i-throws", "terminated");
        assertTrue(persistedTerminatedAt(dir, "i-throws") > 0);
        verify(node.docker).removeContainerCmd("container-throws");
    }

    @Test
    void activeInstancesAreLeftAloneByAStart() throws Exception {
        seed(config(dir, false, true),
                ACCOUNT,
                instance("i-running", InstanceState.running(), null),
                instance("i-stopped", InstanceState.stopped(), "container-stopped"));

        Node node = startNode();

        assertEquals("running", describedState(node, "i-running"));
        assertEquals("stopped", describedState(node, "i-stopped"));
        assertEquals("running", persistedState(dir, "i-running"));
        assertEquals("stopped", persistedState(dir, "i-stopped"));
    }

    private Node startNode() {
        return startNode(new Options());
    }

    private Node startNode(Options options) {
        Node node = new Node(options);
        nodes.add(node);
        return node;
    }

    /** What a test changes about the process it starts; everything else is a quiet mock. */
    private static final class Options {
        private Consumer<RemoveContainerCmd> removeBehaviour;
        private boolean registered;
        private ContainerLiveness liveness = ContainerLiveness.NOT_RUNNING;
        private boolean stopFails;
        private Consumer<Ec2ContainerManager> beforeStartup;

        Options removing(Consumer<RemoveContainerCmd> behaviour) {
            this.removeBehaviour = behaviour;
            return this;
        }

        /** The restore loop re-registers a running container for pending records. */
        Options registered() {
            this.registered = true;
            return this;
        }

        Options liveness(ContainerLiveness liveness) {
            this.liveness = liveness;
            return this;
        }

        Options stopFailing() {
            this.stopFails = true;
            return this;
        }

        Options beforeStartup(Consumer<Ec2ContainerManager> setup) {
            this.beforeStartup = setup;
            return this;
        }
    }

    /** One Floci process over the shared directory: loads the stored records and runs its startup. */
    private final class Node {
        final DockerClient docker = mock(DockerClient.class);
        final Ec2PortForwardManager portForwards = mock(Ec2PortForwardManager.class);
        final Ec2ContainerManager manager;
        final Ec2Service service;

        Node(Options options) {
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
            if (options.removeBehaviour != null) {
                options.removeBehaviour.accept(remove);
            }
            when(docker.removeContainerCmd(anyString())).thenReturn(remove);
            StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
            if (options.stopFails) {
                doThrow(new IllegalStateException("docker is unreachable")).when(stop).exec();
            }
            when(docker.stopContainerCmd(anyString())).thenReturn(stop);
            ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
            when(lifecycle.probeContainer(anyString())).thenReturn(options.liveness);
            EmulatorConfig config = config(dir, false, true);
            manager = spy(manager(config, docker, lifecycle, portForwards));
            doReturn(options.registered).when(manager).restoreMetadataRegistration(any());
            if (options.beforeStartup != null) {
                options.beforeStartup.accept(manager);
            }
            service = new Ec2Service(config, manager, portForwards, mock(AmiImageResolver.class),
                    mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                    StorageFactories.open(config, "persistent"));
            service.restoreMetadataRegistrations();
        }
    }

    private static String describedState(Node node, String id) {
        return node.service.describeInstances(REGION, List.of(id), Map.of()).getFirst().getInstances().getFirst()
                .getState().getName();
    }

    private void awaitPersisted(String id, String state) throws Exception {
        await(() -> state.equals(readState(id)),
                () -> "the stored record of " + id + " to read " + state + " but it reads " + readState(id));
    }

    private String readState(String id) {
        try {
            return persistedState(dir, id);
        } catch (IOException e) {
            return "unreadable: " + e.getMessage();
        }
    }
}
