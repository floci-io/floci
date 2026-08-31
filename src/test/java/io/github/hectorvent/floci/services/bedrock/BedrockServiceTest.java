package io.github.hectorvent.floci.services.bedrock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrock.model.CreateModelInvocationJobRequest;
import io.github.hectorvent.floci.services.bedrock.model.CreateModelInvocationJobResponse;
import io.github.hectorvent.floci.services.bedrock.model.InputDataConfig;
import io.github.hectorvent.floci.services.bedrock.model.ModelInvocationJob;
import io.github.hectorvent.floci.services.bedrock.model.ModelInvocationJobStatus;
import io.github.hectorvent.floci.services.bedrock.model.ModelInvocationJobSummary;
import io.github.hectorvent.floci.services.bedrock.model.OutputDataConfig;
import io.github.hectorvent.floci.services.bedrockruntime.BedrockRuntimeService;
import io.github.hectorvent.floci.services.bedrockruntime.backend.StubBackend;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BedrockServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";

    private BedrockService service;
    private StorageFactory storageFactory;
    private EmulatorConfig config;
    private RegionResolver regionResolver;
    private ObjectMapper objectMapper;
    private BedrockRuntimeService bedrockRuntimeService;
    private S3Service s3Service;
    private EventBridgeService eventBridgeService;

    @BeforeEach
    void setUp() {
        storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(eq("bedrock"), eq("bedrock-jobs.json"), any()))
                .thenAnswer(inv -> new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT_ID));

        config = mock(EmulatorConfig.class);
        regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        objectMapper = new ObjectMapper();

        StubBackend stubBackend = new StubBackend(objectMapper);
        EmulatorConfig.BedrockRuntimeServiceConfig bedrockRuntimeConfig = mock(EmulatorConfig.BedrockRuntimeServiceConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.bedrockRuntime()).thenReturn(bedrockRuntimeConfig);
        when(bedrockRuntimeConfig.backend()).thenReturn("stub");

        bedrockRuntimeService = new BedrockRuntimeService(config, stubBackend, null);
        s3Service = mock(S3Service.class);
        eventBridgeService = mock(EventBridgeService.class);

        service = new BedrockService(
                storageFactory,
                config,
                regionResolver,
                objectMapper,
                bedrockRuntimeService,
                s3Service,
                eventBridgeService
        );
    }

    private CreateModelInvocationJobRequest createValidRequest(String jobName, String inputUri, String outputUri) {
        return new CreateModelInvocationJobRequest(
                jobName,
                "anthropic.claude-3-haiku-20240307-v1:0",
                "arn:aws:iam::000000000000:role/BedrockBatchRole",
                "client-token-123",
                new InputDataConfig(new InputDataConfig.S3InputDataConfig(inputUri, "JSONL", null)),
                new OutputDataConfig(new OutputDataConfig.S3OutputDataConfig(outputUri, null, null)),
                null,
                24,
                Collections.emptyList()
        );
    }

    @Test
    void createModelInvocationJob_validation_missingFields() {
        // null request
        AwsException e1 = assertThrows(AwsException.class, () -> service.createModelInvocationJob(null, REGION));
        assertEquals(400, e1.getHttpStatus());

        // missing jobName
        CreateModelInvocationJobRequest reqNoName = new CreateModelInvocationJobRequest(
                null, "modelId", "roleArn", "token",
                new InputDataConfig(new InputDataConfig.S3InputDataConfig("s3://b/in", null, null)),
                new OutputDataConfig(new OutputDataConfig.S3OutputDataConfig("s3://b/out", null, null)),
                null, 24, null
        );
        assertThrows(AwsException.class, () -> service.createModelInvocationJob(reqNoName, REGION));

        // missing modelId
        CreateModelInvocationJobRequest reqNoModel = new CreateModelInvocationJobRequest(
                "jobName", "", "roleArn", "token",
                new InputDataConfig(new InputDataConfig.S3InputDataConfig("s3://b/in", null, null)),
                new OutputDataConfig(new OutputDataConfig.S3OutputDataConfig("s3://b/out", null, null)),
                null, 24, null
        );
        assertThrows(AwsException.class, () -> service.createModelInvocationJob(reqNoModel, REGION));

        // missing roleArn
        CreateModelInvocationJobRequest reqNoRole = new CreateModelInvocationJobRequest(
                "jobName", "modelId", " ", "token",
                new InputDataConfig(new InputDataConfig.S3InputDataConfig("s3://b/in", null, null)),
                new OutputDataConfig(new OutputDataConfig.S3OutputDataConfig("s3://b/out", null, null)),
                null, 24, null
        );
        assertThrows(AwsException.class, () -> service.createModelInvocationJob(reqNoRole, REGION));

        // missing input s3 uri
        CreateModelInvocationJobRequest reqNoInput = new CreateModelInvocationJobRequest(
                "jobName", "modelId", "roleArn", "token",
                new InputDataConfig(new InputDataConfig.S3InputDataConfig(null, null, null)),
                new OutputDataConfig(new OutputDataConfig.S3OutputDataConfig("s3://b/out", null, null)),
                null, 24, null
        );
        assertThrows(AwsException.class, () -> service.createModelInvocationJob(reqNoInput, REGION));

        // missing output s3 uri
        CreateModelInvocationJobRequest reqNoOutput = new CreateModelInvocationJobRequest(
                "jobName", "modelId", "roleArn", "token",
                new InputDataConfig(new InputDataConfig.S3InputDataConfig("s3://b/in", null, null)),
                new OutputDataConfig(new OutputDataConfig.S3OutputDataConfig("", null, null)),
                null, 24, null
        );
        assertThrows(AwsException.class, () -> service.createModelInvocationJob(reqNoOutput, REGION));
    }

    @Test
    void createModelInvocationJob_successfulExecution() {
        String inputBucket = "test-input-bucket";
        String inputKey = "data/prompts.jsonl";
        String inputUri = "s3://" + inputBucket + "/" + inputKey;
        String outputUri = "s3://test-output-bucket/results";

        String jsonlContent = """
                {"recordId": "rec-1", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello 1"}]}]}}
                {"recordId": "rec-2", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello 2"}]}]}}
                """;

        S3Object s3Object = new S3Object(inputBucket, inputKey, jsonlContent.getBytes(StandardCharsets.UTF_8), "application/jsonlines");
        when(s3Service.getObject(eq(inputBucket), eq(inputKey))).thenReturn(s3Object);

        CreateModelInvocationJobRequest req = createValidRequest("my-batch-job", inputUri, outputUri);
        CreateModelInvocationJobResponse response = service.createModelInvocationJob(req, REGION);

        assertNotNull(response.jobArn());
        assertTrue(response.jobArn().startsWith("arn:aws:bedrock:us-east-1:000000000000:model-invocation-job/"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ModelInvocationJob job = service.getModelInvocationJob(response.jobArn(), REGION);
            assertEquals("my-batch-job", job.getJobName());
            assertEquals(ModelInvocationJobStatus.COMPLETED, job.getStatus());
            assertNotNull(job.getEndTime());
        });

        // Verify EventBridge events were emitted
        verify(eventBridgeService, Mockito.atLeast(2)).putEvents(any(), eq(REGION), eq(ACCOUNT_ID));

        // Verify S3 putObject was called for output JSONL and manifest.json.out
        verify(s3Service).putObject(eq("test-output-bucket"), Mockito.endsWith("prompts.jsonl.out"), any(), eq("application/jsonlines"), any());
        verify(s3Service).putObject(eq("test-output-bucket"), Mockito.endsWith("manifest.json.out"), any(), eq("application/json"), any());
    }

    @Test
    void createModelInvocationJob_partialFailures() {
        String inputBucket = "test-input-bucket";
        String inputKey = "data/mixed.jsonl";
        String inputUri = "s3://" + inputBucket + "/" + inputKey;
        String outputUri = "s3://test-output-bucket/results";

        // One valid line, one invalid line (missing modelInput)
        String jsonlContent = """
                {"recordId": "rec-good", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello"}]}]}}
                {"recordId": "rec-bad"}
                """;

        S3Object s3Object = new S3Object(inputBucket, inputKey, jsonlContent.getBytes(StandardCharsets.UTF_8), "application/jsonlines");
        when(s3Service.getObject(eq(inputBucket), eq(inputKey))).thenReturn(s3Object);

        CreateModelInvocationJobRequest req = createValidRequest("mixed-batch-job", inputUri, outputUri);
        CreateModelInvocationJobResponse response = service.createModelInvocationJob(req, REGION);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ModelInvocationJob job = service.getModelInvocationJob(response.jobArn(), REGION);
            assertEquals(ModelInvocationJobStatus.PARTIALLY_COMPLETED, job.getStatus());
            assertTrue(job.getMessage().contains("1 successes and 1 errors"));
        });
    }

    @Test
    void createModelInvocationJob_inputS3FileNotFound_fails() {
        String inputUri = "s3://test-input-bucket/missing.jsonl";
        String outputUri = "s3://test-output-bucket/results";

        when(s3Service.getObject(eq("test-input-bucket"), eq("missing.jsonl")))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist", 404));

        CreateModelInvocationJobRequest req = createValidRequest("failed-job", inputUri, outputUri);
        CreateModelInvocationJobResponse response = service.createModelInvocationJob(req, REGION);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ModelInvocationJob job = service.getModelInvocationJob(response.jobArn(), REGION);
            assertEquals(ModelInvocationJobStatus.FAILED, job.getStatus());
            assertTrue(job.getMessage().contains("Failed to read input S3 file"));
        });
    }

    @Test
    void getModelInvocationJob_resolvesByIdArnOrName() {
        String inputUri = "s3://test-input-bucket/prompts.jsonl";
        String outputUri = "s3://test-output-bucket/results";
        when(s3Service.getObject(any(), any()))
                .thenReturn(new S3Object("test-input-bucket", "prompts.jsonl", "{}".getBytes(StandardCharsets.UTF_8), "application/json"));

        CreateModelInvocationJobRequest req = createValidRequest("named-job", inputUri, outputUri);
        CreateModelInvocationJobResponse response = service.createModelInvocationJob(req, REGION);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ModelInvocationJob byArn = service.getModelInvocationJob(response.jobArn(), REGION);
            assertNotNull(byArn);
            assertEquals("named-job", byArn.getJobName());
        });

        // Resolve by ARN
        ModelInvocationJob byArn = service.getModelInvocationJob(response.jobArn(), REGION);
        assertNotNull(byArn);
        assertEquals("named-job", byArn.getJobName());

        // Resolve by ID
        ModelInvocationJob byId = service.getModelInvocationJob(byArn.getJobId(), REGION);
        assertEquals(response.jobArn(), byId.getJobArn());

        // Resolve by Name
        ModelInvocationJob byName = service.getModelInvocationJob("named-job", REGION);
        assertEquals(response.jobArn(), byName.getJobArn());

        // Non-existent
        AwsException notFound = assertThrows(AwsException.class, () -> service.getModelInvocationJob("unknown", REGION));
        assertEquals(404, notFound.getHttpStatus());
    }

    @Test
    void stopModelInvocationJob_terminalJobThrows() {
        String inputUri = "s3://test-input-bucket/prompts.jsonl";
        String outputUri = "s3://test-output-bucket/results";
        when(s3Service.getObject(any(), any()))
                .thenReturn(new S3Object("test-input-bucket", "prompts.jsonl", "".getBytes(StandardCharsets.UTF_8), "application/json"));

        CreateModelInvocationJobRequest req = createValidRequest("completed-job", inputUri, outputUri);
        CreateModelInvocationJobResponse response = service.createModelInvocationJob(req, REGION);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ModelInvocationJob job = service.getModelInvocationJob(response.jobArn(), REGION);
            assertEquals(ModelInvocationJobStatus.COMPLETED, job.getStatus());
        });

        // Job finished and is COMPLETED, trying to stop should throw ValidationException (400)
        AwsException e = assertThrows(AwsException.class, () -> service.stopModelInvocationJob(response.jobArn(), REGION));
        assertEquals(400, e.getHttpStatus());
        assertTrue(e.getMessage().contains("terminal status"));
    }

    @Test
    void listModelInvocationJobs_filtersAndPagination() {
        Instant now = Instant.now();

        ModelInvocationJob job1 = new ModelInvocationJob(
                "arn:aws:bedrock:us-east-1:000000000000:model-invocation-job/job1",
                "alpha-job", "model1", null, "role1",
                ModelInvocationJobStatus.COMPLETED, null,
                now.minus(10, ChronoUnit.MINUTES), now, now, now.plus(1, ChronoUnit.DAYS),
                24, null, null, null, null
        );

        ModelInvocationJob job2 = new ModelInvocationJob(
                "arn:aws:bedrock:us-east-1:000000000000:model-invocation-job/job2",
                "beta-job", "model2", null, "role2",
                ModelInvocationJobStatus.FAILED, null,
                now.minus(5, ChronoUnit.MINUTES), now, now, now.plus(1, ChronoUnit.DAYS),
                24, null, null, null, null
        );

        service.clear();

        // Inject jobs
        service.createModelInvocationJob(createValidRequest("job1", "s3://b/in", "s3://b/out"), REGION);
        service.createModelInvocationJob(createValidRequest("beta-job", "s3://b/in", "s3://b/out"), REGION);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            // List all
            PaginatedResult<ModelInvocationJobSummary> all = service.listModelInvocationJobs(
                    null, null, null, null, null, "Descending", 10, null, REGION
            );
            assertEquals(2, all.items().size());

            // Filter by nameContains
            PaginatedResult<ModelInvocationJobSummary> betaFilter = service.listModelInvocationJobs(
                    null, "beta", null, null, null, null, 10, null, REGION
            );
            assertEquals(1, betaFilter.items().size());
            assertEquals("beta-job", betaFilter.items().getFirst().jobName());

            // Filter by statusEquals
            PaginatedResult<ModelInvocationJobSummary> completedFilter = service.listModelInvocationJobs(
                    ModelInvocationJobStatus.COMPLETED, null, null, null, null, null, 10, null, REGION
            );
            assertEquals(0, completedFilter.items().size()); // they failed because s3 wasn't mocked to return data for these, so they are FAILED
        });
    }

    @Test
    void parseS3Uri_tests() {
        BedrockService.S3Location loc1 = BedrockService.parseS3Uri("s3://my-bucket/path/to/file.jsonl");
        assertEquals("my-bucket", loc1.bucket());
        assertEquals("path/to/file.jsonl", loc1.key());

        BedrockService.S3Location loc2 = BedrockService.parseS3Uri("s3://my-bucket");
        assertEquals("my-bucket", loc2.bucket());
        assertEquals("", loc2.key());

        assertThrows(AwsException.class, () -> BedrockService.parseS3Uri("http://not-s3"));
    }
}
