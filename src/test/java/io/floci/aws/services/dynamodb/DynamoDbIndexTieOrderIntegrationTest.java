package io.floci.aws.services.dynamodb;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class DynamoDbIndexTieOrderIntegrationTest {

    private static final List<String> NUMBERS = List.of("-1", "7", "0", "0.5", "-0.5", "100", "-1.5", "1E-130",
            "-1E+125", "9.9999999999999999999999999999999999999E+125", "-12345678901234567890123456789012345678",
            "1234567890123456789012345678901234567.8");
    private static final List<String> NUMBERS_IN_HASH_ORDER = List.of("-1", "7",
            "-12345678901234567890123456789012345678", "9.9999999999999999999999999999999999999E+125", "100", "1E-130",
            "-1E+125", "-1.5", "0.5", "-0.5", "1234567890123456789012345678901234567.8", "0");

    @Test
    void queryReturnsIndexTiesInTheOrderOfTheirTableKeyHash() {
        try (DynamoDbClient client = client()) {
            String table = createTable(client, ScalarAttributeType.S, ScalarAttributeType.S);
            for (String sk : List.of("row-d", "row-f", "row-a", "row-c", "row-e", "row-b")) {
                client.putItem(r -> r.tableName(table)
                        .item(Map.of("pk", text("tie"), "sk", text(sk), "l", text("t"), "g", text("tie"))));
            }
            List<String> lsi = List.of("row-b", "row-a", "row-c", "row-f", "row-d", "row-e");
            List<String> gsi = List.of("row-a", "row-f", "row-b", "row-e", "row-d", "row-c");
            Function<AttributeValue, String> sortKey = AttributeValue::s;

            assertEquals(lsi, query(client, table, "l-all", "pk", text("tie"), true, null, "sk", sortKey));
            assertEquals(lsi.reversed(), query(client, table, "l-all", "pk", text("tie"), false, null, "sk", sortKey));
            assertEquals(gsi, query(client, table, "g-all", "g", text("tie"), true, null, "sk", sortKey));
            assertEquals(gsi.reversed(), query(client, table, "g-all", "g", text("tie"), false, null, "sk", sortKey));
            assertEquals(lsi, query(client, table, "l-all", "pk", text("tie"), true, 1, "sk", sortKey));
            assertEquals(gsi.reversed(), query(client, table, "g-all", "g", text("tie"), false, 2, "sk", sortKey));
            assertEquals(lsi, client.executeStatement(r -> r.statement(
                            "SELECT sk FROM \"" + table + "\".\"l-all\" WHERE pk = 'tie' AND l = 't'"))
                    .items().stream().map(item -> item.get("sk").s()).toList());
        }
    }

    @Test
    void queryOrdersTiedNumberKeysByTheHashOfTheirDigits() {
        try (DynamoDbClient client = client()) {
            String table = createTable(client, ScalarAttributeType.S, ScalarAttributeType.N);
            for (String number : NUMBERS) {
                client.putItem(r -> r.tableName(table)
                        .item(Map.of("pk", text("tie"), "sk", number(number), "l", text("t"), "g", text("g"))));
            }
            Function<AttributeValue, String> sortKey = value -> plain(value.n());

            assertEquals(plain(NUMBERS_IN_HASH_ORDER),
                    query(client, table, "l-all", "pk", text("tie"), true, null, "sk", sortKey));
            assertEquals(plain(List.of("100", "-0.5", "-1", "7", "-12345678901234567890123456789012345678", "0.5",
                            "9.9999999999999999999999999999999999999E+125", "0", "1E-130",
                            "1234567890123456789012345678901234567.8", "-1E+125", "-1.5")),
                    query(client, table, "g-all", "g", text("g"), true, null, "sk", sortKey));
        }
    }

    @Test
    void queryOrdersGsiTiesOfATableWithoutSortKeyByThePartitionKeyHash() {
        try (DynamoDbClient client = client()) {
            String table = createTable(client, ScalarAttributeType.N, null);
            for (String number : NUMBERS) {
                client.putItem(r -> r.tableName(table).item(Map.of("pk", number(number), "l", text("t"), "g", text("g"))));
            }

            assertEquals(plain(NUMBERS_IN_HASH_ORDER),
                    query(client, table, "g-all", "g", text("g"), true, null, "pk", value -> plain(value.n())));
        }
    }

    @Test
    void queryOrdersGsiTiesByTheHashOfThePartitionKeyBytesThenTheSortKeyBytes() {
        try (DynamoDbClient client = client()) {
            String table = createTable(client, ScalarAttributeType.B, ScalarAttributeType.S);
            for (String pk : List.of("00", "0000", "ff", "010203")) {
                for (String sk : List.of("a", "é", "日本", "😀")) {
                    client.putItem(r -> r.tableName(table).item(Map.of("pk", bytes(pk), "sk", text(sk),
                            "l", text("t"), "g", text("g"))));
                }
            }
            List<String> rows = new ArrayList<>();
            for (Map<String, AttributeValue> item : queryItems(client, table, "g-all", "g", text("g"), true, null)) {
                rows.add(HexFormat.of().formatHex(item.get("pk").b().asByteArray()) + "|" + item.get("sk").s());
            }

            assertEquals(List.of("ff|é", "ff|😀", "0000|a", "010203|日本", "0000|日本", "0000|é", "00|a", "010203|é",
                    "00|😀", "ff|a", "010203|a", "00|é", "0000|😀", "00|日本", "ff|日本", "010203|😀"), rows);
        }
    }

    private static DynamoDbClient client() {
        return AwsTestClients.create(DynamoDbClient.builder(), "test", Region.US_EAST_1);
    }

    private static String createTable(DynamoDbClient client, ScalarAttributeType pkType, ScalarAttributeType skType) {
        String table = "tie-order-" + UUID.randomUUID();
        List<AttributeDefinition> attributes = new ArrayList<>(List.of(definition("pk", pkType),
                definition("l", ScalarAttributeType.S), definition("g", ScalarAttributeType.S)));
        List<KeySchemaElement> key = new ArrayList<>(List.of(element("pk", KeyType.HASH)));
        CreateTableRequest.Builder request = CreateTableRequest.builder().tableName(table)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName("g-all")
                        .keySchema(element("g", KeyType.HASH), element("l", KeyType.RANGE))
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build());
        if (skType != null) {
            attributes.add(definition("sk", skType));
            key.add(element("sk", KeyType.RANGE));
            request.localSecondaryIndexes(LocalSecondaryIndex.builder().indexName("l-all")
                    .keySchema(element("pk", KeyType.HASH), element("l", KeyType.RANGE))
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build());
        }
        client.createTable(request.attributeDefinitions(attributes).keySchema(key).build());
        return table;
    }

    private static List<String> query(DynamoDbClient client, String table, String index, String hashName,
                                      AttributeValue hashValue, boolean forward, Integer limit, String field,
                                      Function<AttributeValue, String> show) {
        return queryItems(client, table, index, hashName, hashValue, forward, limit).stream()
                .map(item -> show.apply(item.get(field)))
                .toList();
    }

    private static List<Map<String, AttributeValue>> queryItems(DynamoDbClient client, String table, String index,
                                                                String hashName, AttributeValue hashValue,
                                                                boolean forward, Integer limit) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> cursor = null;
        do {
            QueryResponse response = client.query(QueryRequest.builder().tableName(table).indexName(index)
                    .keyConditionExpression("#h = :h AND l = :l")
                    .expressionAttributeNames(Map.of("#h", hashName))
                    .expressionAttributeValues(Map.of(":h", hashValue, ":l", text("t")))
                    .scanIndexForward(forward).limit(limit).exclusiveStartKey(cursor).build());
            items.addAll(response.items());
            cursor = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
        } while (cursor != null);
        return items;
    }

    private static List<String> plain(List<String> numbers) {
        return numbers.stream().map(DynamoDbIndexTieOrderIntegrationTest::plain).toList();
    }

    private static String plain(String number) {
        return new BigDecimal(number).stripTrailingZeros().toPlainString();
    }

    private static AttributeDefinition definition(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    private static KeySchemaElement element(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue number(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static AttributeValue bytes(String hex) {
        return AttributeValue.builder().b(SdkBytes.fromByteArray(HexFormat.of().parseHex(hex))).build();
    }
}
