package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
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

    // PutResourcePolicy/GetResourcePolicy/DeleteResourcePolicy previously fell through to the
    // default 400 UnknownOperationException branch, which blocks the Terraform provider's
    // aws_dynamodb_resource_policy resource at apply time.
    @Test
    void putGetDeleteResourcePolicyRoundTrips() throws Exception {
        TableDefinition table = createUsersTable("eu-west-1");
        String tableArn = table.getTableArn();
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"AllowDummyRoleAccess\","
                + "\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::222222222222:role/DummyRole\"},"
                + "\"Action\":\"dynamodb:GetItem\",\"Resource\":\"" + tableArn + "\"}]}";

        ObjectNode putRequest = mapper.createObjectNode();
        putRequest.put("ResourceArn", tableArn);
        putRequest.put("Policy", policy);

        Response putResponse = handler.handle("PutResourcePolicy", putRequest, "eu-west-1");
        assertEquals(200, putResponse.getStatus());
        JsonNode putBody = mapper.convertValue(putResponse.getEntity(), JsonNode.class);
        assertTrue(putBody.has("RevisionId"), "PutResourcePolicy must return a RevisionId");
        String revisionId = putBody.get("RevisionId").asText();
        assertFalse(revisionId.isBlank());

        ObjectNode getRequest = mapper.createObjectNode();
        getRequest.put("ResourceArn", tableArn);

        Response getResponse = handler.handle("GetResourcePolicy", getRequest, "eu-west-1");
        assertEquals(200, getResponse.getStatus());
        JsonNode getBody = mapper.convertValue(getResponse.getEntity(), JsonNode.class);
        assertEquals(policy, getBody.get("Policy").asText());
        assertEquals(revisionId, getBody.get("RevisionId").asText());

        ObjectNode deleteRequest = mapper.createObjectNode();
        deleteRequest.put("ResourceArn", tableArn);

        Response deleteResponse = handler.handle("DeleteResourcePolicy", deleteRequest, "eu-west-1");
        assertEquals(200, deleteResponse.getStatus());
        JsonNode deleteBody = mapper.convertValue(deleteResponse.getEntity(), JsonNode.class);
        assertEquals(revisionId, deleteBody.get("RevisionId").asText());

        // Policy is gone: GetResourcePolicy must fail now
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("GetResourcePolicy", getRequest, "eu-west-1"));
        assertEquals("PolicyNotFoundException", ex.getErrorCode());
    }

    @Test
    void putResourcePolicyRejectsAStaleExpectedRevisionId() throws Exception {
        TableDefinition table = createUsersTable("eu-west-1");
        String tableArn = table.getTableArn();

        ObjectNode putRequest = mapper.createObjectNode();
        putRequest.put("ResourceArn", tableArn);
        putRequest.put("Policy", "{}");
        handler.handle("PutResourcePolicy", putRequest, "eu-west-1");

        putRequest.put("ExpectedRevisionId", "not-the-current-revision");
        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutResourcePolicy", putRequest, "eu-west-1"));
        assertEquals("PolicyNotFoundException", ex.getErrorCode());
    }

    @Test
    void putResourcePolicyRejectsInvalidResourceArn() {
        ObjectNode putRequest = mapper.createObjectNode();
        putRequest.put("ResourceArn", "not-an-arn");
        putRequest.put("Policy", "{}");

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("PutResourcePolicy", putRequest, "eu-west-1"));
        assertEquals("ValidationException", ex.getErrorCode());
    }

    // A GSI created with an explicit OnDemandThroughput never had the value stored, so
    // DescribeTable could not report it back; the Terraform provider then proposes replacing
    // the GSI on every plan even though nothing drifted.
    @Test
    void createTableWithGsiOnDemandThroughputRoundTripsThroughDescribeTable() throws Exception {
        ObjectNode createRequest = mapper.createObjectNode();
        createRequest.put("TableName", "gsi-odt-table");
        createRequest.put("BillingMode", "PAY_PER_REQUEST");

        ArrayNode attrDefs = mapper.createArrayNode();
        attrDefs.add(mapper.createObjectNode().put("AttributeName", "id").put("AttributeType", "S"));
        attrDefs.add(mapper.createObjectNode().put("AttributeName", "title").put("AttributeType", "S"));
        attrDefs.add(mapper.createObjectNode().put("AttributeName", "age").put("AttributeType", "S"));
        createRequest.set("AttributeDefinitions", attrDefs);

        ArrayNode keySchema = mapper.createArrayNode();
        keySchema.add(mapper.createObjectNode().put("AttributeName", "id").put("KeyType", "HASH"));
        createRequest.set("KeySchema", keySchema);

        ObjectNode gsi = mapper.createObjectNode();
        gsi.put("IndexName", "TitleIndex");
        ArrayNode gsiKeySchema = mapper.createArrayNode();
        gsiKeySchema.add(mapper.createObjectNode().put("AttributeName", "title").put("KeyType", "HASH"));
        gsiKeySchema.add(mapper.createObjectNode().put("AttributeName", "age").put("KeyType", "RANGE"));
        gsi.set("KeySchema", gsiKeySchema);
        ObjectNode projection = mapper.createObjectNode();
        projection.put("ProjectionType", "INCLUDE");
        projection.set("NonKeyAttributes", mapper.createArrayNode().add("id"));
        gsi.set("Projection", projection);
        ObjectNode gsiOnDemand = mapper.createObjectNode();
        gsiOnDemand.put("MaxReadRequestUnits", 1);
        gsiOnDemand.put("MaxWriteRequestUnits", 1);
        gsi.set("OnDemandThroughput", gsiOnDemand);
        createRequest.set("GlobalSecondaryIndexes", mapper.createArrayNode().add(gsi));

        Response createResponse = handler.handle("CreateTable", createRequest, "eu-west-1");
        assertEquals(200, createResponse.getStatus());
        JsonNode createBody = mapper.convertValue(createResponse.getEntity(), JsonNode.class);
        JsonNode createdGsi = createBody.get("TableDescription").get("GlobalSecondaryIndexes").get(0);
        assertTrue(createdGsi.has("OnDemandThroughput"),
                "CreateTable response must echo the GSI's OnDemandThroughput");
        assertEquals(1, createdGsi.get("OnDemandThroughput").get("MaxReadRequestUnits").asInt());
        assertEquals(1, createdGsi.get("OnDemandThroughput").get("MaxWriteRequestUnits").asInt());

        ObjectNode describeRequest = mapper.createObjectNode();
        describeRequest.put("TableName", "gsi-odt-table");

        Response describeResponse = handler.handle("DescribeTable", describeRequest, "eu-west-1");
        assertEquals(200, describeResponse.getStatus());
        JsonNode describeBody = mapper.convertValue(describeResponse.getEntity(), JsonNode.class);
        JsonNode describedGsi = describeBody.get("Table").get("GlobalSecondaryIndexes").get(0);

        assertTrue(describedGsi.has("OnDemandThroughput"),
                "DescribeTable must report the GSI's OnDemandThroughput");
        JsonNode odt = describedGsi.get("OnDemandThroughput");
        assertEquals(1, odt.get("MaxReadRequestUnits").asInt());
        assertEquals(1, odt.get("MaxWriteRequestUnits").asInt());
    }
}
