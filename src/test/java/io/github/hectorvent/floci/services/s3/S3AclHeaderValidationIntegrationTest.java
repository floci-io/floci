package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AclHeaderValidationIntegrationTest {

    private static final String BUCKET = "acl-header-validation-bucket";
    private static final String ALL_USERS_GROUP_URI = "http://acs.amazonaws.com/groups/global/AllUsers";
    private static final String CONFLICTING_ACL_MESSAGE =
            "Specifying both Canned ACLs and Header Grants is not allowed";

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Order(2)
    @ParameterizedTest
    @ValueSource(strings = {
            "x-amz-grant-read",
            "x-amz-grant-write",
            "x-amz-grant-full-control",
            "x-amz-grant-read-acp",
            "x-amz-grant-write-acp"
    })
    void putObjectRejectsConflictingCannedAclAndGrantHeaders(String grantHeader) {
        assertInvalidRequest(given()
            .header("x-amz-acl", "private")
            .header(grantHeader, "uri=\"" + ALL_USERS_GROUP_URI + "\"")
            .body("conflict body")
        .when()
            .put("/" + BUCKET + "/conflict-put-" + grantHeader + ".txt")
        .then());
    }

    @Test
    @Order(3)
    void putObjectAllowsCannedAclWithBlankGrantHeader() {
        given()
            .header("x-amz-acl", "private")
            .header("x-amz-grant-read", "   ")
            .body("blank grant body")
        .when()
            .put("/" + BUCKET + "/blank-grant-put.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void putObjectAllowsGrantHeaderWithBlankCannedAcl() {
        given()
            .header("x-amz-acl", "   ")
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
            .body("blank canned acl body")
        .when()
            .put("/" + BUCKET + "/blank-canned-put.txt")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void putBucketAclRejectsConflictingCannedAclAndGrantHeaders() {
        assertInvalidRequest(given()
            .header("x-amz-acl", "private")
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
        .when()
            .put("/" + BUCKET + "?acl")
        .then());
    }

    @Test
    @Order(6)
    void putObjectAclRejectsConflictingCannedAclAndGrantHeaders() {
        given()
            .body("target object")
        .when()
            .put("/" + BUCKET + "/acl-target.txt")
        .then()
            .statusCode(200);

        assertInvalidRequest(given()
            .header("x-amz-acl", "private")
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
        .when()
            .put("/" + BUCKET + "/acl-target.txt?acl")
        .then());
    }

    @Test
    @Order(7)
    void initiateMultipartUploadRejectsConflictingCannedAclAndGrantHeaders() {
        assertInvalidRequest(given()
            .header("x-amz-acl", "private")
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
        .when()
            .post("/" + BUCKET + "/multipart-conflict.txt?uploads")
        .then());
    }

    @Test
    @Order(8)
    void copyObjectRejectsConflictingCannedAclAndGrantHeaders() {
        given()
            .body("copy source content")
        .when()
            .put("/" + BUCKET + "/copy-source.txt")
        .then()
            .statusCode(200);

        assertInvalidRequest(given()
            .header("x-amz-copy-source", "/" + BUCKET + "/copy-source.txt")
            .header("x-amz-acl", "private")
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
        .when()
            .put("/" + BUCKET + "/copy-conflict.txt")
        .then());
    }

    private static void assertInvalidRequest(ValidatableResponse response) {
        response.statusCode(400)
                .body("Error.Code", equalTo("InvalidRequest"))
                .body("Error.Message", equalTo(CONFLICTING_ACL_MESSAGE));
    }
}
