package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lakeformation.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class MemoryLakeFormationStorage implements LakeFormationStorage {

    private final AccountAwareStorageBackend<DataLakeSettings> settingsStorage;
    private final AccountAwareStorageBackend<ResourceInfo> resourcesStorage;
    private final AccountAwareStorageBackend<PrincipalResourcePermissions> permissionsStorage;
    private final AccountAwareStorageBackend<LFTag> lfTagsStorage;
    private final AccountAwareStorageBackend<List<LFTagPair>> resourceTagsStorage;

    @Inject
    public MemoryLakeFormationStorage(StorageFactory storageFactory) {
        this.settingsStorage = storageFactory.create("lakeformation", "lakeformation-settings.json", new TypeReference<>() {});
        this.resourcesStorage = storageFactory.create("lakeformation", "lakeformation-resources.json", new TypeReference<>() {});
        this.permissionsStorage = storageFactory.create("lakeformation", "lakeformation-permissions.json", new TypeReference<>() {});
        this.lfTagsStorage = storageFactory.create("lakeformation", "lakeformation-tags.json", new TypeReference<>() {});
        this.resourceTagsStorage = storageFactory.create("lakeformation", "lakeformation-resource-tags.json", new TypeReference<>() {});
    }

    @Override
    public void putDataLakeSettings(String catalogId, DataLakeSettings settings) {
        settingsStorage.put(catalogId, settings);
    }

    @Override
    public Optional<DataLakeSettings> getDataLakeSettings(String catalogId) {
        return settingsStorage.get(catalogId);
    }

    @Override
    public void registerResource(String resourceArn, String roleArn, boolean useServiceLinkedRole, Boolean withFederation) {
        ResourceInfo info = new ResourceInfo();
        info.setResourceArn(resourceArn);
        info.setRoleArn(roleArn);
        info.setWithFederation(withFederation);
        // Use arn as key for storage
        resourcesStorage.put(resourceArn, info);
    }

    @Override
    public void deregisterResource(String resourceArn) {
        resourcesStorage.delete(resourceArn);
    }

    @Override
    public List<ResourceInfo> listResources(FilterCondition filterCondition, Integer maxResults, String nextToken) {
        // Very basic pagination: returns all resources for now
        // In a real implementation this would apply the filter and handle pagination tokens.
        return new ArrayList<>(resourcesStorage.scan(k -> true));
    }

    @Override
    public Optional<ResourceInfo> describeResource(String resourceArn) {
        return resourcesStorage.get(resourceArn);
    }

    @Override
    public void grantPermissions(String catalogId, PrincipalResourcePermissions permissions) {
        // We use a composite key for permissions to keep them unique per principal/resource combination
        String key = buildPermissionsKey(catalogId, permissions);
        permissionsStorage.put(key, permissions);
    }

    @Override
    public void revokePermissions(String catalogId, PrincipalResourcePermissions permissions) {
        String key = buildPermissionsKey(catalogId, permissions);
        permissionsStorage.delete(key);
    }

    @Override
    public List<PrincipalResourcePermissions> listPermissions(String catalogId, DataLakePrincipal principal, Resource resource, String resourceType, boolean includeRelated, Integer maxResults, String nextToken) {
        // Return all explicit grants for now to satisfy Terraform state syncing
        return new ArrayList<>(permissionsStorage.scan(k -> true));
    }

    @Override
    public void createLFTag(String catalogId, String tagKey, List<String> tagValues) {
        LFTag tag = new LFTag();
        tag.setTagKey(tagKey);
        tag.setTagValues(tagValues);
        lfTagsStorage.put(tagKey, tag);
    }

    @Override
    public Optional<LFTag> getLFTag(String catalogId, String tagKey) {
        return lfTagsStorage.get(tagKey);
    }

    @Override
    public void updateLFTag(String catalogId, String tagKey, List<String> tagValuesToAdd, List<String> tagValuesToDelete) {
        lfTagsStorage.get(tagKey).ifPresent(tag -> {
            List<String> currentValues = new ArrayList<>(tag.getTagValues());
            if (tagValuesToDelete != null) {
                currentValues.removeAll(tagValuesToDelete);
            }
            if (tagValuesToAdd != null) {
                for (String val : tagValuesToAdd) {
                    if (!currentValues.contains(val)) {
                        currentValues.add(val);
                    }
                }
            }
            tag.setTagValues(currentValues);
            lfTagsStorage.put(tagKey, tag);
        });
    }

    @Override
    public void deleteLFTag(String catalogId, String tagKey) {
        lfTagsStorage.delete(tagKey);
    }

    @Override
    public List<LFTagPair> listLFTags(String catalogId, String resourceShareType, Integer maxResults, String nextToken) {
        return lfTagsStorage.scan(k -> true).stream()
                .map(tag -> {
                    LFTagPair pair = new LFTagPair();
                    pair.setCatalogId(catalogId);
                    pair.setTagKey(tag.getTagKey());
                    pair.setTagValues(tag.getTagValues());
                    return pair;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void addLFTagsToResource(String catalogId, Resource resource, List<LFTagPair> lfTags) {
        String resourceKey = getResourceKey(resource);
        List<LFTagPair> currentTags = resourceTagsStorage.get(resourceKey).orElse(new ArrayList<>());
        
        for (LFTagPair newTag : lfTags) {
            // Remove existing tag with same key if it exists, to replace values
            currentTags.removeIf(t -> t.getTagKey().equals(newTag.getTagKey()));
            currentTags.add(newTag);
        }
        
        resourceTagsStorage.put(resourceKey, currentTags);
    }

    @Override
    public void removeLFTagsFromResource(String catalogId, Resource resource, List<LFTagPair> lfTags) {
        String resourceKey = getResourceKey(resource);
        resourceTagsStorage.get(resourceKey).ifPresent(currentTags -> {
            for (LFTagPair tagToRemove : lfTags) {
                currentTags.removeIf(t -> t.getTagKey().equals(tagToRemove.getTagKey()));
            }
            resourceTagsStorage.put(resourceKey, currentTags);
        });
    }

    private String buildPermissionsKey(String catalogId, PrincipalResourcePermissions p) {
        String principalId = p.getPrincipal() != null ? p.getPrincipal().getDataLakePrincipalIdentifier() : "unknown";
        String resourceKey = p.getResource() != null ? getResourceKey(p.getResource()) : "unknown";
        return catalogId + ":" + principalId + ":" + resourceKey;
    }

    private String getResourceKey(Resource r) {
        // Basic unique string representation of the resource union
        if (r.getCatalog() != null) return "catalog:" + r.getCatalog().getId();
        if (r.getDatabase() != null) return "database:" + r.getDatabase().getName();
        if (r.getTable() != null) return "table:" + r.getTable().getDatabaseName() + ":" + r.getTable().getName();
        if (r.getTableWithColumns() != null) return "tableWithColumns:" + r.getTableWithColumns().getDatabaseName() + ":" + r.getTableWithColumns().getName();
        if (r.getDataLocation() != null) return "dataLocation:" + r.getDataLocation().getResourceArn();
        if (r.getDataCellsFilter() != null) return "dataCellsFilter:" + r.getDataCellsFilter().getDatabaseName() + ":" + r.getDataCellsFilter().getTableName() + ":" + r.getDataCellsFilter().getName();
        if (r.getLfTag() != null) return "lfTag:" + r.getLfTag().getTagKey();
        if (r.getLfTagPolicy() != null) return "lfTagPolicy:" + r.getLfTagPolicy().getResourceType();
        return "unknown";
    }
}
