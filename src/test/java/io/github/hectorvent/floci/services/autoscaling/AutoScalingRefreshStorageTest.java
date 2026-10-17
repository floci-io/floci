package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceRefresh;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetHealth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingRefreshStorageTest {
    @TempDir Path storagePath;
    private final List<StorageFactory> factories = new ArrayList<>();
    private static final String REGION = "us-east-1";
    private AutoScalingService service;
    private Ec2Service ec2;
    private AutoScalingReconciler reconciler;
    private AutoScalingGroup group;

    private void setUp(String mode) {
        service = storedService(mode);
        service.createAutoScalingGroup(REGION, "suspended-group", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        group = service.describeAutoScalingGroups(REGION, List.of("suspended-group")).getFirst();
        AsgInstance existing = new AsgInstance();
        existing.setInstanceId("i-original");
        existing.setLifecycleState("InService");
        existing.setHealthStatus("Healthy");
        existing.setLaunchTemplateId("lt-original");
        existing.setLaunchTemplateVersion("1");
        group.getInstances().add(existing);
        service.saveAutoScalingGroupIfPresent(group);
        ec2 = mock(Ec2Service.class);
        service.ec2Service = ec2;
        reconciler = new AutoScalingReconciler(service, ec2, mock(ElbV2Service.class));
        when(ec2.isInstanceContainerRunning(anyString())).thenReturn(true);
        when(ec2.describeInstances(eq(REGION), anyList(), isNull()))
                .thenReturn(List.of(reservation("i-original")));
        LaunchTemplate template = new LaunchTemplate();
        template.setLaunchTemplateId("lt-original");
        template.setLatestVersionNumber("1");
        template.getData().setImageId("ami-original");
        template.getData().setInstanceType("t3.micro");
        when(ec2.describeLaunchTemplates(eq(REGION), anyList(), anyList(), anyMap())).thenReturn(List.of(template));
        when(ec2.describeLaunchTemplateVersions(eq(REGION), eq("lt-original"), isNull(), anyList()))
                .thenReturn(List.of(template));
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(reservation("i-replacement"));
    }

    private AutoScalingService storedService(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        EmulatorConfig.WalConfig wal = mock(EmulatorConfig.WalConfig.class);
        when(config.storage()).thenReturn(storage);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(storage.persistentPath()).thenReturn(storagePath.toString());
        when(storage.wal()).thenReturn(wal);
        when(wal.compactionIntervalMs()).thenReturn(60000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("autoscaling")).thenReturn(mode);
        when(access.storageFlushInterval("autoscaling")).thenReturn(60000L);
        StorageFactory factory = new StorageFactory(config, access);
        factories.add(factory);
        AutoScalingService created = new AutoScalingService();
        created.regionResolver = new RegionResolver(REGION, "000000000000");
        created.storageFactory = factory;
        created.initializeStorage();
        return created;
    }

    private void restart(String mode) throws IOException {
        AutoScalingGroup previous = group;
        factories.removeFirst().shutdownAll();
        try (Stream<Path> files = Files.list(storagePath)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains("autoscaling-groups")),
                    "No real group snapshot was written");
        }
        service = storedService(mode);
        service.ec2Service = ec2;
        group = service.describeAutoScalingGroups(REGION, List.of("suspended-group")).getFirst();
        assertNotSame(previous, group, "Restart reused the in-memory group");
        reconciler = new AutoScalingReconciler(service, ec2, mock(ElbV2Service.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void replacementWithoutDeclaredZoneUsesTheAcceptedEc2Placement(String mode) throws IOException {
        setUp(mode);
        group.setAvailabilityZones(List.of());
        service.saveAutoScalingGroupIfPresent(group);
        when(ec2.describeInstances(REGION, List.of(), null)).thenReturn(List.of());
        Reservation accepted = reservation("i-replacement");
        accepted.getInstances().getFirst().setPlacement(new Placement("us-east-1b"));
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(accepted);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        reconciler.reconcile(group);
        assertEquals("i-replacement", group.getInstances().getFirst().getInstanceId());
        assertEquals("us-east-1b", group.getInstances().getFirst().getAvailabilityZone());
        assertEquals("i-replacement", refresh.getReplacements().getFirst().getReplacementInstanceId());
        restart(mode);
        assertEquals("us-east-1b", group.getInstances().getFirst().getAvailabilityZone());
    }

    @AfterEach
    void closeStorage() {
        factories.forEach(StorageFactory::shutdownAll);
    }

    @ParameterizedTest
    @CsvSource({
        "persistent,Launch,true", "persistent,Terminate,true", "persistent,InstanceRefresh,true",
        "persistent,Launch,false", "persistent,Terminate,false", "persistent,InstanceRefresh,false",
        "hybrid,Launch,true", "hybrid,Terminate,true", "hybrid,InstanceRefresh,true",
        "hybrid,Launch,false", "hybrid,Terminate,false", "hybrid,InstanceRefresh,false",
        "wal,Launch,true", "wal,Terminate,true", "wal,InstanceRefresh,true",
        "wal,Launch,false", "wal,Terminate,false", "wal,InstanceRefresh,false"
    })
    void refreshPauseSurvivesRealStorageRestart(String mode, String process, boolean suspendBefore) throws IOException {
        setUp(mode);
        if (suspendBefore) {
            service.suspendProcesses(REGION, "suspended-group", List.of(process));
        }
        InstanceRefresh started = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        if (!suspendBefore) {
            service.suspendProcesses(REGION, "suspended-group", List.of(process));
        }
        restart(mode);
        InstanceRefresh restored = service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst();
        assertNotSame(started, restored);
        assertEquals(started.getInstanceRefreshId(), restored.getInstanceRefreshId());
        assertTrue(group.getSuspendedProcesses().contains(process));
        reconciler.reconcile(group);
        assertAll(
                () -> verify(ec2, never()).terminateInstances(anyString(), anyList()),
                () -> verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(),
                        any(), any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()),
                () -> assertEquals(List.of("i-original"), group.getInstances().stream()
                        .map(AsgInstance::getInstanceId).toList()),
                () -> assertNotEquals("Successful", service.describeInstanceRefreshes(
                        REGION, "suspended-group", null, null, null).instanceRefreshes().getFirst().getStatus()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void failedReplacementLaunchRemainsPausedAfterRestart(String mode) throws IOException {
        setUp(mode);
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IllegalStateException("launch unavailable"))
                .thenReturn(reservation("i-replacement"));
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        assertTrue(group.getInstances().isEmpty());
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        restart(mode);
        InstanceRefresh restored = service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst();
        assertEquals("InProgress", restored.getStatus());
        assertEquals(1, restored.getInstancesToUpdate());
        reconciler.reconcile(group);
        assertAll(
                () -> verify(ec2, times(1)).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(),
                        anyList(), any(), any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()),
                () -> assertTrue(group.getInstances().isEmpty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void resumedRefreshReplacesOriginalAfterRestart(String mode) throws IOException {
        setUp(mode);
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        restart(mode);
        service.resumeProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        assertEquals(List.of("i-replacement"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void manualTerminationDuringPausedRefreshStillRunsAfterRestart(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        AsgInstance manual = new AsgInstance();
        manual.setInstanceId("i-manual");
        manual.setLifecycleState("InService");
        manual.setHealthStatus("Healthy");
        group.getInstances().add(manual);
        group.setDesiredCapacity(2);
        service.saveAutoScalingGroupIfPresent(group);
        service.terminateInstanceInAutoScalingGroup(REGION, "i-manual", true);
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        restart(mode);
        reconciler.reconcile(group);
        assertAll(
                () -> verify(ec2).terminateInstances(REGION, List.of("i-manual")),
                () -> assertEquals(List.of("i-original"), group.getInstances().stream()
                        .map(AsgInstance::getInstanceId).toList()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void manualTerminationStillRunsAfterRestart(String mode) throws IOException {
        setUp(mode);
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        service.terminateInstanceInAutoScalingGroup(REGION, "i-original", true);
        restart(mode);
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        assertTrue(group.getInstances().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void failedReplacementRecordsActivityAndRetainsRetryOwnership(String mode) throws IOException {
        setUp(mode);
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new AwsException(
                        "InsufficientInstanceCapacity", "replacement capacity unavailable", 500))
                .thenThrow(new AwsException("InsufficientInstanceCapacity", "replacement capacity unavailable", 500))
                .thenThrow(new AwsException("RequestLimitExceeded", "replacement throttled", 503))
                .thenReturn(reservation("i-replacement"));
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        String token = refresh.getReplacements().getFirst().getLaunchClientToken();
        reconciler.reconcile(group);
        assertTrue(group.getInstances().isEmpty());
        assertEquals("InProgress", refresh.getStatus());
        assertEquals(token, refresh.getReplacements().getFirst().getLaunchClientToken());
        List<ScalingActivity> failed = service.describeScalingActivities(REGION, "suspended-group").stream()
                .filter(activity -> "Failed".equals(activity.getStatusCode())).toList();
        assertEquals(1, failed.size());
        assertEquals("replacement capacity unavailable", failed.getFirst().getStatusMessage());
        assertEquals(100, failed.getFirst().getProgress());
        assertNotNull(failed.getFirst().getEndTime());
        reconciler.reconcile(group);
        assertEquals(1, service.describeScalingActivities(REGION, "suspended-group").stream()
                .filter(activity -> "Failed".equals(activity.getStatusCode())).count());
        reconciler.reconcile(group);
        assertEquals(List.of("replacement throttled", "replacement capacity unavailable"),
                service.describeScalingActivities(REGION, "suspended-group").stream()
                        .filter(activity -> "Failed".equals(activity.getStatusCode()))
                        .map(ScalingActivity::getStatusMessage).toList());
        restart(mode);
        assertEquals(2, service.describeScalingActivities(REGION, "suspended-group").stream()
                .filter(activity -> "Failed".equals(activity.getStatusCode())).count());
        reconciler.reconcile(group);
        reconciler.reconcile(group);
        verify(ec2, times(4)).runInstances(eq(REGION), any(), any(), eq(1), eq(1), any(), anyList(), any(),
                eq(token), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        assertEquals("Successful", service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void failedReplacementAllowsSiblingProgressWithOneInventoryScan(String mode) {
        setUp(mode);
        group.setDesiredCapacity(2);
        AsgInstance second = new AsgInstance();
        second.setInstanceId("i-second-original");
        second.setLifecycleState("InService");
        second.setHealthStatus("Healthy");
        group.getInstances().add(second);
        service.saveAutoScalingGroupIfPresent(group);
        Reservation unrelated = reservation("i-unrelated");
        unrelated.getInstances().getFirst().setClientToken("unrelated-attempt");
        when(ec2.describeInstances(REGION, List.of(), null)).thenReturn(List.of(unrelated));
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any()))
                .thenThrow(new IllegalStateException("first replacement launch unavailable"))
                .thenReturn(reservation("i-sibling"))
                .thenReturn(reservation("i-retry"));
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        String token = refresh.getReplacements().getFirst().getLaunchClientToken();
        reconciler.reconcile(group);
        assertEquals(List.of("i-sibling"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
        assertEquals("InProgress", refresh.getStatus());
        assertEquals(token, refresh.getReplacements().getFirst().getLaunchClientToken());
        assertEquals("first replacement launch unavailable", service.describeScalingActivities(REGION, "suspended-group")
                .stream().filter(activity -> "Failed".equals(activity.getStatusCode())).findFirst().orElseThrow()
                .getStatusMessage());
        verify(ec2, times(1)).describeInstances(REGION, List.of(), null);
        reconciler.reconcile(group);
        verify(ec2, times(2)).describeInstances(REGION, List.of(), null);
        assertEquals(List.of("i-sibling", "i-retry"),
                group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
        reconciler.reconcile(group);
        assertEquals("Successful", refresh.getStatus());
        verify(ec2, times(2)).describeInstances(REGION, List.of(), null);
    }

    @Test
    void refreshCleanupFailureRetainsOwnershipAndPropagates() {
        setUp("persistent");
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        String token = refresh.getReplacements().getFirst().getLaunchClientToken();
        AutoScalingService intercepted = spy(service);
        doReturn(false).when(intercepted).saveAutoScalingGroupIfPresent(group);
        IllegalStateException cleanupFailure = new IllegalStateException("cleanup refused");
        when(ec2.terminateInstances(REGION, List.of("i-replacement"))).thenThrow(cleanupFailure);
        AutoScalingReconciler worker = new AutoScalingReconciler(intercepted, ec2, mock(ElbV2Service.class));
        assertSame(cleanupFailure, assertThrows(IllegalStateException.class, () -> worker.reconcile(group)));
        assertEquals(token, refresh.getReplacements().getFirst().getLaunchClientToken());
        assertEquals("InProgress", refresh.getStatus());
        assertNull(refresh.getReplacements().getFirst().getReplacementInstanceId());
        assertEquals(List.of("i-replacement"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void launchRetryResumesWithSavedToken(String mode) throws IOException {
        setUp(mode);
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenThrow(new IllegalStateException("launch unavailable"))
                .thenReturn(reservation("i-replacement"));
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        String token = refresh.getReplacements().getFirst().getLaunchClientToken();
        reconciler.reconcile(group);
        restart(mode);
        reconciler.reconcile(group);
        reconciler.reconcile(group);
        verify(ec2, times(2)).runInstances(eq(REGION), any(), any(), eq(1), eq(1), any(), anyList(), any(),
                eq(token), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        verify(ec2, times(1)).terminateInstances(REGION, List.of("i-original"));
        assertEquals("Successful", service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void recoversEc2LaunchWhoseMembershipWasNotSaved(String mode) throws IOException {
        setUp(mode);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        Reservation launched = reservation("i-recovered");
        launched.getInstances().getFirst().setClientToken(refresh.getReplacements().getFirst().getLaunchClientToken());
        when(ec2.describeInstances(REGION, List.of(), null)).thenReturn(List.of(launched));
        group.getInstances().clear();
        service.saveAutoScalingGroupIfPresent(group);
        restart(mode);
        reconciler.reconcile(group);
        assertEquals(List.of("i-recovered"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        reconciler.reconcile(group);
        assertEquals(1, group.getInstances().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void terminatedInstanceDropsOutOfActiveRefresh(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        service.terminateInstanceInAutoScalingGroup(REGION, "i-original", true);
        restart(mode);
        AutoScalingGroup restored = service.describeAutoScalingGroups(REGION, List.of("suspended-group")).getFirst();
        InstanceRefresh refresh = service.activeInstanceRefresh(restored);
        assertNotNull(refresh);
        assertTrue(refresh.getReplacements().isEmpty(), "terminated instance must leave the refresh");
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void explicitTerminationOfSelectedOriginalIsNotPaused(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        service.terminateInstanceInAutoScalingGroup(REGION, "i-original", true);
        restart(mode);
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        assertTrue(group.getInstances().isEmpty());
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void pausedRefreshDoesNotBlockIndependentCapacityIncrease(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        group.setDesiredCapacity(2);
        service.saveAutoScalingGroupIfPresent(group);
        restart(mode);
        reconciler.reconcile(group);
        verify(ec2, never()).terminateInstances(anyString(), anyList());
        verify(ec2).runInstances(eq(REGION), any(), any(), eq(1), eq(1), any(), anyList(), any(), isNull(),
                anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        assertEquals(2, group.getInstances().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void capacityReductionDoesNotLaunchUnneededReplacement(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        group.setDesiredCapacity(0);
        service.saveAutoScalingGroupIfPresent(group);
        restart(mode);
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        assertTrue(group.getInstances().isEmpty());
        assertEquals("Successful", service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void missingOriginalRetainsPausedReplacementReservation(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        when(ec2.describeInstances(REGION, List.of("i-original"), null)).thenReturn(List.of());
        restart(mode);
        reconciler.reconcile(group);
        assertTrue(group.getInstances().isEmpty());
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        service.resumeProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        restart(mode);
        reconciler.reconcile(group);
        assertEquals(List.of("i-replacement"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void recoveredLaunchAlreadyInGroupDoesNotDuplicateMembership(String mode) throws IOException {
        setUp(mode);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        Reservation launched = reservation("i-recovered");
        launched.getInstances().getFirst().setClientToken(refresh.getReplacements().getFirst().getLaunchClientToken());
        when(ec2.describeInstances(REGION, List.of(), null)).thenReturn(List.of(launched));
        group.getInstances().clear();
        AsgInstance member = new AsgInstance();
        member.setInstanceId("i-recovered");
        member.setLifecycleState("Pending");
        member.setHealthStatus("Healthy");
        group.getInstances().add(member);
        service.saveAutoScalingGroupIfPresent(group);
        restart(mode);
        reconciler.reconcile(group);
        assertEquals(1, group.getInstances().size());
        InstanceRefresh restored = service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst();
        assertEquals("i-recovered", restored.getReplacements().getFirst().getReplacementInstanceId());
        assertEquals("Successful", restored.getStatus());
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"persistent,Launch", "persistent,Terminate", "persistent,InstanceRefresh",
            "hybrid,Launch", "hybrid,Terminate", "hybrid,InstanceRefresh",
            "wal,Launch", "wal,Terminate", "wal,InstanceRefresh"})
    void pausedOriginalKeepsTargetRegistrationUntilResume(String mode, String process) throws IOException {
        setUp(mode);
        group.setTargetGroupARNs(List.of("test-target-group"));
        service.saveAutoScalingGroupIfPresent(group);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        service.suspendProcesses(REGION, "suspended-group", List.of(process));
        restart(mode);
        ElbV2Service elb = mock(ElbV2Service.class);
        TargetDescription target = new TargetDescription();
        target.setId("i-original");
        TargetHealth health = new TargetHealth();
        health.setTarget(target);
        health.setState("healthy");
        when(elb.describeTargetHealth(REGION, "test-target-group", List.of())).thenReturn(List.of(health));
        reconciler = new AutoScalingReconciler(service, ec2, elb);
        reconciler.reconcile(group);
        verify(elb, never()).deregisterTargets(anyString(), anyString(), anyList());
        verify(ec2, never()).terminateInstances(anyString(), anyList());
        service.resumeProcesses(REGION, "suspended-group", List.of(process));
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        verify(elb, atLeastOnce()).deregisterTargets(eq(REGION), eq("test-target-group"),
                argThat(targets -> targets.stream().anyMatch(value -> "i-original".equals(value.getId()))));
        assertEquals(List.of("i-replacement"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @ParameterizedTest
    @CsvSource({"persistent,false,false", "persistent,false,true", "persistent,true,false", "persistent,true,true",
            "hybrid,false,false", "hybrid,false,true", "hybrid,true,false", "hybrid,true,true",
            "wal,false,false", "wal,false,true", "wal,true,false", "wal,true,true"})
    void detachedInstancesAreNotReadoptedAfterRestart(String mode, boolean replacement, boolean decrement)
            throws IOException {
        setUp(mode);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        String detachedId = replacement ? "i-detached" : "i-original";
        if (replacement) {
            group.getInstances().clear();
            AsgInstance member = new AsgInstance();
            member.setInstanceId(detachedId);
            member.setLifecycleState("InService");
            member.setHealthStatus("Healthy");
            group.getInstances().add(member);
            refresh.getReplacements().getFirst().setReplacementInstanceId(detachedId);
            refresh.getReplacements().getFirst().setPhase("Warming");
            service.saveInstanceRefresh(refresh);
            service.saveAutoScalingGroupIfPresent(group);
            Reservation launched = reservation(detachedId);
            launched.getInstances().getFirst().setClientToken(refresh.getReplacements().getFirst().getLaunchClientToken());
            when(ec2.describeInstances(eq(REGION), any(), isNull())).thenReturn(List.of(launched));
        }
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        service.detachInstances(REGION, "suspended-group", List.of(detachedId), decrement);
        restart(mode);
        InstanceRefresh restored = service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst();
        assertTrue(restored.getReplacements().isEmpty(), "Explicit detach retained refresh ownership");
        service.resumeProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        reconciler.reconcile(group);
        reconciler.reconcile(group);
        assertEquals(decrement ? 0 : 1, group.getDesiredCapacity());
        assertEquals(decrement ? 0 : 1, group.getInstances().size());
        assertTrue(group.getInstances().stream().noneMatch(member -> detachedId.equals(member.getInstanceId())));
        verify(ec2, never()).terminateInstances(anyString(), argThat(ids -> ids.contains(detachedId)));
        if (decrement) {
            verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                    any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        }
        assertEquals("Successful", restored.getStatus());
    }

    @ParameterizedTest
    @CsvSource({"persistent,false,false", "persistent,true,false", "hybrid,false,false", "hybrid,true,false",
            "wal,false,false", "wal,true,false", "persistent,false,true", "persistent,true,true",
            "hybrid,false,true", "hybrid,true,true", "wal,false,true", "wal,true,true"})
    void recoveredLaunchRetainsAttemptSourceAfterGroupChanges(String mode, boolean sourceDeleted,
                                                             boolean defaultPlacement) throws IOException {
        setUp(mode);
        if (defaultPlacement) {
            group.setAvailabilityZones(List.of());
            service.saveAutoScalingGroupIfPresent(group);
        }
        List<Reservation> launched = new ArrayList<>();
        when(ec2.describeInstances(REGION, List.of(), null)).thenAnswer(invocation -> List.copyOf(launched));
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any())).thenAnswer(invocation -> {
                    Reservation accepted = reservation("i-accepted");
                    accepted.getInstances().getFirst().setClientToken(invocation.getArgument(8));
                    accepted.getInstances().getFirst().setInstanceType("t3.micro");
                    accepted.getInstances().getFirst().setPlacement(new Placement("us-east-1a"));
                    launched.add(accepted);
                    throw new IllegalStateException("EC2 accepted launch but response was lost");
                });
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        reconciler.reconcile(group);
        assertTrue(group.getInstances().isEmpty());
        assertEquals(1, launched.size());
        group.setLaunchTemplateId("lt-changed");
        group.setAvailabilityZones(List.of("us-east-1b"));
        service.saveAutoScalingGroupIfPresent(group);
        LaunchTemplate changed = new LaunchTemplate();
        changed.setLaunchTemplateId("lt-changed");
        changed.setLatestVersionNumber("2");
        changed.getData().setImageId("ami-changed");
        changed.getData().setInstanceType("m5.large");
        when(ec2.describeLaunchTemplates(eq(REGION), anyList(), anyList(), anyMap()))
                .thenReturn(sourceDeleted ? List.of() : List.of(changed));
        when(ec2.describeLaunchTemplateVersions(eq(REGION), eq("lt-changed"), isNull(), anyList()))
                .thenReturn(sourceDeleted ? List.of() : List.of(changed));
        restart(mode);
        reconciler.reconcile(group);
        assertEquals(List.of("i-accepted"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
        AsgInstance recovered = group.getInstances().getFirst();
        assertAll(() -> assertEquals("lt-original", recovered.getLaunchTemplateId()),
                () -> assertEquals("1", recovered.getLaunchTemplateVersion()),
                () -> assertEquals("t3.micro", recovered.getInstanceType()),
                () -> assertEquals("us-east-1a", recovered.getAvailabilityZone()));
        verify(ec2, times(1)).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void legacyRecoveredLaunchUsesOnlyKnownEc2Metadata(String mode) throws IOException {
        setUp(mode);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        Reservation launched = reservation("i-legacy");
        Instance instance = launched.getInstances().getFirst();
        instance.setClientToken(refresh.getReplacements().getFirst().getLaunchClientToken());
        instance.setInstanceType("m5.large");
        instance.setPlacement(new Placement("us-east-1b"));
        when(ec2.describeInstances(REGION, List.of(), null)).thenReturn(List.of(launched));
        group.getInstances().clear();
        service.saveAutoScalingGroupIfPresent(group);
        restart(mode);
        reconciler.reconcile(group);
        AsgInstance recovered = group.getInstances().getFirst();
        assertAll(() -> assertEquals("i-legacy", recovered.getInstanceId()),
                () -> assertEquals("m5.large", recovered.getInstanceType()),
                () -> assertEquals("us-east-1b", recovered.getAvailabilityZone()),
                () -> assertNull(recovered.getLaunchConfigurationName()),
                () -> assertNull(recovered.getLaunchTemplateId()),
                () -> assertNull(recovered.getLaunchTemplateName()),
                () -> assertNull(recovered.getLaunchTemplateVersion()));
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void partialMultiInstanceRefreshResumesWithoutRepeatingCompletedLaunch(String mode) throws IOException {
        setUp(mode);
        AsgInstance second = new AsgInstance();
        second.setInstanceId("i-second");
        second.setLifecycleState("InService");
        second.setHealthStatus("Healthy");
        group.getInstances().add(second);
        group.setDesiredCapacity(2);
        service.saveAutoScalingGroupIfPresent(group);
        when(ec2.describeInstances(REGION, List.of(), null)).thenReturn(List.of());
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(reservation("i-first-replacement"))
                .thenThrow(new IllegalStateException("Second launch unavailable"))
                .thenReturn(reservation("i-second-replacement"));
        InstanceRefresh started = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        assertEquals(2, started.getReplacements().size());
        assertNotEquals(started.getReplacements().getFirst().getLaunchClientToken(),
                started.getReplacements().getLast().getLaunchClientToken());
        reconciler.reconcile(group);
        assertEquals(List.of("i-first-replacement"), group.getInstances().stream()
                .map(AsgInstance::getInstanceId).toList());
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        restart(mode);
        reconciler.reconcile(group);
        InstanceRefresh restored = service.activeInstanceRefresh(group);
        assertNotNull(restored);
        assertEquals(1, restored.getInstancesToUpdate());
        assertEquals("Complete", restored.getReplacements().getFirst().getPhase());
        verify(ec2, times(2)).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        service.resumeProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        reconciler.reconcile(group);
        reconciler.reconcile(group);
        assertEquals("Successful", restored.getStatus());
        assertEquals(0, restored.getInstancesToUpdate());
        assertEquals(List.of("i-first-replacement", "i-second-replacement"), group.getInstances().stream()
                .map(AsgInstance::getInstanceId).toList());
        verify(ec2, times(3)).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        verify(ec2).terminateInstances(REGION, List.of("i-original", "i-second"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void legacyNullOwnershipRetainsExistingCleanupAfterRestart(String mode) throws IOException {
        setUp(mode);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        refresh.setReplacements(null);
        service.saveInstanceRefresh(refresh);
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        restart(mode);
        InstanceRefresh restored = service.activeInstanceRefresh(group);
        assertNotNull(restored);
        assertNull(restored.getReplacements());
        reconciler.reconcile(group);
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        assertEquals(List.of("i-replacement"), group.getInstances().stream()
                .map(AsgInstance::getInstanceId).toList());
        assertEquals("Successful", restored.getStatus());
        assertNull(restored.getReplacements(), "Legacy state was heuristically assigned new ownership");
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void sameNameOtherRegionRefreshDoesNotOwnLocalInstances(String mode) throws IOException {
        setUp(mode);
        String otherRegion = "us-west-2";
        LaunchTemplate otherTemplate = new LaunchTemplate();
        otherTemplate.setLaunchTemplateId("lt-other");
        otherTemplate.getData().setImageId("ami-other");
        when(ec2.describeLaunchTemplateVersions(eq(otherRegion), eq("lt-other"), isNull(), anyList()))
                .thenReturn(List.of(otherTemplate));
        service.createAutoScalingGroup(otherRegion, "suspended-group", null, "lt-other", null, "1", null,
                0, 3, 1, 300, List.of("us-west-2a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        AutoScalingGroup other = service.describeAutoScalingGroups(otherRegion, List.of("suspended-group"))
                .getFirst();
        AsgInstance otherMember = new AsgInstance();
        otherMember.setInstanceId("i-original");
        otherMember.setLifecycleState("InService");
        otherMember.setHealthStatus("Healthy");
        other.getInstances().add(otherMember);
        service.saveAutoScalingGroupIfPresent(other);
        service.suspendProcesses(otherRegion, "suspended-group", List.of("InstanceRefresh"));
        service.startInstanceRefresh(otherRegion, "suspended-group", new InstanceRefresh());
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        service.terminateInstanceInAutoScalingGroup(REGION, "i-original", true);
        restart(mode);
        reconciler.reconcile(group);
        assertTrue(group.getInstances().isEmpty());
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        AutoScalingGroup restoredOther = service.describeAutoScalingGroups(otherRegion,
                List.of("suspended-group")).getFirst();
        InstanceRefresh otherRefresh = service.activeInstanceRefresh(restoredOther);
        assertNotNull(otherRefresh);
        assertEquals("InProgress", otherRefresh.getStatus());
        assertEquals(1, otherRefresh.getReplacements().size());
        assertEquals("i-original", otherRefresh.getReplacements().getFirst().getOriginalInstanceId());
        assertEquals(1, restoredOther.getInstances().size());
        verify(ec2, never()).terminateInstances(eq(otherRegion), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    @SuppressWarnings("unchecked")
    void pausedSelectionCannotBeTerminatedBeforeOwnershipPublication(String mode) throws Exception {
        setUp(mode);
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        service.createAutoScalingGroup(REGION, "unrelated-group", null, "lt-original", null, "1", null,
                0, 3, 0, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        AutoScalingGroup unrelated = service.describeAutoScalingGroups(REGION, List.of("unrelated-group")).getFirst();
        Field field = AutoScalingService.class.getDeclaredField("instanceRefreshes");
        field.setAccessible(true);
        Map<String, InstanceRefresh> original = (Map<String, InstanceRefresh>) field.get(service);
        Map<String, InstanceRefresh> intercepted = spy(original);
        CountDownLatch selected = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        CountDownLatch reconciling = new CountDownLatch(1);
        doAnswer(invocation -> {
            selected.countDown();
            assertTrue(publish.await(5, TimeUnit.SECONDS));
            return original.put(invocation.getArgument(0), invocation.getArgument(1));
        }).when(intercepted).put(anyString(), any(InstanceRefresh.class));
        field.set(service, intercepted);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        Future<?> starting = executor.submit(() ->
                service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh()));
        Future<?> reconcilingFuture = null;
        try {
            assertTrue(selected.await(5, TimeUnit.SECONDS));
            reconcilingFuture = executor.submit(() -> {
                reconciling.countDown();
                reconciler.reconcile(group);
            });
            assertTrue(reconciling.await(5, TimeUnit.SECONDS));
            try {
                reconcilingFuture.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                // Correct group coordination waits for ownership publication before reconciliation.
            }
            executor.submit(() -> reconciler.reconcile(unrelated)).get(5, TimeUnit.SECONDS);
            verify(ec2, never()).terminateInstances(anyString(), anyList());
        } finally {
            publish.countDown();
            try {
                starting.get(5, TimeUnit.SECONDS);
                if (reconcilingFuture != null) {
                    reconcilingFuture.get(5, TimeUnit.SECONDS);
                }
            } finally {
                field.set(service, original);
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"persistent,true", "persistent,false", "hybrid,true", "hybrid,false", "wal,true", "wal,false"})
    @SuppressWarnings("unchecked")
    void explicitRemovalWaitsForRefreshOwnershipPublication(String mode, boolean detach) throws Exception {
        setUp(mode);
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        Field field = AutoScalingService.class.getDeclaredField("instanceRefreshes");
        field.setAccessible(true);
        Map<String, InstanceRefresh> original = (Map<String, InstanceRefresh>) field.get(service);
        Map<String, InstanceRefresh> intercepted = spy(original);
        CountDownLatch selected = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        doAnswer(invocation -> {
            selected.countDown();
            assertTrue(publish.await(5, TimeUnit.SECONDS));
            return original.put(invocation.getArgument(0), invocation.getArgument(1));
        }).when(intercepted).put(anyString(), any(InstanceRefresh.class));
        field.set(service, intercepted);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> starting = executor.submit(() ->
                service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh()));
        Future<?> removing = null;
        try {
            assertTrue(selected.await(5, TimeUnit.SECONDS));
            CountDownLatch removalStarted = new CountDownLatch(1);
            removing = executor.submit(() -> {
                removalStarted.countDown();
                if (detach) {
                    service.detachInstances(REGION, "suspended-group", List.of("i-original"), true);
                } else {
                    service.terminateInstanceInAutoScalingGroup(REGION, "i-original", true);
                }
            });
            assertTrue(removalStarted.await(5, TimeUnit.SECONDS));
            try {
                removing.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                // Removal may wait for the shared group monitor before releasing ownership.
            }
        } finally {
            publish.countDown();
            try {
                starting.get(5, TimeUnit.SECONDS);
                if (removing != null) {
                    removing.get(5, TimeUnit.SECONDS);
                }
            } finally {
                field.set(service, original);
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        InstanceRefresh refresh = service.activeInstanceRefresh(group);
        assertNotNull(refresh);
        assertTrue(refresh.getReplacements().isEmpty(), "Explicit removal retained refresh ownership");
        restart(mode);
        reconciler.reconcile(group);
        assertTrue(group.getInstances().isEmpty());
        if (detach) {
            verify(ec2, never()).terminateInstances(anyString(), anyList());
        } else {
            verify(ec2).terminateInstances(REGION, List.of("i-original"));
        }
    }

    @ParameterizedTest
    @CsvSource({"persistent,true", "persistent,false", "hybrid,true", "hybrid,false", "wal,true", "wal,false"})
    void detachWaitsForReplacementIdentityPublication(String mode, boolean decrement) throws Exception {
        setUp(mode);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        service = spy(service);
        reconciler = new AutoScalingReconciler(service, ec2, mock(ElbV2Service.class));
        CountDownLatch launched = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (group.getInstances().stream().anyMatch(i -> "i-replacement".equals(i.getInstanceId()))
                    && refresh.getReplacements().getFirst().getReplacementInstanceId() == null) {
                launched.countDown();
                assertTrue(publish.await(5, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(service).saveAutoScalingGroupIfPresent(group);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> reconciling = executor.submit(() -> reconciler.reconcile(group));
        Future<?> detaching = null;
        try {
            assertTrue(launched.await(5, TimeUnit.SECONDS));
            CountDownLatch detachStarted = new CountDownLatch(1);
            detaching = executor.submit(() -> {
                detachStarted.countDown();
                service.detachInstances(REGION, "suspended-group", List.of("i-replacement"), decrement);
            });
            assertTrue(detachStarted.await(5, TimeUnit.SECONDS));
            try {
                detaching.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                // Detach may wait until the replacement ID is published under the group monitor.
            }
        } finally {
            publish.countDown();
            try {
                reconciling.get(5, TimeUnit.SECONDS);
                if (detaching != null) {
                    detaching.get(5, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertTrue(group.getInstances().isEmpty());
        assertTrue(refresh.getReplacements().isEmpty(), "Detached replacement retained refresh ownership");
        restart(mode);
        assertTrue(service.activeInstanceRefresh(group).getReplacements().isEmpty());
        assertEquals(decrement ? 0 : 1, group.getDesiredCapacity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void deletingSameNameOtherRegionPreservesPausedRefresh(String mode) throws IOException {
        setUp(mode);
        String otherRegion = "us-west-2";
        LaunchTemplate template = new LaunchTemplate();
        template.setLaunchTemplateId("lt-original");
        template.getData().setImageId("ami-original");
        when(ec2.describeLaunchTemplateVersions(eq(otherRegion), eq("lt-original"), isNull(), anyList()))
                .thenReturn(List.of(template));
        service.createAutoScalingGroup(otherRegion, "suspended-group", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-west-2a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        AutoScalingGroup other = service.describeAutoScalingGroups(otherRegion, List.of("suspended-group"))
                .getFirst();
        AsgInstance member = new AsgInstance();
        member.setInstanceId("i-west");
        member.setLifecycleState("InService");
        other.getInstances().add(member);
        service.saveAutoScalingGroupIfPresent(other);
        service.suspendProcesses(otherRegion, "suspended-group", List.of("InstanceRefresh"));
        InstanceRefresh refresh = service.startInstanceRefresh(otherRegion, "suspended-group", new InstanceRefresh());
        service.deleteAutoScalingGroup(REGION, "suspended-group", true);
        assertSame(refresh, service.activeInstanceRefresh(other), "Peer deletion erased refresh ownership");
        when(ec2.describeInstances(eq(otherRegion), anyList(), isNull())).thenReturn(List.of(reservation("i-west")));
        reconciler.reconcile(other);
        verify(ec2, never()).terminateInstances(eq(otherRegion), anyList());
        assertEquals(List.of("i-west"), other.getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    @SuppressWarnings("unchecked")
    void groupRecreationWaitsForOldRefreshCleanup(String mode) throws Exception {
        setUp(mode);
        Field field = AutoScalingService.class.getDeclaredField("instanceRefreshes");
        field.setAccessible(true);
        Map<String, InstanceRefresh> original = (Map<String, InstanceRefresh>) field.get(service);
        Map<String, InstanceRefresh> intercepted = spy(original);
        CountDownLatch cleanup = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicBoolean firstCleanup = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstCleanup.compareAndSet(true, false)) {
                cleanup.countDown();
                assertTrue(finish.await(5, TimeUnit.SECONDS));
            }
            return original.entrySet();
        }).when(intercepted).entrySet();
        field.set(service, intercepted);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> deleting = executor.submit(() -> service.deleteAutoScalingGroup(REGION, "suspended-group", true));
        Future<InstanceRefresh> recreating = null;
        InstanceRefresh replacement;
        try {
            assertTrue(cleanup.await(5, TimeUnit.SECONDS));
            CountDownLatch creationStarted = new CountDownLatch(1);
            recreating = executor.submit(() -> {
                creationStarted.countDown();
                service.createAutoScalingGroup(REGION, "suspended-group", null, "lt-original", null, "1", null,
                        0, 3, 0, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                        List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
                return service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
            });
            assertTrue(creationStarted.await(5, TimeUnit.SECONDS));
            try {
                recreating.get(250, TimeUnit.MILLISECONDS);
            } catch (TimeoutException expected) {
                // Recreating the same group may wait for the old generation's refresh cleanup.
            }
        } finally {
            finish.countDown();
            try {
                deleting.get(5, TimeUnit.SECONDS);
                replacement = recreating == null ? null : recreating.get(5, TimeUnit.SECONDS);
            } finally {
                field.set(service, original);
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertNotNull(replacement);
        assertEquals(List.of(replacement.getInstanceRefreshId()), service.describeInstanceRefreshes(
                REGION, "suspended-group", null, null, null).instanceRefreshes().stream()
                .map(InstanceRefresh::getInstanceRefreshId).toList());
    }

    private static Reservation reservation(String id) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setState(InstanceState.running());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(instance));
        return reservation;
    }
}
