package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.CreateImportJobRequest;
import software.amazon.awssdk.services.sesv2.model.DataFormat;
import software.amazon.awssdk.services.sesv2.model.ImportDataSource;
import software.amazon.awssdk.services.sesv2.model.ImportDestination;
import software.amazon.awssdk.services.sesv2.model.ImportJobSummary;
import software.amazon.awssdk.services.sesv2.model.ListImportJobsRequest;
import software.amazon.awssdk.services.sesv2.model.ListImportJobsResponse;
import software.amazon.awssdk.services.sesv2.model.SuppressionListDestination;
import software.amazon.awssdk.services.sesv2.model.SuppressionListImportAction;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paging of the SES v2 import-job list through the SDK paginator: every job created here is listed
 * exactly once across small pages. The jobs delete an address that was never suppressed, so they
 * leave no state behind whatever their outcome; import jobs themselves cannot be deleted.
 */
@DisplayName("SES import job list paging")
class SesImportJobPagingTest {

    private static final String KEY = "delete.csv";

    private static SesV2Client sesV2;
    private static S3Client s3;
    private static String bucket;
    private static List<String> jobIds;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        s3 = TestFixtures.s3Client();
        bucket = "floci-ses-import-paging-" + TestFixtures.uniqueName();
        jobIds = new ArrayList<>();
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(KEY).build(),
                RequestBody.fromString("never-suppressed@example.com\n"));
        for (int i = 0; i < 3; i++) {
            jobIds.add(sesV2.createImportJob(CreateImportJobRequest.builder()
                    .importDataSource(ImportDataSource.builder()
                            .s3Url("s3://" + bucket + "/" + KEY).dataFormat(DataFormat.CSV).build())
                    .importDestination(ImportDestination.builder()
                            .suppressionListDestination(SuppressionListDestination.builder()
                                    .suppressionListImportAction(SuppressionListImportAction.DELETE).build())
                            .build())
                    .build()).jobId());
        }
    }

    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(KEY).build());
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } catch (Exception ignored) {
                // Best-effort cleanup; a leftover bucket does not affect other tests.
            }
            s3.close();
        }
        if (sesV2 != null) {
            sesV2.close();
        }
    }

    @Test
    @DisplayName("ListImportJobs paginator walks small pages over every job once")
    void paginatorWalksEveryJobOnce() {
        List<String> listed = new ArrayList<>();
        for (ListImportJobsResponse page : sesV2.listImportJobsPaginator(
                ListImportJobsRequest.builder().pageSize(2).build())) {
            assertThat(page.importJobs()).hasSizeLessThanOrEqualTo(2);
            page.importJobs().stream().map(ImportJobSummary::jobId).forEach(listed::add);
        }

        assertThat(listed).doesNotHaveDuplicates();
        assertThat(listed.stream().filter(jobIds::contains)).containsExactlyInAnyOrderElementsOf(jobIds);
    }
}
