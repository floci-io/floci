package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class S3DeleteObjectsIntegrationTest {

    @Test
    void deleteObjects_emptyKeyList_returnsMalformedXml() {
        String bucket = createBucket();

        given()
            .contentType("application/xml")
            .body("<Delete><Quiet>true</Quiet></Delete>")
        .when()
            .post("/" + bucket + "?delete")
        .then()
            .statusCode(400)
            .body(containsString("<Code>MalformedXML</Code>"));
    }

    @Test
    void deleteObjects_withKeys_deletesAndReportsResult() {
        String bucket = createBucket();
        putObject(bucket, "keep.txt");
        putObject(bucket, "remove.txt");

        given()
            .contentType("application/xml")
            .body("<Delete><Object><Key>remove.txt</Key></Object></Delete>")
        .when()
            .post("/" + bucket + "?delete")
        .then()
            .statusCode(200)
            .body(containsString("<Key>remove.txt</Key>"));

        given()
        .when()
            .get("/" + bucket + "/remove.txt")
        .then()
            .statusCode(404);

        given()
        .when()
            .get("/" + bucket + "/keep.txt")
        .then()
            .statusCode(200);
    }

    private static String createBucket() {
        String bucket = "delete-objects-" + UUID.randomUUID().toString().substring(0, 8);
        given()
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);
        return bucket;
    }

    private static void putObject(String bucket, String key) {
        given()
            .body("content")
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200);
    }
}
