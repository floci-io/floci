package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;

import java.util.Set;

/**
 * Provisions and deletes the CloudFormation resource types for a single service, replacing one
 * arm of the switch in {@code CloudFormationResourceProvisioner}. Implementations inject only
 * the service they wrap and are discovered via CDI by {@link CloudFormationResourceRegistry}.
 *
 * <p>{@code provision} mutates the passed {@link StackResource} in place — setting its physical
 * id and populating its attributes for {@code Fn::GetAtt} — exactly as the original per-type
 * methods did. {@code resource.getResourceType()} disambiguates when a provisioner serves more
 * than one type.
 */
public interface CfnResourceProvisioner {

    Set<String> resourceTypes();

    void provision(StackResource resource, JsonNode properties, ProvisionContext ctx);

    /** Most implementations delegate to {@code service.deleteX(physicalId, region)}. */
    default void delete(String resourceType, String physicalId, String region) {
        // no-op by default: some resource types have no backing delete
    }

    /**
     * Delete with the resource's create-time attributes in hand. Override this when the physical id
     * alone cannot identify what to delete — a lifecycle hook name is unique only within its Auto
     * Scaling group, for instance. The default drops to the id-only form above.
     */
    default void delete(StackResource resource, String region) {
        delete(resource.getResourceType(), resource.getPhysicalId(), region);
    }

    /**
     * Puts the physical entity back to the configuration it had before the failed stack update
     * that is now rolling back, and returns whether it did. Only a provisioner that snapshots
     * that configuration before it mutates can answer true; {@code CloudFormationService} reports
     * the resource as UPDATE_FAILED with "Rollback is not implemented" on false.
     */
    default boolean rollbackUpdate(StackResource resource) {
        return false;
    }

    /**
     * The physical id of the entity this update's replacement displaced, or null when this type
     * owes no replacement cleanup. {@code CloudFormationService} announces it as DELETE_IN_PROGRESS
     * before the stack update is closed, and names it in the status reason when the delete fails.
     * A type whose {@code UpdateReplacePolicy} is {@code Retain} answers null: nothing is deleted.
     */
    default String updateCleanupPhysicalId(StackResource resource) {
        return null;
    }

    /**
     * Runs one attempt at deleting the displaced entity, after the stack update has committed.
     * Called again for each retry, so the attempt count and the last failure live on the resource
     * rather than in the caller. The default reports no cleanup owed.
     */
    default UpdateCleanupResult completeUpdate(StackResource resource) {
        return UpdateCleanupResult.notApplicable();
    }

    /**
     * Whether this update replaced the physical entity, so the stack has cleanup pending and enters
     * UPDATE_COMPLETE_CLEANUP_IN_PROGRESS. The default reports no replacement.
     */
    default boolean hasReplacementUpdate(StackResource resource) {
        return false;
    }

    /**
     * Drops the bookkeeping the replacement cleanup kept on the resource, once the displaced entity
     * is gone or its deletion is given up on. The default has none to drop.
     */
    default void clearUpdate(StackResource resource) {
        // no-op by default: a type with no replacement cleanup records nothing to clear
    }
}
