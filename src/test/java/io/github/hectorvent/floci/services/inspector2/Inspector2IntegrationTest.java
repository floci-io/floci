package io.github.hectorvent.floci.services.inspector2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class Inspector2IntegrationTest {
    private static final String MANAGEMENT_AUTH = auth("222222222222");
    private static final String ADMIN_AUTH = auth("111111111111");

    @Test
    void delegatedAdminEnablementAndOrganizationConfiguration() {
        given().contentType("application/json").header("Authorization", MANAGEMENT_AUTH).body("{}")
                .post("/delegatedadminaccounts/list").then().statusCode(200);
        given().contentType("application/json").header("Authorization", MANAGEMENT_AUTH)
                .body("{\"delegatedAdminAccountId\":\"111111111111\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(200)
                .body("delegatedAdminAccountId", equalTo("111111111111"));
        given().contentType("application/json").header("Authorization", ADMIN_AUTH)
                .body("{\"autoEnable\":{\"ec2\":true,\"ecr\":true,\"lambda\":true,\"lambdaCode\":true,\"codeRepository\":true}}")
                .post("/organizationconfiguration/update").then().statusCode(200)
                .body("autoEnable.codeRepository", equalTo(true));
        given().contentType("application/json").header("Authorization", ADMIN_AUTH).body("{}")
                .post("/organizationconfiguration/describe").then().statusCode(200)
                .body("autoEnable.ec2", equalTo(true))
                .body("autoEnable.ecr", equalTo(true))
                .body("autoEnable.lambda", equalTo(true))
                .body("autoEnable.lambdaCode", equalTo(true))
                .body("autoEnable.codeRepository", equalTo(true));
    }

    @Test
    void batchStatusAllowsOmittedAccountsForCaller() {
        given().contentType("application/json").header("Authorization", ADMIN_AUTH)
                .body("{\"resourceTypes\":[\"EC2\",\"CODE_REPOSITORY\"]}")
                .post("/enable").then().statusCode(200);
        given().contentType("application/json").header("Authorization", ADMIN_AUTH).body("{}")
                .post("/status/batch/get").then().statusCode(200)
                .body("accounts", hasSize(1))
                .body("accounts[0].accountId", equalTo("111111111111"));
    }

    @Test
    void delegatedAdminCanBeDisabledByManagementAccount() {
        given().contentType("application/json").header("Authorization", MANAGEMENT_AUTH)
                .body("{\"delegatedAdminAccountId\":\"111111111111\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(200);
        given().contentType("application/json").header("Authorization", MANAGEMENT_AUTH)
                .body("{\"delegatedAdminAccountId\":\"111111111111\"}")
                .post("/delegatedadminaccounts/disable").then().statusCode(200)
                .body("delegatedAdminAccountId", equalTo("111111111111"));
    }

    @Test
    void organizationConfigurationRejectsNonAdministrator() {
        given().contentType("application/json").header("Authorization", MANAGEMENT_AUTH)
                .body("{\"delegatedAdminAccountId\":\"111111111111\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(200);
        given().contentType("application/json").header("Authorization", MANAGEMENT_AUTH)
                .body("{\"autoEnable\":{\"ec2\":true,\"ecr\":true}}")
                .post("/organizationconfiguration/update").then().statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void invalidAccountIdReturnsValidationException() {
        given().contentType("application/json").header("Authorization", MANAGEMENT_AUTH)
                .body("{\"delegatedAdminAccountId\":\"bad\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260101/us-east-1/inspector2/aws4_request";
    }
}
