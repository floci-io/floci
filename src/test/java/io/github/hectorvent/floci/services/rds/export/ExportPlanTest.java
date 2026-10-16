package io.github.hectorvent.floci.services.rds.export;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure pieces of an export: which tables {@code ExportOnly} selects, the Parquet type each
 * PostgreSQL type is written as, and the object keys a task writes, which Data Prepper's
 * {@code ExportObjectKey} parses from the last five path segments.
 */
class ExportPlanTest {

    @Test
    void emptyExportOnlySelectsEverything() {
        ExportOnlySelection selection = ExportOnlySelection.of(List.of());

        assertTrue(selection.includesDatabase("appdb"));
        assertTrue(selection.includes("appdb", "public", "items"));
    }

    @Test
    void exportOnlySelectsByDatabaseSchemaOrTable() {
        ExportOnlySelection selection = ExportOnlySelection.of(
                List.of("whole", "appdb.audit", "appdb.public.items"));

        assertTrue(selection.includes("whole", "any", "table"));
        assertTrue(selection.includes("appdb", "audit", "events"));
        assertTrue(selection.includes("appdb", "public", "items"));
        assertFalse(selection.includes("appdb", "public", "orders"));
        assertFalse(selection.includesDatabase("other"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"appdb.public.items.extra", "app-db", "appdb..items", "appdb.public items"})
    void exportOnlyRefusesNamesAPartialExportCannotTake(String entry) {
        AwsException error = assertThrows(AwsException.class, () -> ExportOnlySelection.of(List.of(entry)));

        assertThat(error.getErrorCode(), equalTo("InvalidExportOnly"));
    }

    @ParameterizedTest
    @CsvSource({
            "int2, SMALLINT, \"c\"",
            "int4, INTEGER, \"c\"",
            "int8, BIGINT, \"c\"",
            "float4, FLOAT, \"c\"",
            "float8, DOUBLE, \"c\"",
            "bool, BOOLEAN, \"c\"",
            "numeric, VARCHAR, \"c\"::text",
            "timestamptz, VARCHAR, \"c\"::text",
            "jsonb, VARCHAR, \"c\"::text",
            "_uuid, VARCHAR, \"c\"::text",
            "_int4, VARCHAR, \"c\"::text",
            "vector, VARCHAR, \"c\"::text",
            "bytea, BLOB, 'encode(\"c\", ''hex'')'"
    })
    void columnsFollowTheRdsParquetMapping(String pgType, String parquetType, String extract) {
        ExportColumn column = new ExportColumn("c", pgType);

        assertThat(column.parquetType(), equalTo(parquetType));
        assertThat(column.extractExpression(), equalTo(extract));
    }

    @ParameterizedTest
    @CsvSource({"int2, INT32", "int4, INT32", "int8, INT64", "float4, FLOAT", "float8, DOUBLE",
            "bool, BOOLEAN", "bytea, BINARY", "numeric, STRING", "vector, STRING"})
    void exportedTypesAreNamedAsParquetTypes(String pgType, String parquetTypeName) {
        assertThat(new ExportColumn("c", pgType).parquetTypeName(), equalTo(parquetTypeName));
    }

    @Test
    void byteaIsStagedAsHexAndWrittenAsBinary() {
        ExportColumn column = new ExportColumn("payload", "bytea");

        assertThat(column.stagedType(), equalTo("VARCHAR"));
        assertThat(column.writeExpression(), equalTo("unhex(\"payload\") AS \"payload\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "a,b", "a;b", "a=b", "a(b)", "a{b}", "a\tb"})
    void columnNamesRdsSkipsAreFlagged(String name) {
        assertFalse(new ExportColumn(name, "text").hasExportableName());
    }

    @Test
    void identifiersAreQuotedWithEmbeddedQuotesDoubled() {
        assertThat(ExportColumn.quoteIdentifier("we\"ird"), equalTo("\"we\"\"ird\""));
    }

    @Test
    void dataFileKeysFollowTheRdsLayoutDataPrepperParses() {
        ExportLayout layout = new ExportLayout("items/rds", "fx-dp-snapshot-1a2b3c4d-export-5e6f7a8b");

        String key = layout.dataFileKey("appdb", "public", "items");

        assertThat(key, matchesPattern("items/rds/fx-dp-snapshot-1a2b3c4d-export-5e6f7a8b/appdb/public\\.items/1/"
                + "part-00000-[0-9a-f-]{36}\\.gz\\.parquet"));
        String[] parts = key.split("/");
        assertThat(parts[parts.length - 5], equalTo("fx-dp-snapshot-1a2b3c4d-export-5e6f7a8b"));
        assertThat(parts[parts.length - 4], equalTo("appdb"));
        assertThat(parts[parts.length - 3], equalTo("public.items"));
    }

    @Test
    void taskRootTrimsSlashesAndWorksWithoutAPrefix() {
        assertThat(new ExportLayout("/exports/", "task-1").taskRoot(), equalTo("exports/task-1/"));
        assertThat(new ExportLayout(null, "task-1").taskRoot(), equalTo("task-1/"));
        assertThat(new ExportLayout("", "task-1").exportInfoKey(), equalTo("task-1/export_info_task-1.json"));
        assertThat(new ExportLayout("", "task-1").tablesInfoKey(3),
                equalTo("task-1/export_tables_info_task-1_from_1_to_3.json"));
    }

    @Test
    void pathCharactersRdsReplacesBecomeUnderscores() {
        assertThat(ExportLayout.pathComponent("my table`\"\\x"), equalTo("my_table___x"));
    }
}
