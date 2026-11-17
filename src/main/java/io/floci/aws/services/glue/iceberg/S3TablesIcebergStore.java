package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.s3tables.S3TablesService;
import io.floci.aws.services.s3tables.model.Namespace;
import io.floci.aws.services.s3tables.model.S3Table;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A table bucket federated from Amazon S3 Tables ({@code s3tablescatalog/<bucket>}) as an Iceberg catalog: a namespace
 * is one of the bucket's namespaces, which take no properties, and a table one of its tables. S3 Tables decides where
 * a table lives (its warehouse location) when the table is created, and moves the table's metadata location with
 * {@code UpdateTableMetadataLocation}, refused when the version token is no longer the table's. A table S3 Tables holds
 * but nothing has committed to yet, as a staged create leaves it, has no metadata location.
 */
final class S3TablesIcebergStore implements IcebergCatalogStore {

    private static final String ICEBERG = "ICEBERG";
    private static final String SPARK_OWNER_PROPERTY = "owner";

    private final S3TablesService s3Tables;
    private final String bucketArn;
    private final String region;

    S3TablesIcebergStore(S3TablesService s3Tables, String bucketArn, String region) {
        this.s3Tables = s3Tables;
        this.bucketArn = bucketArn;
        this.region = region;
    }

    @Override
    public boolean managesLocations() {
        return true;
    }

    @Override
    public List<String> namespaces() {
        return s3Tables.listNamespaces(bucketArn, null, region).stream().map(Namespace::getName).toList();
    }

    @Override
    public Map<String, String> namespaceProperties(String namespace) {
        requireNamespace(namespace);
        return Map.of();
    }

    /**
     * Creates a namespace. S3 Tables namespaces store no properties: the {@code owner} Spark adds to every
     * {@code CREATE NAMESPACE} is let go, any other property refused rather than silently lost.
     */
    @Override
    public void createNamespace(String namespace, Map<String, String> properties) {
        if (!properties.keySet().stream().allMatch(SPARK_OWNER_PROPERTY::equals)) {
            throw IcebergRestException.badRequest("S3 Tables namespaces take no properties");
        }
        try {
            s3Tables.createNamespace(bucketArn, List.of(namespace), region);
        } catch (AwsException e) {
            if ("ConflictException".equals(e.getErrorCode())) {
                throw IcebergRestException.alreadyExists("Namespace already exists: " + namespace);
            }
            throw IcebergRestException.badRequest(e.getMessage());
        }
    }

    @Override
    public PropertyChanges updateNamespaceProperties(String namespace, List<String> removals,
                                                     Map<String, String> updates) {
        requireNamespace(namespace);
        if (!updates.isEmpty()) {
            throw IcebergRestException.badRequest("S3 Tables namespaces take no properties");
        }
        return new PropertyChanges(List.of(), List.copyOf(removals), List.of());
    }

    @Override
    public void dropNamespace(String namespace) {
        requireNamespace(namespace);
        try {
            s3Tables.deleteNamespace(bucketArn, namespace, region);
        } catch (AwsException e) {
            if ("ConflictException".equals(e.getErrorCode())) {
                throw new IcebergRestException(409, "NamespaceNotEmptyException",
                        "Namespace " + namespace + " is not empty");
            }
            throw IcebergRestException.badRequest(e.getMessage());
        }
    }

    /** The namespace's tables that hold Iceberg metadata; one a staged create holds, empty, is not loadable yet. */
    @Override
    public List<String> tables(String namespace) {
        requireNamespace(namespace);
        return s3Tables.listTables(bucketArn, namespace, null, region).stream()
                .filter(table -> table.getMetadataLocation() != null && !table.getMetadataLocation().isBlank())
                .map(S3Table::getName)
                .toList();
    }

    @Override
    public Optional<StoredTable> table(String namespace, String name) {
        requireNamespace(namespace);
        try {
            S3Table table = s3Tables.getTable(bucketArn, namespace, name, region);
            return Optional.of(stored(table));
        } catch (AwsException e) {
            if ("NotFoundException".equals(e.getErrorCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public String locationForNewTable(String namespace, String name, String requestedLocation) {
        if (requestedLocation != null) {
            throw IcebergRestException.badRequest("S3 Tables decides where a table lives: a create names no location");
        }
        requireNamespace(namespace);
        try {
            return stored(s3Tables.createTable(bucketArn, namespace, name, ICEBERG, null, null, null, Map.of(),
                    region)).location();
        } catch (AwsException e) {
            if ("ConflictException".equals(e.getErrorCode())) {
                throw IcebergRestException.alreadyExists("Table already exists: " + namespace + "." + name);
            }
            throw IcebergRestException.badRequest(e.getMessage());
        }
    }

    @Override
    public void create(String namespace, String name, ObjectNode metadata, String metadataLocation,
                       StoredTable reserved) {
        StoredTable table = reserved != null ? reserved : table(namespace, name)
                .orElseThrow(() -> IcebergRestException.noSuchTable(namespace, name));
        if (table.metadataLocation() != null) {
            throw IcebergRestException.alreadyExists("Table already exists: " + namespace + "." + name);
        }
        commit(namespace, name, table, metadata, metadataLocation);
    }

    @Override
    public void commit(String namespace, String name, StoredTable base, ObjectNode metadata, String metadataLocation) {
        try {
            s3Tables.updateTableMetadataLocation(bucketArn, namespace, name, metadataLocation, base.token(), region);
        } catch (AwsException e) {
            if ("ConflictException".equals(e.getErrorCode())) {
                throw new IcebergRestException(409, "CommitFailedException",
                        "Table " + namespace + "." + name + " was changed concurrently");
            }
            if ("NotFoundException".equals(e.getErrorCode())) {
                throw IcebergRestException.noSuchTable(namespace, name);
            }
            throw IcebergRestException.badRequest(e.getMessage());
        }
    }

    @Override
    public void drop(String namespace, String name) {
        try {
            s3Tables.deleteTable(bucketArn, namespace, name, region);
        } catch (AwsException e) {
            if ("NotFoundException".equals(e.getErrorCode())) {
                throw IcebergRestException.noSuchTable(namespace, name);
            }
            throw IcebergRestException.badRequest(e.getMessage());
        }
    }

    private void requireNamespace(String namespace) {
        try {
            s3Tables.getNamespace(bucketArn, namespace, region);
        } catch (AwsException e) {
            if ("NotFoundException".equals(e.getErrorCode())) {
                throw IcebergRestException.noSuchNamespace(namespace);
            }
            throw e;
        }
    }

    private static StoredTable stored(S3Table table) {
        String location = IcebergGlueTables.stripTrailingSlash(table.getWarehouseLocation());
        String metadataLocation = table.getMetadataLocation() == null || table.getMetadataLocation().isBlank()
                ? null : table.getMetadataLocation();
        return new StoredTable(metadataLocation, table.getVersionToken(), location, true);
    }
}
