package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IotAuthorizerIntegrationTest {

    private static final String FUNCTION_ARN =
            "arn:aws:lambda:us-east-1:000000000000:function:custom-auth";

    @Test
    @Order(1)
    void describeMissingAuthorizerReturnsAwsError() {
        given()
        .when()
            .get("/authorizer/missing-authorizer")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(2)
    void createAuthorizerWithoutFunctionArnIsRejected() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/authorizer/no-function")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(3)
    void createAuthorizerReturnsNameAndArn() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "authorizerFunctionArn": "%s",
                  "tokenKeyName": "auth-token",
                  "tokenSigningPublicKeys": {"key1": "-----BEGIN PUBLIC KEY-----"},
                  "signingDisabled": false,
                  "enableCachingForHttp": true,
                  "tags": [{"Key": "tofu-estate", "Value": "probe1"}]
                }
                """.formatted(FUNCTION_ARN))
        .when()
            .post("/authorizer/estate-authorizer")
        .then()
            .statusCode(200)
            .body("authorizerName", equalTo("estate-authorizer"))
            .body("authorizerArn", equalTo("arn:aws:iot:us-east-1:000000000000:authorizer/estate-authorizer"));
    }

    @Test
    @Order(4)
    void describeAuthorizerReturnsTerminalActiveStatusAndEchoesTheRequest() {
        given()
        .when()
            .get("/authorizer/estate-authorizer")
        .then()
            .statusCode(200)
            .body("authorizerDescription.authorizerName", equalTo("estate-authorizer"))
            .body("authorizerDescription.authorizerArn",
                    equalTo("arn:aws:iot:us-east-1:000000000000:authorizer/estate-authorizer"))
            .body("authorizerDescription.authorizerFunctionArn", equalTo(FUNCTION_ARN))
            .body("authorizerDescription.tokenKeyName", equalTo("auth-token"))
            .body("authorizerDescription.tokenSigningPublicKeys.key1", equalTo("-----BEGIN PUBLIC KEY-----"))
            .body("authorizerDescription.status", equalTo("ACTIVE"))
            .body("authorizerDescription.signingDisabled", equalTo(false))
            .body("authorizerDescription.enableCachingForHttp", equalTo(true))
            .body("authorizerDescription.creationDate", notNullValue())
            .body("authorizerDescription.lastModifiedDate", notNullValue());
    }

    @Test
    @Order(5)
    void createAuthorizerTagsAreReadableOnTheSharedTagPath() {
        given()
            .queryParam("resourceArn", "arn:aws:iot:us-east-1:000000000000:authorizer/estate-authorizer")
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", hasItem("tofu-estate"))
            .body("tags.Value", hasItem("probe1"));
    }

    @Test
    @Order(6)
    void tagAndUntagAnAuthorizerByArn() {
        String arn = "arn:aws:iot:us-east-1:000000000000:authorizer/estate-authorizer";

        given()
            .contentType("application/json")
            .body("""
                {"resourceArn": "%s", "tags": [{"Key": "owner", "Value": "platform"}]}
                """.formatted(arn))
        .when()
            .post("/tags")
        .then()
            .statusCode(200);

        given()
            .queryParam("resourceArn", arn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", hasItem("owner"));

        given()
            .contentType("application/json")
            .body("""
                {"resourceArn": "%s", "tagKeys": ["owner"]}
                """.formatted(arn))
        .when()
            .post("/untag")
        .then()
            .statusCode(200);

        given()
            .queryParam("resourceArn", arn)
        .when()
            .get("/tags")
        .then()
            .statusCode(200)
            .body("tags.Key", not(hasItem("owner")));
    }

    @Test
    @Order(7)
    void listAuthorizersReturnsTheAuthorizerOnTheModelledPath() {
        given()
        .when()
            .get("/authorizers/")
        .then()
            .statusCode(200)
            .body("authorizers.authorizerName", hasItem("estate-authorizer"));
    }

    @Test
    @Order(8)
    void listAuthorizersHonoursTheStatusFilter() {
        given()
            .queryParam("status", "INACTIVE")
        .when()
            .get("/authorizers/")
        .then()
            .statusCode(200)
            .body("authorizers.authorizerName", not(hasItem("estate-authorizer")));
    }

    @Test
    @Order(9)
    void describeDefaultAuthorizerBeforeOneIsSetReturnsAwsError() {
        given()
        .when()
            .get("/default-authorizer")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(10)
    void setDefaultAuthorizerThenDescribeIt() {
        given()
            .contentType("application/json")
            .body("{\"authorizerName\": \"estate-authorizer\"}")
        .when()
            .post("/default-authorizer")
        .then()
            .statusCode(200)
            .body("authorizerName", equalTo("estate-authorizer"))
            .body("authorizerArn", not(emptyString()));

        given()
        .when()
            .get("/default-authorizer")
        .then()
            .statusCode(200)
            .body("authorizerDescription.authorizerName", equalTo("estate-authorizer"))
            .body("authorizerDescription.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(11)
    void creatingTheSameAuthorizerTwiceIsRejected() {
        given()
            .contentType("application/json")
            .body("{\"authorizerFunctionArn\": \"%s\"}".formatted(FUNCTION_ARN))
        .when()
            .post("/authorizer/estate-authorizer")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceAlreadyExistsException"));
    }

    @Test
    @Order(12)
    void deletingAnActiveAuthorizerIsRejected() {
        given()
            .contentType("application/json")
        .when()
            .delete("/authorizer/estate-authorizer")
        .then()
            .statusCode(409)
            .body("__type", equalTo("DeleteConflictException"));
    }

    @Test
    @Order(13)
    void updateAuthorizerChangesTheStoredDefinition() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "authorizerFunctionArn": "arn:aws:lambda:us-east-1:000000000000:function:custom-auth-v2",
                  "status": "INACTIVE",
                  "enableCachingForHttp": false
                }
                """)
        .when()
            .put("/authorizer/estate-authorizer")
        .then()
            .statusCode(200)
            .body("authorizerName", equalTo("estate-authorizer"));

        given()
        .when()
            .get("/authorizer/estate-authorizer")
        .then()
            .statusCode(200)
            .body("authorizerDescription.authorizerFunctionArn",
                    equalTo("arn:aws:lambda:us-east-1:000000000000:function:custom-auth-v2"))
            .body("authorizerDescription.status", equalTo("INACTIVE"))
            .body("authorizerDescription.enableCachingForHttp", equalTo(false))
            .body("authorizerDescription.tokenKeyName", equalTo("auth-token"));
    }

    @Test
    @Order(14)
    void anInactiveAuthorizerCannotBecomeTheDefault() {
        given()
            .contentType("application/json")
            .body("{\"authorizerName\": \"estate-authorizer\"}")
        .when()
            .post("/default-authorizer")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    @Test
    @Order(15)
    void deleteAuthorizerRemovesItAndClearsTheDefault() {
        given()
            .contentType("application/json")
        .when()
            .delete("/authorizer/estate-authorizer")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/authorizer/estate-authorizer")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
        .when()
            .get("/default-authorizer")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(16)
    void createAuthorizerAcceptsAnExplicitInactiveStatus() {
        given()
            .contentType("application/json")
            .body("""
                {"authorizerFunctionArn": "%s", "status": "INACTIVE"}
                """.formatted(FUNCTION_ARN))
        .when()
            .post("/authorizer/inactive-authorizer")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/authorizer/inactive-authorizer")
        .then()
            .statusCode(200)
            .body("authorizerDescription.status", equalTo("INACTIVE"))
            .body("authorizerDescription.tokenSigningPublicKeys.size()", equalTo(0));

        given()
            .contentType("application/json")
        .when()
            .delete("/authorizer/inactive-authorizer")
        .then()
            .statusCode(200);
    }
}
