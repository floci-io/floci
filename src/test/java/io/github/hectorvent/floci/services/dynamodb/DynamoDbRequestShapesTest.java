package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamoDbRequestShapesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void stringMemberNamesTheJsonTokenItGot() {
        assertRejected("Scan", "{\"TableName\":\"t\",\"IndexName\":5}", "NUMBER_VALUE cannot be converted to String");
        assertRejected("Scan", "{\"TableName\":\"t\",\"IndexName\":5.5}", "DECIMAL_VALUE cannot be converted to String");
        assertRejected("Scan", "{\"TableName\":\"t\",\"IndexName\":1e3}", "DECIMAL_VALUE cannot be converted to String");
        assertRejected("Scan", "{\"TableName\":\"t\",\"IndexName\":true}", "TRUE_VALUE cannot be converted to String");
        assertRejected("Scan", "{\"TableName\":\"t\",\"IndexName\":false}", "FALSE_VALUE cannot be converted to String");
        assertRejected("Scan", "{\"TableName\":\"t\",\"IndexName\":{}}", "Start of structure or map found where not expected");
        assertRejected("Scan", "{\"TableName\":\"t\",\"IndexName\":[]}", "UnknownError");
    }

    @Test
    void numericMembersAcceptAnyNumberAndNameTheirJavaType() {
        assertAccepted("Scan", "{\"TableName\":\"t\",\"Limit\":5.5}");
        assertAccepted("Scan", "{\"TableName\":\"t\",\"Limit\":99999999999}");
        assertRejected("Scan", "{\"TableName\":\"t\",\"Limit\":\"5\"}", "STRING_VALUE cannot be converted to Integer");
        assertRejected("Scan", "{\"TableName\":\"t\",\"Limit\":false}", "FALSE_VALUE cannot be converted to Integer");
        assertRejected("UpdateTable",
                "{\"TableName\":\"t\",\"ProvisionedThroughput\":{\"ReadCapacityUnits\":\"5\",\"WriteCapacityUnits\":5}}",
                "STRING_VALUE cannot be converted to Long");
        assertRejected("UpdateTableReplicaAutoScaling", "{\"TableName\":\"t\",\"ProvisionedWriteCapacityAutoScalingUpdate\":"
                + "{\"ScalingPolicyUpdate\":{\"TargetTrackingScalingPolicyConfiguration\":{\"TargetValue\":\"50\"}}}}",
                "STRING_VALUE cannot be converted to Double");
        assertAccepted("ListBackups", "{\"TimeRangeLowerBound\":1577836800}");
        assertRejected("ListBackups", "{\"TimeRangeLowerBound\":\"2020-01-01T00:00:00Z\"}",
                "STRING_VALUE cannot be converted to Date");
    }

    @Test
    void booleanMemberAcceptsItsWordsAsStrings() {
        assertAccepted("Scan", "{\"TableName\":\"t\",\"ConsistentRead\":\"false\"}");
        assertAccepted("Scan", "{\"TableName\":\"t\",\"ConsistentRead\":\"TRUE\"}");
        assertRejected("Scan", "{\"TableName\":\"t\",\"ConsistentRead\":\"abc\"}", "Unexpected token received from parser");
        assertRejected("Scan", "{\"TableName\":\"t\",\"ConsistentRead\":0}", "NUMBER_VALUE cannot be converted to Boolean");
    }

    @Test
    void blobMemberTakesOnlyAString() {
        assertRejected("PutItem", "{\"TableName\":\"t\",\"Item\":{\"a\":{\"B\":5}}}",
                "only base-64-encoded strings are convertible to bytes");
        assertRejected("PutItem", "{\"TableName\":\"t\",\"Item\":{\"a\":{\"BS\":[true]}}}",
                "only base-64-encoded strings are convertible to bytes");
    }

    @Test
    void containerMessageDependsOnWhereTheValueSits() {
        assertRejected("UpdateTable", "{\"TableName\":\"t\",\"ProvisionedThroughput\":\"a\"}", "Unexpected field type");
        assertRejected("UpdateTable", "{\"TableName\":\"t\",\"ProvisionedThroughput\":[]}", "UnknownError");
        assertRejected("Scan", "{\"TableName\":\"t\",\"AttributesToGet\":\"a\"}", "Unexpected field type");
        assertRejected("Scan", "{\"TableName\":\"t\",\"AttributesToGet\":{}}", "Start of structure or map found where not expected");
        assertRejected("Scan", "{\"TableName\":\"t\",\"ExpressionAttributeNames\":5}", "Unexpected field type");
        assertRejected("PutItem", "{\"TableName\":\"t\",\"Item\":{\"a\":5}}", "Unexpected value type in payload");
        assertRejected("UpdateTable", "{\"TableName\":\"t\",\"GlobalSecondaryIndexUpdates\":[\"a\"]}",
                "Unexpected value type in payload");
        assertRejected("BatchWriteItem", "{\"RequestItems\":{\"t\":5}}", "UnknownError");
        assertRejected("BatchGetItem", "{\"RequestItems\":{\"t\":{\"Keys\":[5]}}}", "UnknownError");
    }

    @Test
    void nestedMembersAreCheckedToo() {
        assertRejected("UpdateTable", "{\"TableName\":\"t\",\"GlobalSecondaryIndexUpdates\":[{\"Delete\":{\"IndexName\":5}}]}",
                "NUMBER_VALUE cannot be converted to String");
        assertRejected("CreateTable", "{\"TableName\":\"t\",\"BillingMode\":5}", "NUMBER_VALUE cannot be converted to String");
        assertRejected("Scan", "{\"TableName\":\"t\",\"ExclusiveStartKey\":{\"pk\":{\"S\":123}}}",
                "NUMBER_VALUE cannot be converted to String");
    }

    @Test
    void firstBadMemberInBodyOrderWins() {
        assertRejected("Scan", "{\"TableName\":\"t\",\"Limit\":\"5\",\"IndexName\":5}", "STRING_VALUE cannot be converted to Integer");
        assertRejected("Scan", "{\"TableName\":\"t\",\"IndexName\":5,\"Limit\":\"5\"}", "NUMBER_VALUE cannot be converted to String");
    }

    @Test
    void nullAndUnknownMembersAreLeftToTheOperation() {
        assertAccepted("Scan", "{\"TableName\":\"t\",\"IndexName\":null,\"Limit\":null,\"Unknown\":5}");
        assertAccepted("Scan", "{\"TableName\":\"t\",\"AttributesToGet\":[null]}");
        assertAccepted("NoSuchOperation", "{\"TableName\":5}");
    }

    private static void assertRejected(String action, String body, String message) {
        AwsException error = assertThrows(AwsException.class, () -> DynamoDbRequestShapes.check(action, json(body)));
        assertEquals("SerializationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(message, error.getMessage());
    }

    private static void assertAccepted(String action, String body) {
        assertDoesNotThrow(() -> DynamoDbRequestShapes.check(action, json(body)));
    }

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }
}
