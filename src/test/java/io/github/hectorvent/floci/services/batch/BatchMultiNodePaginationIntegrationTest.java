package io.github.hectorvent.floci.services.batch;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.batch.model.BatchJob;
import io.github.hectorvent.floci.services.batch.model.BatchNodeExecution;
import io.github.hectorvent.floci.services.batch.model.BatchNodeProperties;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.batch.BatchClient;
import software.amazon.awssdk.services.batch.model.ClientException;
import software.amazon.awssdk.services.batch.model.KeyValuesPair;
import software.amazon.awssdk.services.batch.model.ListJobsResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class BatchMultiNodePaginationIntegrationTest {

    private static final String ACCOUNT = "741648900001";
    private static final Region REGION = Region.US_EAST_1;

    @Inject
    StorageFactory storageFactory;

    private AccountAwareStorageBackend<BatchJob> jobs;
    private final List<String> seededJobs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jobs = storageFactory.create("batch", "batch-jobs.json", new TypeReference<Map<String, BatchJob>>() {});
    }

    @AfterEach
    void cleanUp() {
        seededJobs.forEach(id -> jobs.deleteForAccount(ACCOUNT, id));
    }

    @Test
    void sdkPagesMultiNodeJobsAndItsPaginatorVisitsEveryNodeOnce() {
        String jobId = seedJob(3);
        try (BatchClient client = client(ACCOUNT)) {
            ListJobsResponse first = client.listJobs(request -> request.multiNodeJobId(jobId).maxResults(1));
            assertEquals(1, first.jobSummaryList().size());
            assertEquals(0, first.jobSummaryList().getFirst().nodeProperties().nodeIndex());
            assertTrue(first.jobSummaryList().getFirst().nodeProperties().isMainNode());
            assertNotNull(first.nextToken());
            ListJobsResponse second = client.listJobs(request -> request.multiNodeJobId(jobId)
                    .maxResults(1).nextToken(first.nextToken()));
            assertEquals(1, second.jobSummaryList().size());
            assertEquals(1, second.jobSummaryList().getFirst().nodeProperties().nodeIndex());
            assertNotNull(second.nextToken());
            ListJobsResponse last = client.listJobs(request -> request.multiNodeJobId(jobId)
                    .maxResults(1).nextToken(second.nextToken()));
            assertEquals(1, last.jobSummaryList().size());
            assertEquals(2, last.jobSummaryList().getFirst().nodeProperties().nodeIndex());
            assertNull(last.nextToken());
            assertEquals(List.of(0, 1, 2), client.listJobsPaginator(request -> request
                    .multiNodeJobId(jobId).maxResults(2)).jobSummaryList().stream()
                    .map(summary -> summary.nodeProperties().nodeIndex()).toList());
        }
    }

    @Test
    void sdkUsesTheExistingThousandResultLimitForMultiNodeJobs() {
        String jobId = seedJob(1001);
        try (BatchClient client = client(ACCOUNT)) {
            ListJobsResponse first = client.listJobs(request -> request.multiNodeJobId(jobId));
            assertEquals(1000, first.jobSummaryList().size());
            assertNotNull(first.nextToken());
            ListJobsResponse last = client.listJobs(request -> request.multiNodeJobId(jobId)
                    .nextToken(first.nextToken()));
            assertEquals(1, last.jobSummaryList().size());
            assertEquals(1000, last.jobSummaryList().getFirst().nodeProperties().nodeIndex());
            assertNull(last.nextToken());
            assertEquals(1000, client.listJobs(request -> request.multiNodeJobId(jobId)
                    .maxResults(Integer.MAX_VALUE)).jobSummaryList().size());
            assertEquals(1000, client.listJobs(request -> request.multiNodeJobId(jobId)
                    .filters(KeyValuesPair.builder().name("JOB_NAME").values("does-not-match").build()))
                    .jobSummaryList().size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-token", "-1", "2147483648"})
    void sdkRejectsInvalidMultiNodeTokens(String token) {
        String jobId = seedJob(3);
        try (BatchClient client = client(ACCOUNT)) {
            ClientException failure = assertThrows(ClientException.class,
                    () -> client.listJobs(request -> request.multiNodeJobId(jobId).nextToken(token)));
            assertEquals(400, failure.statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, Integer.MAX_VALUE})
    void sdkReturnsAnEmptyTerminalPageAtOrBeyondTheNodeCount(int offset) {
        String jobId = seedJob(3);
        try (BatchClient client = client(ACCOUNT)) {
            ListJobsResponse response = client.listJobs(request -> request.multiNodeJobId(jobId)
                    .maxResults(1).nextToken(Integer.toString(offset)));
            assertTrue(response.jobSummaryList().isEmpty());
            assertNull(response.nextToken());
        }
    }

    @Test
    void sdkKeepsMissingAndNonMultiNodeJobsEmptyAndDoesNotExposeAnotherAccount() {
        String plainJob = seedJob(0);
        String multiNodeJob = seedJob(3);
        try (BatchClient client = client(ACCOUNT); BatchClient otherAccount = client("741648900002")) {
            assertTrue(client.listJobs(request -> request.multiNodeJobId("missing")).jobSummaryList().isEmpty());
            assertTrue(client.listJobs(request -> request.multiNodeJobId(plainJob)).jobSummaryList().isEmpty());
            assertTrue(otherAccount.listJobs(request -> request.multiNodeJobId(multiNodeJob))
                    .jobSummaryList().isEmpty());
        }
    }

    private String seedJob(int nodes) {
        BatchJob job = new BatchJob();
        String id = UUID.randomUUID().toString();
        job.setJobId(id);
        job.setJobArn(AwsArnUtils.Arn.of("batch", REGION.id(), ACCOUNT, "job/" + id).toString());
        job.setJobName("pagination-" + id);
        job.setStatus("SUCCEEDED");
        job.setRegion(REGION.id());
        job.setAccountId(ACCOUNT);
        job.setCreatedAt(System.currentTimeMillis());
        if (nodes > 0) {
            BatchNodeProperties properties = new BatchNodeProperties();
            properties.setNumNodes(nodes);
            properties.setMainNode(0);
            job.setNodeProperties(properties);
            for (int index = 0; index < nodes; index++) {
                BatchNodeExecution execution = new BatchNodeExecution();
                execution.setNodeIndex(index);
                execution.setMainNode(index == 0);
                job.getNodeExecutions().add(execution);
            }
        }
        jobs.putForAccount(ACCOUNT, id, job);
        seededJobs.add(id);
        return id;
    }

    private static BatchClient client(String account) {
        return BatchClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(REGION)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }
}
