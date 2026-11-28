package io.floci.aws.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.RedshiftZeroEtlEndpoints;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.ColumnValue;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.ReplicatedTable;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.RowChange;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.TargetUnavailableException;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The statements and transaction execution that apply an RDS zero-ETL integration's replicated row changes. */
class RedshiftZeroEtlWriterRowChangeTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String TARGET_ARN = "arn:aws:redshift-serverless:us-east-1:000000000000:namespace/ns-1";
    private static final ReplicatedTable LINES = new ReplicatedTable("sales", "order lines",
            List.of(new ReplicatedTable.Column("id", "BIGINT"), new ReplicatedTable.Column("line", "INTEGER"),
                    new ReplicatedTable.Column("note", "VARCHAR(65535)")),
            List.of("id", "line"));

    private static ColumnValue value(String column) {
        return new ColumnValue(column, "CAST(? AS x)", "v");
    }

    private static ColumnValue value(String column, String text) {
        return new ColumnValue(column, "CAST(? AS x)", text);
    }

    private static RedshiftZeroEtlWriter writerWith(Connection connection) throws SQLException {
        RedshiftZeroEtlEndpoints endpoints = mock(RedshiftZeroEtlEndpoints.class);
        when(endpoints.targetWorkgroup(ACCOUNT_ID, TARGET_ARN)).thenReturn(
                new RedshiftServerlessService.WorkgroupTarget(TARGET_ARN, "wg-1", "localhost", 5439, "dev",
                        "admin", "secret", "admin"));
        RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        when(factory.open(any())).thenReturn(connection);
        return new RedshiftZeroEtlWriter(mock(RedshiftService.class), endpoints, factory, new ObjectMapper());
    }

    @Test
    void anUpsertReplacesTheNonKeyColumnsOnAKeyConflict() {
        RowChange change = new RowChange(RowChange.Kind.UPSERT, LINES,
                List.of(value("id"), value("line"), value("note")), List.of());

        assertEquals("INSERT INTO \"sales\".\"order lines\" (\"id\", \"line\", \"note\") "
                        + "VALUES (CAST(? AS x), CAST(? AS x), CAST(? AS x)) ON CONFLICT (\"id\", \"line\") "
                        + "DO UPDATE SET \"note\" = EXCLUDED.\"note\"",
                RedshiftZeroEtlWriter.rowChangeSql(change));
    }

    @Test
    void anUpsertOfOnlyKeyColumnsDoesNothingOnAKeyConflict() {
        RowChange change = new RowChange(RowChange.Kind.UPSERT, LINES, List.of(value("id"), value("line")),
                List.of());

        assertEquals("INSERT INTO \"sales\".\"order lines\" (\"id\", \"line\") VALUES (CAST(? AS x), CAST(? AS x)) "
                + "ON CONFLICT (\"id\", \"line\") DO NOTHING", RedshiftZeroEtlWriter.rowChangeSql(change));
    }

    @Test
    void anUpdateSetsItsValuesWhereTheKeyMatches() {
        RowChange change = new RowChange(RowChange.Kind.UPDATE, LINES, List.of(value("note")),
                List.of(value("id"), value("line")));

        assertEquals("UPDATE \"sales\".\"order lines\" SET \"note\" = CAST(? AS x) "
                        + "WHERE \"id\" = CAST(? AS x) AND \"line\" = CAST(? AS x)",
                RedshiftZeroEtlWriter.rowChangeSql(change));
    }

    @Test
    void anUpdateWithNothingToSetIsLeftOut() {
        RowChange change = new RowChange(RowChange.Kind.UPDATE, LINES, List.of(), List.of(value("id")));

        assertNull(RedshiftZeroEtlWriter.rowChangeSql(change));
    }

    @Test
    void aDeleteRemovesTheRowUnderItsKey() {
        RowChange change = new RowChange(RowChange.Kind.DELETE, LINES, List.of(),
                List.of(value("id"), value("line")));

        assertEquals("DELETE FROM \"sales\".\"order lines\" WHERE \"id\" = CAST(? AS x) AND \"line\" = CAST(? AS x)",
                RedshiftZeroEtlWriter.rowChangeSql(change));
    }

    @Test
    void aDeleteWithoutAKeyIsRefused() {
        RowChange change = new RowChange(RowChange.Kind.DELETE, LINES, List.of(), List.of());

        assertThrows(IllegalArgumentException.class, () -> RedshiftZeroEtlWriter.rowChangeSql(change));
    }

    @Test
    void aTruncateEmptiesTheTable() {
        RowChange change = new RowChange(RowChange.Kind.TRUNCATE, LINES, List.of(), List.of());

        assertEquals("TRUNCATE \"sales\".\"order lines\"", RedshiftZeroEtlWriter.rowChangeSql(change));
    }

    @Test
    void applyRowChangesBindsUpdateValuesBeforeKeysAndCommits() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        RedshiftZeroEtlWriter writer = writerWith(connection);
        RowChange update = new RowChange(RowChange.Kind.UPDATE, LINES,
                List.of(value("note", "revised")),
                List.of(value("id", "42"), value("line", "7")));

        writer.applyRowChanges(ACCOUNT_ID, TARGET_ARN, "analytics", List.of(update));

        InOrder order = inOrder(connection, statement);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).prepareStatement(RedshiftZeroEtlWriter.rowChangeSql(update));
        order.verify(statement).setString(1, "revised");
        order.verify(statement).setString(2, "42");
        order.verify(statement).setString(3, "7");
        order.verify(statement).executeUpdate();
        order.verify(statement).close();
        order.verify(connection).commit();
        order.verify(connection).close();
        verify(connection, never()).rollback();
    }

    @Test
    void applyRowChangesBindsNullColumnValuesAsSqlNull() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        RedshiftZeroEtlWriter writer = writerWith(connection);
        RowChange upsert = new RowChange(RowChange.Kind.UPSERT, LINES,
                List.of(value("id", "10"), value("line", "1"), value("note", null)), List.of());

        writer.applyRowChanges(ACCOUNT_ID, TARGET_ARN, "analytics", List.of(upsert));

        verify(statement).setString(1, "10");
        verify(statement).setString(2, "1");
        verify(statement).setNull(3, Types.VARCHAR);
        verify(statement).executeUpdate();
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void applyRowChangesRollsBackWithoutCommittingWhenALaterChangeFails() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement first = mock(PreparedStatement.class);
        PreparedStatement second = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(first, second);
        when(second.executeUpdate()).thenThrow(new SQLException("duplicate key", "23505"));
        RedshiftZeroEtlWriter writer = writerWith(connection);
        RowChange upsert = new RowChange(RowChange.Kind.UPSERT, LINES,
                List.of(value("id", "1"), value("line", "1"), value("note", "ok")), List.of());
        RowChange delete = new RowChange(RowChange.Kind.DELETE, LINES, List.of(),
                List.of(value("id", "2"), value("line", "1")));

        assertThrows(AwsException.class,
                () -> writer.applyRowChanges(ACCOUNT_ID, TARGET_ARN, "analytics", List.of(upsert, delete)));

        InOrder order = inOrder(connection, first, second);
        order.verify(connection).setAutoCommit(false);
        order.verify(first).executeUpdate();
        order.verify(second).executeUpdate();
        order.verify(connection).rollback();
        order.verify(connection).close();
        verify(connection, never()).commit();
    }

    @Test
    void applyRowChangesTranslatesConnectionFailuresAfterRollback() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new SQLException("connection reset", "08006"));
        RedshiftZeroEtlWriter writer = writerWith(connection);
        RowChange truncate = new RowChange(RowChange.Kind.TRUNCATE, LINES, List.of(), List.of());

        assertThrows(TargetUnavailableException.class,
                () -> writer.applyRowChanges(ACCOUNT_ID, TARGET_ARN, "analytics", List.of(truncate)));

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    void applyRowChangesSkipsOpeningAConnectionWhenThereAreNoChanges() {
        RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        RedshiftZeroEtlWriter writer = new RedshiftZeroEtlWriter(
                mock(RedshiftService.class), mock(RedshiftZeroEtlEndpoints.class), factory, new ObjectMapper());

        writer.applyRowChanges(ACCOUNT_ID, TARGET_ARN, "analytics", List.of());

        verifyNoInteractions(factory);
    }

    @Test
    void applyRowChangesMovesOldRowWhenNewKeyDoesNotExistOnKeyChangingUpdate() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement inPlace = mock(PreparedStatement.class);
        PreparedStatement move = mock(PreparedStatement.class);
        when(inPlace.executeUpdate()).thenReturn(0);
        when(move.executeUpdate()).thenReturn(1);
        when(connection.prepareStatement(anyString())).thenReturn(inPlace, move);
        RedshiftZeroEtlWriter writer = writerWith(connection);
        RowChange update = new RowChange(RowChange.Kind.UPDATE, LINES,
                List.of(value("id", "2"), value("line", "1"), value("note", "moved")),
                List.of(value("id", "1"), value("line", "1")));

        writer.applyRowChanges(ACCOUNT_ID, TARGET_ARN, "analytics", List.of(update));

        InOrder order = inOrder(connection, inPlace, move);
        order.verify(connection).prepareStatement(
                "UPDATE \"sales\".\"order lines\" SET \"id\" = CAST(? AS x), \"line\" = CAST(? AS x),"
                        + " \"note\" = CAST(? AS x) WHERE \"id\" = CAST(? AS x) AND \"line\" = CAST(? AS x)");
        order.verify(inPlace).setString(1, "2");
        order.verify(inPlace).setString(2, "1");
        order.verify(inPlace).setString(3, "moved");
        order.verify(inPlace).setString(4, "2");
        order.verify(inPlace).setString(5, "1");
        order.verify(inPlace).executeUpdate();
        order.verify(connection).prepareStatement(RedshiftZeroEtlWriter.rowChangeSql(update));
        order.verify(move).executeUpdate();
        order.verify(connection).commit();
    }

    @Test
    void applyRowChangesUpdatesExistingNewKeyAndDropsOldKeyWhenKeyChangingUpdateReplays() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement inPlace = mock(PreparedStatement.class);
        PreparedStatement dropOld = mock(PreparedStatement.class);
        when(inPlace.executeUpdate()).thenReturn(1);
        when(connection.prepareStatement(anyString())).thenReturn(inPlace, dropOld);
        RedshiftZeroEtlWriter writer = writerWith(connection);
        RowChange update = new RowChange(RowChange.Kind.UPDATE, LINES,
                List.of(value("id", "2"), value("line", "1"), value("note", "moved")),
                List.of(value("id", "1"), value("line", "1")));

        writer.applyRowChanges(ACCOUNT_ID, TARGET_ARN, "analytics", List.of(update));

        InOrder order = inOrder(connection, inPlace, dropOld);
        order.verify(inPlace).executeUpdate();
        order.verify(connection).prepareStatement(
                "DELETE FROM \"sales\".\"order lines\" WHERE \"id\" = CAST(? AS x) AND \"line\" = CAST(? AS x)");
        order.verify(dropOld).setString(1, "1");
        order.verify(dropOld).setString(2, "1");
        order.verify(dropOld).executeUpdate();
        order.verify(connection).commit();
    }

    @Test
    void applyRowChangesStreamSkipsOpeningAConnectionWhenProducerEmitsNothing() {
        RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        RedshiftZeroEtlWriter writer = new RedshiftZeroEtlWriter(
                mock(RedshiftService.class), mock(RedshiftZeroEtlEndpoints.class), factory, new ObjectMapper());

        writer.applyRowChanges(ACCOUNT_ID, TARGET_ARN, "analytics", sink -> { });

        verifyNoInteractions(factory);
    }

    @Test
    void applyRowChangesStreamRollsBackWhenProducerFailsMidBatch() throws SQLException {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        RedshiftZeroEtlWriter writer = writerWith(connection);
        RowChange truncate = new RowChange(RowChange.Kind.TRUNCATE, LINES, List.of(), List.of());

        assertThrows(IllegalStateException.class, () -> writer.applyRowChanges(ACCOUNT_ID, TARGET_ARN, "analytics",
                sink -> {
                    sink.accept(truncate);
                    throw new IllegalStateException("corrupt pgoutput");
                }));

        verify(statement).executeUpdate();
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }
}
