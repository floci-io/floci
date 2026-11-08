package io.floci.aws.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * These tests verify that normal S3 object operations (keys without leading slashes)
 * continue to work correctly. They establish a baseline on UNFIXED code and must
 * continue to pass after the leading-slash key collision fix is applied.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3PreservationTest {

    private static final String BUCKET = "preserve-bucket";

    // --- Setup ---

    @Test
    @Order(0)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    // --- Test 1: Normal Key PUT/GET ---
    // For keys without leading slashes, PUT content and GET returns identical content with correct ETag.

    @Test
    @Order(1)
    void normalKeyPutGet_simpleKey() {
        String content = "hello-simple";

        // PUT
        given()
            .contentType("text/plain")
            .body(content)
        .when()
            .put("/" + BUCKET + "/simple.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());

        // GET
        given()
        .when()
            .get("/" + BUCKET + "/simple.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .body(equalTo(content));
    }

    @Test
    @Order(2)
    void normalKeyPutGet_nestedKey() {
        String content = "hello-nested";

        // PUT with nested path key
        given()
            .contentType("text/plain")
            .body(content)
        .when()
            .put("/" + BUCKET + "/a/b/c.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());

        // GET
        given()
        .when()
            .get("/" + BUCKET + "/a/b/c.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .body(equalTo(content));
    }

    @Test
    @Order(3)
    void normalKeyPutGet_hyphenatedKey() {
        String content = "hello-hyphen";

        // PUT with hyphenated key
        given()
            .contentType("text/plain")
            .body(content)
        .when()
            .put("/" + BUCKET + "/my-key")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());

        // GET
        given()
        .when()
            .get("/" + BUCKET + "/my-key")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .body(equalTo(content));
    }

    // --- Test 2: Interior Slash Preservation ---
    // For keys with interior slashes, PUT and GET round-trip correctly.

    @Test
    @Order(4)
    void interiorSlashPreservation_pathToFile() {
        String content = "interior-slash-content";

        // PUT
        given()
            .contentType("text/plain")
            .body(content)
        .when()
            .put("/" + BUCKET + "/path/to/file.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());

        // GET
        given()
        .when()
            .get("/" + BUCKET + "/path/to/file.txt")
        .then()
            .statusCode(200)
            .body(equalTo(content));
    }

    @Test
    @Order(5)
    void interiorSlashPreservation_deeplyNestedPath() {
        String content = "deep-nested-content";

        // PUT
        given()
            .contentType("text/plain")
            .body(content)
        .when()
            .put("/" + BUCKET + "/a/b/c/d.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());

        // GET
        given()
        .when()
            .get("/" + BUCKET + "/a/b/c/d.txt")
        .then()
            .statusCode(200)
            .body(equalTo(content));
    }

    // --- Test 3: HEAD Normal Key ---
    // For normal keys, HEAD returns correct Content-Length and Content-Type matching the PUT.

    @Test
    @Order(6)
    void headNormalKey_returnsCorrectMetadata() {
        String content = "head-test-content-12345";

        // PUT
        given()
            .contentType("text/plain")
            .body(content)
        .when()
            .put("/" + BUCKET + "/head-test.txt")
        .then()
            .statusCode(200);

        // HEAD
        given()
        .when()
            .head("/" + BUCKET + "/head-test.txt")
        .then()
            .statusCode(200)
            .header("Content-Length", equalTo(String.valueOf(content.length())))
            .header("Content-Type", containsString("text/plain"));
    }

    // --- Test 4: DELETE Normal Key ---
    // For normal keys, DELETE removes the object and subsequent GET returns 404.

    @Test
    @Order(7)
    void deleteNormalKey_removesObjectAndGetReturns404() {
        String content = "delete-me";

        // PUT
        given()
            .contentType("text/plain")
            .body(content)
        .when()
            .put("/" + BUCKET + "/to-delete.txt")
        .then()
            .statusCode(200);

        // Verify it exists
        given()
        .when()
            .get("/" + BUCKET + "/to-delete.txt")
        .then()
            .statusCode(200)
            .body(equalTo(content));

        // DELETE
        given()
        .when()
            .delete("/" + BUCKET + "/to-delete.txt")
        .then()
            .statusCode(204);

        // GET should now return 404
        given()
        .when()
            .get("/" + BUCKET + "/to-delete.txt")
        .then()
            .statusCode(404);
    }

    // --- Test 5: List Objects Normal Keys ---
    // PUT multiple normal-key objects, list returns all with correct keys.

    @Test
    @Order(8)
    void listObjectsNormalKeys_returnsAllPutObjects() {
        String listBucket = "preserve-list-bucket";

        // Create bucket
        given()
        .when()
            .put("/" + listBucket)
        .then()
            .statusCode(200);

        // PUT three objects
        given()
            .contentType("text/plain")
            .body("alpha")
        .when()
            .put("/" + listBucket + "/alpha.txt")
        .then()
            .statusCode(200);

        given()
            .contentType("text/plain")
            .body("beta")
        .when()
            .put("/" + listBucket + "/beta.txt")
        .then()
            .statusCode(200);

        given()
            .contentType("text/plain")
            .body("gamma")
        .when()
            .put("/" + listBucket + "/nested/gamma.txt")
        .then()
            .statusCode(200);

        // List objects
        given()
        .when()
            .get("/" + listBucket)
        .then()
            .statusCode(200)
            .body(containsString("<Key>alpha.txt</Key>"))
            .body(containsString("<Key>beta.txt</Key>"))
            .body(containsString("<Key>nested/gamma.txt</Key>"));
    }
}
