package io.github.hectorvent.floci.services.globalaccelerator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.globalaccelerator.model.Accelerator;
import io.github.hectorvent.floci.services.globalaccelerator.model.AcceleratorAttributes;
import io.github.hectorvent.floci.services.globalaccelerator.model.EndpointDescription;
import io.github.hectorvent.floci.services.globalaccelerator.model.EndpointGroup;
import io.github.hectorvent.floci.services.globalaccelerator.model.IpSet;
import io.github.hectorvent.floci.services.globalaccelerator.model.Listener;
import io.github.hectorvent.floci.services.globalaccelerator.model.PortOverride;
import io.github.hectorvent.floci.services.globalaccelerator.model.PortRange;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * AWS Global Accelerator management plane.
 *
 * <p>Global Accelerator is a global service: its ARNs carry an empty region segment
 * ({@code arn:aws:globalaccelerator::<account>:accelerator/<id>}) and listener and endpoint
 * group ARNs are built by extending the accelerator ARN, which is how the real service
 * expresses the parent/child relationship. Parent lookups therefore need no back-reference
 * field: an accelerator's listeners are the stored ARNs prefixed by it.
 *
 * <p>Accelerators are {@code DEPLOYED} and endpoints are {@code HEALTHY} as soon as a create
 * returns, so provider waiters complete on their first poll. The static IP addresses come
 * from the address ranges Global Accelerator advertises but route no traffic.
 */
@ApplicationScoped
public class GlobalAcceleratorService {

    private static final Logger LOG = Logger.getLogger(GlobalAcceleratorService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String HEX = "0123456789abcdef";

    private static final String STATUS_DEPLOYED = "DEPLOYED";
    private static final String HEALTH_STATE_HEALTHY = "HEALTHY";
    private static final String LISTENER_SEGMENT = "/listener/";
    private static final String ENDPOINT_GROUP_SEGMENT = "/endpoint-group/";

    private final StorageBackend<String, Accelerator> accelerators;
    private final StorageBackend<String, AcceleratorAttributes> acceleratorAttributes;
    private final StorageBackend<String, Listener> listeners;
    private final StorageBackend<String, EndpointGroup> endpointGroups;
    private final StorageBackend<String, Map<String, String>> tags;
    private final RegionResolver regionResolver;

    @Inject
    public GlobalAcceleratorService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.accelerators = storageFactory.create("globalaccelerator", "globalaccelerator-accelerators.json",
                new TypeReference<Map<String, Accelerator>>() {});
        this.acceleratorAttributes = storageFactory.create("globalaccelerator",
                "globalaccelerator-accelerator-attributes.json",
                new TypeReference<Map<String, AcceleratorAttributes>>() {});
        this.listeners = storageFactory.create("globalaccelerator", "globalaccelerator-listeners.json",
                new TypeReference<Map<String, Listener>>() {});
        this.endpointGroups = storageFactory.create("globalaccelerator", "globalaccelerator-endpoint-groups.json",
                new TypeReference<Map<String, EndpointGroup>>() {});
        this.tags = storageFactory.create("globalaccelerator", "globalaccelerator-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Accelerators ────────────────────────────

    public Accelerator createAccelerator(String name, String ipAddressType, List<String> ipAddresses,
                                         Boolean enabled, Map<String, String> requestTags) {
        requireArgument(name, "Name");
        String resolvedIpAddressType = ipAddressType != null ? ipAddressType : "IPV4";
        if (!"IPV4".equals(resolvedIpAddressType) && !"DUAL_STACK".equals(resolvedIpAddressType)) {
            throw new AwsException("InvalidArgumentException",
                    "IpAddressType must be IPV4 or DUAL_STACK.", 400);
        }

        long now = Instant.now().getEpochSecond();
        String dnsPrefix = "a" + randomHex(16);

        Accelerator accelerator = new Accelerator();
        accelerator.setAcceleratorArn(regionResolver.buildArn("globalaccelerator", "",
                "accelerator/" + UUID.randomUUID()));
        accelerator.setName(name);
        accelerator.setIpAddressType(resolvedIpAddressType);
        accelerator.setEnabled(enabled == null || enabled);
        accelerator.setIpSets(buildIpSets(resolvedIpAddressType, ipAddresses));
        accelerator.setDnsName(dnsPrefix + ".awsglobalaccelerator.com");
        if ("DUAL_STACK".equals(resolvedIpAddressType)) {
            accelerator.setDualStackDnsName(dnsPrefix + ".dualstack.awsglobalaccelerator.com");
        }
        accelerator.setStatus(STATUS_DEPLOYED);
        accelerator.setCreatedTime(now);
        accelerator.setLastModifiedTime(now);

        accelerators.put(accelerator.getAcceleratorArn(), accelerator);
        acceleratorAttributes.put(accelerator.getAcceleratorArn(), defaultAttributes());
        putTags(accelerator.getAcceleratorArn(), requestTags);
        LOG.infov("Created Global Accelerator accelerator: {0}", accelerator.getAcceleratorArn());
        return accelerator;
    }

    public Accelerator describeAccelerator(String acceleratorArn) {
        requireArgument(acceleratorArn, "AcceleratorArn");
        return accelerators.get(acceleratorArn)
                .orElseThrow(() -> new AwsException("AcceleratorNotFoundException",
                        "Accelerator " + acceleratorArn + " does not exist.", 400));
    }

    public Accelerator updateAccelerator(String acceleratorArn, String name, String ipAddressType,
                                         List<String> ipAddresses, Boolean enabled) {
        Accelerator accelerator = describeAccelerator(acceleratorArn);
        if (name != null) {
            accelerator.setName(name);
        }
        if (ipAddressType != null) {
            if (!"IPV4".equals(ipAddressType) && !"DUAL_STACK".equals(ipAddressType)) {
                throw new AwsException("InvalidArgumentException",
                        "IpAddressType must be IPV4 or DUAL_STACK.", 400);
            }
            accelerator.setIpAddressType(ipAddressType);
            accelerator.setIpSets(buildIpSets(ipAddressType, ipAddresses != null
                    ? ipAddresses
                    : firstIpv4Addresses(accelerator)));
            if ("DUAL_STACK".equals(ipAddressType)) {
                if (accelerator.getDualStackDnsName() == null) {
                    accelerator.setDualStackDnsName(accelerator.getDnsName()
                            .replace(".awsglobalaccelerator.com", ".dualstack.awsglobalaccelerator.com"));
                }
            } else {
                accelerator.setDualStackDnsName(null);
            }
        } else if (ipAddresses != null && !ipAddresses.isEmpty()) {
            accelerator.setIpSets(buildIpSets(accelerator.getIpAddressType(), ipAddresses));
        }
        if (enabled != null) {
            accelerator.setEnabled(enabled);
        }
        accelerator.setStatus(STATUS_DEPLOYED);
        accelerator.setLastModifiedTime(Instant.now().getEpochSecond());
        accelerators.put(acceleratorArn, accelerator);
        return accelerator;
    }

    public void deleteAccelerator(String acceleratorArn) {
        Accelerator accelerator = describeAccelerator(acceleratorArn);
        if (Boolean.TRUE.equals(accelerator.getEnabled())) {
            throw new AwsException("AcceleratorNotDisabledException",
                    "The accelerator must be disabled before it can be deleted.", 400);
        }
        if (!listListeners(acceleratorArn).isEmpty()) {
            throw new AwsException("AssociatedListenerFoundException",
                    "The accelerator has associated listeners and cannot be deleted.", 400);
        }
        accelerators.delete(acceleratorArn);
        acceleratorAttributes.delete(acceleratorArn);
        tags.delete(acceleratorArn);
        LOG.infov("Deleted Global Accelerator accelerator: {0}", acceleratorArn);
    }

    public List<Accelerator> listAccelerators() {
        return accelerators.scan(k -> true);
    }

    public AcceleratorAttributes describeAcceleratorAttributes(String acceleratorArn) {
        describeAccelerator(acceleratorArn);
        return acceleratorAttributes.get(acceleratorArn).orElseGet(GlobalAcceleratorService::defaultAttributes);
    }

    public AcceleratorAttributes updateAcceleratorAttributes(String acceleratorArn, Boolean flowLogsEnabled,
                                                             String flowLogsS3Bucket, String flowLogsS3Prefix) {
        AcceleratorAttributes attributes = describeAcceleratorAttributes(acceleratorArn);
        if (flowLogsEnabled != null) {
            attributes.setFlowLogsEnabled(flowLogsEnabled);
        }
        if (flowLogsS3Bucket != null) {
            attributes.setFlowLogsS3Bucket(flowLogsS3Bucket);
        }
        if (flowLogsS3Prefix != null) {
            attributes.setFlowLogsS3Prefix(flowLogsS3Prefix);
        }
        acceleratorAttributes.put(acceleratorArn, attributes);
        return attributes;
    }

    // ──────────────────────────── Listeners ────────────────────────────

    public Listener createListener(String acceleratorArn, List<PortRange> portRanges, String protocol,
                                   String clientAffinity) {
        describeAccelerator(acceleratorArn);
        validatePortRanges(portRanges);
        requireArgument(protocol, "Protocol");
        if (!"TCP".equals(protocol) && !"UDP".equals(protocol)) {
            throw new AwsException("InvalidArgumentException", "Protocol must be TCP or UDP.", 400);
        }

        Listener listener = new Listener();
        listener.setListenerArn(acceleratorArn + LISTENER_SEGMENT + randomId(8));
        listener.setPortRanges(portRanges);
        listener.setProtocol(protocol);
        listener.setClientAffinity(clientAffinity != null ? clientAffinity : "NONE");

        listeners.put(listener.getListenerArn(), listener);
        LOG.infov("Created Global Accelerator listener: {0}", listener.getListenerArn());
        return listener;
    }

    public Listener describeListener(String listenerArn) {
        requireArgument(listenerArn, "ListenerArn");
        return listeners.get(listenerArn)
                .orElseThrow(() -> new AwsException("ListenerNotFoundException",
                        "Listener " + listenerArn + " does not exist.", 400));
    }

    public Listener updateListener(String listenerArn, List<PortRange> portRanges, String protocol,
                                   String clientAffinity) {
        Listener listener = describeListener(listenerArn);
        if (portRanges != null && !portRanges.isEmpty()) {
            validatePortRanges(portRanges);
            listener.setPortRanges(portRanges);
        }
        if (protocol != null) {
            if (!"TCP".equals(protocol) && !"UDP".equals(protocol)) {
                throw new AwsException("InvalidArgumentException", "Protocol must be TCP or UDP.", 400);
            }
            listener.setProtocol(protocol);
        }
        if (clientAffinity != null) {
            listener.setClientAffinity(clientAffinity);
        }
        listeners.put(listenerArn, listener);
        return listener;
    }

    public void deleteListener(String listenerArn) {
        describeListener(listenerArn);
        if (!listEndpointGroups(listenerArn).isEmpty()) {
            throw new AwsException("AssociatedEndpointGroupFoundException",
                    "The listener has associated endpoint groups and cannot be deleted.", 400);
        }
        listeners.delete(listenerArn);
        tags.delete(listenerArn);
        LOG.infov("Deleted Global Accelerator listener: {0}", listenerArn);
    }

    public List<Listener> listListeners(String acceleratorArn) {
        describeAccelerator(acceleratorArn);
        return listeners.scan(k -> k.startsWith(acceleratorArn + LISTENER_SEGMENT));
    }

    // ──────────────────────── Endpoint groups ────────────────────────

    public EndpointGroup createEndpointGroup(String listenerArn, String endpointGroupRegion,
                                             JsonNode endpointConfigurations, Float trafficDialPercentage,
                                             Integer healthCheckPort, String healthCheckProtocol,
                                             String healthCheckPath, Integer healthCheckIntervalSeconds,
                                             Integer thresholdCount, List<PortOverride> portOverrides) {
        Listener listener = describeListener(listenerArn);
        requireArgument(endpointGroupRegion, "EndpointGroupRegion");
        boolean regionTaken = listEndpointGroups(listenerArn).stream()
                .anyMatch(group -> endpointGroupRegion.equals(group.getEndpointGroupRegion()));
        if (regionTaken) {
            throw new AwsException("EndpointGroupAlreadyExistsException",
                    "An endpoint group for Region " + endpointGroupRegion + " already exists on this listener.", 400);
        }

        EndpointGroup group = new EndpointGroup();
        group.setEndpointGroupArn(listenerArn + ENDPOINT_GROUP_SEGMENT + randomId(14));
        group.setEndpointGroupRegion(endpointGroupRegion);
        group.setEndpointDescriptions(toEndpointDescriptions(endpointConfigurations));
        group.setTrafficDialPercentage(trafficDialPercentage != null ? trafficDialPercentage : 100.0f);
        group.setHealthCheckPort(healthCheckPort != null ? healthCheckPort : defaultHealthCheckPort(listener));
        group.setHealthCheckProtocol(healthCheckProtocol != null ? healthCheckProtocol : "TCP");
        group.setHealthCheckPath(healthCheckPath != null ? healthCheckPath : "/");
        group.setHealthCheckIntervalSeconds(healthCheckIntervalSeconds != null ? healthCheckIntervalSeconds : 30);
        group.setThresholdCount(thresholdCount != null ? thresholdCount : 3);
        group.setPortOverrides(portOverrides != null ? portOverrides : new ArrayList<>());

        endpointGroups.put(group.getEndpointGroupArn(), group);
        LOG.infov("Created Global Accelerator endpoint group: {0}", group.getEndpointGroupArn());
        return group;
    }

    public EndpointGroup describeEndpointGroup(String endpointGroupArn) {
        requireArgument(endpointGroupArn, "EndpointGroupArn");
        return endpointGroups.get(endpointGroupArn)
                .orElseThrow(() -> new AwsException("EndpointGroupNotFoundException",
                        "Endpoint group " + endpointGroupArn + " does not exist.", 400));
    }

    public EndpointGroup updateEndpointGroup(String endpointGroupArn, JsonNode endpointConfigurations,
                                             Float trafficDialPercentage, Integer healthCheckPort,
                                             String healthCheckProtocol, String healthCheckPath,
                                             Integer healthCheckIntervalSeconds, Integer thresholdCount,
                                             List<PortOverride> portOverrides) {
        EndpointGroup group = describeEndpointGroup(endpointGroupArn);
        if (endpointConfigurations != null && endpointConfigurations.isArray()) {
            group.setEndpointDescriptions(toEndpointDescriptions(endpointConfigurations));
        }
        if (trafficDialPercentage != null) {
            group.setTrafficDialPercentage(trafficDialPercentage);
        }
        if (healthCheckPort != null) {
            group.setHealthCheckPort(healthCheckPort);
        }
        if (healthCheckProtocol != null) {
            group.setHealthCheckProtocol(healthCheckProtocol);
        }
        if (healthCheckPath != null) {
            group.setHealthCheckPath(healthCheckPath);
        }
        if (healthCheckIntervalSeconds != null) {
            group.setHealthCheckIntervalSeconds(healthCheckIntervalSeconds);
        }
        if (thresholdCount != null) {
            group.setThresholdCount(thresholdCount);
        }
        if (portOverrides != null) {
            group.setPortOverrides(portOverrides);
        }
        endpointGroups.put(endpointGroupArn, group);
        return group;
    }

    public void deleteEndpointGroup(String endpointGroupArn) {
        describeEndpointGroup(endpointGroupArn);
        endpointGroups.delete(endpointGroupArn);
        tags.delete(endpointGroupArn);
        LOG.infov("Deleted Global Accelerator endpoint group: {0}", endpointGroupArn);
    }

    public List<EndpointGroup> listEndpointGroups(String listenerArn) {
        return endpointGroups.scan(k -> k.startsWith(listenerArn + ENDPOINT_GROUP_SEGMENT));
    }

    public EndpointGroup addEndpoints(String endpointGroupArn, JsonNode endpointConfigurations) {
        EndpointGroup group = describeEndpointGroup(endpointGroupArn);
        if (endpointConfigurations == null || !endpointConfigurations.isArray()) {
            throw new AwsException("InvalidArgumentException", "EndpointConfigurations is required.", 400);
        }
        List<EndpointDescription> merged = new ArrayList<>(group.getEndpointDescriptions());
        for (EndpointDescription added : toEndpointDescriptions(endpointConfigurations)) {
            merged.removeIf(existing -> existing.getEndpointId() != null
                    && existing.getEndpointId().equals(added.getEndpointId()));
            merged.add(added);
        }
        group.setEndpointDescriptions(merged);
        endpointGroups.put(endpointGroupArn, group);
        return group;
    }

    public void removeEndpoints(String endpointGroupArn, List<String> endpointIds) {
        EndpointGroup group = describeEndpointGroup(endpointGroupArn);
        if (endpointIds == null || endpointIds.isEmpty()) {
            throw new AwsException("InvalidArgumentException", "EndpointIdentifiers is required.", 400);
        }
        List<EndpointDescription> remaining = new ArrayList<>(group.getEndpointDescriptions());
        remaining.removeIf(endpoint -> endpointIds.contains(endpoint.getEndpointId()));
        group.setEndpointDescriptions(remaining);
        endpointGroups.put(endpointGroupArn, group);
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn) {
        requireResourceExists(resourceArn);
        return tags.get(resourceArn).orElseGet(LinkedHashMap::new);
    }

    public void tagResource(String resourceArn, Map<String, String> newTags) {
        requireResourceExists(resourceArn);
        Map<String, String> existing = new LinkedHashMap<>(tags.get(resourceArn).orElseGet(LinkedHashMap::new));
        if (newTags != null) {
            existing.putAll(newTags);
        }
        tags.put(resourceArn, existing);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        requireResourceExists(resourceArn);
        Map<String, String> existing = new LinkedHashMap<>(tags.get(resourceArn).orElseGet(LinkedHashMap::new));
        if (tagKeys != null) {
            tagKeys.forEach(existing::remove);
        }
        tags.put(resourceArn, existing);
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private void putTags(String resourceArn, Map<String, String> requestTags) {
        tags.put(resourceArn, requestTags != null ? new LinkedHashMap<>(requestTags) : new LinkedHashMap<>());
    }

    private void requireResourceExists(String resourceArn) {
        requireArgument(resourceArn, "ResourceArn");
        if (resourceArn.contains(ENDPOINT_GROUP_SEGMENT)) {
            describeEndpointGroup(resourceArn);
        } else if (resourceArn.contains(LISTENER_SEGMENT)) {
            describeListener(resourceArn);
        } else {
            describeAccelerator(resourceArn);
        }
    }

    private static Integer defaultHealthCheckPort(Listener listener) {
        for (PortRange range : listener.getPortRanges()) {
            if (range.getFromPort() != null) {
                return range.getFromPort();
            }
        }
        return null;
    }

    private List<EndpointDescription> toEndpointDescriptions(JsonNode endpointConfigurations) {
        List<EndpointDescription> descriptions = new ArrayList<>();
        if (endpointConfigurations == null || !endpointConfigurations.isArray()) {
            return descriptions;
        }
        for (JsonNode configuration : endpointConfigurations) {
            EndpointDescription description = new EndpointDescription();
            String endpointId = configuration.path("EndpointId").asText(null);
            if (endpointId == null || endpointId.isBlank()) {
                throw new AwsException("InvalidArgumentException",
                        "EndpointId is required on every endpoint configuration.", 400);
            }
            description.setEndpointId(endpointId);
            description.setWeight(configuration.has("Weight") ? configuration.get("Weight").asInt() : 128);
            description.setClientIpPreservationEnabled(configuration.has("ClientIPPreservationEnabled")
                    ? configuration.get("ClientIPPreservationEnabled").asBoolean()
                    : endpointId.contains(":loadbalancer/app/"));
            description.setHealthState(HEALTH_STATE_HEALTHY);
            descriptions.add(description);
        }
        return descriptions;
    }

    private static void validatePortRanges(List<PortRange> portRanges) {
        if (portRanges == null || portRanges.isEmpty()) {
            throw new AwsException("InvalidArgumentException", "PortRanges is required.", 400);
        }
        for (PortRange range : portRanges) {
            Integer from = range.getFromPort();
            Integer to = range.getToPort();
            if (from == null || to == null || from < 1 || to > 65535 || from > to) {
                throw new AwsException("InvalidPortRangeException",
                        "The port range is not valid. FromPort must be between 1 and ToPort, "
                                + "and ToPort must not exceed 65535.", 400);
            }
        }
    }

    private static List<IpSet> buildIpSets(String ipAddressType, List<String> requestedIpv4Addresses) {
        List<IpSet> ipSets = new ArrayList<>();
        IpSet ipv4 = new IpSet();
        ipv4.setIpFamily("IPv4");
        ipv4.setIpAddressFamily("IPv4");
        ipv4.setIpAddresses(staticIpv4Addresses(requestedIpv4Addresses));
        ipSets.add(ipv4);

        if ("DUAL_STACK".equals(ipAddressType)) {
            IpSet ipv6 = new IpSet();
            ipv6.setIpFamily("IPv6");
            ipv6.setIpAddressFamily("IPv6");
            ipv6.setIpAddresses(List.of(randomIpv6(), randomIpv6()));
            ipSets.add(ipv6);
        }
        return ipSets;
    }

    private static List<String> firstIpv4Addresses(Accelerator accelerator) {
        return accelerator.getIpSets().stream()
                .filter(set -> "IPv4".equals(set.getIpAddressFamily()))
                .findFirst()
                .map(IpSet::getIpAddresses)
                .orElseGet(ArrayList::new);
    }

    /**
     * Global Accelerator assigns two static IPv4 addresses per accelerator, drawn from the
     * ranges it advertises. A caller that brought its own address pool (BYOIP) may pin one or
     * both, in which case the request wins and any shortfall is filled from the service pool.
     */
    private static List<String> staticIpv4Addresses(List<String> requested) {
        List<String> addresses = new ArrayList<>();
        if (requested != null) {
            requested.stream().filter(address -> address != null && !address.isBlank()).forEach(addresses::add);
        }
        String[] pools = {"75.2", "99.83"};
        int poolIndex = 0;
        while (addresses.size() < 2) {
            addresses.add(pools[poolIndex++] + "." + RANDOM.nextInt(256) + "." + RANDOM.nextInt(256));
        }
        return addresses;
    }

    private static String randomIpv6() {
        StringBuilder address = new StringBuilder("2600:9000:a400");
        for (int group = 0; group < 5; group++) {
            address.append(':').append(randomHex(4));
        }
        return address.toString();
    }

    private static AcceleratorAttributes defaultAttributes() {
        AcceleratorAttributes attributes = new AcceleratorAttributes();
        attributes.setFlowLogsEnabled(false);
        return attributes;
    }

    private static void requireArgument(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidArgumentException", field + " is required.", 400);
        }
    }

    private static String randomId(int length) {
        StringBuilder id = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static String randomHex(int length) {
        StringBuilder hex = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            hex.append(HEX.charAt(RANDOM.nextInt(HEX.length())));
        }
        return hex.toString().toLowerCase(Locale.ROOT);
    }
}
