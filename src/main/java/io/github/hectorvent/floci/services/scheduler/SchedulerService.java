package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class SchedulerService {

    private static final Logger LOG = Logger.getLogger(SchedulerService.class);

    // AWS EventBridge Scheduler name constraints: [0-9a-zA-Z-_.]+, 1-64 chars.
    private static final Pattern NAME_PATTERN = Pattern.compile("[0-9a-zA-Z\\-_.]{1,64}");
    private static final String DEFAULT_GROUP = "default";

    private final StorageBackend<String, ScheduleGroup> groupStore;
    private final StorageBackend<String, Schedule> scheduleStore;
    private final RegionResolver regionResolver;

    @Inject
    public SchedulerService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create("scheduler", "scheduler-groups.json",
                        new TypeReference<Map<String, ScheduleGroup>>() {}),
                storageFactory.create("scheduler", "scheduler-schedules.json",
                        new TypeReference<Map<String, Schedule>>() {}),
                regionResolver
        );
    }

    SchedulerService(StorageBackend<String, ScheduleGroup> groupStore,
                     StorageBackend<String, Schedule> scheduleStore,
                     RegionResolver regionResolver) {
        this.groupStore = groupStore;
        this.scheduleStore = scheduleStore;
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Schedule Groups ────────────────────────────

    public ScheduleGroup getOrCreateDefaultGroup(String region) {
        String key = groupKey(region, DEFAULT_GROUP);
        return groupStore.get(key).orElseGet(() -> {
            Instant now = Instant.now();
            ScheduleGroup group = new ScheduleGroup(
                    DEFAULT_GROUP,
                    buildGroupArn(region, DEFAULT_GROUP),
                    "ACTIVE",
                    now,
                    now
            );
            groupStore.put(key, group);
            return group;
        });
    }

    public ScheduleGroup createScheduleGroup(String name, Map<String, String> tags, String region) {
        validateName(name);
        if (DEFAULT_GROUP.equals(name)) {
            throw new AwsException("ConflictException",
                    "ScheduleGroup already exists: " + name, 409);
        }
        String key = groupKey(region, name);
        if (groupStore.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "ScheduleGroup already exists: " + name, 409);
        }
        Instant now = Instant.now();
        ScheduleGroup group = new ScheduleGroup(
                name,
                buildGroupArn(region, name),
                "ACTIVE",
                now,
                now
        );
        if (tags != null) {
            group.getTags().putAll(tags);
        }
        groupStore.put(key, group);
        LOG.infov("Created schedule group: {0} in region {1}", name, region);
        return group;
    }

    public ScheduleGroup getScheduleGroup(String name, String region) {
        String effectiveName = (name == null || name.isBlank()) ? DEFAULT_GROUP : name;
        if (DEFAULT_GROUP.equals(effectiveName)) {
            return getOrCreateDefaultGroup(region);
        }
        validateName(effectiveName);
        return groupStore.get(groupKey(region, effectiveName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "ScheduleGroup not found: " + effectiveName, 404));
    }

    public void deleteScheduleGroup(String name, String region) {
        validateName(name);
        if (DEFAULT_GROUP.equals(name)) {
            throw new AwsException("ValidationException",
                    "Cannot delete the default schedule group.", 400);
        }
        String key = groupKey(region, name);
        groupStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "ScheduleGroup not found: " + name, 404));

        // Cascade-delete all schedules in this group (matches AWS behavior)
        String schedulePrefix = "schedule:" + region + ":" + name + ":";
        List<String> orphanKeys = scheduleStore.scan(k -> k.startsWith(schedulePrefix))
                .stream()
                .map(s -> scheduleKey(region, name, s.getName()))
                .toList();
        orphanKeys.forEach(scheduleStore::delete);

        groupStore.delete(key);
        LOG.infov("Deleted schedule group: {0} (removed {1} schedules)", name, orphanKeys.size());
    }

    public List<ScheduleGroup> listScheduleGroups(String namePrefix, String region) {
        getOrCreateDefaultGroup(region);
        String storagePrefix = "group:" + region + ":";
        return groupStore.scan(k -> {
            if (!k.startsWith(storagePrefix)) {
                return false;
            }
            if (namePrefix == null || namePrefix.isBlank()) {
                return true;
            }
            String groupName = k.substring(storagePrefix.length());
            return groupName.startsWith(namePrefix);
        });
    }

    // ──────────────────────────── Schedules ────────────────────────────

    public Schedule createSchedule(ScheduleRequest req, String region) {
        validateName(req.getName());
        validateScheduleRequest(req);
        String effectiveGroup = resolveAndValidateGroup(req.getGroupName());
        getScheduleGroup(effectiveGroup, region); // verify group exists

        String key = scheduleKey(region, effectiveGroup, req.getName());
        if (scheduleStore.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "Schedule already exists: " + req.getName(), 409);
        }

        Instant now = Instant.now();
        Schedule schedule = new Schedule();
        schedule.setName(req.getName());
        schedule.setArn(buildScheduleArn(region, effectiveGroup, req.getName()));
        schedule.setGroupName(effectiveGroup);
        schedule.setState(req.getState() != null ? req.getState() : "ENABLED");
        schedule.setScheduleExpression(req.getScheduleExpression());
        schedule.setScheduleExpressionTimezone(req.getScheduleExpressionTimezone());
        schedule.setFlexibleTimeWindow(req.getFlexibleTimeWindow());
        schedule.setTarget(req.getTarget());
        schedule.setDescription(req.getDescription());
        schedule.setActionAfterCompletion(req.getActionAfterCompletion());
        schedule.setStartDate(req.getStartDate());
        schedule.setEndDate(req.getEndDate());
        schedule.setKmsKeyArn(req.getKmsKeyArn());
        schedule.setCreationDate(now);
        schedule.setLastModificationDate(now);

        scheduleStore.put(key, schedule);
        LOG.infov("Created schedule: {0} in group {1}, region {2}", req.getName(), effectiveGroup, region);
        return schedule;
    }

    public Schedule getSchedule(String name, String groupName, String region) {
        validateName(name);
        String effectiveGroup = resolveAndValidateGroup(groupName);
        return scheduleStore.get(scheduleKey(region, effectiveGroup, name))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Schedule not found: " + name, 404));
    }

    public Schedule updateSchedule(ScheduleRequest req, String region) {
        validateName(req.getName());
        validateScheduleRequest(req);
        String effectiveGroup = resolveAndValidateGroup(req.getGroupName());
        String key = scheduleKey(region, effectiveGroup, req.getName());
        Schedule existing = scheduleStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Schedule not found: " + req.getName(), 404));

        Instant now = Instant.now();
        Schedule updated = new Schedule();
        updated.setName(req.getName());
        updated.setArn(existing.getArn());
        updated.setGroupName(effectiveGroup);
        updated.setState(req.getState() != null ? req.getState() : "ENABLED");
        updated.setScheduleExpression(req.getScheduleExpression());
        updated.setScheduleExpressionTimezone(req.getScheduleExpressionTimezone());
        updated.setFlexibleTimeWindow(req.getFlexibleTimeWindow());
        updated.setTarget(req.getTarget());
        updated.setDescription(req.getDescription());
        updated.setActionAfterCompletion(req.getActionAfterCompletion());
        updated.setStartDate(req.getStartDate());
        updated.setEndDate(req.getEndDate());
        updated.setKmsKeyArn(req.getKmsKeyArn());
        updated.setCreationDate(existing.getCreationDate());
        updated.setLastModificationDate(now);

        scheduleStore.put(key, updated);
        LOG.infov("Updated schedule: {0} in group {1}", req.getName(), effectiveGroup);
        return updated;
    }

    public void deleteSchedule(String name, String groupName, String region) {
        validateName(name);
        String effectiveGroup = resolveAndValidateGroup(groupName);
        String key = scheduleKey(region, effectiveGroup, name);
        scheduleStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Schedule not found: " + name, 404));
        scheduleStore.delete(key);
        LOG.infov("Deleted schedule: {0} in group {1}", name, effectiveGroup);
    }

    public List<Schedule> listSchedules(String groupName, String namePrefix, String state, String region) {
        String storagePrefix;
        if (groupName != null && !groupName.isBlank()) {
            validateName(groupName);
            storagePrefix = "schedule:" + region + ":" + groupName + ":";
        } else {
            storagePrefix = "schedule:" + region + ":";
        }
        return scheduleStore.scan(k -> {
            if (!k.startsWith(storagePrefix)) {
                return false;
            }
            // Extract schedule name (last segment after the last colon)
            String scheduleName = k.substring(k.lastIndexOf(':') + 1);
            if (namePrefix != null && !namePrefix.isBlank() && !scheduleName.startsWith(namePrefix)) {
                return false;
            }
            return true;
        }).stream().filter(s -> {
            if (state != null && !state.isBlank()) {
                return state.equals(s.getState());
            }
            return true;
        }).toList();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "Name is required.", 400);
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "Name must match pattern [0-9a-zA-Z-_.]{1,64}: " + name, 400);
        }
    }

    private String resolveAndValidateGroup(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return DEFAULT_GROUP;
        }
        validateName(groupName);
        return groupName;
    }

    private void validateScheduleRequest(ScheduleRequest req) {
        if (req.getScheduleExpression() == null || req.getScheduleExpression().isBlank()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'scheduleExpression' failed to satisfy constraint: Member must not be null", 400);
        }
        if (req.getFlexibleTimeWindow() == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'flexibleTimeWindow' failed to satisfy constraint: Member must not be null", 400);
        }
        String flexibleTimeWindowMode = req.getFlexibleTimeWindow().getMode();
        if (flexibleTimeWindowMode == null || flexibleTimeWindowMode.isBlank()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'flexibleTimeWindow.mode' failed to satisfy constraint: Member must not be null", 400);
        }
        flexibleTimeWindowMode = flexibleTimeWindowMode.trim();
        if (!"OFF".equals(flexibleTimeWindowMode) && !"FLEXIBLE".equals(flexibleTimeWindowMode)) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value '" + flexibleTimeWindowMode + "' at 'flexibleTimeWindow.mode' failed to satisfy constraint: Member must satisfy enum value set: [OFF, FLEXIBLE]", 400);
        }
        if ("FLEXIBLE".equals(flexibleTimeWindowMode)
                && req.getFlexibleTimeWindow().getMaximumWindowInMinutes() == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'flexibleTimeWindow.maximumWindowInMinutes' failed to satisfy constraint: Member must not be null", 400);
        }
        if ("OFF".equals(flexibleTimeWindowMode)
                && req.getFlexibleTimeWindow().getMaximumWindowInMinutes() != null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value at 'flexibleTimeWindow.maximumWindowInMinutes' failed to satisfy constraint: Member must be null when flexibleTimeWindow.mode is OFF", 400);
        }
        if (req.getTarget() == null) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'target' failed to satisfy constraint: Member must not be null", 400);
        }
        if (req.getTarget().getArn() == null || req.getTarget().getArn().isBlank()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'target.arn' failed to satisfy constraint: Member must not be null", 400);
        }
        if (req.getTarget().getRoleArn() == null || req.getTarget().getRoleArn().isBlank()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'target.roleArn' failed to satisfy constraint: Member must not be null", 400);
        }
        if (req.getTarget().getDeadLetterConfig() != null
                && (req.getTarget().getDeadLetterConfig().getArn() == null
                || req.getTarget().getDeadLetterConfig().getArn().isBlank())) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at 'target.deadLetterConfig.arn' failed to satisfy constraint: Member must not be null", 400);
        }
    }

    private String buildGroupArn(String region, String name) {
        return regionResolver.buildArn("scheduler", region, "schedule-group/" + name);
    }

    private static String groupKey(String region, String name) {
        return "group:" + region + ":" + name;
    }

    private String buildScheduleArn(String region, String groupName, String name) {
        return regionResolver.buildArn("scheduler", region, "schedule/" + groupName + "/" + name);
    }

    private static String scheduleKey(String region, String groupName, String name) {
        return "schedule:" + region + ":" + groupName + ":" + name;
    }
}
