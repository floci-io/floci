package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsCanonicalUserId;
import io.github.hectorvent.floci.testing.S3TestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Owner;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class S3OwnerIdentityIntegrationTest {

    private static final String ACCOUNT_ONE = "000000000011";
    private static final String ACCOUNT_TWO = "000000000012";

    @Test
    void listObjectsV2FetchOwnerIncludesCanonicalOwnerOnlyWhenRequested() {
        String bucket = "owner-identity-list-v2-" + UUID.randomUUID();
        String key = "key.txt";
        try (S3Client client = S3TestClients.pathStyle()) {
            client.createBucket(request -> request.bucket(bucket));
            try {
                client.putObject(request -> request.bucket(bucket).key(key), RequestBody.fromString("x"));

                assertNull(client.listObjectsV2(request -> request.bucket(bucket))
                        .contents().getFirst().owner());
                assertNull(client.listObjectsV2(request -> request.bucket(bucket).fetchOwner(false))
                        .contents().getFirst().owner());

                Owner v1Owner = client.listObjects(request -> request.bucket(bucket))
                        .contents().getFirst().owner();
                assertNotNull(v1Owner);
                assertEquals(AwsCanonicalUserId.forAccount("741624100001"), v1Owner.id());

                Owner owner = client.listObjectsV2(request -> request.bucket(bucket).fetchOwner(true))
                        .contents().getFirst().owner();
                assertNotNull(owner);
                assertEquals(AwsCanonicalUserId.forAccount("741624100001"), owner.id());
                assertEquals("floci", owner.displayName());
            } finally {
                client.deleteObject(request -> request.bucket(bucket).key(key));
                client.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    @Test
    void listObjectsV2FetchOwnerUsesTheBucketAccountsCanonicalUserId() {
        String bucket = "owner-account-v2-" + UUID.randomUUID();
        String key = "key.txt";
        createBucket(ACCOUNT_ONE, bucket);
        try {
            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().put("/" + bucket + "/" + key)
            .then().statusCode(200);

            given().header("Authorization", authorization(ACCOUNT_ONE))
                    .queryParam("list-type", "2")
            .when().get("/" + bucket)
            .then().statusCode(200)
                    .body(containsString("<Key>" + key + "</Key>"))
                    .body(not(containsString("<Owner>")));

            given().header("Authorization", authorization(ACCOUNT_ONE))
                    .queryParam("list-type", "2")
                    .queryParam("fetch-owner", "true")
            .when().get("/" + bucket)
            .then().statusCode(200)
                    .body(containsString("<Key>" + key + "</Key>"))
                    .body(containsString("<Owner><ID>" + canonical(ACCOUNT_ONE)
                            + "</ID><DisplayName>floci</DisplayName></Owner>"));
        } finally {
            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().delete("/" + bucket + "/" + key)
            .then().statusCode(204);
            given().header("Authorization", authorization(ACCOUNT_ONE))
            .when().delete("/" + bucket)
            .then().statusCode(204);
        }
    }

    @Test
    void listBucketsOwnerIsTheCallersCanonicalUserId() {
        String bucket = "owner-identity-list-bucket";
        createBucket(ACCOUNT_ONE, bucket);
        String canonicalOne = canonical(ACCOUNT_ONE);

        given().header("Authorization", authorization(ACCOUNT_ONE))
        .when().get("/")
        .then().statusCode(200)
                .body(containsString("<Owner><ID>" + canonicalOne
                        + "</ID><DisplayName>floci</DisplayName></Owner>"))
                .body(matchesPattern(".*<Owner><ID>[0-9a-f]{64}</ID>.*"))
                .body(containsString("<Name>" + bucket + "</Name>"));

        given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().get("/")
        .then().statusCode(200)
                .body(containsString("<Owner><ID>" + canonical(ACCOUNT_TWO)
                        + "</ID><DisplayName>floci</DisplayName></Owner>"))
                .body(not(containsString("<Owner><ID>" + canonicalOne + "</ID>")))
                .body(not(containsString("<Name>" + bucket + "</Name>")));
    }

    @Test
    void defaultBucketAclNamesTheOwnerByCanonicalUserId() {
        String bucket = "owner-identity-bucket-acl";
        createBucket(ACCOUNT_ONE, bucket);
        String canonicalOne = canonical(ACCOUNT_ONE);

        given().header("Authorization", authorization(ACCOUNT_ONE))
        .when().get("/" + bucket + "?acl")
        .then().statusCode(200)
                .body(containsString("<Owner><ID>" + canonicalOne
                        + "</ID><DisplayName>floci</DisplayName></Owner>"))
                .body(containsString("xsi:type=\"CanonicalUser\"><ID>" + canonicalOne + "</ID>"))
                .body(not(containsString("<ID>" + ACCOUNT_ONE + "</ID>")));
    }

    @Test
    void defaultObjectAclNamesTheOwnerByCanonicalUserId() {
        String bucket = "owner-identity-object-acl";
        createBucket(ACCOUNT_ONE, bucket);

        given().header("Authorization", authorization(ACCOUNT_ONE))
        .when().put("/" + bucket + "/key.txt")
        .then().statusCode(200);

        String canonicalOne = canonical(ACCOUNT_ONE);
        given().header("Authorization", authorization(ACCOUNT_ONE))
        .when().get("/" + bucket + "/key.txt?acl")
        .then().statusCode(200)
                .body(containsString("<Owner><ID>" + canonicalOne
                        + "</ID><DisplayName>floci</DisplayName></Owner>"))
                .body(containsString("xsi:type=\"CanonicalUser\"><ID>" + canonicalOne + "</ID>"))
                .body(not(containsString("<ID>" + ACCOUNT_ONE + "</ID>")));
    }

    @Test
    void cannedObjectAclKeepsOnlyTheOwnerGrantCanonical() {
        String bucket = "owner-identity-canned-acl";
        createBucket(ACCOUNT_TWO, bucket);

        given().header("Authorization", authorization(ACCOUNT_TWO))
                .body("<OwnershipControls><Rule><ObjectOwnership>ObjectWriter</ObjectOwnership></Rule></OwnershipControls>")
        .when().put("/" + bucket + "?ownershipControls")
        .then().statusCode(200);

        given().header("Authorization", authorization(ACCOUNT_TWO))
                .header("x-amz-acl", "public-read")
        .when().put("/" + bucket + "/public.txt")
        .then().statusCode(200);

        String canonicalTwo = canonical(ACCOUNT_TWO);
        given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().get("/" + bucket + "/public.txt?acl")
        .then().statusCode(200)
                .body(containsString("<Owner><ID>" + canonicalTwo
                        + "</ID><DisplayName>floci</DisplayName></Owner>"))
                .body(containsString("xsi:type=\"CanonicalUser\"><ID>" + canonicalTwo + "</ID>"))
                .body(containsString("<URI>http://acs.amazonaws.com/groups/global/AllUsers</URI>"))
                .body(not(containsString("<ID>" + ACCOUNT_TWO + "</ID>")));
    }

    @Test
    void listPartsOwnerAndInitiatorUseTheUploadAccountsCanonicalUserId() {
        String bucket = "owner-identity-parts-bucket";
        createBucket(ACCOUNT_TWO, bucket);
        String canonicalTwo = canonical(ACCOUNT_TWO);

        String uploadId = given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().post("/" + bucket + "/part.bin?uploads")
        .then().statusCode(200)
                .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");

        given().header("Authorization", authorization(ACCOUNT_TWO))
        .when().get("/" + bucket + "/part.bin?uploadId=" + uploadId)
        .then().statusCode(200)
                .body(containsString("<Initiator><ID>" + canonicalTwo
                        + "</ID><DisplayName>floci</DisplayName></Initiator>"))
                .body(containsString("<Owner><ID>" + canonicalTwo
                        + "</ID><DisplayName>floci</DisplayName></Owner>"))
                .body(not(containsString("<ID>" + ACCOUNT_TWO + "</ID>")));
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
                .body(containsString("<Owner><ID>" + canonical(ACCOUNT_TWO)
                        + "</ID><DisplayName>floci</DisplayName></Owner>"));
    }

    private static String canonical(String account) {
        return AwsCanonicalUserId.forAccount(account);
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
