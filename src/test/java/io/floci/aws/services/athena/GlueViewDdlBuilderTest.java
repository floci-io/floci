package io.floci.aws.services.athena;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.GlueTableResolver;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.floci.aws.services.athena.GluePartitions.partition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlueViewDdlBuilderTest {

    private GlueService glueService;
    private S3Service s3;
    private GlueViewDdlBuilder builder;

    @BeforeEach
    void setUp() {
        glueService = Mockito.mock(GlueService.class);
        s3 = Mockito.mock(S3Service.class);
        // Every location holds a file each read glob here reaches, unless a test says otherwise.
        when(s3.listObjects(anyString(), anyString(), isNull(), anyInt()))
                .thenAnswer(call -> listing(call.getArgument(0), call.getArgument(1) + "region=a/dt=b/c/d/part-0"));
        builder = new GlueViewDdlBuilder(glueService, s3);
    }

    private static List<S3Object> listing(String bucket, String... keys) {
        return Arrays.stream(keys).map(key -> new S3Object(bucket, key, key.endsWith("/") ? 0 : 1, null, null))
                .toList();
    }

    private void holds(String bucket, String prefix, String... keys) {
        when(s3.listObjects(eq(bucket), eq(prefix), isNull(), anyInt())).thenReturn(listing(bucket, keys));
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
        holds("audit", "", "tenant=acme/year=2026/part-0");
        when(glueService.getPartitions("audit", "audit_events")).thenReturn(List.of(partition(null, "acme", "2026")));
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
        holds("audit", "", "tenant=acme/year=2026/month=11/day=16/part-0");
        when(glueService.getPartitions("audit", "audit_events"))
                .thenReturn(List.of(partition(null, "acme", "2026", "11", "16")));
        String ddl = ddlFor(tableWithColumns("audit_events", "s3://audit/",
                List.of(column("eventid")),
                List.of(column("tenant"), column("year"), column("month"), column("day"))));

        assertTrue(ddl.contains("AS SELECT r.\"eventid\" AS \"eventid\", p.\"tenant\" AS \"tenant\", p.\"year\" AS \"year\", "
                + "p.\"month\" AS \"month\", p.\"day\" AS \"day\" FROM read_json_auto(['s3://audit/tenant=acme/year=2026/month=11/day=16/**'], "
                + "union_by_name = true, filename = '$path', hive_partitioning = false) AS r JOIN (VALUES "
                + "('s3://audit/tenant=acme/year=2026/month=11/day=16/', 'acme', '2026', '11', '16'))"), ddl);
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

    private static Column column(String name, String type) {
        Column c = column(name);
        c.setType(type);
        return c;
    }

    private Table projectingParquetTable(String location, Map<String, String> parameters) {
        Table table = createTable("events", location, "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe",
                "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat");
        table.getStorageDescriptor().setColumns(List.of(column("id")));
        table.setPartitionKeys(List.of(column("region", "string"), column("dt", "date")));
        table.setParameters(parameters);
        return table;
    }

    /**
     * A template of bare values, one folder per key. The files hold no partition columns, so each
     * value comes from the object's path, through the template, as Athena maps it; without this the
     * view selects columns nothing defines and DuckDB rejects it.
     */
    @Test
    void templatedPartitionKeysAreTakenFromTheObjectPath() {
        String ddl = ddlFor(projectingParquetTable("s3://b/data/", Map.of("projection.enabled", "true",
                "storage.location.template", "s3://b/data/${region}/${dt}/")));

        String regex = "'^s3://b/data/([^/]+)/([^/]+)/'";
        assertEquals("CREATE SCHEMA IF NOT EXISTS \"audit\";\n"
                + "CREATE OR REPLACE VIEW \"audit\".\"events\" AS SELECT \"id\" AS \"id\", "
                + "regexp_extract(\"$path\", " + regex + ", 1) AS \"region\", "
                + "TRY_CAST(regexp_extract(\"$path\", " + regex + ", 2) AS DATE) AS \"dt\" "
                + "FROM read_parquet('s3://b/data/*/*/**', union_by_name = true, hive_partitioning = false, "
                + "filename = '$path') "
                + "WHERE regexp_matches(\"$path\", " + regex + ") "
                + "AND TRY_CAST(regexp_extract(\"$path\", " + regex + ", 2) AS DATE) IS NOT NULL;\n", ddl);
    }

    /** RE2 has no backreferences, so a repeated placeholder's occurrences are compared in the view. */
    @Test
    void repeatedPlaceholderMustHoldTheSameValueAtEachOccurrence() {
        String ddl = ddlFor(projectingParquetTable("s3://b/repeat/", Map.of("projection.enabled", "true",
                "storage.location.template", "s3://b/repeat/${region}/${dt}/${region}/")));

        String regex = "'^s3://b/repeat/([^/]+)/([^/]+)/([^/]+)/'";
        assertTrue(ddl.contains("regexp_extract(\"$path\", " + regex + ", 1) = "
                + "regexp_extract(\"$path\", " + regex + ", 3)"), ddl);
    }

    /**
     * A template omitting a partition key leaves that key with nothing to bind to, and one view
     * failing to bind fails the whole batch. Athena fails only queries on the table, so the table
     * gets no view and its neighbours are still built.
     */
    @Test
    void tableWhoseTemplateOmitsAPartitionKeyGetsNoViewAndLeavesOthersBuilt() {
        Table incomplete = projectingParquetTable("s3://b/missing/", Map.of("projection.enabled", "true",
                "storage.location.template", "s3://b/missing/${region}/"));
        Table healthy = tableWithColumns("healthy", "s3://b/healthy/", List.of(column("id")), null);
        Database db = createDatabase("audit");
        when(glueService.getDatabases()).thenReturn(List.of(db));
        when(glueService.getTables("audit")).thenReturn(List.of(incomplete, healthy));

        String ddl = builder.build(null);

        assertFalse(ddl.contains("\"events\""), ddl);
        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"audit\".\"healthy\""), ddl);
    }

    /** An Iceberg table reads through its metadata, so a template omitting a key costs it no view. */
    @Test
    void icebergTableWithAnIncompleteTemplateKeepsItsView() {
        Table table = icebergTable("orders", "s3://bucket/warehouse/orders",
                "s3://bucket/warehouse/orders/metadata/00001-abc.metadata.json");
        table.setPartitionKeys(List.of(column("region", "string"), column("dt", "date")));
        Map<String, String> parameters = new HashMap<>(table.getParameters());
        parameters.put("projection.enabled", "true");
        parameters.put("storage.location.template", "s3://bucket/warehouse/orders/${region}/");
        table.setParameters(parameters);

        String ddl = ddlFor(table);

        assertTrue(ddl.contains("CREATE OR REPLACE VIEW \"audit\".\"orders\" AS SELECT \"region\" AS \"region\", "
                + "\"dt\" AS \"dt\" FROM iceberg_scan('s3://bucket/warehouse/orders/metadata/00001-abc.metadata.json');\n"),
                ddl);
    }

    /** The default column=value layout is left to DuckDB's Hive partitioning, as before. */
    @Test
    void defaultLayoutStillReadsPartitionValuesThroughHivePartitioning() {
        String ddl = ddlFor(projectingParquetTable("s3://b/hive/", Map.of("projection.enabled", "true")));

        assertEquals("CREATE SCHEMA IF NOT EXISTS \"audit\";\n"
                + "CREATE OR REPLACE VIEW \"audit\".\"events\" AS SELECT \"id\" AS \"id\", "
                + "\"region\" AS \"region\", \"dt\" AS \"dt\" "
                + "FROM read_parquet('s3://b/hive/region=*/dt=*/**', union_by_name = true);\n", ddl);
    }

    /**
     * Athena answers a table whose location holds no data with no rows, where reading the empty location fails to
     * bind: such a table gets a view of no rows with its declared columns as their types. Folder markers hold no
     * data, and a projected table's location counts only the objects its read glob reaches.
     */
    @Test
    void aTableWhoseLocationHoldsNoDataGetsATypedViewOfNoRows() {
        holds("lake", "events/");
        String empty = "CREATE OR REPLACE VIEW \"audit\".\"events\" AS SELECT CAST(NULL AS BIGINT) AS \"id\", "
                + "CAST(NULL AS VARCHAR) AS \"name\" LIMIT 0;\n";
        assertTrue(ddlFor(unpartitioned(headerlessTsvTable("events", "s3://lake/events/"))).contains(empty));

        holds("lake", "events/", "events/", "events/dt=2026-11-16/");
        assertTrue(ddlFor(unpartitioned(headerlessTsvTable("events", "s3://lake/events"))).contains(empty));

        holds("b", "hive/", "hive/_SUCCESS", "hive/other/part-0");
        assertTrue(ddlFor(projectingParquetTable("s3://b/hive/", Map.of("projection.enabled", "true")))
                .contains("SELECT CAST(NULL AS VARCHAR) AS \"id\", CAST(NULL AS VARCHAR) AS \"region\", "
                        + "CAST(NULL AS DATE) AS \"dt\" LIMIT 0;\n"));
        holds("b", "hive/", "hive/region=eu/dt=2026-11-16/part-0");
        assertTrue(ddlFor(projectingParquetTable("s3://b/hive/", Map.of("projection.enabled", "true")))
                .contains("FROM read_parquet('s3://b/hive/region=*/dt=*/**'"));
    }

    /**
     * A partitioned table is read through its registered partitions: each holding data from its own location, its
     * default folder or one outside the table's, with its values as the keys (Hive's default partition as NULL); none
     * registered or none holding data answers no rows.
     */
    @Test
    void aPartitionedTableIsReadThroughItsRegisteredPartitions() {
        Table table = headerlessTsvTable("events", "s3://lake/events/");
        assertTrue(ddlFor(table).contains("\"events\" AS SELECT CAST(NULL AS BIGINT) AS \"id\", "
                + "CAST(NULL AS VARCHAR) AS \"name\", CAST(NULL AS VARCHAR) AS \"dt\" LIMIT 0;\n"));

        holds("lake", "events/", "events/dt=2026-11-14/part-0", "events/dt=2026-11-17/part-0");
        when(glueService.getPartitions("audit", "events")).thenReturn(List.of(partition(null, "2026-11-14"),
                partition("s3://archive/2026/11/16", "__HIVE_DEFAULT_PARTITION__"), partition(null, "2026-11-15"),
                partition(null)));
        String ddl = ddlFor(table);
        assertTrue(ddl.contains("AS SELECT TRY_CAST(r.\"id\" AS BIGINT) AS \"id\", r.\"name\" AS \"name\", p.\"dt\" AS \"dt\" "
                + "FROM read_csv(['s3://lake/events/dt=2026-11-14/**', 's3://archive/2026/11/16/**'], header = false"), ddl);
        assertTrue(ddl.contains(", filename = '$path', hive_partitioning = false) AS r JOIN (VALUES "
                + "('s3://lake/events/dt=2026-11-14/', '2026-11-14'), ('s3://archive/2026/11/16/', CAST(NULL AS VARCHAR))) "
                + "AS p(\"$location\", \"dt\") ON starts_with(r.\"$path\", p.\"$location\");\n"), ddl);
        assertFalse(ddl.contains("2026-11-15") || ddl.contains("2026-11-17"), ddl);
        // One listing of the table's location answers for every partition under it.
        verify(s3, times(1)).listObjects(eq("lake"), eq("events/"), isNull(), anyInt());
    }

    /**
     * A Parquet table's partitions are read together, columns matched by name, so a partition written before a column
     * was added reads NULL for it; a value its key's type cannot hold fails the query with Athena's
     * HIVE_INVALID_PARTITION_VALUE rather than reading its rows under a NULL key.
     */
    @Test
    void parquetPartitionsAreReadByNameAndAnInvalidValueFailsAsAthenaDoes() {
        Table table = createTable("events", "s3://lake/parquet/", PARQUET_SERDE, null);
        table.getStorageDescriptor().setColumns(List.of(column("id", "bigint"), column("added", "string")));
        table.setPartitionKeys(List.of(column("year", "int")));
        holds("lake", "parquet/", "parquet/year=2026/part-0", "parquet/year=soon/part-0");
        when(glueService.getPartitions("audit", "events")).thenReturn(List.of(partition(null, "2026"),
                partition(null, "soon")));

        String ddl = ddlFor(table);
        assertTrue(ddl.contains("FROM read_parquet(['s3://lake/parquet/year=2026/**', 's3://lake/parquet/year=soon/**'], "
                + "union_by_name = true, filename = '$path', hive_partitioning = false) AS r"), ddl);
        assertTrue(ddl.contains("CASE WHEN TRY_CAST('soon' AS INTEGER) IS NULL THEN error('HIVE_INVALID_PARTITION_VALUE: "
                + "Invalid partition value ''soon'' for INTEGER partition key: year=soon') ELSE CAST('soon' AS INTEGER) END"),
                ddl);
    }

    /** A location whose bucket is gone is read as before, so the query reports it. */
    @Test
    void aTableInAMissingBucketIsReadAsBefore() {
        when(s3.listObjects(eq("gone"), anyString(), isNull(), anyInt()))
                .thenThrow(new AwsException("NoSuchBucket", "The specified bucket does not exist", 404));
        assertTrue(ddlFor(unpartitioned(headerlessTsvTable("events", "s3://gone/events/")))
                .contains("read_csv('s3://gone/events/**'"));
    }

    /** A location holding a glob character the check does not model keeps the read, which the engine globs. */
    @Test
    void aLocationHoldingAnUnmodeledGlobIsReadAsBefore() {
        holds("lake", "events?/");
        holds("lake", "[ab]/");
        assertTrue(ddlFor(unpartitioned(headerlessTsvTable("events", "s3://lake/events?/")))
                .contains("read_csv('s3://lake/events?/**'"));
        assertTrue(ddlFor(unpartitioned(headerlessTsvTable("events", "s3://lake/[ab]/")))
                .contains("read_csv('s3://lake/[ab]/**'"));
    }

    private static Table unpartitioned(Table table) {
        table.setPartitionKeys(List.of());
        return table;
    }

    /** The view of no rows types a nested or binary column as the engine reads it, not as text. */
    @Test
    void anEmptyViewTypesNestedAndBinaryColumns() {
        holds("lake", "nested/");
        Table table = tableWithColumns("nested", "s3://lake/nested/", List.of(column("tags", "array<string>"),
                column("attrs", "map<string,int>"), column("who", "struct<name:string,ids:array<bigint>>"),
                column("raw", "binary"), column("price", "decimal(10, 2)"), column("code", "varchar(8)")), null);
        assertTrue(ddlFor(table).contains("AS SELECT CAST(NULL AS VARCHAR[]) AS \"tags\", "
                + "CAST(NULL AS MAP(VARCHAR, INTEGER)) AS \"attrs\", "
                + "CAST(NULL AS STRUCT(\"name\" VARCHAR, \"ids\" BIGINT[])) AS \"who\", CAST(NULL AS BLOB) AS \"raw\", "
                + "CAST(NULL AS DECIMAL(10, 2)) AS \"price\", CAST(NULL AS VARCHAR) AS \"code\" LIMIT 0;\n"));
    }

    private Table headerlessTsvTable(String name, String location) {
        Table table = createTable(name, location, "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe",
                "org.apache.hadoop.mapred.TextInputFormat");
        table.getStorageDescriptor().getSerdeInfo().setParameters(Map.of("field.delim", "\t"));
        table.getStorageDescriptor().setColumns(List.of(column("id", "bigint"), column("name", "string")));
        table.setPartitionKeys(List.of(column("dt", "string")));
        return table;
    }

    @Test
    void headerlessDelimitedTextIsReadWithItsDelimiterColumnNamesAndTypes() {
        holds("lake", "events/", "events/dt=2026-11-16/part-0");
        when(glueService.getPartitions("audit", "events")).thenReturn(List.of(partition(null, "2026-11-16")));
        String ddl = ddlFor(headerlessTsvTable("events", "s3://lake/events/"));

        assertTrue(ddl.contains("read_csv(['s3://lake/events/dt=2026-11-16/**'], header = false, delim = '\t', quote = '', "
                + "nullstr = '\\N', auto_detect = false, null_padding = true, strict_mode = false, "
                + "columns = {'id': 'VARCHAR', 'name': 'VARCHAR'}, filename = '$path', hive_partitioning = false)"), ddl);
        assertTrue(ddl.contains("SELECT TRY_CAST(r.\"id\" AS BIGINT) AS \"id\", r.\"name\" AS \"name\", "
                + "p.\"dt\" AS \"dt\" FROM"), ddl);
        assertFalse(ddl.contains("read_csv_auto"), ddl);
    }

    @Test
    void delimitedTextPartitionKeysTakeTheirDeclaredTypesToo() {
        Table table = headerlessTsvTable("events", "s3://lake/events/");
        table.setPartitionKeys(List.of(column("year", "int")));
        holds("lake", "events/", "events/year=2026/part-0");
        when(glueService.getPartitions("audit", "events")).thenReturn(List.of(partition(null, "2026")));

        String ddl = ddlFor(table);

        assertTrue(ddl.contains("ELSE CAST('2026' AS INTEGER) END)"), ddl);
        assertTrue(ddl.contains("p.\"year\" AS \"year\""), ddl);
    }

    @Test
    void delimitedTextWithALocationTemplateAlsoReadsItsObjectPaths() {
        Table table = headerlessTsvTable("events", "s3://lake/events/");
        table.setParameters(Map.of("projection.enabled", "true", "projection.dt.type", "injected",
                "storage.location.template", "s3://lake/events/${dt}/"));

        String ddl = ddlFor(table);

        assertTrue(ddl.contains("columns = {'id': 'VARCHAR', 'name': 'VARCHAR'}, hive_partitioning = false, filename = '"),
                ddl);
    }

    @Test
    void onlyTheTablesAQueryNamesGetViews() {
        Database db = createDatabase("lake");
        when(glueService.getDatabases()).thenReturn(List.of(db));
        when(glueService.getTables("lake")).thenReturn(List.of(
                headerlessTsvTable("events", "s3://lake/events/"),
                createTable("broken", "s3://lake/broken/", "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe",
                        null),
                createTable("events_daily", "s3://lake/daily/", "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe",
                        null)));

        String ddl = builder.build("lake", "SELECT count(*) FROM \"lake\".\"EVENTS\" WHERE name = 'broken_x'");

        assertTrue(ddl.contains("VIEW \"lake\".\"events\" AS"), ddl);
        // A qualified reference resolves in its database, not through the context's unqualified names.
        assertFalse(ddl.contains("VIEW \"events\" AS"), ddl);
        assertFalse(ddl.contains("\"broken\""), ddl);
        assertFalse(ddl.contains("events_daily"), ddl);
        assertTrue(ddl.contains("CREATE SCHEMA IF NOT EXISTS \"lake\""), ddl);
    }

    @Test
    void queriesThatListTablesStillGetEveryView() {
        Database db = createDatabase("lake");
        when(glueService.getDatabases()).thenReturn(List.of(db));
        when(glueService.getTables("lake")).thenReturn(List.of(headerlessTsvTable("events", "s3://lake/events/")));

        assertTrue(builder.build("lake", "SELECT table_name FROM information_schema.tables").contains("\"events\""));
        assertTrue(builder.build("lake", "  show tables").contains("\"events\""));
        assertTrue(builder.build("lake", "-- listing\nSHOW TABLES").contains("\"events\""));
        assertTrue(builder.build("lake", "/* listing */ SHOW COLUMNS IN other").contains("\"events\""));
        assertTrue(builder.build("lake", null).contains("\"events\""));
    }

    private static final String PARQUET_SERDE = "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe";

    @Test
    void aTableIsNamedByItsDatabaseAndNameNotByTextThatOnlyLooksLikeIt() {
        when(glueService.getDatabases()).thenReturn(List.of(createDatabase("good"), createDatabase("bad")));
        when(glueService.getTables("good")).thenReturn(List.of(headerlessTsvTable("events", "s3://lake/events/")));
        when(glueService.getTables("bad")).thenReturn(List.of(
                createTable("events", "s3://lake/bad/", PARQUET_SERDE, null),
                createTable("broken", "s3://lake/broken/", PARQUET_SERDE, null)));

        // The namesake in another database, and a name only a literal, a comment or a qualified column holds.
        String ddl = builder.build("good", "SELECT e.broken, 'bad.broken' FROM good.events e -- bad.events\n"
                + "/* broken */ WHERE e.name <> 'x'");

        assertTrue(ddl.contains("VIEW \"good\".\"events\" AS"), ddl);
        assertFalse(ddl.contains("VIEW \"bad\".\"events\""), ddl);
        assertFalse(ddl.contains("\"broken\""), ddl);
        // Unqualified, a table resolves in the context database alone.
        String unqualified = builder.build("good", "SELECT * FROM events");
        assertTrue(unqualified.contains("VIEW \"events\" AS"), unqualified);
        assertFalse(unqualified.contains("VIEW \"bad\".\"events\""), unqualified);
    }

    @Test
    void aQuotedReferenceIsDecodedBeforeItNamesATable() {
        when(glueService.getDatabases()).thenReturn(List.of(createDatabase("lake")));
        when(glueService.getTables("lake")).thenReturn(List.of(headerlessTsvTable("a\"b", "s3://lake/ab/")));

        String ddl = builder.build("lake", "SELECT * FROM \"lake\" . \"A\"\"B\"");

        assertTrue(ddl.contains("VIEW \"lake\".\"a\"\"b\" AS"), ddl);
        assertEquals(List.of(List.of("select"), List.of("lake", "a\"b")),
                GlueViewDdlBuilder.references("SELECT * FROM \"lake\" . \"A\"\"B\"").stream()
                        .filter(name -> !name.getFirst().equals("from")).toList());
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
        when(glueService.getDatabases()).thenReturn(Arrays.asList(null, nullNameDb, blankNameDb, validDb));
        when(glueService.getTables("valid_db")).thenReturn(List.of());

        String ddl = builder.build(null);
        assertTrue(ddl.contains("CREATE SCHEMA IF NOT EXISTS \"valid_db\";\n"));
        assertFalse(ddl.contains("CREATE SCHEMA IF NOT EXISTS \"\";"));
        assertFalse(ddl.contains("CREATE SCHEMA IF NOT EXISTS \"null\";"));
    }

    @Test
    void testGetDatabasesExceptionPropagates() {
        when(glueService.getDatabases()).thenThrow(new RuntimeException("Glue failure"));
        Assertions.assertThrows(RuntimeException.class, () -> builder.build(null));
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

    private Table icebergTable(String name, String location, String metadataLocation) {
        Table table = createTable(name, location, null, null);
        table.setParameters(metadataLocation == null
                ? Map.of("table_type", "ICEBERG")
                : Map.of("table_type", "ICEBERG", "metadata_location", metadataLocation));
        return table;
    }

    /**
     * pyiceberg's GlueCatalog (and AWS's own Glue-Iceberg integration) never populate
     * InputFormat/SerializationLibrary, so an Iceberg table must be detected from
     * Parameters.table_type rather than format-sniffed like every other table kind.
     */
    @Test
    void testIcebergTableUsesIcebergScanOnMetadataLocation() {
        Table table = icebergTable("orders", "s3://bucket/warehouse/orders",
                "s3://bucket/warehouse/orders/metadata/00001-abc.metadata.json");
        String ddl = ddlFor(table);

        assertTrue(ddl.contains(
                "CREATE OR REPLACE VIEW \"audit\".\"orders\" AS SELECT * FROM "
                        + "iceberg_scan('s3://bucket/warehouse/orders/metadata/00001-abc.metadata.json');\n"),
                ddl);
    }

    /** The iceberg extension is expensive to install, so it's only requested when actually needed. */
    @Test
    void testIcebergExtensionSetupOnlyEmittedWhenTableIsIceberg() {
        Table icebergTable = icebergTable("orders", "s3://bucket/orders",
                "s3://bucket/orders/metadata/00000.metadata.json");
        String ddlWithIceberg = ddlFor(icebergTable);
        assertTrue(ddlWithIceberg.startsWith("INSTALL iceberg; LOAD iceberg;\n"), ddlWithIceberg);

        Table plainTable = createTable("orders", "s3://bucket/orders", null, null);
        String ddlWithoutIceberg = ddlFor(plainTable);
        assertFalse(ddlWithoutIceberg.contains("iceberg"), ddlWithoutIceberg);
    }

    /** A malformed Iceberg table (flagged as Iceberg but missing metadata_location) falls back
     *  to format inference rather than emitting an unusable iceberg_scan('') call. */
    @Test
    void testIcebergTableWithoutMetadataLocationFallsBackToFormatInference() {
        Table table = icebergTable("orders", "s3://bucket/orders", null);
        String ddl = ddlFor(table);

        assertTrue(ddl.contains("FROM read_csv_auto('s3://bucket/orders/**');\n"), ddl);
        assertFalse(ddl.contains("iceberg_scan"), ddl);
    }

    @Test
    void testIsIcebergTableDetection() {
        assertFalse(GlueTableResolver.isIcebergTable(null));
        assertFalse(GlueTableResolver.isIcebergTable(createTable("t", "s3://b/t", null, null)));

        Table table = createTable("t", "s3://b/t", null, null);
        table.setParameters(Map.of("table_type", "iceberg"));
        assertTrue(GlueTableResolver.isIcebergTable(table), "table_type comparison should be case-insensitive");

        table.setParameters(Map.of("table_type", "EXTERNAL_TABLE"));
        assertFalse(GlueTableResolver.isIcebergTable(table));
    }

    @Test
    void testIcebergReadExpressionEscapesSingleQuotes() {
        assertEquals("iceberg_scan('s3://bucket/it''s-a-table/metadata/x.json')",
                GlueTableResolver.icebergReadExpression("s3://bucket/it's-a-table/metadata/x.json"));
    }
}

