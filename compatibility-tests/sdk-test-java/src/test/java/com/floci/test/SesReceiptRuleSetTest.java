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
import software.amazon.awssdk.services.ses.model.DescribeReceiptRuleSetResponse;
import software.amazon.awssdk.services.ses.model.ListReceiptRuleSetsResponse;
import software.amazon.awssdk.services.ses.model.RuleSetDoesNotExistException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES V1 receipt-rule-set actions. Floci stores rule sets inertly
 * (no rules, no mail routing), but the management API must still round-trip through the real AWS SDK
 * client — the same surface Terraform's {@code aws_ses_receipt_rule_set} /
 * {@code aws_ses_active_receipt_rule_set} drive — including AWS error mapping and idempotent delete.
 */
@DisplayName("SES V1 ReceiptRuleSet")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesReceiptRuleSetTest {

    private static SesClient ses;
    private static String ruleSet;

    @BeforeAll
    static void setup() {
        ses = TestFixtures.sesClient();
        ruleSet = "sdk-v1-rule-set-" + TestFixtures.uniqueName();
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
                // best-effort: removing the throwaway rule set
            }
            ses.close();
        }
    }

    @Test
    @Order(1)
    void createAndDescribe() {
        ses.createReceiptRuleSet(b -> b.ruleSetName(ruleSet));
        DescribeReceiptRuleSetResponse d = ses.describeReceiptRuleSet(b -> b.ruleSetName(ruleSet));
        assertThat(d.metadata().name()).isEqualTo(ruleSet);
        assertThat(d.metadata().createdTimestamp()).isNotNull();
        assertThat(d.rules()).isEmpty(); // stored-but-inert: never holds rules
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
    void setActive_thenDescribeActive() {
        ses.setActiveReceiptRuleSet(b -> b.ruleSetName(ruleSet));
        DescribeActiveReceiptRuleSetResponse a = ses.describeActiveReceiptRuleSet(b -> { });
        assertThat(a.metadata()).isNotNull();
        assertThat(a.metadata().name()).isEqualTo(ruleSet);

        // The active rule set can't be deleted (matches AWS) — a caller must unset it first.
        assertThatThrownBy(() -> ses.deleteReceiptRuleSet(b -> b.ruleSetName(ruleSet)))
                .isInstanceOf(CannotDeleteException.class);
    }

    @Test
    @Order(5)
    void describeNonExistent_throwsRuleSetDoesNotExist() {
        assertThatThrownBy(() -> ses.describeReceiptRuleSet(b -> b.ruleSetName("sdk-v1-nope")))
                .isInstanceOf(RuleSetDoesNotExistException.class);
    }

    @Test
    @Order(6)
    void unsetActive_leavesNoActiveRuleSet() {
        ses.setActiveReceiptRuleSet(b -> { }); // no name clears the active rule set
        DescribeActiveReceiptRuleSetResponse a = ses.describeActiveReceiptRuleSet(b -> { });
        assertThat(a.metadata()).isNull();
    }

    @Test
    @Order(7)
    void delete_isIdempotent() {
        ses.deleteReceiptRuleSet(b -> b.ruleSetName(ruleSet));
        // Deleting an already-absent rule set still succeeds (matches AWS).
        ses.deleteReceiptRuleSet(b -> b.ruleSetName(ruleSet));
        assertThatThrownBy(() -> ses.describeReceiptRuleSet(b -> b.ruleSetName(ruleSet)))
                .isInstanceOf(RuleSetDoesNotExistException.class);
    }
}
