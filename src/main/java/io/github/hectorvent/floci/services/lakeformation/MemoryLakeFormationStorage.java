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
    public void putDataLakeSettings(String region, String catalogId, DataLakeSettings settings) {
        settingsStorage.put(region + ":" + catalogId, settings);
    }

    @Override
    public Optional<DataLakeSettings> getDataLakeSettings(String region, String catalogId) {
        return settingsStorage.get(region + ":" + catalogId);
    }

    @Override
    public synchronized void registerResource(String region, String resourceArn, String roleArn, boolean useServiceLinkedRole, Boolean withFederation) {
        ResourceInfo info = new ResourceInfo();
        info.setResourceArn(resourceArn);
        info.setRoleArn(roleArn);
        info.setWithFederation(withFederation);
        // Use arn as key for storage
        resourcesStorage.put(region + ":" + resourceArn, info);
    }

    @Override
    public synchronized void updateResource(String region, String resourceArn, String roleArn, String expectedResourceOwnerAccount,
                               Boolean hybridAccessEnabled, Boolean withFederation) {
        String resourceKey = region + ":" + resourceArn;
        ResourceInfo existing = resourcesStorage.get(resourceKey)
                .orElseThrow(() -> new io.github.hectorvent.floci.core.common.AwsException(
                        "EntityNotFoundException", "Resource not found", 400));
        // Work on a defensive copy so concurrent readers (DescribeResource, ListResources)
        // never observe a partially-mutated ResourceInfo: they see either the old complete
        // state or the new complete state, never a mix of field values.
        ResourceInfo updated = copyOf(existing);
        updated.setRoleArn(roleArn);
        if (expectedResourceOwnerAccount != null) {
            updated.setExpectedResourceOwnerAccount(expectedResourceOwnerAccount);
        }
        if (hybridAccessEnabled != null) {
            updated.setHybridAccessEnabled(hybridAccessEnabled);
        }
        if (withFederation != null) {
            updated.setWithFederation(withFederation);
        }
        resourcesStorage.put(resourceKey, updated);
    }

    private static ResourceInfo copyOf(ResourceInfo source) {
        ResourceInfo copy = new ResourceInfo();
        copy.setExpectedResourceOwnerAccount(source.getExpectedResourceOwnerAccount());
        copy.setHybridAccessEnabled(source.getHybridAccessEnabled());
        copy.setLastModified(source.getLastModified());
        copy.setResourceArn(source.getResourceArn());
        copy.setRoleArn(source.getRoleArn());
        copy.setVerificationStatus(source.getVerificationStatus());
        copy.setWithFederation(source.getWithFederation());
        copy.setWithPrivilegedAccess(source.getWithPrivilegedAccess());
        return copy;
    }

    @Override
    public synchronized void deregisterResource(String region, String resourceArn) {
        resourcesStorage.delete(region + ":" + resourceArn);
    }

    @Override
    public List<ResourceInfo> listResources(String region, List<FilterCondition> filterConditions, Integer maxResults, String nextToken) {
        // Deliberate omission: MaxResults and NextToken are ignored. All results fit on a single page.
        return resourcesStorage.scan(k -> k.startsWith(region + ":")).stream()
                .filter(r -> {
                    if (filterConditions != null) {
                        for (FilterCondition filterCondition : filterConditions) {
                            if ("RESOURCE_ARN".equals(filterCondition.getField())) {
                                String arn = r.getResourceArn();
                                List<String> values = filterCondition.getStringValueList();
                                if (values != null && !values.isEmpty()) {
                                    String op = filterCondition.getComparisonOperator();
                                    if ("EQ".equals(op)) {
                                        if (!values.contains(arn)) return false;
                                    } else if ("NE".equals(op)) {
                                        if (values.contains(arn)) return false;
                                    } else if ("BEGINS_WITH".equals(op)) {
                                        if (values.stream().noneMatch(arn::startsWith)) return false;
                                    } else {
                                        throw new io.github.hectorvent.floci.core.common.AwsException("InvalidInputException", "Unsupported ComparisonOperator: " + op, 400);
                                    }
                                }
                            }
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    @Override
    public Optional<ResourceInfo> describeResource(String region, String resourceArn) {
        return resourcesStorage.get(region + ":" + resourceArn);
    }

    @Override
    public void grantPermissions(String region, String catalogId, PrincipalResourcePermissions permissions) {
        String key = region + ":" + buildPermissionsKey(catalogId, permissions);
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
    public void revokePermissions(String region, String catalogId, PrincipalResourcePermissions permissions) {
        String key = region + ":" + buildPermissionsKey(catalogId, permissions);
        permissionsStorage.get(key).ifPresent(existing -> {
            boolean empty = true;
            if (existing.getPermissions() != null && permissions.getPermissions() != null) {
                List<String> current = new ArrayList<>(existing.getPermissions());
                current.removeAll(permissions.getPermissions());
                existing.setPermissions(current);
                
                if (existing.getPermissionsWithGrantOption() != null) {
                    List<String> currentGrants = new ArrayList<>(existing.getPermissionsWithGrantOption());
                    currentGrants.removeAll(permissions.getPermissions());
                    existing.setPermissionsWithGrantOption(currentGrants);
                }
            }
            
            if (existing.getPermissionsWithGrantOption() != null && permissions.getPermissionsWithGrantOption() != null) {
                List<String> current = new ArrayList<>(existing.getPermissionsWithGrantOption());
                current.removeAll(permissions.getPermissionsWithGrantOption());
                existing.setPermissionsWithGrantOption(current);
            }

            if (existing.getPermissions() != null && !existing.getPermissions().isEmpty()) {
                empty = false;
            }
            if (existing.getPermissionsWithGrantOption() != null && !existing.getPermissionsWithGrantOption().isEmpty()) {
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
    public List<PrincipalResourcePermissions> listPermissions(String region, String catalogId, DataLakePrincipal principal, Resource resource, String resourceType, boolean includeRelated, Integer maxResults, String nextToken) {
        // Deliberate omission: MaxResults, NextToken, ResourceType, and IncludeRelated are ignored.
        // All results fit on a single page, and SDKs tolerate unset optional filters.
        String filterResourceKey = resource != null ? getResourceKey(resource) : null;
        return permissionsStorage.scan(k -> k.startsWith(region + ":" + catalogId + ":")).stream()
                .filter(p -> {
                    if (principal != null && principal.getDataLakePrincipalIdentifier() != null) {
                        if (p.getPrincipal() == null || !principal.getDataLakePrincipalIdentifier().equals(p.getPrincipal().getDataLakePrincipalIdentifier())) {
                            return false;
                        }
                    }
                    if (filterResourceKey != null) {
                        if (p.getResource() == null || !filterResourceKey.equals(getResourceKey(p.getResource()))) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void createLFTag(String region, String catalogId, String tagKey, List<String> tagValues) {
        LFTag tag = new LFTag();
        tag.setTagKey(tagKey);
        tag.setTagValues(tagValues);
        lfTagsStorage.put(region + ":" + catalogId + ":" + tagKey, tag);
    }

    @Override
    public Optional<LFTag> getLFTag(String region, String catalogId, String tagKey) {
        return lfTagsStorage.get(region + ":" + catalogId + ":" + tagKey);
    }

    @Override
    public void updateLFTag(String region, String catalogId, String tagKey, List<String> tagValuesToAdd, List<String> tagValuesToDelete) {
        lfTagsStorage.get(region + ":" + catalogId + ":" + tagKey).ifPresent(tag -> {
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
            lfTagsStorage.put(region + ":" + catalogId + ":" + tagKey, tag);
        });
    }

    @Override
    public void deleteLFTag(String region, String catalogId, String tagKey) {
        lfTagsStorage.delete(region + ":" + catalogId + ":" + tagKey);
    }

    @Override
    public List<LFTagPair> listLFTags(String region, String catalogId, String resourceShareType, Integer maxResults, String nextToken) {
        // Deliberate omission: MaxResults and NextToken are ignored. All results fit on a single page.
        return lfTagsStorage.scan(k -> k.startsWith(region + ":" + catalogId + ":")).stream()
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
    public void addLFTagsToResource(String region, String catalogId, Resource resource, List<LFTagPair> lfTags) {
        String resourceKey = region + ":" + catalogId + ":" + getResourceKey(resource);
        List<LFTagPair> currentTags = resourceTagsStorage.get(resourceKey).orElse(new ArrayList<>());
        
        for (LFTagPair newTag : lfTags) {
            // Remove existing tag with same key if it exists, to replace values
            currentTags.removeIf(t -> t.getTagKey().equals(newTag.getTagKey()));
            currentTags.add(newTag);
        }
        
        resourceTagsStorage.put(resourceKey, currentTags);
    }

    @Override
    public void removeLFTagsFromResource(String region, String catalogId, Resource resource, List<LFTagPair> lfTags) {
        String resourceKey = region + ":" + catalogId + ":" + getResourceKey(resource);
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
            String cat = r.getDatabase().getCatalogId() != null ? "catalog:" + r.getDatabase().getCatalogId() + ":" : "";
            return cat + "database:" + r.getDatabase().getName();
        }
        if (r.getTable() != null) {
            String cat = r.getTable().getCatalogId() != null ? "catalog:" + r.getTable().getCatalogId() + ":" : "";
            return cat + "table:" + r.getTable().getDatabaseName() + ":" + r.getTable().getName();
        }
        if (r.getTableWithColumns() != null) {
            String cat = r.getTableWithColumns().getCatalogId() != null ? "catalog:" + r.getTableWithColumns().getCatalogId() + ":" : "";
            StringBuilder sb = new StringBuilder(cat + "tableWithColumns:" + r.getTableWithColumns().getDatabaseName() + ":" + r.getTableWithColumns().getName());
            if (r.getTableWithColumns().getColumnNames() != null && !r.getTableWithColumns().getColumnNames().isEmpty()) {
                sb.append(":cols:").append(r.getTableWithColumns().getColumnNames().stream().map(c -> java.net.URLEncoder.encode(c, java.nio.charset.StandardCharsets.UTF_8)).sorted().collect(Collectors.joining(",")));
            } else if (r.getTableWithColumns().getColumnWildcard() != null) {
                sb.append(":wildcard");
                if (r.getTableWithColumns().getColumnWildcard().getExcludedColumnNames() != null && !r.getTableWithColumns().getColumnWildcard().getExcludedColumnNames().isEmpty()) {
                    sb.append(":excl:").append(r.getTableWithColumns().getColumnWildcard().getExcludedColumnNames().stream().map(c -> java.net.URLEncoder.encode(c, java.nio.charset.StandardCharsets.UTF_8)).sorted().collect(Collectors.joining(",")));
                }
            }
            return sb.toString();
        }
        if (r.getDataLocation() != null) {
            String cat = r.getDataLocation().getCatalogId() != null ? "catalog:" + r.getDataLocation().getCatalogId() + ":" : "";
            return cat + "dataLocation:" + r.getDataLocation().getResourceArn();
        }
        if (r.getDataCellsFilter() != null) {
            String cat = r.getDataCellsFilter().getTableCatalogId() != null ? "catalog:" + r.getDataCellsFilter().getTableCatalogId() + ":" : "";
            return cat + "dataCellsFilter:" + r.getDataCellsFilter().getDatabaseName() + ":" + r.getDataCellsFilter().getTableName() + ":" + r.getDataCellsFilter().getName();
        }
        if (r.getLfTag() != null) {
            String cat = r.getLfTag().getCatalogId() != null ? "catalog:" + r.getLfTag().getCatalogId() + ":" : "";
            StringBuilder sb = new StringBuilder(cat + "lfTag:" + java.net.URLEncoder.encode(r.getLfTag().getTagKey(), java.nio.charset.StandardCharsets.UTF_8));
            if (r.getLfTag().getTagValues() != null) {
                sb.append("=").append(r.getLfTag().getTagValues().stream().map(v -> java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)).sorted().collect(Collectors.joining(",")));
            }
            return sb.toString();
        }
        if (r.getLfTagExpression() != null) {
            String cat = r.getLfTagExpression().getCatalogId() != null ? "catalog:" + r.getLfTagExpression().getCatalogId() + ":" : "";
            return cat + "lfTagExpression:" + r.getLfTagExpression().getName();
        }
        if (r.getLfTagPolicy() != null) {
            String cat = r.getLfTagPolicy().getCatalogId() != null ? "catalog:" + r.getLfTagPolicy().getCatalogId() + ":" : "";
            StringBuilder sb = new StringBuilder(cat + "lfTagPolicy:" + r.getLfTagPolicy().getResourceType());
            if (r.getLfTagPolicy().getExpressionName() != null) {
                sb.append(":exprName:").append(r.getLfTagPolicy().getExpressionName());
            }
            if (r.getLfTagPolicy().getExpression() != null) {
                sb.append(":expr:").append(r.getLfTagPolicy().getExpression().stream()
                        .map(e -> java.net.URLEncoder.encode(e.getTagKey(), java.nio.charset.StandardCharsets.UTF_8) + "=" + e.getTagValues().stream().map(v -> java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8)).sorted().collect(Collectors.joining(",")))
                        .sorted().collect(Collectors.joining(";")));
            }
            return sb.toString();
        }
        return "unknown";
    }
}
