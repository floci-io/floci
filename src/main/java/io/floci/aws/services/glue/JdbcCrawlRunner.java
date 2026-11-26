package io.floci.aws.services.glue;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.DatabaseSecretCredentials;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.glue.JdbcCatalogReader.JdbcColumn;
import io.floci.aws.services.glue.JdbcCatalogReader.JdbcTableSchema;
import io.floci.aws.services.glue.model.AuthenticationConfiguration;
import io.floci.aws.services.glue.model.Column;
import io.floci.aws.services.glue.model.Connection;
import io.floci.aws.services.glue.model.Crawler;
import io.floci.aws.services.glue.model.JdbcTarget;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.kms.KmsService;
import io.floci.aws.services.redshift.RedshiftJdbcTargets;
import io.floci.aws.services.redshift.RedshiftJdbcUrl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crawls the JDBC targets of a crawler whose Glue connection points at a Redshift cluster or workgroup:
 * opens a session with the connection's credentials, reads the tables and views the include path selects,
 * and writes one Data Catalog table for each into the crawler's database. Other JDBC engines and the
 * crawler's other targets are not read.
 *
 * <p>A table is named {@code <TablePrefix><database>_<schema>_<table>}, lowercased, with every character
 * other than a letter, digit or underscore replaced by an underscore, and its location is
 * {@code database.schema.table}.
 */
@ApplicationScoped
public class JdbcCrawlRunner implements CrawlRunner {

    private static final Logger LOG = Logger.getLogger(JdbcCrawlRunner.class);

    private static final int MAX_TABLE_NAME_LENGTH = 128;
    private static final int MAX_COMMENT_BYTES = 255;
    private static final Pattern NUMERIC = Pattern.compile("(?:numeric|decimal)\\((\\d+),(\\d+)\\)");
    private static final Pattern NOT_A_NAME_CHARACTER = Pattern.compile("[^a-z0-9_]");

    private final GlueService glueService;
    private final RedshiftJdbcTargets jdbcTargets;
    private final KmsService kmsService;
    private final RegionResolver regionResolver;
    private final JdbcCatalogReader reader;

    @Inject
    public JdbcCrawlRunner(GlueService glueService, RedshiftJdbcTargets jdbcTargets, KmsService kmsService,
                           RegionResolver regionResolver) {
        this(glueService, jdbcTargets, kmsService, regionResolver, new PostgresCatalogReader());
    }

    JdbcCrawlRunner(GlueService glueService, RedshiftJdbcTargets jdbcTargets, KmsService kmsService,
                    RegionResolver regionResolver, JdbcCatalogReader reader) {
        this.glueService = glueService;
        this.jdbcTargets = jdbcTargets;
        this.kmsService = kmsService;
        this.regionResolver = regionResolver;
        this.reader = reader;
    }

    /** The run service calls this for a crawler with no JDBC target, which crawls nothing. */
    @Override
    public CrawlOutcome run(String crawlerName) {
        return readsDataStore(crawlerName) ? read(crawlerName).apply() : CrawlOutcome.succeeded();
    }

    @Override
    public boolean readsDataStore(String crawlerName) {
        try {
            return !jdbcTargetsOf(glueService.getCrawler(crawlerName)).isEmpty();
        } catch (AwsException e) {
            LOG.debugv("Glue crawler {0} is gone, so it reads no data store: {1}", crawlerName, e.getMessage());
            return false;
        }
    }

    /**
     * Reads every JDBC target. Any target that cannot be read fails the whole crawl before anything is written.
     *
     * @throws AwsException when a connection does not exist, or the cluster, workgroup or secret it names
     * @throws IllegalStateException when a connection cannot be used or the data store cannot be read
     */
    @Override
    public CatalogWrite read(String crawlerName) {
        Crawler crawler = glueService.getCrawler(crawlerName);
        Map<String, Table> found = new LinkedHashMap<>();
        for (JdbcTarget target : jdbcTargetsOf(crawler)) {
            for (Table table : readTarget(crawler, target)) {
                found.putIfAbsent(table.getName(), table);
            }
        }
        return () -> write(crawler, List.copyOf(found.values()));
    }

    private static List<JdbcTarget> jdbcTargetsOf(Crawler crawler) {
        if (crawler.getTargets() == null || crawler.getTargets().getJdbcTargets() == null) {
            return List.of();
        }
        return crawler.getTargets().getJdbcTargets();
    }

    private List<Table> readTarget(Crawler crawler, JdbcTarget target) {
        String connectionName = target.getConnectionName();
        if (connectionName == null || connectionName.isBlank()) {
            throw new IllegalStateException("A JDBC target of crawler " + crawler.getName() + " names no connection.");
        }
        Connection connection = glueService.getConnection(connectionName, false);
        Map<String, String> properties = connection.getConnectionProperties() == null
                ? Map.of() : connection.getConnectionProperties();
        RedshiftJdbcUrl url = redshiftUrl(connectionName, properties.get("JDBC_CONNECTION_URL"));
        JdbcIncludePath includePath;
        try {
            includePath = JdbcIncludePath.parse(target.getPath(), target.getExclusions());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        String region = regionResolver.getRegion();
        RedshiftJdbcTargets.Backend backend = jdbcTargets.resolve(regionResolver.getAccountId(), region, url);
        DatabaseSecretCredentials credentials = credentials(connection, properties, region);
        String jdbcUrl = "jdbc:postgresql://" + backend.host() + ":" + backend.port() + "/" + backend.database()
                + "?preferQueryMode=simple";
        List<JdbcTableSchema> schemas;
        try {
            schemas = reader.read(jdbcUrl, credentials.username(), credentials.password());
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read " + target.getPath() + " through connection "
                    + connectionName + " (" + backend.description() + "): " + e.getMessage(), e);
        }
        List<String> metadata = target.getEnableAdditionalMetadata() == null
                ? List.of() : target.getEnableAdditionalMetadata();
        List<Table> tables = new ArrayList<>();
        for (JdbcTableSchema schema : schemas) {
            if (includePath.selects(schema.schema(), schema.name())) {
                tables.add(catalogTable(crawler, connectionName, includePath.database(), schema,
                        metadata.contains("RAWTYPES"), metadata.contains("COMMENTS")));
            }
        }
        return tables;
    }

    private static RedshiftJdbcUrl redshiftUrl(String connectionName, String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("Connection " + connectionName + " has no JDBC_CONNECTION_URL.");
        }
        try {
            return RedshiftJdbcUrl.parse(jdbcUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Connection " + connectionName + " does not point at a Redshift cluster"
                    + " or workgroup; Floci crawls only Redshift JDBC targets: " + jdbcUrl, e);
        }
    }

    /** The secret the connection names wins over a username and password stored on it. */
    private DatabaseSecretCredentials credentials(Connection connection, Map<String, String> properties,
                                                  String region) {
        AuthenticationConfiguration authentication = connection.getAuthenticationConfiguration();
        String secretId = authentication != null && authentication.getSecretArn() != null
                ? authentication.getSecretArn()
                : properties.get("SECRET_ID");
        if (secretId != null && !secretId.isBlank()) {
            return jdbcTargets.credentialsFromSecret(secretId, region);
        }
        String user = properties.get("USERNAME");
        String password = properties.get("PASSWORD");
        if (password == null && properties.get("ENCRYPTED_PASSWORD") != null) {
            password = new String(kmsService.decrypt(Base64.getDecoder().decode(properties.get("ENCRYPTED_PASSWORD")),
                    region), StandardCharsets.UTF_8);
        }
        if (user == null || password == null) {
            throw new IllegalStateException("Connection " + connection.getName()
                    + " has no credentials: set SECRET_ID, or USERNAME and PASSWORD.");
        }
        return new DatabaseSecretCredentials(user, password);
    }

    private static Table catalogTable(Crawler crawler, String connectionName, String database,
                                      JdbcTableSchema schema, boolean rawTypes, boolean comments) {
        List<Column> columns = new ArrayList<>();
        for (JdbcColumn source : schema.columns()) {
            Column column = new Column(source.name(), catalogType(source.rawType()));
            Map<String, String> parameters = new LinkedHashMap<>();
            if (rawTypes) {
                parameters.put("aws:RawType", source.rawType());
            }
            if (comments && source.comment() != null) {
                parameters.put("aws:RawColumnComment", truncate(source.comment()));
            }
            if (!parameters.isEmpty()) {
                column.setParameters(parameters);
            }
            columns.add(column);
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("classification", "redshift");
        parameters.put("compressionType", "none");
        parameters.put("connectionName", connectionName);
        parameters.put("typeOfData", schema.view() ? "view" : "table");
        parameters.put("CrawlerSchemaDeserializerVersion", "1.0");
        parameters.put("CrawlerSchemaSerializerVersion", "1.0");
        parameters.put("UPDATED_BY_CRAWLER", crawler.getName());
        if (comments && schema.comment() != null) {
            parameters.put("aws:RawTableComment", truncate(schema.comment()));
        }
        StorageDescriptor descriptor = new StorageDescriptor();
        descriptor.setColumns(columns);
        descriptor.setLocation(database + "." + schema.schema() + "." + schema.name());
        descriptor.setCompressed(false);
        descriptor.setNumberOfBuckets(-1);
        StorageDescriptor.SerDeInfo serde = new StorageDescriptor.SerDeInfo();
        serde.setParameters(new LinkedHashMap<>());
        descriptor.setSerdeInfo(serde);
        descriptor.setParameters(new LinkedHashMap<>(parameters));
        descriptor.setStoredAsSubDirectories(false);
        Table table = new Table();
        table.setName(tableName(crawler.getTablePrefix(), database, schema));
        table.setTableType("EXTERNAL_TABLE");
        table.setParameters(parameters);
        table.setStorageDescriptor(descriptor);
        return table;
    }

    private static String tableName(String prefix, String database, JdbcTableSchema schema) {
        String raw = (prefix == null ? "" : prefix) + database + "_" + schema.schema() + "_" + schema.name();
        String name = NOT_A_NAME_CHARACTER.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("_");
        return name.length() > MAX_TABLE_NAME_LENGTH ? name.substring(0, MAX_TABLE_NAME_LENGTH) : name;
    }

    /** The Hive type a crawler records for a Redshift column; types with no Hive counterpart become strings. */
    static String catalogType(String rawType) {
        String type = rawType.toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher numeric = NUMERIC.matcher(type);
        if (numeric.matches()) {
            return "decimal(" + numeric.group(1) + "," + numeric.group(2) + ")";
        }
        return switch (type) {
            case "smallint", "int2" -> "smallint";
            case "integer", "int", "int4" -> "int";
            case "bigint", "int8" -> "bigint";
            case "numeric", "decimal" -> "decimal(18,0)";
            case "real", "float4" -> "float";
            case "doubleprecision", "float8", "float" -> "double";
            case "boolean", "bool" -> "boolean";
            case "date" -> "date";
            case "timestampwithouttimezone", "timestampwithtimezone", "timestamp", "timestamptz" -> "timestamp";
            default -> "string";
        };
    }

    private static String truncate(String comment) {
        byte[] bytes = comment.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_COMMENT_BYTES) {
            return comment;
        }
        int end = comment.length();
        while (comment.substring(0, end).getBytes(StandardCharsets.UTF_8).length > MAX_COMMENT_BYTES) {
            end--;
        }
        return comment.substring(0, end);
    }

    /**
     * Creates the tables the catalog does not have and updates the ones whose schema changed, unless the
     * crawler's {@code SchemaChangePolicy.UpdateBehavior} is {@code LOG}.
     *
     * @throws AwsException when the crawler's database does not exist
     */
    private CrawlOutcome write(Crawler crawler, List<Table> tables) {
        String database = crawler.getDatabaseName();
        if (database == null || database.isBlank()) {
            throw new IllegalStateException("Crawler " + crawler.getName() + " has no DatabaseName to write to.");
        }
        glueService.getDatabase(database);
        Map<String, Table> existing = new HashMap<>();
        for (Table table : glueService.getTables(database)) {
            existing.put(table.getName(), table);
        }
        boolean logOnly = crawler.getSchemaChangePolicy() != null
                && "LOG".equals(crawler.getSchemaChangePolicy().getUpdateBehavior());
        int created = 0;
        int updated = 0;
        for (Table table : tables) {
            Table current = existing.get(table.getName());
            if (current == null) {
                glueService.createTable(database, table);
                created++;
            } else if (!sameSchema(current, table)) {
                if (logOnly) {
                    LOG.infov("Glue crawler {0} found a changed schema for {1}.{2} and only logs it",
                            crawler.getName(), database, table.getName());
                    continue;
                }
                table.setDescription(current.getDescription());
                table.setOwner(current.getOwner());
                glueService.updateTable(database, table, null, false);
                updated++;
            }
        }
        LOG.infov("Glue crawler {0} created {1} and updated {2} tables in {3}", crawler.getName(), created,
                updated, database);
        return CrawlOutcome.succeeded(created, updated, 0);
    }

    private static boolean sameSchema(Table current, Table crawled) {
        StorageDescriptor currentDescriptor = current.getStorageDescriptor();
        if (currentDescriptor == null) {
            return false;
        }
        return Objects.equals(currentDescriptor.getLocation(), crawled.getStorageDescriptor().getLocation())
                && Objects.equals(current.getParameters(), crawled.getParameters())
                && columnsOf(currentDescriptor).equals(columnsOf(crawled.getStorageDescriptor()));
    }

    private static List<List<Object>> columnsOf(StorageDescriptor descriptor) {
        List<List<Object>> columns = new ArrayList<>();
        if (descriptor.getColumns() != null) {
            for (Column column : descriptor.getColumns()) {
                columns.add(Arrays.asList(column.getName(), column.getType(), column.getComment(),
                        column.getParameters()));
            }
        }
        return columns;
    }
}
