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
        String key = buildPermissionsKey(catalogId, permissions);
        PrincipalResourcePermissions existing = permissionsStorage.get(key).orElse(null);
        if (existing != null) {
            if (permissions.getPermissions() != null) {
                List<String> current = new ArrayList<>(existing.getPermissions() != null ? existing.getPermissions() : List.of());
                for (String p : permissions.getPermissions()) {
                    if (!current.contains(p)) {
                        current.add(p);
                    }
                }
                existing.setPermissions(current);
            }
            if (permissions.getPermissionsWithGrantOption() != null) {
                List<String> current = new ArrayList<>(existing.getPermissionsWithGrantOption() != null ? existing.getPermissionsWithGrantOption() : List.of());
                for (String p : permissions.getPermissionsWithGrantOption()) {
                    if (!current.contains(p)) {
                        current.add(p);
                    }
                }
                existing.setPermissionsWithGrantOption(current);
            }
            permissionsStorage.put(key, existing);
        } else {
            permissionsStorage.put(key, permissions);
        }
    }

    @Override
    public void revokePermissions(String catalogId, PrincipalResourcePermissions permissions) {
        String key = buildPermissionsKey(catalogId, permissions);
        permissionsStorage.get(key).ifPresent(existing -> {
            boolean empty = true;
            if (existing.getPermissions() != null && permissions.getPermissions() != null) {
                List<String> current = new ArrayList<>(existing.getPermissions());
                current.removeAll(permissions.getPermissions());
                existing.setPermissions(current);
                if (!current.isEmpty()) {
                    empty = false;
                }
            } else if (existing.getPermissions() != null && !existing.getPermissions().isEmpty()) {
                empty = false;
            }
            
            if (existing.getPermissionsWithGrantOption() != null && permissions.getPermissionsWithGrantOption() != null) {
                List<String> current = new ArrayList<>(existing.getPermissionsWithGrantOption());
                current.removeAll(permissions.getPermissionsWithGrantOption());
                existing.setPermissionsWithGrantOption(current);
                if (!current.isEmpty()) {
                    empty = false;
                }
            } else if (existing.getPermissionsWithGrantOption() != null && !existing.getPermissionsWithGrantOption().isEmpty()) {
                empty = false;
            }

            if (empty) {
                permissionsStorage.delete(key);
            } else {
                permissionsStorage.put(key, existing);
            }
        });
    }

    @Override
    public List<PrincipalResourcePermissions> listPermissions(String catalogId, DataLakePrincipal principal, Resource resource, String resourceType, boolean includeRelated, Integer maxResults, String nextToken) {
        // Return all explicit grants for now to satisfy Terraform state syncing
        return new ArrayList<>(permissionsStorage.scan(k -> k.startsWith(catalogId + ":")));
    }

    @Override
    public void createLFTag(String catalogId, String tagKey, List<String> tagValues) {
        LFTag tag = new LFTag();
        tag.setTagKey(tagKey);
        tag.setTagValues(tagValues);
        lfTagsStorage.put(catalogId + ":" + tagKey, tag);
    }

    @Override
    public Optional<LFTag> getLFTag(String catalogId, String tagKey) {
        return lfTagsStorage.get(catalogId + ":" + tagKey);
    }

    @Override
    public void updateLFTag(String catalogId, String tagKey, List<String> tagValuesToAdd, List<String> tagValuesToDelete) {
        lfTagsStorage.get(catalogId + ":" + tagKey).ifPresent(tag -> {
            List<String> currentValues = new ArrayList<>(tag.getTagValues() != null ? tag.getTagValues() : List.of());
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
            lfTagsStorage.put(catalogId + ":" + tagKey, tag);
        });
    }

    @Override
    public void deleteLFTag(String catalogId, String tagKey) {
        lfTagsStorage.delete(catalogId + ":" + tagKey);
    }

    @Override
    public List<LFTagPair> listLFTags(String catalogId, String resourceShareType, Integer maxResults, String nextToken) {
        return lfTagsStorage.scan(k -> k.startsWith(catalogId + ":")).stream()
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
        String resourceKey = catalogId + ":" + getResourceKey(resource);
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
        String resourceKey = catalogId + ":" + getResourceKey(resource);
        resourceTagsStorage.get(resourceKey).ifPresent(currentTags -> {
            for (LFTagPair tagToRemove : lfTags) {
                for (LFTagPair currentTag : currentTags) {
                    if (currentTag.getTagKey().equals(tagToRemove.getTagKey())) {
                        if (currentTag.getTagValues() != null && tagToRemove.getTagValues() != null) {
                            List<String> updatedValues = new ArrayList<>(currentTag.getTagValues());
                            updatedValues.removeAll(tagToRemove.getTagValues());
                            currentTag.setTagValues(updatedValues);
                        }
                    }
                }
                currentTags.removeIf(t -> t.getTagValues() != null && t.getTagValues().isEmpty());
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
        if (r.getCatalog() != null) {
            return "catalog:" + r.getCatalog().getId();
        }
        if (r.getDatabase() != null) {
            return "database:" + r.getDatabase().getName();
        }
        if (r.getTable() != null) {
            return "table:" + r.getTable().getDatabaseName() + ":" + r.getTable().getName();
        }
        if (r.getTableWithColumns() != null) {
            StringBuilder sb = new StringBuilder("tableWithColumns:" + r.getTableWithColumns().getDatabaseName() + ":" + r.getTableWithColumns().getName());
            if (r.getTableWithColumns().getColumnNames() != null && !r.getTableWithColumns().getColumnNames().isEmpty()) {
                sb.append(":cols:").append(r.getTableWithColumns().getColumnNames().stream().sorted().collect(Collectors.joining(",")));
            } else if (r.getTableWithColumns().getColumnWildcard() != null) {
                sb.append(":cols:*");
                if (r.getTableWithColumns().getColumnWildcard().getExcludedColumnNames() != null && !r.getTableWithColumns().getColumnWildcard().getExcludedColumnNames().isEmpty()) {
                    sb.append(":excl:").append(r.getTableWithColumns().getColumnWildcard().getExcludedColumnNames().stream().sorted().collect(Collectors.joining(",")));
                }
            }
            return sb.toString();
        }
        if (r.getDataLocation() != null) {
            return "dataLocation:" + r.getDataLocation().getResourceArn();
        }
        if (r.getDataCellsFilter() != null) {
            return "dataCellsFilter:" + r.getDataCellsFilter().getTableCatalogId() + ":" + r.getDataCellsFilter().getDatabaseName() + ":" + r.getDataCellsFilter().getTableName() + ":" + r.getDataCellsFilter().getName();
        }
        if (r.getLfTag() != null) {
            StringBuilder sb = new StringBuilder("lfTag:" + r.getLfTag().getTagKey());
            if (r.getLfTag().getTagValues() != null) {
                sb.append("=").append(r.getLfTag().getTagValues().stream().map(v -> "\"" + v + "\"").sorted().collect(Collectors.joining(",")));
            }
            return sb.toString();
        }
        if (r.getLfTagExpression() != null) {
            return "lfTagExpression:" + r.getLfTagExpression().getName();
        }
        if (r.getLfTagPolicy() != null) {
            StringBuilder sb = new StringBuilder("lfTagPolicy:" + r.getLfTagPolicy().getResourceType());
            if (r.getLfTagPolicy().getExpressionName() != null) {
                sb.append(":exprName:").append(r.getLfTagPolicy().getExpressionName());
            }
            if (r.getLfTagPolicy().getExpression() != null) {
                r.getLfTagPolicy().getExpression().stream()
                        .sorted(java.util.Comparator.comparing(io.github.hectorvent.floci.services.lakeformation.model.LFTag::getTagKey))
                        .forEach(tag -> {
                            sb.append(":tag:").append(tag.getTagKey()).append("=");
                            if (tag.getTagValues() != null) {
                                sb.append(tag.getTagValues().stream().sorted().collect(Collectors.joining(",")));
                            }
                        });
            }
            return sb.toString();
        }
        return "unknown";
    }
}
