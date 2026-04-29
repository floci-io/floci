package io.github.hectorvent.floci.services.glue.schemaregistry;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.MetadataInfo;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.Registry;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.RegistryId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.Schema;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaVersion;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.regex.Pattern;

@ApplicationScoped
public class GlueSchemaRegistryService {

    private static final Logger LOG = Logger.getLogger(GlueSchemaRegistryService.class);

    static final String DEFAULT_REGISTRY_NAME = "default-registry";
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9-_$#.]+$");
    private static final int NAME_MAX_LENGTH = 255;
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_MAX_RESULTS = 100;
    private static final int MAX_DELETE_SCHEMA_VERSIONS = 25;
    private static final int MAX_SCHEMA_DEFINITION_LENGTH = 170_000;

    private static final Set<String> DATA_FORMATS = Set.of("AVRO", "JSON", "PROTOBUF");
    private static final Set<String> COMPATIBILITY_MODES = Set.of(
            "NONE", "DISABLED", "BACKWARD", "BACKWARD_ALL",
            "FORWARD", "FORWARD_ALL", "FULL", "FULL_ALL");

    private final StorageBackend<String, Registry> registryStore;
    private final StorageBackend<String, Schema> schemaStore;
    private final StorageBackend<String, SchemaVersion> versionStore;
    private final StorageBackend<String, Map<String, MetadataInfo>> metadataStore;
    private final RegionResolver regionResolver;

    private final Map<String, NavigableMap<Long, String>> versionByNumber = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> versionByDefinitionHash = new ConcurrentHashMap<>();

    @Inject
    public GlueSchemaRegistryService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(
                storageFactory.create("glue", "registries.json", new TypeReference<Map<String, Registry>>() {}),
                storageFactory.create("glue", "schemas.json", new TypeReference<Map<String, Schema>>() {}),
                storageFactory.create("glue", "schema_versions.json", new TypeReference<Map<String, SchemaVersion>>() {}),
                storageFactory.create("glue", "schema_metadata.json", new TypeReference<Map<String, Map<String, MetadataInfo>>>() {}),
                regionResolver
        );
    }

    public GlueSchemaRegistryService(StorageBackend<String, Registry> registryStore,
                                     RegionResolver regionResolver) {
        this(registryStore,
                new io.github.hectorvent.floci.core.storage.InMemoryStorage<>(),
                new io.github.hectorvent.floci.core.storage.InMemoryStorage<>(),
                new io.github.hectorvent.floci.core.storage.InMemoryStorage<>(),
                regionResolver);
    }

    GlueSchemaRegistryService(StorageBackend<String, Registry> registryStore,
                              StorageBackend<String, Schema> schemaStore,
                              StorageBackend<String, SchemaVersion> versionStore,
                              StorageBackend<String, Map<String, MetadataInfo>> metadataStore,
                              RegionResolver regionResolver) {
        this.registryStore = registryStore;
        this.schemaStore = schemaStore;
        this.versionStore = versionStore;
        this.metadataStore = metadataStore;
        this.regionResolver = regionResolver;
        rebuildVersionIndexes();
    }

    @PostConstruct
    void afterCdiInit() {
        rebuildVersionIndexes();
    }

    // ---- Registry --------------------------------------------------------

    public Registry createRegistry(String name, String description, Map<String, String> tags, String region) {
        validateName(name, "RegistryName");
        if (registryStore.get(name).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Registry already exists: " + name, 400);
        }
        Registry registry = new Registry(name);
        registry.setDescription(description);
        registry.setTags(tags);
        registry.setRegistryArn(buildRegistryArn(region, name));
        registryStore.put(name, registry);
        LOG.infov("Created Glue Registry: {0}", name);
        return registry;
    }

    public Registry getRegistry(RegistryId registryId, String region) {
        String name = resolveRegistryName(registryId);
        if (DEFAULT_REGISTRY_NAME.equals(name) && registryStore.get(name).isEmpty()) {
            return autoCreateDefaultRegistry(region);
        }
        return registryStore.get(name)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Registry not found: " + name, 400));
    }

    public List<Registry> listRegistries() {
        return sortedRegistries();
    }

    public Page<Registry> listRegistries(Integer maxResults, String nextToken) {
        return paginate(sortedRegistries(), maxResults, nextToken);
    }

    public Registry updateRegistry(RegistryId registryId, String description, String region) {
        Registry registry = getRegistry(registryId, region);
        registry.setDescription(description);
        registry.setUpdatedTime(Instant.now());
        registryStore.put(registry.getRegistryName(), registry);
        return registry;
    }

    public synchronized Registry deleteRegistry(RegistryId registryId, String region) {
        Registry registry = getRegistry(registryId, region);
        String prefix = registry.getRegistryName() + ":";
        for (Schema schema : schemaStore.scan(k -> k.startsWith(prefix))) {
            deleteSchemaByKey(schemaKey(schema.getRegistryName(), schema.getSchemaName()), schema);
        }
        registry.setStatus("DELETING");
        registryStore.delete(registry.getRegistryName());
        LOG.infov("Deleted Glue Registry: {0}", registry.getRegistryName());
        return registry;
    }

    // ---- Schema ----------------------------------------------------------

    public synchronized SchemaWithFirstVersion createSchema(RegistryId registryId,
                                                            String schemaName,
                                                            String dataFormat,
                                                            String compatibility,
                                                            String description,
                                                            String definition,
                                                            Map<String, String> tags,
                                                            String region) {
        validateName(schemaName, "SchemaName");
        validateDataFormat(dataFormat);
        String compat = compatibility != null ? compatibility : "BACKWARD";
        validateCompatibility(compat);
        validateDefinitionRequired(definition);
        Registry registry = getRegistry(registryId, region);
        String schemaKey = schemaKey(registry.getRegistryName(), schemaName);
        if (schemaStore.get(schemaKey).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "Schema already exists: " + registry.getRegistryName() + "/" + schemaName, 400);
        }
        String defError = SchemaCompatibilityChecker.validateDefinition(definition, dataFormat);
        if (defError != null) {
            throw new AwsException("InvalidInputException",
                    "Schema definition is not valid " + dataFormat + ": " + defError, 400);
        }

        Instant now = Instant.now();
        Schema schema = new Schema();
        schema.setRegistryName(registry.getRegistryName());
        schema.setRegistryArn(registry.getRegistryArn());
        schema.setSchemaName(schemaName);
        schema.setSchemaArn(buildSchemaArn(region, registry.getRegistryName(), schemaName));
        schema.setDescription(description);
        schema.setDataFormat(dataFormat);
        schema.setCompatibility(compat);
        schema.setSchemaStatus("AVAILABLE");
        schema.setSchemaCheckpoint(1L);
        schema.setLatestSchemaVersion(1L);
        schema.setNextSchemaVersion(2L);
        schema.setCreatedTime(now);
        schema.setUpdatedTime(now);
        schema.setTags(tags);

        SchemaVersion version = new SchemaVersion();
        version.setSchemaVersionId(UUID.randomUUID().toString());
        version.setSchemaArn(schema.getSchemaArn());
        version.setVersionNumber(1L);
        version.setStatus("AVAILABLE");
        version.setSchemaDefinition(definition);
        version.setDataFormat(dataFormat);
        version.setCreatedTime(now);

        schemaStore.put(schemaKey, schema);
        versionStore.put(version.getSchemaVersionId(), version);
        indexVersion(schemaKey, version);
        LOG.infov("Created Glue Schema {0}/{1} (version 1: {2})",
                registry.getRegistryName(), schemaName, version.getSchemaVersionId());
        return new SchemaWithFirstVersion(schema, version);
    }

    public Schema getSchema(SchemaId schemaId, String region) {
        return resolveSchema(schemaId, region);
    }

    public List<Schema> listSchemas(RegistryId registryId, String region) {
        Registry registry = getRegistry(registryId, region);
        String prefix = registry.getRegistryName() + ":";
        return sortedSchemas(prefix);
    }

    public Page<Schema> listSchemas(RegistryId registryId, String region, Integer maxResults, String nextToken) {
        Registry registry = getRegistry(registryId, region);
        String prefix = registry.getRegistryName() + ":";
        return paginate(sortedSchemas(prefix), maxResults, nextToken);
    }

    public synchronized Schema updateSchema(SchemaId schemaId,
                                            String compatibility,
                                            String description,
                                            String region) {
        return updateSchema(schemaId, compatibility, description, null, region);
    }

    public synchronized Schema updateSchema(SchemaId schemaId,
                                            String compatibility,
                                            String description,
                                            Long checkpointVersionNumber,
                                            String region) {
        Schema schema = resolveSchema(schemaId, region);
        if (compatibility != null && !compatibility.isBlank()) {
            validateCompatibility(compatibility);
            schema.setCompatibility(compatibility);
        }
        if (description != null) {
            schema.setDescription(description);
        }
        if (checkpointVersionNumber != null) {
            validateCheckpointVersionExists(schema, checkpointVersionNumber);
            schema.setSchemaCheckpoint(checkpointVersionNumber);
        }
        schema.setUpdatedTime(Instant.now());
        schemaStore.put(schemaKey(schema.getRegistryName(), schema.getSchemaName()), schema);
        return schema;
    }

    public synchronized Schema deleteSchema(SchemaId schemaId, String region) {
        Schema schema = resolveSchema(schemaId, region);
        String key = schemaKey(schema.getRegistryName(), schema.getSchemaName());
        int versionsDeleted = deleteSchemaByKey(key, schema);
        schema.setSchemaStatus("DELETING");
        LOG.infov("Deleted Glue Schema {0}/{1} ({2} versions)",
                schema.getRegistryName(), schema.getSchemaName(), versionsDeleted);
        return schema;
    }

    // ---- Schema versions -------------------------------------------------

    public synchronized SchemaVersion registerSchemaVersion(SchemaId schemaId, String definition, String region) {
        validateDefinitionRequired(definition);
        Schema schema = resolveSchema(schemaId, region);
        String dataFormat = schema.getDataFormat();
        String defError = SchemaCompatibilityChecker.validateDefinition(definition, dataFormat);
        if (defError != null) {
            throw new AwsException("InvalidInputException",
                    "Schema definition is not valid " + dataFormat + ": " + defError, 400);
        }

        String schemaKey = schemaKey(schema.getRegistryName(), schema.getSchemaName());
        String hash = canonicalHash(definition, dataFormat);
        Map<String, String> hashIndex = versionByDefinitionHash.computeIfAbsent(schemaKey, k -> new ConcurrentHashMap<>());
        String existingId = hashIndex.get(hash);
        if (existingId != null) {
            return versionStore.get(existingId)
                    .orElseThrow(() -> new AwsException("EntityNotFoundException",
                            "Indexed schema version vanished: " + existingId, 400));
        }

        if ("DISABLED".equals(schema.getCompatibility())) {
            throw new AwsException("InvalidInputException",
                    "Compatibility mode DISABLED — auto-registration of new versions is not allowed", 400);
        }

        List<String> existingDefs = orderedDefinitions(schemaKey);
        SchemaCompatibilityChecker.Result compat =
                SchemaCompatibilityChecker.check(schema.getCompatibility(), existingDefs, definition, dataFormat);
        if (!compat.compatible()) {
            throw new AwsException("InvalidInputException",
                    "Schema is incompatible: " + compat.reason(), 400);
        }

        Instant now = Instant.now();
        long nextVersion = schema.getNextSchemaVersion() != null ? schema.getNextSchemaVersion() : 1L;
        SchemaVersion version = new SchemaVersion();
        version.setSchemaVersionId(UUID.randomUUID().toString());
        version.setSchemaArn(schema.getSchemaArn());
        version.setVersionNumber(nextVersion);
        version.setStatus("AVAILABLE");
        version.setSchemaDefinition(definition);
        version.setDataFormat(dataFormat);
        version.setCreatedTime(now);

        schema.setLatestSchemaVersion(nextVersion);
        schema.setNextSchemaVersion(nextVersion + 1);
        schema.setUpdatedTime(now);

        schemaStore.put(schemaKey, schema);
        versionStore.put(version.getSchemaVersionId(), version);
        indexVersion(schemaKey, version);
        LOG.infov("Registered Glue Schema version {0}/{1} v{2} ({3})",
                schema.getRegistryName(), schema.getSchemaName(), nextVersion, version.getSchemaVersionId());
        return version;
    }

    public SchemaVersion getSchemaVersion(SchemaId schemaId,
                                          String schemaVersionId,
                                          Long versionNumber,
                                          boolean latestVersion,
                                          String region) {
        if (schemaVersionId != null && !schemaVersionId.isBlank()) {
            return versionStore.get(schemaVersionId)
                    .orElseThrow(() -> new AwsException("EntityNotFoundException",
                            "Schema version not found: " + schemaVersionId, 400));
        }
        Schema schema = resolveSchema(schemaId, region);
        String schemaKey = schemaKey(schema.getRegistryName(), schema.getSchemaName());
        NavigableMap<Long, String> byNumber = versionByNumber.get(schemaKey);
        if (byNumber == null || byNumber.isEmpty()) {
            throw new AwsException("EntityNotFoundException", "No schema versions for " + schemaKey, 400);
        }
        Long target;
        if (latestVersion) {
            target = byNumber.lastKey();
        } else if (versionNumber != null) {
            target = versionNumber;
        } else {
            throw new AwsException("InvalidInputException",
                    "Either SchemaVersionId, VersionNumber, or LatestVersion=true is required", 400);
        }
        String id = byNumber.get(target);
        if (id == null) {
            throw new AwsException("EntityNotFoundException",
                    "Schema version " + target + " not found for " + schemaKey, 400);
        }
        return versionStore.get(id)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Schema version vanished: " + id, 400));
    }

    public List<SchemaVersion> listSchemaVersions(SchemaId schemaId, String region) {
        Schema schema = resolveSchema(schemaId, region);
        return sortedSchemaVersions(schema);
    }

    public Page<SchemaVersion> listSchemaVersions(SchemaId schemaId, String region,
                                                  Integer maxResults, String nextToken) {
        Schema schema = resolveSchema(schemaId, region);
        return paginate(sortedSchemaVersions(schema), maxResults, nextToken);
    }

    private List<SchemaVersion> sortedSchemaVersions(Schema schema) {
        String key = schemaKey(schema.getRegistryName(), schema.getSchemaName());
        NavigableMap<Long, String> byNumber = versionByNumber.get(key);
        if (byNumber == null || byNumber.isEmpty()) {
            return List.of();
        }
        List<SchemaVersion> out = new ArrayList<>(byNumber.size());
        for (String id : byNumber.values()) {
            versionStore.get(id).ifPresent(out::add);
        }
        return out;
    }

    public synchronized List<VersionDeletionResult> deleteSchemaVersions(SchemaId schemaId,
                                                                         String versionsExpression,
                                                                         String region) {
        if (versionsExpression == null || versionsExpression.isBlank()) {
            throw new AwsException("InvalidInputException", "Versions expression is required", 400);
        }
        Schema schema = resolveSchema(schemaId, region);
        String key = schemaKey(schema.getRegistryName(), schema.getSchemaName());
        NavigableMap<Long, String> byNumber = versionByNumber.get(key);
        if (byNumber == null) {
            byNumber = new ConcurrentSkipListMap<>();
        }
        List<Long> versions = parseVersionsExpression(versionsExpression);
        List<VersionDeletionResult> results = new ArrayList<>(versions.size());
        Long latestRemaining = byNumber.isEmpty() ? null : byNumber.lastKey();

        for (Long v : versions) {
            // Glue forbids deleting the latest version while older versions exist.
            if (latestRemaining != null && v.equals(latestRemaining) && byNumber.size() > 1) {
                results.add(new VersionDeletionResult(v, "InvalidInputException",
                        "Cannot delete the latest version while older versions remain"));
                continue;
            }
            if (schema.getSchemaCheckpoint() != null && v.equals(schema.getSchemaCheckpoint())) {
                results.add(new VersionDeletionResult(v, "InvalidInputException",
                        "Cannot delete the checkpoint version"));
                continue;
            }
            String id = byNumber.get(v);
            if (id == null) {
                results.add(new VersionDeletionResult(v, "EntityNotFoundException",
                        "Version " + v + " not found"));
                continue;
            }
            String hash = versionStore.get(id)
                    .map(sv -> canonicalHash(sv.getSchemaDefinition(), sv.getDataFormat()))
                    .orElse(null);
            versionStore.delete(id);
            metadataStore.delete(id);
            byNumber.remove(v);
            if (hash != null) {
                Map<String, String> hashIndex = versionByDefinitionHash.get(key);
                if (hashIndex != null) {
                    hashIndex.remove(hash);
                }
            }
            if (v.equals(latestRemaining)) {
                latestRemaining = byNumber.isEmpty() ? null : byNumber.lastKey();
            }
            results.add(new VersionDeletionResult(v, null, null));
        }

        if (byNumber.isEmpty()) {
            versionByNumber.remove(key);
            versionByDefinitionHash.remove(key);
            schema.setLatestSchemaVersion(null);
        } else {
            schema.setLatestSchemaVersion(byNumber.lastKey());
        }
        schema.setUpdatedTime(Instant.now());
        schemaStore.put(key, schema);
        return results;
    }

    public String getSchemaVersionsDiff(SchemaId schemaId, Long firstVersion, Long secondVersion, String region) {
        if (firstVersion == null || secondVersion == null) {
            throw new AwsException("InvalidInputException",
                    "Both FirstSchemaVersionNumber and SecondSchemaVersionNumber are required", 400);
        }
        SchemaVersion first = getSchemaVersion(schemaId, null, firstVersion, false, region);
        SchemaVersion second = getSchemaVersion(schemaId, null, secondVersion, false, region);
        return simpleUnifiedDiff(
                "v" + first.getVersionNumber(), first.getSchemaDefinition(),
                "v" + second.getVersionNumber(), second.getSchemaDefinition());
    }

    // ---- Metadata --------------------------------------------------------

    public synchronized MetadataPutResult putSchemaVersionMetadata(String schemaVersionId,
                                                                    String key,
                                                                    String value) {
        validateMetadataKeyValue(key, value);
        SchemaVersion version = versionStore.get(schemaVersionId)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Schema version not found: " + schemaVersionId, 400));

        Map<String, MetadataInfo> metadata = metadataStore.get(schemaVersionId).orElseGet(java.util.LinkedHashMap::new);
        MetadataInfo info = metadata.get(key);
        Instant now = Instant.now();
        if (info == null) {
            info = new MetadataInfo(value, now);
        } else if (value.equals(info.getMetadataValue())) {
            throw new AwsException("AlreadyExistsException",
                    "Metadata key/value pair already exists: " + key + "=" + value, 400);
        } else {
            // Demote current value into OtherMetadataValueList; check duplicate among history.
            List<MetadataInfo.OtherMetadataValueListItem> history =
                    info.getOtherMetadataValueList() != null
                            ? new ArrayList<>(info.getOtherMetadataValueList())
                            : new ArrayList<>();
            for (var item : history) {
                if (value.equals(item.getMetadataValue())) {
                    throw new AwsException("AlreadyExistsException",
                            "Metadata key/value pair already exists: " + key + "=" + value, 400);
                }
            }
            history.add(0,
                    new MetadataInfo.OtherMetadataValueListItem(info.getMetadataValue(), info.getCreatedTime()));
            info = new MetadataInfo(value, now);
            info.setOtherMetadataValueList(history);
        }
        metadata.put(key, info);
        metadataStore.put(schemaVersionId, metadata);
        return new MetadataPutResult(version, key, value);
    }

    public synchronized MetadataPutResult removeSchemaVersionMetadata(String schemaVersionId,
                                                                       String key,
                                                                       String value) {
        validateMetadataKeyValue(key, value);
        SchemaVersion version = versionStore.get(schemaVersionId)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Schema version not found: " + schemaVersionId, 400));

        Map<String, MetadataInfo> metadata = metadataStore.get(schemaVersionId)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Metadata not found for schema version: " + schemaVersionId, 400));
        MetadataInfo info = metadata.get(key);
        if (info == null) {
            throw new AwsException("EntityNotFoundException",
                    "Metadata key not found: " + key, 400);
        }

        if (value.equals(info.getMetadataValue())) {
            // Remove current; promote first OtherMetadataValueList entry.
            List<MetadataInfo.OtherMetadataValueListItem> history = info.getOtherMetadataValueList();
            if (history == null || history.isEmpty()) {
                metadata.remove(key);
            } else {
                MetadataInfo.OtherMetadataValueListItem promoted = history.get(0);
                MetadataInfo replacement = new MetadataInfo(promoted.getMetadataValue(), promoted.getCreatedTime());
                if (history.size() > 1) {
                    replacement.setOtherMetadataValueList(new ArrayList<>(history.subList(1, history.size())));
                }
                metadata.put(key, replacement);
            }
        } else {
            // Remove from OtherMetadataValueList.
            List<MetadataInfo.OtherMetadataValueListItem> history = info.getOtherMetadataValueList();
            if (history == null) {
                throw new AwsException("EntityNotFoundException",
                        "Metadata key/value not found: " + key + "=" + value, 400);
            }
            boolean removed = history.removeIf(it -> value.equals(it.getMetadataValue()));
            if (!removed) {
                throw new AwsException("EntityNotFoundException",
                        "Metadata key/value not found: " + key + "=" + value, 400);
            }
            info.setOtherMetadataValueList(history.isEmpty() ? null : history);
            metadata.put(key, info);
        }

        if (metadata.isEmpty()) {
            metadataStore.delete(schemaVersionId);
        } else {
            metadataStore.put(schemaVersionId, metadata);
        }
        return new MetadataPutResult(version, key, value);
    }

    public Map<String, MetadataInfo> querySchemaVersionMetadata(String schemaVersionId,
                                                                List<MetadataKeyValueFilter> metadataList) {
        if (versionStore.get(schemaVersionId).isEmpty()) {
            throw new AwsException("EntityNotFoundException",
                    "Schema version not found: " + schemaVersionId, 400);
        }
        Map<String, MetadataInfo> stored = metadataStore.get(schemaVersionId).orElse(Map.of());
        if (metadataList == null || metadataList.isEmpty()) {
            return stored;
        }
        Map<String, MetadataInfo> filtered = new java.util.LinkedHashMap<>();
        for (var f : metadataList) {
            MetadataInfo info = stored.get(f.metadataKey());
            if (info == null) continue;
            if (f.metadataValue() == null || f.metadataValue().isBlank()) {
                filtered.put(f.metadataKey(), info);
            } else if (f.metadataValue().equals(info.getMetadataValue())) {
                filtered.put(f.metadataKey(), info);
            } else if (info.getOtherMetadataValueList() != null) {
                for (var item : info.getOtherMetadataValueList()) {
                    if (f.metadataValue().equals(item.getMetadataValue())) {
                        filtered.put(f.metadataKey(), info);
                        break;
                    }
                }
            }
        }
        return filtered;
    }

    // ---- Tags ------------------------------------------------------------

    public synchronized void tagResource(String resourceArn, Map<String, String> tagsToAdd) {
        if (tagsToAdd == null || tagsToAdd.isEmpty()) {
            return;
        }
        TaggedResource resource = resolveTaggedResource(resourceArn);
        Map<String, String> tags = resource.getTags();
        if (tags == null) {
            tags = new java.util.LinkedHashMap<>();
        } else {
            tags = new java.util.LinkedHashMap<>(tags);
        }
        tags.putAll(tagsToAdd);
        resource.setTags(tags);
        resource.persist();
    }

    public synchronized void untagResource(String resourceArn, List<String> tagKeysToRemove) {
        if (tagKeysToRemove == null || tagKeysToRemove.isEmpty()) {
            return;
        }
        TaggedResource resource = resolveTaggedResource(resourceArn);
        Map<String, String> tags = resource.getTags();
        if (tags == null || tags.isEmpty()) {
            return;
        }
        Map<String, String> updated = new java.util.LinkedHashMap<>(tags);
        for (String key : tagKeysToRemove) {
            updated.remove(key);
        }
        resource.setTags(updated.isEmpty() ? null : updated);
        resource.persist();
    }

    public Map<String, String> getTags(String resourceArn) {
        TaggedResource resource = resolveTaggedResource(resourceArn);
        Map<String, String> tags = resource.getTags();
        return tags != null ? tags : Map.of();
    }

    public CheckValidityResult checkSchemaVersionValidity(String dataFormat, String definition) {
        validateDataFormat(dataFormat);
        if (definition == null || definition.isBlank()) {
            return new CheckValidityResult(false, "SchemaDefinition is required");
        }
        String error = SchemaCompatibilityChecker.validateDefinition(definition, dataFormat);
        if (error == null) {
            return new CheckValidityResult(true, null);
        }
        return new CheckValidityResult(false, error);
    }

    public SchemaVersion getSchemaByDefinition(SchemaId schemaId, String definition, String region) {
        validateDefinitionRequired(definition);
        Schema schema = resolveSchema(schemaId, region);
        String schemaKey = schemaKey(schema.getRegistryName(), schema.getSchemaName());
        String hash = canonicalHash(definition, schema.getDataFormat());
        Map<String, String> hashIndex = versionByDefinitionHash.get(schemaKey);
        String id = hashIndex != null ? hashIndex.get(hash) : null;
        if (id == null) {
            throw new AwsException("EntityNotFoundException",
                    "Schema version is not found. Definition not found in " + schemaKey, 400);
        }
        return versionStore.get(id)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Schema version vanished: " + id, 400));
    }

    // ---- Helpers ---------------------------------------------------------

    public record SchemaWithFirstVersion(Schema schema, SchemaVersion firstVersion) {}

    public record VersionDeletionResult(long versionNumber, String errorCode, String errorMessage) {}

    public record CheckValidityResult(boolean valid, String error) {}

    public record MetadataPutResult(SchemaVersion version, String metadataKey, String metadataValue) {}

    public record MetadataKeyValueFilter(String metadataKey, String metadataValue) {}

    public record Page<T>(List<T> items, String nextToken) {}

    private interface TaggedResource {
        Map<String, String> getTags();
        void setTags(Map<String, String> tags);
        void persist();
    }

    private TaggedResource resolveTaggedResource(String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidInputException", "ResourceArn is required", 400);
        }
        if (resourceArn.contains(":registry/")) {
            String name = parseRegistryNameFromArn(resourceArn);
            Registry r = registryStore.get(name)
                    .orElseThrow(() -> new AwsException("EntityNotFoundException",
                            "Registry not found: " + name, 400));
            return new TaggedResource() {
                @Override public Map<String, String> getTags() { return r.getTags(); }
                @Override public void setTags(Map<String, String> tags) { r.setTags(tags); }
                @Override public void persist() { registryStore.put(r.getRegistryName(), r); }
            };
        }
        if (resourceArn.contains(":schema/")) {
            String[] parts = parseSchemaArn(resourceArn);
            String key = schemaKey(parts[0], parts[1]);
            Schema s = schemaStore.get(key)
                    .orElseThrow(() -> new AwsException("EntityNotFoundException",
                            "Schema not found: " + parts[0] + "/" + parts[1], 400));
            return new TaggedResource() {
                @Override public Map<String, String> getTags() { return s.getTags(); }
                @Override public void setTags(Map<String, String> tags) { s.setTags(tags); }
                @Override public void persist() { schemaStore.put(key, s); }
            };
        }
        throw new AwsException("InvalidInputException",
                "Resource ARN does not point to a Registry or Schema: " + resourceArn, 400);
    }

    private void validateMetadataKeyValue(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new AwsException("InvalidInputException", "MetadataKey is required", 400);
        }
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidInputException", "MetadataValue is required", 400);
        }
    }

    private static List<Long> parseVersionsExpression(String expr) {
        java.util.SortedSet<Long> versions = new java.util.TreeSet<>();
        for (String token : expr.split(",")) {
            token = token.trim();
            if (token.isEmpty()) continue;
            try {
                if (token.contains("-")) {
                    String[] range = token.split("-", 2);
                    long start = Long.parseLong(range[0].trim());
                    long end = Long.parseLong(range[1].trim());
                    if (end < start) {
                        throw new AwsException("InvalidInputException",
                                "Invalid version range: " + token, 400);
                    }
                    for (long v = start; v <= end; v++) {
                        versions.add(v);
                    }
                } else {
                    versions.add(Long.parseLong(token));
                }
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidInputException",
                        "Invalid version expression: " + token, 400);
            }
        }
        if (versions.size() > MAX_DELETE_SCHEMA_VERSIONS) {
            throw new AwsException("InvalidInputException",
                    "Versions expression cannot expand to more than " + MAX_DELETE_SCHEMA_VERSIONS + " versions", 400);
        }
        return new ArrayList<>(versions);
    }

    private static String simpleUnifiedDiff(String labelA, String a, String labelB, String b) {
        if (java.util.Objects.equals(a, b)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(labelA).append('\n');
        sb.append("+++ ").append(labelB).append('\n');
        java.util.List<String> linesA = a == null ? List.of() : List.of(a.split("\n", -1));
        java.util.List<String> linesB = b == null ? List.of() : List.of(b.split("\n", -1));
        int n = linesA.size();
        int m = linesB.size();
        // Compute LCS lengths.
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (linesA.get(i).equals(linesB.get(j))) {
                    dp[i][j] = dp[i + 1][j + 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (linesA.get(i).equals(linesB.get(j))) {
                sb.append(' ').append(linesA.get(i)).append('\n');
                i++; j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                sb.append('-').append(linesA.get(i)).append('\n');
                i++;
            } else {
                sb.append('+').append(linesB.get(j)).append('\n');
                j++;
            }
        }
        while (i < n) {
            sb.append('-').append(linesA.get(i++)).append('\n');
        }
        while (j < m) {
            sb.append('+').append(linesB.get(j++)).append('\n');
        }
        return sb.toString();
    }

    private Schema resolveSchema(SchemaId schemaId, String region) {
        if (schemaId == null) {
            throw new AwsException("InvalidInputException", "SchemaId is required", 400);
        }
        String registryName;
        String schemaName;
        if (schemaId.getSchemaArn() != null && !schemaId.getSchemaArn().isBlank()) {
            String[] parsed = parseSchemaArn(schemaId.getSchemaArn());
            registryName = parsed[0];
            schemaName = parsed[1];
        } else {
            schemaName = schemaId.getSchemaName();
            if (schemaName == null || schemaName.isBlank()) {
                throw new AwsException("InvalidInputException",
                        "SchemaId requires SchemaArn or SchemaName", 400);
            }
            String requested = schemaId.getRegistryName();
            if (requested == null || requested.isBlank()) {
                Registry reg = getRegistry(null, region);
                registryName = reg.getRegistryName();
            } else {
                registryName = requested;
                if (registryStore.get(registryName).isEmpty()) {
                    throw new AwsException("EntityNotFoundException",
                            "Registry not found: " + registryName, 400);
                }
            }
        }
        String key = schemaKey(registryName, schemaName);
        return schemaStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Schema is not found. " + registryName + "/" + schemaName, 400));
    }

    private String[] parseSchemaArn(String arn) {
        int idx = arn.indexOf("schema/");
        if (idx < 0) {
            throw new AwsException("InvalidInputException", "Invalid schema ARN: " + arn, 400);
        }
        String tail = arn.substring(idx + "schema/".length());
        int slash = tail.indexOf('/');
        if (slash < 0 || slash == tail.length() - 1) {
            throw new AwsException("InvalidInputException", "Invalid schema ARN: " + arn, 400);
        }
        return new String[] { tail.substring(0, slash), tail.substring(slash + 1) };
    }

    private List<String> orderedDefinitions(String schemaKey) {
        NavigableMap<Long, String> byNumber = versionByNumber.get(schemaKey);
        if (byNumber == null) {
            return List.of();
        }
        List<String> defs = new ArrayList<>(byNumber.size());
        for (String id : byNumber.values()) {
            versionStore.get(id).ifPresent(v -> defs.add(v.getSchemaDefinition()));
        }
        return defs;
    }

    private List<Registry> sortedRegistries() {
        List<Registry> registries = new ArrayList<>(registryStore.scan(k -> true));
        registries.sort(Comparator.comparing(Registry::getRegistryName,
                Comparator.nullsLast(String::compareTo)));
        return registries;
    }

    private List<Schema> sortedSchemas(String prefix) {
        List<Schema> schemas = new ArrayList<>(schemaStore.scan(k -> k.startsWith(prefix)));
        schemas.sort(Comparator.comparing(Schema::getSchemaName,
                Comparator.nullsLast(String::compareTo)));
        return schemas;
    }

    private static <T> Page<T> paginate(List<T> all, Integer maxResults, String nextToken) {
        int limit = maxResults == null ? DEFAULT_MAX_RESULTS : maxResults;
        if (limit < 1 || limit > MAX_MAX_RESULTS) {
            throw new AwsException("InvalidInputException",
                    "MaxResults must be between 1 and " + MAX_MAX_RESULTS, 400);
        }
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidInputException", "Invalid NextToken", 400);
            }
            if (start < 0 || start > all.size()) {
                throw new AwsException("InvalidInputException", "Invalid NextToken", 400);
            }
        }
        int end = Math.min(start + limit, all.size());
        String newToken = end < all.size() ? String.valueOf(end) : null;
        return new Page<>(List.copyOf(all.subList(start, end)), newToken);
    }

    private void validateCheckpointVersionExists(Schema schema, long checkpointVersionNumber) {
        if (checkpointVersionNumber < 1) {
            throw new AwsException("InvalidInputException", "Schema checkpoint version must be at least 1", 400);
        }
        String key = schemaKey(schema.getRegistryName(), schema.getSchemaName());
        NavigableMap<Long, String> byNumber = versionByNumber.get(key);
        if (byNumber == null || !byNumber.containsKey(checkpointVersionNumber)) {
            throw new AwsException("EntityNotFoundException",
                    "Schema version " + checkpointVersionNumber + " not found for " + key, 400);
        }
    }

    private int deleteSchemaByKey(String key, Schema schema) {
        int versionsDeleted = 0;
        NavigableMap<Long, String> byNumber = versionByNumber.get(key);
        if (byNumber != null) {
            for (String versionId : new ArrayList<>(byNumber.values())) {
                versionStore.delete(versionId);
                metadataStore.delete(versionId);
                versionsDeleted++;
            }
        }
        versionByNumber.remove(key);
        versionByDefinitionHash.remove(key);
        schemaStore.delete(key);
        return versionsDeleted;
    }

    private void indexVersion(String schemaKey, SchemaVersion version) {
        versionByNumber
                .computeIfAbsent(schemaKey, k -> new ConcurrentSkipListMap<>())
                .put(version.getVersionNumber(), version.getSchemaVersionId());
        String hash = canonicalHash(version.getSchemaDefinition(), version.getDataFormat());
        versionByDefinitionHash
                .computeIfAbsent(schemaKey, k -> new ConcurrentHashMap<>())
                .put(hash, version.getSchemaVersionId());
    }

    private void rebuildVersionIndexes() {
        versionByNumber.clear();
        versionByDefinitionHash.clear();
        Map<String, String> arnToSchemaKey = new java.util.HashMap<>();
        for (Schema s : schemaStore.scan(k -> true)) {
            arnToSchemaKey.put(s.getSchemaArn(), schemaKey(s.getRegistryName(), s.getSchemaName()));
        }
        for (SchemaVersion v : versionStore.scan(k -> true)) {
            String schemaKey = arnToSchemaKey.get(v.getSchemaArn());
            if (schemaKey == null) {
                continue;
            }
            indexVersion(schemaKey, v);
        }
    }

    private static String canonicalHash(String definition, String dataFormat) {
        String canonical;
        try {
            canonical = SchemaCompatibilityChecker.canonicalize(definition, dataFormat);
        } catch (RuntimeException e) {
            canonical = definition;
        }
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Registry autoCreateDefaultRegistry(String region) {
        Registry registry = new Registry(DEFAULT_REGISTRY_NAME);
        registry.setRegistryArn(buildRegistryArn(region, DEFAULT_REGISTRY_NAME));
        registryStore.put(DEFAULT_REGISTRY_NAME, registry);
        LOG.infov("Auto-created default Glue Registry");
        return registry;
    }

    String resolveRegistryName(RegistryId registryId) {
        if (registryId == null) {
            return DEFAULT_REGISTRY_NAME;
        }
        if (registryId.getRegistryArn() != null && !registryId.getRegistryArn().isBlank()) {
            return parseRegistryNameFromArn(registryId.getRegistryArn());
        }
        if (registryId.getRegistryName() != null && !registryId.getRegistryName().isBlank()) {
            return registryId.getRegistryName();
        }
        return DEFAULT_REGISTRY_NAME;
    }

    private String parseRegistryNameFromArn(String arn) {
        int slash = arn.indexOf("registry/");
        if (slash < 0) {
            throw new AwsException("InvalidInputException", "Invalid registry ARN: " + arn, 400);
        }
        String name = arn.substring(slash + "registry/".length());
        if (name.isBlank()) {
            throw new AwsException("InvalidInputException", "Invalid registry ARN: " + arn, 400);
        }
        return name;
    }

    private String buildRegistryArn(String region, String name) {
        return regionResolver.buildArn("glue", region, "registry/" + name);
    }

    private String buildSchemaArn(String region, String registryName, String schemaName) {
        return regionResolver.buildArn("glue", region, "schema/" + registryName + "/" + schemaName);
    }

    private static String schemaKey(String registryName, String schemaName) {
        return registryName + ":" + schemaName;
    }

    private void validateName(String name, String field) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInputException", field + " is required", 400);
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new AwsException("InvalidInputException", field + " exceeds " + NAME_MAX_LENGTH + " characters", 400);
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("InvalidInputException",
                    field + " must match " + NAME_PATTERN.pattern(), 400);
        }
    }

    private void validateDataFormat(String dataFormat) {
        if (dataFormat == null || !DATA_FORMATS.contains(dataFormat)) {
            throw new AwsException("InvalidInputException",
                    "DataFormat must be one of " + DATA_FORMATS, 400);
        }
    }

    private void validateCompatibility(String compatibility) {
        if (!COMPATIBILITY_MODES.contains(compatibility)) {
            throw new AwsException("InvalidInputException",
                    "Compatibility must be one of " + COMPATIBILITY_MODES, 400);
        }
    }

    private void validateDefinitionRequired(String definition) {
        if (definition == null || definition.isBlank()) {
            throw new AwsException("InvalidInputException", "SchemaDefinition is required", 400);
        }
        if (definition.length() > MAX_SCHEMA_DEFINITION_LENGTH) {
            throw new AwsException("InvalidInputException",
                    "SchemaDefinition exceeds " + MAX_SCHEMA_DEFINITION_LENGTH + " characters", 400);
        }
    }
}
