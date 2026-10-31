package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreControlService;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreControlService.EndpointLocation;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreMemoryService;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeEndpoint;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Memory;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CloudFormation provisioning for the Amazon Bedrock AgentCore control plane:
 * {@code AWS::BedrockAgentCore::Runtime}, {@code AWS::BedrockAgentCore::RuntimeEndpoint} and
 * {@code AWS::BedrockAgentCore::Memory}.
 *
 * <p>Physical ids and {@code Ref} follow each registry schema's {@code primaryIdentifier}: the
 * runtime's {@code AgentRuntimeId}, the endpoint's {@code AgentRuntimeEndpointArn} and the
 * memory's {@code MemoryArn}. The template reference prose says the runtime's {@code Ref} is its
 * ARN, but the schema (and CDK's generated {@code RuntimeReference}) identify a runtime by id, and
 * the schema is what CloudFormation resolves against. Every top-level read-only property each
 * schema declares is published for {@code Fn::GetAtt}; the runtime's nested
 * {@code WorkloadIdentityDetails} is published in the dotted form GetAtt addresses it by.
 *
 * <p>An update whose create-only properties are unchanged (the runtime's name; the endpoint's
 * runtime id and name; the memory's name and encryption key) is applied in place through the
 * service's update call and a tag diff, so the physical id survives as it does on AWS. The
 * template is the whole declared configuration: a property it no longer carries is cleared on the
 * entity, not left over from the previous update. What the in-place update changes is snapshotted
 * first (see {@link #IN_PLACE_PRIOR_ATTR}) so a stack rollback, or this provision failing after
 * the change, puts the entity back. Any create-only change creates the replacement and leaves the
 * displaced entity to the {@link ReplacementCleanup} record, deleted once the stack update commits.
 *
 * <p>Template properties the emulated service has no model for are accepted and logged once at
 * warn, by name, rather than failing the stack; the template is still valid CloudFormation.
 */
@ApplicationScoped
public class BedrockAgentCoreCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreCfnProvisioner.class);

    private static final String RUNTIME = "AWS::BedrockAgentCore::Runtime";
    private static final String RUNTIME_ENDPOINT = "AWS::BedrockAgentCore::RuntimeEndpoint";
    private static final String MEMORY = "AWS::BedrockAgentCore::Memory";

    /**
     * Template properties the backing service does not store. The runtime's capacity provider,
     * filesystems, lifecycle, platform version and request-header allowlist, and the memory's
     * strategies, indexed keys, namespace keys and stream delivery have no representation in the
     * emulated control plane; the endpoint model carries no tags.
     */
    private static final Set<String> RUNTIME_NOT_STORED = Set.of("CapacityProviderConfiguration",
            "FilesystemConfigurations", "LifecycleConfiguration", "PlatformVersion", "RequestHeaderConfiguration");
    private static final Set<String> RUNTIME_ENDPOINT_NOT_STORED = Set.of("Tags");
    private static final Set<String> MEMORY_NOT_STORED = Set.of("IndexedKeys", "MemoryStrategies",
            "NamespaceKeys", "StreamDeliveryResources");
    private static final Set<String> WARNED_NOT_STORED = ConcurrentHashMap.newKeySet();

    /**
     * The API requires a network configuration; the template does not. CloudFormation fills in the
     * public mode AWS documents as the default
     * (https://docs.aws.amazon.com/bedrock-agentcore-control/latest/APIReference/API_NetworkConfiguration.html).
     */
    private static final String DEFAULT_NETWORK_MODE = "PUBLIC";

    /**
     * What an in-place update changed on a reused entity, recorded before the change so a stack
     * rollback can put it back: the runtime's configuration and tags, the endpoint's target version
     * and description, the memory's mutable properties and tags. Internal, like the other
     * {@code __Floci} attributes.
     */
    static final String IN_PLACE_PRIOR_ATTR = "__FlociBedrockAgentCorePrior";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BedrockAgentCoreControlService runtimeService;
    private final BedrockAgentCoreMemoryService memoryService;

    @Inject
    public BedrockAgentCoreCfnProvisioner(BedrockAgentCoreControlService runtimeService,
                                          BedrockAgentCoreMemoryService memoryService) {
        this.runtimeService = runtimeService;
        this.memoryService = memoryService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(RUNTIME, RUNTIME_ENDPOINT, MEMORY);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case RUNTIME -> provisionRuntime(r, props, ctx);
            case RUNTIME_ENDPOINT -> provisionRuntimeEndpoint(r, props, ctx);
            case MEMORY -> provisionMemory(r, props, ctx);
            default -> throw new AwsException("ValidationError",
                    "Unsupported resource type: " + r.getResourceType(), 400);
        }
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    // ──────────────────────────── Runtime ────────────────────────────

    private void provisionRuntime(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String name = required(props, "AgentRuntimeName", ctx, RUNTIME);
        String roleArn = required(props, "RoleArn", ctx, RUNTIME);
        JsonNode artifact = apiShape(props, "AgentRuntimeArtifact", ctx);
        if (artifact == null) {
            throw new AwsException("ValidationError", RUNTIME + " requires AgentRuntimeArtifact", 400);
        }
        JsonNode network = apiShape(props, "NetworkConfiguration", ctx);
        if (network == null) {
            network = JsonNodeFactory.instance.objectNode().put("networkMode", DEFAULT_NETWORK_MODE);
        }
        String description = ctx.resolveOrDefault(props, "Description", null);
        Map<String, String> environment = resolveStringMap(props, "EnvironmentVariables", ctx);
        JsonNode authorizer = apiShape(props, "AuthorizerConfiguration", ctx);
        // The template's ProtocolConfiguration is the bare protocol enum; the API wraps it.
        String protocol = ctx.resolveOrDefault(props, "ProtocolConfiguration", null);
        JsonNode protocolConfiguration = protocol == null ? null
                : JsonNodeFactory.instance.objectNode().put("serverProtocol", protocol);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        warnNotStored(RUNTIME, props, RUNTIME_NOT_STORED);

        AgentRuntime prior = ctx.isUpdate() ? findRuntime(ctx.priorPhysicalId(), region) : null;
        if (prior != null && name.equals(prior.getAgentRuntimeName())) {
            // Every UpdateAgentRuntime publishes a new version, as on AWS, so an UpdateStack that
            // left this runtime alone must not call it and bump the version for nothing.
            boolean configurationChanged = !(Objects.equals(roleArn, prior.getRoleArn())
                    && Objects.equals(description, prior.getDescription())
                    && artifact.equals(prior.getAgentRuntimeArtifact())
                    && network.equals(prior.getNetworkConfiguration())
                    && Objects.equals(authorizer, prior.getAuthorizerConfiguration())
                    && Objects.equals(protocolConfiguration, prior.getProtocolConfiguration())
                    && environment.equals(prior.getEnvironmentVariables()));
            String arn = runtimeService.arn(prior, String.valueOf(prior.getLatestVersion()), region);
            Map<String, String> priorTags = runtimeService.getTagsByArn(region, arn);
            if (configurationChanged || !priorTags.equals(tags)) {
                ObjectNode snapshot = inPlacePrior(prior.getAgentRuntimeId(), region);
                if (configurationChanged) {
                    ObjectNode configuration = snapshot.putObject("runtime");
                    configuration.put("roleArn", prior.getRoleArn());
                    configuration.put("description", prior.getDescription());
                    configuration.set("artifact", prior.getAgentRuntimeArtifact());
                    configuration.set("network", prior.getNetworkConfiguration());
                    configuration.set("authorizer", prior.getAuthorizerConfiguration());
                    configuration.set("protocol", prior.getProtocolConfiguration());
                    configuration.set("environment", MAPPER.valueToTree(prior.getEnvironmentVariables()));
                }
                snapshot.set("tags", MAPPER.valueToTree(priorTags));
                writeInPlacePrior(r, snapshot);
            }
            JsonNode finalNetwork = network;
            CfnRollback.guarded(r, ctx, false, () -> { }, () -> undoInPlace(r), () -> {
                AgentRuntime runtime = configurationChanged
                        ? runtimeService.updateAgentRuntime(prior.getAgentRuntimeId(), artifact, finalNetwork, roleArn,
                                description, environment, authorizer, protocolConfiguration, region)
                        : prior;
                reconcileRuntimeTags(arn, tags, region);
                publishRuntime(r, runtime, region);
            });
        } else {
            AgentRuntime runtime = runtimeService.createAgentRuntime(name, artifact, network, roleArn, description,
                    environment, authorizer, protocolConfiguration, null, region);
            publishRuntime(r, runtime, region);
            CfnRollback.guarded(r, ctx, true,
                    () -> runtimeService.deleteAgentRuntime(runtime.getAgentRuntimeId(), null, region),
                    () -> undoInPlace(r), () -> {
                        if (!tags.isEmpty()) {
                            runtimeService.tagByArn(region, r.getAttributes().get("AgentRuntimeArn"), tags);
                        }
                    });
        }
    }

    private void reconcileRuntimeTags(String arn, Map<String, String> desired, String region) {
        List<String> stale = ProvisionContext.staleTagKeys(runtimeService.getTagsByArn(region, arn), desired);
        if (!stale.isEmpty()) {
            runtimeService.untagByArn(region, arn, stale);
        }
        if (!desired.isEmpty()) {
            runtimeService.tagByArn(region, arn, desired);
        }
    }

    private void publishRuntime(StackResource r, AgentRuntime runtime, String region) {
        String version = String.valueOf(runtime.getLatestVersion());
        r.setPhysicalId(runtime.getAgentRuntimeId());
        r.getAttributes().put("AgentRuntimeArn", runtimeService.arn(runtime, version, region));
        r.getAttributes().put("AgentRuntimeId", runtime.getAgentRuntimeId());
        r.getAttributes().put("AgentRuntimeVersion", version);
        r.getAttributes().put("Status", runtime.getStatus());
        r.getAttributes().put("CreatedAt", runtime.getCreatedAt().toString());
        r.getAttributes().put("LastUpdatedAt", runtime.getLastUpdatedAt().toString());
        // The emulated runtime is READY from creation, so there is never a failure to report.
        r.getAttributes().put("FailureReason", "");
        r.getAttributes().put("WorkloadIdentityDetails.WorkloadIdentityArn", runtime.getWorkloadIdentityArn());
    }

    /** The runtime a prior physical id names, or null when it names none (gone, or a dispatcher stub). */
    private AgentRuntime findRuntime(String agentRuntimeId, String region) {
        try {
            return runtimeService.getAgentRuntime(agentRuntimeId, region);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    // ──────────────────────────── RuntimeEndpoint ────────────────────────────

    private void provisionRuntimeEndpoint(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String runtimeId = required(props, "AgentRuntimeId", ctx, RUNTIME_ENDPOINT);
        String name = required(props, "Name", ctx, RUNTIME_ENDPOINT);
        String version = ctx.resolveOrDefault(props, "AgentRuntimeVersion", null);
        String description = ctx.resolveOrDefault(props, "Description", null);
        warnNotStored(RUNTIME_ENDPOINT, props, RUNTIME_ENDPOINT_NOT_STORED);

        EndpointLocation prior = ctx.isUpdate()
                ? runtimeService.findEndpointByArn(region, ctx.priorPhysicalId()).orElse(null)
                : null;
        if (prior != null && runtimeId.equals(prior.runtime().getAgentRuntimeId())
                && name.equals(prior.endpoint().getName())) {
            AgentRuntimeEndpoint existing = prior.endpoint();
            // An omitted version leaves the endpoint on the version it serves, as UpdateAgentRuntimeEndpoint does.
            boolean unchanged = (version == null || version.equals(existing.getTargetVersion()))
                    && Objects.equals(description, existing.getDescription());
            if (unchanged) {
                publishRuntimeEndpoint(r, prior.runtime(), existing, region);
                return;
            }
            ObjectNode snapshot = inPlacePrior(runtimeId, region);
            ObjectNode endpointPrior = snapshot.putObject("endpoint");
            endpointPrior.put("name", name);
            endpointPrior.put("targetVersion", existing.getTargetVersion());
            endpointPrior.put("description", existing.getDescription());
            writeInPlacePrior(r, snapshot);
            CfnRollback.guarded(r, ctx, false, () -> { }, () -> undoInPlace(r),
                    () -> publishRuntimeEndpoint(r, prior.runtime(),
                            runtimeService.updateEndpoint(runtimeId, name, version, description, true, region), region));
        } else {
            AgentRuntimeEndpoint endpoint = runtimeService.createEndpoint(runtimeId, name, version, description,
                    null, region);
            r.setPhysicalId(runtimeService.endpointArn(endpoint, region));
            CfnRollback.guarded(r, ctx, true, () -> runtimeService.deleteEndpoint(runtimeId, name, null, region),
                    () -> undoInPlace(r),
                    () -> publishRuntimeEndpoint(r, runtimeService.getAgentRuntime(runtimeId, region), endpoint, region));
        }
    }

    private void publishRuntimeEndpoint(StackResource r, AgentRuntime runtime, AgentRuntimeEndpoint endpoint,
                                        String region) {
        String endpointArn = runtimeService.endpointArn(endpoint, region);
        r.setPhysicalId(endpointArn);
        r.getAttributes().put("AgentRuntimeEndpointArn", endpointArn);
        r.getAttributes().put("AgentRuntimeArn", runtimeService.arn(runtime, endpoint.getTargetVersion(), region));
        r.getAttributes().put("Id", endpoint.getUuid());
        r.getAttributes().put("Status", endpoint.getStatus());
        r.getAttributes().put("TargetVersion", endpoint.getTargetVersion());
        r.getAttributes().put("LiveVersion", endpoint.getLiveVersion());
        r.getAttributes().put("CreatedAt", endpoint.getCreatedAt().toString());
        r.getAttributes().put("LastUpdatedAt", endpoint.getLastUpdatedAt().toString());
        // The emulated endpoint is READY from creation, so there is never a failure to report.
        r.getAttributes().put("FailureReason", "");
    }

    // ──────────────────────────── Memory ────────────────────────────

    private void provisionMemory(StackResource r, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        String name = required(props, "Name", ctx, MEMORY);
        Integer eventExpiryDuration = eventExpiryDuration(props, ctx);
        String description = ctx.resolveOrDefault(props, "Description", null);
        String encryptionKeyArn = ctx.resolveOrDefault(props, "EncryptionKeyArn", null);
        String executionRoleArn = ctx.resolveOrDefault(props, "MemoryExecutionRoleArn", null);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        warnNotStored(MEMORY, props, MEMORY_NOT_STORED);

        Memory prior = ctx.isUpdate() ? findMemory(ctx.priorPhysicalId(), region) : null;
        if (prior != null && name.equals(prior.getName())
                && Objects.equals(encryptionKeyArn, prior.getEncryptionKeyArn())) {
            boolean configurationChanged = !(Objects.equals(description, prior.getDescription())
                    && eventExpiryDuration.equals(prior.getEventExpiryDuration())
                    && Objects.equals(executionRoleArn, prior.getMemoryExecutionRoleArn()));
            String arn = memoryService.arn(prior, region);
            Map<String, String> priorTags = memoryService.getTagsByArn(region, arn);
            if (configurationChanged || !priorTags.equals(tags)) {
                ObjectNode snapshot = inPlacePrior(prior.getMemoryId(), region);
                if (configurationChanged) {
                    ObjectNode configuration = snapshot.putObject("memory");
                    configuration.put("description", prior.getDescription());
                    configuration.put("eventExpiryDuration", prior.getEventExpiryDuration());
                    configuration.put("memoryExecutionRoleArn", prior.getMemoryExecutionRoleArn());
                }
                snapshot.set("tags", MAPPER.valueToTree(priorTags));
                writeInPlacePrior(r, snapshot);
            }
            CfnRollback.guarded(r, ctx, false, () -> { }, () -> undoInPlace(r), () -> {
                Memory memory = configurationChanged
                        ? memoryService.update(prior.getMemoryId(), description, eventExpiryDuration,
                                executionRoleArn, true, region)
                        : prior;
                reconcileMemoryTags(arn, tags, region);
                publishMemory(r, memory, region);
            });
        } else {
            // CreateMemory stores the tags with the memory, so there is no step after it to undo.
            publishMemory(r, memoryService.create(name, eventExpiryDuration, description, encryptionKeyArn,
                    executionRoleArn, tags, null, region), region);
        }
    }

    private void reconcileMemoryTags(String arn, Map<String, String> desired, String region) {
        List<String> stale = ProvisionContext.staleTagKeys(memoryService.getTagsByArn(region, arn), desired);
        if (!stale.isEmpty()) {
            memoryService.untagByArn(region, arn, stale);
        }
        if (!desired.isEmpty()) {
            memoryService.tagByArn(region, arn, desired);
        }
    }

    private void publishMemory(StackResource r, Memory memory, String region) {
        String arn = memoryService.arn(memory, region);
        r.setPhysicalId(arn);
        r.getAttributes().put("MemoryArn", arn);
        r.getAttributes().put("MemoryId", memory.getMemoryId());
        r.getAttributes().put("Status", memory.getStatus());
        r.getAttributes().put("CreatedAt", memory.getCreatedAt().toString());
        r.getAttributes().put("UpdatedAt", memory.getUpdatedAt().toString());
        // The emulated memory is ACTIVE from creation, so there is never a failure to report.
        r.getAttributes().put("FailureReason", "");
    }

    /** The memory a prior physical id names, or null when it names none (gone, or a dispatcher stub). */
    private Memory findMemory(String arn, String region) {
        if (!AwsArnUtils.isArn(arn)) {
            return null;
        }
        try {
            return memoryService.getByArn(region, arn);
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private static Integer eventExpiryDuration(JsonNode props, ProvisionContext ctx) {
        String raw = required(props, "EventExpiryDuration", ctx, MEMORY);
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    MEMORY + " EventExpiryDuration must be an integer, got " + raw, 400);
        }
    }

    // ──────────────────────────── Delete ────────────────────────────

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case RUNTIME -> CfnDeletes.safeDelete("AgentCore runtime", physicalId,
                    () -> runtimeService.deleteAgentRuntime(physicalId, null, region),
                    "ResourceNotFoundException");
            case RUNTIME_ENDPOINT -> runtimeService.findEndpointByArn(region, physicalId).ifPresentOrElse(
                    location -> CfnDeletes.safeDelete("AgentCore runtime endpoint", physicalId,
                            () -> runtimeService.deleteEndpoint(location.runtime().getAgentRuntimeId(),
                                    location.endpoint().getName(), null, region),
                            "ResourceNotFoundException"),
                    () -> LOG.debugv("AgentCore runtime endpoint already gone, treating as deleted: {0}",
                            physicalId));
            case MEMORY -> {
                // A dispatcher stub from before this provisioner existed is not an ARN and names no memory.
                if (!AwsArnUtils.isArn(physicalId)) {
                    return;
                }
                CfnDeletes.safeDelete("AgentCore memory", physicalId,
                        () -> memoryService.delete(memoryService.getByArn(region, physicalId).getMemoryId(),
                                null, region),
                        "ResourceNotFoundException");
            }
            default -> throw new AwsException("ValidationError",
                    "Unsupported resource type: " + resourceType, 400);
        }
    }

    // ──────────────────────────── Update lifecycle ────────────────────────────

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
        resource.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record: the displaced entity was never touched.
     * A reused entity the update changed in place is put back from the snapshot taken before the
     * change, which is spent only once the restore succeeded so a restore that throws is reported
     * rather than claimed. Without either, the update changed nothing about the entity. A provision
     * that fails mid-way restores its own snapshot before the failure propagates (see
     * {@link #guarded}), because the engine discards the attempted resource, snapshot included.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            resource.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
            return true;
        }
        String snapshot = resource.getAttributes().get(IN_PLACE_PRIOR_ATTR);
        if (snapshot != null) {
            restoreInPlace(resource, snapshot);
            resource.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
        }
        return true;
    }

    /**
     * Puts the entity back the way the snapshot recorded it, through the same service calls the
     * update used, and republishes the resource's attributes from the entity as it now stands: a
     * restored runtime is a new version, as it would be on AWS.
     */
    private void restoreInPlace(StackResource resource, String snapshot) {
        ObjectNode prior = readSnapshot(resource, snapshot);
        String region = prior.path("region").asText();
        String entityId = prior.path("entityId").asText();
        switch (resource.getResourceType()) {
            case RUNTIME -> {
                AgentRuntime runtime = runtimeService.getAgentRuntime(entityId, region);
                if (prior.has("runtime")) {
                    ObjectNode configuration = (ObjectNode) prior.get("runtime");
                    runtime = runtimeService.updateAgentRuntime(entityId, configuration.get("artifact"),
                            configuration.get("network"), text(configuration, "roleArn"),
                            text(configuration, "description"), stringMap(configuration.get("environment")),
                            node(configuration, "authorizer"), node(configuration, "protocol"), region);
                }
                if (prior.has("tags")) {
                    reconcileRuntimeTags(runtimeService.arn(runtime, String.valueOf(runtime.getLatestVersion()), region),
                            stringMap(prior.get("tags")), region);
                }
                publishRuntime(resource, runtime, region);
            }
            case RUNTIME_ENDPOINT -> {
                ObjectNode endpointPrior = (ObjectNode) prior.get("endpoint");
                AgentRuntimeEndpoint endpoint = runtimeService.updateEndpoint(entityId, text(endpointPrior, "name"),
                        text(endpointPrior, "targetVersion"), text(endpointPrior, "description"), true, region);
                publishRuntimeEndpoint(resource, runtimeService.getAgentRuntime(entityId, region), endpoint, region);
            }
            case MEMORY -> {
                Memory memory = memoryService.get(entityId, region);
                if (prior.has("memory")) {
                    ObjectNode configuration = (ObjectNode) prior.get("memory");
                    memory = memoryService.update(entityId, text(configuration, "description"),
                            configuration.get("eventExpiryDuration").asInt(),
                            text(configuration, "memoryExecutionRoleArn"), true, region);
                }
                if (prior.has("tags")) {
                    reconcileMemoryTags(memoryService.arn(memory, region), stringMap(prior.get("tags")), region);
                }
                publishMemory(resource, memory, region);
            }
            default -> throw new IllegalStateException("No in-place restore for " + resource.getResourceType());
        }
    }

    /**
     * A fresh snapshot for this resource. Each provision changes one entity in one go, so the
     * snapshot is written once, before the change, with the entity's state as it stands now.
     */
    private static ObjectNode inPlacePrior(String entityId, String region) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("region", region);
        node.put("entityId", entityId);
        return node;
    }

    private static void writeInPlacePrior(StackResource r, ObjectNode node) {
        r.getAttributes().put(IN_PLACE_PRIOR_ATTR, node.toString());
    }

    private static ObjectNode readSnapshot(StackResource r, String snapshot) {
        try {
            return (ObjectNode) MAPPER.readTree(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable in-place snapshot on " + r.getLogicalId(), e);
        }
    }

    /**
     * The in-place undo handed to {@link CfnRollback#guarded}: puts a reused entity back from its
     * snapshot and spends it, leaving it in place when the restore fails so the next rollback can
     * retry.
     */
    private void undoInPlace(StackResource r) {
        String snapshot = r.getAttributes().get(IN_PLACE_PRIOR_ATTR);
        if (snapshot != null) {
            restoreInPlace(r, snapshot);
            r.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static String required(JsonNode props, String name, ProvisionContext ctx, String type) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", type + " requires " + name, 400);
        }
        return value;
    }

    /**
     * A resolved object property in the API's camelCase, or null when absent. A member whose
     * {@code Fn::If} selects {@code AWS::NoValue} is omitted, as CloudFormation omits it, rather
     * than resolved to an empty string.
     */
    private static JsonNode apiShape(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(props.get(name));
        return resolved == null || resolved.isNull() || !resolved.isObject() ? null : toApiShape(resolved);
    }

    /** Recursively renames the members of a resolved node to the API's camelCase, dropping nulls. */
    private static JsonNode toApiShape(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(field -> {
                if (!field.getValue().isNull()) {
                    out.set(decapitalize(field.getKey()), toApiShape(field.getValue()));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> out.add(toApiShape(item)));
            return out;
        }
        return node;
    }

    private static String decapitalize(String key) {
        return key.isEmpty() ? key : Character.toLowerCase(key.charAt(0)) + key.substring(1);
    }

    /** A {@code {key: value}} property with each value resolved, or an empty map when absent. */
    private static Map<String, String> resolveStringMap(JsonNode props, String name, ProvisionContext ctx) {
        Map<String, String> values = new LinkedHashMap<>();
        if (props == null || !props.has(name)) {
            return values;
        }
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(props.get(name));
        if (resolved != null && resolved.isObject()) {
            resolved.fields().forEachRemaining(field -> {
                String value = ctx.engine().resolve(field.getValue());
                values.put(field.getKey(), value == null ? "" : value);
            });
        }
        return values;
    }

    /** A snapshot's string map member, as a mutable map. */
    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(field -> values.put(field.getKey(), field.getValue().asText()));
        }
        return values;
    }

    private static String text(ObjectNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static JsonNode node(ObjectNode node, String field) {
        return node.hasNonNull(field) ? node.get(field) : null;
    }

    /** Logs, once per property per type, a template property the emulated service cannot store. */
    private static void warnNotStored(String type, JsonNode props, Set<String> notStored) {
        if (props == null) {
            return;
        }
        for (String name : notStored) {
            if (props.has(name) && !props.get(name).isNull() && WARNED_NOT_STORED.add(type + "." + name)) {
                LOG.warnv("{0} {1} is accepted but not stored: the emulated AgentCore control plane "
                        + "has no model for it.", type, name);
            }
        }
    }
}
