package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsCanonicalUserId;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3OwnershipControlsIntegrationTest {

    private static final String BUCKET = "ownership-controls-int-test";
    private static final String OWNERSHIP_CONTROLS_XML = """
            <OwnershipControls xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Rule>
                    <ObjectOwnership>BucketOwnerPreferred</ObjectOwnership>
                </Rule>
            </OwnershipControls>
            """;
    private static final String SCHEMA_MESSAGE =
            "The XML you provided was not well-formed or did not validate against our published schema.";

    @Inject
    S3Service s3Service;

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
    void newBucketDefaultsToEnforcedOwnershipAndRejectsAclWrites() {
        String bucket = BUCKET + "-default";
        given().when().put("/" + bucket).then().statusCode(200);

        given()
        .when()
            .get("/" + bucket + "?ownershipControls")
        .then()
            .statusCode(200)
            .body(containsString("<ObjectOwnership>BucketOwnerEnforced</ObjectOwnership>"));

        given()
            .header("x-amz-acl", "public-read")
        .when()
            .put("/" + bucket + "?acl")
        .then()
            .statusCode(400)
            .body(containsString("AccessControlListNotSupported"));
    }

    @Test
    @Order(10)
    void ownerCanUploadWithPrivateAclOnEnforcedBucket() {
        String bucket = BUCKET + "-private-upload";
        given().when().put("/" + bucket).then().statusCode(200);

        given().header("x-amz-acl", "private").body("single upload")
                .when().put("/" + bucket + "/single")
                .then().statusCode(200);
        given().when().get("/" + bucket + "/single")
                .then().statusCode(200).body(containsString("single upload"));

        String uploadId = given().header("x-amz-acl", "private")
                .when().post("/" + bucket + "/multipart?uploads")
                .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        String partETag = given().body("multipart upload")
                .when().put("/" + bucket + "/multipart?uploadId=" + uploadId + "&partNumber=1")
                .then().statusCode(200).extract().header("ETag");
        String completeXml = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"
                + partETag + "</ETag></Part></CompleteMultipartUpload>";
        given().contentType("application/xml").body(completeXml)
                .when().post("/" + bucket + "/multipart?uploadId=" + uploadId)
                .then().statusCode(200);
        given().when().get("/" + bucket + "/multipart")
                .then().statusCode(200).body(containsString("multipart upload"));
    }

    @Test
    @Order(2)
    void createBucketHonorsExplicitOwnershipAndRejectsIncompatibleAcl() {
        String legacyBucket = BUCKET + "-explicit-writer";
        given()
            .header("x-amz-object-ownership", "ObjectWriter")
        .when()
            .put("/" + legacyBucket)
        .then()
            .statusCode(200);

        given().when().get("/" + legacyBucket + "?ownershipControls")
                .then().statusCode(200).body(containsString("<ObjectOwnership>ObjectWriter</ObjectOwnership>"));
        String grantedBucket = BUCKET + "-explicit-grant";
        given().header("x-amz-object-ownership", "ObjectWriter")
                .header("x-amz-grant-read", "id=\"123456789012\"")
                .when().put("/" + grantedBucket)
                .then().statusCode(200);
        given().when().get("/" + grantedBucket + "?acl")
                .then().statusCode(200).body(containsString("123456789012"));
        given().header("x-amz-acl", "public-read")
                .when().put("/" + BUCKET + "-invalid-acl")
                .then().statusCode(400).body(containsString("InvalidBucketAclWithObjectOwnership"));
        given().header("x-amz-grant-read", "id=\"123456789012\"")
                .when().put("/" + BUCKET + "-invalid-grant")
                .then().statusCode(400).body(containsString("InvalidBucketAclWithObjectOwnership"));
        given().header("x-amz-object-ownership", "InvalidOwnership")
                .when().put("/" + BUCKET + "-invalid-mode")
                .then().statusCode(400).body(containsString("InvalidArgument"));
    }

    @Test
    @Order(2)
    void createBucketAcceptsCannedAclsAndRejectsConflictingGrants() {
        for (String cannedAcl : new String[] {
                "log-delivery-write", "aws-exec-read", "bucket-owner-read", "bucket-owner-full-control"}) {
            given().header("x-amz-object-ownership", "ObjectWriter")
                    .header("x-amz-acl", cannedAcl)
                    .when().put("/" + BUCKET + "-canned-" + cannedAcl)
                    .then().statusCode(200);
        }
        given().header("x-amz-object-ownership", "ObjectWriter")
                .header("x-amz-acl", "private")
                .header("x-amz-grant-read", "id=\"123456789012\"")
                .when().put("/" + BUCKET + "-conflicting-acl")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidRequest</Code>"))
                .body(containsString("Specifying both Canned ACLs and Header Grants is not allowed"));
    }

    @Test
    @Order(2)
    void createBucketRejectsPublicAclRegardlessOfAccountBlockSetting() {
        given().header("x-amz-object-ownership", "ObjectWriter")
                .header("x-amz-acl", "public-read")
                .when().put("/" + BUCKET + "-public-unblocked")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidBucketAclWithBlockPublicAccessError</Code>"));

        s3Service.putAccountPublicAccessBlock("000000000000", """
                <PublicAccessBlockConfiguration>
                    <BlockPublicAcls>true</BlockPublicAcls>
                </PublicAccessBlockConfiguration>
                """);
        try {
            given().header("x-amz-object-ownership", "ObjectWriter")
                    .header("x-amz-acl", "public-read")
                    .when().put("/" + BUCKET + "-public-blocked")
                    .then().statusCode(400)
                    .body(containsString("<Code>InvalidBucketAclWithBlockPublicAccessError</Code>"));
        } finally {
            s3Service.deleteAccountPublicAccessBlock("000000000000");
        }
    }

    @Test
    @Order(2)
    void multipartInitiationRejectsGrantsOnEnforcedBucket() {
        String bucket = BUCKET + "-multipart-grants";
        given().when().put("/" + bucket).then().statusCode(200);

        given().header("x-amz-grant-read", "id=\"123456789012\"")
                .when().post("/" + bucket + "/rejected?uploads")
                .then().statusCode(400).body(containsString("AccessControlListNotSupported"));
        given().header("x-amz-grant-full-control", "id=\"000000000000\"")
                .when().post("/" + bucket + "/accepted?uploads")
                .then().statusCode(200).body(containsString("<UploadId>"));
        given().header("x-amz-grant-full-control",
                        "id=\"" + AwsCanonicalUserId.forAccount("000000000000") + "\"")
                .when().post("/" + bucket + "/accepted-canonical?uploads")
                .then().statusCode(200).body(containsString("<UploadId>"));
    }

    @Test
    @Order(2)
    void multipartGrantSurvivesCompletionOnObjectWriterBucket() {
        String bucket = BUCKET + "-multipart-writer";
        String key = "granted-object";
        given().header("x-amz-object-ownership", "ObjectWriter")
                .when().put("/" + bucket).then().statusCode(200);
        String uploadId = given().header("x-amz-grant-read", "id=\"123456789012\"")
                .when().post("/" + bucket + "/" + key + "?uploads")
                .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
        String partETag = given().body("part-one")
                .when().put("/" + bucket + "/" + key + "?uploadId=" + uploadId + "&partNumber=1")
                .then().statusCode(200).extract().header("ETag");
        String completeXml = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"
                + partETag + "</ETag></Part></CompleteMultipartUpload>";
        given().contentType("application/xml").body(completeXml)
                .when().post("/" + bucket + "/" + key + "?uploadId=" + uploadId)
                .then().statusCode(200);
        given().when().get("/" + bucket + "/" + key + "?acl")
                .then().statusCode(200).body(containsString("123456789012"));
    }

    @Test
    @Order(3)
    void putOwnershipControlsReturns200() {
        given()
            .body(OWNERSHIP_CONTROLS_XML)
        .when()
            .put("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void getOwnershipControlsReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(200)
            .body(containsString("<OwnershipControls"))
            .body(containsString("<ObjectOwnership>BucketOwnerPreferred</ObjectOwnership>"));
    }

    @Test
    @Order(5)
    void invalidDirectOwnershipControlsDoNotOverwriteTheStoredMode() {
        given()
            .body(OWNERSHIP_CONTROLS_XML.replace("BucketOwnerPreferred", "NotAnOwnershipMode"))
        .when()
            .put("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(400)
            .body(containsString("<Code>MalformedXML</Code>"))
            .body(containsString(SCHEMA_MESSAGE));

        given()
            .body("<OwnershipControls xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>")
        .when()
            .put("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(400)
            .body(containsString("<Code>MalformedXML</Code>"))
            .body(containsString(SCHEMA_MESSAGE));

        given()
            .body(OWNERSHIP_CONTROLS_XML.replace("<ObjectOwnership>BucketOwnerPreferred</ObjectOwnership>", ""))
        .when()
            .put("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(400)
            .body(containsString("<Code>MalformedXML</Code>"))
            .body(containsString(SCHEMA_MESSAGE));

        given()
            .body(OWNERSHIP_CONTROLS_XML.replace("</OwnershipControls>",
                    "<Rule><ObjectOwnership>ObjectWriter</ObjectOwnership></Rule></OwnershipControls>"))
        .when()
            .put("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(400)
            .body(containsString("<Code>MalformedXML</Code>"))
            .body(containsString(SCHEMA_MESSAGE));

        given()
        .when()
            .get("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(200)
            .body(containsString("<ObjectOwnership>BucketOwnerPreferred</ObjectOwnership>"));
    }

    @Test
    @Order(6)
    void enforcedOwnershipRejectsAclWritesAndDisallowedUploadAcls() {
        given()
            .body("object body")
        .when()
            .put("/" + BUCKET + "/owned-object")
        .then()
            .statusCode(200);

        given()
            .body(OWNERSHIP_CONTROLS_XML.replace("BucketOwnerPreferred", "BucketOwnerEnforced"))
        .when()
            .put("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-acl", "public-read")
        .when()
            .put("/" + BUCKET + "?acl")
        .then()
            .statusCode(400)
            .body(containsString("AccessControlListNotSupported"));

        given()
            .header("x-amz-acl", "public-read")
        .when()
            .put("/" + BUCKET + "/owned-object?acl")
        .then()
            .statusCode(400)
            .body(containsString("AccessControlListNotSupported"));

        given()
            .header("x-amz-acl", "public-read")
            .body("new object")
        .when()
            .put("/" + BUCKET + "/rejected-object")
        .then()
            .statusCode(400)
            .body(containsString("AccessControlListNotSupported"));

        given()
            .header("x-amz-acl", "public-read")
        .when()
            .post("/" + BUCKET + "/rejected-multipart?uploads")
        .then()
            .statusCode(400)
            .body(containsString("AccessControlListNotSupported"));

        given()
            .header("x-amz-grant-read", "uri=\"http://acs.amazonaws.com/groups/global/AllUsers\"")
        .when()
            .post("/" + BUCKET + "/rejected-multipart-grant?uploads")
        .then()
            .statusCode(400)
            .body(containsString("AccessControlListNotSupported"));

        given()
            .body("new object")
        .when()
            .put("/" + BUCKET + "/no-acl-object")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-acl", "bucket-owner-full-control")
            .body("new object")
        .when()
            .put("/" + BUCKET + "/accepted-object")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void deleteOwnershipControlsReturns204() {
        given()
        .when()
            .delete("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(8)
    void getOwnershipControlsAfterDeleteReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(404)
            .body(containsString("OwnershipControlsNotFoundError"));
    }

    @Test
    @Order(9)
    void enforcedOwnershipRejectsBucketAclGrantedToAnotherCanonicalUser() {
        String bucket = BUCKET + "-nonowner-grant";
        String acl = """
                <AccessControlPolicy xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Owner><ID>000000000000</ID></Owner>
                  <AccessControlList>
                    <Grant><Grantee><ID>000000000000</ID></Grantee><Permission>FULL_CONTROL</Permission></Grant>
                    <Grant><Grantee><ID>111111111111</ID></Grantee><Permission>READ</Permission></Grant>
                  </AccessControlList>
                </AccessControlPolicy>
                """;
        String enforced = OWNERSHIP_CONTROLS_XML.replace("BucketOwnerPreferred", "BucketOwnerEnforced");

        given().when().put("/" + bucket).then().statusCode(200);
        given().body(OWNERSHIP_CONTROLS_XML.replace("BucketOwnerPreferred", "ObjectWriter"))
                .when().put("/" + bucket + "?ownershipControls").then().statusCode(200);
        given().body(acl).when().put("/" + bucket + "?acl").then().statusCode(200);
        given().body(enforced).when().put("/" + bucket + "?ownershipControls")
                .then().statusCode(400).body(containsString("InvalidBucketAclWithObjectOwnership"));
        given().when().get("/" + bucket + "?ownershipControls")
                .then().statusCode(200)
                .body(containsString("<ObjectOwnership>ObjectWriter</ObjectOwnership>"));

        given().header("x-amz-acl", "private").when().put("/" + bucket + "?acl")
                .then().statusCode(200);
        given().body(enforced).when().put("/" + bucket + "?ownershipControls")
                .then().statusCode(200);
    }
}
