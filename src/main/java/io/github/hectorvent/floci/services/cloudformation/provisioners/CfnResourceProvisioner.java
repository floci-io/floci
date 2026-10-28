package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Provisions and deletes the CloudFormation resource types for a single service. Implementations
 * inject only
 * the service they wrap and are discovered via CDI by {@link CloudFormationResourceRegistry}.
 *
 * <p>{@code provision} mutates the passed {@link StackResource} in place — setting its physical
 * id and populating its attributes for {@code Fn::GetAtt} — exactly as the original per-type
 * methods did. {@code resource.getResourceType()} disambiguates when a provisioner serves more
 * than one type.
 */
public interface CfnResourceProvisioner {

    Set<String> resourceTypes();

    /** GetAtt attributes the registry schema types as integers, by resource type. */
    default Map<String, Set<String>> integerAttributes() {
        return Map.of();
    }

    /** The JSON type of a stored GetAtt value; most resource attributes are strings. */
    default JsonNode attributeValue(String resourceType, String attributeName, String value) {
        if (integerAttributes().getOrDefault(resourceType, Set.of()).contains(attributeName)) {
            return LongNode.valueOf(Long.parseLong(value));
        }
        return TextNode.valueOf(value);
    }

    void provision(StackResource resource, JsonNode properties, ProvisionContext ctx);

    /**
     * True when the service this provisioner wraps is turned off. {@link CloudFormationResourceRegistry}
     * then serves none of its types, so the dispatcher stubs them instead of provisioning them.
     */
    default boolean serviceDisabled() {
        return false;
    }

    /** The config property that turns this provisioner's service on, named in messages about it being off. */
    default String serviceEnabledFlag() {
        return null;
    }

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

    default boolean rollbackUpdate(StackResource resource, Consumer<StackEvent> progress) {
        return rollbackUpdate(resource);
    }

    /**
     * True when this provisioner keeps the failed update attempt's own identity and tracking for
     * its {@code rollbackUpdate}, so the engine must not restore the previous resource over it.
     */
    default boolean retainsFailedUpdateState(StackResource resource) {
        return false;
    }

    /** Whether a prior failed rollback must be retried before even an outputs-only update commits. */
    default boolean requiresUpdateRecovery(StackResource resource) {
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
     * Deletes entities displaced by an unfinished update before the stack itself is deleted.
     * The default uses normal replacement cleanup. A provisioner may distinguish this operation
     * from committing an update when it still carries configuration needed for rollback.
     */
    default UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        return completeUpdate(resource);
    }

    /**
     * Whether this update replaced the physical entity, so the stack has cleanup pending and enters
     * UPDATE_COMPLETE_CLEANUP_IN_PROGRESS. The default reports no replacement.
     */
    default boolean hasReplacementUpdate(StackResource resource) {
        return false;
    }

    /**
     * Whether a resource of {@code resourceType} takes the physical id of the resource it depends
     * on as its own, as a bucket policy takes its bucket's name. The stack then deletes one left
     * behind before whatever else holds that id. The default is no.
     */
    default boolean takesParentPhysicalId(String resourceType) {
        return false;
    }

    /**
     * Whether a prefix of a {@code resourceType} physical id, up to a {@code /}, {@code :} or
     * {@code |}, can be the physical id of the resource it belongs to, as {@code function|sid} is
     * a Lambda permission's. The stack then deletes one left behind before that resource. The
     * default is yes; a type whose id only joins parts of its own identity says no.
     */
    default boolean idPrefixCanNameParent(String resourceType) {
        return true;
    }

    /**
     * Drops the bookkeeping the replacement cleanup kept on the resource, once the displaced entity
     * is gone or its deletion is given up on. The default has none to drop.
     */
    default void clearUpdate(StackResource resource) {
        // no-op by default: a type with no replacement cleanup records nothing to clear
    }

    /**
     * Drops completed replacement cleanup during stack deletion. The default preserves the normal
     * cleanup behavior; an override may keep rollback configuration until the managed entity is
     * successfully deleted.
     */
    default void clearDeleteCleanup(StackResource resource) {
        clearUpdate(resource);
    }

    /**
     * Carries additive tracking a failed update discovered onto the last known-good resource
     * metadata CloudFormation restores: entities the failed attempt created but could not remove,
     * so they are not orphaned, and the snapshot of an undo the attempt could not complete, so a
     * ContinueUpdateRollback can try it again, or report again that it cannot. Only additive
     * tracking belongs here; committed resource state must not be overwritten. The default has
     * none to carry.
     */
    default void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        // no-op by default: only a provisioner with tracking of its own to carry (generated
        // sub-resources, cleanup entries, an undo snapshot) needs this
    }
}
