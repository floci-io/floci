package io.floci.aws.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.StripedLocks;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudmap.CloudMapService;
import io.floci.aws.services.cloudmap.model.Instance;
import io.floci.aws.services.cloudmap.model.Namespace;
import io.floci.aws.services.cloudmap.model.Service;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.model.Container;
import io.floci.aws.services.ecs.model.EcsRegisteredInstances;
import io.floci.aws.services.ecs.model.EcsServiceModel;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.EcsTaskAddress;
import io.floci.aws.services.ecs.model.NetworkBinding;
import io.floci.aws.services.ecs.model.ServiceConnectServiceResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bridges ECS services to Cloud Map: when an ECS service declares a {@code serviceRegistries}
 * block, this registrar registers each running task as an instance of the named Cloud Map
 * service, and deregisters it when the task stops. Together with Cloud Map answering DNS for
 * its namespaces, that is what makes {@code <cloud-map-service>.<namespace>} resolve to a task.
 * <p>
 * One-way dependency ECS to Cloud Map, mirroring {@link EcsLoadBalancerRegistrar}. Cloud Map
 * never calls back into ECS, so there is no cycle.
 * <p>
 * {@code serviceRegistries} is stored as raw maps, since nothing else in ECS acts on it. The
 * three members read here are the ones AWS uses to place the instance.
 * <p>
 * It also creates and deletes the Cloud Map services behind a Service Connect configuration's
 * endpoints, in the namespace the ECS service joined.
 */
@ApplicationScoped
public class EcsServiceDiscoveryRegistrar {

    private static final Logger LOG = Logger.getLogger(EcsServiceDiscoveryRegistrar.class);

    private final CloudMapService cloudMapService;
    private final EcsContainerManager containerManager;
    // taskArn → the instance registered for it, persisted so it can be released after a restart
    private final AccountAwareStorageBackend<EcsRegisteredInstances> ledger;
    // namespace ARN → held while a service's Service Connect discovery names are checked and taken
    private final StripedLocks serviceConnectNamespaceLocks = new StripedLocks(256);

    /** The most characters a label of a Cloud Map service name holds. */
    private static final int MAX_LABEL = 63;
    /** The tag ECS puts on the Cloud Map services it creates for Service Connect (measured on AWS). */
    static final String ECS_MANAGED_TAG = "AmazonECSManaged";
    /** A discovery name ECS takes: up to 64 lowercase letters, numbers, underscores and hyphens, no hyphen first. */
    private static final Pattern DISCOVERY_NAME = Pattern.compile("[a-z0-9_][a-z0-9_-]{0,63}");
    /** ECS's answer for a discovery name outside {@link #DISCOVERY_NAME} (measured on AWS). */
    static final String INVALID_DISCOVERY_NAME = "The discovery name that you provided is invalid. This must be "
            + "unique within the AWS Cloud Map namespace. Up to 64 characters are allowed. The characters can "
            + "include lowercase letters, numbers, underscores (_), and hyphens (-). A hyphen can\u2019t be the "
            + "first character.";

    @Inject
    public EcsServiceDiscoveryRegistrar(CloudMapService cloudMapService, EcsContainerManager containerManager,
                                        StorageFactory storageFactory) {
        this.cloudMapService = cloudMapService;
        this.containerManager = containerManager;
        this.ledger = storageFactory.create("ecs", "ecs-registered-instances.json",
                new TypeReference<Map<String, EcsRegisteredInstances>>() {});
    }

    /**
     * Registers the task as a Cloud Map instance of every service registry the ECS service declares.
     * The registrations are recorded before they are made, so a process killed in between still
     * leaves a record for the next run to release; releasing one that never registered is harmless.
     */
    public void registerTask(EcsTask task, EcsServiceModel svc, String region) {
        String instanceId = instanceId(task);
        Map<String, Map<String, String>> planned = new LinkedHashMap<>();
        for (Map<String, Object> registry : registries(svc)) {
            String cloudMapServiceId = cloudMapServiceId(registry);
            if (cloudMapServiceId == null) {
                continue;
            }
            Map<String, String> attributes = instanceAttributes(task, svc, registry, region);
            if (attributes.isEmpty()) {
                LOG.warnv("ECS task {0} has no address to register into Cloud Map service {1}",
                        task.getTaskArn(), cloudMapServiceId);
                continue;
            }
            planned.putIfAbsent(cloudMapServiceId, attributes);
        }
        String taskArn = task.getTaskArn();
        if (!planned.isEmpty() && taskArn != null) {
            ledger.put(taskArn, new EcsRegisteredInstances(region, instanceId, List.copyOf(planned.keySet())));
        }
        Set<String> registered = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> entry : planned.entrySet()) {
            String cloudMapServiceId = entry.getKey();
            Map<String, String> attributes = entry.getValue();
            try {
                cloudMapService.registerInstance(cloudMapServiceId, instanceId, null, attributes, region);
                registered.add(cloudMapServiceId);
                LOG.infov("Registered ECS task {0} as a Cloud Map instance of {1} at {2}",
                        task.getTaskArn(), cloudMapServiceId, attributes.get("AWS_INSTANCE_IPV4"));
            } catch (Exception e) {
                LOG.warnv("Could not register ECS task {0} into Cloud Map service {1}: {2}",
                        task.getTaskArn(), cloudMapServiceId, e.getMessage());
            }
        }
        task.setServiceDiscoveryServiceIds(List.copyOf(registered));
        if (taskArn == null || registered.size() == planned.size()) {
            return;
        }
        if (registered.isEmpty()) {
            ledger.delete(taskArn);
        } else {
            ledger.put(taskArn, new EcsRegisteredInstances(region, instanceId, List.copyOf(registered)));
        }
    }

    /** Deregisters the task from the Cloud Map services it actually registered in. */
    public void deregisterTask(EcsTask task, String region) {
        String instanceId = instanceId(task);
        for (String cloudMapServiceId : task.getServiceDiscoveryServiceIds()) {
            try {
                cloudMapService.deregisterInstance(cloudMapServiceId, instanceId, region);
                LOG.infov("Deregistered ECS task {0} from Cloud Map service {1}",
                        task.getTaskArn(), cloudMapServiceId);
            } catch (Exception e) {
                // An instance that never registered, or one a previous stop already removed, is
                // the ordinary case here rather than a failure worth failing the stop over.
                LOG.debugv("Could not deregister ECS task {0} from Cloud Map service {1}: {2}",
                        task.getTaskArn(), cloudMapServiceId, e.getMessage());
            }
        }
        task.setServiceDiscoveryServiceIds(List.of());
        if (task.getTaskArn() != null) {
            ledger.delete(task.getTaskArn());
        }
    }

    /**
     * Deregisters every Cloud Map instance ECS recorded for a task, in every account. Only for
     * startup, when ECS holds no task at all: task state is memory-only, so none of them can still
     * be a live task. Instances ECS did not register, including ones registered through the Cloud
     * Map API, are never touched.
     */
    public void releaseRecordedInstances() {
        for (AccountAwareStorageBackend.AccountEntry<EcsRegisteredInstances> entry
                : ledger.scanAllAccountEntries(key -> true)) {
            EcsRegisteredInstances recorded = entry.value();
            List<String> cloudMapServiceIds = recorded.cloudMapServiceIds() != null
                    ? recorded.cloudMapServiceIds() : List.of();
            RequestScopes.runAs(entry.accountId(), () -> {
                for (String cloudMapServiceId : cloudMapServiceIds) {
                    try {
                        cloudMapService.deregisterInstance(cloudMapServiceId, recorded.instanceId(),
                                recorded.region());
                        LOG.infov("Deregistered Cloud Map instance {0} of {1} left by a previous ECS task",
                                recorded.instanceId(), cloudMapServiceId);
                    } catch (Exception e) {
                        // Already gone, as when its Cloud Map service was deleted: nothing left to release.
                        LOG.debugv("Could not deregister Cloud Map instance {0} of {1}: {2}",
                                recorded.instanceId(), cloudMapServiceId, e.getMessage());
                    }
                }
            });
            ledger.deleteForAccount(entry.accountId(), entry.key());
        }
    }

    /**
     * Deregisters, from the given Cloud Map services, each instance at one of a starting task's
     * addresses that no ECS task recorded. The counterpart of
     * {@link EcsLoadBalancerRegistrar#evictUnrecordedTargets}: such an instance was left by a task
     * that is gone, as one registered by a Floci version that kept no record, and would now resolve
     * the service's name to this task. An instance a live task recorded stays, and an address
     * scoped to a VPC counts only in a Cloud Map service whose namespace is in that VPC.
     */
    public void evictUnrecordedInstances(EcsTask task, Collection<String> cloudMapServiceIds,
                                         Collection<EcsTaskAddress> addresses, String region) {
        if (cloudMapServiceIds.isEmpty() || addresses.isEmpty()) {
            return;
        }
        Set<String> recorded = new HashSet<>();
        for (EcsRegisteredInstances entry : ledger.scan(key -> true)) {
            if (entry.cloudMapServiceIds() != null) {
                entry.cloudMapServiceIds().forEach(id -> recorded.add(id + "/" + entry.instanceId()));
            }
        }
        for (String cloudMapServiceId : cloudMapServiceIds) {
            List<Instance> instances;
            List<EcsTaskAddress> scoped;
            try {
                String vpc = cloudMapService.getNamespace(
                        cloudMapService.getService(cloudMapServiceId).getNamespaceId()).getVpc();
                scoped = addresses.stream().filter(a -> a.appliesTo(vpc)).toList();
                instances = cloudMapService.listInstances(cloudMapServiceId);
            } catch (Exception e) {
                // A Cloud Map service deleted while an ECS service still names it: nothing to evict.
                LOG.debugv("Could not list the instances of Cloud Map service {0}: {1}", cloudMapServiceId,
                        e.getMessage());
                continue;
            }
            for (Instance instance : instances) {
                Map<String, String> attributes = instance.getAttributes() != null ? instance.getAttributes() : Map.of();
                String ip = attributes.get("AWS_INSTANCE_IPV4");
                Integer port = parsePort(attributes.get("AWS_INSTANCE_PORT"));
                if (ip == null || scoped.stream().noneMatch(a -> a.matches(ip, port))
                        || recorded.contains(cloudMapServiceId + "/" + instance.getInstanceId())) {
                    continue;
                }
                try {
                    cloudMapService.deregisterInstance(cloudMapServiceId, instance.getInstanceId(), region);
                    LOG.warnv("Deregistered stale Cloud Map instance {0} of {1} at {2}: no ECS task registered it,"
                            + " and its address now belongs to ECS task {3}", instance.getInstanceId(),
                            cloudMapServiceId, ip, task.getTaskArn());
                } catch (Exception e) {
                    LOG.warnv("Could not deregister stale Cloud Map instance {0} of {1}: {2}",
                            instance.getInstanceId(), cloudMapServiceId, e.getMessage());
                }
            }
        }
    }

    /** The ARN of the Region's Cloud Map namespace that a name or ARN gives; null when there is none. */
    public String namespaceArn(String nameOrArn, String region) {
        Namespace namespace = namespace(nameOrArn, region);
        return namespace == null ? null : namespace.getArn();
    }

    /** The Region's Cloud Map namespace that a name or ARN gives; null when there is none. */
    private Namespace namespace(String nameOrArn, String region) {
        for (Namespace namespace : cloudMapService.listNamespaces(region)) {
            if (nameOrArn.equals(namespace.getName()) || nameOrArn.equals(namespace.getArn())) {
                return namespace;
            }
        }
        return null;
    }

    /**
     * The names of the Cloud Map services a Service Connect configuration gives the service {@code serviceArn},
     * in its order: each {@code services[]} entry's {@code discoveryName}, or its {@code portName} when it has
     * none, and for a client configuration, with no entries, the one Cloud Map service ECS creates for the
     * client ({@link #clientDiscoveryName}). None for a configuration that leaves Service Connect off, and none for
     * an entry without a name.
     */
    public static List<String> discoveryNames(Map<String, Object> configuration, String serviceArn) {
        if (configuration == null || !Boolean.TRUE.equals(configuration.get("enabled"))) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        boolean client = !(configuration.get("services") instanceof List<?> listed) || listed.isEmpty();
        if (configuration.get("services") instanceof List<?> services) {
            for (Object entry : services) {
                if (entry instanceof Map<?, ?> service) {
                    Object name = service.get("discoveryName") instanceof String discoveryName
                            && !discoveryName.isBlank() ? discoveryName : service.get("portName");
                    if (name instanceof String named && !named.isBlank()) {
                        names.add(named);
                    }
                }
            }
        }
        return client && serviceArn != null ? List.of(clientDiscoveryName(serviceArn)) : names;
    }

    /**
     * The Cloud Map service ECS creates for a Service Connect client, which offers no endpoint:
     * {@code aws-ecs-sc.client.<UUID>.<service name>}, the service name cut to its first 63 characters, a Cloud
     * Map label's most, as measured on AWS. How AWS derives the UUID isn't documented (it is none of the
     * name-based UUIDs of the service's ARN or names); Floci derives one from the service's ARN, so the service
     * keeps it across updates, as on AWS.
     */
    static String clientDiscoveryName(String serviceArn) {
        String serviceName = nameFromArn(serviceArn);
        return "aws-ecs-sc.client." + UUID.nameUUIDFromBytes(serviceArn.getBytes(StandardCharsets.UTF_8)) + "."
                + serviceName.substring(0, Math.min(serviceName.length(), MAX_LABEL));
    }

    /**
     * Refuses, with {@code errorCode}, a Service Connect configuration ECS refuses before it looks in the
     * namespace, with ECS's messages (measured on AWS): a {@code discoveryName} outside the naming rule, or a
     * name its entries give more than once. CreateService answers {@code ClientException} and UpdateService
     * {@code InvalidParameterException}.
     */
    public static void validateDiscoveryNames(Map<String, Object> configuration, String errorCode) {
        if (configuration == null || !Boolean.TRUE.equals(configuration.get("enabled"))
                || !(configuration.get("services") instanceof List<?> services)) {
            return;
        }
        for (Object entry : services) {
            if (entry instanceof Map<?, ?> service && service.get("discoveryName") instanceof String name
                    && !DISCOVERY_NAME.matcher(name).matches()) {
                throw new AwsException(errorCode, INVALID_DISCOVERY_NAME, 400);
            }
        }
        refuseRepeatedNames(discoveryNames(configuration, null), false, errorCode);
    }

    /**
     * Refuses names a configuration gives more than once, with ECS's message (measured on AWS); compared without
     * case when {@code ignoreCase}, as a DNS namespace's Cloud Map compares them.
     */
    private static void refuseRepeatedNames(List<String> names, boolean ignoreCase, String errorCode) {
        Set<String> seen = new HashSet<>();
        Set<String> twice = new LinkedHashSet<>();
        for (String name : names) {
            if (!seen.add(ignoreCase ? name.toLowerCase(Locale.ROOT) : name)) {
                twice.add(name);
            }
        }
        if (!twice.isEmpty()) {
            throw new AwsException(errorCode,
                    "The following discoveryNames are defined more than once: " + List.copyOf(twice) + ".", 400);
        }
    }

    /**
     * Refuses, with {@code errorCode}, discovery names {@code namespace} cannot take for a service holding
     * {@code current}: one a Cloud Map service in the namespace already has that is not among {@code current},
     * since "all discovery names must be unique within a namespace". Names compare as Cloud Map compares them,
     * without case in a DNS namespace.
     */
    private void checkDiscoveryNames(List<ServiceConnectServiceResource> current, List<String> discoveryNames,
                                     Namespace namespace, String region, String errorCode) {
        Set<String> own = new HashSet<>();
        current.forEach(resource -> own.add(resource.discoveryArn()));
        for (String name : discoveryNames) {
            cloudMapService.findServiceByName(name, namespace.getId(), region)
                    .filter(existing -> !own.contains(existing.getArn()))
                    .ifPresent(existing -> {
                        throw nameTaken(existing, namespace, errorCode);
                    });
        }
    }

    /**
     * ECS's answer for a discovery name the namespace already holds (measured on AWS): its own, naming the
     * namespace, when another ECS service's endpoint holds it, and otherwise Cloud Map's refusal of its
     * CreateService call, with the details the SDK adds to it.
     */
    private static AwsException nameTaken(Service existing, Namespace namespace, String errorCode) {
        boolean ecsManaged = existing.getTags() != null && "true".equals(existing.getTags().get(ECS_MANAGED_TAG));
        return new AwsException(errorCode, ecsManaged ? "SC service is already used by " + namespace.getArn()
                : cloudMapServiceExists(), 400);
    }

    private static String cloudMapServiceExists() {
        return "Service already exists. (Service: ServiceDiscovery, Status Code: 400, Request ID: "
                + UUID.randomUUID() + ") (SDK Attempt Count: 1)";
    }

    /**
     * Brings the Cloud Map services behind a service's Service Connect endpoints in line with
     * {@code discoveryNames}: a name {@code current} already holds keeps its Cloud Map service, a new
     * one gets a Cloud Map service created in the namespace, and one {@code current} holds that is no
     * longer named has its Cloud Map service deleted. The names are checked first, under a lock for
     * the namespace, so a refusal creates nothing and two ECS services cannot take one name at once; a
     * creation that still fails, as when a Cloud Map CreateService took the name in between, deletes
     * what this call created and leaves {@code current} as it was. A refusal carries {@code errorCode}. The
     * Cloud Map services are of type HTTP, with no DNS records: Service Connect "doesn't use or create DNS
     * hosted zones in Amazon Route 53". Each is described "Managed by" the ECS service {@code serviceArn} and
     * tagged {@code AmazonECSManaged}, as ECS creates them (measured on AWS).
     *
     * @return the resources for {@code discoveryNames}, in their order; none without a namespace
     */
    public List<ServiceConnectServiceResource> syncServiceConnectServices(
            List<ServiceConnectServiceResource> current, List<String> discoveryNames, String namespaceArn,
            String region, String serviceArn, String errorCode) {
        Namespace namespace = namespaceArn == null ? null : namespace(namespaceArn, region);
        List<ServiceConnectServiceResource> resources = new ArrayList<>();
        if (namespace != null && !discoveryNames.isEmpty()) {
            synchronized (serviceConnectNamespaceLocks.lockFor(namespace.getArn())) {
                // A portName, which the naming rule doesn't hold to lowercase, can repeat a name in a case of its own.
                refuseRepeatedNames(discoveryNames, CloudMapService.isDnsNamespace(namespace), errorCode);
                checkDiscoveryNames(current, discoveryNames, namespace, region, errorCode);
                Set<String> own = new HashSet<>();
                current.forEach(resource -> own.add(resource.discoveryArn()));
                List<ServiceConnectServiceResource> created = new ArrayList<>();
                for (String name : discoveryNames) {
                    Service existing = cloudMapService.findServiceByName(name, namespace.getId(), region)
                            .orElse(null);
                    if (existing != null && !own.contains(existing.getArn())) {
                        // The check let through only a name that was free or this service's own: a Cloud Map
                        // CreateService took it since, and that service isn't ECS's to take over or delete.
                        deleteServiceConnectServices(created);
                        throw nameTaken(existing, namespace, errorCode);
                    }
                    if (existing == null) {
                        try {
                            existing = cloudMapService.createService(name, namespace.getId(), null,
                                    "Managed by " + serviceArn, null, null, null, null,
                                    Map.of(ECS_MANAGED_TAG, "true"), region);
                        } catch (AwsException e) {
                            deleteServiceConnectServices(created);
                            throw "ServiceAlreadyExists".equals(e.getErrorCode())
                                    ? new AwsException(errorCode, cloudMapServiceExists(), 400) : e;
                        }
                        created.add(new ServiceConnectServiceResource(name, existing.getArn()));
                    }
                    resources.add(new ServiceConnectServiceResource(name, existing.getArn()));
                }
            }
        }
        Set<String> kept = new HashSet<>();
        resources.forEach(resource -> kept.add(resource.discoveryArn()));
        deleteServiceConnectServices(current.stream()
                .filter(resource -> !kept.contains(resource.discoveryArn())).toList());
        return resources;
    }

    /**
     * Deletes the Cloud Map services behind Service Connect endpoints a service no longer offers.
     * One already gone is skipped; one Cloud Map refuses to delete, because instances were registered
     * in it through the Cloud Map API, is left in place, and its name stays taken.
     */
    public void deleteServiceConnectServices(List<ServiceConnectServiceResource> resources) {
        for (ServiceConnectServiceResource resource : resources) {
            String id = serviceIdOf(resource.discoveryArn());
            if (id == null) {
                continue;
            }
            try {
                cloudMapService.deleteService(id);
            } catch (AwsException e) {
                if ("ServiceNotFound".equals(e.getErrorCode())) {
                    continue;
                }
                LOG.warnv("Could not delete Cloud Map service {0} of Service Connect endpoint {1}: {2}",
                        id, resource.discoveryName(), e.getMessage());
            }
        }
    }

    public boolean hasRegistries(EcsServiceModel svc) {
        return !registries(svc).isEmpty();
    }

    /** The Cloud Map services the ECS service's registries name. */
    public List<String> cloudMapServiceIds(EcsServiceModel svc) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> registry : registries(svc)) {
            String id = cloudMapServiceId(registry);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static Integer parsePort(String port) {
        if (port == null) {
            return null;
        }
        try {
            return Integer.valueOf(port);
        } catch (NumberFormatException e) {
            LOG.debugv("Ignoring a Cloud Map instance port that is not a number: {0}", port);
            return null;
        }
    }

    private List<Map<String, Object>> registries(EcsServiceModel svc) {
        List<Map<String, Object>> registries = svc.getServiceRegistries();
        return registries != null ? registries : List.of();
    }

    /**
     * Builds the instance attributes AWS records for an ECS-registered instance. On AWS the
     * address of an awsvpc task is its ENI address, because that is where the task answers. In
     * Floci the ENI address is held at most on the VPC's Docker network, which peers outside that
     * VPC cannot reach, so an awsvpc task registers the address its container holds on
     * the network it was created on, and falls back to the ENI address only
     * when no running container can say what it holds. A bridge-mode task has no ENI and is
     * registered at the container's host address, as before.
     *
     * <p>The metadata attributes alongside it are the ones the ECS service discovery
     * documentation lists, so a caller can filter a {@code DiscoverInstances} response by them
     * the way it would on AWS. {@code EC2_INSTANCE_ID} is not among them: Floci runs every task
     * as a container rather than on a registered EC2 host, so there is no instance id to name.
     */
    private Map<String, String> instanceAttributes(EcsTask task, EcsServiceModel svc,
                                                   Map<String, Object> registry, String region) {
        Container container = containerFor(task, string(registry, "containerName"));
        String address = task.getPrivateIpAddress();
        boolean awsvpc = address != null && !address.isBlank();
        if (awsvpc) {
            address = containerManager.resolvePeerAddress(container).orElse(address);
        } else if (container != null) {
            address = containerManager.resolveContainerHost(container);
        }
        if (address == null || address.isBlank()) {
            return Map.of();
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("AWS_INSTANCE_IPV4", address);
        Integer port = instancePort(container, registry, awsvpc);
        if (port != null) {
            attributes.put("AWS_INSTANCE_PORT", String.valueOf(port));
        }
        putIfPresent(attributes, "AVAILABILITY_ZONE", task.getAvailabilityZone());
        putIfPresent(attributes, "REGION", region);
        putIfPresent(attributes, "ECS_SERVICE_NAME", svc.getServiceName());
        putIfPresent(attributes, "ECS_CLUSTER_NAME", nameFromArn(task.getClusterArn()));
        putIfPresent(attributes, "ECS_TASK_DEFINITION_FAMILY", familyFromArn(task.getTaskDefinitionArn()));
        return attributes;
    }

    private static void putIfPresent(Map<String, String> attributes, String key, String value) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value);
        }
    }

    /** The trailing name of an ECS ARN, which for a cluster ARN is the cluster name. */
    private static String nameFromArn(String arn) {
        if (arn == null) {
            return null;
        }
        return AwsArnUtils.resourceIfArnFor(arn, "ecs")
                .map(resource -> resource.substring(resource.lastIndexOf('/') + 1))
                .orElse(arn);
    }

    /** The family out of {@code .../task-definition/<family>:<revision>}, without the revision. */
    private static String familyFromArn(String taskDefinitionArn) {
        String name = nameFromArn(taskDefinitionArn);
        if (name == null) {
            return null;
        }
        int colon = name.lastIndexOf(':');
        return colon > 0 ? name.substring(0, colon) : name;
    }

    private Container containerFor(EcsTask task, String containerName) {
        if (task.getContainers() == null || task.getContainers().isEmpty()) {
            return null;
        }
        return task.getContainers().stream()
                .filter(c -> containerName == null || containerName.equals(c.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * The port an SRV record would carry. An explicit {@code port} wins, as it does on AWS.
     * An awsvpc task is registered at its container's own address, where it listens on the
     * {@code containerPort} itself, even when Floci also published that port on a dynamic host
     * port. A bridge-mode task is registered at the host, so it advertises the host port its
     * {@code containerPort} was published on, falling back to the {@code containerPort} when
     * there is no matching binding.
     */
    private Integer instancePort(Container container, Map<String, Object> registry, boolean awsvpc) {
        Integer port = integer(registry, "port");
        if (port != null) {
            return port;
        }
        Integer containerPort = integer(registry, "containerPort");
        if (awsvpc || container == null || containerPort == null || container.getNetworkBindings() == null) {
            return containerPort;
        }
        return container.getNetworkBindings().stream()
                .filter(b -> containerPort == b.containerPort())
                .map(NetworkBinding::hostPort)
                .findFirst()
                .orElse(containerPort);
    }

    /**
     * The Cloud Map service id inside a registry ARN
     * ({@code arn:<partition>:servicediscovery:<region>:<account>:service/srv-xxxxxxxx}). Returns
     * {@code null} for an entry that names no service, which leaves it unregistered rather than
     * failing the task the caller asked for.
     */
    private String cloudMapServiceId(Map<String, Object> registry) {
        String registryArn = string(registry, "registryArn");
        if (registryArn == null || registryArn.isBlank()) {
            return null;
        }
        String serviceId = serviceIdOf(registryArn);
        if (serviceId == null) {
            LOG.warnv("Ignoring an ECS service registry whose registryArn names no Cloud Map service: {0}",
                    registryArn);
        }
        return serviceId;
    }

    /** The service id in a Cloud Map service ARN, {@code ...:service/srv-xxxxxxxx}; null for any other value. */
    private static String serviceIdOf(String serviceArn) {
        String serviceId = AwsArnUtils.resourceIfArnFor(serviceArn, "servicediscovery")
                .filter(resource -> resource.startsWith("service/"))
                .map(resource -> resource.substring("service/".length()))
                .orElse("");
        return serviceId.isEmpty() || serviceId.contains("/") ? null : serviceId;
    }

    /** ECS registers a task under its task id, so a replacement task supersedes its predecessor. */
    private String instanceId(EcsTask task) {
        return nameFromArn(task.getTaskArn());
    }

    private static String string(Map<String, Object> registry, String key) {
        return registry.get(key) instanceof String value ? value : null;
    }

    private static Integer integer(Map<String, Object> registry, String key) {
        return registry.get(key) instanceof Number value ? value.intValue() : null;
    }
}
