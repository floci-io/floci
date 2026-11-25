package io.floci.aws.services.redshiftdata;

import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.ColumnValue;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.ReplicatedTable;
import io.floci.aws.services.redshiftdata.RedshiftZeroEtlWriter.RowChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The statements that apply an RDS zero-ETL integration's replicated row changes to a target table. */
class RedshiftZeroEtlWriterRowChangeTest {

    private static final ReplicatedTable LINES = new ReplicatedTable("sales", "order lines",
            List.of(new ReplicatedTable.Column("id", "BIGINT"), new ReplicatedTable.Column("line", "INTEGER"),
                    new ReplicatedTable.Column("note", "VARCHAR(65535)")),
            List.of("id", "line"));

    private static ColumnValue value(String column) {
        return new ColumnValue(column, "CAST(? AS x)", "v");
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
}
