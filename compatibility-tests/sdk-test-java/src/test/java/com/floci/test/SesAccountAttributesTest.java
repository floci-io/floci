package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.GetAccountSendingEnabledResponse;
import software.amazon.awssdk.services.ses.model.UpdateAccountSendingEnabledRequest;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AccountDetails;
import software.amazon.awssdk.services.sesv2.model.ContactLanguage;
import software.amazon.awssdk.services.sesv2.model.DashboardAttributes;
import software.amazon.awssdk.services.sesv2.model.FeatureStatus;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.GuardianAttributes;
import software.amazon.awssdk.services.sesv2.model.MailType;
import software.amazon.awssdk.services.sesv2.model.PutAccountDetailsRequest;
import software.amazon.awssdk.services.sesv2.model.PutAccountSendingAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.PutAccountSuppressionAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.PutAccountVdmAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.SuppressionListReason;
import software.amazon.awssdk.services.sesv2.model.VdmAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SES account attributes: sending, suppression, VDM (v1 + v2)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesAccountAttributesTest {

    private static SesClient sesV1;
    private static SesV2Client sesV2;
    private static List<SuppressionListReason> originalReasons;
    private static VdmAttributes originalVdm;
    private static Boolean originalSendingEnabled;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        // Capture the caller's existing account-level settings so cleanup can restore them — the
        // suite can run against real AWS and must not leave the caller's account attributes changed.
        GetAccountResponse account = sesV2.getAccount(GetAccountRequest.builder().build());
        originalReasons = account.suppressionAttributes().suppressedReasons();
        originalVdm = account.vdmAttributes();
        originalSendingEnabled = account.sendingEnabled();
    }

    @AfterAll
    static void cleanup() {
        // Attempt each restoration independently so one failure can't leave the other account
        // settings modified, then propagate an aggregated failure — the tests mutate account-level
        // state, so a silent failure would leave a real AWS caller's settings changed while the
        // suite reports green.
        List<Throwable> failures = new ArrayList<>();
        restoreQuietly(failures, SesAccountAttributesTest::restoreVdmAttributes);
        restoreQuietly(failures, SesAccountAttributesTest::restoreSuppression);
        restoreQuietly(failures, SesAccountAttributesTest::restoreSending);
        if (sesV1 != null) {
            sesV1.close();
        }
        if (sesV2 != null) {
            sesV2.close();
        }
        if (!failures.isEmpty()) {
            RuntimeException aggregate = new RuntimeException("account attribute cleanup failed");
            failures.forEach(aggregate::addSuppressed);
            throw aggregate;
        }
    }

    private static void restoreQuietly(List<Throwable> failures, Runnable restore) {
        try {
            restore.run();
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }

    private static void restoreVdmAttributes() {
        if (sesV2 == null) {
            return;
        }
        // VDM has no un-configure API, so a region that started unconfigured (no VdmAttributes in
        // GetAccount) can only be reset to DISABLED once the round-trip test has enabled it.
        VdmAttributes.Builder vdm = VdmAttributes.builder();
        if (originalVdm != null && originalVdm.vdmEnabled() != null) {
            vdm.vdmEnabled(originalVdm.vdmEnabled())
                    .dashboardAttributes(originalVdm.dashboardAttributes())
                    .guardianAttributes(originalVdm.guardianAttributes());
        } else {
            vdm.vdmEnabled(FeatureStatus.DISABLED);
        }
        sesV2.putAccountVdmAttributes(PutAccountVdmAttributesRequest.builder()
                .vdmAttributes(vdm.build())
                .build());
    }

    private static void restoreSuppression() {
        if (sesV2 == null || originalReasons == null) {
            return;
        }
        sesV2.putAccountSuppressionAttributes(PutAccountSuppressionAttributesRequest.builder()
                .suppressedReasons(originalReasons)
                .build());
    }

    private static void restoreSending() {
        if (sesV1 == null || originalSendingEnabled == null) {
            return;
        }
        sesV1.updateAccountSendingEnabled(UpdateAccountSendingEnabledRequest.builder()
                .enabled(originalSendingEnabled).build());
    }

    @Test
    @Order(1)
    void putAndGet_suppressedReasonsRoundTrip() {
        sesV2.putAccountSuppressionAttributes(PutAccountSuppressionAttributesRequest.builder()
                .suppressedReasons(SuppressionListReason.BOUNCE, SuppressionListReason.COMPLAINT)
                .build());

        GetAccountResponse account = sesV2.getAccount(GetAccountRequest.builder().build());
        assertThat(account.suppressionAttributes()).isNotNull();
        assertThat(account.suppressionAttributes().suppressedReasons())
                .containsExactlyInAnyOrder(SuppressionListReason.BOUNCE, SuppressionListReason.COMPLAINT);
    }

    @Test
    @Order(2)
    void put_explicitEmptyList_clearsReasons() {
        sesV2.putAccountSuppressionAttributes(PutAccountSuppressionAttributesRequest.builder()
                .suppressedReasons(Collections.emptyList())
                .build());

        GetAccountResponse account = sesV2.getAccount(GetAccountRequest.builder().build());
        assertThat(account.suppressionAttributes().suppressedReasons()).isEmpty();
    }

    @Test
    @Order(3)
    void putAndGet_vdmAttributesRoundTrip() {
        sesV2.putAccountVdmAttributes(PutAccountVdmAttributesRequest.builder()
                .vdmAttributes(VdmAttributes.builder()
                        .vdmEnabled(FeatureStatus.ENABLED)
                        .dashboardAttributes(DashboardAttributes.builder()
                                .engagementMetrics(FeatureStatus.ENABLED).build())
                        .guardianAttributes(GuardianAttributes.builder()
                                .optimizedSharedDelivery(FeatureStatus.ENABLED).build())
                        .build())
                .build());

        GetAccountResponse account = sesV2.getAccount(GetAccountRequest.builder().build());
        assertThat(account.vdmAttributes()).isNotNull();
        assertThat(account.vdmAttributes().vdmEnabled()).isEqualTo(FeatureStatus.ENABLED);
        assertThat(account.vdmAttributes().dashboardAttributes().engagementMetrics())
                .isEqualTo(FeatureStatus.ENABLED);
        assertThat(account.vdmAttributes().guardianAttributes().optimizedSharedDelivery())
                .isEqualTo(FeatureStatus.ENABLED);
    }

    @Test
    @Order(4)
    void v1UpdateAccountSendingEnabled_disablesAndReenables() {
        sesV1.updateAccountSendingEnabled(UpdateAccountSendingEnabledRequest.builder()
                .enabled(false).build());

        GetAccountSendingEnabledResponse disabled = sesV1.getAccountSendingEnabled();
        assertThat(disabled.enabled()).isFalse();

        sesV1.updateAccountSendingEnabled(UpdateAccountSendingEnabledRequest.builder()
                .enabled(true).build());

        GetAccountSendingEnabledResponse enabled = sesV1.getAccountSendingEnabled();
        assertThat(enabled.enabled()).isTrue();
    }

    @Test
    @Order(5)
    void v1AndV2_shareAccountSendingState() {
        // Disable via v1, observe via v2 GetAccount
        sesV1.updateAccountSendingEnabled(UpdateAccountSendingEnabledRequest.builder()
                .enabled(false).build());

        GetAccountResponse afterV1Disable = sesV2.getAccount(GetAccountRequest.builder().build());
        assertThat(afterV1Disable.sendingEnabled()).isFalse();

        // Re-enable via v2, observe via v1 GetAccountSendingEnabled
        sesV2.putAccountSendingAttributes(PutAccountSendingAttributesRequest.builder()
                .sendingEnabled(true).build());

        GetAccountSendingEnabledResponse afterV2Enable = sesV1.getAccountSendingEnabled();
        assertThat(afterV2Enable.enabled()).isTrue();
    }

    @Test
    @Order(6)
    void putAndGet_accountDetailsRoundTrip() {
        // PutAccountDetails is POST (unlike the sibling PUT account operations); the SDK exercises the
        // real verb, so a mis-wired route would surface here. Status is set by AWS's review and is not
        // controllable, so assert only that ReviewDetails is present with a status and case id.
        //
        // PutAccountDetails has no delete/reset API and AWS owns the resulting ReviewDetails/case, so
        // this mutation cannot be undone — re-submitting captured fields cannot restore the prior
        // review status or case id. Skip it entirely on real AWS rather than risk changing a real
        // caller's account; against Floci the in-memory store makes the round-trip safe and it still
        // catches a mis-wired route there.
        org.junit.jupiter.api.Assumptions.assumeFalse(TestFixtures.isRealAws(),
                "skipping on real AWS: PutAccountDetails cannot be reset and AWS owns ReviewDetails");
        sesV2.putAccountDetails(PutAccountDetailsRequest.builder()
                .mailType(MailType.TRANSACTIONAL)
                .websiteURL("https://example.com")
                .contactLanguage(ContactLanguage.EN)
                .useCaseDescription("compatibility test")
                .additionalContactEmailAddresses("ops@example.com")
                .build());

        GetAccountResponse account = sesV2.getAccount(GetAccountRequest.builder().build());
        AccountDetails details = account.details();
        assertThat(details).isNotNull();
        assertThat(details.mailType()).isEqualTo(MailType.TRANSACTIONAL);
        assertThat(details.websiteURL()).isEqualTo("https://example.com");
        assertThat(details.contactLanguage()).isEqualTo(ContactLanguage.EN);
        assertThat(details.additionalContactEmailAddresses()).contains("ops@example.com");
        assertThat(details.reviewDetails()).isNotNull();
        assertThat(details.reviewDetails().statusAsString()).isNotBlank();
        assertThat(details.reviewDetails().caseId()).isNotBlank();
    }

    @Test
    @Order(7)
    void putAccountDetails_invalidMailType_throwsBadRequest() {
        // Server-side enum validation (the string bypasses the SDK's typed enum), rejected without
        // mutating account state.
        assertThatThrownBy(() -> sesV2.putAccountDetails(PutAccountDetailsRequest.builder()
                        .mailType("SPAM")
                        .websiteURL("https://example.com")
                        .build()))
                .isInstanceOf(SesV2Exception.class);
    }
}
