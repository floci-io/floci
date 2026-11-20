package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.dynamodb.DynamoDbFacade;
import io.floci.aws.services.dynamodb.DynamoDbService;
import io.floci.aws.services.dynamodb.NativeDynamoDbTableService;
import io.floci.aws.services.dynamodb.backend.DynamoDbTableAccess.CapacityUnits;
import io.floci.aws.services.dynamodb.backend.DynamoDbTableAccess.ThroughputChange;
import io.floci.aws.services.dynamodb.backend.DynamoDbTableAccess.ThroughputSpec;
import io.floci.aws.services.dynamodb.backend.DynamoDbTableAccess.WarmUnits;
import io.floci.aws.services.dynamodb.backend.NativeDynamoDbBackend;
import io.floci.aws.services.dynamodb.model.GlobalSecondaryIndex;
import io.floci.aws.services.dynamodb.model.ProvisionedThroughput;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ProvisionedThroughput} and {@code WarmThroughput} on {@code AWS::DynamoDB::Table}, on the
 * table and on each global secondary index.
 */
class DynamoDbThroughputCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String TABLE_NAME = "orders";
    private static final String TABLE_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/" + TABLE_NAME;
    private static final String INDEX = "gix1";
    private static final String PROVISIONED = "{\"TableName\":\"orders\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":1,\"WriteCapacityUnits\":1},"
            + "\"GlobalSecondaryIndexes\":[{\"IndexName\":\"gix1\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":2,\"WriteCapacityUnits\":2}}]}";
    private static final String RAISED = "{\"TableName\":\"orders\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":3,\"WriteCapacityUnits\":3},"
            + "\"GlobalSecondaryIndexes\":[{\"IndexName\":\"gix1\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":1,\"WriteCapacityUnits\":1}}]}";
    private static final String WARM = "{\"TableName\":\"orders\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":1,\"WriteCapacityUnits\":1},"
            + "\"WarmThroughput\":{\"ReadUnitsPerSecond\":5000},"
            + "\"GlobalSecondaryIndexes\":[{\"IndexName\":\"gix1\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":2,\"WriteCapacityUnits\":2},"
            + "\"WarmThroughput\":{\"WriteUnitsPerSecond\":4000}}]}";
    private static final String CAPACITY_AND_WARM = "{\"TableName\":\"orders\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":3,\"WriteCapacityUnits\":3},"
            + "\"WarmThroughput\":{\"ReadUnitsPerSecond\":3,\"WriteUnitsPerSecond\":3}}";
    private static final String ON_DEMAND_WARM = "{\"TableName\":\"orders\",\"BillingMode\":\"PAY_PER_REQUEST\","
            + "\"WarmThroughput\":{\"ReadUnitsPerSecond\":12000,\"WriteUnitsPerSecond\":4000}}";
    private static final String ON_DEMAND_CAPACITY = "{\"TableName\":\"orders\",\"BillingMode\":\"PAY_PER_REQUEST\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":1,\"WriteCapacityUnits\":1}}";
    private static final String EMPTY_WARM = "{\"TableName\":\"orders\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":1,\"WriteCapacityUnits\":1},"
            + "\"WarmThroughput\":{}}";
    private static final String UNCHANGED_WARM = "{\"TableName\":\"orders\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":3,\"WriteCapacityUnits\":3},"
            + "\"WarmThroughput\":{\"ReadUnitsPerSecond\":1,\"WriteUnitsPerSecond\":1}}";
    private static final String UNCHANGED_WARM_RAISED = "{\"TableName\":\"orders\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":4,\"WriteCapacityUnits\":4},"
            + "\"WarmThroughput\":{\"ReadUnitsPerSecond\":1,\"WriteUnitsPerSecond\":1}}";
    private static final String CHANGED_WARM = "{\"TableName\":\"orders\","
            + "\"ProvisionedThroughput\":{\"ReadCapacityUnits\":3,\"WriteCapacityUnits\":3},"
            + "\"WarmThroughput\":{\"ReadUnitsPerSecond\":2,\"WriteUnitsPerSecond\":2}}";
    private static final String INDEX_WARM = "{\"TableName\":\"orders\","
            + "\"GlobalSecondaryIndexes\":[{\"IndexName\":\"gix1\","
            + "\"WarmThroughput\":{\"ReadUnitsPerSecond\":1,\"WriteUnitsPerSecond\":1}}]}";
    private static final String INDEX_WARM_CHANGED = "{\"TableName\":\"orders\","
            + "\"GlobalSecondaryIndexes\":[{\"IndexName\":\"gix1\","
            + "\"WarmThroughput\":{\"ReadUnitsPerSecond\":2,\"WriteUnitsPerSecond\":2}}]}";
    private static final String UNDECLARED = "{\"TableName\":\"orders\"}";

    private final DynamoDbService dynamoDb = mock(DynamoDbService.class);
    private final NativeDynamoDbTableService tableService = mock(NativeDynamoDbTableService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final NativeDynamoDbBackend backend =
            new NativeDynamoDbBackend(null, null, tableService, dynamoDb, null, null, mapper);
    private final DynamoDbCfnProvisioner provisioner = new DynamoDbCfnProvisioner(
            new DynamoDbFacade(backend, backend, new RegionResolver(REGION, "000000000000")));

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private ProvisionContext updateCtx(String priorProperties) throws Exception {
        ProvisionContext create = ctx();
        return new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), TABLE_NAME, event -> { }, props(priorProperties));
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }

    private StackResource resource(String type) {
        StackResource r = new StackResource();
        r.setLogicalId("Orders");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private StackResource existingResource() {
        StackResource r = resource("AWS::DynamoDB::Table");
        r.setPhysicalId(TABLE_NAME);
        r.getAttributes().put("Arn", TABLE_ARN);
        return r;
    }

    private static TableDefinition table(long tableCapacity, long indexCapacity, boolean onDemand) {
        TableDefinition table = new TableDefinition(TABLE_NAME, List.of(), List.of());
        table.setTableArn(TABLE_ARN);
        table.setProvisionedThroughput(new ProvisionedThroughput(tableCapacity, tableCapacity));
        table.setBillingMode(onDemand ? "PAY_PER_REQUEST" : "PROVISIONED");
        GlobalSecondaryIndex gsi = new GlobalSecondaryIndex();
        gsi.setIndexName(INDEX);
        gsi.setProvisionedThroughput(new ProvisionedThroughput(indexCapacity, indexCapacity));
        table.getGlobalSecondaryIndexes().add(gsi);
        return table;
    }

    private void newTable() {
        when(tableService.createTable(anyString(), anyList(), anyList(), anyBoolean(), any(), anyList(), anyList(),
                anyString())).thenReturn(table(1, 2, false));
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table(1, 2, false));
    }

    private void existingTable(TableDefinition current) {
        when(tableService.createTable(anyString(), anyList(), anyList(), anyBoolean(), any(), anyList(), anyList(),
                anyString())).thenThrow(new AwsException("ResourceInUseException", "Table already exists", 400));
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(current);
        when(tableService.updateThroughput(anyString(), any(), anyString())).thenReturn(current);
    }

    private ThroughputChange updatedThroughput() {
        ArgumentCaptor<ThroughputChange> change = ArgumentCaptor.forClass(ThroughputChange.class);
        verify(tableService).updateThroughput(eq(TABLE_NAME), change.capture(), eq(REGION));
        return change.getValue();
    }

    @Test
    void createAppliesTheTableAndIndexCapacity() throws Exception {
        newTable();

        provisioner.provision(resource("AWS::DynamoDB::Table"), props(PROVISIONED), ctx());

        ArgumentCaptor<ThroughputSpec> table = ArgumentCaptor.forClass(ThroughputSpec.class);
        ArgumentCaptor<List<GlobalSecondaryIndex>> indexes = ArgumentCaptor.forClass(List.class);
        verify(tableService).createTable(eq(TABLE_NAME), anyList(), anyList(), eq(false), table.capture(),
                indexes.capture(), anyList(), eq(REGION));
        assertEquals(new CapacityUnits(1, 1), table.getValue().capacity());
        assertNull(table.getValue().warm());
        ProvisionedThroughput index = indexes.getValue().get(0).getProvisionedThroughput();
        assertEquals(2, index.getReadCapacityUnits());
        assertEquals(2, index.getWriteCapacityUnits());
    }

    @Test
    void createStoresTheTableAndIndexWarmThroughputWithOneMemberGiven() throws Exception {
        newTable();

        provisioner.provision(resource("AWS::DynamoDB::Table"), props(WARM), ctx());

        ArgumentCaptor<ThroughputSpec> table = ArgumentCaptor.forClass(ThroughputSpec.class);
        ArgumentCaptor<List<GlobalSecondaryIndex>> indexes = ArgumentCaptor.forClass(List.class);
        verify(tableService).createTable(eq(TABLE_NAME), anyList(), anyList(), eq(false), table.capture(),
                indexes.capture(), anyList(), eq(REGION));
        assertEquals(new WarmUnits(5000L, null), table.getValue().warm());
        assertNull(indexes.getValue().get(0).getWarmReadUnitsPerSecond());
        assertEquals(4000L, indexes.getValue().get(0).getWarmWriteUnitsPerSecond());
    }

    @Test
    void createOnDemandWithWarmThroughputGoesThroughTheThroughputPath() throws Exception {
        newTable();

        provisioner.provision(resource("AWS::DynamoDB::Table"), props(ON_DEMAND_WARM), ctx());

        ArgumentCaptor<ThroughputSpec> table = ArgumentCaptor.forClass(ThroughputSpec.class);
        verify(tableService).createTable(eq(TABLE_NAME), anyList(), anyList(), eq(true), table.capture(),
                anyList(), anyList(), eq(REGION));
        assertEquals(new WarmUnits(12000L, 4000L), table.getValue().warm());
    }

    @Test
    void createFailsWhenTheApiRefusesTheWarmThroughput() throws Exception {
        when(tableService.createTable(anyString(), anyList(), anyList(), anyBoolean(), any(), anyList(), anyList(),
                anyString())).thenThrow(new AwsException("ValidationException", "lower than initial", 400));

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::DynamoDB::Table"), props(ON_DEMAND_WARM), ctx()));

        assertEquals("lower than initial", e.getMessage());
    }

    @Test
    void onDemandWithProvisionedThroughputFailsBeforeTheTableIsCreated() throws Exception {
        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::DynamoDB::Table"), props(ON_DEMAND_CAPACITY), ctx()));

        assertEquals("InvalidRequest", e.getErrorCode());
        assertEquals("Property ProvisionedThroughput can't be used with PAY_PER_REQUEST BillingMode.",
                e.getMessage());
        verify(tableService, never()).createTable(anyString(), anyList(), anyList(), anyBoolean(), any(),
                anyList(), anyList(), anyString());
    }

    @Test
    void anEmptyWarmThroughputFailsBeforeTheTableIsCreated() throws Exception {
        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::DynamoDB::Table"), props(EMPTY_WARM), ctx()));

        assertEquals("Required property [ReadUnitsPerSecond] not found", e.getMessage());
        verify(tableService, never()).createTable(anyString(), anyList(), anyList(), anyBoolean(), any(),
                anyList(), anyList(), anyString());
    }

    @Test
    void anEmptyIndexWarmThroughputFailsBeforeTheTableIsCreated() throws Exception {
        String template = "{\"TableName\":\"orders\",\"GlobalSecondaryIndexes\":[{\"IndexName\":\"gix1\","
                + "\"WarmThroughput\":{}}]}";

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::DynamoDB::Table"), props(template), ctx()));

        assertEquals("Required property [ReadUnitsPerSecond] not found", e.getMessage());
        verify(tableService, never()).createTable(anyString(), anyList(), anyList(), anyBoolean(), any(),
                anyList(), anyList(), anyString());
    }

    @Test
    void aCapacityWithoutAMemberFailsBeforeTheTableIsCreated() throws Exception {
        String missingWrite = "{\"TableName\":\"orders\",\"ProvisionedThroughput\":{\"ReadCapacityUnits\":1}}";
        String missingBoth = "{\"TableName\":\"orders\",\"ProvisionedThroughput\":{}}";

        AwsException write = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::DynamoDB::Table"), props(missingWrite), ctx()));
        AwsException read = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::DynamoDB::Table"), props(missingBoth), ctx()));

        assertEquals("Required property [WriteCapacityUnits] not found", write.getMessage());
        assertEquals("Required property [ReadCapacityUnits] not found", read.getMessage());
        verify(tableService, never()).createTable(anyString(), anyList(), anyList(), anyBoolean(), any(),
                anyList(), anyList(), anyString());
    }

    @Test
    void aTemplateWithoutThroughputCreatesTheTableAsBefore() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(),
                anyString())).thenReturn(table(5, 5, false));
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table(5, 5, false));

        provisioner.provision(resource("AWS::DynamoDB::Table"), props(UNDECLARED), ctx());

        verify(dynamoDb).createTable(eq(TABLE_NAME), anyList(), anyList(), eq(null), eq(null), anyList(),
                anyList(), eq(REGION));
        verify(tableService, never()).createTable(anyString(), anyList(), anyList(), anyBoolean(), any(),
                anyList(), anyList(), anyString());
    }

    @Test
    void aGlobalTableIgnoresTheThroughputProperties() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(),
                anyString())).thenReturn(table(5, 5, false));
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table(5, 5, false));

        provisioner.provision(resource("AWS::DynamoDB::GlobalTable"), props(PROVISIONED), ctx());

        verify(tableService, never()).createTable(anyString(), anyList(), anyList(), anyBoolean(), any(),
                anyList(), anyList(), anyString());
        verify(tableService, never()).updateThroughput(anyString(), any(), anyString());
    }

    @Test
    void updateChangesTheTableAndIndexCapacityInOneCall() throws Exception {
        existingTable(table(1, 2, false));

        provisioner.provision(existingResource(), props(RAISED), updateCtx(PROVISIONED));

        ThroughputChange change = updatedThroughput();
        assertEquals(new CapacityUnits(3, 3), change.table().capacity());
        assertEquals(new CapacityUnits(1, 1), change.indexes().get(INDEX).capacity());
    }

    @Test
    void updateWithTheCurrentCapacityMakesNoCall() throws Exception {
        existingTable(table(1, 2, false));

        provisioner.provision(existingResource(), props(PROVISIONED), updateCtx(PROVISIONED));

        verify(tableService, never()).updateThroughput(anyString(), any(), anyString());
    }

    @Test
    void updateOfAnOnDemandTableLeavesCapacityAlone() throws Exception {
        existingTable(table(0, 0, true));

        provisioner.provision(existingResource(), props(ON_DEMAND_WARM),
                updateCtx("{\"TableName\":\"orders\",\"BillingMode\":\"PAY_PER_REQUEST\"}"));

        ThroughputChange change = updatedThroughput();
        assertNull(change.table().capacity());
        assertEquals(new WarmUnits(12000L, 4000L), change.table().warm());
    }

    @Test
    void updateRaisingCapacityAndWarmThroughputTogetherIsOneCall() throws Exception {
        existingTable(table(1, 2, false));

        provisioner.provision(existingResource(), props(CAPACITY_AND_WARM), updateCtx(PROVISIONED));

        ThroughputChange change = updatedThroughput();
        assertEquals(new CapacityUnits(3, 3), change.table().capacity());
        assertEquals(new WarmUnits(3L, 3L), change.table().warm());
    }

    @Test
    void updateWithTheCurrentWarmThroughputSendsNoWarmThroughput() throws Exception {
        TableDefinition current = table(1, 2, false);
        current.setWarmReadUnitsPerSecond(5000);
        existingTable(current);

        provisioner.provision(existingResource(), props(WARM), updateCtx(PROVISIONED));

        ThroughputChange change = updatedThroughput();
        assertNull(change.table().warm());
        assertEquals(new WarmUnits(null, 4000L), change.indexes().get(INDEX).warm());
    }

    @Test
    void anUnchangedWarmThroughputIsNotSentEvenWhenItDiffersFromTheTable() throws Exception {
        TableDefinition current = table(3, 3, false);
        current.setWarmReadUnitsPerSecond(3);
        current.setWarmWriteUnitsPerSecond(3);
        existingTable(current);

        provisioner.provision(existingResource(), props(UNCHANGED_WARM_RAISED), updateCtx(UNCHANGED_WARM));

        ThroughputChange change = updatedThroughput();
        assertEquals(new CapacityUnits(4, 4), change.table().capacity());
        assertNull(change.table().warm());
    }

    @Test
    void aChangedWarmThroughputIsSent() throws Exception {
        TableDefinition current = table(3, 3, false);
        current.setWarmReadUnitsPerSecond(3);
        current.setWarmWriteUnitsPerSecond(3);
        existingTable(current);

        provisioner.provision(existingResource(), props(CHANGED_WARM), updateCtx(UNCHANGED_WARM));

        assertEquals(new WarmUnits(2L, 2L), updatedThroughput().table().warm());
    }

    @Test
    void anIndexWarmThroughputFollowsTheSameRuleByIndexName() throws Exception {
        TableDefinition current = table(1, 3, false);
        existingTable(current);

        provisioner.provision(existingResource(), props(INDEX_WARM_CHANGED), updateCtx(INDEX_WARM));

        assertEquals(new WarmUnits(2L, 2L), updatedThroughput().indexes().get(INDEX).warm());
    }

    @Test
    void anUnchangedIndexWarmThroughputIsNotSent() throws Exception {
        existingTable(table(1, 3, false));

        provisioner.provision(existingResource(), props(INDEX_WARM), updateCtx(INDEX_WARM));

        verify(tableService, never()).updateThroughput(anyString(), any(), anyString());
    }

    @Test
    void aWarmThroughputWithNoPreviousOneCountsAsChanged() throws Exception {
        existingTable(table(1, 2, false));

        provisioner.provision(existingResource(), props(UNCHANGED_WARM), updateCtx(UNDECLARED));

        assertEquals(new WarmUnits(1L, 1L), updatedThroughput().table().warm());
    }

    @Test
    void updateThatTheApiRefusesKeepsTheSnapshotForTheRollback() throws Exception {
        existingTable(table(1, 2, false));
        when(tableService.updateThroughput(anyString(), any(), anyString()))
                .thenThrow(new AwsException("ValidationException", "decreasing WarmThroughput is not supported", 400));
        StackResource r = existingResource();

        assertThrows(AwsException.class, () -> provisioner.provision(r, props(CAPACITY_AND_WARM), updateCtx(WARM)));

        assertTrue(provisioner.retainsFailedUpdateState(r));
    }

    @Test
    void rollbackPutsThePreviousCapacityBack() throws Exception {
        existingTable(table(1, 2, false));
        StackResource r = existingResource();
        provisioner.provision(r, props(RAISED), updateCtx(PROVISIONED));
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table(3, 1, false));

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<ThroughputChange> change = ArgumentCaptor.forClass(ThroughputChange.class);
        verify(tableService, times(2)).updateThroughput(eq(TABLE_NAME), change.capture(),
                eq(REGION));
        ThroughputChange restore = change.getAllValues().get(1);
        assertEquals(new CapacityUnits(1, 1), restore.table().capacity());
        assertNull(restore.table().warm());
        assertEquals(new CapacityUnits(2, 2), restore.indexes().get(INDEX).capacity());
        assertFalse(r.getAttributes().containsKey(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackOfASnapshotWithoutCapacityLeavesItAlone() {
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table(3, 1, false));
        StackResource r = existingResource();
        r.getAttributes().put(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR, "{\"accountId\":\"000000000000\","
                + "\"region\":\"us-east-1\",\"tableName\":\"orders\",\"tags\":{},\"streamEnabled\":false,"
                + "\"streamViewType\":null,\"ttlEnabled\":false,\"ttlAttributeName\":null}");

        assertTrue(provisioner.rollbackUpdate(r));

        verify(tableService, never()).updateThroughput(anyString(), any(), anyString());
    }
}
