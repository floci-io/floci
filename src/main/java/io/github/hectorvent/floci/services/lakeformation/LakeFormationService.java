package io.github.hectorvent.floci.services.lakeformation;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lakeformation.model.DataCellsFilter;
import io.github.hectorvent.floci.services.lakeformation.model.DataLakeSettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class LakeFormationService {
    private final StorageBackend<String, DataLakeSettings> settingsStore;
    private final StorageBackend<String, Map<String, String>> tagsStore;
    private final StorageBackend<String, DataCellsFilter> dataCellsFilterStore;
    private final StorageBackend<String, List<Map<String, Object>>> permissionsStore;
    private final StorageBackend<String, List<Map<String, Object>>> lfTagsStore;
    private final StorageBackend<String, List<Map<String, Object>>> lfTagExpressionsStore;
    private final StorageBackend<String, List<Map<String, Object>>> resourceTagsStore;
    private final StorageBackend<String, List<Map<String, Object>>> optInStore;
    private final StorageBackend<String, List<Map<String, Object>>> resourceStore;
    private final StorageBackend<String, Map<String, Object>> identityCenterConfigStore;
    private final RegionResolver regionResolver;

    @Inject
    public LakeFormationService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(settingsStoreFrom(storageFactory, "datalake-settings.json", new TypeReference<Map<String, DataLakeSettings>>() {}),
                tagsStoreFrom(storageFactory, "tags.json", new TypeReference<Map<String, Map<String, String>>>() {}),
                dataCellsFilterStoreFrom(storageFactory, "data-cells-filters.json", new TypeReference<Map<String, DataCellsFilter>>() {}),
                permissionsStoreFrom(storageFactory, "permissions.json", new TypeReference<Map<String, List<Map<String, Object>>>>() {}),
                lfTagsStoreFrom(storageFactory, "lftags.json", new TypeReference<Map<String, List<Map<String, Object>>>>() {}),
                lfTagExpressionsStoreFrom(storageFactory, "lftag-expressions.json", new TypeReference<Map<String, List<Map<String, Object>>>>() {}),
                resourceTagsStoreFrom(storageFactory, "resource-tags.json", new TypeReference<Map<String, List<Map<String, Object>>>>() {}),
                optInStoreFrom(storageFactory, "opt-ins.json", new TypeReference<Map<String, List<Map<String, Object>>>>() {}),
                resourceStoreFrom(storageFactory, "resources.json", new TypeReference<Map<String, List<Map<String, Object>>>>() {}),
                identityCenterConfigStoreFrom(storageFactory, "identity-center-config.json", new TypeReference<Map<String, Map<String, Object>>>() {}),
                regionResolver);
    }

    LakeFormationService(StorageBackend<String, DataLakeSettings> settingsStore,
                         StorageBackend<String, Map<String, String>> tagsStore,
                         StorageBackend<String, DataCellsFilter> dataCellsFilterStore,
                         RegionResolver regionResolver) {
        this(settingsStore, tagsStore, dataCellsFilterStore, null, null, null, null, null, null, null, regionResolver);
    }

    LakeFormationService(StorageBackend<String, DataLakeSettings> settingsStore,
                         StorageBackend<String, Map<String, String>> tagsStore,
                         StorageBackend<String, DataCellsFilter> dataCellsFilterStore,
                         StorageBackend<String, List<Map<String, Object>>> permissionsStore,
                         StorageBackend<String, List<Map<String, Object>>> lfTagsStore,
                         StorageBackend<String, List<Map<String, Object>>> lfTagExpressionsStore,
                         StorageBackend<String, List<Map<String, Object>>> resourceTagsStore,
                         StorageBackend<String, List<Map<String, Object>>> optInStore,
                         StorageBackend<String, List<Map<String, Object>>> resourceStore,
                         StorageBackend<String, Map<String, Object>> identityCenterConfigStore,
                         RegionResolver regionResolver) {
        this.settingsStore = settingsStore;
        this.tagsStore = tagsStore;
        this.dataCellsFilterStore = dataCellsFilterStore;
        this.permissionsStore = permissionsStore;
        this.lfTagsStore = lfTagsStore;
        this.lfTagExpressionsStore = lfTagExpressionsStore;
        this.resourceTagsStore = resourceTagsStore;
        this.optInStore = optInStore;
        this.resourceStore = resourceStore;
        this.identityCenterConfigStore = identityCenterConfigStore;
        this.regionResolver = regionResolver;
    }

    public DataLakeSettings getDataLakeSettings(String region) {
        return settingsStore.get(settingsKey(region)).orElseGet(() -> emptySettings());
    }

    public void putDataLakeSettings(String region, DataLakeSettings settings) {
        if (settings == null) {
            throw new AwsException("InvalidInputException", "DataLakeSettings is required", 400);
        }
        DataLakeSettings normalized = normalizeSettings(settings);
        settingsStore.put(settingsKey(region), normalized);
    }

    private DataLakeSettings emptySettings() {
        DataLakeSettings settings = new DataLakeSettings();
        settings.setCreateTableDefaultPermissions(new ArrayList<>());
        settings.setTrustedResourceOwners(new ArrayList<>());
        return settings;
    }

    private DataLakeSettings normalizeSettings(DataLakeSettings settings) {
        DataLakeSettings normalized = new DataLakeSettings();
        normalized.setCreateTableDefaultPermissions(settings.getCreateTableDefaultPermissions() == null
                ? new ArrayList<>()
                : settings.getCreateTableDefaultPermissions().stream()
                .filter(Objects::nonNull)
                .toList());
        normalized.setTrustedResourceOwners(settings.getTrustedResourceOwners() == null
                ? new ArrayList<>()
                : settings.getTrustedResourceOwners().stream().filter(Objects::nonNull).toList());
        return normalized;
    }

    public void tagResource(String region, String resourceArn, Map<String, String> tags) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidInputException", "ResourceArn is required", 400);
        }
        Map<String, String> currentTags = tagsStore.get(resourceKey(region, resourceArn)).orElseGet(HashMap::new);
        if (tags != null) {
            currentTags.putAll(tags);
        }
        tagsStore.put(resourceKey(region, resourceArn), currentTags);
    }

    public void untagResource(String region, String resourceArn, List<String> tagKeys) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidInputException", "ResourceArn is required", 400);
        }
        Map<String, String> currentTags = tagsStore.get(resourceKey(region, resourceArn)).orElseGet(HashMap::new);
        if (tagKeys != null) {
            tagKeys.forEach(currentTags::remove);
        }
        tagsStore.put(resourceKey(region, resourceArn), currentTags);
    }

    public Map<String, String> listTagsForResource(String region, String resourceArn) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw new AwsException("InvalidInputException", "ResourceArn is required", 400);
        }
        return tagsStore.get(resourceKey(region, resourceArn)).orElseGet(HashMap::new);
    }

    public void putDataCellsFilter(String region, DataCellsFilter dataCellsFilter) {
        if (dataCellsFilter == null) {
            throw new AwsException("InvalidInputException", "DataCellsFilter is required", 400);
        }
        dataCellsFilterStore.put(dataCellsFilterKey(region, dataCellsFilter), dataCellsFilter);
    }

    public DataCellsFilter getDataCellsFilter(String region, String databaseName, String name, String tableCatalogId, String tableName) {
        return dataCellsFilterStore.get(dataCellsFilterKey(region, databaseName, name, tableCatalogId, tableName)).orElse(null);
    }

    public void deleteDataCellsFilter(String region, String databaseName, String name, String tableCatalogId, String tableName) {
        dataCellsFilterStore.delete(dataCellsFilterKey(region, databaseName, name, tableCatalogId, tableName));
    }

    public List<Map<String, Object>> grantPermissions(String region, Map<String, Object> request) {
        List<Map<String, Object>> entries = readList(permissionsStore, permissionsKey(region));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("Principal", request.get("Principal"));
        entry.put("Resource", request.get("Resource"));
        entry.put("Permissions", request.get("Permissions"));
        entry.put("PermissionsWithGrantOption", request.get("PermissionsWithGrantOption"));
        entry.put("CatalogId", request.get("CatalogId"));
        entries.add(entry);
        permissionsStore.put(permissionsKey(region), entries);
        return entries;
    }

    public List<Map<String, Object>> revokePermissions(String region, Map<String, Object> request) {
        List<Map<String, Object>> entries = readList(permissionsStore, permissionsKey(region));
        entries.removeIf(existing -> samePermission(existing, request));
        permissionsStore.put(permissionsKey(region), entries);
        return entries;
    }

    public List<Map<String, Object>> listPermissions(String region, Map<String, Object> request) {
        List<Map<String, Object>> entries = readList(permissionsStore, permissionsKey(region));
        if (request == null || request.isEmpty()) {
            return entries;
        }
        return entries.stream()
                .filter(existing -> matchesPermission(existing, request))
                .toList();
    }

    public Map<String, Object> getDataLakePrincipal(String region, String dataLakePrincipalIdentifier) {
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("DataLakePrincipalIdentifier", dataLakePrincipalIdentifier);
        principal.put("DataLakePrincipalType", "IAM");
        return principal;
    }

    public Map<String, Object> createIdentityCenterConfiguration(String region, Map<String, Object> request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("CatalogId", request.get("CatalogId"));
        config.put("ExternalFilteringConfiguration", request.get("ExternalFilteringConfiguration"));
        identityCenterConfigStore.put(identityCenterConfigKey(region), config);
        return config;
    }

    public Map<String, Object> describeIdentityCenterConfiguration(String region) {
        return identityCenterConfigStore.get(identityCenterConfigKey(region)).orElseGet(LinkedHashMap::new);
    }

    public Map<String, Object> updateIdentityCenterConfiguration(String region, Map<String, Object> request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("CatalogId", request.get("CatalogId"));
        config.put("ExternalFilteringConfiguration", request.get("ExternalFilteringConfiguration"));
        identityCenterConfigStore.put(identityCenterConfigKey(region), config);
        return config;
    }

    public void deleteIdentityCenterConfiguration(String region) {
        identityCenterConfigStore.delete(identityCenterConfigKey(region));
    }

    public Map<String, Object> createHybridOptIn(String region, Map<String, Object> request) {
        List<Map<String, Object>> entries = readList(optInStore, optInKey(region));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("CatalogId", request.get("CatalogId"));
        entry.put("PrincipalIdentifier", request.get("PrincipalIdentifier"));
        entries.add(entry);
        optInStore.put(optInKey(region), entries);
        return entry;
    }

    public List<Map<String, Object>> listHybridOptIns(String region) {
        return readList(optInStore, optInKey(region));
    }

    public void deleteHybridOptIn(String region, String principalIdentifier) {
        List<Map<String, Object>> entries = readList(optInStore, optInKey(region));
        entries.removeIf(existing -> Objects.equals(existing.get("PrincipalIdentifier"), principalIdentifier));
        optInStore.put(optInKey(region), entries);
    }

    public Map<String, Object> registerResource(String region, Map<String, Object> request) {
        List<Map<String, Object>> entries = readList(resourceStore, resourceKey(region, "resources"));
        String arn = (String) request.get("ResourceArn");
        if (entries.stream().anyMatch(existing -> Objects.equals(existing.get("ResourceArn"), arn))) {
            throw new AwsException("AlreadyExistsException", "Resource with ARN " + arn + " already exists", 400);
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ResourceArn", arn);
        entry.put("RoleArn", request.get("RoleArn"));
        entry.put("UseServiceLinkedRole", request.get("UseServiceLinkedRole"));
        entries.add(entry);
        resourceStore.put(resourceKey(region, "resources"), entries);
        return entry;
    }

    public List<Map<String, Object>> listResources(String region) {
        return readList(resourceStore, resourceKey(region, "resources"));
    }

    public Map<String, Object> updateResource(String region, Map<String, Object> request) {
        List<Map<String, Object>> entries = readList(resourceStore, resourceKey(region, "resources"));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ResourceArn", request.get("ResourceArn"));
        entry.put("RoleArn", request.get("RoleArn"));
        entry.put("UseServiceLinkedRole", request.get("UseServiceLinkedRole"));
        entries.removeIf(existing -> Objects.equals(existing.get("ResourceArn"), request.get("ResourceArn")));
        entries.add(entry);
        resourceStore.put(resourceKey(region, "resources"), entries);
        return entry;
    }

    public void deregisterResource(String region, String resourceArn) {
        List<Map<String, Object>> entries = readList(resourceStore, resourceKey(region, "resources"));
        entries.removeIf(existing -> Objects.equals(existing.get("ResourceArn"), resourceArn));
        resourceStore.put(resourceKey(region, "resources"), entries);
    }

    public Map<String, Object> getTemporaryGlueTableCredentials(String region) {
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("AccessKeyId", "temporary-access-key");
        credentials.put("SecretAccessKey", "temporary-secret-key");
        credentials.put("SessionToken", "temporary-session-token");
        credentials.put("Expiration", "2099-01-01T00:00:00Z");
        return credentials;
    }

    public Map<String, Object> getTemporaryGluePartitionCredentials(String region) {
        return getTemporaryGlueTableCredentials(region);
    }

    public Map<String, Object> createLFTag(String region, Map<String, Object> request) {
        List<Map<String, Object>> tags = readList(lfTagsStore, lfTagKey(region));
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("CatalogId", request.get("CatalogId"));
        tag.put("TagKey", request.get("TagKey"));
        tag.put("TagValues", request.get("TagValues"));
        tags.removeIf(existing -> Objects.equals(existing.get("TagKey"), request.get("TagKey")));
        tags.add(tag);
        lfTagsStore.put(lfTagKey(region), tags);
        return tag;
    }

    public Map<String, Object> getLFTag(String region, String tagKey) {
        return readList(lfTagsStore, lfTagKey(region)).stream()
                .filter(existing -> Objects.equals(existing.get("TagKey"), tagKey))
                .findFirst()
                .orElseGet(LinkedHashMap::new);
    }

    public Map<String, Object> updateLFTag(String region, Map<String, Object> request) {
        return createLFTag(region, request);
    }

    public void deleteLFTag(String region, String tagKey) {
        List<Map<String, Object>> tags = readList(lfTagsStore, lfTagKey(region));
        tags.removeIf(existing -> Objects.equals(existing.get("TagKey"), tagKey));
        lfTagsStore.put(lfTagKey(region), tags);
    }

    public List<Map<String, Object>> listLFTags(String region) {
        return readList(lfTagsStore, lfTagKey(region));
    }

    public Map<String, Object> createLFTagExpression(String region, Map<String, Object> request) {
        List<Map<String, Object>> expressions = readList(lfTagExpressionsStore, lfTagExpressionKey(region));
        Map<String, Object> expression = new LinkedHashMap<>();
        expression.put("CatalogId", request.get("CatalogId"));
        expression.put("Name", request.get("Name"));
        expression.put("Expression", request.get("Expression"));
        expression.put("Description", request.get("Description"));
        expressions.removeIf(existing -> Objects.equals(existing.get("Name"), request.get("Name")));
        expressions.add(expression);
        lfTagExpressionsStore.put(lfTagExpressionKey(region), expressions);
        return expression;
    }

    public Map<String, Object> getLFTagExpression(String region, String name) {
        return readList(lfTagExpressionsStore, lfTagExpressionKey(region)).stream()
                .filter(existing -> Objects.equals(existing.get("Name"), name))
                .findFirst()
                .orElseGet(LinkedHashMap::new);
    }

    public Map<String, Object> updateLFTagExpression(String region, Map<String, Object> request) {
        return createLFTagExpression(region, request);
    }

    public void deleteLFTagExpression(String region, String name) {
        List<Map<String, Object>> expressions = readList(lfTagExpressionsStore, lfTagExpressionKey(region));
        expressions.removeIf(existing -> Objects.equals(existing.get("Name"), name));
        lfTagExpressionsStore.put(lfTagExpressionKey(region), expressions);
    }

    public List<Map<String, Object>> listLFTagExpressions(String region) {
        return readList(lfTagExpressionsStore, lfTagExpressionKey(region));
    }

    public List<Map<String, Object>> addLFTagsToResource(String region, String resourceArn, List<Map<String, Object>> tags) {
        List<Map<String, Object>> stored = readList(resourceTagsStore, resourceTagsKey(region, resourceArn));
        if (tags != null) {
            stored.addAll(tags);
        }
        resourceTagsStore.put(resourceTagsKey(region, resourceArn), stored);
        return stored;
    }

    public List<Map<String, Object>> removeLFTagsFromResource(String region, String resourceArn, List<String> tagKeys) {
        List<Map<String, Object>> stored = readList(resourceTagsStore, resourceTagsKey(region, resourceArn));
        if (tagKeys != null) {
            stored.removeIf(existing -> tagKeys.contains(existing.get("TagKey")) || tagKeys.contains(existing.get("Key")));
        }
        resourceTagsStore.put(resourceTagsKey(region, resourceArn), stored);
        return stored;
    }

    public List<Map<String, Object>> getResourceLFTags(String region, String resourceArn) {
        return readList(resourceTagsStore, resourceTagsKey(region, resourceArn));
    }

    public Map<String, Object> searchTablesByLFTags(String region, Map<String, Object> request) {
        return Map.of("TaggedTables", List.of(), "TaggedDatabases", List.of());
    }

    public Map<String, Object> searchDatabasesByLFTags(String region, Map<String, Object> request) {
        return Map.of("TaggedDatabases", List.of());
    }

    private boolean samePermission(Map<String, Object> existing, Map<String, Object> request) {
        return Objects.equals(existing.get("CatalogId"), request.get("CatalogId"))
                && Objects.equals(existing.get("Principal"), request.get("Principal"))
                && Objects.equals(existing.get("Resource"), request.get("Resource"));
    }

    private boolean matchesPermission(Map<String, Object> existing, Map<String, Object> request) {
        return (request.get("CatalogId") == null || Objects.equals(existing.get("CatalogId"), request.get("CatalogId")))
                && (request.get("Principal") == null || Objects.equals(existing.get("Principal"), request.get("Principal")))
                && (request.get("Resource") == null || Objects.equals(existing.get("Resource"), request.get("Resource")));
    }

    private List<Map<String, Object>> readList(StorageBackend<String, List<Map<String, Object>>> store, String key) {
        return store.get(key).orElseGet(ArrayList::new);
    }

    private static StorageBackend<String, DataLakeSettings> settingsStoreFrom(StorageFactory storageFactory, String fileName, TypeReference<Map<String, DataLakeSettings>> typeReference) {
        return storageFactory.create("lakeformation", fileName, typeReference);
    }

    private static StorageBackend<String, Map<String, String>> tagsStoreFrom(StorageFactory storageFactory, String fileName, TypeReference<Map<String, Map<String, String>>> typeReference) {
        return storageFactory.create("lakeformation", fileName, typeReference);
    }

    private static StorageBackend<String, DataCellsFilter> dataCellsFilterStoreFrom(StorageFactory storageFactory, String fileName, TypeReference<Map<String, DataCellsFilter>> typeReference) {
        return storageFactory.create("lakeformation", fileName, typeReference);
    }

    private static StorageBackend<String, List<Map<String, Object>>> permissionsStoreFrom(StorageFactory storageFactory, String fileName, TypeReference<Map<String, List<Map<String, Object>>>> typeReference) {
        return storageFactory.create("lakeformation", fileName, typeReference);
    }

    private static StorageBackend<String, List<Map<String, Object>>> lfTagsStoreFrom(StorageFactory storageFactory, String fileName, TypeReference<Map<String, List<Map<String, Object>>>> typeReference) {
        return storageFactory.create("lakeformation", fileName, typeReference);
    }

    private static StorageBackend<String, List<Map<String, Object>>> lfTagExpressionsStoreFrom(StorageFactory storageFactory, String fileName, TypeReference<Map<String, List<Map<String, Object>>>> typeReference) {
        return storageFactory.create("lakeformation", fileName, typeReference);
    }

    private static StorageBackend<String, List<Map<String, Object>>> resourceTagsStoreFrom(StorageFactory storageFactory, String fileName, TypeReference<Map<String, List<Map<String, Object>>>> typeReference) {
        return storageFactory.create("lakeformation", fileName, typeReference);
    }

    private static StorageBackend<String, List<Map<String, Object>>> optInStoreFrom(StorageFactory storageFactory, String fileName, TypeReference<Map<String, List<Map<String, Object>>>> typeReference) {
        return storageFactory.create("lakeformation", fileName, typeReference);
    }

    private static StorageBackend<String, List<Map<String, Object>>> resourceStoreFrom(StorageFactory storageFactory, String fileName, TypeReference<Map<String, List<Map<String, Object>>>> typeReference) {
        return storageFactory.create("lakeformation", fileName, typeReference);
    }

    private static StorageBackend<String, Map<String, Object>> identityCenterConfigStoreFrom(StorageFactory storageFactory, String fileName, TypeReference<Map<String, Map<String, Object>>> typeReference) {
        return storageFactory.create("lakeformation", fileName, typeReference);
    }

    private String settingsKey(String region) {
        String resolvedRegion = region == null ? regionResolver.getDefaultRegion() : region;
        return resolvedRegion + ":settings";
    }

    private String resourceKey(String region, String resourceArn) {
        String resolvedRegion = region == null ? regionResolver.getDefaultRegion() : region;
        return resolvedRegion + ":" + resourceArn;
    }

    private String permissionsKey(String region) {
        String resolvedRegion = region == null ? regionResolver.getDefaultRegion() : region;
        return resolvedRegion + ":permissions";
    }

    private String identityCenterConfigKey(String region) {
        String resolvedRegion = region == null ? regionResolver.getDefaultRegion() : region;
        return resolvedRegion + ":identity-center";
    }

    private String optInKey(String region) {
        String resolvedRegion = region == null ? regionResolver.getDefaultRegion() : region;
        return resolvedRegion + ":opt-ins";
    }

    private String resourceTagsKey(String region, String resourceArn) {
        return resourceKey(region, resourceArn) + ":tags";
    }

    private String lfTagKey(String region) {
        String resolvedRegion = region == null ? regionResolver.getDefaultRegion() : region;
        return resolvedRegion + ":lftags";
    }

    private String lfTagExpressionKey(String region) {
        String resolvedRegion = region == null ? regionResolver.getDefaultRegion() : region;
        return resolvedRegion + ":lftag-expressions";
    }

    private String dataCellsFilterKey(String region, DataCellsFilter dataCellsFilter) {
        return dataCellsFilterKey(region, dataCellsFilter.getDatabaseName(), dataCellsFilter.getName(), dataCellsFilter.getTableCatalogId(), dataCellsFilter.getTableName());
    }

    private String dataCellsFilterKey(String region, String databaseName, String name, String tableCatalogId, String tableName) {
        String resolvedRegion = region == null ? regionResolver.getDefaultRegion() : region;
        return resolvedRegion + ":" + databaseName + ":" + name + ":" + tableCatalogId + ":" + tableName;
    }
}
