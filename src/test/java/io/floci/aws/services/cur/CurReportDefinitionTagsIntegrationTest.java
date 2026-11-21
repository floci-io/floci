package io.floci.aws.services.cur;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costandusagereport.CostAndUsageReportClient;
import software.amazon.awssdk.services.costandusagereport.model.AdditionalArtifact;
import software.amazon.awssdk.services.costandusagereport.model.CompressionFormat;
import software.amazon.awssdk.services.costandusagereport.model.ReportDefinition;
import software.amazon.awssdk.services.costandusagereport.model.ReportFormat;
import software.amazon.awssdk.services.costandusagereport.model.ReportVersioning;
import software.amazon.awssdk.services.costandusagereport.model.ResourceNotFoundException;
import software.amazon.awssdk.services.costandusagereport.model.SchemaElement;
import software.amazon.awssdk.services.costandusagereport.model.Tag;
import software.amazon.awssdk.services.costandusagereport.model.TimeUnit;
import software.amazon.awssdk.services.costandusagereport.model.ValidationException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CurReportDefinitionTagsIntegrationTest {

    private static final String ACCOUNT = "100000007952";

    private CostAndUsageReportClient cur;
    private String reportName;

    @BeforeEach
    void createClient() {
        cur = AwsTestClients.create(CostAndUsageReportClient.builder(), ACCOUNT, Region.US_EAST_1);
        reportName = "tags-" + UUID.randomUUID().toString().substring(0, 8);
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
    void tagsGivenToPutReportDefinitionAreListed() {
        putReport(List.of(tag("team", "red")));

        assertEquals(List.of(tag("team", "red")), listTags());
    }

    @Test
    void tagResourceAddsAndOverwritesTagsAndUntagResourceRemovesThem() {
        putReport(List.of(tag("team", "red"), tag("stage", "test")));

        cur.tagResource(r -> r.reportName(reportName).tags(tag("env", "dev"), tag("team", "blue")));
        assertEquals(List.of(tag("team", "blue"), tag("stage", "test"), tag("env", "dev")), listTags());

        cur.untagResource(r -> r.reportName(reportName).tagKeys("stage", "absent"));
        assertEquals(List.of(tag("team", "blue"), tag("env", "dev")), listTags());
    }

    @Test
    void modifyReportDefinitionKeepsTheReportsTags() {
        putReport(List.of(tag("team", "red")));

        cur.modifyReportDefinition(r -> r.reportName(reportName)
                .reportDefinition(definition().toBuilder().s3Prefix("moved").build()));

        assertEquals(List.of(tag("team", "red")), listTags());
    }

    @Test
    void aReportWithoutTagsListsNone() {
        putReport(List.of());

        assertTrue(listTags().isEmpty());
    }

    @Test
    void tagOperationsOnAMissingReportAnswerResourceNotFound() {
        String missing = reportName + "-missing";

        assertThrows(ResourceNotFoundException.class, () -> cur.listTagsForResource(r -> r.reportName(missing)));
        assertThrows(ResourceNotFoundException.class,
                () -> cur.tagResource(r -> r.reportName(missing).tags(tag("team", "red"))));
        assertThrows(ResourceNotFoundException.class,
                () -> cur.untagResource(r -> r.reportName(missing).tagKeys("team")));
    }

    @Test
    void aTagKeyLongerThan128CharactersIsRejected() {
        putReport(List.of());

        ValidationException error = assertThrows(ValidationException.class,
                () -> cur.tagResource(r -> r.reportName(reportName).tags(tag("k".repeat(129), "v"))));

        assertTrue(error.getMessage().contains("'Tags.Key'"), error.getMessage());
        assertTrue(listTags().isEmpty());
    }

    @Test
    void tagResourceWithoutTagsAndUntagResourceWithoutKeysAreRejected() {
        putReport(List.of());

        ValidationException noTags = assertThrows(ValidationException.class,
                () -> cur.tagResource(r -> r.reportName(reportName)));
        ValidationException noKeys = assertThrows(ValidationException.class,
                () -> cur.untagResource(r -> r.reportName(reportName)));

        assertTrue(noTags.getMessage().contains("'Tags'"), noTags.getMessage());
        assertTrue(noKeys.getMessage().contains("'TagKeys'"), noKeys.getMessage());
    }

    @Test
    void aReportCannotHoldMoreThan50Tags() {
        assertThrows(ValidationException.class, () -> putReport(tags(51)));

        putReport(tags(50));
        assertThrows(ValidationException.class,
                () -> cur.tagResource(r -> r.reportName(reportName).tags(tag("one-more", "v"))));
        assertEquals(50, listTags().size());

        cur.tagResource(r -> r.reportName(reportName).tags(tag("key-1", "replaced")));
        assertEquals(50, listTags().size());
    }

    @Test
    void tagsThatAreNotAListAreRejectedOnCreate() {
        String definition = "{\"ReportName\":\"" + reportName + "\",\"TimeUnit\":\"DAILY\","
                + "\"Format\":\"Parquet\",\"Compression\":\"Parquet\",\"AdditionalSchemaElements\":[\"RESOURCES\"],"
                + "\"S3Bucket\":\"tag-report-bucket\",\"S3Prefix\":\"reports\",\"S3Region\":\"us-east-1\","
                + "\"AdditionalArtifacts\":[\"ATHENA\"],\"RefreshClosedReports\":true,"
                + "\"ReportVersioning\":\"OVERWRITE_REPORT\"}";

        call("PutReportDefinition", "{\"ReportDefinition\":" + definition
                + ",\"Tags\":{\"Key\":\"team\",\"Value\":\"red\"}}", 400);
        call("PutReportDefinition", "{\"ReportDefinition\":" + definition + "}", 200);
    }

    @Test
    void tagKeysAndValuesThatAreNotStringsAreRejected() {
        putReport(List.of(tag("1", "kept")));

        call("TagResource", "{\"ReportName\":\"" + reportName + "\",\"Tags\":[{\"Key\":2,\"Value\":\"v\"}]}", 400);
        call("TagResource", "{\"ReportName\":\"" + reportName + "\",\"Tags\":[{\"Key\":\"k\",\"Value\":3}]}", 400);
        call("UntagResource", "{\"ReportName\":\"" + reportName + "\",\"TagKeys\":[1]}", 400);

        assertEquals(List.of(tag("1", "kept")), listTags());
    }

    private static void call(String action, String body, int status) {
        given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSOrigamiServiceGatewayService." + action)
                .header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/us-east-1/cur/aws4_request")
                .body(body.getBytes(StandardCharsets.UTF_8))
        .when()
                .post("/")
        .then()
                .statusCode(status)
                .body(status == 200 ? not(containsString("__type")) : containsString("ValidationException"));
    }

    private static List<Tag> tags(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> tag("key-" + i, "v")).toList();
    }

    private void putReport(List<Tag> tags) {
        cur.putReportDefinition(r -> r.reportDefinition(definition()).tags(tags));
    }

    private List<Tag> listTags() {
        return cur.listTagsForResource(r -> r.reportName(reportName)).tags();
    }

    private ReportDefinition definition() {
        return ReportDefinition.builder()
                .reportName(reportName)
                .timeUnit(TimeUnit.DAILY)
                .format(ReportFormat.PARQUET)
                .compression(CompressionFormat.PARQUET)
                .additionalSchemaElements(SchemaElement.RESOURCES)
                .s3Bucket("tag-report-bucket")
                .s3Prefix("reports")
                .s3Region("us-east-1")
                .additionalArtifacts(AdditionalArtifact.ATHENA)
                .refreshClosedReports(true)
                .reportVersioning(ReportVersioning.OVERWRITE_REPORT)
                .build();
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }
}
