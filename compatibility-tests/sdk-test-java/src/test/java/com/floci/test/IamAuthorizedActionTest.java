package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishBatchResultEntry;
import software.amazon.awssdk.services.sns.model.SnsException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry;
import software.amazon.awssdk.services.sqs.model.SqsException;
import software.amazon.awssdk.utils.SdkAutoCloseable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Batch operations authorized as their single-item IAM action")
class IamAuthorizedActionTest {

    private IamClient iam;
    private SqsClient sqsAdmin;
    private SnsClient snsAdmin;
    private SqsClient sqs;
    private SnsClient sns;
    private String user;
    private AccessKey accessKey;
    private boolean policyCreated;
    private String queueUrl;
    private String topicArn;

    @BeforeEach
    void setUp() {
        iam = TestFixtures.iamClient();
        sqsAdmin = TestFixtures.sqsClient();
        snsAdmin = TestFixtures.snsClient();
        String name = TestFixtures.uniqueName("authorized-action");
        iam.createUser(r -> r.userName(name));
        user = name;
        accessKey = iam.createAccessKey(r -> r.userName(user)).accessKey();
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey.accessKeyId(), accessKey.secretAccessKey()));
        sqs = SqsClient.builder().endpointOverride(TestFixtures.endpoint()).region(TestFixtures.region())
                .credentialsProvider(credentials).build();
        sns = SnsClient.builder().endpointOverride(TestFixtures.endpoint()).region(TestFixtures.region())
                .credentialsProvider(credentials).build();
        boolean enforced = false;
        try {
            sqs.listQueues();
        } catch (SqsException error) {
            assertThat(error.awsErrorDetails().errorCode()).contains("AccessDenied");
            enforced = true;
        }
        Assumptions.assumeTrue(enforced, "Requires floci.services.iam.enforcement-enabled=true");
        queueUrl = sqsAdmin.createQueue(r -> r.queueName(name)).queueUrl();
        topicArn = snsAdmin.createTopic(r -> r.name(name)).topicArn();
    }

    @AfterEach
    void cleanUp() {
        try {
            if (queueUrl != null) {
                sqsAdmin.deleteQueue(r -> r.queueUrl(queueUrl));
            }
            if (topicArn != null) {
                snsAdmin.deleteTopic(r -> r.topicArn(topicArn));
            }
        } finally {
            try {
                if (policyCreated) {
                    iam.deleteUserPolicy(r -> r.userName(user).policyName("authorized"));
                }
                if (accessKey != null) {
                    iam.deleteAccessKey(r -> r.userName(user).accessKeyId(accessKey.accessKeyId()));
                }
                if (user != null) {
                    iam.deleteUser(r -> r.userName(user));
                }
            } finally {
                for (SdkAutoCloseable client : new SdkAutoCloseable[] {sqs, sns, sqsAdmin, snsAdmin, iam}) {
                    if (client != null) {
                        client.close();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("SendMessageBatch is granted by sqs:SendMessage")
    void sendMessageBatchIsGrantedBySendMessage() {
        allow("sqs:SendMessage");

        assertThat(sqs.sendMessageBatch(r -> r.queueUrl(queueUrl)
                        .entries(e -> e.id("one").messageBody("hello"))).successful())
                .extracting(SendMessageBatchResultEntry::id)
                .containsExactly("one");
    }

    @Test
    @DisplayName("a policy naming SendMessageBatch, which is no IAM action, grants nothing")
    void aPolicyNamingSendMessageBatchGrantsNothing() {
        allow("sqs:SendMessageBatch");

        assertThatThrownBy(() -> sqs.sendMessageBatch(r -> r.queueUrl(queueUrl)
                .entries(e -> e.id("one").messageBody("hello"))))
                .isInstanceOfSatisfying(SqsException.class,
                        error -> assertThat(error.awsErrorDetails().errorCode()).contains("AccessDenied"));
    }

    @Test
    @DisplayName("PublishBatch is granted by sns:Publish")
    void publishBatchIsGrantedByPublish() {
        allow("sns:Publish");

        assertThat(sns.publishBatch(r -> r.topicArn(topicArn)
                        .publishBatchRequestEntries(e -> e.id("one").message("hello"))).successful())
                .extracting(PublishBatchResultEntry::id)
                .containsExactly("one");
    }

    @Test
    @DisplayName("a policy naming PublishBatch, which is no IAM action, grants nothing")
    void aPolicyNamingPublishBatchGrantsNothing() {
        allow("sns:PublishBatch");

        assertThatThrownBy(() -> sns.publishBatch(r -> r.topicArn(topicArn)
                .publishBatchRequestEntries(e -> e.id("one").message("hello"))))
                .isInstanceOfSatisfying(SnsException.class, error -> assertThat(error.statusCode()).isEqualTo(403));
    }

    private void allow(String action) {
        String document = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"*\"}]}";
        iam.putUserPolicy(r -> r.userName(user).policyName("authorized").policyDocument(document));
        policyCreated = true;
    }
}
