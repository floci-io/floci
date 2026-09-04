package io.github.hectorvent.floci.services.macie2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class MacieOrganizationIntegrationTest {
    private static final String MACIE_AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/macie2/aws4_request";
    private static final String GUARDDUTY_AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/guardduty/aws4_request";

    @Test
    void macieAdminAndOrganizationConfigurationLifecycle() {
        given().header("Authorization", MACIE_AUTH).get("/admin").then().statusCode(200)
                .body("adminAccounts", hasSize(0));
        given().contentType("application/json").header("Authorization", MACIE_AUTH)
                .body("{\"adminAccountId\":\"111111111111\"}").post("/admin").then().statusCode(200);
        given().contentType("application/json").header("Authorization", MACIE_AUTH).body("{}")
                .post("/macie").then().statusCode(200);
        given().header("Authorization", MACIE_AUTH).get("/admin").then().statusCode(200)
                .body("adminAccounts[0].accountId", equalTo("111111111111"));
        given().contentType("application/json").header("Authorization", MACIE_AUTH)
                .body("{\"autoEnable\":true}").patch("/admin/configuration").then().statusCode(200);
        given().header("Authorization", MACIE_AUTH).get("/admin/configuration").then().statusCode(200)
                .body("autoEnable", equalTo(true));
    }

    @Test
    void sharedAdminRouteKeepsGuardDutyBehavior() {
        given().header("Authorization", GUARDDUTY_AUTH).get("/admin").then().statusCode(200)
                .body("adminAccounts", hasSize(0));
    }
}
