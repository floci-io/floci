package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.elbv2.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class ElbV2Service {

    @Inject
    ElbV2DataPlane dataPlane;

    @Inject
    ElbV2HealthChecker healthChecker;

    private static final String CANONICAL_HOSTED_ZONE_ID = "Z35SXDOTRQ7X7K";
    private static final String DEFAULT_ACCOUNT = "000000000000";

    // region → ARN → resource
    private final Map<String, Map<String, LoadBalancer>> loadBalancers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, TargetGroup>>  targetGroups  = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Listener>>     listeners     = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Rule>>         rules         = new ConcurrentHashMap<>();

    // indexes
    private final Map<String, List<String>> lbToListeners   = new ConcurrentHashMap<>(); // LB-ARN → listener ARNs
    private final Map<String, List<String>> listenerToRules  = new ConcurrentHashMap<>(); // Listener-ARN → rule ARNs
    private final Map<String, Set<String>>  tgToLbs          = new ConcurrentHashMap<>(); // TG-ARN → LB-ARNs

    // tags: resource-ARN → {key → value}
    private final Map<String, Map<String, String>> tags = new ConcurrentHashMap<>();

    // ── Load Balancers ────────────────────────────────────────────────────────

    public LoadBalancer createLoadBalancer(String region, String name, String scheme,
                                           String type, String ipAddressType,
                                           List<String> subnets, List<String> securityGroups,
                                           Map<String, String> initialTags) {
        validateName(name, "load balancer");
        Map<String, LoadBalancer> regionLbs = loadBalancers.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
        boolean duplicate = regionLbs.values().stream()
                .anyMatch(lb -> lb.getLoadBalancerName().equals(name));
        if (duplicate) {
            throw new AwsException("DuplicateLoadBalancerName",
                    "A load balancer with name '" + name + "' already exists.", 400);
        }

        String lbType = type != null ? type : "application";
        String lbScheme = scheme != null ? scheme : "internet-facing";
        String ipType = ipAddressType != null ? ipAddressType : "ipv4";
        String typePrefix = lbTypePrefix(lbType);
        String id = randomHex16();
        String arn = AwsArnUtils.Arn.of("elasticloadbalancing", region, DEFAULT_ACCOUNT, "loadbalancer/" + typePrefix + "/" + name + "/" + id).toString();
        String dnsName = name + "-" + id + ".elb.localhost";

        LoadBalancer lb = new LoadBalancer();
        lb.setLoadBalancerArn(arn);
        lb.setDnsName(dnsName);
        lb.setCanonicalHostedZoneId(CANONICAL_HOSTED_ZONE_ID);
        lb.setCreatedTime(Instant.now());
        lb.setLoadBalancerName(name);
        lb.setScheme(lbScheme);
        lb.setVpcId("vpc-00000001");
        lb.setState("active");
        lb.setType(lbType);
        lb.setIpAddressType(ipType);
        lb.setRegion(region);
        if (subnets != null) lb.setAvailabilityZones(new ArrayList<>(subnets));
        if (securityGroups != null) lb.setSecurityGroups(new ArrayList<>(securityGroups));

        regionLbs.put(arn, lb);
        lbToListeners.put(arn, new ArrayList<>());
        if (!initialTags.isEmpty()) {
            tags.put(arn, new LinkedHashMap<>(initialTags));
        }
        return lb;
    }

    public List<LoadBalancer> describeLoadBalancers(String region, List<String> arns, List<String> names,
                                                     String marker, Integer pageSize) {
        Map<String, LoadBalancer> regionLbs = loadBalancers.getOrDefault(region, Map.of());
        List<LoadBalancer> result = new ArrayList<>(regionLbs.values());

        if (arns != null && !arns.isEmpty()) {
            Set<String> arnSet = new HashSet<>(arns);
            result = result.stream().filter(lb -> arnSet.contains(lb.getLoadBalancerArn())).collect(Collectors.toList());
            if (result.isEmpty() && !arns.isEmpty()) {
                throw new AwsException("LoadBalancerNotFound",
                        "One or more load balancers not found.", 400);
            }
        }
        if (names != null && !names.isEmpty()) {
            Set<String> nameSet = new HashSet<>(names);
            result = result.stream().filter(lb -> nameSet.contains(lb.getLoadBalancerName())).collect(Collectors.toList());
            if (result.isEmpty() && !names.isEmpty()) {
                throw new AwsException("LoadBalancerNotFound",
                        "One or more load balancers not found.", 400);
            }
        }
        return result;
    }

    public void deleteLoadBalancer(String region, String arn) {
        Map<String, LoadBalancer> regionLbs = loadBalancers.getOrDefault(region, Map.of());
        LoadBalancer lb = regionLbs.remove(arn);
        if (lb == null) {
            return; // AWS silently ignores non-existent LBs on delete
        }
        // cascade: listeners → rules
        List<String> listenerArns = lbToListeners.remove(arn);
        if (listenerArns != null) {
            Map<String, Listener> regionListeners = listeners.getOrDefault(region, Map.of());
            Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());
            for (String listenerArn : listenerArns) {
                dataPlane.stopListener(listenerArn);
                regionListeners.remove(listenerArn);
                List<String> ruleArns = listenerToRules.remove(listenerArn);
                if (ruleArns != null) {
                    ruleArns.forEach(regionRules::remove);
                }
            }
        }
        // remove from TG index
        tgToLbs.values().forEach(lbSet -> lbSet.remove(arn));
        tags.remove(arn);
    }

    public Map<String, String> describeLoadBalancerAttributes(String region, String arn) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        return new LinkedHashMap<>(lb.getAttributes());
    }

    public void modifyLoadBalancerAttributes(String region, String arn, Map<String, String> newAttrs) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.getAttributes().putAll(newAttrs);
    }

    public void setSecurityGroups(String region, String arn, List<String> sgIds) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.setSecurityGroups(new ArrayList<>(sgIds));
    }

    public void setSubnets(String region, String arn, List<String> subnets) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.setAvailabilityZones(new ArrayList<>(subnets));
    }

    public void setIpAddressType(String region, String arn, String ipAddressType) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.setIpAddressType(ipAddressType);
    }

    // ── Target Groups ─────────────────────────────────────────────────────────

    public TargetGroup createTargetGroup(String region, String name, String protocol, String protocolVersion,
                                          Integer port, String vpcId, String targetType,
                                          String healthCheckProtocol, String healthCheckPort,
                                          Boolean healthCheckEnabled, String healthCheckPath,
                                          Integer healthCheckInterval, Integer healthCheckTimeout,
                                          Integer healthyThreshold, Integer unhealthyThreshold,
                                          String matcher, String ipAddressType,
                                          Map<String, String> initialTags) {
        validateName(name, "target group");
        Map<String, TargetGroup> regionTgs = targetGroups.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
        boolean duplicate = regionTgs.values().stream()
                .anyMatch(tg -> tg.getTargetGroupName().equals(name));
        if (duplicate) {
            throw new AwsException("DuplicateTargetGroupName",
                    "A target group with name '" + name + "' already exists.", 400);
        }

        String id = randomHex16();
        String arn = AwsArnUtils.Arn.of("elasticloadbalancing", region, DEFAULT_ACCOUNT, "targetgroup/" + name + "/" + id).toString();

        TargetGroup tg = new TargetGroup();
        tg.setTargetGroupArn(arn);
        tg.setTargetGroupName(name);
        tg.setProtocol(protocol != null ? protocol : "HTTP");
        tg.setProtocolVersion(protocolVersion != null ? protocolVersion : "HTTP1");
        tg.setPort(port);
        tg.setVpcId(vpcId);
        tg.setTargetType(targetType != null ? targetType : "instance");
        tg.setIpAddressType(ipAddressType != null ? ipAddressType : "ipv4");
        tg.setRegion(region);

        // health check defaults
        tg.setHealthCheckEnabled(healthCheckEnabled != null ? healthCheckEnabled : true);
        tg.setHealthCheckProtocol(healthCheckProtocol != null ? healthCheckProtocol : "HTTP");
        tg.setHealthCheckPort(healthCheckPort != null ? healthCheckPort : "traffic-port");
        tg.setHealthCheckPath(healthCheckPath != null ? healthCheckPath : "/");
        tg.setHealthCheckIntervalSeconds(healthCheckInterval != null ? healthCheckInterval : 30);
        tg.setHealthCheckTimeoutSeconds(healthCheckTimeout != null ? healthCheckTimeout : 5);
        tg.setHealthyThresholdCount(healthyThreshold != null ? healthyThreshold : 5);
        tg.setUnhealthyThresholdCount(unhealthyThreshold != null ? unhealthyThreshold : 2);
        tg.setMatcher(matcher != null ? matcher : "200");

        regionTgs.put(arn, tg);
        tgToLbs.put(arn, ConcurrentHashMap.newKeySet());
        if (!initialTags.isEmpty()) {
            tags.put(arn, new LinkedHashMap<>(initialTags));
        }
        healthChecker.startMonitoring(tg);
        return tg;
    }

    public List<TargetGroup> describeTargetGroups(String region, String lbArn, List<String> tgArns,
                                                    List<String> names) {
        Map<String, TargetGroup> regionTgs = targetGroups.getOrDefault(region, Map.of());
        List<TargetGroup> result = new ArrayList<>(regionTgs.values());

        if (lbArn != null && !lbArn.isEmpty()) {
            result = result.stream()
                    .filter(tg -> tgToLbs.getOrDefault(tg.getTargetGroupArn(), Set.of()).contains(lbArn))
                    .collect(Collectors.toList());
        }
        if (tgArns != null && !tgArns.isEmpty()) {
            Set<String> arnSet = new HashSet<>(tgArns);
            result = result.stream().filter(tg -> arnSet.contains(tg.getTargetGroupArn())).collect(Collectors.toList());
            if (result.isEmpty()) {
                throw new AwsException("TargetGroupNotFound", "One or more target groups not found.", 400);
            }
        }
        if (names != null && !names.isEmpty()) {
            Set<String> nameSet = new HashSet<>(names);
            result = result.stream().filter(tg -> nameSet.contains(tg.getTargetGroupName())).collect(Collectors.toList());
        }
        return result;
    }

    public void deleteTargetGroup(String region, String arn) {
        TargetGroup tg = targetGroups.getOrDefault(region, Map.of()).get(arn);
        if (tg == null) {
            return;
        }
        Set<String> lbRefs = tgToLbs.getOrDefault(arn, Set.of());
        if (!lbRefs.isEmpty()) {
            throw new AwsException("ResourceInUse",
                    "Target group '" + tg.getTargetGroupName() + "' is currently in use by a listener or rule.", 400);
        }
        healthChecker.stopMonitoring(arn);
        targetGroups.getOrDefault(region, Map.of()).remove(arn);
        tgToLbs.remove(arn);
        tags.remove(arn);
    }

    public void modifyTargetGroup(String region, String arn, String healthCheckProtocol,
                                   String healthCheckPort, Boolean healthCheckEnabled,
                                   String healthCheckPath, Integer healthCheckInterval,
                                   Integer healthCheckTimeout, Integer healthyThreshold,
                                   Integer unhealthyThreshold, String matcher) {
        TargetGroup tg = requireTargetGroup(region, arn);
        if (healthCheckProtocol != null) tg.setHealthCheckProtocol(healthCheckProtocol);
        if (healthCheckPort != null)     tg.setHealthCheckPort(healthCheckPort);
        if (healthCheckEnabled != null)  tg.setHealthCheckEnabled(healthCheckEnabled);
        if (healthCheckPath != null)     tg.setHealthCheckPath(healthCheckPath);
        if (healthCheckInterval != null) tg.setHealthCheckIntervalSeconds(healthCheckInterval);
        if (healthCheckTimeout != null)  tg.setHealthCheckTimeoutSeconds(healthCheckTimeout);
        if (healthyThreshold != null)    tg.setHealthyThresholdCount(healthyThreshold);
        if (unhealthyThreshold != null)  tg.setUnhealthyThresholdCount(unhealthyThreshold);
        if (matcher != null)             tg.setMatcher(matcher);
    }

    public Map<String, String> describeTargetGroupAttributes(String region, String arn) {
        TargetGroup tg = requireTargetGroup(region, arn);
        return new LinkedHashMap<>(tg.getAttributes());
    }

    public void modifyTargetGroupAttributes(String region, String arn, Map<String, String> newAttrs) {
        TargetGroup tg = requireTargetGroup(region, arn);
        tg.getAttributes().putAll(newAttrs);
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    public Listener createListener(String region, String lbArn, String protocol, Integer port,
                                    String sslPolicy, List<String> certificates,
                                    List<Action> defaultActions, List<String> alpnPolicy,
                                    Map<String, String> initialTags) {
        requireLoadBalancer(region, lbArn);

        Map<String, Listener> regionListeners = listeners.computeIfAbsent(region, k -> new ConcurrentHashMap<>());

        // check duplicate port on same LB
        boolean portExists = regionListeners.values().stream()
                .filter(l -> l.getLoadBalancerArn().equals(lbArn))
                .anyMatch(l -> Objects.equals(l.getPort(), port));
        if (portExists) {
            throw new AwsException("DuplicateListener",
                    "A listener already exists on port " + port + " for this load balancer.", 400);
        }

        LoadBalancer lb = requireLoadBalancer(region, lbArn);
        String lbType = lb.getType() != null ? lb.getType() : "application";
        String typePrefix = lbTypePrefix(lbType);
        String lbId = arnId(lbArn);
        String listenerId = randomHex16();
        String listenerArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, DEFAULT_ACCOUNT, "listener/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId).toString();

        Listener listener = new Listener();
        listener.setListenerArn(listenerArn);
        listener.setLoadBalancerArn(lbArn);
        listener.setPort(port);
        listener.setProtocol(protocol != null ? protocol : "HTTP");
        listener.setSslPolicy(sslPolicy);
        listener.setCertificates(certificates != null ? new ArrayList<>(certificates) : new ArrayList<>());
        listener.setDefaultActions(defaultActions != null ? new ArrayList<>(defaultActions) : new ArrayList<>());
        listener.setAlpnPolicy(alpnPolicy != null ? new ArrayList<>(alpnPolicy) : new ArrayList<>());

        regionListeners.put(listenerArn, listener);
        lbToListeners.computeIfAbsent(lbArn, k -> new ArrayList<>()).add(listenerArn);

        // auto-create the default rule
        Rule defaultRule = buildDefaultRule(region, listenerArn, lb, lbId, listenerId, defaultActions);
        rules.computeIfAbsent(region, k -> new ConcurrentHashMap<>()).put(defaultRule.getRuleArn(), defaultRule);
        listenerToRules.computeIfAbsent(listenerArn, k -> new ArrayList<>()).add(defaultRule.getRuleArn());

        if (!initialTags.isEmpty()) {
            tags.put(listenerArn, new LinkedHashMap<>(initialTags));
        }
        dataPlane.startListener(listener, region, getListenerRules(region, listenerArn));
        return listener;
    }

    public List<Listener> describeListeners(String region, String lbArn, List<String> listenerArns) {
        Map<String, Listener> regionListeners = listeners.getOrDefault(region, Map.of());
        List<Listener> result = new ArrayList<>(regionListeners.values());

        if (lbArn != null && !lbArn.isEmpty()) {
            result = result.stream()
                    .filter(l -> l.getLoadBalancerArn().equals(lbArn))
                    .collect(Collectors.toList());
        }
        if (listenerArns != null && !listenerArns.isEmpty()) {
            Set<String> arnSet = new HashSet<>(listenerArns);
            result = result.stream().filter(l -> arnSet.contains(l.getListenerArn())).collect(Collectors.toList());
        }
        return result;
    }

    public void deleteListener(String region, String listenerArn) {
        Map<String, Listener> regionListeners = listeners.getOrDefault(region, Map.of());
        Listener listener = regionListeners.remove(listenerArn);
        if (listener == null) {
            return;
        }
        dataPlane.stopListener(listenerArn);
        lbToListeners.getOrDefault(listener.getLoadBalancerArn(), List.of()).remove(listenerArn);

        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());
        List<String> ruleArns = listenerToRules.remove(listenerArn);
        if (ruleArns != null) {
            ruleArns.forEach(regionRules::remove);
        }
        tags.remove(listenerArn);
    }

    public Listener modifyListener(String region, String listenerArn, String protocol, Integer port,
                                    String sslPolicy, List<String> certificates,
                                    List<Action> defaultActions, List<String> alpnPolicy) {
        Listener listener = requireListener(region, listenerArn);

        if (port != null && !Objects.equals(listener.getPort(), port)) {
            // check duplicate port on same LB
            Map<String, Listener> regionListeners = listeners.getOrDefault(region, Map.of());
            boolean portExists = regionListeners.values().stream()
                    .filter(l -> l.getLoadBalancerArn().equals(listener.getLoadBalancerArn()) && !l.getListenerArn().equals(listenerArn))
                    .anyMatch(l -> Objects.equals(l.getPort(), port));
            if (portExists) {
                throw new AwsException("DuplicateListener",
                        "A listener already exists on port " + port + " for this load balancer.", 400);
            }
            listener.setPort(port);
        }
        if (protocol != null)      listener.setProtocol(protocol);
        if (sslPolicy != null)     listener.setSslPolicy(sslPolicy);
        if (certificates != null)  listener.setCertificates(new ArrayList<>(certificates));
        if (alpnPolicy != null)    listener.setAlpnPolicy(new ArrayList<>(alpnPolicy));
        if (defaultActions != null) {
            listener.setDefaultActions(new ArrayList<>(defaultActions));
            // update the default rule's actions
            listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                    .map(ra -> rules.getOrDefault(region, Map.of()).get(ra))
                    .filter(r -> r != null && r.isDefault())
                    .forEach(r -> r.setActions(new ArrayList<>(defaultActions)));
        }
        dataPlane.stopListener(listenerArn);
        dataPlane.startListener(requireListener(region, listenerArn), region, getListenerRules(region, listenerArn));
        return listener;
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    public Rule createRule(String region, String listenerArn, List<RuleCondition> conditions,
                            int priority, List<Action> actions, Map<String, String> initialTags) {
        requireListener(region, listenerArn);
        if (priority < 1 || priority > 50000) {
            throw new AwsException("ValidationError", "Priority must be between 1 and 50000.", 400);
        }

        Map<String, Rule> regionRules = rules.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
        List<String> existingRuleArns = listenerToRules.getOrDefault(listenerArn, List.of());
        String priorityStr = String.valueOf(priority);
        boolean priorityTaken = existingRuleArns.stream()
                .map(regionRules::get)
                .filter(Objects::nonNull)
                .anyMatch(r -> priorityStr.equals(r.getPriority()));
        if (priorityTaken) {
            throw new AwsException("PriorityInUse",
                    "The specified priority is already in use.", 400);
        }

        Listener listener = requireListener(region, listenerArn);
        LoadBalancer lb = requireLoadBalancer(region, listener.getLoadBalancerArn());
        String lbType = lb.getType() != null ? lb.getType() : "application";
        String typePrefix = lbTypePrefix(lbType);
        String lbId = arnId(listener.getLoadBalancerArn());
        String listenerId = arnId(listenerArn);
        String ruleId = randomHex16();
        String ruleArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, DEFAULT_ACCOUNT, "listener-rule/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId + "/" + ruleId).toString();

        Rule rule = new Rule();
        rule.setRuleArn(ruleArn);
        rule.setListenerArn(listenerArn);
        rule.setPriority(priorityStr);
        rule.setConditions(conditions != null ? new ArrayList<>(conditions) : new ArrayList<>());
        rule.setActions(actions != null ? new ArrayList<>(actions) : new ArrayList<>());
        rule.setDefault(false);

        regionRules.put(ruleArn, rule);
        listenerToRules.computeIfAbsent(listenerArn, k -> new ArrayList<>()).add(ruleArn);

        // update TG → LB index for all target group actions
        for (Action a : rule.getActions()) {
            linkTgToLb(a, listener.getLoadBalancerArn());
        }

        if (!initialTags.isEmpty()) {
            tags.put(ruleArn, new LinkedHashMap<>(initialTags));
        }
        dataPlane.recompileRules(listenerArn, getListenerRules(region, listenerArn));
        return rule;
    }

    public List<Rule> describeRules(String region, String listenerArn, List<String> ruleArns) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());

        if (ruleArns != null && !ruleArns.isEmpty()) {
            return ruleArns.stream()
                    .map(regionRules::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        if (listenerArn != null && !listenerArn.isEmpty()) {
            return listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                    .map(regionRules::get)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(r -> prioritySortKey(r.getPriority())))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>(regionRules.values());
    }

    public void deleteRule(String region, String ruleArn) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());
        Rule rule = regionRules.get(ruleArn);
        if (rule == null) {
            return;
        }
        if (rule.isDefault()) {
            throw new AwsException("OperationNotPermitted",
                    "The default rule for a listener cannot be deleted.", 400);
        }
        String listenerArn = rule.getListenerArn();
        regionRules.remove(ruleArn);
        listenerToRules.getOrDefault(listenerArn, List.of()).remove(ruleArn);
        tags.remove(ruleArn);
        dataPlane.recompileRules(listenerArn, getListenerRules(region, listenerArn));
    }

    public Rule modifyRule(String region, String ruleArn, List<RuleCondition> conditions, List<Action> actions) {
        Rule rule = requireRule(region, ruleArn);
        String listenerArn = rule.getListenerArn();
        if (conditions != null) rule.setConditions(new ArrayList<>(conditions));
        if (actions != null)    rule.setActions(new ArrayList<>(actions));
        dataPlane.recompileRules(listenerArn, getListenerRules(region, listenerArn));
        return rule;
    }

    public void setRulePriorities(String region, Map<String, Integer> arnToPriority) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());

        // validate all rules exist and are not default before touching anything
        for (Map.Entry<String, Integer> e : arnToPriority.entrySet()) {
            Rule rule = regionRules.get(e.getKey());
            if (rule == null) {
                throw new AwsException("RuleNotFound", "Rule not found: " + e.getKey(), 400);
            }
            if (rule.isDefault()) {
                throw new AwsException("OperationNotPermitted", "Cannot change priority of the default rule.", 400);
            }
            int p = e.getValue();
            if (p < 1 || p > 50000) {
                throw new AwsException("ValidationError", "Priority must be between 1 and 50000.", 400);
            }
        }

        // check for collisions with rules NOT in the update set
        Set<String> updatingArns = arnToPriority.keySet();
        Set<Integer> newPriorities = new HashSet<>(arnToPriority.values());
        for (Rule existing : regionRules.values()) {
            if (!updatingArns.contains(existing.getRuleArn()) && !existing.isDefault()) {
                try {
                    int existingPriority = Integer.parseInt(existing.getPriority());
                    if (newPriorities.contains(existingPriority)) {
                        throw new AwsException("PriorityInUse",
                                "Priority " + existingPriority + " is already in use.", 400);
                    }
                } catch (NumberFormatException ignored) { /* default rule */ }
            }
        }

        // commit
        arnToPriority.forEach((arn, priority) -> regionRules.get(arn).setPriority(String.valueOf(priority)));

        Set<String> affectedListeners = arnToPriority.keySet().stream()
                .map(arn -> regionRules.get(arn).getListenerArn())
                .collect(Collectors.toSet());
        affectedListeners.forEach(la -> dataPlane.recompileRules(la, getListenerRules(region, la)));
    }

    // ── Targets ───────────────────────────────────────────────────────────────

    public void registerTargets(String region, String tgArn, List<TargetDescription> targets) {
        TargetGroup tg = requireTargetGroup(region, tgArn);
        List<TargetDescription> existing = tg.getTargets();
        for (TargetDescription t : targets) {
            // replace if same id+port already registered
            existing.removeIf(e -> e.getId().equals(t.getId()) && Objects.equals(e.getPort(), t.getPort()));
            existing.add(t);
        }
        healthChecker.addTargets(tgArn, targets, tg);
    }

    public void deregisterTargets(String region, String tgArn, List<TargetDescription> targets) {
        TargetGroup tg = requireTargetGroup(region, tgArn);
        for (TargetDescription t : targets) {
            tg.getTargets().removeIf(e -> e.getId().equals(t.getId()) && Objects.equals(e.getPort(), t.getPort()));
        }
        healthChecker.removeTargets(tgArn, targets, tg);
    }

    public List<TargetHealth> describeTargetHealth(String region, String tgArn,
                                                     List<TargetDescription> filterTargets) {
        TargetGroup tg = requireTargetGroup(region, tgArn);
        List<TargetDescription> candidates = filterTargets != null && !filterTargets.isEmpty()
                ? filterTargets : tg.getTargets();

        boolean isLambdaTg = "lambda".equals(tg.getTargetType());
        return candidates.stream().map(t -> {
            TargetHealth th = new TargetHealth();
            th.setTarget(t);
            if (isLambdaTg) {
                th.setHealthCheckPort("N/A");
                th.setState("healthy");
                return th;
            }
            int port = ElbV2HealthChecker.effectivePort(t, tg);
            th.setHealthCheckPort(String.valueOf(port));
            String state = healthChecker.getState(tgArn, t.getId(), port);
            th.setState(state);
            if ("initial".equals(state)) {
                th.setReason("Elb.RegistrationInProgress");
                th.setDescription("Target registration is in progress");
            } else if ("unhealthy".equals(state)) {
                th.setReason("Target.FailedHealthChecks");
                th.setDescription("Health checks failed");
            }
            return th;
        }).collect(Collectors.toList());
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public void addTags(List<String> resourceArns, Map<String, String> newTags) {
        for (String arn : resourceArns) {
            tags.computeIfAbsent(arn, k -> new LinkedHashMap<>()).putAll(newTags);
        }
    }

    public void removeTags(List<String> resourceArns, List<String> tagKeys) {
        for (String arn : resourceArns) {
            Map<String, String> resourceTags = tags.get(arn);
            if (resourceTags != null) {
                tagKeys.forEach(resourceTags::remove);
            }
        }
    }

    public Map<String, Map<String, String>> describeTags(List<String> resourceArns) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String arn : resourceArns) {
            result.put(arn, tags.getOrDefault(arn, Map.of()));
        }
        return result;
    }

    // ── Listener Certificates ─────────────────────────────────────────────────

    public void addListenerCertificates(String region, String listenerArn, List<String> certArns) {
        Listener listener = requireListener(region, listenerArn);
        for (String certArn : certArns) {
            if (!listener.getCertificates().contains(certArn)) {
                listener.getCertificates().add(certArn);
            }
        }
    }

    public void removeListenerCertificates(String region, String listenerArn, List<String> certArns) {
        Listener listener = requireListener(region, listenerArn);
        listener.getCertificates().removeAll(certArns);
    }

    public List<String> describeListenerCertificates(String region, String listenerArn) {
        Listener listener = requireListener(region, listenerArn);
        return new ArrayList<>(listener.getCertificates());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LoadBalancer requireLoadBalancer(String region, String arn) {
        LoadBalancer lb = loadBalancers.getOrDefault(region, Map.of()).get(arn);
        if (lb == null) {
            throw new AwsException("LoadBalancerNotFound",
                    "One or more load balancers not found.", 400);
        }
        return lb;
    }

    private TargetGroup requireTargetGroup(String region, String arn) {
        TargetGroup tg = targetGroups.getOrDefault(region, Map.of()).get(arn);
        if (tg == null) {
            throw new AwsException("TargetGroupNotFound",
                    "One or more target groups not found.", 400);
        }
        return tg;
    }

    private Listener requireListener(String region, String arn) {
        Listener l = listeners.getOrDefault(region, Map.of()).get(arn);
        if (l == null) {
            throw new AwsException("ListenerNotFound",
                    "One or more listeners not found.", 400);
        }
        return l;
    }

    private Rule requireRule(String region, String arn) {
        Rule r = rules.getOrDefault(region, Map.of()).get(arn);
        if (r == null) {
            throw new AwsException("RuleNotFound", "One or more rules not found.", 400);
        }
        return r;
    }

    public TargetGroup getTargetGroup(String region, String arn) {
        return targetGroups.getOrDefault(region, Map.of()).get(arn);
    }

    public TargetGroup getTargetGroupByName(String region, String name) {
        return targetGroups.getOrDefault(region, Map.of()).values().stream()
                .filter(tg -> tg.getTargetGroupName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public void shiftListenerForward(String region, String listenerArn,
                                     String blueTgArn, String greenTgArn, int greenWeightPct) {
        Rule defaultRule = listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                .map(arn -> rules.getOrDefault(region, Map.of()).get(arn))
                .filter(r -> r != null && r.isDefault())
                .findFirst()
                .orElse(null);
        if (defaultRule == null) {
            return;
        }
        Action action = new Action();
        action.setType("forward");
        if (greenWeightPct >= 100) {
            action.setTargetGroupArn(greenTgArn);
        } else {
            Action.TargetGroupTuple blueTuple = new Action.TargetGroupTuple();
            blueTuple.setTargetGroupArn(blueTgArn);
            blueTuple.setWeight(100 - greenWeightPct);
            Action.TargetGroupTuple greenTuple = new Action.TargetGroupTuple();
            greenTuple.setTargetGroupArn(greenTgArn);
            greenTuple.setWeight(greenWeightPct);
            action.setTargetGroups(List.of(blueTuple, greenTuple));
        }
        defaultRule.setActions(List.of(action));
        dataPlane.recompileRules(listenerArn, getListenerRules(region, listenerArn));
    }

    private List<Rule> getListenerRules(String region, String listenerArn) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());
        return listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                .map(regionRules::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(r -> {
                    if ("default".equals(r.getPriority())) return Integer.MAX_VALUE;
                    try { return Integer.parseInt(r.getPriority()); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
                }))
                .collect(Collectors.toList());
    }

    private static void validateName(String name, String resource) {
        if (name == null || name.isEmpty()) {
            throw new AwsException("ValidationError", "Name is required for " + resource + ".", 400);
        }
        if (name.length() > 32) {
            throw new AwsException("ValidationError",
                    "Name '" + name + "' exceeds 32 characters.", 400);
        }
        if (!name.matches("[a-zA-Z0-9-]+")) {
            throw new AwsException("ValidationError",
                    "Name '" + name + "' contains invalid characters. Use alphanumeric characters and hyphens.", 400);
        }
        if (name.startsWith("-") || name.endsWith("-")) {
            throw new AwsException("ValidationError",
                    "Name '" + name + "' cannot start or end with a hyphen.", 400);
        }
    }

    private static String randomHex16() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String lbTypePrefix(String type) {
        return switch (type) {
            case "network" -> "net";
            case "gateway" -> "gwy";
            default -> "app";
        };
    }

    // extracts the last path segment of an ARN (the random hex ID)
    private static String arnId(String arn) {
        int last = arn.lastIndexOf('/');
        return last >= 0 ? arn.substring(last + 1) : arn;
    }

    private static int prioritySortKey(String priority) {
        if ("default".equals(priority)) return Integer.MAX_VALUE;
        try { return Integer.parseInt(priority); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    private Rule buildDefaultRule(String region, String listenerArn, LoadBalancer lb, String lbId,
                                   String listenerId, List<Action> defaultActions) {
        String lbType = lb.getType() != null ? lb.getType() : "application";
        String typePrefix = lbTypePrefix(lbType);
        String ruleId = randomHex16();
        String ruleArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, DEFAULT_ACCOUNT, "listener-rule/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId + "/" + ruleId).toString();

        Rule rule = new Rule();
        rule.setRuleArn(ruleArn);
        rule.setListenerArn(listenerArn);
        rule.setPriority("default");
        rule.setConditions(new ArrayList<>());
        rule.setActions(defaultActions != null ? new ArrayList<>(defaultActions) : new ArrayList<>());
        rule.setDefault(true);
        return rule;
    }

    private void linkTgToLb(Action action, String lbArn) {
        if ("forward".equals(action.getType())) {
            if (action.getTargetGroupArn() != null) {
                tgToLbs.computeIfAbsent(action.getTargetGroupArn(), k -> ConcurrentHashMap.newKeySet()).add(lbArn);
            }
            for (Action.TargetGroupTuple t : action.getTargetGroups()) {
                if (t.getTargetGroupArn() != null) {
                    tgToLbs.computeIfAbsent(t.getTargetGroupArn(), k -> ConcurrentHashMap.newKeySet()).add(lbArn);
                }
            }
        }
    }
}
