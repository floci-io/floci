package io.github.hectorvent.floci.services.kinesisanalytics;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.config.JsonPathConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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

    @Test
    void tagLifecycleRoundTripsThroughTheWireProtocol() {
        String arn = given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-tags", "RuntimeEnvironment": "FLINK-1_18",
                 "ServiceExecutionRole": "%s",
                 "Tags": [{"Key": "env", "Value": "dev"}]}
                """.formatted(ROLE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // Tags is a list of {Key, Value} objects on this API, never echoed on ApplicationDetail.
            .body("ApplicationDetail.Tags", equalTo(null))
            .extract().path("ApplicationDetail.ApplicationARN");

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{\"ResourceARN\": \"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.Key", hasItem("env"))
            .body("Tags.Value", hasItem("dev"));

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.TagResource")
            .contentType(CONTENT_TYPE)
            .body("{\"ResourceARN\": \"" + arn + "\", \"Tags\": [{\"Key\": \"team\", \"Value\": \"platform\"}]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.UntagResource")
            .contentType(CONTENT_TYPE)
            .body("{\"ResourceARN\": \"" + arn + "\", \"TagKeys\": [\"env\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.ListTagsForResource")
            .contentType(CONTENT_TYPE)
            .body("{\"ResourceARN\": \"" + arn + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags.Key", hasItem("team"))
            .body("Tags.Key", not(hasItem("env")));
    }

    @Test
    void updateApplicationWithNewCodeLocationRoundTripsThroughTheWireProtocol() {
        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-update-code", "RuntimeEnvironment": "FLINK-1_18",
                 "ServiceExecutionRole": "%s",
                 "ApplicationConfiguration": {"ApplicationCodeConfiguration": {
                     "CodeContent": {"S3ContentLocation": {
                         "BucketARN": "arn:aws:s3:::flink-code", "FileKey": "app.jar"}},
                     "CodeContentType": "ZIPFILE"}}}
                """.formatted(ROLE))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.UpdateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-update-code", "CurrentApplicationVersionId": 1,
                 "ApplicationConfigurationUpdate": {"ApplicationCodeConfigurationUpdate": {
                     "CodeContentUpdate": {"S3ContentLocationUpdate": {
                         "BucketARNUpdate": "arn:aws:s3:::flink-code-v2",
                         "FileKeyUpdate": "app-v2.jar"}}}}}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ApplicationDetail.ApplicationVersionId", equalTo(2))
            .body("ApplicationDetail.ApplicationConfigurationDescription"
                    + ".ApplicationCodeConfigurationDescription.CodeContentDescription"
                    + ".S3ApplicationCodeLocationDescription.BucketARN", equalTo("arn:aws:s3:::flink-code-v2"))
            .body("ApplicationDetail.ApplicationConfigurationDescription"
                    + ".ApplicationCodeConfigurationDescription.CodeContentDescription"
                    + ".S3ApplicationCodeLocationDescription.FileKey", equalTo("app-v2.jar"));
    }

    @Test
    void snapshotLifecycleRoundTripsThroughTheWireProtocol() {
        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-snapshot", "RuntimeEnvironment": "FLINK-1_18",
                 "ServiceExecutionRole": "%s",
                 "ApplicationConfiguration": {"ApplicationCodeConfiguration": {
                     "CodeContent": {"S3ContentLocation": {
                         "BucketARN": "arn:aws:s3:::flink-code", "FileKey": "app.jar"}},
                     "CodeContentType": "ZIPFILE"}}}
                """.formatted(ROLE))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.StartApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-snapshot", "RunConfiguration": {}}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.CreateApplicationSnapshot")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-snapshot", "SnapshotName": "before-upgrade"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String creationTimestamp = given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.DescribeApplicationSnapshot")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-snapshot", "SnapshotName": "before-upgrade"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SnapshotDetails.SnapshotName", equalTo("before-upgrade"))
            // mock mode: the snapshot completes immediately, same as the application itself.
            .body("SnapshotDetails.SnapshotStatus", equalTo("READY"))
            .extract().jsonPath(new JsonPathConfig(JsonPathConfig.NumberReturnType.BIG_DECIMAL))
            .getString("SnapshotDetails.SnapshotCreationTimestamp");

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.ListApplicationSnapshots")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-snapshot"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SnapshotSummaries.SnapshotName", hasItem("before-upgrade"));

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.DeleteApplicationSnapshot")
            .contentType(CONTENT_TYPE)
            .body("{\"ApplicationName\": \"it-snapshot\", \"SnapshotName\": \"before-upgrade\", "
                    + "\"SnapshotCreationTimestamp\": " + creationTimestamp + "}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.ListApplicationSnapshots")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-snapshot"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("SnapshotSummaries", equalTo(java.util.List.of()));
    }

    @Test
    void createApplicationWithSnapshotsDisabledRejectsCreateApplicationSnapshot() {
        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-nosnaps", "RuntimeEnvironment": "FLINK-1_18",
                 "ServiceExecutionRole": "%s",
                 "ApplicationConfiguration": {"ApplicationCodeConfiguration": {
                     "CodeContent": {"S3ContentLocation": {
                         "BucketARN": "arn:aws:s3:::flink-code", "FileKey": "app.jar"}},
                     "CodeContentType": "ZIPFILE"},
                 "ApplicationSnapshotConfiguration": {"SnapshotsEnabled": false}}}
                """.formatted(ROLE))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.StartApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-nosnaps", "RunConfiguration": {}}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.CreateApplicationSnapshot")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-nosnaps", "SnapshotName": "attempt"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    void createApplicationPresignedUrlRejectsWhenNotRunning() {
        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.CreateApplication")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-presigned", "RuntimeEnvironment": "FLINK-1_18",
                 "ServiceExecutionRole": "%s"}
                """.formatted(ROLE))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "KinesisAnalytics_20180523.CreateApplicationPresignedUrl")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ApplicationName": "it-presigned", "UrlType": "FLINK_DASHBOARD_URL"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }
}
