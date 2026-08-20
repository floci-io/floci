package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AclIntegrationTest {

    private static final String BUCKET = "acl-test-bucket";
    private static final String ALL_USERS_GROUP_URI = "http://acs.amazonaws.com/groups/global/AllUsers";
    private static final String AUTHENTICATED_USERS_GROUP_URI = "http://acs.amazonaws.com/groups/global/AuthenticatedUsers";
    private static String multipartUploadId;

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void putObjectAppliesPublicReadAcl() {
        given()
            .header("x-amz-acl", "public-read")
            .body("public body")
        .when()
            .put("/" + BUCKET + "/public.txt")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/public.txt?acl")
        .then()
            .statusCode(200)
            .body(containsString(ALL_USERS_GROUP_URI))
            .body(containsString("<Permission>READ</Permission>"))
            .body(containsString("<Permission>FULL_CONTROL</Permission>"));
    }

    @Test
    @Order(3)
    void copyObjectWithoutAclDefaultsToPrivateAcl() {
        given()
            .body("copy me")
        .when()
            .put("/" + BUCKET + "/copy-source.txt")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-copy-source", "/" + BUCKET + "/copy-source.txt")
        .when()
            .put("/" + BUCKET + "/copy-default-private.txt")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/copy-default-private.txt?acl")
        .then()
            .statusCode(200)
            .body(not(containsString(ALL_USERS_GROUP_URI)))
            .body(containsString("<Permission>FULL_CONTROL</Permission>"));
    }

    @Test
    @Order(4)
    void copyObjectAppliesRequestedAuthenticatedReadAcl() {
        given()
            .header("x-amz-copy-source", "/" + BUCKET + "/copy-source.txt")
            .header("x-amz-acl", "authenticated-read")
        .when()
            .put("/" + BUCKET + "/copy-authenticated.txt")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/copy-authenticated.txt?acl")
        .then()
            .statusCode(200)
            .body(containsString(AUTHENTICATED_USERS_GROUP_URI))
            .body(containsString("<Permission>READ</Permission>"))
            .body(not(containsString(ALL_USERS_GROUP_URI)));
    }

    @Test
    @Order(5)
    void initiateMultipartUploadAppliesRequestedAclOnComplete() {
        multipartUploadId = given()
            .header("x-amz-acl", "public-read")
        .when()
            .post("/" + BUCKET + "/multipart-public.txt?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given()
            .body("part-one")
        .when()
            .put("/" + BUCKET + "/multipart-public.txt?uploadId=" + multipartUploadId + "&partNumber=1")
        .then()
            .statusCode(200);

        String completeXml = """
                <CompleteMultipartUpload>
                    <Part><PartNumber>1</PartNumber><ETag>etag1</ETag></Part>
                </CompleteMultipartUpload>""";

        given()
            .contentType("application/xml")
            .body(completeXml)
        .when()
            .post("/" + BUCKET + "/multipart-public.txt?uploadId=" + multipartUploadId)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/multipart-public.txt?acl")
        .then()
            .statusCode(200)
            .body(containsString(ALL_USERS_GROUP_URI))
            .body(containsString("<Permission>READ</Permission>"));
    }

    @Test
    @Order(6)
    void putObjectRejectsUnsupportedCannedAcl() {
        given()
            .header("x-amz-acl", "totally-unsupported")
            .body("bad acl")
        .when()
            .put("/" + BUCKET + "/invalid-acl.txt")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"))
            .body(containsString("Unsupported x-amz-acl value"));
    }

    @Test
    @Order(7)
    void initiateMultipartUploadRejectsUnsupportedCannedAcl() {
        given()
            .header("x-amz-acl", "totally-unsupported")
        .when()
            .post("/" + BUCKET + "/invalid-multipart.txt?uploads")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"))
            .body(containsString("Unsupported x-amz-acl value"));
    }

    // Regression coverage for PutObjectAcl/PutBucketAcl not reading canned/explicit ACL headers:
    // AWS SDKs send canned and explicit-grant ACLs to the ?acl subresource as headers with an
    // EMPTY body (there is no XML for those forms - only for a raw AccessControlPolicy document).
    // Before this fix, the empty body was stored verbatim as the object's ACL, and the next
    // GetObjectAcl returned that empty string, which SDK XML parsers reject outright.

    @Test
    @Order(8)
    void putObjectAclAppliesCannedAclFromHeaderWithEmptyBody() {
        given()
            .body("acl subresource body")
        .when()
            .put("/" + BUCKET + "/acl-subresource.txt")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-acl", "public-read")
        .when()
            .put("/" + BUCKET + "/acl-subresource.txt?acl")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/acl-subresource.txt?acl")
        .then()
            .statusCode(200)
            .body(containsString(ALL_USERS_GROUP_URI))
            .body(containsString("<Permission>READ</Permission>"))
            .body(containsString("<Permission>FULL_CONTROL</Permission>"));
    }

    @Test
    @Order(9)
    void putObjectAclBucketOwnerFullControlReplacesPriorGrants() {
        given()
            .header("x-amz-acl", "public-read")
            .body("owner-full-control target")
        .when()
            .put("/" + BUCKET + "/owner-full-control.txt")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/owner-full-control.txt?acl")
        .then()
            .statusCode(200)
            .body(containsString(ALL_USERS_GROUP_URI));

        given()
            .header("x-amz-acl", "bucket-owner-full-control")
        .when()
            .put("/" + BUCKET + "/owner-full-control.txt?acl")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/owner-full-control.txt?acl")
        .then()
            .statusCode(200)
            .body(not(containsString(ALL_USERS_GROUP_URI)))
            .body(containsString("<Permission>FULL_CONTROL</Permission>"));
    }

    @Test
    @Order(10)
    void putBucketAclAppliesCannedAclFromHeaderWithEmptyBody() {
        given()
            .header("x-amz-acl", "public-read")
        .when()
            .put("/" + BUCKET + "?acl")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?acl")
        .then()
            .statusCode(200)
            .body(containsString(ALL_USERS_GROUP_URI))
            .body(containsString("<Permission>READ</Permission>"));
    }

    // Regression coverage for explicit ACL grant headers (x-amz-grant-*) being ignored entirely
    // on PutObject/CopyObject - previously only canned ACLs (x-amz-acl) were applied.

    @Test
    @Order(11)
    void putObjectAppliesExplicitGrantReadHeaderToAllUsersGroup() {
        given()
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
            .body("explicit grant body")
        .when()
            .put("/" + BUCKET + "/explicit-grant-put.txt")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/explicit-grant-put.txt?acl")
        .then()
            .statusCode(200)
            .body(containsString(ALL_USERS_GROUP_URI))
            .body(containsString("<Permission>READ</Permission>"))
            .body(containsString("<Permission>FULL_CONTROL</Permission>"));
    }

    @Test
    @Order(12)
    void copyObjectAppliesExplicitGrantReadHeaderToAllUsersGroup() {
        given()
            .header("x-amz-copy-source", "/" + BUCKET + "/copy-source.txt")
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\"")
        .when()
            .put("/" + BUCKET + "/explicit-grant-copy.txt")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/explicit-grant-copy.txt?acl")
        .then()
            .statusCode(200)
            .body(containsString(ALL_USERS_GROUP_URI))
            .body(containsString("<Permission>READ</Permission>"));
    }

    @Test
    @Order(13)
    void putObjectAppliesExplicitGrantHeaderWithMultipleGrantees() {
        given()
            .header("x-amz-grant-read", "uri=\"" + ALL_USERS_GROUP_URI + "\", uri=\"" + AUTHENTICATED_USERS_GROUP_URI + "\"")
            .body("multi-grantee body")
        .when()
            .put("/" + BUCKET + "/explicit-grant-multi.txt")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/explicit-grant-multi.txt?acl")
        .then()
            .statusCode(200)
            .body(containsString(ALL_USERS_GROUP_URI))
            .body(containsString(AUTHENTICATED_USERS_GROUP_URI));
    }

    @Test
    @Order(14)
    void putObjectRejectsExplicitGrantByEmailAddress() {
        given()
            .header("x-amz-grant-read", "emailAddress=\"someone@example.com\"")
            .body("unsupported grantee")
        .when()
            .put("/" + BUCKET + "/explicit-grant-email.txt")
        .then()
            .statusCode(501)
            .body(containsString("NotImplemented"));
    }

    // Regression coverage for a second empty-body pitfall found in review: calling PutObjectAcl/
    // PutBucketAcl with neither a canned/explicit ACL header NOR a body must not store the empty
    // body as the ACL (that would break the next GetObjectAcl/GetBucketAcl the same way the
    // header-with-empty-body bug did).

    @Test
    @Order(15)
    void putObjectAclWithNoHeadersAndNoBodyKeepsDefaultAcl() {
        given()
            .body("acl no-op target")
        .when()
            .put("/" + BUCKET + "/acl-noop.txt")
        .then()
            .statusCode(200);

        given()
        .when()
            .put("/" + BUCKET + "/acl-noop.txt?acl")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "/acl-noop.txt?acl")
        .then()
            .statusCode(200)
            .body(not(containsString(ALL_USERS_GROUP_URI)))
            .body(containsString("<Permission>FULL_CONTROL</Permission>"));
    }

    @Test
    @Order(16)
    void putBucketAclWithNoHeadersAndNoBodyKeepsDefaultAcl() {
        given()
        .when()
            .put("/" + BUCKET + "?acl")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?acl")
        .then()
            .statusCode(200)
            .body(not(containsString(ALL_USERS_GROUP_URI)))
            .body(containsString("<Permission>FULL_CONTROL</Permission>"));
    }
}
