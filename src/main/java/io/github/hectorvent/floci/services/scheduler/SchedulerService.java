package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
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
        return groupStore.get(groupKey(region, effectiveName))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "ScheduleGroup not found: " + effectiveName, 404));
    }

    public void deleteScheduleGroup(String name, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "Name is required.", 400);
        }
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

    public java.util.List<ScheduleGroup> listScheduleGroups(String namePrefix, String region) {
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

    public Schedule createSchedule(String name, String groupName, String scheduleExpression,
                                   String scheduleExpressionTimezone,
                                   io.github.hectorvent.floci.services.scheduler.model.FlexibleTimeWindow flexibleTimeWindow,
                                   io.github.hectorvent.floci.services.scheduler.model.Target target,
                                   String description, String state, String actionAfterCompletion,
                                   Instant startDate, Instant endDate, String kmsKeyArn,
                                   String region) {
        validateName(name);
        String effectiveGroup = (groupName == null || groupName.isBlank()) ? DEFAULT_GROUP : groupName;
        getScheduleGroup(effectiveGroup, region); // verify group exists

        String key = scheduleKey(region, effectiveGroup, name);
        if (scheduleStore.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "Schedule already exists: " + name, 409);
        }

        Instant now = Instant.now();
        Schedule schedule = new Schedule();
        schedule.setName(name);
        schedule.setArn(buildScheduleArn(region, effectiveGroup, name));
        schedule.setGroupName(effectiveGroup);
        schedule.setState(state != null ? state : "ENABLED");
        schedule.setScheduleExpression(scheduleExpression);
        schedule.setScheduleExpressionTimezone(scheduleExpressionTimezone);
        schedule.setFlexibleTimeWindow(flexibleTimeWindow);
        schedule.setTarget(target);
        schedule.setDescription(description);
        schedule.setActionAfterCompletion(actionAfterCompletion);
        schedule.setStartDate(startDate);
        schedule.setEndDate(endDate);
        schedule.setKmsKeyArn(kmsKeyArn);
        schedule.setCreationDate(now);
        schedule.setLastModificationDate(now);

        scheduleStore.put(key, schedule);
        LOG.infov("Created schedule: {0} in group {1}, region {2}", name, effectiveGroup, region);
        return schedule;
    }

    public Schedule getSchedule(String name, String groupName, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "Name is required.", 400);
        }
        String effectiveGroup = (groupName == null || groupName.isBlank()) ? DEFAULT_GROUP : groupName;
        return scheduleStore.get(scheduleKey(region, effectiveGroup, name))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Schedule not found: " + name, 404));
    }

    public Schedule updateSchedule(String name, String groupName, String scheduleExpression,
                                   String scheduleExpressionTimezone,
                                   io.github.hectorvent.floci.services.scheduler.model.FlexibleTimeWindow flexibleTimeWindow,
                                   io.github.hectorvent.floci.services.scheduler.model.Target target,
                                   String description, String state, String actionAfterCompletion,
                                   Instant startDate, Instant endDate, String kmsKeyArn,
                                   String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "Name is required.", 400);
        }
        String effectiveGroup = (groupName == null || groupName.isBlank()) ? DEFAULT_GROUP : groupName;
        String key = scheduleKey(region, effectiveGroup, name);
        Schedule existing = scheduleStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Schedule not found: " + name, 404));

        Instant now = Instant.now();
        Schedule updated = new Schedule();
        updated.setName(name);
        updated.setArn(existing.getArn());
        updated.setGroupName(effectiveGroup);
        updated.setState(state != null ? state : "ENABLED");
        updated.setScheduleExpression(scheduleExpression);
        updated.setScheduleExpressionTimezone(scheduleExpressionTimezone);
        updated.setFlexibleTimeWindow(flexibleTimeWindow);
        updated.setTarget(target);
        updated.setDescription(description);
        updated.setActionAfterCompletion(actionAfterCompletion);
        updated.setStartDate(startDate);
        updated.setEndDate(endDate);
        updated.setKmsKeyArn(kmsKeyArn);
        updated.setCreationDate(existing.getCreationDate());
        updated.setLastModificationDate(now);

        scheduleStore.put(key, updated);
        LOG.infov("Updated schedule: {0} in group {1}", name, effectiveGroup);
        return updated;
    }

    public void deleteSchedule(String name, String groupName, String region) {
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationException", "Name is required.", 400);
        }
        String effectiveGroup = (groupName == null || groupName.isBlank()) ? DEFAULT_GROUP : groupName;
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
