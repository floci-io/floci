package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The ECS task metadata endpoint, version 4.
 *
 * <p>On AWS this is served at {@code http://169.254.170.2/v4/<id>} and every container is told
 * where to find it through {@code ECS_CONTAINER_METADATA_URI_V4}. A local task cannot be given a
 * link-local address of its own, so Floci serves the same paths on its own port and injects the
 * matching URI into each container it launches; an application, the AWS SDKs' ECS credential and
 * metadata clients, and the aws-for-fluent-bit init process all read the variable rather than the
 * address, so they work unchanged.
 *
 * <p>The two {@code /stats} paths return the Docker stats AWS documents them as returning: one
 * sample per container, taken from the daemon when the request arrives. A container Floci has no
 * running Docker container for reports an empty document rather than an error, because the path
 * itself still exists for it.
 */
@Path("/v4")
@ApplicationScoped
public class EcsTaskMetadataController {

    private static final Logger LOG = Logger.getLogger(EcsTaskMetadataController.class);

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;
    private static final String SERVICE_GROUP_PREFIX = "service:";
    /** The search domain an instance gets, which us-east-1 spells differently from every other region. */
    private static final String LEGACY_SEARCH_DOMAIN_REGION = "us-east-1";

    /**
     * Docker's stats document, written back out the way the daemon sent it. docker-java's model
     * carries a field for every member of every API version, so the members this daemon left out
     * are dropped rather than written as nulls.
     */
    private static final ObjectMapper STATS_MAPPER = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    private final EcsService service;
    private final EcsContainerManager containerManager;
    private final Ec2Service ec2Service;
    private final ObjectMapper objectMapper;

    @Inject
    public EcsTaskMetadataController(EcsService service, EcsContainerManager containerManager,
                                     Ec2Service ec2Service, ObjectMapper objectMapper) {
        this.service = service;
        this.containerManager = containerManager;
        this.ec2Service = ec2Service;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response container(@PathParam("id") String id) {
        Optional<EcsService.MetadataTarget> target = service.findByMetadataId(id);
        if (target.isEmpty()) {
            return notFound();
        }
        EcsService.MetadataTarget found = target.get();
        return Response.ok(containerNode(found.task(), found.container(), found.taskDefinition())).build();
    }

    @GET
    @Path("/{id}/task")
    @Produces(MediaType.APPLICATION_JSON)
    public Response task(@PathParam("id") String id) {
        Optional<EcsService.MetadataTarget> target = service.findByMetadataId(id);
        if (target.isEmpty()) {
            return notFound();
        }
        EcsService.MetadataTarget found = target.get();
        return Response.ok(taskNode(found.task(), found.taskDefinition())).build();
    }

    /**
     * The task document with the task's own tags and, for a task placed on a container instance,
     * that instance's. The container agent serves this path, so it exists for the EC2 launch type
     * only; a Fargate task gets the 404 AWS gives it.
     */
    @GET
    @Path("/{id}/taskWithTags")
    @Produces(MediaType.APPLICATION_JSON)
    public Response taskWithTags(@PathParam("id") String id) {
        Optional<EcsService.MetadataTarget> target = service.findByMetadataId(id);
        if (target.isEmpty() || target.get().task().getLaunchType() != LaunchType.EC2) {
            return notFound();
        }
        EcsService.MetadataTarget found = target.get();
        ObjectNode node = taskNode(found.task(), found.taskDefinition());
        ArrayNode errors = objectMapper.createArrayNode();
        putTags(node, "TaskTags", found.task().getTaskArn(), errors);
        putTags(node, "ContainerInstanceTags", found.task().getContainerInstanceArn(), errors);
        if (!errors.isEmpty()) {
            node.set("Errors", errors);
        }
        return Response.ok(node).build();
    }

    /**
     * One tag collection, omitted when the resource carries none and reported under {@code Errors}
     * the way the agent reports a tag lookup it could not make.
     */
    private void putTags(ObjectNode target, String field, String resourceArn, ArrayNode errors) {
        if (resourceArn == null) {
            return;
        }
        try {
            Map<String, String> tags = service.listTagsForResource(resourceArn);
            if (!tags.isEmpty()) {
                ObjectNode node = target.putObject(field);
                tags.forEach(node::put);
            }
        } catch (AwsException e) {
            ObjectNode error = errors.addObject();
            error.put("ErrorField", field);
            error.put("ErrorCode", e.getErrorCode());
            error.put("ErrorMessage", e.getMessage());
            error.put("StatusCode", e.getHttpStatus());
            error.put("ResourceARN", resourceArn);
        }
    }

    @GET
    @Path("/{id}/stats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response containerStats(@PathParam("id") String id) {
        Optional<EcsService.MetadataTarget> target = service.findByMetadataId(id);
        if (target.isEmpty()) {
            return notFound();
        }
        EcsContainerManager.ContainerStats sample = containerManager
                .sampleContainerStats(target.get().container().getDockerId()).orElse(null);
        return Response.ok(statsNode(sample)).build();
    }

    @GET
    @Path("/{id}/task/stats")
    @Produces(MediaType.APPLICATION_JSON)
    public Response taskStats(@PathParam("id") String id) {
        Optional<EcsService.MetadataTarget> target = service.findByMetadataId(id);
        if (target.isEmpty()) {
            return notFound();
        }
        List<Container> containers = target.get().task().getContainers();
        if (containers == null) {
            containers = List.of();
        }
        // The task's containers are sampled in one pass rather than one after another: Docker's
        // collection tick is a second, and this is the path a sidecar polls for network metrics.
        Map<String, EcsContainerManager.ContainerStats> sampled = containerManager.sampleContainerStats(
                containers.stream().map(Container::getDockerId).filter(Objects::nonNull).toList());

        ObjectNode stats = objectMapper.createObjectNode();
        for (Container container : containers) {
            stats.set(statsKey(container), statsNode(sampled.get(container.getDockerId())));
        }
        return Response.ok(stats).build();
    }

    /** Docker's stats for one container, or an empty document when the daemon had none to give. */
    private ObjectNode statsNode(EcsContainerManager.ContainerStats stats) {
        if (stats == null) {
            return objectMapper.createObjectNode();
        }
        ObjectNode node = STATS_MAPPER.valueToTree(stats.statistics());
        // Docker reports cumulative counters only. The per-second rates alongside them are the
        // agent's own, and are what a sidecar reads /task/stats for.
        if (stats.rxBytesPerSecond() != null && stats.txBytesPerSecond() != null) {
            ObjectNode rates = node.putObject("network_rate_stats");
            rates.put("rx_bytes_per_sec", stats.rxBytesPerSecond());
            rates.put("tx_bytes_per_sec", stats.txBytesPerSecond());
        }
        return node;
    }

    /** Task stats are keyed by Docker id, the way the daemon keys a container. */
    private static String statsKey(Container container) {
        return container.getDockerId() != null ? container.getDockerId() : container.getName();
    }

    private Response notFound() {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", "Unable to get metadata for the requested id");
        return Response.status(Response.Status.NOT_FOUND).entity(error).build();
    }

    private ObjectNode containerNode(EcsTask task, Container container, TaskDefinition taskDef) {
        ContainerDefinition definition = definitionOf(taskDef, container.getName());
        ObjectNode n = objectMapper.createObjectNode();
        n.put("DockerId", container.getDockerId() != null ? container.getDockerId() : container.getMetadataId());
        n.put("Name", container.getName());
        n.put("DockerName", container.getName());
        n.put("Image", container.getImage());
        if (container.getImageDigest() != null && !container.getImageDigest().isBlank()) {
            n.put("ImageID", container.getImageDigest());
        }
        ArrayNode ports = portsNode(container);
        if (!ports.isEmpty()) {
            n.set("Ports", ports);
        }
        n.set("Labels", labelsNode(task, taskDef, container));
        n.put("DesiredStatus", task.getDesiredStatus());
        n.put("KnownStatus", container.getLastStatus());
        if (container.getExitCode() != null) {
            n.put("ExitCode", container.getExitCode());
        }
        ObjectNode limits = containerLimitsNode(definition);
        if (!limits.isEmpty()) {
            n.set("Limits", limits);
        }
        // The container's own clock when Docker gave Floci one, the task's otherwise: a mock-mode
        // task, or one restored from storage, has containers that never went through a daemon.
        putTimestamp(n, "CreatedAt", firstOf(container.getCreatedAt(), task.getCreatedAt()));
        putTimestamp(n, "StartedAt", firstOf(container.getStartedAt(), task.getStartedAt()));
        putTimestamp(n, "FinishedAt", firstOf(container.getFinishedAt(), task.getStoppedAt()));
        n.put("Type", "NORMAL");
        n.put("ContainerARN", container.getContainerArn());
        if (container.getHealthStatus() != null) {
            n.putObject("Health").put("status", container.getHealthStatus());
        }
        if (definition != null && definition.getLogConfiguration() != null) {
            LogConfiguration logConfiguration = definition.getLogConfiguration();
            n.put("LogDriver", logConfiguration.logDriver());
            if (logConfiguration.options() != null) {
                ObjectNode options = n.putObject("LogOptions");
                logConfiguration.options().forEach(options::put);
            }
        }
        n.set("Networks", networksNode(task, taskDef));
        if (task.getPlatformVersion() != null) {
            n.put("Snapshotter", "overlayfs");
        }
        return n;
    }

    private ArrayNode portsNode(Container container) {
        ArrayNode ports = objectMapper.createArrayNode();
        if (container.getNetworkBindings() != null) {
            container.getNetworkBindings().forEach(binding -> {
                ObjectNode port = ports.addObject();
                port.put("ContainerPort", binding.containerPort());
                port.put("Protocol", binding.protocol());
                if (binding.hostPort() > 0) {
                    port.put("HostPort", binding.hostPort());
                }
            });
        }
        return ports;
    }

    private ObjectNode taskNode(EcsTask task, TaskDefinition taskDef) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("Cluster", task.getClusterArn());
        n.put("TaskARN", task.getTaskArn());
        if (taskDef != null) {
            n.put("Family", taskDef.getFamily());
            n.put("Revision", String.valueOf(taskDef.getRevision()));
        }
        n.put("DesiredStatus", task.getDesiredStatus());
        n.put("KnownStatus", task.getLastStatus());
        ObjectNode limits = taskLimitsNode(task);
        if (!limits.isEmpty()) {
            n.set("Limits", limits);
        }
        putTimestamp(n, "PullStartedAt", task.getPullStartedAt());
        putTimestamp(n, "PullStoppedAt", task.getPullStoppedAt());
        putTimestamp(n, "ExecutionStoppedAt", task.getExecutionStoppedAt());
        if (task.getAvailabilityZone() != null) {
            n.put("AvailabilityZone", task.getAvailabilityZone());
        }
        // A service's tasks are grouped as "service:<name>", which is where the endpoint's
        // ServiceName comes from; a standalone task has no service and reports none.
        if (task.getGroup() != null && task.getGroup().startsWith(SERVICE_GROUP_PREFIX)) {
            n.put("ServiceName", task.getGroup().substring(SERVICE_GROUP_PREFIX.length()));
        }
        if (task.getLaunchType() != null) {
            n.put("LaunchType", task.getLaunchType().name());
        }
        // VPCID is the container instance's, so AWS reports it for the EC2 launch type only.
        if (task.getLaunchType() == LaunchType.EC2) {
            Subnet subnet = taskSubnet(task);
            if (subnet != null && subnet.getVpcId() != null) {
                n.put("VPCID", subnet.getVpcId());
            }
        }
        ArrayNode containers = n.putArray("Containers");
        if (task.getContainers() != null) {
            task.getContainers().forEach(container ->
                    containers.add(containerNode(task, container, taskDef)));
        }
        // Fargate reports the clock's accuracy and the task's ephemeral storage use. Floci has no
        // drift to report and does not meter the disk, so both are reported as a healthy baseline.
        if (task.getPlatformVersion() != null) {
            ObjectNode clockDrift = n.putObject("ClockDrift");
            clockDrift.put("ClockErrorBound", 0.0);
            clockDrift.put("ReferenceTimestamp", TIMESTAMP.format(Instant.now()));
            clockDrift.put("ClockSynchronizationStatus", "SYNCHRONIZED");
            if (task.getEphemeralStorage() != null) {
                ObjectNode storage = n.putObject("EphemeralStorageMetrics");
                storage.put("Utilized", 0);
                storage.put("Reserved", task.getEphemeralStorage().sizeInGiB() * 1024);
            }
        }
        return n;
    }

    private ObjectNode labelsNode(EcsTask task, TaskDefinition taskDef, Container container) {
        ObjectNode labels = objectMapper.createObjectNode();
        labels.put("com.amazonaws.ecs.cluster", task.getClusterArn());
        labels.put("com.amazonaws.ecs.container-name", container.getName());
        labels.put("com.amazonaws.ecs.task-arn", task.getTaskArn());
        if (taskDef != null) {
            labels.put("com.amazonaws.ecs.task-definition-family", taskDef.getFamily());
            labels.put("com.amazonaws.ecs.task-definition-version", String.valueOf(taskDef.getRevision()));
        }
        return labels;
    }

    /** The container's own limits, omitted member by member when the definition sets none. */
    private ObjectNode containerLimitsNode(ContainerDefinition definition) {
        ObjectNode limits = objectMapper.createObjectNode();
        if (definition == null) {
            return limits;
        }
        if (definition.getCpu() != null) {
            limits.put("CPU", definition.getCpu());
        }
        if (definition.getMemory() != null) {
            limits.put("Memory", definition.getMemory());
        }
        return limits;
    }

    /** The task's own limits: CPU as a vCPU count, memory in MiB, the way the endpoint reports them. */
    private ObjectNode taskLimitsNode(EcsTask task) {
        ObjectNode limits = objectMapper.createObjectNode();
        Integer cpuUnits = parseInteger(task.getCpu());
        if (cpuUnits != null) {
            limits.put("CPU", cpuUnits / 1024.0);
        }
        Integer memoryMb = parseInteger(task.getMemory());
        if (memoryMb != null) {
            limits.put("Memory", memoryMb);
        }
        return limits;
    }

    private ArrayNode networksNode(EcsTask task, TaskDefinition taskDef) {
        ArrayNode networks = objectMapper.createArrayNode();
        ObjectNode network = objectMapper.createObjectNode();
        network.put("NetworkMode", taskDef != null && taskDef.getNetworkMode() != null
                ? taskDef.getNetworkMode().name() : "bridge");
        ArrayNode addresses = network.putArray("IPv4Addresses");
        if (task.getPrivateIpAddress() != null) {
            addresses.add(task.getPrivateIpAddress());
        }
        if (taskSubnetId(task) != null) {
            network.put("AttachmentIndex", 0);
        }
        // The rest of the network object describes the task ENI and the subnet it sits in. Each
        // member is written only when the interface Floci allocated actually carries it.
        if (task.getMacAddress() != null) {
            network.put("MACAddress", task.getMacAddress());
        }
        putSubnetMembers(network, task);
        if (task.getPrivateDnsName() != null) {
            network.put("PrivateDNSName", task.getPrivateDnsName());
        }
        networks.add(network);
        return networks;
    }

    /**
     * The subnet's own view of the network: its CIDR, the gateway VPC reserves as the first
     * address in it, the resolver at the VPC's third address, and the search domain the region
     * hands out. All four are derived, since a local ENI has no DHCP option set behind it.
     */
    private void putSubnetMembers(ObjectNode network, EcsTask task) {
        Subnet subnet = taskSubnet(task);
        if (subnet == null || subnet.getCidrBlock() == null) {
            return;
        }
        network.put("IPv4SubnetCIDRBlock", subnet.getCidrBlock());
        String gateway = addressWithinCidr(subnet.getCidrBlock(), 1);
        if (gateway != null) {
            network.put("SubnetGatewayIpv4Address", gateway + "/" + prefixLengthOf(subnet.getCidrBlock()));
        }
        Vpc vpc = vpcOf(task, subnet);
        String resolver = vpc != null ? addressWithinCidr(vpc.getCidrBlock(), 2) : null;
        if (resolver != null) {
            network.putArray("DomainNameServers").add(resolver);
        }
        String region = taskRegion(task);
        if (region != null) {
            network.putArray("DomainNameSearchList").add(searchDomain(region));
        }
    }

    private Subnet taskSubnet(EcsTask task) {
        String subnetId = taskSubnetId(task);
        String region = taskRegion(task);
        if (subnetId == null || region == null) {
            return null;
        }
        try {
            return ec2Service.describeSubnets(region, List.of(subnetId), Map.of()).stream()
                    .findFirst()
                    .orElse(null);
        } catch (AwsException e) {
            LOG.debugv("Task {0} references a subnet that is gone: {1}", task.getTaskArn(), e.getMessage());
            return null;
        }
    }

    private Vpc vpcOf(EcsTask task, Subnet subnet) {
        String region = taskRegion(task);
        if (subnet.getVpcId() == null || region == null) {
            return null;
        }
        try {
            return ec2Service.describeVpcs(region, List.of(subnet.getVpcId()), Map.of()).stream()
                    .findFirst()
                    .orElse(null);
        } catch (AwsException e) {
            LOG.debugv("Subnet {0} references a VPC that is gone: {1}",
                    subnet.getSubnetId(), e.getMessage());
            return null;
        }
    }

    private static String taskSubnetId(EcsTask task) {
        AwsVpcConfiguration awsvpc = task.getNetworkConfiguration() != null
                ? task.getNetworkConfiguration().getAwsvpcConfiguration() : null;
        if (awsvpc == null || awsvpc.getSubnets() == null || awsvpc.getSubnets().isEmpty()) {
            return null;
        }
        return awsvpc.getSubnets().getFirst();
    }

    private static String taskRegion(EcsTask task) {
        return AwsArnUtils.regionOrDefault(task.getTaskArn(), null);
    }

    /** The nth address of a CIDR block, or null when the block is not a readable IPv4 one. */
    private static String addressWithinCidr(String cidr, int offset) {
        if (cidr == null) {
            return null;
        }
        String[] parts = cidr.split("/");
        String[] octets = parts[0].split("\\.");
        if (octets.length != 4) {
            return null;
        }
        try {
            long address = 0;
            for (String octet : octets) {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return null;
                }
                address = (address << 8) | value;
            }
            address += offset;
            return "%d.%d.%d.%d".formatted((address >> 24) & 0xFF, (address >> 16) & 0xFF,
                    (address >> 8) & 0xFF, address & 0xFF);
        } catch (NumberFormatException e) {
            LOG.debugv("CIDR block {0} is not a readable IPv4 block: {1}", cidr, e.getMessage());
            return null;
        }
    }

    private static String prefixLengthOf(String cidr) {
        int slash = cidr.indexOf('/');
        return slash < 0 ? "32" : cidr.substring(slash + 1);
    }

    private static String searchDomain(String region) {
        return LEGACY_SEARCH_DOMAIN_REGION.equals(region) ? "ec2.internal" : region + ".compute.internal";
    }

    private static ContainerDefinition definitionOf(TaskDefinition taskDef, String containerName) {
        if (taskDef == null || taskDef.getContainerDefinitions() == null) {
            return null;
        }
        return taskDef.getContainerDefinitions().stream()
                .filter(definition -> containerName.equals(definition.getName()))
                .findFirst()
                .orElse(null);
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant firstOf(Instant preferred, Instant fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static void putTimestamp(ObjectNode target, String field, Instant value) {
        if (value != null) {
            target.put(field, TIMESTAMP.format(value));
        }
    }
}
