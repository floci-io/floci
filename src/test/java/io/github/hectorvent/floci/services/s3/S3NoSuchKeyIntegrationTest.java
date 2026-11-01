package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class S3NoSuchKeyIntegrationTest {
    private static final String BUCKET = "no-such-key-names-the-key";
    private static final String KEY = "dir/missing.txt";
    private static final String PATH = "/" + BUCKET + "/" + KEY;

    @Test
    void noSuchKeyNamesTheKey() {
        given().when().put("/" + BUCKET).then().statusCode(200);

        given().when().get(PATH).then().statusCode(404)
                .body("Error.Code", equalTo("NoSuchKey"))
                .body("Error.Key", equalTo(KEY));
        given().when().get(PATH + "?acl").then().statusCode(404).body("Error.Key", equalTo(KEY));
        given().header("x-amz-object-attributes", "ETag").when().get(PATH + "?attributes")
                .then().statusCode(404).body("Error.Key", equalTo(KEY));
        given().body("<Tagging><TagSet></TagSet></Tagging>").when().put(PATH + "?tagging")
                .then().statusCode(404).body("Error.Key", equalTo(KEY));
        given().when().delete(PATH + "?tagging").then().statusCode(404).body("Error.Key", equalTo(KEY));
        given().when().get(PATH + "?annotation&annotationName=labels")
                .then().statusCode(404).body("Error.Key", equalTo(KEY));
        given().header("If-Match", "*").when().delete(PATH).then().statusCode(404).body("Error.Key", equalTo(KEY));
        given().header("x-amz-copy-source", PATH).when().put("/" + BUCKET + "/copy")
                .then().statusCode(404).body("Error.Key", equalTo(KEY));
        // GetObjectTagging names the key with its bucket.
        given().when().get(PATH + "?tagging").then().statusCode(404).body("Error.Key", equalTo(BUCKET + "/" + KEY));
    }
}
