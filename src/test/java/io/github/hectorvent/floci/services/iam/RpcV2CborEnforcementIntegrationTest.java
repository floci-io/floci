package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Smithy RPC v2 CBOR call names its operation in the path, not in a Query {@code Action} or an
 * {@code X-Amz-Target} header, and is checked as that operation like any other call: an identity
 * policy has to grant it, a deny refuses it, and an operation AWS authorizes as another action is
 * checked as that action. The resource and condition keys a CBOR body names count as a JSON body's
 * do.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class RpcV2CborEnforcementIntegrationTest {

    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final String ACCOUNT_ID = "000000000000";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anRpcV2CallIsGrantedByThePermissionItsOperationNeeds() {
        String accessKeyId = userWithPolicy(allow("sqs:ListQueues"));

        sqsRpcV2(accessKeyId, "sqs", "ListQueues", "{}")
                .statusCode(200);
    }

    @Test
    void anRpcV2CallIsRefusedWithoutThePermissionItsOperationNeeds() throws IOException {
        String accessKeyId = userWithPolicy(allow("sns:ListTopics"));

        assertAccessDenied(sqsRpcV2(accessKeyId, "sqs", "ListQueues", "{}"), "sqs:ListQueues");
    }

    @Test
    void anRpcV2CallIsRefusedByAnExplicitDeny() throws IOException {
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:*","Resource":"*"},
                  {"Effect":"Deny","Action":"sqs:ListQueues","Resource":"*"}
                ]}""");

        assertAccessDenied(sqsRpcV2(accessKeyId, "sqs", "ListQueues", "{}"), "sqs:ListQueues");
    }

    @Test
    void anRpcV2CallIsCheckedAgainstTheServiceThatServesItNotItsSigningScope() throws IOException {
        // Signed for SNS, but the path sends it to SQS, which is what runs it.
        String accessKeyId = userWithPolicy(allow("sns:*"));

        assertAccessDenied(sqsRpcV2(accessKeyId, "sns", "ListQueues", "{}"), "sqs:ListQueues");
    }

    @Test
    void anRpcV2CallIsCheckedAsThePathOperationNotAnXAmzTargetItCarries() throws IOException {
        String queueUrl = createQueue();
        String accessKeyId = userWithPolicy(allow("sqs:ListQueues"));

        assertAccessDenied(sqsRpcV2(signed(accessKeyId, "sqs").header("smithy-protocol", "rpc-v2-cbor")
                        .header("X-Amz-Target", "AmazonSQS.ListQueues"),
                "DeleteQueue", """
                        {"QueueUrl": "%s"}
                        """.formatted(queueUrl)),
                "sqs:DeleteQueue");
    }

    @Test
    void anRpcV2CallWithAnUnrecognisedProtocolHeaderIsStillChecked() throws IOException {
        // The claim filter leaves this call unclaimed, and the endpoint serves it all the same.
        String accessKeyId = userWithPolicy(allow("sns:ListTopics"));

        assertAccessDenied(sqsRpcV2(signed(accessKeyId, "sqs").header("smithy-protocol", "unrecognised"),
                "ListQueues", "{}"), "sqs:ListQueues");
    }

    @Test
    void anUnsignedRpcV2CallWithAnUnrecognisedProtocolHeaderIsRefused() throws IOException {
        byte[] body = sqsRpcV2(given().header("smithy-protocol", "unrecognised"), "ListQueues", "{}")
                .statusCode(403)
                .extract().asByteArray();

        assertEquals("MissingAuthenticationTokenException", CBOR_MAPPER.readTree(body).path("__type").asText());
    }

    @Test
    void anRpcV2BatchSendIsGrantedBySendMessage() {
        String queueUrl = createQueue();
        String accessKeyId = userWithPolicy(allow("sqs:SendMessage"));

        sqsRpcV2(accessKeyId, "sqs", "SendMessageBatch", sendBatch(queueUrl))
                .statusCode(200);
    }

    @Test
    void anRpcV2PolicyNamingTheBatchOperationGrantsNothing() throws IOException {
        String queueUrl = createQueue();
        String accessKeyId = userWithPolicy(allow("sqs:SendMessageBatch"));

        assertAccessDenied(sqsRpcV2(accessKeyId, "sqs", "SendMessageBatch", sendBatch(queueUrl)),
                "sqs:SendMessage");
    }

    @Test
    void anRpcV2CallIsCheckedAgainstTheQueueItsBodyNames() throws IOException {
        String allowed = createQueue();
        String other = createQueue();
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:SendMessage","Resource":"%s"}
                ]}""".formatted(queueArn(allowed)));

        sqsRpcV2(accessKeyId, "sqs", "SendMessage", sendMessage(allowed))
                .statusCode(200);
        assertAccessDenied(sqsRpcV2(accessKeyId, "sqs", "SendMessage", sendMessage(other)), "sqs:SendMessage");
    }

    @Test
    void anRpcV2DenyOnOneQueueHoldsAlongsideABroadAllow() throws IOException {
        String queueUrl = createQueue();
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sqs:*","Resource":"*"},
                  {"Effect":"Deny","Action":"sqs:DeleteQueue","Resource":"%s"}
                ]}""".formatted(queueArn(queueUrl)));

        assertAccessDenied(sqsRpcV2(accessKeyId, "sqs", "DeleteQueue", """
                {"QueueUrl": "%s"}
                """.formatted(queueUrl)), "sqs:DeleteQueue");
    }

    @Test
    void anRpcV2GetItemIsCheckedAgainstTheLeadingKeyItsBodyNames() throws IOException {
        String table = createTable();
        for (String id : List.of("mine", "theirs")) {
            dynamoDbAsRoot("PutItem", """
                    {"TableName": "%s", "Item": {"id": {"S": "%s"}}}
                    """.formatted(table, id));
        }
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
                   "Condition":{"ForAllValues:StringEquals":{"dynamodb:LeadingKeys":["mine"]}}}
                ]}""");

        rpcV2(signed(accessKeyId, "dynamodb").header("smithy-protocol", "rpc-v2-cbor"),
                "DynamoDB_20120810", "GetItem", getItem(table, "mine"))
                .statusCode(200);
        assertAccessDenied(rpcV2(signed(accessKeyId, "dynamodb").header("smithy-protocol", "rpc-v2-cbor"),
                "DynamoDB_20120810", "GetItem", getItem(table, "theirs")), "dynamodb:GetItem");
    }

    @Test
    void aTargetedCborCallIsCheckedAgainstTheStreamItsBodyNames() throws IOException {
        // The older CBOR shape, an x-amz-cbor-1.1 body with X-Amz-Target at the root, has the same
        // body to read the resource from.
        String stream = "rpcv2-" + UUID.randomUUID();
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kinesis:*","Resource":"*"},
                  {"Effect":"Deny","Action":"kinesis:DeleteStream",
                   "Resource":"arn:aws:kinesis:us-east-1:%s:stream/%s"}
                ]}""".formatted(ACCOUNT_ID, stream));

        assertAccessDenied(signed(accessKeyId, "kinesis")
                .header("X-Amz-Target", "Kinesis_20131202.DeleteStream")
                .contentType("application/x-amz-cbor-1.1")
                .body(cbor("""
                        {"StreamName": "%s"}
                        """.formatted(stream)))
        .when()
                .post("/")
        .then(), "kinesis:DeleteStream");
    }

    private static void assertAccessDenied(ValidatableResponse response, String action) throws IOException {
        JsonNode error = CBOR_MAPPER.readTree(response.statusCode(403).extract().asByteArray());
        assertEquals("AccessDeniedException", error.path("__type").asText());
        assertTrue(error.path("message").asText().contains(action), error.toString());
    }

    private static String sendBatch(String queueUrl) {
        return """
                {"QueueUrl": "%s", "Entries": [{"Id": "one", "MessageBody": "hello"}]}
                """.formatted(queueUrl);
    }

    private static String sendMessage(String queueUrl) {
        return """
                {"QueueUrl": "%s", "MessageBody": "hello"}
                """.formatted(queueUrl);
    }

    private static String getItem(String table, String id) {
        return """
                {"TableName": "%s", "Key": {"id": {"S": "%s"}}}
                """.formatted(table, id);
    }

    private static String queueArn(String queueUrl) {
        return given()
                .header("Authorization", auth(ACCOUNT_ID, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "GetQueueAttributes")
                .formParam("Version", "2012-11-05")
                .formParam("QueueUrl", queueUrl)
                .formParam("AttributeName.1", "QueueArn")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath()
                .setRoot("GetQueueAttributesResponse.GetQueueAttributesResult")
                .getString("Attribute.find { it.Name == 'QueueArn' }.Value");
    }

    private String createTable() {
        String table = "rpcv2-" + UUID.randomUUID();
        dynamoDbAsRoot("CreateTable", """
                {"TableName": "%s", "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                 "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                 "BillingMode": "PAY_PER_REQUEST"}
                """.formatted(table));
        cleanup.register(() -> dynamoDbAsRoot("DeleteTable", """
                {"TableName": "%s"}
                """.formatted(table)));
        return table;
    }

    private static void dynamoDbAsRoot(String operation, String body) {
        given()
                .header("Authorization", auth(ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + operation)
                .contentType("application/x-amz-json-1.0")
                .body(body)
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private String createQueue() {
        String queueUrl = given()
                .header("Authorization", auth(ACCOUNT_ID, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateQueue")
                .formParam("Version", "2012-11-05")
                .formParam("QueueName", "rpcv2-" + UUID.randomUUID())
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        cleanup.register(() -> given()
                .header("Authorization", auth(ACCOUNT_ID, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteQueue")
                .formParam("Version", "2012-11-05")
                .formParam("QueueUrl", queueUrl)
                .when().post("/").then().statusCode(200));
        return queueUrl;
    }

    private static String allow(String action) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"%s","Resource":"*"}
                ]}""".formatted(action);
    }

    private String userWithPolicy(String document) {
        String userName = "rpcv2-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ACCOUNT_ID, "iam"), userName).accessKeyId();
        given()
                .header("Authorization", auth(ACCOUNT_ID, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PutUserPolicy")
                .formParam("Version", "2010-05-08")
                .formParam("UserName", userName)
                .formParam("PolicyName", "only")
                .formParam("PolicyDocument", document)
        .when()
                .post("/")
        .then()
                .statusCode(200);
        return accessKeyId;
    }

    private static ValidatableResponse sqsRpcV2(String accessKeyId, String signingScope, String operation,
                                                String jsonBody) {
        return sqsRpcV2(signed(accessKeyId, signingScope).header("smithy-protocol", "rpc-v2-cbor"),
                operation, jsonBody);
    }

    private static RequestSpecification signed(String accessKeyId, String signingScope) {
        return given().header("Authorization", auth(accessKeyId, signingScope));
    }

    private static ValidatableResponse sqsRpcV2(RequestSpecification request, String operation, String jsonBody) {
        return rpcV2(request, "AmazonSQS", operation, jsonBody);
    }

    private static ValidatableResponse rpcV2(RequestSpecification request, String serviceId, String operation,
                                             String jsonBody) {
        return request
                .contentType("application/cbor")
                .accept("application/cbor")
                .body(cbor(jsonBody))
        .when()
                .post("/service/" + serviceId + "/operation/" + operation)
        .then();
    }

    private static byte[] cbor(String json) {
        try {
            return CBOR_MAPPER.writeValueAsBytes(new ObjectMapper().readTree(json));
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
