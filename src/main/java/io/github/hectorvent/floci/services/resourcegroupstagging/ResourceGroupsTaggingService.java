package io.github.hectorvent.floci.services.resourcegroupstagging;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.resourcegroupstagging.model.ResourceTagMapping;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class ResourceGroupsTaggingService implements Resettable {

    private final StorageFactory storageFactory;

    // region::arn → ResourceTagMapping
    private Map<String, ResourceTagMapping> store = new ConcurrentHashMap<>();

    @Inject
    public ResourceGroupsTaggingService(StorageFactory storageFactory) {
        this.storageFactory = storageFactory;
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.store = new StorageBackedMap<>(storageFactory.create("tagging",
                "tagging-resource-mappings.json", new TypeReference<Map<String, ResourceTagMapping>>() {}));
    }

    private String key(String region, String arn) {
        return region + "::" + arn;
    }

    // ─── TagResources ──────────────────────────────────────────────────────────

    // Mutators are synchronized: StorageBackedMap has no atomic computeIfAbsent, so
    // the get-mutate-put sequence would otherwise lose updates under concurrent calls.
    public synchronized void tagResources(List<String> resourceArns, Map<String, String> tags, String region) {
        for (String arn : resourceArns) {
            String storeKey = key(region, arn);
            ResourceTagMapping mapping = store.get(storeKey);
            if (mapping == null) {
                mapping = new ResourceTagMapping(arn);
            }
            mapping.getTags().putAll(tags);
            // Re-put so StorageBackedMap routes the mutation through the backend
            store.put(storeKey, mapping);
        }
    }

    // ─── UntagResources ────────────────────────────────────────────────────────

    public synchronized void untagResources(List<String> resourceArns, List<String> tagKeys, String region) {
        for (String arn : resourceArns) {
            String storeKey = key(region, arn);
            ResourceTagMapping mapping = store.get(storeKey);
            if (mapping != null) {
                tagKeys.forEach(mapping.getTags()::remove);
                store.put(storeKey, mapping);
            }
        }
    }

    public synchronized void deleteResources(List<String> resourceArns, String region) {
        for (String arn : resourceArns) {
            store.remove(key(region, arn));
        }
    }

    public void clear() {
        store.clear();
    }

    // ─── GetResources ──────────────────────────────────────────────────────────

    public record TagFilter(String key, List<String> values) {}

    public record PageResult(List<ResourceTagMapping> items, String nextPaginationToken) {}

    public PageResult getResources(List<String> resourceArnList,
                                   List<TagFilter> tagFilters,
                                   List<String> resourceTypeFilters,
                                   String paginationToken,
                                   int resourcesPerPage,
                                   String region) {
        List<ResourceTagMapping> all = store.values().stream()
                .filter(m -> {
                    // Derive the region from the ARN (arn:aws:svc:region:acct:type/id)
                    String[] parts = m.getResourceArn().split(":", 6);
                    if (parts.length >= 4) {
                        String arnRegion = parts[3];
                        if (!arnRegion.isEmpty() && !arnRegion.equals(region)) return false;
                    }
                    return true;
                })
                .filter(m -> resourceArnList == null || resourceArnList.isEmpty()
                        || resourceArnList.contains(m.getResourceArn()))
                .filter(m -> matchesTagFilters(m, tagFilters))
                .filter(m -> matchesResourceTypeFilters(m, resourceTypeFilters))
                .sorted(Comparator.comparing(ResourceTagMapping::getResourceArn))
                .collect(Collectors.toList());

        int offset = decodePaginationToken(paginationToken);
        int pageSize = (resourcesPerPage > 0) ? resourcesPerPage : 100;
        int end = Math.min(offset + pageSize, all.size());
        List<ResourceTagMapping> page = all.subList(offset, end);
        String nextToken = (end < all.size()) ? encodePaginationToken(end) : null;
        return new PageResult(page, nextToken);
    }

    private boolean matchesTagFilters(ResourceTagMapping m, List<TagFilter> tagFilters) {
        if (tagFilters == null || tagFilters.isEmpty()) return true;
        Map<String, String> tags = m.getTags();
        for (TagFilter filter : tagFilters) {
            String tagValue = tags.get(filter.key());
            if (tagValue == null) return false;
            if (!filter.values().isEmpty() && !filter.values().contains(tagValue)) return false;
        }
        return true;
    }

    private boolean matchesResourceTypeFilters(ResourceTagMapping m, List<String> resourceTypeFilters) {
        if (resourceTypeFilters == null || resourceTypeFilters.isEmpty()) return true;
        // ARN: arn:aws:<service>:<region>:<account>:<type>/<id>  or  arn:aws:<service>:::...
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(m.getResourceArn());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String service = arn.service();
        String resourcePart = arn.resource(); // "type/id" or just "type"
        String resourceType = resourcePart.contains("/")
                ? resourcePart.substring(0, resourcePart.indexOf('/'))
                : resourcePart;
        // filter format is "service:resourceType" (e.g. "ec2:instance")
        for (String filter : resourceTypeFilters) {
            String[] filterParts = filter.split(":", 2);
            if (filterParts.length == 2) {
                if (filterParts[0].equalsIgnoreCase(service)
                        && filterParts[1].equalsIgnoreCase(resourceType)) {
                    return true;
                }
            } else {
                if (filterParts[0].equalsIgnoreCase(service)) return true;
            }
        }
        return false;
    }

    // ─── GetTagKeys ────────────────────────────────────────────────────────────

    public PageResult getTagKeys(String paginationToken, int maxResults, String region) {
        List<String> keys = store.values().stream()
                .filter(m -> {
                    String[] parts = m.getResourceArn().split(":", 6);
                    if (parts.length >= 4) {
                        String arnRegion = parts[3];
                        if (!arnRegion.isEmpty() && !arnRegion.equals(region)) return false;
                    }
                    return true;
                })
                .flatMap(m -> m.getTags().keySet().stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        int offset = decodePaginationToken(paginationToken);
        int pageSize = (maxResults > 0) ? maxResults : 100;
        int end = Math.min(offset + pageSize, keys.size());
        // Return as ResourceTagMapping with just the key in the ARN field (repurposed for keys)
        List<ResourceTagMapping> page = keys.subList(offset, end).stream()
                .map(k -> new ResourceTagMapping(k))
                .collect(Collectors.toList());
        String nextToken = (end < keys.size()) ? encodePaginationToken(end) : null;
        return new PageResult(page, nextToken);
    }

    // ─── GetTagValues ──────────────────────────────────────────────────────────

    public PageResult getTagValues(String tagKey, String paginationToken, int maxResults, String region) {
        List<String> values = store.values().stream()
                .filter(m -> {
                    String[] parts = m.getResourceArn().split(":", 6);
                    if (parts.length >= 4) {
                        String arnRegion = parts[3];
                        if (!arnRegion.isEmpty() && !arnRegion.equals(region)) return false;
                    }
                    return true;
                })
                .map(m -> m.getTags().get(tagKey))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        int offset = decodePaginationToken(paginationToken);
        int pageSize = (maxResults > 0) ? maxResults : 100;
        int end = Math.min(offset + pageSize, values.size());
        List<ResourceTagMapping> page = values.subList(offset, end).stream()
                .map(v -> new ResourceTagMapping(v))
                .collect(Collectors.toList());
        String nextToken = (end < values.size()) ? encodePaginationToken(end) : null;
        return new PageResult(page, nextToken);
    }

    // ─── Pagination helpers ────────────────────────────────────────────────────

    private static String encodePaginationToken(int offset) {
        return Base64.getEncoder().encodeToString(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodePaginationToken(String token) {
        if (token == null || token.isBlank()) return 0;
        try {
            return Integer.parseInt(new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return 0;
        }
    }
}
