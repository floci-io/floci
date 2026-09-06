package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.Action;
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
 * {@code ListenerRule}, moved out of the {@code CloudFormationResourceProvisioner} switch.
 */
@ApplicationScoped
public class ElbV2CfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(ElbV2CfnProvisioner.class);

    private static final String LOAD_BALANCER = "AWS::ElasticLoadBalancingV2::LoadBalancer";
    private static final String TARGET_GROUP = "AWS::ElasticLoadBalancingV2::TargetGroup";
    private static final String LISTENER = "AWS::ElasticLoadBalancingV2::Listener";
    private static final String LISTENER_RULE = "AWS::ElasticLoadBalancingV2::ListenerRule";

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
        switch (r.getResourceType()) {
            case LOAD_BALANCER -> provisionLoadBalancer(r, props, ctx);
            case TARGET_GROUP -> provisionTargetGroup(r, props, ctx);
            case LISTENER -> provisionListener(r, props, ctx);
            case LISTENER_RULE -> provisionListenerRule(r, props, ctx);
            default -> throw new IllegalStateException("ElbV2CfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    /**
     * No already-gone tolerance is needed here: ElbV2Service returns silently for an unknown ARN on
     * all four deletes, and its real refusals (ResourceInUse on a target group a listener still
     * forwards to, OperationNotPermitted on a listener's default rule) must fail the stack delete.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case LOAD_BALANCER -> elbV2Service.deleteLoadBalancer(region, physicalId);
            case TARGET_GROUP -> elbV2Service.deleteTargetGroup(region, physicalId);
            case LISTENER -> elbV2Service.deleteListener(region, physicalId);
            case LISTENER_RULE -> elbV2Service.deleteRule(region, physicalId);
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
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
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
        List<String> subnets = ctx.resolveStringList(props, "Subnets");
        List<String> securityGroups = ctx.resolveStringList(props, "SecurityGroups");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");

        LoadBalancer lb;
        try {
            lb = elbV2Service.createLoadBalancer(ctx.region(), name, scheme, type, ipAddressType,
                    subnets, securityGroups, tags);
        } catch (AwsException e) {
            if (!"DuplicateLoadBalancerName".equals(e.getErrorCode())) {
                throw e;
            }
            lb = elbV2Service.describeLoadBalancers(ctx.region(), null, List.of(name), null, null).get(0);
        }

        r.setPhysicalId(lb.getLoadBalancerArn());
        r.getAttributes().put("LoadBalancerArn", lb.getLoadBalancerArn());
        r.getAttributes().put("DNSName", lb.getDnsName());
        r.getAttributes().put("CanonicalHostedZoneID", lb.getCanonicalHostedZoneId());
        r.getAttributes().put("LoadBalancerName", lb.getLoadBalancerName());
        r.getAttributes().put("LoadBalancerFullName", loadBalancerFullName(lb.getLoadBalancerArn()));
        ReplacementCleanup.record(r, ctx);
    }

    private void provisionTargetGroup(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = nameOrPrior(ctx.resolveOptional(props, "Name"), r.getAttributes().get("TargetGroupName"),
                ctx, r.getLogicalId());
        String protocol = ctx.resolveOptional(props, "Protocol");
        String protocolVersion = ctx.resolveOptional(props, "ProtocolVersion");
        Integer port = parseIntOrNull(ctx.resolveOptional(props, "Port"));
        String vpcId = ctx.resolveOptional(props, "VpcId");
        String targetType = ctx.resolveOptional(props, "TargetType");
        String hcProtocol = ctx.resolveOptional(props, "HealthCheckProtocol");
        String hcPort = ctx.resolveOptional(props, "HealthCheckPort");
        Boolean hcEnabled = parseBooleanOrNull(ctx.resolveOptional(props, "HealthCheckEnabled"));
        String hcPath = ctx.resolveOptional(props, "HealthCheckPath");
        Integer hcInterval = parseIntOrNull(ctx.resolveOptional(props, "HealthCheckIntervalSeconds"));
        Integer hcTimeout = parseIntOrNull(ctx.resolveOptional(props, "HealthCheckTimeoutSeconds"));
        Integer healthyThreshold = parseIntOrNull(ctx.resolveOptional(props, "HealthyThresholdCount"));
        Integer unhealthyThreshold = parseIntOrNull(ctx.resolveOptional(props, "UnhealthyThresholdCount"));
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
        ReplacementCleanup.record(r, ctx);
    }

    /**
     * LoadBalancerArn is create-only: an update that keeps it modifies the listener the stack
     * already owns, one that moves the listener to another balancer creates a replacement and the
     * displaced listener is deleted once the stack update commits.
     */
    private void provisionListener(StackResource r, JsonNode props, ProvisionContext ctx) {
        String lbArn = ctx.resolveOptional(props, "LoadBalancerArn");
        String protocol = resolveOrDefault(props, "Protocol", ctx, "HTTP");
        int port = parseInt(ctx.resolveOptional(props, "Port"), "Port", 80);
        String sslPolicy = ctx.resolveOptional(props, "SslPolicy");
        List<String> certificates = parseCertificates(props, ctx);
        List<Action> defaultActions = parseActions(props != null ? props.get("DefaultActions") : null, ctx);

        Listener listener;
        Listener prior = ctx.isUpdate() ? priorListener(ctx) : null;
        if (prior != null && (lbArn == null || lbArn.equals(prior.getLoadBalancerArn()))) {
            listener = elbV2Service.modifyListener(ctx.region(), ctx.priorPhysicalId(), protocol, port, sslPolicy,
                    certificates, defaultActions, null);
        } else {
            listener = elbV2Service.createListener(ctx.region(), lbArn, protocol, port, sslPolicy, certificates,
                    defaultActions, null, Map.of());
        }

        r.setPhysicalId(listener.getListenerArn());
        r.getAttributes().put("ListenerArn", listener.getListenerArn());
        ReplacementCleanup.record(r, ctx);
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
        ReplacementCleanup.record(r, ctx);
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

    private static String resolveOrDefault(JsonNode props, String name, ProvisionContext ctx, String fallback) {
        String value = ctx.resolveOptional(props, name);
        return value != null && !value.isBlank() ? value : fallback;
    }

    /** An absent value takes the default; anything present has to be an integer. */
    private static int parseInt(String value, String property, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError", "Value of property " + property + " must be an integer.", 400);
        }
    }

    /** The switch treated an unparsable optional integer as unset; kept so a stray value does not fail a stack. */
    private static Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            LOG.debugv("Ignoring non-integer value {0}", value);
            return null;
        }
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
