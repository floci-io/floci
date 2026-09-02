package io.github.hectorvent.floci.services.elb;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * State and behaviour for Classic (2012-06-01) Elastic Load Balancing.
 *
 * <p>Kept apart from {@code ElbV2Service} on purpose. The two APIs share an endpoint host and a
 * credential scope but not a data model: a Classic load balancer is keyed by name and has no ARN,
 * carries its listeners inline rather than through listener and target-group resources, and holds
 * one structured attribute record instead of a key/value list. Answering a Classic request from
 * the v2 store is exactly the defect this class exists to remove.
 *
 * <p>Scope is deliberately the operations the AWS provider drives for {@code aws_elb} and for an
 * Auto Scaling group attached to one. Stickiness and backend-server policies are not implemented;
 * {@code ElbClassicQueryHandler} answers those with {@code UnsupportedOperation} rather than
 * inventing a result.
 */
@ApplicationScoped
public class ElbClassicService {

    /** The hosted zone ID AWS reports for Classic load balancers; fixed per region on AWS too. */
    private static final String CANONICAL_HOSTED_ZONE_ID = "Z35SXDOTRQ7X7K";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Ec2Service ec2Service;
    private final ElbClassicHealthChecker healthChecker;
    private final StorageFactory storageFactory;
    private final EmulatorConfig config;

    // region → load balancer name → record
    private Map<String, Map<String, ClassicLoadBalancer>> loadBalancers = new ConcurrentHashMap<>();

    @Inject
    public ElbClassicService(Ec2Service ec2Service,
                             ElbClassicHealthChecker healthChecker,
                             StorageFactory storageFactory,
                             EmulatorConfig config) {
        this.ec2Service = ec2Service;
        this.healthChecker = healthChecker;
        this.storageFactory = storageFactory;
        this.config = config;
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return;
        }
        this.loadBalancers = new StorageBackedMap<>(storageFactory.create("elb", "elb-load-balancers.json",
                new TypeReference<Map<String, Map<String, ClassicLoadBalancer>>>() {}));
        for (Map.Entry<String, Map<String, ClassicLoadBalancer>> entry
                : new ArrayList<>(loadBalancers.entrySet())) {
            if (!(entry.getValue() instanceof ConcurrentHashMap)) {
                loadBalancers.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
        }
    }

    /**
     * Restarts health checking for load balancers restored from disk. Called from
     * {@code EmulatorLifecycle} once the bean is constructed, like {@code ElbV2Service} does —
     * nothing reachable from {@link #initializeStorage()} may touch an injected collaborator.
     */
    public void restorePersistedRuntime() {
        for (Map<String, ClassicLoadBalancer> regionLbs : loadBalancers.values()) {
            for (ClassicLoadBalancer lb : regionLbs.values()) {
                healthChecker.startMonitoring(lb);
                healthChecker.registerInstances(lb, lb.getInstanceIds());
            }
        }
    }

    // ── Load balancers ────────────────────────────────────────────────────────

    public ClassicLoadBalancer createLoadBalancer(String region, String name,
                                                  List<ClassicListener> listeners,
                                                  List<String> availabilityZones,
                                                  List<String> subnets,
                                                  List<String> securityGroups,
                                                  String scheme,
                                                  Map<String, String> tags) {
        validateName(name);
        if (listeners == null || listeners.isEmpty()) {
            // Listeners is a required member of CreateAccessPointInput, and unlike LoadBalancerName
            // the model gives its member shape required fields — an empty list cannot be honoured.
            throw new AwsException("ValidationError",
                    "At least one listener must be specified.", 400);
        }
        for (ClassicListener listener : listeners) {
            validateListener(listener);
        }

        Map<String, ClassicLoadBalancer> regionLbs =
                loadBalancers.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
        if (regionLbs.containsKey(name)) {
            throw new AwsException("DuplicateLoadBalancerName",
                    "Load balancer named " + name + " already exists and is not compatible with the "
                            + "requested configuration.", 400);
        }

        String id = randomHex();
        String dnsName = name + "-" + id + ".elb." + dnsSuffix();

        ClassicLoadBalancer lb = new ClassicLoadBalancer();
        lb.setLoadBalancerName(name);
        lb.setRegion(region);
        lb.setDnsName(dnsName);
        lb.setCanonicalHostedZoneName(dnsName);
        lb.setCanonicalHostedZoneNameId(CANONICAL_HOSTED_ZONE_ID);
        lb.setScheme(scheme != null && !scheme.isBlank() ? scheme : "internet-facing");
        lb.setCreatedTime(Instant.now());
        lb.setListeners(new ArrayList<>(listeners));
        lb.setSecurityGroups(securityGroups != null ? new ArrayList<>(securityGroups) : new ArrayList<>());
        lb.setHealthCheck(ClassicHealthCheck.defaults());
        if (tags != null && !tags.isEmpty()) {
            lb.setTags(new LinkedHashMap<>(tags));
        }

        if (subnets != null && !subnets.isEmpty()) {
            lb.setSubnets(new ArrayList<>(subnets));
            Map<String, Subnet> byId = resolveSubnets(region, subnets);
            lb.setVpcId(singleVpcId(byId.values()));
            lb.setAvailabilityZones(byId.values().stream()
                    .map(Subnet::getAvailabilityZone)
                    .distinct()
                    .collect(Collectors.toCollection(ArrayList::new)));
        } else if (availabilityZones != null) {
            lb.setAvailabilityZones(new ArrayList<>(availabilityZones));
        }

        regionLbs.put(name, lb);
        loadBalancers.put(region, regionLbs);
        healthChecker.startMonitoring(lb);
        return lb;
    }

    public List<ClassicLoadBalancer> describeLoadBalancers(String region, List<String> names) {
        Map<String, ClassicLoadBalancer> regionLbs = loadBalancers.getOrDefault(region, Map.of());
        if (names == null || names.isEmpty()) {
            return new ArrayList<>(regionLbs.values());
        }
        List<ClassicLoadBalancer> result = new ArrayList<>();
        for (String name : names) {
            ClassicLoadBalancer lb = regionLbs.get(name);
            if (lb == null) {
                throw new AwsException("LoadBalancerNotFound",
                        "There is no ACTIVE Load Balancer named '" + name + "'", 400);
            }
            result.add(lb);
        }
        return result;
    }

    /** Deletes a load balancer. Deleting one that does not exist succeeds, as it does on AWS. */
    public void deleteLoadBalancer(String region, String name) {
        Map<String, ClassicLoadBalancer> regionLbs = loadBalancers.get(region);
        if (regionLbs == null) {
            return;
        }
        if (regionLbs.remove(name) != null) {
            healthChecker.stopMonitoring(region, name);
            loadBalancers.put(region, regionLbs);
        }
    }

    public boolean hasLoadBalancer(String region, String name) {
        return loadBalancers.getOrDefault(region, Map.of()).containsKey(name);
    }

    public ClassicLoadBalancer requireLoadBalancer(String region, String name) {
        ClassicLoadBalancer lb = loadBalancers.getOrDefault(region, Map.of()).get(name);
        if (lb == null) {
            throw new AwsException("LoadBalancerNotFound",
                    "There is no ACTIVE Load Balancer named '" + name + "'", 400);
        }
        return lb;
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    public void createLoadBalancerListeners(String region, String name, List<ClassicListener> listeners) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        for (ClassicListener listener : listeners) {
            validateListener(listener);
        }
        for (ClassicListener listener : listeners) {
            ClassicListener existing = lb.getListeners().stream()
                    .filter(l -> l.getLoadBalancerPort().equals(listener.getLoadBalancerPort()))
                    .findFirst()
                    .orElse(null);
            if (existing != null) {
                // AWS accepts a repeat of an identical listener and rejects a conflicting one.
                if (!sameListener(existing, listener)) {
                    throw new AwsException("DuplicateListener",
                            "A listener already exists for " + name + " with LoadBalancerPort "
                                    + listener.getLoadBalancerPort()
                                    + ", but with a different InstancePort, Protocol, or "
                                    + "SSLCertificateId", 400);
                }
                continue;
            }
            lb.getListeners().add(listener);
        }
        persist(region);
    }

    public void deleteLoadBalancerListeners(String region, String name, List<Integer> loadBalancerPorts) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        lb.getListeners().removeIf(l -> loadBalancerPorts.contains(l.getLoadBalancerPort()));
        persist(region);
    }

    // ── Health ────────────────────────────────────────────────────────────────

    public ClassicHealthCheck configureHealthCheck(String region, String name, ClassicHealthCheck healthCheck) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        validateHealthCheck(healthCheck);
        lb.setHealthCheck(healthCheck);
        persist(region);
        healthChecker.healthCheckChanged(lb);
        return healthCheck;
    }

    public List<String> registerInstances(String region, String name, List<String> instanceIds) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        for (String id : instanceIds) {
            if (!lb.getInstanceIds().contains(id)) {
                lb.getInstanceIds().add(id);
            }
        }
        persist(region);
        healthChecker.registerInstances(lb, instanceIds);
        return List.copyOf(lb.getInstanceIds());
    }

    public List<String> deregisterInstances(String region, String name, List<String> instanceIds) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        lb.getInstanceIds().removeAll(instanceIds);
        persist(region);
        healthChecker.deregisterInstances(region, name, instanceIds);
        return List.copyOf(lb.getInstanceIds());
    }

    /** The health of each requested instance, or of every registered instance when none is named. */
    public Map<String, ElbClassicHealthChecker.InstanceHealth> describeInstanceHealth(
            String region, String name, List<String> instanceIds) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        List<String> wanted = instanceIds == null || instanceIds.isEmpty()
                ? lb.getInstanceIds()
                : instanceIds;
        Map<String, ElbClassicHealthChecker.InstanceHealth> result = new LinkedHashMap<>();
        for (String id : wanted) {
            if (!lb.getInstanceIds().contains(id)) {
                throw new AwsException("InvalidInstance",
                        "Could not find EC2 instance " + id + ".", 400);
            }
            result.put(id, healthChecker.getHealth(region, name, id));
        }
        return result;
    }

    // ── Attributes, zones, subnets, security groups ───────────────────────────

    public ClassicLoadBalancer modifyLoadBalancerAttributes(String region, String name,
                                                            AttributeUpdate update) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        update.applyTo(lb.getAttributes());
        persist(region);
        return lb;
    }

    /** The subset of {@code LoadBalancerAttributes} a request actually carried; unset stays unset. */
    public record AttributeUpdate(Boolean crossZoneEnabled,
                                  Boolean accessLogEnabled,
                                  String accessLogS3BucketName,
                                  String accessLogS3BucketPrefix,
                                  Integer accessLogEmitInterval,
                                  Boolean connectionDrainingEnabled,
                                  Integer connectionDrainingTimeout,
                                  Integer idleTimeout,
                                  Map<String, String> additionalAttributes) {

        void applyTo(io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancerAttributes attrs) {
            if (crossZoneEnabled != null) attrs.setCrossZoneLoadBalancingEnabled(crossZoneEnabled);
            if (accessLogEnabled != null) attrs.setAccessLogEnabled(accessLogEnabled);
            if (accessLogS3BucketName != null) attrs.setAccessLogS3BucketName(accessLogS3BucketName);
            if (accessLogS3BucketPrefix != null) attrs.setAccessLogS3BucketPrefix(accessLogS3BucketPrefix);
            if (accessLogEmitInterval != null) attrs.setAccessLogEmitInterval(accessLogEmitInterval);
            if (connectionDrainingEnabled != null) attrs.setConnectionDrainingEnabled(connectionDrainingEnabled);
            if (connectionDrainingTimeout != null) attrs.setConnectionDrainingTimeout(connectionDrainingTimeout);
            if (idleTimeout != null) attrs.setIdleTimeout(idleTimeout);
            if (additionalAttributes != null && !additionalAttributes.isEmpty()) {
                attrs.getAdditionalAttributes().putAll(additionalAttributes);
            }
        }
    }

    public List<String> applySecurityGroups(String region, String name, List<String> securityGroups) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        lb.setSecurityGroups(new ArrayList<>(securityGroups));
        persist(region);
        return List.copyOf(lb.getSecurityGroups());
    }

    public List<String> attachToSubnets(String region, String name, List<String> subnets) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        resolveSubnets(region, subnets);
        for (String subnet : subnets) {
            if (!lb.getSubnets().contains(subnet)) {
                lb.getSubnets().add(subnet);
            }
        }
        refreshZonesFromSubnets(region, lb);
        persist(region);
        return List.copyOf(lb.getSubnets());
    }

    public List<String> detachFromSubnets(String region, String name, List<String> subnets) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        lb.getSubnets().removeAll(subnets);
        refreshZonesFromSubnets(region, lb);
        persist(region);
        return List.copyOf(lb.getSubnets());
    }

    public List<String> enableAvailabilityZones(String region, String name, List<String> zones) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        for (String zone : zones) {
            if (!lb.getAvailabilityZones().contains(zone)) {
                lb.getAvailabilityZones().add(zone);
            }
        }
        persist(region);
        return List.copyOf(lb.getAvailabilityZones());
    }

    public List<String> disableAvailabilityZones(String region, String name, List<String> zones) {
        ClassicLoadBalancer lb = requireLoadBalancer(region, name);
        lb.getAvailabilityZones().removeAll(zones);
        persist(region);
        return List.copyOf(lb.getAvailabilityZones());
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    public void addTags(String region, List<String> names, Map<String, String> tags) {
        for (String name : names) {
            requireLoadBalancer(region, name).getTags().putAll(tags);
        }
        persist(region);
    }

    public void removeTags(String region, List<String> names, Collection<String> tagKeys) {
        for (String name : names) {
            requireLoadBalancer(region, name).getTags().keySet().removeAll(tagKeys);
        }
        persist(region);
    }

    public Map<String, Map<String, String>> describeTags(String region, List<String> names) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, Map.copyOf(requireLoadBalancer(region, name).getTags()));
        }
        return result;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void persist(String region) {
        Map<String, ClassicLoadBalancer> regionLbs = loadBalancers.get(region);
        if (regionLbs != null) {
            loadBalancers.put(region, regionLbs);
        }
    }

    private void refreshZonesFromSubnets(String region, ClassicLoadBalancer lb) {
        if (lb.getSubnets().isEmpty()) {
            lb.setAvailabilityZones(new ArrayList<>());
            return;
        }
        Map<String, Subnet> byId = resolveSubnets(region, lb.getSubnets());
        lb.setVpcId(singleVpcId(byId.values()));
        lb.setAvailabilityZones(byId.values().stream()
                .map(Subnet::getAvailabilityZone)
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new)));
    }

    private Map<String, Subnet> resolveSubnets(String region, List<String> subnetIds) {
        Map<String, Subnet> byId = ec2Service.describeSubnets(region, subnetIds, Map.of()).stream()
                .collect(Collectors.toMap(Subnet::getSubnetId, s -> s, (l, r) -> l,
                        LinkedHashMap::new));
        for (String subnetId : subnetIds) {
            if (!byId.containsKey(subnetId)) {
                throw new AwsException("SubnetNotFound",
                        "The subnet ID '" + subnetId + "' does not exist", 400);
            }
        }
        return byId;
    }

    private static String singleVpcId(Collection<Subnet> subnets) {
        Set<String> vpcIds = subnets.stream()
                .map(Subnet::getVpcId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (vpcIds.size() > 1) {
            // CreateLoadBalancer's own Errors table documents InvalidConfigurationRequest at 409,
            // not 400: a client that retries on 409 but fails hard on 400 needs the real status.
            throw new AwsException("InvalidConfigurationRequest",
                    "All subnets must belong to the same VPC.", 409);
        }
        return vpcIds.isEmpty() ? null : vpcIds.iterator().next();
    }

    private String dnsSuffix() {
        if (config == null) {
            return EmbeddedDnsServer.DEFAULT_SUFFIX;
        }
        return config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
    }

    private static String randomHex() {
        byte[] bytes = new byte[5];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Rejects a load balancer name AWS would reject.
     *
     * <p>{@code LoadBalancerName} is a required member, and required means present — the model
     * declares no {@code min} on {@code AccessPointName}, so an empty string is a missing name
     * only in the sense that the member was not supplied. The 32-character ceiling and the
     * alphanumeric-and-hyphen rule are AWS's documented constraints for Classic names.
     */
    private static void validateName(String name) {
        if (name == null) {
            throw new AwsException("ValidationError",
                    "LoadBalancerName is required for load balancer.", 400);
        }
        if (name.isEmpty() || name.length() > 32) {
            throw new AwsException("ValidationError",
                    "LoadBalancerName must be between 1 and 32 characters long.", 400);
        }
        if (!name.matches("[a-zA-Z0-9-]+")) {
            throw new AwsException("ValidationError",
                    "LoadBalancerName '" + name + "' contains invalid characters. Use alphanumeric "
                            + "characters and hyphens.", 400);
        }
        if (name.startsWith("-") || name.endsWith("-")) {
            throw new AwsException("ValidationError",
                    "LoadBalancerName '" + name + "' cannot start or end with a hyphen.", 400);
        }
    }

    private static void validateListener(ClassicListener listener) {
        if (listener.getProtocol() == null || listener.getLoadBalancerPort() == null
                || listener.getInstancePort() == null) {
            throw new AwsException("ValidationError",
                    "Protocol, LoadBalancerPort and InstancePort are required for each listener.", 400);
        }
        if (listener.getInstancePort() < 1 || listener.getInstancePort() > 65535) {
            throw new AwsException("ValidationError",
                    "InstancePort must be between 1 and 65535.", 400);
        }
    }

    /**
     * Rejects a health check outside the ranges the model declares — {@code Interval} 5–300,
     * {@code Timeout} 2–60, both thresholds 2–10 — and one with no {@code Target}, which the model
     * marks required.
     */
    private static void validateHealthCheck(ClassicHealthCheck hc) {
        if (hc.getTarget() == null || hc.getTarget().isBlank()) {
            throw new AwsException("ValidationError",
                    "HealthCheck.Target is required.", 400);
        }
        requireRange("HealthCheck.Interval", hc.getInterval(), 5, 300);
        requireRange("HealthCheck.Timeout", hc.getTimeout(), 2, 60);
        requireRange("HealthCheck.UnhealthyThreshold", hc.getUnhealthyThreshold(), 2, 10);
        requireRange("HealthCheck.HealthyThreshold", hc.getHealthyThreshold(), 2, 10);
    }

    private static void requireRange(String member, Integer value, int min, int max) {
        if (value == null) {
            throw new AwsException("ValidationError", member + " is required.", 400);
        }
        if (value < min || value > max) {
            throw new AwsException("ValidationError",
                    member + " must be between " + min + " and " + max + ".", 400);
        }
    }

    private static boolean sameListener(ClassicListener a, ClassicListener b) {
        return java.util.Objects.equals(a.getProtocol(), b.getProtocol())
                && java.util.Objects.equals(a.getInstancePort(), b.getInstancePort())
                && java.util.Objects.equals(a.getInstanceProtocol(), b.getInstanceProtocol())
                && java.util.Objects.equals(a.getSslCertificateId(), b.getSslCertificateId());
    }
}
