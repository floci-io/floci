package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ClusterSetting;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.Secret;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for the ECS core types, {@code AWS::ECS::Cluster},
 * {@code AWS::ECS::TaskDefinition} and {@code AWS::ECS::Service}, moved out of the
 * former CloudFormation monolith's switch. Capacity providers live in
 * {@link EcsCapacityCfnProvisioner}.
 */
@ApplicationScoped
public class EcsCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(EcsCfnProvisioner.class);

    private static final String CLUSTER = "AWS::ECS::Cluster";
    private static final String TASK_DEFINITION = "AWS::ECS::TaskDefinition";
    private static final String SERVICE = "AWS::ECS::Service";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ClusterSetting>> SETTINGS = new TypeReference<>() { };

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
        resource.getAttributes().remove(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    /**
     * When a settings update and its own restore both failed, the snapshot is left on the attempted
     * resource only, and the engine puts back the resource it held before. Carried over, it lets
     * ContinueUpdateRollback retry the restore through {@link #rollbackUpdate} instead of finding
     * nothing to put back. A restore that succeeded removed it, so it is here only after a failed one.
     */
    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        String snapshot = attempted.getAttributes().get(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR);
        if (snapshot != null) {
            previous.getAttributes().put(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR, snapshot);
        }
    }

    /**
     * A replacement is undone through the cleanup record. Without one, a cluster puts back the
     * settings snapshot its update took; with no snapshot the update changed no settings, and the
     * name is create-only, so the idempotent create changed nothing. A service without a record was
     * updated in place, and putting that back needs a snapshot this provisioner does not keep, so the
     * engine reports it as not rolled back, as it did for the switch.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        restoreSettingsSnapshot(resource);
        return CLUSTER.equals(resource.getResourceType());
    }

    private void restoreSettingsSnapshot(StackResource resource) {
        String rawSnapshot = resource.getAttributes().get(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR);
        if (rawSnapshot == null) {
            return;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(rawSnapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable ECS cluster settings snapshot for "
                    + resource.getLogicalId(), e);
        }
        ecsService.updateClusterSettings(resource.getPhysicalId(),
                MAPPER.convertValue(snapshot.get("settings"), SETTINGS), snapshot.get("region").asText());
        resource.getAttributes().remove(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR);
    }

    /**
     * The cluster name is create-only and the physical id, so an unnamed cluster keeps the name its
     * first execution generated. createCluster is idempotent, which is what makes an unchanged
     * update a no-op here. A new cluster is created with its declared {@code ClusterSettings}; an
     * existing one has them applied when they differ, and an update snapshots the prior ones for
     * its rollback.
     */
    private void provisionCluster(StackResource r, JsonNode props, ProvisionContext ctx) {
        r.getAttributes().remove(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR);
        String clusterName = ctx.stablePhysicalName(ctx.resolveOptional(props, "ClusterName"),
                r.getLogicalId(), 255, false);
        List<ClusterSetting> settings = new ArrayList<>();
        JsonNode declared = props != null ? props.get("ClusterSettings") : null;
        JsonNode resolved = declared != null ? ctx.engine().resolveNode(declared) : null;
        if (resolved != null && resolved.isArray()) {
            for (JsonNode item : resolved) {
                // An entry that resolved to AWS::NoValue is not an object.
                if (item.isObject()) {
                    settings.add(new ClusterSetting(item.path("Name").asText(null), item.path("Value").asText(null)));
                }
            }
        }
        // A new cluster takes its settings at creation, so no later call can fail and leave it
        // behind; an existing one (this resource's own on an update, or one the idempotent create
        // returned) has them applied below.
        EcsCluster cluster = ecsService.createCluster(clusterName, null, settings, ctx.region());
        if (!settings.isEmpty() && !settings.equals(cluster.getSettings())) {
            List<ClusterSetting> prior = cluster.getSettings() == null ? null : List.copyOf(cluster.getSettings());
            if (ctx.reusesPriorEntity(clusterName)) {
                ObjectNode snapshot = MAPPER.createObjectNode().put("region", ctx.region());
                snapshot.set("settings", MAPPER.valueToTree(prior));
                r.getAttributes().put(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR, snapshot.toString());
            }
            // Before the id is set: a cluster the idempotent create returned for a resource this
            // update adds was not created by the stack, and a failed update deletes an added
            // resource that carries an id. An update of this resource's own cluster keeps its id.
            try {
                ecsService.updateClusterSettings(clusterName, settings, ctx.region());
            } catch (RuntimeException failure) {
                unwindSettings(r, clusterName, prior, ctx.region(), failure);
                throw failure;
            }
        }
        r.setPhysicalId(cluster.getClusterName());
        r.getAttributes().put("Arn", cluster.getClusterArn());
    }

    /**
     * Puts back the settings an existing cluster had before a settings call that failed, which may
     * have changed them before failing. For this resource's own cluster the engine restores the
     * resource it held before the attempt, which never carried the snapshot, so the rollback walker
     * would skip {@link #rollbackUpdate}; a cluster the stack found rather than created is not
     * restored by anything else. A restore that fails too is recorded as a rollback failure, which
     * ends an update in UPDATE_ROLLBACK_FAILED for the resource's own cluster, and is attached to
     * the original failure. The restore repeats the call that just failed, so it can come back
     * with that very exception, which cannot be suppressed under itself.
     */
    private void unwindSettings(StackResource r, String clusterName, List<ClusterSetting> prior, String region,
                                RuntimeException failure) {
        try {
            ecsService.updateClusterSettings(clusterName, prior, region);
            r.getAttributes().remove(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR);
        } catch (RuntimeException unwindFailure) {
            String reason = "Could not restore the settings of ECS cluster " + clusterName + ": "
                    + unwindFailure.getMessage();
            // ponytail: a cluster the stack found has no id here, so the engine drops the resource
            // and nothing retries this; carrying it would need engine-side tracking for added resources.
            LOG.warnv("{0}", reason);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
            if (unwindFailure != failure) {
                failure.addSuppressed(unwindFailure);
            }
        }
    }

    /**
     * Every property of a task definition is create-only, so an update registers a fresh revision
     * under the same family, as CloudFormation does on AWS; the physical id is the new revision's
     * ARN, and the displaced revision is deregistered once the stack update commits. CloudFormation
     * hands the properties to the ECS handler without template validation, and they are read as it
     * reads them, failing the resource where its model validation does.
     */
    private void provisionTaskDefinition(StackResource r, JsonNode template, ProvisionContext ctx) {
        JsonNode props = new CfnPropertyTypes.HandlerReader(ctx.engine()).read(TASK_DEFINITION, template);
        String family = ctx.resolveOptional(props, "Family");
        if (family == null || family.isBlank()) {
            family = ctx.generatePhysicalName(r.getLogicalId(), 255, false);
        }
        List<ContainerDefinition> containerDefs = parseContainerDefinitions(props.get("ContainerDefinitions"), ctx);
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
     * displaced one deleted once the stack update commits, through the replacement cleanup. Either
     * way the resource completes only once the service is stable, as CloudFormation's does; an
     * in-place update that does not stabilize is put back on the prior configuration.
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
        boolean inPlace = ctx.isUpdate() && serviceName.equals(priorName)
                && clusterName(clusterRef).equals(clusterOfServiceArn(ctx.priorPhysicalId()));
        // Read before updateService, which changes this same model in place.
        EcsServiceModel prior = inPlace ? ecsService.serviceByArn(ctx.priorPhysicalId()) : null;
        String priorTaskDefinition = prior != null ? prior.getTaskDefinition() : null;
        Integer priorDesiredCount = prior != null ? prior.getDesiredCount() : null;
        NetworkConfiguration priorNetworkConfiguration = prior != null ? prior.getNetworkConfiguration() : null;
        if (inPlace) {
            svc = ecsService.updateService(clusterRef, serviceName, taskDefinition,
                    desiredCount, networkConfiguration, ctx.region());
        } else {
            svc = ecsService.createService(clusterRef, serviceName, taskDefinition,
                    desiredCount, launchType, loadBalancers, networkConfiguration, ctx.region());
        }

        r.setPhysicalId(svc.getServiceArn());
        r.getAttributes().put("Name", svc.getServiceName());
        r.getAttributes().put("ServiceArn", svc.getServiceArn());
        if (inPlace) {
            try {
                ecsService.awaitServiceStable(svc);
            } catch (RuntimeException failure) {
                restoreInPlace(r, failure, () -> ecsService.awaitServiceStable(ecsService.updateService(clusterRef,
                        svc.getServiceName(), priorTaskDefinition, priorDesiredCount, priorNetworkConfiguration,
                        ctx.region())));
                throw failure;
            }
        } else {
            awaitCreatedServiceStable(r, svc, ctx);
        }
    }

    /**
     * Puts a service updated in place back on the task definition, desired count and network
     * configuration it had, when the update does not stabilize, and waits for the restored
     * configuration to stabilize, as CloudFormation's rollback does. CloudFormationService restores
     * the prior resource record and reports the rollback complete unless told otherwise, so without
     * this the stack would claim a rollback while ECS still runs the failed configuration. A
     * restore that fails or does not stabilize is attached to the original failure and recorded as
     * a rollback failure.
     */
    private void restoreInPlace(StackResource r, RuntimeException failure, Runnable restore) {
        try {
            restore.run();
        } catch (RuntimeException restoreFailure) {
            String reason = "Could not restore " + r.getPhysicalId() + " after a failed update: "
                    + restoreFailure.getMessage();
            LOG.warnv("{0}", reason);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
            if (restoreFailure != failure) {
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    /**
     * Waits for a service this provision created to stabilize, as CloudFormation does before it
     * reports the resource complete. The resource is marked as owned meanwhile, so a service that
     * never stabilizes is deleted by the create rollback. A replacement created by a stack update
     * is removed here instead: the engine restores the prior resource and never learns the new ARN.
     */
    private void awaitCreatedServiceStable(StackResource r, EcsServiceModel svc, ProvisionContext ctx) {
        r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        try {
            ecsService.awaitServiceStable(svc);
        } catch (RuntimeException failure) {
            if (ctx.isUpdate()) {
                unwindReplacement(r, ctx.region(), failure);
            }
            throw failure;
        }
        r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
    }

    private void unwindReplacement(StackResource r, String region, RuntimeException failure) {
        try {
            deleteService(r.getPhysicalId(), region);
            r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            String reason = "Could not remove " + r.getPhysicalId() + ", the replacement created for "
                    + r.getLogicalId() + " by a failed update: " + cleanupFailure.getMessage();
            LOG.warnv("{0}", reason);
            r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
        }
    }

    /** The cluster name a template value addresses: a name, an ARN's last segment, or the default. */
    private static String clusterName(String clusterRef) {
        if (clusterRef == null || clusterRef.isBlank()) {
            return "default";
        }
        int slash = clusterRef.lastIndexOf('/');
        return AwsArnUtils.looksLikeArn(clusterRef) && slash >= 0 ? clusterRef.substring(slash + 1) : clusterRef;
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
            def.setLogConfiguration(parseLogConfiguration(item.path("LogConfiguration")));
            def.setFirelensConfiguration(parseFirelensConfiguration(item.path("FirelensConfiguration")));
            Map<String, Object> linuxParameters = parseLinuxParameters(item.path("LinuxParameters"));
            if (linuxParameters != null) {
                Map<String, Object> unparsed = new LinkedHashMap<>();
                unparsed.put("linuxParameters", linuxParameters);
                def.setUnparsed(unparsed);
            }
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

    private static LogConfiguration parseLogConfiguration(JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("LogDriver")) {
            return null;
        }
        return new LogConfiguration(node.path("LogDriver").asText(),
                parseOptions(node.path("Options")),
                node.has("SecretOptions") ? parseSecrets(node.path("SecretOptions")) : null);
    }

    /**
     * A container's {@code LinuxParameters}, read as the ECS handler reads them, in the shape
     * RegisterTaskDefinition takes them, and kept where that path keeps them: among the
     * definition's unparsed members, under {@code linuxParameters}. So DescribeTaskDefinition shows
     * them and the Fargate rules on them apply, as for a definition registered through the API.
     * The devices and tmpfs lists are always sent, empty when the template has none, as
     * CloudFormation's handler sends them (measured).
     */
    private static Map<String, Object> parseLinuxParameters(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode capabilities = node.path("Capabilities");
        if (capabilities.isObject()) {
            Map<String, Object> kernelCapabilities = new LinkedHashMap<>();
            putStringList(kernelCapabilities, "add", capabilities.path("Add"));
            putStringList(kernelCapabilities, "drop", capabilities.path("Drop"));
            result.put("capabilities", kernelCapabilities);
        }
        List<Map<String, Object>> devices = new ArrayList<>();
        for (JsonNode item : node.path("Devices")) {
            Map<String, Object> device = new LinkedHashMap<>();
            putValue(device, "hostPath", item.path("HostPath"));
            putValue(device, "containerPath", item.path("ContainerPath"));
            putStringList(device, "permissions", item.path("Permissions"));
            devices.add(device);
        }
        result.put("devices", devices);
        putValue(result, "initProcessEnabled", node.path("InitProcessEnabled"));
        putValue(result, "maxSwap", node.path("MaxSwap"));
        putValue(result, "sharedMemorySize", node.path("SharedMemorySize"));
        putValue(result, "swappiness", node.path("Swappiness"));
        List<Map<String, Object>> mounts = new ArrayList<>();
        for (JsonNode item : node.path("Tmpfs")) {
            Map<String, Object> mount = new LinkedHashMap<>();
            putValue(mount, "containerPath", item.path("ContainerPath"));
            putStringList(mount, "mountOptions", item.path("MountOptions"));
            putValue(mount, "size", item.path("Size"));
            mounts.add(mount);
        }
        result.put("tmpfs", mounts);
        return result;
    }

    /** A member the template sets, as the text, integer or boolean the handler converted it to. */
    private static void putValue(Map<String, Object> target, String key, JsonNode value) {
        if (value.isTextual()) {
            target.put(key, value.textValue());
        } else if (value.isInt()) {
            target.put(key, value.intValue());
        } else if (value.isBoolean()) {
            target.put(key, value.booleanValue());
        }
    }

    private static void putStringList(Map<String, Object> target, String key, JsonNode value) {
        if (value.isArray()) {
            target.put(key, toStringList(value));
        }
    }

    private static FirelensConfiguration parseFirelensConfiguration(JsonNode node) {
        if (node == null || !node.isObject() || !node.hasNonNull("Type")) {
            return null;
        }
        return new FirelensConfiguration(node.path("Type").asText(), parseOptions(node.path("Options")));
    }

    private static LinkedHashMap<String, String> parseOptions(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        LinkedHashMap<String, String> options = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> options.put(entry.getKey(), entry.getValue().asText()));
        return options;
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
        JsonNode awsvpc = ctx.engine().omitNoValue(node).path("AwsvpcConfiguration");
        AwsVpcConfiguration awsvpcConfig = new AwsVpcConfiguration();
        awsvpcConfig.setSubnets(ctx.engine().resolveStringList(awsvpc.get("Subnets")));
        awsvpcConfig.setSecurityGroups(ctx.engine().resolveStringList(awsvpc.get("SecurityGroups")));
        JsonNode assignPublicIp = resolved.path("AwsvpcConfiguration").path("AssignPublicIp");
        if (!assignPublicIp.isMissingNode() && !assignPublicIp.isNull()) {
            awsvpcConfig.setAssignPublicIp(assignPublicIp.asText());
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
