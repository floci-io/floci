package io.github.hectorvent.floci.services.inspector2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Inspector2IntegrationTest {
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/inspector2/aws4_request";

    @Test
    void delegatedAdminEnablementAndOrganizationConfiguration() {
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/delegatedadminaccounts/list").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"delegatedAdminAccountId\":\"111111111111\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(200)
                .body("delegatedAdminAccountId", equalTo("111111111111"));
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"autoEnable\":{\"ec2\":true,\"ecr\":true,\"lambda\":true,\"lambdaCode\":true}}")
                .post("/organizationconfiguration/update").then().statusCode(200);
        given().contentType("application/json").header("Authorization", AUTH).body("{}")
                .post("/organizationconfiguration/describe").then().statusCode(200)
                .body("autoEnable.ec2", equalTo(true))
                .body("autoEnable.ecr", equalTo(true))
                .body("autoEnable.lambda", equalTo(true))
                .body("autoEnable.lambdaCode", equalTo(true));
    }

    @Test
    void invalidAccountIdReturnsValidationException() {
        given().contentType("application/json").header("Authorization", AUTH)
                .body("{\"delegatedAdminAccountId\":\"bad\"}")
                .post("/delegatedadminaccounts/enable").then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }
}
