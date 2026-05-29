package io.github.hectorvent.floci.services.cur;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the CUR management plane. Each test runs against a
 * dedicated profile so report-store state is isolated from sibling tests.
 *
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: AWSOrigamiServiceGatewayService.&lt;Action&gt;
 */
@QuarkusTest
@TestProfile(CurIntegrationTest.IsolatedProfile.class)
class CurIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/cur/aws4_request";

    public static final class IsolatedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.storage.mode", "memory");
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String validReportBody(String name) {
        return "{\"ReportDefinition\":{" +
                "\"ReportName\":\"" + name + "\"," +
                "\"TimeUnit\":\"DAILY\"," +
                "\"Format\":\"Parquet\"," +
                "\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[\"RESOURCES\"]," +
                "\"S3Bucket\":\"my-bucket\"," +
                "\"S3Prefix\":\"reports/\"," +
                "\"S3Region\":\"us-east-1\"," +
                "\"AdditionalArtifacts\":[\"ATHENA\"]," +
                "\"RefreshClosedReports\":true," +
                "\"ReportVersioning\":\"OVERWRITE_REPORT\"" +
                "}}";
    }

    @Test
    void putReportDefinition_create_succeeds() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH)
            .body(validReportBody("create-success"))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ReportName", equalTo("create-success"))
            .body("Format", equalTo("Parquet"));
    }

    @Test
    void putReportDefinition_duplicate_returnsDuplicateException() {
        String body = validReportBody("dup-report");
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(body)
            .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(body)
            .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("DuplicateReportNameException"));
    }

    @Test
    void putReportDefinition_invalidName_returnsValidation() {
        String body = "{\"ReportDefinition\":{" +
                "\"ReportName\":\"has spaces\"," +
                "\"TimeUnit\":\"DAILY\",\"Format\":\"Parquet\",\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[]," +
                "\"S3Bucket\":\"b\",\"S3Region\":\"us-east-1\"}}";
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(body)
            .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void putReportDefinition_invalidTimeUnit_returnsValidation() {
        String body = "{\"ReportDefinition\":{" +
                "\"ReportName\":\"bad-tu\"," +
                "\"TimeUnit\":\"YEARLY\",\"Format\":\"Parquet\",\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[]," +
                "\"S3Bucket\":\"b\",\"S3Region\":\"us-east-1\"}}";
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(body)
            .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void describeReportDefinitions_returnsCreated() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(validReportBody("describe-test"))
            .when().post("/").then().statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.DescribeReportDefinitions")
            .header("Authorization", AUTH).body("{}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ReportDefinitions.ReportName", hasItem("describe-test"));
    }

    @Test
    void modifyReportDefinition_updates() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(validReportBody("modify-target"))
            .when().post("/").then().statusCode(200);

        String modify = "{\"ReportName\":\"modify-target\"," +
                "\"ReportDefinition\":{" +
                "\"ReportName\":\"modify-target\"," +
                "\"TimeUnit\":\"MONTHLY\",\"Format\":\"Parquet\",\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[\"RESOURCES\"]," +
                "\"S3Bucket\":\"new-bucket\",\"S3Region\":\"us-east-1\"" +
                "}}";
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.ModifyReportDefinition")
            .header("Authorization", AUTH).body(modify)
            .when().post("/")
            .then()
            .statusCode(200)
            .body("TimeUnit", equalTo("MONTHLY"))
            .body("S3Bucket", equalTo("new-bucket"));
    }

    @Test
    void modifyReportDefinition_notFound_returns400() {
        String modify = "{\"ReportName\":\"missing-report\"," +
                "\"ReportDefinition\":{" +
                "\"ReportName\":\"missing-report\"," +
                "\"TimeUnit\":\"DAILY\",\"Format\":\"Parquet\",\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[]," +
                "\"S3Bucket\":\"valid-bucket\",\"S3Region\":\"us-east-1\"}}";
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.ModifyReportDefinition")
            .header("Authorization", AUTH).body(modify)
            .when().post("/")
            .then()
            .statusCode(400)
            .body("__type", equalTo("ReportNotFoundException"));
    }

    @Test
    void modifyReportDefinition_nameMismatch_returnsValidation() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(validReportBody("orig-name"))
            .when().post("/").then().statusCode(200);

        String modify = "{\"ReportName\":\"orig-name\"," +
                "\"ReportDefinition\":{" +
                "\"ReportName\":\"different-name\"," +
                "\"TimeUnit\":\"DAILY\",\"Format\":\"Parquet\",\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[]," +
                "\"S3Bucket\":\"b\",\"S3Region\":\"us-east-1\"}}";
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.ModifyReportDefinition")
            .header("Authorization", AUTH).body(modify)
            .when().post("/")
            .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void deleteReportDefinition_removes() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(validReportBody("to-delete"))
            .when().post("/").then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.DeleteReportDefinition")
            .header("Authorization", AUTH).body("{\"ReportName\":\"to-delete\"}")
            .when().post("/")
            .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.DescribeReportDefinitions")
            .header("Authorization", AUTH).body("{}")
            .when().post("/")
            .then().statusCode(200)
            .body("ReportDefinitions.ReportName", not(hasItem("to-delete")));
    }

    @Test
    void deleteReportDefinition_unknownName_returns200() {
        // AWS treats DeleteReportDefinition as idempotent — deleting a missing
        // report should not raise.
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.DeleteReportDefinition")
            .header("Authorization", AUTH).body("{\"ReportName\":\"never-existed\"}")
            .when().post("/")
            .then().statusCode(200);
    }

    @Test
    void putReportDefinition_invalidBucketName_returnsValidation() {
        // S3 bucket names must be 3-63 chars, lowercase alphanumerics, '-' or '.'.
        // Reject before the value can reach DuckDB SQL interpolation.
        String body = "{\"ReportDefinition\":{" +
                "\"ReportName\":\"bad-bucket\"," +
                "\"TimeUnit\":\"DAILY\",\"Format\":\"Parquet\",\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[]," +
                "\"S3Bucket\":\"AB\",\"S3Region\":\"us-east-1\"}}";
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(body)
            .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void putReportDefinition_quoteInS3Prefix_returnsValidation() {
        // A single quote in S3Prefix would terminate the SQL string literal that
        // ParquetEmitter feeds to DuckDB; reject at the management plane.
        String body = "{\"ReportDefinition\":{" +
                "\"ReportName\":\"quote-prefix\"," +
                "\"TimeUnit\":\"DAILY\",\"Format\":\"Parquet\",\"Compression\":\"Parquet\"," +
                "\"AdditionalSchemaElements\":[]," +
                "\"S3Bucket\":\"valid-bucket\"," +
                "\"S3Prefix\":\"foo'); DROP TABLE x; --\"," +
                "\"S3Region\":\"us-east-1\"}}";
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(body)
            .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void putReportDefinition_textCsvFormat_returnsValidation() {
        // Floci only emits Parquet today; accepting textORcsv would let a
        // report persist that the emitter can't actually fulfill.
        String body = "{\"ReportDefinition\":{" +
                "\"ReportName\":\"csv-attempt\"," +
                "\"TimeUnit\":\"DAILY\",\"Format\":\"textORcsv\",\"Compression\":\"GZIP\"," +
                "\"AdditionalSchemaElements\":[]," +
                "\"S3Bucket\":\"valid-bucket\",\"S3Region\":\"us-east-1\"}}";
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
            .header("Authorization", AUTH).body(body)
            .when().post("/").then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void unknownAction_returnsUnknownOperation() {
        given().contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.GetBogusAction")
            .header("Authorization", AUTH).body("{}")
            .when().post("/")
            .then().statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
