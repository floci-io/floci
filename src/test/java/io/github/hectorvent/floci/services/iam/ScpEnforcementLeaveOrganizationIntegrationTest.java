package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.config;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end proof that a service control policy actually denies a member account's action once
 * both IAM enforcement and SCP enforcement are on.
 *
 * <p>floci's account-root principal is a bare 12-digit account-id access key. That principal has no
 * registered IAM identity, so before the {@link IamEnforcementFilter} account-root fix it slipped
 * past enforcement entirely — a {@code DenyLeaveOrganization} SCP attached to a workload OU did not
 * stop the member from leaving. This test drives the full Organizations control plane over HTTP
 * (management sets up org + OU + deny-SCP + member) and asserts that the member's
 * {@code LeaveOrganization} is denied with 403 while a non-denied action still succeeds.</p>
 */
@QuarkusTest
@TestProfile(ScpEnforcementLeaveOrganizationIntegrationTest.ScpEnforcementProfile.class)
class ScpEnforcementLeaveOrganizationIntegrationTest {

    private static final String MGMT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String DENY_LEAVE_ORG_SCP = """
            {"Version":"2012-10-17","Statement":[
              {"Sid":"DenyLeaveOrg","Effect":"Deny",
               "Action":"organizations:LeaveOrganization","Resource":"*"}]}""";

    @Test
    void scpDeniesMemberLeaveOrganizationButAllowsOtherActions() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // --- management sets up the organization -----------------------------------------------
        org(MGMT, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}").then().statusCode(200);

        // FeatureSet=ALL already enables the SERVICE_CONTROL_POLICY type on the root and seeds
        // FullAWSAccess, so no explicit EnablePolicyType is needed for SCPs to participate.
        String rootId = JsonPath.from(org(MGMT, "ListRoots", "{}").asString()).getString("Roots[0].Id");

        String policyId = JsonPath.from(org(MGMT, "CreatePolicy",
                "{\"Name\":\"deny-leave-" + suffix + "\",\"Type\":\"SERVICE_CONTROL_POLICY\","
                        + "\"Description\":\"deny leave org\",\"Content\":"
                        + jsonString(DENY_LEAVE_ORG_SCP) + "}").asString())
                .getString("Policy.PolicySummary.Id");

        String ouId = JsonPath.from(org(MGMT, "CreateOrganizationalUnit",
                "{\"ParentId\":\"" + rootId + "\",\"Name\":\"Denied-" + suffix + "\"}").asString())
                .getString("OrganizationalUnit.Id");

        org(MGMT, "AttachPolicy",
                "{\"PolicyId\":\"" + policyId + "\",\"TargetId\":\"" + ouId + "\"}")
                .then().statusCode(200);

        // CreateAccount is synchronous in floci: the account id is present on the returned status.
        String memberId = JsonPath.from(org(MGMT, "CreateAccount",
                "{\"AccountName\":\"member-" + suffix + "\",\"Email\":\"member-" + suffix + "@floci.test\"}")
                .asString()).getString("CreateAccountStatus.AccountId");

        org(MGMT, "MoveAccount",
                "{\"AccountId\":\"" + memberId + "\",\"SourceParentId\":\"" + rootId
                        + "\",\"DestinationParentId\":\"" + ouId + "\"}")
                .then().statusCode(200);

        // --- the member is now bounded by the OU's deny SCP ------------------------------------
        org(memberId, "LeaveOrganization", "{}")
                .then()
                .statusCode(403)
                .body(containsString("AccessDeniedException"));

        // An action the SCP does not deny must still succeed (FullAWSAccess baseline holds).
        org(memberId, "DescribeOrganization", "{}")
                .then()
                .statusCode(200);
    }

    private static Response org(String account, String action, String body) {
        return given()
                .config(config().encoderConfig(EncoderConfig.encoderConfig()
                        .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.JSON)))
                .header("Authorization", auth(account))
                .header("X-Amz-Target", "AWSOrganizationsV20161128." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when()
                .post("/");
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260629/" + REGION
                + "/organizations/aws4_request, SignedHeaders=host, Signature=abc";
    }

    /** Serializes {@code value} as a JSON string literal (quoted, escaped). */
    private static String jsonString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static final class ScpEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iam.enforcement-enabled", "true",
                    "floci.services.organizations.scp-enforcement-enabled", "true");
        }
    }
}
