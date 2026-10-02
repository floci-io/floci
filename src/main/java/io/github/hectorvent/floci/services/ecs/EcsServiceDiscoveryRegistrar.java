package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.cloudmap.model.Instance;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsRegisteredInstances;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EcsTaskAddress;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 */
@ApplicationScoped
public class EcsServiceDiscoveryRegistrar {

    private static final Logger LOG = Logger.getLogger(EcsServiceDiscoveryRegistrar.class);

    private final CloudMapService cloudMapService;
    private final EcsContainerManager containerManager;
    // taskArn → the instance registered for it, persisted so it can be released after a restart
    private final AccountAwareStorageBackend<EcsRegisteredInstances> ledger;

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
     * Floci the ENI address belongs to no network the task's containers joined, so an awsvpc task
     * registers the address its container actually holds, and falls back to the ENI address only
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

    /** The trailing name of an ARN, which for a cluster ARN is the cluster name. */
    private static String nameFromArn(String arn) {
        if (arn == null) {
            return null;
        }
        int lastSlash = arn.lastIndexOf('/');
        return lastSlash >= 0 ? arn.substring(lastSlash + 1) : arn;
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
        int lastSlash = registryArn.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == registryArn.length() - 1) {
            LOG.warnv("Ignoring an ECS service registry whose registryArn names no Cloud Map service: {0}",
                    registryArn);
            return null;
        }
        return registryArn.substring(lastSlash + 1);
    }

    /** ECS registers a task under its task id, so a replacement task supersedes its predecessor. */
    private String instanceId(EcsTask task) {
        String arn = task.getTaskArn();
        int lastSlash = arn.lastIndexOf('/');
        return lastSlash >= 0 ? arn.substring(lastSlash + 1) : arn;
    }

    private static String string(Map<String, Object> registry, String key) {
        return registry.get(key) instanceof String value ? value : null;
    }

    private static Integer integer(Map<String, Object> registry, String key) {
        return registry.get(key) instanceof Number value ? value.intValue() : null;
    }
}
