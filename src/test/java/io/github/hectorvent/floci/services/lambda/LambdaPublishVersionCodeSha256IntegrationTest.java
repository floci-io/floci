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
 * Issue #2822, the CodeSha256 half. The field is a precondition: "only publish a version if the
 * hash value matches the value that's specified". It was never parsed out of the request, so a
 * publish that should have been rejected succeeded and produced a version, which is how a caller
 * racing someone else's deploy ends up publishing code it never authorised.
 */
@QuarkusTest
class LambdaPublishVersionCodeSha256IntegrationTest {

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

    @Test
    void aMismatchedCodeSha256IsRejectedAndPublishesNothing() throws Exception {
        String fn = "pv-sha-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn);

        given()
            .contentType("application/json")
            .body("{\"CodeSha256\": \"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then()
            .statusCode(400)
            .body("message", containsString("different from current CodeSHA256"));

        // A rejected publish must not have produced a version: only $LATEST should be listed.
        given().when().get("/2015-03-31/functions/" + fn + "/versions")
            .then().statusCode(200).body("Versions.size()", equalTo(1));
    }

    @Test
    void aMatchingCodeSha256Publishes() throws Exception {
        String fn = "pv-sha-ok-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn);

        String sha = given().when().get("/2015-03-31/functions/" + fn + "/configuration")
                .then().statusCode(200).extract().path("CodeSha256");

        given()
            .contentType("application/json")
            .body("{\"CodeSha256\": \"%s\"}".formatted(sha))
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(201).body("Version", not(equalTo("$LATEST")));
    }

    @Test
    void omittingCodeSha256StillPublishes() throws Exception {
        // The precondition is opt in: a request without the field must behave exactly as before.
        String fn = "pv-sha-none-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn);

        given().contentType("application/json").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(201).body("Version", equalTo("1"));
    }

    @Test
    void aMalformedBodyIsRejectedRatherThanPublished() throws Exception {
        // The parse failure used to be swallowed, so a body whose CodeSha256 could not be read was
        // treated as one that never sent it: the precondition was skipped and a version published.
        String fn = "pv-badbody-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn);

        given().contentType("application/json").body("{\"CodeSha256\": ")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(400);

        given().when().get("/2015-03-31/functions/" + fn + "/versions")
            .then().statusCode(200).body("Versions.size()", equalTo(1));
    }

    @Test
    void aNonStringCodeSha256IsRejectedRatherThanIgnored() throws Exception {
        // A cast failure was swallowed the same way, so a hash sent as a number checked nothing.
        String fn = "pv-badtype-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn);

        given().contentType("application/json").body("{\"CodeSha256\": 12345}")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(400);

        given().when().get("/2015-03-31/functions/" + fn + "/versions")
            .then().statusCode(200).body("Versions.size()", equalTo(1));
    }

    @Test
    void aNullJsonBodyIsRejectedRatherThanPublished() throws Exception {
        // "null" is valid JSON and parses cleanly to a null map, so it never reaches the parse
        // failure path. Without a guard the field reads would throw a NullPointerException, and a
        // guard placed inside the try would be caught and reported as a parse failure it is not.
        String fn = "pv-nullbody-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn);

        given().contentType("application/json").body("null")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(400);

        given().when().get("/2015-03-31/functions/" + fn + "/versions")
            .then().statusCode(200).body("Versions.size()", equalTo(1));
    }

    @Test
    void aBlankCodeSha256IsRejectedRatherThanTreatedAsOmitted() throws Exception {
        // An explicit empty string is a present value, not an absent one. It was excluded from the
        // comparison by an isBlank() check and so published without checking anything. It is now
        // compared like any other value and fails as a mismatch, rather than getting an error shape
        // invented for the empty case that has not been measured against the live service.
        String fn = "pv-blank-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn);

        given().contentType("application/json").body("{\"CodeSha256\": \"\"}")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(400)
            .body("message", containsString("different from current CodeSHA256"));

        given().when().get("/2015-03-31/functions/" + fn + "/versions")
            .then().statusCode(200).body("Versions.size()", equalTo(1));
    }
}
