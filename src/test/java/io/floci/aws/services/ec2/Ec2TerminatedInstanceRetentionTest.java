package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
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
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ec2.model.Volume;
import io.floci.aws.services.ec2.model.VolumeAttachment;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Terminated instances stay listed for an hour and are then removed, as the EC2 documentation
 * promises. Each test judges the stored records on disk and what DescribeInstances returns.
 */
class Ec2TerminatedInstanceRetentionTest {

    private static final String ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void aTerminatedInstanceIsListedUntilTheRetentionPassesThenRemovedFromTheStore() throws Exception {
        seed(terminated("i-expiring", NOW.minus(Duration.ofMinutes(30))));
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
        long terminatedAt = NOW.toEpochMilli();
        seed(terminated("i-boundary", NOW));
        Ec2Service service = service();

        service.pruneTerminatedInstances(Instant.ofEpochMilli(terminatedAt).plus(Ec2Service.TERMINATED_INSTANCE_RETENTION));
        assertNotNull(persisted("i-boundary"), "the hour itself has not passed yet");

        service.pruneTerminatedInstances(
                Instant.ofEpochMilli(terminatedAt).plus(Ec2Service.TERMINATED_INSTANCE_RETENTION).plusMillis(1));
        assertNull(persisted("i-boundary"));
    }

    @Test
    void activeInstancesAreNeverPruned() throws Exception {
        List<Instance> active = new ArrayList<>();
        for (InstanceState state : List.of(InstanceState.pending(), InstanceState.running(),
                InstanceState.stopping(), InstanceState.stopped(), InstanceState.shuttingDown())) {
            // The ancient time is on purpose: only a terminated record may be removed for age.
            Instance instance = instance("i-" + state.getName(), state);
            instance.setTerminatedAt(NOW.minus(Duration.ofDays(30)).toEpochMilli());
            active.add(instance);
        }
        seed(active.toArray(new Instance[0]));
        Ec2Service service = service();

        service.pruneTerminatedInstances(NOW.plus(Duration.ofDays(3650)));

        for (Instance instance : active) {
            assertNotNull(persisted(instance.getInstanceId()), instance.getInstanceId() + " must survive");
            assertTrue(listed(service, instance.getInstanceId()));
        }
    }

    @Test
    void aTerminatedRecordWithNoTimeIsNeverPrunedForAge() throws Exception {
        seed(instance("i-untimed", InstanceState.terminated()));
        Ec2Service service = service();

        service.pruneTerminatedInstances(NOW.plus(Duration.ofDays(3650)));

        assertNotNull(persisted("i-untimed"));
    }

    @Test
    void pruningRemovesEveryAccountsExpiredInstancesAndTheirTags() throws Exception {
        seed(terminated("i-default", NOW.minus(Duration.ofHours(2))));
        seedFor(OTHER_ACCOUNT, terminated("i-other", NOW.minus(Duration.ofHours(2))),
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
        seed(terminated("i-gone", NOW.minus(Duration.ofHours(2))));
        seedVolume("vol-left-attached", "i-gone");
        Ec2Service service = service();

        service.pruneTerminatedInstances(NOW);

        JsonNode volume = persistedVolume("vol-left-attached");
        assertEquals("available", volume.path("state").asText());
        assertEquals(0, volume.path("attachments").size(), "no attachment may point at a removed instance");
    }

    @Test
    void theSchedulerPrunesThroughTheServiceOnEachSweep() throws Exception {
        seed(terminated("i-swept", NOW.minus(Duration.ofHours(2))), terminated("i-recent", NOW.minusSeconds(60)));
        EmulatorConfig config = config(true);
        Ec2Service service = service(config);
        Ec2TerminatedInstanceScheduler scheduler = new Ec2TerminatedInstanceScheduler(service, config);

        assertTrue(scheduler.active(), "a background sweep exists while EC2 is enabled");
        scheduler.tick(NOW);

        assertNull(persisted("i-swept"));
        assertNotNull(persisted("i-recent"));
    }

    @Test
    void theSchedulerHasNoBackgroundSweepWhileEc2IsDisabled() {
        EmulatorConfig config = config(false);

        assertFalse(new Ec2TerminatedInstanceScheduler(service(config), config).active());
    }

    @Test
    void markTerminatedStartsTheClockOnceAndKeepsTheFirstTime() {
        Instance instance = instance("i-clock", InstanceState.running());

        instance.markTerminated(1_000L);
        instance.markTerminated(9_000L);

        assertEquals("terminated", instance.getState().getName());
        assertEquals(1_000L, instance.getTerminatedAt());
    }

    @Test
    void aFailedLaunchIsWrittenBackToTheStoreWhenItTerminates() {
        List<Instance> written = new ArrayList<>();
        Ec2ContainerManager manager = manager();
        manager.setPersister(written::add);
        Instance launching = instance("i-failed-launch", InstanceState.pending());

        assertTrue(manager.cancelLaunch(launching));

        assertEquals(List.of(launching), written, "the terminal state must be handed to the store");
        assertEquals("terminated", written.getFirst().getState().getName());
        assertTrue(written.getFirst().getTerminatedAt() > 0);
    }

    private Ec2Service service() {
        return service(config(true));
    }

    private Ec2Service service(EmulatorConfig config) {
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                StorageFactories.open(config, "persistent"));
    }

    private EmulatorConfig config(boolean enabled) {
        EmulatorConfig config = StorageFactories.config(dir, ACCOUNT);
        when(config.services().ec2().mock()).thenReturn(true);
        when(config.services().ec2().enabled()).thenReturn(enabled);
        return config;
    }

    private Ec2ContainerManager manager() {
        return new Ec2ContainerManager(
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class),
                mock(DockerHostResolver.class), mock(DockerClient.class), mock(PortAllocator.class),
                config(false), mock(Ec2MetadataServer.class), mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class), mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class), mock(ContainerReachableEndpoint.class));
    }

    private void seed(Instance... instances) {
        seedFor(ACCOUNT, instances);
    }

    private void seedFor(String account, Instance... instances) {
        StorageFactory factory = StorageFactories.open(config(true), "persistent");
        try {
            AccountAwareStorageBackend<Instance> store = factory.create(
                    "ec2", "ec2-instances.json", new TypeReference<Map<String, Instance>>() { });
            for (Instance instance : instances) {
                store.putForAccount(account, REGION + "::" + instance.getInstanceId(), instance);
            }
        } finally {
            factory.shutdownAll();
        }
    }

    private void seedVolume(String volumeId, String instanceId) {
        StorageFactory factory = StorageFactories.open(config(true), "persistent");
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
        Instance instance = instance(id, InstanceState.terminated());
        instance.setTerminatedAt(at.toEpochMilli());
        return instance;
    }

    private static Instance instance(String id, InstanceState state) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setRegion(REGION);
        instance.setState(state);
        return instance;
    }

    private static boolean listed(Ec2Service service, String id) {
        return service.describeInstances(REGION, List.of(), Map.of()).stream()
                .flatMap(reservation -> reservation.getInstances().stream())
                .anyMatch(instance -> id.equals(instance.getInstanceId()));
    }

    private JsonNode persisted(String id) throws IOException {
        return find("ec2-instances.json", id);
    }

    private JsonNode persistedVolume(String id) throws IOException {
        return find("ec2-volumes.json", id);
    }

    private JsonNode find(String file, String id) throws IOException {
        JsonNode records = JSON.readTree(Files.readString(dir.resolve(file)));
        for (Map.Entry<String, JsonNode> entry : records.properties()) {
            if (entry.getKey().endsWith("::" + id)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
