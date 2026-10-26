package io.github.hectorvent.floci.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegionFacts;
import io.github.hectorvent.floci.core.common.CertificateReferenceResolver;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.common.SsrfProtection;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.AcmCertificateReferenceProvider.CertificateReference;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.VpcDependencyProvider;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.model.*;
import io.github.hectorvent.floci.services.iam.ServerCertificateReferenceProvider;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@ApplicationScoped
public class ElbV2Service implements ResourceProvider, ServerCertificateReferenceProvider, VpcDependencyProvider {

    private static final Logger LOG = Logger.getLogger(ElbV2Service.class);
    /** {@code LoadBalancerTypeEnum}, in the model's order. */
    private static final List<String> LOAD_BALANCER_TYPES = List.of("application", "network", "gateway");

    @Inject
    ElbV2DataPlane dataPlane;

    @Inject
    ElbV2HealthChecker healthChecker;

    @Inject
    RegionResolver regionResolver;

    @Inject
    Ec2Service ec2Service;

    @Inject
    StorageFactory storageFactory;

    @Inject
    EmulatorConfig config;

    /**
     * Left null by the unit tests, which wire this service without IAM or ACM. A referrer with no
     * resolver keeps the behaviour it had before the check existed, rather than refusing every
     * certificate because it cannot look one up.
     */
    @Inject
    CertificateReferenceResolver certificateResolver;

    // region → ARN → resource
    private Map<String, Map<String, LoadBalancer>> loadBalancers = new ConcurrentHashMap<>();
    private Map<String, Map<String, TargetGroup>> targetGroups = new ConcurrentHashMap<>();
    private Map<String, Map<String, Listener>> listeners = new ConcurrentHashMap<>();
    private Map<String, Map<String, Rule>> rules = new ConcurrentHashMap<>();

    // indexes
    private final Map<String, List<String>> lbToListeners   = new ConcurrentHashMap<>(); // LB-ARN → listener ARNs
    private final Map<String, List<String>> listenerToRules  = new ConcurrentHashMap<>(); // Listener-ARN → rule ARNs
    private final Map<String, Set<String>>  tgToLbs          = new ConcurrentHashMap<>(); // TG-ARN → LB-ARNs

    // tags: resource-ARN → {key → value}
    private Map<String, Map<String, String>> tags = new ConcurrentHashMap<>();

    @PostConstruct
    void initializeStorage()
    {
        if (storageFactory == null) {
            return;
        }
        this.loadBalancers = storageBacked("elbv2-load-balancers.json",
                new TypeReference<Map<String, Map<String, LoadBalancer>>>() {});
        this.targetGroups = storageBacked("elbv2-target-groups.json",
                new TypeReference<Map<String, Map<String, TargetGroup>>>() {});
        this.listeners = storageBacked("elbv2-listeners.json",
                new TypeReference<Map<String, Map<String, Listener>>>() {});
        this.rules = storageBacked("elbv2-rules.json",
                new TypeReference<Map<String, Map<String, Rule>>>() {});
        this.tags = storageBacked("elbv2-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        normalizeRegionMaps(loadBalancers);
        normalizeRegionMaps(targetGroups);
        normalizeRegionMaps(listeners);
        normalizeRegionMaps(rules);
        normalizeRegionMaps(tags);
        rebuildIndexes();
    }

    private <V> Map<String, V> storageBacked(String fileName, TypeReference<Map<String, V>> typeReference)
    {
        return new StorageBackedMap<>(storageFactory.create("elbv2", fileName, typeReference));
    }

    private <V> void normalizeRegionMaps(Map<String, Map<String, V>> resources) {
        for (Map.Entry<String, Map<String, V>> entry : new ArrayList<>(resources.entrySet())) {
            if (!(entry.getValue() instanceof ConcurrentHashMap)) {
                resources.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
        }
    }

    /**
     * Drops one entry from an ARN index. The list has to be looked up rather than defaulted:
     * {@code getOrDefault(key, List.of())} hands back an immutable list whose {@code remove}
     * throws, which the Query path reports as an {@code InternalFailure}.
     */
    private static void removeFromIndex(Map<String, List<String>> index, String key, String value) {
        List<String> entries = index.get(key);
        if (entries != null) {
            entries.remove(value);
        }
    }

    private <V> void persistRegion(Map<String, Map<String, V>> resources, String region) {
        Map<String, V> regionResources = resources.get(region);
        if (regionResources != null) {
            resources.put(region, regionResources);
        }
    }

    /**
     * Rebuilds the in-memory indexes from the storage-backed maps. Pure and in-process on purpose:
     * nothing reachable from {@link #initializeStorage()} may call an injected collaborator, because
     * {@link ElbV2DataPlane} calls back into this service and CDI has not registered the instance in
     * the ApplicationScoped context yet — re-entering bean creation instead (issue #1913). Runtime
     * side effects belong in {@link #restorePersistedRuntime()}.
     */
    private void rebuildIndexes()
    {
        lbToListeners.clear();
        listenerToRules.clear();
        tgToLbs.clear();

        for (Map<String, Listener> regionListeners : listeners.values()) {
            for (Listener listener : regionListeners.values()) {
                lbToListeners.computeIfAbsent(listener.getLoadBalancerArn(), k -> new CopyOnWriteArrayList<>())
                        .add(listener.getListenerArn());
            }
        }
        for (Map<String, Rule> regionRules : rules.values()) {
            for (Rule rule : regionRules.values()) {
                listenerToRules.computeIfAbsent(rule.getListenerArn(), k -> new CopyOnWriteArrayList<>())
                        .add(rule.getRuleArn());
            }
        }
        for (Map.Entry<String, Map<String, TargetGroup>> regionEntry : targetGroups.entrySet()) {
            for (TargetGroup targetGroup : regionEntry.getValue().values()) {
                tgToLbs.computeIfAbsent(targetGroup.getTargetGroupArn(), k -> ConcurrentHashMap.newKeySet())
                        .addAll(targetGroup.getLoadBalancerArns());
            }
        }
    }

    /**
     * Brings the data plane and health checks back up for state restored from disk. Invoked from
     * {@code EmulatorLifecycle} once the bean is fully constructed, alongside the other services that
     * restore persisted runtime after {@code storageFactory.loadAll()}.
     */
    public void restorePersistedRuntime()
    {
        refreshCanonicalHostedZones();
        if (healthChecker != null) {
            for (Map<String, TargetGroup> regionTargetGroups : targetGroups.values()) {
                for (TargetGroup targetGroup : regionTargetGroups.values()) {
                    healthChecker.startMonitoring(targetGroup);
                    if (!targetGroup.getTargets().isEmpty()) {
                        healthChecker.addTargets(targetGroup.getTargetGroupArn(), targetGroup.getTargets(), targetGroup);
                    }
                }
            }
        }
        if (dataPlane != null) {
            for (Map.Entry<String, Map<String, Listener>> regionEntry : listeners.entrySet()) {
                String region = regionEntry.getKey();
                for (Listener listener : regionEntry.getValue().values()) {
                    try {
                        restoreListener(region, listener.getListenerArn());
                    } catch (RuntimeException failure) {
                        LOG.warnv("ELBv2 saved listener {0} could not be restored ({1}: {2}); its configuration is retained",
                                listener.getListenerArn(), failure.getClass().getSimpleName(), failure.getMessage());
                    }
                }
            }
        }
    }

    // ── Load Balancers ────────────────────────────────────────────────────────

    /**
     * The subnets a CreateLoadBalancer or SetSubnets request names: {@code Subnets}, or else the subnet
     * of each {@code SubnetMappings} entry. The two are alternatives, so a request that names subnets
     * both ways is refused with the error AWS gives; an empty one does not count.
     */
    public static List<String> requestedSubnets(List<String> subnets, List<String> mappingSubnetIds) {
        if (!subnets.isEmpty() && !mappingSubnetIds.isEmpty()) {
            throw new AwsException("ValidationError",
                    "You can specify either subnets or subnet mappings, not both", 400);
        }
        return mappingSubnetIds.isEmpty() ? subnets : mappingSubnetIds;
    }

    /**
     * Resolving the subnets and publishing the balancer happen under EC2's subnet lock, so a
     * DeleteSubnet cannot pass its dependency check in between and leave the balancer on a subnet
     * that is gone. A {@code Type} outside the model's enum is refused, as AWS refuses it, rather
     * than read as an application load balancer, which is not the one IAM checks the request for.
     */
    public LoadBalancer createLoadBalancer(String region, String name, String scheme,
                                           String type, String ipAddressType,
                                           List<String> subnets, List<String> securityGroups,
                                           Map<String, String> initialTags) {
        if (type != null && !LOAD_BALANCER_TYPES.contains(type)) {
            throw SmithyValidationErrors.validationError(SmithyValidationErrors.notInEnum(type, "type", LOAD_BALANCER_TYPES));
        }
        return ec2Service.supplyWithSubnetReferencesHeld(region, () -> createLoadBalancerWithSubnetsHeld(
                region, name, scheme, type, ipAddressType, subnets, securityGroups, initialTags));
    }

    private LoadBalancer createLoadBalancerWithSubnetsHeld(String region, String name, String scheme,
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
        String arn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "loadbalancer/" + typePrefix + "/" + name + "/" + id).toString();
        String dnsName = name + "-" + id + ".elb." + loadBalancerDnsSuffix();
        requireSubnets(lbType, subnets);
        String vpcId = resolveSubnetVpcId(region, lbType, subnets);

        LoadBalancer lb = new LoadBalancer();
        lb.setLoadBalancerArn(arn);
        lb.setDnsName(dnsName);
        lb.setCanonicalHostedZoneId(canonicalHostedZoneId(lbType, region));
        lb.setCreatedTime(Instant.now());
        lb.setLoadBalancerName(name);
        lb.setScheme(lbScheme);
        lb.setVpcId(vpcId);
        lb.setState("active");
        lb.setType(lbType);
        lb.setIpAddressType(ipType);
        lb.setRegion(region);
        lb.setAvailabilityZones(resolveAvailabilityZones(region, subnets));
        if (securityGroups != null) lb.setSecurityGroups(new ArrayList<>(securityGroups));

        regionLbs.put(arn, lb);
        loadBalancers.put(region, regionLbs);
        lbToListeners.put(arn, new ArrayList<>());
        if (!initialTags.isEmpty()) {
            tags.put(arn, new LinkedHashMap<>(initialTags));
        }
        return lb;
    }

    /**
     * Every load balancer type takes {@code Subnets} or {@code SubnetMappings}, and an Application Load
     * Balancer takes at least two, so a CreateLoadBalancer with fewer is refused before anything is
     * created, with the message AWS gives an Application or a Network Load Balancer. A Gateway Load
     * Balancer, which takes subnets the way a Network Load Balancer does, gets the Network one. AWS
     * counts before it looks a subnet up, so one subnet that does not exist gets this error too, not
     * SubnetNotFound. On create this runs after the duplicate-name check, because a CloudFormation stack
     * update finds the balancer it keeps through DuplicateLoadBalancerName, whatever subnets its
     * template names. SetSubnets takes the same subnets, so {@link #setSubnetsFromRequest} runs this on
     * a SetSubnets that names none, and {@link #setSubnets} on a list that names some, once it has the
     * balancer's type; whether the subnets span two Availability Zones is checked after they are looked
     * up. No public report shows what AWS answers a SetSubnets with one subnet, so it gets the create
     * message and order.
     */
    private static void requireSubnets(String lbType, List<String> subnets) {
        int count = subnets == null ? 0 : subnets.size();
        if ("application".equals(lbType) && count < 2) {
            throw new AwsException("ValidationError",
                    "At least two subnets in two different Availability Zones must be specified", 400);
        }
        if (count == 0) {
            throw new AwsException("ValidationError", "At least one subnet must be specified", 400);
        }
    }

    /**
     * A subnet is in use while a load balancer is configured with it. Account-scoped through the
     * request-scoped storage, and a target group does not count: it pins nothing in AWS either.
     */
    @Override
    public boolean hasSubnetDependency(String region, String subnetId) {
        return loadBalancers.getOrDefault(region, Map.of()).values().stream()
                .anyMatch(lb -> lb.getAvailabilityZones().stream()
                        .anyMatch(zone -> subnetId.equals(zone.getSubnetId())));
    }

    @Override
    public boolean hasVpcDependency(String region, String vpcId) {
        return loadBalancers.getOrDefault(region, Map.of()).values().stream()
                .anyMatch(lb -> vpcId.equals(lb.getVpcId()));
    }

    private String loadBalancerDnsSuffix() {
        if (config == null) {
            return EmbeddedDnsServer.DEFAULT_SUFFIX;
        }
        return config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
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
        Map<String, LoadBalancer> regionLbs = loadBalancers.get(region);
        if (regionLbs == null) {
            return; // AWS silently ignores non-existent LBs on delete
        }
        LoadBalancer lb = regionLbs.get(arn);
        if (lb == null) {
            return;
        }
        List<String> listenerArns;
        synchronized (lb) {
            if (!regionLbs.remove(arn, lb)) {
                return;
            }
            listenerArns = listeners.getOrDefault(region, Map.of()).values().stream()
                    .filter(listener -> arn.equals(listener.getLoadBalancerArn()))
                    .map(Listener::getListenerArn).toList();
            lbToListeners.remove(arn);
        }
        for (String listenerArn : listenerArns) {
            deleteListener(region, listenerArn);
        }
        // remove from TG index
        tgToLbs.values().forEach(lbSet -> lbSet.remove(arn));
        tags.remove(arn);
        loadBalancers.put(region, regionLbs);
    }

    public Map<String, String> describeLoadBalancerAttributes(String region, String arn) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        return ElbV2DefaultAttributes.merge(ElbV2DefaultAttributes.forLoadBalancer(lb), lb.getAttributes());
    }

    public void modifyLoadBalancerAttributes(String region, String arn, Map<String, String> newAttrs) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.getAttributes().putAll(newAttrs);
        persistRegion(loadBalancers, region);
    }

    LoadBalancer getLoadBalancer(String region, String arn) {
        return loadBalancers.getOrDefault(region, Map.of()).get(arn);
    }

    /** Capacity reservation status for a load balancer. All fields are {@code null} when no
     *  capacity is reserved, which is always the case in Floci (capacity cannot be reserved). */
    public record CapacityReservation(Integer decreaseRequestsRemaining,
                                      Integer minimumCapacityUnits,
                                      Instant lastModifiedTime) {}

    public CapacityReservation describeCapacityReservation(String region, String arn) {
        requireLoadBalancer(region, arn);
        // Floci does not reserve load balancer capacity, so there is never a reservation to report.
        return new CapacityReservation(null, null, null);
    }

    public void setSecurityGroups(String region, String arn, List<String> sgIds) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.setSecurityGroups(new ArrayList<>(sgIds));
        persistRegion(loadBalancers, region);
    }

    /**
     * The SetSubnets action, for the Query handler. A request with neither {@code Subnets} nor
     * {@code SubnetMappings} is refused and the balancer stays on its subnets, with the error
     * CreateLoadBalancer gives a balancer of its type with neither; AWS gives a Network Load Balancer
     * that error for SetSubnets too. The balancer is looked up first, as the message depends on its
     * type. Only an empty list is refused here; a request that names subnets is checked by
     * {@link #setSubnets}.
     */
    void setSubnetsFromRequest(String region, String arn, List<String> subnets) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        if (subnets.isEmpty()) {
            requireSubnets(lb.getType(), subnets);
        }
        setSubnets(region, arn, subnets);
    }

    /**
     * Under EC2's subnet lock for the same reason as {@link #createLoadBalancer}. Unlike the SetSubnets
     * action, this takes an empty list: CloudFormation moves balancers through it, and its rollback
     * moves a balancer an earlier Floci version saved without subnets back onto none.
     */
    public void setSubnets(String region, String arn, List<String> subnets) {
        ec2Service.runWithSubnetReferencesHeld(region, () -> {
            LoadBalancer lb = requireLoadBalancer(region, arn);
            // Counted as on create, before any subnet is looked up; an empty list is taken, as above.
            if (subnets != null && !subnets.isEmpty()) {
                requireSubnets(lb.getType(), subnets);
            }
            String vpcId = resolveSubnetVpcId(region, lb.getType(), subnets);
            if (vpcId != null && lb.getVpcId() != null && !lb.getVpcId().equals(vpcId)) {
                throw new AwsException("InvalidConfigurationRequest",
                        "All subnets must belong to the load balancer VPC.", 400);
            }
            lb.setAvailabilityZones(resolveAvailabilityZones(region, subnets));
            if (vpcId != null) {
                lb.setVpcId(vpcId);
            }
            persistRegion(loadBalancers, region);
        });
    }

    public void setIpAddressType(String region, String arn, String ipAddressType) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.setIpAddressType(ipAddressType);
        persistRegion(loadBalancers, region);
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
        validatePort(port, "port");
        validateName(name, "target group");
        if (port == null && !"lambda".equals(targetType)) {
            throw new AwsException("ValidationError", "A port must be specified", 400);
        }
        validateHealthCheckPort(healthCheckPort);
        Map<String, TargetGroup> regionTgs = targetGroups.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
        boolean duplicate = regionTgs.values().stream()
                .anyMatch(tg -> tg.getTargetGroupName().equals(name));
        if (duplicate) {
            throw new AwsException("DuplicateTargetGroupName",
                    "A target group with name '" + name + "' already exists.", 400);
        }

        String id = randomHex16();
        String arn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "targetgroup/" + name + "/" + id).toString();

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
        targetGroups.put(region, regionTgs);
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
            if (result.size() != arnSet.size()) {
                throw new AwsException("TargetGroupNotFound", "One or more target groups not found.", 400);
            }
        }
        if (names != null && !names.isEmpty()) {
            Set<String> nameSet = new HashSet<>(names);
            result = result.stream().filter(tg -> nameSet.contains(tg.getTargetGroupName())).collect(Collectors.toList());
            if (result.size() != nameSet.size()) {
                throw new AwsException("TargetGroupNotFound", "One or more target groups not found.", 400);
            }
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
        persistRegion(targetGroups, region);
        tgToLbs.remove(arn);
        tags.remove(arn);
    }

    public void modifyTargetGroup(String region, String arn, String healthCheckProtocol,
                                   String healthCheckPort, Boolean healthCheckEnabled,
                                   String healthCheckPath, Integer healthCheckInterval,
                                   Integer healthCheckTimeout, Integer healthyThreshold,
                                   Integer unhealthyThreshold, String matcher) {
        TargetGroup tg = requireTargetGroup(region, arn);
        validateHealthCheckPort(healthCheckPort);
        if (healthCheckProtocol != null) tg.setHealthCheckProtocol(healthCheckProtocol);
        if (healthCheckPort != null)     tg.setHealthCheckPort(healthCheckPort);
        if (healthCheckEnabled != null)  tg.setHealthCheckEnabled(healthCheckEnabled);
        if (healthCheckPath != null)     tg.setHealthCheckPath(healthCheckPath);
        if (healthCheckInterval != null) tg.setHealthCheckIntervalSeconds(healthCheckInterval);
        if (healthCheckTimeout != null)  tg.setHealthCheckTimeoutSeconds(healthCheckTimeout);
        if (healthyThreshold != null)    tg.setHealthyThresholdCount(healthyThreshold);
        if (unhealthyThreshold != null)  tg.setUnhealthyThresholdCount(unhealthyThreshold);
        if (matcher != null)             tg.setMatcher(matcher);
        persistRegion(targetGroups, region);
    }

    public Map<String, String> describeTargetGroupAttributes(String region, String arn) {
        TargetGroup tg = requireTargetGroup(region, arn);
        return ElbV2DefaultAttributes.merge(ElbV2DefaultAttributes.forTargetGroup(tg), tg.getAttributes());
    }

    public void modifyTargetGroupAttributes(String region, String arn, Map<String, String> newAttrs) {
        TargetGroup tg = requireTargetGroup(region, arn);
        tg.getAttributes().putAll(newAttrs);
        persistRegion(targetGroups, region);
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    public Listener createListener(String region, String lbArn, String protocol, Integer port,
                                    String sslPolicy, List<String> certificates,
                                    List<Action> defaultActions, List<String> alpnPolicy,
                                    Map<String, String> initialTags) {
        validatePort(port, "port");
        requireGatewayListenerSettings(requireLoadBalancer(region, lbArn), protocol, port);

        Map<String, Listener> regionListeners = listeners.computeIfAbsent(region, k -> new ConcurrentHashMap<>());

        // check duplicate port on same LB
        boolean portExists = regionListeners.values().stream()
                .filter(l -> l.getLoadBalancerArn().equals(lbArn))
                .anyMatch(l -> Objects.equals(l.getPort(), port));
        if (portExists) {
            throw new AwsException("DuplicateListener",
                    "A listener already exists on port " + port + " for this load balancer.", 400);
        }

        // Fail fast, then again under the guard the listener is published inside: this one keeps
        // the error precedence, and only the one holding the lock is atomic with the write.
        requireListenerCertificates(region, protocol, certificates);

        LoadBalancer lb = requireLoadBalancer(region, lbArn);
        String lbType = lb.getType() != null ? lb.getType() : "application";
        String typePrefix = lbTypePrefix(lbType);
        String lbId = arnId(lbArn);
        String listenerId = randomHex16();
        String listenerArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "listener/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId).toString();

        Listener listener = new Listener();
        listener.setListenerArn(listenerArn);
        listener.setLoadBalancerArn(lbArn);
        listener.setPort(port);
        listener.setProtocol(protocol != null ? protocol : ("gateway".equals(lbType) ? "GENEVE" : "HTTP"));
        listener.setSslPolicy(sslPolicy);
        listener.setCertificates(certificates != null ? new ArrayList<>(certificates) : new ArrayList<>());
        listener.setDefaultActions(defaultActions != null ? new ArrayList<>(defaultActions) : new ArrayList<>());
        listener.setAlpnPolicy(alpnPolicy != null ? new ArrayList<>(alpnPolicy) : new ArrayList<>());

        Rule defaultRule = buildDefaultRule(region, listenerArn, lb, lbId, listenerId, defaultActions);
        while (true) {
            requireListenerCertificates(region, protocol, certificates);
            requireCompatibleListenerProtocol(listenerArn, port, listener.getProtocol());
            boolean applied = publishListener(listener, region, port, () -> {
                synchronized (lb) {
                    requireCanonicalLoadBalancer(region, lb);
                    requireAvailableListenerPort(regionListeners, lbArn, listenerArn, port);
                    regionListeners.put(listenerArn, listener);
                    listeners.put(region, regionListeners);
                    lbToListeners.computeIfAbsent(lbArn, ignored -> new CopyOnWriteArrayList<>()).add(listenerArn);
                    Map<String, Rule> regionRules = rules.computeIfAbsent(region, ignored -> new ConcurrentHashMap<>());
                    regionRules.put(defaultRule.getRuleArn(), defaultRule);
                    rules.put(region, regionRules);
                    listenerToRules.computeIfAbsent(listenerArn, ignored -> new CopyOnWriteArrayList<>())
                            .add(defaultRule.getRuleArn());
                    for (Action action : defaultRule.getActions()) {
                        linkTgToLb(action, lbArn);
                    }
                    if (!initialTags.isEmpty()) {
                        tags.put(listenerArn, new LinkedHashMap<>(initialTags));
                    }
                    return true;
                }
            }, operation -> supplyCertificatesHeld(certificates, () -> {
                requireListenerCertificates(region, protocol, certificates);
                return operation.get();
            }));
            if (applied) {
                break;
            }
        }
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

    public Map<String, String> describeListenerAttributes(String region, String arn) {
        Listener listener = requireListener(region, arn);
        return new LinkedHashMap<>(listener.getAttributes());
    }

    public void modifyListenerAttributes(String region, String arn, Map<String, String> newAttrs) {
        Listener listener = requireListener(region, arn);
        listener.getAttributes().putAll(newAttrs);
        persistRegion(listeners, region);
    }

    public void deleteListener(String region, String listenerArn) {
        while (true) {
            Map<String, Listener> regionListeners = listeners.get(region);
            Listener listener = regionListeners == null ? null : regionListeners.get(listenerArn);
            if (listener == null) {
                return;
            }
            ListenerState expected = listenerState(listener);
            LoadBalancer lb = loadBalancers.getOrDefault(region, Map.of()).get(listener.getLoadBalancerArn());
            BooleanSupplier removal = () -> {
                if (!isCurrentListener(region, expected)) {
                    return false;
                }
                regionListeners.remove(listenerArn, listener);
                removeFromIndex(lbToListeners, listener.getLoadBalancerArn(), listenerArn);
                Map<String, Rule> regionRules = rules.computeIfAbsent(region, ignored -> new ConcurrentHashMap<>());
                List<String> ruleArns = listenerToRules.remove(listenerArn);
                if (ruleArns != null) {
                    ruleArns.forEach(regionRules::remove);
                }
                listeners.put(region, regionListeners);
                rules.put(region, regionRules);
                tags.remove(listenerArn);
                unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
                return true;
            };
            boolean removed = dataPlane.removeListener(listenerArn, expected.port(), () -> {
                if (lb == null) {
                    return removal.getAsBoolean();
                }
                synchronized (lb) {
                    return removal.getAsBoolean();
                }
            });
            if (removed) {
                return;
            }
        }
    }

    public Listener modifyListener(String region, String listenerArn, String protocol, Integer port,
                                    String sslPolicy, List<String> certificates,
                                    List<Action> defaultActions, List<String> alpnPolicy) {
        validatePort(port, "port");
        while (true) {
            Listener initial = requireListener(region, listenerArn);
            LoadBalancer initialLb = requireLoadBalancer(region, initial.getLoadBalancerArn());
            requireGatewayListenerSettings(initialLb, protocol, port);
            ListenerState initialState = listenerState(initial, initialLb);
            requireAvailableListenerPort(listeners.getOrDefault(region, Map.of()), initial.getLoadBalancerArn(),
                    listenerArn, port != null ? port : initialState.port());
            if (validateExistingListener(region, initialState, initialLb, () ->
                    requireListenerCertificates(region, protocol != null ? protocol : initialState.protocol(),
                            certificates != null ? certificates : initialState.certificates()))) {
                break;
            }
        }
        while (true) {
            Listener listener = requireListener(region, listenerArn);
            LoadBalancer lb = requireLoadBalancer(region, listener.getLoadBalancerArn());
            ListenerState expected = listenerState(listener, lb);
            Listener prospective = prospectiveListener(expected, protocol, port, certificates);
            requireAvailableListenerPort(listeners.getOrDefault(region, Map.of()), listener.getLoadBalancerArn(),
                    listenerArn, prospective.getPort());
            if (!validateExistingListener(region, expected, lb, () -> {
                requireListenerCertificates(region, prospective.getProtocol(), prospective.getCertificates());
                requireCompatibleListenerProtocol(listenerArn, prospective.getPort(), prospective.getProtocol());
            })) {
                continue;
            }
            BooleanSupplier publication = () -> {
                synchronized (lb) {
                    requireCanonicalLoadBalancer(region, lb);
                    if (!isCurrentListener(region, expected)) {
                        return false;
                    }
                    requireAvailableListenerPort(listeners.getOrDefault(region, Map.of()),
                            listener.getLoadBalancerArn(), listenerArn, prospective.getPort());
                    if (port != null) {
                        listener.setPort(port);
                    }
                    if (protocol != null) {
                        listener.setProtocol(protocol);
                    }
                    if (sslPolicy != null) {
                        listener.setSslPolicy(sslPolicy);
                    }
                    if (certificates != null) {
                        listener.setCertificates(new ArrayList<>(certificates));
                    }
                    if (alpnPolicy != null) {
                        listener.setAlpnPolicy(new ArrayList<>(alpnPolicy));
                    }
                    if (defaultActions != null) {
                        listener.setDefaultActions(new ArrayList<>(defaultActions));
                        listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                                .map(arn -> rules.getOrDefault(region, Map.of()).get(arn))
                                .filter(rule -> rule != null && rule.isDefault())
                                .forEach(rule -> rule.setActions(new ArrayList<>(defaultActions)));
                        for (Action action : defaultActions) {
                            linkTgToLb(action, listener.getLoadBalancerArn());
                        }
                        unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
                    }
                    persistRegion(listeners, region);
                    persistRegion(rules, region);
                    return true;
                }
            };
            boolean restart = port != null || protocol != null || certificates != null;
            boolean applied = restart
                    ? publishExistingListener(prospective, region, expected, lb,
                            () -> requireCertificates(region, prospective.getProtocol(),
                                    certificates != null ? certificates : prospective.getCertificates()), publication)
                    : supplyCertificatesHeld(prospective.getCertificates(), () -> {
                        synchronized (lb) {
                            if (!isCurrentListener(region, expected)) {
                                return false;
                            }
                        }
                        requireCertificates(region, prospective.getProtocol(), prospective.getCertificates());
                        requirePublishedCertificates(region, prospective.getProtocol(), prospective.getCertificates());
                        return dataPlane.updateListener(expected.port(), publication);
                    });
            if (applied) {
                break;
            }
        }
        if (defaultActions != null) {
            dataPlane.recompileRules(listenerArn, () -> getListenerRules(region, listenerArn));
        }
        return requireListener(region, listenerArn);
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
        String ruleArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "listener-rule/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId + "/" + ruleId).toString();

        Rule rule = new Rule();
        rule.setRuleArn(ruleArn);
        rule.setListenerArn(listenerArn);
        rule.setPriority(priorityStr);
        rule.setConditions(conditions != null ? new ArrayList<>(conditions) : new ArrayList<>());
        rule.setActions(actions != null ? new ArrayList<>(actions) : new ArrayList<>());
        rule.setDefault(false);

        regionRules.put(ruleArn, rule);
        rules.put(region, regionRules);
        listenerToRules.computeIfAbsent(listenerArn, k -> new CopyOnWriteArrayList<>()).add(ruleArn);

        // update TG → LB index for all target group actions
        for (Action a : rule.getActions()) {
            linkTgToLb(a, listener.getLoadBalancerArn());
        }

        if (!initialTags.isEmpty()) {
            tags.put(ruleArn, new LinkedHashMap<>(initialTags));
        }
        dataPlane.recompileRules(listenerArn, () -> getListenerRules(region, listenerArn));
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
        rules.put(region, regionRules);
        removeFromIndex(listenerToRules, listenerArn, ruleArn);
        tags.remove(ruleArn);
        Listener listener = listeners.getOrDefault(region, Map.of()).get(listenerArn);
        if (listener != null) {
            unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
        }
        dataPlane.recompileRules(listenerArn, () -> getListenerRules(region, listenerArn));
    }

    public Rule modifyRule(String region, String ruleArn, List<RuleCondition> conditions, List<Action> actions) {
        Rule rule = requireRule(region, ruleArn);
        String listenerArn = rule.getListenerArn();
        if (conditions != null) rule.setConditions(new ArrayList<>(conditions));
        if (actions != null)    rule.setActions(new ArrayList<>(actions));
        persistRegion(rules, region);
        dataPlane.recompileRules(listenerArn, () -> getListenerRules(region, listenerArn));
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

        // priorities are unique per listener, so collisions are only checked among rules of the same listener
        Map<String, Set<Integer>> newPrioritiesByListener = new HashMap<>();
        for (Map.Entry<String, Integer> e : arnToPriority.entrySet()) {
            String listenerArn = regionRules.get(e.getKey()).getListenerArn();
            if (!newPrioritiesByListener.computeIfAbsent(listenerArn, k -> new HashSet<>()).add(e.getValue())) {
                throw new AwsException("PriorityInUse",
                        "Priority " + e.getValue() + " is already in use.", 400);
            }
        }
        // iterate the concurrent region map, not the listener index lists, which CreateRule/DeleteRule mutate
        for (Rule existing : regionRules.values()) {
            if (existing.isDefault() || arnToPriority.containsKey(existing.getRuleArn())) {
                continue;
            }
            Set<Integer> newPriorities = newPrioritiesByListener.get(existing.getListenerArn());
            if (newPriorities == null) {
                continue;
            }
            int existingPriority = Integer.parseInt(existing.getPriority());
            if (newPriorities.contains(existingPriority)) {
                throw new AwsException("PriorityInUse",
                        "Priority " + existingPriority + " is already in use.", 400);
            }
        }

        // commit
        arnToPriority.forEach((arn, priority) -> regionRules.get(arn).setPriority(String.valueOf(priority)));
        rules.put(region, regionRules);

        Set<String> affectedListeners = arnToPriority.keySet().stream()
                .map(arn -> regionRules.get(arn).getListenerArn())
                .collect(Collectors.toSet());
        affectedListeners.forEach(la -> dataPlane.recompileRules(la, () -> getListenerRules(region, la)));
    }

    // ── Targets ───────────────────────────────────────────────────────────────

    public void registerTargets(String region, String tgArn, List<TargetDescription> targets) {
        validateTargetPorts(targets);
        TargetGroup tg = requireTargetGroup(region, tgArn);
        validateLambdaTargetPorts(tg, targets);
        if (!"lambda".equals(tg.getTargetType())) {
            for (TargetDescription t : targets) {
                requireNotMetadataAddress(t.getId());
            }
        }
        List<TargetDescription> existing = tg.getTargets();
        for (TargetDescription t : targets) {
            if (!"lambda".equals(tg.getTargetType()) && t.getPort() == null && tg.getPort() != null) {
                t.setPort(tg.getPort());
            }
            // replace if same id+port already registered
            existing.removeIf(e -> e.getId().equals(t.getId())
                    && ElbV2HealthChecker.effectivePort(e, tg) == ElbV2HealthChecker.effectivePort(t, tg));
            existing.add(t);
        }
        persistRegion(targetGroups, region);
        healthChecker.addTargets(tgArn, targets, tg);
    }

    private static void requireNotMetadataAddress(String targetId) {
        if (!ElbV2TargetResolver.isIpLiteral(targetId)) {
            return;
        }
        try {
            SsrfProtection.rejectMetadataAddresses(InetAddress.getAllByName(targetId), targetId);
        } catch (IOException e) {
            throw new AwsException("InvalidTarget",
                    "The IP address '" + targetId + "' is not a valid target", 400);
        }
    }

    public void deregisterTargets(String region, String tgArn, List<TargetDescription> targets) {
        validateTargetPorts(targets);
        TargetGroup tg = requireTargetGroup(region, tgArn);
        validateLambdaTargetPorts(tg, targets);
        for (TargetDescription t : targets) {
            tg.getTargets().removeIf(e -> e.getId().equals(t.getId())
                    && ElbV2HealthChecker.effectivePort(e, tg) == ElbV2HealthChecker.effectivePort(t, tg));
        }
        persistRegion(targetGroups, region);
        healthChecker.removeTargets(tgArn, targets, tg);
    }

    public List<TargetHealth> describeTargetHealth(String region, String tgArn,
                                                     List<TargetDescription> filterTargets) {
        validateTargetPorts(filterTargets);
        TargetGroup tg = requireTargetGroup(region, tgArn);
        validateLambdaTargetPorts(tg, filterTargets);
        boolean hasFilterTargets = filterTargets != null && !filterTargets.isEmpty();
        List<TargetDescription> candidates = hasFilterTargets ? filterTargets : tg.getTargets();

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
            if (hasFilterTargets && !isRegisteredTarget(tg, t, port)) {
                th.setState("unused");
                th.setReason("Target.NotRegistered");
                th.setDescription("Target is not registered to the target group");
                return th;
            }
            ElbV2HealthChecker.TargetHealthStatus health = healthChecker.getHealth(tgArn, t.getId(), port);
            th.setState(health.state());
            th.setReason(health.reason());
            th.setDescription(health.description());
            return th;
        }).collect(Collectors.toList());
    }

    private static boolean isRegisteredTarget(TargetGroup targetGroup, TargetDescription candidate, int candidatePort) {
        return targetGroup.getTargets().stream()
                .anyMatch(registered -> Objects.equals(registered.getId(), candidate.getId())
                        && ElbV2HealthChecker.effectivePort(registered, targetGroup) == candidatePort);
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public void addTags(List<String> resourceArns, Map<String, String> newTags) {
        for (String arn : resourceArns) {
            tags.computeIfAbsent(arn, k -> new LinkedHashMap<>()).putAll(newTags);
            tags.put(arn, tags.get(arn));
        }
    }

    public void removeTags(List<String> resourceArns, List<String> tagKeys) {
        for (String arn : resourceArns) {
            Map<String, String> resourceTags = tags.get(arn);
            if (resourceTags != null) {
                tagKeys.forEach(resourceTags::remove);
                tags.put(arn, resourceTags);
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

    @Override
    public List<Reference> serverCertificateReferences() {
        List<Reference> references = new ArrayList<>();
        for (Map<String, Listener> regionListeners : listeners.values()) {
            for (Listener listener : regionListeners.values()) {
                for (String certificate : listener.getCertificates()) {
                    references.add(new Reference(certificate, "listener " + listener.getListenerArn()));
                }
            }
        }
        return references;
    }

    public void addListenerCertificates(String region, String listenerArn, List<String> certArns) {
        Listener initial = requireListener(region, listenerArn);
        requireCertificates(region, initial.getProtocol(), certArns);
        changeListenerCertificates(region, listenerArn, certArns, false);
    }

    public void removeListenerCertificates(String region, String listenerArn, List<String> certArns) {
        changeListenerCertificates(region, listenerArn, certArns, true);
    }

    private void changeListenerCertificates(String region, String listenerArn, List<String> requested, boolean remove) {
        while (true) {
            Listener listener = requireListener(region, listenerArn);
            LoadBalancer lb = requireLoadBalancer(region, listener.getLoadBalancerArn());
            ListenerState expected = listenerState(listener, lb);
            List<String> certificates = new ArrayList<>(expected.certificates());
            if (remove) {
                if (servesCertificates(expected.protocol()) && !certificates.isEmpty()
                        && requested.contains(certificates.getFirst())) {
                    throw new AwsException("OperationNotPermitted",
                            "The default listener certificate cannot be removed.", 400);
                }
                certificates.removeAll(requested);
            } else {
                if (!validateExistingListener(region, expected, lb,
                        () -> requireCertificates(region, expected.protocol(), requested))) {
                    continue;
                }
                for (String arn : requested) {
                    if (!certificates.contains(arn)) {
                        certificates.add(arn);
                    }
                }
            }
            Listener prospective = prospectiveListener(expected, null, null, certificates);
            if (publishExistingListener(prospective, region, expected, lb, () -> {
                if (!remove) {
                    requireCertificates(region, expected.protocol(), requested);
                }
            }, () -> {
                synchronized (lb) {
                    requireCanonicalLoadBalancer(region, lb);
                    if (!isCurrentListener(region, expected)) {
                        return false;
                    }
                    listener.setCertificates(certificates);
                    persistRegion(listeners, region);
                    return true;
                }
            })) {
                return;
            }
        }
    }

    public List<String> describeListenerCertificates(String region, String listenerArn) {
        Listener listener = requireListener(region, listenerArn);
        return new ArrayList<>(listener.getCertificates());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record ListenerState(Listener listener, Integer port, String protocol, List<String> certificates) {}

    private static ListenerState listenerState(Listener listener) {
        return new ListenerState(listener, listener.getPort(), listener.getProtocol(),
                new ArrayList<>(listener.getCertificates()));
    }

    private static ListenerState listenerState(Listener listener, LoadBalancer lb) {
        synchronized (lb) {
            return listenerState(listener);
        }
    }

    private boolean isCurrentListener(String region, ListenerState expected) {
        Listener current = listeners.getOrDefault(region, Map.of()).get(expected.listener().getListenerArn());
        return current == expected.listener() && Objects.equals(current.getPort(), expected.port())
                && Objects.equals(current.getProtocol(), expected.protocol())
                && current.getCertificates().equals(expected.certificates());
    }

    private static Listener prospectiveListener(ListenerState expected, String protocol, Integer port,
                                                List<String> certificates) {
        Listener prospective = new Listener();
        prospective.setListenerArn(expected.listener().getListenerArn());
        prospective.setLoadBalancerArn(expected.listener().getLoadBalancerArn());
        prospective.setPort(port != null ? port : expected.port());
        prospective.setProtocol(protocol != null ? protocol : expected.protocol());
        prospective.setCertificates(new ArrayList<>(certificates != null ? certificates : expected.certificates()));
        return prospective;
    }

    private void requireCanonicalLoadBalancer(String region, LoadBalancer lb) {
        if (loadBalancers.getOrDefault(region, Map.of()).get(lb.getLoadBalancerArn()) != lb) {
            throw new AwsException("LoadBalancerNotFound", "One or more load balancers not found.", 400);
        }
    }

    private static void requireAvailableListenerPort(Map<String, Listener> regionListeners, String lbArn,
                                                     String listenerArn, Integer port) {
        if (regionListeners.values().stream().anyMatch(listener -> lbArn.equals(listener.getLoadBalancerArn())
                && !listenerArn.equals(listener.getListenerArn()) && Objects.equals(listener.getPort(), port))) {
            throw new AwsException("DuplicateListener",
                    "A listener already exists on port " + port + " for this load balancer.", 400);
        }
    }

    private static void requireGatewayListenerSettings(LoadBalancer loadBalancer, String protocol, Integer port) {
        if (port != null && "gateway".equals(loadBalancer.getType())) {
            throw new AwsException("InvalidConfigurationRequest",
                    "You can't specify a port for a Gateway Load Balancer.", 400);
        }
        if (protocol != null && "gateway".equals(loadBalancer.getType())) {
            throw new AwsException("InvalidConfigurationRequest",
                    "You can't specify a protocol for a Gateway Load Balancer.", 400);
        }
    }

    private void requireCompatibleListenerProtocol(String listenerArn, Integer port, String protocol) {
        if (port == null) {
            return;
        }
        try {
            dataPlane.requireCompatibleListenerProtocol(listenerArn, port, protocol);
        } catch (ElbV2DataPlane.IncompatibleListenerProtocolException conflict) {
            throw new ListenerProtocolConflict(conflict);
        }
    }

    private boolean publishListener(Listener prospective, String region, Integer previousPort,
                                    BooleanSupplier publication, Function<Supplier<Boolean>, Boolean> admission) {
        try {
            return dataPlane.publishListener(prospective, region, previousPort, publication,
                    () -> getListenerRules(region, prospective.getListenerArn()), admission);
        } catch (ElbV2DataPlane.IncompatibleListenerProtocolException conflict) {
            throw new ListenerProtocolConflict(conflict);
        }
    }

    private static final class ListenerProtocolConflict extends AwsException {
        private ListenerProtocolConflict(ElbV2DataPlane.IncompatibleListenerProtocolException conflict) {
            super("InvalidConfigurationRequest", conflict.getMessage(), 400);
        }
    }

    private boolean staleListenerPreparation(String region, ListenerState expected, LoadBalancer lb,
                                             AwsException failure) {
        if (!(failure instanceof ListenerProtocolConflict) && !"CertificateNotFound".equals(failure.getErrorCode())) {
            return false;
        }
        synchronized (lb) {
            return !isCurrentListener(region, expected)
                    || loadBalancers.getOrDefault(region, Map.of()).get(lb.getLoadBalancerArn()) != lb;
        }
    }

    private boolean validateExistingListener(String region, ListenerState expected, LoadBalancer lb,
                                             Runnable validation) {
        try {
            validation.run();
            return true;
        } catch (AwsException failure) {
            if (staleListenerPreparation(region, expected, lb, failure)) {
                return false;
            }
            throw failure;
        }
    }

    private boolean publishExistingListener(Listener prospective, String region, ListenerState expected,
                                            LoadBalancer lb, Runnable referenceValidation, BooleanSupplier publication) {
        try {
            return publishListener(prospective, region, expected.port(), publication,
                    operation -> supplyCertificatesHeld(prospective.getCertificates(), () -> {
                        synchronized (lb) {
                            if (!isCurrentListener(region, expected)
                                    || loadBalancers.getOrDefault(region, Map.of()).get(lb.getLoadBalancerArn()) != lb) {
                                return false;
                            }
                        }
                        referenceValidation.run();
                        requirePublishedCertificates(region, prospective.getProtocol(), prospective.getCertificates());
                        return operation.get();
                    }));
        } catch (AwsException failure) {
            if (staleListenerPreparation(region, expected, lb, failure)) {
                return false;
            }
            throw failure;
        }
    }

    private void restoreListener(String region, String listenerArn) {
        while (true) {
            Listener listener = listeners.getOrDefault(region, Map.of()).get(listenerArn);
            if (listener == null) {
                return;
            }
            LoadBalancer lb = loadBalancers.getOrDefault(region, Map.of()).get(listener.getLoadBalancerArn());
            if (lb == null) {
                return;
            }
            ListenerState expected = listenerState(listener, lb);
            if (publishExistingListener(prospectiveListener(expected, null, null, null), region, expected, lb,
                    () -> { }, () -> {
                synchronized (lb) {
                    return loadBalancers.getOrDefault(region, Map.of()).get(lb.getLoadBalancerArn()) == lb
                            && isCurrentListener(region, expected);
                }
            })) {
                return;
            }
        }
    }

    private void requirePublishedCertificates(String region, String protocol, List<String> certificateArns) {
        if (certificateResolver != null && certificateArns != null && servesCertificates(protocol)) {
            for (String arn : certificateArns) {
                if (arn != null && AwsArnUtils.isArn(arn) && "acm".equals(AwsArnUtils.parse(arn).service())
                        && !certificateResolver.isUsableAcmCertificate(region, arn)) {
                    throw new AwsException("CertificateNotFound", "The specified certificate is not available for use.", 400);
                }
            }
        }
        if ("HTTPS".equalsIgnoreCase(protocol) && (certificateArns == null || certificateArns.isEmpty())) {
            throw new AwsException("InvalidConfigurationRequest",
                    "An HTTPS listener requires a default certificate.", 400);
        }
    }

    /**
     * Refuses a certificate the account does not have. {@code CertificateNotFound} is modeled on
     * {@code CreateListener}, {@code ModifyListener} and {@code AddListenerCertificates} alone,
     * and the reference gives it one sentence, so that sentence is the message rather than one
     * naming the ARN.
     *
     * <p>Only for the listener types the member belongs to. The reference marks {@code
     * Certificates} "[HTTPS and TLS listeners]" and the user guide scopes the certificate list to
     * an HTTPS listener, so a certificate on a listener of any other protocol is a configuration
     * AWS does not describe, and what it answers there is not {@code CertificateNotFound} on any
     * evidence here. Such a request keeps the behaviour it had rather than being refused with a
     * code read off the wrong case.
     *
     * <p>A blank entry is refused like any other. {@code Certificates} is a list of the
     * certificates the listener serves, and an empty value in it names one no better than a value
     * that is no ARN at all, so letting it through would store a certificate the listener cannot
     * serve.
     */
    private void requireCertificates(String region, String protocol,
                                     List<String> certificateArns) {
        if (certificateResolver == null || certificateArns == null || !servesCertificates(protocol)) {
            return;
        }
        for (String certificateArn : certificateArns) {
            if (certificateArn == null || certificateArn.isBlank()
                    || !certificateResolver.exists(region, certificateArn)) {
                throw new AwsException("CertificateNotFound",
                        "The specified certificate does not exist.", 400);
            }
        }
    }

    private void requireListenerCertificates(String region, String protocol, List<String> certificateArns) {
        requireCertificates(region, protocol, certificateArns);
        if ("HTTPS".equalsIgnoreCase(protocol) && (certificateArns == null || certificateArns.isEmpty())) {
            throw new AwsException("InvalidConfigurationRequest",
                    "An HTTPS listener requires a default certificate.", 400);
        }
        requirePublishedCertificates(region, protocol, certificateArns);
    }

    /** The listener protocols a certificate belongs to, per the reference's own bracket. */
    private static boolean servesCertificates(String protocol) {
        return "HTTPS".equalsIgnoreCase(protocol) || "TLS".equalsIgnoreCase(protocol);
    }

    private <T> T supplyCertificatesHeld(List<String> certificateArns, Supplier<T> action) {
        return certificateResolver == null ? action.get() : certificateResolver.supplyWhileHeld(certificateArns, action);
    }

    public List<CertificateReference> acmCertificateReferences() {
        List<CertificateReference> references = new ArrayList<>();
        for (Map<String, Listener> regionListeners : listeners.values()) {
            for (Listener listener : regionListeners.values()) {
                for (String certificateArn : listener.getCertificates()) {
                    references.add(new CertificateReference(certificateArn, listener.getLoadBalancerArn()));
                }
            }
        }
        return references;
    }

    public void certificateReimported(String certificateArn, String region) {
        for (Listener listener : listeners.getOrDefault(region, Map.of()).values()) {
            if (listener.getCertificates().contains(certificateArn)) {
                try {
                    restoreListener(region, listener.getListenerArn());
                } catch (RuntimeException failure) {
                    LOG.warnv("ELBv2 certificate refresh for listener {0} failed ({1}: {2}); its configuration is retained",
                            listener.getListenerArn(), failure.getClass().getSimpleName(), failure.getMessage());
                }
            }
        }
    }

    private LoadBalancer requireLoadBalancer(String region, String arn) {
        LoadBalancer lb = loadBalancers.getOrDefault(region, Map.of()).get(arn);
        if (lb == null) {
            throw new AwsException("LoadBalancerNotFound",
                    "One or more load balancers not found.", 400);
        }
        return lb;
    }

    private List<AvailabilityZone> resolveAvailabilityZones(String region, List<String> subnetIds) {
        if (subnetIds == null || subnetIds.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Subnet> subnetsById = resolveSubnetsById(region, subnetIds);
        List<AvailabilityZone> availabilityZones = new ArrayList<>();
        for (String subnetId : subnetIds) {
            Subnet subnet = subnetsById.get(subnetId);
            AvailabilityZone availabilityZone = new AvailabilityZone();
            availabilityZone.setSubnetId(subnet.getSubnetId());
            availabilityZone.setZoneName(subnet.getAvailabilityZone());
            availabilityZones.add(availabilityZone);
        }
        return availabilityZones;
    }

    private String resolveSubnetVpcId(String region, String lbType, List<String> subnetIds) {
        if (subnetIds == null || subnetIds.isEmpty()) {
            return null;
        }

        Map<String, Subnet> subnetsById = resolveSubnetsById(region, subnetIds);

        if ("application".equals(lbType)) {
            long distinctAvailabilityZones = subnetIds.stream()
                    .map(subnetsById::get)
                    .map(Subnet::getAvailabilityZone)
                    .distinct()
                    .count();
            if (distinctAvailabilityZones < 2) {
                throw new AwsException("InvalidConfigurationRequest",
                        "Application Load Balancers must be attached to subnets in at least two Availability Zones.", 400);
            }
        }

        Set<String> vpcIds = subnetIds.stream()
                .map(subnetsById::get)
                .map(Subnet::getVpcId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (vpcIds.size() > 1) {
            throw new AwsException("InvalidConfigurationRequest",
                    "All subnets must belong to the same VPC.", 400);
        }

        return vpcIds.iterator().next();
    }

    private Map<String, Subnet> resolveSubnetsById(String region, List<String> subnetIds) {
        List<Subnet> subnets = ec2Service.describeSubnets(region, subnetIds, Map.of());
        Map<String, Subnet> subnetsById = subnets.stream()
                .collect(Collectors.toMap(Subnet::getSubnetId, subnet -> subnet, (left, right) -> left));

        for (String subnetId : subnetIds) {
            if (!subnetsById.containsKey(subnetId)) {
                throw new AwsException("SubnetNotFound",
                        "The subnet ID '" + subnetId + "' does not exist", 400);
            }
        }
        return subnetsById;
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
        dataPlane.recompileRules(listenerArn, () -> getListenerRules(region, listenerArn));
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

    private static void validateLambdaTargetPorts(TargetGroup group, List<TargetDescription> targets) {
        if (!"lambda".equals(group.getTargetType()) || targets == null) {
            return;
        }
        for (TargetDescription target : targets) {
            if (target.getPort() != null) {
                throw new AwsException("ValidationError", "Port cannot be specified for Lambda function targets", 400);
            }
        }
    }

    private static void validateTargetPorts(List<TargetDescription> targets) {
        if (targets == null) {
            return;
        }
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            addPortViolation(violations, targets.get(i).getPort(), "targets." + (i + 1) + ".member.port");
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationError(violations);
        }
    }

    static void validatePort(Integer port, String member) {
        List<String> violations = new ArrayList<>();
        addPortViolation(violations, port, member);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationError(violations);
        }
    }

    private static void addPortViolation(List<String> violations, Integer port, String member) {
        if (port == null) {
            return;
        }
        String violation = SmithyValidationErrors.outOfRange(member, port, 1, 65535);
        if (violation != null) {
            violations.add(violation);
        }
    }

    /** {@code HealthCheckPort} is either {@code traffic-port} or a port number from 1 to 65535. */
    private static void validateHealthCheckPort(String healthCheckPort) {
        if (healthCheckPort == null || "traffic-port".equals(healthCheckPort)) {
            return;
        }
        if (ElbV2HealthChecker.parsePort(healthCheckPort) == null) {
            throw new AwsException("ValidationError",
                    "Health check port '" + healthCheckPort + "' must be 'traffic-port' or a port number from 1 to 65535.", 400);
        }
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

    /**
     * A load balancer stored before the hosted zones were looked up per region and type still
     * carries the one fixed zone every balancer used to report, so restored balancers take the
     * zone of their region and type again, written back so the stored copy agrees.
     */
    private void refreshCanonicalHostedZones() {
        for (String region : List.copyOf(loadBalancers.keySet())) {
            Map<String, LoadBalancer> regionLoadBalancers = loadBalancers.get(region);
            if (regionLoadBalancers == null) {
                continue;
            }
            for (LoadBalancer lb : regionLoadBalancers.values()) {
                String lbType = lb.getType() != null ? lb.getType() : "application";
                lb.setCanonicalHostedZoneId(canonicalHostedZoneId(lbType, region));
            }
            loadBalancers.put(region, regionLoadBalancers);
        }
    }

    /**
     * Network load balancers have their own hosted zone per region, distinct from the one
     * Application (and Classic) load balancers share; a gateway load balancer has none.
     */
    private static String canonicalHostedZoneId(String type, String region) {
        return switch (type) {
            case "network" -> AwsRegionFacts.nlbHostedZoneId(region).orElse(null);
            case "gateway" -> null;
            default -> AwsRegionFacts.albHostedZoneId(region).orElse(null);
        };
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
        String ruleArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "listener-rule/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId + "/" + ruleId).toString();

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
                addLoadBalancerReference(action.getTargetGroupArn(), lbArn);
            }
            for (Action.TargetGroupTuple t : action.getTargetGroups()) {
                if (t.getTargetGroupArn() != null) {
                    tgToLbs.computeIfAbsent(t.getTargetGroupArn(), k -> ConcurrentHashMap.newKeySet()).add(lbArn);
                    addLoadBalancerReference(t.getTargetGroupArn(), lbArn);
                }
            }
        }
    }

    private void addLoadBalancerReference(String targetGroupArn, String loadBalancerArn) {
        for (Map.Entry<String, Map<String, TargetGroup>> entry : targetGroups.entrySet()) {
            TargetGroup targetGroup = entry.getValue().get(targetGroupArn);
            if (targetGroup != null) {
                if (!targetGroup.getLoadBalancerArns().contains(loadBalancerArn)) {
                    targetGroup.getLoadBalancerArns().add(loadBalancerArn);
                    targetGroups.put(entry.getKey(), entry.getValue());
                }
                return;
            }
        }
    }

    /**
     * Drops {@code lbArn} from every target group that is no longer referenced by any of the
     * load balancer's remaining listeners or rules. Called after a listener or rule is removed
     * so a target group can be deleted once nothing routes to it (avoids a stale "in use" guard
     * during teardown).
     */
    private void unlinkUnreferencedTargetGroups(String region, String lbArn) {
        Set<String> referenced = new HashSet<>();
        Map<String, Listener> regionListeners = listeners.getOrDefault(region, Map.of());
        for (Listener listener : regionListeners.values()) {
            if (lbArn.equals(listener.getLoadBalancerArn())) {
                listener.getDefaultActions().forEach(a -> collectActionTargetGroups(a, referenced));
            }
        }
        for (Rule rule : rules.getOrDefault(region, Map.of()).values()) {
            Listener listener = regionListeners.get(rule.getListenerArn());
            if (listener != null && lbArn.equals(listener.getLoadBalancerArn())) {
                rule.getActions().forEach(a -> collectActionTargetGroups(a, referenced));
            }
        }
        tgToLbs.forEach((tgArn, lbSet) -> {
            if (!referenced.contains(tgArn)) {
                lbSet.remove(lbArn);
                removeLoadBalancerReference(tgArn, lbArn);
            }
        });
    }

    private void removeLoadBalancerReference(String targetGroupArn, String loadBalancerArn) {
        for (Map.Entry<String, Map<String, TargetGroup>> entry : targetGroups.entrySet()) {
            TargetGroup targetGroup = entry.getValue().get(targetGroupArn);
            if (targetGroup != null && targetGroup.getLoadBalancerArns().remove(loadBalancerArn)) {
                targetGroups.put(entry.getKey(), entry.getValue());
                return;
            }
        }
    }

    private static void collectActionTargetGroups(Action action, Set<String> out) {
        if (action.getTargetGroupArn() != null) {
            out.add(action.getTargetGroupArn());
        }
        for (Action.TargetGroupTuple t : action.getTargetGroups()) {
            if (t.getTargetGroupArn() != null) {
                out.add(t.getTargetGroupArn());
            }
        }
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Map<String, LoadBalancer> regionLoadBalancers : loadBalancers.values()) {
            for (LoadBalancer loadBalancer : regionLoadBalancers.values()) {
                addExplorerResource(resources, loadBalancer.getLoadBalancerArn(),
                        "elasticloadbalancing:loadbalancer", loadBalancer.getCreatedTime());
            }
        }
        for (Map<String, TargetGroup> regionTargetGroups : targetGroups.values()) {
            for (TargetGroup targetGroup : regionTargetGroups.values()) {
                addExplorerResource(resources, targetGroup.getTargetGroupArn(),
                        "elasticloadbalancing:targetgroup", null);
            }
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(
                new SupportedResourceType("elasticloadbalancing:loadbalancer", "elasticloadbalancing", true),
                new SupportedResourceType("elasticloadbalancing:targetgroup", "elasticloadbalancing", true));
    }

    private void addExplorerResource(List<ExplorerResource> out, String arn, String resourceType,
                                     Instant createdAt) {
        if (arn == null) {
            return;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        out.add(new ExplorerResource(arn, resourceType, "elasticloadbalancing",
                parsed.region(), parsed.accountId(),
                createdAt != null ? createdAt : Instant.now(),
                tags.getOrDefault(arn, Map.of())));
    }
}
