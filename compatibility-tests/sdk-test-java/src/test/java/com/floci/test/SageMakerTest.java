package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.sagemaker.SageMakerClient;
import software.amazon.awssdk.services.sagemaker.model.CreateEndpointConfigResponse;
import software.amazon.awssdk.services.sagemaker.model.CreateModelResponse;
import software.amazon.awssdk.services.sagemaker.model.CreateTrainingJobResponse;
import software.amazon.awssdk.services.sagemaker.model.DescribeModelResponse;
import software.amazon.awssdk.services.sagemaker.model.DescribeTrainingJobResponse;
import software.amazon.awssdk.services.sagemaker.model.ListEndpointConfigsResponse;
import software.amazon.awssdk.services.sagemaker.model.TrainingJobStatus;

import static org.assertj.core.api.Assertions.assertThat;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SageMakerTest {

    private static final String TRAINING_IMAGE = "public.ecr.aws/docker/library/busybox:stable";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/sdk-test-role";

    static SageMakerClient sagemaker;
    static S3Client s3;
    static String modelName;
    static String endpointConfigName;
    static String bucket;
    static String trainingJobName;

    @BeforeAll
    static void setup() {
        sagemaker = TestFixtures.sageMakerClient();
        s3 = TestFixtures.s3Client();
        modelName = "sdk-test-model";
        endpointConfigName = "sdk-test-endpoint-config";
        bucket = "sdk-test-sagemaker";
        trainingJobName = "sdk-test-training-job";
    }

    @AfterAll
    static void teardown() {
        sagemaker.close();
        s3.close();
    }

    @Test
    @Order(1)
    void createModel() {
        CreateModelResponse resp = sagemaker.createModel(r -> r
                .modelName(modelName)
                .primaryContainer(c -> c.image(TRAINING_IMAGE))
                .executionRoleArn(ROLE_ARN));

        assertThat(resp.modelArn()).contains(":model/" + modelName);
    }

    @Test
    @Order(2)
    void describeModel() {
        DescribeModelResponse resp = sagemaker.describeModel(r -> r.modelName(modelName));

        assertThat(resp.modelName()).isEqualTo(modelName);
    }

    @Test
    @Order(3)
    void createEndpointConfig() {
        CreateEndpointConfigResponse resp = sagemaker.createEndpointConfig(r -> r
                .endpointConfigName(endpointConfigName)
                .productionVariants(v -> v
                        .variantName("AllTraffic")
                        .modelName(modelName)
                        .initialInstanceCount(1)
                        .instanceType("ml.t2.medium")));

        assertThat(resp.endpointConfigArn()).contains(":endpoint-config/" + endpointConfigName);
    }

    @Test
    @Order(4)
    void listEndpointConfigs() {
        ListEndpointConfigsResponse resp = sagemaker.listEndpointConfigs(r -> r.build());

        assertThat(resp.endpointConfigs())
                .anyMatch(cfg -> cfg.endpointConfigName().equals(endpointConfigName));
    }

    @Test
    @Order(5)
    void createTrainingJob_runsToCompletion() throws InterruptedException {
        s3.createBucket(r -> r.bucket(bucket));
        s3.putObject(r -> r.bucket(bucket).key("input/data.txt"), RequestBody.fromString("hello"));

        CreateTrainingJobResponse created = sagemaker.createTrainingJob(r -> r
                .trainingJobName(trainingJobName)
                .roleArn(ROLE_ARN)
                .algorithmSpecification(a -> a
                        .trainingImage(TRAINING_IMAGE)
                        .trainingInputMode("File")
                        .containerEntrypoint("/bin/sh", "-c")
                        .containerArguments("mkdir -p /opt/ml/model && echo ok > /opt/ml/model/model.txt"))
                .inputDataConfig(c -> c
                        .channelName("train")
                        .dataSource(d -> d.s3DataSource(s -> s
                                .s3DataType("S3Prefix")
                                .s3Uri("s3://" + bucket + "/input"))))
                .outputDataConfig(o -> o.s3OutputPath("s3://" + bucket + "/output"))
                .resourceConfig(c -> c
                        .instanceType("ml.m5.large")
                        .instanceCount(1)
                        .volumeSizeInGB(1))
                .stoppingCondition(c -> c.maxRuntimeInSeconds(60)));

        assertThat(created.trainingJobArn()).contains(":training-job/" + trainingJobName);

        DescribeTrainingJobResponse job = null;
        for (int i = 0; i < 60; i++) {
            job = sagemaker.describeTrainingJob(r -> r.trainingJobName(trainingJobName));
            if (job.trainingJobStatus() == TrainingJobStatus.COMPLETED
                    || job.trainingJobStatus() == TrainingJobStatus.FAILED) {
                break;
            }
            Thread.sleep(2000);
        }

        assertThat(job).isNotNull();
        assertThat(job.trainingJobStatus()).isEqualTo(TrainingJobStatus.COMPLETED);

        String artifact = job.modelArtifacts().s3ModelArtifacts();
        String key = artifact.substring(("s3://" + bucket + "/").length());
        byte[] data = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        assertThat(data).isNotEmpty();
    }

    @Test
    @Order(6)
    void deleteEndpointConfig() {
        sagemaker.deleteEndpointConfig(r -> r.endpointConfigName(endpointConfigName));

        ListEndpointConfigsResponse after = sagemaker.listEndpointConfigs(r -> r.build());
        assertThat(after.endpointConfigs())
                .noneMatch(cfg -> cfg.endpointConfigName().equals(endpointConfigName));
    }

    @Test
    @Order(7)
    void deleteModel() {
        sagemaker.deleteModel(r -> r.modelName(modelName));
    }
}
