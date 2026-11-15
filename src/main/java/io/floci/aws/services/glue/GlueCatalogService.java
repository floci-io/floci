package io.floci.aws.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.RegionScopedStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.glue.iceberg.IcebergGlueTables;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.GlueCatalog;
import io.floci.aws.services.glue.model.StorageDescriptor;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3tables.S3TablesService;
import io.floci.aws.services.s3tables.model.Namespace;
import io.floci.aws.services.s3tables.model.S3Table;
import io.floci.aws.services.s3tables.model.TableBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The catalogs of the Glue Data Catalog beside an account's default one ({@code CreateCatalog}, {@code GetCatalog},
 * {@code GetCatalogs}, {@code UpdateCatalog}, {@code DeleteCatalog}), and the catalogs federated from Amazon S3
 * Tables. Integrating table buckets with AWS analytics services creates the federated catalog
 * {@code s3tablescatalog} (connection {@code aws:s3tables}); each table bucket of the account is then a child
 * catalog, {@code s3tablescatalog/<bucket>}, whose databases are the bucket's namespaces and whose tables are its
 * tables, read from S3 Tables. A catalog id may name the account in front: {@code <account>:s3tablescatalog/<bucket>}.
 */
@ApplicationScoped
public class GlueCatalogService {

    private static final Logger LOG = Logger.getLogger(GlueCatalogService.class);

    /** The connection that federates a catalog from Amazon S3 Tables. */
    static final String S3_TABLES_CONNECTION = "aws:s3tables";
    private static final Pattern ACCOUNT_ID = Pattern.compile("^\\d{12}$");
    private static final Pattern ACCOUNT_QUALIFIER = Pattern.compile("^\\d{12}:");
    private static final Pattern CATALOG_NAME = Pattern.compile("^(?!(.*[./\\\\]|aws:)).{1,64}$");
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 1000;

    private final StorageBackend<String, GlueCatalog> catalogStore;
    private final S3TablesService s3Tables;
    private final S3Service s3;
    private final RegionResolver regionResolver;
    private final ResourceGroupsTaggingService tagging;
    private final ObjectMapper mapper;

    @Inject
    public GlueCatalogService(StorageFactory storageFactory, S3TablesService s3Tables, S3Service s3,
                              RegionResolver regionResolver, ResourceGroupsTaggingService tagging,
                              ObjectMapper mapper) {
        this(new RegionScopedStorage<>(storageFactory, regionResolver, "glue", "catalogs.json",
                new TypeReference<>() {}), s3Tables, s3, regionResolver, tagging, mapper);
    }

    GlueCatalogService(StorageBackend<String, GlueCatalog> catalogStore, S3TablesService s3Tables, S3Service s3,
                       RegionResolver regionResolver, ResourceGroupsTaggingService tagging, ObjectMapper mapper) {
        this.catalogStore = catalogStore;
        this.s3Tables = s3Tables;
        this.s3 = s3;
        this.regionResolver = regionResolver;
        this.tagging = tagging;
        this.mapper = mapper;
    }

    // ─── Catalogs ─────────────────────────────────────────────────────────────────

    public synchronized void createCatalog(String name, JsonNode input, Map<String, String> tags, String region) {
        if (name == null || !CATALOG_NAME.matcher(name).matches()) {
            throw invalid("Catalog name must be 1 to 64 characters with no '.', '/' or '\\\\' and must not start "
                    + "with 'aws:': " + name);
        }
        if (ACCOUNT_ID.matcher(name).matches() || ACCOUNT_QUALIFIER.matcher(name).lookingAt()) {
            // A catalog id names the account this way, so such a name could never be read back.
            throw invalid("Catalog name must not be an account id or start with one followed by ':': " + name);
        }
        requireInput(input);
        if (catalogStore.get(name).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Catalog already exists: " + name, 400);
        }
        GlueCatalog catalog = new GlueCatalog();
        catalog.setName(name);
        catalog.setCatalogId(qualified(name));
        catalog.setResourceArn(arn(region, name));
        Instant now = Instant.now();
        catalog.setCreateTime(now);
        catalog.setUpdateTime(now);
        apply(catalog, input, region);
        catalogStore.put(name, catalog);
        if (tags != null && !tags.isEmpty()) {
            try {
                tagging.tagResources(List.of(catalog.getResourceArn()), tags, region);
            } catch (RuntimeException e) {
                catalogStore.delete(name);
                throw e;
            }
        }
        LOG.debugv("Created Glue catalog {0}", name);
    }

    public GlueCatalog getCatalog(String catalogId, String region) {
        CatalogPath path = path(catalogId);
        if (path.isDefault()) {
            throw notFound(catalogId);
        }
        GlueCatalog parent = catalogStore.get(path.name()).orElseThrow(() -> notFound(catalogId));
        if (path.child() == null) {
            return parent;
        }
        return bucketCatalog(parent, bucket(parent, path.child(), region).orElseThrow(() -> notFound(catalogId)),
                region);
    }

    /**
     * The catalogs beneath {@code parentCatalogId} (the default catalog when null): the catalogs created in the
     * account, or a federated S3 Tables catalog's table buckets; with {@code recursive}, the catalogs beneath those
     * too, and with {@code includeRoot} the parent itself first.
     */
    public GlueService.Page<GlueCatalog> getCatalogs(String parentCatalogId, boolean recursive, boolean includeRoot,
                                                     Integer maxResults, String nextToken, String region) {
        List<GlueCatalog> catalogs = new ArrayList<>();
        CatalogPath parent = path(parentCatalogId);
        if (parent.isDefault()) {
            List<GlueCatalog> created = catalogStore.scan(key -> true).stream()
                    .sorted(Comparator.comparing(GlueCatalog::getName)).toList();
            for (GlueCatalog catalog : created) {
                catalogs.add(catalog);
                if (recursive) {
                    catalogs.addAll(children(catalog, region));
                }
            }
        } else {
            GlueCatalog catalog = getCatalog(parentCatalogId, region);
            if (includeRoot) {
                catalogs.add(catalog);
            }
            if (parent.child() == null) {
                catalogs.addAll(children(catalog, region));
            }
        }
        PaginatedResult<GlueCatalog> page = Pagination.paginate(catalogs, GlueCatalog::getCatalogId, maxResults,
                nextToken, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, "InvalidInputException", "NextToken");
        return new GlueService.Page<>(page.items(), page.nextToken());
    }

    public synchronized void updateCatalog(String catalogId, JsonNode input, String region) {
        requireInput(input);
        // Applied to a copy, so a refused input leaves the stored catalog as it was.
        GlueCatalog catalog = mapper.convertValue(createdCatalog(catalogId), GlueCatalog.class);
        apply(catalog, input, region);
        catalog.setUpdateTime(Instant.now());
        catalogStore.put(catalog.getName(), catalog);
    }

    public synchronized void deleteCatalog(String catalogId, String region) {
        GlueCatalog catalog = createdCatalog(catalogId);
        catalogStore.delete(catalog.getName());
        tagging.deleteResources(List.of(catalog.getResourceArn()), region);
    }

    // ─── Databases and tables of a federated catalog ────────────────────────────

    /**
     * Whether {@code catalogId} names the account's default catalog: none at all, the account id, or the default
     * catalog of the account named in front of nothing else. A catalog that is neither the default nor a table bucket
     * of a federated S3 Tables catalog is answered as one that holds no databases.
     */
    public boolean isDefaultCatalog(String catalogId) {
        return path(catalogId).isDefault();
    }

    /**
     * The ARN of the table bucket a federated S3 Tables catalog id names ({@code [<account>:]s3tablescatalog/<bucket>}),
     * and the catalog's id as catalogs answer it; empty for any other catalog id.
     */
    public Optional<FederatedBucket> federatedTableBucket(String catalogId, String region) {
        return federated(catalogId, region).map(found -> new FederatedBucket(found.bucket().getArn(),
                found.bucket().getName(), found.catalogId()));
    }

    /** A table bucket a federated catalog names, and the catalog's qualified id. */
    public record FederatedBucket(String bucketArn, String bucketName, String catalogId) {
    }

    public GlueService.Page<Database> getDatabases(String catalogId, Integer maxResults, String nextToken,
                                                   String region) {
        Optional<Federated> federated = federated(catalogId, region);
        List<Database> databases = new ArrayList<>();
        federated.ifPresent(found -> s3Tables.listNamespaces(found.bucket().getArn(), null, region).stream()
                .sorted(Comparator.comparing(Namespace::getName))
                .forEach(namespace -> databases.add(database(found.catalogId(), namespace))));
        PaginatedResult<Database> page = Pagination.paginate(databases, Database::getName, maxResults, nextToken,
                DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, "InvalidInputException", "NextToken");
        return new GlueService.Page<>(page.items(), page.nextToken());
    }

    public Database getDatabase(String catalogId, String name, String region) {
        Federated federated = requireFederated(catalogId, name, region);
        return database(federated.catalogId(), requireNamespace(federated, name, region));
    }

    public GlueService.Page<Table> getTables(String catalogId, String databaseName, Integer maxResults,
                                             String nextToken, String region) {
        Federated federated = requireFederated(catalogId, databaseName, region);
        requireNamespace(federated, databaseName, region);
        List<S3Table> s3TableList = s3Tables.listTables(federated.bucket().getArn(), databaseName, null, region)
                .stream().sorted(Comparator.comparing(S3Table::getName)).toList();
        // Paged before any metadata file is read, so a page reads only its own tables'.
        PaginatedResult<S3Table> page = Pagination.paginate(s3TableList, S3Table::getName, maxResults, nextToken,
                DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, "InvalidInputException", "NextToken");
        return new GlueService.Page<>(page.items().stream()
                .map(table -> table(federated.catalogId(), databaseName, table)).toList(), page.nextToken());
    }

    public Table getTable(String catalogId, String databaseName, String name, String region) {
        Federated federated = requireFederated(catalogId, databaseName, region);
        requireNamespace(federated, databaseName, region);
        S3Table table;
        try {
            table = s3Tables.getTable(federated.bucket().getArn(), databaseName, name, region);
        } catch (AwsException e) {
            throw new AwsException("EntityNotFoundException", "Table " + name + " not found.", 400);
        }
        return table(federated.catalogId(), databaseName, table);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    /** A catalog id split into the catalog it names and, for a child, the child's name. */
    private record CatalogPath(String name, String child) {
        boolean isDefault() {
            return name == null;
        }
    }

    /**
     * The catalog {@code catalogId} names: {@code [<account>:]<catalog>[/<child>]}. An account other than the
     * caller's names a catalog the caller cannot see here.
     */
    private CatalogPath path(String catalogId) {
        if (catalogId == null || catalogId.isBlank()) {
            return new CatalogPath(null, null);
        }
        String id = catalogId;
        int colon = id.indexOf(':');
        if (colon > 0 && ACCOUNT_ID.matcher(id.substring(0, colon)).matches()) {
            if (!id.substring(0, colon).equals(regionResolver.getAccountId())) {
                throw notFound(catalogId);
            }
            id = id.substring(colon + 1);
        }
        if (id.isEmpty() || id.equals(regionResolver.getAccountId())) {
            return new CatalogPath(null, null);
        }
        int slash = id.indexOf('/');
        return slash < 0 ? new CatalogPath(id, null) : new CatalogPath(id.substring(0, slash), id.substring(slash + 1));
    }

    private GlueCatalog createdCatalog(String catalogId) {
        CatalogPath path = path(catalogId);
        if (path.isDefault() || path.child() != null) {
            throw invalid("Only a catalog created with CreateCatalog can be changed or deleted: " + catalogId);
        }
        return catalogStore.get(path.name()).orElseThrow(() -> notFound(catalogId));
    }

    private static boolean federatesS3Tables(GlueCatalog catalog) {
        return catalog.getFederatedCatalog() != null
                && S3_TABLES_CONNECTION.equals(catalog.getFederatedCatalog().get("ConnectionName"));
    }

    private List<GlueCatalog> children(GlueCatalog catalog, String region) {
        return federatedBuckets(catalog, region).stream()
                .sorted(Comparator.comparing(TableBucket::getName))
                .map(bucket -> bucketCatalog(catalog, bucket, region))
                .toList();
    }

    private Optional<TableBucket> bucket(GlueCatalog parent, String bucketName, String region) {
        return federatedBuckets(parent, region).stream()
                .filter(bucket -> bucket.getName().equals(bucketName))
                .findFirst();
    }

    /**
     * The table buckets a catalog federates: every bucket of the account in the Region for the identifier
     * {@code arn:aws:s3tables:<region>:<account>:bucket/*}, or the one bucket its ARN names.
     */
    private List<TableBucket> federatedBuckets(GlueCatalog catalog, String region) {
        if (!federatesS3Tables(catalog)) {
            return List.of();
        }
        String identifier = String.valueOf(catalog.getFederatedCatalog().get("Identifier"));
        return s3Tables.listTableBuckets(null, region).stream()
                .filter(bucket -> identifier.endsWith(":bucket/*")
                        ? bucket.getArn().startsWith(identifier.substring(0, identifier.length() - 1))
                        : bucket.getArn().equals(identifier))
                .toList();
    }

    /**
     * A federated S3 Tables catalog's identifier: all the account's table buckets in the Region
     * ({@code arn:aws:s3tables:<region>:<account>:bucket/*}) or one of them, by its ARN.
     */
    private void validateS3TablesIdentifier(Map<String, Object> federatedCatalog, String region) {
        Object identifier = federatedCatalog.get("Identifier");
        String prefix = AwsArnUtils.Arn.of("s3tables", region, regionResolver.getAccountId(), "bucket/").toString();
        if (!(identifier instanceof String value) || !value.startsWith(prefix) || value.length() == prefix.length()
                || (value.endsWith("*") && !value.equals(prefix + "*"))) {
            throw invalid("FederatedCatalog.Identifier for the aws:s3tables connection must be " + prefix
                    + "* or a table bucket ARN of this account and Region: " + identifier);
        }
    }

    /** A table bucket a federated catalog id names, and the catalog's id as catalogs answer it. */
    private record Federated(TableBucket bucket, String catalogId) {
    }

    /** The table bucket a federated catalog id names; empty for the default catalog or any other catalog. */
    private Optional<Federated> federated(String catalogId, String region) {
        CatalogPath path = path(catalogId);
        if (path.isDefault() || path.child() == null) {
            return Optional.empty();
        }
        return catalogStore.get(path.name()).flatMap(parent -> bucket(parent, path.child(), region))
                .map(bucket -> new Federated(bucket, qualified(path.name() + "/" + path.child())));
    }

    private Federated requireFederated(String catalogId, String databaseName, String region) {
        return federated(catalogId, region).orElseThrow(() -> new AwsException("EntityNotFoundException",
                "Database " + databaseName + " not found.", 400));
    }

    private GlueCatalog bucketCatalog(GlueCatalog parent, TableBucket bucket, String region) {
        String path = parent.getName() + "/" + bucket.getName();
        GlueCatalog catalog = new GlueCatalog();
        catalog.setName(bucket.getName());
        catalog.setCatalogId(qualified(path));
        catalog.setResourceArn(arn(region, path));
        catalog.setFederatedCatalog(Map.of("Identifier", bucket.getArn(),
                "ConnectionName", S3_TABLES_CONNECTION));
        catalog.setCreateTime(instant(bucket.getCreatedAt()));
        catalog.setUpdateTime(instant(bucket.getCreatedAt()));
        return catalog;
    }

    private Namespace requireNamespace(Federated federated, String name, String region) {
        try {
            return s3Tables.getNamespace(federated.bucket().getArn(), name, region);
        } catch (AwsException e) {
            throw new AwsException("EntityNotFoundException", "Database " + name + " not found.", 400);
        }
    }

    private static Database database(String catalogId, Namespace namespace) {
        Database database = new Database(namespace.getName());
        database.setCatalogId(catalogId);
        database.setCreateTime(instant(namespace.getCreatedAt()));
        return database;
    }

    /**
     * An S3 table as Glue shows it: an Iceberg table whose {@code metadata_location} and columns come from its current
     * metadata file, or a table with only its location while S3 Tables holds no metadata for it yet.
     */
    private Table table(String catalogId, String databaseName, S3Table s3Table) {
        String metadataLocation = s3Table.getMetadataLocation();
        Table table = null;
        if (metadataLocation != null && !metadataLocation.isBlank()) {
            try {
                table = IcebergGlueTables.glueTable(s3Table.getName(),
                        IcebergGlueTables.readMetadata(s3, mapper, metadataLocation), metadataLocation);
            } catch (IOException | RuntimeException e) {
                // One unreadable metadata file should not fail a whole listing: the table keeps its location.
                LOG.warnv("Cannot read metadata {0} of S3 table {1}; its columns are left out: {2}",
                        metadataLocation, s3Table.getName(), e.getMessage());
            }
        }
        if (table == null) {
            table = new Table();
            table.setName(s3Table.getName());
            table.setTableType("EXTERNAL_TABLE");
            Map<String, String> parameters = new HashMap<>(Map.of("table_type", "ICEBERG"));
            if (metadataLocation != null && !metadataLocation.isBlank()) {
                parameters.put("metadata_location", metadataLocation);
            }
            table.setParameters(parameters);
            StorageDescriptor descriptor = new StorageDescriptor();
            descriptor.setLocation(s3Table.getWarehouseLocation());
            table.setStorageDescriptor(descriptor);
        }
        table.setDatabaseName(databaseName);
        table.setCatalogId(catalogId);
        table.setCreateTime(instant(s3Table.getCreatedAt()));
        table.setUpdateTime(instant(s3Table.getModifiedAt()));
        return table;
    }

    private static void requireInput(JsonNode input) {
        if (input == null || input.isNull() || !input.isObject()) {
            throw invalid("CatalogInput is required.");
        }
    }

    /** Applies a {@code CatalogInput}, after checking all of it, so a refused one changes nothing. */
    private void apply(GlueCatalog catalog, JsonNode input, String region) {
        String allowFullTableExternalDataAccess = null;
        if (input.has("AllowFullTableExternalDataAccess")) {
            allowFullTableExternalDataAccess = input.get("AllowFullTableExternalDataAccess").asText();
            if (!allowFullTableExternalDataAccess.equals("True") && !allowFullTableExternalDataAccess.equals("False")) {
                throw invalid("AllowFullTableExternalDataAccess must be True or False: "
                        + allowFullTableExternalDataAccess);
            }
        }
        Map<String, Object> federatedCatalog = input.has("FederatedCatalog")
                ? mapper.convertValue(input.get("FederatedCatalog"), new TypeReference<>() {})
                : catalog.getFederatedCatalog();
        if (federatedCatalog != null && S3_TABLES_CONNECTION.equals(federatedCatalog.get("ConnectionName"))) {
            validateS3TablesIdentifier(federatedCatalog, region);
        }
        if (allowFullTableExternalDataAccess != null) {
            catalog.setAllowFullTableExternalDataAccess(allowFullTableExternalDataAccess);
        }
        if (input.has("Description")) {
            catalog.setDescription(input.get("Description").asText(null));
        }
        if (input.has("Parameters")) {
            catalog.setParameters(mapper.convertValue(input.get("Parameters"), new TypeReference<>() {}));
        }
        catalog.setFederatedCatalog(federatedCatalog);
        if (input.has("TargetRedshiftCatalog")) {
            catalog.setTargetRedshiftCatalog(mapper.convertValue(input.get("TargetRedshiftCatalog"),
                    new TypeReference<>() {}));
        }
        if (input.has("CatalogProperties")) {
            catalog.setCatalogProperties(mapper.convertValue(input.get("CatalogProperties"), new TypeReference<>() {}));
        }
        if (input.has("CreateTableDefaultPermissions")) {
            catalog.setCreateTableDefaultPermissions(mapper.convertValue(input.get("CreateTableDefaultPermissions"),
                    new TypeReference<>() {}));
        }
        if (input.has("CreateDatabaseDefaultPermissions")) {
            catalog.setCreateDatabaseDefaultPermissions(mapper.convertValue(
                    input.get("CreateDatabaseDefaultPermissions"), new TypeReference<>() {}));
        }
    }

    private String qualified(String path) {
        return regionResolver.getAccountId() + ":" + path;
    }

    private String arn(String region, String path) {
        return AwsArnUtils.Arn.of("glue", region, regionResolver.getAccountId(), "catalog/" + path).toString();
    }

    private static Instant instant(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static AwsException notFound(String catalogId) {
        return new AwsException("EntityNotFoundException", "Catalog not found: " + catalogId, 400);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidInputException", message, 400);
    }
}
