package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A template that declares {@code StreamSpecification} on {@code AWS::DynamoDB::Table} must get a
 * streamed table.
 *
 * <p>The CloudFormation property carries no {@code StreamEnabled} flag — unlike the DynamoDB API,
 * declaring the block IS the request — so provisioning read it as absent and created the table
 * streamless. DescribeTable then returned no {@code LatestStreamArn} and an event source mapping
 * registered against the expected stream ARN polled "Stream not found" forever, while the plain
 * UpdateTable API on the same runtime enabled streams correctly.
 */
class DynamoDbStreamSpecificationCfnProvisionerTest {

    private static final String TABLE = "stream-repro";
    private static final String TABLE_ARN =
            "arn:aws:dynamodb:us-east-1:000000000000:table/" + TABLE;
    private static final String STREAM_ARN = TABLE_ARN + "/stream/2026-01-01T00:00:00.000";

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService dynamoDbService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        dynamoDbService = mock(DynamoDbService.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, dynamoDbService, null, null, null, null, null, null,
                null, null, null, null, null, null, mapper, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                new CloudFormationResourceRegistry(List.of()), null);

        TableDefinition created = new TableDefinition();
        created.setTableName(TABLE);
        created.setTableArn(TABLE_ARN);
        when(dynamoDbService.createTable(anyString(), anyList(), anyList(), any(), any(),
                anyList(), anyList(), anyString())).thenReturn(created);
    }

    /** Make provisioning take the update path: the table already exists, with a live stream. */
    private void existingStreamedTable() {
        when(dynamoDbService.createTable(anyString(), anyList(), anyList(), any(), any(),
                anyList(), anyList(), anyString()))
                .thenThrow(new AwsException("ResourceInUseException", "Table already exists", 400));
        when(dynamoDbService.describeTable(TABLE, "us-east-1"))
                .thenReturn(streamed("NEW_AND_OLD_IMAGES"));
    }

    /** The table the service reports once its stream is switched off (ARN retained, as AWS does). */
    private TableDefinition streamDisabled() {
        TableDefinition table = streamed("NEW_AND_OLD_IMAGES");
        table.setStreamEnabled(false);
        return table;
    }

    /** The table the service reports once its stream is on. */
    private TableDefinition streamed(String viewType) {
        TableDefinition table = new TableDefinition();
        table.setTableName(TABLE);
        table.setTableArn(TABLE_ARN);
        table.setStreamEnabled(true);
        table.setStreamArn(STREAM_ARN);
        table.setStreamViewType(viewType);
        return table;
    }

    private StackResource provisionTable(String properties) throws Exception {
        return provisioner.provision("Table", "AWS::DynamoDB::Table",
                mapper.readTree(properties), engine(), "us-east-1", "000000000000", "my-stack");
    }

    /**
     * An UpdateStack re-provisions with the resource's PREVIOUS attributes already in place, so a
     * stale StreamArn has to be actively removed rather than merely left unwritten.
     */
    private StackResource reprovisionTable(String properties, Map<String, String> priorAttributes)
            throws Exception {
        return provisioner.provision("Table", "AWS::DynamoDB::Table",
                mapper.readTree(properties), engine(), "us-east-1", "000000000000", "my-stack",
                TABLE, priorAttributes);
    }

    // ── the reported bug ──────────────────────────────────────────────────────

    @Test
    void declaredStreamSpecificationEnablesTheStream() throws Exception {
        when(dynamoDbService.enableStream(eq(TABLE), eq("NEW_AND_OLD_IMAGES"), eq("us-east-1")))
                .thenReturn(streamed("NEW_AND_OLD_IMAGES"));

        StackResource resource = provisionTable("""
                {"TableName":"stream-repro",
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "BillingMode":"PAY_PER_REQUEST",
                 "StreamSpecification":{"StreamViewType":"NEW_AND_OLD_IMAGES"}}
                """);

        // Previously never called: the table came out streamless.
        verify(dynamoDbService).enableStream(TABLE, "NEW_AND_OLD_IMAGES", "us-east-1");
        assertEquals(TABLE, resource.getPhysicalId());
        assertEquals(TABLE_ARN, resource.getAttributes().get("Arn"));
        // Fn::GetAtt StreamArn must resolve to the stream that actually exists.
        assertEquals(STREAM_ARN, resource.getAttributes().get("StreamArn"));
    }

    @Test
    void streamViewTypeIsHonoured() throws Exception {
        when(dynamoDbService.enableStream(eq(TABLE), eq("KEYS_ONLY"), eq("us-east-1")))
                .thenReturn(streamed("KEYS_ONLY"));

        provisionTable("""
                {"TableName":"stream-repro",
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "StreamSpecification":{"StreamViewType":"KEYS_ONLY"}}
                """);

        verify(dynamoDbService).enableStream(TABLE, "KEYS_ONLY", "us-east-1");
    }

    /**
     * The CloudFormation schema marks StreamViewType required, but floci accepts a template that
     * omits it and applies a view type rather than rejecting the stack — the same leniency
     * DynamoDbJsonHandler already applies on CreateTable/UpdateTable.
     */
    @Test
    void streamSpecificationWithoutAViewTypeStillEnablesTheStream() throws Exception {
        when(dynamoDbService.enableStream(eq(TABLE), any(), eq("us-east-1")))
                .thenReturn(streamed("NEW_AND_OLD_IMAGES"));

        provisionTable("""
                {"TableName":"stream-repro",
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "StreamSpecification":{}}
                """);

        verify(dynamoDbService).enableStream(TABLE, null, "us-east-1");
    }

    // ── unchanged behaviour without the block ─────────────────────────────────

    @Test
    void tableWithoutStreamSpecificationGetsNoStreamAndNoStreamArnAttribute() throws Exception {
        StackResource resource = provisionTable("""
                {"TableName":"stream-repro",
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}]}
                """);

        verify(dynamoDbService, never()).enableStream(anyString(), any(), anyString());
        assertEquals(TABLE_ARN, resource.getAttributes().get("Arn"));
        // A streamless table previously advertised a synthetic StreamArn that resolved to nothing.
        assertNull(resource.getAttributes().get("StreamArn"));
        assertFalse(resource.getAttributes().containsKey("StreamArn"));
    }

    // ── an update that drops the block switches the stream back off ──────────

    @Test
    void removingStreamSpecificationOnUpdateDisablesTheStream() throws Exception {
        existingStreamedTable();
        when(dynamoDbService.disableStream(TABLE, "us-east-1")).thenReturn(streamDisabled());

        StackResource resource = reprovisionTable("""
                {"TableName":"stream-repro",
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}]}
                """, Map.of("Arn", TABLE_ARN, "StreamArn", STREAM_ARN));

        // Otherwise the table keeps emitting records to whatever still holds its ARN.
        verify(dynamoDbService).disableStream(TABLE, "us-east-1");
        verify(dynamoDbService, never()).enableStream(anyString(), any(), anyString());
        // and the stale ARN must stop resolving through Fn::GetAtt
        assertNull(resource.getAttributes().get("StreamArn"));
        assertFalse(resource.getAttributes().containsKey("StreamArn"));
    }

    @Test
    void keepingStreamSpecificationOnUpdateDoesNotDisableTheStream() throws Exception {
        existingStreamedTable();
        when(dynamoDbService.enableStream(eq(TABLE), eq("NEW_AND_OLD_IMAGES"), eq("us-east-1")))
                .thenReturn(streamed("NEW_AND_OLD_IMAGES"));

        StackResource resource = provisionTable("""
                {"TableName":"stream-repro",
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "StreamSpecification":{"StreamViewType":"NEW_AND_OLD_IMAGES"}}
                """);

        verify(dynamoDbService, never()).disableStream(anyString(), anyString());
        assertEquals(STREAM_ARN, resource.getAttributes().get("StreamArn"));
    }

    /** A table that never had a stream must not trigger a pointless disable call. */
    @Test
    void streamlessTableIsNotDisabledAgain() throws Exception {
        provisionTable("""
                {"TableName":"stream-repro",
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}]}
                """);

        verify(dynamoDbService, never()).disableStream(anyString(), anyString());
    }

    /**
     * Changing StreamViewType on an update must reach the stream, not just the table metadata:
     * the records a stream emits are built from its view type.
     */
    @Test
    void changingStreamViewTypeOnUpdateRetargetsTheStream() throws Exception {
        existingStreamedTable();
        when(dynamoDbService.enableStream(eq(TABLE), eq("KEYS_ONLY"), eq("us-east-1")))
                .thenReturn(streamed("KEYS_ONLY"));

        StackResource resource = reprovisionTable("""
                {"TableName":"stream-repro",
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "StreamSpecification":{"StreamViewType":"KEYS_ONLY"}}
                """, Map.of("Arn", TABLE_ARN, "StreamArn", STREAM_ARN));

        verify(dynamoDbService).enableStream(TABLE, "KEYS_ONLY", "us-east-1");
        verify(dynamoDbService, never()).disableStream(anyString(), anyString());
        assertEquals(STREAM_ARN, resource.getAttributes().get("StreamArn"));
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }
}
