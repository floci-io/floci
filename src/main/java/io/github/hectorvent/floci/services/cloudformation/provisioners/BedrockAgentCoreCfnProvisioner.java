package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * service's update call and a tag diff, so the physical id survives as it does on AWS. Any
 * create-only change creates the replacement and leaves the displaced entity to the
 * {@link ReplacementCleanup} record, deleted once the stack update commits.
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
        String description = ctx.resolveOptional(props, "Description");
        Map<String, String> environment = resolveStringMap(props, "EnvironmentVariables", ctx);
        JsonNode authorizer = apiShape(props, "AuthorizerConfiguration", ctx);
        // The template's ProtocolConfiguration is the bare protocol enum; the API wraps it.
        String protocol = ctx.resolveOptional(props, "ProtocolConfiguration");
        JsonNode protocolConfiguration = protocol == null || protocol.isBlank() ? null
                : JsonNodeFactory.instance.objectNode().put("serverProtocol", protocol);
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        warnNotStored(RUNTIME, props, RUNTIME_NOT_STORED);

        AgentRuntime prior = ctx.isUpdate() ? findRuntime(ctx.priorPhysicalId(), region) : null;
        AgentRuntime runtime;
        if (prior != null && name.equals(prior.getAgentRuntimeName())) {
            // Every UpdateAgentRuntime publishes a new version, as on AWS, so an UpdateStack that
            // left this runtime alone must not call it and bump the version for nothing.
            boolean unchanged = Objects.equals(roleArn, prior.getRoleArn())
                    && Objects.equals(description, prior.getDescription())
                    && artifact.equals(prior.getAgentRuntimeArtifact())
                    && network.equals(prior.getNetworkConfiguration())
                    && (authorizer == null || authorizer.equals(prior.getAuthorizerConfiguration()))
                    && (protocolConfiguration == null
                            || protocolConfiguration.equals(prior.getProtocolConfiguration()))
                    && environment.equals(prior.getEnvironmentVariables());
            runtime = unchanged ? prior : runtimeService.updateAgentRuntime(prior.getAgentRuntimeId(), artifact,
                    network, roleArn, description, environment, authorizer, protocolConfiguration, region);
            String arn = runtimeService.arn(runtime, String.valueOf(runtime.getLatestVersion()), region);
            reconcileRuntimeTags(arn, tags, region);
        } else {
            runtime = runtimeService.createAgentRuntime(name, artifact, network, roleArn, description,
                    environment, authorizer, protocolConfiguration, null, region);
            if (!tags.isEmpty()) {
                runtimeService.tagByArn(region,
                        runtimeService.arn(runtime, String.valueOf(runtime.getLatestVersion()), region), tags);
            }
        }
        publishRuntime(r, runtime, region);
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
        String description = ctx.resolveOptional(props, "Description");
        warnNotStored(RUNTIME_ENDPOINT, props, RUNTIME_ENDPOINT_NOT_STORED);

        EndpointLocation prior = ctx.isUpdate()
                ? runtimeService.findEndpointByArn(region, ctx.priorPhysicalId()).orElse(null)
                : null;
        AgentRuntime runtime;
        AgentRuntimeEndpoint endpoint;
        if (prior != null && runtimeId.equals(prior.runtime().getAgentRuntimeId())
                && name.equals(prior.endpoint().getName())) {
            runtime = prior.runtime();
            // An omitted version leaves the endpoint on the version it serves, as UpdateAgentRuntimeEndpoint does.
            boolean unchanged = (version == null || version.equals(prior.endpoint().getTargetVersion()))
                    && Objects.equals(description, prior.endpoint().getDescription());
            endpoint = unchanged ? prior.endpoint()
                    : runtimeService.updateEndpoint(runtimeId, name, version, description, region);
        } else {
            endpoint = runtimeService.createEndpoint(runtimeId, name, version, description, null, region);
            runtime = runtimeService.getAgentRuntime(runtimeId, region);
        }
        publishRuntimeEndpoint(r, runtime, endpoint, region);
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
        String description = ctx.resolveOptional(props, "Description");
        String encryptionKeyArn = ctx.resolveOrDefault(props, "EncryptionKeyArn", null);
        String executionRoleArn = ctx.resolveOptional(props, "MemoryExecutionRoleArn");
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        warnNotStored(MEMORY, props, MEMORY_NOT_STORED);

        Memory prior = ctx.isUpdate() ? findMemory(ctx.priorPhysicalId(), region) : null;
        Memory memory;
        if (prior != null && name.equals(prior.getName())
                && Objects.equals(encryptionKeyArn, prior.getEncryptionKeyArn())) {
            memory = memoryService.update(prior.getMemoryId(), description, eventExpiryDuration,
                    executionRoleArn, region);
            String arn = memoryService.arn(memory, region);
            List<String> stale = ProvisionContext.staleTagKeys(memoryService.getTagsByArn(region, arn), tags);
            if (!stale.isEmpty()) {
                memoryService.untagByArn(region, arn, stale);
            }
            if (!tags.isEmpty()) {
                memoryService.tagByArn(region, arn, tags);
            }
        } else {
            memory = memoryService.create(name, eventExpiryDuration, description, encryptionKeyArn,
                    executionRoleArn, tags, null, region);
        }
        publishMemory(r, memory, region);
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
        if (!arn.startsWith("arn:")) {
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
                if (!physicalId.startsWith("arn:")) {
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

    // ──────────────────────────── Replacement lifecycle ────────────────────────────

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

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static String required(JsonNode props, String name, ProvisionContext ctx, String type) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", type + " requires " + name, 400);
        }
        return value;
    }

    /** A resolved object property in the API's camelCase, or null when absent. */
    private static JsonNode apiShape(JsonNode props, String name, ProvisionContext ctx) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
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
        JsonNode resolved = ctx.engine().resolveNode(props.get(name));
        if (resolved != null && resolved.isObject()) {
            resolved.fields().forEachRemaining(field -> {
                String value = ctx.engine().resolve(field.getValue());
                values.put(field.getKey(), value == null ? "" : value);
            });
        }
        return values;
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
