package io.floci.aws.services.autoscaling;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.autoscaling.model.AsgInstance;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.floci.aws.services.autoscaling.model.InstanceRefresh;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.FIRST;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.NODE;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.REGION;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.SECOND;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.groupsSharingTheNode;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.node;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.nodeGroupTags;
import static io.floci.aws.services.autoscaling.TargetMatchers.onlyTarget;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The groups EKS keeps for its managed node groups. Floci runs one node per cluster, so the groups of a cluster's
 * node groups all hold that node: Auto Scaling adds it, registers it with the target groups attached to each group,
 * and keeps it in a target group as long as one group holding it has that target group attached.
 */
class AutoScalingEksManagedGroupTest {

    private final ElbV2Service targets = mock(ElbV2Service.class);
    private final Ec2Service ec2 = mock(Ec2Service.class);
    private final AutoScalingService service = AutoScalingGroupFixtures.service(targets, null);

    AutoScalingEksManagedGroupTest() {
        service.ec2Service = ec2;
    }

    @Test
    void theGroupMirrorsTheNodeGroupWithoutALaunchSourceAndIsCreatedOnce() {
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-a");
        subnet.setAvailabilityZone("us-east-1a");
        when(ec2.describeSubnets(REGION, List.of("subnet-a", "subnet-unknown"), null)).thenReturn(List.of(subnet));

        service.createEksManagedGroup(REGION, FIRST, 1, 3, 2, List.of("subnet-a", "subnet-unknown"), nodeGroupTags("workers"));

        AutoScalingGroup group = service.requireGroup(REGION, FIRST);
        assertTrue(group.isEksManaged());
        assertEquals("arn:aws:autoscaling:us-east-1:000000000000:autoScalingGroup:" + FIRST,
                group.getAutoScalingGroupArn());
        assertEquals(1, group.getMinSize());
        assertEquals(3, group.getMaxSize());
        assertEquals(2, group.getDesiredCapacity());
        assertEquals(List.of("us-east-1a"), group.getAvailabilityZones());
        assertEquals(List.of("subnet-a", "subnet-unknown"), group.getSubnetIds());
        assertEquals(nodeGroupTags("workers"), group.getTags());
        assertEquals(Map.of("eks:cluster-name", true, "eks:nodegroup-name", true,
                "k8s.io/cluster-autoscaler/enabled", true, "k8s.io/cluster-autoscaler/shop", true),
                group.getTagPropagateAtLaunch());
        assertEquals(List.of("Default"), group.getTerminationPolicies());
        assertNull(group.getLaunchConfigurationName());
        assertNull(group.getLaunchTemplateId());
        assertNull(group.getLaunchTemplateName());
        assertNull(group.getMixedInstancesPolicy());
        assertEquals(List.of(), group.getInstances());

        // Restoring a node group creates its group again; one that exists is left as it is.
        service.createEksManagedGroup(REGION, FIRST, 0, 5, 0, List.of(), Map.of());

        assertSame(group, service.requireGroup(REGION, FIRST));
        assertEquals(1, group.getMinSize());
        assertEquals(3, group.getMaxSize());
        assertEquals(2, group.getDesiredCapacity());
        assertEquals(nodeGroupTags("workers"), group.getTags());
    }

    @Test
    void anAddedNodeIsInServiceAndRegisteredWithTheTargetGroupsAlreadyAttached() {
        service.createEksManagedGroup(REGION, FIRST, 1, 3, 2, List.of(), nodeGroupTags("workers"));
        service.attachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-attached"));
        service.attachTrafficSources(REGION, FIRST, List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-source", "elbv2"),
                new AutoScalingService.TrafficSourceIdentifier("lattice-service", "vpc-lattice")));
        verifyNoInteractions(targets);

        service.addEksManagedInstance(REGION, FIRST, node());
        service.addEksManagedInstance(REGION, FIRST, node());

        List<AsgInstance> members = service.requireGroup(REGION, FIRST).getInstances();
        assertEquals(1, members.size());
        AsgInstance member = members.getFirst();
        assertEquals(NODE, member.getInstanceId());
        assertEquals("InService", member.getLifecycleState());
        assertEquals("Healthy", member.getHealthStatus());
        assertEquals("us-east-1b", member.getAvailabilityZone());
        assertEquals("m5.large", member.getInstanceType());
        assertNull(member.getLaunchTemplateId());
        verify(targets).registerTargets(eq(REGION), eq("tg-attached"), onlyTarget(NODE));
        verify(targets).registerTargets(eq(REGION), eq("tg-source"), onlyTarget(NODE));
        verifyNoMoreInteractions(targets);
    }

    @Test
    void attachingAndDetachingTargetGroupsRegistersAndDeregistersTheNode() {
        service.createEksManagedGroup(REGION, FIRST, 1, 3, 2, List.of(), nodeGroupTags("workers"));
        service.addEksManagedInstance(REGION, FIRST, node());

        service.attachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-a"));
        service.attachTrafficSources(REGION, FIRST, List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-b", null)));
        verify(targets).registerTargets(eq(REGION), eq("tg-a"), onlyTarget(NODE));
        verify(targets).registerTargets(eq(REGION), eq("tg-b"), onlyTarget(NODE));

        // Attached both ways, tg-a stays attached when one of them is detached.
        service.attachTrafficSources(REGION, FIRST, List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-a", "elbv2")));
        service.detachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-a"));
        verifyNoMoreInteractions(targets);

        service.detachTrafficSources(REGION, FIRST, List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-a", "elbv2"),
                new AutoScalingService.TrafficSourceIdentifier("tg-b", "elbv2")));

        verify(targets).deregisterTargets(eq(REGION), eq("tg-a"), onlyTarget(NODE));
        verify(targets).deregisterTargets(eq(REGION), eq("tg-b"), onlyTarget(NODE));
        verifyNoMoreInteractions(targets);
        assertEquals(List.of(NODE), memberIds(FIRST));
    }

    @Test
    void aDetachElbV2RefusesLeavesTheTargetGroupAttachedSoTheDetachCanBeSentAgain() {
        service.createEksManagedGroup(REGION, FIRST, 1, 3, 2, List.of(), nodeGroupTags("workers"));
        service.addEksManagedInstance(REGION, FIRST, node());
        service.attachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-a"));
        service.attachTrafficSources(REGION, FIRST, List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-b", null)));
        AwsException refusal = new AwsException("InternalFailure", "ELBv2 could not deregister the target", 500);
        doThrow(refusal).doNothing().when(targets).deregisterTargets(eq(REGION), eq("tg-a"), anyList());
        doThrow(refusal).doNothing().when(targets).deregisterTargets(eq(REGION), eq("tg-b"), anyList());

        assertThrows(AwsException.class,
                () -> service.detachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-a")));
        assertEquals(List.of("tg-a"), service.describeLoadBalancerTargetGroups(REGION, FIRST));
        service.detachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-a"));
        assertEquals(List.of(), service.describeLoadBalancerTargetGroups(REGION, FIRST));

        List<AutoScalingService.TrafficSourceIdentifier> trafficSource = List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-b", "elbv2"));
        assertThrows(AwsException.class, () -> service.detachTrafficSources(REGION, FIRST, trafficSource));
        assertEquals(Map.of("tg-b", "elbv2"), service.describeTrafficSources(REGION, FIRST, null));
        service.detachTrafficSources(REGION, FIRST, trafficSource);
        assertEquals(Map.of(), service.describeTrafficSources(REGION, FIRST, null));

        verify(targets, times(2)).deregisterTargets(eq(REGION), eq("tg-a"), onlyTarget(NODE));
        verify(targets, times(2)).deregisterTargets(eq(REGION), eq("tg-b"), onlyTarget(NODE));
    }

    @Test
    void detachingATargetGroupLeavesTheSharedNodeInItWhileAnotherGroupHasItAttached() {
        groupsSharingTheNode(service);
        service.attachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-shared"));
        service.attachTrafficSources(REGION, SECOND, List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-shared", "elbv2")));
        clearInvocations(targets);

        service.detachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-shared"));

        verifyNoInteractions(targets);

        service.detachTrafficSources(REGION, SECOND, List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-shared", "elbv2")));

        verify(targets).deregisterTargets(eq(REGION), eq("tg-shared"), onlyTarget(NODE));
        verifyNoMoreInteractions(targets);
    }

    @Test
    void detachingTheNodeLeavesTheTargetGroupsAnotherGroupHoldingItHasAttached() {
        groupsSharingTheNode(service);
        service.attachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-first", "tg-shared"));
        service.attachTrafficSources(REGION, FIRST, List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-source", "elbv2")));
        service.attachLoadBalancerTargetGroups(REGION, SECOND, List.of("tg-shared"));
        clearInvocations(targets);

        service.detachInstances(REGION, FIRST, List.of(NODE), false);

        assertEquals(List.of(), memberIds(FIRST));
        assertEquals(List.of(NODE), memberIds(SECOND));
        verify(targets).deregisterTargets(eq(REGION), eq("tg-first"), onlyTarget(NODE));
        verify(targets).deregisterTargets(eq(REGION), eq("tg-source"), onlyTarget(NODE));
        verifyNoMoreInteractions(targets);
    }

    /**
     * Each group is deleted as EKS deletes it with its node group, or through DeleteAutoScalingGroup with
     * ForceDelete, which is refused without it while the group holds the node.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletingAGroupLeavesTheSharedNodeInTheTargetGroupsAnotherGroupHoldsAndTerminatesNothing(
            boolean throughAutoScaling) {
        groupsSharingTheNode(service);
        service.attachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-first", "tg-shared"));
        service.attachLoadBalancerTargetGroups(REGION, SECOND, List.of("tg-shared"));
        clearInvocations(targets);

        AwsException inUse = assertThrows(AwsException.class,
                () -> service.deleteAutoScalingGroup(REGION, FIRST, false));
        assertEquals("ResourceInUse", inUse.getErrorCode());

        delete(FIRST, throughAutoScaling);

        assertEquals(List.of(), service.describeAutoScalingGroups(REGION, List.of(FIRST)));
        assertEquals(List.of(NODE), memberIds(SECOND));
        verify(targets).deregisterTargets(eq(REGION), eq("tg-first"), onlyTarget(NODE));
        verifyNoMoreInteractions(targets);

        delete(SECOND, throughAutoScaling);

        assertEquals(List.of(), service.describeAutoScalingGroups(REGION, null));
        verify(targets).deregisterTargets(eq(REGION), eq("tg-shared"), onlyTarget(NODE));
        verifyNoMoreInteractions(targets);
        // The node is EKS's: deleting its groups terminates nothing.
        verifyNoInteractions(ec2);
    }

    @Test
    void attachingWhileAnotherGroupDetachesTheSameTargetGroupLeavesTheSharedNodeRegistered() throws Exception {
        groupsSharingTheNode(service);
        // The target group's registered targets, as ELB keeps them.
        Set<String> registered = ConcurrentHashMap.newKeySet();
        CountDownLatch deregistering = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<TargetDescription> described = invocation.getArgument(2);
            described.forEach(target -> registered.add(target.getId()));
            return null;
        }).when(targets).registerTargets(eq(REGION), eq("tg-shared"), anyList());
        doAnswer(invocation -> {
            deregistering.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "the parked deregistration was not released");
            List<TargetDescription> described = invocation.getArgument(2);
            described.forEach(target -> registered.remove(target.getId()));
            return null;
        }).when(targets).deregisterTargets(eq(REGION), eq("tg-shared"), anyList());
        service.attachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-shared"));
        assertEquals(Set.of(NODE), registered);

        // FIRST's detach finds no other group with tg-shared and parks deregistering the node from it.
        FutureTask<Void> detach = new FutureTask<>(
                () -> service.detachLoadBalancerTargetGroups(REGION, FIRST, List.of("tg-shared")), null);
        FutureTask<Void> attach = new FutureTask<>(
                () -> service.attachLoadBalancerTargetGroups(REGION, SECOND, List.of("tg-shared")), null);
        Thread detaching = Thread.ofPlatform().daemon().start(detach);
        Thread attaching = Thread.ofPlatform().daemon().unstarted(attach);
        try {
            assertTrue(deregistering.await(5, TimeUnit.SECONDS), "the detach did not reach the deregistration");
            attaching.start();
            ThreadStates.awaitBlockedOrDone(attaching);
            assertEquals(Thread.State.BLOCKED, attaching.getState());
        } finally {
            release.countDown();
        }
        detach.get(5, TimeUnit.SECONDS);
        attach.get(5, TimeUnit.SECONDS);
        detaching.join(5000);

        assertEquals(Set.of(NODE), registered);
    }

    @Test
    void operationsThatWouldMoveOrReplaceTheNodeAreRefusedAndTheRestAreNot() {
        service.createEksManagedGroup(REGION, FIRST, 1, 3, 2, List.of(), nodeGroupTags("workers"));
        service.addEksManagedInstance(REGION, FIRST, node());

        assertRefused(() -> service.terminateInstanceInAutoScalingGroup(REGION, NODE, false));
        assertRefused(() -> service.enterStandby(REGION, FIRST, List.of(NODE), false));
        assertRefused(() -> service.exitStandby(REGION, FIRST, List.of(NODE)));
        assertRefused(() -> service.startInstanceRefresh(REGION, FIRST, new InstanceRefresh()));
        assertRefused(() -> service.setInstanceHealth(REGION, NODE, "Unhealthy", false));
        assertRefused(() -> service.attachInstances(REGION, FIRST, List.of("i-0fedcba9876543210")));

        AutoScalingGroup group = service.requireGroup(REGION, FIRST);
        AsgInstance member = group.getInstances().getFirst();
        assertEquals(List.of(NODE), memberIds(FIRST));
        assertEquals("InService", member.getLifecycleState());
        assertEquals("Healthy", member.getHealthStatus());
        assertNull(member.getStandbyActivityId());
        assertEquals(2, group.getDesiredCapacity());
        assertEquals(List.of(), service.describeInstanceRefreshes(REGION, FIRST, null, null, null)
                .instanceRefreshes());

        // Changes that leave the node where it is stay allowed.
        service.setInstanceHealth(REGION, NODE, "Healthy", false);
        service.setDesiredCapacity(REGION, FIRST, 3);
        service.setInstanceProtection(REGION, FIRST, List.of(NODE), true);

        assertEquals(3, group.getDesiredCapacity());
        assertTrue(member.isProtectedFromScaleIn());
        verifyNoInteractions(targets, ec2);
    }

    private static void assertRefused(Executable operation) {
        AwsException refused = assertThrows(AwsException.class, operation);
        assertEquals("ValidationError", refused.getErrorCode());
        assertEquals("Auto Scaling group '" + FIRST + "' is managed by Amazon EKS for a node group. Its instance is"
                + " the cluster's node, which the cluster's node groups share.", refused.getMessage());
        assertEquals(400, refused.getHttpStatus());
    }

    /** Deletes {@code group} as EKS does with its node group, or with DeleteAutoScalingGroup and ForceDelete. */
    private void delete(String group, boolean throughAutoScaling) {
        if (throughAutoScaling) {
            service.deleteAutoScalingGroup(REGION, group, true);
        } else {
            service.deleteEksManagedGroup(REGION, group);
        }
    }

    private List<String> memberIds(String group) {
        return service.requireGroup(REGION, group).getInstances().stream().map(AsgInstance::getInstanceId).toList();
    }
}
