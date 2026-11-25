package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.Action;
import io.floci.aws.services.elbv2.model.AvailabilityZone;
import io.floci.aws.services.elbv2.model.Listener;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.elbv2.model.Rule;
import io.floci.aws.services.elbv2.model.RuleCondition;
import io.floci.aws.services.elbv2.model.TargetGroup;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * EKS Auto Mode's load balancing capability for Ingresses: while a cluster has it on, an Ingress whose IngressClass
 * names the {@code eks.amazonaws.com/alb} controller gets an Application Load Balancer, as on AWS (measured):
 *
 * <ul>
 *   <li>named {@code k8s-<namespace>-<name>-<hash>}, with the scheme and subnets of the class's
 *   {@code IngressClassParams}, else the {@code alb.ingress.kubernetes.io/scheme} annotation and the cluster's
 *   subnets, and {@code internal} by default;</li>
 *   <li>an HTTP listener on port 80 whose default answers a fixed 404 ({@code text/plain}), or forwards to the
 *   Ingress's default backend;</li>
 *   <li>a rule per host and path, in order, forwarding to a target group of the backend Service: {@code Prefix /} is
 *   the path pattern {@code /*}, as AWS makes it, another {@code Prefix} path {@code /p} is {@code /p} and
 *   {@code /p/*}, and an {@code Exact} one is itself;</li>
 *   <li>target groups on the Service's NodePort with the cluster's node registered, an HTTP health check on the
 *   traffic port at the {@code alb.ingress.kubernetes.io/healthcheck-path} annotation's path, {@code /} by default,
 *   every 15 seconds with a 5 second timeout;</li>
 *   <li>the tags Auto Mode sets, {@code eks:eks-cluster-name}, {@code ingress.eks.amazonaws.com/stack} and
 *   {@code ingress.eks.amazonaws.com/resource};</li>
 *   <li>its DNS name in the Ingress's {@code status.loadBalancer} once the node is registered.</li>
 * </ul>
 *
 * <p>Floci's load balancers reach the node, not the pods inside it, so a backend is served through its Service's
 * NodePort: an Ingress whose target type is {@code ip} gets no load balancer, and a route whose Service has no
 * NodePort is left out. Only port 80 is served: HTTPS listeners and certificates aren't.
 */
final class EksAutoModeIngresses {

    private static final Logger LOG = Logger.getLogger(EksAutoModeIngresses.class);
    static final String ALB_CONTROLLER = "eks.amazonaws.com/alb"; // partition-literal: a Kubernetes controller name
    static final String STACK_TAG = "ingress.eks.amazonaws.com/stack"; // partition-literal: a tag key, not a host
    static final String RESOURCE_TAG = "ingress.eks.amazonaws.com/resource"; // partition-literal: a tag key
    static final String SCHEME_ANNOTATION = "alb.ingress.kubernetes.io/scheme";
    static final String TARGET_TYPE_ANNOTATION = "alb.ingress.kubernetes.io/target-type";
    static final String HEALTH_CHECK_PATH_ANNOTATION = "alb.ingress.kubernetes.io/healthcheck-path";
    private static final String PARAMS_GROUP = "eks.amazonaws.com"; // partition-literal: a Kubernetes API group
    private static final String DEFAULT_CLASS_ANNOTATION = "ingressclass.kubernetes.io/is-default-class";
    /** What Ingress names hash besides the Ingress, so a Service of the same name gets another name. */
    static final String KIND = "ingress/";

    private final ElbV2Service elb;
    private final EksAutoModeElb resources;
    private final EksAutoModeKubectl kubectl;

    EksAutoModeIngresses(ElbV2Service elb, EksAutoModeElb resources, EksAutoModeKubectl kubectl) {
        this.elb = elb;
        this.resources = resources;
        this.kubectl = kubectl;
    }

    /** A Service port an Ingress forwards to, and the NodePort the node serves it on. */
    record Backend(String service, int port, int nodePort) {

        String resource(AlbIngress ingress) {
            return ingress.stack() + "-" + service + ":" + port;
        }
    }

    /** A rule: the host it matches, if any, its path patterns, and the backend it forwards to. */
    record Route(String host, List<String> paths, Backend backend) { }

    /** An Ingress that gets an Application Load Balancer, as its class and annotations configure it. */
    record AlbIngress(String namespace, String name, String uid, String scheme, List<String> subnets,
                      String healthCheckPath, List<Route> routes, Backend defaultBackend) {

        String stack() {
            return namespace + "/" + name;
        }

        /** Each backend once, the default one included. */
        List<Backend> backends() {
            Set<Backend> all = new LinkedHashSet<>();
            routes.forEach(route -> all.add(route.backend()));
            if (defaultBackend != null) {
                all.add(defaultBackend);
            }
            return List.copyOf(all);
        }
    }

    /** Makes and updates the load balancers of the cluster's ALB Ingresses, and deletes those of gone ones. */
    void reconcile(Cluster cluster, String region, Instance node) {
        JsonNode classes = kubectl.list(cluster, "ingressclasses");
        boolean anyAlbClass = false;
        for (JsonNode item : classes.path("items")) {
            anyAlbClass |= ALB_CONTROLLER.equals(item.path("spec").path("controller").asText());
        }
        // Without an ALB class no Ingress gets a load balancer, so the other lists aren't read.
        List<AlbIngress> ingresses = !anyAlbClass ? List.of() : albIngresses(kubectl.list(cluster, "ingresses"),
                classes, ingressClassParams(cluster), kubectl.list(cluster, "services"));
        Map<String, LoadBalancer> existing = resources.loadBalancersOf(cluster, region, STACK_TAG, KIND, "application");
        for (AlbIngress ingress : ingresses) {
            LoadBalancer lb = existing.remove(ingress.stack());
            // One Ingress ELBv2 refuses, for its subnets say, leaves the others served.
            try {
                if (lb != null && !ingress.scheme().equals(lb.getScheme())) {
                    // A load balancer's scheme can't change, so another scheme takes another load balancer.
                    delete(cluster, region, ingress.stack(), lb);
                    lb = null;
                }
                if (lb == null) {
                    lb = createLoadBalancer(cluster, region, ingress);
                } else {
                    keepSubnets(cluster, region, ingress, lb);
                }
                if (ensureRouting(cluster, region, ingress, lb, node)) {
                    kubectl.writeHostname(cluster, "ingress", ingress.namespace(), ingress.name(), ingress.uid(),
                            lb.getDnsName());
                }
            } catch (RuntimeException e) {
                LOG.warnv("Ingress {0} of EKS cluster {1} gets no load balancer: {2}", ingress.stack(),
                        cluster.getName(), e.getMessage());
            }
        }
        existing.forEach((stack, lb) -> delete(cluster, region, stack, lb));
    }

    /**
     * The cluster's IngressClassParams; none on a node started before Floci installed the kind. Any other failed read
     * stops the pass, so no load balancer is made without the settings a class gives it.
     */
    private JsonNode ingressClassParams(Cluster cluster) {
        try {
            return kubectl.list(cluster, "ingressclassparams");
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("doesn't have a resource type")) {
                return MissingNode.getInstance();
            }
            throw e;
        }
    }

    /** Deletes every load balancer made for the cluster's Ingresses, with their listeners and target groups. */
    void deleteLoadBalancers(Cluster cluster, String region) {
        resources.loadBalancersOf(cluster, region, STACK_TAG, KIND, "application")
                .forEach((stack, lb) -> delete(cluster, region, stack, lb));
    }

    /**
     * The Ingresses of these {@code kubectl get ... -o json} lists that get an Application Load Balancer: those of
     * an IngressClass naming the ALB controller, or of no class while such a class is the default, with a target type
     * other than {@code ip}.
     */
    static List<AlbIngress> albIngresses(JsonNode ingresses, JsonNode classes, JsonNode params, JsonNode services) {
        Map<String, JsonNode> albClasses = new HashMap<>();
        String defaultClass = null;
        for (JsonNode item : classes.path("items")) {
            if (ALB_CONTROLLER.equals(item.path("spec").path("controller").asText())) {
                String name = item.path("metadata").path("name").asText();
                albClasses.put(name, item);
                if ("true".equals(item.path("metadata").path("annotations").path(DEFAULT_CLASS_ANNOTATION).asText())) {
                    defaultClass = name;
                }
            }
        }
        Map<String, JsonNode> paramsByName = new HashMap<>();
        params.path("items").forEach(item -> paramsByName.put(item.path("metadata").path("name").asText(), item));
        Map<String, JsonNode> servicesByStack = new HashMap<>();
        services.path("items").forEach(item -> servicesByStack.put(item.path("metadata").path("namespace").asText()
                + "/" + item.path("metadata").path("name").asText(), item));

        List<AlbIngress> found = new ArrayList<>();
        for (JsonNode item : ingresses.path("items")) {
            JsonNode metadata = item.path("metadata");
            JsonNode spec = item.path("spec");
            String className = spec.path("ingressClassName").asText(defaultClass == null ? "" : defaultClass);
            JsonNode ingressClass = albClasses.get(className);
            if (ingressClass == null) {
                continue;
            }
            JsonNode classParams = paramsOf(ingressClass, paramsByName);
            JsonNode annotations = metadata.path("annotations");
            String targetType = classParams.path("targetType").asText(annotations.path(TARGET_TYPE_ANNOTATION)
                    .asText("instance"));
            if ("ip".equals(targetType)) {
                LOG.warnv("Ingress {0}/{1} gets no load balancer: Floci reaches the node, not its pods, so it serves "
                        + "target type instance only", metadata.path("namespace").asText(), metadata.path("name").asText());
                continue;
            }
            String namespace = metadata.path("namespace").asText();
            List<String> subnets = new ArrayList<>();
            classParams.path("subnets").path("ids").forEach(id -> subnets.add(id.asText()));
            List<Route> routes = new ArrayList<>();
            // As the Load Balancer Controller builds them: the rules in the order the Ingress lists them, and each
            // rule's paths in their precedence.
            for (JsonNode rule : spec.path("rules")) {
                List<JsonNode> paths = new ArrayList<>();
                rule.path("http").path("paths").forEach(paths::add);
                for (int i : precedence(paths)) {
                    Backend backend = backend(paths.get(i).path("backend"), namespace, servicesByStack);
                    if (backend != null) {
                        routes.add(new Route(rule.path("host").asText(null), pathPatterns(paths.get(i)), backend));
                    }
                }
            }
            found.add(new AlbIngress(namespace, metadata.path("name").asText(), metadata.path("uid").asText(""),
                    classParams.path("scheme").asText(annotations.path(SCHEME_ANNOTATION).asText("internal")),
                    List.copyOf(subnets), annotations.path(HEALTH_CHECK_PATH_ANNOTATION).asText("/"),
                    List.copyOf(routes), backend(spec.path("defaultBackend"), namespace, servicesByStack)));
        }
        return found;
    }

    /**
     * One Ingress rule's paths' indexes in the order they are matched, as the AWS Load Balancer Controller orders
     * them: {@code Exact} paths first, then {@code Prefix} paths longest first, then {@code ImplementationSpecific}
     * ones, each in the order the rule lists them otherwise.
     */
    static List<Integer> precedence(List<JsonNode> paths) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt((Integer i) -> pathRank(paths.get(i)))
                .thenComparingInt(i -> "Prefix".equals(pathType(paths.get(i)))
                        ? -paths.get(i).path("path").asText("/").length() : 0));
        return order;
    }

    private static int pathRank(JsonNode path) {
        return switch (pathType(path)) {
            case "Exact" -> 0;
            case "Prefix" -> 1;
            default -> 2;
        };
    }

    private static String pathType(JsonNode path) {
        return path.path("pathType").asText("ImplementationSpecific");
    }

    private static JsonNode paramsOf(JsonNode ingressClass, Map<String, JsonNode> paramsByName) {
        JsonNode reference = ingressClass.path("spec").path("parameters");
        if (!"IngressClassParams".equals(reference.path("kind").asText())
                || !PARAMS_GROUP.equals(reference.path("apiGroup").asText())) {
            return MissingNode.getInstance();
        }
        JsonNode found = paramsByName.get(reference.path("name").asText());
        return found == null ? MissingNode.getInstance() : found.path("spec");
    }

    /** The Service port a backend names, by number or name, and its NodePort; null without one. */
    private static Backend backend(JsonNode backend, String namespace, Map<String, JsonNode> servicesByStack) {
        JsonNode service = backend.path("service");
        String name = service.path("name").asText(null);
        JsonNode found = name == null ? null : servicesByStack.get(namespace + "/" + name);
        if (found == null) {
            return null;
        }
        JsonNode wanted = service.path("port");
        for (JsonNode port : found.path("spec").path("ports")) {
            boolean matches = (wanted.has("number") ? wanted.path("number").asInt() == port.path("port").asInt()
                    : wanted.path("name").asText().equals(port.path("name").asText()))
                    // A port number a Service also serves over UDP takes its TCP NodePort.
                    && "TCP".equals(port.path("protocol").asText("TCP"));
            if (matches && port.path("nodePort").asInt() > 0) {
                return new Backend(name, port.path("port").asInt(), port.path("nodePort").asInt());
            }
        }
        return null;
    }

    /** The ALB path patterns of an Ingress path: {@code Prefix /} is {@code /*}, as AWS makes it (measured). */
    static List<String> pathPatterns(JsonNode path) {
        String value = path.path("path").asText("/");
        String type = path.path("pathType").asText("ImplementationSpecific");
        if (!"Prefix".equals(type)) {
            return List.of(value);
        }
        if ("/".equals(value)) {
            return List.of("/*");
        }
        String trimmed = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        return List.of(trimmed, trimmed + "/*");
    }

    private LoadBalancer createLoadBalancer(Cluster cluster, String region, AlbIngress ingress) {
        List<String> subnets = subnets(cluster, ingress);
        LoadBalancer lb = elb.createLoadBalancer(region, EksAutoModeElb.resourceName(cluster, KIND, ingress.stack(), ""),
                ingress.scheme(), "application", "ipv4", subnets, List.of(),
                tags(cluster, ingress, "LoadBalancer"));
        LOG.infov("Created Application Load Balancer {0} for Ingress {1} of EKS cluster {2}",
                lb.getLoadBalancerName(), ingress.stack(), cluster.getName());
        return lb;
    }

    /** The load balancer moves to the subnets the Ingress's class names now, when they differ. */
    private void keepSubnets(Cluster cluster, String region, AlbIngress ingress, LoadBalancer lb) {
        List<String> wanted = subnets(cluster, ingress);
        List<String> have = lb.getAvailabilityZones() == null ? List.of()
                : lb.getAvailabilityZones().stream().map(AvailabilityZone::getSubnetId).toList();
        if (!wanted.isEmpty() && !Set.copyOf(wanted).equals(Set.copyOf(have))) {
            elb.setSubnets(region, lb.getLoadBalancerArn(), wanted);
        }
    }

    private static List<String> subnets(Cluster cluster, AlbIngress ingress) {
        return !ingress.subnets().isEmpty() ? ingress.subnets()
                : cluster.getResourcesVpcConfig() == null ? List.of() : cluster.getResourcesVpcConfig().getSubnetIds();
    }

    /**
     * Makes the Ingress's target groups, the node's registration and the listener where missing, sets its rules to
     * the Ingress's routes when they differ, and removes the target groups no route forwards to any more. Answers
     * whether the load balancer can serve: the node is registered.
     */
    private boolean ensureRouting(Cluster cluster, String region, AlbIngress ingress, LoadBalancer lb, Instance node) {
        Map<String, TargetGroup> groups = resources.targetGroupsOf(cluster, region, STACK_TAG, KIND, ingress.stack());
        Map<Backend, String> groupArns = new HashMap<>();
        Set<String> wanted = new LinkedHashSet<>();
        for (Backend backend : ingress.backends()) {
            String name = EksAutoModeElb.resourceName(cluster, KIND, ingress.stack(), "-" + backend.nodePort());
            wanted.add(name);
            TargetGroup group = groups.get(name);
            if (group == null) {
                String vpcId = cluster.getResourcesVpcConfig() == null ? null : cluster.getResourcesVpcConfig().getVpcId();
                group = elb.createTargetGroup(region, name, "HTTP", null, backend.nodePort(), vpcId, "instance", "HTTP",
                        "traffic-port", true, ingress.healthCheckPath(), 15, 5, null, null, null, "ipv4",
                        tags(cluster, ingress, backend.resource(ingress)));
            } else if (!ingress.healthCheckPath().equals(group.getHealthCheckPath())) {
                elb.modifyTargetGroup(region, group.getTargetGroupArn(), null, null, null, ingress.healthCheckPath(),
                        null, null, null, null, null);
            }
            resources.registerNode(region, group, node);
            groupArns.put(backend, group.getTargetGroupArn());
        }
        Action defaultAction = ingress.defaultBackend() == null ? notFound() : forward(groupArns.get(ingress.defaultBackend()));
        List<Listener> listeners = elb.describeListeners(region, lb.getLoadBalancerArn(), null);
        Listener listener = listeners.stream().filter(l -> Integer.valueOf(80).equals(l.getPort())).findFirst()
                .orElseGet(() -> elb.createListener(region, lb.getLoadBalancerArn(), "HTTP", 80, null, null,
                        List.of(defaultAction), null, tags(cluster, ingress, "LoadBalancer")));
        if (!sameAction(listener.getDefaultActions(), defaultAction)) {
            // A default backend added, changed or dropped since the listener was made.
            elb.modifyListener(region, listener.getListenerArn(), null, null, null, null, List.of(defaultAction), null);
        }
        setRules(region, listener, ingress, groupArns);
        groups.forEach((name, group) -> {
            if (!wanted.contains(name)) {
                elb.deleteTargetGroup(region, group.getTargetGroupArn());
            }
        });
        return node != null;
    }

    /** The listener's rules become the Ingress's routes, in order from priority 1, when they differ. */
    private void setRules(String region, Listener listener, AlbIngress ingress, Map<Backend, String> groupArns) {
        List<Rule> current = elb.describeRules(region, listener.getListenerArn(), null).stream()
                .filter(rule -> !"default".equals(rule.getPriority())).toList();
        List<String> desired = new ArrayList<>();
        for (Route route : ingress.routes()) {
            desired.add(route.host() + " " + route.paths() + " " + groupArns.get(route.backend()));
        }
        List<String> have = current.stream().map(EksAutoModeIngresses::describe).toList();
        if (have.equals(desired)) {
            return;
        }
        current.forEach(rule -> elb.deleteRule(region, rule.getRuleArn()));
        int priority = 1;
        for (Route route : ingress.routes()) {
            List<RuleCondition> conditions = new ArrayList<>();
            if (route.host() != null) {
                conditions.add(condition("host-header", List.of(route.host())));
            }
            conditions.add(condition("path-pattern", route.paths()));
            elb.createRule(region, listener.getListenerArn(), conditions, priority++,
                    List.of(forward(groupArns.get(route.backend()))), Map.of());
        }
    }

    /** A rule as {@link #setRules} compares it: its host, its path patterns and the target group it forwards to. */
    private static String describe(Rule rule) {
        String host = null;
        List<String> paths = List.of();
        for (RuleCondition condition : rule.getConditions()) {
            if ("host-header".equals(condition.getField())) {
                host = condition.getValues().isEmpty() ? null : condition.getValues().getFirst();
            } else if ("path-pattern".equals(condition.getField())) {
                paths = condition.getValues();
            }
        }
        String target = rule.getActions().isEmpty() ? null : rule.getActions().getFirst().getTargetGroupArn();
        return host + " " + paths + " " + target;
    }

    private static RuleCondition condition(String field, List<String> values) {
        RuleCondition condition = new RuleCondition();
        condition.setField(field);
        condition.setValues(new ArrayList<>(values));
        if ("host-header".equals(field)) {
            condition.setHostHeaderValues(new ArrayList<>(values));
        } else {
            condition.setPathPatternValues(new ArrayList<>(values));
        }
        return condition;
    }

    private static boolean sameAction(List<Action> actions, Action wanted) {
        if (actions == null || actions.size() != 1) {
            return false;
        }
        Action have = actions.getFirst();
        return Objects.equals(have.getType(), wanted.getType())
                && Objects.equals(have.getTargetGroupArn(), wanted.getTargetGroupArn())
                && Objects.equals(have.getFixedResponseStatusCode(), wanted.getFixedResponseStatusCode());
    }

    private static Action forward(String targetGroupArn) {
        Action action = new Action();
        action.setType("forward");
        action.setTargetGroupArn(Objects.requireNonNull(targetGroupArn));
        return action;
    }

    /** The listener's default without a default backend: a fixed 404, as AWS makes it (measured). */
    private static Action notFound() {
        Action action = new Action();
        action.setType("fixed-response");
        action.setFixedResponseStatusCode("404");
        action.setFixedResponseContentType("text/plain");
        return action;
    }

    private static Map<String, String> tags(Cluster cluster, AlbIngress ingress, String resource) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(EksAutoModeElb.CLUSTER_TAG, cluster.getName());
        tags.put(STACK_TAG, ingress.stack());
        tags.put(RESOURCE_TAG, resource);
        return tags;
    }

    private void delete(Cluster cluster, String region, String stack, LoadBalancer lb) {
        resources.delete(cluster, region, STACK_TAG, KIND, stack, lb);
        LOG.infov("Deleted Application Load Balancer {0} of Ingress {1} of EKS cluster {2}",
                lb.getLoadBalancerName(), stack, cluster.getName());
    }
}
