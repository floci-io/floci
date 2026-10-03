package io.github.hectorvent.floci.services.cloudformation.provisioners;

/**
 * What one attempt at deleting the physical entity a replacement displaced reported back, read by
 * {@code CloudFormationService} while it finishes a committed stack update.
 *
 * <p>{@code applicable} false means this resource has no replacement cleanup owed at all, and the
 * remaining fields carry nothing. Otherwise {@code complete} says whether cleanup is settled:
 * the entity is gone or {@code skippedReason} explains why deletion was abandoned. On false the
 * caller retries until {@code attempts} reaches 3 and then reports
 * {@code failureReason} against {@code previousPhysicalId}.
 *
 * <p>It lives in this package so a {@link CfnResourceProvisioner} can return one and
 * {@code CfnResourceDispatcher} can aggregate it.
 */
public record UpdateCleanupResult(
        boolean applicable,
        boolean complete,
        String previousPhysicalId,
        int attempts,
        String failureReason,
        String skippedReason) {

    public UpdateCleanupResult(boolean applicable, boolean complete, String previousPhysicalId,
                               int attempts, String failureReason) {
        this(applicable, complete, previousPhysicalId, attempts, failureReason, null);
    }

    public static UpdateCleanupResult skipped(String previousPhysicalId, String reason) {
        return new UpdateCleanupResult(true, true, previousPhysicalId, 0, null, reason);
    }

    /** The answer of a resource type that owes no replacement cleanup. */
    public static UpdateCleanupResult notApplicable() {
        return new UpdateCleanupResult(false, true, null, 0, null);
    }
}
