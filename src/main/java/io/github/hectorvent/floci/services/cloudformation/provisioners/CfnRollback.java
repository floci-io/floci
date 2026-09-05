package io.github.hectorvent.floci.services.cloudformation.provisioners;

import org.jboss.logging.Logger;

/**
 * Rollback bookkeeping shared by every resource handler, on both sides of the ongoing
 * decomposition: the remaining {@code CloudFormationResourceProvisioner} switch arms and the
 * extracted {@link CfnResourceProvisioner} implementations. It lives here instead of in either
 * half so the ownership marker and the cleanup logging stay single-sourced while types migrate
 * out one service at a time.
 */
public final class CfnRollback {

    private static final Logger LOG = Logger.getLogger(CfnRollback.class);

    /**
     * Marks a resource whose backing entity this stack created. {@code CloudFormationService} reads
     * it to decide whether a CREATE_FAILED resource still has to be deleted during stack rollback.
     */
    public static final String ROLLBACK_OWNED_ATTR = "__FlociRollbackOwned";

    /**
     * Marks a resource whose prior physical entity is still intact after a failed update, so the
     * rollback must not try to restore it. Set by a provisioner that creates the replacement before
     * deleting the original; read by {@code CloudFormationService} when deciding what a rollback
     * owes. Lives here rather than on {@code CloudFormationResourceProvisioner} so extracted
     * provisioners in this package can set it.
     */
    public static final String UPDATE_ROLLBACK_RESTORED_ATTR = "__FlociUpdateRollbackRestored";

    /**
     * Carries the reason a provisioner's own restoration attempt did not complete after a failed
     * update. {@code CloudFormationService} copies it onto the committed resource so the rollback
     * walker reports UPDATE_ROLLBACK_FAILED with that reason instead of claiming the prior entity
     * is live. Lives here for the same reason as the marker above.
     */
    public static final String UPDATE_ROLLBACK_FAILURE_ATTR = "__FlociUpdateRollbackFailure";

    /**
     * Holds the configuration a pipe carried before the update in flight mutated it, so a failed
     * stack update can put it back. Written by {@code PipesCfnProvisioner} before its first
     * mutating call and spent by its {@code rollbackUpdate}. Lives here beside the other rollback
     * markers rather than on the provisioner, so the marker names stay in one place.
     */
    public static final String PIPE_UPDATE_SNAPSHOT_ATTR = "__FlociPipeUpdateSnapshot";

    /**
     * Holds the pipe a rename displaced: the name it still lives under, the region that addresses
     * it, how many times deleting it has been attempted, and when the replacement was created.
     * Written by {@code PipesCfnProvisioner} when it creates the replacement, and spent by whichever
     * end the update reaches: {@code completeUpdate} deletes the displaced pipe once the update has
     * committed, and {@code rollbackUpdate} points the resource back at it and deletes the
     * replacement when the update fails instead.
     */
    public static final String PIPE_RENAME_CLEANUP_ATTR = "__FlociPipeRenameCleanup";

    private CfnRollback() {
    }

    /**
     * Runs one compensating IAM call while unwinding a failed provision. The stack must report the
     * primary failure, so a cleanup failure is attached to it as suppressed and logged rather than
     * thrown. Returns false when the cleanup itself failed, leaving the caller to keep the
     * resource marked as stack-owned.
     */
    public static boolean attemptIamCleanup(RuntimeException primaryFailure, String description, Runnable cleanup) {
        try {
            cleanup.run();
            return true;
        } catch (RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
            LOG.warnv("IAM rollback cleanup failed while attempting to {0}: {1}",
                    description, cleanupFailure.getMessage());
            return false;
        }
    }
}
