package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DynamoDbQueryPartitionRangeTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
        service.createTable("Orders",
                List.of(new KeySchemaElement("pk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                List.of(new AttributeDefinition("pk", "S"), new AttributeDefinition("sk", "S"),
                        new AttributeDefinition("gpk", "S")), 5L, 5L,
                List.of(new GlobalSecondaryIndex("ByGroup",
                        List.of(new KeySchemaElement("gpk", "HASH"), new KeySchemaElement("sk", "RANGE")),
                        null, "ALL", List.of())), REGION);
    }

    @Test
    void expressionQueryKeepsOverlappingAndEscapedPartitionsDistinct() {
        for (String pk : List.of("user", "user2", "user#", "user\\", "user\\#", "user#\\")) {
            for (String sk : List.of("a", "#b", "\\c", "\ufffflast")) {
                service.putItem("Orders", item(pk, sk), REGION);
            }
        }
        for (String pk : List.of("user", "user2", "user#", "user\\", "user\\#", "user#\\")) {
            DynamoDbService.QueryResult result = query(pk, "pk = :p", null, null, null);
            assertEquals(4, result.items().size());
            assertThat(result.items().stream().map(i -> i.path("pk").path("S").asText()).toList(),
                    contains(pk, pk, pk, pk));
            assertThat(sortKeys(result), contains("#b", "\\c", "a", "\ufffflast"));
        }
        assertThat(query("missing", "pk = :p", null, null, null).items(), empty());
    }

    @Test
    void expressionQueryPreservesSortConditions() {
        populate();
        assertThat(sortKeys(query("user", "pk = :p AND begins_with(sk, :prefix)", null, null, null)),
                contains("a1", "a2", "a3"));
        assertThat(sortKeys(query("user", "pk = :p AND sk BETWEEN :lo AND :hi", null, null, null)),
                contains("a2", "a3", "b1"));
        assertThat(sortKeys(query("user", "pk = :p AND sk < :lo", null, null, null)), contains("a1"));
    }

    @Test
    void queryPaginatesThePartitionInBothDirections() {
        populate();
        for (boolean forward : List.of(true, false)) {
            DynamoDbService.QueryResult first = query("user", "pk = :p", forward, 2, null);
            assertNotNull(first.lastEvaluatedKey());
            DynamoDbService.QueryResult second = query("user", "pk = :p", forward, 2, first.lastEvaluatedKey());
            assertNotNull(second.lastEvaluatedKey());
            assertThat(sortKeys(first), forward ? contains("a1", "a2") : contains("b1", "a3"));
            assertThat(sortKeys(second), forward ? contains("a3", "b1") : contains("a2", "a1"));
            assertThat(query("user", "pk = :p", forward, 2, second.lastEvaluatedKey()).items(), empty());
        }
    }

    @Test
    void legacyQueryKeepsEscapedPartitionsAndSortConditions() throws Exception {
        for (String pk : List.of("user", "user2", "user#", "user\\")) {
            service.putItem("Orders", item(pk, "a1"), REGION);
            service.putItem("Orders", item(pk, "b1"), REGION);
        }
        for (String pk : List.of("user", "user#", "user\\")) {
            ObjectNode conditions = mapper.createObjectNode();
            conditions.putObject("pk").put("ComparisonOperator", "EQ")
                    .putArray("AttributeValueList").addObject().put("S", pk);
            conditions.set("sk", mapper.readTree("""
                    {"ComparisonOperator":"BEGINS_WITH","AttributeValueList":[{"S":"a"}]}
                    """));
            DynamoDbService.QueryResult result = service.query("Orders", conditions, null, null, null, null, REGION);
            assertThat(sortKeys(result), contains("a1"));
            assertEquals(pk, result.items().getFirst().path("pk").path("S").asText());
        }
    }

    @Test
    void hashOnlyQuerySelectsTheExactEscapedKeyInBothForms() {
        service.createTable("Users", List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")), 5L, 5L, REGION);
        for (String pk : List.of("user", "user2", "user#", "user\\")) {
            ObjectNode item = mapper.createObjectNode();
            item.putObject("pk").put("S", pk);
            service.putItem("Users", item, REGION);
        }
        for (String pk : List.of("user", "user#", "user\\", "missing")) {
            ObjectNode values = mapper.createObjectNode();
            values.putObject(":p").put("S", pk);
            ObjectNode conditions = mapper.createObjectNode();
            conditions.putObject("pk").put("ComparisonOperator", "EQ")
                    .putArray("AttributeValueList").addObject().put("S", pk);
            List<JsonNode> expected = pk.equals("missing") ? List.of() : List.of(values.get(":p"));
            for (DynamoDbService.QueryResult result : List.of(
                    service.query("Users", null, values, "pk = :p", null, null, REGION),
                    service.query("Users", conditions, null, null, null, null, REGION))) {
                assertEquals(expected, result.items().stream().map(i -> i.get("pk")).toList());
            }
        }
    }

    @Test
    void indexQueryStillReadsMatchingItemsAcrossBasePartitions() {
        populate();
        ObjectNode values = mapper.createObjectNode();
        values.putObject(":p").put("S", "shared");
        DynamoDbService.QueryResult result = service.query("Orders", null, values, "gpk = :p",
                null, null, false, "ByGroup", null, null, REGION);
        assertThat(sortKeys(result), contains("b1", "b1", "a3", "a3", "a2", "a2", "a1", "a1"));
        ObjectNode conditions = mapper.createObjectNode();
        conditions.putObject("gpk").put("ComparisonOperator", "EQ")
                .putArray("AttributeValueList").addObject().put("S", "shared");
        assertEquals(8, service.query("Orders", conditions, null, null, null, null,
                null, "ByGroup", null, null, REGION).items().size());
    }

    private void populate() {
        for (String pk : List.of("user", "user2")) {
            for (String sk : List.of("a1", "a2", "a3", "b1")) {
                service.putItem("Orders", item(pk, sk), REGION);
            }
        }
    }

    private ObjectNode item(String pk, String sk) {
        ObjectNode item = mapper.createObjectNode();
        item.putObject("pk").put("S", pk);
        item.putObject("sk").put("S", sk);
        item.putObject("gpk").put("S", "shared");
        return item;
    }

    private DynamoDbService.QueryResult query(String pk, String expression, Boolean forward,
                                               Integer limit, JsonNode start) {
        ObjectNode values = mapper.createObjectNode();
        values.putObject(":p").put("S", pk);
        if (expression.contains(":prefix")) {
            values.putObject(":prefix").put("S", "a");
        }
        if (expression.contains(":lo")) {
            values.putObject(":lo").put("S", "a2");
        }
        if (expression.contains(":hi")) {
            values.putObject(":hi").put("S", "b1");
        }
        return service.query("Orders", null, values, expression, null, limit, forward,
                null, start, null, REGION);
    }

    private List<String> sortKeys(DynamoDbService.QueryResult result) {
        return result.items().stream().map(i -> i.path("sk").path("S").asText()).toList();
    }
}
