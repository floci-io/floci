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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
