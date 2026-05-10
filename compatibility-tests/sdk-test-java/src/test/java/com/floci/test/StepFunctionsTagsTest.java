package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for Step Functions tag operations:
 * ListTagsForResource, TagResource, UntagResource on state machines and activities.
 *
 * Covers issue #766: Terraform AWS provider v5+ calls ListTagsForResource
 * unconditionally on every read of aws_sfn_state_machine.
 */
@DisplayName("SFN Tag Operations")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StepFunctionsTagsTest {

    private static final String ROLE_ARN = System.getenv("SFN_ROLE_ARN") != null
            ? System.getenv("SFN_ROLE_ARN")
            : "arn:aws:iam::000000000000:role/service-role/test-role";

    private static final String SIMPLE_DEFINITION = """
            {"Comment":"tag test","StartAt":"Done","States":{"Done":{"Type":"Pass","End":true}}}
            """.strip();

    private static SfnClient sfn;
    private static String stateMachineArn;
    private static String activityArn;

    @BeforeAll
    static void setup() {
        sfn = TestFixtures.sfnClient();
        String smName = TestFixtures.uniqueName("tags-sm");
        stateMachineArn = sfn.createStateMachine(b -> b
                .name(smName)
                .definition(SIMPLE_DEFINITION)
                .roleArn(ROLE_ARN)).stateMachineArn();

        String actName = TestFixtures.uniqueName("tags-activity");
        activityArn = sfn.createActivity(b -> b.name(actName)).activityArn();
    }

    @AfterAll
    static void cleanup() {
        if (sfn != null) {
            if (stateMachineArn != null) {
                try { sfn.deleteStateMachine(b -> b.stateMachineArn(stateMachineArn)); } catch (Exception ignored) {}
            }
            if (activityArn != null) {
                try { sfn.deleteActivity(b -> b.activityArn(activityArn)); } catch (Exception ignored) {}
            }
            sfn.close();
        }
    }

    // ──────────────── State Machine Tags ────────────────

    @Test
    @Order(1)
    void listTags_newStateMachine_returnsEmptyMap() {
        ListTagsForResourceResponse resp = sfn.listTagsForResource(b -> b.resourceArn(stateMachineArn));
        assertThat(resp.tags()).isEmpty();
    }

    @Test
    @Order(2)
    void tagResource_stateMachine_addsTagsAndListReturnsAll() {
        sfn.tagResource(b -> b
                .resourceArn(stateMachineArn)
                .tags(Map.of("env", "test", "team", "platform")));

        ListTagsForResourceResponse resp = sfn.listTagsForResource(b -> b.resourceArn(stateMachineArn));
        assertThat(resp.tags())
                .containsEntry("env", "test")
                .containsEntry("team", "platform");
    }

    @Test
    @Order(3)
    void untagResource_stateMachine_removesOnlySpecifiedKeys() {
        sfn.untagResource(b -> b
                .resourceArn(stateMachineArn)
                .tagKeys("team"));

        ListTagsForResourceResponse resp = sfn.listTagsForResource(b -> b.resourceArn(stateMachineArn));
        assertThat(resp.tags())
                .containsEntry("env", "test")
                .doesNotContainKey("team");
    }

    @Test
    @Order(4)
    void tagResource_stateMachine_overwritesExistingKey() {
        sfn.tagResource(b -> b
                .resourceArn(stateMachineArn)
                .tags(Map.of("env", "prod")));

        ListTagsForResourceResponse resp = sfn.listTagsForResource(b -> b.resourceArn(stateMachineArn));
        assertThat(resp.tags()).containsEntry("env", "prod");
    }

    // ──────────────── Activity Tags ────────────────

    @Test
    @Order(5)
    void listTags_newActivity_returnsEmptyMap() {
        ListTagsForResourceResponse resp = sfn.listTagsForResource(b -> b.resourceArn(activityArn));
        assertThat(resp.tags()).isEmpty();
    }

    @Test
    @Order(6)
    void tagResource_activity_addsTagsAndListReturnsAll() {
        sfn.tagResource(b -> b
                .resourceArn(activityArn)
                .tags(Map.of("owner", "infra")));

        ListTagsForResourceResponse resp = sfn.listTagsForResource(b -> b.resourceArn(activityArn));
        assertThat(resp.tags()).containsEntry("owner", "infra");
    }

    @Test
    @Order(7)
    void untagResource_activity_removesSpecifiedKey() {
        sfn.untagResource(b -> b
                .resourceArn(activityArn)
                .tagKeys("owner"));

        ListTagsForResourceResponse resp = sfn.listTagsForResource(b -> b.resourceArn(activityArn));
        assertThat(resp.tags()).doesNotContainKey("owner");
    }
}
