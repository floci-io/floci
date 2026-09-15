package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.graphql.execution.FieldError;
import io.github.hectorvent.floci.services.appsync.graphql.execution.GraphQlRequestContext;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DynamoDbDataSourceInvokerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DynamoDbService dynamoDb = mock(DynamoDbService.class);
    private final AppSyncDataSourceRoleAuthorizer roleAuthorizer = mock(AppSyncDataSourceRoleAuthorizer.class);
    private final DynamoDbDataSourceInvoker invoker = new DynamoDbDataSourceInvoker(
            dynamoDb, mapper, roleAuthorizer);
    private final GraphQlRequestContext ctx = new GraphQlRequestContext(
            "api", "000000000000", "eu-west-1", "API Key Authorization", null, Map.of(), Map.of());

    private DataSource table;

    @BeforeEach
    void setUp() {
        lenient().when(roleAuthorizer.authorizeDynamoDb(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        table = new DataSource();
        table.setName("todos");
        table.setType(DataSourceType.AMAZON_DYNAMODB);
        table.setServiceRoleArn("arn:aws:iam::000000000000:role/AppSyncDataSource");
        table.setDynamodbConfig(new HashMap<>(Map.of("tableName", "Todos", "awsRegion", "us-east-1")));
    }

    private static Map<String, Object> request(String operation, Map<String, Object> extra) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("version", "2018-05-29");
        request.put("operation", operation);
        request.putAll(extra);
        return request;
    }

    @Test
    void getItemMapsKeyAndConvertsResult() throws Exception {
        JsonNode stored = mapper.readTree("{\"id\":{\"S\":\"1\"},\"title\":{\"S\":\"milk\"},\"count\":{\"N\":\"2\"}}");
        when(dynamoDb.getItem(eq("Todos"), any(), eq("us-east-1"))).thenReturn(stored);

        DataSourceResult result = invoker.invoke(table,
                request("GetItem", Map.of("key", Map.of("id", Map.of("S", "1")))), ctx);

        assertNull(result.error());
        assertEquals(Map.of("id", "1", "title", "milk", "count", 2L), result.result());
        ArgumentCaptor<JsonNode> key = ArgumentCaptor.forClass(JsonNode.class);
        verify(dynamoDb).getItem(eq("Todos"), key.capture(), eq("us-east-1"));
        assertEquals("1", key.getValue().get("id").get("S").asText());
    }

    @Test
    void getItemMissingItemIsNull() {
        when(dynamoDb.getItem(any(), any(), any())).thenReturn(null);
        DataSourceResult result = invoker.invoke(table,
                request("GetItem", Map.of("key", Map.of("id", Map.of("S", "x")))), ctx);
        assertNull(result.result());
        assertNull(result.error());
    }

    @Test
    void regionFallsBackToRequestRegion() {
        table.getDynamodbConfig().remove("awsRegion");
        when(dynamoDb.getItem(any(), any(), any())).thenReturn(null);
        invoker.invoke(table, request("GetItem", Map.of("key", Map.of("id", Map.of("S", "x")))), ctx);
        verify(dynamoDb).getItem(eq("Todos"), any(), eq("eu-west-1"));
    }

    @Test
    void projectionIsRejectedBeforeCallingDynamoDb() {
        assertThrows(UnsupportedDataSourceOperationException.class, () -> invoker.invoke(table,
                request("GetItem", Map.of(
                        "key", Map.of("id", Map.of("S", "1")),
                        "projection", Map.of("expression", "id"))), ctx));

        verifyNoInteractions(dynamoDb);
    }

    @Test
    void deniedServiceRoleDoesNotCallDynamoDb() {
        when(roleAuthorizer.authorizeDynamoDb(
                table, "dynamodb:GetItem", "Todos", "us-east-1", ctx))
                .thenReturn(Optional.of(FieldError.of("DynamoDB:AccessDeniedException", "denied")));

        DataSourceResult result = invoker.invoke(table,
                request("GetItem", Map.of("key", Map.of("id", Map.of("S", "1")))), ctx);

        assertEquals("DynamoDB:AccessDeniedException", result.error().errorType());
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void putItemMergesKeyOverAttributeValuesAndPassesCondition() {
        Map<String, Object> condition = Map.of(
                "expression", "attribute_not_exists(#id)",
                "expressionNames", Map.of("#id", "id"),
                "expressionValues", Map.of(":v", Map.of("S", "z")));
        Map<String, Object> attributeValues = Map.of(
                "id", Map.of("S", "WRONG"),
                "title", Map.of("S", "milk"));

        DataSourceResult result = invoker.invoke(table, request("PutItem", Map.of(
                "key", Map.of("id", Map.of("S", "1")),
                "attributeValues", attributeValues,
                "condition", condition)), ctx);

        ArgumentCaptor<JsonNode> item = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> names = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<JsonNode> values = ArgumentCaptor.forClass(JsonNode.class);
        verify(dynamoDb).putItem(eq("Todos"), item.capture(), eq("attribute_not_exists(#id)"),
                names.capture(), values.capture(), eq("us-east-1"), eq("ALL_OLD"));
        assertEquals("1", item.getValue().get("id").get("S").asText());
        assertEquals("milk", item.getValue().get("title").get("S").asText());
        assertEquals("id", names.getValue().get("#id").asText());
        assertEquals("z", values.getValue().get(":v").get("S").asText());
        assertNull(result.error());
        assertEquals(Map.of("id", "1", "title", "milk"), result.result());
    }

    @Test
    void putItemWithoutConditionPassesNulls() {
        invoker.invoke(table, request("PutItem", Map.of("key", Map.of("id", Map.of("S", "1")))), ctx);
        verify(dynamoDb).putItem(eq("Todos"), any(), isNull(), isNull(), isNull(), eq("us-east-1"), eq("ALL_OLD"));
    }

    @Test
    void conditionalCheckFailureReturnsErrorAndExistingItem() throws Exception {
        JsonNode existing = mapper.readTree("{\"id\":{\"S\":\"1\"}}");
        doThrow(new ConditionalCheckFailedException(existing))
                .when(dynamoDb).putItem(any(), any(), any(), any(), any(), any(), any());

        DataSourceResult result = invoker.invoke(table, request("PutItem", Map.of(
                "key", Map.of("id", Map.of("S", "1")),
                "condition", Map.of("expression", "attribute_not_exists(id)"))), ctx);

        assertEquals("DynamoDB:ConditionalCheckFailedException", result.error().errorType());
        assertEquals("The conditional request failed", result.error().message());
        assertEquals(Map.of("id", "1"), result.result());
    }

    @Test
    void otherDynamoDbErrorsKeepTheirCode() {
        when(dynamoDb.getItem(any(), any(), any()))
                .thenThrow(new AwsException("ResourceNotFoundException", "Requested resource not found", 400));
        DataSourceResult result = invoker.invoke(table,
                request("GetItem", Map.of("key", Map.of("id", Map.of("S", "1")))), ctx);
        assertEquals("DynamoDB:ResourceNotFoundException", result.error().errorType());
        assertNull(result.result());
    }

    @Test
    void deferredOperationsRaiseWithoutTouchingDynamoDb() {
        for (String op : List.of("UpdateItem", "DeleteItem", "Query", "Scan", "BatchGetItem", "TransactWriteItems", "Sync")) {
            UnsupportedDataSourceOperationException e = assertThrows(UnsupportedDataSourceOperationException.class,
                    () -> invoker.invoke(table, request(op, Map.of()), ctx), op);
            assertTrue(e.getMessage().contains(op));
        }
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void invalidDocumentsFailBeforeCallingDynamoDb() {
        assertThrows(InvalidRequestDocumentException.class,
                () -> invoker.invoke(table, Map.of("version", "2018-05-29"), ctx));
        assertThrows(InvalidRequestDocumentException.class,
                () -> invoker.invoke(table, request("GetItem", Map.of()), ctx));
        assertThrows(InvalidRequestDocumentException.class,
                () -> invoker.invoke(table, request("PutItem", Map.of("key", "not-an-object")), ctx));
        assertThrows(InvalidRequestDocumentException.class,
                () -> invoker.invoke(table, request("PutItem", Map.of(
                        "key", Map.of("id", Map.of("S", "1")), "condition", Map.of("expressionNames", Map.of()))), ctx));
        assertThrows(InvalidRequestDocumentException.class,
                () -> invoker.invoke(table, request("Bogus", Map.of()), ctx));
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void missingTableNameIsInternalFailure() {
        table.setDynamodbConfig(Map.of());
        DataSourceResult result = invoker.invoke(table,
                request("GetItem", Map.of("key", Map.of("id", Map.of("S", "1")))), ctx);
        assertEquals("InternalFailure", result.error().errorType());
        verifyNoInteractions(dynamoDb);
    }
}
