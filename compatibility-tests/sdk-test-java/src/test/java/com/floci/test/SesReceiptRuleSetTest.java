package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.AlreadyExistsException;
import software.amazon.awssdk.services.ses.model.CannotDeleteException;
import software.amazon.awssdk.services.ses.model.DescribeActiveReceiptRuleSetResponse;
import software.amazon.awssdk.services.ses.model.DescribeReceiptRuleResponse;
import software.amazon.awssdk.services.ses.model.DescribeReceiptRuleSetResponse;
import software.amazon.awssdk.services.ses.model.InvalidSnsTopicException;
import software.amazon.awssdk.services.ses.model.ListReceiptRuleSetsResponse;
import software.amazon.awssdk.services.ses.model.ReceiptRule;
import software.amazon.awssdk.services.ses.model.RuleDoesNotExistException;
import software.amazon.awssdk.services.ses.model.RuleSetDoesNotExistException;
import software.amazon.awssdk.services.ses.model.TlsPolicy;
import software.amazon.awssdk.services.sns.SnsClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES V1 receipt-rule-set and receipt-rule actions, the surface
 * Terraform's {@code aws_ses_receipt_rule_set} / {@code aws_ses_active_receipt_rule_set} /
 * {@code aws_ses_receipt_rule} drive. Rule sets and rules are stored without routing any mail,
 * but AWS error mapping, ordering semantics (front insert, After placement,
 * SetReceiptRulePosition), full-replace update, idempotent deletes, and local validation of
 * action targets all follow behavior probed against real AWS.
 */
@DisplayName("SES V1 ReceiptRuleSet and ReceiptRule")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesReceiptRuleSetTest {

    private static SesClient ses;
    private static SnsClient sns;
    private static String ruleSet;
    private static String topicArn;

    @BeforeAll
    static void setup() {
        ses = TestFixtures.sesClient();
        sns = TestFixtures.snsClient();
        ruleSet = "sdk-v1-rule-set-" + TestFixtures.uniqueName();
        topicArn = sns.createTopic(b -> b.name("sdk-v1-receipt-rule-topic")).topicArn();
    }

    @AfterAll
    static void cleanup() {
        if (ses != null) {
            try {
                ses.setActiveReceiptRuleSet(b -> { });
            } catch (RuntimeException ignored) {
                // best-effort: clearing the active rule set
            }
            try {
                ses.deleteReceiptRuleSet(b -> b.ruleSetName(ruleSet));
            } catch (RuntimeException ignored) {
                // best-effort: removing the throwaway rule set (rules cascade with it)
            }
            ses.close();
        }
        if (sns != null) {
            try {
                sns.deleteTopic(b -> b.topicArn(topicArn));
            } catch (RuntimeException ignored) {
                // best-effort: removing the throwaway topic
            }
            sns.close();
        }
    }

    @Test
    @Order(1)
    void createAndDescribe() {
        ses.createReceiptRuleSet(b -> b.ruleSetName(ruleSet));
        DescribeReceiptRuleSetResponse d = ses.describeReceiptRuleSet(b -> b.ruleSetName(ruleSet));
        assertThat(d.metadata().name()).isEqualTo(ruleSet);
        assertThat(d.metadata().createdTimestamp()).isNotNull();
        assertThat(d.rules()).isEmpty(); // no rules yet
    }

    @Test
    @Order(2)
    void createDuplicate_throwsAlreadyExists() {
        assertThatThrownBy(() -> ses.createReceiptRuleSet(b -> b.ruleSetName(ruleSet)))
                .isInstanceOf(AlreadyExistsException.class);
    }

    @Test
    @Order(3)
    void list_containsRuleSet() {
        ListReceiptRuleSetsResponse r = ses.listReceiptRuleSets(b -> { });
        assertThat(r.ruleSets()).anyMatch(m -> ruleSet.equals(m.name()));
    }

    @Test
    @Order(4)
    void createRuleAndDescribe_roundTripsActions() {
        ses.createReceiptRule(b -> b.ruleSetName(ruleSet).rule(r -> r
                .name("rich")
                .enabled(true)
                .tlsPolicy(TlsPolicy.REQUIRE)
                .scanEnabled(true)
                .recipients("probe@example.com", "other@example.org")
                .actions(
                        a -> a.addHeaderAction(h -> h.headerName("X-Floci").headerValue("v1")),
                        a -> a.snsAction(s -> s.topicArn(topicArn)),
                        a -> a.stopAction(s -> s.scope("RuleSet")))));

        DescribeReceiptRuleResponse described = ses.describeReceiptRule(
                b -> b.ruleSetName(ruleSet).ruleName("rich"));
        ReceiptRule rule = described.rule();
        assertThat(rule.enabled()).isTrue();
        assertThat(rule.tlsPolicy()).isEqualTo(TlsPolicy.REQUIRE);
        assertThat(rule.scanEnabled()).isTrue();
        // Recipients come back sorted; actions keep their submitted order and the SNS action
        // picks up the UTF-8 encoding default (probed against real AWS).
        assertThat(rule.recipients()).containsExactly("other@example.org", "probe@example.com");
        assertThat(rule.actions()).hasSize(3);
        assertThat(rule.actions().get(0).addHeaderAction().headerName()).isEqualTo("X-Floci");
        assertThat(rule.actions().get(1).snsAction().encodingAsString()).isEqualTo("UTF-8");
        assertThat(rule.actions().get(2).stopAction().scopeAsString()).isEqualTo("RuleSet");
    }

    @Test
    @Order(5)
    void createRuleOrdering_frontInsertAndAfter() {
        ses.createReceiptRule(b -> b.ruleSetName(ruleSet).rule(r -> r.name("first")));
        ses.createReceiptRule(b -> b.ruleSetName(ruleSet).rule(r -> r.name("middle")).after("first"));

        DescribeReceiptRuleSetResponse described = ses.describeReceiptRuleSet(
                b -> b.ruleSetName(ruleSet));
        assertThat(described.rules()).extracting(ReceiptRule::name)
                .containsExactly("first", "middle", "rich");
    }

    @Test
    @Order(6)
    void duplicateRuleCreate_andUnknownRules_mapToTypedExceptions() {
        assertThatThrownBy(() -> ses.createReceiptRule(
                b -> b.ruleSetName(ruleSet).rule(r -> r.name("rich"))))
                .isInstanceOf(AlreadyExistsException.class)
                .hasMessageContaining("Rule already exists: rich");

        assertThatThrownBy(() -> ses.describeReceiptRule(
                b -> b.ruleSetName(ruleSet).ruleName("ghost")))
                .isInstanceOf(RuleDoesNotExistException.class)
                .hasMessageContaining("Rule does not exist: ghost");

        assertThatThrownBy(() -> ses.createReceiptRule(
                b -> b.ruleSetName("sdk-v1-nope").rule(r -> r.name("r"))))
                .isInstanceOf(RuleSetDoesNotExistException.class);
    }

    @Test
    @Order(7)
    void ruleWithMissingTopic_failsWithInvalidSnsTopic() {
        assertThatThrownBy(() -> ses.createReceiptRule(b -> b.ruleSetName(ruleSet).rule(r -> r
                .name("snsneg")
                .actions(a -> a.snsAction(s -> s.topicArn(
                        "arn:aws:sns:us-east-1:000000000000:sdk-v1-no-such-topic"))))))
                .isInstanceOf(InvalidSnsTopicException.class)
                .hasMessageContaining("Could not publish to SNS topic");
    }

    @Test
    @Order(8)
    void updateRule_isFullReplace_andSetPositionMovesToFront() {
        ses.updateReceiptRule(b -> b.ruleSetName(ruleSet).rule(r -> r.name("rich")));

        ReceiptRule updated = ses.describeReceiptRule(
                b -> b.ruleSetName(ruleSet).ruleName("rich")).rule();
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.tlsPolicy()).isEqualTo(TlsPolicy.OPTIONAL);
        assertThat(updated.actions()).isEmpty();
        assertThat(updated.recipients()).isEmpty();

        ses.setReceiptRulePosition(b -> b.ruleSetName(ruleSet).ruleName("rich"));
        assertThat(ses.describeReceiptRuleSet(b -> b.ruleSetName(ruleSet)).rules())
                .extracting(ReceiptRule::name)
                .containsExactly("rich", "first", "middle");
    }

    @Test
    @Order(9)
    void deleteRule_isIdempotent() {
        ses.deleteReceiptRule(b -> b.ruleSetName(ruleSet).ruleName("middle"));
        ses.deleteReceiptRule(b -> b.ruleSetName(ruleSet).ruleName("middle"));
        assertThat(ses.describeReceiptRuleSet(b -> b.ruleSetName(ruleSet)).rules())
                .extracting(ReceiptRule::name)
                .containsExactly("rich", "first");
    }

    @Test
    @Order(10)
    void setActive_thenDescribeActive() {
        ses.setActiveReceiptRuleSet(b -> b.ruleSetName(ruleSet));
        DescribeActiveReceiptRuleSetResponse a = ses.describeActiveReceiptRuleSet(b -> { });
        assertThat(a.metadata()).isNotNull();
        assertThat(a.metadata().name()).isEqualTo(ruleSet);
        // The active describe renders the set's remaining rules too.
        assertThat(a.rules()).extracting(ReceiptRule::name).containsExactly("rich", "first");

        // The active rule set can't be deleted (matches AWS) — a caller must unset it first.
        assertThatThrownBy(() -> ses.deleteReceiptRuleSet(b -> b.ruleSetName(ruleSet)))
                .isInstanceOf(CannotDeleteException.class);
    }

    @Test
    @Order(11)
    void describeNonExistent_throwsRuleSetDoesNotExist() {
        assertThatThrownBy(() -> ses.describeReceiptRuleSet(b -> b.ruleSetName("sdk-v1-nope")))
                .isInstanceOf(RuleSetDoesNotExistException.class);
    }

    @Test
    @Order(12)
    void unsetActive_leavesNoActiveRuleSet() {
        ses.setActiveReceiptRuleSet(b -> { }); // no name clears the active rule set
        DescribeActiveReceiptRuleSetResponse a = ses.describeActiveReceiptRuleSet(b -> { });
        assertThat(a.metadata()).isNull();
    }

    @Test
    @Order(13)
    void delete_isIdempotent_andCascadesRemainingRules() {
        ses.deleteReceiptRuleSet(b -> b.ruleSetName(ruleSet));
        // Deleting an already-absent rule set still succeeds (matches AWS).
        ses.deleteReceiptRuleSet(b -> b.ruleSetName(ruleSet));
        assertThatThrownBy(() -> ses.describeReceiptRuleSet(b -> b.ruleSetName(ruleSet)))
                .isInstanceOf(RuleSetDoesNotExistException.class);
    }
}
