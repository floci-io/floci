package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * SQS and SNS calls sent over the Query protocol name their queue or topic in a form body. AWS
 * authorizes them against that queue or topic, as it does the same calls over JSON, so a statement
 * naming one has to bind to it: issue 4898 measured an identity Allow naming a queue that worked over
 * JSON and was refused over Query, which was checked against {@code sqs:<region>:<account>:*}.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class QueryProtocolQueueAndTopicEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void aQuerySendMessageIsCheckedAgainstTheQueueItsFormBodyNames() {
        String allowed = createQueue();
        String other = createQueue();
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:SendMessage","Resource":"%s"}
                ]}""".formatted(queueArn(allowed)));

        sendMessage(signed(accessKeyId, "sqs"), allowed).statusCode(200);
        assertAccessDenied(sendMessage(signed(accessKeyId, "sqs"), other), "sqs:SendMessage");
    }

    @Test
    void aQueryDenyOnOneQueueHoldsAlongsideABroadAllow() {
        String queueUrl = createQueue();
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:*","Resource":"*"},
                  {"Effect":"Deny","Action":"sqs:SendMessage","Resource":"%s"}
                ]}""".formatted(queueArn(queueUrl)));

        assertAccessDenied(sendMessage(signed(accessKeyId, "sqs"), queueUrl), "sqs:SendMessage");
    }

    /** The Query controller dispatches a POST's form body, so a queue in its query string decides nothing. */
    @Test
    void aQuerySendMessageIsCheckedAgainstTheFormBodyQueueNotOneInItsQueryString() {
        String allowed = createQueue();
        String other = createQueue();
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:SendMessage","Resource":"%s"}
                ]}""".formatted(queueArn(allowed)));

        assertAccessDenied(sendMessage(signed(accessKeyId, "sqs").queryParam("QueueUrl", allowed), other),
                "sqs:SendMessage");
    }

    /**
     * The AWS SDK for Java v1 posts to the queue URL's path and leaves QueueUrl out of the form body;
     * the router adds it back, and the check has to see that queue.
     */
    @Test
    void aPathStyleQuerySendMessageIsCheckedAgainstTheQueueItsPathNames() {
        String queueUrl = createQueue();
        String other = createQueue();
        String allowedOnQueue = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:SendMessage","Resource":"%s"}
                ]}""".formatted(queueArn(queueUrl)));
        String deniedOnQueue = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:*","Resource":"*"},
                  {"Effect":"Deny","Action":"sqs:SendMessage","Resource":"%s"}
                ]}""".formatted(queueArn(queueUrl)));

        pathStyleSendMessage(signed(allowedOnQueue, "sqs"), queueUrl).statusCode(200);
        assertAccessDenied(pathStyleSendMessage(signed(allowedOnQueue, "sqs"), other), "sqs:SendMessage");
        assertAccessDenied(pathStyleSendMessage(signed(deniedOnQueue, "sqs"), queueUrl), "sqs:SendMessage");
    }

    @Test
    void aQueryCreateQueueIsCheckedAgainstTheQueueItNames() {
        String prefix = "query-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:CreateQueue","Resource":"arn:aws:sqs:us-east-1:%s:%s-*"}
                ]}""".formatted(ACCOUNT_ID, prefix));

        String queueUrl = createQueue(signed(accessKeyId, "sqs"), prefix + "-allowed")
                .statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        registerQueueRemoval(queueUrl);
        assertAccessDenied(createQueue(signed(accessKeyId, "sqs"), "other-" + prefix), "sqs:CreateQueue");
    }

    @Test
    void aQueryPublishIsCheckedAgainstTheTopicItsFormBodyNames() {
        String allowed = createTopic();
        String other = createTopic();
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:Publish","Resource":"%s"}
                ]}""".formatted(allowed));

        publish(accessKeyId, allowed).statusCode(200);
        assertAccessDenied(publish(accessKeyId, other), "sns:Publish");
    }

    @Test
    void aQueryDenyOnOneTopicHoldsAlongsideABroadAllow() {
        String topicArn = createTopic();
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:*","Resource":"*"},
                  {"Effect":"Deny","Action":"sns:Publish","Resource":"%s"}
                ]}""".formatted(topicArn));

        assertAccessDenied(publish(accessKeyId, topicArn), "sns:Publish");
    }

    @Test
    void aQueryCreateTopicIsCheckedAgainstTheTopicItNames() {
        String prefix = "query-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:CreateTopic","Resource":"arn:aws:sns:us-east-1:%s:%s-*"}
                ]}""".formatted(ACCOUNT_ID, prefix));

        String topicArn = query(signed(accessKeyId, "sns"), "CreateTopic", "Name", prefix + "-allowed")
                .statusCode(200)
                .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        cleanup.register(() -> query(signed(ACCOUNT_ID, "sns"), "DeleteTopic", "TopicArn", topicArn)
                .statusCode(200));
        assertAccessDenied(query(signed(accessKeyId, "sns"), "CreateTopic", "Name", "other-" + prefix),
                "sns:CreateTopic");
    }

    private static void assertAccessDenied(ValidatableResponse response, String action) {
        response.statusCode(403)
                .body("ErrorResponse.Error.Code", containsString("AccessDenied"))
                .body("ErrorResponse.Error.Message", containsString(action));
    }

    private ValidatableResponse publish(String accessKeyId, String topicArn) {
        return query(signed(accessKeyId, "sns"), "Publish", "TopicArn", topicArn, "Message", "hello");
    }

    private static ValidatableResponse sendMessage(RequestSpecification request, String queueUrl) {
        return query(request, "SendMessage", "QueueUrl", queueUrl, "MessageBody", "hello");
    }

    private static ValidatableResponse pathStyleSendMessage(RequestSpecification request, String queueUrl) {
        return request
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "SendMessage")
                .formParam("MessageBody", "hello")
                .when().post(URI.create(queueUrl).getPath()).then();
    }

    private static ValidatableResponse createQueue(RequestSpecification request, String queueName) {
        return query(request, "CreateQueue", "QueueName", queueName);
    }

    private String createQueue() {
        String queueUrl = createQueue(signed(ACCOUNT_ID, "sqs"), "query-" + UUID.randomUUID())
                .statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        registerQueueRemoval(queueUrl);
        return queueUrl;
    }

    private void registerQueueRemoval(String queueUrl) {
        cleanup.register(() -> query(signed(ACCOUNT_ID, "sqs"), "DeleteQueue", "QueueUrl", queueUrl)
                .statusCode(200));
    }

    private String queueArn(String queueUrl) {
        return query(signed(ACCOUNT_ID, "sqs"), "GetQueueAttributes", "QueueUrl", queueUrl,
                "AttributeName.1", "QueueArn")
                .statusCode(200)
                .extract().xmlPath()
                .setRoot("GetQueueAttributesResponse.GetQueueAttributesResult")
                .getString("Attribute.find { it.Name == 'QueueArn' }.Value");
    }

    private String createTopic() {
        return EnforcementFixtures.createTopic(cleanup, baseUri.getPort(), auth(ACCOUNT_ID, "sns"),
                "query-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private String userWithPolicy(String document) {
        String userName = "query-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ACCOUNT_ID, "iam"), userName).accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT_ID, "iam"), userName, "only", document);
        return accessKeyId;
    }

    /** Names the port, which RestAssured no longer points at once the test method has returned. */
    private RequestSpecification signed(String accessKeyId, String service) {
        return given().port(baseUri.getPort()).header("Authorization", auth(accessKeyId, service));
    }

    private static ValidatableResponse query(RequestSpecification request, String action, String... params) {
        RequestSpecification spec = request
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            spec.formParam(params[i], params[i + 1]);
        }
        return spec.when().post("/").then();
    }
}
