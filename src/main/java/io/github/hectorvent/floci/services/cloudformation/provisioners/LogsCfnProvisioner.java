package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provisions {@code AWS::Logs::LogGroup}. */
@ApplicationScoped
public class LogsCfnProvisioner implements CfnResourceProvisioner {

    /**
     * Records whether the log group's name came from the template or was generated, so a later
     * update can tell a rename from a no-op. Read back off the resource's stored attributes.
     */
    private static final String LOG_GROUP_NAME_MODE_ATTR = "FlociLogGroupNameMode";
    private static final String NAME_MODE_EXPLICIT = "explicit";
    private static final String NAME_MODE_GENERATED = "generated";
    private static final int LOG_GROUP_NAME_MAX_LENGTH = 512;
    private static final int GENERATED_NAME_SUFFIX_LENGTH = 12;

    private final CloudWatchLogsService logsService;

    public LogsCfnProvisioner(CloudWatchLogsService logsService) {
        this.logsService = logsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Logs::LogGroup");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String explicitName = ctx.resolveOptional(props, "LogGroupName");
        boolean hasExplicitName = explicitName != null && !explicitName.isBlank();
        String priorPhysicalId = ctx.priorPhysicalId();

        String previousNameMode = r.getAttributes().get(LOG_GROUP_NAME_MODE_ATTR);
        if (previousNameMode == null && priorPhysicalId != null) {
            // Stacks persisted before FlociLogGroupNameMode existed have no recorded mode, but an
            // auto-generated name always has the deterministic shape generatePhysicalName produces,
            // so anything else must have been explicit.
            previousNameMode = isGeneratedName(priorPhysicalId, ctx.stackName(), r.getLogicalId(),
                    LOG_GROUP_NAME_MAX_LENGTH) ? NAME_MODE_GENERATED : NAME_MODE_EXPLICIT;
        }
        // Going from an explicit name to none is itself a replacement-worthy change on real AWS, not
        // something to silently keep reconciling under the old explicit name.
        boolean explicitNameRemoved = priorPhysicalId != null && !hasExplicitName
                && NAME_MODE_EXPLICIT.equals(previousNameMode);

        String name;
        if (hasExplicitName) {
            name = explicitName;
        } else if (priorPhysicalId != null && !explicitNameRemoved) {
            // No explicit name and the prior name was itself auto-generated: keep it across updates
            // instead of generating a fresh random one each time, so the log group is reconciled in
            // place rather than replaced on every no-op update.
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
        if (props != null && props.has("Tags") && props.get("Tags").isArray()) {
            for (JsonNode tag : props.get("Tags")) {
                String key = ctx.engine().resolve(tag.path("Key"));
                if (!key.isEmpty()) {
                    tags.put(key, ctx.engine().resolve(tag.path("Value")));
                }
            }
        }

        // LogGroupName isn't updatable in place on real AWS (a change replaces the resource), so only
        // reconcile in place when the name is unchanged and the group is still there; otherwise this is
        // either a first create or a rename, both of which need a fresh createLogGroup call. On a rename,
        // create the new group before deleting the old one: if the new name collides with something else
        // and createLogGroup throws, the update rolls back without touching the old group, since rollback
        // does not restore a resource this method already deleted.
        if (priorPhysicalId != null && priorPhysicalId.equals(name)
                && logsService.logGroupExists(name, ctx.region())) {
            reconcileLogGroup(name, retentionInDays, tags, ctx.region());
        } else {
            boolean preservedPriorGroup = priorPhysicalId != null
                    && !priorPhysicalId.equals(name)
                    && logsService.logGroupExists(priorPhysicalId, ctx.region());
            try {
                logsService.createLogGroup(name, retentionInDays, tags, ctx.region());
            } catch (RuntimeException failure) {
                if (preservedPriorGroup) {
                    r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
                }
                throw failure;
            }
            if (preservedPriorGroup) {
                logsService.deleteLogGroup(priorPhysicalId, ctx.region());
            }
        }

        // Ref returns the log group name; GetAtt Arn is arn:aws:logs:<region>:<account>:log-group:<name>:*
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", AwsArnUtils.Arn
                .of("logs", ctx.region(), ctx.accountId(), "log-group:" + name + ":*").toString());
        r.getAttributes().put(LOG_GROUP_NAME_MODE_ATTR,
                hasExplicitName ? NAME_MODE_EXPLICIT : NAME_MODE_GENERATED);
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

    /**
     * Whether a physical id has the exact shape {@code generatePhysicalName} produces. Copied from
     * the monolith, which still needs it for a type that has not migrated yet.
     */
    private boolean isGeneratedName(String physicalId, String stackName, String logicalId, int maxLength) {
        if (physicalId == null || physicalId.length() < GENERATED_NAME_SUFFIX_LENGTH + 1) {
            return false;
        }
        String suffix = physicalId.substring(physicalId.length() - GENERATED_NAME_SUFFIX_LENGTH);
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        if (physicalId.charAt(physicalId.length() - GENERATED_NAME_SUFFIX_LENGTH - 1) != '-') {
            return false;
        }
        String actualPrefix = physicalId.substring(0, physicalId.length() - GENERATED_NAME_SUFFIX_LENGTH - 1);
        return actualPrefix.equals(expectedGeneratedNamePrefix(stackName, logicalId, maxLength));
    }

    private String expectedGeneratedNamePrefix(String stackName, String logicalId, int maxLength) {
        String base = stackName + "-" + logicalId;
        if (maxLength <= 0 || base.length() + 1 + GENERATED_NAME_SUFFIX_LENGTH <= maxLength) {
            return base;
        }
        int keep = Math.max(0, maxLength - GENERATED_NAME_SUFFIX_LENGTH - 1);
        String prefix = base.length() > keep ? base.substring(0, keep) : base;
        while (prefix.endsWith("-")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        logsService.deleteLogGroup(physicalId, region);
    }
}
