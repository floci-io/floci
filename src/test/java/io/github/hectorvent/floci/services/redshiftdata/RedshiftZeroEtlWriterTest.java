package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        when(redshiftService.describeClusters("warehouse")).thenReturn(List.of(cluster));
        when(connectionFactory.open(any())).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        RedshiftZeroEtlWriter writer = new RedshiftZeroEtlWriter(redshiftService, connectionFactory, objectMapper);
        writer.createLandingTable("warehouse", "floci_zetl_orders");

        verify(statement).executeUpdate(contains("event_id TEXT PRIMARY KEY"));
        verify(statement).executeUpdate(contains("new_image_json TEXT"));
        verify(connection).close();
    }

    @Test
    void writesStreamRecordsIdempotentlyAndReturnsNewestSequence() throws Exception {
        RedshiftService redshiftService = mock(RedshiftService.class);
        RedshiftDataConnectionFactory connectionFactory = mock(RedshiftDataConnectionFactory.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(redshiftService.describeClusters("warehouse")).thenReturn(List.of(cluster()));
        when(connectionFactory.open(any())).thenReturn(connection);
        when(connection.prepareStatement(contains("ON CONFLICT (event_id) DO NOTHING"))).thenReturn(statement);

        DynamoDbStreamRecord first = record("event-1", "000000000000000000001");
        DynamoDbStreamRecord second = record("event-2", "000000000000000000002");
        RedshiftZeroEtlWriter writer = new RedshiftZeroEtlWriter(redshiftService, connectionFactory, objectMapper);

        String sequence = writer.writeBatch("warehouse", "floci_zetl_orders", List.of(first, second));

        assertEquals("000000000000000000002", sequence);
        verify(statement, times(2)).executeUpdate();
        verify(statement).setString(1, "event-1");
        verify(statement).setString(1, "event-2");
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

    private static DynamoDbStreamRecord record(String eventId, String sequenceNumber) {
        DynamoDbStreamRecord record = new DynamoDbStreamRecord();
        record.setEventId(eventId);
        record.setEventName("INSERT");
        record.setEventSource("aws:dynamodb");
        record.setAwsRegion("us-east-1");
        record.setSequenceNumber(sequenceNumber);
        record.setApproximateCreationDateTime(1_758_153_600L);
        record.setKeys(JsonNodeFactory.instance.objectNode().put("id", "1"));
        record.setNewImage(JsonNodeFactory.instance.objectNode().put("value", "new"));
        return record;
    }
}
