package io.github.hectorvent.floci.services.resourcegroupstagging;

import io.github.hectorvent.floci.services.resourcegroupstagging.model.ResourceTagMapping;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class ResourceGroupsTaggingService {

    // region::arn → ResourceTagMapping
    private final Map<String, ResourceTagMapping> store = new ConcurrentHashMap<>();

    private String key(String region, String arn) {
        return region + "::" + arn;
    }

    // ─── TagResources ──────────────────────────────────────────────────────────

    public void tagResources(List<String> resourceArns, Map<String, String> tags, String region) {
        for (String arn : resourceArns) {
            store.computeIfAbsent(key(region, arn), k -> new ResourceTagMapping(arn))
                    .getTags().putAll(tags);
        }
    }

    // ─── UntagResources ────────────────────────────────────────────────────────

    public void untagResources(List<String> resourceArns, List<String> tagKeys, String region) {
        for (String arn : resourceArns) {
            ResourceTagMapping mapping = store.get(key(region, arn));
            if (mapping != null) {
                tagKeys.forEach(mapping.getTags()::remove);
            }
        }
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
        String arn = m.getResourceArn();
        String[] parts = arn.split(":", 6);
        if (parts.length < 3) return false;
        String service = parts[2];
        // resource part is parts[5]: "type/id" or just "type"
        String resourcePart = parts.length >= 6 ? parts[5] : "";
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
