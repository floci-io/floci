package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SqsPrincipalSenderIdIntegrationTest {

    private static final String IAM_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/iam/aws4_request";
    private static final String STS_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/sts/aws4_request";
    private static final String ASSUME_ROLE_POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sts:AssumeRole\"}]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void senderIdMatchesAssumedRoleIdWhenSentByAssumedRole() {
        String roleName = "sqs-sender-role-test";
        String sessionName = "sqs-role-session";
        String queueName = "sqs-assumed-role-queue";
        String queueUrl = null;

        try {
            ValidatableResponse roleResp = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateRole")
                    .formParam("RoleName", roleName)
                    .formParam("AssumeRolePolicyDocument", ASSUME_ROLE_POLICY_DOCUMENT)
                    .header("Authorization", IAM_AUTHORIZATION)
                    .when().post("/").then().statusCode(200);

            String roleId = roleResp.extract().xmlPath().getString(
                    "CreateRoleResponse.CreateRoleResult.Role.RoleId");
            String roleArn = roleResp.extract().xmlPath().getString(
                    "CreateRoleResponse.CreateRoleResult.Role.Arn");

            ValidatableResponse assumed = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "AssumeRole")
                    .formParam("RoleArn", roleArn)
                    .formParam("RoleSessionName", sessionName)
                    .header("Authorization", STS_AUTHORIZATION)
                    .when().post("/").then().statusCode(200);

            String accessKeyId = assumed.extract().xmlPath().getString(
                    "AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
            String sessionToken = assumed.extract().xmlPath().getString(
                    "AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken");
            String assumedRoleId = assumed.extract().xmlPath().getString(
                    "AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.AssumedRoleId");

            assertEquals(roleId + ":" + sessionName, assumedRoleId);

            queueUrl = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateQueue")
                    .formParam("QueueName", queueName)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

            String authHeader = "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                    + "/20261001/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=abc";

            given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", authHeader)
                    .header("X-Amz-Security-Token", sessionToken)
                    .formParam("Action", "SendMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MessageBody", "hello-from-assumed-role")
                    .when().post("/").then().statusCode(200);

            XmlPath xml = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "ReceiveMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MaxNumberOfMessages", "1")
                    .formParam("VisibilityTimeout", "0")
                    .formParam("AttributeName.1", "SenderId")
                    .when().post("/").then().statusCode(200)
                    .body(containsString("<Name>SenderId</Name>"))
                    .body(containsString("<Value>" + assumedRoleId + "</Value>"))
                    .extract().xmlPath();

            String senderId = xml.getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Attribute.Value");
            assertEquals(assumedRoleId, senderId);
            assertEquals(roleId + ":" + sessionName, senderId);
        } finally {
            deleteQueue(queueUrl);
            deleteRole(roleName);
        }
    }

    @Test
    void senderIdMatchesUserIdWhenSentByIamUser() {
        String userName = "sqs-sender-user-test";
        String queueName = "sqs-iam-user-queue";
        String queueUrl = null;
        String accessKeyId = null;

        try {
            XmlPath userResp = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateUser")
                    .formParam("UserName", userName)
                    .header("Authorization", IAM_AUTHORIZATION)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath();

            String userId = userResp.getString("CreateUserResponse.CreateUserResult.User.UserId");
            assertTrue(userId.startsWith("AIDA"), "IAM UserId should start with AIDA");

            XmlPath keyResp = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateAccessKey")
                    .formParam("UserName", userName)
                    .header("Authorization", IAM_AUTHORIZATION)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath();

            accessKeyId = keyResp.getString(
                    "CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");

            queueUrl = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateQueue")
                    .formParam("QueueName", queueName)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

            String authHeader = "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                    + "/20261001/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=abc";

            given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", authHeader)
                    .formParam("Action", "SendMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MessageBody", "hello-from-iam-user")
                    .when().post("/").then().statusCode(200);

            XmlPath xml = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "ReceiveMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MaxNumberOfMessages", "1")
                    .formParam("VisibilityTimeout", "0")
                    .formParam("AttributeName.1", "SenderId")
                    .when().post("/").then().statusCode(200)
                    .body(containsString("<Name>SenderId</Name>"))
                    .body(containsString("<Value>" + userId + "</Value>"))
                    .extract().xmlPath();

            String senderId = xml.getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Attribute.Value");
            assertEquals(userId, senderId);
            assertTrue(senderId.startsWith("AIDA"));
        } finally {
            deleteQueue(queueUrl);
            deleteAccessKey(userName, accessKeyId);
            deleteUser(userName);
        }
    }

    @Test
    void queueWithMultipleSendersPreservesIndividualSenderIds() {
        String roleName = "sqs-multi-sender-role";
        String sessionName = "multi-session";
        String userName = "sqs-multi-sender-user";
        String queueName = "sqs-multi-sender-queue";
        String queueUrl = null;
        String userAccessKeyId = null;

        try {
            ValidatableResponse roleResp = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateRole")
                    .formParam("RoleName", roleName)
                    .formParam("AssumeRolePolicyDocument", ASSUME_ROLE_POLICY_DOCUMENT)
                    .header("Authorization", IAM_AUTHORIZATION)
                    .when().post("/").then().statusCode(200);

            String roleId = roleResp.extract().xmlPath().getString(
                    "CreateRoleResponse.CreateRoleResult.Role.RoleId");
            String roleArn = roleResp.extract().xmlPath().getString(
                    "CreateRoleResponse.CreateRoleResult.Role.Arn");

            ValidatableResponse assumed = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "AssumeRole")
                    .formParam("RoleArn", roleArn)
                    .formParam("RoleSessionName", sessionName)
                    .header("Authorization", STS_AUTHORIZATION)
                    .when().post("/").then().statusCode(200);

            String roleAccessKeyId = assumed.extract().xmlPath().getString(
                    "AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
            String roleSessionToken = assumed.extract().xmlPath().getString(
                    "AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken");
            String expectedAssumedRoleId = roleId + ":" + sessionName;

            XmlPath userResp = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateUser")
                    .formParam("UserName", userName)
                    .header("Authorization", IAM_AUTHORIZATION)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath();

            String expectedUserId = userResp.getString("CreateUserResponse.CreateUserResult.User.UserId");

            XmlPath keyResp = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateAccessKey")
                    .formParam("UserName", userName)
                    .header("Authorization", IAM_AUTHORIZATION)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath();

            userAccessKeyId = keyResp.getString(
                    "CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");

            queueUrl = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateQueue")
                    .formParam("QueueName", queueName)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

            String roleAuth = "AWS4-HMAC-SHA256 Credential=" + roleAccessKeyId
                    + "/20261001/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=abc";
            given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", roleAuth)
                    .header("X-Amz-Security-Token", roleSessionToken)
                    .formParam("Action", "SendMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MessageBody", "msg-assumed-role")
                    .when().post("/").then().statusCode(200);

            String userAuth = "AWS4-HMAC-SHA256 Credential=" + userAccessKeyId
                    + "/20261001/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=abc";
            given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", userAuth)
                    .formParam("Action", "SendMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MessageBody", "msg-iam-user")
                    .when().post("/").then().statusCode(200);

            given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "SendMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MessageBody", "msg-default-account")
                    .when().post("/").then().statusCode(200);

            XmlPath xml = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "ReceiveMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MaxNumberOfMessages", "10")
                    .formParam("VisibilityTimeout", "0")
                    .formParam("AttributeName.1", "SenderId")
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath();

            List<String> bodies = xml.getList(
                    "ReceiveMessageResponse.ReceiveMessageResult.Message.Body", String.class);
            assertEquals(3, bodies.size());
            int roleIdx = bodies.indexOf("msg-assumed-role");
            int userIdx = bodies.indexOf("msg-iam-user");
            int defaultIdx = bodies.indexOf("msg-default-account");
            assertTrue(roleIdx >= 0, "Assumed role message should be present");
            assertTrue(userIdx >= 0, "IAM user message should be present");
            assertTrue(defaultIdx >= 0, "Default account message should be present");

            List<String> senderIds = xml.getList(
                    "ReceiveMessageResponse.ReceiveMessageResult.Message.Attribute.Value", String.class);
            assertEquals(expectedAssumedRoleId, senderIds.get(roleIdx));
            assertEquals(expectedUserId, senderIds.get(userIdx));
            assertEquals("000000000000", senderIds.get(defaultIdx));
        } finally {
            deleteQueue(queueUrl);
            deleteAccessKey(userName, userAccessKeyId);
            deleteUser(userName);
            deleteRole(roleName);
        }
    }

    @Test
    void senderIdIsSnsServicePrincipalWhenDeliveredFromSnsSubscription() {
        String queueName = "sns-sender-id-queue";
        String topicName = "sns-sender-id-topic";
        String queueUrl = null;
        String topicArn = null;
        String subscriptionArn = null;

        try {
            queueUrl = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateQueue")
                    .formParam("QueueName", queueName)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

            String queueArn = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "GetQueueAttributes")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("AttributeName.1", "QueueArn")
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath().getString("GetQueueAttributesResponse.GetQueueAttributesResult.Attribute.find { it.Name == 'QueueArn' }.Value");

            topicArn = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateTopic")
                    .formParam("Name", topicName)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");

            subscriptionArn = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "Subscribe")
                    .formParam("TopicArn", topicArn)
                    .formParam("Protocol", "sqs")
                    .formParam("Endpoint", queueArn)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath().getString("SubscribeResponse.SubscribeResult.SubscriptionArn");

            String authHeader = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261001/us-east-1/sns/aws4_request, SignedHeaders=host, Signature=abc";
            given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", authHeader)
                    .formParam("Action", "Publish")
                    .formParam("TopicArn", topicArn)
                    .formParam("Message", "hello-from-sns")
                    .when().post("/").then().statusCode(200);

            XmlPath xml = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "ReceiveMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MaxNumberOfMessages", "1")
                    .formParam("VisibilityTimeout", "0")
                    .formParam("AttributeName.1", "SenderId")
                    .when().post("/").then().statusCode(200)
                    .body(containsString("<Name>SenderId</Name>"))
                    .extract().xmlPath();

            String senderId = xml.getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Attribute.Value");
            assertEquals("sns.amazonaws.com", senderId);
        } finally {
            if (subscriptionArn != null && !subscriptionArn.isEmpty()) {
                given()
                        .contentType("application/x-www-form-urlencoded")
                        .formParam("Action", "Unsubscribe")
                        .formParam("SubscriptionArn", subscriptionArn)
                        .when().post("/");
            }
            if (topicArn != null) {
                given()
                        .contentType("application/x-www-form-urlencoded")
                        .formParam("Action", "DeleteTopic")
                        .formParam("TopicArn", topicArn)
                        .when().post("/");
            }
            deleteQueue(queueUrl);
        }
    }

    @Test
    void senderIdIsNotAttributedToRoleWhenSessionTokenIsMissingOrInvalid() {
        String roleName = "sqs-sender-untrusted-role-test";
        String sessionName = "test-untrusted-session";
        String queueName = "sqs-untrusted-role-queue";
        String queueUrl = null;

        try {
            ValidatableResponse roleResp = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateRole")
                    .formParam("RoleName", roleName)
                    .formParam("AssumeRolePolicyDocument", ASSUME_ROLE_POLICY_DOCUMENT)
                    .header("Authorization", IAM_AUTHORIZATION)
                    .when().post("/").then().statusCode(200);

            String roleId = roleResp.extract().xmlPath().getString(
                    "CreateRoleResponse.CreateRoleResult.Role.RoleId");
            String roleArn = roleResp.extract().xmlPath().getString(
                    "CreateRoleResponse.CreateRoleResult.Role.Arn");

            ValidatableResponse assumed = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "AssumeRole")
                    .formParam("RoleArn", roleArn)
                    .formParam("RoleSessionName", sessionName)
                    .header("Authorization", STS_AUTHORIZATION)
                    .when().post("/").then().statusCode(200);

            String roleAccessKeyId = assumed.extract().xmlPath().getString(
                    "AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
            String assumedRoleId = roleId + ":" + sessionName;

            queueUrl = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateQueue")
                    .formParam("QueueName", queueName)
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

            String roleAuth = "AWS4-HMAC-SHA256 Credential=" + roleAccessKeyId
                    + "/20261001/us-east-1/sqs/aws4_request, SignedHeaders=host, Signature=abc";

            // 1. SendMessage without X-Amz-Security-Token -> must not be attributed to assumedRoleId
            given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", roleAuth)
                    .formParam("Action", "SendMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MessageBody", "msg-no-token")
                    .when().post("/").then().statusCode(200);

            // 2. SendMessage with invalid X-Amz-Security-Token -> must not be attributed to assumedRoleId
            given()
                    .contentType("application/x-www-form-urlencoded")
                    .header("Authorization", roleAuth)
                    .header("X-Amz-Security-Token", "invalid-token")
                    .formParam("Action", "SendMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MessageBody", "msg-bad-token")
                    .when().post("/").then().statusCode(200);

            XmlPath xml = given()
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "ReceiveMessage")
                    .formParam("QueueUrl", queueUrl)
                    .formParam("MaxNumberOfMessages", "10")
                    .formParam("VisibilityTimeout", "0")
                    .formParam("AttributeName.1", "SenderId")
                    .when().post("/").then().statusCode(200)
                    .extract().xmlPath();

            List<String> senderIds = xml.getList(
                    "ReceiveMessageResponse.ReceiveMessageResult.Message.Attribute.Value", String.class);
            assertEquals(2, senderIds.size());
            for (String senderId : senderIds) {
                assertNotEquals(assumedRoleId, senderId);
                assertEquals("000000000000", senderId);
            }
        } finally {
            deleteQueue(queueUrl);
            deleteRole(roleName);
        }
    }

    private void deleteQueue(String queueUrl) {
        if (queueUrl == null) {
            return;
        }
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteQueue")
                .formParam("QueueUrl", queueUrl)
                .when().post("/");
    }

    private void deleteRole(String roleName) {
        if (roleName == null) {
            return;
        }
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole")
                .formParam("RoleName", roleName)
                .header("Authorization", IAM_AUTHORIZATION)
                .when().post("/");
    }

    private void deleteAccessKey(String userName, String accessKeyId) {
        if (userName == null || accessKeyId == null) {
            return;
        }
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteAccessKey")
                .formParam("UserName", userName)
                .formParam("AccessKeyId", accessKeyId)
                .header("Authorization", IAM_AUTHORIZATION)
                .when().post("/");
    }

    private void deleteUser(String userName) {
        if (userName == null) {
            return;
        }
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteUser")
                .formParam("UserName", userName)
                .header("Authorization", IAM_AUTHORIZATION)
                .when().post("/");
    }
}
