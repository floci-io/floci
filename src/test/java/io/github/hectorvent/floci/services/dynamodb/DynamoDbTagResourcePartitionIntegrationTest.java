package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * TagResource validates its ResourceArn against a pattern the emulator owns: botocore gives
 * DynamoDB's {@code ResourceArnString} no pattern at all, only a length range. That pattern pinned
 * the commercial partition, so a Floci deployed against a GovCloud or China region minted table
 * ARNs it then refused to accept back, and tagging a table by its own ARN failed as malformed.
 *
 * <p>The region drives the partition, and it comes from the request's SigV4 credential scope.
 */
@QuarkusTest
class DynamoDbTagResourcePartitionIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";

    /**
     * Tables created here live in the emulator's shared state for the rest of the suite, and the
     * non-commercial ones carry a non-commercial ARN. ResourceExplorer2's cross-service scan
     * asserts that every resource it can see is in the commercial partition, so leaving these
     * behind fails an unrelated test. Every table this class creates is removed again.
     */
    private final List<Map.Entry<String, String>> createdTables = new ArrayList<>();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void deleteCreatedTables() {
        for (Map.Entry<String, String> table : createdTables) {
            given()
                .header("Authorization", auth(table.getKey()))
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType(CONTENT_TYPE)
                .body("{\"TableName\": \"%s\"}".formatted(table.getValue()))
            .when()
                .post("/");
        }
        createdTables.clear();
    }

    private static String auth(String region) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region + "/dynamodb/aws4_request, "
                + "SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=abc";
    }

    private String createTable(String region, String tableName) {
        createdTables.add(Map.entry(region, tableName));
        return given()
            .header("Authorization", auth(region))
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                  "TableName": "%s",
                  "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                  "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                  "BillingMode": "PAY_PER_REQUEST"
                }
                """.formatted(tableName))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("TableDescription.TableArn");
    }

    private static Response tag(String region, String resourceArn) {
        return given()
            .header("Authorization", auth(region))
            .header("X-Amz-Target", "DynamoDB_20120810.TagResource")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceArn": "%s", "Tags": [{"Key": "env", "Value": "test"}]}
                """.formatted(resourceArn))
        .when()
            .post("/");
    }

    /**
     * The round trip: create a table in a region, then tag it by the ARN the emulator gave back.
     * This has to hold in every partition, and it is the case that was broken.
     */
    @ParameterizedTest
    @CsvSource({
            "us-east-1,      arn:aws:dynamodb:",
            "us-gov-west-1,  arn:aws-us-gov:dynamodb:",
            "cn-north-1,     arn:aws-cn:dynamodb:",
            "us-isob-east-1, arn:aws-iso-b:dynamodb:"})
    void tagsATableByItsOwnArnInEveryPartition(String region, String expectedArnPrefix) {
        String tableName = "tag-partition-" + UUID.randomUUID().toString().substring(0, 8);
        String tableArn = createTable(region, tableName);

        org.hamcrest.MatcherAssert.assertThat(tableArn, startsWith(expectedArnPrefix));
        tag(region, tableArn).then().statusCode(200);
    }

    /**
     * A table ARN from another partition is well formed, so it is no longer a ValidationException.
     * It is simply not a resource this region holds, which is what AWS reports too.
     */
    @Test
    void aTableArnFromAnotherPartitionIsNotFoundRatherThanMalformed() {
        String tableName = "tag-foreign-" + UUID.randomUUID().toString().substring(0, 8);
        createTable("us-east-1", tableName);

        tag("us-east-1", "arn:aws-cn:dynamodb:cn-north-1:000000000000:table/" + tableName)
            .then()
                .statusCode(400)
                .body(containsString("ResourceNotFoundException"));
    }

    /** Widening the partition must not loosen the rest of the shape. */
    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:sqs:us-east-1:000000000000:table/Some",
            "arn:aws:dynamodb:us-east-1:0:table/Some",
            "arn:aws:dynamodb:us-east-1:000000000000:stream/Some",
            "arn:notaws:dynamodb:us-east-1:000000000000:table/Some",
            "not-an-arn"})
    void stillRejectsAMalformedArnAsAValidationException(String resourceArn) {
        tag("us-east-1", resourceArn)
            .then()
                .statusCode(400)
                .body(containsString("ValidationException"));
    }
}
