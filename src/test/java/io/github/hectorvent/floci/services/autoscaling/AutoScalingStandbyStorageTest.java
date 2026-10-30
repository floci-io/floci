package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.testing.StorageFactories;
import io.restassured.path.xml.XmlPath;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A move into or out of standby that a restart interrupts finishes after the restart: the member's
 * transient lifecycle state and the activity it completes are both stored, and a group stored before
 * standby existed loads and enters standby unchanged.
 */
class AutoScalingStandbyStorageTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String GROUP = "stored-standby";
    private static final String ACTIVITIES =
            "DescribeScalingActivitiesResponse.DescribeScalingActivitiesResult.Activities.member";
    @TempDir Path directory;
    private final List<StorageFactory> factories = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void movesInterruptedByARestartFinishAfterIt(String mode) throws IOException {
        AutoScalingService service = storedService(mode);
        storeGroup(service);
        String entered = ok(handler(service).handle("EnterStandby", request(true), REGION))
                .getString("EnterStandbyResponse.EnterStandbyResult.Activities.member.ActivityId");

        service = restart(mode);
        assertEquals("EnteringStandby", lifecycle(service, "i-a"));
        assertEquals("InProgress", activity(service, entered, "StatusCode"));
        reconcile(service, Set.of());
        assertEquals("Standby", lifecycle(service, "i-a"));
        assertEquals("Successful", activity(service, entered, "StatusCode"));
        assertEquals(1, group(service).getDesiredCapacity());

        String exited = ok(handler(service).handle("ExitStandby", request(null), REGION))
                .getString("ExitStandbyResponse.ExitStandbyResult.Activities.member.ActivityId");
        service = restart(mode);
        assertEquals("Pending", lifecycle(service, "i-a"));
        reconcile(service, Set.of());

        assertEquals("InService", lifecycle(service, "i-a"));
        assertEquals("Successful", activity(service, exited, "StatusCode"));
        assertTrue(activities(service).getList(ACTIVITIES + ".Description", String.class).stream()
                        .noneMatch(description -> description.startsWith("Launching a new EC2 instance")),
                "The restored member is recognised as returning from standby, not as a launch");
        assertEquals(2, group(service).getDesiredCapacity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void aReturnThatFailsTheHealthCheckAfterARestartIsStillReplaced(String mode) throws IOException {
        AutoScalingService service = storedService(mode);
        storeGroup(service);
        ok(handler(service).handle("EnterStandby", request(true), REGION));
        reconcile(service, Set.of());
        assertEquals("Standby", lifecycle(service, "i-a"));
        String exited = ok(handler(service).handle("ExitStandby", request(null), REGION))
                .getString("ExitStandbyResponse.ExitStandbyResult.Activities.member.ActivityId");

        service = restart(mode);
        Ec2Service ec2 = reconcile(service, Set.of("i-a"));

        verify(ec2).terminateInstances(REGION, List.of("i-a"));
        assertEquals(List.of("i-b"), memberIds(service));
        assertEquals("Cancelled", activity(service, exited, "StatusCode"));
        assertEquals("Successful", activities(service).getString(ACTIVITIES
                + ".find { it.Description == 'Terminating EC2 instance: i-a' }.StatusCode"));
        service = restart(mode);
        assertEquals(List.of("i-b"), memberIds(service));
    }

    private AutoScalingService restart(String mode) {
        factories.removeFirst().shutdownAll();
        String snapshot = mode.equals("wal") ? "autoscaling-groups-snapshot.json" : "autoscaling-groups.json";
        assertTrue(Files.exists(directory.resolve(snapshot)), "No real group snapshot was written");
        return storedService(mode);
    }

    /** One reconcile pass in which EC2 reports every instance running, except {@code stopped}. */
    private Ec2Service reconcile(AutoScalingService service, Set<String> stopped) {
        Ec2Service ec2 = mock(Ec2Service.class);
        when(ec2.isInstanceContainerRunning(anyString())).thenAnswer(invocation ->
                !stopped.contains(invocation.<String>getArgument(0)));
        when(ec2.describeInstances(eq(REGION), anyList(), isNull())).thenAnswer(invocation -> {
            List<Instance> found = new ArrayList<>();
            for (String id : invocation.<List<String>>getArgument(1)) {
                Instance instance = new Instance();
                instance.setInstanceId(id);
                instance.setState(stopped.contains(id) ? InstanceState.stopped() : InstanceState.running());
                found.add(instance);
            }
            Reservation reservation = new Reservation();
            reservation.setInstances(found);
            return List.of(reservation);
        });
        service.ec2Service = ec2;
        AutoScalingReconciler reconciler = new AutoScalingReconciler(service, ec2, mock(ElbV2Service.class),
                mock(ElbClassicService.class), null);
        reconciler.reconcile(group(service));
        return ec2;
    }

    /** A group with two InService members, as stored before standby existed: no standbyActivityId field. */
    private static void storeGroup(AutoScalingService service) throws IOException {
        String json = "{\"autoScalingGroupName\":\"" + GROUP + "\",\"region\":\"us-east-1\","
                + "\"minSize\":0,\"maxSize\":2,\"desiredCapacity\":2,\"availabilityZones\":[\"us-east-1a\"],"
                + "\"instances\":["
                + "{\"instanceId\":\"i-a\",\"availabilityZone\":\"us-east-1a\",\"lifecycleState\":\"InService\","
                + "\"healthStatus\":\"Healthy\"},"
                + "{\"instanceId\":\"i-b\",\"availabilityZone\":\"us-east-1a\",\"lifecycleState\":\"InService\","
                + "\"healthStatus\":\"Healthy\"}]}";
        AccountAwareStorageBackend<AutoScalingGroup> store = service.storageFactory.create("autoscaling",
                "autoscaling-groups.json", new TypeReference<Map<String, AutoScalingGroup>>() {});
        store.put(REGION + "::" + GROUP, new ObjectMapper().readValue(json, AutoScalingGroup.class));
    }

    private static List<String> memberIds(AutoScalingService service) {
        return group(service).getInstances().stream().map(AsgInstance::getInstanceId).toList();
    }

    private static MultivaluedHashMap<String, String> request(Boolean decrement) {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AutoScalingGroupName", GROUP);
        params.add("InstanceIds.member.1", "i-a");
        if (decrement != null) {
            params.add("ShouldDecrementDesiredCapacity", decrement.toString());
        }
        return params;
    }

    private static AutoScalingQueryHandler handler(AutoScalingService service) {
        return new AutoScalingQueryHandler(service);
    }

    private static XmlPath ok(Response response) {
        assertEquals(200, response.getStatus(), (String) response.getEntity());
        return new XmlPath((String) response.getEntity());
    }

    private static XmlPath activities(AutoScalingService service) {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AutoScalingGroupName", GROUP);
        return ok(handler(service).handle("DescribeScalingActivities", params, REGION));
    }

    private static String activity(AutoScalingService service, String activityId, String field) {
        return activities(service).getString(ACTIVITIES + ".find { it.ActivityId == '" + activityId + "' }." + field);
    }

    private static AutoScalingGroup group(AutoScalingService service) {
        return service.describeAutoScalingGroups(REGION, List.of(GROUP)).getFirst();
    }

    private static String lifecycle(AutoScalingService service, String instanceId) {
        return group(service).getInstances().stream()
                .filter(member -> instanceId.equals(member.getInstanceId()))
                .map(AsgInstance::getLifecycleState)
                .findFirst().orElse(null);
    }

    private AutoScalingService storedService(String mode) {
        StorageFactory factory = StorageFactories.open(directory, mode, ACCOUNT);
        factories.add(factory);
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, ACCOUNT);
        service.storageFactory = factory;
        service.initializeStorage();
        return service;
    }

    @AfterEach
    void closeStorage() {
        factories.forEach(StorageFactory::shutdownAll);
    }
}
