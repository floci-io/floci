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
