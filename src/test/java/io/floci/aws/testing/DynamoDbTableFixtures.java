package io.floci.aws.testing;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** SDK table fixtures shared by DynamoDB integration tests. */
public final class DynamoDbTableFixtures {

    private DynamoDbTableFixtures() {
    }

    /** Creates an on-demand table with a string partition key named {@code pk}, returning its ARN. */
    public static String createKeyedTable(DynamoDbClient client, String name) {
        return client.createTable(b -> b.tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder()
                        .attributeName("pk").attributeType(ScalarAttributeType.S).build()))
                .tableDescription().tableArn();
    }
}
