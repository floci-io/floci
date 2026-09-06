package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.Secret;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for the ECS core types, {@code AWS::ECS::Cluster},
 * {@code AWS::ECS::TaskDefinition} and {@code AWS::ECS::Service}, moved out of the
 * {@code CloudFormationResourceProvisioner} switch. Capacity providers live in
 * {@link EcsCapacityCfnProvisioner}.
 */
@ApplicationScoped
public class EcsCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EcsCfnProvisioner.class);

    private static final String CLUSTER = "AWS::ECS::Cluster";
    private static final String TASK_DEFINITION = "AWS::ECS::TaskDefinition";
    private static final String SERVICE = "AWS::ECS::Service";

    private final EcsService ecsService;

    @Inject
    public EcsCfnProvisioner(EcsService ecsService) {
        this.ecsService = ecsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CLUSTER, TASK_DEFINITION, SERVICE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case CLUSTER -> provisionCluster(r, props, ctx);
            case TASK_DEFINITION -> provisionTaskDefinition(r, props, ctx);
            case SERVICE -> provisionService(r, props, ctx);
            default -> throw new IllegalStateException("EcsCfnProvisioner cannot handle " + r.getResourceType());
        }
        // A provision that left the resource with a new physical id replaced the entity: the
        // displaced one is deleted once the update commits, or restored if the update rolls back.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            // Only the service's own not-found code is tolerated: a real failure such as
            // ClusterContainsTasksException must still fail the stack delete (#1634).
            case CLUSTER -> CfnDeletes.safeDelete("ECS cluster", physicalId,
                    () -> ecsService.deleteCluster(physicalId, region), "ClusterNotFoundException");
            // An already-missing task definition, for example after a persistent restore that
            // dropped ECS state, surfaces as ClientException "Unable to describe task definition".
            case TASK_DEFINITION -> CfnDeletes.safeDelete("ECS task definition", physicalId,
                    () -> ecsService.deregisterTaskDefinition(physicalId, region), "ClientException");
            case SERVICE -> deleteService(physicalId, region);
            default -> { }
        }
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record. Without one, a cluster has nothing to put
     * back: its only property is the create-only name, so an update that kept it re-issued the
     * idempotent create and changed nothing. A service without a record was updated in place, and
     * putting that back needs a snapshot this provisioner does not keep, so the engine reports it as
     * not rolled back, as it did for the switch.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        return CLUSTER.equals(resource.getResourceType());
    }

    /**
     * The cluster name is create-only and the physical id, so an unnamed cluster keeps the name its
     * first execution generated. createCluster is idempotent, which is what makes an unchanged
     * update a no-op here.
     */
    private void provisionCluster(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterName = ctx.stablePhysicalName(ctx.resolveOptional(props, "ClusterName"),
                r.getLogicalId(), 255, false);
        EcsCluster cluster = ecsService.createCluster(clusterName, ctx.region());
        r.setPhysicalId(cluster.getClusterName());
        r.getAttributes().put("Arn", cluster.getClusterArn());
    }

    /**
     * Every property of a task definition is create-only, so an update registers a fresh revision
     * under the same family, as CloudFormation does on AWS; the physical id is the new revision's
     * ARN, and the displaced revision is deregistered once the stack update commits.
     */
    private void provisionTaskDefinition(StackResource r, JsonNode props, ProvisionContext ctx) {
        String family = ctx.resolveOptional(props, "Family");
        if (family == null || family.isBlank()) {
            family = ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }
        List<ContainerDefinition> containerDefs =
                parseContainerDefinitions(props != null ? props.get("ContainerDefinitions") : null, ctx);
        NetworkMode networkMode = parseEnum(NetworkMode.class, ctx.resolveOptional(props, "NetworkMode"));
        String cpu = ctx.resolveOptional(props, "Cpu");
        String memory = ctx.resolveOptional(props, "Memory");
        String taskRoleArn = ctx.resolveOptional(props, "TaskRoleArn");
        String executionRoleArn = ctx.resolveOptional(props, "ExecutionRoleArn");
        List<String> requiresCompatibilities = ctx.resolveStringList(props, "RequiresCompatibilities");

        TaskDefinition td = ecsService.registerTaskDefinition(family, containerDefs, networkMode, cpu, memory,
                taskRoleArn, executionRoleArn, requiresCompatibilities, ctx.region());

        r.setPhysicalId(td.getTaskDefinitionArn());
        r.getAttributes().put("TaskDefinitionArn", td.getTaskDefinitionArn());
    }

    /**
     * The physical id is the service ARN, not the name, so the name an unnamed service got at
     * create time is read back from the {@code Name} attribute rather than the prior id. An update
     * that keeps the name and the cluster updates the service in place; a changed name or a
     * changed cluster (both create-only) is a replacement: the new service is created and the
     * displaced one deleted once the stack update commits, through the replacement cleanup.
     */
    private void provisionService(StackResource r, JsonNode props, ProvisionContext ctx) {
        String clusterRef = ctx.resolveOptional(props, "Cluster");
        String taskDefinition = ctx.resolveOptional(props, "TaskDefinition");
        int desiredCount = parseDesiredCount(ctx.resolveOptional(props, "DesiredCount"));
        LaunchType launchType = parseEnum(LaunchType.class, ctx.resolveOptional(props, "LaunchType"));
        List<EcsLoadBalancer> loadBalancers =
                parseLoadBalancers(props != null ? props.get("LoadBalancers") : null, ctx);
        NetworkConfiguration networkConfiguration =
                parseNetworkConfiguration(props != null ? props.get("NetworkConfiguration") : null, ctx);

        String priorName = r.getAttributes().get("Name");
        String serviceName = ctx.resolveOptional(props, "ServiceName");
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = priorName;
        }
        if (serviceName == null || serviceName.isBlank()) {
            serviceName = ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }

        EcsServiceModel svc;
        if (ctx.isUpdate() && serviceName.equals(priorName)
                && clusterName(clusterRef).equals(clusterOfServiceArn(ctx.priorPhysicalId()))) {
            svc = ecsService.updateService(clusterRef, serviceName, taskDefinition,
                    desiredCount, networkConfiguration, ctx.region());
        } else {
            svc = ecsService.createService(clusterRef, serviceName, taskDefinition,
                    desiredCount, launchType, loadBalancers, networkConfiguration, ctx.region());
        }

        r.setPhysicalId(svc.getServiceArn());
        r.getAttributes().put("Name", svc.getServiceName());
        r.getAttributes().put("ServiceArn", svc.getServiceArn());
    }

    /** The cluster name a template value addresses: a name, an ARN's last segment, or the default. */
    private static String clusterName(String clusterRef) {
        if (clusterRef == null || clusterRef.isBlank()) {
            return "default";
        }
        int slash = clusterRef.lastIndexOf('/');
        return clusterRef.startsWith("arn:") && slash >= 0 ? clusterRef.substring(slash + 1) : clusterRef;
    }

    /** The cluster segment of {@code service/<cluster>/<name>}; the legacy {@code service/<name>} form is the default. */
    private static String clusterOfServiceArn(String serviceArn) {
        try {
            String[] segments = AwsArnUtils.parse(serviceArn).resource().split("/");
            return segments.length == 3 ? segments[1] : "default";
        } catch (IllegalArgumentException e) {
            return "default";
        }
    }

    /**
     * Floci service ARNs embed the cluster, {@code arn:aws:ecs:<region>:<acct>:service/<cluster>/<service>},
     * so both are parsed out and the right cluster's tasks get stopped during teardown.
     */
    private void deleteService(String serviceArn, String region) {
        String clusterRef = null;
        String serviceName = serviceArn;
        try {
            String[] segments = AwsArnUtils.parse(serviceArn).resource().split("/");
            if (segments.length == 3) {
                clusterRef = segments[1];
                serviceName = segments[2];
            } else if (segments.length == 2) {
                serviceName = segments[1];
            }
        } catch (IllegalArgumentException e) {
            LOG.debugv("ECS service id {0} is not an ARN, deleting it as a bare service name", serviceArn);
        }
        final String cluster = clusterRef;
        final String name = serviceName;
        CfnDeletes.safeDelete("ECS service", serviceArn,
                () -> ecsService.deleteService(cluster, name, true, region), "ServiceNotFoundException");
    }

    private static List<ContainerDefinition> parseContainerDefinitions(JsonNode node, ProvisionContext ctx) {
        List<ContainerDefinition> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        JsonNode resolved = ctx.engine().resolveNode(node);
        if (resolved == null || !resolved.isArray()) {
            return result;
        }
        for (JsonNode item : resolved) {
            ContainerDefinition def = new ContainerDefinition();
            def.setName(item.path("Name").asText(null));
            def.setImage(item.path("Image").asText(null));
            def.setEssential(item.path("Essential").asBoolean(true));
            if (item.hasNonNull("Cpu")) {
                def.setCpu(item.path("Cpu").asInt());
            }
            if (item.hasNonNull("Memory")) {
                def.setMemory(item.path("Memory").asInt());
            }
            if (item.hasNonNull("MemoryReservation")) {
                def.setMemoryReservation(item.path("MemoryReservation").asInt());
            }
            def.setPortMappings(parsePortMappings(item.path("PortMappings")));
            def.setEnvironment(parseEnvironment(item.path("Environment")));
            def.setSecrets(parseSecrets(item.path("Secrets")));
            if (item.path("Command").isArray()) {
                def.setCommand(toStringList(item.path("Command")));
            }
            if (item.path("EntryPoint").isArray()) {
                def.setEntryPoint(toStringList(item.path("EntryPoint")));
            }
            result.add(def);
        }
        return result;
    }

    private static List<PortMapping> parsePortMappings(JsonNode node) {
        List<PortMapping> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new PortMapping(item.path("ContainerPort").asInt(0), item.path("HostPort").asInt(0),
                    item.path("Protocol").asText("tcp")));
        }
        return result;
    }

    private static List<KeyValuePair> parseEnvironment(JsonNode node) {
        List<KeyValuePair> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new KeyValuePair(item.path("Name").asText(), item.path("Value").asText()));
        }
        return result;
    }

    private static List<Secret> parseSecrets(JsonNode node) {
        List<Secret> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new Secret(item.path("Name").asText(), item.path("ValueFrom").asText()));
        }
        return result;
    }

    private static List<EcsLoadBalancer> parseLoadBalancers(JsonNode node, ProvisionContext ctx) {
        List<EcsLoadBalancer> result = new ArrayList<>();
        if (node == null || node.isNull()) {
            return result;
        }
        JsonNode resolved = ctx.engine().resolveNode(node);
        if (resolved == null || !resolved.isArray()) {
            return result;
        }
        for (JsonNode item : resolved) {
            EcsLoadBalancer lb = new EcsLoadBalancer();
            if (item.hasNonNull("TargetGroupArn")) {
                lb.setTargetGroupArn(item.path("TargetGroupArn").asText());
            }
            if (item.hasNonNull("LoadBalancerName")) {
                lb.setLoadBalancerName(item.path("LoadBalancerName").asText());
            }
            if (item.hasNonNull("ContainerName")) {
                lb.setContainerName(item.path("ContainerName").asText());
            }
            if (item.hasNonNull("ContainerPort")) {
                lb.setContainerPort(item.path("ContainerPort").asInt());
            }
            result.add(lb);
        }
        return result;
    }

    private static NetworkConfiguration parseNetworkConfiguration(JsonNode node, ProvisionContext ctx) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(node);
        if (resolved == null || !resolved.isObject() || !resolved.hasNonNull("AwsvpcConfiguration")) {
            return null;
        }
        JsonNode awsvpc = resolved.path("AwsvpcConfiguration");
        AwsVpcConfiguration awsvpcConfig = new AwsVpcConfiguration();
        awsvpcConfig.setSubnets(toStringList(awsvpc.path("Subnets")));
        awsvpcConfig.setSecurityGroups(toStringList(awsvpc.path("SecurityGroups")));
        if (awsvpc.hasNonNull("AssignPublicIp")) {
            awsvpcConfig.setAssignPublicIp(awsvpc.path("AssignPublicIp").asText());
        }
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpcConfig);
        return networkConfiguration;
    }

    /** An absent or unknown enum value means "not set", as the switch arms treated it. */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            LOG.debugv("Ignoring unknown {0} value {1}", type.getSimpleName(), value);
            return null;
        }
    }

    /** DesiredCount defaults to 1 when absent; anything present has to be an integer. */
    private static int parseDesiredCount(String value) {
        if (value == null || value.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError", "Value of property DesiredCount must be an integer.", 400);
        }
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> result.add(v.asText()));
        }
        return result;
    }
}
