package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ElasticLoadBalancing;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.Action;
import io.floci.aws.services.elbv2.model.Listener;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.services.elbv2.model.TargetGroup;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * EKS Auto Mode's load balancing capability for Services: while a cluster has it on, a Service of type
 * LoadBalancer whose {@code loadBalancerClass} is {@code eks.amazonaws.com/nlb} gets a Network Load Balancer in the
 * cluster's subnets, as on AWS. Each of the Service's TCP ports gets a target group on its NodePort, with the
 * cluster's node registered by instance ID as node group target groups register it and a TCP health check, and a
 * listener on the Service port. Once the node is registered, the load balancer's DNS name is written to the
 * Service's {@code status.loadBalancer}. The load balancer is deleted with the Service, with the cluster, and when
 * the cluster's load balancing is turned off.
 *
 * <p>Floci reads the cluster with {@code kubectl} in the node, so this reconciles on an interval rather than
 * watching. It keeps no state of its own: each pass makes the load balancer, target groups, registration and
 * listeners the Service needs, so a pass that failed halfway is finished by the next. What it made carries the
 * cluster and the Service in the tags Auto Mode sets, {@code eks:eks-cluster-name} and
 * {@code service.eks.amazonaws.com/stack}, and a name hashed from the cluster's ARN and the Service, and is found
 * again by both.
 *
 * <p>A Service with no {@code loadBalancerClass}, which Auto Mode also serves as its cluster default, stays with
 * k3s's own ServiceLB here. UDP and SCTP ports get no listener, as Floci's load balancers serve TCP and HTTP only,
 * and a Service with no TCP NodePort gets no load balancer.
 */
@ApplicationScoped
public class EksAutoModeLoadBalancers {

    private static final Logger LOG = Logger.getLogger(EksAutoModeLoadBalancers.class);
    static final String NLB_CLASS = "eks.amazonaws.com/nlb"; // partition-literal: a Kubernetes class name, not a host
    /** The tag keys Auto Mode sets, the only ones {@code AmazonEKSLoadBalancingPolicy} lets it set on them. */
    static final String CLUSTER_TAG = "eks:eks-cluster-name";
    static final String STACK_TAG = "service.eks.amazonaws.com/stack"; // partition-literal: a tag key, not a host
    static final String SCHEME_ANNOTATION = "service.beta.kubernetes.io/aws-load-balancer-scheme";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** A target group's name ends with the Service port it serves and the hash. */
    private static final Pattern PORT_SUFFIX = Pattern.compile("-(\\d{1,5})-[0-9a-f]{10}$");

    private final EksService eksService;
    private final EksClusterManager clusterManager;
    private final ElbV2Service elb;
    private final EmulatorConfig config;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "eks-auto-mode-load-balancers"));

    @Inject
    public EksAutoModeLoadBalancers(EksService eksService, EksClusterManager clusterManager, ElbV2Service elb,
                                    EmulatorConfig config) {
        this.eksService = eksService;
        this.clusterManager = clusterManager;
        this.elb = elb;
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        // Observing the startup event creates the bean, which starts the reconciler.
    }

    @PostConstruct
    void start() {
        if (!config.services().eks().mock()) {
            scheduler.scheduleAtFixedRate(this::reconcileAll, 10, 10, TimeUnit.SECONDS);
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    void reconcileAll() {
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : eksService.runningClusters()) {
            Cluster cluster = entry.value();
            String region = eksService.clusterRegion(cluster);
            try {
                RequestScopes.runAs(entry.accountId(), region, () -> {
                    if (loadBalancingOn(cluster)) {
                        reconcile(cluster, region);
                    } else {
                        deleteLoadBalancers(cluster, region);
                    }
                });
            } catch (RuntimeException e) {
                LOG.warnv(e, "EKS Auto Mode load balancers of cluster {0} were not reconciled", cluster.getName());
            }
        }
    }

    private static boolean loadBalancingOn(Cluster cluster) {
        return cluster.getKubernetesNetworkConfig() != null
                && ElasticLoadBalancing.loadBalancingOn(cluster.getKubernetesNetworkConfig().getElasticLoadBalancing());
    }

    /** One Service port: its protocol, the port clients use and the NodePort the node serves it on. */
    record ServicePort(String protocol, int port, int nodePort) { }

    /**
     * A Service that gets a load balancer: its namespace, name and UID, the scheme it asks for, and its TCP ports.
     */
    record NlbService(String namespace, String name, String uid, String scheme, List<ServicePort> ports) {

        String stack() {
            return namespace + "/" + name;
        }
    }

    /**
     * Makes what each Service needs, writes its DNS name back once it can serve, and deletes the load balancers of
     * Services that are gone.
     */
    void reconcile(Cluster cluster, String region) {
        List<NlbService> services = nlbServices(readServices(cluster));
        Map<String, LoadBalancer> existing = loadBalancersOf(cluster, region);
        Instance node = clusterManager.getRegisteredClusterNodeInstance(cluster);
        for (NlbService service : services) {
            LoadBalancer lb = existing.remove(service.stack());
            if (lb == null) {
                lb = createLoadBalancer(cluster, region, service);
            }
            if (ensurePorts(cluster, region, service, lb, node)) {
                writeStatus(cluster, service, lb.getDnsName());
            }
        }
        existing.forEach((stack, lb) -> delete(cluster, region, stack, lb));
    }

    /** Deletes every load balancer made for {@code cluster}'s Services, with their listeners and target groups. */
    public void deleteLoadBalancers(Cluster cluster, String region) {
        loadBalancersOf(cluster, region).forEach((stack, lb) -> delete(cluster, region, stack, lb));
    }

    /**
     * The Services of {@code kubectl get services -A -o json} that get an Auto Mode Network Load Balancer: of type
     * LoadBalancer, of its class, with a TCP port on a NodePort.
     */
    static List<NlbService> nlbServices(JsonNode list) {
        List<NlbService> services = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            JsonNode spec = item.path("spec");
            if (!"LoadBalancer".equals(spec.path("type").asText()) || !NLB_CLASS.equals(spec.path("loadBalancerClass").asText())) {
                continue;
            }
            List<ServicePort> ports = new ArrayList<>();
            for (JsonNode port : spec.path("ports")) {
                if ("TCP".equals(port.path("protocol").asText("TCP")) && port.path("nodePort").asInt() > 0) {
                    ports.add(new ServicePort("TCP", port.path("port").asInt(), port.path("nodePort").asInt()));
                }
            }
            if (ports.isEmpty()) {
                continue;
            }
            JsonNode metadata = item.path("metadata");
            services.add(new NlbService(metadata.path("namespace").asText(), metadata.path("name").asText(),
                    metadata.path("uid").asText(""), metadata.path("annotations").path(SCHEME_ANNOTATION).asText("internal"),
                    ports));
        }
        return services;
    }

    private JsonNode readServices(Cluster cluster) {
        ContainerExec.Result result = clusterManager.execInContainerForResult(cluster.getContainerId(),
                new String[]{"kubectl", "get", "services", "-A", "-o", "json"}, 15);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("kubectl get services failed: " + result.summary());
        }
        try {
            return MAPPER.readTree(result.stdout());
        } catch (Exception e) {
            throw new IllegalStateException("kubectl get services returned no JSON: " + e.getMessage(), e);
        }
    }

    /**
     * The load balancers made for {@code cluster}'s Services, by the Service they were made for: network load
     * balancers with the cluster's and a Service's tags, named as this names that Service's.
     */
    private Map<String, LoadBalancer> loadBalancersOf(Cluster cluster, String region) {
        Map<String, LoadBalancer> found = new LinkedHashMap<>();
        List<LoadBalancer> all = elb.describeLoadBalancers(region, null, null, null, null);
        if (all.isEmpty()) {
            return found;
        }
        Map<String, Map<String, String>> tags = elb.describeTags(all.stream().map(LoadBalancer::getLoadBalancerArn).toList());
        for (LoadBalancer lb : all) {
            String stack = stackOf(cluster, tags.get(lb.getLoadBalancerArn()));
            if (stack != null && "network".equals(lb.getType())
                    && resourceName(cluster, stack, "").equals(lb.getLoadBalancerName())) {
                found.put(stack, lb);
            }
        }
        return found;
    }

    /** The target groups made for the Service {@code stack} of {@code cluster}, by name, wherever they are attached. */
    private Map<String, TargetGroup> targetGroupsOf(Cluster cluster, String region, String stack) {
        Map<String, TargetGroup> found = new HashMap<>();
        List<TargetGroup> all = elb.describeTargetGroups(region, null, null, null);
        if (all.isEmpty()) {
            return found;
        }
        Map<String, Map<String, String>> tags = elb.describeTags(all.stream().map(TargetGroup::getTargetGroupArn).toList());
        for (TargetGroup group : all) {
            Matcher port = PORT_SUFFIX.matcher(group.getTargetGroupName());
            if (stack.equals(stackOf(cluster, tags.get(group.getTargetGroupArn()))) && port.find()
                    && resourceName(cluster, stack, "-" + port.group(1)).equals(group.getTargetGroupName())) {
                found.put(group.getTargetGroupName(), group);
            }
        }
        return found;
    }

    /** The Service, {@code namespace/name}, a resource's tags name, if they name {@code cluster}. */
    private static String stackOf(Cluster cluster, Map<String, String> tags) {
        if (tags == null || !cluster.getName().equals(tags.get(CLUSTER_TAG))) {
            return null;
        }
        String stack = tags.get(STACK_TAG);
        return stack != null && stack.indexOf('/') > 0 ? stack : null;
    }

    private static Map<String, String> tags(Cluster cluster, NlbService service) {
        return Map.of(CLUSTER_TAG, cluster.getName(), STACK_TAG, service.stack());
    }

    private LoadBalancer createLoadBalancer(Cluster cluster, String region, NlbService service) {
        List<String> subnets = cluster.getResourcesVpcConfig() == null ? List.of()
                : cluster.getResourcesVpcConfig().getSubnetIds();
        LoadBalancer lb = elb.createLoadBalancer(region, resourceName(cluster, service.stack(), ""), service.scheme(),
                "network", "ipv4", subnets, List.of(), tags(cluster, service));
        LOG.infov("Created Network Load Balancer {0} for Service {1} of EKS cluster {2}",
                lb.getLoadBalancerName(), service.stack(), cluster.getName());
        return lb;
    }

    /**
     * Makes each port's target group, the node's registration in it and the port's listener where missing, and
     * removes those of ports the Service no longer has. A target group whose NodePort changed is made again, as its
     * port can't change. Answers whether every port can serve: the node is registered.
     */
    private boolean ensurePorts(Cluster cluster, String region, NlbService service, LoadBalancer lb, Instance node) {
        Map<String, TargetGroup> groups = targetGroupsOf(cluster, region, service.stack());
        Map<Integer, Listener> listeners = new HashMap<>();
        elb.describeListeners(region, lb.getLoadBalancerArn(), null).forEach(l -> listeners.put(l.getPort(), l));
        Set<String> wanted = new HashSet<>();
        for (ServicePort port : service.ports()) {
            String name = resourceName(cluster, service.stack(), "-" + port.port());
            wanted.add(name);
            TargetGroup group = groups.get(name);
            Listener listener = listeners.remove(port.port());
            if (group != null && !Integer.valueOf(port.nodePort()).equals(group.getPort())) {
                if (listener != null) {
                    elb.deleteListener(region, listener.getListenerArn());
                    listener = null;
                }
                elb.deleteTargetGroup(region, group.getTargetGroupArn());
                group = null;
            }
            if (group == null) {
                String vpcId = cluster.getResourcesVpcConfig() == null ? null : cluster.getResourcesVpcConfig().getVpcId();
                group = elb.createTargetGroup(region, name, "TCP", null, port.nodePort(), vpcId, "instance", "TCP",
                        null, null, null, null, null, null, null, null, "ipv4", tags(cluster, service));
            }
            if (node != null && (group.getTargets() == null
                    || group.getTargets().stream().noneMatch(t -> node.getInstanceId().equals(t.getId())))) {
                TargetDescription target = new TargetDescription();
                target.setId(node.getInstanceId());
                elb.registerTargets(region, group.getTargetGroupArn(), List.of(target));
            }
            if (listener == null) {
                Action forward = new Action();
                forward.setType("forward");
                forward.setTargetGroupArn(group.getTargetGroupArn());
                elb.createListener(region, lb.getLoadBalancerArn(), "TCP", port.port(), null, null, List.of(forward),
                        null, tags(cluster, service));
            }
        }
        listeners.values().forEach(l -> elb.deleteListener(region, l.getListenerArn()));
        groups.forEach((name, group) -> {
            if (!wanted.contains(name)) {
                elb.deleteTargetGroup(region, group.getTargetGroupArn());
            }
        });
        return node != null;
    }

    private void delete(Cluster cluster, String region, String stack, LoadBalancer lb) {
        elb.describeListeners(region, lb.getLoadBalancerArn(), null)
                .forEach(listener -> elb.deleteListener(region, listener.getListenerArn()));
        elb.deleteLoadBalancer(region, lb.getLoadBalancerArn());
        targetGroupsOf(cluster, region, stack).values()
                .forEach(group -> elb.deleteTargetGroup(region, group.getTargetGroupArn()));
        LOG.infov("Deleted Network Load Balancer {0} of Service {1} of EKS cluster {2}", lb.getLoadBalancerName(),
                stack, cluster.getName());
    }

    /**
     * Writes the load balancer's DNS name to the Service's status, as the controller on AWS does. The patch names
     * the Service's UID, so a Service made again under the same name meanwhile refuses it.
     */
    private void writeStatus(Cluster cluster, NlbService service, String dnsName) {
        String identity = service.uid().isEmpty() ? "" : "\"metadata\":{\"uid\":\"" + service.uid() + "\"},";
        String patch = "{" + identity + "\"status\":{\"loadBalancer\":{\"ingress\":[{\"hostname\":\"" + dnsName
                + "\"}]}}}";
        ContainerExec.Result result = clusterManager.execInContainerForResult(cluster.getContainerId(),
                new String[]{"kubectl", "patch", "service", service.name(), "-n", service.namespace(),
                        "--subresource=status", "--type=merge", "-p", patch}, 15);
        if (result.exitCode() != 0) {
            LOG.warnv("Could not write the load balancer of Service {0} to its status: {1}",
                    service.stack(), result.summary());
        }
    }

    /**
     * A load balancer or target group name: {@code k8s-<namespace>-<name><suffix>-<hash>}, within ELBv2's 32
     * characters, the namespace and name cut so the suffix, a target group's Service port, stays whole. The hash
     * names the cluster's ARN and the Service, so two clusters' Services of one name don't collide.
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
