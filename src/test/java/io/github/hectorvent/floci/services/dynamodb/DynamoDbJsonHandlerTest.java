package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbJsonHandlerTest {

    private DynamoDbService service;
    private ObjectMapper mapper;
    private DynamoDbJsonHandler handler;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>());
        mapper = new ObjectMapper();
        handler = new DynamoDbJsonHandler(service, null, null, mapper);
    }

    private TableDefinition createUsersTable(String region) {
        return service.createTable("Users",
                List.of(new KeySchemaElement("userId", "HASH")),
                List.of(new AttributeDefinition("userId", "S")),
                5L, 5L, region);
    }

    private ObjectNode attributeValue(String type, String value) {
        ObjectNode attrValue = mapper.createObjectNode();
        attrValue.put(type, value);
        return attrValue;
    }

    private ObjectNode item(String... kvPairs) {
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i < kvPairs.length; i += 2) {
            node.set(kvPairs[i], attributeValue("S", kvPairs[i + 1]));
        }
        return node;
    }

    private JsonNode createRequest(String tableName, JsonNode key, String updateExpression, 
    JsonNode exprAttrNames, JsonNode exprAttrValues, String returnValues){
        ObjectNode node = mapper.createObjectNode();
        node.put("TableName", tableName);
        node.set("Key", key);
        node.put("UpdateExpression", updateExpression);
        if (exprAttrNames != null){
            node.set("ExpressionAttributeNames", exprAttrNames);
        }
        if (exprAttrValues != null){
            node.set("ExpressionAttributeValues", exprAttrValues);
        }
        node.put("ReturnValues", returnValues);
        return node;
    }

    @Test
    void updateItemReturnValuesUpdatedNew()  throws Exception {
        createUsersTable("us-east-1");

        service.putItem("Users", item("userId", "u-fallback", "delAttr", "old", "changeAttr", "val1", "sameAttr", "static"), "us-east-1");

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.put(":changeVal", attributeValue("S", "val2"));
        exprValues.put(":newVal", attributeValue("S", "newVal"));

        JsonNode request = createRequest("Users", key, 
        "SET changeAttr = :changeVal,  newAttr = :newVal, REMOVE delAttr",
        null, exprValues, "UPDATED_NEW");

        Response response = null;
        
        response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertTrue(responseData.has("Attributes"), "Attributes property must be present");
        JsonNode attr = responseData.get("Attributes");

        assertTrue(attr.has("changeAttr"), "Attributes should have changeAttr");
        assertTrue(attr.get("changeAttr").has("S"), "changeAttr should have S");
        assertEquals("val2", attr.get("changeAttr").get("S").asText());

        assertTrue(attr.has("newAttr"), "Attributes should have newAttr");
        assertTrue(attr.get("newAttr").has("S"), "newAttr should have S");
        assertEquals("newVal", attr.get("newAttr").get("S").asText());

        assertFalse(attr.has("delAttr"), "Attributes should not have delAttr");

        assertFalse(attr.has("sameAttr"), "Attributes should not have sameAttr");
    }
    
    @Test
    void updateItemReturnValuesUpdatedNewOnNewItem() throws Exception {
        createUsersTable("us-east-1");

        // Item does not exist - UpdateItem creates it
        ObjectNode key = item("userId", "u-new");

        ObjectNode exprValues = mapper.createObjectNode();
        ObjectNode startVal = mapper.createObjectNode();
        startVal.put("N", "60000000");
        ObjectNode incVal = mapper.createObjectNode();
        incVal.put("N", "1");
        exprValues.set(":start", startVal);
        exprValues.set(":inc", incVal);

        ObjectNode exprNames = mapper.createObjectNode();
        exprNames.put("#cnt", "counter");

        JsonNode request = createRequest("Users", key,
                "SET #cnt = if_not_exists(#cnt, :start) + :inc",
                exprNames, exprValues, "UPDATED_NEW");

        Response response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertTrue(responseData.has("Attributes"), "Attributes must be present when item is newly created");
        JsonNode attr = responseData.get("Attributes");

        assertTrue(attr.has("counter"), "Attributes should have counter");
        assertEquals("60000001", attr.get("counter").get("N").asText());

        assertFalse(attr.has("userId"), "UPDATED_NEW should not include key attributes");
    }

    @Test
    void updateItemReturnValuesUpdatedOld()  throws Exception {
        createUsersTable("us-east-1");

        service.putItem("Users", item("userId", "u-fallback", "delAttr", "old", "changeAttr", "val1", "sameAttr", "static"), "us-east-1");

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.put(":changeVal", attributeValue("S", "val2"));
        exprValues.put(":newVal", attributeValue("S", "newVal"));

        JsonNode request = createRequest("Users", key, 
        "SET changeAttr = :changeVal,  newAttr = :newVal, REMOVE delAttr",
        null, exprValues, "UPDATED_OLD");

        Response response = null;
        
        response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertTrue(responseData.has("Attributes"), "Attributes property must be present");
        JsonNode attr = responseData.get("Attributes");

        assertTrue(attr.has("changeAttr"), "Attributes should have changeAttr");
        assertTrue(attr.get("changeAttr").has("S"), "changeAttr should have S");
        assertEquals("val1", attr.get("changeAttr").get("S").asText());

        assertFalse(attr.has("newAttr"), "Attributes should not have newAttr");

        assertTrue(attr.has("delAttr"), "Attributes should have delAttr");
        assertTrue(attr.get("delAttr").has("S"), "delAttr should have S");
        assertEquals("old", attr.get("delAttr").get("S").asText());

        assertFalse(attr.has("sameAttr"), "Attributes should not have sameAttr");
    }
    
    @Test
    void updateItemReturnValuesAllOld()  throws Exception {
        createUsersTable("us-east-1");

        service.putItem("Users", item("userId", "u-fallback", "delAttr", "old", "changeAttr", "val1", "sameAttr", "static"), "us-east-1");

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.put(":changeVal", attributeValue("S", "val2"));
        exprValues.put(":newVal", attributeValue("S", "newVal"));

        JsonNode request = createRequest("Users", key, 
        "SET changeAttr = :changeVal,  newAttr = :newVal, REMOVE delAttr",
        null, exprValues, "ALL_OLD");

        Response response = null;
        
        response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertTrue(responseData.has("Attributes"), "Attributes property must be present");
        JsonNode attr = responseData.get("Attributes");

        assertTrue(attr.has("changeAttr"), "Attributes should have changeAttr");
        assertTrue(attr.get("changeAttr").has("S"), "changeAttr should have S");
        assertEquals("val1", attr.get("changeAttr").get("S").asText());

        assertFalse(attr.has("newAttr"), "Attributes should not have newAttr");

        assertTrue(attr.has("delAttr"), "Attributes should have delAttr");
        assertTrue(attr.get("delAttr").has("S"), "delAttr should have S");
        assertEquals("old", attr.get("delAttr").get("S").asText());

        assertTrue(attr.has("sameAttr"), "Attributes should have sameAttr");
        assertTrue(attr.get("sameAttr").has("S"), "sameAttr should have S");
        assertEquals("static", attr.get("sameAttr").get("S").asText());
    }
    
    @Test
    void updateItemReturnValuesAllNew()  throws Exception {
        createUsersTable("us-east-1");

        service.putItem("Users", item("userId", "u-fallback", "delAttr", "old", "changeAttr", "val1", "sameAttr", "static"), "us-east-1");

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.put(":changeVal", attributeValue("S", "val2"));
        exprValues.put(":newVal", attributeValue("S", "newVal"));

        JsonNode request = createRequest("Users", key, 
        "SET changeAttr = :changeVal,  newAttr = :newVal, REMOVE delAttr",
        null, exprValues, "ALL_NEW");

        Response response = null;
        
        response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertTrue(responseData.has("Attributes"), "Attributes property must be present");
        JsonNode attr = responseData.get("Attributes");

        assertTrue(attr.has("changeAttr"), "Attributes should have changeAttr");
        assertTrue(attr.get("changeAttr").has("S"), "changeAttr should have S");
        assertEquals("val2", attr.get("changeAttr").get("S").asText());

        assertTrue(attr.has("newAttr"), "Attributes should have newAttr");
        assertTrue(attr.get("newAttr").has("S"), "newAttr should have S");
        assertEquals("newVal", attr.get("newAttr").get("S").asText());

        assertFalse(attr.has("delAttr"), "Attributes should not have delAttr");

        assertTrue(attr.has("sameAttr"), "Attributes should have sameAttr");
        assertTrue(attr.get("sameAttr").has("S"), "sameAttr should have S");
        assertEquals("static", attr.get("sameAttr").get("S").asText());
    }
    
    @Test
    void updateItemReturnValuesNone()  throws Exception {
        createUsersTable("us-east-1");

        service.putItem("Users", item("userId", "u-fallback", "delAttr", "old", "changeAttr", "val1", "sameAttr", "static"), "us-east-1");

        ObjectNode key = item("userId", "u-fallback");

        ObjectNode exprValues = mapper.createObjectNode();
        exprValues.put(":changeVal", attributeValue("S", "val2"));
        exprValues.put(":newVal", attributeValue("S", "newVal"));

        JsonNode request = createRequest("Users", key, 
        "SET changeAttr = :changeVal,  newAttr = :newVal, REMOVE delAttr",
        null, exprValues, "NONE");

        Response response = null;
        
        response = handler.handle("UpdateItem", request, "us-east-1");
        assertNotNull(response);

        JsonNode responseData = mapper.convertValue(response.getEntity(), JsonNode.class);

        assertNotNull(responseData);
        assertFalse(responseData.has("Attributes"), "Attributes property must not be present");
    }

    // Reproduces #1604
    @Test
    void transactWriteItemsCancellationReasonMessageIsNullForNonFailedItems() throws Exception {
        createUsersTable("us-east-1");
        service.putItem("Users", item("userId", "A"), "us-east-1");

        ObjectNode condCheckA = mapper.createObjectNode();
        condCheckA.put("TableName", "Users");
        condCheckA.set("Key", item("userId", "A"));
        condCheckA.put("ConditionExpression", "attribute_exists(userId)");

        ObjectNode condCheckB = mapper.createObjectNode();
        condCheckB.put("TableName", "Users");
        condCheckB.set("Key", item("userId", "B"));
        condCheckB.put("ConditionExpression", "attribute_exists(userId)");

        ObjectNode txItemA = mapper.createObjectNode();
        txItemA.set("ConditionCheck", condCheckA);
        ObjectNode txItemB = mapper.createObjectNode();
        txItemB.set("ConditionCheck", condCheckB);

        ObjectNode request = mapper.createObjectNode();
        ArrayNode txItems = request.putArray("TransactItems");
        txItems.add(txItemA);
        txItems.add(txItemB);

        Response response = handler.handle("TransactWriteItems", request, "us-east-1");

        assertEquals(400, response.getStatus());

        JsonNode body = mapper.convertValue(response.getEntity(), JsonNode.class);
        assertEquals("TransactionCanceledException", body.get("__type").asText());

        ArrayNode reasons = (ArrayNode) body.get("CancellationReasons");
        assertEquals(2, reasons.size());

        assertEquals("None", reasons.get(0).get("Code").asText());
        assertNull(reasons.get(0).get("Message"), "non failed item must not have a Message field");
    }
}
