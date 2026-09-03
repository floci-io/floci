package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            // No explicit name and this resource already has a physical id: keep it across updates
            // instead of generating a fresh random one each retry, same as LogGroup/Queue do.
            name = r.getPhysicalId() != null
                    ? r.getPhysicalId()
                    : ctx.generatePhysicalName(r.getLogicalId(), 64, false);
        }
        // Tags wrapped in an intrinsic (e.g. Fn::If choosing between two tag lists) is not resolved
        // here: the engine's Fn::If support is scalar-only, so a conditional list would collapse to
        // a string rather than the chosen array. tagsAreResolvable is true both when Tags is absent
        // (the template genuinely wants no tags) and when it is a plain array (the template's actual
        // desired tags), and false only when Tags is present but not a plain array - the one case
        // where the desired state genuinely cannot be read, so the retry path below must not treat
        // "couldn't resolve the intended tags" as "the intended tags are empty" and delete everything
        // live.
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
        try {
            group = schedulerService.createScheduleGroup(name, tags, ctx.region());
        } catch (AwsException e) {
            if (!"ConflictException".equals(e.getErrorCode()) || !name.equals(r.getPhysicalId())) {
                throw e;
            }
            // Same-stack create-retry: CloudFormation only retries a resource under the physical id
            // it previously assigned, so a conflict on that exact name means this logical resource's
            // own group already exists from an earlier attempt. Adopt it instead of failing the whole
            // stack on a retry of a step that already succeeded.
            group = schedulerService.getScheduleGroup(name, ctx.region());
            // Reconcile tags both ways: a key dropped from the template (or the whole Tags list
            // emptied, or Tags removed entirely) must not linger on the live resource, matching how
            // the same-stack retry path applies every other property change rather than only ever
            // adding. Skipped only when Tags is present but unresolvable (Fn::If) - untagging every
            // live key there would erase tags for a reason unrelated to what the template asked for.
            if (tagsAreResolvable) {
                Set<String> staleKeys = new HashSet<>(group.getTags().keySet());
                staleKeys.removeAll(tags.keySet());
                if (!staleKeys.isEmpty()) {
                    schedulerService.untagScheduleGroup(name, ctx.region(), new ArrayList<>(staleKeys));
                }
            }
            if (!tags.isEmpty()) {
                schedulerService.tagScheduleGroup(name, ctx.region(), tags);
            }
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
