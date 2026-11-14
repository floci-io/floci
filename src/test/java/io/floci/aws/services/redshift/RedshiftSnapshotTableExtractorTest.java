package io.floci.aws.services.redshift;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedshiftSnapshotTableExtractorTest {

    private static final String DUMP = String.join("\n",
            "SET search_path = '';",
            "CREATE TABLE public.orders (",
            "    id integer NOT NULL,",
            "    note character varying(20)",
            ");",
            "COPY public.orders (id, note) FROM stdin;",
            "1\tCREATE TABLE public.sneaky (",
            "2\thello",
            "\\.",
            "CREATE TABLE public.empty_table (",
            "    id integer",
            ");",
            "CREATE TABLE sales.\"Mixed Case\" (",
            "    id integer",
            ");",
            "COPY sales.\"Mixed Case\" (id) FROM stdin;",
            "7",
            "\\.",
            "");

    private Path dump;

    @BeforeEach
    void writeDump(@TempDir Path directory) throws IOException {
        dump = Files.writeString(directory.resolve("snapshot.sql"), DUMP);
    }

    private Optional<String> script(String schema, String table, String targetSchema, String newTable,
                                    boolean caseSensitive) throws IOException {
        return RedshiftSnapshotTableExtractor.restoreScript(dump, schema, table, targetSchema, newTable,
                caseSensitive);
    }

    @Test
    void renamesTheTableAndKeepsItsRowsInOneTransaction() throws IOException {
        String script = script("public", "orders", "archive", "orders_copy", false).orElseThrow();

        assertEquals(String.join("\n",
                "BEGIN;",
                "CREATE TABLE \"archive\".\"orders_copy\" (",
                "    id integer NOT NULL,",
                "    note character varying(20)",
                ");",
                "COPY \"archive\".\"orders_copy\" (id, note) FROM stdin;",
                "1\tCREATE TABLE public.sneaky (",
                "2\thello",
                "\\.",
                "COMMIT;",
                ""), script);
    }

    @Test
    void aTableWithoutRowsHasNoCopyBlock() throws IOException {
        String script = script("public", "empty_table", "public", "copy", false).orElseThrow();

        assertTrue(script.contains("CREATE TABLE \"public\".\"copy\" ("), script);
        assertFalse(script.contains("COPY"), script);
    }

    @Test
    void anUnknownTableOrOneInsideRowDataIsNotFound() throws IOException {
        assertTrue(script("public", "missing", "public", "x", false).isEmpty());
        assertTrue(script("public", "sneaky", "public", "x", false).isEmpty());
        assertTrue(script("other", "orders", "public", "x", false).isEmpty());
    }

    @Test
    void namesMatchIgnoringCaseUnlessTheRequestIsCaseSensitive() throws IOException {
        assertTrue(script("SALES", "mixed case", "public", "x", false).isPresent());
        assertTrue(script("sales", "mixed case", "public", "x", true).isEmpty());
        assertTrue(script("sales", "Mixed Case", "public", "x", true).isPresent());
    }

    @Test
    void anUnloggedTableKeepsItsQualifierAndItsRows() throws IOException {
        Files.writeString(dump, String.join("\n",
                "CREATE UNLOGGED TABLE public.scratch (",
                "    id integer",
                ");",
                "COPY public.scratch (id) FROM stdin;",
                "5",
                "\\.",
                ""));

        String script = script("public", "scratch", "public", "scratch_copy", false).orElseThrow();

        assertTrue(script.contains("CREATE UNLOGGED TABLE \"public\".\"scratch_copy\" ("), script);
        assertTrue(script.contains("COPY \"public\".\"scratch_copy\" (id) FROM stdin;"), script);
    }

    @Test
    void aPartitionedTableIsRefusedBecauseItsRowsLiveInItsPartitions() throws IOException {
        Files.writeString(dump, String.join("\n",
                "CREATE TABLE public.events (",
                "    id integer",
                ")",
                "PARTITION BY RANGE (id);",
                "CREATE TABLE public.events_1 (",
                "    id integer",
                ");",
                ""));

        assertThrows(RedshiftSnapshotTableExtractor.UnsupportedTableException.class,
                () -> script("public", "events", "public", "copy", false));
        assertTrue(script("public", "events_1", "public", "copy", false).isPresent());
    }

    @Test
    void aTargetNameWithAQuoteStaysOneIdentifier() throws IOException {
        String script = script("public", "orders", "public", "a\"; DROP TABLE x; --", false).orElseThrow();

        assertTrue(script.contains("CREATE TABLE \"public\".\"a\"\"; DROP TABLE x; --\" ("), script);
    }
}
