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
     * Holds the targets an EventBridge rule carried before the update in flight reconciled them,
     * with the rule name, bus and region needed to address them again, so a failed stack update can
     * put them back. Written by {@code EventsCfnProvisioner} before its first target call and spent
     * by its {@code rollbackUpdate}. The rule name alone does not address a target: a rule on a
     * custom bus is keyed by that bus, and the rollback hook is handed the stack resource alone.
     */
    public static final String RULE_TARGETS_SNAPSHOT_ATTR = "__FlociRuleTargetsSnapshot";

    /**
     * Holds the configuration a Batch entity carried before the in-place update in flight changed
     * it, in the request shape its update call takes, so a failed stack update can put it back.
     * Written by {@code BatchCfnProvisioner} before its update call and spent by its
     * {@code rollbackUpdate}. Carries the resource type because one provisioner serves three, and
     * the rollback hook is handed the stack resource alone: a compute environment is restored
     * through UpdateComputeEnvironment and a job queue through UpdateJobQueue, and a job
     * definition instead names the revision the failed update registered so it can be
     * deregistered.
     */
    public static final String BATCH_UPDATE_SNAPSHOT_ATTR = "__FlociBatchUpdateSnapshot";

    /**
     * Holds the settings an event invoke configuration carried before an in-place update changed
     * them, in the request shape a put takes, so a failed stack update can put them back. Written
     * by {@code LambdaEventInvokeConfigCfnProvisioner} before its update call and spent by its
     * {@code rollbackUpdate}.
     */
    public static final String EVENT_INVOKE_CONFIG_SNAPSHOT_ATTR = "__FlociEventInvokeConfigSnapshot";

    /**
     * Holds the body and tags a dashboard carried before an in-place update changed them, or the
     * fact that it did not exist, so a failed stack update can put it back. Written by
     * {@code CloudWatchDashboardCfnProvisioner} before its first mutating call and spent by its
     * {@code rollbackUpdate}.
     */
    public static final String DASHBOARD_UPDATE_SNAPSHOT_ATTR = "__FlociDashboardUpdateSnapshot";

    /**
     * Holds the definition a metric filter had before an in-place update replaced it, or the fact
     * that it did not exist, so a failed stack update can put it back. Written by
     * {@code LogsMetricFilterCfnProvisioner} before its put and spent by its {@code rollbackUpdate}.
     */
    public static final String METRIC_FILTER_UPDATE_SNAPSHOT_ATTR = "__FlociMetricFilterUpdateSnapshot";

    /**
     * Holds the pipe a rename displaced: the name it still lives under, the region that addresses
     * it, how many times deleting it has been attempted, and when the replacement was created.
     * Written by {@code PipesCfnProvisioner} when it creates the replacement, and spent by whichever
     * end the update reaches: {@code completeUpdate} deletes the displaced pipe once the update has
     * committed, and {@code rollbackUpdate} points the resource back at it and deletes the
     * replacement when the update fails instead.
     */
    public static final String PIPE_RENAME_CLEANUP_ATTR = "__FlociPipeRenameCleanup";

    /**
     * Holds the entity a replacing update displaced, for provisioners whose replacement needs no
     * rollback snapshot: the prior physical id, its resource type and region, and how many times
     * deleting it has been attempted. Written by {@link ReplacementCleanup#record} when a
     * provision leaves the resource with a new physical id, and spent by {@code completeUpdate}
     * once the update has committed.
     */
    public static final String REPLACEMENT_CLEANUP_ATTR = "__FlociReplacementCleanup";

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
