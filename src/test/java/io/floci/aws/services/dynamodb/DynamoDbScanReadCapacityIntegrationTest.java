package io.floci.aws.services.dynamodb;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ReturnConsumedCapacity;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class DynamoDbScanReadCapacityIntegrationTest {

    @Test
    void scanOfAnOnDemandTableReadsFourPartitions() {
        try (DynamoDbClient client = client()) {
            String empty = createTable(client, true, false);
            String tiny = createTable(client, true, true);
            for (String sk : new String[] {"row-1", "row-2", "row-3"}) {
                client.putItem(r -> r.tableName(tiny).item(Map.of("pk", text("k"), "sk", text(sk), "g", text("g"))));
            }

            assertEquals(2.0, scanUnits(client, empty, r -> { }));
            assertEquals(4.0, scanUnits(client, empty, r -> r.consistentRead(true)));
            assertEquals(2.0, scanUnits(client, tiny, r -> { }));
            assertEquals(4.0, scanUnits(client, tiny, r -> r.consistentRead(true)));
            assertEquals(2.0, scanUnits(client, tiny, r -> r.select(Select.COUNT)));
            assertEquals(2.0, scanUnits(client, tiny, r -> r.filterExpression("sk = :v")
                    .expressionAttributeValues(Map.of(":v", text("nope")))));
            assertEquals(2.0, scanUnits(client, tiny, r -> r.indexName("gix")));
            assertEquals(2.0, scanUnits(client, tiny, r -> r.indexName("lix")));
            assertEquals(4.0, scanUnits(client, tiny, r -> r.indexName("lix").consistentRead(true)));
            assertEquals(3.5, scanUnits(client, tiny, r -> r.limit(1)));
            assertEquals(2.5, scanUnits(client, tiny, r -> r.limit(2)));
            assertEquals(2.0, statementUnits(client, "SELECT * FROM \"" + tiny + "\"", false));
            assertEquals(4.0, statementUnits(client, "SELECT * FROM \"" + tiny + "\"", true));
            assertEquals(2.0, statementUnits(client, "SELECT * FROM \"" + tiny + "\".\"gix\"", false));
            assertEquals(2.0, statementUnits(client, "SELECT * FROM \"" + tiny + "\" WHERE x = 1", false));
            assertEquals(2.0, statementUnits(client, "SELECT * FROM \"" + empty + "\"", false));
        }
    }

    @Test
    void scanChargesEachPartitionItsOwnBlocks() {
        try (DynamoDbClient client = client()) {
            String big = createTable(client, true, false);
            for (String sk : new String[] {"b1", "b2", "b3"}) {
                client.putItem(r -> r.tableName(big).item(Map.of("pk", text("k"), "sk", text(sk),
                        "pad", text("x".repeat(3500)))));
            }
            String many = createTable(client, true, false);
            for (int i = 0; i < 12; i++) {
                String pk = "m" + i;
                client.putItem(r -> r.tableName(many).item(Map.of("pk", text(pk), "sk", text("x"),
                        "pad", text("y".repeat(1000)))));
            }

            assertEquals(3.0, scanUnits(client, big, r -> { }));
            assertEquals(6.0, scanUnits(client, big, r -> r.consistentRead(true)));
            assertEquals(2.5, scanUnits(client, many, r -> { }));
            assertEquals(5.0, scanUnits(client, many, r -> r.consistentRead(true)));
            assertEquals(3.0, statementUnits(client, "SELECT * FROM \"" + big + "\"", false));
            assertEquals(2.5, statementUnits(client, "SELECT * FROM \"" + many + "\"", false));
            String severalKeys = "SELECT * FROM \"" + many + "\" WHERE pk IN ['m0', 'm1']";
            assertEquals(statementUnits(client, severalKeys, false),
                    statementUnits(client, severalKeys + " ORDER BY pk", false));
        }
    }

    @Test
    void scanOfAProvisionedTableReadsOnePartition() {
        try (DynamoDbClient client = client()) {
            String table = createTable(client, false, false);
            for (String sk : new String[] {"row-1", "row-2", "row-3"}) {
                client.putItem(r -> r.tableName(table).item(Map.of("pk", text("k"), "sk", text(sk))));
            }

            assertEquals(0.5, scanUnits(client, table, r -> { }));
            assertEquals(1.0, scanUnits(client, table, r -> r.consistentRead(true)));
            assertEquals(0.5, statementUnits(client, "SELECT * FROM \"" + table + "\"", false));
        }
    }

    private static DynamoDbClient client() {
        return AwsTestClients.create(DynamoDbClient.builder(), "test", Region.US_EAST_1);
    }

    private static String createTable(DynamoDbClient client, boolean onDemand, boolean indexes) {
        String table = "scan-capacity-" + UUID.randomUUID();
        CreateTableRequest.Builder request = CreateTableRequest.builder().tableName(table)
                .keySchema(element("pk", KeyType.HASH), element("sk", KeyType.RANGE));
        if (onDemand) {
            request.billingMode(BillingMode.PAY_PER_REQUEST);
        } else {
            request.provisionedThroughput(ProvisionedThroughput.builder()
                    .readCapacityUnits(1L).writeCapacityUnits(1L).build());
        }
        if (indexes) {
            Projection all = Projection.builder().projectionType(ProjectionType.ALL).build();
            request.attributeDefinitions(definition("pk"), definition("sk"), definition("g"))
                    .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName("gix")
                            .keySchema(element("g", KeyType.HASH)).projection(all).build())
                    .localSecondaryIndexes(LocalSecondaryIndex.builder().indexName("lix")
                            .keySchema(element("pk", KeyType.HASH), element("g", KeyType.RANGE))
                            .projection(all).build());
        } else {
            request.attributeDefinitions(definition("pk"), definition("sk"));
        }
        client.createTable(request.build());
        return table;
    }

    private static double scanUnits(DynamoDbClient client, String table, Consumer<ScanRequest.Builder> options) {
        double units = 0;
        Map<String, AttributeValue> cursor = null;
        do {
            ScanRequest.Builder request = ScanRequest.builder().tableName(table)
                    .returnConsumedCapacity(ReturnConsumedCapacity.TOTAL).exclusiveStartKey(cursor);
            options.accept(request);
            ScanResponse response = client.scan(request.build());
            units += response.consumedCapacity().capacityUnits();
            cursor = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
        } while (cursor != null);
        return units;
    }

    private static double statementUnits(DynamoDbClient client, String statement, boolean consistentRead) {
        ExecuteStatementResponse response = client.executeStatement(r -> r.statement(statement)
                .consistentRead(consistentRead).returnConsumedCapacity(ReturnConsumedCapacity.TOTAL));
        return response.consumedCapacity().capacityUnits();
    }

    private static AttributeDefinition definition(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }

    private static KeySchemaElement element(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }
}
