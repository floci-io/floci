package io.github.hectorvent.floci.services.athena;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class GlueViewDdlBuilderTest {

    private GlueService glueService;
    private GlueViewDdlBuilder builder;

    @BeforeEach
    void setUp() {
        glueService = Mockito.mock(GlueService.class);
        builder = new GlueViewDdlBuilder(glueService);
    }

    private Database createDatabase(String name) {
        Database db = new Database();
        db.setName(name);
        return db;
    }

    private Table createTable(String name, String location, String serde, String inputFormat) {
        Table table = new Table();
        table.setName(name);
        if (location != null || serde != null || inputFormat != null) {
            StorageDescriptor sd = new StorageDescriptor();
            sd.setLocation(location);
            sd.setInputFormat(inputFormat);
            if (serde != null) {
                StorageDescriptor.SerDeInfo serdeInfo = new StorageDescriptor.SerDeInfo();
                serdeInfo.setSerializationLibrary(serde);
                sd.setSerdeInfo(serdeInfo);
            }
            table.setStorageDescriptor(sd);
        }
        return table;
    }

    private static Column column(String name) {
        Column c = new Column();
        c.setName(name);
        c.setType("string");
        return c;
    }

    private Table tableWithColumns(String name, String location, List<Column> columns, List<Column> partitionKeys) {
        Table table = createTable(name, location, "org.openx.data.jsonserde.JsonSerDe", null);
        table.getStorageDescriptor().setColumns(columns);
        table.setPartitionKeys(partitionKeys);
        return table;
    }

    private String ddlFor(Table table) {
        Database db = createDatabase("audit");
        when(glueService.getDatabases()).thenReturn(List.of(db));
        when(glueService.getTables("audit")).thenReturn(List.of(table));
        return builder.build(null);
    }

    /**
     * Athena reports the column names the catalog declares. The files here spell them camelCase, so a
     * view built by inference alone would expose the file's spelling and a client reading the declared
     * name would find nothing.
     */
    @Test
    void testDeclaredColumnsAreProjectedInsteadOfStar() {
        String ddl = ddlFor(tableWithColumns("audit_events", "s3://audit/",
                List.of(column("eventid"), column("tenantid")),
                List.of(column("tenant"), column("year"))));

        assertTrue(ddl.contains("\"eventid\" AS \"eventid\""), ddl);
        assertTrue(ddl.contains("\"tenantid\" AS \"tenantid\""), ddl);
        assertFalse(ddl.contains("SELECT * FROM"), ddl);
    }

    /** Partition keys are part of the schema and are what partition predicates filter on. */
    @Test
    void testPartitionKeysAreProjectedAlongsideDataColumns() {
        String ddl = ddlFor(tableWithColumns("audit_events", "s3://audit/",
                List.of(column("eventid")),
                List.of(column("tenant"), column("year"), column("month"), column("day"))));

        for (String name : List.of("eventid", "tenant", "year", "month", "day")) {
            assertTrue(ddl.contains("\"" + name + "\" AS \"" + name + "\""), name + " missing from: " + ddl);
        }
    }

    /** A partition key repeating a data column would bind twice and make the view ambiguous. */
    @Test
    void testDuplicateColumnAndPartitionKeyIsProjectedOnce() {
        String ddl = ddlFor(tableWithColumns("t", "s3://b/",
                List.of(column("tenant")), List.of(column("TENANT"))));

        int first = ddl.indexOf("AS \"tenant\"");
        assertTrue(first >= 0, ddl);
        assertEquals(-1, ddl.indexOf("AS \"tenant\"", first + 1), "projected twice: " + ddl);
    }

    /** A table that declares no columns leaves inference in charge, as before. */
    @Test
    void testTableWithoutDeclaredColumnsStillUsesStar() {
        String ddl = ddlFor(tableWithColumns("t", "s3://b/", null, null));

        assertTrue(ddl.contains("SELECT * FROM"), ddl);
    }

    /** Column identifiers go through the same quoting as table and schema names. */
    @Test
    void testColumnIdentifierQuotingEscapesDoubleQuotes() {
        String ddl = ddlFor(tableWithColumns("t", "s3://b/", List.of(column("we\"ird")), null));

        assertTrue(ddl.contains("\"we\"\"ird\" AS \"we\"\"ird\""), ddl);
    }

    @Test
    void testTwoDatabasesQualifiedViewsAndSchemaPerDb() {
        Database shopDb = createDatabase("shop");
        Database analyticsDb = createDatabase("analytics");
        when(glueService.getDatabases()).thenReturn(List.of(shopDb, analyticsDb));

        Table ordersTable = createTable("orders", "s3://analytics-bucket/orders/", "org.openx.data.jsonserde.JsonSerDe", null);
        Table eventsTable = createTable("events", "s3://analytics-bucket/events/", "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe", null);
        when(glueService.getTables("shop")).thenReturn(List.of(ordersTable));
        when(glueService.getTables("analytics")).thenReturn(List.of(eventsTable));

        String ddl = builder.build(null);

        assertTrue(ddl.contains("CREATE SCHEMA IF NOT EXISTS \"shop\";\n"));
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"shop\".\"orders\" AS SELECT * FROM read_json_auto('s3://analytics-bucket/orders/**');\n"));
        assertTrue(ddl.contains("CREATE SCHEMA IF NOT EXISTS \"analytics\";\n"));
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"analytics\".\"events\" AS SELECT * FROM read_parquet('s3://analytics-bucket/events/**', union_by_name = true);\n"));
        assertFalse(ddl.contains("CREATE OR REPLACE VIEW \"orders\" AS"));
        assertFalse(ddl.contains("CREATE OR REPLACE VIEW \"events\" AS"));
    }

    @Test
    void testContextDatabaseAddsUnqualifiedAlias() {
        Database shopDb = createDatabase("shop");
        when(glueService.getDatabases()).thenReturn(List.of(shopDb));

        Table ordersTable = createTable("orders", "s3://analytics-bucket/orders", "org.openx.data.jsonserde.JsonSerDe", null);
        when(glueService.getTables("shop")).thenReturn(List.of(ordersTable));

        String ddlWithContext = builder.build("shop");
        assertTrue(ddlWithContext.contains("CREATE OR REPLACE VIEW \"shop\".\"orders\" AS SELECT * FROM read_json_auto('s3://analytics-bucket/orders/**');\n"));
        assertTrue(ddlWithContext.contains("CREATE OR REPLACE VIEW \"orders\" AS SELECT * FROM read_json_auto('s3://analytics-bucket/orders/**');\n"));

        String ddlNull = builder.build(null);
        assertTrue(ddlNull.contains("CREATE OR REPLACE VIEW \"shop\".\"orders\" AS SELECT * FROM read_json_auto('s3://analytics-bucket/orders/**');\n"));
        assertFalse(ddlNull.contains("CREATE OR REPLACE VIEW \"orders\" AS"));

        String ddlEmpty = builder.build("");
        assertTrue(ddlEmpty.contains("CREATE OR REPLACE VIEW \"shop\".\"orders\" AS SELECT * FROM read_json_auto('s3://analytics-bucket/orders/**');\n"));
        assertFalse(ddlEmpty.contains("CREATE OR REPLACE VIEW \"orders\" AS"));
    }

    @Test
    void testContextDatabaseNotInGlueDoesNotThrow() {
        Database shopDb = createDatabase("shop");
        when(glueService.getDatabases()).thenReturn(List.of(shopDb));

        Table ordersTable = createTable("orders", "s3://analytics-bucket/orders", "org.openx.data.jsonserde.JsonSerDe", null);
        when(glueService.getTables("shop")).thenReturn(List.of(ordersTable));
        when(glueService.getTables("ghost")).thenThrow(new AwsException("EntityNotFoundException", "Database ghost not found", 400));

        String ddl = builder.build("ghost");
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"shop\".\"orders\" AS SELECT * FROM read_json_auto('s3://analytics-bucket/orders/**');\n"));
        assertFalse(ddl.contains("CREATE OR REPLACE VIEW \"ghost\""));
        assertFalse(ddl.contains("CREATE OR REPLACE VIEW \"orders\" AS"));
    }

    @Test
    void testTableWithNullOrBlankLocationIsSkipped() {
        Database shopDb = createDatabase("shop");
        when(glueService.getDatabases()).thenReturn(List.of(shopDb));

        Table noSd = new Table();
        noSd.setName("no_sd");

        Table nullLoc = createTable("null_loc", null, null, null);
        Table blankLoc = createTable("blank_loc", "   ", null, null);
        Table valid = createTable("valid", "s3://bucket/valid", null, null);

        when(glueService.getTables("shop")).thenReturn(List.of(noSd, nullLoc, blankLoc, valid));

        String ddl = builder.build(null);
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"shop\".\"valid\" AS SELECT * FROM read_csv_auto('s3://bucket/valid/**');\n"));
        assertFalse(ddl.contains("no_sd"));
        assertFalse(ddl.contains("null_loc"));
        assertFalse(ddl.contains("blank_loc"));
    }

    @Test
    void testReadFunctionInferencePreserved() {
        Database db = createDatabase("db");
        when(glueService.getDatabases()).thenReturn(List.of(db));

        Table parquetTable = createTable("parquet_tbl", "s3://bucket/parquet", "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe", null);
        Table jsonTable = createTable("json_tbl", "s3://bucket/json", "org.openx.data.jsonserde.JsonSerDe", "org.apache.hadoop.mapred.TextInputFormat");
        Table hiveTable = createTable("hive_tbl", "s3://bucket/hive", null, "org.apache.hadoop.hive.ql.io.HiveIgnoreKeyTextOutputFormat");
        Table csvTable = createTable("csv_tbl", "s3://bucket/csv", null, null);

        when(glueService.getTables("db")).thenReturn(List.of(parquetTable, jsonTable, hiveTable, csvTable));

        String ddl = builder.build(null);
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"db\".\"parquet_tbl\" AS SELECT * FROM read_parquet('s3://bucket/parquet/**', union_by_name = true);\n"));
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"db\".\"json_tbl\" AS SELECT * FROM read_json_auto('s3://bucket/json/**');\n"));
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"db\".\"hive_tbl\" AS SELECT * FROM read_json_auto('s3://bucket/hive/**');\n"));
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"db\".\"csv_tbl\" AS SELECT * FROM read_csv_auto('s3://bucket/csv/**');\n"));
    }

    @Test
    void testIdentifierQuotingEscapesDoubleQuotes() {
        Database db = createDatabase("my\"db");
        when(glueService.getDatabases()).thenReturn(List.of(db));

        Table tbl = createTable("my\"table", "s3://bucket/quoted", null, null);
        when(glueService.getTables("my\"db")).thenReturn(List.of(tbl));

        String ddl = builder.build("my\"db");
        assertTrue(ddl.contains("CREATE SCHEMA IF NOT EXISTS \"my\"\"db\";\n"));
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"my\"\"db\".\"my\"\"table\" AS SELECT * FROM read_csv_auto('s3://bucket/quoted/**');\n"));
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"my\"\"table\" AS SELECT * FROM read_csv_auto('s3://bucket/quoted/**');\n"));
    }

    @Test
    void testNoDatabasesReturnsEmpty() {
        when(glueService.getDatabases()).thenReturn(List.of());
        when(glueService.getTables("x")).thenReturn(List.of());

        String ddl = builder.build("x");
        assertEquals("", ddl);
    }

    @Test
    void testNullOrBlankDatabaseNameIsSkipped() {
        Database nullNameDb = createDatabase(null);
        Database blankNameDb = createDatabase("   ");
        Database validDb = createDatabase("valid_db");
        when(glueService.getDatabases()).thenReturn(java.util.Arrays.asList(null, nullNameDb, blankNameDb, validDb));
        when(glueService.getTables("valid_db")).thenReturn(List.of());

        String ddl = builder.build(null);
        assertTrue(ddl.contains("CREATE SCHEMA IF NOT EXISTS \"valid_db\";\n"));
        assertFalse(ddl.contains("CREATE SCHEMA IF NOT EXISTS \"\";"));
        assertFalse(ddl.contains("CREATE SCHEMA IF NOT EXISTS \"null\";"));
    }

    @Test
    void testGetDatabasesExceptionPropagates() {
        when(glueService.getDatabases()).thenThrow(new RuntimeException("Glue failure"));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> builder.build(null));
    }

    @Test
    void testTableWithNullOrBlankNameIsSkipped() {
        Database shopDb = createDatabase("shop");
        when(glueService.getDatabases()).thenReturn(List.of(shopDb));

        Table nullNameTable = createTable(null, "s3://bucket/null_name", null, null);
        Table emptyNameTable = createTable("", "s3://bucket/empty_name", null, null);
        Table blankNameTable = createTable("   ", "s3://bucket/blank_name", null, null);
        Table validTable = createTable("valid_tbl", "s3://bucket/valid", null, null);

        when(glueService.getTables("shop")).thenReturn(List.of(nullNameTable, emptyNameTable, blankNameTable, validTable));

        String ddl = builder.build("shop");
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"shop\".\"valid_tbl\" AS SELECT * FROM read_csv_auto('s3://bucket/valid/**');\n"));
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"valid_tbl\" AS SELECT * FROM read_csv_auto('s3://bucket/valid/**');\n"));
        assertFalse(ddl.contains("null_name"));
        assertFalse(ddl.contains("empty_name"));
        assertFalse(ddl.contains("blank_name"));
        assertFalse(ddl.contains("CREATE OR REPLACE VIEW \"shop\".\"\""));
        assertFalse(ddl.contains("CREATE OR REPLACE VIEW \"\""));
    }

    @Test
    void testSingleQuotesInLocationAreEscaped() {
        Database db = createDatabase("db");
        when(glueService.getDatabases()).thenReturn(List.of(db));

        Table singleQuoteTable = createTable("user_events", "s3://bucket/it's-a-dir/data", null, null);
        Table parquetSingleQuote = createTable("parquet_events", "s3://bucket/user's-store/parquet", "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe", null);
        when(glueService.getTables("db")).thenReturn(List.of(singleQuoteTable, parquetSingleQuote));

        String ddl = builder.build(null);
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"db\".\"user_events\" AS SELECT * FROM read_csv_auto('s3://bucket/it''s-a-dir/data/**');\n"));
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"db\".\"parquet_events\" AS SELECT * FROM read_parquet('s3://bucket/user''s-store/parquet/**', union_by_name = true);\n"));
    }

    @Test
    void testContextDatabaseTablesFetchedOnlyOnce() {
        Database db = createDatabase("shop");
        when(glueService.getDatabases()).thenReturn(List.of(db));
        Table table = createTable("orders", "s3://bucket/data", null, null);
        when(glueService.getTables("shop")).thenReturn(List.of(table));

        String ddl = builder.build("shop");
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"shop\".\"orders\""));
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"orders\""));
        Mockito.verify(glueService, Mockito.times(1)).getTables("shop");
    }
}

