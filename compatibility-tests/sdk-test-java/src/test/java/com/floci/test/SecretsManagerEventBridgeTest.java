package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.Target;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Secrets Manager API events through CloudTrail and EventBridge")
class SecretsManagerEventBridgeTest {
    @Test
    @DisplayName("Actual SDK secret writes reach a secret-specific SQS rule without PutEvents fixtures")
    void secretWritesReachSqsThroughALoggingTrail() throws Exception {
        String name = TestFixtures.uniqueName("secret-events");
        try (SecretsManagerClient secrets = TestFixtures.secretsManagerClient();
             CloudTrailClient trails = TestFixtures.cloudTrailClient();
             EventBridgeClient events = TestFixtures.eventBridgeClient();
             SqsClient sqs = TestFixtures.sqsClient();
             S3Client s3 = TestFixtures.s3Client()) {
            s3.createBucket(r -> r.bucket(name));
            String queue = sqs.createQueue(r -> r.queueName(name)).queueUrl();
            String queueArn = sqs.getQueueAttributes(r -> r.queueUrl(queue)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            String secret = secrets.createSecret(r -> r.name(name).secretString("initial-example")).arn();
            trails.createTrail(r -> r.name(name).s3BucketName(name));
            events.putRule(r -> r.name(name).eventPattern("{\"source\":[\"aws.secretsmanager\"],"
                    + "\"detail-type\":[\"AWS API Call via CloudTrail\"],"
                    + "\"detail\":{\"responseElements\":{\"arn\":[\"" + secret + "\"]}}}"));
            events.putTargets(r -> r.rule(name).targets(Target.builder().id("queue").arn(queueArn).build()));
            try {
                trails.startLogging(r -> r.name(name));
                secrets.putSecretValue(r -> r.secretId(secret).secretString("sensitive-put-example"));
                secrets.updateSecret(r -> r.secretId(secret).secretString("sensitive-update-example"));
                secrets.getSecretValue(r -> r.secretId(secret));

                List<Message> messages = new ArrayList<>();
                for (int attempt = 0; attempt < 5 && messages.size() < 2; attempt++) {
                    messages.addAll(sqs.receiveMessage(r -> r.queueUrl(queue)
                            .maxNumberOfMessages(10).waitTimeSeconds(1)).messages());
                }
                assertThat(messages).hasSize(2);
                List<String> actions = new ArrayList<>();
                ObjectMapper mapper = new ObjectMapper();
                for (Message message : messages) {
                    JsonNode event = mapper.readTree(message.body());
                    assertThat(event.path("source").asText()).isEqualTo("aws.secretsmanager");
                    assertThat(event.path("detail-type").asText()).isEqualTo("AWS API Call via CloudTrail");
                    assertThat(event.path("detail").path("responseElements").path("arn").asText()).isEqualTo(secret);
                    assertThat(event.path("detail").path("managementEvent").asBoolean()).isTrue();
                    assertThat(event.path("detail").path("readOnly").asBoolean()).isFalse();
                    assertThat(message.body()).doesNotContain("sensitive-", "secretString", "secretBinary");
                    actions.add(event.path("detail").path("eventName").asText());
                    sqs.deleteMessage(r -> r.queueUrl(queue).receiptHandle(message.receiptHandle()));
                }
                assertThat(actions).containsExactlyInAnyOrder("PutSecretValue", "UpdateSecret");
                assertThat(sqs.receiveMessage(r -> r.queueUrl(queue).waitTimeSeconds(1)).messages()).isEmpty();
            } finally {
                trails.stopLogging(r -> r.name(name));
                trails.deleteTrail(r -> r.name(name));
                events.removeTargets(r -> r.rule(name).ids("queue"));
                events.deleteRule(r -> r.name(name));
                secrets.deleteSecret(r -> r.secretId(secret).forceDeleteWithoutRecovery(true));
                sqs.deleteQueue(r -> r.queueUrl(queue));
                s3.listObjectsV2(r -> r.bucket(name)).contents().forEach(object ->
                        s3.deleteObject(r -> r.bucket(name).key(object.key())));
                s3.deleteBucket(r -> r.bucket(name));
            }
        }
    }
}
