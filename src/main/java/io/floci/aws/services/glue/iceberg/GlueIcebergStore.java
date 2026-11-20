package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.glue.GlueTableResolver;
import io.floci.aws.services.glue.model.Database;
import io.floci.aws.services.glue.model.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The account's default Glue Data Catalog as an Iceberg catalog: a namespace is a Glue database, whose {@code location}
 * and {@code comment} properties are its {@code LocationUri} and {@code Description}; a table is a Glue table whose
 * {@code table_type} is {@code ICEBERG} and whose {@code metadata_location} names its current metadata file. A commit
 * moves {@code metadata_location}, refused when the Glue table's version changed since its base.
 */
final class GlueIcebergStore implements IcebergCatalogStore {

    private static final String PARAM_PREVIOUS_METADATA_LOCATION = "previous_metadata_location";

    private final GlueService glue;

    GlueIcebergStore(GlueService glue) {
        this.glue = glue;
    }

    @Override
    public boolean managesLocations() {
        return false;
    }

    @Override
    public List<String> namespaces() {
        return glue.getDatabases().stream().map(Database::getName).toList();
    }

    @Override
    public Map<String, String> namespaceProperties(String namespace) {
        return properties(requireDatabase(namespace));
    }

    @Override
    public void createNamespace(String namespace, Map<String, String> properties) {
        Database database = new Database(namespace);
        Map<String, String> parameters = new LinkedHashMap<>();
        properties.forEach((key, value) -> {
            switch (key) {
                case "location" -> database.setLocationUri(value);
                case "comment" -> database.setDescription(value);
                default -> parameters.put(key, value);
            }
        });
        database.setParameters(parameters);
        try {
            glue.createDatabase(database);
        } catch (AwsException e) {
            throw translated(e, namespace, null);
        }
    }

    @Override
    public PropertyChanges updateNamespaceProperties(String namespace, List<String> removals,
                                                     Map<String, String> updates) {
        Database database = requireDatabase(namespace);
        Database updated = new Database(database.getName());
        updated.setDescription(database.getDescription());
        updated.setLocationUri(database.getLocationUri());
        Map<String, String> parameters = new LinkedHashMap<>(database.getParameters() == null ? Map.of()
                : database.getParameters());
        List<String> removed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : removals) {
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
        updates.forEach((key, value) -> {
            switch (key) {
                case "location" -> updated.setLocationUri(value);
                case "comment" -> updated.setDescription(value);
                default -> parameters.put(key, value);
            }
        });
        updated.setParameters(parameters);
        glue.updateDatabase(database.getName(), updated);
        return new PropertyChanges(removed, missing, List.copyOf(updates.keySet()));
    }

    @Override
    public void dropNamespace(String namespace) {
        Database database = requireDatabase(namespace);
        if (!glue.getTables(database.getName()).isEmpty()) {
            throw new IcebergRestException(409, "NamespaceNotEmptyException",
                    "Namespace " + database.getName() + " is not empty");
        }
        glue.deleteDatabase(database.getName());
    }

    @Override
    public List<String> tables(String namespace) {
        Database database = requireDatabase(namespace);
        return glue.getTables(database.getName()).stream()
                .filter(GlueTableResolver::isIcebergTable)
                .map(Table::getName)
                .toList();
    }

    @Override
    public Optional<StoredTable> table(String namespace, String name) {
        Table table;
        try {
            table = glue.getTable(namespace, name);
        } catch (AwsException e) {
            if ("EntityNotFoundException".equals(e.getErrorCode())) {
                return Optional.empty();
            }
            throw e;
        }
        boolean iceberg = GlueTableResolver.isIcebergTable(table);
        return Optional.of(new StoredTable(iceberg ? GlueTableResolver.icebergMetadataLocation(table) : null,
                table.getVersionId(), null, iceberg));
    }

    @Override
    public String locationForNewTable(String namespace, String name, String requestedLocation) {
        if (requestedLocation != null) {
            return IcebergGlueTables.stripTrailingSlash(requestedLocation);
        }
        Database database = requireDatabase(namespace);
        if (database.getLocationUri() == null || database.getLocationUri().isBlank()) {
            throw IcebergRestException.badRequest("Table " + database.getName() + "." + name
                    + " needs a location: the request names none and namespace " + database.getName()
                    + " has no location property");
        }
        return IcebergGlueTables.stripTrailingSlash(database.getLocationUri()) + "/" + name;
    }

    @Override
    public void create(String namespace, String name, ObjectNode metadata, String metadataLocation,
                       StoredTable reserved) {
        try {
            glue.createTable(namespace, icebergTable(name, metadata, metadataLocation, null));
        } catch (AwsException e) {
            throw translated(e, namespace, null);
        }
    }

    @Override
    public void commit(String namespace, String name, StoredTable base, ObjectNode metadata, String metadataLocation) {
        Table existing;
        try {
            existing = glue.getTable(namespace, name);
        } catch (AwsException e) {
            throw translated(e, namespace, name);
        }
        Table updated = icebergTable(existing.getName(), metadata, metadataLocation, existing);
        updated.getParameters().put(PARAM_PREVIOUS_METADATA_LOCATION, base.metadataLocation());
        try {
            glue.updateTable(namespace, updated, base.token(), false);
        } catch (AwsException e) {
            if ("ConcurrentModificationException".equals(e.getErrorCode())) {
                throw new IcebergRestException(409, "CommitFailedException",
                        "Table " + namespace + "." + name + " was changed concurrently");
            }
            throw translated(e, namespace, name);
        }
    }

    @Override
    public void drop(String namespace, String name) {
        glue.deleteTable(namespace, name);
    }

    private Database requireDatabase(String name) {
        try {
            return glue.getDatabase(name);
        } catch (AwsException e) {
            if ("EntityNotFoundException".equals(e.getErrorCode())) {
                throw IcebergRestException.noSuchNamespace(name);
            }
            throw e;
        }
    }

    private static Map<String, String> properties(Database database) {
        Map<String, String> properties = new LinkedHashMap<>();
        if (database.getParameters() != null) {
            properties.putAll(database.getParameters());
        }
        if (database.getLocationUri() != null) {
            properties.put("location", database.getLocationUri());
        }
        if (database.getDescription() != null) {
            properties.put("comment", database.getDescription());
        }
        return properties;
    }

    /**
     * The Glue table holding an Iceberg table, as {@link IcebergGlueTables#glueTable} builds it; what {@code existing}
     * holds beyond that (a description, an owner, other parameters) carries over.
     */
    private static Table icebergTable(String name, ObjectNode metadata, String metadataLocation, Table existing) {
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

    private static IcebergRestException translated(AwsException e, String database, String table) {
        return switch (e.getErrorCode()) {
            case "AlreadyExistsException" -> IcebergRestException.alreadyExists(e.getMessage());
            case "EntityNotFoundException" -> table == null ? IcebergRestException.noSuchNamespace(database)
                    : IcebergRestException.noSuchTable(database, table);
            default -> IcebergRestException.badRequest(e.getMessage());
        };
    }
}
