package io.floci.aws.services.cur;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costandusagereport.CostAndUsageReportClient;
import software.amazon.awssdk.services.costandusagereport.model.CompressionFormat;
import software.amazon.awssdk.services.costandusagereport.model.ReportFormat;
import software.amazon.awssdk.services.costandusagereport.model.SchemaElement;
import software.amazon.awssdk.services.costandusagereport.model.TimeUnit;
import software.amazon.awssdk.services.costandusagereport.model.ValidationException;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Every CUR request is checked against the constraints of the botocore model. */
@QuarkusTest
class CurRequestConstraintsIntegrationTest {

    private static final String ACCOUNT = "100000008230";

    private CostAndUsageReportClient cur;
    private String reportName;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createClient() {
        cur = AwsTestClients.create(CostAndUsageReportClient.builder(), ACCOUNT, Region.US_EAST_1);
        reportName = "constraints-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void deleteReportAndCloseClient() {
        try {
            cur.deleteReportDefinition(r -> r.reportName(reportName));
        } finally {
            cur.close();
        }
    }

    @Test
    void aReportNameWithADotIsAccepted() {
        reportName = "report.v1." + UUID.randomUUID().toString().substring(0, 8);

        putReport();

        assertEquals(1, storedReports());
    }

    @Test
    void aReportNameFlociCannotWriteToS3IsRefusedAndNotStored() {
        reportName = "report(" + UUID.randomUUID().toString().substring(0, 8) + ")";

        assertThrows(ValidationException.class, this::putReport);
        assertEquals(0, storedReports());
    }

    @Test
    void aDefinitionWithoutTheS3PrefixTheModelRequiresIsRefused() {
        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.PutReportDefinition")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/cur/aws4_request")
                .body("{\"ReportDefinition\":{\"ReportName\":\"" + reportName + "\",\"TimeUnit\":\"DAILY\","
                        + "\"Format\":\"Parquet\",\"Compression\":\"Parquet\","
                        + "\"AdditionalSchemaElements\":[\"RESOURCES\"],\"S3Bucket\":\"constraints-bucket\","
                        + "\"S3Region\":\"us-east-1\"}}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("S3Prefix"));
        assertEquals(0, storedReports());
    }

    @Test
    void aReportNameThatIsNotAStringIsRefusedAndDeletesNothing() {
        putReport();

        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSOrigamiServiceGatewayService.DeleteReportDefinition")
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/cur/aws4_request")
                .body("{\"ReportName\":5}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        assertEquals(1, storedReports());
    }

    private void putReport() {
        cur.putReportDefinition(r -> r.reportDefinition(d -> d.reportName(reportName).timeUnit(TimeUnit.DAILY)
                .format(ReportFormat.PARQUET).compression(CompressionFormat.PARQUET)
                .additionalSchemaElements(SchemaElement.RESOURCES).s3Bucket("constraints-bucket")
                .s3Prefix("reports").s3Region("us-east-1")));
    }

    private long storedReports() {
        return cur.describeReportDefinitions(r -> { }).reportDefinitions().stream()
                .filter(definition -> definition.reportName().equals(reportName)).count();
    }
}
