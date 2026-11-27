package io.floci.aws.services.cloudmap;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.core.common.dns.DnsRecord;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudmap.model.Instance;
import io.floci.aws.services.cloudmap.model.Namespace;
import io.floci.aws.services.cloudmap.model.Operation;
import io.floci.aws.services.cloudmap.model.Service;
import io.floci.aws.services.route53.Route53Service;
import io.floci.aws.services.route53.model.VpcAssociation;
import io.netty.util.NetUtil;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Cloud Map (AWS service discovery) business logic. Mirrors the EventBridge service
 * pattern: ID-keyed account-aware storage, {@link AwsException} for domain errors.
 * Async operations apply their effect synchronously and record an {@link Operation}
 * whose status reaches SUCCESS immediately (or after a configurable delay).
 */
@ApplicationScoped
public class CloudMapService implements StoredResourceArns {

    private static final Logger LOG = Logger.getLogger(CloudMapService.class);
    private static final String SERVICE = "servicediscovery";
    private static final String NAMESPACE_RESOURCE = "namespace/";
    private static final String SERVICE_RESOURCE = "service/";
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    /** Route 53 answers a service discovery query with at most eight records. */
    private static final int MAX_DNS_ANSWERS = 8;
    /** FailureThreshold is no longer supported: AWS always sets it to 1. */
    private static final String HEALTH_CHECK_CUSTOM_CONFIG = "{\"FailureThreshold\":1}";

    private final SecureRandom random = new SecureRandom();

    private final StorageBackend<String, Namespace> namespaceStore;
    private final StorageBackend<String, Service> serviceStore;
    private final StorageBackend<String, Instance> instanceStore;
    private final StorageBackend<String, Operation> operationStore;
    private final RegionResolver regionResolver;
    private final Route53Service route53Service;
    private final ObjectMapper objectMapper;
    private final int completionDelaySeconds;
    private final ScheduledExecutorService scheduler;

    @Inject
    public CloudMapService(StorageFactory storageFactory, EmulatorConfig config,
                           RegionResolver regionResolver, ObjectMapper objectMapper,
                           Route53Service route53Service) {
        this.namespaceStore = storageFactory.create("cloudmap", "cloudmap-namespaces.json",
                new TypeReference<Map<String, Namespace>>() {});
        this.serviceStore = storageFactory.create("cloudmap", "cloudmap-services.json",
                new TypeReference<Map<String, Service>>() {});
        this.instanceStore = storageFactory.create("cloudmap", "cloudmap-instances.json",
                new TypeReference<Map<String, Instance>>() {});
        this.operationStore = storageFactory.create("cloudmap", "cloudmap-operations.json",
                new TypeReference<Map<String, Operation>>() {});
        this.regionResolver = regionResolver;
        this.route53Service = route53Service;
        this.objectMapper = objectMapper;
        this.completionDelaySeconds = config.services().cloudmap().operationCompletionDelaySeconds();
        this.scheduler = completionDelaySeconds > 0
                ? Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "cloudmap-ops");
                    t.setDaemon(true);
                    return t;
                })
                : null;
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
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
        ns.setHostedZoneId(createZone(ns, null));
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
        ns.setHostedZoneId(createZone(ns, new VpcAssociation(vpc, region)));
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
        ns.setArn(regionResolver.buildArn(SERVICE, region, NAMESPACE_RESOURCE + ns.getId()));
        return ns;
    }

    public Namespace getNamespace(String id) {
        return requireNamespace(id);
    }

    /** The namespace of the id as GetNamespace answers it in the Region: only one created there. */
    public Namespace getNamespace(String id, String region) {
        return requireNamespace(id, region);
    }

    /** Whether a namespace is a DNS one, public or private, whose service names are DNS labels. */
    public static boolean isDnsNamespace(Namespace namespace) {
        return "DNS_PRIVATE".equals(namespace.getType()) || "DNS_PUBLIC".equals(namespace.getType());
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

    public PaginatedResult<Namespace> listNamespaces(String region, Integer maxResults, String nextToken) {
        return paginate(listNamespaces(region), Namespace::getId, maxResults, nextToken,
                "ListNamespaces", region, null);
    }

    public void updateNamespace(String id, String description) {
        Namespace ns = requireNamespace(id);
        ns.setDescription(description);
        namespaceStore.put(id, ns);
    }

    public Operation deleteNamespace(String id, String region) {
        Namespace ns = requireNamespace(id, region);
        boolean hasServices = scan(serviceStore).stream()
                .anyMatch(s -> ns.getId().equals(s.getNamespaceId()));
        if (hasServices) {
            throw new AwsException("ResourceInUse",
                    "The namespace contains existing services and cannot be deleted.", 400);
        }
        if (ns.getHostedZoneId() != null) {
            // The zone goes with the namespace. A zone holding records beyond the default NS/SOA
            // is refused (HostedZoneNotEmpty) and the namespace is kept, rather than orphaning records.
            try {
                route53Service.deleteHostedZone(ns.getHostedZoneId());
            } catch (AwsException e) {
                if (!"NoSuchHostedZone".equals(e.getErrorCode())) {
                    throw e;
                }
                // Already deleted through Route 53; nothing is orphaned, so the namespace still goes.
                LOG.debugv("Hosted zone {0} of Cloud Map namespace {1} was already deleted",
                        ns.getHostedZoneId(), id);
            }
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
        JsonNode dnsConfigNode = dnsConfig == null ? null : parseDnsConfig(dnsConfig);
        String resolvedNamespaceId = namespaceId;
        if (dnsConfigNode != null && resolvedNamespaceId == null) {
            // DnsConfig may carry the namespace id when the top-level field is absent.
            resolvedNamespaceId = dnsConfigNode.path("NamespaceId").textValue();
        }
        if (resolvedNamespaceId != null) {
            requireNamespace(resolvedNamespaceId, region);
        }
        final String nsId = resolvedNamespaceId;
        if (findServiceByName(name, nsId, region).isPresent()) {
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
        service.setHealthCheckCustomConfig(healthCheckCustomConfig == null ? null : HEALTH_CHECK_CUSTOM_CONFIG);
        service.setType(resolveServiceType(type, dnsConfig));
        service.setCreatorRequestId(creatorRequestId != null ? creatorRequestId : UUID.randomUUID().toString());
        service.setCreateDate(Instant.now());
        service.setRegion(region);
        service.setInstanceCount(0);
        service.setRevision(0L);
        if (tags != null) {
            service.setTags(tags);
        }
        service.setArn(regionResolver.buildArn(SERVICE, region, SERVICE_RESOURCE + service.getId()));
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

    /** The service of the id as GetService answers it in the Region: only one created there. */
    public Service getService(String id, String region) {
        return requireService(id, region);
    }

    /**
     * The service named {@code name} in the namespace {@code namespaceId} of {@code region}, of
     * which there is at most one. A DNS namespace compares names without case, as DNS labels are.
     */
    public Optional<Service> findServiceByName(String name, String namespaceId, String region) {
        Namespace namespace = namespaceId == null ? null : namespaceStore.get(namespaceId)
                .filter(n -> answeredIn(n.getRegion(), region)).orElse(null);
        boolean dnsNamespace = namespace != null && isDnsNamespace(namespace);
        return scan(serviceStore).stream()
                .filter(s -> region.equals(s.getRegion()) && Objects.equals(namespaceId, s.getNamespaceId())
                        && (dnsNamespace ? name.equalsIgnoreCase(s.getName()) : name.equals(s.getName())))
                .findFirst();
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

    public PaginatedResult<Service> listServices(String region, String namespaceIdFilter,
                                                  Integer maxResults, String nextToken) {
        PaginatedResult<Service> page = paginate(listServices(region, null), Service::getId,
                maxResults, nextToken, "ListServices", region, namespaceIdFilter);
        // AWS applies filters after selecting the page, so an empty page can still have a token.
        List<Service> items = page.items().stream()
                .filter(s -> namespaceIdFilter == null || namespaceIdFilter.equals(s.getNamespaceId())).toList();
        return new PaginatedResult<>(items, page.nextToken());
    }

    /**
     * Only the TTLs of the existing DNS records can change, as on AWS: any other DnsConfig change
     * (record types or count, RoutingPolicy), or a DnsConfig for a service without one, is rejected.
     * A null {@code dnsConfig} keeps the stored one, since AWS cannot remove it; a null description
     * or health check config clears it, as AWS UpdateService does.
     */
    public void updateService(String id, String description, String dnsConfig, String healthCheckConfig) {
        Service service = requireService(id);
        if (dnsConfig != null) {
            if (!withoutTtls(parseDnsConfig(dnsConfig)).equals(withoutTtls(dnsConfigNode(service)))) {
                throw new AwsException("InvalidInput",
                        "Only the TTLs of a service's DNS records can be updated.", 400);
            }
            service.setDnsConfig(dnsConfig);
        }
        service.setDescription(description);
        service.setHealthCheckConfig(healthCheckConfig);
        serviceStore.put(id, service);
    }

    public void deleteService(String id) {
        deleteService(requireService(id));
    }

    /** Deletes the service of the id as DeleteService does in the Region: only one created there. */
    public void deleteService(String id, String region) {
        deleteService(requireService(id, region));
    }

    private void deleteService(Service service) {
        boolean hasInstances = !scanInstances(service.getId()).isEmpty();
        if (hasInstances) {
            throw new AwsException("ResourceInUse",
                    "The service contains registered instances and cannot be deleted.", 400);
        }
        serviceStore.delete(service.getId());
        if (service.getNamespaceId() != null) {
            namespaceStore.get(service.getNamespaceId())
                    .filter(n -> answeredIn(n.getRegion(), service.getRegion())).ifPresent(n -> {
                n.setServiceCount(Math.max(0, n.getServiceCount() - 1));
                namespaceStore.put(n.getId(), n);
            });
        }
    }

    // ──────────────────────────── Instances ────────────────────────────

    public Operation registerInstance(String serviceId, String instanceId, String creatorRequestId,
                                      Map<String, String> attributes, String region) {
        Service service = requireService(serviceId, region);
        if (instanceId == null || instanceId.isBlank()) {
            throw new AwsException("InvalidInput", "InstanceId is required.", 400);
        }
        if (attributes == null || attributes.isEmpty()) {
            throw new AwsException("InvalidInput", "Attributes are required.", 400);
        }
        if (dnsRecordTtl(service, "SRV") >= 0 && service.getNamespaceId() != null) {
            Namespace namespace = requireNamespace(service.getNamespaceId());
            if (isDnsNamespace(namespace)) {
                try {
                    DnsRecord.encodeName(instanceId + "." + service.getName() + "." + namespace.getName());
                } catch (IllegalArgumentException e) {
                    throw new AwsException("InvalidInput",
                            "The SRV target must have ASCII labels of 1 to 63 bytes and at most 253 characters.", 400);
                }
            }
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
        Service service = requireService(serviceId, region);
        if (instanceStore.get(instanceKey(serviceId, instanceId)).isEmpty()) {
            throw new AwsException("InstanceNotFound", "Instance not found: " + instanceId, 404);
        }
        instanceStore.delete(instanceKey(serviceId, instanceId));
        service.setInstanceCount(Math.max(0, service.getInstanceCount() - 1));
        service.setRevision(service.getRevision() + 1);
        serviceStore.put(service.getId(), service);
        return submitOperation("DEREGISTER_INSTANCE", "INSTANCE", instanceId, region);
    }

    public Instance getInstance(String serviceId, String instanceId, String region) {
        requireService(serviceId, region);
        return instanceStore.get(instanceKey(serviceId, instanceId))
                .orElseThrow(() -> new AwsException("InstanceNotFound", "Instance not found: " + instanceId, 404));
    }

    public List<Instance> listInstances(String serviceId) {
        requireService(serviceId);
        return scanInstances(serviceId);
    }

    /** The instances of the service of the id as ListInstances answers them in the Region: only one created there. */
    public List<Instance> listInstances(String serviceId, String region) {
        requireService(serviceId, region);
        return scanInstances(serviceId);
    }

    public Map<String, String> getInstancesHealthStatus(String serviceId, List<String> instanceIds, String region) {
        requireService(serviceId, region);
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
        List<Instance> filtered = filterInstances(service.getId(), healthFilter, queryParameters);
        if (maxResults != null && maxResults > 0 && filtered.size() > maxResults) {
            filtered = filtered.subList(0, maxResults);
        }
        return new DiscoverResult(filtered, service.getRevision());
    }

    public List<Instance> filterInstances(String serviceId, String healthFilter,
                                          Map<String, String> queryParameters) {
        List<Instance> filtered = applyHealthFilter(listInstances(serviceId), healthFilter);
        if (queryParameters != null && !queryParameters.isEmpty()) {
            filtered = filtered.stream()
                    .filter(i -> queryParameters.entrySet().stream()
                            .allMatch(e -> e.getValue().equals(attributesOf(i).get(e.getKey()))))
                    .toList();
        }
        return filtered;
    }

    private static Map<String, String> attributesOf(Instance instance) {
        return instance.getAttributes() != null ? instance.getAttributes() : Map.of();
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

    /**
     * Resolves {@code <service>.<namespace>} to the IPv4 addresses registered under it, so the
     * embedded DNS server can answer for a DNS namespace. The DNS server forwards names outside
     * every DNS namespace upstream, while a name inside a namespace remains owned even when no
     * usable A records exist.
     *
     * <p>Only DNS namespaces answer. An HTTP namespace is discoverable through DiscoverInstances
     * and has no DNS records on AWS, so giving it any here would invent behaviour callers cannot
     * rely on outside Floci.
     *
     * <p>Unlike {@link #discoverInstances}, this takes no region: a DNS query carries none. The
     * longest matching namespace name wins, and a namespace name registered in more than one
     * region resolves through whichever copy holds instances.
     *
     * <p>A DNS query carries no credential either, so this reads the default account's
     * partition. A namespace registered under a non-default account is discoverable through
     * the API but does not resolve by name.
     *
     * <p>Health and answer size follow Route 53: healthy instances answer while any exist, all
     * of them answer when none is healthy, and at most {@link #MAX_DNS_ANSWERS} records go back
     * either way.
     */
    public List<String> resolveDnsName(String queryName) {
        return resolveDnsNameIfOwned(queryName)
                .map(DnsAnswer::addresses).orElse(List.of());
    }

    /** Keeps zone ownership distinct from the A records and their TTL. */
    public Optional<DnsAnswer> resolveDnsNameIfOwned(String queryName) {
        return resolveDnsNameIfOwned(queryName, 1);
    }

    public Optional<DnsAnswer> resolveDnsNameIfOwned(String queryName, int type) {
        return resolveDnsNameIfOwned(queryName, type, dnsNamespacesByLongestName());
    }

    public Optional<DnsAnswer> resolveDnsNameIfOwned(String queryName, int type, ClientVpc origin) {
        Set<String> privateZones = route53Service.privateDnsZoneIds(queryName, origin);
        List<Namespace> visible = dnsNamespacesByLongestName().stream()
                .filter(namespace -> "DNS_PRIVATE".equals(namespace.getType())
                        ? privateZones.contains(namespace.getHostedZoneId())
                        : privateZones.isEmpty())
                .toList();
        return resolveDnsNameIfOwned(queryName, type, visible);
    }

    private Optional<DnsAnswer> resolveDnsNameIfOwned(String queryName, int type, List<Namespace> namespaces) {
        if (queryName == null || queryName.isBlank()) {
            return Optional.empty();
        }
        String name = queryName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }

        String matchedNamespaceName = null;
        boolean nameExists = false;
        for (Namespace namespace : namespaces) {
            String namespaceName = namespace.getName().toLowerCase(Locale.ROOT);
            String suffix = "." + namespaceName;
            boolean apex = name.equals(namespaceName);
            if (!apex && !name.endsWith(suffix)) {
                continue;
            }
            if (matchedNamespaceName != null && !matchedNamespaceName.equals(namespaceName)) {
                break;
            }
            matchedNamespaceName = namespaceName;
            if (apex) {
                nameExists = true;
                continue;
            }
            String label = name.substring(0, name.length() - suffix.length());
            List<Service> services = scan(serviceStore).stream()
                    .filter(service -> namespace.getId().equals(service.getNamespaceId()))
                    .toList();
            Optional<DnsAnswer> byService = resolveServiceName(services, label, namespace.getName(), type);
            if (byService.filter(answer -> !answer.isEmpty()).isPresent()) {
                return byService;
            }
            Optional<DnsAnswer> byInstance = resolveInstanceHostname(services, label, type);
            if (byInstance.filter(answer -> !answer.isEmpty()).isPresent()) {
                return byInstance;
            }
            nameExists |= byService.isPresent() || byInstance.isPresent();
        }
        return matchedNamespaceName != null
                ? Optional.of(nameExists ? DnsAnswer.noData() : DnsAnswer.nxDomain()) : Optional.empty();
    }

    /**
     * Requested records at {@code <service>.<namespace>}, preserving ownership for other types.
     */
    private Optional<DnsAnswer> resolveServiceName(List<Service> services, String label, String namespace, int type) {
        boolean exists = false;
        for (Service service : services) {
            if (!label.equalsIgnoreCase(service.getName())) {
                continue;
            }
            List<Instance> instances = scanInstances(service.getId());
            exists |= !instances.isEmpty();
            int recordType = type;
            String recordName = switch (type) {
                case 1 -> "A";
                case 5 -> "CNAME";
                case 28 -> "AAAA";
                case 33 -> "SRV";
                default -> "";
            };
            int ttl = dnsRecordTtl(service, recordName);
            if (ttl < 0 && dnsRecordTtl(service, "CNAME") >= 0) {
                recordType = 5;
                ttl = dnsRecordTtl(service, "CNAME");
            }
            if (ttl < 0) {
                continue;
            }
            List<DnsRecord> records = new ArrayList<>();
            for (Instance instance : applyHealthFilter(instances, "HEALTHY_OR_ELSE_ALL")) {
                DnsRecord record = instanceDnsRecord(instance, service, namespace, recordType);
                if (record != null) {
                    records.add(record);
                }
            }
            if (!records.isEmpty()) {
                if (recordType == 5 || "WEIGHTED".equals(dnsConfigNode(service).path("RoutingPolicy").asText())) {
                    return Optional.of(DnsAnswer.typedRecords(
                            List.of(records.get(ThreadLocalRandom.current().nextInt(records.size()))), ttl));
                }
                return Optional.of(DnsAnswer.typedRecords(records.size() > MAX_DNS_ANSWERS
                        ? records.subList(0, MAX_DNS_ANSWERS) : records, ttl));
            }
        }
        return exists ? Optional.of(DnsAnswer.noData()) : Optional.empty();
    }

    /**
     * The address records at an SRV record's target, {@code <InstanceId>.<service>.<namespace>},
     * published with the service's SRV TTL.
     */
    private Optional<DnsAnswer> resolveInstanceHostname(List<Service> services, String label, int type) {
        boolean exists = false;
        for (Service service : services) {
            String serviceSuffix = "." + service.getName().toLowerCase(Locale.ROOT);
            if (!label.endsWith(serviceSuffix)) {
                continue;
            }
            int ttl = dnsRecordTtl(service, "SRV");
            if (ttl < 0) {
                continue;
            }
            String instanceId = label.substring(0, label.length() - serviceSuffix.length());
            for (Instance instance : scanInstances(service.getId())) {
                if (!instanceId.equalsIgnoreCase(instance.getInstanceId())) {
                    continue;
                }
                exists = true;
                DnsRecord record = instanceDnsRecord(instance, service, "", type);
                if ((type == 1 || type == 28) && record != null) {
                    return Optional.of(DnsAnswer.typedRecords(List.of(record), ttl));
                }
            }
        }
        return exists ? Optional.of(DnsAnswer.noData()) : Optional.empty();
    }

    private DnsRecord instanceDnsRecord(Instance instance, Service service, String namespace, int type) {
        Map<String, String> attributes = instance.getAttributes();
        String ipv4 = attributes.get("AWS_INSTANCE_IPV4");
        String ipv6 = attributes.get("AWS_INSTANCE_IPV6");
        try {
            DnsRecord record = switch (type) {
                case 1 -> isIpv4(ipv4) ? new DnsRecord.Address(1, ipv4) : null;
                case 28 -> ipv6 != null && !ipv6.contains("%") && !ipv6.startsWith("[")
                        && NetUtil.isValidIpV6Address(ipv6) ? new DnsRecord.Address(28, ipv6) : null;
                case 5 -> attributes.get("AWS_INSTANCE_CNAME") != null
                        ? new DnsRecord.Cname(attributes.get("AWS_INSTANCE_CNAME")) : null;
                case 33 -> attributes.get("AWS_INSTANCE_PORT") != null
                        ? new DnsRecord.Srv(Integer.parseInt(attributes.get("AWS_INSTANCE_PORT")),
                                instance.getInstanceId() + "." + service.getName() + "." + namespace) : null;
                default -> null;
            };
            if (record != null) {
                record.data();
            }
            return record;
        } catch (IllegalArgumentException e) {
            LOG.debugv("Invalid DNS attributes for Cloud Map instance {0}: {1}",
                    instance.getInstanceId(), e.getMessage());
            return null;
        }
    }

    private JsonNode parseDnsConfig(String dnsConfig) {
        JsonNode config;
        try {
            config = objectMapper.readTree(dnsConfig);
        } catch (JacksonException e) {
            throw new AwsException("InvalidInput", "DnsConfig must be valid JSON.", 400);
        }
        JsonNode records = config == null ? MissingNode.getInstance() : config.path("DnsRecords");
        if (!records.isArray()) {
            throw new AwsException("InvalidInput", "DnsConfig.DnsRecords is required.", 400);
        }
        for (JsonNode record : records) {
            if (ttlSeconds(record.path("TTL")) < 0) {
                throw new AwsException("InvalidInput",
                        "DnsRecords TTL must be an integer from 0 to 2147483647.", 400);
            }
        }
        return config;
    }

    /** The DnsConfig as an update must keep it: record types in any order, TTLs free to change. */
    private static JsonNode withoutTtls(JsonNode dnsConfig) {
        JsonNode copy = dnsConfig.deepCopy();
        if (copy instanceof ObjectNode object) {
            List<String> types = new ArrayList<>();
            for (JsonNode record : object.path("DnsRecords")) {
                types.add(record.path("Type").asText());
            }
            types.sort(Comparator.naturalOrder());
            ArrayNode records = object.putArray("DnsRecords");
            types.forEach(records::add);
        }
        return copy;
    }

    /** Returns -1 when the service has no record of the requested type. */
    private int dnsRecordTtl(Service service, String type) {
        JsonNode records = dnsConfigNode(service).path("DnsRecords");
        if (!records.isArray()) {
            return "A".equals(type) ? DnsAnswer.DEFAULT_TTL_SECONDS : -1;
        }
        for (JsonNode record : records) {
            if (type.equalsIgnoreCase(record.path("Type").asText())) {
                int ttl = ttlSeconds(record.path("TTL"));
                return ttl >= 0 ? ttl : DnsAnswer.DEFAULT_TTL_SECONDS;
            }
        }
        return -1;
    }

    private static int ttlSeconds(JsonNode ttl) {
        if (!ttl.isIntegralNumber() || !ttl.canConvertToInt()) {
            return -1;
        }
        int value = ttl.intValue();
        return value >= 0 ? value : -1;
    }

    private JsonNode dnsConfigNode(Service service) {
        String dnsConfig = service.getDnsConfig();
        if (dnsConfig == null || dnsConfig.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(dnsConfig);
        } catch (JacksonException e) {
            LOG.debugv("Could not read the DnsConfig of Cloud Map service {0}: {1}",
                    service.getId(), e.getMessage());
            return MissingNode.getInstance();
        }
    }

    /**
     * Whether the attribute is a dotted-quad the DNS server can put in an A record's rdata.
     * AWS rejects anything else at {@code RegisterInstance}; Floci stores it, so a name whose
     * only instance carries a bad value receives a negative response rather than an invalid A
     * record or an answer from an unrelated upstream resolver.
     */
    private static boolean isIpv4(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int i = 0; i < octet.length(); i++) {
                if (!Character.isDigit(octet.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private List<Namespace> dnsNamespacesByLongestName() {
        return scan(namespaceStore).stream()
                .filter(n -> n.getName() != null && !n.getName().isBlank())
                .filter(CloudMapService::isDnsNamespace)
                .sorted(Comparator.comparingInt((Namespace n) -> n.getName().length()).reversed())
                .toList();
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

    public Operation getOperation(String id, String region) {
        return operationStore.get(id)
                .filter(op -> answeredIn(op.getRegion(), region))
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

    public PaginatedResult<Operation> listOperations(String region, Map<String, List<String>> filters,
                                                     Integer maxResults, String nextToken) {
        Map<String, List<String>> normalized = new TreeMap<>();
        if (filters != null) {
            filters.forEach((name, values) -> normalized.put(name, values.stream().distinct().sorted().toList()));
        }
        PaginatedResult<Operation> page = paginate(listOperations(region, null), Operation::getId,
                maxResults, nextToken, "ListOperations", region, normalized);
        List<Operation> items = page.items().stream().filter(op -> matchesOperationFilters(op, filters)).toList();
        return new PaginatedResult<>(items, page.nextToken());
    }

    private <T> PaginatedResult<T> paginate(List<T> items, Function<T, String> cursorOf,
                                           Integer maxResults, String nextToken,
                                           String action, String region, Object query) {
        String namespace = action + ":" + regionResolver.getAccountId() + ":" + region + ":"
                + Digests.sha256Hex(objectMapper.valueToTree(query).toString());
        return Pagination.paginate(items, cursorOf, maxResults, nextToken, 100, 100, 4096, "InvalidInput", namespace);
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
        return taggable(taggableRaw(resourceArn)).getTags();
    }

    /** The tags of the namespace or the service of the ARN as ListTagsForResource answers them in the Region. */
    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        return taggable(taggableRaw(resourceArn, region)).getTags();
    }

    /**
     * Gives the namespace or the service a new map holding its tags and {@code tags}. Tag calls run one at a time, so
     * two of them never lose one's tags, and a lookup reads one whole set.
     */
    public synchronized void tagResource(String resourceArn, Map<String, String> tags) {
        addTags(taggableRaw(resourceArn), tags);
    }

    /** Tags the namespace or the service of the ARN as TagResource does in the Region: only one created there. */
    public synchronized void tagResource(String resourceArn, Map<String, String> tags, String region) {
        addTags(taggableRaw(resourceArn, region), tags);
    }

    private void addTags(Object resource, Map<String, String> tags) {
        if (resource instanceof Namespace ns) {
            Map<String, String> merged = new LinkedHashMap<>(ns.getTags());
            merged.putAll(tags);
            ns.setTags(merged);
            namespaceStore.put(ns.getId(), ns);
        } else if (resource instanceof Service s) {
            Map<String, String> merged = new LinkedHashMap<>(s.getTags());
            merged.putAll(tags);
            s.setTags(merged);
            serviceStore.put(s.getId(), s);
        }
    }

    /** Gives the namespace or the service a new map without {@code tagKeys}, one tag call at a time. */
    public synchronized void untagResource(String resourceArn, List<String> tagKeys) {
        removeTags(taggableRaw(resourceArn), tagKeys);
    }

    /** Untags the namespace or the service of the ARN as UntagResource does in the Region: only one created there. */
    public synchronized void untagResource(String resourceArn, List<String> tagKeys, String region) {
        removeTags(taggableRaw(resourceArn, region), tagKeys);
    }

    private void removeTags(Object resource, List<String> tagKeys) {
        if (resource instanceof Namespace ns) {
            Map<String, String> remaining = new LinkedHashMap<>(ns.getTags());
            tagKeys.forEach(remaining::remove);
            ns.setTags(remaining);
            namespaceStore.put(ns.getId(), ns);
        } else if (resource instanceof Service s) {
            Map<String, String> remaining = new LinkedHashMap<>(s.getTags());
            tagKeys.forEach(remaining::remove);
            s.setTags(remaining);
            serviceStore.put(s.getId(), s);
        }
    }

    @Override
    public String namespace() {
        return SERVICE;
    }

    /** A request names a namespace or a service by the id its ARN ends in: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the namespace or the service whose ARN is {@code arn}, in the request's account, where the tag
     * handlers read: an ARN of another account names none. Each is read in place by the id its ARN ends in, one stored
     * before storage was account-aware included. That is the id the handlers find it by, so an ARN naming another
     * Region than the resource's reads its tags too, although a request sent there finds no resource. A tag without
     * a value reads as an empty one. Tag calls give the resource a new map, so the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, SERVICE)) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        if (!parsed.accountId().equals(account)) {
            return Map.of();
        }
        String resource = parsed.resource();
        Optional<Map<String, String>> tags = Optional.empty();
        if (resource.startsWith(NAMESPACE_RESOURCE)) {
            tags = AccountAwareStorageBackend.readInAccount(namespaceStore, account,
                    resource.substring(NAMESPACE_RESOURCE.length())).map(Namespace::getTags);
        } else if (resource.startsWith(SERVICE_RESOURCE)) {
            tags = AccountAwareStorageBackend.readInAccount(serviceStore, account,
                    resource.substring(SERVICE_RESOURCE.length())).map(Service::getTags);
        }
        Map<String, String> copy = new LinkedHashMap<>();
        tags.orElse(Map.of()).forEach((key, value) -> copy.put(key, value == null ? "" : value));
        return copy;
    }

    private interface Taggable { Map<String, String> getTags(); }

    private static Taggable taggable(Object raw) {
        if (raw instanceof Namespace ns) {
            return ns::getTags;
        }
        return ((Service) raw)::getTags;
    }

    private Object taggableRaw(String resourceArn) {
        if (resourceArn == null) {
            throw new AwsException("InvalidInput", "ResourceARN is required.", 400);
        }
        String id = AwsArnUtils.resourceIfArnFor(resourceArn, SERVICE)
                .map(resource -> resource.substring(resource.lastIndexOf('/') + 1))
                .orElse(resourceArn);
        Optional<Namespace> ns = namespaceStore.get(id);
        if (ns.isPresent()) {
            return ns.get();
        }
        return serviceStore.get(id).orElseThrow(() -> resourceNotFound(resourceArn));
    }

    private Object taggableRaw(String resourceArn, String region) {
        Object raw = taggableRaw(resourceArn);
        String createdIn = raw instanceof Namespace ns ? ns.getRegion() : ((Service) raw).getRegion();
        if (!answeredIn(createdIn, region)) {
            throw resourceNotFound(resourceArn);
        }
        return raw;
    }

    private static AwsException resourceNotFound(String resourceArn) {
        return new AwsException("ResourceNotFoundException", "Resource not found: " + resourceArn, 404);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private Namespace requireNamespace(String id) {
        return requireNamespace(id, namespace -> true);
    }

    private Namespace requireNamespace(String id, String region) {
        return requireNamespace(id, namespace -> answeredIn(namespace.getRegion(), region));
    }

    private Namespace requireNamespace(String id, Predicate<Namespace> answered) {
        if (id == null) {
            throw new AwsException("InvalidInput", "Namespace id is required.", 400);
        }
        return namespaceStore.get(id).filter(answered)
                .orElseThrow(() -> new AwsException("NamespaceNotFound", "Namespace not found: " + id, 404));
    }

    private Service requireService(String id) {
        return requireService(id, service -> true);
    }

    private Service requireService(String id, String region) {
        return requireService(id, service -> answeredIn(service.getRegion(), region));
    }

    private Service requireService(String id, Predicate<Service> answered) {
        if (id == null) {
            throw new AwsException("InvalidInput", "Service id is required.", 400);
        }
        return serviceStore.get(id).filter(answered)
                .orElseThrow(() -> new AwsException("ServiceNotFound", "Service not found: " + id, 404));
    }

    /** Whether Cloud Map answers a record created in {@code createdIn} in the Region: created there, or unrecorded. */
    private static boolean answeredIn(String createdIn, String region) {
        return createdIn == null || createdIn.equals(region);
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

    private String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALNUM.charAt(random.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    /** Creates the Route 53 zone behind a DNS namespace (private when a VPC association is given). */
    private String createZone(Namespace ns, VpcAssociation vpc) {
        return route53Service.createHostedZone(ns.getName(), "cloudmap-" + ns.getId(),
                "Created by Cloud Map namespace " + ns.getId(), vpc).zone().getId();
    }
}
