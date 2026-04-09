package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.DeleteFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.FilterRule;
import software.amazon.awssdk.services.s3.model.FilterRuleName;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketNotificationConfigurationResponse;
import software.amazon.awssdk.services.s3.model.LambdaFunctionConfiguration;
import software.amazon.awssdk.services.s3.model.NotificationConfiguration;
import software.amazon.awssdk.services.s3.model.NotificationConfigurationFilter;
import software.amazon.awssdk.services.s3.model.PutBucketNotificationConfigurationRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;
import software.amazon.awssdk.services.s3.model.S3KeyFilter;
import software.amazon.awssdk.services.s3.model.TopicConfiguration;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.CreateTopicRequest;
import software.amazon.awssdk.services.sns.model.DeleteTopicRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("S3 Notifications")
class S3NotificationsTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";

    private static S3Client s3;
    private static SqsClient sqs;
    private static SnsClient sns;
    private static LambdaClient lambda;
    private static CloudWatchLogsClient logs;

    private static String bucketName;
    private static String queueUrl;
    private static String queueArn;
    private static String topicArn;
    private static String functionName;
    private static String functionArn;

    @BeforeAll
    static void setup() {
        s3 = TestFixtures.s3Client();
        sqs = TestFixtures.sqsClient();
        sns = TestFixtures.snsClient();
        lambda = TestFixtures.lambdaClient();
        logs = TestFixtures.cloudWatchLogsClient();

        bucketName = TestFixtures.uniqueName("java-s3-notif-bucket");
        String queueName = TestFixtures.uniqueName("java-s3-notif-queue");
        String topicName = TestFixtures.uniqueName("java-s3-notif-topic");
        functionName = TestFixtures.uniqueName("java-s3-notif-fn");

        queueUrl = sqs.createQueue(CreateQueueRequest.builder()
                        .queueName(queueName)
                        .build())
                .queueUrl();

        queueArn = sqs.getQueueAttributes(GetQueueAttributesRequest.builder()
                        .queueUrl(queueUrl)
                        .attributeNames(QueueAttributeName.QUEUE_ARN)
                        .build())
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);

        topicArn = sns.createTopic(CreateTopicRequest.builder()
                        .name(topicName)
                        .build())
                .topicArn();

        s3.createBucket(CreateBucketRequest.builder()
                .bucket(bucketName)
                .build());

        functionArn = lambda.createFunction(CreateFunctionRequest.builder()
                        .functionName(functionName)
                        .runtime(Runtime.NODEJS20_X)
                        .role(ROLE)
                        .handler("index.handler")
                        .code(FunctionCode.builder()
                                .zipFile(SdkBytes.fromByteArray(LambdaUtils.s3NotificationLoggerZip()))
                                .build())
                        .build())
                .functionArn();
    }

    @AfterAll
    static void cleanup() {
        if (s3 != null) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key("incoming/report.csv").build());
            } catch (Exception ignored) {}
            try {
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucketName).build());
            } catch (Exception ignored) {}
            s3.close();
        }
        if (sqs != null) {
            try {
                sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
            } catch (Exception ignored) {}
            sqs.close();
        }
        if (sns != null) {
            try {
                sns.deleteTopic(DeleteTopicRequest.builder().topicArn(topicArn).build());
            } catch (Exception ignored) {}
            sns.close();
        }
        if (lambda != null) {
            try {
                lambda.deleteFunction(DeleteFunctionRequest.builder().functionName(functionName).build());
            } catch (Exception ignored) {}
            lambda.close();
        }
        if (logs != null) {
            logs.close();
        }
    }

    @Test
    void bucketNotificationConfigurationRoundTripIncludesLambdaAndFilters() {
        s3.putBucketNotificationConfiguration(PutBucketNotificationConfigurationRequest.builder()
                .bucket(bucketName)
                .notificationConfiguration(NotificationConfiguration.builder()
                        .queueConfigurations(QueueConfiguration.builder()
                                .id("sqs-filtered")
                                .queueArn(queueArn)
                                .events(Event.S3_OBJECT_CREATED)
                                .filter(filter("incoming/", ".csv"))
                                .build())
                        .topicConfigurations(TopicConfiguration.builder()
                                .id("sns-filtered")
                                .topicArn(topicArn)
                                .events(Event.S3_OBJECT_REMOVED)
                                .filter(filter("", ".txt"))
                                .build())
                        .lambdaFunctionConfigurations(LambdaFunctionConfiguration.builder()
                                .id("lambda-filtered")
                                .lambdaFunctionArn(functionArn)
                                .events(Event.S3_OBJECT_CREATED_PUT)
                                .filter(filter("incoming/", ".csv"))
                                .build())
                        .build())
                .build());

        GetBucketNotificationConfigurationResponse response = s3.getBucketNotificationConfiguration(
                GetBucketNotificationConfigurationRequest.builder()
                        .bucket(bucketName)
                        .build());

        assertThat(response.queueConfigurations())
                .anySatisfy(config -> {
                    assertThat(config.id()).isEqualTo("sqs-filtered");
                    assertThat(config.queueArn()).isEqualTo(queueArn);
                    assertThat(config.events()).contains(Event.S3_OBJECT_CREATED);
                    assertThat(config.filter().key().filterRules())
                            .anyMatch(rule -> rule.name() == FilterRuleName.PREFIX && "incoming/".equals(rule.value()))
                            .anyMatch(rule -> rule.name() == FilterRuleName.SUFFIX && ".csv".equals(rule.value()));
                });

        assertThat(response.topicConfigurations())
                .anySatisfy(config -> {
                    assertThat(config.id()).isEqualTo("sns-filtered");
                    assertThat(config.topicArn()).isEqualTo(topicArn);
                    assertThat(config.events()).contains(Event.S3_OBJECT_REMOVED);
                    assertThat(config.filter().key().filterRules())
                            .anyMatch(rule -> rule.name() == FilterRuleName.PREFIX && "".equals(rule.value()))
                            .anyMatch(rule -> rule.name() == FilterRuleName.SUFFIX && ".txt".equals(rule.value()));
                });

        assertThat(response.lambdaFunctionConfigurations())
                .anySatisfy(config -> {
                    assertThat(config.id()).isEqualTo("lambda-filtered");
                    assertThat(config.lambdaFunctionArn()).isEqualTo(functionArn);
                    assertThat(config.events()).contains(Event.S3_OBJECT_CREATED_PUT);
                    assertThat(config.filter().key().filterRules())
                            .anyMatch(rule -> rule.name() == FilterRuleName.PREFIX && "incoming/".equals(rule.value()))
                            .anyMatch(rule -> rule.name() == FilterRuleName.SUFFIX && ".csv".equals(rule.value()));
                });
    }

    @Test
    void lambdaNotificationInvokesFunctionForMatchingObject() throws InterruptedException {
        String key = "incoming/report.csv";
        String expectedMessage = "[s3-notification] received " + bucketName + "/" + key;

        s3.putBucketNotificationConfiguration(PutBucketNotificationConfigurationRequest.builder()
                .bucket(bucketName)
                .notificationConfiguration(NotificationConfiguration.builder()
                        .lambdaFunctionConfigurations(LambdaFunctionConfiguration.builder()
                                .id("lambda-filtered")
                                .lambdaFunctionArn(functionArn)
                                .events(Event.S3_OBJECT_CREATED_PUT)
                                .filter(filter("incoming/", ".csv"))
                                .build())
                        .build())
                .build());

        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(key)
                        .contentType("text/plain")
                        .build(),
                RequestBody.fromString("compatibility test payload"));

        assertThat(waitForLogMessage("/aws/lambda/" + functionName, expectedMessage))
                .as("expected Lambda logs to contain the S3 notification record")
                .isTrue();
    }

    private static NotificationConfigurationFilter filter(String prefix, String suffix) {
        return NotificationConfigurationFilter.builder()
                .key(S3KeyFilter.builder()
                        .filterRules(
                                FilterRule.builder().name(FilterRuleName.PREFIX).value(prefix).build(),
                                FilterRule.builder().name(FilterRuleName.SUFFIX).value(suffix).build()
                        )
                        .build())
                .build();
    }

    private static boolean waitForLogMessage(String logGroupName, String expectedMessage) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            boolean found;
            try {
                found = logs.filterLogEvents(FilterLogEventsRequest.builder()
                                .logGroupName(logGroupName)
                                .build())
                        .events()
                        .stream()
                        .anyMatch(event -> event.message() != null && event.message().contains(expectedMessage));
            } catch (ResourceNotFoundException ignored) {
                found = false;
            }
            if (found) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }
}
