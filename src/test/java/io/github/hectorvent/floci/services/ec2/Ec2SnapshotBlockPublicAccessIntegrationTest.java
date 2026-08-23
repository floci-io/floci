package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Integration tests for snapshot block public access, which is account-and-region scoped
 * state rather than a resource: there is nothing to tag and nothing to describe by id, only
 * a single value that Enable/Disable move and Get reads back.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2SnapshotBlockPublicAccessIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    @Order(1)
    void defaultStateIsUnblocked() {
        given()
            .formParam("Action", "GetSnapshotBlockPublicAccessState")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetSnapshotBlockPublicAccessStateResponse.state", equalTo("unblocked"))
            .body("GetSnapshotBlockPublicAccessStateResponse.managedBy", equalTo("account"));
    }

    @Test
    @Order(2)
    void enableStoresTheRequestedBlockingMode() {
        given()
            .formParam("Action", "EnableSnapshotBlockPublicAccess")
            .formParam("State", "block-all-sharing")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EnableSnapshotBlockPublicAccessResponse.state", equalTo("block-all-sharing"));

        given()
            .formParam("Action", "GetSnapshotBlockPublicAccessState")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetSnapshotBlockPublicAccessStateResponse.state", equalTo("block-all-sharing"));
    }

    @Test
    @Order(3)
    void enableCanNarrowTheModeToNewSharingOnly() {
        given()
            .formParam("Action", "EnableSnapshotBlockPublicAccess")
            .formParam("State", "block-new-sharing")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("EnableSnapshotBlockPublicAccessResponse.state", equalTo("block-new-sharing"));

        given()
            .formParam("Action", "GetSnapshotBlockPublicAccessState")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetSnapshotBlockPublicAccessStateResponse.state", equalTo("block-new-sharing"));
    }

    @Test
    @Order(4)
    void enableRejectsUnblocked() {
        given()
            .formParam("Action", "EnableSnapshotBlockPublicAccess")
            .formParam("State", "unblocked")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
    }

    @Test
    @Order(5)
    void enableRequiresState() {
        given()
            .formParam("Action", "EnableSnapshotBlockPublicAccess")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    @Order(6)
    void disableReturnsAndPersistsUnblocked() {
        given()
            .formParam("Action", "DisableSnapshotBlockPublicAccess")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DisableSnapshotBlockPublicAccessResponse.state", equalTo("unblocked"));

        given()
            .formParam("Action", "GetSnapshotBlockPublicAccessState")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetSnapshotBlockPublicAccessStateResponse.state", equalTo("unblocked"));
    }
}
