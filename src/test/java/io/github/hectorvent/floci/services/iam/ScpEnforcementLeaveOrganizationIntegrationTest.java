package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
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
 *
 * <p>SCP enforcement comes from {@link IamEnforcementProfile}, which this class shares with the
 * other IAM enforcement tests, so it removes the organization, OU, SCP and member account it
 * builds once the test ends.</p>
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ScpEnforcementLeaveOrganizationIntegrationTest {

    private static final String MGMT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String DENY_LEAVE_ORG_SCP = """
            {"Version":"2012-10-17","Statement":[
              {"Sid":"DenyLeaveOrg","Effect":"Deny",
               "Action":"organizations:LeaveOrganization","Resource":"*"}]}""";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @Test
    void scpDeniesMemberLeaveOrganizationButAllowsOtherActions() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // --- management sets up the organization -----------------------------------------------
        org(MGMT, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}").then().statusCode(200);
        cleanup.register(() -> org(MGMT, "DeleteOrganization", "{}").then().statusCode(200));

        // A new root has no policy types enabled; SCPs only participate once EnablePolicyType
        // turns them on. FullAWSAccess is already attached, so enabling them denies nothing alone.
        String rootId = JsonPath.from(org(MGMT, "ListRoots", "{}").asString()).getString("Roots[0].Id");
        org(MGMT, "EnablePolicyType",
                "{\"RootId\":\"" + rootId + "\",\"PolicyType\":\"SERVICE_CONTROL_POLICY\"}")
                .then().statusCode(200);

        String policyId = JsonPath.from(org(MGMT, "CreatePolicy",
                "{\"Name\":\"deny-leave-" + suffix + "\",\"Type\":\"SERVICE_CONTROL_POLICY\","
                        + "\"Description\":\"deny leave org\",\"Content\":"
                        + jsonString(DENY_LEAVE_ORG_SCP) + "}").asString())
                .getString("Policy.PolicySummary.Id");
        cleanup.register(() -> org(MGMT, "DeletePolicy", "{\"PolicyId\":\"" + policyId + "\"}")
                .then().statusCode(200));

        String ouId = JsonPath.from(org(MGMT, "CreateOrganizationalUnit",
                "{\"ParentId\":\"" + rootId + "\",\"Name\":\"Denied-" + suffix + "\"}").asString())
                .getString("OrganizationalUnit.Id");
        cleanup.register(() -> org(MGMT, "DeleteOrganizationalUnit",
                "{\"OrganizationalUnitId\":\"" + ouId + "\"}").then().statusCode(200));

        org(MGMT, "AttachPolicy",
                "{\"PolicyId\":\"" + policyId + "\",\"TargetId\":\"" + ouId + "\"}")
                .then().statusCode(200);
        cleanup.register(() -> org(MGMT, "DetachPolicy",
                "{\"PolicyId\":\"" + policyId + "\",\"TargetId\":\"" + ouId + "\"}")
                .then().statusCode(200));

        // CreateAccount is synchronous in floci: the account id is present on the returned status.
        String memberId = JsonPath.from(org(MGMT, "CreateAccount",
                "{\"AccountName\":\"member-" + suffix + "\",\"Email\":\"member-" + suffix + "@floci.test\"}")
                .asString()).getString("CreateAccountStatus.AccountId");
        cleanup.register(() -> org(MGMT, "RemoveAccountFromOrganization",
                "{\"AccountId\":\"" + memberId + "\"}").then().statusCode(200));

        org(MGMT, "MoveAccount",
                "{\"AccountId\":\"" + memberId + "\",\"SourceParentId\":\"" + rootId
                        + "\",\"DestinationParentId\":\"" + ouId + "\"}")
                .then().statusCode(200);

        // --- the member is now bounded by the OU's deny SCP ------------------------------------
        org(memberId, "LeaveOrganization", "{}")
                .then()
                .statusCode(400)
                .body(containsString("AccessDeniedException"));

        // An action the SCP does not deny must still succeed (FullAWSAccess baseline holds).
        org(memberId, "DescribeOrganization", "{}")
                .then()
                .statusCode(200);

        // A GetSessionToken session the member's root minted is bounded by the same SCP.
        String sessionKey = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + memberId + "/20260629/" + REGION
                        + "/sts/aws4_request, SignedHeaders=host, Signature=abc")
                .formParam("Action", "GetSessionToken")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().path("GetSessionTokenResponse.GetSessionTokenResult.Credentials.AccessKeyId");
        org(sessionKey, "LeaveOrganization", "{}")
                .then()
                .statusCode(400)
                .body(containsString("AccessDeniedException"));
    }

    private Response org(String account, String action, String body) {
        return given()
                .port(endpoint.getPort())
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
}
