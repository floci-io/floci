package io.floci.aws.services.glue;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.DatabaseSecretCredentials;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.glue.JdbcCatalogReader.JdbcColumn;
import io.floci.aws.services.glue.JdbcCatalogReader.JdbcTableSchema;
import io.floci.aws.services.glue.model.AuthenticationConfiguration;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.ConnectionInput;
import io.floci.aws.services.glue.model.Crawler;
import io.floci.aws.services.glue.model.CrawlerTargets;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.JdbcTarget;
import io.floci.aws.services.glue.model.S3Target;
import io.floci.aws.services.glue.model.SchemaChangePolicy;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.redshift.RedshiftJdbcTargets;
import io.floci.aws.services.redshift.RedshiftJdbcUrl;
import io.floci.aws.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcCrawlRunnerTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String URL = "jdbc:redshift://analytics.abc123.us-east-1.redshift.amazonaws.com:5439/dev";
    private static final String SESSION_URL = "jdbc:postgresql://rs-backend:15439/dev?preferQueryMode=simple";

    private GlueService glueService;
    private RedshiftJdbcTargets jdbcTargets;
    private List<String> sessions;
    private List<JdbcTableSchema> tables;
    private SQLException readFailure;
    private JdbcCrawlRunner runner;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        StorageFactory storageFactory = new InMemoryStorageFactory(ACCOUNT_ID);
        KmsService kmsService = new KmsService(storageFactory, regionResolver);
        glueService = new GlueService(storageFactory, new GlueSchemaRegistryService(storageFactory, regionResolver),
                regionResolver, new ResourceGroupsTaggingService(storageFactory), kmsService);
        jdbcTargets = mock(RedshiftJdbcTargets.class);
        when(jdbcTargets.resolve(eq(ACCOUNT_ID), eq(REGION), any(RedshiftJdbcUrl.class)))
                .thenReturn(new RedshiftJdbcTargets.Backend("cluster analytics", "rs-backend", 15439, "dev", "admin"));
        sessions = new ArrayList<>();
        tables = new ArrayList<>();
        JdbcCatalogReader reader = (jdbcUrl, user, password) -> {
            sessions.add(jdbcUrl + " " + user + " " + password);
            if (readFailure != null) {
                throw readFailure;
            }
            return tables;
        };
        runner = new JdbcCrawlRunner(glueService, jdbcTargets, kmsService, regionResolver, reader);
        glueService.createDatabase(new Database("lake"));
    }

    private void connection(String name, Map<String, String> properties) {
        ConnectionInput input = new ConnectionInput();
        input.setName(name);
        input.setConnectionType("JDBC");
        input.setConnectionProperties(new LinkedHashMap<>(properties));
        glueService.createConnection(input, null, REGION);
    }

    private void passwordConnection(String name) {
        connection(name, Map.of("JDBC_CONNECTION_URL", URL, "USERNAME", "crawler", "PASSWORD", "Secret123"));
    }

    private static JdbcTarget target(String connectionName, String path, String... exclusions) {
        JdbcTarget target = new JdbcTarget();
        target.setConnectionName(connectionName);
        target.setPath(path);
        if (exclusions.length > 0) {
            target.setExclusions(List.of(exclusions));
        }
        return target;
    }

    private Crawler crawler(String name, JdbcTarget... targets) {
        CrawlerTargets crawlerTargets = new CrawlerTargets();
        crawlerTargets.setJdbcTargets(List.of(targets));
        Crawler crawler = new Crawler();
        crawler.setName(name);
        crawler.setRole("arn:aws:iam::000000000000:role/glue");
        crawler.setDatabaseName("lake");
        crawler.setTargets(crawlerTargets);
        return crawler;
    }

    private void create(Crawler crawler) {
        glueService.createCrawler(crawler, null, REGION);
    }

    private static JdbcTableSchema table(String schema, String name, JdbcColumn... columns) {
        return new JdbcTableSchema(schema, name, false, null, List.of(columns));
    }

    private static JdbcColumn column(String name, String rawType) {
        return new JdbcColumn(name, rawType, null);
    }

    private CrawlOutcome crawl(String crawlerName) {
        return runner.read(crawlerName).apply();
    }

    private static List<String> columnsOf(Table table) {
        List<String> columns = new ArrayList<>();
        for (Column column : table.getStorageDescriptor().getColumns()) {
            columns.add(column.getName() + ":" + column.getType());
        }
        return columns;
    }

    @Test
    void readsADataStoreOnlyForACrawlerWithJdbcTargets() {
        passwordConnection("analytics");
        create(crawler("jdbc", target("analytics", "dev/public/%")));
        S3Target s3 = new S3Target();
        s3.setPath("s3://raw/data");
        CrawlerTargets s3Targets = new CrawlerTargets();
        s3Targets.setS3Targets(List.of(s3));
        Crawler s3Crawler = crawler("s3");
        s3Crawler.setTargets(s3Targets);
        create(s3Crawler);

        assertTrue(runner.readsDataStore("jdbc"));
        assertFalse(runner.readsDataStore("s3"));
        assertFalse(runner.readsDataStore("missing"));
    }

    @Test
    void aCrawlerWithoutJdbcTargetsSucceedsWithoutTouchingTheCatalog() {
        S3Target s3 = new S3Target();
        s3.setPath("s3://raw/data");
        CrawlerTargets s3Targets = new CrawlerTargets();
        s3Targets.setS3Targets(List.of(s3));
        Crawler crawler = crawler("s3");
        crawler.setDatabaseName(null);
        crawler.setTargets(s3Targets);
        create(crawler);

        assertEquals(CrawlOutcome.succeeded(), runner.run("s3"));
        assertThat(sessions, empty());
    }

    @Test
    void createsACatalogTableForEachSelectedTable() {
        passwordConnection("analytics");
        create(crawler("orders", target("analytics", "dev/public/%")));
        tables.add(table("public", "orders", column("id", "integer"), column("amount", "numeric(10,2)"),
                column("note", "character varying(256)"), column("placed_at", "timestamp without time zone"),
                column("paid", "boolean")));
        tables.add(table("sales", "invoices", column("id", "bigint")));

        CrawlOutcome outcome = crawl("orders");

        assertEquals(CrawlOutcome.succeeded(1, 0, 0), outcome);
        assertEquals(List.of(SESSION_URL + " crawler Secret123"), sessions);
        Table created = glueService.getTable("lake", "dev_public_orders");
        assertThat(columnsOf(created), contains("id:int", "amount:decimal(10,2)", "note:string",
                "placed_at:timestamp", "paid:boolean"));
        assertEquals("dev.public.orders", created.getStorageDescriptor().getLocation());
        assertEquals("EXTERNAL_TABLE", created.getTableType());
        assertThat(created.getParameters(), hasEntry("classification", "redshift"));
        assertThat(created.getParameters(), hasEntry("connectionName", "analytics"));
        assertThat(created.getParameters(), hasEntry("typeOfData", "table"));
        assertThat(created.getParameters(), hasEntry("UPDATED_BY_CRAWLER", "orders"));
        assertThat(created.getStorageDescriptor().getParameters(), hasEntry("classification", "redshift"));
        assertThat(glueService.getTables("lake"), hasSize(1));
    }

    @Test
    void mapsRedshiftTypesToCatalogTypes() {
        passwordConnection("analytics");
        create(crawler("types", target("analytics", "dev/public/types")));
        tables.add(table("public", "types", column("a", "smallint"), column("b", "bigint"), column("c", "real"),
                column("d", "double precision"), column("e", "numeric"), column("f", "character(1)"),
                column("g", "text"), column("h", "date"), column("i", "timestamp with time zone"),
                column("j", "super"), column("k", "time without time zone")));

        crawl("types");

        assertThat(columnsOf(glueService.getTable("lake", "dev_public_types")), contains("a:smallint", "b:bigint",
                "c:float", "d:double", "e:decimal(18,0)", "f:string", "g:string", "h:date", "i:timestamp",
                "j:string", "k:string"));
    }

    @Test
    void appliesTheTablePrefixAndTheExclusions() {
        passwordConnection("analytics");
        Crawler crawler = crawler("prefixed", target("analytics", "dev/%", "audit/*", "**_tmp"));
        crawler.setTablePrefix("rs_");
        create(crawler);
        tables.add(table("public", "orders", column("id", "integer")));
        tables.add(table("public", "orders_tmp", column("id", "integer")));
        tables.add(table("audit", "events", column("id", "integer")));
        tables.add(table("Sales", "Big-Deals", column("id", "integer")));

        CrawlOutcome outcome = crawl("prefixed");

        assertEquals(CrawlOutcome.succeeded(2, 0, 0), outcome);
        List<String> names = new ArrayList<>();
        for (Table table : glueService.getTables("lake")) {
            names.add(table.getName());
        }
        names.sort(null);
        assertEquals(List.of("rs_dev_public_orders", "rs_dev_sales_big_deals"), names);
    }

    @Test
    void marksAViewAsAView() {
        passwordConnection("analytics");
        create(crawler("views", target("analytics", "dev/public/%")));
        tables.add(new JdbcTableSchema("public", "recent_orders", true, null, List.of(column("id", "integer"))));

        crawl("views");

        assertThat(glueService.getTable("lake", "dev_public_recent_orders").getParameters(),
                hasEntry("typeOfData", "view"));
    }

    @Test
    void addsRawTypesAndCommentsOnlyWhenTheTargetAsksForThem() {
        passwordConnection("analytics");
        JdbcTarget withMetadata = target("analytics", "dev/public/orders");
        withMetadata.setEnableAdditionalMetadata(List.of("RAWTYPES", "COMMENTS"));
        create(crawler("metadata", withMetadata));
        create(crawler("plain", target("analytics", "dev/public/orders")));
        tables.add(new JdbcTableSchema("public", "orders", false, "Orders placed online",
                List.of(new JdbcColumn("id", "integer", "order key"))));

        crawl("metadata");
        Table rich = glueService.getTable("lake", "dev_public_orders");
        Column id = rich.getStorageDescriptor().getColumns().getFirst();
        assertThat(id.getParameters(), hasEntry("aws:RawType", "integer"));
        assertThat(id.getParameters(), hasEntry("aws:RawColumnComment", "order key"));
        assertThat(rich.getParameters(), hasEntry("aws:RawTableComment", "Orders placed online"));

        glueService.deleteTable("lake", "dev_public_orders");
        crawl("plain");
        Table plain = glueService.getTable("lake", "dev_public_orders");
        assertNull(plain.getStorageDescriptor().getColumns().getFirst().getParameters());
        assertThat(plain.getParameters(), not(hasKey("aws:RawTableComment")));
    }

    @Test
    void aSecondCrawlUpdatesOnlyTheTablesWhoseSchemaChanged() {
        passwordConnection("analytics");
        create(crawler("orders", target("analytics", "dev/public/%")));
        tables.add(table("public", "orders", column("id", "integer")));
        tables.add(table("public", "customers", column("id", "integer")));
        crawl("orders");

        tables.set(0, table("public", "orders", column("id", "integer"), column("total", "bigint")));
        CrawlOutcome second = crawl("orders");

        assertEquals(CrawlOutcome.succeeded(0, 1, 0), second);
        assertThat(columnsOf(glueService.getTable("lake", "dev_public_orders")), contains("id:int", "total:bigint"));
        assertEquals(CrawlOutcome.succeeded(0, 0, 0), crawl("orders"));
    }

    @Test
    void anUpdateBehaviorOfLogLeavesExistingTablesAlone() {
        passwordConnection("analytics");
        Crawler crawler = crawler("orders", target("analytics", "dev/public/%"));
        SchemaChangePolicy policy = new SchemaChangePolicy();
        policy.setUpdateBehavior("LOG");
        crawler.setSchemaChangePolicy(policy);
        create(crawler);
        tables.add(table("public", "orders", column("id", "integer")));
        crawl("orders");

        tables.set(0, table("public", "orders", column("id", "integer"), column("total", "bigint")));
        CrawlOutcome second = crawl("orders");

        assertEquals(CrawlOutcome.succeeded(0, 0, 0), second);
        assertThat(columnsOf(glueService.getTable("lake", "dev_public_orders")), contains("id:int"));
    }

    @Test
    void readsTheCredentialsFromTheSecretTheConnectionNames() {
        connection("by-secret", Map.of("JDBC_CONNECTION_URL", URL, "SECRET_ID", "rs-secret"));
        when(jdbcTargets.credentialsFromSecret("rs-secret", REGION))
                .thenReturn(new DatabaseSecretCredentials("secret-user", "secret-pass"));
        create(crawler("secret", target("by-secret", "dev/public/%")));

        crawl("secret");

        assertEquals(List.of(SESSION_URL + " secret-user secret-pass"), sessions);
    }

    @Test
    void readsTheCredentialsFromTheAuthenticationConfigurationSecret() {
        ConnectionInput input = new ConnectionInput();
        input.setName("by-auth");
        input.setConnectionType("JDBC");
        input.setConnectionProperties(new LinkedHashMap<>(Map.of("JDBC_CONNECTION_URL", URL)));
        AuthenticationConfiguration authentication = new AuthenticationConfiguration();
        authentication.setAuthenticationType("BASIC");
        authentication.setSecretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:rs-AbCdEf");
        input.setAuthenticationConfiguration(authentication);
        glueService.createConnection(input, null, REGION);
        when(jdbcTargets.credentialsFromSecret("arn:aws:secretsmanager:us-east-1:000000000000:secret:rs-AbCdEf",
                REGION)).thenReturn(new DatabaseSecretCredentials("auth-user", "auth-pass"));
        create(crawler("auth", target("by-auth", "dev/public/%")));

        crawl("auth");

        assertEquals(List.of(SESSION_URL + " auth-user auth-pass"), sessions);
    }

    @Test
    void aMissingConnectionFailsBeforeReadingAnything() {
        create(crawler("orphan", target("missing", "dev/public/%")));

        AwsException e = assertThrows(AwsException.class, () -> runner.read("orphan"));

        assertThat(e.getMessage(), containsString("missing"));
        assertThat(sessions, empty());
    }

    @Test
    void aConnectionWithoutCredentialsFails() {
        connection("anonymous", Map.of("JDBC_CONNECTION_URL", URL));
        create(crawler("anonymous", target("anonymous", "dev/public/%")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> runner.read("anonymous"));

        assertThat(e.getMessage(), containsString("anonymous"));
        assertThat(sessions, empty());
    }

    @Test
    void aConnectionToAnotherEngineFails() {
        connection("mysql", Map.of("JDBC_CONNECTION_URL", "jdbc:mysql://db.example.com:3306/shop",
                "USERNAME", "u", "PASSWORD", "p"));
        create(crawler("mysql", target("mysql", "shop/%")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> runner.read("mysql"));

        assertThat(e.getMessage(), containsString("Redshift"));
    }

    @Test
    void badCredentialsOrAnUnreachableEndpointFailWithTheDriverMessageAndWriteNothing() {
        passwordConnection("analytics");
        create(crawler("orders", target("analytics", "dev/public/%")));
        readFailure = new SQLException("password authentication failed for user \"crawler\"", "28P01");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> runner.read("orders"));

        assertThat(e.getMessage(), containsString("password authentication failed"));
        assertThat(e.getMessage(), containsString("analytics"));
        assertThat(glueService.getTables("lake"), empty());
    }

    @Test
    void aFailingTargetWritesNothingFromTheTargetsThatSucceeded() {
        passwordConnection("analytics");
        create(crawler("two", target("analytics", "dev/public/%"), target("missing", "dev/sales/%")));
        tables.add(table("public", "orders", column("id", "integer")));

        assertThrows(AwsException.class, () -> runner.read("two"));

        assertThat(glueService.getTables("lake"), empty());
    }

    @Test
    void aMissingCatalogDatabaseFailsTheWrite() {
        passwordConnection("analytics");
        Crawler crawler = crawler("nowhere", target("analytics", "dev/public/%"));
        crawler.setDatabaseName("absent");
        create(crawler);
        tables.add(table("public", "orders", column("id", "integer")));

        CrawlRunner.CatalogWrite write = runner.read("nowhere");
        AwsException e = assertThrows(AwsException.class, write::apply);

        assertThat(e.getMessage(), containsString("absent"));
    }

    @Test
    void anIncludePathNamingAnotherDatabaseFailsBeforeReading() {
        passwordConnection("analytics");
        create(crawler("elsewhere", target("analytics", "missing/public/%")));
        tables.add(table("public", "orders", column("id", "integer")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> runner.read("elsewhere"));

        assertThat(e.getMessage(), containsString("missing"));
        assertThat(e.getMessage(), containsString("dev"));
        assertThat(sessions, empty());
    }

    /** Adds a table parameter and a comment and parameter on the first column, as a user editing the table would. */
    private void annotate(String tableName) {
        Table table = glueService.getTable("lake", tableName);
        Map<String, String> parameters = new LinkedHashMap<>(table.getParameters());
        parameters.put("owner_team", "data");
        table.setParameters(parameters);
        Column first = table.getStorageDescriptor().getColumns().getFirst();
        first.setComment("primary key");
        first.setParameters(Map.of("pii", "false"));
        glueService.updateTable("lake", table, null, false);
    }

    @Test
    void anUnchangedSourceKeepsTheMetadataAUserAdded() {
        passwordConnection("analytics");
        create(crawler("orders", target("analytics", "dev/public/%")));
        tables.add(table("public", "orders", column("id", "integer")));
        crawl("orders");
        annotate("dev_public_orders");

        CrawlOutcome second = crawl("orders");

        assertEquals(CrawlOutcome.succeeded(0, 0, 0), second);
        Table table = glueService.getTable("lake", "dev_public_orders");
        assertThat(table.getParameters(), hasEntry("owner_team", "data"));
        assertEquals("primary key", table.getStorageDescriptor().getColumns().getFirst().getComment());
    }

    @Test
    void aSchemaChangeKeepsTheMetadataAUserAdded() {
        passwordConnection("analytics");
        create(crawler("orders", target("analytics", "dev/public/%")));
        tables.add(table("public", "orders", column("id", "integer")));
        crawl("orders");
        annotate("dev_public_orders");

        tables.set(0, table("public", "orders", column("id", "integer"), column("total", "bigint")));
        CrawlOutcome second = crawl("orders");

        assertEquals(CrawlOutcome.succeeded(0, 1, 0), second);
        Table table = glueService.getTable("lake", "dev_public_orders");
        assertThat(columnsOf(table), contains("id:int", "total:bigint"));
        assertThat(table.getParameters(), hasEntry("owner_team", "data"));
        assertThat(table.getParameters(), hasEntry("classification", "redshift"));
        Column id = table.getStorageDescriptor().getColumns().getFirst();
        assertEquals("primary key", id.getComment());
        assertThat(id.getParameters(), hasEntry("pii", "false"));
    }

    @Test
    void aStoredColumnWithoutATypeIsReplacedByTheCrawledSchema() {
        passwordConnection("analytics");
        create(crawler("orders", target("analytics", "dev/public/%")));
        tables.add(table("public", "orders", column("id", "integer")));
        crawl("orders");
        Table untyped = glueService.getTable("lake", "dev_public_orders");
        StorageDescriptor descriptor = untyped.getStorageDescriptor();
        descriptor.setColumns(List.of(new Column("id", null)));
        glueService.updateTable("lake", untyped, null, false);

        CrawlOutcome outcome = crawl("orders");

        assertEquals(CrawlOutcome.succeeded(0, 1, 0), outcome);
        assertThat(columnsOf(glueService.getTable("lake", "dev_public_orders")), contains("id:int"));
    }
}
