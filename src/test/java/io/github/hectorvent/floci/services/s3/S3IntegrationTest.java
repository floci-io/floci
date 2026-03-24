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
            .statusCode(200);
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
    @Order(50)
    void getObjectIfNoneMatchReturns304() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-None-Match", eTag)
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(304)
            .header("ETag", equalTo(eTag));
    }

    @Test
    @Order(51)
    void getObjectIfNoneMatchNonMatchingReturns200() {
        given()
            .header("If-None-Match", "\"wrong-etag\"")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .body(equalTo("Hello World from S3!"));
    }

    @Test
    @Order(52)
    void getObjectIfMatchReturns200() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-Match", eTag)
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .body(equalTo("Hello World from S3!"));
    }

    @Test
    @Order(53)
    void getObjectIfMatchWrongEtagReturns412() {
        given()
            .header("If-Match", "\"wrong-etag\"")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(412)
            .body(containsString("PreconditionFailed"));
    }

    @Test
    @Order(54)
    void headObjectIfNoneMatchReturns304() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-None-Match", eTag)
        .when()
            .head("/test-bucket/greeting.txt")
        .then()
            .statusCode(304);
    }

    @Test
    @Order(55)
    void getObjectIfModifiedSinceReturns304() {
        given()
            .header("If-Modified-Since", "Sun, 24 Mar 2030 00:00:00 GMT")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(304);
    }

    @Test
    @Order(56)
    void getObjectIfUnmodifiedSinceReturns412() {
        given()
            .header("If-Unmodified-Since", "Tue, 24 Mar 2020 00:00:00 GMT")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(412)
            .body(containsString("PreconditionFailed"));
    }

    @Test
    @Order(57)
    void getObjectIfMatchWildcardReturns200() {
        given()
            .header("If-Match", "*")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(200)
            .body(equalTo("Hello World from S3!"));
    }

    @Test
    @Order(58)
    void getObjectIfNoneMatchCommaListReturns304() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-None-Match", "\"wrong-etag\", " + eTag + ", \"other\"")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(304);
    }

    @Test
    @Order(59)
    void ifNoneMatchTakesPrecedenceOverIfModifiedSince() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-None-Match", eTag)
            .header("If-Modified-Since", "Tue, 24 Mar 2020 00:00:00 GMT")
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(304);
    }

    @Test
    @Order(60)
    void notModifiedResponseIncludesLastModified() {
        String eTag = given()
            .when().head("/test-bucket/greeting.txt")
            .then().statusCode(200)
            .extract().header("ETag");

        given()
            .header("If-None-Match", eTag)
        .when()
            .get("/test-bucket/greeting.txt")
        .then()
            .statusCode(304)
            .header("ETag", equalTo(eTag))
            .header("Last-Modified", notNullValue());
    }

    @Test
    @Order(100)
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
}
