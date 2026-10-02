package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbTableAccess.TimeToLive;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.LocalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
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
 * reports, so UpdateTimeToLive is called only when the setting differs. An in-place update of an
 * {@code AWS::DynamoDB::Table} keeps the tags, stream and TTL setting it replaces, so a failed
 * stack update can put all three back.
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
        boolean ttlDeclared = ttlSpec != null && ttlSpec.isObject();
        TimeToLive desiredTtl = ttlDeclared ? declaredTimeToLive(r, ttlSpec, engine) : TIME_TO_LIVE_OFF;

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
        TableDefinition table;
        boolean created = false;
        try {
            table = dynamoDb.tables().createTable(scope, tableName, keySchema, attrDefs, null, null, gsis, lsis);
            created = true;
        } catch (AwsException e) {
            if (!"ResourceInUseException".equals(e.getErrorCode())) {
                throw e;
            }
            table = dynamoDb.tables().describeTable(scope, tableName);
        }

        try {
            // TTL is read when the template declares it or an in-place update may have removed it, so
            // UpdateTimeToLive is called only when something differs. A rename of an enabled TTL
            // attribute fails here, and the rollback snapshot is taken here, before anything changes.
            // A table this attempt created has no prior state to snapshot; a failure deletes it instead.
            boolean inPlaceUpdate = !created && ctx.reusesPriorEntity(tableName);
            TimeToLive currentTtl = null;
            if (ttlDeclared || inPlaceUpdate) {
                currentTtl = dynamoDb.tables().timeToLive(scope, tableName);
                requireTimeToLiveChangeAllowed(currentTtl, desiredTtl);
            }
            Map<String, String> currentTags = dynamoDb.tables().listTagsOfResource(scope, table.getTableArn());
            if (inPlaceUpdate && TABLE.equals(r.getResourceType())) {
                snapshotBeforeUpdate(r, scope, tableName, table, currentTags, currentTtl);
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
            if (currentTtl != null) {
                reconcileTimeToLive(scope, tableName, currentTtl, desiredTtl);
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
            if (created) {
                deleteCreatedTable(r, scope, tableName);
            }
            throw failure;
        }
    }

    /**
     * Deletes the table a failed provision created. A delete that fails leaves the table owned by
     * the resource, so the stack's create rollback retries it.
     */
    private void deleteCreatedTable(StackResource r, Scope scope, String tableName) {
        try {
            CfnDeletes.safeDelete("DynamoDB table", tableName,
                    () -> dynamoDb.tables().deleteTable(scope, tableName), "ResourceNotFoundException");
        } catch (RuntimeException cleanupFailure) {
            LOG.warnv("Could not delete DynamoDB table {0} created by the failed provision of {1}: {2}",
                    tableName, r.getLogicalId(), cleanupFailure.getMessage());
            r.setPhysicalId(tableName);
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
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

    /** {@code Enabled} may arrive as the string {@code "true"}, so it goes through the engine. */
    private static TimeToLive declaredTimeToLive(StackResource r, JsonNode spec,
                                                 CloudFormationTemplateEngine engine) {
        boolean enabled = spec.has("Enabled") && Boolean.parseBoolean(engine.resolve(spec.get("Enabled")));
        String attributeName = spec.has("AttributeName") ? engine.resolve(spec.get("AttributeName")) : null;
        if (enabled && (attributeName == null || attributeName.isBlank())) {
            throw new AwsException("ValidationError", r.getResourceType() + " " + r.getLogicalId()
                    + " TimeToLiveSpecification requires AttributeName when Enabled is true", 400);
        }
        return new TimeToLive(enabled, attributeName);
    }

    /**
     * DynamoDB refuses to enable a second attribute while one is enabled, and CloudFormation
     * renames one by disabling TTL in an update and enabling the new attribute in a later one.
     */
    private static void requireTimeToLiveChangeAllowed(TimeToLive current, TimeToLive desired) {
        if (desired.enabled() && current.enabled() && !desired.attributeName().equals(current.attributeName())) {
            throw new AwsException("ValidationException", "TimeToLive is active on a different AttributeName", 400);
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
     * Keeps the tags, stream setting and TTL setting the table has before an in-place update
     * changes them, with the account and region that address it, so {@link #rollbackUpdate} can
     * put all three back.
     */
    private static void snapshotBeforeUpdate(StackResource r, Scope scope, String tableName, TableDefinition table,
                                             Map<String, String> tags, TimeToLive ttl) {
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
     * Puts back the tags, stream setting and TTL setting an in-place table update changed. All
     * three go back or none is claimed: restoring one alone would report a rollback while the
     * others stayed changed. Only an in-place {@code AWS::DynamoDB::Table} update takes the
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
