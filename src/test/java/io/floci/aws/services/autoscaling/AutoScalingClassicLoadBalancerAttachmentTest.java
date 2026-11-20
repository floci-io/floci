package io.floci.aws.services.autoscaling;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.floci.aws.services.elb.ElbClassicService;
import io.floci.aws.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.FIRST;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.GROUP;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.IN_SERVICE;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.NODE;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.REGION;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.SECOND;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.STANDBY;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.groupsSharingTheNode;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.node;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.nodeGroupTags;
import static io.floci.aws.services.autoscaling.AutoScalingGroupFixtures.ordinaryGroup;
import static io.floci.aws.services.autoscaling.TargetMatchers.exactlyTargets;
import static io.floci.aws.services.autoscaling.TargetMatchers.onlyTarget;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Attaching a Classic Load Balancer to a group registers the group's in-service instances with it, and detaching one
 * deregisters the group's instances from it, as AWS does and as {@link AutoScalingTargetGroupAttachmentTest} checks
 * for target groups: by name and as {@code elb} traffic sources, for an ordinary group and for EKS groups sharing
 * their cluster's node.
 */
class AutoScalingClassicLoadBalancerAttachmentTest {

    private final ElbV2Service targets = mock(ElbV2Service.class);
    private final ElbClassicService classic = mock(ElbClassicService.class);
    private final AutoScalingService service = AutoScalingGroupFixtures.service(targets, classic);
    private final AutoScalingGroup group = ordinaryGroup(service);

    AutoScalingClassicLoadBalancerAttachmentTest() {
        group.setLoadBalancerNames(new ArrayList<>(List.of("lb-attached")));
    }

    @Test
    void attachingAClassicLoadBalancerRegistersTheGroupsInServiceInstancesWithIt() {
        service.attachLoadBalancers(REGION, GROUP, List.of("lb-attached", "lb-new"));

        // Only the load balancer the attach added, and only the instance in service: one in standby is not.
        verify(classic).registerInstances(REGION, "lb-new", List.of(IN_SERVICE));
        verifyNoMoreInteractions(classic);
        assertEquals(List.of("lb-attached", "lb-new"), group.getLoadBalancerNames());
    }

    @Test
    void detachingAClassicLoadBalancerDeregistersTheGroupsInstancesFromIt() {
        group.getLoadBalancerNames().add("lb-kept");

        service.detachLoadBalancers(REGION, GROUP, List.of("lb-attached"));

        verify(classic).deregisterInstances(REGION, "lb-attached", List.of(IN_SERVICE, STANDBY));
        verifyNoMoreInteractions(classic);
        assertEquals(List.of("lb-kept"), group.getLoadBalancerNames());
    }

    @Test
    void anElbTrafficSourceRegistersAndDeregistersTheInstancesAsAClassicLoadBalancerDoes() {
        service.attachTrafficSources(REGION, GROUP, List.of(
                new AutoScalingService.TrafficSourceIdentifier("lb-source", "elb")));
        verify(classic).registerInstances(REGION, "lb-source", List.of(IN_SERVICE));

        service.detachTrafficSources(REGION, GROUP, List.of(
                new AutoScalingService.TrafficSourceIdentifier("lb-source", "elb")));

        verify(classic).deregisterInstances(REGION, "lb-source", List.of(IN_SERVICE, STANDBY));
        verifyNoMoreInteractions(classic);
        assertEquals(Map.of(), group.getTrafficSourceTypeByIdentifier());
    }

    /** A group deleted with its instances takes them out of its {@code elb} traffic sources too. */
    @Test
    void aForceDeleteDeregistersTheInstancesFromAnElbTrafficSource() {
        group.getTrafficSourceTypeByIdentifier().put("lb-source", "elb");

        service.deleteAutoScalingGroup(REGION, GROUP, true);

        verify(classic).deregisterInstances(REGION, "lb-attached", List.of(IN_SERVICE, STANDBY));
        verify(classic).deregisterInstances(REGION, "lb-source", List.of(IN_SERVICE, STANDBY));
    }

    @Test
    void aDetachTheLoadBalancerRefusesLeavesItAttachedSoTheDetachCanBeSentAgain() {
        AwsException refusal = new AwsException("Throttling", "Rate exceeded", 400);
        when(classic.deregisterInstances(REGION, "lb-attached", List.of(IN_SERVICE, STANDBY)))
                .thenThrow(refusal).thenReturn(List.of());

        assertSame(refusal, assertThrows(AwsException.class,
                () -> service.detachLoadBalancers(REGION, GROUP, List.of("lb-attached"))));
        assertEquals(List.of("lb-attached"), group.getLoadBalancerNames());

        service.detachLoadBalancers(REGION, GROUP, List.of("lb-attached"));

        verify(classic, times(2)).deregisterInstances(REGION, "lb-attached", List.of(IN_SERVICE, STANDBY));
        assertEquals(List.of(), group.getLoadBalancerNames());
    }

    @Test
    void aClassicLoadBalancerDeletedBeforeItsDetachIsDetached() {
        when(classic.deregisterInstances(REGION, "lb-attached", List.of(IN_SERVICE, STANDBY)))
                .thenThrow(new AwsException("LoadBalancerNotFound",
                        "There is no ACTIVE Load Balancer named 'lb-attached'", 400));

        service.detachLoadBalancers(REGION, GROUP, List.of("lb-attached"));

        assertEquals(List.of(), group.getLoadBalancerNames());
    }

    /**
     * A detach whose Classic Load Balancer refuses after the members left the target group it also named puts the
     * in-service member back in that target group and leaves both attached, as they were, and sending the detach
     * again finishes it.
     */
    @Test
    void aDetachWhoseClassicLoadBalancerRefusesPutsTheMembersBackInWhatTheyAlreadyLeft() {
        group.getTrafficSourceTypeByIdentifier().put("tg-source", "elbv2");
        group.getTrafficSourceTypeByIdentifier().put("lb-source", "elb");
        AwsException refusal = new AwsException("Throttling", "Rate exceeded", 400);
        when(classic.deregisterInstances(REGION, "lb-source", List.of(IN_SERVICE, STANDBY)))
                .thenThrow(refusal).thenReturn(List.of());
        List<AutoScalingService.TrafficSourceIdentifier> both = List.of(
                new AutoScalingService.TrafficSourceIdentifier("tg-source", "elbv2"),
                new AutoScalingService.TrafficSourceIdentifier("lb-source", "elb"));

        assertSame(refusal, assertThrows(AwsException.class,
                () -> service.detachTrafficSources(REGION, GROUP, both)));

        assertEquals(Map.of("tg-source", "elbv2", "lb-source", "elb"), group.getTrafficSourceTypeByIdentifier());
        InOrder order = inOrder(targets);
        order.verify(targets).deregisterTargets(eq(REGION), eq("tg-source"), exactlyTargets(IN_SERVICE, STANDBY));
        order.verify(targets).registerTargets(eq(REGION), eq("tg-source"), onlyTarget(IN_SERVICE));

        service.detachTrafficSources(REGION, GROUP, both);

        verify(targets, times(2)).deregisterTargets(eq(REGION), eq("tg-source"), exactlyTargets(IN_SERVICE, STANDBY));
        verify(classic, times(2)).deregisterInstances(REGION, "lb-source", List.of(IN_SERVICE, STANDBY));
        assertEquals(Map.of(), group.getTrafficSourceTypeByIdentifier());
    }

    /** The groups of a cluster's node groups share its node, which stays in a load balancer either has attached. */
    @Test
    void detachingAClassicLoadBalancerLeavesTheSharedNodeInItWhileAnotherEksGroupHasItAttached() {
        groupsSharingTheNode(service);
        service.attachLoadBalancers(REGION, FIRST, List.of("lb-shared"));
        service.attachTrafficSources(REGION, SECOND, List.of(
                new AutoScalingService.TrafficSourceIdentifier("lb-shared", "elb")));
        verify(classic, times(2)).registerInstances(REGION, "lb-shared", List.of(NODE));
        clearInvocations(classic);

        service.detachLoadBalancers(REGION, FIRST, List.of("lb-shared"));

        verifyNoInteractions(classic);

        service.detachTrafficSources(REGION, SECOND, List.of(
                new AutoScalingService.TrafficSourceIdentifier("lb-shared", "elb")));

        verify(classic).deregisterInstances(REGION, "lb-shared", List.of(NODE));
        verifyNoMoreInteractions(classic);
    }

    /** A node that joins an EKS group after a Classic Load Balancer was attached to it is registered with it. */
    @Test
    void aNodeJoiningAnEksGroupIsRegisteredWithItsClassicLoadBalancers() {
        service.createEksManagedGroup(REGION, FIRST, 1, 3, 2, List.of(), nodeGroupTags("workers"));
        service.attachLoadBalancers(REGION, FIRST, List.of("lb-web"));
        verifyNoInteractions(classic);

        service.addEksManagedInstance(REGION, FIRST, node());

        verify(classic).registerInstances(REGION, "lb-web", List.of(NODE));
    }

    /** Deleting an EKS group, or detaching the node from it, leaves the node in what the other group has attached. */
    @Test
    void deletingOrDetachingFromAnEksGroupLeavesTheNodeInTheLoadBalancersTheOtherGroupHasAttached() {
        groupsSharingTheNode(service);
        service.attachLoadBalancers(REGION, FIRST, List.of("lb-shared", "lb-first"));
        service.attachLoadBalancers(REGION, SECOND, List.of("lb-shared"));
        clearInvocations(classic);

        service.detachInstances(REGION, FIRST, List.of(NODE), false);

        verify(classic).deregisterInstances(REGION, "lb-first", List.of(NODE));
        verifyNoMoreInteractions(classic);

        service.deleteEksManagedGroup(REGION, SECOND);

        verify(classic).deregisterInstances(REGION, "lb-shared", List.of(NODE));
        verifyNoMoreInteractions(classic);
    }
}
