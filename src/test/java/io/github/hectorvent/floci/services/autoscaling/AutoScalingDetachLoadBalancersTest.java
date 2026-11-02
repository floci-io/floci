package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceRefresh;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceRefreshReplacement;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingDetachLoadBalancersTest {
    private static final String REGION = "us-east-1";
    private final ElbV2Service targets = mock(ElbV2Service.class);
    private final ElbClassicService classic = mock(ElbClassicService.class);
    private final AutoScalingService service = new AutoScalingService(targets, classic);
    private final AutoScalingGroup group = new AutoScalingGroup();
    private final AsgInstance owned = member("i-owned");
    private final AsgInstance retained = member("i-retained");

    AutoScalingDetachLoadBalancersTest() {
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = mock(Ec2Service.class);
        group.setAutoScalingGroupName("group");
        group.setRegion(REGION);
        group.setDesiredCapacity(2);
        group.setLoadBalancerNames(List.of("first", "second"));
        group.setTargetGroupARNs(List.of("target-group"));
        group.getInstances().addAll(List.of(owned, retained));
        service.groups.put(REGION + "::group", group);
    }

    @ParameterizedTest
    @ValueSource(strings = {"InService", "Standby"})
    void detachCleansBothBalancerKindsAndOnlyActualMembers(String state) {
        owned.setLifecycleState(state);

        service.detachInstances(REGION, "group", List.of("i-owned", "i-other-group"), false);

        verify(classic).deregisterInstances(REGION, "first", List.of("i-owned"));
        verify(classic).deregisterInstances(REGION, "second", List.of("i-owned"));
        verifyNoMoreInteractions(classic);
        verify(targets).deregisterTargets(eq(REGION), eq("target-group"),
                argThat(values -> values.size() == 1 && "i-owned".equals(values.getFirst().getId())));
        assertEquals(List.of(retained), group.getInstances());
        assertEquals(2, group.getDesiredCapacity());
    }

    @Test
    void failedClassicCleanupPreservesMembershipCapacityTagsAndRefreshUntilRetry() {
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "group", new InstanceRefresh());
        assertEquals(List.of("i-owned", "i-retained"), refresh.getReplacements().stream()
                .map(InstanceRefreshReplacement::getOriginalInstanceId).toList());
        AwsException failure = new AwsException("Throttling", "retry", 503);
        when(classic.deregisterInstances(REGION, "second", List.of("i-owned")))
                .thenThrow(failure).thenReturn(List.of("i-retained"));

        assertSame(failure, assertThrows(AwsException.class,
                () -> service.detachInstances(REGION, "group", List.of("i-owned"), true)));
        assertEquals(List.of(owned, retained), group.getInstances());
        assertEquals(2, group.getDesiredCapacity());
        verifyNoInteractions(service.ec2Service);
        assertEquals(List.of("i-owned", "i-retained"), refresh.getReplacements().stream()
                .map(InstanceRefreshReplacement::getOriginalInstanceId).toList());

        service.detachInstances(REGION, "group", List.of("i-owned"), true);

        assertEquals(List.of(retained), group.getInstances());
        assertEquals(1, group.getDesiredCapacity());
        assertEquals(List.of("i-retained"), refresh.getReplacements().stream()
                .map(InstanceRefreshReplacement::getOriginalInstanceId).toList());
        verify(classic, times(2)).deregisterInstances(REGION, "first", List.of("i-owned"));
        verify(classic, times(2)).deregisterInstances(REGION, "second", List.of("i-owned"));
        verify(service.ec2Service).deleteSystemTag(REGION, List.of("i-owned"),
                AutoScalingService.GROUP_NAME_TAG, "group");
    }

    @Test
    void deletedClassicBalancerDoesNotPreventRemainingCleanup() {
        when(classic.deregisterInstances(REGION, "first", List.of("i-owned")))
                .thenThrow(new AwsException("LoadBalancerNotFound", "deleted", 400));

        service.detachInstances(REGION, "group", List.of("i-owned"), true);

        verify(classic).deregisterInstances(REGION, "second", List.of("i-owned"));
        assertEquals(List.of(retained), group.getInstances());
    }

    @Test
    void rejectedStandbyTransitionDoesNotDeregisterAnything() {
        owned.setLifecycleState("EnteringStandby");

        assertThrows(AwsException.class,
                () -> service.detachInstances(REGION, "group", List.of("i-owned"), true));

        verifyNoInteractions(classic, targets, service.ec2Service);
        assertEquals(List.of(owned, retained), group.getInstances());
    }

    @Test
    void nonmemberDetachDoesNotTouchLoadBalancers() {
        service.detachInstances(REGION, "group", List.of("i-other-group"), false);

        verifyNoInteractions(classic, targets, service.ec2Service);
        assertEquals(List.of(owned, retained), group.getInstances());
    }

    @Test
    void detachDeregistersOnlyActualMembersBeforeRemovingOwnership() {
        group.setTargetGroupARNs(List.of("shared-target-group"));

        service.detachInstances(REGION, "group", List.of("i-owned", "i-other-group"), false);

        verify(targets).deregisterTargets(eq(REGION), eq("shared-target-group"),
                argThat(values -> values.size() == 1 && "i-owned".equals(values.getFirst().getId())));
        assertEquals(List.of(retained), group.getInstances());
    }

    @Test
    void detachDeregistrationFailurePreservesMembershipForRetry() {
        group.setTargetGroupARNs(List.of("shared-target-group"));
        doThrow(new AwsException("Throttling", "retry", 503)).when(targets)
                .deregisterTargets(eq(REGION), eq("shared-target-group"), anyList());

        assertThrows(AwsException.class,
                () -> service.detachInstances(REGION, "group", List.of("i-owned"), false));

        assertEquals(List.of(owned, retained), group.getInstances());
        verifyNoInteractions(classic, service.ec2Service);
    }

    @Test
    void detachSkipsDeletedTargetGroupAndCleansTheRemainingGroup() {
        group.setTargetGroupARNs(List.of("deleted-target-group", "shared-target-group"));
        doThrow(new AwsException("TargetGroupNotFound", "deleted", 400)).when(targets)
                .deregisterTargets(eq(REGION), eq("deleted-target-group"), anyList());

        service.detachInstances(REGION, "group", List.of("i-owned"), false);

        verify(targets).deregisterTargets(eq(REGION), eq("shared-target-group"), anyList());
        verify(classic).deregisterInstances(REGION, "first", List.of("i-owned"));
        assertEquals(List.of(retained), group.getInstances());
    }

    private static AsgInstance member(String id) {
        AsgInstance member = new AsgInstance();
        member.setInstanceId(id);
        member.setLifecycleState("InService");
        return member;
    }
}
