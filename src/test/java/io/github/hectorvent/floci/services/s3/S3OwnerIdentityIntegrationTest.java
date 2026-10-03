package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class S3OwnerIdentityIntegrationTest {

    private static final String ACCOUNT_ONE = "000000000011";
    private static final String ACCOUNT_TWO = "000000000012";

    @Test
    void listBucketsOwnerMatchesTheCallerAccountAndBucketAcl() {
        String bucket = "owner-identity-list-bucket";
        createBucket(ACCOUNT_ONE, bucket);

        given().header("Authorization", authorization(ACCOUNT_ONE))
        .when().get("/")
        .then().statusCode(200)
                .body(containsString("<Owner><ID>" + ACCOUNT_ONE + "</ID><DisplayName>floci</DisplayName></Owner>"))
                .body(containsString("<Name>" + bucket + "</Name>"));

        given().header("Authorization", authorization(ACCOUNT_ONE))
        .when().get("/" + bucket + "?acl")
        .then().statusCode(200)
                .body(containsString("<Owner><ID>" + ACCOUNT_ONE + "</ID><DisplayName>floci</DisplayName></Owner>"));

        given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().get("/")
        .then().statusCode(200)
                .body(containsString("<Owner><ID>" + ACCOUNT_TWO + "</ID><DisplayName>floci</DisplayName></Owner>"))
                .body(not(containsString("<Name>" + bucket + "</Name>")));
    }

    @Test
    void listPartsOwnerAndInitiatorUseTheUploadAccount() {
        String bucket = "owner-identity-parts-bucket";
        createBucket(ACCOUNT_TWO, bucket);

        String uploadId = given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().post("/" + bucket + "/part.bin?uploads")
        .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().get("/" + bucket + "/part.bin?uploadId=" + uploadId)
        .then().statusCode(200)
                .body(containsString("<Initiator><ID>" + ACCOUNT_TWO
                        + "</ID><DisplayName>floci</DisplayName></Initiator>"))
                .body(containsString("<Owner><ID>" + ACCOUNT_TWO
                        + "</ID><DisplayName>floci</DisplayName></Owner>"));
    }

    @Test
    void anUploadCannotBeAttributedToAnotherAccountOrSurviveBucketDeletion() {
        String bucket = "owner-identity-reused-bucket";
        createBucket(ACCOUNT_ONE, bucket);
        createBucket(ACCOUNT_TWO, bucket);

        String firstUploadId = given().header("Authorization", authorization(ACCOUNT_ONE))
        .when().post("/" + bucket + "/part.bin?uploads")
        .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().get("/" + bucket + "/part.bin?uploadId=" + firstUploadId)
        .then().statusCode(404)
                .body(containsString("<Code>NoSuchUpload</Code>"));

        given().header("Authorization", authorization(ACCOUNT_ONE))
        .when().delete("/" + bucket)
        .then().statusCode(204);

        createBucket(ACCOUNT_ONE, bucket);
        given().header("Authorization", authorization(ACCOUNT_ONE))
        .when().get("/" + bucket + "/part.bin?uploadId=" + firstUploadId)
        .then().statusCode(404)
                .body(containsString("<Code>NoSuchUpload</Code>"));

        given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().get("/" + bucket + "/part.bin?uploadId=" + firstUploadId)
        .then().statusCode(404)
                .body(containsString("<Code>NoSuchUpload</Code>"));

        String secondUploadId = given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().post("/" + bucket + "/part.bin?uploads")
        .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().get("/" + bucket + "/part.bin?uploadId=" + secondUploadId)
        .then().statusCode(200)
                .body(containsString("<Owner><ID>" + ACCOUNT_TWO
                        + "</ID><DisplayName>floci</DisplayName></Owner>"));
    }

    private static void createBucket(String account, String bucket) {
        given().header("Authorization", authorization(account))
        .when().put("/" + bucket)
        .then().statusCode(200);
    }

    private static String authorization(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20261002/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
