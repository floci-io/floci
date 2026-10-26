package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.AvailabilityZone;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CloudFormation provisioning for the Elastic Load Balancing v2 types,
 * {@code AWS::ElasticLoadBalancingV2::LoadBalancer}, {@code TargetGroup}, {@code Listener} and
 * {@code ListenerRule}, moved out of the former CloudFormation monolith's switch.
 */
@ApplicationScoped
public class ElbV2CfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ElbV2CfnProvisioner.class);

    private static final String LOAD_BALANCER = "AWS::ElasticLoadBalancingV2::LoadBalancer";
    private static final String TARGET_GROUP = "AWS::ElasticLoadBalancingV2::TargetGroup";
    private static final String LISTENER = "AWS::ElasticLoadBalancingV2::Listener";
    private static final String LISTENER_RULE = "AWS::ElasticLoadBalancingV2::ListenerRule";
    /** Prefixes one uses attribute per subnet a load balancer is on, numbered from 1. */
    private static final String USED_SUBNET_ATTR_PREFIX = CfnRollback.USES_ATTR_PREFIX + "Subnet.";

    private final ElbV2Service elbV2Service;

    @Inject
    public ElbV2CfnProvisioner(ElbV2Service elbV2Service) {
        this.elbV2Service = elbV2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(LOAD_BALANCER, TARGET_GROUP, LISTENER, LISTENER_RULE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        r.getAttributes().remove(CfnRollback.LOAD_BALANCER_SUBNETS_UPDATE_SNAPSHOT_ATTR);
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case LOAD_BALANCER -> provisionLoadBalancer(r, props, ctx);
            case TARGET_GROUP -> provisionTargetGroup(r, props, ctx);
            case LISTENER -> provisionListener(r, props, ctx);
            case LISTENER_RULE -> provisionListenerRule(r, props, ctx);
            default -> throw new IllegalStateException("ElbV2CfnProvisioner cannot handle " + r.getResourceType());
        }
        // A provision that left the resource with a new physical id replaced the entity: the
        // displaced one is deleted once the update commits, or restored if the update rolls back.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * Each delete tolerates only the not-found code ElbV2Service defines for that type, so an
     * entity already gone is delete-complete, while the service's refusals (ResourceInUse on a
     * target group a listener still forwards to, OperationNotPermitted on a listener's default
     * rule) keep failing the stack delete.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case LOAD_BALANCER -> CfnDeletes.safeDelete("load balancer", physicalId,
                    () -> elbV2Service.deleteLoadBalancer(region, physicalId), "LoadBalancerNotFound");
            case TARGET_GROUP -> CfnDeletes.safeDelete("target group", physicalId,
                    () -> elbV2Service.deleteTargetGroup(region, physicalId), "TargetGroupNotFound");
            case LISTENER -> CfnDeletes.safeDelete("listener", physicalId,
                    () -> elbV2Service.deleteListener(region, physicalId), "ListenerNotFound");
            case LISTENER_RULE -> CfnDeletes.safeDelete("listener rule", physicalId,
                    () -> elbV2Service.deleteRule(region, physicalId), "RuleNotFound");
            default -> { }
        }
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if ("UPDATE_COMPLETE".equals(resource.getStatus())) {
            resource.getAttributes().remove(CfnRollback.LOAD_BALANCER_SUBNETS_UPDATE_SNAPSHOT_ATTR);
        }
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.LOAD_BALANCER_SUBNETS_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record. Without one, a load balancer is moved
     * back to the subnets an update took it off, and otherwise it and a target group have nothing to
     * put back: an update that kept the name changed nothing else, the way the switch did. A listener
     * or rule without a record was modified in place, and putting that back needs a snapshot this
     * provisioner does not keep, so the engine reports it as not rolled back, as it did for the switch.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        if (LOAD_BALANCER.equals(resource.getResourceType())) {
            return restorePriorSubnets(resource);
        }
        return TARGET_GROUP.equals(resource.getResourceType());
    }

    /**
     * A failed move back propagates, so the engine reports its reason rather than an unimplemented
     * rollback, and the snapshot stays until a move back succeeds.
     */
    private boolean restorePriorSubnets(StackResource resource) {
        String prior = resource.getAttributes().get(CfnRollback.LOAD_BALANCER_SUBNETS_UPDATE_SNAPSHOT_ATTR);
        if (prior == null) {
            return true;
        }
        String arn = resource.getPhysicalId();
        List<String> priorSubnets = prior.isEmpty() ? List.of() : List.of(prior.split(","));
        elbV2Service.setSubnets(AwsArnUtils.parse(arn).region(), arn, priorSubnets);
        recordUsedSubnets(resource, priorSubnets);
        resource.getAttributes().remove(CfnRollback.LOAD_BALANCER_SUBNETS_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    /**
     * The physical id is the ARN, so the name an unnamed load balancer got at create time is read
     * back from the {@code LoadBalancerName} attribute before a new one is generated; the switch
     * generated afresh on every pass and left the previous balancer behind. A name that already
     * exists is reused, which is what makes an unchanged update a no-op. A changed name (create-only)
     * creates the replacement and hands the displaced balancer to the replacement cleanup.
     */
    private void provisionLoadBalancer(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = nameOrPrior(ctx.resolveOptional(props, "Name"), r.getAttributes().get("LoadBalancerName"),
                ctx, r.getLogicalId());
        String scheme = ctx.resolveOptional(props, "Scheme");
        String type = ctx.resolveOptional(props, "Type");
        String ipAddressType = ctx.resolveOptional(props, "IpAddressType");
        List<String> subnets = templateSubnets(props, ctx);
        List<String> securityGroups = ctx.resolveStringList(props, "SecurityGroups");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        LoadBalancer lb;
        List<String> onSubnets;
        try {
            lb = elbV2Service.createLoadBalancer(ctx.region(), name, scheme, type, ipAddressType,
                    subnets, securityGroups, tags);
            onSubnets = subnetIds(lb);
        } catch (AwsException e) {
            if (!"DuplicateLoadBalancerName".equals(e.getErrorCode())) {
                throw e;
            }
            lb = elbV2Service.describeLoadBalancers(ctx.region(), null, List.of(name), null, null).get(0);
            onSubnets = ctx.reusesPriorEntity(lb.getLoadBalancerArn())
                    ? moveToTemplateSubnets(r, lb, subnets, ctx)
                    : subnetIds(lb);
        }

        r.setPhysicalId(lb.getLoadBalancerArn());
        r.getAttributes().put("LoadBalancerArn", lb.getLoadBalancerArn());
        r.getAttributes().put("DNSName", lb.getDnsName());
        r.getAttributes().put("CanonicalHostedZoneID", lb.getCanonicalHostedZoneId());
        r.getAttributes().put("LoadBalancerName", lb.getLoadBalancerName());
        r.getAttributes().put("LoadBalancerFullName", loadBalancerFullName(lb.getLoadBalancerArn()));
        recordUsedSubnets(r, onSubnets);
    }

    private static List<String> subnetIds(LoadBalancer lb) {
        return lb.getAvailabilityZones().stream().map(AvailabilityZone::getSubnetId).toList();
    }

    /**
     * Records the subnets the balancer is on as uses attributes. EC2 refuses to delete a subnet a
     * load balancer still uses, and once the template no longer names the balancer, as when an
     * update removes it together with a subnet it is on, these are what orders it before them.
     */
    private static void recordUsedSubnets(StackResource r, List<String> subnetIds) {
        r.getAttributes().keySet().removeIf(key -> key.startsWith(USED_SUBNET_ATTR_PREFIX));
        for (int i = 0; i < subnetIds.size(); i++) {
            r.getAttributes().put(USED_SUBNET_ATTR_PREFIX + (i + 1), subnetIds.get(i));
        }
    }

    /**
     * The template's subnets: {@code Subnets}, or else the {@code SubnetId} of each
     * {@code SubnetMappings} entry, the form a Network Load Balancer uses to give a zone an Elastic IP.
     * Both non-empty fails the resource with the error CreateLoadBalancer gives, through
     * {@link ElbV2Service#requestedSubnets}, as the resource allows one or the other; an empty side, such
     * as one an Fn::If drops, does not count. {@code SubnetId} is required in a mapping, so one without it
     * fails the resource before anything is created or moved, with the error the resource schema gives.
     * A blank one counts as a mapping and, with no {@code Subnets}, is passed on as given for ELBv2 to
     * refuse; dropping it would create or move the balancer without it.
     */
    private static List<String> templateSubnets(JsonNode props, ProvisionContext ctx) {
        List<String> subnets = ctx.resolveStringList(props, "Subnets");
        if (props == null || !props.has("SubnetMappings")) {
            return subnets;
        }
        List<String> mapped = new ArrayList<>();
        // A mapping, or its SubnetId, that an Fn::If drops with AWS::NoValue is left out, as on AWS.
        JsonNode mappings = ctx.engine().resolveNodeOmittingNoValue(props.get("SubnetMappings"));
        if (mappings != null && mappings.isArray()) {
            for (int i = 0; i < mappings.size(); i++) {
                String subnetId = ctx.resolveOptional(mappings.get(i), "SubnetId");
                if (subnetId == null) {
                    throw new AwsException("InvalidRequest",
                            "Model validation failed (#/SubnetMappings/" + i + ": required key [SubnetId] not found)", 400);
                }
                mapped.add(subnetId);
            }
        }
        return ElbV2Service.requestedSubnets(subnets, mapped);
    }

    /**
     * {@code Subnets} and {@code SubnetMappings} are not create-only, so an update that keeps the
     * balancer moves it onto the template's subnets in place, as SetSubnets does. That also releases
     * the subnets it leaves, which EC2 refuses to delete while a load balancer still uses them, so the
     * update cleanup can delete a subnet the template dropped or replaced. The prior list is kept for
     * a rollback. Returns the subnets the balancer is on afterwards.
     */
    private List<String> moveToTemplateSubnets(StackResource r, LoadBalancer lb, List<String> subnets,
                                               ProvisionContext ctx) {
        List<String> current = subnetIds(lb);
        if (subnets.isEmpty() || Set.copyOf(subnets).equals(Set.copyOf(current))) {
            return current;
        }
        elbV2Service.setSubnets(ctx.region(), lb.getLoadBalancerArn(), subnets);
        r.getAttributes().put(CfnRollback.LOAD_BALANCER_SUBNETS_UPDATE_SNAPSHOT_ATTR, String.join(",", current));
        return subnets;
    }

    private void provisionTargetGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = nameOrPrior(ctx.resolveOptional(props, "Name"), r.getAttributes().get("TargetGroupName"),
                ctx, r.getLogicalId());
        String protocol = ctx.resolveOptional(props, "Protocol");
        String protocolVersion = ctx.resolveOptional(props, "ProtocolVersion");
        Integer port = ctx.resolveOptionalInt(props, "Port");
        String vpcId = ctx.resolveOptional(props, "VpcId");
        String targetType = ctx.resolveOptional(props, "TargetType");
        String hcProtocol = ctx.resolveOptional(props, "HealthCheckProtocol");
        String hcPort = ctx.resolveOptional(props, "HealthCheckPort");
        Boolean hcEnabled = parseBooleanOrNull(ctx.resolveOptional(props, "HealthCheckEnabled"));
        String hcPath = ctx.resolveOptional(props, "HealthCheckPath");
        Integer hcInterval = ctx.resolveOptionalInt(props, "HealthCheckIntervalSeconds");
        Integer hcTimeout = ctx.resolveOptionalInt(props, "HealthCheckTimeoutSeconds");
        Integer healthyThreshold = ctx.resolveOptionalInt(props, "HealthyThresholdCount");
        Integer unhealthyThreshold = ctx.resolveOptionalInt(props, "UnhealthyThresholdCount");
        String matcher = parseMatcher(props, ctx);
        String ipAddressType = ctx.resolveOptional(props, "IpAddressType");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        TargetGroup tg;
        try {
            tg = elbV2Service.createTargetGroup(ctx.region(), name, protocol, protocolVersion, port, vpcId, targetType,
                    hcProtocol, hcPort, hcEnabled, hcPath, hcInterval, hcTimeout,
                    healthyThreshold, unhealthyThreshold, matcher, ipAddressType, tags);
        } catch (AwsException e) {
            if (!"DuplicateTargetGroupName".equals(e.getErrorCode())) {
                throw e;
            }
            tg = elbV2Service.describeTargetGroups(ctx.region(), null, null, List.of(name)).get(0);
        }

        r.setPhysicalId(tg.getTargetGroupArn());
        r.getAttributes().put("TargetGroupArn", tg.getTargetGroupArn());
        r.getAttributes().put("TargetGroupName", tg.getTargetGroupName());
        r.getAttributes().put("TargetGroupFullName", targetGroupFullName(tg.getTargetGroupArn()));
        // A list-valued attribute, joined the way Route53's NameServers is. Empty until a listener
        // forwards to the group, as on AWS.
        r.getAttributes().put("LoadBalancerArns", String.join(",", tg.getLoadBalancerArns()));
    }

    /**
     * LoadBalancerArn is create-only: an update that keeps it modifies the listener the stack
     * already owns, one that moves the listener to another balancer creates a replacement and the
     * displaced listener is deleted once the stack update commits.
     */
    private void provisionListener(StackResource r, JsonNode props, ProvisionContext ctx) {
        String lbArn = ctx.resolveOptional(props, "LoadBalancerArn");
        Listener prior = ctx.isUpdate() ? priorListener(ctx) : null;
        String effectiveLbArn = lbArn != null ? lbArn : prior != null ? prior.getLoadBalancerArn() : null;
        LoadBalancer lb = effectiveLbArn == null ? null
                : elbV2Service.describeLoadBalancers(ctx.region(), List.of(effectiveLbArn),
                null, null, null).getFirst();
        boolean gateway = lb != null && "gateway".equals(lb.getType());
        String protocol = gateway ? ctx.resolveOptional(props, "Protocol")
                : ctx.resolveOrDefault(props, "Protocol", "HTTP");
        Integer port = gateway ? ctx.resolveOptionalInt(props, "Port")
                : Integer.valueOf(parseInt(ctx.resolveOptional(props, "Port"), "Port", 80));
        String sslPolicy = ctx.resolveOptional(props, "SslPolicy");
        List<String> certificates = parseCertificates(props, ctx);
        List<Action> defaultActions = parseActions(props != null ? props.get("DefaultActions") : null, ctx);

        Listener listener;
        if (prior != null && (lbArn == null || lbArn.equals(prior.getLoadBalancerArn()))) {
            listener = elbV2Service.modifyListener(ctx.region(), ctx.priorPhysicalId(), protocol, port, sslPolicy,
                    certificates, defaultActions, null);
        } else {
            listener = elbV2Service.createListener(ctx.region(), lbArn, protocol, port, sslPolicy, certificates,
                    defaultActions, null, Map.of());
        }

        r.setPhysicalId(listener.getListenerArn());
        r.getAttributes().put("ListenerArn", listener.getListenerArn());
    }

    /**
     * ListenerArn is create-only: an update that keeps it modifies the rule the stack already owns,
     * one that moves the rule to another listener creates a replacement and the displaced rule is
     * deleted once the stack update commits.
     */
    private void provisionListenerRule(StackResource r, JsonNode props, ProvisionContext ctx) {
        String listenerArn = ctx.resolveOptional(props, "ListenerArn");
        int priority = parseInt(ctx.resolveOptional(props, "Priority"), "Priority", 1);
        List<RuleCondition> conditions = parseRuleConditions(props != null ? props.get("Conditions") : null, ctx);
        List<Action> actions = parseActions(props != null ? props.get("Actions") : null, ctx);

        Rule rule;
        Rule prior = ctx.isUpdate() ? priorRule(ctx) : null;
        if (prior != null && (listenerArn == null || listenerArn.equals(prior.getListenerArn()))) {
            rule = elbV2Service.modifyRule(ctx.region(), ctx.priorPhysicalId(), conditions, actions);
        } else {
            rule = elbV2Service.createRule(ctx.region(), listenerArn, conditions, priority, actions, Map.of());
        }

        r.setPhysicalId(rule.getRuleArn());
        r.getAttributes().put("RuleArn", rule.getRuleArn());
        r.getAttributes().put("IsDefault", String.valueOf(rule.isDefault()));
    }

    /** The listener the stack points at, or null when it was removed out of band (then create). */
    private Listener priorListener(ProvisionContext ctx) {
        try {
            return elbV2Service.describeListeners(ctx.region(), null, List.of(ctx.priorPhysicalId()))
                    .stream().findFirst().orElse(null);
        } catch (AwsException e) {
            if (!"ListenerNotFound".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Listener {0} no longer exists, creating it again", ctx.priorPhysicalId());
            return null;
        }
    }

    private Rule priorRule(ProvisionContext ctx) {
        try {
            return elbV2Service.describeRules(ctx.region(), null, List.of(ctx.priorPhysicalId()))
                    .stream().findFirst().orElse(null);
        } catch (AwsException e) {
            if (!"RuleNotFound".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Rule {0} no longer exists, creating it again", ctx.priorPhysicalId());
            return null;
        }
    }

    private static String nameOrPrior(String declared, String prior, ProvisionContext ctx, String logicalId) {
        if (declared != null && !declared.isBlank()) {
            return declared;
        }
        if (prior != null && !prior.isBlank()) {
            return prior;
        }
        return generateElbName(ctx.stackName(), logicalId);
    }

    /** ELBv2 names: at most 32 characters of {@code [A-Za-z0-9-]}, no leading or trailing hyphen. */
    private static String generateElbName(String stackName, String logicalId) {
        String base = (stackName + "-" + logicalId).replaceAll("[^A-Za-z0-9-]", "");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        int maxBase = 32 - 1 - suffix.length();
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase);
        }
        base = base.replaceAll("-+$", "");
        if (base.isEmpty()) {
            base = "elb";
        }
        return base + "-" + suffix;
    }

    /** The ARN resource is {@code loadbalancer/<type>/<name>/<id>}; the full name drops the prefix. */
    private static String loadBalancerFullName(String lbArn) {
        String resource = AwsArnUtils.parse(lbArn).resource();
        String prefix = "loadbalancer/";
        return resource.startsWith(prefix) ? resource.substring(prefix.length()) : resource;
    }

    /** The target group full name keeps its prefix: {@code targetgroup/<name>/<id>}. */
    private static String targetGroupFullName(String tgArn) {
        return AwsArnUtils.parse(tgArn).resource();
    }

    private static String parseMatcher(JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("Matcher") || props.get("Matcher").isNull()) {
            return null;
        }
        JsonNode m = ctx.engine().resolveNode(props.get("Matcher"));
        if (m.hasNonNull("HttpCode")) {
            return m.path("HttpCode").asText();
        }
        if (m.hasNonNull("GrpcCode")) {
            return m.path("GrpcCode").asText();
        }
        return null;
    }

    private static List<String> parseCertificates(JsonNode props, ProvisionContext ctx) {
        List<String> result = new ArrayList<>();
        if (props == null || !props.has("Certificates") || props.get("Certificates").isNull()) {
            return result;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("Certificates"));
        if (resolved.isArray()) {
            for (JsonNode c : resolved) {
                if (c.hasNonNull("CertificateArn")) {
                    result.add(c.path("CertificateArn").asText());
                }
            }
        }
        return result;
    }

    private static List<Action> parseActions(JsonNode node, ProvisionContext ctx) {
        List<Action> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        JsonNode resolved = ctx.engine().resolveNode(node);
        if (!resolved.isArray()) {
            return result;
        }
        for (JsonNode item : resolved) {
            Action action = new Action();
            action.setType(textOrNull(item, "Type"));
            if (item.hasNonNull("Order")) {
                action.setOrder(item.path("Order").asInt());
            }
            if (item.hasNonNull("TargetGroupArn")) {
                action.setTargetGroupArn(item.path("TargetGroupArn").asText());
            }
            JsonNode forward = item.path("ForwardConfig");
            if (forward.isObject()) {
                JsonNode tgs = forward.path("TargetGroups");
                if (tgs.isArray()) {
                    List<Action.TargetGroupTuple> tuples = new ArrayList<>();
                    for (JsonNode t : tgs) {
                        Action.TargetGroupTuple tuple = new Action.TargetGroupTuple();
                        if (t.hasNonNull("TargetGroupArn")) {
                            tuple.setTargetGroupArn(t.path("TargetGroupArn").asText());
                        }
                        if (t.hasNonNull("Weight")) {
                            tuple.setWeight(t.path("Weight").asInt());
                        }
                        tuples.add(tuple);
                    }
                    action.setTargetGroups(tuples);
                }
                JsonNode stickiness = forward.path("TargetGroupStickinessConfig");
                if (stickiness.isObject()) {
                    if (stickiness.hasNonNull("Enabled")) {
                        action.setStickinessEnabled(stickiness.path("Enabled").asBoolean());
                    }
                    if (stickiness.hasNonNull("DurationSeconds")) {
                        action.setStickinessDurationSeconds(stickiness.path("DurationSeconds").asInt());
                    }
                }
            }
            JsonNode redirect = item.path("RedirectConfig");
            if (redirect.isObject()) {
                action.setRedirectProtocol(textOrNull(redirect, "Protocol"));
                action.setRedirectPort(textOrNull(redirect, "Port"));
                action.setRedirectHost(textOrNull(redirect, "Host"));
                action.setRedirectPath(textOrNull(redirect, "Path"));
                action.setRedirectQuery(textOrNull(redirect, "Query"));
                action.setRedirectStatusCode(textOrNull(redirect, "StatusCode"));
            }
            JsonNode fixed = item.path("FixedResponseConfig");
            if (fixed.isObject()) {
                action.setFixedResponseStatusCode(textOrNull(fixed, "StatusCode"));
                action.setFixedResponseContentType(textOrNull(fixed, "ContentType"));
                action.setFixedResponseMessageBody(textOrNull(fixed, "MessageBody"));
            }
            result.add(action);
        }
        return result;
    }

    private static List<RuleCondition> parseRuleConditions(JsonNode node, ProvisionContext ctx) {
        List<RuleCondition> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        JsonNode resolved = ctx.engine().resolveNode(node);
        if (!resolved.isArray()) {
            return result;
        }
        for (JsonNode item : resolved) {
            RuleCondition condition = new RuleCondition();
            condition.setField(textOrNull(item, "Field"));
            if (item.path("Values").isArray()) {
                condition.setValues(toStringList(item.path("Values")));
            }
            JsonNode pathCfg = item.path("PathPatternConfig");
            if (pathCfg.path("Values").isArray()) {
                condition.setPathPatternValues(toStringList(pathCfg.path("Values")));
            }
            JsonNode hostCfg = item.path("HostHeaderConfig");
            if (hostCfg.path("Values").isArray()) {
                condition.setHostHeaderValues(toStringList(hostCfg.path("Values")));
            }
            JsonNode httpHeaderCfg = item.path("HttpHeaderConfig");
            if (httpHeaderCfg.isObject()) {
                condition.setHttpHeaderName(textOrNull(httpHeaderCfg, "HttpHeaderName"));
                if (httpHeaderCfg.path("Values").isArray()) {
                    condition.setHttpHeaderValues(toStringList(httpHeaderCfg.path("Values")));
                }
            }
            JsonNode methodCfg = item.path("HttpRequestMethodConfig");
            if (methodCfg.path("Values").isArray()) {
                condition.setHttpMethodValues(toStringList(methodCfg.path("Values")));
            }
            JsonNode sourceIpCfg = item.path("SourceIpConfig");
            if (sourceIpCfg.path("Values").isArray()) {
                condition.setSourceIpValues(toStringList(sourceIpCfg.path("Values")));
            }
            JsonNode queryCfg = item.path("QueryStringConfig");
            if (queryCfg.path("Values").isArray()) {
                List<RuleCondition.QueryStringPair> pairs = new ArrayList<>();
                for (JsonNode q : queryCfg.path("Values")) {
                    RuleCondition.QueryStringPair pair = new RuleCondition.QueryStringPair();
                    pair.setKey(textOrNull(q, "Key"));
                    pair.setValue(textOrNull(q, "Value"));
                    pairs.add(pair);
                }
                condition.setQueryStringValues(pairs);
            }
            result.add(condition);
        }
        return result;
    }

    /** An absent value takes the default; anything present has to be an integer. */
    private static int parseInt(String value, String property, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw notAnInteger(property);
        }
    }

    private static AwsException notAnInteger(String property) {
        return new AwsException("ValidationError", "Value of property " + property + " must be an integer.", 400);
    }

    private static Boolean parseBooleanOrNull(String value) {
        return value == null || value.isBlank() ? null : Boolean.valueOf(value);
    }

    private static String textOrNull(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> result.add(v.asText()));
        }
        return result;
    }
}
