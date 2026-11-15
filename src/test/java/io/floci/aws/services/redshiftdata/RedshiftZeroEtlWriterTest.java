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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /** A target whose {@code public.orders} is absent ({@code owner} null, {@code exists} false) or held as given. */
    private static final class Target {
        final Connection connection = mock(Connection.class);
        final Statement statement = mock(Statement.class);
        final RedshiftService redshiftService = mock(RedshiftService.class);
        final RedshiftZeroEtlWriter writer;

        /** {@code columns} are the table's column names, each followed by its information_schema data type. */
        Target(boolean exists, String owner, String... columns) throws SQLException {
            RedshiftDataConnectionFactory connectionFactory = mock(RedshiftDataConnectionFactory.class);
            when(redshiftService.describeClustersForAccount("111111111111", "warehouse")).thenReturn(List.of(cluster()));
            when(connectionFactory.open(any())).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            PreparedStatement ownerQuery = mock(PreparedStatement.class);
            ResultSet ownerRow = mock(ResultSet.class);
            when(connection.prepareStatement(contains("to_regclass"))).thenReturn(ownerQuery);
            when(ownerQuery.executeQuery()).thenReturn(ownerRow);
            when(ownerRow.next()).thenReturn(true);
            when(ownerRow.getBoolean(1)).thenReturn(exists);
            when(ownerRow.getString(2)).thenReturn(owner);
            if (columns.length > 0) {
                columnsOf(connection, columns);
            }
            writer = new RedshiftZeroEtlWriter(redshiftService, mock(RedshiftZeroEtlEndpoints.class), connectionFactory,
                    new ObjectMapper());
        }

        boolean create() {
            return writer.createCurrentStateTable("111111111111", WAREHOUSE_ARN, orders(), "one");
        }
    }

    @Test
    void createsAndMarksTheCurrentStateTableUnderTheDynamoDbTableName() throws Exception {
        Target target = new Target(false, null);

        assertTrue(target.create());

        InOrder inOrder = inOrder(target.connection, target.statement);
        inOrder.verify(target.connection).setAutoCommit(false);
        inOrder.verify(target.statement).executeUpdate(contains("CREATE TABLE IF NOT EXISTS public.\"orders\" "
                + "(\"id\" TEXT NOT NULL, \"value\" JSONB NOT NULL"));
        inOrder.verify(target.statement).executeUpdate(
                "COMMENT ON TABLE public.\"orders\" IS 'floci-zetl-integration:one'");
        inOrder.verify(target.connection).commit();
        verify(target.statement, never()).executeUpdate(contains("DROP TABLE"));
        verify(target.connection).close();
    }

    @Test
    void keepsATableTheIntegrationMarkedWithTheColumnsItWrites() throws Exception {
        Target target = new Target(true, "floci-zetl-integration:one", "id", "text", "value", "jsonb");

        assertFalse(target.create());

        verify(target.statement, never()).executeUpdate(any());
        verify(target.connection).commit();
    }

    @Test
    void startsAfreshATableAnEarlierIntegrationLeft() throws Exception {
        Target target = new Target(true, "floci-zetl-integration:earlier", "id", "text", "value", "jsonb");
        when(target.redshiftService.integrationExists("111111111111", "earlier")).thenReturn(false);

        assertTrue(target.create());

        InOrder inOrder = inOrder(target.statement);
        inOrder.verify(target.statement).executeUpdate("DROP TABLE public.\"orders\"");
        inOrder.verify(target.statement).executeUpdate(contains("CREATE TABLE IF NOT EXISTS public.\"orders\""));
        inOrder.verify(target.statement).executeUpdate(
                "COMMENT ON TABLE public.\"orders\" IS 'floci-zetl-integration:one'");
        verify(target.connection).commit();
    }

    @Test
    void startsAfreshItsOwnTableWhoseKeysAreNotTheSources() throws Exception {
        Target target = new Target(true, "floci-zetl-integration:one", "order_id", "text", "value", "jsonb");

        assertTrue(target.create());

        verify(target.statement).executeUpdate("DROP TABLE public.\"orders\"");
    }

    @Test
    void startsAfreshItsOwnTableWhoseKeyHasAnotherTypeThanTheSources() throws Exception {
        // The source was made again with the same key name, a number now where it was a string.
        Target target = new Target(true, "floci-zetl-integration:one", "id", "numeric", "value", "jsonb");

        assertTrue(target.create());

        verify(target.statement).executeUpdate("DROP TABLE public.\"orders\"");
        verify(target.statement).executeUpdate(contains("\"id\" TEXT NOT NULL"));
    }

    @Test
    void refusesATableAnotherLiveIntegrationMarkedAndLeavesItAlone() throws Exception {
        Target target = new Target(true, "floci-zetl-integration:other", "id", "text", "value", "jsonb");
        when(target.redshiftService.integrationExists("111111111111", "other")).thenReturn(true);

        AwsException e = assertThrows(AwsException.class, target::create);

        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertTrue(e.getMessage().contains("is in use by zero-ETL integration other"));
        verify(target.statement, never()).executeUpdate(any());
        verify(target.connection).rollback();
        verify(target.connection, never()).commit();
    }

    @Test
    void refusesATableNoIntegrationMadeAndLeavesItAlone() throws Exception {
        Target target = new Target(true, null, "id", "text", "value", "jsonb");

        AwsException e = assertThrows(AwsException.class, target::create);

        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertTrue(e.getMessage().contains("public.\"orders\""));
        assertTrue(e.getMessage().contains("was not created by a zero-ETL integration"));
        verify(target.statement, never()).executeUpdate(any());
        verify(target.connection).rollback();
        verify(target.connection, never()).commit();
        verify(target.connection).close();
    }

    @Test
    void refusesATableCarryingSomeoneElsesComment() throws Exception {
        Target target = new Target(true, "orders of the finance team", "id", "text", "value", "jsonb");

        assertThrows(AwsException.class, target::create);

        verify(target.statement, never()).executeUpdate(any());
    }

    @Test
    void emptiesTheCurrentStateTable() throws Exception {
        Target target = new Target(true, "floci-zetl-integration:one");

        target.writer.resetCurrentStateTable("111111111111", WAREHOUSE_ARN, orders());

        verify(target.statement).executeUpdate("TRUNCATE TABLE public.\"orders\"");
        verify(target.connection).close();
    }

    @Test
    void aFailureNamesTheTableAndCarriesThePostgresMessage() throws Exception {
        Target target = new Target(false, null);
        when(target.statement.executeUpdate(any())).thenThrow(new SQLException("permission denied for schema public"));

        AwsException e = assertThrows(AwsException.class, target::create);

        assertEquals("Could not create Redshift zero-ETL table public.\"orders\": permission denied for schema public",
                e.getMessage());
        verify(target.connection).rollback();
    }


    /** Stubs the information_schema lookup of a public table's columns, each name followed by its data type. */
    private static PreparedStatement columnsOf(Connection connection, String... namesAndTypes) throws SQLException {
        int count = namesAndTypes.length / 2;
        String[] names = new String[count];
        String[] types = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = namesAndTypes[2 * i];
            types[i] = namesAndTypes[2 * i + 1];
        }
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.prepareStatement(contains("information_schema.columns"))).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        Boolean[] next = new Boolean[count + 1];
        for (int i = 0; i < count; i++) {
            next[i] = true;
        }
        next[count] = false;
        when(rows.next()).thenReturn(next[0], Arrays.copyOfRange(next, 1, next.length));
        when(rows.getString(1)).thenReturn(names[0], Arrays.copyOfRange(names, 1, names.length));
        when(rows.getString(2)).thenReturn(types[0], Arrays.copyOfRange(types, 1, types.length));
        return statement;
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
        when(upsert.executeUpdate()).thenThrow(new SQLException("relation \"public.orders\" does not exist"));
        ObjectNode keys = new ObjectMapper().createObjectNode();
        keys.putObject("id").put("S", "1");

        RedshiftZeroEtlWriter writer = new RedshiftZeroEtlWriter(redshiftService, mock(RedshiftZeroEtlEndpoints.class),
                connectionFactory, new ObjectMapper());
        AwsException e = assertThrows(AwsException.class, () -> writer.applyChanges("111111111111", WAREHOUSE_ARN,
                orders(), List.of(new RedshiftZeroEtlCurrentState.Change(keys, keys))));

        assertEquals("InternalFailure", e.getErrorCode());
        assertEquals("Could not apply Redshift zero-ETL changes to public.\"orders\": relation \"public.orders\" "
                + "does not exist", e.getMessage());
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
