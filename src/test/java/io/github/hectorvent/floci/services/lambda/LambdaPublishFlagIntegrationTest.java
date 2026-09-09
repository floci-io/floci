package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * The Publish flag on CreateFunction and UpdateFunctionCode.
 *
 * <p>Response shapes here were measured against the live service, which is not consistent
 * between the two: CreateFunction reports the published version but answers with the
 * unqualified ARN, while UpdateFunctionCode returns the qualified one.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaPublishFlagIntegrationTest {

    private static final String FN = "publish-flag-fn";
    private static final String FN_NO_PUBLISH = "publish-flag-nopub-fn";
    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    private static String zipBase64(String marker) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("handler.py"));
            zos.write(("def handler(e, c):\n    return '" + marker + "'\n").getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String createBody(String name, String marker, boolean publish) throws Exception {
        return """
            {
                "FunctionName": "%s",
                "Runtime": "python3.12",
                "Role": "%s",
                "Handler": "handler.handler",
                "Code": { "ZipFile": "%s" },
                "Publish": %s
            }
            """.formatted(name, ROLE, zipBase64(marker), publish);
    }

    @Test
    @Order(1)
    void createWithPublishReturnsVersionOneAndAnUnqualifiedArn() throws Exception {
        given()
            .contentType("application/json")
            .body(createBody(FN, "v1", true))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("1"))
            // Measured: CreateFunction keeps the plain ARN even when it publishes.
            .body("FunctionArn", endsWith(":function:" + FN))
            .body("FunctionArn", not(endsWith(":1")));
    }

    @Test
    @Order(2)
    void createWithPublishActuallyCreatesTheVersion() {
        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .statusCode(200)
            .body("Versions.Version", containsInAnyOrder("$LATEST", "1"));
    }

    @Test
    @Order(3)
    void unqualifiedGetStillDescribesLatest() {
        given()
        .when()
            .get("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(200)
            .body("Configuration.Version", equalTo("$LATEST"));
    }

    @Test
    @Order(4)
    void updateCodeWithPublishReturnsTheNextVersionAndAQualifiedArn() throws Exception {
        given()
            .contentType("application/json")
            .body("{\"ZipFile\": \"" + zipBase64("v2") + "\", \"Publish\": true}")
        .when()
            .put("/2015-03-31/functions/" + FN + "/code")
        .then()
            .statusCode(200)
            .body("Version", equalTo("2"))
            // Measured: unlike CreateFunction, this one is qualified.
            .body("FunctionArn", endsWith(":function:" + FN + ":2"));

        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .body("Versions.Version", containsInAnyOrder("$LATEST", "1", "2"));
    }

    @Test
    @Order(5)
    void updateCodeWithoutPublishReturnsLatestAndCreatesNoVersion() throws Exception {
        given()
            .contentType("application/json")
            .body("{\"ZipFile\": \"" + zipBase64("v3") + "\"}")
        .when()
            .put("/2015-03-31/functions/" + FN + "/code")
        .then()
            .statusCode(200)
            .body("Version", equalTo("$LATEST"))
            .body("FunctionArn", endsWith(":function:" + FN));

        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .body("Versions.Version", containsInAnyOrder("$LATEST", "1", "2"));
    }

    @Test
    @Order(6)
    void publishedVersionKeepsTheCodeItWasPublishedFrom() {
        // Version 1 was published from v1's code while $LATEST has since moved to v3, so their
        // CodeSha256 must differ — the point of publishing being an immutable snapshot.
        String latestSha = given()
        .when()
            .get("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(200)
            .extract().path("Configuration.CodeSha256");

        String versionOneSha = given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .statusCode(200)
            .extract().path("Versions.find { it.Version == '1' }.CodeSha256");

        org.junit.jupiter.api.Assertions.assertNotEquals(latestSha, versionOneSha);
    }

    @Test
    @Order(7)
    void createWithoutPublishReturnsLatestAndCreatesNoVersion() throws Exception {
        given()
            .contentType("application/json")
            .body(createBody(FN_NO_PUBLISH, "v1", false))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("$LATEST"))
            .body("FunctionArn", endsWith(":function:" + FN_NO_PUBLISH));

        given()
        .when()
            .get("/2015-03-31/functions/" + FN_NO_PUBLISH + "/versions")
        .then()
            .body("Versions.Version", containsInAnyOrder("$LATEST"));
    }

    @Test
    @Order(8)
    void omittingPublishEntirelyBehavesAsFalse() throws Exception {
        String name = FN_NO_PUBLISH + "-omitted";
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "python3.12",
                    "Role": "%s",
                    "Handler": "handler.handler",
                    "Code": { "ZipFile": "%s" }
                }
                """.formatted(name, ROLE, zipBase64("v1")))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("$LATEST"));

        given()
        .when()
            .delete("/2015-03-31/functions/" + name)
        .then()
            .statusCode(204);
    }

    // ── cleanup ───────────────────────────────────────────────────────────────

    @Test
    @Order(9)
    void cleanup_deleteFunctions() {
        for (String name : new String[] {FN, FN_NO_PUBLISH}) {
            given()
            .when()
                .delete("/2015-03-31/functions/" + name)
            .then()
                .statusCode(204);
        }
    }
}
