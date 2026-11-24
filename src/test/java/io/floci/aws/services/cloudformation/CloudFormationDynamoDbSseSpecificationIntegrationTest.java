package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.SSEDescription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SSESpecification} on {@code AWS::DynamoDB::Table} and {@code AWS::DynamoDB::GlobalTable}: "If enabled (true), server-side
 * encryption type is set to KMS and an AWS managed key is used" (AWS::DynamoDB::Table
 * SSESpecification), so DescribeTable reports the {@code SSEDescription} that CreateTable with the
 * same setting gives.
 */
@QuarkusTest
class CloudFormationDynamoDbSseSpecificationIntegrationTest {

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Orders": {
                  "Type": "AWS::DynamoDB::Table",
                  "Properties": {
                    "TableName": "%s",
                    "BillingMode": "PAY_PER_REQUEST",
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "SSESpecification": %s
                  }
                }
              }
            }
            """;

    private static final String GLOBAL_TEMPLATE = """
            {
              "Resources": {
                "Orders": {
                  "Type": "AWS::DynamoDB::GlobalTable",
                  "Properties": {
                    "TableName": "%s",
                    "BillingMode": "PAY_PER_REQUEST",
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "Replicas": [{"Region": "us-east-1"}],
                    "SSESpecification": {"SSEEnabled": true, "SSEType": "KMS"}
                  }
                }
              }
            }
            """;

    private CloudFormationClient cfn;
    private DynamoDbClient dynamoDb;
    private String stackName;
    private String tableName;

    @BeforeEach
    void setUp() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        dynamoDb = AwsTestClients.create(DynamoDbClient.builder(), "test", Region.US_EAST_1);
        String suffix = Long.toString(System.nanoTime(), 36);
        stackName = "cfn-ddb-sse-" + suffix;
        tableName = "sse-table-" + suffix;
    }

    @AfterEach
    void cleanUp() {
        try {
            CfnStackWaits.deleteStack(cfn, stackName);
        } finally {
            cfn.close();
            dynamoDb.close();
        }
    }

    @Test
    void sseEnabledEncryptsTheTableWithTheAwsManagedKey() {
        CfnStackWaits.createStack(cfn, stackName, TEMPLATE.formatted(tableName, "{\"SSEEnabled\": true}"),
                "CREATE_COMPLETE");

        SSEDescription sse = dynamoDb.describeTable(r -> r.tableName(tableName)).table().sseDescription();

        assertEquals("ENABLED", sse.statusAsString());
        assertEquals("KMS", sse.sseTypeAsString());
        assertTrue(sse.kmsMasterKeyArn().endsWith(":key/aws-managed-dynamodb"), sse.kmsMasterKeyArn());
    }

    @Test
    void kmsMasterKeyIdIsTheKeyTheTableReports() {
        String key = "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";
        CfnStackWaits.createStack(cfn, stackName, TEMPLATE.formatted(tableName,
                "{\"SSEEnabled\": true, \"SSEType\": \"KMS\", \"KMSMasterKeyId\": \"" + key + "\"}"),
                "CREATE_COMPLETE");

        SSEDescription sse = dynamoDb.describeTable(r -> r.tableName(tableName)).table().sseDescription();

        assertEquals(key, sse.kmsMasterKeyArn());
    }

    @Test
    void globalTableSseEnabledEncryptsTheTableWithTheAwsManagedKey() {
        CfnStackWaits.createStack(cfn, stackName, GLOBAL_TEMPLATE.formatted(tableName), "CREATE_COMPLETE");

        SSEDescription sse = dynamoDb.describeTable(r -> r.tableName(tableName)).table().sseDescription();

        assertEquals("ENABLED", sse.statusAsString());
        assertEquals("KMS", sse.sseTypeAsString());
        assertTrue(sse.kmsMasterKeyArn().endsWith(":key/aws-managed-dynamodb"), sse.kmsMasterKeyArn());
    }

    @Test
    void unknownSseTypeFailsTheStackAndLeavesNoTable() {
        CfnStackWaits.createStack(cfn, stackName, TEMPLATE.formatted(tableName,
                "{\"SSEEnabled\": true, \"SSEType\": \"DES\"}"), "ROLLBACK_COMPLETE");

        String reason = CfnStackWaits.failedResourceReason(stackName);
        assertTrue(reason.contains("Value 'DES' at 'sSESpecification.sSEType' failed to satisfy constraint"), reason);
        assertThrows(ResourceNotFoundException.class, () -> dynamoDb.describeTable(r -> r.tableName(tableName)));
    }
}
