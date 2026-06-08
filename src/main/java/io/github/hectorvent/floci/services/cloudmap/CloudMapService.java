package io.github.hectorvent.floci.services.cloudmap;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudmap.model.Instance;
import io.github.hectorvent.floci.services.cloudmap.model.Namespace;
import io.github.hectorvent.floci.services.cloudmap.model.Operation;
import io.github.hectorvent.floci.services.cloudmap.model.Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cloud Map (AWS service discovery) business logic. Mirrors the EventBridge service
 * pattern: ID-keyed account-aware storage, {@link AwsException} for domain errors.
 * Async operations apply their effect synchronously and record an {@link Operation}
 * whose status reaches SUCCESS immediately (or after a configurable delay).
 */
@ApplicationScoped
public class CloudMapService {

    private static final Logger LOG = Logger.getLogger(CloudMapService.class);
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StorageBackend<String, Namespace> namespaceStore;
    private final StorageBackend<String, Service> serviceStore;
    private final StorageBackend<String, Instance> instanceStore;
    private final StorageBackend<String, Operation> operationStore;
    private final RegionResolver regionResolver;
    private final int completionDelaySeconds;
    private final ScheduledExecutorService scheduler;

    @Inject
    public CloudMapService(StorageFactory storageFactory, EmulatorConfig config,
                           RegionResolver regionResolver) {
        this.namespaceStore = storageFactory.create("cloudmap", "cloudmap-namespaces.json",
                new TypeReference<Map<String, Namespace>>() {});
        this.serviceStore = storageFactory.create("cloudmap", "cloudmap-services.json",
                new TypeReference<Map<String, Service>>() {});
        this.instanceStore = storageFactory.create("cloudmap", "cloudmap-instances.json",
                new TypeReference<Map<String, Instance>>() {});
        this.operationStore = storageFactory.create("cloudmap", "cloudmap-operations.json",
                new TypeReference<Map<String, Operation>>() {});
        this.regionResolver = regionResolver;
        this.completionDelaySeconds = config.services().cloudmap().operationCompletionDelaySeconds();
        this.scheduler = completionDelaySeconds > 0
                ? Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "cloudmap-ops");
                    t.setDaemon(true);
                    return t;
                })
                : null;
    }

    // ──────────────────────────── Namespaces ────────────────────────────

    public Operation createHttpNamespace(String name, String creatorRequestId,
                                         String description, Map<String, String> tags, String region) {
        Namespace ns = newNamespace(name, "HTTP", description, creatorRequestId, tags, region);
        namespaceStore.put(ns.getId(), ns);
        return submitOperation("CREATE_NAMESPACE", "NAMESPACE", ns.getId(), region);
    }

    public Operation createPublicDnsNamespace(String name, String creatorRequestId,
                                              String description, Map<String, String> tags, String region) {
        Namespace ns = newNamespace(name, "DNS_PUBLIC", description, creatorRequestId, tags, region);
        ns.setHostedZoneId(generateHostedZoneId());
        namespaceStore.put(ns.getId(), ns);
        return submitOperation("CREATE_NAMESPACE", "NAMESPACE", ns.getId(), region);
    }

    public Operation createPrivateDnsNamespace(String name, String vpc, String creatorRequestId,
                                               String description, Map<String, String> tags, String region) {
        if (vpc == null || vpc.isBlank()) {
            throw new AwsException("InvalidInput", "Vpc is required for a private DNS namespace.", 400);
        }
        Namespace ns = newNamespace(name, "DNS_PRIVATE", description, creatorRequestId, tags, region);
        ns.setVpc(vpc);
        ns.setHostedZoneId(generateHostedZoneId());
        namespaceStore.put(ns.getId(), ns);
        return submitOperation("CREATE_NAMESPACE", "NAMESPACE", ns.getId(), region);
    }

    private Namespace newNamespace(String name, String type, String description,
                                   String creatorRequestId, Map<String, String> tags, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInput", "Namespace name is required.", 400);
        }
        boolean exists = scan(namespaceStore).stream()
                .anyMatch(n -> region.equals(n.getRegion()) && name.equals(n.getName()));
        if (exists) {
            throw new AwsException("NamespaceAlreadyExists",
                    "A namespace named \"" + name + "\" already exists.", 400);
        }
        Namespace ns = new Namespace();
        ns.setId("ns-" + randomId(20));
        ns.setName(name);
        ns.setType(type);
        ns.setDescription(description);
        ns.setCreatorRequestId(creatorRequestId != null ? creatorRequestId : UUID.randomUUID().toString());
        ns.setCreateDate(Instant.now());
        ns.setRegion(region);
        ns.setServiceCount(0);
        if (tags != null) {
            ns.setTags(tags);
        }
        ns.setArn(regionResolver.buildArn("servicediscovery", region, "namespace/" + ns.getId()));
        return ns;
    }

    public Namespace getNamespace(String id) {
        return requireNamespace(id);
    }

    public List<Namespace> listNamespaces(String region) {
        List<Namespace> result = new ArrayList<>();
        for (Namespace n : scan(namespaceStore)) {
            if (region.equals(n.getRegion())) {
                result.add(n);
            }
        }
        return result;
    }

    public Operation deleteNamespace(String id, String region) {
        Namespace ns = requireNamespace(id);
        boolean hasServices = scan(serviceStore).stream()
                .anyMatch(s -> ns.getId().equals(s.getNamespaceId()));
        if (hasServices) {
            throw new AwsException("ResourceInUse",
                    "The namespace contains existing services and cannot be deleted.", 400);
        }
        namespaceStore.delete(id);
        return submitOperation("DELETE_NAMESPACE", "NAMESPACE", id, region);
    }

    // ──────────────────────────── Services ────────────────────────────

    public Service createService(String name, String namespaceId, String creatorRequestId,
                                 String description, String dnsConfig, String healthCheckConfig,
                                 String healthCheckCustomConfig, String type,
                                 Map<String, String> tags, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInput", "Service name is required.", 400);
        }
        String resolvedNamespaceId = namespaceId;
        if (dnsConfig != null && resolvedNamespaceId == null) {
            // DnsConfig may carry the namespace id when the top-level field is absent.
            resolvedNamespaceId = dnsConfigNamespaceId(dnsConfig);
        }
        if (resolvedNamespaceId != null) {
            requireNamespace(resolvedNamespaceId);
        }
        final String nsId = resolvedNamespaceId;
        boolean exists = scan(serviceStore).stream()
                .anyMatch(s -> region.equals(s.getRegion()) && name.equals(s.getName())
                        && java.util.Objects.equals(nsId, s.getNamespaceId()));
        if (exists) {
            throw new AwsException("ServiceAlreadyExists",
                    "A service named \"" + name + "\" already exists.", 400);
        }
        Service service = new Service();
        service.setId("srv-" + randomId(20));
        service.setName(name);
        service.setNamespaceId(nsId);
        service.setDescription(description);
        service.setDnsConfig(dnsConfig);
        service.setHealthCheckConfig(healthCheckConfig);
        service.setHealthCheckCustomConfig(healthCheckCustomConfig);
        service.setType(resolveServiceType(type, dnsConfig));
        service.setCreatorRequestId(creatorRequestId != null ? creatorRequestId : UUID.randomUUID().toString());
        service.setCreateDate(Instant.now());
        service.setRegion(region);
        service.setInstanceCount(0);
        service.setRevision(0L);
        if (tags != null) {
            service.setTags(tags);
        }
        service.setArn(regionResolver.buildArn("servicediscovery", region, "service/" + service.getId()));
        serviceStore.put(service.getId(), service);
        if (nsId != null) {
            namespaceStore.get(nsId).ifPresent(n -> {
                n.setServiceCount(n.getServiceCount() + 1);
                namespaceStore.put(n.getId(), n);
            });
        }
        return service;
    }

    public Service getService(String id) {
        return requireService(id);
    }

    public List<Service> listServices(String region, String namespaceIdFilter) {
        List<Service> result = new ArrayList<>();
        for (Service s : scan(serviceStore)) {
            if (!region.equals(s.getRegion())) {
                continue;
            }
            if (namespaceIdFilter != null && !namespaceIdFilter.equals(s.getNamespaceId())) {
                continue;
            }
            result.add(s);
        }
        return result;
    }

    public void deleteService(String id) {
        Service service = requireService(id);
        boolean hasInstances = !scanInstances(service.getId()).isEmpty();
        if (hasInstances) {
            throw new AwsException("ResourceInUse",
                    "The service contains registered instances and cannot be deleted.", 400);
        }
        serviceStore.delete(id);
        if (service.getNamespaceId() != null) {
            namespaceStore.get(service.getNamespaceId()).ifPresent(n -> {
                n.setServiceCount(Math.max(0, n.getServiceCount() - 1));
                namespaceStore.put(n.getId(), n);
            });
        }
    }

    // ──────────────────────────── Instances ────────────────────────────

    public Operation registerInstance(String serviceId, String instanceId, String creatorRequestId,
                                      Map<String, String> attributes, String region) {
        Service service = requireService(serviceId);
        if (instanceId == null || instanceId.isBlank()) {
            throw new AwsException("InvalidInput", "InstanceId is required.", 400);
        }
        if (attributes == null || attributes.isEmpty()) {
            throw new AwsException("InvalidInput", "Attributes are required.", 400);
        }
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        instance.setServiceId(serviceId);
        instance.setCreatorRequestId(creatorRequestId != null ? creatorRequestId : UUID.randomUUID().toString());
        instance.setAttributes(attributes);
        boolean isNew = instanceStore.get(instanceKey(serviceId, instanceId)).isEmpty();
        instanceStore.put(instanceKey(serviceId, instanceId), instance);
        if (isNew) {
            service.setInstanceCount(service.getInstanceCount() + 1);
        }
        service.setRevision(service.getRevision() + 1);
        serviceStore.put(service.getId(), service);
        return submitOperation("REGISTER_INSTANCE", "INSTANCE", instanceId, region);
    }

    public Operation deregisterInstance(String serviceId, String instanceId, String region) {
        Service service = requireService(serviceId);
        if (instanceStore.get(instanceKey(serviceId, instanceId)).isEmpty()) {
            throw new AwsException("InstanceNotFound", "Instance not found: " + instanceId, 404);
        }
        instanceStore.delete(instanceKey(serviceId, instanceId));
        service.setInstanceCount(Math.max(0, service.getInstanceCount() - 1));
        service.setRevision(service.getRevision() + 1);
        serviceStore.put(service.getId(), service);
        return submitOperation("DEREGISTER_INSTANCE", "INSTANCE", instanceId, region);
    }

    public Instance getInstance(String serviceId, String instanceId) {
        requireService(serviceId);
        return instanceStore.get(instanceKey(serviceId, instanceId))
                .orElseThrow(() -> new AwsException("InstanceNotFound", "Instance not found: " + instanceId, 404));
    }

    public List<Instance> listInstances(String serviceId) {
        requireService(serviceId);
        return scanInstances(serviceId);
    }

    public Map<String, String> getInstancesHealthStatus(String serviceId, List<String> instanceIds) {
        requireService(serviceId);
        Map<String, String> status = new LinkedHashMap<>();
        for (Instance i : scanInstances(serviceId)) {
            if (instanceIds == null || instanceIds.isEmpty() || instanceIds.contains(i.getInstanceId())) {
                status.put(i.getInstanceId(), i.getHealthStatus());
            }
        }
        if (status.isEmpty() && instanceIds != null && !instanceIds.isEmpty()) {
            throw new AwsException("InstanceNotFound", "No matching instances found.", 404);
        }
        return status;
    }

    // ──────────────────────────── Discovery ────────────────────────────

    public record DiscoverResult(List<Instance> instances, long revision) {}

    public DiscoverResult discoverInstances(String namespaceName, String serviceName,
                                            String healthFilter, Map<String, String> queryParameters,
                                            Integer maxResults, String region) {
        Service service = resolveService(namespaceName, serviceName, region);
        List<Instance> all = scanInstances(service.getId());
        List<Instance> filtered = applyHealthFilter(all, healthFilter);
        if (queryParameters != null && !queryParameters.isEmpty()) {
            filtered = filtered.stream()
                    .filter(i -> queryParameters.entrySet().stream()
                            .allMatch(e -> e.getValue().equals(i.getAttributes().get(e.getKey()))))
                    .toList();
        }
        if (maxResults != null && maxResults > 0 && filtered.size() > maxResults) {
            filtered = filtered.subList(0, maxResults);
        }
        return new DiscoverResult(filtered, service.getRevision());
    }

    public long discoverInstancesRevision(String namespaceName, String serviceName, String region) {
        return resolveService(namespaceName, serviceName, region).getRevision();
    }

    private List<Instance> applyHealthFilter(List<Instance> instances, String healthFilter) {
        String filter = healthFilter == null ? "ALL" : healthFilter;
        return switch (filter) {
            case "HEALTHY" -> instances.stream().filter(i -> "HEALTHY".equals(i.getHealthStatus())).toList();
            case "UNHEALTHY" -> instances.stream().filter(i -> "UNHEALTHY".equals(i.getHealthStatus())).toList();
            case "HEALTHY_OR_ELSE_ALL" -> {
                List<Instance> healthy = instances.stream()
                        .filter(i -> "HEALTHY".equals(i.getHealthStatus())).toList();
                yield healthy.isEmpty() ? instances : healthy;
            }
            default -> instances;
        };
    }

    private Service resolveService(String namespaceName, String serviceName, String region) {
        Namespace ns = scan(namespaceStore).stream()
                .filter(n -> region.equals(n.getRegion()) && namespaceName.equals(n.getName()))
                .findFirst()
                .orElseThrow(() -> new AwsException("NamespaceNotFound",
                        "Namespace not found: " + namespaceName, 404));
        return scan(serviceStore).stream()
                .filter(s -> serviceName.equals(s.getName()) && ns.getId().equals(s.getNamespaceId()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ServiceNotFound",
                        "Service not found: " + serviceName, 404));
    }

    // ──────────────────────────── Operations ────────────────────────────

    public Operation getOperation(String id) {
        return operationStore.get(id)
                .orElseThrow(() -> new AwsException("OperationNotFound", "Operation not found: " + id, 404));
    }

    public List<Operation> listOperations(String region, Map<String, List<String>> filters) {
        List<Operation> result = new ArrayList<>();
        for (Operation op : scan(operationStore)) {
            if (!region.equals(op.getRegion())) {
                continue;
            }
            if (matchesOperationFilters(op, filters)) {
                result.add(op);
            }
        }
        return result;
    }

    private boolean matchesOperationFilters(Operation op, Map<String, List<String>> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, List<String>> f : filters.entrySet()) {
            List<String> values = f.getValue();
            boolean ok = switch (f.getKey()) {
                case "STATUS" -> values.contains(op.getStatus());
                case "TYPE" -> values.contains(op.getType());
                case "NAMESPACE_ID" -> values.contains(op.getTargets().get("NAMESPACE"));
                case "SERVICE_ID" -> values.contains(op.getTargets().get("SERVICE"));
                default -> true;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private Operation submitOperation(String type, String targetType, String targetId, String region) {
        Operation op = new Operation();
        op.setId(randomId(32) + "-" + randomId(8));
        op.setType(type);
        op.setCreateDate(Instant.now());
        op.setUpdateDate(Instant.now());
        op.setRegion(region);
        op.getTargets().put(targetType, targetId);
        if (scheduler == null) {
            op.setStatus("SUCCESS");
            operationStore.put(op.getId(), op);
        } else {
            op.setStatus("PENDING");
            operationStore.put(op.getId(), op);
            String accountId = regionResolver.getAccountId();
            scheduler.schedule(() -> completeOperation(accountId, op.getId()),
                    completionDelaySeconds, TimeUnit.SECONDS);
        }
        return op;
    }

    private void completeOperation(String accountId, String operationId) {
        try {
            if (operationStore instanceof AccountAwareStorageBackend<Operation> aware) {
                aware.getForAccount(accountId, operationId).ifPresent(op -> {
                    op.setStatus("SUCCESS");
                    op.setUpdateDate(Instant.now());
                    aware.putForAccount(accountId, operationId, op);
                });
            }
        } catch (Exception e) {
            LOG.warnv("Failed to complete Cloud Map operation {0}: {1}", operationId, e.getMessage());
        }
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn) {
        return taggable(resourceArn).getTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        Object resource = taggableRaw(resourceArn);
        if (resource instanceof Namespace ns) {
            ns.getTags().putAll(tags);
            namespaceStore.put(ns.getId(), ns);
        } else if (resource instanceof Service s) {
            s.getTags().putAll(tags);
            serviceStore.put(s.getId(), s);
        }
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        Object resource = taggableRaw(resourceArn);
        if (resource instanceof Namespace ns) {
            tagKeys.forEach(ns.getTags()::remove);
            namespaceStore.put(ns.getId(), ns);
        } else if (resource instanceof Service s) {
            tagKeys.forEach(s.getTags()::remove);
            serviceStore.put(s.getId(), s);
        }
    }

    private interface Taggable { Map<String, String> getTags(); }

    private Taggable taggable(String resourceArn) {
        Object raw = taggableRaw(resourceArn);
        if (raw instanceof Namespace ns) {
            return ns::getTags;
        }
        return ((Service) raw)::getTags;
    }

    private Object taggableRaw(String resourceArn) {
        if (resourceArn == null) {
            throw new AwsException("InvalidInput", "ResourceARN is required.", 400);
        }
        int slash = resourceArn.lastIndexOf('/');
        int colon = resourceArn.lastIndexOf(':');
        String id = resourceArn.substring(Math.max(slash, colon) + 1);
        Optional<Namespace> ns = namespaceStore.get(id);
        if (ns.isPresent()) {
            return ns.get();
        }
        return serviceStore.get(id).orElseThrow(() ->
                new AwsException("ResourceNotFoundException", "Resource not found: " + resourceArn, 404));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private Namespace requireNamespace(String id) {
        if (id == null) {
            throw new AwsException("InvalidInput", "Namespace id is required.", 400);
        }
        return namespaceStore.get(id)
                .orElseThrow(() -> new AwsException("NamespaceNotFound", "Namespace not found: " + id, 404));
    }

    private Service requireService(String id) {
        if (id == null) {
            throw new AwsException("InvalidInput", "Service id is required.", 400);
        }
        return serviceStore.get(id)
                .orElseThrow(() -> new AwsException("ServiceNotFound", "Service not found: " + id, 404));
    }

    private List<Instance> scanInstances(String serviceId) {
        List<Instance> result = new ArrayList<>();
        for (Instance i : scan(instanceStore)) {
            if (serviceId.equals(i.getServiceId())) {
                result.add(i);
            }
        }
        return result;
    }

    private <V> List<V> scan(StorageBackend<String, V> store) {
        return store.scan(k -> true);
    }

    private String instanceKey(String serviceId, String instanceId) {
        return serviceId + "/" + instanceId;
    }

    private String resolveServiceType(String type, String dnsConfig) {
        if (type != null && !type.isBlank()) {
            return "HTTP".equals(type) ? "HTTP" : type;
        }
        return dnsConfig != null ? "DNS_HTTP" : "HTTP";
    }

    private String dnsConfigNamespaceId(String dnsConfig) {
        // Best-effort extraction of a NamespaceId embedded in the DnsConfig JSON.
        int idx = dnsConfig.indexOf("\"NamespaceId\"");
        if (idx < 0) {
            return null;
        }
        int start = dnsConfig.indexOf('"', dnsConfig.indexOf(':', idx) + 1);
        int end = dnsConfig.indexOf('"', start + 1);
        return (start > 0 && end > start) ? dnsConfig.substring(start + 1, end) : null;
    }

    private String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    private String generateHostedZoneId() {
        StringBuilder sb = new StringBuilder("Z");
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < 13; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
