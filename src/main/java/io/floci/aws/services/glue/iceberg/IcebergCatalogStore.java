package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a catalog of AWS Glue's Iceberg REST endpoint keeps its namespaces and tables: the account's default Glue Data
 * Catalog ({@link GlueIcebergStore}), or a table bucket federated from Amazon S3 Tables ({@link S3TablesIcebergStore}).
 * The endpoint itself checks a commit's requirements, applies its updates and writes metadata files; a store only
 * records which metadata file is current, refusing a commit whose base is no longer current. Errors are the endpoint's
 * own ({@link IcebergRestException}).
 */
interface IcebergCatalogStore {

    /**
     * A table the store holds: its current metadata file (null while S3 Tables holds a table nothing has committed to
     * yet), the token a commit has to name as its base, its location when the store manages it, and whether it is an
     * Iceberg table at all.
     */
    record StoredTable(String metadataLocation, String token, String location, boolean iceberg) {
    }

    /** What a namespace properties update removed, could not find, and updated. */
    record PropertyChanges(List<String> removed, List<String> missing, List<String> updated) {
    }

    /** Whether the store decides where a table lives, so a table's location is never the client's to name. */
    boolean managesLocations();

    List<String> namespaces();

    /** The namespace's properties; {@code NoSuchNamespaceException} when there is no such namespace. */
    Map<String, String> namespaceProperties(String namespace);

    void createNamespace(String namespace, Map<String, String> properties);

    PropertyChanges updateNamespaceProperties(String namespace, List<String> removals, Map<String, String> updates);

    /** Drops an empty namespace; {@code NamespaceNotEmptyException} when it holds tables. */
    void dropNamespace(String namespace);

    /** The namespace's Iceberg tables. */
    List<String> tables(String namespace);

    Optional<StoredTable> table(String namespace, String name);

    /**
     * The location a new table takes: the one the request names, or the store's own. A store that manages locations
     * (S3 Tables) creates the table here, empty, and refuses a location the request names.
     */
    String locationForNewTable(String namespace, String name, String requestedLocation);

    /**
     * Records a new table whose first metadata file is {@code metadataLocation}; {@code reserved} is the empty table
     * {@link #locationForNewTable} left, if the store made one. {@code AlreadyExistsException} when the table appeared
     * meanwhile.
     */
    void create(String namespace, String name, ObjectNode metadata, String metadataLocation, StoredTable reserved);

    /**
     * Moves the table's current metadata file from {@code base}'s to {@code metadataLocation};
     * {@code CommitFailedException} when the table changed since {@code base} was read.
     */
    void commit(String namespace, String name, StoredTable base, ObjectNode metadata, String metadataLocation);

    void drop(String namespace, String name);
}
