package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Kinesis GetRecords and DynamoDB Streams GetRecords name no stream but the one their shard iterator
 * was minted for, and AWS authorizes them against that stream: the Kinesis developer guide's
 * "Controlling access" Example 1 lets {@code kinesis:Get*} on one stream read its records. A
 * statement naming a stream has to bind to it, and a {@code StreamARN} beside the iterator cannot
 * move the check to another stream.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StreamGetRecordsEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String KINESIS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String DYNAMODB_CONTENT_TYPE = "application/x-amz-json-1.0";

    @TestHTTPResource("/")
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theAwsExamplePolicyReadsTheRecordsOfItsStream() {
        String stream = createStreamWithOneRecord();
        String caller = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["kinesis:Get*"],"Resource":["%1$s"]},
                  {"Effect":"Allow","Action":["kinesis:DescribeStreamSummary"],"Resource":["%1$s"]},
                  {"Effect":"Allow","Action":["kinesis:ListStreams"],"Resource":["*"]}
                ]}""".formatted(kinesisArn(stream)));

        String iterator = kinesis(caller, "GetShardIterator", trimHorizon(stream))
                .statusCode(200)
                .extract().path("ShardIterator");
        kinesis(caller, "GetRecords", "{\"ShardIterator\":\"" + iterator + "\"}")
                .statusCode(200)
                .body("Records", hasSize(1));
    }

    @Test
    void aDenyOnAStreamRefusesGetRecordsWithItsIterator() {
        String stream = createStreamWithOneRecord();
        String caller = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kinesis:*","Resource":"*"},
                  {"Effect":"Deny","Action":"kinesis:GetRecords","Resource":"%s"}
                ]}""".formatted(kinesisArn(stream)));

        assertAccessDenied(kinesis(caller, "GetRecords", "{\"ShardIterator\":\"" + iterator(stream) + "\"}"),
                "kinesis:GetRecords");
    }

    @Test
    void aStreamArnBesideTheIteratorDoesNotChooseTheStreamGetRecordsIsCheckedAgainst() {
        String allowed = createStreamWithOneRecord();
        String other = createStreamWithOneRecord();
        String caller = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kinesis:Get*","Resource":"%s"}
                ]}""".formatted(kinesisArn(allowed)));

        assertAccessDenied(kinesis(caller, "GetRecords", """
                {"ShardIterator":"%s","StreamARN":"%s"}""".formatted(iterator(other), kinesisArn(allowed))),
                "kinesis:GetRecords");
    }

    /** As ListShards refuses a StreamARN its NextToken does not belong to. */
    @Test
    void getRecordsRefusesAStreamArnItsIteratorDoesNotBelongTo() {
        String stream = createStreamWithOneRecord();
        String other = createStreamWithOneRecord();

        kinesis(ACCOUNT_ID, "GetRecords", """
                {"ShardIterator":"%s","StreamARN":"%s"}""".formatted(iterator(other), kinesisArn(stream)))
                .statusCode(400)
                .body("__type", equalTo("InvalidArgumentException"));
        kinesis(ACCOUNT_ID, "GetRecords", """
                {"ShardIterator":"%s","StreamARN":"%s"}""".formatted(iterator(stream), kinesisArn(stream)))
                .statusCode(200)
                .body("Records", hasSize(1));
    }

    @Test
    void aDynamoDbStreamsGetRecordsIsCheckedAgainstTheStreamItsIteratorReads() {
        String table = "records-" + unique();
        String streamArn = createTableWithStreamAndOneItem(table);
        String iterator = dynamoDbStreamIterator(streamArn);
        String allowed = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:GetRecords","Resource":"%s"}
                ]}""".formatted(tableArn(table) + "/stream/*"));
        String denied = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"dynamodb:*","Resource":"*"},
                  {"Effect":"Deny","Action":"dynamodb:GetRecords","Resource":"%s"}
                ]}""".formatted(tableArn(table) + "/stream/*"));

        dynamoDb(allowed, "DynamoDBStreams_20120810.GetRecords", "{\"ShardIterator\":\"" + iterator + "\"}")
                .statusCode(200)
                .body("Records", hasSize(1));
        assertAccessDenied(dynamoDb(denied, "DynamoDBStreams_20120810.GetRecords",
                "{\"ShardIterator\":\"" + iterator + "\",\"TableName\":\"" + table + "-public\"}"),
                "dynamodb:GetRecords");
    }

    private static void assertAccessDenied(ValidatableResponse response, String action) {
        response.statusCode(400)
                .body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString(action));
    }

    private String createStreamWithOneRecord() {
        String stream = "records-" + unique();
        kinesis(ACCOUNT_ID, "CreateStream", "{\"StreamName\":\"" + stream + "\",\"ShardCount\":1}")
                .statusCode(200);
        int port = baseUri.getPort();
        cleanup.register(() -> given().port(port)
                .header("Authorization", auth(ACCOUNT_ID, "kinesis"))
                .header("X-Amz-Target", "Kinesis_20131202.DeleteStream")
                .contentType(KINESIS_CONTENT_TYPE)
                .body("{\"StreamName\":\"" + stream + "\"}")
                .when().post("/").then().statusCode(200));
        kinesis(ACCOUNT_ID, "PutRecord", """
                {"StreamName":"%s","Data":"aGVsbG8=","PartitionKey":"pk"}""".formatted(stream))
                .statusCode(200);
        return stream;
    }

    private static String iterator(String stream) {
        return kinesis(ACCOUNT_ID, "GetShardIterator", trimHorizon(stream))
                .statusCode(200)
                .extract().path("ShardIterator");
    }

    private static String trimHorizon(String stream) {
        return """
                {"StreamName":"%s","ShardId":"shardId-000000000000","ShardIteratorType":"TRIM_HORIZON"}"""
                .formatted(stream);
    }

    private static String kinesisArn(String stream) {
        return "arn:aws:kinesis:us-east-1:" + ACCOUNT_ID + ":stream/" + stream;
    }

    private static ValidatableResponse kinesis(String accessKeyId, String operation, String body) {
        return given()
                .header("Authorization", auth(accessKeyId, "kinesis"))
                .header("X-Amz-Target", "Kinesis_20131202." + operation)
                .contentType(KINESIS_CONTENT_TYPE)
                .body(body)
        .when()
                .post("/")
        .then();
    }

    private String createTableWithStreamAndOneItem(String table) {
        String streamArn = dynamoDb(ACCOUNT_ID, "DynamoDB_20120810.CreateTable", """
                {"TableName":"%s","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST",
                 "StreamSpecification":{"StreamEnabled":true,"StreamViewType":"NEW_IMAGE"}}"""
                .formatted(table))
                .statusCode(200)
                .extract().path("TableDescription.LatestStreamArn");
        int port = baseUri.getPort();
        cleanup.register(() -> given().port(port)
                .header("Authorization", auth(ACCOUNT_ID, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body("{\"TableName\":\"" + table + "\"}")
                .when().post("/").then().statusCode(200));
        dynamoDb(ACCOUNT_ID, "DynamoDB_20120810.PutItem", """
                {"TableName":"%s","Item":{"pk":{"S":"one"}}}""".formatted(table))
                .statusCode(200);
        return streamArn;
    }

    private static String dynamoDbStreamIterator(String streamArn) {
        String shardId = dynamoDb(ACCOUNT_ID, "DynamoDBStreams_20120810.DescribeStream",
                "{\"StreamArn\":\"" + streamArn + "\"}")
                .statusCode(200)
                .extract().path("StreamDescription.Shards[0].ShardId");
        return dynamoDb(ACCOUNT_ID, "DynamoDBStreams_20120810.GetShardIterator", """
                {"StreamArn":"%s","ShardId":"%s","ShardIteratorType":"TRIM_HORIZON"}"""
                .formatted(streamArn, shardId))
                .statusCode(200)
                .extract().path("ShardIterator");
    }

    private static String tableArn(String table) {
        return "arn:aws:dynamodb:us-east-1:" + ACCOUNT_ID + ":table/" + table;
    }

    private static ValidatableResponse dynamoDb(String accessKeyId, String target, String body) {
        return given()
                .header("Authorization", auth(accessKeyId, "dynamodb"))
                .header("X-Amz-Target", target)
                .contentType(DYNAMODB_CONTENT_TYPE)
                .body(body)
        .when()
                .post("/")
        .then();
    }

    private String userWithPolicy(String document) {
        String userName = "records-" + unique();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ACCOUNT_ID, "iam"), userName).accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT_ID, "iam"), userName, "only", document);
        return accessKeyId;
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
