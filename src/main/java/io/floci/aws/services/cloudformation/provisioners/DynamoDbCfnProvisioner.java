package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.dynamodb.DynamoDbFacade;
import io.floci.aws.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.floci.aws.services.dynamodb.backend.DynamoDbTableAccess.TimeToLive;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.GlobalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.KinesisStreamingDestination;
import io.floci.aws.services.dynamodb.model.LocalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Provisions {@code AWS::DynamoDB::Table}, {@code AWS::DynamoDB::GlobalTable} and the CDK legacy
 * global-table custom resource {@code Custom::DynamoDBReplica}.
 *
 * <p>Extracted from the former CloudFormation monolith. A global table is provisioned as a
 * plain table and then its {@code Replicas} property is reconciled against the tracked replica
 * regions: declared regions are added and dropped ones removed, so an UpdateStack that changes the
 * Replicas list converges. The deployment region is served by the table itself and is filtered out
 * rather than tracked as a replica. The replica custom resource is the
 * one the CDK legacy global table ({@code dynamodb.Table.replicationRegions}) emits per replica
 * region. Its provider Lambda only calls UpdateTable with a ReplicaUpdates Create, so that call is
 * applied directly against the DynamoDB service rather than through the async CDK Provider
 * framework, and its delete removes the replica the same way instead of invoking the handler.
 * {@code Ref} of a replica follows CDK's {@code <tableName>-<region>} format.
 *
 * <p>A table's {@code TimeToLiveSpecification} is reconciled against what DescribeTimeToLive
 * reports, so UpdateTimeToLive is called only when the setting differs. Its
 * {@code KinesisStreamSpecification} is reconciled against the table's active Kinesis streaming
 * destinations the same way. An in-place update of an {@code AWS::DynamoDB::Table} keeps the tags,
 * stream, TTL setting and Kinesis streaming destinations it replaces, so a failed stack update can
 * put all four back.
 */
@ApplicationScoped
public class DynamoDbCfnProvisioner implements CfnResourceProvisioner {

    static final String TABLE = "AWS::DynamoDB::Table";
    static final String GLOBAL_TABLE = "AWS::DynamoDB::GlobalTable";
    static final String REPLICA = "Custom::DynamoDBReplica";

    private static final Logger LOG = Logger.getLogger(DynamoDbCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TimeToLive TIME_TO_LIVE_OFF = new TimeToLive(false, null);

    private static final String REPLICA_TABLE_NAME_ATTR = "TableName";
    private static final String REPLICA_REGION_ATTR = "__FlociDynamoDbReplicaRegion";
    private static final String REPLICA_SKIP_DELETION_ATTR = "__FlociDynamoDbReplicaSkipDeletion";
    private static final int TABLE_NAME_MAX_LENGTH = 255;
    private static final String KINESIS_STREAM_SPECIFICATION = "KinesisStreamSpecification";
    private static final String DESTINATION_ACTIVE = "ACTIVE";
    private static final int STREAM_ARN_MIN_LENGTH = 37;
    private static final int STREAM_ARN_MAX_LENGTH = 1024;

    /** One Kinesis streaming destination a table should forward to, with its timestamp precision. */
    private record KinesisDestinationSpec(String streamArn, String precision) {
    }

    private final DynamoDbFacade dynamoDb;

    @Inject
    public DynamoDbCfnProvisioner(DynamoDbFacade dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TABLE, GLOBAL_TABLE, REPLICA);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::DynamoDB::Table", "AWS::DynamoDB::GlobalTable" -> provisionTable(r, props, ctx);
            case "Custom::DynamoDBReplica" -> provisionReplica(r, props, ctx);
            default -> throw new IllegalStateException(
                    "DynamoDbCfnProvisioner received an unsupported type: " + r.getResourceType());
        }
    }

    /**
     * A replica's delete needs the table name and region recorded at create time; the physical id
     * alone only carries them in CDK's {@code <tableName>-<region>} form, which is the fallback.
     */
    @Override
    public void delete(StackResource resource, String region) {
        switch (resource.getResourceType()) {
            case "Custom::DynamoDBReplica" -> deleteReplica(resource, region);
            default -> delete(resource.getResourceType(), resource.getPhysicalId(), region);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case "AWS::DynamoDB::Table", "AWS::DynamoDB::GlobalTable" -> CfnDeletes.safeDelete(
                    "DynamoDB table", physicalId,
                    // Deletes get no ProvisionContext; the engine and Cloud Control run them as the stack account.
                    () -> dynamoDb.tables().deleteTable(dynamoDb.scope(region), physicalId),
                    "ResourceNotFoundException");
            // Nothing to derive from the id alone once the attributes are gone; the replica stays.
            case "Custom::DynamoDBReplica" -> LOG.warnv(
                    "No delete implemented for resource type {0}: {1} is not removed here.",
                    resourceType, physicalId);
            default -> throw new IllegalStateException(
                    "DynamoDbCfnProvisioner received an unsupported type: " + resourceType);
        }
    }

    private void provisionTable(StackResource r, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        r.getAttributes().remove(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR);
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        // TableName is create-only, so an update without one must keep the name generated on
        // create rather than mint another: a fresh name here created a second table on every
        // UpdateStack and re-pointed Ref at it.
        String tableName = ctx.stablePhysicalName(ctx.resolveOptional(props, "TableName"),
                r.getLogicalId(), TABLE_NAME_MAX_LENGTH, false);
        JsonNode ttlSpec = props != null ? engine.resolveNode(props.get("TimeToLiveSpecification")) : null;
        boolean ttlDeclared = ttlSpec != null && !ttlSpec.isNull();
        if (ttlDeclared) {
            CfnPropertyTypes.requireType("TimeToLiveSpecification", ttlSpec, JsonNodeType.OBJECT);
        }
        // A stack that never declared TTL leaves it alone, as AWS does, so TTL set outside the stack
        // survives an update; removing a block the previous template declared turns TTL off.
        boolean ttlManaged = ttlDeclared || ctx.previouslyDeclared("TimeToLiveSpecification");
        TimeToLive desiredTtl = ttlDeclared ? declaredTimeToLive(ttlSpec, engine) : TIME_TO_LIVE_OFF;
        JsonNode kinesisSpec = props != null ? engine.resolveNode(props.get(KINESIS_STREAM_SPECIFICATION)) : null;
        boolean kinesisDeclared = kinesisSpec != null && !kinesisSpec.isNull();
        if (kinesisDeclared) {
            CfnPropertyTypes.requireType(KINESIS_STREAM_SPECIFICATION, kinesisSpec, JsonNodeType.OBJECT);
        }
        boolean kinesisManaged = kinesisDeclared || ctx.previouslyDeclared(KINESIS_STREAM_SPECIFICATION);
        List<KinesisDestinationSpec> desiredKinesis = kinesisDeclared
                ? List.of(declaredKinesisDestination(kinesisSpec, engine))
                : List.of();

        List<KeySchemaElement> keySchema = new ArrayList<>();
        List<AttributeDefinition> attrDefs = new ArrayList<>();
        List<GlobalSecondaryIndex> gsis = new ArrayList<>();
        List<LocalSecondaryIndex> lsis = new ArrayList<>();

        if (props != null && props.has("KeySchema")) {
            for (JsonNode ks : props.get("KeySchema")) {
                String attrName = engine.resolve(ks.get("AttributeName"));
                String keyType = engine.resolve(ks.get("KeyType"));
                keySchema.add(new KeySchemaElement(attrName, keyType));
            }
        }
        if (props != null && props.has("AttributeDefinitions")) {
            for (JsonNode ad : props.get("AttributeDefinitions")) {
                String attrName = engine.resolve(ad.get("AttributeName"));
                String attrType = engine.resolve(ad.get("AttributeType"));
                attrDefs.add(new AttributeDefinition(attrName, attrType));
            }
        }

        if (props != null && props.has("GlobalSecondaryIndexes")) {
            for (JsonNode gsiNode : props.get("GlobalSecondaryIndexes")) {
                String indexName = engine.resolve(gsiNode.get("IndexName"));
                List<KeySchemaElement> gsiKeySchema = new ArrayList<>();
                if (gsiNode.has("KeySchema")) {
                    for (JsonNode ks : gsiNode.get("KeySchema")) {
                        String attrName = engine.resolve(ks.get("AttributeName"));
                        String keyType = engine.resolve(ks.get("KeyType"));
                        gsiKeySchema.add(new KeySchemaElement(attrName, keyType));
                    }
                }
                String projectionType = "ALL";
                JsonNode projection = gsiNode.get("Projection");
                List<String> nonKeyAttributes = new ArrayList<>();
                if (projection != null && projection.has("ProjectionType")) {
                    projectionType = engine.resolve(projection.get("ProjectionType"));
                    JsonNode nonKeyAttrArray = projection.path("NonKeyAttributes");
                    if (!nonKeyAttrArray.isMissingNode() && nonKeyAttrArray.isArray()) {
                        for (JsonNode nonKeyAttr : nonKeyAttrArray) {
                            nonKeyAttributes.add(nonKeyAttr.asText());
                        }
                    }
                }
                gsis.add(new GlobalSecondaryIndex(indexName, gsiKeySchema, null, projectionType, nonKeyAttributes));
            }
        }

        if (props != null && props.has("LocalSecondaryIndexes")) {
            for (JsonNode lsiNode : props.get("LocalSecondaryIndexes")) {
                String indexName = engine.resolve(lsiNode.get("IndexName"));
                List<KeySchemaElement> lsiKeySchema = new ArrayList<>();
                if (lsiNode.has("KeySchema")) {
                    for (JsonNode ks : lsiNode.get("KeySchema")) {
                        String attrName = engine.resolve(ks.get("AttributeName"));
                        String keyType = engine.resolve(ks.get("KeyType"));
                        lsiKeySchema.add(new KeySchemaElement(attrName, keyType));
                    }
                }
                String projectionType = "ALL";
                JsonNode projection = lsiNode.get("Projection");
                if (projection != null && projection.has("ProjectionType")) {
                    projectionType = engine.resolve(projection.get("ProjectionType"));
                }
                lsis.add(new LocalSecondaryIndex(indexName, lsiKeySchema, null, projectionType));
            }
        }

        if (keySchema.isEmpty()) {
            keySchema.add(new KeySchemaElement("id", "HASH"));
            attrDefs.add(new AttributeDefinition("id", "S"));
        }

        Scope scope = new Scope(ctx.accountId(), region);
        boolean onDemand = "PAY_PER_REQUEST".equals(ctx.resolveOptional(props, "BillingMode"));
        TableDefinition table;
        boolean created = false;
        try {
            table = onDemand
                    ? dynamoDb.tables().createOnDemandTable(scope, tableName, keySchema, attrDefs, gsis, lsis)
                    : dynamoDb.tables().createTable(scope, tableName, keySchema, attrDefs, null, null, gsis, lsis);
            created = true;
        } catch (AwsException e) {
            if (!"ResourceInUseException".equals(e.getErrorCode())) {
                throw e;
            }
            table = dynamoDb.tables().describeTable(scope, tableName);
        }

        try {
            // TTL is read when the template declares it, and on an in-place update for the rollback
            // snapshot, so UpdateTimeToLive is called only when something differs. A rename of an
            // enabled TTL attribute fails here, and the snapshot is taken here, before anything changes.
            // A table this attempt created has no prior state to snapshot; a failure deletes it instead.
            boolean inPlaceUpdate = !created && ctx.reusesPriorEntity(tableName);
            TimeToLive currentTtl = null;
            if (ttlDeclared || inPlaceUpdate) {
                currentTtl = dynamoDb.tables().timeToLive(scope, tableName);
                requireTimeToLiveChangeAllowed(currentTtl, desiredTtl);
            }
            Map<String, String> currentTags = dynamoDb.tables().listTagsOfResource(scope, table.getTableArn());
            if (inPlaceUpdate && TABLE.equals(r.getResourceType())) {
                snapshotBeforeUpdate(r, scope, tableName, table, currentTags, currentTtl, kinesisManaged);
            }

            reconcileTags(scope, table.getTableArn(), currentTags,
                    parseCfnTags(props != null ? props.get("Tags") : null, engine));

            // A template that declares StreamSpecification wants a stream. Unlike the DynamoDB API,
            // the CloudFormation property carries no StreamEnabled flag: declaring the block IS the
            // request, so its presence alone turns the stream on. Without this the table is created
            // streamless and an event source mapping polls its ARN forever.
            //
            // Removing the block on an update is the inverse request: the stream is reconciled off,
            // or a table updated out of streaming would keep emitting records to whatever still holds
            // its ARN.
            JsonNode streamSpec = props != null ? props.path("StreamSpecification") : null;
            if (streamSpec != null && streamSpec.isObject()) {
                String viewType = streamSpec.has("StreamViewType")
                        ? engine.resolve(streamSpec.get("StreamViewType"))
                        : null;
                table = dynamoDb.tables().enableStream(scope, tableName, viewType);
            } else if (table.isStreamEnabled()) {
                table = dynamoDb.tables().disableStream(scope, tableName);
            }

            // TimeToLiveSpecification is reconciled like the stream: declaring it enabled turns TTL on
            // for the attribute, and Enabled false or a block removed on an update turns it off.
            if (ttlManaged && currentTtl != null) {
                reconcileTimeToLive(scope, tableName, currentTtl, desiredTtl);
            }

            if (kinesisManaged) {
                table = reconcileKinesisDestinations(scope, tableName, table, desiredKinesis, true);
            }

            r.setPhysicalId(tableName);
            r.getAttributes().put("Arn", table.getTableArn());
            // Only the global table's registry schema declares TableId read-only; a plain table exposes
            // Arn and StreamArn alone, so publishing it there would accept a Fn::GetAtt CloudFormation
            // rejects. DescribeTable reports the same value, so Fn::GetAtt and the API agree.
            if (GLOBAL_TABLE.equals(r.getResourceType())) {
                r.getAttributes().put("TableId", table.getTableId());
                reconcileGlobalTableReplicas(tableName, props, ctx);
            }
            // Only a live stream has an ARN worth handing to Fn::GetAtt. Publishing one unconditionally
            // resolved to nothing on a streamless table; publishing the retained ARN of a stream that
            // has since been switched off would resolve to something no longer running. An update
            // starts from the previous attributes, so the stale entry has to be removed rather than
            // merely left unwritten.
            publishStreamArn(r, table);
        } catch (RuntimeException failure) {
            // A table this attempt created would outlive the failure otherwise: the create rollback
            // deletes only a resource that carries its physical id, and a replacing update puts the
            // previous resource back. An adopted table, or one an in-place update changes, is kept.
            // DeletionPolicy Retain does not keep it: AWS validates the tags and the stream inside
            // CreateTable, so on AWS such a failure leaves no table to retain.
            if (created) {
                deleteCreatedTable(r, scope, tableName, ctx.isUpdate(), failure);
            }
            throw failure;
        }
    }

    /**
     * Deletes the table a failed provision created. A delete that fails on create leaves the table
     * owned by the resource, so the stack's create rollback retries it. On a replacing update the
     * engine puts the previous resource back and never learns this table, so the failure is
     * reported as a rollback failure that names it.
     */
    private void deleteCreatedTable(StackResource r, Scope scope, String tableName, boolean update,
                                    RuntimeException failure) {
        try {
            CfnDeletes.safeDelete("DynamoDB table", tableName,
                    () -> dynamoDb.tables().deleteTable(scope, tableName), "ResourceNotFoundException");
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            String reason = "Could not delete DynamoDB table " + tableName + ", created for "
                    + r.getLogicalId() + " by a failed provision: " + cleanupFailure.getMessage();
            LOG.warnv("{0}", reason);
            if (update) {
                r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
            } else {
                r.setPhysicalId(tableName);
                r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
            }
        }
    }

    private static void publishStreamArn(StackResource r, TableDefinition table) {
        if (table.isStreamEnabled() && table.getStreamArn() != null) {
            r.getAttributes().put("StreamArn", table.getStreamArn());
        } else {
            r.getAttributes().remove("StreamArn");
        }
    }

    private void reconcileTags(Scope scope, String tableArn, Map<String, String> current,
                               Map<String, String> desired) {
        List<String> staleTags = ProvisionContext.staleTagKeys(current, desired);
        if (!staleTags.isEmpty()) {
            dynamoDb.tables().untagResource(scope, tableArn, staleTags);
        }
        if (!desired.isEmpty()) {
            dynamoDb.tables().tagResource(scope, tableArn, desired);
        }
    }

    /**
     * {@code Enabled} is required and may arrive as the string {@code "true"}, so it goes through
     * the engine. Both checks run before the table is created, as AWS validates the template
     * before any resource exists. The failures carry the error code AWS CloudFormation reports.
     */
    private static TimeToLive declaredTimeToLive(JsonNode spec, CloudFormationTemplateEngine engine) {
        JsonNode enabledNode = spec.get("Enabled");
        if (enabledNode == null || enabledNode.isNull()) {
            throw new AwsException("InvalidRequest",
                    "Model validation failed (#/TimeToLiveSpecification: required key [Enabled] not found)", 400);
        }
        String enabledText = engine.resolve(enabledNode);
        if (!"true".equalsIgnoreCase(enabledText) && !"false".equalsIgnoreCase(enabledText)) {
            throw new AwsException("InvalidRequest", "Model validation failed (#/TimeToLiveSpecification/Enabled:"
                    + " expected type: Boolean, found: String)", 400);
        }
        boolean enabled = Boolean.parseBoolean(enabledText);
        String attributeName = spec.has("AttributeName") ? engine.resolve(spec.get("AttributeName")) : null;
        if (enabled && (attributeName == null || attributeName.isBlank())) {
            throw new AwsException("InvalidRequest", "Invalid request provided: AttributeName property of"
                    + " TimeToLiveSpecification is required when TTL status is enabled or when enabling TTL.", 400);
        }
        return new TimeToLive(enabled, attributeName);
    }

    /**
     * DynamoDB refuses to enable a second attribute while one is enabled, and CloudFormation
     * renames one by disabling TTL in an update and enabling the new attribute in a later one. The
     * failure carries the handler message and error code AWS CloudFormation reports.
     */
    private static void requireTimeToLiveChangeAllowed(TimeToLive current, TimeToLive desired) {
        if (desired.enabled() && current.enabled() && !desired.attributeName().equals(current.attributeName())) {
            throw new AwsException("InvalidRequest", "Invalid request provided: Cannot change time-to-live attribute"
                    + " name. To update this property, you must first disable TTL then enable TTL with the new"
                    + " attribute name.", 400);
        }
    }

    /** Calls UpdateTimeToLive only when the table's setting differs; disabling names the current attribute. */
    private void reconcileTimeToLive(Scope scope, String tableName, TimeToLive current, TimeToLive desired) {
        if (desired.enabled() && !current.enabled()) {
            dynamoDb.tables().updateTimeToLive(scope, tableName, desired.attributeName(), true);
        } else if (!desired.enabled() && current.enabled()) {
            dynamoDb.tables().updateTimeToLive(scope, tableName, current.attributeName(), false);
        }
    }

    /**
     * Runs before the table is created, as AWS validates the template before any resource exists. An
     * empty {@code StreamArn} is present, so it passes here and fails the API's length check later.
     */
    private static KinesisDestinationSpec declaredKinesisDestination(JsonNode spec,
                                                                     CloudFormationTemplateEngine engine) {
        JsonNode streamArnNode = spec.get("StreamArn");
        String streamArn = streamArnNode == null || streamArnNode.isNull() ? null : engine.resolve(streamArnNode);
        if (streamArn == null) {
            throw new AwsException("InvalidRequest",
                    "Model validation failed (#/KinesisStreamSpecification: required key [StreamArn] not found)", 400);
        }
        JsonNode precisionNode = spec.get("ApproximateCreationDateTimePrecision");
        String precision = precisionNode == null || precisionNode.isNull()
                ? KinesisStreamingDestination.PRECISION_MILLISECOND
                : engine.resolve(precisionNode);
        if (!KinesisStreamingDestination.PRECISION_MILLISECOND.equals(precision)
                && !KinesisStreamingDestination.PRECISION_MICROSECOND.equals(precision)) {
            throw new AwsException("InvalidRequest", "Model validation failed"
                    + " (#/KinesisStreamSpecification/ApproximateCreationDateTimePrecision:"
                    + " failed validation constraint for keyword [enum])", 400);
        }
        return new KinesisDestinationSpec(streamArn, precision);
    }

    private static List<KinesisDestinationSpec> activeKinesisDestinations(TableDefinition table) {
        List<KinesisDestinationSpec> active = new ArrayList<>();
        for (KinesisStreamingDestination destination : table.getKinesisStreamingDestinations()) {
            if (DESTINATION_ACTIVE.equals(destination.getDestinationStatus())) {
                active.add(new KinesisDestinationSpec(destination.getStreamArn(),
                        destination.getApproximateCreationDateTimePrecision()));
            }
        }
        return active;
    }

    /**
     * Leaves exactly the desired destinations active. A destination whose precision alone differs is
     * updated in place, as CloudFormation does with one UpdateKinesisStreamingDestination call. The
     * active list is copied first: the service may hand back the stored table, whose destination list
     * the calls below change.
     * {@code fromTemplate} checks every declared {@code StreamArn} as the AWS handler does, whether
     * or not the table already forwards to it; a rollback restores destinations the table already had
     * and enables them as they were.
     */
    private TableDefinition reconcileKinesisDestinations(Scope scope, String tableName, TableDefinition table,
                                                         List<KinesisDestinationSpec> desired, boolean fromTemplate) {
        if (fromTemplate) {
            for (KinesisDestinationSpec destination : desired) {
                requireUsableStreamArn(tableName, destination.streamArn());
            }
        }
        List<KinesisDestinationSpec> active = activeKinesisDestinations(table);
        for (KinesisDestinationSpec destination : active) {
            if (!containsStream(desired, destination.streamArn())) {
                table = dynamoDb.tables().disableKinesisStreamingDestination(scope, tableName,
                        destination.streamArn());
            }
        }
        for (KinesisDestinationSpec destination : desired) {
            if (active.contains(destination)) {
                continue;
            }
            if (containsStream(active, destination.streamArn())) {
                table = dynamoDb.tables().updateKinesisStreamingDestination(scope, tableName,
                        destination.streamArn(), destination.precision());
            } else if (fromTemplate) {
                table = enableKinesisDestination(scope, tableName, destination);
            } else {
                table = dynamoDb.tables().enableKinesisStreamingDestination(scope, tableName,
                        destination.streamArn(), destination.precision());
            }
        }
        return table;
    }

    /**
     * Fails the way the AWS handler does. A {@code StreamArn} outside the API's 37 to 1024 characters
     * fails EnableKinesisStreamingDestination's own validation. Any other value that is not a Kinesis
     * stream ARN, such as a stream's name from {@code Ref}, never becomes active, so the table does
     * not stabilize.
     */
    private static void requireUsableStreamArn(String tableName, String streamArn) {
        String violation = SmithyValidationErrors.lengthOutOfRange("streamArn", streamArn,
                STREAM_ARN_MIN_LENGTH, STREAM_ARN_MAX_LENGTH);
        if (violation != null) {
            throw SmithyValidationErrors.exception("InvalidRequest", violation);
        }
        if (!AwsArnUtils.isArnFor(streamArn, "kinesis")) {
            throw notStabilized(tableName);
        }
    }

    /** A stream that does not exist, as the table's account and region see it, never becomes active either. */
    private TableDefinition enableKinesisDestination(Scope scope, String tableName,
                                                     KinesisDestinationSpec destination) {
        try {
            return dynamoDb.tables().enableKinesisStreamingDestination(scope, tableName, destination.streamArn(),
                    destination.precision());
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                throw notStabilized(tableName);
            }
            throw e;
        }
    }

    private static AwsException notStabilized(String tableName) {
        return new AwsException("NotStabilized", "Resource of type '" + TABLE + "' with identifier '" + tableName
                + "' did not stabilize.", 400);
    }

    private static boolean containsStream(List<KinesisDestinationSpec> destinations, String streamArn) {
        return destinations.stream().anyMatch(destination -> destination.streamArn().equals(streamArn));
    }

    /**
     * Keeps the tags, stream setting, TTL setting and active Kinesis streaming destinations the table
     * has before an in-place update changes them, with the account and region that address it, so
     * {@link #rollbackUpdate} can put all four back.
     */
    private static void snapshotBeforeUpdate(StackResource r, Scope scope, String tableName, TableDefinition table,
                                             Map<String, String> tags, TimeToLive ttl, boolean kinesisManaged) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("accountId", scope.accountId());
        snapshot.put("region", scope.region());
        snapshot.put("tableName", tableName);
        ObjectNode tagsNode = snapshot.putObject("tags");
        tags.forEach(tagsNode::put);
        snapshot.put("streamEnabled", table.isStreamEnabled());
        snapshot.put("streamViewType", table.getStreamViewType());
        snapshot.put("ttlEnabled", ttl.enabled());
        snapshot.put("ttlAttributeName", ttl.attributeName());
        if (kinesisManaged) {
            ArrayNode destinations = snapshot.putArray("kinesisDestinations");
            for (KinesisDestinationSpec destination : activeKinesisDestinations(table)) {
                destinations.addObject()
                        .put("streamArn", destination.streamArn())
                        .put("precision", destination.precision());
            }
        }
        r.getAttributes().put(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    /**
     * An in-place update that failed after its snapshot may already have changed the table, so the
     * engine keeps this attempt for {@link #rollbackUpdate} instead of putting the previous resource
     * back over it. A failure before the snapshot changed nothing and is left to the engine.
     */
    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR);
    }

    /**
     * The engine calls this for every resource once a stack update commits. A committed update is
     * never rolled back, so its snapshot is dropped here: left on the resource, a retried rollback
     * of a later update would put the table back to its state before this one.
     */
    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR);
        return UpdateCleanupResult.notApplicable();
    }

    /**
     * Puts back the tags, stream setting, TTL setting and Kinesis streaming destinations an in-place
     * table update changed. All four go back or none is claimed: restoring one alone would report a
     * rollback while the others stayed changed. A snapshot without destinations leaves them as they
     * are. Only an in-place {@code AWS::DynamoDB::Table} update takes the
     * snapshot, so a global table or a replacing update still reports that rollback is not
     * implemented. The snapshot is spent only once the restore succeeded: a restore that throws
     * leaves it in place for the next attempt.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String raw = resource.getAttributes().get(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return false;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the DynamoDB table update snapshot for "
                    + resource.getLogicalId(), e);
        }
        Scope scope = new Scope(snapshot.path("accountId").asText(), snapshot.path("region").asText());
        String tableName = snapshot.path("tableName").asText();
        TableDefinition table = dynamoDb.tables().describeTable(scope, tableName);

        Map<String, String> tags = new LinkedHashMap<>();
        snapshot.path("tags").properties().forEach(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        reconcileTags(scope, table.getTableArn(),
                dynamoDb.tables().listTagsOfResource(scope, table.getTableArn()), tags);

        if (snapshot.path("streamEnabled").asBoolean()) {
            table = dynamoDb.tables().enableStream(scope, tableName, snapshot.path("streamViewType").textValue());
        } else if (table.isStreamEnabled()) {
            table = dynamoDb.tables().disableStream(scope, tableName);
        }

        TimeToLive priorTtl = new TimeToLive(snapshot.path("ttlEnabled").asBoolean(),
                snapshot.path("ttlAttributeName").textValue());
        TimeToLive currentTtl = dynamoDb.tables().timeToLive(scope, tableName);
        requireTimeToLiveChangeAllowed(currentTtl, priorTtl);
        reconcileTimeToLive(scope, tableName, currentTtl, priorTtl);

        if (snapshot.has("kinesisDestinations")) {
            List<KinesisDestinationSpec> priorDestinations = new ArrayList<>();
            for (JsonNode destination : snapshot.path("kinesisDestinations")) {
                priorDestinations.add(new KinesisDestinationSpec(destination.path("streamArn").asText(),
                        destination.path("precision").asText()));
            }
            table = reconcileKinesisDestinations(scope, tableName, table, priorDestinations, false);
        }

        publishStreamArn(resource, table);
        resource.getAttributes().remove(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    /**
     * Reconciles a global table's tracked replica regions to its declared {@code Replicas} property.
     * Serves create and update: declared regions absent from the table are added and tracked regions
     * no longer declared are removed, so an UpdateStack that edits the Replicas list converges rather
     * than only ever growing. The deployment region is served by the table itself and the service
     * rejects a replica there (as the UpdateTable ReplicaUpdates API does), so it is filtered out of
     * the reconcile. The table is still marked a global table homed in that region, so DescribeTable
     * lists the deployment region as an ACTIVE replica alongside the others, as AWS does.
     */
    private void reconcileGlobalTableReplicas(String tableName, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String localRegion = ctx.region();
        List<String> declared = new ArrayList<>();
        JsonNode replicas = props != null ? engine.resolveNode(props.get("Replicas")) : null;
        if (replicas != null && replicas.isArray()) {
            for (JsonNode replica : replicas) {
                String replicaRegion = engine.resolve(replica.path("Region"));
                if (replicaRegion != null && !replicaRegion.isBlank()
                        && !replicaRegion.equals(localRegion) && !declared.contains(replicaRegion)) {
                    declared.add(replicaRegion);
                }
            }
        }

        Scope scope = new Scope(ctx.accountId(), localRegion);
        TableDefinition table = dynamoDb.tables().describeTable(scope, tableName);
        // This resource is a global table, so mark it homed in the deployment region even when it
        // declares no other replica: DescribeTable then lists the home region as an ACTIVE replica,
        // the single-region global table AWS reports (and CDK TableV2 emits by default).
        dynamoDb.tables().ensureGlobalTable(scope, tableName);
        List<String> existing = table.getReplicaRegions();
        List<String> toAdd = new ArrayList<>();
        for (String replicaRegion : declared) {
            if (!existing.contains(replicaRegion)) {
                toAdd.add(replicaRegion);
            }
        }
        List<String> toRemove = new ArrayList<>();
        for (String replicaRegion : existing) {
            if (!declared.contains(replicaRegion)) {
                toRemove.add(replicaRegion);
            }
        }
        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            return;
        }
        dynamoDb.tables().applyReplicaUpdates(scope, tableName, toAdd, toRemove);
    }

    private void provisionReplica(StackResource r, JsonNode props, ProvisionContext ctx) {
        String tableName = ctx.resolveOptional(props, "TableName");
        String replicaRegion = ctx.resolveOptional(props, "Region");
        if (tableName == null || tableName.isBlank()) {
            throw new AwsException("ValidationError",
                    "Custom::DynamoDBReplica " + r.getLogicalId() + " is missing TableName", 400);
        }
        if (replicaRegion == null || replicaRegion.isBlank()) {
            throw new AwsException("ValidationError",
                    "Custom::DynamoDBReplica " + r.getLogicalId() + " is missing Region", 400);
        }
        String priorTableName = r.getAttributes().get(REPLICA_TABLE_NAME_ATTR);
        String priorRegion = r.getAttributes().get(REPLICA_REGION_ATTR);
        if (priorRegion == null || priorRegion.isBlank()) {
            priorRegion = replicaRegionFromPhysicalId(
                    r.getPhysicalId(), priorTableName != null ? priorTableName : tableName);
        }
        List<String> removeRegions = priorRegion != null
                && !priorRegion.isBlank()
                && !priorRegion.equals(replicaRegion)
                ? List.of(priorRegion)
                : List.of();
        // Validate and persist replacement as one operation so an old-replica removal failure
        // cannot leave the new replica applied while the resource still points at the old region.
        dynamoDb.tables().applyReplicaUpdates(
                new Scope(ctx.accountId(), ctx.region()), tableName, List.of(replicaRegion), removeRegions);
        r.setPhysicalId(tableName + "-" + replicaRegion);
        r.getAttributes().put(REPLICA_TABLE_NAME_ATTR, tableName);
        r.getAttributes().put(REPLICA_REGION_ATTR, replicaRegion);
        r.getAttributes().put(REPLICA_SKIP_DELETION_ATTR,
                Boolean.toString(Boolean.TRUE.equals(
                        parseBooleanOrNull(ctx.resolveOptional(props, "SkipReplicaDeletion")))));
    }

    private void deleteReplica(StackResource r, String region) {
        Map<String, String> attributes = r.getAttributes() != null ? r.getAttributes() : Map.of();
        if (Boolean.parseBoolean(attributes.get(REPLICA_SKIP_DELETION_ATTR))) {
            LOG.debugv("Keeping replica for retained Custom::DynamoDBReplica {0}", r.getLogicalId());
            return;
        }
        String tableName = attributes.get(REPLICA_TABLE_NAME_ATTR);
        String replicaRegion = attributes.get(REPLICA_REGION_ATTR);
        if (replicaRegion == null || replicaRegion.isBlank()) {
            replicaRegion = replicaRegionFromPhysicalId(r.getPhysicalId(), tableName);
        }
        if (tableName == null || tableName.isBlank() || replicaRegion == null || replicaRegion.isBlank()) {
            return;
        }
        // Removing a replica the table no longer has is already a no-op in the service, so the one
        // "already gone" case is the table itself; anything else must reach the stack as DELETE_FAILED.
        String removedTableName = tableName;
        String removedRegion = replicaRegion;
        CfnDeletes.safeDelete("DynamoDB replica", removedTableName + "-" + removedRegion,
                () -> dynamoDb.tables().applyReplicaUpdates(
                        // Ambient stack account, as in delete(String, String, String).
                        dynamoDb.scope(region), removedTableName, List.of(), List.of(removedRegion)),
                "ResourceNotFoundException");
    }

    private static String replicaRegionFromPhysicalId(String physicalId, String tableName) {
        if (physicalId == null || physicalId.isBlank()) {
            return null;
        }
        String prefix = tableName + "-";
        return tableName != null && !tableName.isBlank() && physicalId.startsWith(prefix)
                ? physicalId.substring(prefix.length())
                : physicalId;
    }

    private static Boolean parseBooleanOrNull(String value) {
        return (value == null || value.isBlank()) ? null : Boolean.valueOf(value);
    }

    private Map<String, String> parseCfnTags(JsonNode tagsNode, CloudFormationTemplateEngine engine) {
        tagsNode = engine.resolveNode(tagsNode);
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = engine.resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }
}
