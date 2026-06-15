package io.github.hectorvent.floci.services.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.batch.model.BatchComputeEnvironment;
import io.github.hectorvent.floci.services.batch.model.BatchJob;
import io.github.hectorvent.floci.services.batch.model.BatchJobDefinition;
import io.github.hectorvent.floci.services.batch.model.BatchJobQueue;
import io.github.hectorvent.floci.services.batch.model.BatchRunResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void dockerTimeoutFailsWithoutRetryingRemainingAttempts() throws Exception {
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        when(runner.run(any(BatchJob.class), anyInt()))
                .thenReturn(new BatchRunResult(137, "Job timed out", "log-stream", 1L, 2L, true));
        BatchService service = dockerService(runner);

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"timeout-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"timeout-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"timeout-job",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"},
                  "retryStrategy":{"attempts":3}
                }
                """), REGION).path("jobDefinitionArn").asText();

        String jobId = service.submitJob(json("""
                {
                  "jobName":"timeout-submit",
                  "jobQueue":"%s",
                  "jobDefinition":"%s",
                  "timeout":{"attemptDurationSeconds":60}
                }
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        JsonNode job = waitForJobStatus(service, jobId, "FAILED");
        assertNotNull(job);
        assertEquals("Job timed out", job.path("statusReason").asText());
        assertEquals(1, job.path("attempts").size());
        verify(runner, times(1)).run(any(BatchJob.class), anyInt());
    }

    @Test
    void dockerRetriesFailedAttemptAndCanSucceed() throws Exception {
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        when(runner.run(any(BatchJob.class), anyInt()))
                .thenReturn(new BatchRunResult(1, "first failed", "log-1", 1L, 2L, false))
                .thenReturn(new BatchRunResult(0, null, "log-2", 3L, 4L, false));
        BatchService service = dockerService(runner);

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"retry-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"retry-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"retry-job",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"},
                  "retryStrategy":{"attempts":2}
                }
                """), REGION).path("jobDefinitionArn").asText();

        String jobId = service.submitJob(json("""
                {"jobName":"retry-submit","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        JsonNode job = waitForJobStatus(service, jobId, "SUCCEEDED");
        assertNotNull(job);
        assertEquals(2, job.path("attempts").size());
        assertEquals(0, job.path("attempts").get(1).path("container").path("exitCode").asInt());
        verify(runner, times(2)).run(any(BatchJob.class), anyInt());
    }

    @Test
    void dockerRetryExhaustionFailsJobAndKeepsAttempts() throws Exception {
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        when(runner.run(any(BatchJob.class), anyInt()))
                .thenReturn(new BatchRunResult(1, "failed once", "log-1", 1L, 2L, false))
                .thenReturn(new BatchRunResult(2, "failed twice", "log-2", 3L, 4L, false));
        BatchService service = dockerService(runner);

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"exhaust-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"exhaust-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"exhaust-job",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"},
                  "retryStrategy":{"attempts":2}
                }
                """), REGION).path("jobDefinitionArn").asText();

        String jobId = service.submitJob(json("""
                {"jobName":"exhaust-submit","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        JsonNode job = waitForJobStatus(service, jobId, "FAILED");
        assertNotNull(job);
        assertEquals("failed twice", job.path("statusReason").asText());
        assertEquals(2, job.path("attempts").size());
        assertEquals(2, job.path("attempts").get(1).path("container").path("exitCode").asInt());
        verify(runner, times(2)).run(any(BatchJob.class), anyInt());
    }

    @Test
    void listJobsUsesStableJobIdTiebreakerForSameCreatedAt() throws Exception {
        ReverseScanJobStorage jobStore = new ReverseScanJobStorage();
        BatchService service = immediateService(jobStore);

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"page-tie-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"page-tie-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"page-tie-job",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"}
                }
                """), REGION).path("jobDefinitionArn").asText();

        service.submitJob(json("""
                {"jobName":"page-tie-first","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION);
        service.submitJob(json("""
                {"jobName":"page-tie-second","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION);

        List<BatchJob> jobs = jobStore.scan(k -> true);
        assertEquals(2, jobs.size());
        BatchJob secondInserted = jobs.get(0);
        BatchJob firstInserted = jobs.get(1);
        secondInserted.setCreatedAt(123L);
        secondInserted.setJobId("b-job");
        firstInserted.setCreatedAt(123L);
        firstInserted.setJobId("a-job");

        JsonNode firstPage = service.listJobs(json("""
                {"jobQueue":"%s","jobStatus":"SUCCEEDED","maxResults":1}
                """.formatted(queueArn)));
        assertEquals("a-job", firstPage.path("jobSummaryList").get(0).path("jobId").asText());

        JsonNode secondPage = service.listJobs(json("""
                {"jobQueue":"%s","jobStatus":"SUCCEEDED","maxResults":1,"nextToken":"%s"}
                """.formatted(queueArn, firstPage.path("nextToken").asText())));
        assertEquals("b-job", secondPage.path("jobSummaryList").get(0).path("jobId").asText());
    }

    private BatchService dockerService(BatchDockerRunner runner) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.runnerMode()).thenReturn("docker");

        return new BatchService(
                new InMemoryStorage<String, BatchJobDefinition>(),
                new InMemoryStorage<String, BatchJobQueue>(),
                new InMemoryStorage<String, BatchComputeEnvironment>(),
                new InMemoryStorage<String, BatchJob>(),
                new RegionResolver(REGION, ACCOUNT),
                config,
                objectMapper,
                runner);
    }

    private BatchService immediateService(StorageBackend<String, BatchJob> jobStore) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.runnerMode()).thenReturn("immediate");

        return new BatchService(
                new InMemoryStorage<String, BatchJobDefinition>(),
                new InMemoryStorage<String, BatchJobQueue>(),
                new InMemoryStorage<String, BatchComputeEnvironment>(),
                jobStore,
                new RegionResolver(REGION, ACCOUNT),
                config,
                objectMapper,
                mock(BatchDockerRunner.class));
    }

    private ObjectNode json(String body) throws Exception {
        return (ObjectNode) objectMapper.readTree(body);
    }

    private JsonNode waitForJobStatus(BatchService service, String jobId, String status) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("jobs").add(jobId);
        for (int i = 0; i < 100; i++) {
            JsonNode job = service.describeJobs(request).path("jobs").get(0);
            if (job != null && status.equals(job.path("status").asText())) {
                return job;
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static final class ReverseScanJobStorage implements StorageBackend<String, BatchJob> {
        private final LinkedHashMap<String, BatchJob> store = new LinkedHashMap<>();

        @Override
        public void put(String key, BatchJob value) {
            store.put(key, value);
        }

        @Override
        public Optional<BatchJob> get(String key) {
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public void delete(String key) {
            store.remove(key);
        }

        @Override
        public List<BatchJob> scan(Predicate<String> keyFilter) {
            List<BatchJob> values = new ArrayList<>();
            store.forEach((key, value) -> {
                if (keyFilter.test(key)) {
                    values.add(value);
                }
            });
            Collections.reverse(values);
            return values;
        }

        @Override
        public Set<String> keys() {
            return Set.copyOf(store.keySet());
        }

        @Override
        public void flush() {
        }

        @Override
        public void load() {
        }

        @Override
        public void clear() {
            store.clear();
        }
    }
}
