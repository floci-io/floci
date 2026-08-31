package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.*;
import software.amazon.awssdk.services.bedrock.model.Tag;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Bedrock Batch Inference Integration Test")
class BedrockBatchInferenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static BedrockClient bedrock;
    private static S3Client s3;
    private static String bucketName;

    @BeforeAll
    static void setup() {
        bedrock = TestFixtures.bedrockClient();
        s3 = TestFixtures.s3Client();
        bucketName = TestFixtures.uniqueName("bedrock-batch");
        s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
    }

    @AfterAll
    static void cleanup() {
        if (s3 != null && bucketName != null) {
            try {
                ListObjectsV2Response list = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucketName).build());
                for (S3Object obj : list.contents()) {
                    s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(obj.key()).build());
                }
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
            } catch (Exception ignored) {
            }
            s3.close();
        }
        if (bedrock != null) {
            bedrock.close();
        }
    }

    @Test
    void createAndGetModelInvocationJob() {
        String jobName = TestFixtures.uniqueName("batch-job");
        String inputUri = "s3://" + bucketName + "/in.jsonl";
        String outputUri = "s3://" + bucketName + "/out";

        CreateModelInvocationJobResponse createRes = bedrock.createModelInvocationJob(CreateModelInvocationJobRequest.builder()
                .jobName(jobName)
                .modelId("amazon.titan-text-express-v1")
                .roleArn("arn:aws:iam::000000000000:role/BedrockBatchRole")
                .inputDataConfig(ModelInvocationJobInputDataConfig.builder()
                        .s3InputDataConfig(ModelInvocationJobS3InputDataConfig.builder()
                                .s3Uri(inputUri)
                                .build())
                        .build())
                .outputDataConfig(ModelInvocationJobOutputDataConfig.builder()
                        .s3OutputDataConfig(ModelInvocationJobS3OutputDataConfig.builder()
                                .s3Uri(outputUri)
                                .build())
                        .build())
                .tags(Tag.builder().key("Environment").value("testing").build())
                .build());

        assertThat(createRes.jobArn()).isNotNull();
        assertThat(createRes.jobArn()).contains(":model-invocation-job/");

        GetModelInvocationJobResponse getRes = bedrock.getModelInvocationJob(GetModelInvocationJobRequest.builder()
                .jobIdentifier(createRes.jobArn())
                .build());

        assertThat(getRes.jobArn()).isEqualTo(createRes.jobArn());
        assertThat(getRes.jobName()).isEqualTo(jobName);
        assertThat(getRes.modelId()).isEqualTo("amazon.titan-text-express-v1");
        assertThat(getRes.status()).isNotNull();
        assertThat(getRes.inputDataConfig().s3InputDataConfig().s3Uri()).isEqualTo(inputUri);
        assertThat(getRes.outputDataConfig().s3OutputDataConfig().s3Uri()).isEqualTo(outputUri);
    }

    @Test
    void getNonexistentJobThrowsException() {
        assertThatThrownBy(() -> bedrock.getModelInvocationJob(GetModelInvocationJobRequest.builder()
                .jobIdentifier("arn:aws:bedrock:us-east-1:000000000000:model-invocation-job/nonexistent999")
                .build()))
                .isInstanceOf(BedrockException.class);
    }

    @Test
    void listModelInvocationJobsWithFiltering() {
        String jobName1 = TestFixtures.uniqueName("list-job-1");
        String jobName2 = TestFixtures.uniqueName("list-job-2");

        bedrock.createModelInvocationJob(CreateModelInvocationJobRequest.builder()
                .jobName(jobName1)
                .modelId("amazon.titan-text-express-v1")
                .roleArn("arn:aws:iam::000000000000:role/BedrockBatchRole")
                .inputDataConfig(ModelInvocationJobInputDataConfig.builder()
                        .s3InputDataConfig(ModelInvocationJobS3InputDataConfig.builder()
                                .s3Uri("s3://" + bucketName + "/1.jsonl")
                                .build())
                        .build())
                .outputDataConfig(ModelInvocationJobOutputDataConfig.builder()
                        .s3OutputDataConfig(ModelInvocationJobS3OutputDataConfig.builder()
                                .s3Uri("s3://" + bucketName + "/out1")
                                .build())
                        .build())
                .build());

        bedrock.createModelInvocationJob(CreateModelInvocationJobRequest.builder()
                .jobName(jobName2)
                .modelId("anthropic.claude-3-sonnet-20240229-v1:0")
                .roleArn("arn:aws:iam::000000000000:role/BedrockBatchRole")
                .inputDataConfig(ModelInvocationJobInputDataConfig.builder()
                        .s3InputDataConfig(ModelInvocationJobS3InputDataConfig.builder()
                                .s3Uri("s3://" + bucketName + "/2.jsonl")
                                .build())
                        .build())
                .outputDataConfig(ModelInvocationJobOutputDataConfig.builder()
                        .s3OutputDataConfig(ModelInvocationJobS3OutputDataConfig.builder()
                                .s3Uri("s3://" + bucketName + "/out2")
                                .build())
                        .build())
                .build());

        ListModelInvocationJobsResponse listRes = bedrock.listModelInvocationJobs(ListModelInvocationJobsRequest.builder().build());
        assertThat(listRes.invocationJobSummaries()).isNotNull();
        List<String> names = listRes.invocationJobSummaries().stream().map(ModelInvocationJobSummary::jobName).toList();
        assertThat(names).contains(jobName1, jobName2);

        ListModelInvocationJobsResponse filteredRes = bedrock.listModelInvocationJobs(ListModelInvocationJobsRequest.builder()
                .nameContains(jobName1)
                .build());
        List<String> filteredNames = filteredRes.invocationJobSummaries().stream().map(ModelInvocationJobSummary::jobName).toList();
        assertThat(filteredNames).contains(jobName1);
        assertThat(filteredNames).doesNotContain(jobName2);
    }

    @Test
    void stopModelInvocationJob() {
        String jobName = TestFixtures.uniqueName("stop-job");
        CreateModelInvocationJobResponse createRes = bedrock.createModelInvocationJob(CreateModelInvocationJobRequest.builder()
                .jobName(jobName)
                .modelId("amazon.titan-text-express-v1")
                .roleArn("arn:aws:iam::000000000000:role/BedrockBatchRole")
                .inputDataConfig(ModelInvocationJobInputDataConfig.builder()
                        .s3InputDataConfig(ModelInvocationJobS3InputDataConfig.builder()
                                .s3Uri("s3://" + bucketName + "/stop.jsonl")
                                .build())
                        .build())
                .outputDataConfig(ModelInvocationJobOutputDataConfig.builder()
                        .s3OutputDataConfig(ModelInvocationJobS3OutputDataConfig.builder()
                                .s3Uri("s3://" + bucketName + "/stop-out")
                                .build())
                        .build())
                .build());

        // Only stop if not already terminal
        Set<ModelInvocationJobStatus> terminalStatuses = Set.of(
                ModelInvocationJobStatus.COMPLETED,
                ModelInvocationJobStatus.FAILED,
                ModelInvocationJobStatus.STOPPED,
                ModelInvocationJobStatus.EXPIRED,
                ModelInvocationJobStatus.PARTIALLY_COMPLETED);
        GetModelInvocationJobResponse current = bedrock.getModelInvocationJob(GetModelInvocationJobRequest.builder()
                .jobIdentifier(createRes.jobArn())
                .build());
        if (!terminalStatuses.contains(current.status())) {
            bedrock.stopModelInvocationJob(StopModelInvocationJobRequest.builder()
                    .jobIdentifier(createRes.jobArn())
                    .build());
        }

        GetModelInvocationJobResponse job = bedrock.getModelInvocationJob(GetModelInvocationJobRequest.builder()
                .jobIdentifier(createRes.jobArn())
                .build());
        assertThat(job.statusAsString()).isIn("Stopping", "Stopped", "Failed");
        assertThat(job.endTime()).isNotNull();
    }

    @Test
    void batchInferenceExecutionWithS3() throws IOException {
        String inputKey = "prompts.jsonl";
        String outputPrefix = TestFixtures.uniqueName("batch-out");
        String inputUri = "s3://" + bucketName + "/" + inputKey;
        String outputUri = "s3://" + bucketName + "/" + outputPrefix;

        String jsonlContent = "{\"recordId\":\"rec-1\",\"modelInput\":{\"inputText\":\"Hello Bedrock\"}}\n" +
                "{\"recordId\":\"rec-2\",\"modelInput\":{\"inputText\":\"How are you?\"}}";

        s3.putObject(PutObjectRequest.builder()
                .bucket(bucketName)
                .key(inputKey)
                .build(), RequestBody.fromString(jsonlContent, StandardCharsets.UTF_8));

        String jobName = TestFixtures.uniqueName("s3-exec-job");
        CreateModelInvocationJobResponse createRes = bedrock.createModelInvocationJob(CreateModelInvocationJobRequest.builder()
                .jobName(jobName)
                .modelId("amazon.titan-text-express-v1")
                .roleArn("arn:aws:iam::000000000000:role/BedrockBatchRole")
                .inputDataConfig(ModelInvocationJobInputDataConfig.builder()
                        .s3InputDataConfig(ModelInvocationJobS3InputDataConfig.builder()
                                .s3Uri(inputUri)
                                .build())
                        .build())
                .outputDataConfig(ModelInvocationJobOutputDataConfig.builder()
                        .s3OutputDataConfig(ModelInvocationJobS3OutputDataConfig.builder()
                                .s3Uri(outputUri)
                                .build())
                        .build())
                .build());

        String jobArn = createRes.jobArn();
        String jobId = jobArn.substring(jobArn.lastIndexOf('/') + 1);

        // Poll until job reaches a terminal status (max 30s)
        Set<ModelInvocationJobStatus> terminalStatuses = Set.of(
                ModelInvocationJobStatus.COMPLETED,
                ModelInvocationJobStatus.FAILED,
                ModelInvocationJobStatus.STOPPED,
                ModelInvocationJobStatus.EXPIRED,
                ModelInvocationJobStatus.PARTIALLY_COMPLETED);
        long deadline = System.currentTimeMillis() + 30_000;
        GetModelInvocationJobResponse job;
        do {
            job = bedrock.getModelInvocationJob(GetModelInvocationJobRequest.builder()
                    .jobIdentifier(jobArn)
                    .build());
            if (terminalStatuses.contains(job.status())) break;
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        } while (System.currentTimeMillis() < deadline);
        assertThat(job.status()).isEqualTo(ModelInvocationJobStatus.COMPLETED);

        // Verify output JSONL file in S3
        String outputKey = outputPrefix + "/" + jobId + "/" + inputKey + ".out";
        String outputStr = s3.getObject(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(outputKey)
                .build(), ResponseTransformer.toBytes()).asUtf8String();

        String[] outputLines = outputStr.trim().split("\n");
        assertThat(outputLines).hasSize(2);

        JsonNode outRecord1 = MAPPER.readTree(outputLines[0]);
        assertThat(outRecord1.get("recordId").asText()).isEqualTo("rec-1");
        assertThat(outRecord1.has("modelOutput")).isTrue();

        // Verify manifest file in S3
        String manifestKey = outputPrefix + "/" + jobId + "/manifest.json.out";
        String manifestStr = s3.getObject(GetObjectRequest.builder()
                .bucket(bucketName)
                .key(manifestKey)
                .build(), ResponseTransformer.toBytes()).asUtf8String();

        JsonNode manifest = MAPPER.readTree(manifestStr);
        assertThat(manifest.get("totalRecordCount").asInt()).isEqualTo(2);
        assertThat(manifest.get("processedRecordCount").asInt()).isEqualTo(2);
        assertThat(manifest.get("successRecordCount").asInt()).isEqualTo(2);
        assertThat(manifest.get("errorRecordCount").asInt()).isEqualTo(0);
    }
}
