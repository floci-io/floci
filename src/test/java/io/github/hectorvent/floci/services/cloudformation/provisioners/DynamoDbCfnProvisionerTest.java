package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbTableAccess;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbTableAccess.TimeToLive;
import io.github.hectorvent.floci.services.dynamodb.backend.NativeDynamoDbBackend;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.ProvisionedThroughput;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The DynamoDB CFN provisioner in isolation: the exact {@code Fn::GetAtt} keys the registry
 * schema declares, the delete contract for each of its three types, the
 * {@code TimeToLiveSpecification} reconcile, and the snapshot a failed table update rolls back
 * from. The stream and replica
 * behaviour keeps its own coverage in {@code DynamoDbStreamSpecificationCfnProvisionerTest} and
 * {@code DynamoDbReplicaCfnProvisionerTest}, which reach this class through the fixture.
 */
class DynamoDbCfnProvisionerTest {

    private static final String TABLE_NAME = "orders";
    private static final String TABLE_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/" + TABLE_NAME;
    private static final String TABLE_ID = "2d4c5c3a-1b6e-4a1d-9f0a-7b1c2d3e4f50";
    private static final String STREAM_ARN = TABLE_ARN + "/stream/2026-01-01T00:00:00.000";
    private static final String TTL_PROPS = "{\"TableName\":\"orders\",\"TimeToLiveSpecification\":"
            + "{\"AttributeName\":\"expiresAt\",\"Enabled\":true}}";

    private final DynamoDbService dynamoDb = mock(DynamoDbService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeDynamoDbBackend backend = new NativeDynamoDbBackend(null, null, dynamoDb, null, null, mapper);
    private final DynamoDbCfnProvisioner provisioner = new DynamoDbCfnProvisioner(
            new DynamoDbFacade(backend, backend, new RegionResolver("us-east-1", "000000000000")));

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    /** The update path: the same engine plus the physical id the previous provision assigned. */
    private ProvisionContext updateCtx(String priorPhysicalId) {
        ProvisionContext create = ctx();
        return new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), priorPhysicalId);
    }

    /** The update path, with the properties the previous template declared. */
    private ProvisionContext updateCtx(String priorPhysicalId, String priorProperties) throws Exception {
        ProvisionContext create = ctx();
        return new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), priorPhysicalId, event -> { }, props(priorProperties));
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private TableDefinition table(boolean streamed) {
        TableDefinition table = new TableDefinition();
        table.setTableName(TABLE_NAME);
        table.setTableArn(TABLE_ARN);
        table.setTableId(TABLE_ID);
        if (streamed) {
            table.setStreamEnabled(true);
            table.setStreamArn(STREAM_ARN);
            table.setStreamViewType("NEW_AND_OLD_IMAGES");
        }
        return table;
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void ownsExactlyTheThreeDynamoDbTypes() {
        assertEquals(Set.of("AWS::DynamoDB::Table", "AWS::DynamoDB::GlobalTable", "Custom::DynamoDBReplica"),
                provisioner.resourceTypes());
    }

    /** The registry schema declares only Arn and StreamArn read-only on a plain table; TableId belongs to the global table. */
    @Test
    void tablePublishesOnlyArnWithoutAStream() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");

        provisioner.provision(r, props("{\"TableName\":\"orders\"}"), ctx());

        assertEquals(TABLE_NAME, r.getPhysicalId());
        assertEquals(Set.of("Arn"), r.getAttributes().keySet());
        assertEquals(TABLE_ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void globalTableIsProvisionedAsATableAndPublishesTableId() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(table(false));
        StackResource r = resource("AWS::DynamoDB::GlobalTable", "Orders");

        provisioner.provision(r, props("{\"TableName\":\"orders\"}"), ctx());

        verify(dynamoDb).createTable(eq(TABLE_NAME), anyList(), anyList(), any(), any(), anyList(), anyList(),
                eq("us-east-1"));
        assertEquals(TABLE_NAME, r.getPhysicalId());
        assertEquals(Set.of("Arn", "TableId"), r.getAttributes().keySet());
        assertEquals(TABLE_ID, r.getAttributes().get("TableId"));
        // No Replicas declared and none tracked, so nothing to reconcile.
        verify(dynamoDb, never()).applyReplicaUpdates(anyString(), anyList(), anyList(), anyString());
    }

    @Test
    void globalTableCreateAddsDeclaredReplicaRegionsFilteringTheDeploymentRegion() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(table(false));
        StackResource r = resource("AWS::DynamoDB::GlobalTable", "Orders");

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"Replicas\":["
                + "{\"Region\":\"us-east-1\"},{\"Region\":\"us-west-2\"},{\"Region\":\"eu-west-1\"}]}"), ctx());

        // us-east-1 is the deployment region served by the table itself, so it is filtered out.
        verify(dynamoDb).applyReplicaUpdates(TABLE_NAME, List.of("us-west-2", "eu-west-1"), List.of(), "us-east-1");
    }

    @Test
    void globalTableUpdateAddsNewReplicasAndRemovesDroppedOnes() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        TableDefinition existing = table(false);
        existing.setReplicaRegions(new ArrayList<>(List.of("us-west-2", "eu-west-1")));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(existing);
        StackResource r = resource("AWS::DynamoDB::GlobalTable", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"Replicas\":["
                + "{\"Region\":\"us-west-2\"},{\"Region\":\"ap-south-1\"}]}"), updateCtx(TABLE_NAME));

        verify(dynamoDb).applyReplicaUpdates(TABLE_NAME, List.of("ap-south-1"), List.of("eu-west-1"), "us-east-1");
    }

    @Test
    void globalTableWithUnchangedReplicasSkipsTheReplicaUpdate() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        TableDefinition existing = table(false);
        existing.setReplicaRegions(new ArrayList<>(List.of("us-west-2")));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(existing);
        StackResource r = resource("AWS::DynamoDB::GlobalTable", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"Replicas\":[{\"Region\":\"us-west-2\"}]}"),
                updateCtx(TABLE_NAME));

        verify(dynamoDb, never()).applyReplicaUpdates(anyString(), anyList(), anyList(), anyString());
    }

    @Test
    void streamedTablePublishesStreamArn() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        when(dynamoDb.enableStream(TABLE_NAME, "NEW_AND_OLD_IMAGES", "us-east-1")).thenReturn(table(true));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"StreamSpecification\":"
                + "{\"StreamViewType\":\"NEW_AND_OLD_IMAGES\"}}"), ctx());

        assertEquals(Set.of("Arn", "StreamArn"), r.getAttributes().keySet());
        assertEquals(STREAM_ARN, r.getAttributes().get("StreamArn"));
    }

    @Test
    void tableDeleteTreatsResourceNotFoundAsAlreadyGone() {
        doThrow(new AwsException("ResourceNotFoundException", "Requested resource not found", 400))
                .when(dynamoDb).deleteTable("missing", "us-east-1");

        provisioner.delete("AWS::DynamoDB::Table", "missing", "us-east-1");

        verify(dynamoDb).deleteTable("missing", "us-east-1");
    }

    @Test
    void tableDeletePropagatesAnyOtherError() {
        doThrow(new AwsException("InternalServerError", "boom", 500))
                .when(dynamoDb).deleteTable(TABLE_NAME, "us-east-1");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::DynamoDB::Table", TABLE_NAME, "us-east-1"));

        assertEquals("InternalServerError", e.getErrorCode());
    }

    @Test
    void globalTableDeleteRemovesTheTable() {
        provisioner.delete("AWS::DynamoDB::GlobalTable", TABLE_NAME, "us-east-1");

        verify(dynamoDb).deleteTable(TABLE_NAME, "us-east-1");
    }

    @Test
    void replicaDeleteWithCreateTimeAttributesRemovesTheReplicaRegion() {
        StackResource r = resource("Custom::DynamoDBReplica", "Replica");
        r.setPhysicalId("orders-us-west-2");
        r.getAttributes().put("TableName", TABLE_NAME);
        r.getAttributes().put("__FlociDynamoDbReplicaRegion", "us-west-2");
        r.getAttributes().put("__FlociDynamoDbReplicaSkipDeletion", "false");

        provisioner.delete(r, "us-east-1");

        verify(dynamoDb).applyReplicaUpdates(TABLE_NAME, List.of(), List.of("us-west-2"), "us-east-1");
    }

    @Test
    void replicaDeleteWithoutAttributesFallsBackToTheCdkPhysicalId() {
        StackResource r = resource("Custom::DynamoDBReplica", "Replica");
        r.setPhysicalId("orders-us-west-2");
        r.getAttributes().put("TableName", TABLE_NAME);

        provisioner.delete(r, "us-east-1");

        verify(dynamoDb).applyReplicaUpdates(TABLE_NAME, List.of(), List.of("us-west-2"), "us-east-1");
    }

    /** Only a table that is already gone is "already deleted"; any other failure must reach the stack as DELETE_FAILED. */
    @Test
    void replicaDeletePropagatesAnythingButANotFoundTable() {
        StackResource r = resource("Custom::DynamoDBReplica", "Replica");
        r.setPhysicalId("orders-us-west-2");
        r.getAttributes().put("TableName", TABLE_NAME);
        r.getAttributes().put("__FlociDynamoDbReplicaRegion", "us-west-2");
        doThrow(new AwsException("ValidationException", "Replica RegionName must not be empty", 400))
                .when(dynamoDb).applyReplicaUpdates(TABLE_NAME, List.of(), List.of("us-west-2"), "us-east-1");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.delete(r, "us-east-1"));
        assertEquals("ValidationException", e.getErrorCode());

        doThrow(new AwsException("ResourceNotFoundException", "Requested resource not found", 400))
                .when(dynamoDb).applyReplicaUpdates(TABLE_NAME, List.of(), List.of("us-west-2"), "us-east-1");

        provisioner.delete(r, "us-east-1");
    }

    @Test
    void replicaDeleteByIdAloneLeavesTheReplicaInPlace() {
        provisioner.delete("Custom::DynamoDBReplica", "orders-us-west-2", "us-east-1");

        verifyNoInteractions(dynamoDb);
    }

    @Test
    void tableDeleteThroughTheResourceOverloadUsesTheIdPath() {
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.delete(r, "us-east-1");

        verify(dynamoDb).deleteTable(TABLE_NAME, "us-east-1");
        verify(dynamoDb, never()).applyReplicaUpdates(anyString(), anyList(), anyList(), anyString());
    }

    @Test
    void unsupportedTypeIsRejectedOnProvisionAndDelete() throws Exception {
        StackResource r = resource("AWS::SQS::Queue", "Queue");
        JsonNode props = props("{}");
        ProvisionContext ctx = ctx();

        assertThrows(IllegalStateException.class, () -> provisioner.provision(r, props, ctx));
        assertThrows(IllegalStateException.class, () -> provisioner.delete("AWS::SQS::Queue", "q", "us-east-1"));
        verifyNoInteractions(dynamoDb);
    }

    /**
     * A table without an explicit TableName keeps the name generated on create: regenerating one
     * on every UpdateStack created a second table and re-pointed Ref at it.
     */
    @Test
    void updateWithoutExplicitNameReusesTheGeneratedName() throws Exception {
        String priorName = "my-stack-Orders-0123456789ab";
        TableDefinition existing = table(false);
        existing.setTableName(priorName);
        when(dynamoDb.createTable(eq(priorName), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenThrow(new AwsException("ResourceInUseException", "Table already exists", 400));
        when(dynamoDb.describeTable(priorName, "us-east-1")).thenReturn(existing);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(priorName);

        provisioner.provision(r, props("{}"), updateCtx(priorName));

        assertEquals(priorName, r.getPhysicalId());
        verify(dynamoDb).createTable(eq(priorName), anyList(), anyList(), any(), any(), anyList(), anyList(),
                eq("us-east-1"));
        verify(dynamoDb, atLeastOnce()).describeTable(priorName, "us-east-1");
    }

    @Test
    void tagsAreAppliedAndStaleOnesRemoved() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        when(dynamoDb.listTagsOfResource(TABLE_ARN, "us-east-1")).thenReturn(Map.of("old", "1", "env", "dev"));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"prod\"}]}"),
                ctx());

        verify(dynamoDb).untagResource(TABLE_ARN, List.of("old"), "us-east-1");
        verify(dynamoDb).tagResource(TABLE_ARN, Map.of("env", "prod"), "us-east-1");
    }

    private TableDefinition withTimeToLive(TableDefinition table, String attributeName) {
        table.setTtlEnabled(true);
        table.setTtlAttributeName(attributeName);
        return table;
    }

    /** The update path: the table already exists and reports {@code current}. */
    private void existingTable(TableDefinition current) {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenThrow(new AwsException("ResourceInUseException", "Table already exists", 400));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(current);
    }

    @Test
    void createWithTimeToLiveEnablesItOnce() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(table(false));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"TimeToLiveSpecification\":"
                + "{\"AttributeName\":\"expiresAt\",\"Enabled\":true}}"), ctx());

        verify(dynamoDb).updateTimeToLive(TABLE_NAME, "expiresAt", true, "us-east-1");
    }

    @Test
    void enabledGivenAsAStringIsResolvedThroughTheEngine() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(table(false));
        ProvisionContext ctx = ctx();
        when(ctx.engine().resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            if (node != null && node.has("Ref")) {
                return "true";
            }
            return node == null ? null : node.asText();
        });
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"TimeToLiveSpecification\":"
                + "{\"AttributeName\":\"expiresAt\",\"Enabled\":{\"Ref\":\"TtlOn\"}}}"), ctx);

        verify(dynamoDb).updateTimeToLive(TABLE_NAME, "expiresAt", true, "us-east-1");
    }

    @Test
    void updateWithTheSameTimeToLiveMakesNoTimeToLiveCall() throws Exception {
        existingTable(withTimeToLive(table(false), "expiresAt"));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"TimeToLiveSpecification\":"
                + "{\"AttributeName\":\"expiresAt\",\"Enabled\":true}}"), updateCtx(TABLE_NAME));

        verify(dynamoDb, never()).updateTimeToLive(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void updateWithEnabledFalseDisablesTheCurrentAttribute() throws Exception {
        existingTable(withTimeToLive(table(false), "expiresAt"));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"TimeToLiveSpecification\":{\"Enabled\":false}}"),
                updateCtx(TABLE_NAME));

        verify(dynamoDb).updateTimeToLive(TABLE_NAME, "expiresAt", false, "us-east-1");
    }

    @Test
    void updateThatRemovesTheTimeToLiveBlockDisablesIt() throws Exception {
        existingTable(withTimeToLive(table(false), "expiresAt"));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props("{\"TableName\":\"orders\"}"), updateCtx(TABLE_NAME, TTL_PROPS));

        verify(dynamoDb).updateTimeToLive(TABLE_NAME, "expiresAt", false, "us-east-1");
    }

    /** As measured on AWS: TTL enabled outside a stack that never declared it survives a tag-only update. */
    @Test
    void updateOfAStackThatNeverDeclaredTimeToLiveLeavesItAlone() throws Exception {
        existingTable(withTimeToLive(table(false), "expiresAt"));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"prod\"}]}"),
                updateCtx(TABLE_NAME, "{\"TableName\":\"orders\"}"));

        verify(dynamoDb, never()).updateTimeToLive(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void timeToLiveWithoutEnabledFailsModelValidationBeforeTheTableIsCreated() throws Exception {
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        JsonNode props = props("{\"TableName\":\"orders\",\"TimeToLiveSpecification\":{\"AttributeName\":\"exp\"}}");
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("InvalidRequest", e.getErrorCode());
        assertEquals("Model validation failed (#/TimeToLiveSpecification: required key [Enabled] not found)",
                e.getMessage());
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void timeToLiveWithANonBooleanEnabledFailsModelValidationBeforeTheTableIsCreated() throws Exception {
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        JsonNode props = props("{\"TableName\":\"orders\",\"TimeToLiveSpecification\":"
                + "{\"AttributeName\":\"exp\",\"Enabled\":\"tru\"}}");
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("InvalidRequest", e.getErrorCode());
        assertEquals("Model validation failed (#/TimeToLiveSpecification/Enabled: expected type: Boolean,"
                + " found: String)", e.getMessage());
        verifyNoInteractions(dynamoDb);
    }

    /** CloudFormation renames a TTL attribute by disabling it in one update and enabling the new one in a later one. */
    @Test
    void updateThatRenamesAnEnabledTimeToLiveAttributeFailsBeforeChangingAnything() throws Exception {
        existingTable(withTimeToLive(table(false), "expiresAt"));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);
        // Left by an earlier successful update; it must not make the engine skip its own restore.
        r.getAttributes().put(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR, "{}");
        JsonNode props = props("{\"TableName\":\"orders\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"prod\"}],"
                + "\"TimeToLiveSpecification\":{\"AttributeName\":\"deleteAfter\",\"Enabled\":true}}");
        ProvisionContext ctx = updateCtx(TABLE_NAME);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("InvalidRequest", e.getErrorCode());
        assertEquals("Invalid request provided: Cannot change time-to-live attribute name. To update this property,"
                + " you must first disable TTL then enable TTL with the new attribute name.", e.getMessage());
        assertEquals(400, e.getHttpStatus());
        verify(dynamoDb, never()).updateTimeToLive(anyString(), any(), anyBoolean(), anyString());
        verify(dynamoDb, never()).tagResource(anyString(), any(), anyString());
        assertFalse(provisioner.retainsFailedUpdateState(r));
        verify(dynamoDb, never()).deleteTable(anyString(), anyString());
    }

    /**
     * The table's own update fails after it changed the tags and the stream. The engine must keep
     * this attempt, snapshot included, and hand it to rollbackUpdate rather than put the previous
     * resource back and report a rollback that never happened.
     */
    @Test
    void inPlaceUpdateThatFailsAfterItsSnapshotIsRetainedAndRolledBack() throws Exception {
        existingTable(table(true));
        when(dynamoDb.listTagsOfResource(TABLE_ARN, "us-east-1")).thenReturn(Map.of("env", "dev"));
        when(dynamoDb.disableStream(TABLE_NAME, "us-east-1")).thenReturn(table(false));
        doThrow(new AwsException("InternalServerError", "boom", 500))
                .when(dynamoDb).updateTimeToLive(TABLE_NAME, "expiresAt", true, "us-east-1");
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);
        r.getAttributes().put("Arn", TABLE_ARN);
        r.getAttributes().put("StreamArn", STREAM_ARN);
        JsonNode props = props("{\"TableName\":\"orders\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"prod\"}],"
                + "\"TimeToLiveSpecification\":{\"AttributeName\":\"expiresAt\",\"Enabled\":true}}");
        ProvisionContext ctx = updateCtx(TABLE_NAME);

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertTrue(provisioner.retainsFailedUpdateState(r));
        assertEquals(TABLE_NAME, r.getPhysicalId());
        assertEquals(TABLE_ARN, r.getAttributes().get("Arn"));
        verify(dynamoDb, never()).deleteTable(anyString(), anyString());

        when(dynamoDb.listTagsOfResource(TABLE_ARN, "us-east-1")).thenReturn(Map.of("env", "prod"));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(table(false));
        when(dynamoDb.enableStream(TABLE_NAME, "NEW_AND_OLD_IMAGES", "us-east-1")).thenReturn(table(true));

        assertTrue(provisioner.rollbackUpdate(r));
        verify(dynamoDb).tagResource(TABLE_ARN, Map.of("env", "dev"), "us-east-1");
        verify(dynamoDb).enableStream(TABLE_NAME, "NEW_AND_OLD_IMAGES", "us-east-1");
        verify(dynamoDb, never()).updateTimeToLive(TABLE_NAME, "expiresAt", false, "us-east-1");
        assertFalse(provisioner.retainsFailedUpdateState(r));
    }

    private void createdTableWhoseTimeToLiveCallFails() {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(table(false));
        doThrow(new AwsException("LimitExceededException", "boom", 400))
                .when(dynamoDb).updateTimeToLive(TABLE_NAME, "expiresAt", true, "us-east-1");
    }

    @Test
    void createThatFailsAfterCreatingTheTableDeletesIt() throws Exception {
        createdTableWhoseTimeToLiveCallFails();
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        JsonNode props = props(TTL_PROPS);
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("LimitExceededException", e.getErrorCode());
        verify(dynamoDb).deleteTable(TABLE_NAME, "us-east-1");
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void replacingUpdateThatFailsDeletesTheNewTableAndLeavesTheOldOne() throws Exception {
        createdTableWhoseTimeToLiveCallFails();
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId("orders-v1");
        JsonNode props = props(TTL_PROPS);
        ProvisionContext ctx = updateCtx("orders-v1");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        verify(dynamoDb).deleteTable(TABLE_NAME, "us-east-1");
        verify(dynamoDb, never()).deleteTable("orders-v1", "us-east-1");
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    /** A table this attempt did not create, the update target or one adopted on create, is never deleted. */
    @Test
    void failureOnATableThisAttemptDidNotCreateDeletesNothing() throws Exception {
        existingTable(table(false));
        doThrow(new AwsException("LimitExceededException", "boom", 400))
                .when(dynamoDb).updateTimeToLive(TABLE_NAME, "expiresAt", true, "us-east-1");
        JsonNode props = props(TTL_PROPS);

        for (ProvisionContext ctx : List.of(updateCtx(TABLE_NAME), ctx())) {
            StackResource r = resource("AWS::DynamoDB::Table", "Orders");
            r.setPhysicalId(ctx.priorPhysicalId());

            assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

            assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
        }
        verify(dynamoDb, never()).deleteTable(anyString(), anyString());
    }

    /** A cleanup that fails leaves the table to the engine's create rollback, which retries the delete. */
    @Test
    void createWhoseCleanupFailsLeavesTheTableOwnedForTheStackRollback() throws Exception {
        createdTableWhoseTimeToLiveCallFails();
        doThrow(new AwsException("InternalServerError", "delete failed", 500))
                .when(dynamoDb).deleteTable(TABLE_NAME, "us-east-1");
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        JsonNode props = props(TTL_PROPS);
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("LimitExceededException", e.getErrorCode());
        assertEquals(TABLE_NAME, r.getPhysicalId());
        assertEquals("true", r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    /**
     * The engine puts the previous resource back after a failed update and never learns the new
     * table, so a cleanup that fails there is reported as a rollback failure naming it.
     */
    @Test
    void replacingUpdateWhoseCleanupFailsReportsTheTableAsARollbackFailure() throws Exception {
        createdTableWhoseTimeToLiveCallFails();
        doThrow(new AwsException("InternalServerError", "delete failed", 500))
                .when(dynamoDb).deleteTable(TABLE_NAME, "us-east-1");
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId("orders-v1");
        JsonNode props = props(TTL_PROPS);
        ProvisionContext ctx = updateCtx("orders-v1");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("LimitExceededException", e.getErrorCode());
        assertEquals(1, e.getSuppressed().length);
        String reason = r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR);
        assertTrue(reason != null && reason.contains(TABLE_NAME) && reason.contains("delete failed"), reason);
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    /**
     * The update target was deleted outside the stack, so this attempt created it again. That table
     * is this attempt's to delete, and there is no prior state to snapshot for a rollback.
     */
    @Test
    void updateThatRecreatesAMissingTableAndFailsDeletesItAndKeepsNoSnapshot() throws Exception {
        createdTableWhoseTimeToLiveCallFails();
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);
        JsonNode props = props(TTL_PROPS);
        ProvisionContext ctx = updateCtx(TABLE_NAME);

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        verify(dynamoDb).deleteTable(TABLE_NAME, "us-east-1");
        assertFalse(provisioner.retainsFailedUpdateState(r));
    }

    @Test
    void successfulCreateLeavesNoOwnershipMarker() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(table(false));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");

        provisioner.provision(r, props(TTL_PROPS), ctx());

        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
        verify(dynamoDb, never()).deleteTable(anyString(), anyString());
    }

    @Test
    void enabledTimeToLiveWithoutAnAttributeNameFailsBeforeTheTableIsCreated() throws Exception {
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        JsonNode props = props("{\"TableName\":\"orders\",\"TimeToLiveSpecification\":{\"Enabled\":true}}");
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("InvalidRequest", e.getErrorCode());
        assertEquals("Invalid request provided: AttributeName property of TimeToLiveSpecification is required"
                + " when TTL status is enabled or when enabling TTL.", e.getMessage());
        assertEquals(400, e.getHttpStatus());
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void rollbackUpdateRestoresTagsStreamAndTimeToLive() throws Exception {
        existingTable(table(true));
        when(dynamoDb.listTagsOfResource(TABLE_ARN, "us-east-1")).thenReturn(Map.of("env", "dev"));
        when(dynamoDb.disableStream(TABLE_NAME, "us-east-1")).thenReturn(table(false));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);
        r.getAttributes().put("StreamArn", STREAM_ARN);
        provisioner.provision(r, props("{\"TableName\":\"orders\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"prod\"}],"
                + "\"TimeToLiveSpecification\":{\"AttributeName\":\"expiresAt\",\"Enabled\":true}}"),
                updateCtx(TABLE_NAME));
        // The table as the update left it: retagged, streamless and expiring.
        when(dynamoDb.listTagsOfResource(TABLE_ARN, "us-east-1")).thenReturn(Map.of("env", "prod"));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(withTimeToLive(table(false), "expiresAt"));
        when(dynamoDb.enableStream(TABLE_NAME, "NEW_AND_OLD_IMAGES", "us-east-1")).thenReturn(table(true));

        boolean rolledBack = provisioner.rollbackUpdate(r);

        assertTrue(rolledBack);
        verify(dynamoDb).tagResource(TABLE_ARN, Map.of("env", "dev"), "us-east-1");
        verify(dynamoDb).enableStream(TABLE_NAME, "NEW_AND_OLD_IMAGES", "us-east-1");
        verify(dynamoDb).updateTimeToLive(TABLE_NAME, "expiresAt", false, "us-east-1");
        assertEquals(STREAM_ARN, r.getAttributes().get("StreamArn"));
        assertFalse(r.getAttributes().containsKey(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR));
    }

    private static final String BY_EMAIL_PROPS = "{\"TableName\":\"orders\","
            + "\"AttributeDefinitions\":[{\"AttributeName\":\"pk\",\"AttributeType\":\"S\"},"
            + "{\"AttributeName\":\"email\",\"AttributeType\":\"S\"}],"
            + "\"KeySchema\":[{\"AttributeName\":\"pk\",\"KeyType\":\"HASH\"}],"
            + "\"GlobalSecondaryIndexes\":[{\"IndexName\":\"byEmail\","
            + "\"KeySchema\":[{\"AttributeName\":\"email\",\"KeyType\":\"HASH\"}],"
            + "\"Projection\":{\"ProjectionType\":\"ALL\"}}]}";

    private static GlobalSecondaryIndex index(String name, String hashKey) {
        return new GlobalSecondaryIndex(name, List.of(new KeySchemaElement(hashKey, "HASH")), null, "ALL", List.of());
    }

    /** UpdateTable is answered with the table it was asked to change. */
    private void updateTableAnswers(TableDefinition table) {
        when(dynamoDb.updateTable(anyString(), any(), any(), anyList(), anyList(), anyList(), anyList(), anyList(),
                any(), anyString())).thenReturn(table);
    }

    /** Adding a global secondary index on update creates it in place, as on AWS. */
    @Test
    void anUpdateCreatesTheIndexTheTemplateAdds() throws Exception {
        TableDefinition current = table(false);
        existingTable(current);
        updateTableAnswers(current);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props(BY_EMAIL_PROPS), updateCtx(TABLE_NAME));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<GlobalSecondaryIndex>> creates = ArgumentCaptor.forClass(List.class);
        verify(dynamoDb).updateTable(eq(TABLE_NAME), isNull(), isNull(), creates.capture(), eq(List.of()), anyList(),
                eq(List.of()), eq(List.of()), isNull(), eq("us-east-1"));
        assertEquals(List.of("byEmail"), creates.getValue().stream().map(GlobalSecondaryIndex::getIndexName).toList());
    }

    @Test
    void anUpdateDeletesTheIndexTheTemplateDrops() throws Exception {
        TableDefinition current = table(false);
        current.getGlobalSecondaryIndexes().add(index("byEmail", "email"));
        existingTable(current);
        updateTableAnswers(current);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props("{\"TableName\":\"orders\"}"), updateCtx(TABLE_NAME));

        verify(dynamoDb).updateTable(TABLE_NAME, null, null, List.of(), List.of("byEmail"), List.of(), List.of(),
                List.of(), null, "us-east-1");
    }

    /** An index keeps its key schema; a different one needs a new index name, as on AWS. */
    @Test
    void anIndexWhoseKeySchemaChangedIsRefused() throws Exception {
        TableDefinition current = table(false);
        current.getGlobalSecondaryIndexes().add(index("byEmail", "pk"));
        existingTable(current);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);
        JsonNode props = props(BY_EMAIL_PROPS);
        ProvisionContext update = updateCtx(TABLE_NAME);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, update));

        assertEquals("InvalidRequest", e.getErrorCode());
        verify(dynamoDb, never()).updateTable(anyString(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyList(), any(), anyString());
    }

    private static final String PROJECTED_INDEXES_PROPS = "{\"TableName\":\"orders\","
            + "\"AttributeDefinitions\":[{\"AttributeName\":\"pk\",\"AttributeType\":\"S\"},"
            + "{\"AttributeName\":\"status\",\"AttributeType\":\"S\"},"
            + "{\"AttributeName\":\"email\",\"AttributeType\":\"S\"}],"
            + "\"KeySchema\":[{\"AttributeName\":\"pk\",\"KeyType\":\"HASH\"}],"
            + "\"GlobalSecondaryIndexes\":[{\"IndexName\":\"byStatus\","
            + "\"KeySchema\":[{\"AttributeName\":\"status\",\"KeyType\":\"HASH\"}],"
            + "\"Projection\":{\"ProjectionType\":\"KEYS_ONLY\"}},"
            + "{\"IndexName\":\"byEmail\",\"KeySchema\":[{\"AttributeName\":\"email\",\"KeyType\":\"HASH\"}],"
            + "\"Projection\":{\"ProjectionType\":\"INCLUDE\",\"NonKeyAttributes\":[\"name\",\"total\"]}}],"
            + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"prod\"}]}";

    /** A tag change on a table whose KEYS_ONLY and INCLUDE indexes the template keeps as they are. */
    @Test
    void anUpdateLeavesUnchangedKeysOnlyAndIncludeIndexesAlone() throws Exception {
        TableDefinition current = table(false);
        current.getGlobalSecondaryIndexes().add(new GlobalSecondaryIndex("byStatus",
                List.of(new KeySchemaElement("status", "HASH")), null, "KEYS_ONLY", List.of()));
        current.getGlobalSecondaryIndexes().add(new GlobalSecondaryIndex("byEmail",
                List.of(new KeySchemaElement("email", "HASH")), null, "INCLUDE", List.of("total", "name")));
        existingTable(current);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props(PROJECTED_INDEXES_PROPS), updateCtx(TABLE_NAME));

        verify(dynamoDb).tagResource(TABLE_ARN, Map.of("env", "prod"), "us-east-1");
        verify(dynamoDb, never()).updateTable(anyString(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyList(), any(), anyString());
    }

    /** A backend that reports no projection for an index gives no sign the template changed it. */
    @Test
    void anIndexWhoseProjectionIsNotReportedIsNotTreatedAsChanged() throws Exception {
        TableDefinition current = table(false);
        for (String hashKey : List.of("status", "email")) {
            GlobalSecondaryIndex unreported = new GlobalSecondaryIndex();
            unreported.setIndexName("status".equals(hashKey) ? "byStatus" : "byEmail");
            unreported.setKeySchema(List.of(new KeySchemaElement(hashKey, "HASH")));
            current.getGlobalSecondaryIndexes().add(unreported);
        }
        existingTable(current);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props(PROJECTED_INDEXES_PROPS), updateCtx(TABLE_NAME));

        verify(dynamoDb).tagResource(TABLE_ARN, Map.of("env", "prod"), "us-east-1");
        verify(dynamoDb, never()).updateTable(anyString(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyList(), any(), anyString());
    }

    /** BillingMode PAY_PER_REQUEST reaches a table the backend creates provisioned. */
    @Test
    void onDemandBillingIsAppliedOnCreate() throws Exception {
        TableDefinition created = table(false);
        created.setProvisionedThroughput(new ProvisionedThroughput(5, 5));
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(created);
        updateTableAnswers(created);

        provisioner.provision(resource("AWS::DynamoDB::Table", "Orders"),
                props("{\"TableName\":\"orders\",\"BillingMode\":\"PAY_PER_REQUEST\"}"), ctx());

        verify(dynamoDb).createTable(eq(TABLE_NAME), anyList(), anyList(), isNull(), isNull(), anyList(), anyList(),
                eq("us-east-1"));
        verify(dynamoDb).updateTable(TABLE_NAME, null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                "PAY_PER_REQUEST", "us-east-1");
    }

    @Test
    void provisionedThroughputIsPassedOnCreateAndChangedOnUpdate() throws Exception {
        TableDefinition created = table(false);
        created.setProvisionedThroughput(new ProvisionedThroughput(3, 4));
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(created);
        String provisioned = "{\"TableName\":\"orders\",\"BillingMode\":\"PROVISIONED\","
                + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":%d,\"WriteCapacityUnits\":%d}}";

        provisioner.provision(resource("AWS::DynamoDB::Table", "Orders"), props(provisioned.formatted(3, 4)), ctx());

        verify(dynamoDb).createTable(eq(TABLE_NAME), anyList(), anyList(), eq(3L), eq(4L), anyList(), anyList(),
                eq("us-east-1"));
        // The table already has the declared throughput, so nothing is updated.
        verify(dynamoDb, never()).updateTable(anyString(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyList(), any(), anyString());

        existingTable(created);
        updateTableAnswers(created);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);
        provisioner.provision(r, props(provisioned.formatted(6, 4)), updateCtx(TABLE_NAME));

        verify(dynamoDb).updateTable(TABLE_NAME, 6L, 4L, List.of(), List.of(), List.of(), List.of(), List.of(),
                "PROVISIONED", "us-east-1");
    }

    private static final String DECLARED_THROUGHPUT = "{\"TableName\":\"orders\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":5,\"WriteCapacityUnits\":5}%s}";

    /**
     * CloudFormation diffs a template against the previous one, not against the table: a table the
     * DynamoDB Local backend created on demand keeps that billing while the template's throughput
     * stays the same, and Local refuses any billing change.
     */
    @Test
    void anUpdateThatKeepsTheDeclaredThroughputLeavesTheBillingAlone() throws Exception {
        TableDefinition onDemand = table(false);
        onDemand.setBillingMode("PAY_PER_REQUEST");
        onDemand.setProvisionedThroughput(new ProvisionedThroughput(0, 0));
        existingTable(onDemand);
        updateTableAnswers(onDemand);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props(DECLARED_THROUGHPUT.formatted(",\"Tags\":[{\"Key\":\"env\",\"Value\":\"prod\"}]")),
                updateCtx(TABLE_NAME, DECLARED_THROUGHPUT.formatted("")));

        verify(dynamoDb).tagResource(TABLE_ARN, Map.of("env", "prod"), "us-east-1");
        verify(dynamoDb, never()).updateTable(anyString(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyList(), any(), anyString());
    }

    @Test
    void aBillingChangeTheTemplateMakesIsAppliedOnUpdate() throws Exception {
        TableDefinition provisioned = table(false);
        provisioned.setProvisionedThroughput(new ProvisionedThroughput(5, 5));
        existingTable(provisioned);
        updateTableAnswers(provisioned);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"BillingMode\":\"PAY_PER_REQUEST\"}"),
                updateCtx(TABLE_NAME, DECLARED_THROUGHPUT.formatted("")));

        verify(dynamoDb).updateTable(TABLE_NAME, null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                "PAY_PER_REQUEST", "us-east-1");
    }

    /** The stack's template is the previous one again after a rollback, so retrying the change applies it. */
    @Test
    void aBillingChangeRetriedAfterItsUpdateRolledBackIsAppliedAgain() throws Exception {
        TableDefinition provisioned = table(false);
        provisioned.setProvisionedThroughput(new ProvisionedThroughput(5, 5));
        TableDefinition onDemand = table(false);
        onDemand.setBillingMode("PAY_PER_REQUEST");
        onDemand.setProvisionedThroughput(new ProvisionedThroughput(0, 0));
        existingTable(provisioned);
        updateTableAnswers(onDemand);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);
        String payPerRequest = "{\"TableName\":\"orders\",\"BillingMode\":\"PAY_PER_REQUEST\"}";
        provisioner.provision(r, props(payPerRequest), updateCtx(TABLE_NAME, DECLARED_THROUGHPUT.formatted("")));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(onDemand);

        assertTrue(provisioner.rollbackUpdate(r));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(provisioned);
        provisioner.provision(r, props(payPerRequest), updateCtx(TABLE_NAME, DECLARED_THROUGHPUT.formatted("")));

        verify(dynamoDb).updateTable(TABLE_NAME, 5L, 5L, List.of(), List.of(), List.of(), List.of(), List.of(),
                "PROVISIONED", "us-east-1");
        verify(dynamoDb, times(2)).updateTable(TABLE_NAME, null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(), "PAY_PER_REQUEST", "us-east-1");
    }

    /** The same template text resolves to new capacities when the parameter it references changes. */
    @Test
    void aThroughputChangedThroughAParameterIsApplied() throws Exception {
        String parameterized = "{\"TableName\":\"orders\",\"ProvisionedThroughput\":"
                + "{\"ReadCapacityUnits\":{\"Ref\":\"Capacity\"},\"WriteCapacityUnits\":{\"Ref\":\"Capacity\"}}}";
        TableDefinition created = table(false);
        created.setProvisionedThroughput(new ProvisionedThroughput(5, 5));
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(created);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        provisioner.provision(r, props(parameterized), resolvingCapacityTo(ctx(), 5));
        existingTable(created);
        updateTableAnswers(created);

        provisioner.provision(r, props(parameterized), resolvingCapacityTo(updateCtx(TABLE_NAME, parameterized), 10));

        verify(dynamoDb).updateTable(TABLE_NAME, 10L, 10L, List.of(), List.of(), List.of(), List.of(), List.of(),
                "PROVISIONED", "us-east-1");
    }

    /** Resolves a ProvisionedThroughput whose capacities reference a parameter to {@code capacity}. */
    private ProvisionContext resolvingCapacityTo(ProvisionContext ctx, long capacity) {
        when(ctx.engine().resolveNode(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            if (node != null && node.path("ReadCapacityUnits").has("Ref")) {
                return mapper.createObjectNode().put("ReadCapacityUnits", capacity)
                        .put("WriteCapacityUnits", capacity);
            }
            return node;
        });
        return ctx;
    }

    /** A refused index change fails before the update retags the table or changes its billing. */
    @Test
    void aRefusedIndexChangeLeavesTagsAndBillingAsTheyWere() throws Exception {
        TableDefinition current = table(false);
        current.setProvisionedThroughput(new ProvisionedThroughput(5, 5));
        current.getGlobalSecondaryIndexes().add(index("byEmail", "pk"));
        existingTable(current);
        updateTableAnswers(current);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);
        JsonNode props = props(BY_EMAIL_PROPS.replace("\"TableName\":\"orders\",",
                "\"TableName\":\"orders\",\"BillingMode\":\"PAY_PER_REQUEST\","
                        + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"prod\"}],"));
        ProvisionContext update = updateCtx(TABLE_NAME);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, update));

        assertEquals("InvalidRequest", e.getErrorCode());
        verify(dynamoDb, never()).tagResource(anyString(), any(), anyString());
        verify(dynamoDb, never()).updateTable(anyString(), any(), any(), anyList(), anyList(), anyList(), anyList(),
                anyList(), any(), anyString());
        assertFalse(provisioner.retainsFailedUpdateState(r));
    }

    /** A failed update takes back the index it created, with the rest of the table's prior state. */
    @Test
    void rollbackUpdateDeletesTheIndexTheFailedUpdateCreated() throws Exception {
        TableDefinition current = table(false);
        existingTable(current);
        updateTableAnswers(current);
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);
        provisioner.provision(r, props(BY_EMAIL_PROPS), updateCtx(TABLE_NAME));
        TableDefinition afterUpdate = table(false);
        afterUpdate.getGlobalSecondaryIndexes().add(index("byEmail", "email"));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(afterUpdate);

        assertTrue(provisioner.rollbackUpdate(r));

        verify(dynamoDb).updateTable(TABLE_NAME, null, null, List.of(), List.of("byEmail"), List.of(), List.of(),
                List.of(), null, "us-east-1");
    }

    private static final String KINESIS_A = "arn:aws:kinesis:us-east-1:000000000000:stream/changes-a";
    private static final String KINESIS_B = "arn:aws:kinesis:us-east-1:000000000000:stream/changes-b";
    private static final Scope SCOPE = new Scope("000000000000", "us-east-1");

    private final DynamoDbTableAccess tables = mock(DynamoDbTableAccess.class);
    private final DynamoDbCfnProvisioner overTables = new DynamoDbCfnProvisioner(
            new DynamoDbFacade(backend, tables, new RegionResolver("us-east-1", "000000000000")));

    private static String kinesisProps(String streamArn) {
        return "{\"TableName\":\"orders\",\"KinesisStreamSpecification\":{\"StreamArn\":\"" + streamArn + "\"}}";
    }

    private static TableDefinition forwardingTo(TableDefinition table, String streamArn, String status) {
        KinesisStreamingDestination destination = new KinesisStreamingDestination(streamArn);
        destination.setDestinationStatus(status);
        table.getKinesisStreamingDestinations().add(destination);
        return table;
    }

    /** The table as an UpdateStack finds it, after a create that enabled {@code streamArn}. */
    private StackResource createdForwardingTo(String streamArn) throws Exception {
        when(tables.createTable(any(), anyString(), anyList(), anyList(), any(), any(), anyList(), anyList()))
                .thenReturn(table(false));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        overTables.provision(r, props(kinesisProps(streamArn)), ctx());
        when(tables.createTable(any(), anyString(), anyList(), anyList(), any(), any(), anyList(), anyList()))
                .thenThrow(new AwsException("ResourceInUseException", "Table already exists", 400));
        when(tables.describeTable(SCOPE, TABLE_NAME)).thenReturn(forwardingTo(table(false), streamArn, "ACTIVE"));
        when(tables.timeToLive(SCOPE, TABLE_NAME)).thenReturn(new TimeToLive(false, null));
        return r;
    }

    @Test
    void aDeclaredKinesisStreamSpecificationEnablesTheDestination() throws Exception {
        createdForwardingTo(KINESIS_A);

        verify(tables).enableKinesisStreamingDestination(SCOPE, TABLE_NAME, KINESIS_A, null);
    }

    @Test
    void anUpdateThatDropsTheBlockDisablesTheDestinationItEnabled() throws Exception {
        StackResource r = createdForwardingTo(KINESIS_A);

        overTables.provision(r, props("{\"TableName\":\"orders\"}"), updateCtx(TABLE_NAME));

        verify(tables).disableKinesisStreamingDestination(SCOPE, TABLE_NAME, KINESIS_A);
    }

    @Test
    void anUpdateToAnotherStreamSwapsTheDestination() throws Exception {
        StackResource r = createdForwardingTo(KINESIS_A);

        overTables.provision(r, props(kinesisProps(KINESIS_B)), updateCtx(TABLE_NAME));

        verify(tables).disableKinesisStreamingDestination(SCOPE, TABLE_NAME, KINESIS_A);
        verify(tables).enableKinesisStreamingDestination(SCOPE, TABLE_NAME, KINESIS_B, null);
    }

    /** An unchanged block leaves an active destination alone; enabling it again would be refused. */
    @Test
    void anUnchangedKinesisStreamSpecificationIsNotEnabledAgain() throws Exception {
        StackResource r = createdForwardingTo(KINESIS_A);

        overTables.provision(r, props(kinesisProps(KINESIS_A)), updateCtx(TABLE_NAME));

        verify(tables).enableKinesisStreamingDestination(SCOPE, TABLE_NAME, KINESIS_A, null);
        verify(tables, never()).disableKinesisStreamingDestination(any(), anyString(), anyString());
    }

    /** A failed update that turned the destination off turns it back on with the rest of the table. */
    @Test
    void rollbackUpdateEnablesTheDestinationTheFailedUpdateDisabled() throws Exception {
        StackResource r = createdForwardingTo(KINESIS_A);
        overTables.provision(r, props("{\"TableName\":\"orders\"}"), updateCtx(TABLE_NAME));
        when(tables.describeTable(SCOPE, TABLE_NAME)).thenReturn(forwardingTo(table(false), KINESIS_A, "DISABLED"));

        assertTrue(overTables.rollbackUpdate(r));

        verify(tables, times(2)).enableKinesisStreamingDestination(SCOPE, TABLE_NAME, KINESIS_A, null);
    }

    @Test
    void rollbackUpdateThatFailsKeepsTheSnapshotForTheNextAttempt() throws Exception {
        existingTable(table(false));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId(TABLE_NAME);
        provisioner.provision(r, props("{\"TableName\":\"orders\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"prod\"}]}"),
                updateCtx(TABLE_NAME));
        when(dynamoDb.listTagsOfResource(TABLE_ARN, "us-east-1")).thenReturn(Map.of("env", "prod"));
        doThrow(new AwsException("InternalServerError", "boom", 500))
                .when(dynamoDb).untagResource(TABLE_ARN, List.of("env"), "us-east-1");

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertTrue(r.getAttributes().containsKey(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR));
    }

    /**
     * A snapshot describes the update in flight. One left by an earlier update is dropped, so a
     * replacing update, which takes none, cannot roll the new table back to the old one's state.
     */
    @Test
    void replacingUpdateDropsAStaleSnapshotAndReportsNoRollback() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        when(dynamoDb.describeTable(TABLE_NAME, "us-east-1")).thenReturn(table(false));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");
        r.setPhysicalId("orders-v1");
        r.getAttributes().put(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR, "{}");

        provisioner.provision(r, props("{\"TableName\":\"orders\"}"), updateCtx("orders-v1"));

        assertFalse(r.getAttributes().containsKey(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR));
        assertFalse(provisioner.rollbackUpdate(r));
    }
}
