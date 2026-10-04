package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.scheduler.model.FlexibleTimeWindow;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleRequest;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;

/** Provisions schedules through the same Scheduler service used by its REST API. */
@ApplicationScoped
public class SchedulerScheduleCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Scheduler::Schedule";
    private static final Logger LOG = Logger.getLogger(SchedulerScheduleCfnProvisioner.class);
    private static final String GROUP_ATTR = "FlociSchedulerGroupName";
    private static final String NAME_MODE_ATTR = "FlociSchedulerNameMode";
    private static final String NAME_REPLACEMENT_ATTR = "__FlociSchedulerNameReplacement";
    private static final String SNAPSHOT_ATTR = "__FlociSchedulerUpdateSnapshot";
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .addModule(new JavaTimeModule())
            .build();

    private final SchedulerService schedulerService;

    @Inject
    public SchedulerScheduleCfnProvisioner(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        readCleanup(resource);
        if (ctx.isUpdate() && resource.getAttributes().containsKey(SNAPSHOT_ATTR)) {
            try {
                rollbackUpdate(resource);
                ctx = new ProvisionContext(ctx.engine(), ctx.region(), ctx.accountId(), ctx.stackName(),
                        resource.getPhysicalId(), ctx.progress());
                resource.getAttributes().remove(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR);
                resource.getAttributes().remove(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR);
            } catch (RuntimeException failure) {
                resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, failure.getMessage());
                throw new IllegalStateException("Could not finish the previous schedule update rollback", failure);
            }
        }
        Map<String, String> attributesBefore = Map.copyOf(resource.getAttributes());
        String explicitName = optional(props, "Name", ctx);
        if (explicitName != null && explicitName.isBlank()) {
            throw validation("Name must not be blank");
        }
        boolean named = explicitName != null && !explicitName.isBlank();
        boolean removedName = ctx.isUpdate() && !named
                && "explicit".equals(attributesBefore.get(NAME_MODE_ATTR));
        String name = removedName ? ctx.generatePhysicalName(resource.getLogicalId(), 64, false)
                : ctx.stablePhysicalName(explicitName, resource.getLogicalId(), 64, false);
        String explicitGroup = optional(props, "GroupName", ctx);
        if (explicitGroup != null && explicitGroup.isBlank()) {
            throw validation("GroupName must not be blank");
        }
        String group = explicitGroup == null ? "default" : explicitGroup;
        ScheduleRequest request = request(props, name, group, ctx);
        String priorGroup = attributesBefore.getOrDefault(GROUP_ATTR, "default");
        boolean sameAddress = ctx.reusesPriorEntity(name) && priorGroup.equals(group);
        boolean groupMove = ctx.reusesPriorEntity(name) && !priorGroup.equals(group);

        Schedule schedule;
        ObjectNode moveSnapshot = null;
        if (sameAddress) {
            Schedule current = schedulerService.getSchedule(name, group, ctx.region());
            snapshot(resource, current, ctx.region(), null, null);
            try {
                schedule = schedulerService.updateSchedule(request, ctx.region());
            } catch (RuntimeException failure) {
                restoreAfterFailure(resource, failure);
                throw failure;
            }
        } else if (groupMove) {
            Schedule current = schedulerService.getSchedule(name, priorGroup, ctx.region());
            moveSnapshot = snapshot(resource, current, ctx.region(), name, group);
            ScheduleRequest paused = requestFrom(current);
            paused.setState("DISABLED");
            try {
                // Keep a moving schedule from firing at both addresses while the stack update
                // waits for its other resources. The original is restored if creation fails.
                schedulerService.updateSchedule(paused, ctx.region());
                schedule = schedulerService.createSchedule(request, ctx.region());
            } catch (RuntimeException failure) {
                restoreAfterFailure(resource, failure);
                throw failure;
            }
        } else {
            schedule = schedulerService.createSchedule(request, ctx.region());
        }
        if (!sameAddress) {
            retireDisplaced(resource, address(schedule.getGroupName(), schedule.getName()));
        }
        resource.setPhysicalId(schedule.getName());
        resource.getAttributes().put("Arn", schedule.getArn());
        resource.getAttributes().put(GROUP_ATTR, schedule.getGroupName());
        resource.getAttributes().put(NAME_MODE_ATTR, named ? "explicit" : "generated");
        resource.getAttributes().put(NAME_REPLACEMENT_ATTR,
                Boolean.toString(ctx.isUpdate() && !ctx.priorPhysicalId().equals(name)));

        // Ref is the name, but deletion needs both name and group. The cleanup helper's private
        // identity includes the group so a group move with an unchanged Ref is still tracked.
        ProvisionContext cleanupContext = new ProvisionContext(ctx.engine(), ctx.region(), ctx.accountId(),
                ctx.stackName(), ctx.isUpdate() ? address(priorGroup, ctx.priorPhysicalId()) : null, ctx.progress());
        ReplacementCleanup.record(cleanupResource(resource), cleanupContext, attributesBefore);
        if (moveSnapshot != null) {
            moveSnapshot.put("destinationCreated", true);
            resource.getAttributes().put(SNAPSHOT_ATTR, moveSnapshot.toString());
        }
    }

    private static ObjectNode snapshot(StackResource resource, Schedule current, String region,
                                       String destinationName, String destinationGroup) {
        ObjectNode snapshot = MAPPER.createObjectNode().put("region", region);
        snapshot.set("request", MAPPER.valueToTree(requestFrom(current)));
        if (destinationName != null) {
            snapshot.put("destinationName", destinationName);
            snapshot.put("destinationGroup", destinationGroup);
            snapshot.put("destinationCreated", false);
        }
        resource.getAttributes().put(SNAPSHOT_ATTR, snapshot.toString());
        return snapshot;
    }

    private static ScheduleRequest request(JsonNode props, String name, String group, ProvisionContext ctx) {
        ScheduleRequest request = new ScheduleRequest();
        request.setName(name);
        request.setGroupName(group);
        String expression = optional(props, "ScheduleExpression", ctx);
        if (expression == null || expression.isBlank()) {
            throw validation("requires ScheduleExpression");
        }
        request.setScheduleExpression(expression);
        request.setFlexibleTimeWindow(requiredObject(props, "FlexibleTimeWindow", FlexibleTimeWindow.class, ctx));
        request.setTarget(requiredObject(props, "Target", Target.class, ctx));
        request.setDescription(optional(props, "Description", ctx));
        request.setScheduleExpressionTimezone(optional(props, "ScheduleExpressionTimezone", ctx));
        String state = optional(props, "State", ctx);
        if (state != null && !Set.of("ENABLED", "DISABLED").contains(state)) {
            throw validation("State must be ENABLED or DISABLED");
        }
        request.setState(state);
        request.setKmsKeyArn(optional(props, "KmsKeyArn", ctx));
        request.setStartDate(date(props, "StartDate", ctx));
        request.setEndDate(date(props, "EndDate", ctx));
        return request;
    }

    private static <T> T requiredObject(JsonNode props, String property, Class<T> type, ProvisionContext ctx) {
        JsonNode resolved = props == null ? null : ctx.engine().resolveNodeOmittingNoValue(props.get(property));
        if (resolved == null || !resolved.isObject()) {
            throw validation("requires an object for " + property);
        }
        try {
            return MAPPER.treeToValue(resolved, type);
        } catch (JsonProcessingException e) {
            throw validation("has invalid or unsupported " + property + " properties: " + e.getOriginalMessage());
        }
    }

    private static String optional(JsonNode props, String property, ProvisionContext ctx) {
        JsonNode resolved = props == null ? null : ctx.engine().resolveNodeOmittingNoValue(props.get(property));
        if (resolved == null || resolved.isNull() || resolved.isMissingNode()) {
            return null;
        }
        if (!resolved.isValueNode()) {
            throw validation("requires a scalar for " + property);
        }
        return resolved.asText();
    }

    private static Instant date(JsonNode props, String property, ProvisionContext ctx) {
        String value = optional(props, property, ctx);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw validation(property + " must be a UTC date-time");
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationError", TYPE + " " + message, 400);
    }

    private static ScheduleRequest requestFrom(Schedule schedule) {
        ScheduleRequest request = new ScheduleRequest();
        request.setName(schedule.getName());
        request.setGroupName(schedule.getGroupName());
        request.setScheduleExpression(schedule.getScheduleExpression());
        request.setScheduleExpressionTimezone(schedule.getScheduleExpressionTimezone());
        request.setFlexibleTimeWindow(schedule.getFlexibleTimeWindow());
        request.setTarget(schedule.getTarget());
        request.setDescription(schedule.getDescription());
        request.setState(schedule.getState());
        request.setActionAfterCompletion(schedule.getActionAfterCompletion());
        request.setKmsKeyArn(schedule.getKmsKeyArn());
        request.setStartDate(schedule.getStartDate());
        request.setEndDate(schedule.getEndDate());
        return request;
    }

    private void restoreAfterFailure(StackResource resource, RuntimeException failure) {
        try {
            rollbackUpdate(resource);
            resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
        } catch (RuntimeException restoreFailure) {
            resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, restoreFailure.getMessage());
            if (restoreFailure != failure) {
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        deleteAddress(address(resource.getAttributes().getOrDefault(GROUP_ATTR, "default"),
                resource.getPhysicalId()), region);
        UpdateCleanupResult result = ReplacementCleanup.complete(cleanupResource(resource),
                (type, id, cleanupRegion) -> deleteAddress(id, cleanupRegion));
        if (result.applicable() && !result.complete()) {
            throw new IllegalStateException(result.failureReason());
        }
        resource.getAttributes().remove(SNAPSHOT_ATTR);
    }

    private void deleteAddress(String physicalId, String region) {
        int separator = physicalId.indexOf('/');
        String group = physicalId.substring(0, separator);
        String name = physicalId.substring(separator + 1);
        CfnDeletes.safeDelete("Scheduler schedule", physicalId,
                () -> schedulerService.deleteSchedule(name, group, region), "ResourceNotFoundException");
    }

    private static String address(String group, String name) {
        return group + "/" + name;
    }

    private static StackResource cleanupResource(StackResource resource) {
        StackResource cleanup = new StackResource();
        cleanup.setLogicalId(resource.getLogicalId());
        cleanup.setResourceType(resource.getResourceType());
        cleanup.setPhysicalId(address(resource.getAttributes().getOrDefault(GROUP_ATTR, "default"),
                resource.getPhysicalId()));
        cleanup.setAttributes(resource.getAttributes());
        // GroupName is mutable on CloudFormation. Retain applies to a name replacement only,
        // otherwise a group move would leave the old schedule firing indefinitely.
        if (Boolean.parseBoolean(resource.getAttributes().get(NAME_REPLACEMENT_ATTR))) {
            cleanup.setUpdateReplacePolicy(resource.getUpdateReplacePolicy());
        }
        return cleanup;
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(cleanupResource(resource));
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        if ("UPDATE_FAILED".equals(resource.getStatus()) && retainsFailedUpdateState(resource)) {
            throw new IllegalStateException("Schedule rollback is still pending; its original configuration "
                    + "cannot be discarded by another resource's update cleanup");
        }
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        UpdateCleanupResult result = ReplacementCleanup.complete(cleanupResource(resource),
                (type, id, region) -> deleteAddress(id, region));
        if (!result.applicable() || result.complete()) {
            resource.getAttributes().remove(NAME_REPLACEMENT_ATTR);
        }
        return result;
    }

    @Override
    public UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        UpdateCleanupResult result = ReplacementCleanup.complete(cleanupResource(resource),
                (type, id, region) -> deleteAddress(id, region));
        return result;
    }

    @Override
    public void clearDeleteCleanup(StackResource resource) {
        ReplacementCleanup.clear(resource);
        if (!ReplacementCleanup.hasReplacement(resource)) {
            resource.getAttributes().remove(NAME_REPLACEMENT_ATTR);
        }
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
        resource.getAttributes().remove(NAME_REPLACEMENT_ATTR);
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        ObjectNode source = readCleanup(attempted);
        if (source != null) {
            String currentAddress = cleanupResource(previous).getPhysicalId();
            for (JsonNode entry : source.path("displaced")) {
                String physicalId = entry.path("physicalId").asText();
                if (physicalId.equals(currentAddress)) {
                    continue;
                }
                // The attempted update can recreate an address whose earlier cleanup was exhausted.
                // Preserve that attempt's current retry record when the engine restores the old resource.
                retireDisplaced(previous, physicalId);
            }
        }
        ReplacementCleanup.mergeDisplaced(cleanupResource(previous), cleanupResource(attempted));
    }

    private static ObjectNode readCleanup(StackResource resource) {
        String raw = resource.getAttributes().get(CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        if (raw == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isObject() || (node.has("displaced") && !node.path("displaced").isArray())) {
                throw new IllegalStateException("Invalid schedule replacement cleanup");
            }
            for (JsonNode entry : node.path("displaced")) {
                if (!entry.isObject()) {
                    throw new IllegalStateException("Invalid schedule replacement cleanup");
                }
            }
            return (ObjectNode) node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read schedule replacement cleanup", e);
        }
    }

    private static void retireDisplaced(StackResource resource, String physicalId) {
        ObjectNode cleanup = readCleanup(resource);
        if (cleanup == null) {
            return;
        }
        ArrayNode remaining = MAPPER.createArrayNode();
        for (JsonNode entry : cleanup.path("displaced")) {
            if (!physicalId.equals(entry.path("physicalId").asText())) {
                remaining.add(entry);
            }
        }
        cleanup.set("displaced", remaining);
        if (remaining.isEmpty() && !cleanup.has("priorPhysicalId")) {
            resource.getAttributes().remove(CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        } else {
            resource.getAttributes().put(CfnRollback.REPLACEMENT_CLEANUP_ATTR, cleanup.toString());
        }
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        JsonNode snapshot = readSnapshot(resource);
        if (snapshot != null && snapshot.path("destinationCreated").asBoolean(false)) {
            // Disable the move's destination before restoring an enabled original, even if deleting
            // the destination fails. The cleanup helper retains that disabled orphan's address.
            pauseDestination(snapshot);
        }
        StackResource cleanup = cleanupResource(resource);
        RuntimeException cleanupFailure = null;
        try {
            ReplacementCleanup.rollback(cleanup, (type, id, region) -> deleteAddress(id, region));
        } catch (RuntimeException failure) {
            cleanupFailure = failure;
        } finally {
            String restoredAddress = cleanup.getPhysicalId();
            resource.setPhysicalId(restoredAddress.substring(restoredAddress.indexOf('/') + 1));
        }
        if (snapshot != null) {
            try {
                ScheduleRequest request = MAPPER.treeToValue(snapshot.get("request"), ScheduleRequest.class);
                schedulerService.updateSchedule(request, snapshot.path("region").asText());
                resource.getAttributes().remove(SNAPSHOT_ATTR);
            } catch (JsonProcessingException | RuntimeException restoreFailure) {
                if (cleanupFailure != null && cleanupFailure != restoreFailure) {
                    restoreFailure.addSuppressed(cleanupFailure);
                }
                throw new IllegalStateException("Could not restore schedule update snapshot", restoreFailure);
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
        return true;
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(SNAPSHOT_ATTR);
    }

    private static JsonNode readSnapshot(StackResource resource) {
        String raw = resource.getAttributes().get(SNAPSHOT_ATTR);
        if (raw == null) {
            return null;
        }
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read schedule update snapshot", e);
        }
    }

    private void pauseDestination(JsonNode snapshot) {
        String name = snapshot.path("destinationName").asText();
        String group = snapshot.path("destinationGroup").asText();
        String region = snapshot.path("region").asText();
        try {
            ScheduleRequest request = requestFrom(schedulerService.getSchedule(name, group, region));
            request.setState("DISABLED");
            schedulerService.updateSchedule(request, region);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Schedule move destination {0}/{1} already gone during rollback", group, name);
        }
    }

}
