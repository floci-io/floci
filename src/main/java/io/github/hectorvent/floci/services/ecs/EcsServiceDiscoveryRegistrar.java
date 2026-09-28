package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.cloudmap.CloudMapService;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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

    @Inject
    public EcsServiceDiscoveryRegistrar(CloudMapService cloudMapService, EcsContainerManager containerManager) {
        this.cloudMapService = cloudMapService;
        this.containerManager = containerManager;
    }

    /** Registers the task as a Cloud Map instance of every service registry the ECS service declares. */
    public void registerTask(EcsTask task, EcsServiceModel svc, String region) {
        String instanceId = instanceId(task);
        Set<String> registered = new LinkedHashSet<>();
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
    }

    public boolean hasRegistries(EcsServiceModel svc) {
        return !registries(svc).isEmpty();
    }

    private List<Map<String, Object>> registries(EcsServiceModel svc) {
        List<Map<String, Object>> registries = svc.getServiceRegistries();
        return registries != null ? registries : List.of();
    }

    /**
     * Builds the instance attributes AWS records for an ECS-registered instance. The address is
     * the task's own ENI address in awsvpc mode, and otherwise the container's address on the
     * Docker network, which is the one another container on that network can reach.
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
        if ((address == null || address.isBlank()) && container != null) {
            address = containerManager.resolveContainerHost(container);
        }
        if (address == null || address.isBlank()) {
            return Map.of();
        }

        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("AWS_INSTANCE_IPV4", address);
        Integer port = instancePort(container, registry);
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
     * The port an SRV record would carry. An explicit {@code port} wins, as it does on AWS;
     * otherwise the host port the selected container's {@code containerPort} was published on,
     * so a bridge-mode task advertises the port that is reachable rather than the one inside the
     * container. A {@code containerPort} with no matching binding falls back to itself, which is
     * the awsvpc case where the two are always equal.
     */
    private Integer instancePort(Container container, Map<String, Object> registry) {
        Integer port = integer(registry, "port");
        if (port != null) {
            return port;
        }
        Integer containerPort = integer(registry, "containerPort");
        if (container == null || containerPort == null || container.getNetworkBindings() == null) {
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
