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
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("s2", "cascade-grp", "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
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
    void createAndUpdateRejectInvalidExpressions() {
        Target target = new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null);
        FlexibleTimeWindow window = new FlexibleTimeWindow("OFF", null);
        for (String expression : List.of("cron(invalid)", "cron(0 10 * * *)",
                "rate(0 minutes)", "rate(1 week)", "rate(1 hours)", "rate(5 hour)",
                "rate(1 minutes)", "rate(5 minute)", "rate(1 days)", "rate(5 day)",
                "at(2026-02-30T10:00:00)")) {
            ScheduleRequest invalid = newRequest("invalid", null, expression, window, target);
            AwsException createError = assertThrows(AwsException.class,
                    () -> service.createSchedule(invalid, REGION), expression);
            assertEquals("ValidationException", createError.getErrorCode());
            assertEquals(400, createError.getHttpStatus());
            assertTrue(createError.getMessage().startsWith("Invalid ScheduleExpression: "));
        }

        ScheduleRequest valid = newRequest("existing", null, "rate(1 hour)", window, target);
        service.createSchedule(valid, REGION);
        for (String expression : List.of("cron(invalid)", "rate(1 week)", "rate(1 hours)", "rate(5 hour)")) {
            ScheduleRequest invalidUpdate = newRequest("existing", null, expression, window, target);
            AwsException updateError = assertThrows(AwsException.class,
                    () -> service.updateSchedule(invalidUpdate, REGION), expression);
            assertEquals("ValidationException", updateError.getErrorCode());
            assertTrue(updateError.getMessage().startsWith("Invalid ScheduleExpression: "));
            assertEquals("rate(1 hour)", service.getSchedule("existing", null, REGION).getScheduleExpression());
        }
    }

    @Test
    void createAndUpdateRejectInvalidTargetRoleArn() {
        FlexibleTimeWindow window = new FlexibleTimeWindow("OFF", null);
        for (String roleArn : List.of("not-a-role-arn", "arn:aws:iam::123:role/r",
                "arn:aws:iam::000000000000:user/r")) {
            ScheduleRequest invalid = newRequest("invalid", null, "rate(1 hour)", window,
                    new Target("arn:t", roleArn, null, null));
            AwsException error = assertThrows(AwsException.class,
                    () -> service.createSchedule(invalid, REGION), roleArn);
            assertEquals("ValidationException", error.getErrorCode());
            assertEquals(400, error.getHttpStatus());
            assertTrue(error.getMessage().startsWith("1 validation error detected: Value '" + roleArn
                    + "' at 'target.roleArn' failed to satisfy constraint: Member must satisfy regular expression pattern: "));
            assertEquals("1 validation error detected: Value '" + roleArn
                    + "' at 'target.roleArn' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                    + "^arn:aws(-[a-z]+)?:iam::\\d{12}:role\\/[\\w+=,.@\\/-]+$", error.getMessage());
        }

        Target validTarget = new Target("arn:t", "arn:aws-us-gov:iam::000000000000:role/path/r", null, null);
        service.createSchedule(newRequest("existing", null, "rate(1 hour)", window, validTarget), REGION);
        ScheduleRequest invalidUpdate = newRequest("existing", null, "rate(1 hour)", window,
                new Target("arn:t", "not-a-role-arn", null, null));
        AwsException updateError = assertThrows(AwsException.class,
                () -> service.updateSchedule(invalidUpdate, REGION));
        assertEquals("ValidationException", updateError.getErrorCode());
        assertEquals(validTarget.getRoleArn(), service.getSchedule("existing", null, REGION).getTarget().getRoleArn());
    }

    @Test
    void createAcceptsValidAtRateAndSixFieldCron() {
        Target target = new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null);
        FlexibleTimeWindow window = new FlexibleTimeWindow("OFF", null);
        List<String> expressions = List.of("at(2026-10-01T10:00:00)", "rate(2 days)",
                "cron(0 10 ? * MON *)");
        for (int i = 0; i < expressions.size(); i++) {
            Schedule schedule = service.createSchedule(
                    newRequest("valid-" + i, null, expressions.get(i), window, target), REGION);
            assertEquals(expressions.get(i), schedule.getScheduleExpression());
        }
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
                                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleMissingFlexibleTimeWindowThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)", null,
                                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
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
                                new Target(null, "arn:aws:iam::000000000000:role/r", null, null)),
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
                                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleInvalidFlexibleTimeWindowModeThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("INVALID", null),
                                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleFlexibleMissingMaxWindowThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("FLEXIBLE", null),
                                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleOffModeWithMaxWindowThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", 10),
                                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleDeadLetterConfigMissingArnThrows() {
        Target target = new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null);
        target.setDeadLetterConfig(new DeadLetterConfig(null));
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null), target),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    // Target.Input in the Scheduler API reference: templated Lambda, Step Functions and EventBridge
    // targets require well-formed JSON; other target types accept any text, and universal
    // (aws-sdk) target input is only checked when the schedule is invoked.

    @Test
    void createScheduleRejectsNonJsonInputForLambdaTarget() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:aws:lambda:us-east-1:000000000000:function:my-func",
                                        "arn:aws:iam::000000000000:role/my-role", "not json", null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void createScheduleRejectsNonJsonInputForEventBridgeTarget() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:aws:events:us-east-1:000000000000:event-bus/my-bus",
                                        "arn:aws:iam::000000000000:role/my-role", "{\"unterminated\":", null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void updateScheduleRejectsNonJsonInputForStepFunctionsTarget() {
        String stateMachineArn = "arn:aws:states:us-east-1:000000000000:stateMachine:my-workflow";
        service.createSchedule(
                newRequest("sfn-upd", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target(stateMachineArn, "arn:aws:iam::000000000000:role/my-role", "{}", null)),
                REGION);
        AwsException e = assertThrows(AwsException.class, () ->
                service.updateSchedule(
                        newRequest("sfn-upd", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target(stateMachineArn, "arn:aws:iam::000000000000:role/my-role", "not json", null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleRejectsInputWithTrailingTokensForLambdaTarget() {
        // Without FAIL_ON_TRAILING_TOKENS, "{} garbage" parses as the leading object and the
        // rest is silently dropped.
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:aws:lambda:us-east-1:000000000000:function:my-func",
                                        "arn:aws:iam::000000000000:role/my-role", "{} garbage", null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleRejectsBlankInputForStepFunctionsTarget() {
        // Target.Input has a minimum length of 1, and readTree returns a missing node for a
        // blank value rather than failing.
        for (String blank : List.of("", " ")) {
            AwsException e = assertThrows(AwsException.class, () ->
                    service.createSchedule(
                            newRequest("s", null, "rate(1 hour)",
                                    new FlexibleTimeWindow("OFF", null),
                                    new Target("arn:aws:states:us-east-1:000000000000:stateMachine:my-workflow",
                                            "arn:aws:iam::000000000000:role/my-role", blank, null)),
                            REGION));
            assertEquals("ValidationException", e.getErrorCode());
        }
    }

    @Test
    void createScheduleAcceptsTextInputForSqsTarget() {
        Schedule s = service.createSchedule(
                newRequest("sqs-text", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:aws:sqs:us-east-1:000000000000:my-queue",
                                "arn:aws:iam::000000000000:role/my-role", "plain text", null)),
                REGION);
        assertEquals("plain text", s.getTarget().getInput());
    }

    @Test
    void createScheduleAcceptsAnyInputForUniversalTarget() {
        Schedule s = service.createSchedule(
                newRequest("universal", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:aws:scheduler:::aws-sdk:lambda:invoke",
                                "arn:aws:iam::000000000000:role/my-role", "not json", null)),
                REGION);
        assertEquals("not json", s.getTarget().getInput());
    }

    @Test
    void updateScheduleMissingRequiredFieldsThrows() {
        service.createSchedule(
                newRequest("val-upd", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                REGION);
        AwsException e = assertThrows(AwsException.class, () ->
                service.updateSchedule(
                        newRequest("val-upd", null, null,
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                        REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void createScheduleDuplicateThrows() {
        service.createSchedule(
                newRequest("dup", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                REGION);
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("dup", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                        REGION));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void createScheduleInNonExistentGroupThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.createSchedule(
                        newRequest("s", "no-such-group", "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                        REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void getSchedule() {
        service.createSchedule(
                newRequest("find-me", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
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
                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null));
        createReq.setDescription("original desc");
        service.createSchedule(createReq, REGION);

        ScheduleRequest updateReq = newRequest("upd", null, "rate(5 minutes)",
                new FlexibleTimeWindow("FLEXIBLE", 10),
                new Target("arn:t2", "arn:aws:iam::000000000000:role/r2", "{}", null));
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
    void createAndUpdatePreserveRecurringExpressionAndTimezone() {
        Target target = new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null);
        FlexibleTimeWindow window = new FlexibleTimeWindow("OFF", null);
        Schedule created = service.createSchedule(
                newRequest("recurring", null, "rate(1 day)", window, target), REGION);
        assertEquals("rate(1 day)", created.getScheduleExpression());
        assertNull(created.getScheduleExpressionTimezone());

        ScheduleRequest update = newRequest("recurring", null, "cron(30 8 * * ? *)", window, target);
        update.setScheduleExpressionTimezone("America/Los_Angeles");
        Schedule updated = service.updateSchedule(update, REGION);
        Schedule fetched = service.getSchedule("recurring", null, REGION);
        assertEquals("cron(30 8 * * ? *)", updated.getScheduleExpression());
        assertEquals("America/Los_Angeles", fetched.getScheduleExpressionTimezone());
        assertEquals(Instant.parse("2026-04-21T15:30:00Z"),
                SchedulerExpressionParser.nextCronFire(fetched.getScheduleExpression(),
                        Instant.parse("2026-04-21T00:00:00Z"),
                        fetched.getScheduleExpressionTimezone()));
    }

    @Test
    void updateScheduleNotFoundThrows() {
        AwsException e = assertThrows(AwsException.class, () ->
                service.updateSchedule(
                        newRequest("missing", null, "rate(1 hour)",
                                new FlexibleTimeWindow("OFF", null),
                                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                        REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void deleteSchedule() {
        service.createSchedule(
                newRequest("to-del", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
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
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("s2", null, "rate(2 hours)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
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
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("s-group-a", "group-a", "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
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
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("s-in-group-b", "group-b", "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
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
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("alpha-2", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                REGION);
        service.createSchedule(
                newRequest("beta-1", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null),
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                REGION);
        List<Schedule> result = service.listSchedules(null, "alpha", null, REGION);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(s -> s.getName().startsWith("alpha")));
    }

    @Test
    void listSchedulesWithStateFilter() {
        ScheduleRequest enabledReq = newRequest("enabled-1", null, "rate(1 hour)",
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null));
        enabledReq.setState("ENABLED");
        service.createSchedule(enabledReq, REGION);

        ScheduleRequest disabledReq = newRequest("disabled-1", null, "rate(1 hour)",
                new FlexibleTimeWindow("OFF", null),
                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null));
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
        Target target = new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null);
        target.setDeadLetterConfig(new DeadLetterConfig("arn:aws:sqs:us-east-1:000000000000:dlq"));
        service.createSchedule(
                newRequest("dlc-upd", null, "rate(1 hour)",
                        new FlexibleTimeWindow("OFF", null), target),
                REGION);

        Target updatedTarget = new Target("arn:t2", "arn:aws:iam::000000000000:role/r2", null, null);
        updatedTarget.setDeadLetterConfig(new DeadLetterConfig("arn:aws:sqs:us-east-1:000000000000:dlq-updated"));
        ScheduleRequest updateReq = newRequest("dlc-upd", null, "rate(5 minutes)",
                new FlexibleTimeWindow("OFF", null), updatedTarget);
        Schedule updated = service.updateSchedule(updateReq, REGION);
        assertEquals("arn:aws:sqs:us-east-1:000000000000:dlq-updated",
                updated.getTarget().getDeadLetterConfig().getArn());
    }

    @Test
    void createScheduleWithRetryPolicy() {
        Target target = new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null);
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
                new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null));
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
                        new Target("arn:t", "arn:aws:iam::000000000000:role/r", null, null)),
                "us-east-1");
        assertThrows(AwsException.class, () ->
                service.getSchedule("regional", null, "us-west-2"));
    }
}
