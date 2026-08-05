package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.DeleteIdentityPolicyRequest;
import software.amazon.awssdk.services.ses.model.GetIdentityPoliciesRequest;
import software.amazon.awssdk.services.ses.model.ListIdentityPoliciesRequest;
import software.amazon.awssdk.services.ses.model.PutIdentityPolicyRequest;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.AlreadyExistsException;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityPolicyRequest;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityPoliciesRequest;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.UpdateEmailIdentityPolicyRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES identity-policy (sending authorization) APIs
 * across both protocols against a live Floci instance: the AWS Java SDK v2
 * Create/Get/Update/DeleteEmailIdentityPolicy and the v1 Put/Get/List/DeleteIdentityPolicy,
 * including the v2 create-duplicate / update-missing errors and the shared store
 * (a v1-written policy is visible via the v2 Get).
 */
@DisplayName("SES identity policies (v1 + v2)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesIdentityPolicyTest {

    private static final String IDENTITY = "sdk-policy.example.com";
    private static final String DOC = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";

    private static SesClient sesV1;
    private static SesV2Client sesV2;

    @BeforeAll
    static void setup() {
        sesV1 = TestFixtures.sesClient();
        sesV2 = TestFixtures.sesV2Client();
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(IDENTITY).build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 != null) {
            try {
                // Best-effort cleanup: deleting the identity also drops its policies. Ignore errors
                // (e.g. the identity was never created) so they don't mask the actual test result.
                sesV2.deleteEmailIdentity(DeleteEmailIdentityRequest.builder().emailIdentity(IDENTITY).build());
            } catch (Exception ignored) { }
            sesV2.close();
        }
        if (sesV1 != null) {
            sesV1.close();
        }
    }

    @Test
    @Order(1)
    void v2_createThenGet() {
        sesV2.createEmailIdentityPolicy(CreateEmailIdentityPolicyRequest.builder()
                .emailIdentity(IDENTITY).policyName("p1").policy(DOC).build());

        assertThat(sesV2.getEmailIdentityPolicies(GetEmailIdentityPoliciesRequest.builder()
                .emailIdentity(IDENTITY).build()).policies())
                .containsKey("p1");
    }

    @Test
    @Order(2)
    void v2_createDuplicate_throwsAlreadyExists() {
        assertThatThrownBy(() -> sesV2.createEmailIdentityPolicy(CreateEmailIdentityPolicyRequest.builder()
                .emailIdentity(IDENTITY).policyName("p1").policy(DOC).build()))
                .isInstanceOf(AlreadyExistsException.class)
                .hasMessageContaining("Policy <p1> already exists");
    }

    @Test
    @Order(3)
    void v2_updateThenGet() {
        String updated = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"U\"}]}";
        sesV2.updateEmailIdentityPolicy(UpdateEmailIdentityPolicyRequest.builder()
                .emailIdentity(IDENTITY).policyName("p1").policy(updated).build());

        assertThat(sesV2.getEmailIdentityPolicies(GetEmailIdentityPoliciesRequest.builder()
                .emailIdentity(IDENTITY).build()).policies().get("p1"))
                .contains("\"Sid\":\"U\"");
    }

    @Test
    @Order(4)
    void v2_updateMissing_throwsNotFound() {
        assertThatThrownBy(() -> sesV2.updateEmailIdentityPolicy(UpdateEmailIdentityPolicyRequest.builder()
                .emailIdentity(IDENTITY).policyName("nope").policy(DOC).build()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Policy <nope> does not exist");
    }

    @Test
    @Order(5)
    void v1_putUpsert_listGet() {
        sesV1.putIdentityPolicy(PutIdentityPolicyRequest.builder()
                .identity(IDENTITY).policyName("v1p").policy(DOC).build());
        // Upsert: same name again with no error.
        sesV1.putIdentityPolicy(PutIdentityPolicyRequest.builder()
                .identity(IDENTITY).policyName("v1p").policy(DOC).build());

        assertThat(sesV1.listIdentityPolicies(ListIdentityPoliciesRequest.builder()
                .identity(IDENTITY).build()).policyNames()).contains("v1p");
        assertThat(sesV1.getIdentityPolicies(GetIdentityPoliciesRequest.builder()
                .identity(IDENTITY).policyNames("v1p").build()).policies()).containsKey("v1p");
    }

    @Test
    @Order(6)
    void v1PolicyVisibleViaV2_andV2VisibleViaV1() {
        // v1-written policy shows up in the v2 Get (shared store).
        assertThat(sesV2.getEmailIdentityPolicies(GetEmailIdentityPoliciesRequest.builder()
                .emailIdentity(IDENTITY).build()).policies()).containsKeys("p1", "v1p");
        // v2-written policy shows up in the v1 List.
        assertThat(sesV1.listIdentityPolicies(ListIdentityPoliciesRequest.builder()
                .identity(IDENTITY).build()).policyNames()).contains("p1", "v1p");
    }

    @Test
    @Order(7)
    void v1_deleteIsIdempotent() {
        sesV1.deleteIdentityPolicy(DeleteIdentityPolicyRequest.builder()
                .identity(IDENTITY).policyName("v1p").build());
        // Deleting again does not throw on v1.
        sesV1.deleteIdentityPolicy(DeleteIdentityPolicyRequest.builder()
                .identity(IDENTITY).policyName("v1p").build());

        assertThat(sesV1.listIdentityPolicies(ListIdentityPoliciesRequest.builder()
                .identity(IDENTITY).build()).policyNames()).doesNotContain("v1p");
    }
}
