package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::Scheduler::ScheduleGroup}. Previously unhandled, so
 * the resource type fell through to the generic stub: the stack reported CREATE_COMPLETE with a
 * random physical id and no group was ever created in SchedulerService (issue #2396).
 */
@ApplicationScoped
public class SchedulerScheduleGroupCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(SchedulerScheduleGroupCfnProvisioner.class);

    private final SchedulerService schedulerService;

    @Inject
    public SchedulerScheduleGroupCfnProvisioner(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Scheduler::ScheduleGroup");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        // Name is a createOnlyProperty in the registry schema, so an unnamed group keeps the name
        // it already had across updates instead of getting a fresh random one each time provision
        // runs. Read from the context, not the resource: provision assigns the new id as it works.
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "Name"), r.getLogicalId(), 64, false);
        // Tags wrapped in an intrinsic (e.g. Fn::If choosing between two tag lists) is not resolved
        // here: the engine collapses a list-valued intrinsic to a string (#2983), so a conditional
        // list would not arrive as the chosen array. tagsAreResolvable is true both when Tags is
        // absent (the template genuinely wants no tags) and when it is a plain array (the template's
        // actual desired tags), and false only when Tags is present but not a plain array, the one
        // case where the desired state genuinely cannot be read, so the reconcile path below must
        // not treat "couldn't resolve the intended tags" as "the intended tags are empty" and delete
        // everything live.
        boolean hasTagsProperty = props != null && props.has("Tags");
        boolean tagsAreResolvable = !hasTagsProperty || props.get("Tags").isArray();
        Map<String, String> tags = new HashMap<>();
        if (hasTagsProperty && tagsAreResolvable) {
            for (JsonNode tag : props.get("Tags")) {
                String key = ctx.engine().resolve(tag.path("Key"));
                if (!key.isEmpty()) {
                    tags.put(key, ctx.engine().resolve(tag.path("Value")));
                }
            }
        }

        ScheduleGroup group;
        if (ctx.reusesPriorEntity(name)) {
            // provision is also the update path, and createScheduleGroup answers ConflictException
            // for a name that exists. The derived name matching the prior physical id means this
            // logical resource's own group is already there, so reconcile it rather than create:
            // per the registry schema the update handler is a tag diff and nothing else. A key
            // dropped from the template (or the whole Tags list emptied, or Tags removed entirely)
            // must not linger on the live resource. Skipped only when Tags is present but
            // unresolvable, where untagging every live key would erase tags for a reason unrelated
            // to what the template asked for.
            group = schedulerService.getScheduleGroup(name, ctx.region());
            if (tagsAreResolvable) {
                List<String> staleKeys = ProvisionContext.staleTagKeys(group.getTags(), tags);
                if (!staleKeys.isEmpty()) {
                    schedulerService.untagScheduleGroup(name, ctx.region(), staleKeys);
                }
            }
            if (!tags.isEmpty()) {
                schedulerService.tagScheduleGroup(name, ctx.region(), tags);
            }
        } else {
            group = schedulerService.createScheduleGroup(name, tags, ctx.region());
        }
        r.setPhysicalId(group.getName());
        r.getAttributes().put("Arn", group.getArn());
        if (group.getCreationDate() != null) {
            r.getAttributes().put("CreationDate", group.getCreationDate().toString());
        }
        if (group.getLastModificationDate() != null) {
            r.getAttributes().put("LastModificationDate", group.getLastModificationDate().toString());
        }
        if (group.getState() != null) {
            r.getAttributes().put("State", group.getState());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        try {
            schedulerService.deleteScheduleGroup(physicalId, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Schedule group already gone, treating as deleted: {0}", physicalId);
        }
    }
}
