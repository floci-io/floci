package io.floci.aws.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.RedshiftZeroEtlEndpoints;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedshiftZeroEtlWriterTest {

    private static final String WAREHOUSE_ARN = "arn:aws:redshift:us-east-1:111111111111:cluster:warehouse";

    @Test
    void createsLandingTableWithStableEventColumns() throws Exception {
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftDataConnectionFactory connectionFactory = mock(RedshiftDataConnectionFactory.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        Cluster cluster = cluster();
        when(redshiftService.describeClustersForAccount("111111111111", "warehouse")).thenReturn(List.of(cluster));
        when(connectionFactory.open(any())).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        RedshiftZeroEtlWriter writer = new RedshiftZeroEtlWriter(redshiftService,
                mock(RedshiftZeroEtlEndpoints.class), connectionFactory, objectMapper);
        writer.createLandingTable("111111111111", WAREHOUSE_ARN, "floci_zetl_orders");

        verify(statement).executeUpdate(contains("event_id TEXT PRIMARY KEY"));
        verify(statement).executeUpdate(contains("new_image_json TEXT"));
        verify(connection).close();
    }

    @Test
    void writesAwsStreamRecordsIdempotently() throws Exception {
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftDataConnectionFactory connectionFactory = mock(RedshiftDataConnectionFactory.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(redshiftService.describeClustersForAccount("111111111111", "warehouse")).thenReturn(List.of(cluster()));
        when(connectionFactory.open(any())).thenReturn(connection);
        when(connection.prepareStatement(contains("ON CONFLICT (event_id) DO NOTHING"))).thenReturn(statement);

        JsonNode first = record("event-1", "000000000000000000001");
        JsonNode second = record("event-2", "000000000000000000002");
        RedshiftZeroEtlWriter writer = new RedshiftZeroEtlWriter(redshiftService,
                mock(RedshiftZeroEtlEndpoints.class), connectionFactory, objectMapper);

        writer.writeBatch("111111111111", WAREHOUSE_ARN, "floci_zetl_orders", List.of(first, second));

        verify(statement, times(2)).executeUpdate();
        verify(statement).setString(1, "event-1");
        verify(statement).setString(1, "event-2");
        verify(statement, times(2)).setString(2, "INSERT");
        verify(statement, times(2)).setString(3, "aws:dynamodb");
        verify(statement, times(2)).setString(4, "us-east-1");
        verify(statement).setString(5, "000000000000000000002");
        verify(statement, times(2)).setObject(6,
                OffsetDateTime.ofInstant(Instant.ofEpochSecond(1_758_153_600L), ZoneOffset.UTC),
                Types.TIMESTAMP_WITH_TIMEZONE);
        verify(statement, times(2)).setString(7, "{\"id\":\"1\"}");
        verify(statement, times(2)).setNull(8, Types.VARCHAR);
        verify(statement, times(2)).setString(9, "{\"value\":\"new\"}");
        verify(connection).close();
    }

    @Test
    void createsTheCurrentStateTableUnderTheDynamoDbTableName() throws Exception {
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftDataConnectionFactory connectionFactory = mock(RedshiftDataConnectionFactory.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(redshiftService.describeClustersForAccount("111111111111", "warehouse")).thenReturn(List.of(cluster()));
        when(connectionFactory.open(any())).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        new RedshiftZeroEtlWriter(redshiftService, mock(RedshiftZeroEtlEndpoints.class), connectionFactory,
                new ObjectMapper()).createCurrentStateTable("111111111111", WAREHOUSE_ARN, orders());

        verify(statement).executeUpdate(contains("CREATE TABLE IF NOT EXISTS public.\"orders\" (\"id\" TEXT NOT NULL, "
                + "\"value\" JSONB NOT NULL"));
        verify(connection).close();
    }

    @Test
    void appliesUpsertsAndDeletesInOneTransaction() throws Exception {
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftDataConnectionFactory connectionFactory = mock(RedshiftDataConnectionFactory.class);
        Connection connection = mock(Connection.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        PreparedStatement delete = mock(PreparedStatement.class);
        when(redshiftService.describeClustersForAccount("111111111111", "warehouse")).thenReturn(List.of(cluster()));
        when(connectionFactory.open(any())).thenReturn(connection);
        when(connection.prepareStatement(contains("ON CONFLICT"))).thenReturn(upsert);
        when(connection.prepareStatement(contains("DELETE FROM"))).thenReturn(delete);
        ObjectNode keys = new ObjectMapper().createObjectNode();
        keys.putObject("id").put("S", "1");
        ObjectNode item = new ObjectMapper().createObjectNode();
        item.putObject("id").put("S", "1");
        item.putObject("note").put("S", "hello");

        new RedshiftZeroEtlWriter(redshiftService, mock(RedshiftZeroEtlEndpoints.class), connectionFactory,
                new ObjectMapper()).applyChanges("111111111111", WAREHOUSE_ARN, orders(), List.of(
                new RedshiftZeroEtlCurrentState.Change(keys, item),
                new RedshiftZeroEtlCurrentState.Change(keys, null)));

        InOrder inOrder = inOrder(connection, upsert, delete);
        inOrder.verify(connection).setAutoCommit(false);
        inOrder.verify(upsert).setString(1, "1");
        inOrder.verify(upsert).setString(2, "{\"id\":{\"S\":\"1\"},\"note\":{\"S\":\"hello\"}}");
        inOrder.verify(upsert).executeUpdate();
        inOrder.verify(delete).setString(1, "1");
        inOrder.verify(delete).executeUpdate();
        inOrder.verify(connection).commit();
        inOrder.verify(connection).close();
        verify(connection, never()).rollback();
    }

    @Test
    void aFailedChangeRollsTheBatchBack() throws Exception {
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftDataConnectionFactory connectionFactory = mock(RedshiftDataConnectionFactory.class);
        Connection connection = mock(Connection.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        when(redshiftService.describeClustersForAccount("111111111111", "warehouse")).thenReturn(List.of(cluster()));
        when(connectionFactory.open(any())).thenReturn(connection);
        when(connection.prepareStatement(any())).thenReturn(upsert);
        when(upsert.executeUpdate()).thenThrow(new SQLException("numeric field overflow"));
        ObjectNode keys = new ObjectMapper().createObjectNode();
        keys.putObject("id").put("S", "1");

        RedshiftZeroEtlWriter writer = new RedshiftZeroEtlWriter(redshiftService, mock(RedshiftZeroEtlEndpoints.class),
                connectionFactory, new ObjectMapper());
        AwsException e = assertThrows(AwsException.class, () -> writer.applyChanges("111111111111", WAREHOUSE_ARN,
                orders(), List.of(new RedshiftZeroEtlCurrentState.Change(keys, keys))));

        assertEquals("InternalFailure", e.getErrorCode());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private static RedshiftZeroEtlCurrentState orders() {
        TableDefinition table = new TableDefinition();
        table.setTableName("orders");
        table.setKeySchema(List.of(new KeySchemaElement("id", "HASH")));
        table.setAttributeDefinitions(List.of(new AttributeDefinition("id", "S")));
        return RedshiftZeroEtlCurrentState.of(table);
    }

    @Test
    void writesANamespaceThroughItsWorkgroupIntoTheNamespaceDatabase() throws Exception {
        String namespaceArn = "arn:aws:redshift-serverless:us-east-1:111111111111:namespace/"
                + "8f3e0b52-6c1a-4f0e-9d7b-2a4c5e6f7a8b";
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftZeroEtlEndpoints endpoints = mock(RedshiftZeroEtlEndpoints.class);
        RedshiftDataConnectionFactory connectionFactory = mock(RedshiftDataConnectionFactory.class);
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(endpoints.targetWorkgroup("111111111111", namespaceArn)).thenReturn(
                new RedshiftServerlessService.WorkgroupTarget(
                        "arn:aws:redshift-serverless:us-east-1:111111111111:workgroup/analytics", "analytics",
                        "localhost", 5433, "warehouse", "admin", "password", "admin"));
        ArgumentCaptor<RedshiftDataResourceResolver.DatabaseTarget> target =
                ArgumentCaptor.forClass(RedshiftDataResourceResolver.DatabaseTarget.class);
        when(connectionFactory.open(target.capture())).thenReturn(connection);

        new RedshiftZeroEtlWriter(redshiftService, endpoints, connectionFactory, new ObjectMapper())
                .createLandingTable("111111111111", namespaceArn, "floci_zetl_orders");

        assertEquals("localhost", target.getValue().host());
        assertEquals(5433, target.getValue().port());
        assertEquals("warehouse", target.getValue().database());
        assertEquals("admin", target.getValue().user());
        assertEquals("password", target.getValue().password());
        verifyNoInteractions(redshiftService);
    }

    private static Cluster cluster() {
        Cluster cluster = new Cluster();
        cluster.setClusterIdentifier("warehouse");
        cluster.setContainerHost("localhost");
        cluster.setContainerPort(5432);
        cluster.setMasterUsername("admin");
        cluster.setMasterPassword("password");
        return cluster;
    }

    private static JsonNode record(String eventId, String sequenceNumber) {
        ObjectNode record = new ObjectMapper().createObjectNode()
                .put("eventID", eventId)
                .put("eventName", "INSERT")
                .put("eventSource", "aws:dynamodb")
                .put("awsRegion", "us-east-1");
        ObjectNode dynamodb = record.putObject("dynamodb")
                .put("SequenceNumber", sequenceNumber)
                .put("ApproximateCreationDateTime", 1_758_153_600L);
        dynamodb.putObject("Keys").put("id", "1");
        dynamodb.putObject("NewImage").put("value", "new");
        return record;
    }
}
