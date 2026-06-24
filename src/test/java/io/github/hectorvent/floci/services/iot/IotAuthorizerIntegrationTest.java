package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotAuthorizerIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iot/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(10)
    void createAuthorizer() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "authorizerFunctionArn": "arn:aws:lambda:us-east-1:000000000000:function:my-authorizer",
                  "tokenKeyName": "MyAuthToken",
                  "signingDisabled": true,
                  "status": "ACTIVE"
                }
                """)
        .when()
            .post("/authorizer/device-auth")
        .then()
            .statusCode(200)
            .body("authorizerName", equalTo("device-auth"))
            .body("authorizerArn", endsWith(":authorizer/device-auth"));
    }

    @Test
    @Order(11)
    void describeAuthorizer() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/authorizer/device-auth")
        .then()
            .statusCode(200)
            .body("authorizerDescription.authorizerName", equalTo("device-auth"))
            .body("authorizerDescription.authorizerFunctionArn",
                    equalTo("arn:aws:lambda:us-east-1:000000000000:function:my-authorizer"))
            .body("authorizerDescription.tokenKeyName", equalTo("MyAuthToken"))
            .body("authorizerDescription.signingDisabled", equalTo(true))
            .body("authorizerDescription.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(12)
    void createAuthorizerWithoutSigningKeysRejected() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {
                  "authorizerFunctionArn": "arn:aws:lambda:us-east-1:000000000000:function:my-authorizer",
                  "tokenKeyName": "MyAuthToken",
                  "signingDisabled": false
                }
                """)
        .when()
            .post("/authorizer/needs-keys")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(13)
    void updateAuthorizerStatus() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"status\": \"INACTIVE\"}")
        .when()
            .put("/authorizer/device-auth")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/authorizer/device-auth")
        .then()
            .statusCode(200)
            .body("authorizerDescription.status", equalTo("INACTIVE"));

        // restore to ACTIVE for the default-authorizer test
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"status\": \"ACTIVE\"}")
        .when()
            .put("/authorizer/device-auth")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(14)
    void listAuthorizers() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/authorizers")
        .then()
            .statusCode(200)
            .body("authorizers.authorizerName", hasItem("device-auth"));
    }

    @Test
    @Order(15)
    void setDefaultAuthorizer() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"authorizerName\": \"device-auth\"}")
        .when()
            .post("/default-authorizer")
        .then()
            .statusCode(200)
            .body("authorizerName", equalTo("device-auth"));
    }

    @Test
    @Order(16)
    void cannotDeleteDefaultAuthorizer() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/authorizer/device-auth")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(17)
    void describeMissingAuthorizerReturns404() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/authorizer/ghost")
        .then()
            .statusCode(404);
    }
}
