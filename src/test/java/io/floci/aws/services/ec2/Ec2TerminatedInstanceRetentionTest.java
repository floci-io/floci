package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ec2.model.Volume;
import io.floci.aws.services.ec2.model.VolumeAttachment;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.floci.aws.testing.Ec2LifecycleFixtures.ACCOUNT;
import static io.floci.aws.testing.Ec2LifecycleFixtures.REGION;
import static io.floci.aws.testing.Ec2LifecycleFixtures.await;
import static io.floci.aws.testing.Ec2LifecycleFixtures.config;
import static io.floci.aws.testing.Ec2LifecycleFixtures.instance;
import static io.floci.aws.testing.Ec2LifecycleFixtures.manager;
import static io.floci.aws.testing.Ec2LifecycleFixtures.persistedRecord;
import static io.floci.aws.testing.Ec2LifecycleFixtures.seed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Terminated instances stay listed for an hour and are then removed, as the EC2 documentation
 * promises. Each test judges the stored records on disk and what DescribeInstances returns.
 */
class Ec2TerminatedInstanceRetentionTest {

    private static final String OTHER_ACCOUNT = "111111111111";
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final long WAIT_SECONDS = 10;

    @TempDir
    Path dir;

    @Test
    void aTerminatedInstanceIsListedUntilTheRetentionPassesThenRemovedFromTheStore() throws Exception {
        seed(config(dir, true, true), ACCOUNT, terminated("i-expiring", NOW.minus(Duration.ofMinutes(30))));
        Ec2Service service = service();

        service.pruneTerminatedInstances(NOW.plus(Duration.ofMinutes(29)));
        assertTrue(listed(service, "i-expiring"), "still inside the hour");
        assertNotNull(persisted("i-expiring"));

        service.pruneTerminatedInstances(NOW.plus(Duration.ofMinutes(31)));
        assertFalse(listed(service, "i-expiring"), "past the hour");
        assertNull(persisted("i-expiring"), "and gone from the stored records");
    }

    @Test
    void theRetentionIsExactlyOneHour() throws Exception {
        seed(config(dir, true, true), ACCOUNT, terminated("i-boundary", NOW));
        Ec2Service service = service();

        service.pruneTerminatedInstances(NOW.plus(Ec2Service.TERMINATED_INSTANCE_RETENTION));
        assertNotNull(persisted("i-boundary"), "the hour itself has not passed yet");

        service.pruneTerminatedInstances(NOW.plus(Ec2Service.TERMINATED_INSTANCE_RETENTION).plusMillis(1));
        assertNull(persisted("i-boundary"));
    }

    @Test
    void activeInstancesAreNeverPruned() throws Exception {
        List<Instance> active = new ArrayList<>();
        for (InstanceState state : List.of(InstanceState.pending(), InstanceState.running(),
                InstanceState.stopping(), InstanceState.stopped(), InstanceState.shuttingDown())) {
            // The ancient time is on purpose: only a terminated record may be removed for age.
            Instance instance = instance("i-" + state.getName(), state, null);
            instance.setTerminatedAt(NOW.minus(Duration.ofDays(30)).toEpochMilli());
            active.add(instance);
        }
        seed(config(dir, true, true), ACCOUNT, active.toArray(new Instance[0]));
        Ec2Service service = service();

        service.pruneTerminatedInstances(NOW.plus(Duration.ofDays(3650)));

        for (Instance instance : active) {
            assertNotNull(persisted(instance.getInstanceId()), instance.getInstanceId() + " must survive");
            assertTrue(listed(service, instance.getInstanceId()));
        }
    }

    @Test
    void aTerminatedRecordWithNoTimeIsNeverPrunedForAge() throws Exception {
        seed(config(dir, true, true), ACCOUNT, instance("i-untimed", InstanceState.terminated(), null));
        Ec2Service service = service();

        service.pruneTerminatedInstances(NOW.plus(Duration.ofDays(3650)));

        assertNotNull(persisted("i-untimed"));
    }

    @Test
    void pruningRemovesEveryAccountsExpiredInstancesAndTheirTags() throws Exception {
        EmulatorConfig config = config(dir, true, true);
        seed(config, ACCOUNT, terminated("i-default", NOW.minus(Duration.ofHours(2))));
        seed(config, OTHER_ACCOUNT, terminated("i-other", NOW.minus(Duration.ofHours(2))),
                terminated("i-other-fresh", NOW.minus(Duration.ofMinutes(5))));
        Ec2Service service = service();
        service.createTags(REGION, List.of("i-default"), List.of(new Tag("env", "qa")));

        service.pruneTerminatedInstances(NOW);

        assertNull(persisted("i-default"));
        assertNull(persisted("i-other"), "another account's expired instance is removed too");
        assertNotNull(persisted("i-other-fresh"), "and its fresh one is kept");
        assertTrue(service.resourceTags(REGION, "i-default").isEmpty());
        assertFalse(Files.readString(dir.resolve("ec2-tags.json")).contains("i-default"),
                "the tags are gone from the stored records");
    }

    @Test
    void pruningReleasesTheVolumesAFailedLaunchLeftAttached() throws Exception {
        seed(config(dir, true, true), ACCOUNT, terminated("i-gone", NOW.minus(Duration.ofHours(2))));
        seedVolume("vol-left-attached", "i-gone");
        Ec2Service service = service();

        service.pruneTerminatedInstances(NOW);

        JsonNode volume = persistedRecord(dir, "ec2-volumes.json", "vol-left-attached");
        assertEquals("available", volume.path("state").asText());
        assertEquals(0, volume.path("attachments").size(), "no attachment may point at a removed instance");
    }

    @Test
    void aFailureRemovingOneInstanceDoesNotBlockTheOthersAndIsReportedToTheCaller() throws Exception {
        seed(config(dir, true, true), ACCOUNT,
                terminated("i-good-1", NOW.minus(Duration.ofHours(2))),
                terminated("i-bad", NOW.minus(Duration.ofHours(2))),
                terminated("i-good-2", NOW.minus(Duration.ofHours(2))));
        Ec2Service service = spy(service());
        doThrow(new IllegalStateException("the attached volumes cannot be released")).when(service)
                .pruneTerminatedInstance(argThat((AccountAwareStorageBackend.AccountEntry<Instance> entry) ->
                        "i-bad".equals(entry.value().getInstanceId())));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.pruneTerminatedInstances(NOW));

        assertTrue(failure.getMessage().contains("i-bad"), failure.getMessage());
        assertNull(persisted("i-good-1"), "one record failing must not keep the others from expiring");
        assertNull(persisted("i-good-2"));
        assertNotNull(persisted("i-bad"), "the failed record stays, to be tried again on the next sweep");
    }

    @Test
    void theSchedulerPrunesThroughTheServiceOnEachSweep() throws Exception {
        EmulatorConfig config = config(dir, true, true);
        seed(config, ACCOUNT, terminated("i-swept", NOW.minus(Duration.ofHours(2))),
                terminated("i-recent", NOW.minusSeconds(60)));
        Ec2TerminatedInstanceScheduler scheduler = new Ec2TerminatedInstanceScheduler(service(config), config);

        assertTrue(scheduler.active(), "a background sweep exists while EC2 is enabled");
        scheduler.tick(NOW);

        assertNull(persisted("i-swept"));
        assertNotNull(persisted("i-recent"));
    }

    @Test
    void theSchedulerHasNoBackgroundSweepWhileEc2IsDisabled() {
        EmulatorConfig config = config(dir, true, false);

        assertFalse(new Ec2TerminatedInstanceScheduler(service(config), config).active());
    }

    @Test
    void markTerminatedStartsTheClockOnceAndKeepsTheFirstTime() {
        Instance instance = instance("i-clock", InstanceState.running(), null);

        instance.markTerminated(1_000L);
        instance.markTerminated(9_000L);

        assertEquals("terminated", instance.getState().getName());
        assertEquals(1_000L, instance.getTerminatedAt());
    }

    @Test
    void aFailedLaunchIsWrittenBackToTheStoreWhenItTerminates() {
        List<Instance> written = new ArrayList<>();
        Ec2ContainerManager manager = quietManager();
        manager.setPersister(written::add);
        Instance launching = instance("i-failed-launch", InstanceState.pending(), null);

        assertTrue(manager.cancelLaunch(launching));

        assertEquals(List.of(launching), written, "the terminal state must be handed to the store");
        assertEquals("terminated", written.getFirst().getState().getName());
        assertTrue(written.getFirst().getTerminatedAt() > 0);
    }

    @Test
    void aWriteThatFailsIsRetriedSoTheStoredStateDoesNotStayStale() {
        List<String> written = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        Ec2ContainerManager manager = quietManager();
        manager.setPersister(instance -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("the store is busy");
            }
            written.add(instance.getState().getName());
        });

        assertTrue(manager.cancelLaunch(instance("i-retried", InstanceState.pending(), null)));

        assertEquals(List.of("terminated"), written, "the first write failed and must be made again");
    }

    @Test
    void anOlderSnapshotIsNeverTheLastStateWritten() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        when(docker.stopContainerCmd(anyString())).thenReturn(mock(StopContainerCmd.class, RETURNS_SELF));
        when(docker.removeContainerCmd(anyString())).thenReturn(mock(RemoveContainerCmd.class, RETURNS_SELF));
        Ec2ContainerManager manager = manager(config(dir, true, false), docker,
                mock(ContainerLifecycleManager.class), mock(Ec2PortForwardManager.class));
        List<String> written = new CopyOnWriteArrayList<>();
        CountDownLatch stopSnapshotTaken = new CountDownLatch(1);
        CountDownLatch commitTheStopSnapshot = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        // A store that, like the write-ahead log, takes its snapshot of the instance first and
        // commits it later, so a slow writer can commit an older snapshot after a newer one.
        manager.setPersister(instance -> {
            String snapshot = instance.getState().getName();
            if (writes.getAndIncrement() == 0) {
                stopSnapshotTaken.countDown();
                try {
                    commitTheStopSnapshot.await(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            written.add(snapshot);
        });
        Instance instance = instance("i-race", InstanceState.running(), "container-race");
        try {
            manager.stop(instance);
            assertTrue(stopSnapshotTaken.await(WAIT_SECONDS, TimeUnit.SECONDS), "the stop's write must be waiting");
            manager.terminate(instance, null);
            await(() -> written.contains("terminated"), "the termination to be written");

            commitTheStopSnapshot.countDown();

            await(() -> written.size() >= 2 && "terminated".equals(written.getLast()),
                    () -> "the last write to be the termination, but the writes were " + written);
        } finally {
            manager.stop();
        }
    }

    private Ec2ContainerManager quietManager() {
        return manager(config(dir, true, false), mock(DockerClient.class),
                mock(ContainerLifecycleManager.class), mock(Ec2PortForwardManager.class));
    }

    private Ec2Service service() {
        return service(config(dir, true, true));
    }

    private Ec2Service service(EmulatorConfig config) {
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                StorageFactories.open(config, "persistent"));
    }

    private void seedVolume(String volumeId, String instanceId) {
        StorageFactory factory = StorageFactories.open(config(dir, true, true), "persistent");
        try {
            AccountAwareStorageBackend<Volume> store = factory.create(
                    "ec2", "ec2-volumes.json", new TypeReference<Map<String, Volume>>() { });
            Volume volume = new Volume();
            volume.setVolumeId(volumeId);
            volume.setRegion(REGION);
            volume.setState("in-use");
            VolumeAttachment attachment = new VolumeAttachment();
            attachment.setInstanceId(instanceId);
            attachment.setDevice("/dev/sdf");
            attachment.setState("attached");
            volume.getAttachments().add(attachment);
            store.put(REGION + "::" + volumeId, volume);
        } finally {
            factory.shutdownAll();
        }
    }

    private static Instance terminated(String id, Instant at) {
        Instance instance = instance(id, InstanceState.terminated(), null);
        instance.setTerminatedAt(at.toEpochMilli());
        return instance;
    }

    private static boolean listed(Ec2Service service, String id) {
        return service.describeInstances(REGION, List.of(), Map.of()).stream()
                .flatMap(reservation -> reservation.getInstances().stream())
                .anyMatch(instance -> id.equals(instance.getInstanceId()));
    }

    private JsonNode persisted(String id) throws IOException {
        return persistedRecord(dir, "ec2-instances.json", id);
    }
}
