package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RedshiftZeroEtlWriterTest {

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

        RedshiftZeroEtlWriter writer = new RedshiftZeroEtlWriter(redshiftService, connectionFactory, objectMapper);
        writer.createLandingTable("111111111111", "warehouse", "floci_zetl_orders");

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
        RedshiftZeroEtlWriter writer = new RedshiftZeroEtlWriter(redshiftService, connectionFactory, objectMapper);

        writer.writeBatch("111111111111", "warehouse", "floci_zetl_orders", List.of(first, second));

        verify(statement, times(2)).executeUpdate();
        verify(statement).setString(1, "event-1");
        verify(statement).setString(1, "event-2");
        verify(statement, times(2)).setString(2, "INSERT");
        verify(statement, times(2)).setString(3, "aws:dynamodb");
        verify(statement, times(2)).setString(4, "us-east-1");
        verify(statement).setString(5, "000000000000000000002");
        verify(statement, times(2)).setObject(6, Instant.ofEpochSecond(1_758_153_600L));
        verify(statement, times(2)).setString(7, "{\"id\":\"1\"}");
        verify(statement, times(2)).setNull(8, Types.VARCHAR);
        verify(statement, times(2)).setString(9, "{\"value\":\"new\"}");
        verify(connection).close();
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
