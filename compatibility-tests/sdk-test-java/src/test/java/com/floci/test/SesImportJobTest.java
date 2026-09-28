package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.ContactListDestination;
import software.amazon.awssdk.services.sesv2.model.ContactListImportAction;
import software.amazon.awssdk.services.sesv2.model.CreateContactListRequest;
import software.amazon.awssdk.services.sesv2.model.CreateImportJobRequest;
import software.amazon.awssdk.services.sesv2.model.CreateImportJobResponse;
import software.amazon.awssdk.services.sesv2.model.DataFormat;
import software.amazon.awssdk.services.sesv2.model.DeleteContactListRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteSuppressedDestinationRequest;
import software.amazon.awssdk.services.sesv2.model.GetContactRequest;
import software.amazon.awssdk.services.sesv2.model.GetContactResponse;
import software.amazon.awssdk.services.sesv2.model.GetImportJobRequest;
import software.amazon.awssdk.services.sesv2.model.GetImportJobResponse;
import software.amazon.awssdk.services.sesv2.model.ImportDataSource;
import software.amazon.awssdk.services.sesv2.model.ImportDestination;
import software.amazon.awssdk.services.sesv2.model.ImportDestinationType;
import software.amazon.awssdk.services.sesv2.model.ImportJobSummary;
import software.amazon.awssdk.services.sesv2.model.JobStatus;
import software.amazon.awssdk.services.sesv2.model.ListImportJobsRequest;
import software.amazon.awssdk.services.sesv2.model.ListImportJobsResponse;
import software.amazon.awssdk.services.sesv2.model.ListSuppressedDestinationsRequest;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.SubscriptionStatus;
import software.amazon.awssdk.services.sesv2.model.SuppressionListDestination;
import software.amazon.awssdk.services.sesv2.model.SuppressionListImportAction;
import software.amazon.awssdk.services.sesv2.model.SuppressedDestinationSummary;
import software.amazon.awssdk.services.sesv2.model.Topic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES v2 import-job APIs: CreateImportJob / GetImportJob /
 * ListImportJobs marshalling, the asynchronous job lifecycle polled through GetImportJob, and the
 * imported rows read back through the suppression-list and contact APIs, against a live Floci
 * instance. The source objects are written through the S3 SDK.
 */
@DisplayName("SES v2 Import Jobs")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesImportJobTest {

    private static final String BUCKET = "floci-ses-import-job-sdk-test";
    private static final String LIST = "compat-import-job-list";
    private static final String SUPPRESSION_KEY = "suppression.csv";
    private static final String CONTACTS_KEY = "contacts.json";

    private static SesV2Client sesV2;
    private static S3Client s3;
    private static String suppressionJobId;
    private static String contactJobId;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        s3 = TestFixtures.s3Client();
        cleanupQuietly();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(SUPPRESSION_KEY).build(),
                RequestBody.fromString("alice@example.com,BOUNCE\nbob@example.com,COMPLAINT\ncarol@example.com,SPAM\n"));
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(CONTACTS_KEY).build(),
                RequestBody.fromString("""
                        {"emailAddress":"carol@example.com","unsubscribeAll":false,"attributesData":"{\\"Name\\":\\"Carol\\"}","topicPreferences":[{"topicName":"Sports","subscriptionStatus":"OPT_IN"}]}
                        {"emailAddress":"dave@example.com","unsubscribeAll":true}
                        """));
        sesV2.createContactList(CreateContactListRequest.builder()
                .contactListName(LIST)
                .topics(Topic.builder().topicName("Sports").displayName("Sports")
                        .defaultSubscriptionStatus(SubscriptionStatus.OPT_OUT).build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        cleanupQuietly();
        if (sesV2 != null) {
            sesV2.close();
        }
        if (s3 != null) {
            s3.close();
        }
    }

    private static void cleanupQuietly() {
        for (String address : List.of("alice@example.com", "bob@example.com")) {
            quietly(() -> sesV2.deleteSuppressedDestination(
                    DeleteSuppressedDestinationRequest.builder().emailAddress(address).build()));
        }
        quietly(() -> sesV2.deleteContactList(DeleteContactListRequest.builder().contactListName(LIST).build()));
        for (String key : List.of(SUPPRESSION_KEY, CONTACTS_KEY)) {
            quietly(() -> s3.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key(key).build()));
        }
        quietly(() -> s3.deleteBucket(DeleteBucketRequest.builder().bucket(BUCKET).build()));
    }

    // Best-effort teardown: the resource is usually already gone (a fresh Floci, or the previous
    // run cleaned up), so a NotFound here is the expected state and the next call must still run.
    private static void quietly(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Exception ignored) {
            // see above: an absent resource is exactly what cleanup wants
        }
    }

    @Test
    @Order(1)
    @DisplayName("CreateImportJob into the suppression list completes and lists the addresses")
    void createImportJob_suppressionList() throws InterruptedException {
        CreateImportJobResponse created = sesV2.createImportJob(CreateImportJobRequest.builder()
                .importDataSource(ImportDataSource.builder()
                        .s3Url("s3://" + BUCKET + "/" + SUPPRESSION_KEY).dataFormat(DataFormat.CSV).build())
                .importDestination(ImportDestination.builder()
                        .suppressionListDestination(SuppressionListDestination.builder()
                                .suppressionListImportAction(SuppressionListImportAction.PUT).build())
                        .build())
                .build());
        suppressionJobId = created.jobId();
        assertThat(suppressionJobId).isNotBlank();

        GetImportJobResponse job = pollUntilTerminal(suppressionJobId);
        assertThat(job.jobStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.importDataSource().s3Url()).isEqualTo("s3://" + BUCKET + "/" + SUPPRESSION_KEY);
        assertThat(job.importDataSource().dataFormat()).isEqualTo(DataFormat.CSV);
        assertThat(job.importDestination().suppressionListDestination().suppressionListImportAction())
                .isEqualTo(SuppressionListImportAction.PUT);
        assertThat(job.importDestination().contactListDestination()).isNull();
        assertThat(job.processedRecordsCount()).isEqualTo(3);
        assertThat(job.failedRecordsCount()).isEqualTo(1);
        assertThat(job.createdTimestamp()).isNotNull();
        assertThat(job.completedTimestamp()).isNotNull();
        assertThat(job.failureInfo()).isNull();

        List<String> suppressed = sesV2.listSuppressedDestinations(ListSuppressedDestinationsRequest.builder().build())
                .suppressedDestinationSummaries().stream()
                .map(SuppressedDestinationSummary::emailAddress)
                .toList();
        assertThat(suppressed).contains("alice@example.com", "bob@example.com");
    }

    @Test
    @Order(2)
    @DisplayName("CreateImportJob into a contact list creates the contacts")
    void createImportJob_contactList() throws InterruptedException {
        CreateImportJobResponse created = sesV2.createImportJob(CreateImportJobRequest.builder()
                .importDataSource(ImportDataSource.builder()
                        .s3Url("s3://" + BUCKET + "/" + CONTACTS_KEY).dataFormat(DataFormat.JSON).build())
                .importDestination(ImportDestination.builder()
                        .contactListDestination(ContactListDestination.builder()
                                .contactListName(LIST)
                                .contactListImportAction(ContactListImportAction.PUT).build())
                        .build())
                .build());
        contactJobId = created.jobId();

        GetImportJobResponse job = pollUntilTerminal(contactJobId);
        assertThat(job.jobStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.importDestination().contactListDestination().contactListName()).isEqualTo(LIST);
        assertThat(job.processedRecordsCount()).isEqualTo(2);
        assertThat(job.failedRecordsCount()).isEqualTo(0);

        GetContactResponse carol = sesV2.getContact(GetContactRequest.builder()
                .contactListName(LIST).emailAddress("carol@example.com").build());
        assertThat(carol.attributesData()).isEqualTo("{\"Name\":\"Carol\"}");
        assertThat(carol.topicPreferences()).hasSize(1);
        assertThat(carol.topicPreferences().get(0).subscriptionStatus()).isEqualTo(SubscriptionStatus.OPT_IN);
        GetContactResponse dave = sesV2.getContact(GetContactRequest.builder()
                .contactListName(LIST).emailAddress("dave@example.com").build());
        assertThat(dave.unsubscribeAll()).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("ListImportJobs returns both jobs and filters by destination type")
    void listImportJobs() {
        ListImportJobsResponse all = sesV2.listImportJobs(ListImportJobsRequest.builder().build());
        assertThat(all.importJobs()).extracting(ImportJobSummary::jobId).contains(suppressionJobId, contactJobId);
        assertThat(all.nextToken()).isNull();

        ListImportJobsResponse contactOnly = sesV2.listImportJobs(ListImportJobsRequest.builder()
                .importDestinationType(ImportDestinationType.CONTACT_LIST).pageSize(10).build());
        assertThat(contactOnly.importJobs()).extracting(ImportJobSummary::jobId)
                .contains(contactJobId).doesNotContain(suppressionJobId);
        assertThat(contactOnly.importJobs().get(0).jobStatus()).isEqualTo(JobStatus.COMPLETED);
    }

    @Test
    @Order(4)
    @DisplayName("CreateImportJob with a missing object completes as FAILED with an ErrorMessage")
    void createImportJob_missingObject_fails() throws InterruptedException {
        String jobId = sesV2.createImportJob(CreateImportJobRequest.builder()
                .importDataSource(ImportDataSource.builder()
                        .s3Url("s3://" + BUCKET + "/missing.csv").dataFormat(DataFormat.CSV).build())
                .importDestination(ImportDestination.builder()
                        .suppressionListDestination(SuppressionListDestination.builder()
                                .suppressionListImportAction(SuppressionListImportAction.DELETE).build())
                        .build())
                .build()).jobId();

        GetImportJobResponse job = pollUntilTerminal(jobId);
        assertThat(job.jobStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.failureInfo().errorMessage()).contains("does not exist");
    }

    @Test
    @Order(5)
    @DisplayName("CreateImportJob rejects an invalid S3Url and an unknown contact list")
    void createImportJob_validation() {
        assertThatThrownBy(() -> sesV2.createImportJob(CreateImportJobRequest.builder()
                .importDataSource(ImportDataSource.builder().s3Url("https://example.com/x.csv")
                        .dataFormat(DataFormat.CSV).build())
                .importDestination(ImportDestination.builder()
                        .suppressionListDestination(SuppressionListDestination.builder()
                                .suppressionListImportAction(SuppressionListImportAction.PUT).build())
                        .build())
                .build()))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> sesV2.createImportJob(CreateImportJobRequest.builder()
                .importDataSource(ImportDataSource.builder().s3Url("s3://" + BUCKET + "/" + CONTACTS_KEY)
                        .dataFormat(DataFormat.JSON).build())
                .importDestination(ImportDestination.builder()
                        .contactListDestination(ContactListDestination.builder()
                                .contactListName("no-such-list")
                                .contactListImportAction(ContactListImportAction.PUT).build())
                        .build())
                .build()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(6)
    @DisplayName("GetImportJob for an unknown id throws NotFoundException")
    void getImportJob_unknown() {
        assertThatThrownBy(() -> sesV2.getImportJob(GetImportJobRequest.builder().jobId("no-such-job").build()))
                .isInstanceOf(NotFoundException.class);
    }

    private static GetImportJobResponse pollUntilTerminal(String jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        GetImportJobResponse job;
        do {
            job = sesV2.getImportJob(GetImportJobRequest.builder().jobId(jobId).build());
            if (job.jobStatus() == JobStatus.COMPLETED || job.jobStatus() == JobStatus.FAILED) {
                return job;
            }
            Thread.sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return job;
    }
}
