package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3IntegrationTest {

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/test-bucket")
        .then()
            .statusCode(200)
            .header("Location", equalTo("/test-bucket"));
    }

    @Test
    @Order(2)
    void createDuplicateBucketFails() {
        given()
        .when()
            .put("/test-bucket")
        .then()
            .statusCode(409)
            .body(containsString("BucketAlreadyOwnedByYou"));
    }

    @Test
    @Order(3)
    void listBuckets() {
        given()
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("test-bucket"));
    }

    @Test
    @Order(4)
    void putObject() {
        given()
            .contentType("text/plain")
            .header("x-amz-meta-owner", "team-a")
            .header("x-amz-storage-class", "STANDARD_IA")
            .body("Hello World from S3!")
        .when()
            .put("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
    }

    @Test
    @Order(5)
    void getObject() {
        given()
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("Content-Length", notNullValue())
            .header("x-amz-meta-owner", equalTo("team-a"))
            .header("x-amz-storage-class", equalTo("STANDARD_IA"))
            .header("x-amz-checksum-sha256", notNullValue())
            .body(equalTo("Hello World from S3!"));
    }

    @Test
    @Order(6)
    void getObjectAttributes() {
        given()
            .header("x-amz-object-attributes", "ETag,ObjectSize,StorageClass,Checksum")
        .when()
            .get("/test-bucket/greeting.txt?attributes")
        .then()
            .statusCode(200)
            .body(containsString("<GetObjectAttributesResponse"))
            .body(containsString("<StorageClass>STANDARD_IA</StorageClass>"))
            .body(containsString("<ObjectSize>20</ObjectSize>"))
            .body(containsString("<ChecksumSHA256>"));
    }

    @Test
    @Order(7)
    void headObject() {
        given()
        .when()
            .head("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("Content-Length", notNullValue())
            .header("x-amz-meta-owner", equalTo("team-a"))
            .header("x-amz-storage-class", equalTo("STANDARD_IA"))
            .header("x-amz-checksum-sha256", notNullValue());
    }

    @Test
    @Order(8)
    void getObjectNotFound() {
        given()
        .when()
            .get("/test-bucket/nonexistent.txt")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchKey"));
    }

    @Test
    @Order(9)
    void putAnotherObject() {
        given()
            .contentType("application/json")
            .body("{\"key\": \"value\"}")
        .when()
            .put("/test-bucket/data/config.json")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(10)
    void listObjects() {
        given()
        .when()
            .get("/test-bucket")
        .then()
            .statusCode(200)
            .body(containsString("greeting.txt"))
            .body(containsString("data/config.json"));
    }

    @Test
    @Order(11)
    void listObjectsWithPrefix() {
        given()
            .queryParam("prefix", "data/")
        .when()
            .get("/test-bucket")
        .then()
            .statusCode(200)
            .body(containsString("data/config.json"))
            .body(not(containsString("greeting.txt")));
    }

    @Test
    @Order(12)
    void copyObject() {
        given()
            .header("x-amz-copy-source", "/test-bucket/greeting.txt")
            .header("x-amz-metadata-directive", "REPLACE")
            .header("x-amz-meta-owner", "team-b")
            .header("x-amz-storage-class", "GLACIER")
            .contentType("application/json")
        .when()
            .put("/test-bucket/greeting-copy.txt")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        // Verify the copy
        given()
        .when()
            .get("/test-bucket/greeting-copy.txt")
        .then()
            .statusCode(200)
            .header("x-amz-meta-owner", equalTo("team-b"))
            .header("x-amz-storage-class", equalTo("GLACIER"))
            .body(equalTo("Hello World from S3!"));
    }

    @Test
    @Order(13)
    void deleteObject() {
        given()
        .when()
            .delete("/test-bucket/greeting-copy.txt")
        .then()
            .statusCode(204);

        // Verify it's gone
        given()
        .when()
            .get("/test-bucket/greeting-copy.txt")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(14)
    void deleteNonEmptyBucketFails() {
        given()
        .when()
            .delete("/test-bucket")
        .then()
            .statusCode(409)
            .body(containsString("BucketNotEmpty"));
    }

    @Test
    @Order(16)
    void cleanupAndDeleteBucket() {
        // Delete all objects
        given().delete("/test-bucket/greeting.txt");
        given().delete("/test-bucket/data/config.json");

        // Now delete bucket
        given()
        .when()
            .delete("/test-bucket")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(15)
    void getObjectAttributesRejectsUnknownSelector() {
        given()
            .header("x-amz-object-attributes", "ETag,UnknownThing")
        .when()
            .get("/test-bucket/greeting.txt?attributes")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));
    }

    @Test
    void getNonExistentBucket() {
        given()
        .when()
            .get("/nonexistent-bucket")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    @Test
    @Order(17)
    void headBucketReturnsStoredRegionForLocationConstraintBucket() {
        String bucket = "eu-head-bucket";
        String createBucketConfiguration = """
                <CreateBucketConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <LocationConstraint>eu-central-1</LocationConstraint>
                </CreateBucketConfiguration>
                """;

        given()
            .contentType("application/xml")
            .body(createBucketConfiguration)
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200)
            .header("Location", equalTo("/" + bucket));

        given()
        .when()
            .head("/" + bucket)
        .then()
            .statusCode(200)
            .header("x-amz-bucket-region", equalTo("eu-central-1"));

        given()
        .when()
            .delete("/" + bucket)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(18)
    void createBucketUsesSigningRegionWhenBodyEmpty() {
        String bucket = "signed-region-bucket";

        given()
            .header("Authorization",
                    "AWS4-HMAC-SHA256 Credential=test/20260325/eu-west-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=test")
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200)
            .header("Location", equalTo("/" + bucket));

        given()
        .when()
            .head("/" + bucket)
        .then()
            .statusCode(200)
            .header("x-amz-bucket-region", equalTo("eu-west-1"));

        given()
        .when()
            .delete("/" + bucket)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(19)
    void createBucketRejectsUsEast1LocationConstraint() {
        String createBucketConfiguration = """
                <CreateBucketConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <LocationConstraint>us-east-1</LocationConstraint>
                </CreateBucketConfiguration>
                """;

        given()
            .contentType("application/xml")
            .body(createBucketConfiguration)
        .when()
            .put("/invalid-location-bucket")
        .then()
            .statusCode(400)
            .body(containsString("InvalidLocationConstraint"));
    }

    @Test
    @Order(20)
    void copyObjectWithNonAsciiKeySucceeds() {
        String bucket = "copy-nonascii-bucket";
        String srcKey = "src/テスト画像.png";
        String dstKey = "dst/テスト画像.png";
        String encodedSrcKey = "src/%E3%83%86%E3%82%B9%E3%83%88%E7%94%BB%E5%83%8F.png";

        given().put("/" + bucket).then().statusCode(200);

        given()
            .contentType("application/octet-stream")
            .body("hello".getBytes())
        .when()
            .put("/" + bucket + "/" + srcKey)
        .then()
            .statusCode(200);

        given()
            .header("x-amz-copy-source", "/" + bucket + "/" + encodedSrcKey)
        .when()
            .put("/" + bucket + "/" + dstKey)
        .then()
            .statusCode(200)
            .body(containsString("ETag"));

        given()
        .when()
            .get("/" + bucket + "/" + dstKey)
        .then()
            .statusCode(200)
            .body(equalTo("hello"));

        given().delete("/" + bucket + "/" + srcKey);
        given().delete("/" + bucket + "/" + dstKey);
        given().delete("/" + bucket);
    }
}
