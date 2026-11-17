package io.floci.aws.services.autoscaling;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.autoscaling.model.AsgInstance;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.Placement;
import io.floci.aws.services.elb.ElbClassicService;
import io.floci.aws.services.elbv2.ElbV2Service;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;

/**
 * The groups the load balancer attachment tests share: an ordinary group with one member in service and one in
 * standby, and the groups of two node groups of one EKS cluster, which both hold the cluster's one node, as Floci runs
 * a cluster.
 */
final class AutoScalingGroupFixtures {

    static final String REGION = "us-east-1";
    static final String GROUP = "web";
    static final String IN_SERVICE = "i-0a1b2c3d4e5f60001";
    static final String STANDBY = "i-0a1b2c3d4e5f60002";
    static final String FIRST = "eks-workers-1a2b3c4d";
    static final String SECOND = "eks-batch-5e6f7a8b";
    static final String NODE = "i-0123456789abcdef0";

    private AutoScalingGroupFixtures() {
    }

    /** A service in {@link #REGION} on the given load balancer services, with EC2 mocked. */
    static AutoScalingService service(ElbV2Service targets, ElbClassicService classic) {
        AutoScalingService service = new AutoScalingService(targets, classic);
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = mock(Ec2Service.class);
        return service;
    }

    /**
     * {@link #GROUP}, an ordinary group of {@link #REGION} holding {@link #IN_SERVICE} in service and {@link #STANDBY}
     * in standby, stored in {@code service}.
     */
    static AutoScalingGroup ordinaryGroup(AutoScalingService service) {
        AutoScalingGroup group = new AutoScalingGroup();
        group.setAutoScalingGroupName(GROUP);
        group.setRegion(REGION);
        group.getInstances().addAll(List.of(member(IN_SERVICE, "InService"), member(STANDBY, "Standby")));
        service.groups.put(REGION + "::" + GROUP, group);
        return group;
    }

    static AsgInstance member(String id, String state) {
        AsgInstance member = new AsgInstance();
        member.setInstanceId(id);
        member.setLifecycleState(state);
        return member;
    }

    /** {@link #FIRST} and {@link #SECOND}, the groups of two node groups of one cluster, both holding its node. */
    static void groupsSharingTheNode(AutoScalingService service) {
        service.createEksManagedGroup(REGION, FIRST, 1, 3, 2, List.of(), nodeGroupTags("workers"));
        service.createEksManagedGroup(REGION, SECOND, 1, 1, 1, List.of(), nodeGroupTags("batch"));
        service.addEksManagedInstance(REGION, FIRST, node());
        service.addEksManagedInstance(REGION, SECOND, node());
    }

    /** The tags EKS gives the group of the node group {@code nodegroupName} of the cluster {@code shop}. */
    static Map<String, String> nodeGroupTags(String nodegroupName) {
        return Map.of(
                "eks:cluster-name", "shop",
                "eks:nodegroup-name", nodegroupName,
                "k8s.io/cluster-autoscaler/enabled", "true",
                "k8s.io/cluster-autoscaler/shop", "owned");
    }

    /** {@link #NODE}, the cluster's node, as EKS adds it to a node group's group. */
    static Instance node() {
        Instance node = new Instance();
        node.setInstanceId(NODE);
        node.setInstanceType("m5.large");
        node.setPlacement(new Placement("us-east-1b"));
        return node;
    }
}
