package io.github.hectorvent.floci.services.iam;

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

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Operations AWS authorizes as an action of another name are checked as that action, on the Query
 * and JSON paths alike: a batch call needs the single-item permission AWS documents, and a policy
 * naming the batch operation, which is no IAM action, grants nothing.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamAuthorizedActionEnforcementIntegrationTest {

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static final String ACCOUNT_ID = "000000000000";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aBatchSendIsGrantedBySendMessageOverQuery() {
        String queueUrl = createQueue();
        String accessKeyId = userAllowed("sqs:SendMessage");

        sqsQuery(accessKeyId, "SendMessageBatch", Map.of("QueueUrl", queueUrl,
                "SendMessageBatchRequestEntry.1.Id", "one",
                "SendMessageBatchRequestEntry.1.MessageBody", "hello"))
                .statusCode(200);
    }

    @Test
    void aBatchSendIsGrantedBySendMessageOverJson() {
        String queueUrl = createQueue();
        String accessKeyId = userAllowed("sqs:SendMessage");

        given()
                .header("Authorization", auth(accessKeyId, "sqs"))
                .header("X-Amz-Target", "AmazonSQS.SendMessageBatch")
                .contentType("application/x-amz-json-1.0")
                .body("""
                        {"QueueUrl": "%s", "Entries": [{"Id": "one", "MessageBody": "hello"}]}
                        """.formatted(queueUrl))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void aPolicyNamingTheBatchOperationGrantsNothing() {
        String queueUrl = createQueue();
        String accessKeyId = userAllowed("sqs:SendMessageBatch");

        sqsQuery(accessKeyId, "SendMessageBatch", Map.of("QueueUrl", queueUrl,
                "SendMessageBatchRequestEntry.1.Id", "one",
                "SendMessageBatchRequestEntry.1.MessageBody", "hello"))
                .statusCode(403)
                .body(containsString("sqs:SendMessage"));
    }

    @Test
    void aBatchPublishIsGrantedByPublish() {
        String topicArn = given()
                .header("Authorization", auth(ACCOUNT_ID, "sns"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateTopic")
                .formParam("Version", "2010-03-31")
                .formParam("Name", "authorized-" + UUID.randomUUID())
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");
        cleanup.register(() -> given()
                .header("Authorization", auth(ACCOUNT_ID, "sns"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteTopic")
                .formParam("Version", "2010-03-31")
                .formParam("TopicArn", topicArn)
                .when().post("/").then().statusCode(200));
        String accessKeyId = userAllowed("sns:Publish");

        given()
                .header("Authorization", auth(accessKeyId, "sns"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PublishBatch")
                .formParam("Version", "2010-03-31")
                .formParam("TopicArn", topicArn)
                .formParam("PublishBatchRequestEntries.member.1.Id", "one")
                .formParam("PublishBatchRequestEntries.member.1.Message", "hello")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("PublishBatchResponse.PublishBatchResult.Successful.member.Id", equalTo("one"));
    }

    @Test
    void aTransactGetIsCheckedItemByItemAgainstEachLeadingKey() {
        // AWS checks TransactGetItems as dynamodb:GetItem, and Floci checks each Get that way with
        // the item's own leading key. Authorized as one GetItem instead, the call would carry no
        // dynamodb:LeadingKeys, and a ForAllValues condition would grant every key.
        String table = createTable();
        for (String id : List.of("mine", "theirs")) {
            dynamoDb(ACCOUNT_ID, "PutItem", """
                    {"TableName": "%s", "Item": {"id": {"S": "%s"}}}
                    """.formatted(table, id))
                    .statusCode(200);
        }
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetItem","Resource":"*",
                   "Condition":{"ForAllValues:StringEquals":{"dynamodb:LeadingKeys":["mine"]}}}
                ]}""");

        dynamoDb(accessKeyId, "TransactGetItems", transactGet(table, "mine"))
                .statusCode(200)
                .body("Responses[0].Item.id.S", equalTo("mine"));
        dynamoDb(accessKeyId, "TransactGetItems", transactGet(table, "theirs"))
                // DynamoDB answers a denial with 400 and its AccessDeniedException, as AWS does.
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"))
                .body(containsString("dynamodb:GetItem"));
    }

    @Test
    void aBudgetIsChangedUnderModifyBudgetAndReadUnderViewBudget() {
        String budget = "authorized-" + UUID.randomUUID().toString().substring(0, 8);
        String create = """
                {"AccountId": "%s", "Budget": {"BudgetName": "%s", "BudgetType": "COST", "TimeUnit": "MONTHLY",
                 "BudgetLimit": {"Amount": "100", "Unit": "USD"}}}
                """.formatted(ACCOUNT_ID, budget);
        String named = """
                {"AccountId": "%s", "BudgetName": "%s"}
                """.formatted(ACCOUNT_ID, budget);

        // CreateBudget is no IAM action, so a policy naming it grants nothing.
        budgets(userAllowed("budgets:CreateBudget"), "CreateBudget", create)
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"))
                .body(containsString("budgets:ModifyBudget"));
        budgets(userAllowed("budgets:ModifyBudget"), "CreateBudget", create).statusCode(200);
        cleanup.register(() -> budgets(ACCOUNT_ID, "DeleteBudget", named).statusCode(200));

        String reader = userAllowed("budgets:ViewBudget");
        budgets(reader, "DescribeBudget", named)
                .statusCode(200)
                .body("Budget.BudgetName", equalTo(budget));
        budgets(reader, "DeleteBudget", named)
                .statusCode(400)
                .body("__type", equalTo("AccessDeniedException"))
                .body(containsString("budgets:ModifyBudget"));
    }

    private String createTable() {
        String table = "authorized-" + UUID.randomUUID();
        dynamoDb(ACCOUNT_ID, "CreateTable", """
                {"TableName": "%s", "KeySchema": [{"AttributeName": "id", "KeyType": "HASH"}],
                 "AttributeDefinitions": [{"AttributeName": "id", "AttributeType": "S"}],
                 "BillingMode": "PAY_PER_REQUEST"}
                """.formatted(table))
                .statusCode(200);
        cleanup.register(() -> dynamoDb(ACCOUNT_ID, "DeleteTable", """
                {"TableName": "%s"}
                """.formatted(table))
                .statusCode(200));
        return table;
    }

    private static String transactGet(String table, String id) {
        return """
                {"TransactItems": [{"Get": {"TableName": "%s", "Key": {"id": {"S": "%s"}}}}]}
                """.formatted(table, id);
    }

    private String createQueue() {
        String queueUrl = sqsQuery(ACCOUNT_ID, "CreateQueue",
                Map.of("QueueName", "authorized-" + UUID.randomUUID()))
                .statusCode(200)
                .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
        cleanup.register(() -> sqsQuery(ACCOUNT_ID, "DeleteQueue", Map.of("QueueUrl", queueUrl))
                .statusCode(200));
        return queueUrl;
    }

    private String userAllowed(String action) {
        return userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"%s","Resource":"*"}
                ]}""".formatted(action));
    }

    private String userWithPolicy(String document) {
        String userName = "authorized-" + UUID.randomUUID().toString().substring(0, 8);
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

    private static ValidatableResponse dynamoDb(String accessKeyId, String operation, String body) {
        return given()
                .header("Authorization", auth(accessKeyId, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + operation)
                .contentType("application/x-amz-json-1.0")
                .body(body)
        .when()
                .post("/")
        .then();
    }

    private static ValidatableResponse budgets(String accessKeyId, String operation, String body) {
        return given()
                .header("Authorization", auth(accessKeyId, "budgets"))
                .header("X-Amz-Target", "AWSBudgetServiceGateway." + operation)
                .contentType("application/x-amz-json-1.1")
                .body(body)
        .when()
                .post("/")
        .then();
    }

    private static ValidatableResponse sqsQuery(String accessKeyId, String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", auth(accessKeyId, "sqs"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2012-11-05");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }
}
