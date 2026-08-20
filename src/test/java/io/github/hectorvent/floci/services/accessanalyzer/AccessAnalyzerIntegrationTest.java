package io.github.hectorvent.floci.services.accessanalyzer;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Integration tests for lex00/floci#75: before {@link AccessAnalyzerController} and
 * {@link AccessAnalyzerService} existed there was no AccessAnalyzer package anywhere in
 * floci, so {@code aws_accessanalyzer_analyzer}'s {@code CreateAnalyzer} 404'd outright with
 * {@code UnknownOperationException}. Covers create/get/list/delete plus the generic
 * {@code /tags/{resourceArn}} routes ({@link AccessAnalyzerTagHandler} registers
 * {@code access-analyzer} with {@code SharedTagsController}), the same shape
 * {@code AmpIntegrationTest}/{@code BackupIntegrationTest} use for their own REST-JSON
 * services.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AccessAnalyzerIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260220/us-east-1/access-analyzer/aws4_request";
    private static final String ANALYZER_NAME = "test-account-analyzer";

    private static String analyzerArn;

    @Test
    @Order(1)
    void createAnalyzer() {
        var response = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"analyzerName": "%s", "type": "ACCOUNT", "tags": {"team": "security"}}
                """.formatted(ANALYZER_NAME))
        .when()
            .put("/analyzer")
        .then()
            .statusCode(200)
            .body("arn", containsString(":access-analyzer:"))
            .body("arn", containsString("analyzer/" + ANALYZER_NAME))
            .extract();
        analyzerArn = response.path("arn");
    }

    @Test
    @Order(2)
    void createAnalyzerWithTheSameNameIsRejected() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"analyzerName\": \"" + ANALYZER_NAME + "\", \"type\": \"ACCOUNT\"}")
        .when()
            .put("/analyzer")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ConflictException"));
    }

    @Test
    @Order(3)
    void getAnalyzerReturnsWhatWasCreated() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/analyzer/" + ANALYZER_NAME)
        .then()
            .statusCode(200)
            .body("analyzer.name", equalTo(ANALYZER_NAME))
            .body("analyzer.type", equalTo("ACCOUNT"))
            .body("analyzer.status", equalTo("ACTIVE"))
            .body("analyzer.arn", equalTo(analyzerArn))
            .body("analyzer.tags.team", equalTo("security"))
            // Never run in the emulator, and must not be fabricated on the wire.
            .body("analyzer.lastResourceAnalyzed", equalTo(null));
    }

    @Test
    @Order(4)
    void getUnknownAnalyzerIsRejected() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/analyzer/no-such-analyzer")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(5)
    void listAnalyzersIncludesTheCreatedOne() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/analyzer")
        .then()
            .statusCode(200)
            .body("analyzers", hasSize(greaterThanOrEqualTo(1)))
            .body("analyzers.name", org.hamcrest.Matchers.hasItem(ANALYZER_NAME));
    }

    // ── Generic resource tagging, dispatched by SharedTagsController ────────────

    @Test
    @Order(6)
    void listTagsForResource() {
        given()
        .when()
            .get("/tags/" + analyzerArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("security"));
    }

    @Test
    @Order(7)
    void tagResourceAddsWithoutDroppingExisting() {
        given()
            .contentType("application/json")
            .body("{\"tags\":{\"tofu-estate\":\"crossing\"}}")
        .when()
            .post("/tags/" + analyzerArn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + analyzerArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("security"))
            .body("tags.'tofu-estate'", equalTo("crossing"));
    }

    @Test
    @Order(8)
    void untagResourceRemovesOnlyTheNamedKey() {
        given()
        .when()
            .delete("/tags/" + analyzerArn + "?tagKeys=tofu-estate")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/tags/" + analyzerArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("security"))
            .body("tags.containsKey('tofu-estate')", equalTo(false));
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    @Order(9)
    void deleteUnknownAnalyzerIsRejected() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/analyzer/no-such-analyzer")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(10)
    void deleteAnalyzer() {
        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/analyzer/" + ANALYZER_NAME)
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/analyzer/" + ANALYZER_NAME)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
