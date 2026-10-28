package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * Proves that SQS and SNS requests see the target queue's or topic's {@code aws:ResourceTag}
 * keys, which an ABAC policy conditions on.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SqsSnsResourceTagEnforcementIntegrationTest {

    private static final String CALLER_ACCOUNT_ID = "111122223333";
    private static final String ROLE_ACCOUNT_ID = "222233334444";
    private static final String REGION = "us-east-1";

    @Test
    void aTaggedQueueAllowsTheDataActionsAndAnUntaggedQueueDeniesThem() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String taggedQueue = createQueue("tags-tagged-" + suffix);
        String untaggedQueue = createQueue("tags-untagged-" + suffix);
        tagQueue(taggedQueue, "team", "blue");
        String role = "SqsResourceTagRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["sqs:SendMessage","sqs:ReceiveMessage","sqs:DeleteMessage"],
                   "Resource":"*","Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role);

        sendMessage(accessKey, taggedQueue, "allowed", 200);
        sendMessage(accessKey, untaggedQueue, "denied", 403);
        receiveMessage(accessKey, taggedQueue, 200);
        receiveMessage(accessKey, untaggedQueue, 403);
    }

    @Test
    void aQueueTagIsReadFromTheQueuesAccountNotTheCallers() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String taggedQueue = createQueue("tags-cross-tagged-" + suffix);
        String untaggedQueue = createQueue("tags-cross-untagged-" + suffix);
        tagQueue(taggedQueue, "team", "blue");
        String accessKey = createUserWithSqsTagPolicy("CrossAccountSqsUser" + suffix);

        sendMessage(accessKey, taggedQueue, "allowed", 200);
        sendMessage(accessKey, untaggedQueue, "denied", 403);
    }

    @Test
    void createActionsDoNotBorrowTagsFromADecoyTarget() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String taggedQueue = createQueue("tags-create-queue-" + suffix);
        tagQueue(taggedQueue, "team", "blue");
        String taggedTopic = createTopic("tags-create-topic-" + suffix);
        tagTopic(taggedTopic, "team", "blue");
        String role = "CreateDecoyRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["sqs:CreateQueue","sns:CreateTopic"],"Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role);

        createQueueDenied(accessKey, "other-queue-" + suffix, taggedQueue);
        createTopicDenied(accessKey, "other-topic-" + suffix, taggedTopic);
    }

    @Test
    void publishBatchDoesNotTakeAPhoneNumberAsItsTarget() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tagged = createTopic("tags-batch-sms-tagged-" + suffix);
        String untagged = createTopic("tags-batch-sms-untagged-" + suffix);
        tagTopic(tagged, "team", "blue");
        String role = "SnsBatchSmsRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:Publish","Resource":"*"},
                  {"Effect":"Deny","Action":"sns:Publish","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role);

        publishBatch(accessKey, "Action", tagged, "+15555550100", 403);
        publishBatch(accessKey, "Operation", tagged, "+15555550100", 403);
        publishBatch(accessKey, "Action", untagged, "+15555550100", 200);
    }

    @Test
    void aNumericQueuePrefixDoesNotMoveTheTagLookupToAnotherAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String queueUrl = createQueue("tags-prefix-" + suffix);
        tagQueue(queueUrl, "team", "blue");
        String role = "SqsPrefixRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:SendMessage","Resource":"*"},
                  {"Effect":"Deny","Action":"sqs:SendMessage","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role);
        String prefixed = queueUrl.replace("http://localhost:4566/", "http://localhost:4566/111111111111/");

        sendMessage(accessKey, prefixed, "denied", 403);
    }

    @Test
    void snsTagActionsUseTheTopicsTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tagged = createTopic("tags-action-tagged-" + suffix);
        String untagged = createTopic("tags-action-untagged-" + suffix);
        tagTopic(tagged, "team", "blue");
        String role = "SnsTagActionRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:ListTagsForResource","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role);

        listTagsForResource(accessKey, tagged, 200);
        listTagsForResource(accessKey, untagged, 403);
    }

    @Test
    void snsSubscriptionActionsUseTheTopicsTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tagged = createTopic("tags-sub-tagged-" + suffix);
        String untagged = createTopic("tags-sub-untagged-" + suffix);
        tagTopic(tagged, "team", "blue");
        String taggedSubscription = subscribe(tagged, "tagged-" + suffix + "@example.com");
        String untaggedSubscription = subscribe(untagged, "untagged-" + suffix + "@example.com");
        String role = "SnsSubscriptionRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:GetSubscriptionAttributes","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role);

        getSubscriptionAttributes(accessKey, taggedSubscription, 200);
        getSubscriptionAttributes(accessKey, untaggedSubscription, 403);
    }

    @Test
    void snsPublishWithAPhoneNumberDoesNotBorrowTheTopicsTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String tagged = createTopic("tags-sms-tagged-" + suffix);
        tagTopic(tagged, "team", "blue");
        String role = "SnsSmsRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:Publish","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role);

        publish(accessKey, tagged, 200);
        publishWithPhoneNumber(accessKey, tagged, "+15555550100", 403);
    }

    @Test
    void sqsGetQueueUrlUsesTheQueuesTags() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String taggedName = "tags-get-url-tagged-" + suffix;
        String untaggedName = "tags-get-url-untagged-" + suffix;
        String taggedQueue = createQueue(taggedName);
        createQueue(untaggedName);
        tagQueue(taggedQueue, "team", "blue");
        String role = "SqsGetUrlRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:GetQueueUrl","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role);

        getQueueUrl(accessKey, taggedName, 200);
        getQueueUrl(accessKey, untaggedName, 403);
    }

    @Test
    void aTaggedTopicAllowsPublishAndAnUntaggedTopicDeniesIt() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String taggedTopic = createTopic("tags-tagged-" + suffix);
        String untaggedTopic = createTopic("tags-untagged-" + suffix);
        tagTopic(taggedTopic, "team", "blue");
        String role = "SnsResourceTagRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:Publish","Resource":"*",
                   "Condition":{"StringLike":{"aws:ResourceTag/team":"bl*"}}}]}
                """);
        String accessKey = assumeRole(role);

        publish(accessKey, taggedTopic, 200);
        publish(accessKey, untaggedTopic, 403);
    }

    private static String createQueue(String queueName) {
        return given()
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", queueName)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "sqs"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("CreateQueueResponse.CreateQueueResult.QueueUrl", startsWith("http"))
                .extract()
                .path("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static void tagQueue(String queueUrl, String key, String value) {
        given()
                .formParam("Action", "TagQueue")
                .formParam("QueueUrl", queueUrl)
                .formParam("Tag.1.Key", key)
                .formParam("Tag.1.Value", value)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "sqs"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void sendMessage(String accessKey, String queueUrl, String body, int status) {
        ValidatableResponse response = given()
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MessageBody", body)
                .header("Authorization", auth(accessKey, "sqs"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("not authorized"));
        }
    }

    private static void receiveMessage(String accessKey, String queueUrl, int status) {
        ValidatableResponse response = given()
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .header("Authorization", auth(accessKey, "sqs"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("not authorized"));
        }
    }

    private static String createTopic(String topicName) {
        return given()
                .formParam("Action", "CreateTopic")
                .formParam("Name", topicName)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("CreateTopicResponse.CreateTopicResult.TopicArn", startsWith("arn:"))
                .extract()
                .path("CreateTopicResponse.CreateTopicResult.TopicArn");
    }

    private static void tagTopic(String topicArn, String key, String value) {
        given()
                .formParam("Action", "TagResource")
                .formParam("ResourceArn", topicArn)
                .formParam("Tags.member.1.Key", key)
                .formParam("Tags.member.1.Value", value)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void publish(String accessKey, String topicArn, int status) {
        ValidatableResponse response = given()
                .formParam("Action", "Publish")
                .formParam("TopicArn", topicArn)
                .formParam("Message", "hello")
                .header("Authorization", auth(accessKey, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("not authorized"));
        }
    }

    private static void createRole(String roleName) {
        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {"Effect": "Allow", "Principal": {"AWS": "*"}, "Action": "sts:AssumeRole"}
                      ]
                    }
                    """)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putRolePolicy(String roleName, String policyDocument) {
        given()
                .formParam("Action", "PutRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", "ResourceTagPolicy")
                .formParam("PolicyDocument", policyDocument)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void publishBatch(String accessKey, String actionField, String topicArn,
                                     String phoneNumber, int status) {
        ValidatableResponse response = given()
                .formParam(actionField, "PublishBatch")
                .formParam("TopicArn", topicArn)
                .formParam("PhoneNumber", phoneNumber)
                .formParam("PublishBatchRequestEntries.member.1.Id", "1")
                .formParam("PublishBatchRequestEntries.member.1.Message", "hello")
                .header("Authorization", auth(accessKey, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("not authorized"));
        }
    }

    private static void createQueueDenied(String accessKey, String queueName, String decoyQueueUrl) {
        given()
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", queueName)
                .formParam("QueueUrl", decoyQueueUrl)
                .header("Authorization", auth(accessKey, "sqs"))
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body(containsString("not authorized"));
    }

    private static void createTopicDenied(String accessKey, String name, String decoyTopicArn) {
        given()
                .formParam("Action", "CreateTopic")
                .formParam("Name", name)
                .formParam("TopicArn", decoyTopicArn)
                .header("Authorization", auth(accessKey, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .body(containsString("not authorized"));
    }

    private static String subscribe(String topicArn, String endpoint) {
        return given()
                .formParam("Action", "Subscribe")
                .formParam("TopicArn", topicArn)
                .formParam("Protocol", "email")
                .formParam("Endpoint", endpoint)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract()
                .path("SubscribeResponse.SubscribeResult.SubscriptionArn");
    }

    private static void listTagsForResource(String accessKey, String resourceArn, int status) {
        ValidatableResponse response = given()
                .formParam("Action", "ListTagsForResource")
                .formParam("ResourceArn", resourceArn)
                .header("Authorization", auth(accessKey, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("not authorized"));
        }
    }

    private static void getSubscriptionAttributes(String accessKey, String subscriptionArn, int status) {
        ValidatableResponse response = given()
                .formParam("Action", "GetSubscriptionAttributes")
                .formParam("SubscriptionArn", subscriptionArn)
                .header("Authorization", auth(accessKey, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("not authorized"));
        }
    }

    private static void getQueueUrl(String accessKey, String queueName, int status) {
        ValidatableResponse response = given()
                .formParam("Action", "GetQueueUrl")
                .formParam("QueueName", queueName)
                .header("Authorization", auth(accessKey, "sqs"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("not authorized"));
        }
    }

    private static void publishWithPhoneNumber(String accessKey, String topicArn, String phoneNumber, int status) {
        ValidatableResponse response = given()
                .formParam("Action", "Publish")
                .formParam("TopicArn", topicArn)
                .formParam("PhoneNumber", phoneNumber)
                .formParam("Message", "hello")
                .header("Authorization", auth(accessKey, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("not authorized"));
        }
    }

    private static String createUserWithSqsTagPolicy(String userName) {
        given()
                .formParam("Action", "CreateUser")
                .formParam("UserName", userName)
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
        String accessKey = given()
                .formParam("Action", "CreateAccessKey")
                .formParam("UserName", userName)
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract()
                .path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        given()
                .formParam("Action", "PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", "ResourceTagPolicy")
                .formParam("PolicyDocument", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"sqs:SendMessage","Resource":"*",
                       "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                    """)
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
        return accessKey;
    }

    private static String assumeRole(String roleName) {
        return given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + roleName)
                .formParam("RoleSessionName", "resource-tag-test")
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "sts"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"))
                .extract()
                .path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260904/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
