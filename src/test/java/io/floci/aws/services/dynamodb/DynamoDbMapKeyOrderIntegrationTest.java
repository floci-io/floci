package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;

import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.call;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.createTable;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.deleteTable;
import static io.floci.aws.services.dynamodb.DynamoDbAttributeOrderFixture.order;
import static io.floci.aws.testing.DynamoDbRequests.ddb;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class DynamoDbMapKeyOrderIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ZBK = "{\"M\":{\"z\":{\"N\":\"1\"},\"b\":{\"N\":\"2\"},\"k\":{\"N\":\"3\"}}}";
    private static final String UNSORTED_ITEM = "{'pk':'k','m':{'z':1,'k':{'z':1,'b':2,'a':3},'j':{'z':1,'b':2,'a':3}},"
            + "'l':[{'z':1,'b':2},{'z':1,'b':2}],'b':{'z':1,'b':2},'n':1}";

    private String table;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createTestTable() {
        table = createTable("map-key-order");
    }

    @AfterEach
    void deleteTestTable() {
        deleteTable(table);
    }

    private JsonNode stored() {
        byte[] body = ddb("GetItem", "{\"TableName\":\"" + table + "\",\"ConsistentRead\":true,"
                + "\"Key\":{\"pk\":{\"S\":\"k\"}}}").extract().asByteArray();
        try {
            return MAPPER.readTree(body).get("Item");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String mapOrder(JsonNode node) {
        return order(node.get("M"));
    }

    private void put(String item) {
        call("PutItem", asciiJson("{\"TableName\":\"" + table + "\",\"Item\":" + item + "}"));
    }

    // The test client sends a String body as ISO-8859-1.
    private static String asciiJson(String json) {
        StringBuilder ascii = new StringBuilder();
        json.chars().forEach(c -> {
            if (c < 128) {
                ascii.append((char) c);
            } else {
                ascii.append(String.format("\\u%04x", c));
            }
        });
        return ascii.toString();
    }

    private void update(String expression, String values) {
        String valuesMember = values == null ? "" : ",\"ExpressionAttributeValues\":" + values;
        call("UpdateItem", "{\"TableName\":\"" + table + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},"
                + "\"UpdateExpression\":\"" + expression + "\"" + valuesMember + "}");
    }

    private void partiQL(String statement) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("Statement", statement.replace("$T", "\"" + table + "\""));
        call("ExecuteStatement", body.toString());
    }

    private void insertUnsorted() {
        partiQL("INSERT INTO $T VALUE " + UNSORTED_ITEM);
    }

    @Test
    void putItemStoresNestedMapsInCodePointOrder() {
        put("{\"pk\":{\"S\":\"k\"},\"m\":{\"M\":{\"z\":" + ZBK + ",\"b\":{\"L\":[" + ZBK + "]},\"k\":" + ZBK + "}}}");

        JsonNode m = stored().get("m");
        assertEquals("b,k,z", mapOrder(m));
        assertEquals("b,k,z", mapOrder(m.get("M").get("z")));
        assertEquals("b,k,z", mapOrder(m.get("M").get("b").get("L").get(0)));
    }

    @Test
    void putItemOrdersKeysByCodePointNotByUtf16Unit() {
        put("{\"pk\":{\"S\":\"k\"},\"m\":{\"M\":{\"😀\":{\"S\":\"x\"},\"￦\":{\"S\":\"x\"},"
                + "\"é\":{\"S\":\"x\"},\"aa\":{\"S\":\"x\"},\"a b\":{\"S\":\"x\"},\"a\":{\"S\":\"x\"},"
                + "\"_\":{\"S\":\"x\"},\"B\":{\"S\":\"x\"},\"A\":{\"S\":\"x\"},\"1\":{\"S\":\"x\"}}}}");

        assertEquals("1,A,B,_,a,a b,aa,é,￦,😀", mapOrder(stored().get("m")));
    }

    @Test
    void batchWriteItemStoresMapsInCodePointOrder() {
        call("BatchWriteItem", "{\"RequestItems\":{\"" + table + "\":[{\"PutRequest\":{\"Item\":"
                + "{\"pk\":{\"S\":\"k\"},\"m\":" + ZBK + "}}}]}}");

        assertEquals("b,k,z", mapOrder(stored().get("m")));
    }

    @Test
    void transactWriteItemsPutStoresMapsInCodePointOrder() {
        call("TransactWriteItems", "{\"TransactItems\":[{\"Put\":{\"TableName\":\"" + table + "\",\"Item\":"
                + "{\"pk\":{\"S\":\"k\"},\"m\":" + ZBK + "}}}]}");

        assertEquals("b,k,z", mapOrder(stored().get("m")));
    }

    @Test
    void transactWriteItemsUpdateStoresMapsInCodePointOrder() {
        put("{\"pk\":{\"S\":\"k\"}}");
        call("TransactWriteItems", "{\"TransactItems\":[{\"Update\":{\"TableName\":\"" + table + "\","
                + "\"Key\":{\"pk\":{\"S\":\"k\"}},\"UpdateExpression\":\"SET a = :v\","
                + "\"ExpressionAttributeValues\":{\":v\":" + ZBK + "}}}]}");

        assertEquals("b,k,z", mapOrder(stored().get("a")));
    }

    @Test
    void updateItemSetStoresMapsInCodePointOrder() {
        put("{\"pk\":{\"S\":\"k\"},\"m\":{\"M\":{}}}");
        update("SET a = :v, m.x = :v", "{\":v\":" + ZBK + "}");

        JsonNode item = stored();
        assertEquals("b,k,z", mapOrder(item.get("a")));
        assertEquals("b,k,z", mapOrder(item.get("m").get("M").get("x")));
    }

    @Test
    void attributeUpdatesPutStoresMapsInCodePointOrder() {
        put("{\"pk\":{\"S\":\"k\"}}");
        call("UpdateItem", "{\"TableName\":\"" + table + "\",\"Key\":{\"pk\":{\"S\":\"k\"}},"
                + "\"AttributeUpdates\":{\"a\":{\"Action\":\"PUT\",\"Value\":" + ZBK + "}}}");

        assertEquals("b,k,z", mapOrder(stored().get("a")));
    }

    @Test
    void listAppendStoresMapsInCodePointOrder() {
        put("{\"pk\":{\"S\":\"k\"},\"l\":{\"L\":[]}}");
        update("SET l = list_append(l, :v)", "{\":v\":{\"L\":[" + ZBK + "]}}");

        assertEquals("b,k,z", mapOrder(stored().get("l").get("L").get(0)));
    }

    @Test
    void ifNotExistsStoresMapsInCodePointOrder() {
        put("{\"pk\":{\"S\":\"k\"}}");
        update("SET a = if_not_exists(a, :v)", "{\":v\":" + ZBK + "}");

        assertEquals("b,k,z", mapOrder(stored().get("a")));
    }

    @Test
    void partiQLInsertKeepsTheWrittenOrder() {
        partiQL("INSERT INTO $T VALUE {'pk':'k','m':{'z':1,'b':{'z':1,'k':2,'b':3},'k':3}}");

        JsonNode m = stored().get("m");
        assertEquals("z,b,k", mapOrder(m));
        assertEquals("z,k,b", mapOrder(m.get("M").get("b")));
    }

    @Test
    void batchExecuteStatementInsertKeepsTheWrittenOrder() {
        ObjectNode statement = MAPPER.createObjectNode();
        statement.put("Statement", "INSERT INTO \"" + table + "\" VALUE {'pk':'k','m':{'z':1,'b':2,'k':3}}");
        call("BatchExecuteStatement", "{\"Statements\":[" + statement + "]}");

        assertEquals("z,b,k", mapOrder(stored().get("m")));
    }

    @Test
    void executeTransactionInsertKeepsTheWrittenOrder() {
        ObjectNode statement = MAPPER.createObjectNode();
        statement.put("Statement", "INSERT INTO \"" + table + "\" VALUE {'pk':'k','m':{'z':1,'b':2,'k':3}}");
        call("ExecuteTransaction", "{\"TransactStatements\":[" + statement + "]}");

        assertEquals("z,b,k", mapOrder(stored().get("m")));
    }

    @Test
    void partiQLUpdateSetKeepsTheWrittenOrderOfTheLiteral() {
        partiQL("INSERT INTO $T VALUE {'pk':'k'}");
        partiQL("UPDATE $T SET m = {'z':1,'b':2,'k':3} WHERE pk = 'k'");

        assertEquals("z,b,k", mapOrder(stored().get("m")));
    }

    @Test
    void partiQLUpdateSetOfNestedKeyKeepsTheLiteralAndSortsTheMap() {
        insertUnsorted();
        partiQL("UPDATE $T SET m.x = {'q':1,'d':2,'a':3} WHERE pk = 'k'");

        JsonNode m = stored().get("m");
        assertEquals("j,k,x,z", mapOrder(m));
        assertEquals("q,d,a", mapOrder(m.get("M").get("x")));
    }

    @Test
    void partiQLRemoveSortsTheMap() {
        insertUnsorted();
        partiQL("UPDATE $T REMOVE m.q WHERE pk = 'k'");

        assertEquals("j,k,z", mapOrder(stored().get("m")));
    }

    @Test
    void setOnAMapKeySortsItAndLeavesNestedMapsAlone() {
        insertUnsorted();
        update("SET m.x = :v", "{\":v\":{\"N\":\"1\"}}");

        JsonNode m = stored().get("m");
        assertEquals("j,k,x,z", mapOrder(m));
        assertEquals("z,b,a", mapOrder(m.get("M").get("k")));
    }

    @Test
    void setBelowANestedMapSortsEveryMapOnThePath() {
        insertUnsorted();
        update("SET m.k.w = :v", "{\":v\":{\"N\":\"1\"}}");

        JsonNode m = stored().get("m");
        assertEquals("j,k,z", mapOrder(m));
        assertEquals("a,b,w,z", mapOrder(m.get("M").get("k")));
        assertEquals("z,b,a", mapOrder(m.get("M").get("j")));
    }

    @Test
    void setBelowAListElementSortsOnlyThatElement() {
        insertUnsorted();
        update("SET l[0].e = :v", "{\":v\":{\"N\":\"1\"}}");

        JsonNode list = stored().get("l").get("L");
        assertEquals("b,e,z", mapOrder(list.get(0)));
        assertEquals("z,b", mapOrder(list.get(1)));
    }

    @Test
    void copyFromAPathKeepsTheSourceOrder() {
        insertUnsorted();
        update("SET a = b", null);

        JsonNode item = stored();
        assertEquals("z,b", mapOrder(item.get("a")));
    }

    @Test
    void laterPathThroughACopyDoesNotReorderTheSource() {
        insertUnsorted();
        update("SET a = b, c = b", null);
        update("SET a.e = :v", "{\":v\":{\"N\":\"1\"}}");

        JsonNode item = stored();
        assertEquals("b,e,z", mapOrder(item.get("a")));
        assertEquals("z,b", mapOrder(item.get("b")));
        assertEquals("z,b", mapOrder(item.get("c")));
    }

    @Test
    void copyOfANestedMapKeepsItsOrderWhileTheMapIsSorted() {
        insertUnsorted();
        update("SET m.k = m.j", null);

        JsonNode m = stored().get("m");
        assertEquals("j,k,z", mapOrder(m));
        assertEquals("z,b,a", mapOrder(m.get("M").get("k")));
    }

    @Test
    void updateThatTouchesNoMapLeavesEveryMapAsItWas() {
        insertUnsorted();
        update("ADD n :one", "{\":one\":{\"N\":\"1\"}}");

        JsonNode item = stored();
        assertEquals("z,k,j", mapOrder(item.get("m")));
        assertEquals("z,b,a", mapOrder(item.get("m").get("M").get("k")));
        assertEquals("z,b", mapOrder(item.get("b")));
    }
}
