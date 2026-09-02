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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * DeleteFunction with a Qualifier removes one published version, not the function.
 *
 * <p>Behaviour here mirrors the live service, which was measured for each case: deleting
 * {@code $LATEST} or an alias name is rejected, a version an alias references is a conflict,
 * a version that does not exist is a silent success, and the function, its other versions
 * and its aliases all survive.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaDeleteFunctionQualifierIntegrationTest {

    private static final String FN = "del-qualifier-fn";
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

    private static void updateCode(String marker) throws Exception {
        given()
            .contentType("application/json")
            .body("{\"ZipFile\": \"" + zipBase64(marker) + "\"}")
        .when()
            .put("/2015-03-31/functions/" + FN + "/code")
        .then()
            .statusCode(200);
    }

    private static void publish() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .statusCode(201);
    }

    @Test
    @Order(1)
    void seedFunctionWithThreeVersions() throws Exception {
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
                """.formatted(FN, ROLE, zipBase64("v1")))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201);

        publish();                 // version 1
        updateCode("v2");
        publish();                 // version 2
        updateCode("v3");
        publish();                 // version 3

        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .statusCode(200)
            .body("Versions.Version", containsInAnyOrder("$LATEST", "1", "2", "3"));
    }

    @Test
    @Order(2)
    void deletingLatestByQualifierIsRejected() {
        given()
            .queryParam("Qualifier", "$LATEST")
        .when()
            .delete("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo("$LATEST version cannot be deleted without deleting the function."));
    }

    @Test
    @Order(3)
    void deletingByAliasNameIsRejected() {
        given()
            .queryParam("Qualifier", "notaversion")
        .when()
            .delete("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo("Deletion of aliases is not currently supported."));
    }

    @Test
    @Order(3)
    void nonAsciiQualifierIsRejectedByThePattern() {
        // Character.isDigit accepts non-ASCII decimal digits, so a full-width or Arabic-Indic
        // digit would otherwise be read as a version number and answered with a silent 204.
        // The live service pattern-validates the qualifier first; measured on GetFunction,
        // which enforces the same constraint.
        for (String qualifier : new String[] {"\uFF13", "\u0663"}) {
            given()
                .queryParam("Qualifier", qualifier)
            .when()
                .delete("/2015-03-31/functions/" + FN)
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("at 'qualifier' failed to satisfy constraint"));
        }

        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .body("Versions.Version", containsInAnyOrder("$LATEST", "1", "2", "3"));
    }

    @Test
    @Order(4)
    void deletingAVersionThatDoesNotExistSucceedsSilently() {
        // Measured: the live service returns success and changes nothing, rather than 404.
        given()
            .queryParam("Qualifier", "99")
        .when()
            .delete("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .body("Versions.Version", containsInAnyOrder("$LATEST", "1", "2", "3"));
    }

    @Test
    @Order(5)
    void versionReferencedByAnAliasCannotBeDeleted() {
        given()
            .contentType("application/json")
            .body("{\"Name\": \"live\", \"FunctionVersion\": \"1\"}")
        .when()
            .post("/2015-03-31/functions/" + FN + "/aliases")
        .then()
            .statusCode(201);

        given()
            .queryParam("Qualifier", "1")
        .when()
            .delete("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceConflictException"))
            .body("message", equalTo(
                    "Unable to delete version because the following aliases reference it: [live]"));

        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .body("Versions.Version", containsInAnyOrder("$LATEST", "1", "2", "3"));
    }

    @Test
    @Order(6)
    void deletingAnUnreferencedVersionRemovesOnlyThatVersion() {
        given()
            .queryParam("Qualifier", "2")
        .when()
            .delete("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(204);

        // The regression this test exists for: before the qualifier was honoured, this call
        // deleted the whole function.
        given()
        .when()
            .get("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo(FN));

        // Checked through ListVersionsByFunction rather than a qualified GetFunction:
        // GetFunction ignores Qualifier today and would answer for $LATEST either way.
        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .body("Versions.Version", containsInAnyOrder("$LATEST", "1", "3"));

        // The alias, pointing at a different version, is untouched.
        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/aliases/live")
        .then()
            .statusCode(200)
            .body("FunctionVersion", equalTo("1"));
    }

    @Test
    @Order(8)
    void deleteWithoutAQualifierStillRemovesTheWholeFunction() {
        given()
        .when()
            .delete("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(404);

        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/versions")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(9)
    void emptyQualifierIsTreatedAsAbsent() throws Exception {
        // RESTEasy binds an empty query value as null, so ?Qualifier= is a whole-function
        // delete; pinned so the distinction is not lost if that binding ever changes.
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s-empty",
                    "Runtime": "python3.12",
                    "Role": "%s",
                    "Handler": "handler.handler",
                    "Code": { "ZipFile": "%s" }
                }
                """.formatted(FN, ROLE, zipBase64("v1")))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201);

        given()
            .queryParam("Qualifier", "")
        .when()
            .delete("/2015-03-31/functions/" + FN + "-empty")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "-empty")
        .then()
            .statusCode(404);
    }
}
