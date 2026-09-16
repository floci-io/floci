package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The DynamoDB CFN provisioner in isolation: the exact {@code Fn::GetAtt} keys the registry
 * schema declares, and the delete contract for each of its three types. The stream and replica
 * behaviour keeps its own coverage in {@code DynamoDbStreamSpecificationCfnProvisionerTest} and
 * {@code DynamoDbReplicaCfnProvisionerTest}, which reach this class through the fixture.
 */
class DynamoDbCfnProvisionerTest {

    private static final String TABLE_NAME = "orders";
    private static final String TABLE_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/" + TABLE_NAME;
    private static final String TABLE_ID = "2d4c5c3a-1b6e-4a1d-9f0a-7b1c2d3e4f50";
    private static final String STREAM_ARN = TABLE_ARN + "/stream/2026-01-01T00:00:00.000";

    private final DynamoDbService dynamoDb = mock(DynamoDbService.class);
    private final DynamoDbCfnProvisioner provisioner = new DynamoDbCfnProvisioner(dynamoDb);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
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

    @Test
    void tablePublishesArnAndTableIdAndNoStreamArnWithoutAStream() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");

        provisioner.provision(r, props("{\"TableName\":\"orders\"}"), ctx());

        assertEquals(TABLE_NAME, r.getPhysicalId());
        assertEquals(Set.of("Arn", "TableId"), r.getAttributes().keySet());
        assertEquals(TABLE_ARN, r.getAttributes().get("Arn"));
        assertEquals(TABLE_ID, r.getAttributes().get("TableId"));
    }

    @Test
    void globalTableIsProvisionedAsATableAndPublishesTableId() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        StackResource r = resource("AWS::DynamoDB::GlobalTable", "Orders");

        provisioner.provision(r, props("{\"TableName\":\"orders\"}"), ctx());

        verify(dynamoDb).createTable(eq(TABLE_NAME), anyList(), anyList(), any(), any(), anyList(), anyList(),
                eq("us-east-1"));
        assertEquals(TABLE_NAME, r.getPhysicalId());
        assertEquals(Set.of("Arn", "TableId"), r.getAttributes().keySet());
        assertEquals(TABLE_ID, r.getAttributes().get("TableId"));
    }

    @Test
    void streamedTablePublishesStreamArn() throws Exception {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table(false));
        when(dynamoDb.enableStream(TABLE_NAME, "NEW_AND_OLD_IMAGES", "us-east-1")).thenReturn(table(true));
        StackResource r = resource("AWS::DynamoDB::Table", "Orders");

        provisioner.provision(r, props("{\"TableName\":\"orders\",\"StreamSpecification\":"
                + "{\"StreamViewType\":\"NEW_AND_OLD_IMAGES\"}}"), ctx());

        assertEquals(Set.of("Arn", "TableId", "StreamArn"), r.getAttributes().keySet());
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
}
