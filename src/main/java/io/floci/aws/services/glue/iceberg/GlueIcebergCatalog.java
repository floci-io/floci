package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.glue.GlueCatalogService;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3tables.S3TablesService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Iceberg REST catalog AWS Glue serves at {@code /iceberg}. The catalog a request names decides where its
 * namespaces and tables live ({@link IcebergCatalogStore}): the caller's default catalog ({@code :} or its account id)
 * is Floci's Glue Data Catalog, whose tables are Glue tables with {@code table_type} {@code ICEBERG}; a table bucket
 * federated from Amazon S3 Tables ({@code s3tablescatalog:<bucket>}, or {@code <account>:s3tablescatalog:<bucket>}, the
 * colons standing for the slashes of its Glue catalog id) keeps them in S3 Tables. A commit checks its requirements,
 * applies its updates, writes the next metadata file beside the last one, and has the store move the table's current
 * metadata file to it, refused when the table changed since the commit's base was read; so Spark's {@code GlueCatalog},
 * Athena and S3 Tables readers see each commit.
 *
 * <p>The operations are the twelve AWS lists for the endpoint; Glue supports single-level namespaces only.
 */
@ApplicationScoped
public class GlueIcebergCatalog {

    private static final Logger LOG = Logger.getLogger(GlueIcebergCatalog.class);

    static final String DEFAULT_CATALOG = ":";
    private static final Pattern METADATA_VERSION = Pattern.compile("^(\\d+)-.*\\.metadata\\.json$");
    private static final Pattern ACCOUNT_QUALIFIER = Pattern.compile("^(\\d{12}):(.+)$");
    /** The REST routes the endpoint answers, as {@code GetConfig}'s {@code endpoints} names them. */
    private static final List<String> ENDPOINTS = List.of(
            "GET /v1/{prefix}/namespaces",
            "POST /v1/{prefix}/namespaces",
            "GET /v1/{prefix}/namespaces/{namespace}",
            "POST /v1/{prefix}/namespaces/{namespace}/properties",
            "DELETE /v1/{prefix}/namespaces/{namespace}",
            "GET /v1/{prefix}/namespaces/{namespace}/tables",
            "POST /v1/{prefix}/namespaces/{namespace}/tables",
            "GET /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "HEAD /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "POST /v1/{prefix}/namespaces/{namespace}/tables/{table}",
            "DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}");

    private final GlueIcebergStore glueStore;
    private final GlueCatalogService catalogs;
    private final S3TablesService s3Tables;
    private final S3Service s3;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Inject
    public GlueIcebergCatalog(GlueService glue, S3Service s3, RegionResolver regionResolver, ObjectMapper mapper,
                              GlueCatalogService catalogs, S3TablesService s3Tables) {
        this(glue, s3, regionResolver, mapper, catalogs, s3Tables, Clock.systemUTC());
    }

    GlueIcebergCatalog(GlueService glue, S3Service s3, RegionResolver regionResolver, ObjectMapper mapper,
                       GlueCatalogService catalogs, S3TablesService s3Tables, Clock clock) {
        this.glueStore = new GlueIcebergStore(glue);
        this.catalogs = catalogs;
        this.s3Tables = s3Tables;
        this.s3 = s3;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ─── GetConfig ────────────────────────────────────────────────────────────────

    /**
     * The catalog's configuration: the path prefix of the catalog {@code warehouse} names (the default catalog when it
     * names none, or a federated S3 Tables catalog such as {@code <account>:s3tablescatalog/<bucket>}), and the routes
     * served.
     */
    ObjectNode config(String warehouse) {
        String catalog = warehouse == null || warehouse.isBlank() ? DEFAULT_CATALOG : warehouse;
        String prefix;
        if (isDefault(catalog)) {
            prefix = catalog;
        } else {
            prefix = federatedBucket(catalog).map(bucket -> bucket.catalogId().replace('/', ':'))
                    .orElseThrow(() -> unsupported(catalog));
        }
        ObjectNode config = mapper.createObjectNode();
        config.putObject("defaults");
        config.putObject("overrides").put("prefix", "catalogs/" + prefix);
        ArrayNode endpoints = config.putArray("endpoints");
        ENDPOINTS.forEach(endpoints::add);
        return config;
    }

    // ─── Namespaces ───────────────────────────────────────────────────────────────

    ObjectNode listNamespaces(String catalog, String parent) {
        IcebergCatalogStore store = store(catalog);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode namespaces = result.putArray("namespaces");
        if (parent != null && !parent.isBlank()) {
            // Glue namespaces have one level, so nothing sits beneath one.
            store.namespaceProperties(namespace(parent));
            return result;
        }
        for (String namespace : store.namespaces()) {
            namespaces.addArray().add(namespace);
        }
        return result;
    }

    public ObjectNode createNamespace(String catalog, JsonNode request) {
        IcebergCatalogStore store = store(catalog);
        JsonNode levels = request.path("namespace");
        if (!levels.isArray() || levels.size() != 1) {
            throw IcebergRestException.badRequest("AWS Glue supports single-level namespaces only");
        }
        String namespace = levels.get(0).asText();
        store.createNamespace(namespace, strings(request.path("properties")));
        ObjectNode result = mapper.createObjectNode();
        result.putArray("namespace").add(namespace);
        result.set("properties", mapper.valueToTree(store.namespaceProperties(namespace)));
        return result;
    }

    public ObjectNode loadNamespace(String catalog, String namespace) {
        IcebergCatalogStore store = store(catalog);
        String name = namespace(namespace);
        Map<String, String> properties = store.namespaceProperties(name);
        ObjectNode result = mapper.createObjectNode();
        result.putArray("namespace").add(name);
        result.set("properties", mapper.valueToTree(properties));
        return result;
    }

    ObjectNode updateNamespaceProperties(String catalog, String namespace, JsonNode request) {
        IcebergCatalogStore store = store(catalog);
        List<String> removals = new ArrayList<>();
        request.path("removals").forEach(key -> removals.add(key.asText()));
        IcebergCatalogStore.PropertyChanges changes = store.updateNamespaceProperties(namespace(namespace), removals,
                strings(request.path("updates")));
        ObjectNode result = mapper.createObjectNode();
        result.set("removed", mapper.valueToTree(changes.removed()));
        result.set("missing", mapper.valueToTree(changes.missing()));
        result.set("updated", mapper.valueToTree(changes.updated()));
        return result;
    }

    void dropNamespace(String catalog, String namespace) {
        store(catalog).dropNamespace(namespace(namespace));
    }

    // ─── Tables ───────────────────────────────────────────────────────────────────

    ObjectNode listTables(String catalog, String namespace) {
        IcebergCatalogStore store = store(catalog);
        String name = namespace(namespace);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode identifiers = result.putArray("identifiers");
        for (String table : store.tables(name)) {
            ObjectNode identifier = identifiers.addObject();
            identifier.putArray("namespace").add(name);
            identifier.put("name", table);
        }
        return result;
    }

    /**
     * Creates a table from a {@code CreateTableRequest}, or, with {@code stage-create}, answers the metadata it would
     * have without committing it: the commit that follows, required to find no table ({@code assert-create}), does. A
     * store that decides where tables live (S3 Tables) holds the table, empty, from here on.
     */
    public ObjectNode createTable(String catalog, String namespace, JsonNode request) {
        IcebergCatalogStore store = store(catalog);
        String ns = namespace(namespace);
        store.namespaceProperties(ns);
        String name = request.path("name").asText();
        if (name.isBlank()) {
            throw IcebergRestException.badRequest("Table name is required");
        }
        String requested = request.hasNonNull("location") ? request.get("location").asText() : null;
        Optional<IcebergCatalogStore.StoredTable> existing = store.table(ns, name);
        boolean reserved = existing.isPresent() && store.managesLocations()
                && existing.get().metadataLocation() == null;
        if (existing.isPresent() && !reserved) {
            throw IcebergRestException.alreadyExists("Table already exists: " + ns + "." + name);
        }
        if (reserved && requested != null) {
            throw IcebergRestException.badRequest("S3 Tables decides where a table lives: a create names no location");
        }
        // Checked before a store that decides locations creates the table, so a bad request leaves nothing behind.
        ObjectNode metadata = IcebergTableMetadata.forCreate(request, requested == null ? "s3://placeholder" : requested,
                clock.millis());
        boolean reservedHere = !reserved && store.managesLocations();
        String location = reserved ? existing.get().location() : store.locationForNewTable(ns, name, requested);
        metadata.put("location", location);
        ObjectNode result = mapper.createObjectNode();
        if (request.path("stage-create").asBoolean(false)) {
            result.set("metadata", metadata);
            result.putObject("config");
            return result;
        }
        String metadataLocation = null;
        try {
            metadataLocation = writeMetadata(metadata, 0);
            store.create(ns, name, metadata, metadataLocation, reserved ? existing.get() : null);
        } catch (RuntimeException e) {
            if (metadataLocation != null) {
                deleteMetadata(metadataLocation);
            }
            if (reservedHere) {
                dropEmptyReservation(store, ns, name);
            }
            throw e;
        }
        result.put("metadata-location", metadataLocation);
        result.set("metadata", metadata);
        result.putObject("config");
        return result;
    }

    public ObjectNode loadTable(String catalog, String namespace, String name, String snapshots) {
        IcebergCatalogStore store = store(catalog);
        String ns = namespace(namespace);
        String metadataLocation = requireIcebergTable(store, ns, name).metadataLocation();
        ObjectNode metadata = readMetadata(metadataLocation);
        if ("refs".equals(snapshots)) {
            metadata.set("snapshots", referencedSnapshots(metadata));
        }
        ObjectNode result = mapper.createObjectNode();
        result.put("metadata-location", metadataLocation);
        result.set("metadata", metadata);
        result.putObject("config");
        return result;
    }

    void tableExists(String catalog, String namespace, String name) {
        IcebergCatalogStore store = store(catalog);
        requireIcebergTable(store, namespace(namespace), name);
    }

    /**
     * Applies a {@code CommitTableRequest}: checks its requirements against the table's current metadata, applies its
     * updates, writes the next metadata file and has the store make it current, refused
     * ({@code CommitFailedException}) when the table changed since. A commit requiring {@code assert-create} creates
     * the table.
     */
    public ObjectNode commitTable(String catalog, String namespace, String name, JsonNode request) {
        IcebergCatalogStore store = store(catalog);
        String ns = namespace(namespace);
        store.namespaceProperties(ns);
        Optional<IcebergCatalogStore.StoredTable> existing = store.table(ns, name);
        boolean creating = false;
        for (JsonNode requirement : request.path("requirements")) {
            creating |= "assert-create".equals(requirement.path("type").asText());
        }
        if (existing.isPresent() && !existing.get().iceberg()) {
            throw IcebergRestException.badRequest("Table " + ns + "." + name + " is not an Iceberg table");
        }
        IcebergCatalogStore.StoredTable current = existing.filter(table -> table.metadataLocation() != null)
                .orElse(null);
        if (current == null && !creating) {
            throw IcebergRestException.noSuchTable(ns, name);
        }
        ObjectNode base = current == null ? null : readMetadata(current.metadataLocation());
        IcebergTableMetadata.check(base, request.path("requirements"));
        long now = clock.millis();
        ObjectNode metadata = base == null ? IcebergTableMetadata.empty(now) : base.deepCopy();
        IcebergTableMetadata.apply(metadata, request.path("updates"), now);
        IcebergCatalogStore.StoredTable reserved = current == null ? existing.orElse(null) : null;
        boolean reservedHere = base == null && reserved == null && store.managesLocations();
        if (base == null) {
            reserved = placeNewTable(store, ns, name, metadata, reserved);
        } else if (store.managesLocations()) {
            // S3 Tables decided where the table lives; no commit moves it.
            requireManagedLocation(metadata, current.location(), ns, name);
        }
        if (base != null) {
            metadata.withArray("metadata-log").addObject()
                    .put("timestamp-ms", base.path("last-updated-ms").asLong())
                    .put("metadata-file", current.metadataLocation());
        }
        String metadataLocation = null;
        try {
            metadataLocation = writeMetadata(metadata,
                    base == null ? 0 : metadataVersion(current.metadataLocation()) + 1);
            if (base == null) {
                store.create(ns, name, metadata, metadataLocation, reserved);
            } else {
                store.commit(ns, name, current, metadata, metadataLocation);
            }
        } catch (RuntimeException e) {
            // The metadata file names a commit that did not happen.
            if (metadataLocation != null) {
                deleteMetadata(metadataLocation);
            }
            if (reservedHere) {
                dropEmptyReservation(store, ns, name);
            }
            if (e instanceof IcebergRestException rest && "AlreadyExistsException".equals(rest.type())) {
                throw new IcebergRestException(409, "CommitFailedException",
                        "Table " + ns + "." + name + " was created concurrently");
            }
            throw e;
        }
        LOG.debugv("Committed Iceberg table {0}.{1} at {2}", ns, name, metadataLocation);
        ObjectNode result = mapper.createObjectNode();
        result.put("metadata-location", metadataLocation);
        result.set("metadata", metadata);
        return result;
    }

    /**
     * Drops the table. A Glue table's metadata and data files stay where they are, as Glue's DeleteTable leaves a
     * table's storage alone whether or not the client asked for a purge; an S3 Tables table's warehouse, its data and
     * metadata, goes with it, as S3 Tables' DeleteTable deletes it.
     */
    public void dropTable(String catalog, String namespace, String name) {
        IcebergCatalogStore store = store(catalog);
        String ns = namespace(namespace);
        store.namespaceProperties(ns);
        IcebergCatalogStore.StoredTable table = store.table(ns, name)
                .orElseThrow(() -> IcebergRestException.noSuchTable(ns, name));
        if (!table.iceberg()) {
            throw IcebergRestException.noSuchTable(ns, name);
        }
        store.drop(ns, name);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    /** The store of the catalog a request's path names. */
    private IcebergCatalogStore store(String catalog) {
        if (isDefault(catalog)) {
            return glueStore;
        }
        return federatedBucket(catalog)
                .<IcebergCatalogStore>map(bucket -> new S3TablesIcebergStore(s3Tables, bucket.bucketArn(),
                        regionResolver.getRegion()))
                .orElseThrow(() -> unsupported(catalog));
    }

    private boolean isDefault(String catalog) {
        return DEFAULT_CATALOG.equals(catalog) || regionResolver.getAccountId().equals(catalog);
    }

    /**
     * The table bucket a catalog names, as a request's path does ({@code [<account>:]s3tablescatalog:<bucket>}) or a
     * warehouse does ({@code [<account>:]s3tablescatalog/<bucket>}); empty for any other catalog.
     */
    private Optional<GlueCatalogService.FederatedBucket> federatedBucket(String catalog) {
        if (catalogs == null || catalog == null) {
            return Optional.empty();
        }
        Matcher qualified = ACCOUNT_QUALIFIER.matcher(catalog);
        String account = qualified.matches() ? qualified.group(1) + ":" : "";
        String path = (qualified.matches() ? qualified.group(2) : catalog).replace(':', '/');
        try {
            return catalogs.federatedTableBucket(account + path, regionResolver.getRegion());
        } catch (AwsException e) {
            // Another account's catalog is not found here; any other failure is the caller's to see.
            if ("EntityNotFoundException".equals(e.getErrorCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static IcebergRestException unsupported(String catalog) {
        return IcebergRestException.badRequest("Catalog " + catalog + " is not supported: use the caller's default "
                + "catalog (':' or its account id) or a table bucket of its federated S3 Tables catalog "
                + "(s3tablescatalog:<bucket>)");
    }

    /**
     * Gives a table a commit creates its location: S3 Tables decides it, holding the table from here on if a staged
     * create has not already, and refuses another; elsewhere the commit's own location stands, or the namespace's
     * default. Answers the empty table S3 Tables holds, if any.
     */
    private IcebergCatalogStore.StoredTable placeNewTable(IcebergCatalogStore store, String ns, String name,
                                                          ObjectNode metadata, IcebergCatalogStore.StoredTable reserved) {
        if (!store.managesLocations()) {
            if (!metadata.hasNonNull("location")) {
                metadata.put("location", store.locationForNewTable(ns, name, null));
            }
            return reserved;
        }
        if (reserved != null) {
            requireManagedLocation(metadata, reserved.location(), ns, name);
            metadata.put("location", reserved.location());
            return reserved;
        }
        if (metadata.hasNonNull("location")) {
            // Checked before S3 Tables creates the table, so a refused commit leaves nothing behind.
            throw IcebergRestException.badRequest("S3 Tables decides where a table lives: a commit that creates "
                    + ns + "." + name + " names no location of its own; stage the create first");
        }
        metadata.put("location", store.locationForNewTable(ns, name, null));
        return store.table(ns, name).orElse(null);
    }

    /** Refuses metadata whose location is not the one S3 Tables gave the table. */
    private static void requireManagedLocation(ObjectNode metadata, String location, String ns, String name) {
        if (metadata.hasNonNull("location")
                && !IcebergGlueTables.stripTrailingSlash(metadata.get("location").asText()).equals(location)) {
            throw IcebergRestException.badRequest("S3 Tables decides where a table lives: " + ns + "." + name
                    + " lives at " + location);
        }
    }

    /**
     * Drops a table this request had S3 Tables create, once the request failed before anything was committed to it,
     * so it does not stay behind empty; a failure to is logged, since the request's error matters more.
     */
    private void dropEmptyReservation(IcebergCatalogStore store, String ns, String name) {
        try {
            if (store.table(ns, name).filter(table -> table.metadataLocation() == null).isPresent()) {
                store.drop(ns, name);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not drop the empty table {0}.{1} a failed create left: {2}", ns, name, e.getMessage());
        }
    }

    /** The namespace a path segment names, refused when it has more than one level (a {@code 0x1F} separator). */
    private static String namespace(String segment) {
        if (segment.indexOf('\u001f') >= 0) {
            throw IcebergRestException.badRequest("AWS Glue supports single-level namespaces only: " + segment);
        }
        return segment;
    }

    private static IcebergCatalogStore.StoredTable requireIcebergTable(IcebergCatalogStore store, String namespace,
                                                                       String name) {
        store.namespaceProperties(namespace);
        return store.table(namespace, name)
                .filter(table -> table.iceberg() && table.metadataLocation() != null)
                .orElseThrow(() -> IcebergRestException.noSuchTable(namespace, name));
    }

    private static Map<String, String> strings(JsonNode object) {
        Map<String, String> values = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return values;
    }

    private ObjectNode readMetadata(String metadataLocation) {
        try {
            return (ObjectNode) IcebergGlueTables.readMetadata(s3, mapper, metadataLocation);
        } catch (IllegalArgumentException e) {
            throw IcebergRestException.badRequest(e.getMessage());
        } catch (AwsException e) {
            throw IcebergRestException.badRequest("Cannot read table metadata " + metadataLocation + ": "
                    + e.getMessage());
        } catch (IOException | ClassCastException e) {
            throw IcebergRestException.badRequest("Cannot read table metadata " + metadataLocation + ": "
                    + e.getMessage());
        }
    }

    /** Writes the metadata as the table's {@code version}-th metadata file and answers its location. */
    private String writeMetadata(ObjectNode metadata, int version) {
        String fileName = String.format("%05d-%s.metadata.json", version, UUID.randomUUID());
        String metadataLocation = metadata.path("location").asText() + "/metadata/" + fileName;
        IcebergGlueTables.S3Location location = s3Location(metadataLocation);
        try {
            s3.putObject(location.bucket(), location.key(),
                    mapper.writeValueAsString(metadata).getBytes(StandardCharsets.UTF_8), "application/json", null);
        } catch (AwsException e) {
            throw IcebergRestException.badRequest("Cannot write table metadata " + metadataLocation + ": "
                    + e.getMessage());
        } catch (IOException e) {
            throw new IcebergRestException(500, "ServiceFailureException", "Cannot serialize table metadata: "
                    + e.getMessage());
        }
        return metadataLocation;
    }

    /** Removes a metadata file a failed commit wrote; a failure to is logged, since the commit's error matters more. */
    private void deleteMetadata(String metadataLocation) {
        IcebergGlueTables.S3Location location = s3Location(metadataLocation);
        try {
            s3.deleteObject(location.bucket(), location.key());
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove metadata file {0} of a failed commit: {1}", metadataLocation, e.getMessage());
        }
    }

    /** The version a metadata file's name starts with ({@code 00003-<uuid>.metadata.json}); -1 for another name. */
    static int metadataVersion(String metadataLocation) {
        String fileName = metadataLocation.substring(metadataLocation.lastIndexOf('/') + 1);
        Matcher matcher = METADATA_VERSION.matcher(fileName);
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private ArrayNode referencedSnapshots(ObjectNode metadata) {
        List<Long> referenced = new ArrayList<>();
        metadata.path("refs").forEach(ref -> referenced.add(ref.path("snapshot-id").asLong()));
        ArrayNode kept = mapper.createArrayNode();
        for (JsonNode snapshot : metadata.path("snapshots")) {
            if (referenced.contains(snapshot.path("snapshot-id").asLong())) {
                kept.add(snapshot);
            }
        }
        return kept;
    }

    private static IcebergGlueTables.S3Location s3Location(String location) {
        try {
            return IcebergGlueTables.s3Location(location);
        } catch (IllegalArgumentException e) {
            throw IcebergRestException.badRequest(e.getMessage());
        }
    }
}
