package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Routes each CloudFormation resource to the per-service provisioner that owns its type, through
 * {@link CloudFormationResourceRegistry}, and holds the two behaviours no provisioner owns: a type
 * nothing serves is stubbed (or refused, in strict mode), and a delete nothing serves is reported.
 * It also answers the engine's update-lifecycle questions (cleanup, rollback, replacement tracking)
 * by delegating to the owner, and offers Cloud Control its stack-less entry points.
 *
 * <p>No type-specific code belongs here. A new type gets a per-service provisioner; this class
 * only dispatches.
 */
@ApplicationScoped
public class CfnResourceDispatcher {

    private static final Logger LOG = Logger.getLogger(CfnResourceDispatcher.class);

    private final ObjectMapper objectMapper;
    private final CloudFormationResourceRegistry registry;
    private final CfnDynamicReferences dynamicReferences;
    private final EmulatorConfig config;

    @Inject
    public CfnResourceDispatcher(ObjectMapper objectMapper, CloudFormationResourceRegistry registry,
                                 CfnDynamicReferences dynamicReferences, EmulatorConfig config) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.dynamicReferences = dynamicReferences;
        this.config = config;
    }

    /**
     * Provisions a single resource and returns the populated StackResource (physical id and
     * attributes set).
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
        return provision(logicalId, resourceType, properties, engine, region, accountId, stackName,
                null, Map.of());
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
            CfnResourceProvisioner owner = registry.forType(resourceType).orElse(null);
            if (owner != null) {
                owner.provision(resource, properties,
                        new ProvisionContext(engine, region, accountId, stackName, existingPhysicalId, progress));
            } else if (!stubUnsupportedResourceTypesAllowed()) {
                // Before the physical id below is assigned, so the Cloud Control path sees a
                // resource with none and reports this message rather than a success. On the stack
                // path the catch below turns it into CREATE_FAILED with the same sentence, which
                // rolls the stack back.
                throw new AwsException("ValidationError", unsupportedResourceTypeMessage(resourceType), 400);
            } else {
                // Warn, not debug, and a status reason on the resource: the stub reports
                // CREATE_COMPLETE while creating nothing, so without both the stack is
                // indistinguishable from one where every resource was really provisioned. The
                // reason reaches DescribeStackEvents through the event CloudFormationService
                // already builds from it.
                LOG.warnv("Stubbing unsupported resource type {0} ({1}): nothing is created "
                                + "for it. Set floci.services.cloudformation."
                                + "allow-stub-unsupported-resource-types=false to fail the "
                                + "stack instead.",
                        resourceType, logicalId);
                resource.setStatusReason(unsupportedResourceTypeMessage(resourceType)
                        + " It was stubbed and nothing was created for it.");
                resource.setPhysicalId(logicalId + "-" + UUID.randomUUID().toString().substring(0, 8));
                resource.getAttributes().put("Arn", "arn:aws:stub:::" + logicalId); // partition-literal: stub marker for an unowned type, asserted by tests
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
     * Whether a resource type with no provisioner may be stubbed. The dispatchers hand-built in
     * unit tests carry no config; absent configuration means the documented default, which here is
     * the lenient behaviour, so the test reads {@code config == null ||}.
     */
    private boolean stubUnsupportedResourceTypesAllowed() {
        return config == null || config.services().cloudformation().allowStubUnsupportedResourceTypes();
    }

    /** The one sentence Floci says about a resource type it has no provisioner for. */
    private static String unsupportedResourceTypeMessage(String resourceType) {
        return "Resource type " + resourceType + " is not supported by Floci.";
    }

    /**
     * Provisions a single resource with no enclosing CloudFormation stack, the Cloud Control
     * {@code CreateResource} path. Cloud Control DesiredState carries resolved values (no
     * intrinsics), so a minimal template engine suffices; any type a stack can create, Cloud
     * Control can too.
     */
    public StackResource provisionStandalone(String resourceType, JsonNode properties, String region,
                                             String accountId) {
        CloudFormationTemplateEngine engine = CloudFormationTemplateEngine.standalone(accountId, region,
                "cloudcontrol", objectMapper,
                value -> dynamicReferences.resolveDynamicReferences(value, region, false));
        return provision("resource", resourceType, properties, engine, region, accountId, "cloudcontrol");
    }

    /**
     * Deletes a resource by type and physical id with no enclosing stack, the Cloud Control
     * {@code DeleteResource} path, with the attributes recorded when the resource was created:
     * custom resources, EKS nodegroups and IAM inline policies cannot be deleted from type and
     * physical id alone, so without these their delete silently no-ops.
     */
    public void deleteStandalone(String resourceType, String identifier, String region,
                                 Map<String, String> attributes) {
        StackResource resource = new StackResource();
        resource.setResourceType(resourceType);
        resource.setPhysicalId(identifier);
        resource.setAttributes(new HashMap<>(attributes == null ? Map.of() : attributes));
        delete(resource, region);
    }

    /**
     * Deletes a provisioned resource. The owning provisioner gets the whole resource, so an
     * attribute-aware delete can read its create-time attributes, and an exact type match always
     * beats the {@code Custom::} fallback the registry applies.
     */
    public void delete(StackResource resource, String region) {
        String resourceType = resource.getResourceType();
        CfnResourceProvisioner owner = registry.forType(resourceType).orElse(null);
        if (owner != null) {
            owner.delete(resource, region);
            return;
        }
        delete(resourceType, resource.getPhysicalId(), region);
    }

    /**
     * Deletes a single resource by type and physical id. Failures propagate to the caller so the
     * stack transitions to DELETE_FAILED, matching AWS: deleting a non-empty S3 bucket raises
     * BucketNotEmpty and must not be reported as a successful stack deletion.
     */
    public void delete(String resourceType, String physicalId, String region) {
        CfnResourceProvisioner owner = registry.forType(resourceType).orElse(null);
        if (owner != null) {
            owner.delete(resourceType, physicalId, region);
            return;
        }
        // Warn for the same reason the create path does: the delete reports success over a type
        // nothing here removes, and at debug that is invisible at the default log level. The line
        // names the physical id without claiming a resource survives it: a stubbed type left
        // nothing behind.
        LOG.warnv("No delete implemented for resource type {0}: {1} is not removed here.",
                resourceType, physicalId);
    }

    /**
     * One attempt at deleting what this update's replacement displaced, delegated to the
     * provisioner that owns the type.
     */
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return registry.forType(resource.getResourceType())
                .map(owner -> owner.completeUpdate(resource))
                .filter(UpdateCleanupResult::applicable)
                .orElseGet(UpdateCleanupResult::notApplicable);
    }

    /**
     * The physical id this update displaced, announced as DELETE_IN_PROGRESS before the stack
     * update closes. A type whose {@code UpdateReplacePolicy} is {@code Retain} owes no cleanup.
     */
    public String updateCleanupPhysicalId(StackResource resource) {
        if ("Retain".equals(resource.getUpdateReplacePolicy())) {
            return null;
        }
        return registry.forType(resource.getResourceType())
                .map(owner -> owner.updateCleanupPhysicalId(resource))
                .orElse(null);
    }

    /** Only an opted-in provisioner may identify cleanup owed by an UPDATE_FAILED resource. */
    public boolean hasPendingRollbackCleanup(StackResource resource) {
        return registry.forType(resource.getResourceType())
                .map(owner -> owner.hasPendingRollbackCleanup(resource))
                .orElse(false);
    }

    /** Only an opted-in provisioner may keep a failed update attempt in place of the previous resource. */
    public boolean retainsFailedUpdateState(StackResource resource) {
        return registry.forType(resource.getResourceType())
                .map(owner -> owner.retainsFailedUpdateState(resource))
                .orElse(false);
    }

    /** Whether this update replaced the resource's physical entity, so the stack has cleanup pending. */
    public boolean hasReplacementUpdate(StackResource resource) {
        return registry.forType(resource.getResourceType())
                .map(owner -> owner.hasReplacementUpdate(resource))
                .orElse(false);
    }

    /** Drops the cleanup bookkeeping this update left on the resource. */
    public void clearUpdate(StackResource resource) {
        registry.forType(resource.getResourceType())
                .ifPresent(owner -> owner.clearUpdate(resource));
    }

    /**
     * Puts the physical entity back to its pre-update configuration when a later resource fails
     * the stack update, delegated to the provisioner that owns the type.
     */
    public boolean rollbackUpdate(StackResource resource) {
        return rollbackUpdate(resource, event -> {});
    }

    public boolean rollbackUpdate(StackResource resource, Consumer<StackEvent> progress) {
        return registry.forType(resource.getResourceType())
                .map(owner -> owner.rollbackUpdate(resource, progress))
                .orElse(false);
    }

    /**
     * Carries ownership discovered by a failed update onto the last known-good resource metadata
     * that CloudFormation restores. Only additive cleanup tracking belongs here; normal attempted
     * attributes must not overwrite the committed resource state.
     */
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        // Any provisioner using ReplacementCleanup: an entity the failed attempt created and could
        // not remove is owed to the next cleanup, which runs on the restored resource.
        ReplacementCleanup.mergeDisplaced(previous, attempted);
        if (!Objects.equals(previous.getResourceType(), attempted.getResourceType())) {
            return;
        }
        // Provisioners that track their own generated sub-resources (ApiGatewayV2's Api body
        // routes, integrations and authorizers) carry that tracking forward themselves.
        registry.forType(previous.getResourceType())
                .ifPresent(owner -> owner.mergeFailedUpdateResourceTracking(previous, attempted));
    }
}
