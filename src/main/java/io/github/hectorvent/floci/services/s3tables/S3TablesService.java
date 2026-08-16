package io.github.hectorvent.floci.services.s3tables;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3tables.model.Namespace;
import io.github.hectorvent.floci.services.s3tables.model.S3Table;
import io.github.hectorvent.floci.services.s3tables.model.TableBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class S3TablesService {
    private static final Logger LOG = Logger.getLogger(S3TablesService.class);
    private static final String BUCKET_PREFIX = "bucket/";

    private final StorageBackend<String, TableBucket> store;
    private final RegionResolver regionResolver;

    @Inject
    public S3TablesService(StorageFactory factory, RegionResolver regionResolver) {
        this.store = factory.create("s3tables", "s3tables.json", new TypeReference<Map<String, TableBucket>>() {});
        this.regionResolver = regionResolver;
    }

    public synchronized TableBucket createTableBucket(String name, Object encryptionConfiguration,
                                         Object storageClassConfiguration, Map<String, String> tags, String region) {
        validateBucketName(name);
        String key = storageKey(region, name);
        if (store.get(key).isPresent()) {
            throw conflict("The table bucket " + name + " already exists.");
        }
        TableBucket bucket = new TableBucket(name, bucketArn(region, name), now(), regionResolver.getAccountId(),
                encryptionConfiguration, storageClassConfiguration, tags);
        store.put(key, bucket);
        LOG.infov("Created S3 Tables bucket {0} in region {1}", name, region);
        return bucket;
    }

    public synchronized TableBucket getTableBucket(String tableBucketArn, String region) {
        String name = resourceName(tableBucketArn, BUCKET_PREFIX);
        TableBucket bucket = store.get(storageKey(region, name))
                .orElseThrow(() -> notFound("The table bucket does not exist."));
        if (!bucket.getArn().equals(tableBucketArn)) {
            throw notFound("The table bucket does not exist.");
        }
        return bucket;
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
        S3Table table = new S3Table(name, bucket.getArn() + "/table/" + name, namespaceName, format,
                versionToken(), metadataLocation(metadata), now(), regionResolver.getAccountId(), metadata,
                encryptionConfiguration, storageClassConfiguration, tags);
        namespace.getTables().put(name, table);
        persist(bucket, region);
        return table;
    }

    public synchronized S3Table getTable(String tableBucketArn, String namespaceName, String name, String region) {
        return getTable(getTableBucket(tableBucketArn, region), namespaceName, name);
    }

    public synchronized List<S3Table> listTables(String tableBucketArn, String namespaceName, String prefix, String region) {
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        List<S3Table> tables = new ArrayList<>();
        if (namespaceName == null) {
            bucket.getNamespaces().values().forEach(namespace -> tables.addAll(namespace.getTables().values()));
        } else {
            tables.addAll(getNamespace(bucket, namespaceName).getTables().values());
        }
        return tables.stream().filter(table -> prefix == null || table.getName().startsWith(prefix))
                .sorted(Comparator.comparing(S3Table::getName)).toList();
    }

    public synchronized void deleteTable(String tableBucketArn, String namespaceName, String name, String region) {
        TableBucket bucket = getTableBucket(tableBucketArn, region);
        Namespace namespace = getNamespace(bucket, namespaceName);
        getTable(bucket, namespaceName, name);
        namespace.getTables().remove(name);
        persist(bucket, region);
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
        table.setArn(bucket.getArn() + "/table/" + targetName);
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

    private String resourceName(String arn, String prefix) {
        if (arn == null || !arn.startsWith("arn:") || !arn.contains(":" + prefix)) {
            throw notFound("The table bucket does not exist.");
        }
        return arn.substring(arn.lastIndexOf(prefix) + prefix.length());
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
