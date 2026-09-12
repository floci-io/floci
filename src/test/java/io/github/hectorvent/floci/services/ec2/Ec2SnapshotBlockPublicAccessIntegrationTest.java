package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Integration tests for block public access for snapshots over the EC2 Query Protocol
 * (form-encoded POST, XML response).
 *
 * <p>The setting is account-and-region scoped state rather than a resource: there is no id
 * and nothing to tag, only one value that Enable and Disable move and Get reads back. Only
 * the Get response carries {@code managedBy}.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2SnapshotBlockPublicAccessIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String OTHER_REGION_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/eu-west-1/ec2/aws4_request";

    @Test
    @Order(1)
    void unconfiguredRegionReportsUnblocked() {
        given()
            .formParam("Action", "GetSnapshotBlockPublicAccessState")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("GetSnapshotBlockPublicAccessStateResponse.state", equalTo("unblocked"))
            .body("GetSnapshotBlockPublicAccessStateResponse.managedBy", equalTo("account"));
    }

    @Test
    @Order(2)
    void enableStoresBlockAllSharing() {
        String body = given()
            .formParam("Action", "EnableSnapshotBlockPublicAccess")
            .formParam("State", "block-all-sharing")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("EnableSnapshotBlockPublicAccessResponse.state", equalTo("block-all-sharing"))
            .extract().asString();

        // EnableSnapshotBlockPublicAccessResult carries State alone.
        assertThat(body, not(containsString("managedBy")));

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
    void enableNarrowsTheBlockToNewSharing() {
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
    void anotherRegionKeepsItsOwnState() {
        given()
            .formParam("Action", "GetSnapshotBlockPublicAccessState")
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetSnapshotBlockPublicAccessStateResponse.state", equalTo("unblocked"));

        given()
            .formParam("Action", "EnableSnapshotBlockPublicAccess")
            .formParam("State", "block-all-sharing")
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
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
            .body("GetSnapshotBlockPublicAccessStateResponse.state", equalTo("block-new-sharing"));
    }

    @Test
    @Order(7)
    void disableReturnsAndStoresUnblocked() {
        String body = given()
            .formParam("Action", "DisableSnapshotBlockPublicAccess")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("DisableSnapshotBlockPublicAccessResponse.state", equalTo("unblocked"))
            .extract().asString();

        // DisableSnapshotBlockPublicAccessResult carries State alone.
        assertThat(body, not(containsString("managedBy")));

        given()
            .formParam("Action", "GetSnapshotBlockPublicAccessState")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetSnapshotBlockPublicAccessStateResponse.state", equalTo("unblocked"));

        // Disabling one region leaves the other alone.
        given()
            .formParam("Action", "GetSnapshotBlockPublicAccessState")
            .header("Authorization", OTHER_REGION_AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetSnapshotBlockPublicAccessStateResponse.state", equalTo("block-all-sharing"));
    }

    @Test
    @Order(8)
    void dryRunGetReturnsDryRunOperation() {
        given()
            .formParam("Action", "GetSnapshotBlockPublicAccessState")
            .formParam("DryRun", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(412)
            .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
    }

    @Test
    @Order(9)
    void dryRunEnableLeavesTheStoredStateAlone() {
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
            .formParam("Action", "EnableSnapshotBlockPublicAccess")
            .formParam("State", "block-new-sharing")
            .formParam("DryRun", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(412)
            .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));

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
    @Order(10)
    void dryRunEnableRejectsAnInvalidStateBeforeReportingDryRun() {
        given()
            .formParam("Action", "EnableSnapshotBlockPublicAccess")
            .formParam("State", "unblocked")
            .formParam("DryRun", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));

        given()
            .formParam("Action", "EnableSnapshotBlockPublicAccess")
            .formParam("DryRun", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("MissingParameter"));

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
    @Order(11)
    void dryRunDisableLeavesTheStoredStateAlone() {
        given()
            .formParam("Action", "DisableSnapshotBlockPublicAccess")
            .formParam("DryRun", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(412)
            .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));

        given()
            .formParam("Action", "GetSnapshotBlockPublicAccessState")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetSnapshotBlockPublicAccessStateResponse.state", equalTo("block-all-sharing"));
    }
}
