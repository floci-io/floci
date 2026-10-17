package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.jboss.logging.Logger;

/**
 * Rollback bookkeeping shared by every {@link CfnResourceProvisioner} and read by
 * {@code CloudFormationService}. It lives here so the ownership marker and the cleanup logging
 * stay single-sourced across the per-service provisioners.
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
     * owes. Lives here so every provisioner in this package can set it.
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
     * Holds the subnets a kept ELBv2 load balancer had before the update in flight moved it,
     * comma-joined, so a failed stack update can move it back. Written by
     * {@code ElbV2CfnProvisioner} after its SetSubnets and spent by its {@code rollbackUpdate},
     * retained across a failed move back.
     */
    public static final String LOAD_BALANCER_SUBNETS_UPDATE_SNAPSHOT_ATTR = "__FlociLoadBalancerSubnetsUpdateSnapshot";

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
     * Holds what a custom resource had before an update sent its handler an {@code Update}: the
     * physical id, the old and the attempted properties, the region and the {@code Data} attributes,
     * so a failed stack update can send the handler the old properties back, whether the handler
     * applied the update or failed it. Written by {@code CustomResourceCfnProvisioner} before the
     * invoke and spent by its {@code rollbackUpdate}.
     */
    public static final String CUSTOM_RESOURCE_UPDATE_SNAPSHOT_ATTR = "__FlociCustomResourceUpdateSnapshot";

    /**
     * Holds the body and tags a dashboard carried before an in-place update changed them, or the
     * fact that it did not exist, so a failed stack update can put it back. Written by
     * {@code CloudWatchDashboardCfnProvisioner} before its first mutating call and spent by its
     * {@code rollbackUpdate}.
     */
    public static final String DASHBOARD_UPDATE_SNAPSHOT_ATTR = "__FlociDashboardUpdateSnapshot";
    public static final String S3_BUCKET_UPDATE_SNAPSHOT_ATTR = "__FlociS3BucketUpdateSnapshot";

    /**
     * Holds the description, event pattern and retention an EventBridge archive carried before an
     * in-place update changed them, so a failed stack update can put them back. Written by
     * {@code EventsArchiveCfnProvisioner} before its update call and spent by its
     * {@code rollbackUpdate}.
     */
    public static final String ARCHIVE_UPDATE_SNAPSHOT_ATTR = "__FlociArchiveUpdateSnapshot";

    /**
     * Holds the connection, endpoint, method, description and rate limit an EventBridge API
     * destination carried before an in-place update changed them, so a failed stack update can put
     * them back. Written by {@code EventsCfnProvisioner} before its update call and spent by its
     * {@code rollbackUpdate}.
     */
    public static final String API_DESTINATION_UPDATE_SNAPSHOT_ATTR = "__FlociApiDestinationUpdateSnapshot";

    /**
     * Holds the name, description and endpoint configuration a REST API had before an in-place
     * update patched them, and whether the update also re-applied an OpenAPI document, so a failed
     * stack update can put them back. Written by {@code ApiGatewayRestApiCfnProvisioner} before its
     * update call and spent by its {@code rollbackUpdate}.
     */
    public static final String REST_API_UPDATE_SNAPSHOT_ATTR = "__FlociRestApiUpdateSnapshot";

    /**
     * Holds the tags a web function or the mutable configuration a web function endpoint carried
     * before the in-place update in flight changed it, so a failed stack update can put it back.
     * Written by {@code LambdaWebCfnProvisioner} before its update call and spent by its
     * {@code rollbackUpdate}.
     */
    public static final String LAMBDA_WEB_UPDATE_SNAPSHOT_ATTR = "__FlociLambdaWebUpdateSnapshot";

    /**
     * Holds the function ARN, status, caching flag, token settings and tags an IoT authorizer
     * carried before an in-place update changed them, so a failed stack update can put them back.
     * Written by {@code IotAuthorizerCfnProvisioner} before its update call and spent by its
     * {@code rollbackUpdate}.
     */
    public static final String AUTHORIZER_UPDATE_SNAPSHOT_ATTR = "__FlociAuthorizerUpdateSnapshot";

    /**
     * Holds the customer id, description, enabled flag and tags an API key carried before an
     * in-place update changed them, or the key an update created because the one the stack held
     * was gone, so a failed stack update can put the key back or delete the created one. Written
     * by {@code ApiGatewayApiKeyCfnProvisioner} before its first in-place mutating call or right
     * after that creation, and spent by its {@code rollbackUpdate}.
     */
    public static final String API_KEY_UPDATE_SNAPSHOT_ATTR = "__FlociApiKeyUpdateSnapshot";

    /**
     * Holds the role, object prefixes, tagging action and tags a GuardDuty Malware Protection plan
     * carried before an in-place update changed them, or the plan an update created because the one
     * the stack held was gone, so a failed stack update can put the plan back or delete the created
     * one. Written by {@code GuardDutyMalwareProtectionPlanCfnProvisioner} before its first mutating
     * call or right after that creation, and spent by its {@code rollbackUpdate}.
     */
    public static final String GUARDDUTY_PLAN_UPDATE_SNAPSHOT_ATTR = "__FlociGuardDutyPlanUpdateSnapshot";

    /**
     * Holds the name and tags an API Gateway V2 VPC link carried before an in-place update changed
     * them, so a failed stack update can put them back. Written by {@code ApiGatewayV2CfnProvisioner}
     * before its update call and spent by its {@code rollbackUpdate}.
     */
    public static final String VPC_LINK_UPDATE_SNAPSHOT_ATTR = "__FlociVpcLinkUpdateSnapshot";

    /**
     * Holds the connection type and id an API Gateway V2 integration carried before an update, and
     * whether that update also changed another field, so a failed stack update can put the
     * connection back. Written by {@code ApiGatewayV2CfnProvisioner} before its update call and
     * spent by its {@code rollbackUpdate}.
     */
    public static final String INTEGRATION_CONNECTION_SNAPSHOT_ATTR = "__FlociIntegrationConnectionSnapshot";

    /**
     * Holds the certificate, security policy, endpoint type and tags an API Gateway v2 custom domain
     * carried before an in-place update changed them, so a failed stack update can put them back.
     * Written by {@code ApiGatewayV2DomainCfnProvisioner} before its first mutating call and spent
     * by its {@code rollbackUpdate}.
     */
    public static final String API_GATEWAY_V2_DOMAIN_UPDATE_SNAPSHOT_ATTR = "__FlociApiGatewayV2DomainUpdateSnapshot";

    /**
     * Holds the key, API and stage an API Gateway v2 API mapping carried before an in-place update
     * changed them, with the domain that addresses it, so a failed stack update can put them back.
     * Written by {@code ApiGatewayV2DomainCfnProvisioner} before its update call and spent by its
     * {@code rollbackUpdate}.
     */
    public static final String API_MAPPING_UPDATE_SNAPSHOT_ATTR = "__FlociApiMappingUpdateSnapshot";

    /**
     * Holds the complete prior metric filter, identity, name mode and per-address mutation outcomes
     * and ownership states.
     * Written before either an in-place put or a delete-then-create replacement; retained across
     * failed restoration attempts and spent only after rollback or commit succeeds.
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

    /**
     * Holds the tags, stream setting and time-to-live setting a DynamoDB table carried before an
     * in-place update changed them, with the account and region that address it, so a failed
     * stack update can put all three back. Written by {@code DynamoDbCfnProvisioner} before its
     * first mutating call and spent by its {@code rollbackUpdate}.
     */
    public static final String DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR = "__FlociDynamoDbTableUpdateSnapshot";

    /**
     * Holds the attributes and tags a queue carried before an in-place update changed them,
     * so a failed stack update can restore them.
     */
    public static final String SQS_UPDATE_SNAPSHOT_ATTR = "__FlociSqsUpdateSnapshot";

    /**
     * Holds the value, settings and managed tags an SSM parameter carried before an in-place
     * update changed them, with the resource's attributes from before, so a failed stack update
     * can put them back. Written by {@code SsmCfnProvisioner} before its put and spent by its
     * {@code rollbackUpdate}; a rename leaves its rollback to {@link #REPLACEMENT_CLEANUP_ATTR}.
     */
    public static final String SSM_PARAMETER_UPDATE_SNAPSHOT_ATTR = "__FlociSsmParameterUpdateSnapshot";

    /**
     * Holds the settings a Cognito user pool or user pool client had before an in-place update
     * changed them, so a failed stack update can put them back. Written by
     * {@code CognitoCfnProvisioner} before its update call and spent by its {@code rollbackUpdate}.
     */
    public static final String COGNITO_UPDATE_SNAPSHOT_ATTR = "__FlociCognitoUpdateSnapshot";

    /**
     * Holds the settings and tags a serverless cache had before an in-place update changed them,
     * so a failed stack update can put them back. Written by {@code ElastiCacheServerlessCfnProvisioner}
     * before its modify call and spent by its {@code rollbackUpdate}.
     */
    public static final String SERVERLESS_CACHE_UPDATE_SNAPSHOT_ATTR = "__FlociServerlessCacheUpdateSnapshot";

    /**
     * Holds the configuration an Auto Scaling group carried before an in-place update changed it,
     * so a failed stack update can restore it.
     */
    public static final String ASG_UPDATE_SNAPSHOT_ATTR = "__FlociAsgUpdateSnapshot";

    /**
     * Holds the version a launch template created during an in-place update and the prior version,
     * so a failed stack update can roll it back.
     */
    public static final String LAUNCH_TEMPLATE_UPDATE_SNAPSHOT_ATTR = "__FlociLaunchTemplateUpdateSnapshot";

    /**
     * Holds the settings a Lambda MicroVMs network connector had before an in-place update changed
     * them, so a failed stack update can put them back. Written by {@code LambdaMicrovmsCfnProvisioner}
     * before its update call and spent by its {@code rollbackUpdate}.
     */
    public static final String NETWORK_CONNECTOR_UPDATE_SNAPSHOT_ATTR = "__FlociNetworkConnectorUpdateSnapshot";

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

    /**
     * Runs the steps that follow a create or change a reused entity in place. If one of them
     * throws, what this provision did is undone before the failure propagates: a created entity is
     * deleted again by {@code undoCreate}, a reused one is put back by {@code undoInPlace}, which
     * restores the provisioner's snapshot and is a no-op when there is none. The engine only removes
     * a failed create it can see, and on a failed update it restores the previous resource metadata
     * and discards the attempted one, snapshot included, so the undo has to happen here.
     *
     * <p>A compensation that fails is reported, never hidden. A created entity carries
     * {@link #ROLLBACK_OWNED_ATTR} while the steps run, so the engine's create rollback deletes it
     * (and reports that failing) if the undo here could not, and during an update the entity is
     * also listed in the cleanup record, which the engine carries onto the restored resource and
     * runs before the next committed update or the stack delete; a reused entity keeps its snapshot
     * and the reason lands in {@link #UPDATE_ROLLBACK_FAILURE_ATTR}, which the engine copies onto
     * the committed resource so the rollback walker reports UPDATE_ROLLBACK_FAILED instead of
     * claiming the prior state is live.
     */
    public static void guarded(StackResource r, ProvisionContext ctx, boolean created, Runnable undoCreate,
                               Runnable undoInPlace, Runnable steps) {
        if (created) {
            r.getAttributes().put(ROLLBACK_OWNED_ATTR, "true");
        }
        try {
            steps.run();
            r.getAttributes().remove(ROLLBACK_OWNED_ATTR);
        } catch (RuntimeException e) {
            if (created) {
                try {
                    undoCreate.run();
                    r.getAttributes().remove(ROLLBACK_OWNED_ATTR);
                } catch (RuntimeException undo) {
                    e.addSuppressed(undo);
                    String reason = "Could not remove " + r.getPhysicalId() + ", created for " + r.getLogicalId()
                            + " by a failed update: " + undo.getMessage();
                    LOG.warn(reason);
                    r.getAttributes().put(UPDATE_ROLLBACK_FAILURE_ATTR, reason);
                    if (ctx.isUpdate()) {
                        // The engine restores the previous resource and keeps only what the cleanup
                        // record carries, so the replacement is listed there for the next cleanup.
                        ReplacementCleanup.recordOrphan(r, r.getPhysicalId(), r.getResourceType(), ctx.region());
                    }
                }
            } else {
                try {
                    undoInPlace.run();
                } catch (RuntimeException undo) {
                    e.addSuppressed(undo);
                    String reason = "Could not restore " + r.getPhysicalId() + " for " + r.getLogicalId()
                            + " after a failed update: " + undo.getMessage();
                    LOG.warn(reason);
                    r.getAttributes().put(UPDATE_ROLLBACK_FAILURE_ATTR, reason);
                }
            }
            throw e;
        }
    }
}
