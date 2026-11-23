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
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.Action;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * EKS Auto Mode's load balancing capability for Services: while a cluster has it on, a Service of type
 * LoadBalancer whose {@code loadBalancerClass} is {@code eks.amazonaws.com/nlb} gets a Network Load Balancer in the
 * cluster's subnets, as on AWS. Each of the Service's ports gets a target group on its NodePort, with the cluster's
 * node registered by instance ID as node group target groups register it, and a listener on the Service port. The
 * load balancer's DNS name is written to the Service's {@code status.loadBalancer}, and the load balancer is deleted
 * with the Service.
 *
 * <p>Floci reads the cluster with {@code kubectl} in the node, so this reconciles on an interval rather than
 * watching. It keeps no state of its own: the load balancers it made carry the cluster and the Service in their tags,
 * the tags the AWS Load Balancer Controller uses, and are found again by them.
 *
 * <p>A Service with no {@code loadBalancerClass}, which Auto Mode also serves as its cluster default, stays with
 * k3s's own ServiceLB here.
 */
@ApplicationScoped
public class EksAutoModeLoadBalancers {

    private static final Logger LOG = Logger.getLogger(EksAutoModeLoadBalancers.class);
    static final String NLB_CLASS = "eks.amazonaws.com/nlb"; // partition-literal: a Kubernetes class name, not a host
    static final String CLUSTER_TAG = "elbv2.k8s.aws/cluster";
    static final String STACK_TAG = "service.k8s.aws/stack";
    static final String SCHEME_ANNOTATION = "service.beta.kubernetes.io/aws-load-balancer-scheme";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        for (AccountAwareStorageBackend.AccountEntry<Cluster> entry : eksService.autoModeLoadBalancingClusters()) {
            Cluster cluster = entry.value();
            try {
                RequestScopes.runAs(entry.accountId(), eksService.clusterRegion(cluster),
                        () -> reconcile(cluster, eksService.clusterRegion(cluster)));
            } catch (RuntimeException e) {
                LOG.warnv("EKS Auto Mode load balancers of cluster {0} were not reconciled: {1}",
                        cluster.getName(), e.getMessage());
            }
        }
    }

    /** One Service port: its protocol, the port clients use and the NodePort the node serves it on. */
    record ServicePort(String protocol, int port, int nodePort) { }

    /** A Service that gets a load balancer: its namespace and name, the scheme it asks for, and its ports. */
    record NlbService(String namespace, String name, String scheme, List<ServicePort> ports) {

        String stack() {
            return namespace + "/" + name;
        }
    }

    /** Creates the load balancers of new Services, writes their DNS names back, and deletes those of gone ones. */
    void reconcile(Cluster cluster, String region) {
        List<NlbService> services = nlbServices(readServices(cluster));
        Map<String, LoadBalancer> existing = loadBalancersOf(cluster, region);
        for (NlbService service : services) {
            LoadBalancer lb = existing.remove(service.stack());
            if (lb == null) {
                lb = create(cluster, region, service);
            }
            writeStatus(cluster, service, lb.getDnsName());
        }
        existing.values().forEach(lb -> delete(region, lb));
    }

    /** The Services of {@code kubectl get services -A -o json} that get an Auto Mode Network Load Balancer. */
    static List<NlbService> nlbServices(JsonNode list) {
        List<NlbService> services = new ArrayList<>();
        for (JsonNode item : list.path("items")) {
            JsonNode spec = item.path("spec");
            if (!"LoadBalancer".equals(spec.path("type").asText()) || !NLB_CLASS.equals(spec.path("loadBalancerClass").asText())) {
                continue;
            }
            List<ServicePort> ports = new ArrayList<>();
            for (JsonNode port : spec.path("ports")) {
                if (port.path("nodePort").asInt() > 0) {
                    ports.add(new ServicePort(port.path("protocol").asText("TCP"), port.path("port").asInt(),
                            port.path("nodePort").asInt()));
                }
            }
            JsonNode metadata = item.path("metadata");
            services.add(new NlbService(metadata.path("namespace").asText(), metadata.path("name").asText(),
                    metadata.path("annotations").path(SCHEME_ANNOTATION).asText("internal"), ports));
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

    /** The load balancers made for {@code cluster}'s Services, by the Service they were made for. */
    private Map<String, LoadBalancer> loadBalancersOf(Cluster cluster, String region) {
        Map<String, LoadBalancer> found = new LinkedHashMap<>();
        List<LoadBalancer> all = elb.describeLoadBalancers(region, null, null, null, null);
        if (all.isEmpty()) {
            return found;
        }
        Map<String, Map<String, String>> tags = elb.describeTags(all.stream().map(LoadBalancer::getLoadBalancerArn).toList());
        for (LoadBalancer lb : all) {
            Map<String, String> lbTags = tags.getOrDefault(lb.getLoadBalancerArn(), Map.of());
            if (cluster.getName().equals(lbTags.get(CLUSTER_TAG)) && lbTags.get(STACK_TAG) != null) {
                found.put(lbTags.get(STACK_TAG), lb);
            }
        }
        return found;
    }

    private LoadBalancer create(Cluster cluster, String region, NlbService service) {
        Map<String, String> tags = Map.of(CLUSTER_TAG, cluster.getName(), STACK_TAG, service.stack());
        List<String> subnets = cluster.getResourcesVpcConfig() == null ? List.of()
                : cluster.getResourcesVpcConfig().getSubnetIds();
        String vpcId = cluster.getResourcesVpcConfig() == null ? null : cluster.getResourcesVpcConfig().getVpcId();
        LoadBalancer lb = elb.createLoadBalancer(region, resourceName(cluster, service, ""), service.scheme(), "network",
                "ipv4", subnets, List.of(), tags);
        Instance node = clusterManager.getRegisteredClusterNodeInstance(cluster);
        for (ServicePort port : service.ports()) {
            TargetGroup group = elb.createTargetGroup(region, resourceName(cluster, service, "-" + port.port()),
                    port.protocol(), null, port.nodePort(), vpcId, "instance", null, null, null, null, null, null,
                    null, null, null, "ipv4", tags);
            if (node != null) {
                TargetDescription target = new TargetDescription();
                target.setId(node.getInstanceId());
                elb.registerTargets(region, group.getTargetGroupArn(), List.of(target));
            }
            Action forward = new Action();
            forward.setType("forward");
            forward.setTargetGroupArn(group.getTargetGroupArn());
            elb.createListener(region, lb.getLoadBalancerArn(), port.protocol(), port.port(), null, null,
                    List.of(forward), null, tags);
        }
        LOG.infov("Created Network Load Balancer {0} for Service {1} of EKS cluster {2}",
                lb.getLoadBalancerName(), service.stack(), cluster.getName());
        return lb;
    }

    private void delete(String region, LoadBalancer lb) {
        List<TargetGroup> groups = elb.describeTargetGroups(region, lb.getLoadBalancerArn(), null, null);
        elb.describeListeners(region, lb.getLoadBalancerArn(), null)
                .forEach(listener -> elb.deleteListener(region, listener.getListenerArn()));
        elb.deleteLoadBalancer(region, lb.getLoadBalancerArn());
        groups.forEach(group -> elb.deleteTargetGroup(region, group.getTargetGroupArn()));
        LOG.infov("Deleted Network Load Balancer {0}: its Service is gone", lb.getLoadBalancerName());
    }

    /** Writes the load balancer's DNS name to the Service's status, as the controller on AWS does. */
    private void writeStatus(Cluster cluster, NlbService service, String dnsName) {
        String patch = "{\"status\":{\"loadBalancer\":{\"ingress\":[{\"hostname\":\"" + dnsName + "\"}]}}}";
        ContainerExec.Result result = clusterManager.execInContainerForResult(cluster.getContainerId(),
                new String[]{"kubectl", "patch", "service", service.name(), "-n", service.namespace(),
                        "--subresource=status", "--type=merge", "-p", patch}, 15);
        if (result.exitCode() != 0) {
            LOG.warnv("Could not write the load balancer of Service {0} to its status: {1}",
                    service.stack(), result.summary());
        }
    }

    /**
     * A load balancer or target group name: {@code k8s-<namespace>-<name><suffix>-<hash>}, cut to ELBv2's 32
     * characters, the hash naming the cluster and Service so two clusters' Services of one name don't collide.
     */
    static String resourceName(Cluster cluster, NlbService service, String suffix) {
        String hash = Digests.sha256Hex(cluster.getArn() + "/" + service.stack() + suffix).substring(0, 10);
        String base = ("k8s-" + cut(service.namespace(), 7) + "-" + cut(service.name(), 7) + suffix)
                .replaceAll("[^A-Za-z0-9-]", "-");
        return (cut(base, 32 - 11) + "-" + hash).replaceAll("-+", "-");
    }

    private static String cut(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
