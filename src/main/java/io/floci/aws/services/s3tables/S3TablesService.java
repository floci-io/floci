package io.floci.aws.services.s3tables;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.services.s3tables.model.Namespace;
import io.floci.aws.services.s3tables.model.S3Table;
import io.floci.aws.services.s3tables.model.TableBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class S3TablesService {
    private static final Logger LOG = Logger.getLogger(S3TablesService.class);
    private static final String BUCKET_PREFIX = "bucket/";
    private static final String TABLE_INFIX = "/table/";
    private static final int LIST_PAGE_SIZE = 1000;

    private final StorageBackend<String, TableBucket> store;
    private final RegionResolver regionResolver;
    private final S3Service s3Service;

    @Inject
    public S3TablesService(StorageFactory factory, RegionResolver regionResolver, S3Service s3Service) {
        this.store = factory.create("s3tables", "s3tables.json", new TypeReference<Map<String, TableBucket>>() {});
        this.regionResolver = regionResolver;
        this.s3Service = s3Service;
    }

    public synchronized TableBucket createTableBucket(String name, Object encryptionConfiguration,
                                         Object storageClassConfiguration, Map<String, String> tags, String region) {
        validateBucketName(name);
        String key = storageKey(region, name);
        String arn = bucketArn(region, name);
        if (findTableBucket(key, arn).isPresent()) {
            throw conflict("The table bucket " + name + " already exists.");
        }
        TableBucket bucket = new TableBucket(name, arn, now(), regionResolver.getAccountId(),
                encryptionConfiguration, storageClassConfiguration, tags);
        store.put(key, bucket);
        LOG.infov("Created S3 Tables bucket {0} in region {1}", name, region);
        return bucket;
    }

    public synchronized TableBucket getTableBucket(String tableBucketArn, String region) {
        String name = resourceName(tableBucketArn, BUCKET_PREFIX);
        String key = storageKey(region, name);
        TableBucket bucket = findTableBucket(key, tableBucketArn)
                .orElseThrow(() -> notFound("The table bucket does not exist."));
        if (!bucket.getArn().equals(tableBucketArn)) {
            throw notFound("The table bucket does not exist.");
        }
        return bucket;
    }

    public synchronized Object getTableBucketEncryption(String tableBucketArn, String region) {
        Object configuration = getTableBucket(tableBucketArn, region).getEncryptionConfiguration();
        return configuration == null ? Map.of("sseAlgorithm", "AES256") : configuration;
    }

    public synchronized List<TableBucket> listTableBuckets(String prefix, String region) {
        return store.scan(key -> key.startsWith(region + "::")).stream()
                .filter(bucket -> prefix == null || bucket.getName().startsWith(prefix))
                .sorted(Comparator.comparing(TableBucket::getName))
                .toList();
    }

    public synchronized void deleteTableBucket(String tableBucketArn, String region) {
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        if (!bucket.getNamespaces().isEmpty()) {
            throw conflict("The table bucket is not empty.");
        }
        store.delete(storageKey(region, bucket.getName()));
    }

    public synchronized Object getTableBucketStorageClass(String tableBucketArn, String region) {
        Object configuration = getTableBucket(tableBucketArn, region).getStorageClassConfiguration();
        return configuration == null ? Map.of("storageClass", "STANDARD") : configuration;
    }

    public synchronized Namespace createNamespace(String tableBucketArn, List<String> namespace, String region) {
        String namespaceName = namespaceName(namespace);
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        if (bucket.getNamespaces().containsKey(namespaceName)) {
            throw conflict("The namespace already exists.");
        }
        Namespace value = new Namespace(namespaceName, now());
        bucket.getNamespaces().put(namespaceName, value);
        persist(bucket, region);
        return value;
    }

    public synchronized Namespace getNamespace(String tableBucketArn, String namespaceName, String region) {
        return getNamespace(getTableBucket(tableBucketArn, region), namespaceName);
    }

    public synchronized List<Namespace> listNamespaces(String tableBucketArn, String prefix, String region) {
        return getTableBucket(tableBucketArn, region).getNamespaces().values().stream()
                .filter(namespace -> prefix == null || namespace.getName().startsWith(prefix))
                .sorted(Comparator.comparing(Namespace::getName))
                .toList();
    }

    public synchronized void deleteNamespace(String tableBucketArn, String namespaceName, String region) {
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        Namespace namespace = getNamespace(bucket, namespaceName);
        if (!namespace.getTables().isEmpty()) {
            throw conflict("The namespace is not empty.");
        }
        bucket.getNamespaces().remove(namespaceName);
        persist(bucket, region);
    }

    public synchronized S3Table createTable(String tableBucketArn, String namespaceName, String name, String format,
                               Object metadata, Object encryptionConfiguration, Object storageClassConfiguration,
                               Map<String, String> tags, String region) {
        requireName(name, "Table name is required.");
        if (!"ICEBERG".equals(format)) {
            throw badRequest("format must be ICEBERG.");
        }
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        Namespace namespace = getNamespace(bucket, namespaceName);
        if (namespace.getTables().containsKey(name)) {
            throw conflict("The table already exists.");
        }
        // AWS ends a table's ARN in an id it mints, not the table's name, so tables of one name in two namespaces
        // differ, and a rename keeps it.
        S3Table table = new S3Table(name, bucket.getArn() + TABLE_INFIX + UUID.randomUUID(), namespaceName, format,
                versionToken(), metadataLocation(metadata), now(), regionResolver.getAccountId(), metadata,
                encryptionConfiguration, storageClassConfiguration, tags);
        table.setWarehouseLocation(createWarehouse(region));
        namespace.getTables().put(name, table);
        try {
            persist(bucket, region);
        } catch (RuntimeException e) {
            namespace.getTables().remove(name);
            deleteWarehouse(table.getWarehouseLocation());
            throw e;
        }
        return table;
    }

    public synchronized S3Table getTable(String tableBucketArn, String namespaceName, String name, String region) {
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        S3Table table = getTable(bucket, namespaceName, name);
        withWarehouses(bucket, List.of(table), region);
        return table;
    }

    /**
     * The table {@code tableArn} names, as {@code GetTable} reads it when given one: in the table bucket the ARN's
     * resource starts with, whichever namespace holds it. A table persisted before ARNs ended in a minted id keeps
     * the name-based ARN it was stored with, and is found by it.
     */
    public synchronized S3Table getTableByArn(String tableArn, String region) {
        int infix = tableArn == null ? -1 : tableArn.lastIndexOf(TABLE_INFIX);
        if (infix < 0) {
            throw notFound("The table does not exist.");
        }
        TableBucket bucket = getTableBucket(tableArn.substring(0, infix), region);
        S3Table table = bucket.getNamespaces().values().stream()
                .flatMap(namespace -> namespace.getTables().values().stream())
                .filter(candidate -> tableArn.equals(candidate.getArn()))
                .findFirst()
                .orElseThrow(() -> notFound("The table does not exist."));
        withWarehouses(bucket, List.of(table), region);
        return table;
    }

    /**
     * The ARN of the table a request names by its table bucket's ARN, its namespace and its name, as IAM asks for it
     * before the handler runs; empty when there is no such table. Reads only: a table without a warehouse yet keeps
     * it so.
     */
    public synchronized Optional<String> tableArn(String tableBucketArn, String namespaceName, String name,
                                                  String region) {
        try {
            return Optional.of(getTable(getTableBucket(tableBucketArn, region), namespaceName, name).getArn());
        } catch (AwsException e) {
            if (!"NotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            return Optional.empty();
        }
    }

    /**
     * The tags of the table bucket or the table stored under {@code arn}, in the account and Region the ARN names, by
     * key, which IAM gives a resource-tag condition; empty when there is none. Reads that one bucket's entry, a
     * pre-multi-account one of the default account in place, and matches the bucket, or a table of it, on the whole
     * ARN, leaving both as they are.
     */
    public synchronized Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "s3tables")) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        if (!parsed.resource().startsWith(BUCKET_PREFIX)) {
            return Map.of();
        }
        String rest = parsed.resource().substring(BUCKET_PREFIX.length());
        String bucketName = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
        TableBucket bucket = stored(parsed.accountId(), storageKey(parsed.region(), bucketName)).orElse(null);
        if (bucket == null) {
            return Map.of();
        }
        if (arn.equals(bucket.getArn())) {
            return Map.copyOf(bucket.getTags());
        }
        if (!arn.startsWith(bucket.getArn() + TABLE_INFIX)) {
            return Map.of();
        }
        return bucket.getNamespaces().values().stream()
                .flatMap(namespace -> namespace.getTables().values().stream())
                .filter(table -> arn.equals(table.getArn()))
                .findFirst()
                .<Map<String, String>>map(table -> Map.copyOf(table.getTags()))
                .orElse(Map.of());
    }

    public synchronized List<S3Table> listTables(String tableBucketArn, String namespaceName, String prefix, String region) {
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        List<S3Table> tables = new ArrayList<>();
        if (namespaceName == null) {
            bucket.getNamespaces().values().forEach(namespace -> tables.addAll(namespace.getTables().values()));
        } else {
            tables.addAll(getNamespace(bucket, namespaceName).getTables().values());
        }
        List<S3Table> listed = tables.stream().filter(table -> prefix == null || table.getName().startsWith(prefix))
                .sorted(Comparator.comparing(S3Table::getName)).toList();
        withWarehouses(bucket, listed, region);
        return listed;
    }

    public synchronized void deleteTable(String tableBucketArn, String namespaceName, String name, String region) {
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        Namespace namespace = getNamespace(bucket, namespaceName);
        S3Table table = getTable(bucket, namespaceName, name);
        // The warehouse goes first: if emptying it fails, the table is still there to delete again.
        deleteWarehouse(table.getWarehouseLocation());
        namespace.getTables().remove(name);
        persist(bucket, region);
    }

    /**
     * Gives each table persisted before tables had a warehouse one of its own, so every response
     * that reports {@code warehouseLocation} has one. A save that fails takes the new warehouses
     * back out.
     */
    private void withWarehouses(TableBucket bucket, List<S3Table> tables, String region) {
        List<S3Table> backfilled = new ArrayList<>();
        for (S3Table table : tables) {
            if (table.getWarehouseLocation() == null) {
                table.setWarehouseLocation(createWarehouse(region));
                backfilled.add(table);
            }
        }
        if (backfilled.isEmpty()) {
            return;
        }
        try {
            persist(bucket, region);
        } catch (RuntimeException e) {
            for (S3Table table : backfilled) {
                deleteWarehouse(table.getWarehouseLocation());
                table.setWarehouseLocation(null);
            }
            throw e;
        }
    }

    /**
     * Creates the bucket that holds a table's data and metadata files and returns its location, as
     * S3 Tables gives every table a warehouse of its own: an Iceberg client such as Spark's
     * {@code S3TablesCatalog} writes there and commits with {@code UpdateTableMetadataLocation}.
     */
    private String createWarehouse(String region) {
        String name = UUID.randomUUID() + S3Service.TABLE_WAREHOUSE_BUCKET_SUFFIX;
        s3Service.createBucket(name, region);
        return "s3://" + name;
    }

    /** Deletes a table's warehouse with everything in it, as deleting an S3 Tables table deletes its data. */
    private void deleteWarehouse(String warehouseLocation) {
        if (warehouseLocation == null || !warehouseLocation.startsWith("s3://")) {
            return;
        }
        String name = warehouseLocation.substring("s3://".length());
        try {
            String keyMarker = null;
            String versionIdMarker = null;
            do {
                S3Service.ListVersionsResult versions = s3Service.listObjectVersions(name, null, null, LIST_PAGE_SIZE,
                        keyMarker, versionIdMarker);
                for (S3Object version : versions.versions()) {
                    s3Service.deleteObject(name, version.getKey(), version.getVersionId());
                }
                keyMarker = versions.isTruncated() ? versions.nextKeyMarker() : null;
                versionIdMarker = versions.isTruncated() ? versions.nextVersionIdMarker() : null;
            } while (keyMarker != null);
            String continuation = null;
            do {
                S3Service.ListObjectsResult page = s3Service.listObjectsWithPrefixes(name, null, null, LIST_PAGE_SIZE,
                        continuation, null);
                for (S3Object object : page.objects()) {
                    s3Service.deleteObject(name, object.getKey());
                }
                continuation = page.isTruncated() ? page.nextContinuationToken() : null;
            } while (continuation != null);
            s3Service.deleteBucket(name);
        } catch (AwsException e) {
            if (!"NoSuchBucket".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Warehouse bucket {0} was already gone", name);
        }
    }

    public synchronized S3Table renameTable(String tableBucketArn, String namespaceName, String name, String newNamespaceName,
                               String newName, String versionToken, String region) {
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        S3Table table = getTable(bucket, namespaceName, name);
        assertCurrentToken(table, versionToken);
        String targetNamespaceName = newNamespaceName == null ? namespaceName : newNamespaceName;
        String targetName = newName == null ? name : newName;
        requireName(targetName, "newName is required.");
        Namespace sourceNamespace = getNamespace(bucket, namespaceName);
        Namespace targetNamespace = getNamespace(bucket, targetNamespaceName);
        if ((!namespaceName.equals(targetNamespaceName) || !name.equals(targetName))
                && targetNamespace.getTables().containsKey(targetName)) {
            throw conflict("The target table already exists.");
        }
        sourceNamespace.getTables().remove(name);
        table.setNamespace(targetNamespaceName);
        table.setName(targetName);
        touch(table);
        targetNamespace.getTables().put(targetName, table);
        persist(bucket, region);
        return table;
    }

    public synchronized S3Table updateTableMetadataLocation(String tableBucketArn, String namespaceName, String name,
                                               String metadataLocation, String versionToken, String region) {
        if (metadataLocation == null || metadataLocation.isBlank() || versionToken == null || versionToken.isBlank()) {
            throw badRequest("metadataLocation and versionToken are required.");
        }
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        S3Table table = getTable(bucket, namespaceName, name);
        assertCurrentToken(table, versionToken);
        table.setMetadataLocation(metadataLocation);
        touch(table);
        persist(bucket, region);
        withWarehouses(bucket, List.of(table), region);
        return table;
    }

    public synchronized void putTableBucketPolicy(String tableBucketArn, String resourcePolicy, String region) {
        if (resourcePolicy == null) {
            throw badRequest("resourcePolicy is required.");
        }
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        bucket.setResourcePolicy(resourcePolicy);
        persist(bucket, region);
    }

    public synchronized String getTableBucketPolicy(String tableBucketArn, String region) {
        String policy = getTableBucket(tableBucketArn, region).getResourcePolicy();
        if (policy == null) {
            throw notFound("The table bucket policy does not exist.");
        }
        return policy;
    }

    public synchronized void deleteTableBucketPolicy(String tableBucketArn, String region) {
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        if (bucket.getResourcePolicy() == null) {
            throw notFound("The table bucket policy does not exist.");
        }
        bucket.setResourcePolicy(null);
        persist(bucket, region);
    }

    public synchronized void putTableBucketMaintenance(String tableBucketArn, String type, Object value, String region) {
        requireName(type, "Maintenance configuration type is required.");
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        bucket.getMaintenanceConfigurations().put(type, value);
        persist(bucket, region);
    }

    public synchronized Object getTableBucketMaintenance(String tableBucketArn, String type, String region) {
        Object value = getTableBucket(tableBucketArn, region).getMaintenanceConfigurations().get(type);
        if (value == null) {
            throw notFound("The table bucket maintenance configuration does not exist.");
        }
        return value;
    }

    public synchronized Map<String, Object> getTableBucketMaintenanceConfigurations(String tableBucketArn, String region) {
        return new LinkedHashMap<>(getTableBucket(tableBucketArn, region).getMaintenanceConfigurations());
    }

    public synchronized void putTablePolicy(String tableBucketArn, String namespaceName, String name, String resourcePolicy, String region) {
        if (resourcePolicy == null) {
            throw badRequest("resourcePolicy is required.");
        }
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        S3Table table = getTable(bucket, namespaceName, name);
        table.setResourcePolicy(resourcePolicy);
        persist(bucket, region);
    }

    public synchronized String getTablePolicy(String tableBucketArn, String namespaceName, String name, String region) {
        String policy = getTable(tableBucketArn, namespaceName, name, region).getResourcePolicy();
        if (policy == null) {
            throw notFound("The table policy does not exist.");
        }
        return policy;
    }

    public synchronized void deleteTablePolicy(String tableBucketArn, String namespaceName, String name, String region) {
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        S3Table table = getTable(bucket, namespaceName, name);
        if (table.getResourcePolicy() == null) {
            throw notFound("The table policy does not exist.");
        }
        table.setResourcePolicy(null);
        persist(bucket, region);
    }

    public synchronized void putTableMaintenance(String tableBucketArn, String namespaceName, String name, String type,
                                    Object value, String region) {
        requireName(type, "Maintenance configuration type is required.");
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        S3Table table = getTable(bucket, namespaceName, name);
        table.getMaintenanceConfigurations().put(type, value);
        persist(bucket, region);
    }

    public synchronized Object getTableMaintenance(String tableBucketArn, String namespaceName, String name, String type, String region) {
        Object value = getTable(tableBucketArn, namespaceName, name, region).getMaintenanceConfigurations().get(type);
        if (value == null) {
            throw notFound("The table maintenance configuration does not exist.");
        }
        return value;
    }

    public synchronized Map<String, Object> getTableMaintenanceConfigurations(String tableBucketArn, String namespaceName,
                                                                   String name, String region) {
        return new LinkedHashMap<>(getTable(tableBucketArn, namespaceName, name, region).getMaintenanceConfigurations());
    }

    private Namespace getNamespace(TableBucket bucket, String name) {
        Namespace namespace = bucket.getNamespaces().get(name);
        if (namespace == null) {
            throw notFound("The namespace does not exist.");
        }
        return namespace;
    }

    private S3Table getTable(TableBucket bucket, String namespaceName, String name) {
        S3Table table = getNamespace(bucket, namespaceName).getTables().get(name);
        if (table == null) {
            throw notFound("The table does not exist.");
        }
        return table;
    }

    private String bucketArn(String region, String name) {
        return AwsArnUtils.Arn.of("s3tables", region, regionResolver.getAccountId(), BUCKET_PREFIX + name).toString();
    }

    private String storageKey(String region, String name) {
        return region + "::" + name;
    }

    private Optional<TableBucket> findTableBucket(String key, String tableBucketArn) {
        if (store instanceof AccountAwareStorageBackend<TableBucket> aware) {
            String accountId = regionResolver.getAccountId();
            return aware.getForAccountMigratingLegacy(accountId, key,
                    legacy -> accountId.equals(aware.defaultAccountId()) && tableBucketArn.equals(legacy.getArn()));
        }
        return store.get(key);
    }

    /** The bucket stored under {@code key} in the account, or the default account's legacy one, read only. */
    private Optional<TableBucket> stored(String accountId, String key) {
        return store instanceof AccountAwareStorageBackend<TableBucket> aware
                ? aware.getForAccount(accountId, key).or(() -> aware.getLegacyForAccount(accountId, key))
                : store.get(key);
    }

    private String resourceName(String arn, String prefix) {
        // Guard and cut must use the same needle. Checking for ":" + prefix but cutting on prefix
        // alone means a name that itself contains the prefix is cut at the wrong place.
        String needle = ":" + prefix;
        if (!AwsArnUtils.looksLikeArn(arn) || !arn.contains(needle)) {
            throw notFound("The table bucket does not exist.");
        }
        return arn.substring(arn.indexOf(needle) + needle.length());
    }

    private void persist(TableBucket bucket, String region) {
        store.put(storageKey(region, bucket.getName()), bucket);
    }

    private String namespaceName(List<String> namespace) {
        if (namespace == null || namespace.size() != 1) {
            throw badRequest("namespace must contain exactly one name.");
        }
        requireName(namespace.getFirst(), "Namespace name is required.");
        return namespace.getFirst();
    }

    private void validateBucketName(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")) {
            throw badRequest("Table bucket names must be 3-63 lowercase letters, numbers, or hyphens.");
        }
    }

    private void requireName(String value, String message) {
        if (value == null || value.isBlank()) {
            throw badRequest(message);
        }
    }

    @SuppressWarnings("unchecked")
    private String metadataLocation(Object metadata) {
        if (metadata instanceof Map<?, ?> map) {
            Object direct = map.get("metadataLocation");
            if (direct instanceof String value) {
                return value;
            }
            for (Object value : map.values()) {
                String nested = metadataLocation(value);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private void assertCurrentToken(S3Table table, String suppliedToken) {
        if (suppliedToken != null && !suppliedToken.equals(table.getVersionToken())) {
            throw conflict("The supplied version token is stale.");
        }
    }

    private void touch(S3Table table) {
        table.setVersionToken(versionToken());
        table.setModifiedAt(now());
    }

    private String now() { return Instant.now().toString(); }
    private String versionToken() { return UUID.randomUUID().toString(); }
    private AwsException badRequest(String message) { return new AwsException("BadRequestException", message, 400); }
    private AwsException conflict(String message) { return new AwsException("ConflictException", message, 409); }
    private AwsException notFound(String message) { return new AwsException("NotFoundException", message, 404); }
}
