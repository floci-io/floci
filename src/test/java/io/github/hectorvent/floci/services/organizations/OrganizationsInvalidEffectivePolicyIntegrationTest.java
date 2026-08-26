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

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private RequestSpecification organizations(String action, String body) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + MANAGEMENT_ACCOUNT
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
            .body("Accounts", empty());
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
}
