package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.provisioners.OpenApiDocuments;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ReplacementCleanup;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ProvisionContext;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnDynamicReferences;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.UpdateCleanupResult;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.pipes.model.DesiredState;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.apigatewayv2.model.*;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Provisions individual CloudFormation resource types using Floci's existing service implementations.
 */
@ApplicationScoped
public class CloudFormationResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(CloudFormationResourceProvisioner.class);
    static final String UPDATE_ROLLBACK_RESTORED_ATTR = CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR;
    static final String UPDATE_ROLLBACK_FAILURE_ATTR = CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR;
    private static final int GENERATED_NAME_SUFFIX_LENGTH = 12;

    /**
     * Types whose delete needs the whole {@link StackResource}, not just its physical id, because a
     * create-time attribute or stashed state identifies what to remove. Now empty: every such type
     * moved to a per-service provisioner that overrides {@code delete(StackResource, String)}. Kept
     * as the gate {@code CfnDeletePrecedenceTest} checks, which fails if a type is ever both listed
     * here and served by the registry.
     */
    static final Set<String> DELETE_NEEDS_STACK_RESOURCE = Set.of();

    /**
     * Resource types still served by the switch in {@link #provision} rather than the registry. Now
     * empty: every type moved to a per-service provisioner. Kept as the guard that fails loudly,
     * rather than silently stubbing, if an arm is ever removed without clearing its entry, and kept
     * in step with the registry by {@code CfnResourceInventoryTest}.
     */
    static final Set<String> LEGACY_SWITCH_TYPES = Set.of();

    private final ObjectMapper objectMapper;
    // Item 15 decomposition: every resource type now has a per-service provisioner reached through
    // the registry, so the switch below serves no type and only stubs or rejects the unknown. The
    // last type-specific service deps left with the types they served; what remains is what the
    // dispatch itself needs.
    private final CloudFormationResourceRegistry resourceRegistry;
    private final CfnDynamicReferences dynamicReferences;
    private final EmulatorConfig config;

    @Inject
    public CloudFormationResourceProvisioner(ObjectMapper objectMapper,
                                             CloudFormationResourceRegistry resourceRegistry,
                                             CfnDynamicReferences dynamicReferences,
                                             EmulatorConfig config) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.resourceRegistry = resourceRegistry;
        this.dynamicReferences = dynamicReferences;
    }

    /**
     * Provisions a single resource. Returns the populated StackResource (physicalId + attributes set).
     *
     * <p>A resource type with no provisioner is stubbed: a synthetic physical id, an
     * {@code arn:aws:stub:::} ARN attribute and {@code CREATE_COMPLETE}, logged at warn and
     * carrying a status reason saying nothing was created. With
     * {@code floci.services.cloudformation.allow-stub-unsupported-resource-types} off it comes back
     * {@code CREATE_FAILED} instead, with no physical id.
     */
    public StackResource provision(String logicalId, String resourceType, JsonNode properties,
                                   CloudFormationTemplateEngine engine, String region, String accountId,
                                   String stackName) {
        return provision(logicalId, resourceType, properties, engine, region, accountId, stackName, null);
    }

    public StackResource provision(String logicalId, String resourceType, JsonNode properties,
                                   CloudFormationTemplateEngine engine, String region, String accountId,
                                   String stackName, String existingPhysicalId) {
        return provision(logicalId, resourceType, properties, engine, region, accountId, stackName,
                existingPhysicalId, Map.of());
    }

    public StackResource provision(String logicalId, String resourceType, JsonNode properties,
                                   CloudFormationTemplateEngine engine, String region, String accountId,
                                   String stackName, String existingPhysicalId,
                                   Map<String, String> existingAttributes) {
        return provision(logicalId, resourceType, properties, engine, region, accountId, stackName,
                existingPhysicalId, existingAttributes, event -> {});
    }

    public StackResource provision(String logicalId, String resourceType, JsonNode properties,
                                   CloudFormationTemplateEngine engine, String region, String accountId,
                                   String stackName, String existingPhysicalId,
                                   Map<String, String> existingAttributes, Consumer<StackEvent> progress) {
        StackResource resource = new StackResource();
        resource.setLogicalId(logicalId);
        resource.setResourceType(resourceType);
        resource.setPhysicalId(existingPhysicalId);
        resource.setAttributes(new HashMap<>(existingAttributes != null ? existingAttributes : Map.of()));

        try {
            CfnResourceProvisioner extracted = resourceRegistry.forType(resourceType).orElse(null);
            if (extracted != null) {
                extracted.provision(resource, properties,
                        new ProvisionContext(engine, region, accountId, stackName, existingPhysicalId, progress));
                resource.setStatus("CREATE_COMPLETE");
                return resource;
            }
            switch (resourceType) {
                default -> {
                    if (LEGACY_SWITCH_TYPES.contains(resourceType)) {
                        // A declared legacy type reaching the default arm means its case was removed
                        // without removing its LEGACY_SWITCH_TYPES entry (or without registering a
                        // provisioner). Stubbing it would report CREATE_COMPLETE with a fake ARN and
                        // hide the mistake, so fail instead.
                        throw new IllegalStateException("No switch arm for declared legacy type "
                                + resourceType + ", remove its LEGACY_SWITCH_TYPES entry when it "
                                + "moves to a per-service provisioner.");
                    } else if (!stubUnsupportedResourceTypesAllowed()) {
                        // Before the physical id below is assigned, so the Cloud Control path sees
                        // a resource with none and reports this message rather than a success. On
                        // the stack path the catch below turns it into CREATE_FAILED with the same
                        // sentence, which rolls the stack back.
                        throw new AwsException("ValidationError",
                                unsupportedResourceTypeMessage(resourceType), 400);
                    } else {
                        // Warn, not debug, and a status reason on the resource: the stub reports
                        // CREATE_COMPLETE while creating nothing, so without both the stack is
                        // indistinguishable from one where every resource was really provisioned.
                        // The reason reaches DescribeStackEvents through the event
                        // CloudFormationService already builds from it.
                        LOG.warnv("Stubbing unsupported resource type {0} ({1}): nothing is created "
                                        + "for it. Set floci.services.cloudformation."
                                        + "allow-stub-unsupported-resource-types=false to fail the "
                                        + "stack instead.",
                                resourceType, logicalId);
                        resource.setStatusReason(unsupportedResourceTypeMessage(resourceType)
                                + " It was stubbed and nothing was created for it.");
                        resource.setPhysicalId(logicalId + "-" + UUID.randomUUID().toString().substring(0, 8));
                        resource.getAttributes().put("Arn", "arn:aws:stub:::" + logicalId);
                    }
                }
            }
            resource.setStatus("CREATE_COMPLETE");
        } catch (Exception e) {
            LOG.warnv("Failed to provision {0} ({1}): {2}", resourceType, logicalId, e.getMessage());
            resource.setStatus("CREATE_FAILED");
            resource.setStatusReason(e.getMessage());
        }
        return resource;
    }

    /**
     * Whether a resource type with no provisioner may be stubbed. The provisioners hand-built in
     * unit tests carry no config; absent configuration means the documented default, which here is
     * the lenient behaviour, so the test reads {@code config == null ||}.
     */
    private boolean stubUnsupportedResourceTypesAllowed() {
        return config == null || config.services().cloudformation().allowStubUnsupportedResourceTypes();
    }

    /** The one sentence Floci says about a resource type it has no provisioner for. */
    static String unsupportedResourceTypeMessage(String resourceType) {
        return "Resource type " + resourceType + " is not supported by Floci.";
    }

    /**
     * Provision a single resource with no enclosing CloudFormation stack, the Cloud Control
     * {@code CreateResource} path. Cloud Control DesiredState carries resolved values (no
     * intrinsics), so a minimal template engine suffices. Reuses the same 114-type provisioning
     * that CloudFormation stacks use, so any type a stack can create, Cloud Control can too.
     */
    public StackResource provisionStandalone(String resourceType, JsonNode properties, String region, String accountId) {
        CloudFormationTemplateEngine engine = new CloudFormationTemplateEngine(
                accountId, region, "cloudcontrol", "cloudcontrol",
                Map.of(), new HashMap<>(), new HashMap<>(), Map.of(), Map.of(), objectMapper, name -> null,
                value -> dynamicReferences.resolveDynamicReferences(value, region, false));
        return provision("resource", resourceType, properties, engine, region, accountId, "cloudcontrol");
    }

    /** Delete a resource by type + physical id, the Cloud Control {@code DeleteResource} path. */
    public void deleteStandalone(String resourceType, String identifier, String region) {
        deleteStandalone(resourceType, identifier, region, Map.of());
    }

    /**
     * As above, with the attributes recorded when the resource was created. Custom resources, EKS
     * nodegroups and IAM inline policies cannot be deleted from type and physical id alone, so
     * without these their delete silently no-ops.
     */
    public void deleteStandalone(String resourceType, String identifier, String region,
                                 Map<String, String> attributes) {
        deleteStandalone(resourceType, identifier, region, "000000000000", attributes);
    }

    /** Account-aware standalone delete used by Cloud Control. */
    public void deleteStandalone(String resourceType, String identifier, String region,
                                 String accountId, Map<String, String> attributes) {
        StackResource resource = new StackResource();
        resource.setResourceType(resourceType);
        resource.setPhysicalId(identifier);
        resource.setAttributes(new HashMap<>(attributes == null ? Map.of() : attributes));
        delete(resource, region, accountId);
    }

    /**
     * Deletes a provisioned resource. Custom resources are re-invoked with {@code RequestType=Delete}
     * (using the ServiceToken + properties stashed at create time); everything else delegates to the
     * type-keyed {@link #delete(String, String, String)}.
     */
    public void delete(StackResource resource, String region) {
        delete(resource, region, "000000000000");
    }

    public void delete(StackResource resource, String region, String accountId) {
        String resourceType = resource.getResourceType();
        // Registry first. An extracted provisioner owns its type outright, and gets the whole
        // resource so an attribute-aware delete can read its create-time attributes. Consulting it
        // ahead of the branches below means an exact match always beats the Custom:: prefix branch
        // (which is how Custom::DynamoDBReplica moved to DynamoDbCfnProvisioner), and that migrating one of the
        // DELETE_NEEDS_STACK_RESOURCE types cannot silently keep using the stale branch here.
        CfnResourceProvisioner extractedForDelete = resourceRegistry.forType(resourceType).orElse(null);
        if (extractedForDelete != null) {
            extractedForDelete.delete(resource, region);
            return;
        }
        delete(resourceType, resource.getPhysicalId(), region);
    }

    /**
     * Deletes a single resource by type + physical id. Failures propagate to the caller
     * (CloudFormationService#deleteStackResources) so the stack transitions to DELETE_FAILED,
     * matching AWS, e.g. deleting a non-empty S3 bucket raises BucketNotEmpty and must not be
     * silently reported as a successful stack deletion. Resource types that AWS itself treats
     * leniently keep their dedicated handling: the {@code *Safe} helpers below swallow expected
     * conflicts, and KMS keys are intentionally left for scheduled deletion.
     */
    public void delete(String resourceType, String physicalId, String region) {
        CfnResourceProvisioner extracted = resourceRegistry.forType(resourceType).orElse(null);
        if (extracted != null) {
            extracted.delete(resourceType, physicalId, region);
            return;
        }
        switch (resourceType) {
            // Warn for the same reason the create path does: the delete reports success over a
            // type nothing here removes, and at debug that is invisible at the default log level.
            // The line names the physical id without claiming a resource survives it: this arm
            // takes both a type the create switch provisioned and one it only stubbed, and only
            // the first leaves something behind.
            default -> LOG.warnv("No delete implemented for resource type {0}: {1} is not removed "
                    + "here.", resourceType, physicalId);
        }
    }

    /**
     * One attempt at deleting what this update's replacement displaced, delegated to the
     * provisioner that owns the type. Step Functions was the last type answering here without an
     * extracted provisioner, so this is now pure delegation.
     */
    UpdateCleanupResult completeUpdate(StackResource resource) {
        return resourceRegistry.forType(resource.getResourceType())
                .map(owner -> owner.completeUpdate(resource))
                .filter(UpdateCleanupResult::applicable)
                .orElseGet(UpdateCleanupResult::notApplicable);
    }
    /**
     * The physical id this update displaced, announced as DELETE_IN_PROGRESS before the stack
     * update closes. A type whose {@code UpdateReplacePolicy} is {@code Retain} owes no cleanup.
     */
    String updateCleanupPhysicalId(StackResource resource) {
        if ("Retain".equals(resource.getUpdateReplacePolicy())) {
            return null;
        }
        return resourceRegistry.forType(resource.getResourceType())
                .map(owner -> owner.updateCleanupPhysicalId(resource))
                .orElse(null);
    }

    /** Only an opted-in provisioner may identify cleanup owed by an UPDATE_FAILED resource. */
    boolean hasPendingRollbackCleanup(StackResource resource) {
        return resourceRegistry.forType(resource.getResourceType())
                .map(owner -> owner.hasPendingRollbackCleanup(resource))
                .orElse(false);
    }

    /** Only an opted-in provisioner may keep a failed update attempt in place of the previous resource. */
    boolean retainsFailedUpdateState(StackResource resource) {
        return resourceRegistry.forType(resource.getResourceType())
                .map(owner -> owner.retainsFailedUpdateState(resource))
                .orElse(false);
    }

    /**
     * Whether this update replaced the resource's physical entity, so the stack has cleanup
     * pending.
     */
    boolean hasReplacementUpdate(StackResource resource) {
        return resourceRegistry.forType(resource.getResourceType())
                .map(owner -> owner.hasReplacementUpdate(resource))
                .orElse(false);
    }
    /** Drops the cleanup bookkeeping this update left on the resource. */
    void clearUpdate(StackResource resource) {
        resourceRegistry.forType(resource.getResourceType())
                .ifPresent(owner -> owner.clearUpdate(resource));
    }

    /**
     * Puts the physical entity back to its pre-update configuration when a later resource fails
     * the stack update, delegated to the provisioner that owns the type.
     */
    boolean rollbackUpdate(StackResource resource) {
        return rollbackUpdate(resource, event -> {});
    }

    boolean rollbackUpdate(StackResource resource, Consumer<StackEvent> progress) {
        return resourceRegistry.forType(resource.getResourceType())
                .map(owner -> owner.rollbackUpdate(resource, progress))
                .orElse(false);
    }

    /**
     * Carries ownership discovered by a failed update onto the last known-good resource metadata
     * that CloudFormation restores. Only additive cleanup tracking belongs here; normal attempted
     * attributes must not overwrite the committed resource state.
     */
    void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        // Any provisioner using ReplacementCleanup: an entity the failed attempt created and could
        // not remove is owed to the next cleanup, which runs on the restored resource.
        ReplacementCleanup.mergeDisplaced(previous, attempted);
        if (!Objects.equals(previous.getResourceType(), attempted.getResourceType())) {
            return;
        }
        // Extracted provisioners that track their own generated sub-resources (ApiGatewayV2's Api
        // body routes/integrations/authorizers) carry that tracking forward themselves.
        resourceRegistry.forType(previous.getResourceType())
                .ifPresent(owner -> owner.mergeFailedUpdateResourceTracking(previous, attempted));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String textOrNull(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private String resolveOptional(JsonNode props, String name, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return engine.resolve(props.get(name));
    }

    /**
     * Resolves CloudFormation dynamic references in a provisioned property value. Delegates to
     * {@link CfnDynamicReferences}. {@code allowSsmSecure} is {@code true} only for the RDS
     * master-credential properties resolved here directly; every other property value reaches
     * {@link CfnDynamicReferences} through {@link CloudFormationTemplateEngine#resolveNode}, which
     * disallows {@code ssm-secure} the same way the general path does.
     */
    private String resolveDynamicReferences(String value, String region, boolean allowSsmSecure) {
        return dynamicReferences.resolveDynamicReferences(value, region, allowSsmSecure);
    }

    private String resolveOrDefault(JsonNode props, String name,
                                    CloudFormationTemplateEngine engine, String defaultValue) {
        String value = resolveOptional(props, name, engine);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /**
     * Generate an AWS-like physical name: {stackName}-{logicalId}-{randomSuffix}.
     * Mirrors the naming pattern AWS CloudFormation uses when no explicit name is provided.
     */
    private String generatePhysicalName(String stackName, String logicalId, int maxLength, boolean lowercase) {
        String suffix = UUID.randomUUID().toString().replace("-", "")
                .substring(0, GENERATED_NAME_SUFFIX_LENGTH);
        String base = stackName + "-" + logicalId;
        if (lowercase) {
            base = base.toLowerCase();
        }
        String name = base + "-" + suffix;
        if (maxLength > 0 && name.length() > maxLength) {
            // Truncate the descriptive prefix but always keep the trailing uniqueness token. When a
            // stack's name approaches the length limit, distinct logical resources still get distinct
            // physical names, CloudFormation preserves the random suffix when it shortens a generated
            // name. Truncating the whole string (suffix included) would collapse every such resource
            // onto one name and break Ref/GetAtt-based lookup (e.g. a custom resource's ServiceToken
            // resolving to the wrong Lambda).
            int keep = Math.max(0, maxLength - suffix.length() - 1);
            String prefix = base.length() > keep ? base.substring(0, keep) : base;
            while (prefix.endsWith("-")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            name = prefix.isEmpty() ? suffix : prefix + "-" + suffix;
        }
        return name;
    }
}
