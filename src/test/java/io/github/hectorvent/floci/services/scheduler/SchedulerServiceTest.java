package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.scheduler.model.DeadLetterConfig;
import io.github.hectorvent.floci.services.scheduler.model.FlexibleTimeWindow;
import io.github.hectorvent.floci.services.scheduler.model.RetryPolicy;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleRequest;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

    private ScheduleRequest newRequest(String name, String groupName, String expression,
                                       FlexibleTimeWindow ftw, Target target) {
        ScheduleRequest req = new ScheduleRequest();
        req.setName(name);
        req.setGroupName(groupName);
        req.setScheduleExpression(expression);
        req.setFlexibleTimeWindow(ftw);
        req.setTarget(target);
        return req;
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
    void deleteScheduleGroupCascadesSchedules() {
        service.createScheduleGroup("cascade-grp", null, REGION);
        service.createSchedule(
                newRequest("s1", "cascade-grp", "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("s2", "cascade-grp", "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        service.deleteScheduleGroup("cascade-grp", REGION);
        assertThrows(AwsException.class, () ->
                service.getSchedule("s1", "cascade-grp", REGION));
        assertThrows(AwsException.class, () ->
                service.getSchedule("s2", "cascade-grp", REGION));
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
        ScheduleRequest req = newRequest("my-schedule", null, "rate(1 hour)",
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:aws:lambda:us-east-1:000000000000:function:my-func",
                        "arn:aws:iam::000000000000:role/my-role", null, null));
        Schedule s = service.createSchedule(req, REGION);
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
        ScheduleRequest req = newRequest("my-schedule", "custom", "rate(5 minutes)",
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:aws:sqs:us-east-1:000000000000:my-queue",
                        "arn:aws:iam::000000000000:role/r", null, null));
        Schedule s = service.createSchedule(req, REGION);
        assertEquals("custom", s.getGroupName());
        assertTrue(s.getArn().contains("schedule/custom/my-schedule"));
    }

    @Test
    void createScheduleMissingExpressionThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, null,
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:t", "arn:r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleMissingFlexibleTimeWindowThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)", null,
                                new Target("arn:t", "arn:r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleMissingTargetThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null), null),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleMissingTargetArnThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target(null, "arn:r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleMissingTargetRoleArnThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:t", null, null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleMissingFlexibleTimeWindowModeThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow(null, null),
                                new Target("arn:t", "arn:r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleInvalidFlexibleTimeWindowModeThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("INVALID", null),
                                new Target("arn:t", "arn:r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleFlexibleMissingMaxWindowThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("FLEXIBLE", null),
                                new Target("arn:t", "arn:r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleOffModeWithMaxWindowThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", 10),
                                new Target("arn:t", "arn:r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleDeadLetterConfigMissingArnThrows() {
        Target target = new Target("arn:t", "arn:r", null, null);
        target.setDeadLetterConfig(new DeadLetterConfig(null));
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null), target),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void updateScheduleMissingRequiredFieldsThrows() {
        service.createSchedule(
                newRequest("val-upd", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        AwsException e = assertThrows(AwsException.class, () ->
                service.updateSchedule(
                        newRequest("val-upd", null, null,
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:t", "arn:r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleDuplicateThrows() {
        service.createSchedule(
                newRequest("dup", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("dup", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:t", "arn:r", null, null)),
                        REGION));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void createScheduleInNonExistentGroupThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", "no-such-group", "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:t", "arn:r", null, null)),
                        REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void getSchedule() {
        service.createSchedule(
                newRequest("find-me", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
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
        ScheduleRequest createReq = newRequest("upd", null, "rate(1 hour)",
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null));
        createReq.setDescription("original desc");
        service.createSchedule(createReq, REGION);

        ScheduleRequest updateReq = newRequest("upd", null, "rate(5 minutes)",
                new FlexibleTimeWindow("FLEXIBLE", 10),
                new Target("arn:t2", "arn:r2", "{}", null));
        updateReq.setScheduleExpressionTimezone("UTC");
        updateReq.setDescription("updated desc");
        updateReq.setState("DISABLED");
        Schedule updated = service.updateSchedule(updateReq, REGION);
        assertEquals("rate(5 minutes)", updated.getScheduleExpression());
        assertEquals("DISABLED", updated.getState());
        assertEquals("updated desc", updated.getDescription());
        assertNotNull(updated.getCreationDate());
        assertTrue(updated.getLastModificationDate().compareTo(updated.getCreationDate()) >= 0);
    }

    @Test
    void updateScheduleNotFoundThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.updateSchedule(
                        newRequest("missing", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:t", "arn:r", null, null)),
                        REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void deleteSchedule() {
        service.createSchedule(
                newRequest("to-del", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
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
        service.createSchedule(
                newRequest("s1", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("s2", null, "rate(2 hours)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        List<Schedule> result = service.listSchedules(null, null, null, REGION);
        assertEquals(2, result.size());
    }

    @Test
    void listSchedulesAcrossGroups() {
        service.createScheduleGroup("group-a", null, REGION);
        service.createSchedule(
                newRequest("s-default", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("s-group-a", "group-a", "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        List<Schedule> result = service.listSchedules(null, null, null, REGION);
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> "s-default".equals(s.getName())));
        assertTrue(result.stream().anyMatch(s -> "s-group-a".equals(s.getName())));
    }

    @Test
    void listSchedulesFilteredByGroup() {
        service.createScheduleGroup("group-b", null, REGION);
        service.createSchedule(
                newRequest("s-in-default", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("s-in-group-b", "group-b", "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        List<Schedule> result = service.listSchedules("group-b", null, null, REGION);
        assertEquals(1, result.size());
        assertEquals("s-in-group-b", result.get(0).getName());
    }

    @Test
    void listSchedulesWithNamePrefix() {
        service.createSchedule(
                newRequest("alpha-1", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("alpha-2", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("beta-1", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                REGION);
        List<Schedule> result = service.listSchedules(null, "alpha", null, REGION);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(s -> s.getName().startsWith("alpha")));
    }

    @Test
    void listSchedulesWithStateFilter() {
        ScheduleRequest enabledReq = newRequest("enabled-1", null, "rate(1 hour)",
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null));
        enabledReq.setState("ENABLED");
        service.createSchedule(enabledReq, REGION);

        ScheduleRequest disabledReq = newRequest("disabled-1", null, "rate(1 hour)",
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null));
        disabledReq.setState("DISABLED");
        service.createSchedule(disabledReq, REGION);

        List<Schedule> result = service.listSchedules(null, null, "DISABLED", REGION);
        assertEquals(1, result.size());
        assertEquals("disabled-1", result.get(0).getName());
    }

    @Test
    void createScheduleWithDeadLetterConfig() {
        Target target = new Target("arn:aws:lambda:us-east-1:000000000000:function:my-func",
                "arn:aws:iam::000000000000:role/my-role", null, null);
        target.setDeadLetterConfig(new DeadLetterConfig("arn:aws:sqs:us-east-1:000000000000:dlq"));
        ScheduleRequest req = newRequest("dlc-schedule", null, "rate(1 hour)",
                new FlexibleTimeWindow("OFF", null), target);
        Schedule s = service.createSchedule(req, REGION);
        assertNotNull(s.getTarget().getDeadLetterConfig());
        assertEquals("arn:aws:sqs:us-east-1:000000000000:dlq",
                s.getTarget().getDeadLetterConfig().getArn());
    }

    @Test
    void updateScheduleOverwritesDeadLetterConfig() {
        Target target = new Target("arn:t", "arn:r", null, null);
        target.setDeadLetterConfig(new DeadLetterConfig("arn:aws:sqs:us-east-1:000000000000:dlq"));
        service.createSchedule(
                newRequest("dlc-upd", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null), target),
                REGION);

        Target updatedTarget = new Target("arn:t2", "arn:r2", null, null);
        updatedTarget.setDeadLetterConfig(new DeadLetterConfig("arn:aws:sqs:us-east-1:000000000000:dlq-updated"));
        ScheduleRequest updateReq = newRequest("dlc-upd", null, "rate(5 minutes)",
                new FlexibleTimeWindow("OFF", null), updatedTarget);
        Schedule updated = service.updateSchedule(updateReq, REGION);
        assertEquals("arn:aws:sqs:us-east-1:000000000000:dlq-updated",
                updated.getTarget().getDeadLetterConfig().getArn());
    }

    @Test
    void createScheduleWithRetryPolicy() {
        Target target = new Target("arn:t", "arn:r", null, null);
        target.setRetryPolicy(new RetryPolicy(3600, 5));
        ScheduleRequest req = newRequest("retry-schedule", null, "rate(1 hour)",
                new FlexibleTimeWindow("OFF", null), target);
        Schedule s = service.createSchedule(req, REGION);
        assertNotNull(s.getTarget().getRetryPolicy());
        assertEquals(3600, s.getTarget().getRetryPolicy().getMaximumEventAgeInSeconds());
        assertEquals(5, s.getTarget().getRetryPolicy().getMaximumRetryAttempts());
    }

    @Test
    void createScheduleWithStartAndEndDate() {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-12-31T23:59:59Z");
        ScheduleRequest req = newRequest("dated-schedule", null, "rate(1 hour)",
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:r", null, null));
        req.setStartDate(start);
        req.setEndDate(end);
        Schedule s = service.createSchedule(req, REGION);
        assertEquals(start, s.getStartDate());
        assertEquals(end, s.getEndDate());

        Schedule fetched = service.getSchedule("dated-schedule", null, REGION);
        assertEquals(start, fetched.getStartDate());
        assertEquals(end, fetched.getEndDate());
    }

    @Test
    void schedulesAreRegionScoped() {
        service.createSchedule(
                newRequest("regional", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:r", null, null)),
                "us-east-1");
        assertThrows(AwsException.class, () ->
                service.getSchedule("regional", null, "us-west-2"));
    }
}
