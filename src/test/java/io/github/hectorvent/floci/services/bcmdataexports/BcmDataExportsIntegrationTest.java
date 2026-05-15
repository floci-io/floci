package io.github.hectorvent.floci.services.bcmdataexports;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the BCM Data Exports management plane.
 *
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: AWSBillingAndCostManagementDataExports.&lt;Action&gt;
 */
@QuarkusTest
@TestProfile(BcmDataExportsIntegrationTest.IsolatedProfile.class)
class BcmDataExportsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bcm-data-exports/aws4_request";

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

    private static String validExportBody(String name) {
        return "{\"Export\":{" +
                "\"Name\":\"" + name + "\"," +
                "\"Description\":\"daily focus export\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT * FROM COST_AND_USAGE_REPORT\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"my-bucket\",\"S3Prefix\":\"out/\",\"S3Region\":\"us-east-1\"," +
                  "\"S3OutputConfigurations\":{\"Format\":\"PARQUET\",\"Compression\":\"PARQUET\",\"OutputType\":\"CUSTOM\",\"Overwrite\":\"OVERWRITE_REPORT\"}}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
    }

    private static String createExportAndReturnArn(String name) {
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(validExportBody(name))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ExportArn", notNullValue())
            .extract().path("ExportArn");
    }

    @Test
    void createExport_succeeds() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(validExportBody("create-success"))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ExportArn", containsString("export/create-success"));
    }

    @Test
    void createExport_duplicate_returnsValidation() {
        createExportAndReturnArn("dup-export");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(validExportBody("dup-export"))
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createExport_invalidName_returnsValidation() {
        String body = validExportBody("bad name with spaces");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createExport_missingDataQuery_returnsValidation() {
        String body = "{\"Export\":{" +
                "\"Name\":\"no-query\"," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"b\",\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createExport_invalidFrequency_returnsValidation() {
        String body = "{\"Export\":{" +
                "\"Name\":\"bad-cadence\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 1\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"b\",\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"DAILY\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getExport_returnsCreated() {
        String arn = createExportAndReturnArn("get-target");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Export.Name", equalTo("get-target"))
            .body("Export.RefreshCadence.Frequency", equalTo("SYNCHRONOUS"));
    }

    @Test
    void getExport_notFound_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"arn:aws:bcm-data-exports:us-east-1:000000000000:export/missing\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listExports_includesCreated() {
        createExportAndReturnArn("listed-export");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.ListExports")
            .header("Authorization", AUTH).body("{}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Exports.ExportName", hasItem("listed-export"));
    }

    @Test
    void updateExport_replacesDescription() {
        String arn = createExportAndReturnArn("upd-target");
        String body = "{\"ExportArn\":\"" + arn + "\"," +
                "\"Export\":{" +
                "\"Name\":\"upd-target\"," +
                "\"Description\":\"updated description\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 2\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{\"S3Bucket\":\"new-bucket\",\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.UpdateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ExportArn", equalTo(arn));

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Export.Description", equalTo("updated description"))
            .body("Export.DestinationConfigurations.S3Destination.S3Bucket", equalTo("new-bucket"));
    }

    @Test
    void updateExport_notFound_returns400() {
        String body = "{\"ExportArn\":\"arn:aws:bcm-data-exports:us-east-1:000000000000:export/missing\"," +
                "\"Export\":{\"Name\":\"missing\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 1\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{\"S3Bucket\":\"valid-bucket\",\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.UpdateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteExport_removes() {
        String arn = createExportAndReturnArn("del-target");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.DeleteExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteExport_unknownArn_returns200() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.DeleteExport")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"arn:aws:bcm-data-exports:us-east-1:000000000000:export/never-was\"}")
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    void listExecutions_emptyForFreshExport() {
        String arn = createExportAndReturnArn("exec-list");
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.ListExecutions")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("Executions", hasSize(0));
    }

    @Test
    void listExecutions_unknownExport_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.ListExecutions")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"arn:aws:bcm-data-exports:us-east-1:000000000000:export/missing\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getExecution_unknownIds_returns400() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetExecution")
            .header("Authorization", AUTH).body("{\"ExportArn\":\"arn:aws:bcm-data-exports:us-east-1:000000000000:export/missing\",\"ExecutionId\":\"x\"}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createExport_invalidBucketName_returnsValidation() {
        String body = "{\"Export\":{" +
                "\"Name\":\"bad-bucket\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 1\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"InvalidUpper\",\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createExport_quoteInS3Prefix_returnsValidation() {
        String body = "{\"Export\":{" +
                "\"Name\":\"quote-prefix\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 1\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"valid-bucket\"," +
                  "\"S3Prefix\":\"out'); DROP DATABASE; --\"," +
                  "\"S3Region\":\"us-east-1\"}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void createExport_textOrCsvFormat_returnsValidation() {
        String body = "{\"Export\":{" +
                "\"Name\":\"csv-attempt\"," +
                "\"DataQuery\":{\"QueryStatement\":\"SELECT 1\"}," +
                "\"DestinationConfigurations\":{\"S3Destination\":{" +
                  "\"S3Bucket\":\"valid-bucket\",\"S3Region\":\"us-east-1\"," +
                  "\"S3OutputConfigurations\":{\"Format\":\"TEXT_OR_CSV\",\"Compression\":\"GZIP\",\"OutputType\":\"CUSTOM\"}}}," +
                "\"RefreshCadence\":{\"Frequency\":\"SYNCHRONOUS\"}}}";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.CreateExport")
            .header("Authorization", AUTH).body(body)
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void unknownAction_returnsUnknownOperation() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSBillingAndCostManagementDataExports.GetBogusAction")
            .header("Authorization", AUTH).body("{}")
        .when().post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
