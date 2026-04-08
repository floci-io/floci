package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.*;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EventBridge Scheduler")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchedulerTest {

    private static SchedulerClient scheduler;
    private static final String GROUP_NAME = "test-schedule-group";
    private static final String SCHEDULE_NAME = "test-schedule";

    @BeforeAll
    static void setup() {
        scheduler = TestFixtures.schedulerClient();
    }

    @AfterAll
    static void cleanup() {
        if (scheduler != null) {
            try {
                scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                        .name(SCHEDULE_NAME).build());
            } catch (Exception ignored) {}
            try {
                scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                        .name("dlc-schedule").build());
            } catch (Exception ignored) {}
            try {
                scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                        .name("rp-schedule").build());
            } catch (Exception ignored) {}
            try {
                scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                        .name("dated-schedule").build());
            } catch (Exception ignored) {}
            try {
                scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                        .name("grouped-schedule").groupName(GROUP_NAME).build());
            } catch (Exception ignored) {}
            try {
                scheduler.deleteScheduleGroup(DeleteScheduleGroupRequest.builder()
                        .name(GROUP_NAME).build());
            } catch (Exception ignored) {}
            scheduler.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("CreateScheduleGroup - create group")
    void createScheduleGroup() {
        CreateScheduleGroupResponse resp = scheduler.createScheduleGroup(
                CreateScheduleGroupRequest.builder()
                        .name(GROUP_NAME)
                        .build());

        assertThat(resp.scheduleGroupArn()).isNotNull().contains(GROUP_NAME);
    }

    @Test
    @Order(2)
    @DisplayName("GetScheduleGroup - get created group")
    void getScheduleGroup() {
        GetScheduleGroupResponse resp = scheduler.getScheduleGroup(
                GetScheduleGroupRequest.builder()
                        .name(GROUP_NAME)
                        .build());

        assertThat(resp.name()).isEqualTo(GROUP_NAME);
        assertThat(resp.arn()).isNotNull().contains(GROUP_NAME);
        assertThat(resp.state()).isEqualTo(ScheduleGroupState.ACTIVE);
        assertThat(resp.creationDate()).isNotNull();
    }

    @Test
    @Order(3)
    @DisplayName("ListScheduleGroups - created group is present")
    void listScheduleGroupsContainsGroup() {
        ListScheduleGroupsResponse resp = scheduler.listScheduleGroups(
                ListScheduleGroupsRequest.builder().build());

        boolean found = resp.scheduleGroups().stream()
                .anyMatch(g -> GROUP_NAME.equals(g.name()));
        assertThat(found).isTrue();
    }

    @Test
    @Order(4)
    @DisplayName("ListScheduleGroups - namePrefix filter works")
    void listScheduleGroupsNamePrefixFilter() {
        ListScheduleGroupsResponse resp = scheduler.listScheduleGroups(
                ListScheduleGroupsRequest.builder()
                        .namePrefix("test-schedule")
                        .build());

        assertThat(resp.scheduleGroups()).isNotEmpty();
        assertThat(resp.scheduleGroups()).allMatch(g -> g.name().startsWith("test-schedule"));
    }

    @Test
    @Order(5)
    @DisplayName("CreateScheduleGroup - duplicate returns ConflictException")
    void createScheduleGroupDuplicate() {
        assertThatThrownBy(() -> scheduler.createScheduleGroup(
                CreateScheduleGroupRequest.builder()
                        .name(GROUP_NAME)
                        .build()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @Order(6)
    @DisplayName("GetScheduleGroup - non-existent returns ResourceNotFoundException")
    void getScheduleGroupNotFound() {
        assertThatThrownBy(() -> scheduler.getScheduleGroup(
                GetScheduleGroupRequest.builder()
                        .name("does-not-exist-group")
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ──────────────────────────── Schedule CRUD ────────────────────────────

    @Test
    @Order(10)
    @DisplayName("CreateSchedule - create in default group")
    void createSchedule() {
        CreateScheduleResponse resp = scheduler.createSchedule(CreateScheduleRequest.builder()
                .name(SCHEDULE_NAME)
                .scheduleExpression("rate(1 hour)")
                .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                .target(Target.builder()
                        .arn("arn:aws:lambda:us-east-1:000000000000:function:my-func")
                        .roleArn("arn:aws:iam::000000000000:role/scheduler-role")
                        .build())
                .build());

        assertThat(resp.scheduleArn()).isNotNull().contains(SCHEDULE_NAME);
    }

    @Test
    @Order(11)
    @DisplayName("CreateSchedule - create in custom group")
    void createScheduleInGroup() {
        // Re-create the group (was tested above but may have been deleted)
        try {
            scheduler.createScheduleGroup(CreateScheduleGroupRequest.builder()
                    .name(GROUP_NAME).build());
        } catch (ConflictException ignored) {}

        CreateScheduleResponse resp = scheduler.createSchedule(CreateScheduleRequest.builder()
                .name("grouped-schedule")
                .groupName(GROUP_NAME)
                .scheduleExpression("rate(5 minutes)")
                .flexibleTimeWindow(FlexibleTimeWindow.builder()
                        .mode(FlexibleTimeWindowMode.FLEXIBLE)
                        .maximumWindowInMinutes(10)
                        .build())
                .target(Target.builder()
                        .arn("arn:aws:sqs:us-east-1:000000000000:my-queue")
                        .roleArn("arn:aws:iam::000000000000:role/r")
                        .input("{\"key\":\"value\"}")
                        .build())
                .state(ScheduleState.DISABLED)
                .description("test schedule in group")
                .build());

        assertThat(resp.scheduleArn()).isNotNull().contains(GROUP_NAME);
    }

    @Test
    @Order(12)
    @DisplayName("CreateSchedule - duplicate returns ConflictException")
    void createScheduleDuplicate() {
        assertThatThrownBy(() -> scheduler.createSchedule(CreateScheduleRequest.builder()
                .name(SCHEDULE_NAME)
                .scheduleExpression("rate(1 hour)")
                .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                .target(Target.builder().arn("arn:t").roleArn("arn:r").build())
                .build()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @Order(13)
    @DisplayName("GetSchedule - get created schedule")
    void getSchedule() {
        GetScheduleResponse resp = scheduler.getSchedule(GetScheduleRequest.builder()
                .name(SCHEDULE_NAME)
                .build());

        assertThat(resp.name()).isEqualTo(SCHEDULE_NAME);
        assertThat(resp.groupName()).isEqualTo("default");
        assertThat(resp.state()).isEqualTo(ScheduleState.ENABLED);
        assertThat(resp.scheduleExpression()).isEqualTo("rate(1 hour)");
        assertThat(resp.flexibleTimeWindow().mode()).isEqualTo(FlexibleTimeWindowMode.OFF);
        assertThat(resp.target().arn()).contains("function:my-func");
        assertThat(resp.creationDate()).isNotNull();
        assertThat(resp.lastModificationDate()).isNotNull();
    }

    @Test
    @Order(14)
    @DisplayName("GetSchedule - in custom group")
    void getScheduleInGroup() {
        GetScheduleResponse resp = scheduler.getSchedule(GetScheduleRequest.builder()
                .name("grouped-schedule")
                .groupName(GROUP_NAME)
                .build());

        assertThat(resp.name()).isEqualTo("grouped-schedule");
        assertThat(resp.groupName()).isEqualTo(GROUP_NAME);
        assertThat(resp.state()).isEqualTo(ScheduleState.DISABLED);
        assertThat(resp.description()).isEqualTo("test schedule in group");
    }

    @Test
    @Order(15)
    @DisplayName("GetSchedule - non-existent returns ResourceNotFoundException")
    void getScheduleNotFound() {
        assertThatThrownBy(() -> scheduler.getSchedule(GetScheduleRequest.builder()
                .name("does-not-exist-schedule")
                .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(16)
    @DisplayName("ListSchedules - no group returns all schedules across groups")
    void listSchedules() {
        ListSchedulesResponse resp = scheduler.listSchedules(ListSchedulesRequest.builder().build());

        boolean foundDefault = resp.schedules().stream()
                .anyMatch(s -> SCHEDULE_NAME.equals(s.name()));
        assertThat(foundDefault).isTrue();
        boolean foundGrouped = resp.schedules().stream()
                .anyMatch(s -> "grouped-schedule".equals(s.name()));
        assertThat(foundGrouped).isTrue();
    }

    @Test
    @Order(17)
    @DisplayName("ListSchedules - filter by group")
    void listSchedulesInGroup() {
        ListSchedulesResponse resp = scheduler.listSchedules(ListSchedulesRequest.builder()
                .groupName(GROUP_NAME)
                .build());

        assertThat(resp.schedules()).isNotEmpty();
        boolean found = resp.schedules().stream()
                .anyMatch(s -> "grouped-schedule".equals(s.name()));
        assertThat(found).isTrue();
        // Should NOT contain schedules from default group
        boolean hasDefault = resp.schedules().stream()
                .anyMatch(s -> SCHEDULE_NAME.equals(s.name()));
        assertThat(hasDefault).isFalse();
    }

    @Test
    @Order(18)
    @DisplayName("CreateSchedule - with DeadLetterConfig")
    void createScheduleWithDeadLetterConfig() {
        CreateScheduleResponse resp = scheduler.createSchedule(CreateScheduleRequest.builder()
                .name("dlc-schedule")
                .scheduleExpression("rate(10 minutes)")
                .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                .target(Target.builder()
                        .arn("arn:aws:lambda:us-east-1:000000000000:function:dlc-func")
                        .roleArn("arn:aws:iam::000000000000:role/r")
                        .deadLetterConfig(DeadLetterConfig.builder()
                                .arn("arn:aws:sqs:us-east-1:000000000000:my-dlq")
                                .build())
                        .build())
                .build());

        assertThat(resp.scheduleArn()).isNotNull().contains("dlc-schedule");

        GetScheduleResponse get = scheduler.getSchedule(GetScheduleRequest.builder()
                .name("dlc-schedule").build());
        assertThat(get.target().deadLetterConfig()).isNotNull();
        assertThat(get.target().deadLetterConfig().arn())
                .isEqualTo("arn:aws:sqs:us-east-1:000000000000:my-dlq");

        // Cleanup
        scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                .name("dlc-schedule").build());
    }

    @Test
    @Order(19)
    @DisplayName("CreateSchedule - with RetryPolicy")
    void createScheduleWithRetryPolicy() {
        CreateScheduleResponse resp = scheduler.createSchedule(CreateScheduleRequest.builder()
                .name("rp-schedule")
                .scheduleExpression("rate(10 minutes)")
                .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                .target(Target.builder()
                        .arn("arn:aws:lambda:us-east-1:000000000000:function:rp-func")
                        .roleArn("arn:aws:iam::000000000000:role/r")
                        .retryPolicy(software.amazon.awssdk.services.scheduler.model.RetryPolicy.builder()
                                .maximumEventAgeInSeconds(3600)
                                .maximumRetryAttempts(5)
                                .build())
                        .build())
                .build());

        assertThat(resp.scheduleArn()).isNotNull().contains("rp-schedule");

        GetScheduleResponse get = scheduler.getSchedule(GetScheduleRequest.builder()
                .name("rp-schedule").build());
        assertThat(get.target().retryPolicy()).isNotNull();
        assertThat(get.target().retryPolicy().maximumEventAgeInSeconds()).isEqualTo(3600);
        assertThat(get.target().retryPolicy().maximumRetryAttempts()).isEqualTo(5);

        // Cleanup
        scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                .name("rp-schedule").build());
    }

    @Test
    @Order(20)
    @DisplayName("CreateSchedule - with StartDate and EndDate")
    void createScheduleWithStartAndEndDate() {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-12-31T23:59:59Z");

        CreateScheduleResponse resp = scheduler.createSchedule(CreateScheduleRequest.builder()
                .name("dated-schedule")
                .scheduleExpression("rate(1 hour)")
                .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                .target(Target.builder()
                        .arn("arn:aws:lambda:us-east-1:000000000000:function:dated-func")
                        .roleArn("arn:aws:iam::000000000000:role/r")
                        .build())
                .startDate(start)
                .endDate(end)
                .build());

        assertThat(resp.scheduleArn()).isNotNull().contains("dated-schedule");

        GetScheduleResponse get = scheduler.getSchedule(GetScheduleRequest.builder()
                .name("dated-schedule").build());
        assertThat(get.startDate()).isNotNull();
        assertThat(get.startDate().getEpochSecond()).isEqualTo(start.getEpochSecond());
        assertThat(get.endDate()).isNotNull();
        assertThat(get.endDate().getEpochSecond()).isEqualTo(end.getEpochSecond());

        // Cleanup
        scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                .name("dated-schedule").build());
    }

    @Test
    @Order(21)
    @DisplayName("UpdateSchedule - update expression and state")
    void updateSchedule() {
        UpdateScheduleResponse resp = scheduler.updateSchedule(UpdateScheduleRequest.builder()
                .name(SCHEDULE_NAME)
                .scheduleExpression("rate(30 minutes)")
                .flexibleTimeWindow(FlexibleTimeWindow.builder()
                        .mode(FlexibleTimeWindowMode.FLEXIBLE)
                        .maximumWindowInMinutes(5)
                        .build())
                .target(Target.builder()
                        .arn("arn:aws:lambda:us-east-1:000000000000:function:updated-func")
                        .roleArn("arn:aws:iam::000000000000:role/updated-role")
                        .build())
                .state(ScheduleState.DISABLED)
                .description("updated description")
                .build());

        assertThat(resp.scheduleArn()).isNotNull().contains(SCHEDULE_NAME);

        // Verify the update
        GetScheduleResponse get = scheduler.getSchedule(GetScheduleRequest.builder()
                .name(SCHEDULE_NAME).build());
        assertThat(get.scheduleExpression()).isEqualTo("rate(30 minutes)");
        assertThat(get.state()).isEqualTo(ScheduleState.DISABLED);
        assertThat(get.description()).isEqualTo("updated description");
        assertThat(get.flexibleTimeWindow().mode()).isEqualTo(FlexibleTimeWindowMode.FLEXIBLE);
        assertThat(get.flexibleTimeWindow().maximumWindowInMinutes()).isEqualTo(5);
    }

    @Test
    @Order(22)
    @DisplayName("UpdateSchedule - non-existent returns ResourceNotFoundException")
    void updateScheduleNotFound() {
        assertThatThrownBy(() -> scheduler.updateSchedule(UpdateScheduleRequest.builder()
                .name("does-not-exist-schedule")
                .scheduleExpression("rate(1 hour)")
                .flexibleTimeWindow(FlexibleTimeWindow.builder().mode(FlexibleTimeWindowMode.OFF).build())
                .target(Target.builder().arn("arn:t").roleArn("arn:r").build())
                .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(23)
    @DisplayName("DeleteSchedule - delete schedule")
    void deleteSchedule() {
        scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                .name(SCHEDULE_NAME)
                .build());

        assertThatThrownBy(() -> scheduler.getSchedule(GetScheduleRequest.builder()
                .name(SCHEDULE_NAME).build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(24)
    @DisplayName("DeleteSchedule - non-existent returns ResourceNotFoundException")
    void deleteScheduleNotFound() {
        assertThatThrownBy(() -> scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                .name("does-not-exist-schedule")
                .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(25)
    @DisplayName("DeleteSchedule - delete schedule in group")
    void deleteScheduleInGroup() {
        scheduler.deleteSchedule(DeleteScheduleRequest.builder()
                .name("grouped-schedule")
                .groupName(GROUP_NAME)
                .build());

        assertThatThrownBy(() -> scheduler.getSchedule(GetScheduleRequest.builder()
                .name("grouped-schedule")
                .groupName(GROUP_NAME)
                .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(26)
    @DisplayName("DeleteScheduleGroup - cleanup test group")
    void deleteScheduleGroupCleanup() {
        scheduler.deleteScheduleGroup(DeleteScheduleGroupRequest.builder()
                .name(GROUP_NAME)
                .build());

        assertThatThrownBy(() -> scheduler.getScheduleGroup(
                GetScheduleGroupRequest.builder()
                        .name(GROUP_NAME)
                        .build()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
