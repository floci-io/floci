package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.restassured.path.xml.XmlPath;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * EnterStandby and ExitStandby as the EC2 Auto Scaling user guide describes them: an InService instance
 * leaves its group's load balancers and waits in Standby, outside the group's capacity, until it is put
 * back in service; one activity per instance records each move. Driven through the Query handler and a
 * real service and reconciler, with EC2 and both load balancer services mocked.
 */
class AutoScalingStandbyTest {

    private static final String REGION = "us-east-1";
    private static final String GROUP = "standby-group";
    private static final String TARGET_GROUP =
            "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/standby/0123456789abcdef";
    private static final String CLASSIC = "standby-classic";
    private static final String ENTERED = "EnterStandbyResponse.EnterStandbyResult.Activities.member";
    private static final String EXITED = "ExitStandbyResponse.ExitStandbyResult.Activities.member";
    private static final String ACTIVITIES =
            "DescribeScalingActivitiesResponse.DescribeScalingActivitiesResult.Activities.member";
    private static final String CAUSE_TIME = "At \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z ";

    private AutoScalingService service;
    private AutoScalingQueryHandler handler;
    private AutoScalingReconciler reconciler;
    private Ec2Service ec2;
    private ElbV2Service elbV2;
    private ElbClassicService elbClassic;
    private final Map<String, InstanceState> ec2States = new LinkedHashMap<>();
    private final AtomicInteger launches = new AtomicInteger();

    @BeforeEach
    void setUp() {
        service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.createAutoScalingGroup(REGION, GROUP, null, "lt-standby", null, "1", null,
                0, 3, 2, 300, List.of("us-east-1a"), List.of(), List.of(TARGET_GROUP), List.of(CLASSIC),
                "EC2", 0, List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        AutoScalingGroup group = group();
        group.getInstances().add(member("i-a"));
        group.getInstances().add(member("i-b"));
        service.saveAutoScalingGroupIfPresent(group);
        ec2States.put("i-a", InstanceState.running());
        ec2States.put("i-b", InstanceState.running());

        ec2 = mock(Ec2Service.class);
        elbV2 = mock(ElbV2Service.class);
        elbClassic = mock(ElbClassicService.class);
        service.ec2Service = ec2;
        handler = new AutoScalingQueryHandler(service);
        reconciler = new AutoScalingReconciler(service, ec2, elbV2, elbClassic, null);
        when(ec2.isInstanceContainerRunning(anyString())).thenAnswer(invocation -> {
            InstanceState state = ec2States.get(invocation.<String>getArgument(0));
            return state != null && "running".equals(state.getName());
        });
        // As Ec2Service does, an id EC2 has no record of fails the whole call with InvalidInstanceID.NotFound.
        when(ec2.describeInstances(eq(REGION), anyList(), isNull())).thenAnswer(invocation -> {
            List<Instance> found = new ArrayList<>();
            for (String id : invocation.<List<String>>getArgument(1)) {
                if (!ec2States.containsKey(id)) {
                    throw instanceNotFound(id);
                }
                found.add(instance(id, ec2States.get(id)));
            }
            return found.isEmpty() ? List.of() : List.of(reservation(found));
        });
        // Ec2Service terminates the ids in order and stops at the first it has no record of.
        when(ec2.terminateInstances(eq(REGION), anyList())).thenAnswer(invocation -> {
            for (String id : invocation.<List<String>>getArgument(1)) {
                if (!ec2States.containsKey(id)) {
                    throw instanceNotFound(id);
                }
                ec2States.put(id, InstanceState.terminated());
            }
            return List.of();
        });
        LaunchTemplate template = new LaunchTemplate();
        template.setLaunchTemplateId("lt-standby");
        template.setLatestVersionNumber("1");
        template.getData().setImageId("ami-standby");
        template.getData().setInstanceType("t3.micro");
        when(ec2.describeLaunchTemplates(eq(REGION), anyList(), anyList(), anyMap())).thenReturn(List.of(template));
        when(ec2.describeLaunchTemplateVersions(eq(REGION), eq("lt-standby"), isNull(), anyList()))
                .thenReturn(List.of(template));
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any(), isNull(), eq(0), eq(REGION + "a"), isNull(), isNull(), isNull(),
                anyBoolean(), isNull(), isNull(), any(), eq(false), isNull(), isNull()))
                .thenAnswer(invocation -> {
                    String id = "i-replacement-" + launches.incrementAndGet();
                    ec2States.put(id, InstanceState.running());
                    return reservation(List.of(instance(id, InstanceState.running())));
                });
    }

    @Test
    void enterStandbyWithDecrementLeavesTheLoadBalancersAndKeepsTheGroupFromReplacingIt() {
        Response response = enterStandby(true, "i-a");

        assertEquals(200, response.getStatus());
        XmlPath entered = xml(response);
        assertEquals(1, entered.getList(ENTERED + ".ActivityId").size());
        assertEquals(GROUP, entered.getString(ENTERED + ".AutoScalingGroupName"));
        assertEquals("Moving EC2 instance to Standby: i-a", entered.getString(ENTERED + ".Description"));
        assertTrue(entered.getString(ENTERED + ".Cause").matches(CAUSE_TIME
                        + "instance i-a was moved to standby in response to a user request,"
                        + " shrinking the capacity from 2 to 1\\."),
                entered.getString(ENTERED + ".Cause"));
        assertEquals("InProgress", entered.getString(ENTERED + ".StatusCode"));
        assertEquals("50", entered.getString(ENTERED + ".Progress"));
        assertEquals(1, group().getDesiredCapacity());
        assertEquals("EnteringStandby", lifecycle("i-a"));
        verify(elbV2, never()).deregisterTargets(anyString(), anyString(), anyList());

        reconciler.reconcile(group());

        assertEquals("Standby", lifecycle("i-a"));
        assertEquals("InService", lifecycle("i-b"));
        verify(elbV2).deregisterTargets(eq(REGION), eq(TARGET_GROUP),
                argThat(targets -> targets.stream().map(TargetDescription::getId).toList().equals(List.of("i-a"))));
        verify(elbClassic).deregisterInstances(REGION, CLASSIC, List.of("i-a"));
        verify(elbV2, never()).registerTargets(anyString(), anyString(), anyList());
        assertNoLaunch();
        verify(ec2, never()).terminateInstances(anyString(), anyList());
        XmlPath activities = describeScalingActivities();
        String activityId = entered.getString(ENTERED + ".ActivityId");
        assertEquals("Successful", activity(activities, activityId, "StatusCode"));
        assertEquals("100", activity(activities, activityId, "Progress"));
        assertFalse(activity(activities, activityId, "EndTime").isEmpty());
        assertEquals("Moving EC2 instance to Standby: i-a", activity(activities, activityId, "Description"));

        reconciler.reconcile(group());
        assertEquals(List.of("i-a", "i-b"), memberIds());
        assertEquals("Standby", lifecycle("i-a"));
        assertEquals(1, group().getDesiredCapacity());
    }

    @Test
    void enterStandbyWithoutDecrementLaunchesAReplacement() {
        XmlPath entered = ok(enterStandby(false, "i-a"));

        assertTrue(entered.getString(ENTERED + ".Cause").matches(CAUSE_TIME
                        + "instance i-a was moved to standby in response to a user request\\."),
                entered.getString(ENTERED + ".Cause"));
        assertEquals(2, group().getDesiredCapacity());

        reconciler.reconcile(group());

        assertEquals("Standby", lifecycle("i-a"));
        assertEquals(List.of("i-a", "i-b", "i-replacement-1"), memberIds());
        assertEquals("Pending", lifecycle("i-replacement-1"));
        reconciler.reconcile(group());
        assertEquals("InService", lifecycle("i-replacement-1"));
        assertEquals("Standby", lifecycle("i-a"));
        assertEquals(1, launches.get());
    }

    @Test
    void exitStandbyGoesThroughPendingAndRegistersWithTheLoadBalancers() {
        ok(enterStandby(true, "i-a"));
        reconciler.reconcile(group());
        assertEquals("Standby", lifecycle("i-a"));
        clearInvocations(elbV2, elbClassic);

        Response response = exitStandby("i-a");

        assertEquals(200, response.getStatus());
        XmlPath exited = xml(response);
        assertEquals(1, exited.getList(EXITED + ".ActivityId").size());
        assertEquals(GROUP, exited.getString(EXITED + ".AutoScalingGroupName"));
        assertEquals("Moving EC2 instance out of Standby: i-a", exited.getString(EXITED + ".Description"));
        assertTrue(exited.getString(EXITED + ".Cause").matches(CAUSE_TIME
                        + "instance i-a was moved out of standby in response to a user request,"
                        + " increasing the capacity from 1 to 2\\."),
                exited.getString(EXITED + ".Cause"));
        assertEquals("PreInService", exited.getString(EXITED + ".StatusCode"));
        assertEquals("30", exited.getString(EXITED + ".Progress"));
        assertEquals(2, group().getDesiredCapacity());
        assertEquals("Pending", lifecycle("i-a"));

        reconciler.reconcile(group());

        assertEquals("InService", lifecycle("i-a"));
        assertEquals("Healthy", group().getInstances().stream()
                .filter(member -> "i-a".equals(member.getInstanceId())).findFirst().orElseThrow().getHealthStatus());
        verify(elbV2).registerTargets(eq(REGION), eq(TARGET_GROUP),
                argThat(targets -> targets.stream().map(TargetDescription::getId).toList().equals(List.of("i-a"))));
        verify(elbClassic).registerInstances(REGION, CLASSIC, List.of("i-a"));
        assertNoLaunch();
        XmlPath activities = describeScalingActivities();
        String activityId = exited.getString(EXITED + ".ActivityId");
        assertEquals("Successful", activity(activities, activityId, "StatusCode"));
        assertEquals("100", activity(activities, activityId, "Progress"));
        assertTrue(activities.getList(ACTIVITIES + ".Description", String.class).stream()
                        .noneMatch(description -> description.startsWith("Launching a new EC2 instance")),
                "A member returning from standby was not launched");
        assertEquals(List.of("i-a", "i-b"), memberIds());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stopped", "terminated", "gone"})
    void standbyMembersAreNotScaledInOrRemovedWhenTheirInstanceStops(String fate) {
        ok(enterStandby(true, "i-a"));
        reconciler.reconcile(group());
        setEc2State("i-a", fate);

        service.setDesiredCapacity(REGION, GROUP, 0);
        reconciler.reconcile(group());

        verify(ec2).terminateInstances(REGION, List.of("i-b"));
        verify(ec2, never()).terminateInstances(REGION, List.of("i-a"));
        assertEquals(List.of("i-a"), memberIds());
        assertEquals("Standby", lifecycle("i-a"));
        assertEquals(0, AutoScalingReconciler.activeCapacity(group()));
        reconciler.reconcile(group());
        assertEquals(List.of("i-a"), memberIds());
    }

    @Test
    void attachInstancesCountsOnlyMembersOutsideStandby() {
        ok(enterStandby(true, "i-a"));
        reconciler.reconcile(group());

        MultivaluedHashMap<String, String> attach = params(GROUP);
        attach.add("InstanceIds.member.1", "i-c");
        assertEquals(200, handler.handle("AttachInstances", attach, REGION).getStatus());

        assertEquals(2, group().getDesiredCapacity());
    }

    @Test
    void refusesInstancesOutsideTheGroupOrInTheWrongState() {
        assertError(enterStandby(true, "i-missing"),
                "The instance i-missing is not part of Auto Scaling group standby-group.");
        assertError(enterStandby(true, "i-x", "i-y"),
                "The instances i-x, i-y are not part of Auto Scaling group standby-group.");
        assertError(exitStandby("i-a"), "The instance i-a is not in Standby.");
        assertEquals(2, group().getDesiredCapacity());

        ok(enterStandby(true, "i-a"));
        assertError(enterStandby(true, "i-a"), "The instance i-a is not in InService.");
        assertError(exitStandby("i-a"), "The instance i-a is not in Standby.");
        assertError(enterStandby(true, "i-b", "i-missing"),
                "The instance i-missing is not part of Auto Scaling group standby-group.");
        assertEquals(1, group().getDesiredCapacity());
        assertEquals("InService", lifecycle("i-b"));

        MultivaluedHashMap<String, String> unknown = params("no-such-group");
        unknown.add("InstanceIds.member.1", "i-a");
        unknown.add("ShouldDecrementDesiredCapacity", "true");
        assertError(handler.handle("EnterStandby", unknown, REGION), "Auto Scaling group 'no-such-group' not found.");
        assertError(handler.handle("ExitStandby", unknown, REGION), "Auto Scaling group 'no-such-group' not found.");
        assertEquals(1, xml(describeScalingActivitiesResponse()).getList(ACTIVITIES + ".ActivityId").size());
    }

    @Test
    void refusesARequestWithoutItsRequiredMembers() {
        MultivaluedHashMap<String, String> noDecrement = params(GROUP);
        noDecrement.add("InstanceIds.member.1", "i-a");
        assertError(handler.handle("EnterStandby", noDecrement, REGION), "1 validation error detected: Value null"
                + " at 'shouldDecrementDesiredCapacity' failed to satisfy constraint: Member must not be null");

        MultivaluedHashMap<String, String> nothing = new MultivaluedHashMap<>();
        nothing.add("InstanceIds.member.1", "i-a");
        assertError(handler.handle("EnterStandby", nothing, REGION), "2 validation errors detected: Value null"
                + " at 'autoScalingGroupName' failed to satisfy constraint: Member must not be null; Value null"
                + " at 'shouldDecrementDesiredCapacity' failed to satisfy constraint: Member must not be null");
        assertError(handler.handle("ExitStandby", nothing, REGION), "1 validation error detected: Value null"
                + " at 'autoScalingGroupName' failed to satisfy constraint: Member must not be null");
        assertEquals("InService", lifecycle("i-a"));
        assertEquals(2, group().getDesiredCapacity());
    }

    @Test
    void desiredCapacityMustStayBetweenMinSizeAndMaxSize() {
        service.updateAutoScalingGroup(REGION, GROUP, null, null, null, null, null, 2, 2, null, null,
                null, null, null, null, null, AsgOptionalFields.none());

        assertError(enterStandby(true, "i-a"), "AutoScalingGroup standby-group has min-size=2, max-size=2,"
                + " and desired-size=2. To place into standby 1 instances, please update the AutoScalingGroup"
                + " sizes appropriately.");
        assertEquals("InService", lifecycle("i-a"));

        assertEquals(200, enterStandby(false, "i-a").getStatus());
        reconciler.reconcile(group());
        assertEquals("Standby", lifecycle("i-a"));
        assertError(exitStandby("i-a"), "AutoScalingGroup standby-group has min-size=2, max-size=2,"
                + " and desired-size=2. To exit standby 1 instances, please update the AutoScalingGroup"
                + " sizes appropriately.");
        assertEquals("Standby", lifecycle("i-a"));
        assertEquals(2, group().getDesiredCapacity());
    }

    @Test
    void enteringTwoInstancesRecordsOneActivityEachAndOneCapacityChange() {
        XmlPath entered = ok(enterStandby(true, "i-a", "i-b", "i-a"));

        assertEquals(List.of("Moving EC2 instance to Standby: i-a", "Moving EC2 instance to Standby: i-b"),
                entered.getList(ENTERED + ".Description"));
        assertTrue(entered.getList(ENTERED + ".Cause", String.class).stream().allMatch(cause ->
                cause.endsWith("shrinking the capacity from 2 to 0.")), entered.prettify());
        assertEquals(0, group().getDesiredCapacity());

        reconciler.reconcile(group());
        assertEquals("Standby", lifecycle("i-a"));
        assertEquals("Standby", lifecycle("i-b"));
        verify(elbClassic).deregisterInstances(REGION, CLASSIC, List.of("i-a", "i-b"));

        XmlPath exited = ok(exitStandby("i-b", "i-a"));
        assertEquals(List.of("Moving EC2 instance out of Standby: i-b", "Moving EC2 instance out of Standby: i-a"),
                exited.getList(EXITED + ".Description"));
        assertTrue(exited.getList(EXITED + ".Cause", String.class).stream().allMatch(cause ->
                cause.endsWith("increasing the capacity from 0 to 2.")), exited.prettify());
        assertEquals(2, group().getDesiredCapacity());
    }

    @Test
    void anEmptyInstanceListMovesNothing() {
        MultivaluedHashMap<String, String> enter = params(GROUP);
        enter.add("ShouldDecrementDesiredCapacity", "true");
        Response entered = handler.handle("EnterStandby", enter, REGION);
        assertEquals(200, entered.getStatus());
        assertTrue(xml(entered).getList(ENTERED + ".ActivityId").isEmpty());

        Response exited = handler.handle("ExitStandby", params(GROUP), REGION);
        assertEquals(200, exited.getStatus());
        assertTrue(xml(exited).getList(EXITED + ".ActivityId").isEmpty());
        assertEquals(2, group().getDesiredCapacity());
    }

    @Test
    void aMoveInterruptedByTerminationIsCancelled() {
        String activityId = ok(enterStandby(true, "i-a")).getString(ENTERED + ".ActivityId");
        service.terminateInstanceInAutoScalingGroup(REGION, "i-a", false);

        reconciler.reconcile(group());

        assertEquals("Cancelled", activity(describeScalingActivities(), activityId, "StatusCode"));
        verify(ec2).terminateInstances(REGION, List.of("i-a"));
        assertFalse(memberIds().contains("i-a"));
    }

    @Test
    void anInstanceEc2NoLongerHasIsReplacedWhenItLeavesStandby() {
        ok(enterStandby(true, "i-a"));
        reconciler.reconcile(group());
        ec2States.remove("i-a");
        String activityId = ok(exitStandby("i-a")).getString(EXITED + ".ActivityId");
        assertEquals(2, group().getDesiredCapacity());

        reconciler.reconcile(group());

        verify(ec2, never()).terminateInstances(eq(REGION), argThat(ids -> ids.contains("i-a")));
        assertEquals(List.of("i-b", "i-replacement-1"), memberIds());
        XmlPath activities = describeScalingActivities();
        assertEquals("Cancelled", activity(activities, activityId, "StatusCode"));
        assertEquals("Successful", activities.getString(ACTIVITIES
                + ".find { it.Description == 'Terminating EC2 instance: i-a' }.StatusCode"), activities.prettify());
        assertEquals(2, group().getDesiredCapacity());
    }

    @Test
    void anInstanceThatStoppedOrTerminatedInStandbyFailsTheHealthCheckOnItsReturn() {
        ok(enterStandby(true, "i-a", "i-b"));
        reconciler.reconcile(group());
        ec2States.put("i-a", InstanceState.stopped());
        ec2States.put("i-b", InstanceState.terminated());
        ok(exitStandby("i-a", "i-b"));
        assertEquals(2, group().getDesiredCapacity());

        reconciler.reconcile(group());

        verify(ec2).terminateInstances(REGION, List.of("i-a"));
        verify(ec2, never()).terminateInstances(eq(REGION), argThat(ids -> ids.contains("i-b")));
        assertEquals(List.of("i-replacement-1", "i-replacement-2"), memberIds());
        XmlPath activities = describeScalingActivities();
        for (String id : List.of("i-a", "i-b")) {
            String path = ACTIVITIES + ".find { it.Description == 'Terminating EC2 instance: " + id + "' }";
            assertEquals("Successful", activities.getString(path + ".StatusCode"), activities.prettify());
            assertTrue(activities.getString(path + ".Cause").matches(CAUSE_TIME
                    + "an instance was taken out of service in response to an EC2 health check indicating it"
                    + " has been terminated or stopped\\."), activities.getString(path + ".Cause"));
        }
        assertTrue(activities.getList(ACTIVITIES + ".Description", String.class).stream()
                .noneMatch(description -> description.startsWith("Removing stale")), activities.prettify());
        assertEquals(List.of("Cancelled", "Cancelled"), activities.getList(ACTIVITIES
                + ".findAll { it.Description.text().startsWith('Moving EC2 instance out of Standby') }.StatusCode"));
    }

    @Test
    void returningFromStandbyKeepsTheHealthStatus() {
        ok(enterStandby(true, "i-a"));
        reconciler.reconcile(group());
        MultivaluedHashMap<String, String> health = new MultivaluedHashMap<>();
        health.add("InstanceId", "i-a");
        health.add("HealthStatus", "Unhealthy");
        health.add("ShouldRespectGracePeriod", "false");
        assertEquals(200, handler.handle("SetInstanceHealth", health, REGION).getStatus());
        reconciler.reconcile(group());
        assertEquals("Unhealthy", healthOf("i-a"));

        ok(exitStandby("i-a"));
        reconciler.reconcile(group());

        assertEquals("InService", lifecycle("i-a"));
        assertEquals("Unhealthy", healthOf("i-a"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void detachRefusesAnInstanceWhileItsStandbyMoveIsInProgress(boolean entering) {
        String activityId = ok(enterStandby(true, "i-a")).getString(ENTERED + ".ActivityId");
        if (!entering) {
            reconciler.reconcile(group());
            activityId = ok(exitStandby("i-a")).getString(EXITED + ".ActivityId");
        }
        int desired = group().getDesiredCapacity();

        MultivaluedHashMap<String, String> detach = params(GROUP);
        detach.add("InstanceIds.member.1", "i-b");
        detach.add("InstanceIds.member.2", "i-a");
        detach.add("ShouldDecrementDesiredCapacity", "true");
        assertError(handler.handle("DetachInstances", detach, REGION), "The instance i-a is not in InService or Standby.");

        assertEquals(entering ? "EnteringStandby" : "Pending", lifecycle("i-a"));
        assertEquals(List.of("i-a", "i-b"), memberIds());
        assertEquals(desired, group().getDesiredCapacity());
        assertEquals(entering ? "InProgress" : "PreInService",
                activity(describeScalingActivities(), activityId, "StatusCode"));
        verify(ec2, never()).deleteSystemTag(anyString(), anyList(), anyString(), anyString());
        reconciler.reconcile(group());
        assertEquals(entering ? "Standby" : "InService", lifecycle("i-a"));
        assertEquals("Successful", activity(describeScalingActivities(), activityId, "StatusCode"));
    }

    @Test
    void aRefusedTerminationOfAReturningInstanceIsRetriedOnTheNextPass() {
        ok(enterStandby(true, "i-a"));
        reconciler.reconcile(group());
        ec2States.put("i-a", InstanceState.stopped());
        String activityId = ok(exitStandby("i-a")).getString(EXITED + ".ActivityId");
        doThrow(new AwsException("RequestLimitExceeded", "Request limit exceeded.", 400))
                .doReturn(List.of())
                .when(ec2).terminateInstances(REGION, List.of("i-a"));

        reconciler.reconcile(group());

        assertEquals(List.of("i-a", "i-b"), memberIds());
        assertEquals("Pending", lifecycle("i-a"));
        XmlPath refused = describeScalingActivities();
        assertEquals("PreInService", activity(refused, activityId, "StatusCode"));
        assertTrue(refused.getList(ACTIVITIES + ".Description", String.class).stream()
                .noneMatch(description -> description.startsWith("Terminating EC2 instance")), refused.prettify());
        assertNoLaunch();

        reconciler.reconcile(group());

        verify(ec2, times(2)).terminateInstances(REGION, List.of("i-a"));
        assertEquals(List.of("i-b", "i-replacement-1"), memberIds());
        XmlPath retried = describeScalingActivities();
        assertEquals("Cancelled", activity(retried, activityId, "StatusCode"));
        assertEquals("Successful", retried.getString(ACTIVITIES
                + ".find { it.Description == 'Terminating EC2 instance: i-a' }.StatusCode"));
    }

    @Test
    void anInstanceRefreshCancelsTheMoveOfAnInstanceOnItsWayBack() {
        ok(enterStandby(true, "i-a"));
        reconciler.reconcile(group());
        String activityId = ok(exitStandby("i-a")).getString(EXITED + ".ActivityId");

        assertEquals(200, handler.handle("StartInstanceRefresh", params(GROUP), REGION).getStatus());
        assertEquals("Terminating", lifecycle("i-a"));
        reconciler.reconcile(group());

        assertEquals("Cancelled", activity(describeScalingActivities(), activityId, "StatusCode"));
        verify(ec2).terminateInstances(REGION, List.of("i-a", "i-b"));
        assertFalse(memberIds().contains("i-a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SetDesiredCapacity", "UpdateAutoScalingGroup"})
    void capacityChangesWaitForTheGroupLock(String action) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> changer = new AtomicReference<>();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = workers.submit(() -> service.reconcileIfPresent(group(), () -> {
                held.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(held.await(5, TimeUnit.SECONDS));
            MultivaluedHashMap<String, String> change = params(GROUP);
            change.add("SetDesiredCapacity".equals(action) ? "DesiredCapacity" : "MaxSize", "3");
            Future<Response> changing = workers.submit(() -> {
                changer.set(Thread.currentThread());
                return handler.handle(action, change, REGION);
            });
            await().atMost(Duration.ofSeconds(5)).until(() -> changing.isDone()
                    || changer.get() != null && changer.get().getState() == Thread.State.BLOCKED);
            assertFalse(changing.isDone(), action + " must wait for a standby move or reconcile pass in progress");
            release.countDown();
            assertEquals(200, changing.get(5, TimeUnit.SECONDS).getStatus());
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void theResponseShowsEachMoveAsItStarted() {
        List<ScalingActivity> started = service.enterStandby(REGION, GROUP, List.of("i-a"), true);
        reconciler.reconcile(group());

        assertEquals("Successful",
                activity(describeScalingActivities(), started.getFirst().getActivityId(), "StatusCode"));
        assertEquals("InProgress", started.getFirst().getStatusCode());
        assertEquals(50, started.getFirst().getProgress());
        assertNull(started.getFirst().getEndTime());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anInstanceRefreshLeavesStandbyMembersAlone(boolean settled) {
        ok(enterStandby(true, "i-a"));
        if (settled) {
            reconciler.reconcile(group());
        }

        MultivaluedHashMap<String, String> refresh = params(GROUP);
        assertEquals(200, handler.handle("StartInstanceRefresh", refresh, REGION).getStatus());

        assertEquals(settled ? "Standby" : "EnteringStandby", lifecycle("i-a"));
        assertEquals("Terminating", lifecycle("i-b"));
        reconciler.reconcile(group());
        assertEquals("Standby", lifecycle("i-a"));
    }

    @Test
    void deletingTheGroupCancelsItsMoves() {
        String activityId = ok(enterStandby(true, "i-a")).getString(ENTERED + ".ActivityId");

        service.deleteAutoScalingGroup(REGION, GROUP, true);

        assertEquals("Cancelled", activity(describeScalingActivities(), activityId, "StatusCode"));
    }

    private Response enterStandby(boolean decrement, String... instanceIds) {
        MultivaluedHashMap<String, String> params = params(GROUP);
        for (int index = 0; index < instanceIds.length; index++) {
            params.add("InstanceIds.member." + (index + 1), instanceIds[index]);
        }
        params.add("ShouldDecrementDesiredCapacity", String.valueOf(decrement));
        return handler.handle("EnterStandby", params, REGION);
    }

    private Response exitStandby(String... instanceIds) {
        MultivaluedHashMap<String, String> params = params(GROUP);
        for (int index = 0; index < instanceIds.length; index++) {
            params.add("InstanceIds.member." + (index + 1), instanceIds[index]);
        }
        return handler.handle("ExitStandby", params, REGION);
    }

    private Response describeScalingActivitiesResponse() {
        return handler.handle("DescribeScalingActivities", params(GROUP), REGION);
    }

    private XmlPath describeScalingActivities() {
        return xml(describeScalingActivitiesResponse());
    }

    private static MultivaluedHashMap<String, String> params(String group) {
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("AutoScalingGroupName", group);
        return params;
    }

    private static XmlPath ok(Response response) {
        assertEquals(200, response.getStatus(), (String) response.getEntity());
        return xml(response);
    }

    private static XmlPath xml(Response response) {
        return new XmlPath((String) response.getEntity());
    }

    private static void assertError(Response response, String message) {
        assertEquals(400, response.getStatus(), (String) response.getEntity());
        XmlPath error = xml(response);
        assertEquals("ValidationError", error.getString("ErrorResponse.Error.Code"));
        assertEquals(message, error.getString("ErrorResponse.Error.Message"));
    }

    private static String activity(XmlPath activities, String activityId, String field) {
        return activities.getString(ACTIVITIES + ".find { it.ActivityId == '" + activityId + "' }." + field);
    }

    private static String activityFor(XmlPath response, String path, String instanceId) {
        return response.getString(path + ".find { it.Description.text().endsWith(': " + instanceId + "') }.ActivityId");
    }

    private AutoScalingGroup group() {
        return service.describeAutoScalingGroups(REGION, List.of(GROUP)).getFirst();
    }

    private List<String> memberIds() {
        return group().getInstances().stream().map(AsgInstance::getInstanceId).toList();
    }

    private String healthOf(String instanceId) {
        return group().getInstances().stream()
                .filter(member -> instanceId.equals(member.getInstanceId()))
                .map(AsgInstance::getHealthStatus)
                .findFirst().orElse(null);
    }

    /** Sets what EC2 reports for an instance: a state name, or {@code gone} for no instance at all. */
    private void setEc2State(String instanceId, String fate) {
        switch (fate) {
            case "stopped" -> ec2States.put(instanceId, InstanceState.stopped());
            case "terminated" -> ec2States.put(instanceId, InstanceState.terminated());
            case "gone" -> ec2States.remove(instanceId);
            default -> throw new IllegalArgumentException(fate);
        }
    }

    private String lifecycle(String instanceId) {
        return group().getInstances().stream()
                .filter(member -> instanceId.equals(member.getInstanceId()))
                .map(AsgInstance::getLifecycleState)
                .findFirst().orElse(null);
    }

    private void assertNoLaunch() {
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any(), isNull(), eq(0), eq(REGION + "a"), isNull(), isNull(), isNull(),
                anyBoolean(), isNull(), isNull(), any(), eq(false), isNull(), isNull());
    }

    private static AsgInstance member(String id) {
        AsgInstance member = new AsgInstance();
        member.setInstanceId(id);
        member.setAvailabilityZone("us-east-1a");
        member.setLifecycleState("InService");
        member.setHealthStatus("Healthy");
        member.setLaunchTemplateId("lt-standby");
        member.setLaunchTemplateVersion("1");
        return member;
    }

    private static Instance instance(String id, InstanceState state) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setState(state);
        return instance;
    }

    private static AwsException instanceNotFound(String instanceId) {
        return new AwsException("InvalidInstanceID.NotFound",
                "The instance ID '" + instanceId + "' does not exist", 400);
    }

    private static Reservation reservation(List<Instance> instances) {
        Reservation reservation = new Reservation();
        reservation.setInstances(instances);
        return reservation;
    }
}
