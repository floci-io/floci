package io.floci.aws.services.dynamodb;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.CRC32;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class DynamoDbLegacyIntegrationTest {
    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void missingTableHasAwsErrorAndCrcInsteadOfAnUnrecognizedTarget() {
        Response response = call("20111205", "DescribeTable", "{\"TableName\":\"absent-" + UUID.randomUUID() + "\"}");
        response.then().statusCode(400).body("__type",
                equalTo("com.amazonaws.dynamodb.v20111205#ResourceNotFoundException"));
        CRC32 crc = new CRC32();
        crc.update(response.asString().getBytes(StandardCharsets.UTF_8));
        assertEquals(Long.toUnsignedString(crc.getValue()), response.header("x-amz-crc32"));
        given().header("X-Amz-Target", "DynamoDB_20111205.DescribeTable")
                .contentType("application/json").body("{\"TableName\":\"absent\"}")
                .post("/").then().statusCode(404);
    }

    @Test
    void legacyAndModernTableMetadataShareActualStorage() {
        String name = "legacy-" + UUID.randomUUID();
        call("20120810", "CreateTable", """
                {"TableName":"%s","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """.formatted(name)).then().statusCode(200);
        try {
            call("20111205", "DescribeTable", "{\"TableName\":\"" + name + "\"}").then().statusCode(200)
                    .body("Table.TableName", equalTo(name))
                    .body("Table.KeySchema.HashKeyElement.AttributeName", equalTo("pk"))
                    .body("Table.KeySchema.HashKeyElement.AttributeType", equalTo("S"))
                    .body("Table.KeySchema.RangeKeyElement", nullValue());
            call("20120810", "DescribeTable", "{\"TableName\":\"" + name + "\"}").then().statusCode(200)
                    .body("Table.KeySchema[0].KeyType", equalTo("HASH"))
                    .body("Table.AttributeDefinitions[0].AttributeName", equalTo("pk"));
            call("20111205", "GetItem", "{\"TableName\":\"" + name + "\"}").then().statusCode(400)
                    .body("__type", equalTo("com.amazonaws.dynamodb.v20111205#UnknownOperationException"));
        } finally {
            call("20120810", "DeleteTable", "{\"TableName\":\"" + name + "\"}").then().statusCode(200);
        }
    }

    @Test
    void adapterValidationErrorsUseLegacyNamespaceAndCorrectCrc() {
        Response response = call("20111205", "Query", "{}");
        response.then().statusCode(400)
                .body("__type", equalTo("com.amazon.coral.validate#ValidationException"));
        CRC32 crc = new CRC32();
        crc.update(response.asString().getBytes(StandardCharsets.UTF_8));
        assertEquals(Long.toUnsignedString(crc.getValue()), response.header("x-amz-crc32"));
        call("20120810", "Query", "{}").then().statusCode(400)
                .body("__type", not(containsString("v20111205")));
    }

    private static Response call(String version, String action, String body) {
        return given().header("X-Amz-Target", "DynamoDB_" + version + "." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/");
    }
}
