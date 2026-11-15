package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.GlueTableResolver;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.Table;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Iceberg REST catalog AWS Glue serves at {@code /iceberg}, over Floci's Glue Data Catalog: a namespace is a Glue
 * database, a table a Glue table whose {@code table_type} is {@code ICEBERG} and whose {@code metadata_location}
 * names its current metadata file in S3. A commit writes the next metadata file beside the last one and moves
 * {@code metadata_location} to it, refused when the Glue table changed since the commit's base was read, so Spark's
 * {@code GlueCatalog}, Athena and any other reader of the Glue table see each commit.
 *
 * <p>The operations are the twelve AWS lists for the endpoint. The catalog in the path is the caller's default
 * catalog, named {@code :} or by the caller's account id; Glue supports single-level namespaces only.
 */
@ApplicationScoped
public class GlueIcebergCatalog {

    private static final Logger LOG = Logger.getLogger(GlueIcebergCatalog.class);

    static final String DEFAULT_CATALOG = ":";
    private static final String PARAM_TABLE_TYPE = IcebergGlueTables.PARAM_TABLE_TYPE;
    private static final String PARAM_METADATA_LOCATION = IcebergGlueTables.PARAM_METADATA_LOCATION;
    private static final String PARAM_PREVIOUS_METADATA_LOCATION = "previous_metadata_location";
    private static final String ICEBERG = IcebergGlueTables.ICEBERG;
    private static final Pattern METADATA_VERSION = Pattern.compile("^(\\d+)-.*\\.metadata\\.json$");
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

    private final GlueService glue;
    private final S3Service s3;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Inject
    public GlueIcebergCatalog(GlueService glue, S3Service s3, RegionResolver regionResolver, ObjectMapper mapper) {
        this(glue, s3, regionResolver, mapper, Clock.systemUTC());
    }

    GlueIcebergCatalog(GlueService glue, S3Service s3, RegionResolver regionResolver, ObjectMapper mapper,
                       Clock clock) {
        this.glue = glue;
        this.s3 = s3;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ─── GetConfig ────────────────────────────────────────────────────────────────

    /** The catalog's configuration: the path prefix of the catalog {@code warehouse} names, and the routes served. */
    ObjectNode config(String warehouse) {
        String catalog = warehouse == null || warehouse.isBlank() ? DEFAULT_CATALOG : warehouse;
        requireDefaultCatalog(catalog);
        ObjectNode config = mapper.createObjectNode();
        config.putObject("defaults");
        config.putObject("overrides").put("prefix", "catalogs/" + catalog);
        ArrayNode endpoints = config.putArray("endpoints");
        ENDPOINTS.forEach(endpoints::add);
        return config;
    }

    // ─── Namespaces ───────────────────────────────────────────────────────────────

    ObjectNode listNamespaces(String catalog, String parent) {
        requireDefaultCatalog(catalog);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode namespaces = result.putArray("namespaces");
        if (parent != null && !parent.isBlank()) {
            // Glue namespaces have one level, so nothing sits beneath one.
            requireNamespace(namespace(parent));
            return result;
        }
        for (Database database : glue.getDatabases()) {
            namespaces.addArray().add(database.getName());
        }
        return result;
    }

    ObjectNode createNamespace(String catalog, JsonNode request) {
        requireDefaultCatalog(catalog);
        JsonNode levels = request.path("namespace");
        if (!levels.isArray() || levels.size() != 1) {
            throw IcebergRestException.badRequest("AWS Glue supports single-level namespaces only");
        }
        Database database = new Database(levels.get(0).asText());
        Map<String, String> parameters = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> properties = request.path("properties").fields();
        while (properties.hasNext()) {
            Map.Entry<String, JsonNode> property = properties.next();
            switch (property.getKey()) {
                case "location" -> database.setLocationUri(property.getValue().asText());
                case "comment" -> database.setDescription(property.getValue().asText());
                default -> parameters.put(property.getKey(), property.getValue().asText());
            }
        }
        database.setParameters(parameters);
        try {
            glue.createDatabase(database);
        } catch (AwsException e) {
            throw translated(e, database.getName(), null);
        }
        ObjectNode result = mapper.createObjectNode();
        result.putArray("namespace").add(database.getName());
        result.set("properties", properties(requireNamespace(database.getName())));
        return result;
    }

    ObjectNode loadNamespace(String catalog, String namespace) {
        requireDefaultCatalog(catalog);
        Database database = requireNamespace(namespace(namespace));
        ObjectNode result = mapper.createObjectNode();
        result.putArray("namespace").add(database.getName());
        result.set("properties", properties(database));
        return result;
    }

    ObjectNode updateNamespaceProperties(String catalog, String namespace, JsonNode request) {
        requireDefaultCatalog(catalog);
        Database database = requireNamespace(namespace(namespace));
        Database updated = new Database(database.getName());
        updated.setDescription(database.getDescription());
        updated.setLocationUri(database.getLocationUri());
        Map<String, String> parameters = new LinkedHashMap<>(database.getParameters() == null ? Map.of()
                : database.getParameters());
        ObjectNode result = mapper.createObjectNode();
        ArrayNode removed = result.putArray("removed");
        ArrayNode missing = result.putArray("missing");
        for (JsonNode key : request.path("removals")) {
            String name = key.asText();
            boolean present = switch (name) {
                case "location" -> updated.getLocationUri() != null;
                case "comment" -> updated.getDescription() != null;
                default -> parameters.containsKey(name);
            };
            if (!present) {
                missing.add(name);
                continue;
            }
            switch (name) {
                case "location" -> updated.setLocationUri(null);
                case "comment" -> updated.setDescription(null);
                default -> parameters.remove(name);
            }
            removed.add(name);
        }
        ArrayNode updatedKeys = result.putArray("updated");
        Iterator<Map.Entry<String, JsonNode>> updates = request.path("updates").fields();
        while (updates.hasNext()) {
            Map.Entry<String, JsonNode> update = updates.next();
            switch (update.getKey()) {
                case "location" -> updated.setLocationUri(update.getValue().asText());
                case "comment" -> updated.setDescription(update.getValue().asText());
                default -> parameters.put(update.getKey(), update.getValue().asText());
            }
            updatedKeys.add(update.getKey());
        }
        updated.setParameters(parameters);
        glue.updateDatabase(database.getName(), updated);
        return result;
    }

    void dropNamespace(String catalog, String namespace) {
        requireDefaultCatalog(catalog);
        Database database = requireNamespace(namespace(namespace));
        if (!glue.getTables(database.getName()).isEmpty()) {
            throw new IcebergRestException(409, "NamespaceNotEmptyException",
                    "Namespace " + database.getName() + " is not empty");
        }
        glue.deleteDatabase(database.getName());
    }

    // ─── Tables ───────────────────────────────────────────────────────────────────

    ObjectNode listTables(String catalog, String namespace) {
        requireDefaultCatalog(catalog);
        Database database = requireNamespace(namespace(namespace));
        ObjectNode result = mapper.createObjectNode();
        ArrayNode identifiers = result.putArray("identifiers");
        for (Table table : glue.getTables(database.getName())) {
            if (GlueTableResolver.isIcebergTable(table)) {
                ObjectNode identifier = identifiers.addObject();
                identifier.putArray("namespace").add(database.getName());
                identifier.put("name", table.getName());
            }
        }
        return result;
    }

    /**
     * Creates a table from a {@code CreateTableRequest}, or, with {@code stage-create}, answers the metadata it would
     * have without creating it: the commit that follows, required to find no table ({@code assert-create}), does.
     */
    public ObjectNode createTable(String catalog, String namespace, JsonNode request) {
        requireDefaultCatalog(catalog);
        Database database = requireNamespace(namespace(namespace));
        String name = request.path("name").asText();
        if (name.isBlank()) {
            throw IcebergRestException.badRequest("Table name is required");
        }
        if (findTable(database.getName(), name) != null) {
            throw IcebergRestException.alreadyExists("Table already exists: " + database.getName() + "." + name);
        }
        String location = request.hasNonNull("location") ? stripTrailingSlash(request.get("location").asText())
                : defaultLocation(database, name);
        ObjectNode metadata = IcebergTableMetadata.forCreate(request, location, clock.millis());
        ObjectNode result = mapper.createObjectNode();
        if (request.path("stage-create").asBoolean(false)) {
            result.set("metadata", metadata);
            result.putObject("config");
            return result;
        }
        String metadataLocation = writeMetadata(metadata, 0);
        try {
            createGlueTable(database.getName(), name, metadata, metadataLocation);
        } catch (RuntimeException e) {
            deleteMetadata(metadataLocation);
            throw e;
        }
        result.put("metadata-location", metadataLocation);
        result.set("metadata", metadata);
        result.putObject("config");
        return result;
    }

    public ObjectNode loadTable(String catalog, String namespace, String name, String snapshots) {
        requireDefaultCatalog(catalog);
        Database database = requireNamespace(namespace(namespace));
        Table table = requireIcebergTable(database.getName(), name);
        String metadataLocation = GlueTableResolver.icebergMetadataLocation(table);
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
        requireDefaultCatalog(catalog);
        Database database = requireNamespace(namespace(namespace));
        requireIcebergTable(database.getName(), name);
    }

    /**
     * Applies a {@code CommitTableRequest}: checks its requirements against the table's current metadata, applies its
     * updates, writes the next metadata file and moves the Glue table's {@code metadata_location} to it, refused
     * ({@code CommitFailedException}) when the Glue table changed since. A commit requiring {@code assert-create}
     * creates the table.
     */
    public ObjectNode commitTable(String catalog, String namespace, String name, JsonNode request) {
        requireDefaultCatalog(catalog);
        Database database = requireNamespace(namespace(namespace));
        Table existing = findTable(database.getName(), name);
        boolean creating = false;
        for (JsonNode requirement : request.path("requirements")) {
            creating |= "assert-create".equals(requirement.path("type").asText());
        }
        if (existing == null && !creating) {
            throw IcebergRestException.noSuchTable(database.getName(), name);
        }
        if (existing != null && !GlueTableResolver.isIcebergTable(existing)) {
            throw IcebergRestException.badRequest("Table " + database.getName() + "." + name
                    + " is not an Iceberg table");
        }
        String baseLocation = existing == null ? null : GlueTableResolver.icebergMetadataLocation(existing);
        ObjectNode base = existing == null ? null : readMetadata(baseLocation);
        IcebergTableMetadata.check(base, request.path("requirements"));
        long now = clock.millis();
        ObjectNode metadata = base == null ? IcebergTableMetadata.empty(now) : base.deepCopy();
        IcebergTableMetadata.apply(metadata, request.path("updates"), now);
        if (!metadata.hasNonNull("location")) {
            metadata.put("location", defaultLocation(database, name));
        }
        if (base != null) {
            metadata.withArray("metadata-log").addObject()
                    .put("timestamp-ms", base.path("last-updated-ms").asLong())
                    .put("metadata-file", baseLocation);
        }
        String metadataLocation = writeMetadata(metadata, base == null ? 0 : metadataVersion(baseLocation) + 1);
        try {
            if (existing == null) {
                glue.createTable(database.getName(), icebergTable(name, metadata, metadataLocation, null));
            } else {
                Table updated = icebergTable(existing.getName(), metadata, metadataLocation, existing);
                updated.getParameters().put(PARAM_PREVIOUS_METADATA_LOCATION, baseLocation);
                glue.updateTable(database.getName(), updated, existing.getVersionId(), false);
            }
        } catch (AwsException e) {
            // The metadata file names a commit that did not happen.
            deleteMetadata(metadataLocation);
            if ("AlreadyExistsException".equals(e.getErrorCode())) {
                throw new IcebergRestException(409, "CommitFailedException",
                        "Table " + database.getName() + "." + name + " was created concurrently");
            }
            if ("ConcurrentModificationException".equals(e.getErrorCode())) {
                throw new IcebergRestException(409, "CommitFailedException",
                        "Table " + database.getName() + "." + name + " was changed concurrently");
            }
            // A create can only miss its namespace, dropped since it was looked up.
            throw translated(e, database.getName(), existing == null ? null : name);
        } catch (RuntimeException e) {
            deleteMetadata(metadataLocation);
            throw e;
        }
        LOG.debugv("Committed Iceberg table {0}.{1} at {2}", database.getName(), name, metadataLocation);
        ObjectNode result = mapper.createObjectNode();
        result.put("metadata-location", metadataLocation);
        result.set("metadata", metadata);
        return result;
    }

    /**
     * Drops the Glue table. Its metadata and data files stay where they are, as Glue's DeleteTable leaves a table's
     * storage alone, whether or not the client asked for a purge.
     */
    void dropTable(String catalog, String namespace, String name) {
        requireDefaultCatalog(catalog);
        Database database = requireNamespace(namespace(namespace));
        requireIcebergTable(database.getName(), name);
        glue.deleteTable(database.getName(), name);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private void requireDefaultCatalog(String catalog) {
        if (!DEFAULT_CATALOG.equals(catalog) && !regionResolver.getAccountId().equals(catalog)) {
            throw IcebergRestException.badRequest("Catalog " + catalog
                    + " is not supported: use the caller's default catalog (':' or its account id)");
        }
    }

    /** The namespace a path segment names, refused when it has more than one level (a {@code 0x1F} separator). */
    private static String namespace(String segment) {
        if (segment.indexOf('\u001f') >= 0) {
            throw IcebergRestException.badRequest("AWS Glue supports single-level namespaces only: " + segment);
        }
        return segment;
    }

    private Database requireNamespace(String name) {
        try {
            return glue.getDatabase(name);
        } catch (AwsException e) {
            if ("EntityNotFoundException".equals(e.getErrorCode())) {
                throw IcebergRestException.noSuchNamespace(name);
            }
            throw e;
        }
    }

    private Table findTable(String database, String name) {
        try {
            return glue.getTable(database, name);
        } catch (AwsException e) {
            if ("EntityNotFoundException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private Table requireIcebergTable(String database, String name) {
        Table table = findTable(database, name);
        if (table == null || !GlueTableResolver.isIcebergTable(table)) {
            throw IcebergRestException.noSuchTable(database, name);
        }
        return table;
    }

    private ObjectNode properties(Database database) {
        ObjectNode properties = mapper.createObjectNode();
        if (database.getParameters() != null) {
            database.getParameters().forEach(properties::put);
        }
        if (database.getLocationUri() != null) {
            properties.put("location", database.getLocationUri());
        }
        if (database.getDescription() != null) {
            properties.put("comment", database.getDescription());
        }
        return properties;
    }

    private static String defaultLocation(Database database, String table) {
        if (database.getLocationUri() == null || database.getLocationUri().isBlank()) {
            throw IcebergRestException.badRequest("Table " + database.getName() + "." + table
                    + " needs a location: the request names none and namespace " + database.getName()
                    + " has no location property");
        }
        return stripTrailingSlash(database.getLocationUri()) + "/" + table;
    }

    private static String stripTrailingSlash(String location) {
        return location.endsWith("/") ? location.substring(0, location.length() - 1) : location;
    }

    private void createGlueTable(String database, String name, ObjectNode metadata, String metadataLocation) {
        try {
            glue.createTable(database, icebergTable(name, metadata, metadataLocation, null));
        } catch (AwsException e) {
            throw translated(e, database, name);
        }
    }

    /**
     * The Glue table holding an Iceberg table: {@code table_type} and {@code metadata_location} in its parameters,
     * its location and its current schema's columns in its storage descriptor, as AWS Glue keeps one. What
     * {@code existing} holds beyond those (a description, other parameters) carries over.
     */
    private Table icebergTable(String name, ObjectNode metadata, String metadataLocation, Table existing) {
        Table table = IcebergGlueTables.glueTable(name, metadata, metadataLocation);
        if (existing != null) {
            table.setDescription(existing.getDescription());
            table.setOwner(existing.getOwner());
            if (existing.getParameters() != null) {
                Map<String, String> parameters = new HashMap<>(existing.getParameters());
                parameters.putAll(table.getParameters());
                table.setParameters(parameters);
            }
        }
        return table;
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

    private static IcebergRestException translated(AwsException e, String database, String table) {
        return switch (e.getErrorCode()) {
            case "AlreadyExistsException" -> IcebergRestException.alreadyExists(e.getMessage());
            case "EntityNotFoundException" -> table == null ? IcebergRestException.noSuchNamespace(database)
                    : IcebergRestException.noSuchTable(database, table);
            default -> IcebergRestException.badRequest(e.getMessage());
        };
    }
}
