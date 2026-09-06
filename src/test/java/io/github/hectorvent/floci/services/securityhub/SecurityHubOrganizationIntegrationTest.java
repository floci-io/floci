package io.github.hectorvent.floci.services.securityhub;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class SecurityHubOrganizationIntegrationTest {
    private static final String MANAGEMENT_AUTH = "AWS4-HMAC-SHA256 Credential=222222222222/20260101/us-east-1/securityhub/aws4_request";
    private static final String ADMIN_AUTH = "AWS4-HMAC-SHA256 Credential=111111111111/20260101/us-east-1/securityhub/aws4_request";

    @Test
    void organizationAndConfigurationPolicyLifecycle() {
        given().header("Authorization", MANAGEMENT_AUTH).get("/organization/admin").then().statusCode(200)
                .body("AdminAccounts", hasSize(0));
        given().contentType("application/json").header("Authorization", MANAGEMENT_AUTH)
                .body("{\"AdminAccountId\":\"111111111111\"}").post("/organization/admin/enable")
                .then().statusCode(200)
                .body("AdminAccountId", equalTo("111111111111"))
                .body("Feature", equalTo("SecurityHub"));
        given().header("Authorization", ADMIN_AUTH).get("/accounts").then().statusCode(200)
                .body("HubArn", notNullValue());
        String aggregatorArn = given().contentType("application/json").header("Authorization", ADMIN_AUTH)
                .body("{\"RegionLinkingMode\":\"ALL_REGIONS\"}").post("/findingAggregator/create")
                .then().statusCode(200).extract().path("FindingAggregatorArn");
        given().header("Authorization", ADMIN_AUTH).get("/findingAggregator/get/" + aggregatorArn)
                .then().statusCode(200).body("RegionLinkingMode", equalTo("ALL_REGIONS"));
        given().contentType("application/json").header("Authorization", ADMIN_AUTH)
                .body("{\"OrganizationConfiguration\":{\"ConfigurationType\":\"CENTRAL\"},\"AutoEnable\":false,\"AutoEnableStandards\":\"NONE\"}")
                .post("/organization/configuration").then().statusCode(200);
        given().header("Authorization", ADMIN_AUTH).get("/organization/configuration").then().statusCode(200)
                .body("OrganizationConfiguration.ConfigurationType", equalTo("CENTRAL"));
        String policyId = given().contentType("application/json").header("Authorization", ADMIN_AUTH)
                .body("{\"Name\":\"security-policy\",\"ConfigurationPolicy\":{\"SecurityHub\":{\"ServiceEnabled\":true}}}")
                .post("/configurationPolicy/create").then().statusCode(200).extract().path("Id");
        given().header("Authorization", ADMIN_AUTH).get("/configurationPolicy/get/" + policyId)
                .then().statusCode(200).body("Id", equalTo(policyId));
    }

    @Test
    void invalidAdminAccountIdReturnsValidationException() {
        given().contentType("application/json").header("Authorization", MANAGEMENT_AUTH)
                .body("{\"AdminAccountId\":\"bad\"}").post("/organization/admin/enable")
                .then().statusCode(400).body("__type", equalTo("InvalidInputException"));
    }
}
