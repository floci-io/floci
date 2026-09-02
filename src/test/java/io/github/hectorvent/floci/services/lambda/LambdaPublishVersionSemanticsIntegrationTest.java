package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Issue #2822: PublishVersion published unconditionally and ignored CodeSha256.
 *
 * <p>Repeated publishes accumulated identical versions, and the precondition that exists to stop a
 * caller publishing code it did not intend was silently ignored, so a publish that should have been
 * rejected succeeded and produced a version.
 */
@QuarkusTest
class LambdaPublishVersionSemanticsIntegrationTest {

    private static String zipB64(String body) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("handler.py"));
            zos.write(("def handler(e, c):\n    return {'body': '" + body + "'}\n").getBytes("UTF-8"));
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static void createFunction(String name, String body) throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {"FunctionName": "%s", "Runtime": "python3.12",
                 "Role": "arn:aws:iam::000000000000:role/r", "Handler": "handler.handler",
                 "Code": {"ZipFile": "%s"}}
                """.formatted(name, zipB64(body)))
        .when().post("/2015-03-31/functions")
        .then().statusCode(org.hamcrest.Matchers.anyOf(equalTo(200), equalTo(201)));
    }

    private static String publish(String name, String bodyJson) {
        return given().contentType("application/json").body(bodyJson)
            .when().post("/2015-03-31/functions/" + name + "/versions")
            .then().statusCode(201)
            .extract().path("Version");
    }

    @Test
    void publishingTwiceWithNothingChangedReturnsTheExistingVersion() throws Exception {
        String fn = "pv-dedup-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn, "v1");

        String first = publish(fn, "{}");
        String second = publish(fn, "{}");
        String third = publish(fn, "{}");

        assertSame(first, second, third);

        // And the version list has not grown a duplicate for each attempt.
        given().when().get("/2015-03-31/functions/" + fn + "/versions")
            .then().statusCode(200)
            .body("Versions.size()", equalTo(2));   // $LATEST plus the single published version
    }

    private static void assertSame(String first, String second, String third) {
        org.junit.jupiter.api.Assertions.assertEquals(first, second,
                "an unchanged publish must return the existing version, not create a new one");
        org.junit.jupiter.api.Assertions.assertEquals(first, third,
                "repeated unchanged publishes must keep returning the same version");
    }

    @Test
    void aRealChangeStillProducesANewVersion() throws Exception {
        String fn = "pv-change-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn, "v1");
        String first = publish(fn, "{}");

        given()
            .contentType("application/json")
            .body("{\"ZipFile\": \"%s\"}".formatted(zipB64("v2")))
        .when().put("/2015-03-31/functions/" + fn + "/code")
        .then().statusCode(200);

        String second = publish(fn, "{}");
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second,
                "a code change must still produce a new version");

        // A configuration-only change counts too, since a version snapshots configuration as well.
        given().contentType("application/json").body("{\"Timeout\": 42}")
        .when().put("/2015-03-31/functions/" + fn + "/configuration")
        .then().statusCode(200);

        String third = publish(fn, "{}");
        org.junit.jupiter.api.Assertions.assertNotEquals(second, third,
                "a configuration change must still produce a new version");
    }

    @Test
    void aMismatchedCodeSha256IsRejectedAndPublishesNothing() throws Exception {
        String fn = "pv-sha-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn, "v1");

        given()
            .contentType("application/json")
            .body("{\"CodeSha256\": \"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then()
            .statusCode(400)
            .body("message", containsString("different from current CodeSHA256"));

        // The rejected publish must not have produced a version.
        given().when().get("/2015-03-31/functions/" + fn + "/versions")
            .then().statusCode(200).body("Versions.size()", equalTo(1));
    }

    @Test
    void aMatchingCodeSha256Publishes() throws Exception {
        String fn = "pv-sha-ok-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn, "v1");

        String sha = given().when().get("/2015-03-31/functions/" + fn + "/configuration")
                .then().statusCode(200).extract().path("CodeSha256");

        given()
            .contentType("application/json")
            .body("{\"CodeSha256\": \"%s\"}".formatted(sha))
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then()
            .statusCode(201)
            .body("Version", not(equalTo("$LATEST")));
    }
}
