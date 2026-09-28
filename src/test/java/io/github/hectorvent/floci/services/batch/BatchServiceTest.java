package io.github.hectorvent.floci.services.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.batch.model.BatchComputeEnvironment;
import io.github.hectorvent.floci.services.batch.model.BatchJob;
import io.github.hectorvent.floci.services.batch.model.BatchJobDefinition;
import io.github.hectorvent.floci.services.batch.model.BatchJobQueue;
import io.github.hectorvent.floci.services.batch.model.BatchNodeExecution;
import io.github.hectorvent.floci.services.batch.model.BatchRunResult;
import io.github.hectorvent.floci.services.batch.model.BatchStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @Test
    void updateJobQueueChangesPriorityStateAndComputeEnvironmentOrder() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());

        String firstComputeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"update-ce-1","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String secondComputeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"update-ce-2","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"update-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(firstComputeArn)), REGION).path("jobQueueArn").asText();

        JsonNode updated = service.updateJobQueue(json("""
                {
                  "jobQueue":"update-queue",
                  "state":"DISABLED",
                  "priority":5,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(secondComputeArn)));
        assertEquals("update-queue", updated.path("jobQueueName").asText());
        assertEquals(queueArn, updated.path("jobQueueArn").asText());

        JsonNode detail = service.describeJobQueues(json("""
                {"jobQueues":["%s"]}
                """.formatted(queueArn))).path("jobQueues").get(0);
        assertEquals("DISABLED", detail.path("state").asText());
        assertEquals(5, detail.path("priority").asInt());
        assertEquals(secondComputeArn,
                detail.path("computeEnvironmentOrder").get(0).path("computeEnvironment").asText());
    }

    @Test
    void updateJobQueueRejectsUnknownQueue() {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        AwsException e = assertThrows(AwsException.class, () -> service.updateJobQueue(json("""
                {"jobQueue":"missing-queue","priority":2}
                """)));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void deleteJobQueueRejectsEnabledQueue() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"delete-enabled-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        service.createJobQueue(json("""
                {
                  "jobQueueName":"delete-enabled-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.deleteJobQueue(json("""
                {"jobQueue":"delete-enabled-queue"}
                """)));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void deleteJobQueueRemovesDisabledQueueAndToleratesRepeatDeletes() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"delete-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"delete-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();

        service.updateJobQueue(json("""
                {"jobQueue":"delete-queue","state":"DISABLED"}
                """));
        JsonNode deleted = service.deleteJobQueue(json("""
                {"jobQueue":"delete-queue"}
                """));
        assertEquals(0, deleted.size());

        JsonNode queues = service.describeJobQueues(json("""
                {"jobQueues":["%s"]}
                """.formatted(queueArn))).path("jobQueues");
        assertEquals(0, queues.size());

        JsonNode repeated = service.deleteJobQueue(json("""
                {"jobQueue":"delete-queue"}
                """));
        assertEquals(0, repeated.size());
    }

    @Test
    void updateComputeEnvironmentChangesStateServiceRoleAndComputeResources() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());

        String computeArn = service.createComputeEnvironment(json("""
                {
                  "computeEnvironmentName":"update-ce",
                  "type":"MANAGED",
                  "computeResources":{"type":"EC2","minvCpus":0,"maxvCpus":4,"instanceTypes":["optimal"]}
                }
                """), REGION).path("computeEnvironmentArn").asText();

        JsonNode updated = service.updateComputeEnvironment(json("""
                {
                  "computeEnvironment":"update-ce",
                  "state":"DISABLED",
                  "serviceRole":"arn:aws:iam::000000000000:role/BatchServiceRole",
                  "computeResources":{"maxvCpus":8,"desiredvCpus":2}
                }
                """));
        assertEquals("update-ce", updated.path("computeEnvironmentName").asText());
        assertEquals(computeArn, updated.path("computeEnvironmentArn").asText());

        JsonNode detail = service.describeComputeEnvironments(json("""
                {"computeEnvironments":["%s"]}
                """.formatted(computeArn))).path("computeEnvironments").get(0);
        assertEquals("DISABLED", detail.path("state").asText());
        assertEquals("arn:aws:iam::000000000000:role/BatchServiceRole", detail.path("serviceRole").asText());
        // Partial update: only the sent fields change, minvCpus/instanceTypes survive untouched.
        assertEquals(0, detail.path("computeResources").path("minvCpus").asInt());
        assertEquals(8, detail.path("computeResources").path("maxvCpus").asInt());
        assertEquals(2, detail.path("computeResources").path("desiredvCpus").asInt());
        assertEquals("optimal", detail.path("computeResources").path("instanceTypes").get(0).asText());
    }

    @Test
    void updateComputeEnvironmentRejectsUnknownEnvironment() {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        AwsException e = assertThrows(AwsException.class, () -> service.updateComputeEnvironment(json("""
                {"computeEnvironment":"missing-ce","state":"DISABLED"}
                """)));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void updateComputeEnvironmentRejectsInvalidState() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"invalid-state-ce","type":"MANAGED"}
                """), REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.updateComputeEnvironment(json("""
                {"computeEnvironment":"invalid-state-ce","state":"SUSPENDED"}
                """)));
        assertEquals("ClientException", e.getErrorCode());

        // Rejected before being written: the environment is still ENABLED, not stuck holding
        // an invalid value DeleteComputeEnvironment could never match against.
        JsonNode detail = service.describeComputeEnvironments(json("""
                {"computeEnvironments":["invalid-state-ce"]}
                """)).path("computeEnvironments").get(0);
        assertEquals("ENABLED", detail.path("state").asText());
    }

    @Test
    void createComputeEnvironmentRejectsInvalidState() {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        AwsException e = assertThrows(AwsException.class, () -> service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"bad-create-ce","type":"MANAGED","state":"SUSPENDED"}
                """), REGION));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void deleteComputeEnvironmentRejectsEnabledEnvironment() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"delete-enabled-ce","type":"MANAGED"}
                """), REGION);

        AwsException e = assertThrows(AwsException.class, () -> service.deleteComputeEnvironment(json("""
                {"computeEnvironment":"delete-enabled-ce"}
                """)));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void deleteComputeEnvironmentRejectsEnvironmentStillAttachedToJobQueue() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"attached-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        service.createJobQueue(json("""
                {
                  "jobQueueName":"attached-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION);
        service.updateComputeEnvironment(json("""
                {"computeEnvironment":"attached-ce","state":"DISABLED"}
                """));

        AwsException e = assertThrows(AwsException.class, () -> service.deleteComputeEnvironment(json("""
                {"computeEnvironment":"attached-ce"}
                """)));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void deleteComputeEnvironmentRemovesDisabledEnvironmentAndToleratesRepeatDeletes() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"delete-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();

        service.updateComputeEnvironment(json("""
                {"computeEnvironment":"delete-ce","state":"DISABLED"}
                """));
        JsonNode deleted = service.deleteComputeEnvironment(json("""
                {"computeEnvironment":"delete-ce"}
                """));
        assertEquals(0, deleted.size());

        JsonNode envs = service.describeComputeEnvironments(json("""
                {"computeEnvironments":["%s"]}
                """.formatted(computeArn))).path("computeEnvironments");
        assertEquals(0, envs.size());

        JsonNode repeated = service.deleteComputeEnvironment(json("""
                {"computeEnvironment":"delete-ce"}
                """));
        assertEquals(0, repeated.size());
    }

    @Test
    void submitArrayJobFansOutChildrenAndAggregatesStatus() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());

        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"array-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {
                  "jobQueueName":"array-queue",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"array-job",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"}
                }
                """), REGION).path("jobDefinitionArn").asText();

        JsonNode submitted = service.submitJob(json("""
                {
                  "jobName":"array-submit",
                  "jobQueue":"%s",
                  "jobDefinition":"%s",
                  "arrayProperties":{"size":3}
                }
                """.formatted(queueArn, definitionArn)), REGION);
        String parentId = submitted.path("jobId").asText();

        JsonNode parent = service.describeJobs(json("""
                {"jobs":["%s"]}
                """.formatted(parentId))).path("jobs").get(0);
        assertEquals("SUCCEEDED", parent.path("status").asText());
        assertEquals(3, parent.path("arrayProperties").path("size").asInt());
        assertEquals(3, parent.path("arrayProperties").path("statusSummary").path("SUCCEEDED").asInt());
        assertEquals(0, parent.path("container").size());

        JsonNode child = service.describeJobs(json("""
                {"jobs":["%s:1"]}
                """.formatted(parentId))).path("jobs").get(0);
        assertEquals("SUCCEEDED", child.path("status").asText());
        assertEquals(1, child.path("arrayProperties").path("index").asInt());
        assertEquals("array-submit", child.path("jobName").asText());

        JsonNode childList = service.listJobs(json("""
                {"arrayJobId":"%s"}
                """.formatted(parentId))).path("jobSummaryList");
        assertEquals(3, childList.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i, childList.get(i).path("arrayProperties").path("index").asInt());
        }

        JsonNode queueList = service.listJobs(json("""
                {"jobQueue":"%s","jobStatus":"SUCCEEDED"}
                """.formatted(queueArn))).path("jobSummaryList");
        long matchingParents = 0;
        for (JsonNode summary : queueList) {
            if (parentId.equals(summary.path("jobId").asText())) {
                matchingParents++;
            }
            assertEquals(false, summary.path("jobId").asText().contains(":"));
        }
        assertEquals(1, matchingParents);
    }

    @Test
    void submitArrayJobRejectsSizeOutOfBounds() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String queueArn = arrayReadyQueue(service);
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"array-bounds-job",
                  "type":"container",
                  "containerProperties":{"image":"public.ecr.aws/example/job:latest"}
                }
                """), REGION).path("jobDefinitionArn").asText();

        AwsException tooSmall = assertThrows(AwsException.class, () -> service.submitJob(json("""
                {"jobName":"too-small","jobQueue":"%s","jobDefinition":"%s","arrayProperties":{"size":1}}
                """.formatted(queueArn, definitionArn)), REGION));
        assertEquals("ClientException", tooSmall.getErrorCode());

        AwsException tooBig = assertThrows(AwsException.class, () -> service.submitJob(json("""
                {"jobName":"too-big","jobQueue":"%s","jobDefinition":"%s","arrayProperties":{"size":10001}}
                """.formatted(queueArn, definitionArn)), REGION));
        assertEquals("ClientException", tooBig.getErrorCode());
    }

    @Test
    void listJobsRequiresExactlyOneSelector() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String queueArn = arrayReadyQueue(service);

        AwsException none = assertThrows(AwsException.class, () -> service.listJobs(json("{}")));
        assertEquals("ClientException", none.getErrorCode());

        AwsException both = assertThrows(AwsException.class, () -> service.listJobs(json("""
                {"jobQueue":"%s","arrayJobId":"some-id"}
                """.formatted(queueArn))));
        assertEquals("ClientException", both.getErrorCode());
    }

    @Test
    void registerMultiNodeJobDefinitionValidatesRangeCoverage() {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());

        AwsException e = assertThrows(AwsException.class, () -> service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"gap-def",
                  "type":"multinode",
                  "nodeProperties":{
                    "numNodes":3,
                    "mainNode":0,
                    "nodeRangeProperties":[
                      {"targetNodes":"0:0","container":{"image":"main:latest"}}
                    ]
                  }
                }
                """), REGION));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void registerMultiNodeJobDefinitionRejectsContainerProperties() {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());

        AwsException e = assertThrows(AwsException.class, () -> service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"mixed-def",
                  "type":"multinode",
                  "containerProperties":{"image":"solo:latest"},
                  "nodeProperties":{
                    "numNodes":2,
                    "mainNode":0,
                    "nodeRangeProperties":[{"targetNodes":"0:1","container":{"image":"worker:latest"}}]
                  }
                }
                """), REGION));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void submitMultiNodeJobRunsAllNodesAndSucceeds() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String queueArn = arrayReadyQueue(service);
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"mnp-job",
                  "type":"multinode",
                  "nodeProperties":{
                    "numNodes":2,
                    "mainNode":0,
                    "nodeRangeProperties":[
                      {"targetNodes":"0:0","container":{"image":"main:latest"}},
                      {"targetNodes":"1:1","container":{"image":"worker:latest"}}
                    ]
                  }
                }
                """), REGION).path("jobDefinitionArn").asText();

        String jobId = service.submitJob(json("""
                {"jobName":"mnp-submit","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        JsonNode job = waitForJobStatus(service, jobId, "SUCCEEDED");
        assertNotNull(job);
        assertEquals(2, job.path("nodeProperties").path("numNodes").asInt());
        assertEquals(0, job.path("nodeProperties").path("mainNode").asInt());
        assertEquals(1, job.path("attempts").size());
        assertEquals(0, job.path("container").size());

        JsonNode nodes = service.listJobs(json("""
                {"multiNodeJobId":"%s"}
                """.formatted(jobId))).path("jobSummaryList");
        assertEquals(2, nodes.size());
        assertEquals(0, nodes.get(0).path("nodeProperties").path("nodeIndex").asInt());
        assertEquals(true, nodes.get(0).path("nodeProperties").path("isMainNode").asBoolean());
        assertEquals(1, nodes.get(1).path("nodeProperties").path("nodeIndex").asInt());
        assertEquals(false, nodes.get(1).path("nodeProperties").path("isMainNode").asBoolean());
    }

    @Test
    void submitMultiNodeJobSucceedsWhenOnlyAChildNodeFails() throws Exception {
        // AWS determines the job's outcome solely from the main node, per the MNP user guide.
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        when(runner.run(any(BatchJob.class), anyInt(), any(BatchNodeExecution.class)))
                .thenAnswer(invocation -> {
                    BatchNodeExecution node = invocation.getArgument(2);
                    return node.getNodeIndex() == 1
                            ? new BatchRunResult(3, "worker crashed", "log-1", 1L, 2L, false)
                            : new BatchRunResult(0, null, "log-0", 1L, 2L, false);
                });
        BatchService service = dockerService(runner);
        String queueArn = arrayReadyQueue(service);
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"mnp-child-fail-job",
                  "type":"multinode",
                  "nodeProperties":{
                    "numNodes":2,
                    "mainNode":0,
                    "nodeRangeProperties":[{"targetNodes":"0:1","container":{"image":"worker:latest"}}]
                  }
                }
                """), REGION).path("jobDefinitionArn").asText();

        String jobId = service.submitJob(json("""
                {"jobName":"mnp-child-fail-submit","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        JsonNode job = waitForJobStatus(service, jobId, "SUCCEEDED");
        assertNotNull(job);
        verify(runner, times(2)).run(any(BatchJob.class), anyInt(), any(BatchNodeExecution.class));

        JsonNode nodes = service.listJobs(json("""
                {"multiNodeJobId":"%s"}
                """.formatted(jobId))).path("jobSummaryList");
        assertEquals(3, nodes.get(1).path("container").path("exitCode").asInt());
    }

    @Test
    void submitMultiNodeJobFailsWhenTheMainNodeFails() throws Exception {
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        when(runner.run(any(BatchJob.class), anyInt(), any(BatchNodeExecution.class)))
                .thenAnswer(invocation -> {
                    BatchNodeExecution node = invocation.getArgument(2);
                    return node.isMainNode()
                            ? new BatchRunResult(3, "main crashed", "log-0", 1L, 2L, false)
                            : new BatchRunResult(0, null, "log-1", 1L, 2L, false);
                });
        BatchService service = dockerService(runner);
        String queueArn = arrayReadyQueue(service);
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"mnp-main-fail-job",
                  "type":"multinode",
                  "nodeProperties":{
                    "numNodes":2,
                    "mainNode":0,
                    "nodeRangeProperties":[{"targetNodes":"0:1","container":{"image":"worker:latest"}}]
                  }
                }
                """), REGION).path("jobDefinitionArn").asText();

        String jobId = service.submitJob(json("""
                {"jobName":"mnp-main-fail-submit","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        JsonNode job = waitForJobStatus(service, jobId, "FAILED");
        assertNotNull(job);
        assertEquals("main crashed", job.path("statusReason").asText());
        verify(runner, times(2)).run(any(BatchJob.class), anyInt(), any(BatchNodeExecution.class));
    }

    @Test
    void submitMultiNodeJobRejectsContainerOverrides() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String queueArn = arrayReadyQueue(service);
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"mnp-overrides-job",
                  "type":"multinode",
                  "nodeProperties":{
                    "numNodes":1,
                    "mainNode":0,
                    "nodeRangeProperties":[{"targetNodes":"0:0","container":{"image":"main:latest"}}]
                  }
                }
                """), REGION).path("jobDefinitionArn").asText();

        AwsException e = assertThrows(AwsException.class, () -> service.submitJob(json("""
                {
                  "jobName":"mnp-overrides-submit","jobQueue":"%s","jobDefinition":"%s",
                  "containerOverrides":{"command":["nope"]}
                }
                """.formatted(queueArn, definitionArn)), REGION));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void submitJobRejectsArrayPropertiesForMultiNodeJobDefinition() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String queueArn = arrayReadyQueue(service);
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"mnp-array-job",
                  "type":"multinode",
                  "nodeProperties":{
                    "numNodes":1,
                    "mainNode":0,
                    "nodeRangeProperties":[{"targetNodes":"0:0","container":{"image":"main:latest"}}]
                  }
                }
                """), REGION).path("jobDefinitionArn").asText();

        AwsException e = assertThrows(AwsException.class, () -> service.submitJob(json("""
                {"jobName":"mnp-array-submit","jobQueue":"%s","jobDefinition":"%s","arrayProperties":{"size":2}}
                """.formatted(queueArn, definitionArn)), REGION));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void nodeOverridesNumNodesRequiresAnOpenEndedRange() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String queueArn = arrayReadyQueue(service);
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"mnp-closed-range-job",
                  "type":"multinode",
                  "nodeProperties":{
                    "numNodes":2,
                    "mainNode":0,
                    "nodeRangeProperties":[{"targetNodes":"0:1","container":{"image":"worker:latest"}}]
                  }
                }
                """), REGION).path("jobDefinitionArn").asText();

        AwsException e = assertThrows(AwsException.class, () -> service.submitJob(json("""
                {
                  "jobName":"mnp-closed-range-submit","jobQueue":"%s","jobDefinition":"%s",
                  "nodeOverrides":{"numNodes":4}
                }
                """.formatted(queueArn, definitionArn)), REGION));
        assertEquals("ClientException", e.getErrorCode());
    }

    @Test
    void cancelJobFailsRunnableJobWithRequestedReason() throws Exception {
        InMemoryStorage<String, BatchJob> jobStore = new InMemoryStorage<>();
        BatchService service = service("immediate", jobStore, mock(BatchDockerRunner.class));
        BatchJob job = storedJob("cancel-runnable", BatchStatus.RUNNABLE);
        jobStore.put(job.getJobId(), job);

        service.cancelJob(json("""
                {"jobId":"cancel-runnable","reason":"No longer needed"}
                """));

        JsonNode detail = service.describeJobs(json("""
                {"jobs":["cancel-runnable"]}
                """)).path("jobs").get(0);
        assertEquals("FAILED", detail.path("status").asText());
        assertEquals("No longer needed", detail.path("statusReason").asText());
        assertTrue(detail.path("stoppedAt").asLong() > 0);
    }

    @Test
    void cancelJobDoesNotStopRunningJob() throws Exception {
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        CountDownLatch runStarted = new CountDownLatch(1);
        CountDownLatch releaseRun = new CountDownLatch(1);
        when(runner.run(any(BatchJob.class), anyInt())).thenAnswer(invocation -> {
            runStarted.countDown();
            assertTrue(releaseRun.await(2, TimeUnit.SECONDS));
            return new BatchRunResult(0, null, "log-stream", 1L, 2L, false);
        });
        BatchService service = dockerService(runner);
        String queueArn = arrayReadyQueue(service);
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"cancel-running-job",
                  "type":"container",
                  "containerProperties":{"image":"job:latest"}
                }
                """), REGION).path("jobDefinitionArn").asText();
        String jobId = service.submitJob(json("""
                {"jobName":"cancel-running","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        assertTrue(runStarted.await(2, TimeUnit.SECONDS));
        try {
            service.cancelJob(json("""
                    {"jobId":"%s","reason":"Too late to cancel"}
                    """.formatted(jobId)));
            JsonNode running = service.describeJobs(json("""
                    {"jobs":["%s"]}
                    """.formatted(jobId))).path("jobs").get(0);
            assertEquals("RUNNING", running.path("status").asText());
            verify(runner, never()).requestStop(jobId);
            verify(runner, never()).stopJob(jobId);
        } finally {
            releaseRun.countDown();
        }
        assertNotNull(waitForJobStatus(service, jobId, "SUCCEEDED"));
    }

    @Test
    void terminateJobStopsRunningContainerAndPreservesRequestedReason() throws Exception {
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        CountDownLatch runStarted = new CountDownLatch(1);
        CountDownLatch stopRequested = new CountDownLatch(1);
        when(runner.run(any(BatchJob.class), anyInt())).thenAnswer(invocation -> {
            runStarted.countDown();
            assertTrue(stopRequested.await(2, TimeUnit.SECONDS));
            return new BatchRunResult(137, "Job terminated", "log-stream", 1L, 2L, false);
        });
        doAnswer(invocation -> {
            stopRequested.countDown();
            return null;
        }).when(runner).requestStop(any(String.class));
        BatchService service = dockerService(runner);
        String queueArn = arrayReadyQueue(service);
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"terminate-running-job",
                  "type":"container",
                  "containerProperties":{"image":"job:latest"}
                }
                """), REGION).path("jobDefinitionArn").asText();
        String jobId = service.submitJob(json("""
                {"jobName":"terminate-running","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        assertTrue(runStarted.await(2, TimeUnit.SECONDS));
        service.terminateJob(json("""
                {"jobId":"%s","reason":"Operator requested shutdown"}
                """.formatted(jobId)));

        JsonNode failed = waitForJobStatus(service, jobId, "FAILED");
        assertNotNull(failed);
        assertEquals("Operator requested shutdown", failed.path("statusReason").asText());
        JsonNode failedWithAttempt = waitForJobAttemptCount(service, jobId, 1);
        assertNotNull(failedWithAttempt);
        assertEquals(137, failedWithAttempt.path("attempts").get(0).path("container").path("exitCode").asInt());
        assertEquals("Operator requested shutdown", failedWithAttempt.path("statusReason").asText());
        verify(runner).requestStop(jobId);
        verify(runner).stopJob(jobId);
    }

    @Test
    void jobControlValidatesRequiredFieldsReasonLimitAndUnknownJobs() throws Exception {
        InMemoryStorage<String, BatchJob> jobStore = new InMemoryStorage<>();
        BatchService service = immediateService(jobStore);

        AwsException missingJobId = assertThrows(AwsException.class, () -> service.cancelJob(json("""
                {"reason":"No longer needed"}
                """)));
        assertEquals("ClientException", missingJobId.getErrorCode());
        assertEquals("jobId is required", missingJobId.getMessage());

        AwsException missingReason = assertThrows(AwsException.class, () -> service.terminateJob(json("""
                {"jobId":"job-1"}
                """)));
        assertEquals("ClientException", missingReason.getErrorCode());
        assertEquals("reason is required", missingReason.getMessage());

        BatchJob boundaryJob = storedJob("boundary-job", BatchStatus.RUNNABLE);
        jobStore.put(boundaryJob.getJobId(), boundaryJob);
        String boundaryReason = "x".repeat(1024);
        service.cancelJob(json("""
                {"jobId":"boundary-job","reason":"%s"}
                """.formatted(boundaryReason)));
        JsonNode boundaryDetail = service.describeJobs(json("""
                {"jobs":["boundary-job"]}
                """)).path("jobs").get(0);
        assertEquals(boundaryReason, boundaryDetail.path("statusReason").asText());

        BatchJob overLimitJob = storedJob("over-limit-job", BatchStatus.RUNNABLE);
        jobStore.put(overLimitJob.getJobId(), overLimitJob);
        AwsException overLimit = assertThrows(AwsException.class, () -> service.cancelJob(json("""
                {"jobId":"over-limit-job","reason":"%s"}
                """.formatted("x".repeat(1025)))));
        assertEquals("ClientException", overLimit.getErrorCode());

        AwsException unknownJob = assertThrows(AwsException.class, () -> service.terminateJob(json("""
                {"jobId":"unknown-job","reason":"Stop requested"}
                """)));
        assertEquals("ClientException", unknownJob.getErrorCode());
        assertEquals("Job not found: unknown-job", unknownJob.getMessage());
    }

    @Test
    void jobControlIsIdempotentForTerminalJobs() throws Exception {
        InMemoryStorage<String, BatchJob> jobStore = new InMemoryStorage<>();
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        BatchService service = service("docker", jobStore, runner);
        BatchJob succeeded = storedJob("succeeded-job", BatchStatus.SUCCEEDED);
        succeeded.setStatusReason("Job completed successfully");
        BatchJob failed = storedJob("failed-job", BatchStatus.FAILED);
        failed.setStatusReason("Original failure");
        jobStore.put(succeeded.getJobId(), succeeded);
        jobStore.put(failed.getJobId(), failed);

        service.cancelJob(json("""
                {"jobId":"succeeded-job","reason":"Cancel again"}
                """));
        service.terminateJob(json("""
                {"jobId":"failed-job","reason":"Terminate again"}
                """));

        JsonNode jobs = service.describeJobs(json("""
                {"jobs":["succeeded-job","failed-job"]}
                """)).path("jobs");
        assertEquals("SUCCEEDED", jobs.get(0).path("status").asText());
        assertEquals("Job completed successfully", jobs.get(0).path("statusReason").asText());
        assertEquals("FAILED", jobs.get(1).path("status").asText());
        assertEquals("Original failure", jobs.get(1).path("statusReason").asText());
        verify(runner, never()).requestStop(any(String.class));
        verify(runner, never()).stopJob(any(String.class));
    }

    @Test
    void terminateMultiNodeJobStopsNodesAndPreservesRequestedReason() throws Exception {
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        CountDownLatch nodesStarted = new CountDownLatch(2);
        CountDownLatch stopRequested = new CountDownLatch(1);
        when(runner.run(any(BatchJob.class), anyInt(), any(BatchNodeExecution.class)))
                .thenAnswer(invocation -> {
                    BatchNodeExecution node = invocation.getArgument(2);
                    nodesStarted.countDown();
                    assertTrue(stopRequested.await(2, TimeUnit.SECONDS));
                    return new BatchRunResult(137, "Job terminated", "log-" + node.getNodeIndex(),
                            1L, 2L, false);
                });
        doAnswer(invocation -> {
            stopRequested.countDown();
            return null;
        }).when(runner).requestStop(any(String.class));
        BatchService service = dockerService(runner);
        String queueArn = arrayReadyQueue(service);
        String definitionArn = service.registerJobDefinition(json("""
                {
                  "jobDefinitionName":"terminate-mnp-job",
                  "type":"multinode",
                  "nodeProperties":{
                    "numNodes":2,
                    "mainNode":0,
                    "nodeRangeProperties":[{"targetNodes":"0:1","container":{"image":"worker:latest"}}]
                  }
                }
                """), REGION).path("jobDefinitionArn").asText();
        String jobId = service.submitJob(json("""
                {"jobName":"terminate-mnp","jobQueue":"%s","jobDefinition":"%s"}
                """.formatted(queueArn, definitionArn)), REGION).path("jobId").asText();

        assertTrue(nodesStarted.await(2, TimeUnit.SECONDS));
        service.terminateJob(json("""
                {"jobId":"%s","reason":"Stop every node"}
                """.formatted(jobId)));

        JsonNode failedWithAttempt = waitForJobAttemptCount(service, jobId, 1);
        assertNotNull(failedWithAttempt);
        assertEquals("FAILED", failedWithAttempt.path("status").asText());
        assertEquals("Stop every node", failedWithAttempt.path("statusReason").asText());
        JsonNode nodes = service.listJobs(json("""
                {"multiNodeJobId":"%s"}
                """.formatted(jobId))).path("jobSummaryList");
        assertEquals(137, nodes.get(0).path("container").path("exitCode").asInt());
        assertEquals(137, nodes.get(1).path("container").path("exitCode").asInt());
        verify(runner).requestStop(jobId);
        verify(runner).stopJob(jobId);
    }

    @Test
    void terminateArrayParentFailsAndStopsEveryNonterminalChild() throws Exception {
        InMemoryStorage<String, BatchJob> jobStore = new InMemoryStorage<>();
        BatchDockerRunner runner = mock(BatchDockerRunner.class);
        BatchService service = service("docker", jobStore, runner);
        BatchJob parent = storedJob("array-parent", BatchStatus.SUBMITTED);
        parent.setArraySize(2);
        BatchJob first = storedJob("array-parent:0", BatchStatus.RUNNABLE);
        first.setArrayJobId(parent.getJobId());
        first.setArrayIndex(0);
        BatchJob second = storedJob("array-parent:1", BatchStatus.RUNNING);
        second.setArrayJobId(parent.getJobId());
        second.setArrayIndex(1);
        jobStore.put(parent.getJobId(), parent);
        jobStore.put(first.getJobId(), first);
        jobStore.put(second.getJobId(), second);

        service.terminateJob(json("""
                {"jobId":"array-parent","reason":"Stop the array"}
                """));

        JsonNode detail = service.describeJobs(json("""
                {"jobs":["array-parent","array-parent:0","array-parent:1"]}
                """)).path("jobs");
        assertEquals("FAILED", detail.get(0).path("status").asText());
        assertEquals("Stop the array", detail.get(0).path("statusReason").asText());
        assertEquals("FAILED", detail.get(1).path("status").asText());
        assertEquals("FAILED", detail.get(2).path("status").asText());
        verify(runner).requestStop("array-parent:1");
        verify(runner, never()).stopJob("array-parent:0");
        verify(runner).stopJob("array-parent:1");
    }

    private String arrayReadyQueue(BatchService service) throws Exception {
        String suffix = UUID.randomUUID().toString();
        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"ce-%s","type":"MANAGED"}
                """.formatted(suffix)), REGION).path("computeEnvironmentArn").asText();
        return service.createJobQueue(json("""
                {
                  "jobQueueName":"queue-%s",
                  "priority":1,
                  "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]
                }
                """.formatted(suffix, computeArn)), REGION).path("jobQueueArn").asText();
    }

    // ── teardown (one hold of the lock: look-up, disable, delete) ────────────

    @Test
    void teardownDisablesAndDeletesAnEnabledComputeEnvironmentAndIsIdempotent() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String arn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"td-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        assertEquals("ENABLED", service.describeComputeEnvironments(json(
                "{\"computeEnvironments\":[\"td-ce\"]}")).path("computeEnvironments").get(0).path("state").asText());

        assertTrue(service.teardownComputeEnvironment(arn), "an ENABLED environment is disabled then deleted");
        assertTrue(service.describeComputeEnvironments(json("{\"computeEnvironments\":[\"td-ce\"]}"))
                .path("computeEnvironments").isEmpty());
        assertFalse(service.teardownComputeEnvironment(arn), "a repeat counts the environment as gone");
    }

    @Test
    void teardownRefusesAComputeEnvironmentStillAttachedToAQueueUntilTheQueueIsGone() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"td-attached-ce","type":"MANAGED"}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {"jobQueueName":"td-queue","priority":1,
                 "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]}
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();

        AwsException refused = assertThrows(AwsException.class,
                () -> service.teardownComputeEnvironment(computeArn));
        assertTrue(refused.getMessage().contains("still associated with a job queue"), refused.getMessage());
        // The refused teardown left the environment behind, disabled, exactly as AWS would.
        assertEquals("DISABLED", service.describeComputeEnvironments(json(
                "{\"computeEnvironments\":[\"td-attached-ce\"]}")).path("computeEnvironments").get(0).path("state").asText());

        assertTrue(service.teardownJobQueue(queueArn), "an ENABLED queue is disabled then deleted");
        assertFalse(service.teardownJobQueue(queueArn));
        assertTrue(service.teardownComputeEnvironment(computeArn));
    }

    @Test
    void teardownDeregistersAnActiveJobDefinitionOnce() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String arn = service.registerJobDefinition(json("""
                {"jobDefinitionName":"td-def","type":"container",
                 "containerProperties":{"image":"public.ecr.aws/example/job:latest"}}
                """), REGION).path("jobDefinitionArn").asText();

        assertTrue(service.teardownJobDefinition(arn));
        assertEquals("INACTIVE", service.describeJobDefinitions(json("{\"jobDefinitions\":[\"" + arn + "\"]}"))
                .path("jobDefinitions").get(0).path("status").asText());
        assertFalse(service.teardownJobDefinition(arn), "an INACTIVE revision counts as gone");
        assertFalse(service.teardownJobDefinition("arn:aws:batch:us-east-1:000000000000:job-definition/never:1"));
    }

    // ── tags ─────────────────────────────────────────────────────────────────

    /** TagResource obeys the create-time tag rules, on the request and on the merged result. */
    @Test
    void tagResourceEnforcesTheCreateTimeTagRulesOnTheRequestAndTheMergedResult() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String queueArn = createTaggedQueue(service, "rules-queue", Map.of("team", "a"));

        assertEquals("ClientException", assertThrows(AwsException.class,
                () -> service.tagResource(queueArn, Map.of("aws:cloudformation:stack-name", "x"))).getErrorCode());
        assertThrows(AwsException.class, () -> service.tagResource(queueArn, Map.of("k".repeat(129), "v")));
        assertThrows(AwsException.class, () -> service.tagResource(queueArn, Map.of("k", "v".repeat(257))));
        assertThrows(AwsException.class, () -> service.tagResource(queueArn, tagsNumbered(0, 51)));

        service.tagResource(queueArn, tagsNumbered(0, 39));
        assertEquals(40, service.listTagsForResource(queueArn).size());
        assertThrows(AwsException.class, () -> service.tagResource(queueArn, tagsNumbered(39, 59)),
                "a request that fits on its own must not push the resource past 50 tags");
        assertEquals(40, service.listTagsForResource(queueArn).size(), "a rejected request stores nothing");
        service.tagResource(queueArn, tagsNumbered(39, 49));
        assertEquals(50, service.listTagsForResource(queueArn).size());

        assertThrows(AwsException.class, () -> service.untagResource(queueArn,
                tagsNumbered(0, 51).keySet().stream().toList()));
        service.untagResource(queueArn, tagsNumbered(0, 49).keySet().stream().toList());
        assertEquals(Map.of("team", "a"), service.listTagsForResource(queueArn));
    }

    private static Map<String, String> tagsNumbered(int from, int toExclusive) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = from; i < toExclusive; i++) {
            tags.put("k" + i, "v");
        }
        return tags;
    }

    private String createTaggedQueue(BatchService service, String name, Map<String, String> tags) throws Exception {
        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"%s-ce","type":"MANAGED"}
                """.formatted(name)), REGION).path("computeEnvironmentArn").asText();
        return service.createJobQueue(json("""
                {"jobQueueName":"%s","priority":1,"tags":%s,
                 "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]}
                """.formatted(name, new ObjectMapper().writeValueAsString(tags), computeArn)), REGION)
                .path("jobQueueArn").asText();
    }

    @Test
    void tagResourceMergesAndUntagResourceRemovesOnEveryTaggableType() throws Exception {
        BatchService service = immediateService(new InMemoryStorage<String, BatchJob>());
        String computeArn = service.createComputeEnvironment(json("""
                {"computeEnvironmentName":"tag-ce","type":"MANAGED","tags":{"team":"a"}}
                """), REGION).path("computeEnvironmentArn").asText();
        String queueArn = service.createJobQueue(json("""
                {"jobQueueName":"tag-queue","priority":1,
                 "computeEnvironmentOrder":[{"order":1,"computeEnvironment":"%s"}]}
                """.formatted(computeArn)), REGION).path("jobQueueArn").asText();
        String definitionArn = service.registerJobDefinition(json("""
                {"jobDefinitionName":"tag-def","type":"container",
                 "containerProperties":{"image":"public.ecr.aws/example/job:latest"}}
                """), REGION).path("jobDefinitionArn").asText();

        for (String arn : List.of(computeArn, queueArn, definitionArn)) {
            service.tagResource(arn, Map.of("tier", "gold"));
            service.tagResource(arn, Map.of("env", "blue"));
            Map<String, String> tags = service.listTagsForResource(arn);
            assertEquals("gold", tags.get("tier"), arn);
            assertEquals("blue", tags.get("env"), "an earlier tag survives a later TagResource: " + arn);
            service.untagResource(arn, List.of("tier", "never-set"));
            assertEquals(Map.of("env", "blue", "team", "a").entrySet().stream()
                            .filter(e -> arn.equals(computeArn) || !"team".equals(e.getKey()))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                    service.listTagsForResource(arn), arn);
        }
        // The create-time tags of the compute environment were kept through the merges.
        assertEquals("a", service.listTagsForResource(computeArn).get("team"));

        AwsException unknown = assertThrows(AwsException.class, () -> service.listTagsForResource(
                "arn:aws:batch:us-east-1:000000000000:job-queue/never"));
        assertEquals("ClientException", unknown.getErrorCode());
        assertThrows(AwsException.class, () -> service.tagResource(queueArn, Map.of()));
        assertThrows(AwsException.class, () -> service.untagResource(queueArn, List.of()));
    }

    private BatchService dockerService(BatchDockerRunner runner) {
        return service("docker", new InMemoryStorage<String, BatchJob>(), runner);
    }

    private BatchService service(String runnerMode, StorageBackend<String, BatchJob> jobStore,
                                 BatchDockerRunner runner) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.BatchServiceConfig batch = mock(EmulatorConfig.BatchServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.batch()).thenReturn(batch);
        when(batch.runnerMode()).thenReturn(runnerMode);

        return new BatchService(
                new InMemoryStorage<String, BatchJobDefinition>(),
                new InMemoryStorage<String, BatchJobQueue>(),
                new InMemoryStorage<String, BatchComputeEnvironment>(),
                jobStore,
                new RegionResolver(REGION, ACCOUNT),
                config,
                objectMapper,
                runner);
    }

    private BatchService immediateService(StorageBackend<String, BatchJob> jobStore) {
        return service("immediate", jobStore, mock(BatchDockerRunner.class));
    }

    private BatchJob storedJob(String jobId, BatchStatus status) {
        BatchJob job = new BatchJob();
        job.setJobId(jobId);
        job.setJobArn("arn:aws:batch:us-east-1:" + ACCOUNT + ":job/" + jobId);
        job.setJobName(jobId);
        job.setJobQueue("queue");
        job.setJobDefinition("definition");
        job.setStatus(status.name());
        job.setCreatedAt(1L);
        job.setRegion(REGION);
        job.setAccountId(ACCOUNT);
        return job;
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

    private JsonNode waitForJobAttemptCount(BatchService service, String jobId, int attemptCount) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.putArray("jobs").add(jobId);
        for (int i = 0; i < 100; i++) {
            JsonNode job = service.describeJobs(request).path("jobs").get(0);
            if (job != null && job.path("attempts").size() == attemptCount) {
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
