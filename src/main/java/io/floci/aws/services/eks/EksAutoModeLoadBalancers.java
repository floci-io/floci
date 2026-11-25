package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.ElasticLoadBalancing;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.Action;
import io.floci.aws.services.elbv2.model.Listener;
import io.floci.aws.services.elbv2.model.LoadBalancer;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * EKS Auto Mode's load balancing capability, for Services here and for Ingresses through
 * {@link EksAutoModeIngresses}: while a cluster has it on, a Service of type
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
 * <p>A Service with no {@code loadBalancerClass} gets one too, as {@code eks.amazonaws.com/nlb} is then the cluster
 * default, while the cluster's node runs without k3s's own ServiceLB: a node started with load balancing on does.
 * A node whose load balancing was turned on after it started keeps ServiceLB, and with it those Services, until it
 * is next replaced, so that no Service has two load balancers. UDP and SCTP ports get no listener, as Floci's load
 * balancers serve TCP and HTTP only, and a Service with no TCP NodePort gets no load balancer.
 */
@ApplicationScoped
public class EksAutoModeLoadBalancers {

    private static final Logger LOG = Logger.getLogger(EksAutoModeLoadBalancers.class);
    static final String NLB_CLASS = "eks.amazonaws.com/nlb"; // partition-literal: a Kubernetes class name, not a host
    /** The tag keys Auto Mode sets, the only ones {@code AmazonEKSLoadBalancingPolicy} lets it set on them. */
    static final String STACK_TAG = "service.eks.amazonaws.com/stack"; // partition-literal: a tag key, not a host
    static final String SCHEME_ANNOTATION = "service.beta.kubernetes.io/aws-load-balancer-scheme";

    private final EksService eksService;
    private final EksClusterManager clusterManager;
    private final ElbV2Service elb;
    private final EksAutoModeElb resources;
    private final EksAutoModeKubectl kubectl;
    private final EksAutoModeIngresses ingresses;
    private final EmulatorConfig config;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "eks-auto-mode-load-balancers"));

    @Inject
    public EksAutoModeLoadBalancers(EksService eksService, EksClusterManager clusterManager, ElbV2Service elb,
                                    EmulatorConfig config) {
        this.eksService = eksService;
        this.clusterManager = clusterManager;
        this.elb = elb;
        this.resources = new EksAutoModeElb(elb);
        this.kubectl = new EksAutoModeKubectl(clusterManager);
        this.ingresses = new EksAutoModeIngresses(elb, resources, kubectl);
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
        List<NlbService> services = nlbServices(kubectl.list(cluster, "services"),
                clusterManager.runsWithoutServiceLb(cluster));
        Map<String, LoadBalancer> existing = resources.loadBalancersOf(cluster, region, STACK_TAG, "network");
        Instance node = clusterManager.getRegisteredClusterNodeInstance(cluster);
        for (NlbService service : services) {
            LoadBalancer lb = existing.remove(service.stack());
            if (lb == null) {
                lb = createLoadBalancer(cluster, region, service);
            }
            if (ensurePorts(cluster, region, service, lb, node)) {
                kubectl.writeHostname(cluster, "service", service.namespace(), service.name(), service.uid(),
                        lb.getDnsName());
            }
        }
        existing.forEach((stack, lb) -> delete(cluster, region, stack, lb));
        ingresses.reconcile(cluster, region, node);
    }

    /**
     * Deletes every load balancer made for {@code cluster}'s Services and Ingresses, with their listeners and target
     * groups.
     */
    public void deleteLoadBalancers(Cluster cluster, String region) {
        resources.loadBalancersOf(cluster, region, STACK_TAG, "network")
                .forEach((stack, lb) -> delete(cluster, region, stack, lb));
        ingresses.deleteLoadBalancers(cluster, region);
    }

    /**
     * The Services of {@code kubectl get services -A -o json} that get an Auto Mode Network Load Balancer: of type
     * LoadBalancer, of its class (and with {@code classless}, of no class), with a TCP port on a NodePort.
     */
    static List<NlbService> nlbServices(JsonNode list, boolean classless) {
        List<NlbService> services = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            JsonNode spec = item.path("spec");
            String lbClass = spec.path("loadBalancerClass").asText("");
            if (!"LoadBalancer".equals(spec.path("type").asText())
                    || !(NLB_CLASS.equals(lbClass) || classless && lbClass.isEmpty())) {
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

    private static Map<String, String> tags(Cluster cluster, NlbService service) {
        return Map.of(EksAutoModeElb.CLUSTER_TAG, cluster.getName(), STACK_TAG, service.stack());
    }

    private LoadBalancer createLoadBalancer(Cluster cluster, String region, NlbService service) {
        List<String> subnets = cluster.getResourcesVpcConfig() == null ? List.of()
                : cluster.getResourcesVpcConfig().getSubnetIds();
        LoadBalancer lb = elb.createLoadBalancer(region, EksAutoModeElb.resourceName(cluster, service.stack(), ""),
                service.scheme(),
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
        Map<String, TargetGroup> groups = resources.targetGroupsOf(cluster, region, STACK_TAG, service.stack());
        Map<Integer, Listener> listeners = new HashMap<>();
        elb.describeListeners(region, lb.getLoadBalancerArn(), null).forEach(l -> listeners.put(l.getPort(), l));
        Set<String> wanted = new HashSet<>();
        for (ServicePort port : service.ports()) {
            String name = EksAutoModeElb.resourceName(cluster, service.stack(), "-" + port.port());
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
            resources.registerNode(region, group, node);
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
        resources.delete(cluster, region, STACK_TAG, stack, lb);
        LOG.infov("Deleted Network Load Balancer {0} of Service {1} of EKS cluster {2}", lb.getLoadBalancerName(),
                stack, cluster.getName());
    }
}
