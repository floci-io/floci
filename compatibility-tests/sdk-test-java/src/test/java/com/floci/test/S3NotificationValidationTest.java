package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Event;
import software.amazon.awssdk.services.s3.model.NotificationConfiguration;
import software.amazon.awssdk.services.s3.model.QueueConfiguration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("S3 notification destination validation")
class S3NotificationValidationTest {

    @Test
    void destinationValidationAndSkipHeaderWorkThroughAwsSdk() throws Exception {
        String bucket = TestFixtures.uniqueName("java-s3-validation");
        String queueName = TestFixtures.uniqueName("java-s3-validation");
        try (S3Client s3 = TestFixtures.s3Client(); SqsClient sqs = TestFixtures.sqsClient()) {
            String queueUrl = sqs.createQueue(request -> request.queueName(queueName)).queueUrl();
            try {
                s3.createBucket(request -> request.bucket(bucket));
                try {
                    String queueArn = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl)
                            .attributeNames(QueueAttributeName.QUEUE_ARN))
                            .attributes().get(QueueAttributeName.QUEUE_ARN);
                    NotificationConfiguration valid = configuration(queueArn);
                    s3.putBucketNotificationConfiguration(request -> request.bucket(bucket)
                            .notificationConfiguration(valid));

                    List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                            .maxNumberOfMessages(1).waitTimeSeconds(5)).messages();
                    assertThat(messages).hasSize(1);
                    Message message = messages.get(0);
                    JsonNode event = new ObjectMapper().readTree(message.body());
                    assertThat(event.path("Service").asText()).isEqualTo("Amazon S3");
                    assertThat(event.path("Event").asText()).isEqualTo("s3:TestEvent");
                    assertThat(event.path("Bucket").asText()).isEqualTo(bucket);
                    assertThat(Instant.parse(event.path("Time").asText())).isNotNull();
                    assertThat(event.path("RequestId").asText()).isNotBlank();
                    assertThat(event.path("HostId").asText()).isNotBlank();
                    assertThat(event.has("Records")).isFalse();
                    sqs.deleteMessage(request -> request.queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle()));

                    String missingArn = queueArn + "-missing";
                    S3Exception missing = assertThrows(S3Exception.class,
                            () -> s3.putBucketNotificationConfiguration(request -> request.bucket(bucket)
                                    .notificationConfiguration(configuration(missingArn))));
                    assertThat(missing.statusCode()).isEqualTo(400);
                    assertThat(missing.awsErrorDetails().errorCode()).isEqualTo("InvalidArgument");
                    assertThat(s3.getBucketNotificationConfiguration(request -> request.bucket(bucket))
                            .queueConfigurations()).extracting(QueueConfiguration::queueArn)
                            .containsExactly(queueArn);

                    s3.putBucketNotificationConfiguration(request -> request.bucket(bucket)
                            .skipDestinationValidation(true)
                            .notificationConfiguration(NotificationConfiguration.builder()
                                    .queueConfigurations(queueConfiguration(queueArn),
                                            queueConfiguration(missingArn)).build()));
                    assertThat(s3.getBucketNotificationConfiguration(request -> request.bucket(bucket))
                            .queueConfigurations()).extracting(QueueConfiguration::queueArn)
                            .containsExactly(queueArn, missingArn);
                    assertThat(sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                            .waitTimeSeconds(0)).messages()).isEmpty();

                    S3Exception malformed = assertThrows(S3Exception.class,
                            () -> s3.putBucketNotificationConfiguration(request -> request.bucket(bucket)
                                    .skipDestinationValidation(true)
                                    .notificationConfiguration(configuration("not-an-arn"))));
                    assertThat(malformed.statusCode()).isEqualTo(400);
                    assertThat(malformed.awsErrorDetails().errorCode()).isEqualTo("InvalidArgument");
                } finally {
                    s3.deleteBucket(request -> request.bucket(bucket));
                }
            } finally {
                sqs.deleteQueue(request -> request.queueUrl(queueUrl));
            }
        }
    }

    private static NotificationConfiguration configuration(String queueArn) {
        return NotificationConfiguration.builder().queueConfigurations(queueConfiguration(queueArn)).build();
    }

    private static QueueConfiguration queueConfiguration(String queueArn) {
        return QueueConfiguration.builder().queueArn(queueArn).events(Event.S3_OBJECT_CREATED).build();
    }
}
