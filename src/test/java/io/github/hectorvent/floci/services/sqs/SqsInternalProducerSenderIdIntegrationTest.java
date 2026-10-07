package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Internal producers (S3 notifications, EventBridge targets) send to SQS on the thread of the
 * request that triggered them. The SenderId must not be the role session of that request.
 */
@QuarkusTest
class SqsInternalProducerSenderIdIntegrationTest {

    private static final String IAM_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/iam/aws4_request";
    private static final String STS_AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/sts/aws4_request";
    private static final String ASSUME_ROLE_POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sts:AssumeRole\"}]}";
    private static final String QUEUE_OWNER_ACCOUNT = "000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void s3NotificationSenderIdIsNotUploaderRoleSession() {
        String roleName = "sqs-s3-notif-role";
        String bucket = "sqs-s3-notif-sender-bucket";
        String queueName = "sqs-s3-notif-sender-queue";
        String queueUrl = null;

        try {
            AssumedRole role = assumeRole(roleName, "s3-uploader");
            queueUrl = createQueue(queueName);
            String queueArn = queueArn(queueUrl);

            given().when().put("/" + bucket).then().statusCode(200);
            given()
                    .header("x-amz-skip-destination-validation", "true")
                    .contentType("application/xml")
                    .queryParam("notification", "")
                    .body("""
                            <NotificationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                              <QueueConfiguration>
                                <Id>sender-id-notif</Id>
                                <Queue>%s</Queue>
                                <Event>s3:ObjectCreated:*</Event>
                              </QueueConfiguration>
                            </NotificationConfiguration>
                            """.formatted(queueArn))
                    .when().put("/" + bucket)
                    .then().statusCode(200);

            given()
                    .header("Authorization", signedAuthorization(role.accessKeyId(), "s3"))
                    .header("X-Amz-Security-Token", role.sessionToken())
                    .contentType("text/plain")
                    .body("payload")
                    .when().put("/" + bucket + "/uploaded.txt")
                    .then().statusCode(200);

            String senderId = receiveSenderId(queueUrl);
            assertNotEquals(role.assumedRoleId(), senderId);
            assertEquals(QUEUE_OWNER_ACCOUNT, senderId);
        } finally {
            given().when().delete("/" + bucket + "/uploaded.txt");
            given().when().delete("/" + bucket);
            deleteQueue(queueUrl);
            deleteRole(roleName);
        }
    }

    @Test
    void eventBridgeSenderIdIsNotPutEventsCallerRoleSession() {
        String roleName = "sqs-eb-sender-role";
        String queueName = "sqs-eb-sender-queue";
        String ruleName = "sqs-eb-sender-rule";
        String queueUrl = null;

        try {
            AssumedRole role = assumeRole(roleName, "eb-publisher");
            queueUrl = createQueue(queueName);
            String queueArn = queueArn(queueUrl);

            given()
                    .contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "AWSEvents.PutRule")
                    .body("{\"Name\":\"" + ruleName + "\",\"EventBusName\":\"default\","
                            + "\"EventPattern\":\"{\\\"source\\\":[\\\"test.sender-id\\\"]}\"}")
                    .when().post("/").then().statusCode(200);
            given()
                    .contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "AWSEvents.PutTargets")
                    .body("{\"Rule\":\"" + ruleName + "\",\"EventBusName\":\"default\","
                            + "\"Targets\":[{\"Id\":\"t1\",\"Arn\":\"" + queueArn + "\"}]}")
                    .when().post("/").then().statusCode(200)
                    .body("FailedEntryCount", equalTo(0));

            given()
                    .contentType("application/x-amz-json-1.1")
                    .header("Authorization", signedAuthorization(role.accessKeyId(), "events"))
                    .header("X-Amz-Security-Token", role.sessionToken())
                    .header("X-Amz-Target", "AWSEvents.PutEvents")
                    .body("{\"Entries\":[{\"EventBusName\":\"default\",\"Source\":\"test.sender-id\","
                            + "\"DetailType\":\"SenderIdTest\",\"Detail\":\"{}\"}]}")
                    .when().post("/").then().statusCode(200)
                    .body("FailedEntryCount", equalTo(0));

            String senderId = receiveSenderId(queueUrl);
            assertNotEquals(role.assumedRoleId(), senderId);
            assertEquals(QUEUE_OWNER_ACCOUNT, senderId);
        } finally {
            given()
                    .contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "AWSEvents.RemoveTargets")
                    .body("{\"Rule\":\"" + ruleName + "\",\"EventBusName\":\"default\",\"Ids\":[\"t1\"]}")
                    .when().post("/");
            given()
                    .contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", "AWSEvents.DeleteRule")
                    .body("{\"Name\":\"" + ruleName + "\",\"EventBusName\":\"default\"}")
                    .when().post("/");
            deleteQueue(queueUrl);
            deleteRole(roleName);
        }
    }

    private record AssumedRole(String accessKeyId, String sessionToken, String assumedRoleId) {}

    private AssumedRole assumeRole(String roleName, String sessionName) {
        String roleArn = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", ASSUME_ROLE_POLICY_DOCUMENT)
                .header("Authorization", IAM_AUTHORIZATION)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");

        ValidatableResponse assumed = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", roleArn)
                .formParam("RoleSessionName", sessionName)
                .header("Authorization", STS_AUTHORIZATION)
                .when().post("/").then().statusCode(200);
        XmlPath xml = assumed.extract().xmlPath();
        return new AssumedRole(
                xml.getString("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId"),
                xml.getString("AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken"),
                xml.getString("AssumeRoleResponse.AssumeRoleResult.AssumedRoleUser.AssumedRoleId"));
    }

    private String signedAuthorization(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261001/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private String createQueue(String queueName) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", queueName)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private String queueArn(String queueUrl) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueAttributes")
                .formParam("QueueUrl", queueUrl)
                .formParam("AttributeName.1", "QueueArn")
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString(
                        "GetQueueAttributesResponse.GetQueueAttributesResult.Attribute.find { it.Name == 'QueueArn' }.Value");
    }

    private String receiveSenderId(String queueUrl) {
        String senderId = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MaxNumberOfMessages", "1")
                .formParam("VisibilityTimeout", "0")
                .formParam("WaitTimeSeconds", "5")
                .formParam("AttributeName.1", "SenderId")
                .when().post("/").then().statusCode(200)
                .extract().xmlPath()
                .getString("ReceiveMessageResponse.ReceiveMessageResult.Message.Attribute.find { it.Name == 'SenderId' }.Value");
        assertNotNull(senderId, "no message with a SenderId arrived in the queue");
        return senderId;
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
        given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteRole")
                .formParam("RoleName", roleName)
                .header("Authorization", IAM_AUTHORIZATION)
                .when().post("/");
    }
}
