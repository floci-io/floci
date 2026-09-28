package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbTtlService;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbStreamsJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.quarkus.arc.ClientProxy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The native engine behind the DynamoDB seam. Raw calls go to the native wire handlers and typed
 * calls go straight to {@link DynamoDbService}, each under the scope's account.
 */
@ApplicationScoped
@Typed(NativeDynamoDbBackend.class)
public class NativeDynamoDbBackend implements DynamoDbOperations, DynamoDbItemAccess, DynamoDbTableAccess,
        DynamoDbBackendLifecycle {

    private final NativeDynamoDbJsonHandler jsonHandler;
    private final NativeDynamoDbStreamsJsonHandler streamsHandler;
    private final DynamoDbService dynamoDbService;
    private final DynamoDbStreamService streamService;
    private final DynamoDbTtlService ttlService;
    private final ObjectMapper objectMapper;

    @Inject
    public NativeDynamoDbBackend(NativeDynamoDbJsonHandler jsonHandler,
                                 NativeDynamoDbStreamsJsonHandler streamsHandler,
                                 DynamoDbService dynamoDbService, DynamoDbStreamService streamService,
                                 DynamoDbTtlService ttlService, ObjectMapper objectMapper) {
        this.jsonHandler = jsonHandler;
        this.streamsHandler = streamsHandler;
        this.dynamoDbService = dynamoDbService;
        this.streamService = streamService;
        this.ttlService = ttlService;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves the engine beans so their constructors load persisted items, recover interrupted
     * jobs and rebuild stream buffers now, before any consumer reads, then schedules the TTL sweep.
     */
    @Override
    public void start() {
        ClientProxy.unwrap(dynamoDbService);
        ClientProxy.unwrap(streamService);
        ttlService.start();
    }

    /** A native reset is always allowed. */
    @Override
    public void checkReset() {
    }

    @Override
    public void beforeReset() {
        ttlService.pause();
    }

    @Override
    public void reset() {
        dynamoDbService.clearProcessState();
    }

    @Override
    public void afterReset() {
        ttlService.resume();
    }

    @Override
    public void stop() {
        ttlService.stop();
        dynamoDbService.stopKinesisForwarding();
    }

    @Override
    public Reply execute(Call call) throws Exception {
        String region = call.scope().region();
        return RequestScopes.callAsChecked(call.scope().accountId(), () -> reply(switch (call.api()) {
            case DYNAMODB -> jsonHandler.handle(call.action(), call.body(), region);
            case DYNAMODB_STREAMS -> streamsHandler.handle(call.action(), call.body(), region);
        }));
    }

    private Reply reply(Response response) {
        Object entity = response.getEntity();
        JsonNode body;
        if (entity == null) {
            body = null;
        } else if (entity instanceof JsonNode node) {
            body = node;
        } else {
            body = objectMapper.valueToTree(entity);
        }
        return new Reply(response.getStatus(), body, response.getStringHeaders());
    }

    @Override
    public void putItem(Scope scope, String tableName, JsonNode item, String conditionExpression,
                        JsonNode expressionAttributeNames, JsonNode expressionAttributeValues) {
        RequestScopes.runAs(scope.accountId(), () -> dynamoDbService.putItem(tableName, item, conditionExpression,
                expressionAttributeNames, expressionAttributeValues, scope.region(), "NONE"));
    }

    @Override
    public JsonNode getItem(Scope scope, String tableName, JsonNode key) {
        return RequestScopes.callAs(scope.accountId(), () -> dynamoDbService.getItem(tableName, key, scope.region()));
    }

    @Override
    public void deleteItem(Scope scope, String tableName, JsonNode key, String conditionExpression,
                           JsonNode expressionAttributeNames, JsonNode expressionAttributeValues) {
        RequestScopes.runAs(scope.accountId(), () -> dynamoDbService.deleteItem(tableName, key, conditionExpression,
                expressionAttributeNames, expressionAttributeValues, scope.region(), "NONE"));
    }

    @Override
    public JsonNode updateItem(Scope scope, String tableName, JsonNode key, JsonNode attributeUpdates,
                               String updateExpression, JsonNode expressionAttributeNames,
                               JsonNode expressionAttributeValues, String returnValues, String conditionExpression) {
        DynamoDbService.UpdateResult result = RequestScopes.callAs(scope.accountId(), () ->
                dynamoDbService.updateItem(tableName, key, attributeUpdates, updateExpression,
                        expressionAttributeNames, expressionAttributeValues, returnValues, conditionExpression,
                        scope.region(), "NONE"));
        if ("ALL_NEW".equals(returnValues)) {
            return result.newItem();
        } else if ("ALL_OLD".equals(returnValues)) {
            return result.oldItem();
        } else {
            return null;
        }
    }

    @Override
    public ScanPage scan(Scope scope, String tableName, String filterExpression, JsonNode expressionAttributeNames,
                         JsonNode expressionAttributeValues, JsonNode scanFilter, Integer limit,
                         JsonNode exclusiveStartKey) {
        DynamoDbService.ScanResult result = RequestScopes.callAs(scope.accountId(), () ->
                dynamoDbService.scan(tableName, filterExpression, expressionAttributeNames,
                        expressionAttributeValues, scanFilter, limit, exclusiveStartKey, scope.region()));
        return new ScanPage(result.items(), result.scannedCount(), result.lastEvaluatedKey());
    }

    @Override
    public TableDefinition createTable(Scope scope, String tableName, List<KeySchemaElement> keySchema,
                                       List<AttributeDefinition> attributeDefinitions, Long readCapacity,
                                       Long writeCapacity, List<GlobalSecondaryIndex> globalSecondaryIndexes,
                                       List<LocalSecondaryIndex> localSecondaryIndexes) {
        return RequestScopes.callAs(scope.accountId(), () -> dynamoDbService.createTable(tableName, keySchema,
                attributeDefinitions, readCapacity, writeCapacity, globalSecondaryIndexes, localSecondaryIndexes,
                scope.region()));
    }

    @Override
    public TableDefinition describeTable(Scope scope, String tableName) {
        return RequestScopes.callAs(scope.accountId(), () -> dynamoDbService.describeTable(tableName, scope.region()));
    }

    @Override
    public Optional<TableDefinition> findTable(Scope scope, String tableName) {
        return RequestScopes.callAs(scope.accountId(), () -> dynamoDbService.findTable(tableName, scope.region()));
    }

    @Override
    public void deleteTable(Scope scope, String tableName) {
        RequestScopes.runAs(scope.accountId(), () -> dynamoDbService.deleteTable(tableName, scope.region()));
    }

    @Override
    public TableDefinition enableStream(Scope scope, String tableName, String viewType) {
        return RequestScopes.callAs(scope.accountId(),
                () -> dynamoDbService.enableStream(tableName, viewType, scope.region()));
    }

    @Override
    public TableDefinition disableStream(Scope scope, String tableName) {
        return RequestScopes.callAs(scope.accountId(), () -> dynamoDbService.disableStream(tableName, scope.region()));
    }

    @Override
    public Map<String, String> listTagsOfResource(Scope scope, String resourceArn) {
        return RequestScopes.callAs(scope.accountId(),
                () -> dynamoDbService.listTagsOfResource(resourceArn, scope.region()));
    }

    @Override
    public void tagResource(Scope scope, String resourceArn, Map<String, String> tags) {
        RequestScopes.runAs(scope.accountId(), () -> dynamoDbService.tagResource(resourceArn, tags, scope.region()));
    }

    @Override
    public void untagResource(Scope scope, String resourceArn, List<String> tagKeys) {
        RequestScopes.runAs(scope.accountId(),
                () -> dynamoDbService.untagResource(resourceArn, tagKeys, scope.region()));
    }

    @Override
    public TableDefinition applyReplicaUpdates(Scope scope, String tableName, List<String> addRegions,
                                               List<String> removeRegions) {
        return RequestScopes.callAs(scope.accountId(),
                () -> dynamoDbService.applyReplicaUpdates(tableName, addRegions, removeRegions, scope.region()));
    }

    @Override
    public TableDefinition ensureGlobalTable(Scope scope, String tableName) {
        return RequestScopes.callAs(scope.accountId(),
                () -> dynamoDbService.ensureGlobalTable(tableName, scope.region()));
    }

    @Override
    public List<ExplorerResource> resources(String accountId) {
        return RequestScopes.callAs(accountId, dynamoDbService::getResources);
    }
}
