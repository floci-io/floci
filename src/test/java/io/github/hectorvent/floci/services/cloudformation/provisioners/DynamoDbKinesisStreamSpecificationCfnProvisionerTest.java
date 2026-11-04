package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.NativeDynamoDbTableService;
import io.github.hectorvent.floci.services.dynamodb.backend.NativeDynamoDbBackend;
import io.github.hectorvent.floci.services.dynamodb.model.KinesisStreamingDestination;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code KinesisStreamSpecification} on {@code AWS::DynamoDB::Table}: the declared stream becomes
 * the table's active Kinesis streaming destination on create and update, removing the block turns
 * it off, and a failed in-place update puts the previous destinations back.
 */
class DynamoDbKinesisStreamSpecificationCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String TABLE_NAME = "orders";
    private static final String TABLE_ARN = "arn:aws:dynamodb:us-east-1:000000000000:table/" + TABLE_NAME;
    private static final String STREAM_ARN = "arn:aws:kinesis:us-east-1:000000000000:stream/orders-events";
    private static final String OTHER_STREAM_ARN = "arn:aws:kinesis:us-east-1:000000000000:stream/orders-audit";
    private static final String MILLISECOND = KinesisStreamingDestination.PRECISION_MILLISECOND;
    private static final String MICROSECOND = KinesisStreamingDestination.PRECISION_MICROSECOND;
    private static final String DECLARED = "{\"TableName\":\"orders\",\"KinesisStreamSpecification\":"
            + "{\"StreamArn\":\"" + STREAM_ARN + "\"}}";
    private static final String DECLARED_MICROSECOND = "{\"TableName\":\"orders\",\"KinesisStreamSpecification\":"
            + "{\"StreamArn\":\"" + STREAM_ARN + "\",\"ApproximateCreationDateTimePrecision\":\"MICROSECOND\"}}";
    private static final String DECLARED_OTHER = "{\"TableName\":\"orders\",\"KinesisStreamSpecification\":"
            + "{\"StreamArn\":\"" + OTHER_STREAM_ARN + "\"}}";
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

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Orders");
        r.setResourceType("AWS::DynamoDB::Table");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private StackResource existingResource() {
        StackResource r = resource();
        r.setPhysicalId(TABLE_NAME);
        r.getAttributes().put("Arn", TABLE_ARN);
        return r;
    }

    private static TableDefinition table(KinesisStreamingDestination... destinations) {
        TableDefinition table = new TableDefinition();
        table.setTableName(TABLE_NAME);
        table.setTableArn(TABLE_ARN);
        table.setKinesisStreamingDestinations(List.of(destinations));
        return table;
    }

    private static KinesisStreamingDestination active(String streamArn, String precision) {
        return new KinesisStreamingDestination(streamArn, precision);
    }

    private void newTable() {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenReturn(table());
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table());
    }

    private void existingTable(TableDefinition current) {
        when(dynamoDb.createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(), anyString()))
                .thenThrow(new AwsException("ResourceInUseException", "Table already exists", 400));
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(current);
    }

    @Test
    void createEnablesTheDeclaredDestinationWithMillisecondPrecisionByDefault() throws Exception {
        newTable();

        provisioner.provision(resource(), props(DECLARED), ctx());

        verify(tableService).enableKinesisStreamingDestination(TABLE_NAME, STREAM_ARN, MILLISECOND, REGION);
    }

    @Test
    void createHonoursTheDeclaredPrecision() throws Exception {
        newTable();

        provisioner.provision(resource(), props(DECLARED_MICROSECOND), ctx());

        verify(tableService).enableKinesisStreamingDestination(TABLE_NAME, STREAM_ARN, MICROSECOND, REGION);
    }

    @Test
    void createWithoutTheBlockTouchesNoDestination() throws Exception {
        newTable();

        provisioner.provision(resource(), props(UNDECLARED), ctx());

        verifyNoInteractions(tableService);
    }

    @Test
    void blockWithoutAStreamArnFailsModelValidationBeforeTheTableIsCreated() throws Exception {
        StackResource r = resource();
        JsonNode props = props("{\"TableName\":\"orders\",\"KinesisStreamSpecification\":{}}");
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("InvalidRequest", e.getErrorCode());
        verify(dynamoDb, never()).createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(),
                anyString());
    }

    @Test
    void unknownPrecisionFailsModelValidationBeforeTheTableIsCreated() throws Exception {
        StackResource r = resource();
        JsonNode props = props("{\"TableName\":\"orders\",\"KinesisStreamSpecification\":"
                + "{\"StreamArn\":\"" + STREAM_ARN + "\",\"ApproximateCreationDateTimePrecision\":\"SECOND\"}}");
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("InvalidRequest", e.getErrorCode());
        verify(dynamoDb, never()).createTable(anyString(), anyList(), anyList(), any(), any(), anyList(), anyList(),
                anyString());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"false|String", "[]|JSONArray"})
    void blockThatIsNotAnObjectFailsTemplateValidationBeforeTheTableIsCreated(String value, String found)
            throws Exception {
        StackResource r = resource();
        JsonNode props = props("{\"TableName\":\"orders\",\"KinesisStreamSpecification\":" + value + "}");
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("Property [KinesisStreamSpecification] expected type: JSONObject, found: " + found,
                e.getMessage());
        verifyNoInteractions(dynamoDb, tableService);
    }

    @Test
    void updateWithABlockThatIsNotAnObjectLeavesTheDeclaredDestinationActive() throws Exception {
        existingTable(table(active(STREAM_ARN, MILLISECOND)));
        StackResource r = existingResource();
        JsonNode props = props("{\"TableName\":\"orders\",\"KinesisStreamSpecification\":\"off\"}");
        ProvisionContext ctx = updateCtx(DECLARED);

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        verifyNoInteractions(tableService);
    }

    /**
     * A stream that does not exist never becomes the active destination, so AWS fails the table as
     * NotStabilized inside its own provision, and the new table is not left behind.
     */
    @Test
    void createWhoseStreamDoesNotExistFailsAsNotStabilizedAndDeletesTheTable() throws Exception {
        newTable();
        doThrow(new AwsException("ResourceNotFoundException", "Kinesis stream not found: " + STREAM_ARN, 400))
                .when(tableService).enableKinesisStreamingDestination(TABLE_NAME, STREAM_ARN, MILLISECOND, REGION);
        StackResource r = resource();
        JsonNode props = props(DECLARED);
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("NotStabilized", e.getErrorCode());
        assertEquals("Resource of type 'AWS::DynamoDB::Table' with identifier 'orders' did not stabilize.",
                e.getMessage());
        verify(dynamoDb).deleteTable(TABLE_NAME, REGION);
    }

    /** A stream's name, which {@code Ref} of an AWS::Kinesis::Stream returns, is not its ARN. */
    @Test
    void createWithAStreamNameInsteadOfItsArnFailsAsNotStabilizedAndDeletesTheTable() throws Exception {
        newTable();
        StackResource r = resource();
        JsonNode props = props("{\"TableName\":\"orders\",\"KinesisStreamSpecification\":"
                + "{\"StreamArn\":\"orders-events-stream-name-long-enough-1\"}}");
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("NotStabilized", e.getErrorCode());
        verifyNoInteractions(tableService);
        verify(dynamoDb).deleteTable(TABLE_NAME, REGION);
    }

    /**
     * EnableKinesisStreamingDestination rejects a StreamArn under 37 characters with its own
     * validation. An empty one is present, so it passes model validation and fails the same check.
     */
    @ParameterizedTest
    @ValueSource(strings = {"kds-short", ""})
    void createWithAStreamArnUnder37CharactersFailsTheApiValidationAndDeletesTheTable(String streamArn)
            throws Exception {
        newTable();
        StackResource r = resource();
        JsonNode props = props("{\"TableName\":\"orders\",\"KinesisStreamSpecification\":"
                + "{\"StreamArn\":\"" + streamArn + "\"}}");
        ProvisionContext ctx = ctx();

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("InvalidRequest", e.getErrorCode());
        assertEquals("1 validation error detected: Value '" + streamArn + "' at 'streamArn' failed to satisfy"
                + " constraint: Member must have length greater than or equal to 37", e.getMessage());
        verifyNoInteractions(tableService);
        verify(dynamoDb).deleteTable(TABLE_NAME, REGION);
    }

    @Test
    void updateWithTheSameDestinationMakesNoDestinationCall() throws Exception {
        existingTable(table(active(STREAM_ARN, MILLISECOND)));

        provisioner.provision(existingResource(), props(DECLARED), updateCtx(DECLARED));

        verifyNoInteractions(tableService);
    }

    /** A destination set through the API with a stream name does not let a template declare that name. */
    @Test
    void updateDeclaringAStreamArnTheTableAlreadyForwardsToStillChecksIt() throws Exception {
        existingTable(table(active("events", MILLISECOND)));
        StackResource r = existingResource();
        JsonNode props = props("{\"TableName\":\"orders\",\"KinesisStreamSpecification\":{\"StreamArn\":\"events\"}}");
        ProvisionContext ctx = updateCtx(UNDECLARED);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx));

        assertEquals("InvalidRequest", e.getErrorCode());
        verifyNoInteractions(tableService);
    }

    @Test
    void updateThatRemovesTheBlockDisablesTheDestination() throws Exception {
        existingTable(table(active(STREAM_ARN, MILLISECOND)));

        provisioner.provision(existingResource(), props(UNDECLARED), updateCtx(DECLARED));

        verify(tableService).disableKinesisStreamingDestination(TABLE_NAME, STREAM_ARN, REGION);
    }

    /** A destination enabled outside a stack that never declared the property survives its updates. */
    @Test
    void updateOfAStackThatNeverDeclaredTheBlockLeavesDestinationsAlone() throws Exception {
        existingTable(table(active(STREAM_ARN, MILLISECOND)));

        provisioner.provision(existingResource(), props(UNDECLARED), updateCtx(UNDECLARED));

        verifyNoInteractions(tableService);
    }

    @Test
    void updateToAnotherStreamMovesTheDestination() throws Exception {
        existingTable(table(active(OTHER_STREAM_ARN, MILLISECOND)));

        provisioner.provision(existingResource(), props(DECLARED), updateCtx(DECLARED_OTHER));

        verify(tableService).disableKinesisStreamingDestination(TABLE_NAME, OTHER_STREAM_ARN, REGION);
        verify(tableService).enableKinesisStreamingDestination(TABLE_NAME, STREAM_ARN, MILLISECOND, REGION);
    }

    /**
     * CloudFormation changes the precision with one UpdateKinesisStreamingDestination call, so the
     * destination stays active and nothing it already captured is discarded.
     */
    @Test
    void updateThatChangesThePrecisionUpdatesTheDestinationInPlace() throws Exception {
        existingTable(table(active(STREAM_ARN, MILLISECOND)));

        provisioner.provision(existingResource(), props(DECLARED_MICROSECOND), updateCtx(DECLARED));

        verify(tableService).updateKinesisStreamingDestination(TABLE_NAME, STREAM_ARN, MICROSECOND, REGION);
        verifyNoMoreInteractions(tableService);
    }

    @Test
    void rollbackUpdatePutsThePreviousDestinationBack() throws Exception {
        existingTable(table(active(OTHER_STREAM_ARN, MILLISECOND)));
        StackResource r = existingResource();
        provisioner.provision(r, props(DECLARED), updateCtx(DECLARED_OTHER));
        // The table as the update left it: forwarding to the newly declared stream only.
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table(active(STREAM_ARN, MILLISECOND)));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(tableService).disableKinesisStreamingDestination(TABLE_NAME, STREAM_ARN, REGION);
        verify(tableService).enableKinesisStreamingDestination(TABLE_NAME, OTHER_STREAM_ARN, MILLISECOND, REGION);
        assertFalse(r.getAttributes().containsKey(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackOfAPrecisionChangePutsThePreviousPrecisionBackInPlace() throws Exception {
        existingTable(table(active(STREAM_ARN, MILLISECOND)));
        StackResource r = existingResource();
        provisioner.provision(r, props(DECLARED_MICROSECOND), updateCtx(DECLARED));
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table(active(STREAM_ARN, MICROSECOND)));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(tableService).updateKinesisStreamingDestination(TABLE_NAME, STREAM_ARN, MICROSECOND, REGION);
        verify(tableService).updateKinesisStreamingDestination(TABLE_NAME, STREAM_ARN, MILLISECOND, REGION);
        verifyNoMoreInteractions(tableService);
    }

    /**
     * The template checks are for a newly declared StreamArn. A destination the table already had,
     * here one set through the API with a stream name, is put back as it was.
     */
    @Test
    void rollbackRestoresAPriorDestinationWithoutTheTemplateChecks() throws Exception {
        existingTable(table(active("events", MILLISECOND)));
        StackResource r = existingResource();
        provisioner.provision(r, props(DECLARED), updateCtx(UNDECLARED));
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table(active(STREAM_ARN, MILLISECOND)));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(tableService).disableKinesisStreamingDestination(TABLE_NAME, STREAM_ARN, REGION);
        verify(tableService).enableKinesisStreamingDestination(TABLE_NAME, "events", MILLISECOND, REGION);
    }

    @Test
    void rollbackOfAStackThatNeverDeclaredTheBlockLeavesDestinationsAlone() throws Exception {
        existingTable(table(active(STREAM_ARN, MILLISECOND)));
        StackResource r = existingResource();
        provisioner.provision(r, props(UNDECLARED), updateCtx(UNDECLARED));
        // A destination set outside the stack while the update ran.
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table(active(OTHER_STREAM_ARN, MILLISECOND)));

        assertTrue(provisioner.rollbackUpdate(r));

        verifyNoInteractions(tableService);
    }

    /** A snapshot an earlier version took, without the destinations, leaves them as they are. */
    @Test
    void rollbackOfASnapshotWithoutDestinationsLeavesThemAlone() {
        when(dynamoDb.describeTable(TABLE_NAME, REGION)).thenReturn(table(active(STREAM_ARN, MILLISECOND)));
        StackResource r = existingResource();
        r.getAttributes().put(CfnRollback.DYNAMODB_TABLE_UPDATE_SNAPSHOT_ATTR, "{\"accountId\":\"000000000000\","
                + "\"region\":\"us-east-1\",\"tableName\":\"orders\",\"tags\":{},\"streamEnabled\":false,"
                + "\"streamViewType\":null,\"ttlEnabled\":false,\"ttlAttributeName\":null}");

        assertTrue(provisioner.rollbackUpdate(r));

        verifyNoInteractions(tableService);
    }
}
