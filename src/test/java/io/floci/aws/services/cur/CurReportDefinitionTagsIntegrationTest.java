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

import java.util.List;
import java.util.UUID;

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
        cur.deleteReportDefinition(r -> r.reportName(reportName));
        cur.close();
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

        assertTrue(error.getMessage().contains("tags.1.member.key"), error.getMessage());
        assertTrue(listTags().isEmpty());
    }

    @Test
    void tagResourceWithoutTagsAndUntagResourceWithoutKeysAreRejected() {
        putReport(List.of());

        ValidationException noTags = assertThrows(ValidationException.class,
                () -> cur.tagResource(r -> r.reportName(reportName)));
        ValidationException noKeys = assertThrows(ValidationException.class,
                () -> cur.untagResource(r -> r.reportName(reportName)));

        assertTrue(noTags.getMessage().contains("'tags'"), noTags.getMessage());
        assertTrue(noKeys.getMessage().contains("'tagKeys'"), noKeys.getMessage());
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
