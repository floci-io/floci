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
class S3EncryptionIntegrationTest {

    private static final String BUCKET = "encryption-int-test";
    private static final String SSE_KMS_XML = """
            <ServerSideEncryptionConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Rule>
                    <ApplyServerSideEncryptionByDefault>
                        <SSEAlgorithm>aws:kms</SSEAlgorithm>
                        <KMSMasterKeyID>arn:aws:kms:us-east-1:000000000000:key/abc</KMSMasterKeyID>
                    </ApplyServerSideEncryptionByDefault>
                    <BucketKeyEnabled>true</BucketKeyEnabled>
                </Rule>
            </ServerSideEncryptionConfiguration>
            """;

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    /**
     * Since January 2023 AWS applies SSE-S3 (AES256) as the base level of encryption on every
     * bucket, and {@code GetBucketEncryption} returns that default configuration rather than
     * {@code 404 ServerSideEncryptionConfigurationNotFoundError}. The ACK s3-controller reads
     * {@code getEncryptionResponse.ServerSideEncryptionConfiguration.Rules} without a nil guard,
     * so a 404 here crashes it; returning the default keeps Floci AWS-faithful and unblocks ACK.
     */
    @Test
    @Order(2)
    void getEncryptionBeforePutReturnsDefaultSseS3() {
        given()
        .when()
            .get("/" + BUCKET + "?encryption")
        .then()
            .statusCode(200)
            .body(containsString("<ServerSideEncryptionConfiguration"))
            .body(containsString("<SSEAlgorithm>AES256</SSEAlgorithm>"))
            .body(not(containsString("ServerSideEncryptionConfigurationNotFoundError")));
    }

    @Test
    @Order(3)
    void putEncryptionReturns200() {
        given()
            .body(SSE_KMS_XML)
        .when()
            .put("/" + BUCKET + "?encryption")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void getEncryptionReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?encryption")
        .then()
            .statusCode(200)
            .body(containsString("<SSEAlgorithm>aws:kms</SSEAlgorithm>"));
    }

    @Test
    @Order(5)
    void getEncryptionOnMissingBucketReturns404() {
        given()
        .when()
            .get("/this-bucket-does-not-exist-enc?encryption")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    /**
     * After DeleteBucketEncryption clears the explicit config, GetBucketEncryption must fall back
     * to the AWS default SSE-S3 (AES256) — not the previously stored KMS config and not a 404.
     */
    @Test
    @Order(6)
    void getEncryptionAfterDeleteReturnsDefaultSseS3() {
        given()
        .when()
            .delete("/" + BUCKET + "?encryption")
        .then()
            .statusCode(204);
        given()
        .when()
            .get("/" + BUCKET + "?encryption")
        .then()
            .statusCode(200)
            .body(containsString("<SSEAlgorithm>AES256</SSEAlgorithm>"))
            .body(not(containsString("aws:kms")));
    }
}
