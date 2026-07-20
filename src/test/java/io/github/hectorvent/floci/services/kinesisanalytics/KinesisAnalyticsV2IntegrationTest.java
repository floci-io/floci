package io.github.hectorvent.floci.services.kinesisanalytics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.config.JsonPathConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

/**
 * Drives the full JSON-1.1 dispatch path (AwsJson11Controller → ResolvedServiceCatalog →
 * KinesisAnalyticsV2JsonHandler) via the {@code KinesisAnalytics_20180523.} target prefix.
 * Runs with {@code kinesis-analytics.mock=true} (see src/test/resources/application.yml), so no
 * Docker daemon is needed — StartApplication comes up RUNNING immediately.
 */
@QuarkusTest
class KinesisAnalyticsV2IntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/x";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private void createApplication(String name) {
        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "%s", "RuntimeEnvironment": "FLINK-1_18",
                 "ServiceExecutionRole": "%s"}
                """.formatted(name, ROLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ApplicationDetail.ApplicationName", equalTo(name))
            .body("ApplicationDetail.ApplicationStatus", equalTo("READY"))
            .body("ApplicationDetail.ApplicationARN", startsWith("arn:aws:kinesisanalytics:"));
    }

    @Test
    void createThenDescribeApplication() {
        createApplication("it-describe");

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.DescribeApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-describe"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ApplicationDetail.ApplicationName", equalTo("it-describe"))
            .body("ApplicationDetail.RuntimeEnvironment", equalTo("FLINK-1_18"));
    }

    @Test
    void startApplicationTransitionsToRunning() {
        createApplication("it-start");

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.StartApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-start", "RunConfiguration": {}}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.DescribeApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-start"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // mock mode: RUNNING immediately
            .body("ApplicationDetail.ApplicationStatus", equalTo("RUNNING"));
    }

    @Test
    void listApplicationsIncludesCreated() {
        createApplication("it-list");

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.ListApplications")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ApplicationSummaries.ApplicationName", hasItem("it-list"));
    }

    @Test
    void deleteApplicationSucceeds() {
        createApplication("it-delete");

        // DeleteApplication validates CreateTimestamp against the stored value — fetch the real one
        // (epoch seconds). Use BigDecimal number handling: the default float extraction truncates the
        // ~10-digit epoch to ~7 significant figures, which would shift it by seconds and fail the match.
        String createTimestamp = given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.DescribeApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-delete"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath(new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
            .getString("ApplicationDetail.CreateTimestamp");

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.DeleteApplication")
            .contentType(CONTENT_TYPE)
            .body("{\"ApplicationName\": \"it-delete\", \"CreateTimestamp\": " + createTimestamp + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
