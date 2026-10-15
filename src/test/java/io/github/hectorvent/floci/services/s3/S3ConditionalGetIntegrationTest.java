package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * A conditional GET or HEAD carrying an object's own Last-Modified, as a client revalidating a cached
 * copy sends it. Last-Modified has second precision, so the object has not been modified since itself.
 */
@QuarkusTest
class S3ConditionalGetIntegrationTest {

    @Test
    void getObject_ifModifiedSinceOwnLastModified_304() {
        String bucket = createBucket("get-if-modified-own");
        String eTag = putObject(bucket, "obj.txt", "body");
        String lastModified = lastModified(bucket, "obj.txt");

        given().header("If-Modified-Since", lastModified)
        .when().get("/" + bucket + "/obj.txt")
        .then().statusCode(304)
            .header("ETag", equalTo(eTag))
            .header("Last-Modified", equalTo(lastModified));
    }

    @Test
    void getObject_ifUnmodifiedSinceOwnLastModified_200() {
        String bucket = createBucket("get-if-unmodified-own");
        putObject(bucket, "obj.txt", "body");
        String lastModified = lastModified(bucket, "obj.txt");

        given().header("If-Unmodified-Since", lastModified)
        .when().get("/" + bucket + "/obj.txt")
        .then().statusCode(200).body(equalTo("body"));
    }

    @Test
    void headObject_ifModifiedSinceOwnLastModified_304() {
        String bucket = createBucket("head-if-modified-own");
        putObject(bucket, "obj.txt", "body");
        String lastModified = lastModified(bucket, "obj.txt");

        given().header("If-Modified-Since", lastModified)
        .when().head("/" + bucket + "/obj.txt")
        .then().statusCode(304);
    }

    @Test
    void headObject_ifUnmodifiedSinceOwnLastModified_200() {
        String bucket = createBucket("head-if-unmodified-own");
        putObject(bucket, "obj.txt", "body");
        String lastModified = lastModified(bucket, "obj.txt");

        given().header("If-Unmodified-Since", lastModified)
        .when().head("/" + bucket + "/obj.txt")
        .then().statusCode(200);
    }

    private static String createBucket(String label) {
        String bucket = label + "-" + UUID.randomUUID().toString().substring(0, 8);
        given().when().put("/" + bucket).then().statusCode(200);
        return bucket;
    }

    private static String putObject(String bucket, String key, String body) {
        return given().body(body).when().put("/" + bucket + "/" + key)
                .then().statusCode(200).extract().header("ETag");
    }

    private static String lastModified(String bucket, String key) {
        return given().when().head("/" + bucket + "/" + key)
                .then().statusCode(200).extract().header("Last-Modified");
    }
}
