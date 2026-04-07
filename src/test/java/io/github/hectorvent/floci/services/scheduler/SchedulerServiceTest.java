package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.scheduler.model.FlexibleTimeWindow;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerServiceTest {

    private static final String REGION = "us-east-1";

    private SchedulerService service;

    @BeforeEach
    void setUp() {
        service = new SchedulerService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000")
        );
    }

    @Test
    void getOrCreateDefaultGroup() {
        ScheduleGroup group = service.getOrCreateDefaultGroup(REGION);
        assertEquals("default", group.getName());
        assertEquals("ACTIVE", group.getState());
        assertTrue(group.getArn().contains("schedule-group/default"));
        assertTrue(group.getArn().contains(":scheduler:"));
    }

    @Test
    void getOrCreateDefaultGroupIsIdempotent() {
        ScheduleGroup first = service.getOrCreateDefaultGroup(REGION);
        ScheduleGroup second = service.getOrCreateDefaultGroup(REGION);
        assertEquals(first.getArn(), second.getArn());
        assertEquals(first.getCreationDate(), second.getCreationDate());
    }

    @Test
    void createScheduleGroup() {
        ScheduleGroup group = service.createScheduleGroup("my-group", null, REGION);
        assertEquals("my-group", group.getName());
        assertEquals("ACTIVE", group.getState());
        assertTrue(group.getArn().contains("schedule-group/my-group"));
    }

    @Test
    void createScheduleGroupWithTags() {
        ScheduleGroup group = service.createScheduleGroup(
                "tagged", Map.of("env", "test"), REGION);
        assertEquals("test", group.getTags().get("env"));
    }

    @Test
    void createScheduleGroupDuplicateThrows() {
        service.createScheduleGroup("dup", null, REGION);
        AwsException e = assertThrows(AwsException.class, () ->
                service.createScheduleGroup("dup", null, REGION));
        assertEquals("ConflictException", e.getErrorCode());
        assertEquals(409, e.getHttpStatus());
    }

    @Test
    void createScheduleGroupReservedDefaultNameThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createScheduleGroup("default", null, REGION));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void createScheduleGroupBlankNameThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createScheduleGroup("", null, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleGroupInvalidCharactersThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createScheduleGroup("bad name!", null, REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void getScheduleGroup() {
        service.createScheduleGroup("find-me", null, REGION);
        ScheduleGroup group = service.getScheduleGroup("find-me", REGION);
        assertEquals("find-me", group.getName());
    }

    @Test
    void getScheduleGroupNotFoundThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.getScheduleGroup("missing", REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void getScheduleGroupBlankReturnsDefault() {
        ScheduleGroup group = service.getScheduleGroup("", REGION);
        assertEquals("default", group.getName());
    }

    @Test
    void deleteScheduleGroup() {
        service.createScheduleGroup("to-delete", null, REGION);
        service.deleteScheduleGroup("to-delete", REGION);
        assertThrows(AwsException.class, () ->
                service.getScheduleGroup("to-delete", REGION));
    }

    @Test
    void deleteDefaultGroupThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.deleteScheduleGroup("default", REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void deleteScheduleGroupNotFoundThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.deleteScheduleGroup("missing", REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void listScheduleGroupsIncludesDefault() {
        List<ScheduleGroup> groups = service.listScheduleGroups(null, REGION);
        assertTrue(groups.stream().anyMatch(g -> "default".equals(g.getName())));
    }

    @Test
    void listScheduleGroupsWithPrefix() {
        service.createScheduleGroup("alpha-1", null, REGION);
        service.createScheduleGroup("alpha-2", null, REGION);
        service.createScheduleGroup("beta-1", null, REGION);
        List<ScheduleGroup> result = service.listScheduleGroups("alpha", REGION);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(g -> g.getName().startsWith("alpha")));
    }

    @Test
    void scheduleGroupsAreRegionScoped() {
        service.createScheduleGroup("shared", null, "us-east-1");
        assertThrows(AwsException.class, () ->
                service.getScheduleGroup("shared", "us-west-2"));
    }

    // ──────────────────────────── Schedule tests ────────────────────────────

    @Test
    void createSchedule() {
        Schedule s = service.createSchedule("my-schedule", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:aws:lambda:us-east-1:000000000000:function:my-func",
                        "arn:aws:iam::000000000000:role/my-role", null, null),
                null, null, null, null, null, null, REGION);
        assertEquals("my-schedule", s.getName());
        assertEquals("default", s.getGroupName());
        assertEquals("ENABLED", s.getState());
        assertTrue(s.getArn().contains("schedule/default/my-schedule"));
        assertNotNull(s.getCreationDate());
        assertNotNull(s.getLastModificationDate());
    }

    @Test
    void createScheduleInCustomGroup() {
        service.createScheduleGroup("custom", null, REGION);
        Schedule s = service.createSchedule("my-schedule", "custom", "rate(5 minutes)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:aws:sqs:us-east-1:000000000000:my-queue",
                        "arn:aws:iam::000000000000:role/r", null, null),
                null, null, null, null, null, null, REGION);
        assertEquals("custom", s.getGroupName());
        assertTrue(s.getArn().contains("schedule/custom/my-schedule"));
    }

    @Test
    void createScheduleDuplicateThrows() {
        service.createSchedule("dup", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, null, null, null, null, null, REGION);
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule("dup", null, "rate(1 hour)", null,
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null),
                        null, null, null, null, null, null, REGION));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void createScheduleInNonExistentGroupThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule("s", "no-such-group", "rate(1 hour)", null,
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null),
                        null, null, null, null, null, null, REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void getSchedule() {
        service.createSchedule("find-me", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, null, null, null, null, null, REGION);
        Schedule s = service.getSchedule("find-me", null, REGION);
        assertEquals("find-me", s.getName());
    }

    @Test
    void getScheduleNotFoundThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.getSchedule("missing", null, REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void updateSchedule() {
        service.createSchedule("upd", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                "original desc", null, null, null, null, null, REGION);
        Schedule updated = service.updateSchedule("upd", null, "rate(5 minutes)", "UTC",
                new FlexibleTimeWindow("FLEXIBLE", 10),
                new Target("arn:t2", "arn:r2", "{}", null),
                "updated desc", "DISABLED", null, null, null, null, REGION);
        assertEquals("rate(5 minutes)", updated.getScheduleExpression());
        assertEquals("DISABLED", updated.getState());
        assertEquals("updated desc", updated.getDescription());
        assertNotNull(updated.getCreationDate());
        assertTrue(updated.getLastModificationDate().compareTo(updated.getCreationDate()) >= 0);
    }

    @Test
    void updateScheduleNotFoundThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.updateSchedule("missing", null, "rate(1 hour)", null,
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null),
                        null, null, null, null, null, null, REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void deleteSchedule() {
        service.createSchedule("to-del", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, null, null, null, null, null, REGION);
        service.deleteSchedule("to-del", null, REGION);
        assertThrows(AwsException.class, () ->
                service.getSchedule("to-del", null, REGION));
    }

    @Test
    void deleteScheduleNotFoundThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.deleteSchedule("missing", null, REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void listSchedules() {
        service.createSchedule("s1", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, null, null, null, null, null, REGION);
        service.createSchedule("s2", null, "rate(2 hours)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, null, null, null, null, null, REGION);
        List<Schedule> result = service.listSchedules(null, null, null, REGION);
        assertEquals(2, result.size());
    }

    @Test
    void listSchedulesWithNamePrefix() {
        service.createSchedule("alpha-1", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, null, null, null, null, null, REGION);
        service.createSchedule("alpha-2", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, null, null, null, null, null, REGION);
        service.createSchedule("beta-1", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, null, null, null, null, null, REGION);
        List<Schedule> result = service.listSchedules(null, "alpha", null, REGION);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(s -> s.getName().startsWith("alpha")));
    }

    @Test
    void listSchedulesWithStateFilter() {
        service.createSchedule("enabled-1", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, "ENABLED", null, null, null, null, REGION);
        service.createSchedule("disabled-1", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, "DISABLED", null, null, null, null, REGION);
        List<Schedule> result = service.listSchedules(null, null, "DISABLED", REGION);
        assertEquals(1, result.size());
        assertEquals("disabled-1", result.get(0).getName());
    }

    @Test
    void schedulesAreRegionScoped() {
        service.createSchedule("regional", null, "rate(1 hour)", null,
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null),
                null, null, null, null, null, null, "us-east-1");
        assertThrows(AwsException.class, () ->
                service.getSchedule("regional", null, "us-west-2"));
    }
}
