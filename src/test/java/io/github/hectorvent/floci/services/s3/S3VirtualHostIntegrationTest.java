package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for virtual-hosted-style S3 requests.
 * Bucket name is sent via the Host header instead of the path.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3VirtualHostIntegrationTest {

    private static final String BUCKET = "vhost-bucket";
    private static final String HOST = BUCKET + ".localhost";

    @Test
    @Order(1)
    void createBucketViaVirtualHost() {
        given()
            .header("Host", HOST)
        .when()
            .put("/")
        .then()
            .statusCode(200)
            .header("Location", equalTo("/" + BUCKET));
    }

    @Test
    @Order(2)
    void headBucketViaVirtualHost() {
        given()
            .header("Host", HOST)
        .when()
            .head("/")
        .then()
            .statusCode(200)
            .header("x-amz-bucket-region", notNullValue());
    }

    @Test
    @Order(3)
    void putObjectViaVirtualHost() {
        given()
            .header("Host", HOST)
            .contentType("text/plain")
            .header("x-amz-meta-source", "virtual-host-test")
            .body("virtual hosted content")
        .when()
            .put("/hello.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());
    }

    @Test
    @Order(4)
    void getObjectViaVirtualHost() {
        given()
            .header("Host", HOST)
        .when()
            .get("/hello.txt")
        .then()
            .statusCode(200)
            .header("x-amz-meta-source", equalTo("virtual-host-test"))
            .body(equalTo("virtual hosted content"));
    }

    @Test
    @Order(5)
    void headObjectViaVirtualHost() {
        given()
            .header("Host", HOST)
        .when()
            .head("/hello.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .header("Content-Length", notNullValue());
    }

    @Test
    @Order(6)
    void putObjectWithNestedKeyViaVirtualHost() {
        given()
            .header("Host", HOST)
            .contentType("application/json")
            .body("{\"nested\": true}")
        .when()
            .put("/path/to/nested.json")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void listObjectsViaVirtualHost() {
        given()
            .header("Host", HOST)
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("hello.txt"))
            .body(containsString("path/to/nested.json"));
    }

    @Test
    @Order(8)
    void listObjectsWithPrefixViaVirtualHost() {
        given()
            .header("Host", HOST)
            .queryParam("prefix", "path/")
        .when()
            .get("/")
        .then()
            .statusCode(200)
            .body(containsString("path/to/nested.json"))
            .body(not(containsString("hello.txt")));
    }

    @Test
    @Order(9)
    void copyObjectViaVirtualHost() {
        given()
            .header("Host", HOST)
            .header("x-amz-copy-source", "/" + BUCKET + "/hello.txt")
        .when()
            .put("/hello-copy.txt")
        .then()
            .statusCode(200)
            .body(containsString("CopyObjectResult"));

        given()
            .header("Host", HOST)
        .when()
            .get("/hello-copy.txt")
        .then()
            .statusCode(200)
            .body(equalTo("virtual hosted content"));
    }

    @Test
    @Order(10)
    void deleteObjectViaVirtualHost() {
        given()
            .header("Host", HOST)
        .when()
            .delete("/hello-copy.txt")
        .then()
            .statusCode(204);

        given()
            .header("Host", HOST)
        .when()
            .get("/hello-copy.txt")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(11)
    void getObjectNotFoundViaVirtualHost() {
        given()
            .header("Host", HOST)
        .when()
            .get("/nonexistent.txt")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchKey"));
    }

    @Test
    @Order(12)
    void pathStyleAndVirtualHostSeeTheSameData() {
        // Object created via virtual-host should be visible via path-style
        given()
        .when()
            .get("/" + BUCKET + "/hello.txt")
        .then()
            .statusCode(200)
            .body(equalTo("virtual hosted content"));
    }

    @Test
    @Order(20)
    void cleanupAndDeleteBucket() {
        given().header("Host", HOST).delete("/hello.txt");
        given().header("Host", HOST).delete("/path/to/nested.json");

        given()
            .header("Host", HOST)
        .when()
            .delete("/")
        .then()
            .statusCode(204);
    }
}
