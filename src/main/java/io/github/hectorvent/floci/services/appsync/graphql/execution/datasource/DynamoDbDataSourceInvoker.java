package io.github.hectorvent.floci.services.appsync.graphql.execution.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.graphql.execution.FieldError;
import io.github.hectorvent.floci.services.appsync.graphql.execution.GraphQlRequestContext;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs AppSync DynamoDB request documents ({@code {"version":"2018-05-29","operation":"GetItem",...}})
 * against the in-process {@link DynamoDbService}. Only {@code GetItem} and {@code PutItem} are
 * implemented; the remaining operations raise {@link UnsupportedDataSourceOperationException}.
 *
 * <p>The table is resolved under the request context's account, which the caller has already
 * switched to the API owner. Access is authorized through the data source's service role.
 * {@code consistentRead}, {@code condition.equalsIgnore} and {@code condition.consistentRead} are
 * accepted and ignored.
 */
@ApplicationScoped
public class DynamoDbDataSourceInvoker implements DataSourceInvoker {

    private static final Logger LOG = Logger.getLogger(DynamoDbDataSourceInvoker.class);

    private static final Set<String> DEFERRED_OPERATIONS = Set.of(
            "UpdateItem", "DeleteItem", "Query", "Scan",
            "BatchGetItem", "BatchPutItem", "BatchDeleteItem",
            "TransactGetItems", "TransactWriteItems", "Sync");

    private final DynamoDbService dynamoDbService;
    private final ObjectMapper objectMapper;
    private final AppSyncDataSourceRoleAuthorizer roleAuthorizer;

    @Inject
    public DynamoDbDataSourceInvoker(DynamoDbService dynamoDbService, ObjectMapper objectMapper,
                                     AppSyncDataSourceRoleAuthorizer roleAuthorizer) {
        this.dynamoDbService = dynamoDbService;
        this.objectMapper = objectMapper;
        this.roleAuthorizer = roleAuthorizer;
    }

    @Override
    public DataSourceResult invoke(DataSource dataSource, Map<String, Object> request, GraphQlRequestContext context) {
        Map<String, Object> config = dataSource.getDynamodbConfig();
        String tableName = config != null ? asString(config.get("tableName")) : null;
        if (tableName == null || tableName.isBlank()) {
            return DataSourceResult.error(FieldError.of("InternalFailure",
                    "Data source " + dataSource.getName() + " has no tableName configured"));
        }
        String region = asString(config.get("awsRegion"));
        if (region == null || region.isBlank()) {
            region = context.region();
        }

        String operation = asString(request.get("operation"));
        if (operation == null || operation.isBlank()) {
            throw new InvalidRequestDocumentException("Missing operation in DynamoDB request document");
        }
        if (DEFERRED_OPERATIONS.contains(operation)) {
            throw new UnsupportedDataSourceOperationException(
                    "DynamoDB operation " + operation + " is not yet supported by Floci");
        }

        try {
            return switch (operation) {
                case "GetItem" -> getItem(dataSource, tableName, request, region, context);
                case "PutItem" -> putItem(dataSource, tableName, request, region, context);
                default -> throw new InvalidRequestDocumentException("Unsupported operation '" + operation + "'");
            };
        } catch (ConditionalCheckFailedException e) {
            return new DataSourceResult(DynamoDbAttributeValues.toPlainItem(e.getItem()),
                    FieldError.of("DynamoDB:ConditionalCheckFailedException", e.getMessage()));
        } catch (AwsException e) {
            return DataSourceResult.error(FieldError.of("DynamoDB:" + e.getErrorCode(), e.getMessage()));
        } catch (InvalidRequestDocumentException | UnsupportedDataSourceOperationException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.errorv(e, "DynamoDB data source {0} failed for operation {1}", dataSource.getName(), operation);
            return DataSourceResult.error(FieldError.of("DynamoDB:InternalFailure", e.getMessage()));
        }
    }

    private DataSourceResult getItem(DataSource dataSource, String tableName, Map<String, Object> request,
                                     String region, GraphQlRequestContext context) {
        if (request.containsKey("projection")) {
            throw new UnsupportedDataSourceOperationException(
                    "DynamoDB GetItem projection is not yet supported by Floci");
        }
        JsonNode key = objectMapper.valueToTree(requireObject(request, "key"));
        Optional<FieldError> denied = roleAuthorizer.authorizeDynamoDb(
                dataSource, "dynamodb:GetItem", tableName, region, context);
        if (denied.isPresent()) {
            return DataSourceResult.error(denied.get());
        }
        JsonNode item = dynamoDbService.getItem(tableName, key, region);
        return DataSourceResult.of(DynamoDbAttributeValues.toPlainItem(item));
    }

    private DataSourceResult putItem(DataSource dataSource, String tableName, Map<String, Object> request,
                                     String region, GraphQlRequestContext context) {
        Map<String, Object> key = requireObject(request, "key");
        Map<String, Object> attributeValues = optionalObject(request, "attributeValues");

        // Key attributes win over attributeValues: Amplify templates carry the id in both.
        Map<String, Object> item = new LinkedHashMap<>();
        if (attributeValues != null) {
            item.putAll(attributeValues);
        }
        item.putAll(key);
        JsonNode itemNode = objectMapper.valueToTree(item);

        Map<String, Object> condition = optionalObject(request, "condition");
        String expression = null;
        JsonNode names = null;
        JsonNode values = null;
        if (condition != null) {
            expression = asString(condition.get("expression"));
            if (expression == null || expression.isBlank()) {
                throw new InvalidRequestDocumentException("condition.expression is required when condition is present");
            }
            Map<String, Object> exprNames = optionalObject(condition, "expressionNames");
            Map<String, Object> exprValues = optionalObject(condition, "expressionValues");
            names = exprNames != null ? objectMapper.valueToTree(exprNames) : null;
            values = exprValues != null ? objectMapper.valueToTree(exprValues) : null;
        }

        Optional<FieldError> denied = roleAuthorizer.authorizeDynamoDb(
                dataSource, "dynamodb:PutItem", tableName, region, context);
        if (denied.isPresent()) {
            return DataSourceResult.error(denied.get());
        }
        dynamoDbService.putItem(tableName, itemNode, expression, names, values, region, "ALL_OLD");
        return DataSourceResult.of(DynamoDbAttributeValues.toPlainItem(itemNode));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Map<String, Object> parent, String field) {
        Object value = parent.get(field);
        if (!(value instanceof Map<?, ?>)) {
            throw new InvalidRequestDocumentException("'" + field + "' is required and must be an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionalObject(Map<String, Object> parent, String field) {
        Object value = parent.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new InvalidRequestDocumentException("'" + field + "' must be an object");
        }
        return (Map<String, Object>) value;
    }

    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
