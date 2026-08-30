package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Verifies the IAM GetAccountSummary query action returns entity counts (which change as
 * resources are created) alongside static quota values. IAM state is shared across tests in
 * this suite, so counts are asserted via before/after deltas rather than absolute values.
 */
@QuarkusTest
class IamGetAccountSummaryIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private long summaryValue(String key) {
        return Long.parseLong(
                given()
                    .formParam("Action", "GetAccountSummary")
                    .header("Authorization", IAM_AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(200)
                    .contentType("application/xml")
                    .extract()
                    .path("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                            + ".find { it.key == '" + key + "' }.value"));
    }

    @Test
    void countsIncreaseAsEntitiesAreCreated() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String userName = "gas-user-" + suffix;
        String policyName = "gas-policy-" + suffix;

        long usersBefore = summaryValue("Users");
        long policiesBefore = summaryValue("Policies");

        given()
            .formParam("Action", "CreateUser")
            .formParam("UserName", userName)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "CreatePolicy")
            .formParam("PolicyName", policyName)
            .formParam("PolicyDocument",
                    "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}")
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        assertSummaryValue("Users", usersBefore + 1);
        assertSummaryValue("Policies", policiesBefore + 1);
    }

    private void assertSummaryValue(String key, long expected) {
        given()
            .formParam("Action", "GetAccountSummary")
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == '" + key + "' }.value", equalTo(String.valueOf(expected)));
    }

    @Test
    void reportsAllThirtyFourDocumentedFields() {
        // docs.aws.amazon.com/IAM/latest/APIReference/API_GetAccountSummary.html lists exactly
        // 34 valid SummaryMap keys - a caller indexing into any of them should never KeyError.
        given()
            .formParam("Action", "GetAccountSummary")
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry.size()",
                    equalTo(34));
    }

    @Test
    void reportsStaticQuotaValues() {
        given()
            .formParam("Action", "GetAccountSummary")
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            // VersionsPerPolicyQuota mirrors the 5-versions-per-policy cap enforced in
            // IamService#createPolicyVersion - not just a display value.
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'VersionsPerPolicyQuota' }.value", equalTo("5"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'UsersQuota' }.value", equalTo("5000"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'GroupsQuota' }.value", equalTo("300"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'RolesQuota' }.value", equalTo("1000"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'PoliciesQuota' }.value", equalTo("1500"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'InstanceProfilesQuota' }.value", equalTo("1000"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'AttachedPoliciesPerUserQuota' }.value", equalTo("10"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'AttachedPoliciesPerGroupQuota' }.value", equalTo("10"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'AttachedPoliciesPerRoleQuota' }.value", equalTo("20"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'PolicySizeQuota' }.value", equalTo("6144"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'AssumeRolePolicySizeQuota' }.value", equalTo("2048"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'AccountPasswordPresent' }.value", equalTo("0"))
            .body("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == 'MFADevices' }.value", equalTo("0"));
    }

    @Test
    void userAccessKeysDoNotSetRootAccountAccessKeySignal() {
        String userName = "gas-key-user-" + Long.toString(System.nanoTime(), 36);

        given()
            .formParam("Action", "CreateUser")
            .formParam("UserName", userName)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "CreateAccessKey")
            .formParam("UserName", userName)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        assertSummaryValue("AccountAccessKeysPresent", 0);
    }
}
