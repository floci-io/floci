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
import static org.hamcrest.Matchers.hasItem;

/**
 * The CONSOLIDATED_BILLING → ALL upgrade path, which gates whether policy types can be enabled
 * at all. Uses its own management account so it never contends with the other Organizations
 * integration tests inside the shared Quarkus instance.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationsFeatureSetIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AWSOrganizationsV20161128.";
    private static final String MANAGEMENT_ACCOUNT = "555555555555";

    private String rootId;

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
    void consolidatedBillingOrganizationHasNoAvailablePolicyTypes() {
        organizations("CreateOrganization", "{\"FeatureSet\":\"CONSOLIDATED_BILLING\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Organization.FeatureSet", equalTo("CONSOLIDATED_BILLING"))
            .body("Organization.AvailablePolicyTypes", empty());

        rootId = organizations("ListRoots", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Roots[0].PolicyTypes", empty())
            .extract().jsonPath().getString("Roots[0].Id");
    }

    @Test
    @Order(2)
    void policyTypesCannotBeEnabledWithoutAllFeatures() {
        organizations("EnablePolicyType",
                "{\"RootId\":\"" + rootId + "\",\"PolicyType\":\"SERVICE_CONTROL_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("PolicyTypeNotAvailableForOrganizationException"));
    }

    @Test
    @Order(3)
    void enableAllFeaturesCompletesImmediatelyWithNoMemberAccounts() {
        organizations("EnableAllFeatures", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Handshake.Action", equalTo("ENABLE_ALL_FEATURES"))
            .body("Handshake.State", equalTo("ACCEPTED"));

        organizations("DescribeOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Organization.FeatureSet", equalTo("ALL"))
            .body("Organization.AvailablePolicyTypes.Type", hasItem("SERVICE_CONTROL_POLICY"));
    }

    @Test
    @Order(4)
    void enableAllFeaturesIsRejectedOnceAlreadyEnabled() {
        organizations("EnableAllFeatures", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("HandshakeConstraintViolationException"));
    }

    @Test
    @Order(5)
    void policyTypesCanBeEnabledAfterUpgrading() {
        organizations("EnablePolicyType", "{\"RootId\":\"" + rootId + "\",\"PolicyType\":\"BACKUP_POLICY\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Root.PolicyTypes.Type", hasItem("BACKUP_POLICY"));
    }

    @Test
    @Order(6)
    void tearDownOrganization() {
        organizations("DeleteOrganization", "{}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
