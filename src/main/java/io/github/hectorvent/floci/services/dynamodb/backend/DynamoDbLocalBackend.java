package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbTableNames;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The DynamoDB engine backed by an external, official DynamoDB Local. Wire calls are forwarded
 * through {@link DynamoDbLocalClient} under the caller's account and region; table and stream ARNs
 * are translated between the public shape and Local's fixed {@code ddblocal} shape. Local keeps no
 * tags, so Floci holds them in its own store keyed by account, region and table name.
 */
@ApplicationScoped
@Typed(DynamoDbLocalBackend.class)
public class DynamoDbLocalBackend implements DynamoDbBackend {

    private static final Logger LOG = Logger.getLogger(DynamoDbLocalBackend.class);

    private static final String LOCAL_ARN_PREFIX = "arn:aws:dynamodb:ddblocal:000000000000:"; // partition-literal: DynamoDB Local's fixed ARN prefix
    private static final String LOCAL_REGION = "ddblocal";
    private static final Duration READINESS_BUDGET = Duration.ofSeconds(30);
    private static final Scope PROBE = new Scope("000000000000", "us-east-1"); // partition-literal: readiness probe namespace, Local accepts any region
    private static final String REPLICAS_UNSUPPORTED = "Replicas are not supported by the DynamoDB Local backend";
    private static final Pattern PARTITION = Pattern.compile(AwsArnUtils.PARTITION_REGEX);
    private static final List<String> PUBLIC_ARN_FIELDS = List.of("TableArn", "IndexArn", "LatestStreamArn",
            "StreamArn", "LastEvaluatedStreamArn", "message", "Message");

    private final DynamoDbLocalClient client;
    private final AccountAwareStorageBackend<Map<String, String>> tags;
    private final ObjectMapper objectMapper;
    private final ObjectMapper aws;
    private final Duration readiness;

    @Inject
    public DynamoDbLocalBackend(EmulatorConfig config, StorageFactory storageFactory, ObjectMapper objectMapper) {
        this(new DynamoDbLocalClient(validatedEndpoint(config.services().dynamodb().localEndpoint()),
                        Duration.ofSeconds(config.services().dynamodb().localConnectTimeoutSeconds()),
                        Duration.ofSeconds(config.services().dynamodb().localRequestTimeoutSeconds()), objectMapper),
                storageFactory.create("dynamodb", "dynamodb-local-tags.json",
                        new TypeReference<Map<String, Map<String, String>>>() {}),
                objectMapper, READINESS_BUDGET);
    }

    DynamoDbLocalBackend(DynamoDbLocalClient client, AccountAwareStorageBackend<Map<String, String>> tags,
                         ObjectMapper objectMapper, Duration readiness) {
        this.client = client;
        this.tags = tags;
        this.objectMapper = objectMapper;
        this.aws = objectMapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);
        this.readiness = readiness;
    }

    static URI validatedEndpoint(Optional<String> value) {
        String raw = value.map(String::trim).filter(v -> !v.isEmpty()).orElseThrow(() -> new IllegalStateException(
                "floci.services.dynamodb.local-endpoint is required when floci.services.dynamodb.backend is local"));
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw invalidEndpoint(raw, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw invalidEndpoint(raw, null);
        }
        String host = uri.getHost().replaceFirst("\\.$", "");
        if (AwsPartitions.isDnsSuffix(host) || AwsPartitions.stripKnownDnsSuffix(host).isPresent()) {
            throw new IllegalStateException("floci.services.dynamodb.local-endpoint '" + raw
                    + "' must point at DynamoDB Local, not an AWS endpoint");
        }
        return uri;
    }

    private static IllegalStateException invalidEndpoint(String raw, Exception cause) {
        return new IllegalStateException("floci.services.dynamodb.local-endpoint '" + raw
                + "' is not a valid http or https URL", cause);
    }

    @Override
    public Reply execute(Call call) {
        Scope scope = call.scope();
        String action = call.action();
        JsonNode body = call.body() == null ? objectMapper.createObjectNode() : call.body().deepCopy();
        if (!"CreateTable".equals(action)) {
            toLocal(scope, body);
        }
        Reply served = call.api() != Api.DYNAMODB ? null : switch (action) {
            case "TagResource" -> {
                tagResource(scope, text(body, "ResourceArn"), tagsFrom(body.path("Tags")));
                yield new Reply(200, objectMapper.createObjectNode(), Map.of());
            }
            case "UntagResource" -> {
                List<String> keys = new ArrayList<>();
                body.path("TagKeys").forEach(key -> keys.add(key.asText()));
                untagResource(scope, text(body, "ResourceArn"), keys);
                yield new Reply(200, objectMapper.createObjectNode(), Map.of());
            }
            case "ListTagsOfResource" -> {
                String resourceArn = text(body, "ResourceArn");
                Map<String, String> found;
                try {
                    found = listTagsOfResource(scope, resourceArn);
                } catch (AwsException e) {
                    if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                        throw e;
                    }
                    throw new AwsException("AccessDeniedException",
                            "User is not authorized to perform: dynamodb:ListTagsOfResource on resource: "
                                    + resourceArn, 400);
                }
                ObjectNode reply = objectMapper.createObjectNode();
                ArrayNode tagList = reply.putArray("Tags");
                found.forEach((key, value) -> tagList.addObject().put("Key", key).put("Value", value));
                yield new Reply(200, reply, Map.of());
            }
            case "UpdateTable" -> {
                if (body.has("ReplicaUpdates")) {
                    throw new AwsException("ValidationException", REPLICAS_UNSUPPORTED, 400);
                }
                yield null;
            }
            default -> null;
        };
        if (served != null) {
            return served;
        }
        Reply reply = send(scope, call.api(), action, body);
        if (reply.status() >= 200 && reply.status() < 300 && call.api() == Api.DYNAMODB) {
            JsonNode replyBody = reply.body() == null ? MissingNode.getInstance() : reply.body();
            String tableName = text(replyBody.path("TableDescription"), "TableName");
            if (tableName != null && "CreateTable".equals(action)) {
                tags.putForAccount(scope.accountId(), key(scope.region(), tableName), tagsFrom(body.path("Tags")));
            } else if (tableName != null && "DeleteTable".equals(action)) {
                tags.deleteForAccount(scope.accountId(), key(scope.region(), tableName));
            }
        }
        toPublic(scope, reply.body());
        return reply;
    }

    private Reply send(Scope scope, Api api, String action, JsonNode body) {
        try {
            return client.send(scope, api, action, body);
        } catch (IOException e) {
            throw notAnswered(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw notAnswered(e);
        }
    }

    private AwsException notAnswered(Exception e) {
        return new AwsException("InternalServerError",
                "DynamoDB Local at " + client.endpoint() + " did not answer: " + e, 500);
    }

    static void toLocal(Scope scope, JsonNode node) {
        if (node instanceof ObjectNode object) {
            if (object.get("RequestItems") instanceof ObjectNode requestItems) {
                ObjectNode renamed = object.objectNode();
                for (Map.Entry<String, JsonNode> entry : requestItems.properties()) {
                    String name = tableName(scope, entry.getKey());
                    if (renamed.replace(name, entry.getValue()) != null) {
                        throw new AwsException("ValidationException",
                                "Table " + name + " is named more than once in RequestItems", 400);
                    }
                }
                object.set("RequestItems", renamed);
            }
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                JsonNode value = field.getValue();
                if (value.isTextual() && "TableName".equals(field.getKey())) {
                    field.setValue(TextNode.valueOf(tableName(scope, value.asText())));
                } else if (value.isTextual()
                        && ("StreamArn".equals(field.getKey()) || "ExclusiveStartStreamArn".equals(field.getKey()))) {
                    field.setValue(TextNode.valueOf(localStreamArn(scope, value.asText())));
                } else if (value.isContainerNode()) {
                    toLocal(scope, value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(element -> toLocal(scope, element));
        }
    }

    static String tableName(Scope scope, String value) {
        if (!value.startsWith("arn:")) {
            return value;
        }
        DynamoDbTableNames.resolveWithRegion(value, scope.region());
        return ownTableName(scope, value);
    }

    // A well-formed table ARN of another account, region or partition names no table in this namespace.
    private static String ownTableName(Scope scope, String arn) {
        String name = DynamoDbTableNames.resolve(arn);
        if (!PARTITION.matcher(AwsArnUtils.parse(arn).partition()).matches()) {
            throw new AwsException("ValidationException", "Invalid table ARN: " + arn, 400);
        }
        if (!arn.startsWith(publicPrefix(scope))) {
            throw new AwsException("ResourceNotFoundException",
                    "Requested resource not found: Table: " + arn + " not found", 400);
        }
        return name;
    }

    static String localStreamArn(Scope scope, String value) {
        if (!AwsArnUtils.isArn(value)) {
            return value;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
        if (!"dynamodb".equals(arn.service()) || !arn.resource().contains("/stream/")) {
            return value;
        }
        if (!value.startsWith(publicPrefix(scope))) {
            throw new AwsException("ResourceNotFoundException",
                    "Requested resource not found: Stream: " + value + " not found", 400);
        }
        return LOCAL_ARN_PREFIX + arn.resource();
    }

    private static String publicPrefix(Scope scope) {
        return AwsArnUtils.Arn.of("dynamodb", scope.region(), scope.accountId(), "").toString();
    }

    static void toPublic(Scope scope, JsonNode node) {
        toPublic(node, publicPrefix(scope), scope.region());
    }

    private static void toPublic(JsonNode node, String prefix, String region) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                JsonNode value = field.getValue();
                if (value.isTextual() && PUBLIC_ARN_FIELDS.contains(field.getKey())) {
                    field.setValue(TextNode.valueOf(value.asText().replace(LOCAL_ARN_PREFIX, prefix)));
                } else if (value.isTextual() && "awsRegion".equals(field.getKey())
                        && LOCAL_REGION.equals(value.asText())) {
                    field.setValue(TextNode.valueOf(region));
                } else if (value.isContainerNode()) {
                    toPublic(value, prefix, region);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(element -> toPublic(element, prefix, region));
        }
    }

    private JsonNode call(Scope scope, String action, ObjectNode body) {
        return execute(new Call(scope, Api.DYNAMODB, action, body)).successBody();
    }

    private ObjectNode body(String tableName, Object... nameValuePairs) {
        ObjectNode body = aws.createObjectNode().put("TableName", tableName);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            Object value = nameValuePairs[i + 1];
            if (value == null || value instanceof NullNode || value instanceof MissingNode) {
                continue;
            }
            String name = (String) nameValuePairs[i];
            body.set(name, value instanceof JsonNode node ? node : aws.valueToTree(value));
        }
        return body;
    }

    @Override
    public void putItem(Scope scope, String tableName, JsonNode item, String conditionExpression,
                        JsonNode expressionAttributeNames, JsonNode expressionAttributeValues) {
        call(scope, "PutItem", body(tableName, "Item", item, "ConditionExpression", conditionExpression,
                "ExpressionAttributeNames", expressionAttributeNames,
                "ExpressionAttributeValues", expressionAttributeValues));
    }

    @Override
    public JsonNode getItem(Scope scope, String tableName, JsonNode key) {
        return present(call(scope, "GetItem", body(tableName, "Key", key)).get("Item"));
    }

    @Override
    public void deleteItem(Scope scope, String tableName, JsonNode key, String conditionExpression,
                           JsonNode expressionAttributeNames, JsonNode expressionAttributeValues) {
        call(scope, "DeleteItem", body(tableName, "Key", key, "ConditionExpression", conditionExpression,
                "ExpressionAttributeNames", expressionAttributeNames,
                "ExpressionAttributeValues", expressionAttributeValues));
    }

    @Override
    public JsonNode updateItem(Scope scope, String tableName, JsonNode key, JsonNode attributeUpdates,
                               String updateExpression, JsonNode expressionAttributeNames,
                               JsonNode expressionAttributeValues, String returnValues, String conditionExpression) {
        JsonNode reply = call(scope, "UpdateItem", body(tableName, "Key", key, "AttributeUpdates", attributeUpdates,
                "UpdateExpression", updateExpression, "ExpressionAttributeNames", expressionAttributeNames,
                "ExpressionAttributeValues", expressionAttributeValues, "ReturnValues", returnValues,
                "ConditionExpression", conditionExpression));
        if ("ALL_NEW".equals(returnValues) || "ALL_OLD".equals(returnValues)) {
            return present(reply.get("Attributes"));
        }
        return null;
    }

    @Override
    public ScanPage scan(Scope scope, String tableName, String filterExpression, JsonNode expressionAttributeNames,
                         JsonNode expressionAttributeValues, JsonNode scanFilter, Integer limit,
                         JsonNode exclusiveStartKey) {
        JsonNode reply = call(scope, "Scan", body(tableName, "FilterExpression", filterExpression,
                "ExpressionAttributeNames", expressionAttributeNames,
                "ExpressionAttributeValues", expressionAttributeValues, "ScanFilter", scanFilter, "Limit", limit,
                "ExclusiveStartKey", exclusiveStartKey));
        List<JsonNode> items = new ArrayList<>();
        reply.path("Items").forEach(items::add);
        return new ScanPage(items, reply.path("ScannedCount").asInt(), present(reply.get("LastEvaluatedKey")));
    }

    private static JsonNode present(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    @Override
    public TableDefinition createTable(Scope scope, String tableName, List<KeySchemaElement> keySchema,
                                       List<AttributeDefinition> attributeDefinitions, Long readCapacity,
                                       Long writeCapacity, List<GlobalSecondaryIndex> globalSecondaryIndexes,
                                       List<LocalSecondaryIndex> localSecondaryIndexes) {
        ObjectNode body = body(tableName, "KeySchema", keySchema, "AttributeDefinitions", attributeDefinitions);
        ObjectNode throughput = null;
        if (readCapacity != null && writeCapacity != null) {
            throughput = aws.createObjectNode()
                    .put("ReadCapacityUnits", readCapacity)
                    .put("WriteCapacityUnits", writeCapacity);
            body.put("BillingMode", "PROVISIONED").set("ProvisionedThroughput", throughput);
        } else {
            body.put("BillingMode", "PAY_PER_REQUEST");
        }
        if (globalSecondaryIndexes != null && !globalSecondaryIndexes.isEmpty()) {
            ArrayNode indexes = body.putArray("GlobalSecondaryIndexes");
            for (GlobalSecondaryIndex gsi : globalSecondaryIndexes) {
                ObjectNode index = index(indexes, gsi.getIndexName(), gsi.getKeySchema(), gsi.getProjectionType(),
                        gsi.getNonKeyAttributes());
                if (throughput != null) {
                    index.set("ProvisionedThroughput", throughput.deepCopy());
                }
            }
        }
        if (localSecondaryIndexes != null && !localSecondaryIndexes.isEmpty()) {
            ArrayNode indexes = body.putArray("LocalSecondaryIndexes");
            for (LocalSecondaryIndex lsi : localSecondaryIndexes) {
                index(indexes, lsi.getIndexName(), lsi.getKeySchema(), lsi.getProjectionType(),
                        lsi.getNonKeyAttributes());
            }
        }
        return table(call(scope, "CreateTable", body).path("TableDescription"));
    }

    private ObjectNode index(ArrayNode indexes, String indexName, List<KeySchemaElement> keySchema,
                             String projectionType, List<String> nonKeyAttributes) {
        ObjectNode index = indexes.addObject().put("IndexName", indexName);
        index.set("KeySchema", aws.valueToTree(keySchema));
        ObjectNode projection = index.putObject("Projection")
                .put("ProjectionType", projectionType == null ? "ALL" : projectionType);
        if (nonKeyAttributes != null && !nonKeyAttributes.isEmpty()) {
            projection.set("NonKeyAttributes", aws.valueToTree(nonKeyAttributes));
        }
        return index;
    }

    @Override
    public TableDefinition describeTable(Scope scope, String tableName) {
        return table(call(scope, "DescribeTable", body(tableName)).path("Table"));
    }

    @Override
    public Optional<TableDefinition> findTable(Scope scope, String tableName) {
        try {
            return Optional.of(describeTable(scope, tableName));
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // The seam's direct delete skips deletion protection, like native; the wire DeleteTable still enforces it.
    // ponytail: a DeleteTable that fails after the UpdateTable leaves protection off; the stack delete that asked
    // for removal reports DELETE_FAILED and a retry completes it.
    @Override
    public void deleteTable(Scope scope, String tableName) {
        if (describeTable(scope, tableName).isDeletionProtectionEnabled()) {
            call(scope, "UpdateTable", body(tableName, "DeletionProtectionEnabled", false));
        }
        call(scope, "DeleteTable", body(tableName));
    }

    @Override
    public TableDefinition enableStream(Scope scope, String tableName, String viewType) {
        String type = viewType == null ? "NEW_AND_OLD_IMAGES" : viewType;
        TableDefinition current = describeTable(scope, tableName);
        if (current.isStreamEnabled()) {
            if (viewType == null || type.equals(current.getStreamViewType())) {
                return current;
            }
            // ponytail: Local cannot retarget a live stream, so a new view type starts a new stream ARN
            updateStream(scope, tableName, false, null);
        }
        return updateStream(scope, tableName, true, type);
    }

    @Override
    public TableDefinition disableStream(Scope scope, String tableName) {
        TableDefinition current = describeTable(scope, tableName);
        if (!current.isStreamEnabled()) {
            return current;
        }
        return updateStream(scope, tableName, false, null);
    }

    private TableDefinition updateStream(Scope scope, String tableName, boolean enabled, String viewType) {
        ObjectNode body = body(tableName);
        ObjectNode spec = body.putObject("StreamSpecification").put("StreamEnabled", enabled);
        if (viewType != null) {
            spec.put("StreamViewType", viewType);
        }
        return table(call(scope, "UpdateTable", body).path("TableDescription"));
    }

    private TableDefinition table(JsonNode description) {
        TableDefinition table = aws.convertValue(description, TableDefinition.class);
        JsonNode spec = description.path("StreamSpecification");
        table.setStreamEnabled(spec.path("StreamEnabled").asBoolean(false));
        table.setStreamViewType(text(spec, "StreamViewType"));
        table.setStreamArn(table.isStreamEnabled() ? text(description, "LatestStreamArn") : null);
        table.setTableId(description.path("TableId").asText());
        return table;
    }

    @Override
    public Map<String, String> listTagsOfResource(Scope scope, String resourceArn) {
        return new LinkedHashMap<>(tags.getForAccount(scope.accountId(), existingTableKey(scope, resourceArn))
                .orElse(Map.of()));
    }

    // ponytail: no tag limits or tag-key validation; Floci records what callers send.
    @Override
    public void tagResource(Scope scope, String resourceArn, Map<String, String> tagsToAdd) {
        String key = existingTableKey(scope, resourceArn);
        synchronized (tags) {
            Map<String, String> current = new LinkedHashMap<>(tags.getForAccount(scope.accountId(), key).orElse(Map.of()));
            current.putAll(tagsToAdd);
            tags.putForAccount(scope.accountId(), key, current);
        }
    }

    @Override
    public void untagResource(Scope scope, String resourceArn, List<String> tagKeys) {
        String key = existingTableKey(scope, resourceArn);
        synchronized (tags) {
            Map<String, String> current = new LinkedHashMap<>(tags.getForAccount(scope.accountId(), key).orElse(Map.of()));
            tagKeys.forEach(current::remove);
            tags.putForAccount(scope.accountId(), key, current);
        }
    }

    private String existingTableKey(Scope scope, String resourceArn) {
        if (resourceArn == null || !resourceArn.startsWith("arn:")) {
            throw new AwsException("ValidationException", "Invalid TableArn", 400);
        }
        String name = ownTableName(scope, resourceArn);
        describeTable(scope, name);
        return key(scope.region(), name);
    }

    @Override
    public TableDefinition applyReplicaUpdates(Scope scope, String tableName, List<String> addRegions,
                                               List<String> removeRegions) {
        throw new AwsException("ValidationException", REPLICAS_UNSUPPORTED, 400);
    }

    // ponytail: a single-region global table has no replicas, so it is the table itself.
    @Override
    public TableDefinition ensureGlobalTable(Scope scope, String tableName) {
        return describeTable(scope, tableName);
    }

    // ponytail: Local cannot list its namespaces, so regions come from the tag store: a region Floci
    // has no table record for (made directly on Local, or lost with memory storage) is not listed.
    @Override
    public List<ExplorerResource> resources(String accountId) {
        List<String> regions = tags.keysForAccount(accountId).stream()
                .map(k -> k.substring(0, k.indexOf('/')))
                .distinct()
                .sorted()
                .toList();
        List<ExplorerResource> resources = new ArrayList<>();
        for (String region : regions) {
            Scope scope = new Scope(accountId, region);
            String start = null;
            do {
                ObjectNode request = objectMapper.createObjectNode();
                if (start != null) {
                    request.put("ExclusiveStartTableName", start);
                }
                JsonNode page = call(scope, "ListTables", request);
                for (JsonNode tableName : page.path("TableNames")) {
                    String name = tableName.asText();
                    resources.add(new ExplorerResource(
                            AwsArnUtils.Arn.of("dynamodb", region, accountId, "table/" + name).toString(),
                            "dynamodb:table", "dynamodb", region, accountId, Instant.now(),
                            tags.getForAccount(accountId, key(region, name)).orElse(Map.of())));
                }
                start = text(page, "LastEvaluatedTableName");
            } while (start != null);
        }
        return resources;
    }

    @Override
    public void start() {
        long deadline = System.nanoTime() + readiness.toNanos();
        while (true) {
            if (ready(deadline)) {
                LOG.infov("DynamoDB backend: DynamoDB Local at {0}", client.endpoint());
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for DynamoDB Local at "
                        + client.endpoint(), e);
            }
        }
    }

    private boolean ready(long deadline) {
        Reply reply;
        try {
            reply = client.send(PROBE, Api.DYNAMODB, "ListTables", objectMapper.createObjectNode().put("Limit", 1),
                    Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
        } catch (IOException e) {
            if (System.nanoTime() - deadline >= 0) {
                throw new IllegalStateException("DynamoDB Local at " + client.endpoint() + " is not reachable after "
                        + readiness.toSeconds() + "s", e);
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for DynamoDB Local at " + client.endpoint(), e);
        }
        if (reply.status() >= 200 && reply.status() < 300) {
            return true;
        }
        throw new IllegalStateException("DynamoDB Local at " + client.endpoint() + " answered ListTables with status "
                + reply.status() + ": " + reply.body());
    }

    @Override
    public void checkReset() {
        throw new AwsException("UnsupportedOperation", "The DynamoDB Local backend keeps its data outside Floci; "
                + "reset the external DynamoDB Local instance instead", 409);
    }

    @Override
    public void beforeReset() {
    }

    @Override
    public void reset() {
    }

    @Override
    public void afterReset() {
    }

    @Override
    public void stop() {
        client.close();
    }

    private static String key(String region, String tableName) {
        return region + "/" + tableName;
    }

    private static Map<String, String> tagsFrom(JsonNode tagList) {
        Map<String, String> result = new LinkedHashMap<>();
        tagList.forEach(tag -> result.put(tag.path("Key").asText(), tag.path("Value").asText()));
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
