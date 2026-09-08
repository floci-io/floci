package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DynamoDbKeyConditionParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolvesSimplePartitionKeyEquality() {
        assertEquals("USER_alice", DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, json("{\":v\":{\"S\":\"USER_alice\"}}"), "pk"));
    }

    @Test
    void resolvesPartitionKeyEqualityAlongsideASortKeyCondition() {
        assertEquals("USER_alice", DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v AND sk > :s", null,
                json("{\":v\":{\"S\":\"USER_alice\"},\":s\":{\"S\":\"2020\"}}"), "pk"));
    }

    @Test
    void resolvesAnAliasedPartitionKeyThroughExpressionAttributeNames() {
        assertEquals("USER_alice", DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "#p = :v", json("{\"#p\":\"pk\"}"),
                json("{\":v\":{\"S\":\"USER_alice\"}}"), "pk"));
    }

    @Test
    void resolvesNumericAndBinaryPartitionKeys() {
        assertEquals("42", DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, json("{\":v\":{\"N\":\"42\"}}"), "pk"));
        assertEquals("YWJj", DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, json("{\":v\":{\"B\":\"YWJj\"}}"), "pk"));
    }

    @Test
    void returnsNullWhenThePartitionKeyIsNotPinnedByEquality() {
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "sk > :s", null, json("{\":s\":{\"S\":\"2020\"}}"), "pk"));
    }

    @Test
    void returnsNullForABeginsWithConditionOnThePartitionKey() {
        // begins_with is a valid sort-key condition but never an equality; treating it as
        // one would pin the leading key to a prefix and wrongly allow a whole partition range.
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "begins_with(pk, :v)", null, json("{\":v\":{\"S\":\"USER_\"}}"), "pk"));
    }

    @Test
    void returnsNullWhenThePartitionKeyEqualityIsUnderAnOr() {
        // A top-level OR means the request is not constrained to a single partition value.
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :a OR pk = :b", null,
                json("{\":a\":{\"S\":\"USER_alice\"},\":b\":{\"S\":\"USER_bob\"}}"), "pk"));
    }

    @Test
    void returnsNullOnUnparseableOrMissingInput() {
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = = :v", null, json("{\":v\":{\"S\":\"x\"}}"), "pk"));
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                null, null, json("{}"), "pk"));
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, null, "pk"));
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, json("{\":other\":{\"S\":\"x\"}}"), "pk"));
        assertNull(DynamoDbKeyConditionParser.partitionKeyEqualityValue(
                "pk = :v", null, json("{\":v\":{\"S\":\"x\"}}"), null));
    }
}
