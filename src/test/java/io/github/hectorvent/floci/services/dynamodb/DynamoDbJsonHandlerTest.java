package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private void createProvisionedTableWithGsi(String tableName, String region) {
        GlobalSecondaryIndex gsi = new GlobalSecondaryIndex(
                "TitleIndex",
                List.of(new KeySchemaElement("title", "HASH")),
                null, "ALL", null);
        gsi.getProvisionedThroughput().setReadCapacityUnits(5);
        gsi.getProvisionedThroughput().setWriteCapacityUnits(5);
        service.createTable(
                tableName,
                List.of(new KeySchemaElement("id", "HASH")),
                List.of(
                        new AttributeDefinition("id", "S"),
                        new AttributeDefinition("title", "S")),
                5L, 5L, List.of(gsi), region);
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

    @Test
    void sseSettingsSurvivePersistentStorageReload(@TempDir Path tempDir) throws Exception {
        Path tableFile = tempDir.resolve("dynamodb-tables.json");
        TypeReference<Map<String, TableDefinition>> typeReference = new TypeReference<>() {};

        DynamoDbService persistentService = new DynamoDbService(new PersistentStorage<>(tableFile, typeReference));
        DynamoDbJsonHandler persistentHandler = new DynamoDbJsonHandler(persistentService, null, null, mapper);

        ObjectNode createRequest = mapper.createObjectNode();
        createRequest.put("TableName", "PersistedSseTable");
        ObjectNode keySchemaElement = mapper.createObjectNode();
        keySchemaElement.put("AttributeName", "pk");
        keySchemaElement.put("KeyType", "HASH");
        createRequest.putArray("KeySchema").add(keySchemaElement);
        ObjectNode attrDef = mapper.createObjectNode();
        attrDef.put("AttributeName", "pk");
        attrDef.put("AttributeType", "S");
        createRequest.putArray("AttributeDefinitions").add(attrDef);
        createRequest.put("BillingMode", "PAY_PER_REQUEST");

        persistentHandler.handle("CreateTable", createRequest, "us-east-1");

        ObjectNode updateRequest = mapper.createObjectNode();
        updateRequest.put("TableName", "PersistedSseTable");
        ObjectNode sseSpec = updateRequest.putObject("SSESpecification");
        sseSpec.put("Enabled", true);
        sseSpec.put("SSEType", "KMS");
        sseSpec.put("KMSMasterKeyId", "arn:aws:kms:us-east-1:000000000000:key/reload-test-key");

        persistentHandler.handle("UpdateTable", updateRequest, "us-east-1");

        // Simulate a process restart: a brand new storage backend instance reading the same file,
        // independent of the in-memory table object mutated above.
        PersistentStorage<String, TableDefinition> reloadedStorage = new PersistentStorage<>(tableFile, typeReference);
        reloadedStorage.load();
        DynamoDbService reloadedService = new DynamoDbService(reloadedStorage);

        TableDefinition reloadedTable = reloadedService.describeTable("PersistedSseTable", "us-east-1");
        assertTrue(reloadedTable.isSseEnabled(), "SSE must survive a storage reload");
        assertEquals("KMS", reloadedTable.getSseType());
        assertEquals("arn:aws:kms:us-east-1:000000000000:key/reload-test-key",
                reloadedTable.getKmsMasterKeyArn());
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
    void createAndUpdateTableWithGsiOnDemandThroughputRoundTripsThroughDescribeTable() throws Exception {
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

        ObjectNode updateAction = mapper.createObjectNode();
        updateAction.put("IndexName", "TitleIndex");
        updateAction.set("OnDemandThroughput", mapper.createObjectNode()
                .put("MaxReadRequestUnits", 20)
                .put("MaxWriteRequestUnits", 30));
        ObjectNode updateRequest = mapper.createObjectNode();
        updateRequest.put("TableName", "gsi-odt-table");
        updateRequest.set("GlobalSecondaryIndexUpdates", mapper.createArrayNode().add(
                mapper.createObjectNode().set("Update", updateAction)));

        Response updateResponse = handler.handle("UpdateTable", updateRequest, "eu-west-1");
        assertEquals(200, updateResponse.getStatus());
        JsonNode updateBody = mapper.convertValue(updateResponse.getEntity(), JsonNode.class);
        JsonNode updatedGsi = updateBody.get("TableDescription").get("GlobalSecondaryIndexes").get(0);
        assertEquals(20, updatedGsi.get("OnDemandThroughput").get("MaxReadRequestUnits").asInt());
        assertEquals(30, updatedGsi.get("OnDemandThroughput").get("MaxWriteRequestUnits").asInt());

        describeResponse = handler.handle("DescribeTable", describeRequest, "eu-west-1");
        describeBody = mapper.convertValue(describeResponse.getEntity(), JsonNode.class);
        describedGsi = describeBody.get("Table").get("GlobalSecondaryIndexes").get(0);
        assertEquals(20, describedGsi.get("OnDemandThroughput").get("MaxReadRequestUnits").asInt());
        assertEquals(30, describedGsi.get("OnDemandThroughput").get("MaxWriteRequestUnits").asInt());
    }

    @Test
    void updateTableChangesGsiProvisionedThroughput() throws Exception {
        createProvisionedTableWithGsi("gsi-provisioned-table", "eu-west-1");

        ObjectNode updateAction = mapper.createObjectNode();
        updateAction.put("IndexName", "TitleIndex");
        updateAction.set("ProvisionedThroughput", mapper.createObjectNode()
                .put("ReadCapacityUnits", 20)
                .put("WriteCapacityUnits", 30));
        ObjectNode updateRequest = mapper.createObjectNode();
        updateRequest.put("TableName", "gsi-provisioned-table");
        updateRequest.set("GlobalSecondaryIndexUpdates", mapper.createArrayNode().add(
                mapper.createObjectNode().set("Update", updateAction)));

        Response updateResponse = handler.handle("UpdateTable", updateRequest, "eu-west-1");
        assertEquals(200, updateResponse.getStatus());
        JsonNode updateBody = mapper.convertValue(updateResponse.getEntity(), JsonNode.class);
        JsonNode updatedGsi = updateBody.get("TableDescription").get("GlobalSecondaryIndexes").get(0);
        assertEquals(20, updatedGsi.get("ProvisionedThroughput").get("ReadCapacityUnits").asInt());
        assertEquals(30, updatedGsi.get("ProvisionedThroughput").get("WriteCapacityUnits").asInt());

        ObjectNode describeRequest = mapper.createObjectNode();
        describeRequest.put("TableName", "gsi-provisioned-table");
        Response describeResponse = handler.handle("DescribeTable", describeRequest, "eu-west-1");
        JsonNode describeBody = mapper.convertValue(describeResponse.getEntity(), JsonNode.class);
        JsonNode describedGsi = describeBody.get("Table").get("GlobalSecondaryIndexes").get(0);
        assertEquals(20, describedGsi.get("ProvisionedThroughput").get("ReadCapacityUnits").asInt());
        assertEquals(30, describedGsi.get("ProvisionedThroughput").get("WriteCapacityUnits").asInt());
    }

    @Test
    void updateTableRejectsConflictingDeleteAndUpdateWithoutRemovingGsi() throws Exception {
        createProvisionedTableWithGsi("gsi-conflicting-update-table", "eu-west-1");

        ObjectNode updateRequest = mapper.createObjectNode();
        updateRequest.put("TableName", "gsi-conflicting-update-table");
        updateRequest.set("GlobalSecondaryIndexUpdates", mapper.createArrayNode()
                .add(mapper.createObjectNode().set("Delete",
                        mapper.createObjectNode().put("IndexName", "TitleIndex")))
                .add(mapper.createObjectNode().set("Update", mapper.createObjectNode()
                        .put("IndexName", "TitleIndex")
                        .set("ProvisionedThroughput", mapper.createObjectNode()
                                .put("ReadCapacityUnits", 20)
                                .put("WriteCapacityUnits", 30)))));

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> handler.handle("UpdateTable", updateRequest, "eu-west-1"));

        ObjectNode describeRequest = mapper.createObjectNode();
        describeRequest.put("TableName", "gsi-conflicting-update-table");
        Response describeResponse = handler.handle("DescribeTable", describeRequest, "eu-west-1");
        JsonNode describeBody = mapper.convertValue(describeResponse.getEntity(), JsonNode.class);
        JsonNode describedIndexes = describeBody.get("Table").path("GlobalSecondaryIndexes");
        assertAll(
                () -> assertTrue(error instanceof AwsException awsError
                        && "ValidationException".equals(awsError.getErrorCode())),
                () -> assertEquals(1, describedIndexes.size()));
    }

    // Request validation for AWS parity: real DynamoDB rejects each of these with a
    // ValidationException (paritysuite dynamodb-conformance tier1).

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private AwsException expectValidationException(String action, JsonNode request) {
        var ex = assertThrows(AwsException.class, () -> handler.handle(action, request, "eu-west-1"));
        assertEquals("ValidationException", ex.getErrorCode());
        return ex;
    }

    @Test
    void createTableRejectsProvisionedThroughputWithPayPerRequest() {
        var ex = expectValidationException("CreateTable", json("""
                {
                    "TableName": "PprTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST",
                    "ProvisionedThroughput": {"ReadCapacityUnits": 5, "WriteCapacityUnits": 5}
                }
                """));
        assertEquals("One or more parameter values were invalid: Neither ReadCapacityUnits nor "
                + "WriteCapacityUnits can be specified when BillingMode is PAY_PER_REQUEST", ex.getMessage());
    }

    @Test
    void createTableRejectsGsiIncludeProjectionWithoutNonKeyAttributes() {
        var ex = expectValidationException("CreateTable", json("""
                {
                    "TableName": "GsiIncTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "g", "AttributeType": "S"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST",
                    "GlobalSecondaryIndexes": [{
                        "IndexName": "gsi1",
                        "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}],
                        "Projection": {"ProjectionType": "INCLUDE"}
                    }]
                }
                """));
        assertEquals("One or more parameter values were invalid: "
                + "ProjectionType is INCLUDE, but NonKeyAttributes is not specified", ex.getMessage());
    }

    @Test
    void createTableRejectsLsiIncludeProjectionWithoutNonKeyAttributes() {
        var ex = expectValidationException("CreateTable", json("""
                {
                    "TableName": "LsiIncTable",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"},
                        {"AttributeName": "lsiSk", "AttributeType": "S"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST",
                    "LocalSecondaryIndexes": [{
                        "IndexName": "lsi1",
                        "KeySchema": [
                            {"AttributeName": "pk", "KeyType": "HASH"},
                            {"AttributeName": "lsiSk", "KeyType": "RANGE"}
                        ],
                        "Projection": {"ProjectionType": "INCLUDE"}
                    }]
                }
                """));
        assertEquals("One or more parameter values were invalid: "
                + "ProjectionType is INCLUDE, but NonKeyAttributes is not specified", ex.getMessage());
    }

    @Test
    void createTableRejectsKeysOnlyProjectionCarryingNonKeyAttributes() {
        var ex = expectValidationException("CreateTable", json("""
                {
                    "TableName": "KeysOnlyTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "g", "AttributeType": "S"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST",
                    "GlobalSecondaryIndexes": [{
                        "IndexName": "gsi1",
                        "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}],
                        "Projection": {"ProjectionType": "KEYS_ONLY", "NonKeyAttributes": ["x"]}
                    }]
                }
                """));
        assertEquals("One or more parameter values were invalid: "
                + "ProjectionType is KEYS_ONLY, but NonKeyAttributes is specified", ex.getMessage());
    }

    @Test
    void updateTableRejectsGsiIncludeProjectionWithoutNonKeyAttributes() {
        createUsersTable("eu-west-1");
        var ex = expectValidationException("UpdateTable", json("""
                {
                    "TableName": "Users",
                    "AttributeDefinitions": [{"AttributeName": "g", "AttributeType": "S"}],
                    "GlobalSecondaryIndexUpdates": [{"Create": {
                        "IndexName": "gsi1",
                        "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}],
                        "Projection": {"ProjectionType": "INCLUDE"}
                    }}]
                }
                """));
        assertEquals("One or more parameter values were invalid: "
                + "ProjectionType is INCLUDE, but NonKeyAttributes is not specified", ex.getMessage());
        assertTrue(service.describeTable("Users", "eu-west-1").getGlobalSecondaryIndexes().isEmpty());
    }

    @Test
    void updateTableRejectsAllProjectionCarryingNonKeyAttributes() {
        createUsersTable("eu-west-1");
        var ex = expectValidationException("UpdateTable", json("""
                {
                    "TableName": "Users",
                    "AttributeDefinitions": [{"AttributeName": "g", "AttributeType": "S"}],
                    "GlobalSecondaryIndexUpdates": [{"Create": {
                        "IndexName": "gsi1",
                        "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}],
                        "Projection": {"ProjectionType": "ALL", "NonKeyAttributes": ["x"]}
                    }}]
                }
                """));
        assertEquals("One or more parameter values were invalid: "
                + "ProjectionType is ALL, but NonKeyAttributes is specified", ex.getMessage());
    }

    // Checked against real DynamoDB: the projection is validated before the table lookup.
    @Test
    void updateTableValidatesGsiProjectionBeforeTableLookup() {
        var ex = expectValidationException("UpdateTable", json("""
                {
                    "TableName": "NoSuchTable",
                    "AttributeDefinitions": [{"AttributeName": "g", "AttributeType": "S"}],
                    "GlobalSecondaryIndexUpdates": [{"Create": {
                        "IndexName": "gsi1",
                        "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}],
                        "Projection": {"ProjectionType": "INCLUDE"}
                    }}]
                }
                """));
        assertEquals("One or more parameter values were invalid: "
                + "ProjectionType is INCLUDE, but NonKeyAttributes is not specified", ex.getMessage());
    }

    // The empty-list, null, and index-position cases below were checked against real DynamoDB.
    @Test
    void createTableRejectsEmptyGsiNonKeyAttributesWithItsPosition() {
        var ex = expectValidationException("CreateTable", json("""
                {
                    "TableName": "EmptyGsiNonKey",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "g", "AttributeType": "S"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST",
                    "GlobalSecondaryIndexes": [
                        {
                            "IndexName": "gsi1",
                            "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}],
                            "Projection": {"ProjectionType": "ALL"}
                        },
                        {
                            "IndexName": "gsi2",
                            "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}],
                            "Projection": {"ProjectionType": "KEYS_ONLY", "NonKeyAttributes": []}
                        }
                    ]
                }
                """));
        assertEquals("1 validation error detected: Value '[]' at "
                + "'globalSecondaryIndexes.2.member.projection.nonKeyAttributes' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1", ex.getMessage());
    }

    @Test
    void createTableRejectsEmptyLsiNonKeyAttributes() {
        var ex = expectValidationException("CreateTable", json("""
                {
                    "TableName": "EmptyLsiNonKey",
                    "KeySchema": [
                        {"AttributeName": "pk", "KeyType": "HASH"},
                        {"AttributeName": "sk", "KeyType": "RANGE"}
                    ],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "sk", "AttributeType": "S"},
                        {"AttributeName": "lsiSk", "AttributeType": "S"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST",
                    "LocalSecondaryIndexes": [{
                        "IndexName": "lsi1",
                        "KeySchema": [
                            {"AttributeName": "pk", "KeyType": "HASH"},
                            {"AttributeName": "lsiSk", "KeyType": "RANGE"}
                        ],
                        "Projection": {"ProjectionType": "KEYS_ONLY", "NonKeyAttributes": []}
                    }]
                }
                """));
        assertEquals("1 validation error detected: Value '[]' at "
                + "'localSecondaryIndexes.1.member.projection.nonKeyAttributes' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1", ex.getMessage());
    }

    @Test
    void updateTableRejectsEmptyGsiNonKeyAttributesBeforeProjectionTypeCheck() {
        createUsersTable("eu-west-1");
        var ex = expectValidationException("UpdateTable", json("""
                {
                    "TableName": "Users",
                    "AttributeDefinitions": [{"AttributeName": "g", "AttributeType": "S"}],
                    "GlobalSecondaryIndexUpdates": [{"Create": {
                        "IndexName": "gsi1",
                        "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}],
                        "Projection": {"ProjectionType": "INCLUDE", "NonKeyAttributes": []}
                    }}]
                }
                """));
        assertEquals("1 validation error detected: Value '[]' at "
                + "'globalSecondaryIndexUpdates.1.member.create.projection.nonKeyAttributes' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1", ex.getMessage());
    }

    @Test
    void createTableTreatsNullNonKeyAttributesAsNotSpecified() throws Exception {
        Response response = handler.handle("CreateTable", json("""
                {
                    "TableName": "NullNonKey",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [
                        {"AttributeName": "pk", "AttributeType": "S"},
                        {"AttributeName": "g", "AttributeType": "S"}
                    ],
                    "BillingMode": "PAY_PER_REQUEST",
                    "GlobalSecondaryIndexes": [{
                        "IndexName": "gsi1",
                        "KeySchema": [{"AttributeName": "g", "KeyType": "HASH"}],
                        "Projection": {"ProjectionType": "KEYS_ONLY", "NonKeyAttributes": null}
                    }]
                }
                """), "eu-west-1");
        assertEquals(200, response.getStatus());
        var gsi = service.describeTable("NullNonKey", "eu-west-1").findGsi("gsi1").orElseThrow();
        assertEquals("KEYS_ONLY", gsi.getProjectionType());
    }

    @Test
    void createTableRejectsStreamViewTypeWithStreamEnabledFalse() {
        var ex = expectValidationException("CreateTable", json("""
                {
                    "TableName": "StreamFalseTable",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "BillingMode": "PAY_PER_REQUEST",
                    "StreamSpecification": {"StreamEnabled": false, "StreamViewType": "NEW_AND_OLD_IMAGES"}
                }
                """));
        assertEquals("One or more parameter values were invalid: "
                + "StreamViewType cannot be specified when StreamEnabled is false", ex.getMessage());
    }

    @Test
    void scanRejectsTotalSegmentsAboveTheMaximum() {
        var ex = expectValidationException("Scan", json("""
                {"TableName": "Users", "Segment": 0, "TotalSegments": 1000001}
                """));
        assertEquals("1 validation error detected: Value '1000001' at 'totalSegments' failed to "
                + "satisfy constraint: Member must have value less than or equal to 1000000", ex.getMessage());
    }

    @Test
    void updateItemRejectsExpressionAttributeNamesWithNoExpression() {
        createUsersTable("eu-west-1");
        var ex = expectValidationException("UpdateItem", json("""
                {
                    "TableName": "Users",
                    "Key": {"userId": {"S": "u1"}},
                    "ExpressionAttributeNames": {"#s": "status"}
                }
                """));
        assertEquals("ExpressionAttributeNames can only be specified when using expressions: "
                + "UpdateExpression is null, ConditionExpression is null", ex.getMessage());
    }

    @Test
    void scanRejectsANegativeSegment() {
        var ex = expectValidationException("Scan", json("""
                {"TableName": "Users", "Segment": -1, "TotalSegments": 4}
                """));
        assertEquals("1 validation error detected: Value '-1' at 'segment' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 0", ex.getMessage());
    }

    @Test
    void scanRejectsTotalSegmentsBelowOne() {
        var ex = expectValidationException("Scan", json("""
                {"TableName": "Users", "Segment": 0, "TotalSegments": 0}
                """));
        assertEquals("1 validation error detected: Value '0' at 'totalSegments' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1", ex.getMessage());
    }

    @Test
    void scanReportsTotalSegmentsBeforeSegmentWhenBothAreOutOfRange() {
        var ex = expectValidationException("Scan", json("""
                {"TableName": "Users", "Segment": -1, "TotalSegments": 0}
                """));
        assertEquals("2 validation errors detected: "
                + "Value '0' at 'totalSegments' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 1; "
                + "Value '-1' at 'segment' failed to satisfy constraint: "
                + "Member must have value greater than or equal to 0", ex.getMessage());
    }

    @Test
    void scanAcceptsSegmentsAtTheBounds() throws Exception {
        createUsersTable("eu-west-1");

        var response = handler.handle("Scan", json("""
                {"TableName": "Users", "Segment": 0, "TotalSegments": 1000000}
                """), "eu-west-1");
        assertEquals(200, response.getStatus());
    }

    @Test
    void parallelScanSegmentsAreStableWhenItemsDeleted() throws Exception {
        String region = "eu-west-1";
        createUsersTable(region);

        for (int i = 0; i < 10; i++) {
            service.putItem("Users", item("userId", "item-" + i), region);
        }

        int totalSegments = 10;
        int totalDeleted = 0;
        for (int segment = 0; segment < totalSegments; segment++) {
            var scanReq = mapper.createObjectNode();
            scanReq.put("TableName", "Users");
            scanReq.put("Segment", segment);
            scanReq.put("TotalSegments", totalSegments);

            var response = handler.handle("Scan", scanReq, region);
            assertEquals(200, response.getStatus());
            var entity = mapper.readTree(response.getEntity().toString());
            var items = entity.get("Items");
            for (var item : items) {
                totalDeleted++;
                String key = item.get("userId").get("S").asText();
                service.deleteItem("Users", item("userId", key), region);
            }
        }

        assertEquals(10, totalDeleted, "All 10 items should have been returned and deleted across segments");

        var checkResponse = handler.handle("Scan", json("""
                {"TableName": "Users", "Select": "COUNT"}
                """), region);
        var checkEntity = mapper.readTree(checkResponse.getEntity().toString());
        assertEquals(0, checkEntity.get("Count").asInt(), "No remaining items should be in the table");
    }

    @Test
    void parallelScanPaginationWithLimit() throws Exception {
        String region = "eu-west-1";
        createUsersTable(region);

        for (int i = 0; i < 20; i++) {
            service.putItem("Users", item("userId", "user-" + i), region);
        }

        int totalSegments = 4;
        Set<String> collectedKeys = new HashSet<>();
        for (int segment = 0; segment < totalSegments; segment++) {
            JsonNode exclusiveStartKey = null;
            do {
                var scanReq = mapper.createObjectNode();
                scanReq.put("TableName", "Users");
                scanReq.put("Segment", segment);
                scanReq.put("TotalSegments", totalSegments);
                scanReq.put("Limit", 2);
                if (exclusiveStartKey != null) {
                    scanReq.set("ExclusiveStartKey", exclusiveStartKey);
                }

                var response = handler.handle("Scan", scanReq, region);
                assertEquals(200, response.getStatus());
                var entity = mapper.readTree(response.getEntity().toString());
                var items = entity.get("Items");
                assertTrue(items.size() <= 2, "Page size must not exceed limit");
                for (var item : items) {
                    String key = item.get("userId").get("S").asText();
                    assertTrue(collectedKeys.add(key), "Item must not be returned multiple times: " + key);
                }
                exclusiveStartKey = entity.get("LastEvaluatedKey");
            } while (exclusiveStartKey != null && !exclusiveStartKey.isNull());
        }

        assertEquals(20, collectedKeys.size(), "All 20 items must be collected across paginated segments");
    }

    @Test
    void parallelScanSelectCount() throws Exception {
        String region = "eu-west-1";
        createUsersTable(region);

        for (int i = 0; i < 15; i++) {
            service.putItem("Users", item("userId", "id-" + i), region);
        }

        int totalSegments = 5;
        int sumCount = 0;
        int sumScannedCount = 0;
        for (int segment = 0; segment < totalSegments; segment++) {
            var scanReq = mapper.createObjectNode();
            scanReq.put("TableName", "Users");
            scanReq.put("Segment", segment);
            scanReq.put("TotalSegments", totalSegments);
            scanReq.put("Select", "COUNT");

            var response = handler.handle("Scan", scanReq, region);
            assertEquals(200, response.getStatus());
            var entity = mapper.readTree(response.getEntity().toString());
            sumCount += entity.get("Count").asInt();
            sumScannedCount += entity.get("ScannedCount").asInt();
        }

        assertEquals(15, sumCount, "Sum of Count across segments must equal total items");
        assertEquals(15, sumScannedCount, "Sum of ScannedCount across segments must equal total items");
    }

    @Test
    void parallelScanSamePartitionKeySameSegment() throws Exception {
        String region = "eu-west-1";
        service.createTable("Orders",
                List.of(
                        new KeySchemaElement("userId", "HASH"),
                        new KeySchemaElement("orderId", "RANGE")),
                List.of(
                        new AttributeDefinition("userId", "S"),
                        new AttributeDefinition("orderId", "S")),
                5L, 5L, region);

        for (int i = 0; i < 5; i++) {
            service.putItem("Orders", item("userId", "alice", "orderId", "ord-" + i), region);
            service.putItem("Orders", item("userId", "bob", "orderId", "ord-" + i), region);
        }

        int totalSegments = 10;
        Map<String, Set<Integer>> userSegments = new HashMap<>();
        userSegments.put("alice", new HashSet<>());
        userSegments.put("bob", new HashSet<>());

        for (int segment = 0; segment < totalSegments; segment++) {
            var scanReq = mapper.createObjectNode();
            scanReq.put("TableName", "Orders");
            scanReq.put("Segment", segment);
            scanReq.put("TotalSegments", totalSegments);

            var response = handler.handle("Scan", scanReq, region);
            assertEquals(200, response.getStatus());
            var entity = mapper.readTree(response.getEntity().toString());
            var items = entity.get("Items");
            for (var item : items) {
                String userId = item.get("userId").get("S").asText();
                userSegments.get(userId).add(segment);
            }
        }

        assertEquals(1, userSegments.get("alice").size(), "All alice items must belong to the exact same segment");
        assertEquals(1, userSegments.get("bob").size(), "All bob items must belong to the exact same segment");
    }
}
