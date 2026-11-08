package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dlm.DlmClient;
import software.amazon.awssdk.services.dlm.model.CreateRule;
import software.amazon.awssdk.services.dlm.model.GettablePolicyStateValues;
import software.amazon.awssdk.services.dlm.model.IntervalUnitValues;
import software.amazon.awssdk.services.dlm.model.LifecyclePolicy;
import software.amazon.awssdk.services.dlm.model.PolicyDetails;
import software.amazon.awssdk.services.dlm.model.PolicyTypeValues;
import software.amazon.awssdk.services.dlm.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dlm.model.ResourceTypeValues;
import software.amazon.awssdk.services.dlm.model.RetainRule;
import software.amazon.awssdk.services.dlm.model.Schedule;
import software.amazon.awssdk.services.dlm.model.SettablePolicyStateValues;
import software.amazon.awssdk.services.dlm.model.Tag;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Amazon Data Lifecycle Manager")
class DlmTest {

    private static DlmClient dlm;
    private static String policyId;
    private static String policyArn;

    @BeforeAll
    static void setup() {
        dlm = TestFixtures.dlmClient();
    }

    @AfterAll
    static void cleanup() {
        if (dlm == null) {
            return;
        }
        if (policyId != null) {
            try {
                dlm.deleteLifecyclePolicy(r -> r.policyId(policyId));
            } catch (RuntimeException e) {
                System.out.println("DLM cleanup skipped the lifecycle policy: " + e);
            }
        }
        dlm.close();
    }

    @Test
    void policyLifecycleRoundTripsNestedDetailsTimestampsFiltersAndTags() {
        PolicyDetails details = PolicyDetails.builder()
                .policyType(PolicyTypeValues.EBS_SNAPSHOT_MANAGEMENT)
                .resourceTypes(ResourceTypeValues.VOLUME)
                .targetTags(Tag.builder().key("backup").value("true").build())
                .schedules(Schedule.builder()
                        .name("daily")
                        .createRule(CreateRule.builder()
                                .interval(24)
                                .intervalUnit(IntervalUnitValues.HOURS)
                                .build())
                        .retainRule(RetainRule.builder().count(7).build())
                        .tagsToAdd(Tag.builder().key("managed").value("dlm").build())
                        .build())
                .build();

        policyId = dlm.createLifecyclePolicy(r -> r
                .executionRoleArn("arn:aws:iam::000000000000:role/dlm")
                .description("daily")
                .state(SettablePolicyStateValues.ENABLED)
                .policyDetails(details)
                .tags(Map.of("env", "test")))
                .policyId();

        LifecyclePolicy created = dlm.getLifecyclePolicy(r -> r.policyId(policyId)).policy();
        policyArn = created.policyArn();

        assertThat(created.description()).isEqualTo("daily");
        assertThat(created.dateCreated()).isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(1));
        assertThat(created.policyDetails().resourceTypes()).containsExactly(ResourceTypeValues.VOLUME);
        assertThat(created.policyDetails().schedules().get(0).createRule().interval()).isEqualTo(24);
        assertThat(created.tags()).containsEntry("env", "test");

        assertThat(dlm.getLifecyclePolicies(r -> r
                .state(GettablePolicyStateValues.ENABLED)
                .resourceTypes(ResourceTypeValues.VOLUME)
                .targetTags("backup=true")
                .tagsToAdd("managed=dlm"))
                .policies())
                .extracting(summary -> summary.policyId())
                .contains(policyId);

        dlm.updateLifecyclePolicy(r -> r
                .policyId(policyId)
                .description("weekly")
                .state(SettablePolicyStateValues.DISABLED));
        LifecyclePolicy updated = dlm.getLifecyclePolicy(r -> r.policyId(policyId)).policy();
        assertThat(updated.description()).isEqualTo("weekly");
        assertThat(updated.state()).isEqualTo(GettablePolicyStateValues.DISABLED);
        assertThat(updated.policyDetails().schedules().get(0).createRule().interval()).isEqualTo(24);

        dlm.tagResource(r -> r.resourceArn(policyArn).tags(Map.of("owner", "platform")));
        assertThat(dlm.listTagsForResource(r -> r.resourceArn(policyArn)).tags())
                .containsEntry("env", "test")
                .containsEntry("owner", "platform");

        dlm.untagResource(r -> r.resourceArn(policyArn).tagKeys("env"));
        assertThat(dlm.listTagsForResource(r -> r.resourceArn(policyArn)).tags())
                .doesNotContainKey("env")
                .containsEntry("owner", "platform");

        dlm.deleteLifecyclePolicy(r -> r.policyId(policyId));
        assertThatThrownBy(() -> dlm.getLifecyclePolicy(r -> r.policyId(policyId)))
                .isInstanceOf(ResourceNotFoundException.class);
        policyId = null;
    }
}
