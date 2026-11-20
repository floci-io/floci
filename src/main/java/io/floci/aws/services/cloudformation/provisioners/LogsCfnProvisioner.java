package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudwatch.logs.CloudWatchLogsService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provisions {@code AWS::Logs::LogGroup}. */
@ApplicationScoped
public class LogsCfnProvisioner implements CfnResourceProvisioner {

    private static final int LOG_GROUP_NAME_MAX_LENGTH = 512;

    private final CloudWatchLogsService logsService;

    public LogsCfnProvisioner(CloudWatchLogsService logsService) {
        this.logsService = logsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Logs::LogGroup");
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (ctx.isUpdate()) {
            // Rolling this update back undoes it alone; only ContinueUpdateRollback retries the
            // delete the last rollback could not make.
            ReplacementCleanup.endRollbackRetry(r);
        }
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        String explicitName = ctx.resolveOptional(props, "LogGroupName");
        boolean hasExplicitName = explicitName != null && !explicitName.isBlank();
        String priorPhysicalId = ctx.priorPhysicalId();

        // LogGroupName is create-only, so the engine's change decides whether this update replaces
        // the group. A named group cannot be replaced under the same name.
        boolean replaces = ctx.isUpdate() && priorPhysicalId != null
                && ctx.changes("AWS::Logs::LogGroup", props).requiresReplacement();
        if (replaces && hasExplicitName && explicitName.equals(priorPhysicalId)) {
            throw CfnExceptions.customNamedReplacement(explicitName);
        }

        String name;
        if (hasExplicitName) {
            name = explicitName;
        } else if (priorPhysicalId != null && !replaces) {
            // No explicit name and no replacement: keep the prior name across updates instead of
            // generating a fresh random one each time, so the log group is reconciled in place.
            name = priorPhysicalId;
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), LOG_GROUP_NAME_MAX_LENGTH, false);
        }

        Integer retentionInDays = null;
        String retention = ctx.resolveOptional(props, "RetentionInDays");
        if (retention != null && !retention.isBlank()) {
            try {
                retentionInDays = Integer.valueOf(retention.trim());
            } catch (NumberFormatException ignored) {
                // leave unset
            }
        }
        Map<String, String> tags = new HashMap<>();
        JsonNode tagsNode = props != null ? ctx.engine().resolveNode(props.get("Tags")) : null;
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = ctx.engine().resolve(tag.path("Key"));
                if (!key.isEmpty()) {
                    tags.put(key, ctx.engine().resolve(tag.path("Value")));
                }
            }
        }

        // Reconcile in place when the name is unchanged and the group is still there; otherwise this is
        // either a first create or a replacement, both of which need a fresh createLogGroup call. A
        // replacement leaves the old group alone: ReplacementCleanup deletes it once the stack update
        // commits, or the new one when the update rolls back.
        if (priorPhysicalId != null && priorPhysicalId.equals(name)
                && logsService.logGroupExists(name, ctx.region())) {
            reconcileLogGroup(name, retentionInDays, tags, ctx.region());
        } else {
            logsService.createLogGroup(name, retentionInDays, tags, ctx.region());
        }

        // Ref returns the log group name; GetAtt Arn is arn:aws:logs:<region>:<account>:log-group:<name>:*
        r.setPhysicalId(name);
        // Written by earlier versions to tell a rename from a no-op; the engine decides that now.
        r.getAttributes().remove("FlociLogGroupNameMode");
        r.getAttributes().put("Arn", AwsArnUtils.Arn
                .of("logs", ctx.region(), ctx.accountId(), "log-group:" + name + ":*").toString());
        // createLogGroup fails on an existing name, so a replacing update always created its group.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private void reconcileLogGroup(String name, Integer retentionInDays, Map<String, String> tags,
                                   String region) {
        if (retentionInDays != null) {
            logsService.putRetentionPolicy(name, retentionInDays, region);
        } else {
            logsService.deleteRetentionPolicy(name, region);
        }
        Map<String, String> existingTags = logsService.listTagsLogGroup(name, region);
        List<String> tagsToRemove = existingTags.keySet().stream()
                .filter(key -> !tags.containsKey(key))
                .toList();
        if (!tagsToRemove.isEmpty()) {
            logsService.untagLogGroup(name, tagsToRemove, region);
        }
        if (!tags.isEmpty()) {
            logsService.tagLogGroup(name, tags, region);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("log group", physicalId,
                () -> logsService.deleteLogGroup(physicalId, region), "ResourceNotFoundException");
    }
}
