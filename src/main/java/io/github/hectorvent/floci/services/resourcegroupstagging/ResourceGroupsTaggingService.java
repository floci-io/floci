package io.github.hectorvent.floci.services.resourcegroupstagging;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.resourcegroupstagging.model.ResourceTagMapping;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class ResourceGroupsTaggingService implements Resettable {

    private static final Logger LOG = Logger.getLogger(ResourceGroupsTaggingService.class);
    private static final Pattern TYPE_DELIMITER = Pattern.compile("[/:]");
    private static final String WILDCARD_SUFFIX = ":*";

    private final StorageFactory storageFactory;
    private final Iterable<ResourceProvider> providers;
    private final Iterable<TagHandler> tagHandlers;
    private final RegionResolver regionResolver;

    // region::arn → ResourceTagMapping
    private Map<String, ResourceTagMapping> store = new ConcurrentHashMap<>();

    @Inject
    public ResourceGroupsTaggingService(StorageFactory storageFactory,
                                        Instance<ResourceProvider> providers,
                                        Instance<TagHandler> tagHandlers,
                                        RegionResolver regionResolver) {
        // The upcasts select the Iterable constructor instead of recursing into this one.
        this(storageFactory, (Iterable<ResourceProvider>) providers, (Iterable<TagHandler>) tagHandlers,
                regionResolver);
    }

    public ResourceGroupsTaggingService(StorageFactory storageFactory) {
        this(storageFactory, List.of(), List.of(), null);
    }

    ResourceGroupsTaggingService(StorageFactory storageFactory,
                                 Iterable<ResourceProvider> providers,
                                 Iterable<TagHandler> tagHandlers,
                                 RegionResolver regionResolver) {
        this.storageFactory = storageFactory;
        this.providers = providers;
        this.tagHandlers = tagHandlers;
        this.regionResolver = regionResolver;
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

    /**
     * Returns the tags for a single resource, or an empty map if no tags have been applied.
     */
    public Map<String, String> getTagsForResource(String region, String arn) {
        ResourceTagMapping mapping = store.get(key(region, arn));
        return mapping != null ? Collections.unmodifiableMap(mapping.getTags()) : Map.of();
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

    // ─── Write-through for the tagging API ────────────────────────────────────

    /**
     * TagResources from the tagging API: the owning service's {@link TagHandler} gets the tags
     * first, and the store keeps a copy unless a {@link ResourceProvider} declares the
     * resource's type, in which case the owning service holds the only copy.
     * {@link #tagResources} stays store-only for services that dual-write, so they can never
     * recurse through their own handler.
     */
    public void applyTags(List<String> resourceArns, Map<String, String> tags, String region) {
        for (String arn : resourceArns) {
            if (!tagThroughOwner(arn, tags, region)) {
                tagResources(List.of(arn), tags, region);
            }
        }
    }

    public void removeTags(List<String> resourceArns, List<String> tagKeys, String region) {
        for (String arn : resourceArns) {
            TagHandler handler = ownerHandler(arn);
            if (handler == null) {
                continue;
            }
            try {
                handler.untagResource(AwsArnUtils.regionOrDefault(arn, region), arn, tagKeys);
            } catch (AwsException e) {
                LOG.debugv("{0} rejected untagging {1} ({2}); removing the keys from the tagging store only",
                        handler.serviceKey(), arn, e.getErrorCode());
            }
        }
        untagResources(resourceArns, tagKeys, region);
    }

    private boolean tagThroughOwner(String arn, Map<String, String> tags, String region) {
        TagHandler handler = ownerHandler(arn);
        if (handler == null) {
            return false;
        }
        try {
            handler.tagResource(AwsArnUtils.regionOrDefault(arn, region), arn, tags);
        } catch (AwsException e) {
            LOG.debugv("{0} rejected tags for {1} ({2}); keeping them in the tagging store",
                    handler.serviceKey(), arn, e.getErrorCode());
            return false;
        }
        return isProviderOwned(AwsArnUtils.parse(arn));
    }

    private TagHandler ownerHandler(String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            return null;
        }
        String service = AwsArnUtils.parse(arn).service();
        for (TagHandler handler : tagHandlers) {
            if (handler.serviceKey().equals(service)) {
                return handler;
            }
        }
        return null;
    }

    private boolean isProviderOwned(AwsArnUtils.Arn arn) {
        String type = parsedType(arn.resource());
        for (ResourceProvider provider : providers) {
            for (SupportedResourceType supported : provider.getSupportedResourceTypes()) {
                String declared = typeSuffix(supported.resourceType());
                int slash = declared.indexOf('/');
                String firstSegment = slash < 0 ? declared : declared.substring(0, slash);
                if (supported.service().equals(arn.service()) && firstSegment.equals(type)) {
                    return true;
                }
            }
        }
        return false;
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
        List<ResourceTagMapping> all = visibleEntries(region).stream()
                .filter(e -> resourceArnList == null || resourceArnList.isEmpty()
                        || resourceArnList.contains(e.mapping().getResourceArn()))
                .filter(e -> matchesTagFilters(e.mapping(), tagFilters))
                .filter(e -> matchesResourceTypeFilters(e, resourceTypeFilters))
                .map(ViewEntry::mapping)
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

    private boolean matchesResourceTypeFilters(ViewEntry entry, List<String> resourceTypeFilters) {
        if (resourceTypeFilters == null || resourceTypeFilters.isEmpty()) {
            return true;
        }
        String resourceArn = entry.mapping().getResourceArn();
        if (!AwsArnUtils.isArn(resourceArn)) {
            return false;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        String resourceType = parsedType(arn.resource());
        // filter format is "service[:resourceType]" (e.g. "ec2:instance", "apigateway:/apikeys")
        for (String filter : resourceTypeFilters) {
            String[] filterParts = filter.split(":", 2);
            if (!filterParts[0].equalsIgnoreCase(arn.service())) {
                continue;
            }
            if (filterParts.length == 1) {
                return true;
            }
            String filterType = stripLeadingSlash(filterParts[1]);
            if (filterType.equalsIgnoreCase(resourceType)
                    || entry.declaredTypes().stream().anyMatch(filterType::equalsIgnoreCase)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The type segment of an ARN resource part: one leading {@code /} dropped, then cut at the
     * first {@code /} or {@code :}, so {@code function:f}, {@code instance/i-1} and
     * {@code /apikeys/k} yield {@code function}, {@code instance} and {@code apikeys}.
     */
    static String parsedType(String resource) {
        return TYPE_DELIMITER.split(stripLeadingSlash(resource), 2)[0];
    }

    private static String stripLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private static String typeSuffix(String resourceType) {
        return resourceType.substring(resourceType.indexOf(':') + 1);
    }

    // ─── Merged view ───────────────────────────────────────────────────────────

    private record ViewEntry(ResourceTagMapping mapping, Set<String> declaredTypes) {

        static ViewEntry of(String arn) {
            return new ViewEntry(new ResourceTagMapping(arn), new HashSet<>());
        }
    }

    /**
     * Store mappings plus every provider resource that has tags or is already in the store,
     * visible to the request's region and account. The owning service's tags win on the same
     * key. Entries are copies, so the stored mappings are never mutated.
     */
    private List<ViewEntry> visibleEntries(String region) {
        String accountId = regionResolver != null ? regionResolver.getAccountId() : null;
        // TreeMap keeps the ARN order offset pagination depends on
        Map<String, ViewEntry> byArn = new TreeMap<>();
        for (ResourceTagMapping stored : store.values()) {
            String arn = stored.getResourceArn();
            if (isVisible(arn, region, accountId)) {
                byArn.computeIfAbsent(arn, ViewEntry::of).mapping().getTags().putAll(stored.getTags());
            }
        }
        for (ExplorerResource resource : providerResources()) {
            String arn = resource.arn().endsWith(WILDCARD_SUFFIX)
                    ? resource.arn().substring(0, resource.arn().length() - WILDCARD_SUFFIX.length())
                    : resource.arn();
            ViewEntry entry = byArn.get(arn);
            if (entry == null) {
                if (resource.tags().isEmpty() || !isVisible(arn, region, accountId)) {
                    continue;
                }
                entry = ViewEntry.of(arn);
                byArn.put(arn, entry);
            }
            entry.mapping().getTags().putAll(resource.tags());
            entry.declaredTypes().add(typeSuffix(resource.resourceType()));
        }
        return new ArrayList<>(byArn.values());
    }

    private List<ExplorerResource> providerResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (ResourceProvider provider : providers) {
            try {
                resources.addAll(provider.getResources());
            } catch (RuntimeException e) {
                LOG.warnv(e, "ResourceProvider {0} failed to supply resources; excluding it from tag discovery",
                        provider.getClass().getSimpleName());
            }
        }
        return resources;
    }

    // Region and account come from the ARN (arn:<partition>:svc:region:acct:resource); an empty
    // segment means global, and a string that is not an ARN stays visible as it always has.
    private static boolean isVisible(String arn, String region, String accountId) {
        String[] parts = arn.split(":", 6);
        if (parts.length >= 4 && !parts[3].isEmpty() && !parts[3].equals(region)) {
            return false;
        }
        return accountId == null || parts.length < 5 || parts[4].isEmpty() || parts[4].equals(accountId);
    }

    // ─── GetTagKeys ────────────────────────────────────────────────────────────

    public PageResult getTagKeys(String paginationToken, int maxResults, String region) {
        List<String> keys = visibleEntries(region).stream()
                .flatMap(e -> e.mapping().getTags().keySet().stream())
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
        List<String> values = visibleEntries(region).stream()
                .map(e -> e.mapping().getTags().get(tagKey))
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
