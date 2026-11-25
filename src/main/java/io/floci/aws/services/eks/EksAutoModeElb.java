package io.floci.aws.services.eks;

import io.floci.aws.core.common.Digests;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.services.elbv2.model.TargetGroup;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ELBv2 resources EKS Auto Mode makes for a cluster's Services and Ingresses, found again without state of their
 * own: each carries the cluster and its Kubernetes object, {@code namespace/name}, in the tags Auto Mode sets
 * ({@code eks:eks-cluster-name} and a stack tag of the object's kind), and a name hashed from the cluster's ARN and
 * the object. Only what has both is taken as Auto Mode's, so a load balancer that merely carries the tags is left
 * alone.
 */
final class EksAutoModeElb {

    static final String CLUSTER_TAG = "eks:eks-cluster-name";
    /** A target group's name ends with what it serves, a port number, and the hash. */
    private static final Pattern PORT_SUFFIX = Pattern.compile("-(\\d{1,5})-[0-9a-f]{10}$");

    private final ElbV2Service elb;

    EksAutoModeElb(ElbV2Service elb) {
        this.elb = elb;
    }

    /** The load balancers of {@code type} made for {@code cluster}'s objects of {@code stackTag}, by object. */
    Map<String, LoadBalancer> loadBalancersOf(Cluster cluster, String region, String stackTag, String type) {
        Map<String, LoadBalancer> found = new LinkedHashMap<>();
        List<LoadBalancer> all = elb.describeLoadBalancers(region, null, null, null, null);
        if (all.isEmpty()) {
            return found;
        }
        Map<String, Map<String, String>> tags = elb.describeTags(all.stream().map(LoadBalancer::getLoadBalancerArn).toList());
        for (LoadBalancer lb : all) {
            String stack = stackOf(cluster, tags.get(lb.getLoadBalancerArn()), stackTag);
            if (stack != null && type.equals(lb.getType())
                    && resourceName(cluster, stack, "").equals(lb.getLoadBalancerName())) {
                found.put(stack, lb);
            }
        }
        return found;
    }

    /** The target groups made for the object {@code stack} of {@code cluster}, by name, wherever they are attached. */
    Map<String, TargetGroup> targetGroupsOf(Cluster cluster, String region, String stackTag, String stack) {
        Map<String, TargetGroup> found = new HashMap<>();
        List<TargetGroup> all = elb.describeTargetGroups(region, null, null, null);
        if (all.isEmpty()) {
            return found;
        }
        Map<String, Map<String, String>> tags = elb.describeTags(all.stream().map(TargetGroup::getTargetGroupArn).toList());
        for (TargetGroup group : all) {
            Matcher port = PORT_SUFFIX.matcher(group.getTargetGroupName());
            if (stack.equals(stackOf(cluster, tags.get(group.getTargetGroupArn()), stackTag)) && port.find()
                    && resourceName(cluster, stack, "-" + port.group(1)).equals(group.getTargetGroupName())) {
                found.put(group.getTargetGroupName(), group);
            }
        }
        return found;
    }

    /** Registers the cluster's node in {@code group} by instance ID, unless it is registered. */
    void registerNode(String region, TargetGroup group, Instance node) {
        if (node != null && (group.getTargets() == null
                || group.getTargets().stream().noneMatch(t -> node.getInstanceId().equals(t.getId())))) {
            TargetDescription target = new TargetDescription();
            target.setId(node.getInstanceId());
            elb.registerTargets(region, group.getTargetGroupArn(), List.of(target));
        }
    }

    /** Deletes {@code lb} with its listeners, and every target group made for its object, attached or not. */
    void delete(Cluster cluster, String region, String stackTag, String stack, LoadBalancer lb) {
        elb.describeListeners(region, lb.getLoadBalancerArn(), null)
                .forEach(listener -> elb.deleteListener(region, listener.getListenerArn()));
        elb.deleteLoadBalancer(region, lb.getLoadBalancerArn());
        targetGroupsOf(cluster, region, stackTag, stack).values()
                .forEach(group -> elb.deleteTargetGroup(region, group.getTargetGroupArn()));
    }

    /** The object, {@code namespace/name}, a resource's tags name, if they name {@code cluster}. */
    private static String stackOf(Cluster cluster, Map<String, String> tags, String stackTag) {
        if (tags == null || !cluster.getName().equals(tags.get(CLUSTER_TAG))) {
            return null;
        }
        String stack = tags.get(stackTag);
        return stack != null && stack.indexOf('/') > 0 ? stack : null;
    }

    /**
     * A load balancer or target group name: {@code k8s-<namespace>-<name><suffix>-<hash>}, within ELBv2's 32
     * characters, the namespace and name cut so the suffix, a target group's port, stays whole. The hash names the
     * cluster's ARN and the object, so two clusters' objects of one name don't collide.
     */
    static String resourceName(Cluster cluster, String stack, String suffix) {
        String hash = Digests.sha256Hex(cluster.getArn() + "/" + stack + suffix).substring(0, 10);
        int slash = stack.indexOf('/');
        String base = ("k8s-" + cut(stack.substring(0, slash), 7) + "-" + cut(stack.substring(slash + 1), 7))
                .replaceAll("[^A-Za-z0-9-]", "-");
        return (cut(base, 32 - 11 - suffix.length()) + suffix + "-" + hash).replaceAll("-+", "-");
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
