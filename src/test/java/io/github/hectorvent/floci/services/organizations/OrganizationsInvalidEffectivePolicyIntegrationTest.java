package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

/**
 * ListAccountsWithInvalidEffectivePolicy takes a required PolicyType drawn from the
 * EffectivePolicyType enum. Uses its own management account so it never contends with the
 * other Organizations integration tests inside the shared Quarkus instance.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationsInvalidEffectivePolicyIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AWSOrganizationsV20161128.";
    private static final String MANAGEMENT_ACCOUNT = "666666666666";

    private String memberAccountId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private RequestSpecification organizations(String action, String body) {
        return organizations(MANAGEMENT_ACCOUNT, action, body);
    }

    private RequestSpecification organizations(String accountId, String action, String body) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accountId
                        + "/20260822/us-east-1/organizations/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .contentType(CONTENT_TYPE)
                .body(body);
    }

    @Test
    @Order(1)
    void createOrganization() {
        organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void listAccountsWithInvalidEffectivePolicyAcceptsAModelledPolicyType() {
        organizations("ListAccountsWithInvalidEffectivePolicy", "{\"PolicyType\":\"TAG_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accounts", empty())
            // PolicyType is echoed back per the model's response shape - with Accounts
            // always empty by design, it's the only field this response can inform on.
            .body("PolicyType", equalTo("TAG_POLICY"));
    }

    /**
     * The enum gained these after floci first rendered it; AWS accepts them, so the
     * check must follow the current model rather than the older shorter list.
     */
    @Test
    @Order(3)
    void listAccountsWithInvalidEffectivePolicyAcceptsTheNewerPolicyTypes() {
        for (String policyType : new String[] {
                "INSPECTOR_POLICY", "UPGRADE_ROLLOUT_POLICY", "BEDROCK_POLICY",
                "S3_POLICY", "NETWORK_SECURITY_DIRECTOR_POLICY"}) {
            organizations("ListAccountsWithInvalidEffectivePolicy",
                    "{\"PolicyType\":\"" + policyType + "\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("Accounts", empty());
        }
    }

    @Test
    @Order(4)
    void listAccountsWithInvalidEffectivePolicyRejectsAnUnmodelledPolicyType() {
        organizations("ListAccountsWithInvalidEffectivePolicy", "{\"PolicyType\":\"NOT_A_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }

    /**
     * SERVICE_CONTROL_POLICY is a valid policy type but not an effective-policy type, so it
     * must be rejected here the same way DescribeEffectivePolicy rejects it.
     */
    @Test
    @Order(5)
    void listAccountsWithInvalidEffectivePolicyRejectsAnAccessControlPolicyType() {
        organizations("ListAccountsWithInvalidEffectivePolicy",
                "{\"PolicyType\":\"SERVICE_CONTROL_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    @Order(6)
    void listAccountsWithInvalidEffectivePolicyRequiresPolicyType() {
        organizations("ListAccountsWithInvalidEffectivePolicy", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    @Order(7)
    void createMemberAccount() {
        var response = organizations("CreateAccount", "{\"Email\":\"member@example.com\",\"AccountName\":\"Member\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateAccountStatus.AccountId", matchesPattern("\\d{12}"))
            .extract().jsonPath();

        memberAccountId = response.getString("CreateAccountStatus.AccountId");
    }

    /**
     * AWS restricts this operation to the management account or a delegated administrator — unlike
     * DescribeEffectivePolicy, which "you can call ... from any account in a organization" per the
     * botocore model. An ordinary member must not get a reassuring empty list.
     */
    @Test
    @Order(8)
    void listAccountsWithInvalidEffectivePolicyRejectsAnOrdinaryMemberAccount() {
        organizations(memberAccountId, "ListAccountsWithInvalidEffectivePolicy", "{\"PolicyType\":\"TAG_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    @Order(9)
    void registerMemberAsDelegatedAdministrator() {
        organizations(MANAGEMENT_ACCOUNT, "RegisterDelegatedAdministrator",
                "{\"AccountId\":\"" + memberAccountId + "\",\"ServicePrincipal\":\"config.amazonaws.com\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    /**
     * The model names no service that a delegated administrator must be scoped to for this
     * operation, and EffectivePolicyType maps to none — there is nothing to check a registration
     * against. Rather than accept delegation for an unrelated service (config.amazonaws.com here),
     * this stays management-only, matching every other operation gated on this boilerplate phrase.
     */
    @Test
    @Order(10)
    void listAccountsWithInvalidEffectivePolicyRejectsADelegatedAdministratorForAnUnrelatedService() {
        organizations(memberAccountId, "ListAccountsWithInvalidEffectivePolicy", "{\"PolicyType\":\"TAG_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(403)
            .body("__type", equalTo("AccessDeniedException"));
    }
}
