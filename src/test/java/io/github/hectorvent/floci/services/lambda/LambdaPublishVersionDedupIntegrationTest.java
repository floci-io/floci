package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Issue #2822, the unchanged-publish half. "AWS Lambda doesn't publish a version if the function's
 * configuration and code haven't changed since the last version." Publishing was unconditional, so
 * repeated publishes accumulated identical versions.
 */
@QuarkusTest
class LambdaPublishVersionDedupIntegrationTest {

    private static String zipB64(String body) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("handler.py"));
            zos.write(("def handler(e, c):\n    return {'body': '" + body + "'}\n").getBytes("UTF-8"));
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static void createFunction(String name) throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {"FunctionName": "%s", "Runtime": "python3.12",
                 "Role": "arn:aws:iam::000000000000:role/r", "Handler": "handler.handler",
                 "Code": {"ZipFile": "%s"}}
                """.formatted(name, zipB64("v1")))
        .when().post("/2015-03-31/functions")
        .then().statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(201)));
    }

    private static String publish(String name, String bodyJson) {
        return given().contentType("application/json").body(bodyJson)
            .when().post("/2015-03-31/functions/" + name + "/versions")
            .then().statusCode(201).extract().path("Version");
    }

    @Test
    void publishingRepeatedlyWithNothingChangedReturnsTheExistingVersion() throws Exception {
        String fn = "pv-dedup-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn);

        String first = publish(fn, "{}");
        org.junit.jupiter.api.Assertions.assertEquals(first, publish(fn, "{}"),
                "an unchanged publish must return the existing version, not create a new one");
        org.junit.jupiter.api.Assertions.assertEquals(first, publish(fn, "{}"),
                "repeated unchanged publishes must keep returning the same version");

        given().when().get("/2015-03-31/functions/" + fn + "/versions")
            .then().statusCode(200)
            .body("Versions.size()", equalTo(2));   // $LATEST plus the single published version
    }

    @Test
    void aRealChangeStillProducesANewVersion() throws Exception {
        String fn = "pv-change-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn);
        String first = publish(fn, "{}");

        given().contentType("application/json")
            .body("{\"ZipFile\": \"%s\"}".formatted(zipB64("v2")))
        .when().put("/2015-03-31/functions/" + fn + "/code").then().statusCode(200);
        String second = publish(fn, "{}");
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second,
                "a code change must still produce a new version");

        // Configuration counts too, since a version snapshots configuration as well as code.
        given().contentType("application/json").body("{\"Timeout\": 42}")
        .when().put("/2015-03-31/functions/" + fn + "/configuration").then().statusCode(200);
        String third = publish(fn, "{}");
        org.junit.jupiter.api.Assertions.assertNotEquals(second, third,
                "a configuration change must still produce a new version");
    }

    @Test
    void aDescriptionReturningToAnEarlierValueStillPublishes() throws Exception {
        // Only the most recent version counts. AWS compares against the last version, so A then B
        // then A must publish a third time. Matching any historical version would hand back version
        // 1 and leave the caller holding a version whose place in the history is wrong.
        String fn = "pv-abab-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn);

        String a1 = publish(fn, "{\"Description\": \"A\"}");
        String b = publish(fn, "{\"Description\": \"B\"}");
        String a2 = publish(fn, "{\"Description\": \"A\"}");

        org.junit.jupiter.api.Assertions.assertNotEquals(a1, b);
        org.junit.jupiter.api.Assertions.assertNotEquals(a1, a2,
                "returning to an earlier description must publish, not match the older version");
        org.junit.jupiter.api.Assertions.assertNotEquals(b, a2);
    }
}
