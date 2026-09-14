package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.CodeBuildService;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodePipelineServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodePipelineService service = new CodePipelineService(
            new InMemoryStorageFactory(),
            mapper,
            mock(CodeBuildService.class),
            mock(CodeDeployService.class),
            mock(LambdaService.class),
            mock(S3Service.class));

    @Test
    void sourcePollingAndPipelineUpdateSerializeBaselineChanges() throws Exception {
        S3Service s3Service = mock(S3Service.class);
        S3Object source = new S3Object();
        source.setETag("\"baseline\"");
        AtomicInteger headCalls = new AtomicInteger();
        CountDownLatch pollEntered = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        when(s3Service.headObject("source-bucket", "source.zip")).thenAnswer(invocation -> {
            int call = headCalls.incrementAndGet();
            if (call == 2) {
                pollEntered.countDown();
                if (!releasePoll.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the source poll");
                }
            }
            return source;
        });

        CodePipelineService pollingService = new CodePipelineService(
                new InMemoryStorageFactory(),
                mapper,
                mock(CodeBuildService.class),
                mock(CodeDeployService.class),
                mock(LambdaService.class),
                s3Service);
        String pipelineRequest = """
                {
                    "pipeline": {
                        "name": "polling-pipeline",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
                        "artifactStore": {"type": "S3", "location": "codepipeline-artifacts"},
                        "stages": [{
                            "name": "Source",
                            "actions": [{
                                "name": "SourceObject",
                                "actionTypeId": {
                                    "category": "Source",
                                    "owner": "AWS",
                                    "provider": "S3",
                                    "version": "1"
                                },
                                "configuration": {
                                    "S3Bucket": "source-bucket",
                                    "S3ObjectKey": "source.zip"
                                },
                                "outputArtifacts": [{"name": "SourceOutput"}]
                            }]
                        }, {
                            "name": "Approve",
                            "actions": [{
                                "name": "ManualApproval",
                                "actionTypeId": {
                                    "category": "Approval",
                                    "owner": "AWS",
                                    "provider": "Manual",
                                    "version": "1"
                                }
                            }]
                        }]
                    }
                }
                """;
        JsonNode request = mapper.readTree(pipelineRequest);

        try {
            pollingService.handle("CreatePipeline", request, REGION, ACCOUNT);
            pollingService.resumePersistedExecutions();
            assertTrue(pollEntered.await(2, TimeUnit.SECONDS));

            JsonNode updateRequest = mapper.readTree(pipelineRequest);
            CompletableFuture<JsonNode> update = CompletableFuture.supplyAsync(
                    () -> pollingService.handle("UpdatePipeline", updateRequest, REGION, ACCOUNT));

            Thread.sleep(150);
            assertFalse(update.isDone(), "UpdatePipeline must wait for the in-flight source poll");

            releasePoll.countDown();
            update.get(2, TimeUnit.SECONDS);
        } finally {
            releasePoll.countDown();
            pollingService.shutdown();
        }
    }

    @Test
    void aStartAfterShutdownIsRefusedAndTheExecutionIsPersistedAsFailed() throws Exception {
        handle("CreatePipeline", """
                {
                    "pipeline": {
                        "name": "pipeline",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
                        "artifactStore": {"type": "S3", "location": "codepipeline-artifacts"},
                        "stages": [{
                            "name": "Approve",
                            "actions": [{
                                "name": "ManualApproval",
                                "actionTypeId": {
                                    "category": "Approval",
                                    "owner": "AWS",
                                    "provider": "Manual",
                                    "version": "1"
                                }
                            }]
                        }, {
                            "name": "Complete",
                            "actions": [{
                                "name": "ManualApprovalComplete",
                                "actionTypeId": {
                                    "category": "Approval",
                                    "owner": "AWS",
                                    "provider": "Manual",
                                    "version": "1"
                                }
                            }]
                        }]
                    }
                }
                """);
        service.shutdown();

        AwsException refused = assertThrows(AwsException.class,
                () -> handle("StartPipelineExecution", "{\"name\": \"pipeline\"}"));

        assertEquals("ConflictException", refused.getErrorCode());
        assertEquals(400, refused.getHttpStatus());
        JsonNode summaries = handle("ListPipelineExecutions", "{\"pipelineName\": \"pipeline\"}")
                .path("pipelineExecutionSummaries");
        assertEquals(1, summaries.size());
        assertEquals("Failed", summaries.get(0).path("status").asText());
    }

    private JsonNode handle(String action, String body) throws Exception {
        return service.handle(action, mapper.readTree(body), REGION, ACCOUNT);
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT);
        }
    }
}
