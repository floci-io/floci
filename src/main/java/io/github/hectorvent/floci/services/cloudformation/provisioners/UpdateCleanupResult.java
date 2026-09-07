package io.github.hectorvent.floci.services.cloudformation.provisioners;

/**
 * What one attempt at deleting the physical entity a replacement displaced reported back, read by
 * {@code CloudFormationService} while it finishes a committed stack update.
 *
 * <p>{@code applicable} false means this resource has no replacement cleanup owed at all, and the
 * remaining fields carry nothing. Otherwise {@code complete} says whether the displaced entity is
 * gone; on false the caller retries until {@code attempts} reaches 3 and then reports
 * {@code failureReason} against {@code previousPhysicalId}.
 *
 * <p>It lives in this package rather than inside {@code CloudFormationResourceProvisioner} so an
 * extracted {@link CfnResourceProvisioner} can return one without depending on the monolith it was
 * extracted from.
 */
public record UpdateCleanupResult(
        boolean applicable,
        boolean complete,
        String previousPhysicalId,
        int attempts,
        String failureReason) {

    /** The answer of a resource type that owes no replacement cleanup. */
    public static UpdateCleanupResult notApplicable() {
        return new UpdateCleanupResult(false, true, null, 0, null);
    }
}
