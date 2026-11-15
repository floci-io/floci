package io.floci.aws.services.autoscaling;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.autoscaling.model.AsgInstance;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.elb.ElbClassicService;
import io.floci.aws.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.autoscaling.TargetMatchers.exactlyTargets;
import static io.floci.aws.services.autoscaling.TargetMatchers.onlyTarget;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Attaching a target group to a group registers the group's in-service instances with it, and detaching one
 * deregisters the group's instances from it, as AWS does, for an ordinary group as for an EKS group
 * ({@link AutoScalingEksManagedGroupTest}): as target groups and as {@code elbv2} traffic sources.
 */
class AutoScalingTargetGroupAttachmentTest {

    private static final String REGION = "us-east-1";
    private static final String GROUP = "web";
    private static final String IN_SERVICE = "i-0a1b2c3d4e5f60001";
    private static final String STANDBY = "i-0a1b2c3d4e5f60002";

    private final ElbV2Service targets = mock(ElbV2Service.class);
    private final AutoScalingService service = new AutoScalingService(targets, mock(ElbClassicService.class));
    private final AutoScalingGroup group = new AutoScalingGroup();

    AutoScalingTargetGroupAttachmentTest() {
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = mock(Ec2Service.class);
        group.setAutoScalingGroupName(GROUP);
        group.setRegion(REGION);
        group.setTargetGroupARNs(new ArrayList<>(List.of("tg-attached")));
        group.getInstances().addAll(List.of(member(IN_SERVICE, "InService"), member(STANDBY, "Standby")));
        service.groups.put(REGION + "::" + GROUP, group);
    }

    @Test
    void attachingATargetGroupRegistersTheGroupsInServiceInstancesWithIt() {
        service.attachLoadBalancerTargetGroups(REGION, GROUP, List.of("tg-attached", "tg-new"));

        // Only the target group the attach added, and only the instance in service: one in standby is not.
        verify(targets).registerTargets(eq(REGION), eq("tg-new"), onlyTarget(IN_SERVICE));
        verifyNoMoreInteractions(targets);
        assertEquals(List.of("tg-attached", "tg-new"), group.getTargetGroupARNs());
    }

    @Test
    void detachingATargetGroupDeregistersTheGroupsInstancesFromIt() {
        group.getTargetGroupARNs().add("tg-kept");

        service.detachLoadBalancerTargetGroups(REGION, GROUP, List.of("tg-attached"));

        // In one call: the group's instances are its own, so none stays in the target group for another group.
        verify(targets).deregisterTargets(eq(REGION), eq("tg-attached"), exactlyTargets(IN_SERVICE, STANDBY));
        verifyNoMoreInteractions(targets);
        assertEquals(List.of("tg-kept"), group.getTargetGroupARNs());
    }

    @Test
    void anElbV2TrafficSourceRegistersAndDeregistersTheInstancesAsATargetGroupDoes() {
        service.attachTrafficSources(REGION, GROUP, List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-source", "elbv2")));
        verify(targets).registerTargets(eq(REGION), eq("tg-source"), onlyTarget(IN_SERVICE));

        service.detachTrafficSources(REGION, GROUP, List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-source", "elbv2")));

        verify(targets).deregisterTargets(eq(REGION), eq("tg-source"), exactlyTargets(IN_SERVICE, STANDBY));
        verifyNoMoreInteractions(targets);
        assertEquals(Map.of(), group.getTrafficSourceTypeByIdentifier());
    }

    @Test
    void aDetachElbV2RefusesLeavesTheTargetGroupAttachedSoTheDetachCanBeSentAgain() {
        AwsException refusal = new AwsException("Throttling", "Rate exceeded", 400);
        doThrow(refusal).doNothing().when(targets).deregisterTargets(eq(REGION), eq("tg-attached"), anyList());

        assertSame(refusal, assertThrows(AwsException.class,
                () -> service.detachLoadBalancerTargetGroups(REGION, GROUP, List.of("tg-attached"))));
        assertEquals(List.of("tg-attached"), group.getTargetGroupARNs());

        service.detachLoadBalancerTargetGroups(REGION, GROUP, List.of("tg-attached"));

        verify(targets, times(2)).deregisterTargets(eq(REGION), eq("tg-attached"),
                exactlyTargets(IN_SERVICE, STANDBY));
        assertEquals(List.of(), group.getTargetGroupARNs());
    }

    @Test
    void aTargetGroupDeletedBeforeItsDetachIsDetached() {
        doThrow(new AwsException("TargetGroupNotFound", "One or more target groups not found", 400))
                .when(targets).deregisterTargets(eq(REGION), eq("tg-attached"), anyList());

        service.detachLoadBalancerTargetGroups(REGION, GROUP, List.of("tg-attached"));

        assertEquals(List.of(), group.getTargetGroupARNs());
    }

    private static AsgInstance member(String id, String state) {
        AsgInstance member = new AsgInstance();
        member.setInstanceId(id);
        member.setLifecycleState(state);
        return member;
    }
}
