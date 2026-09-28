package io.github.hectorvent.floci.services.resourcegroupstagging;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    private static final String GLOBAL_REGION = "global";

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
     * TagResources from the tagging API. An ARN that a {@link ResourceProvider} currently lists
     * goes to its owning service's {@link TagHandler} only, so the owner holds the one copy; any
     * other ARN goes to the tagging store. {@link #tagResources} stays store-only for services
     * that dual-write, so they can never recurse through their own handler.
     *
     * @return the owning service's rejection per ARN, empty when every ARN was tagged
     */
    public Map<String, AwsException> applyTags(List<String> resourceArns, Map<String, String> tags, String region) {
        Map<String, AwsException> failures = new LinkedHashMap<>();
        Map<String, TagHandler> owners = listedOwners(resourceArns);
        for (String arn : resourceArns) {
            TagHandler owner = owners.get(arn);
            if (owner == null) {
                tagResources(List.of(arn), tags, region);
                continue;
            }
            try {
                owner.tagResource(AwsArnUtils.regionOrDefault(arn, region), arn, tags);
            } catch (AwsException e) {
                failures.put(arn, e);
            }
        }
        return failures;
    }

    /**
     * UntagResources from the tagging API, routed as {@link #applyTags} routes. The keys also
     * leave the tagging store for every ARN whose owning service did not reject them.
     *
     * @return the owning service's rejection per ARN, empty when every ARN was untagged
     */
    public Map<String, AwsException> removeTags(List<String> resourceArns, List<String> tagKeys, String region) {
        Map<String, AwsException> failures = new LinkedHashMap<>();
        Map<String, TagHandler> owners = listedOwners(resourceArns);
        List<String> untagged = new ArrayList<>();
        for (String arn : resourceArns) {
            TagHandler owner = owners.get(arn);
            if (owner != null) {
                try {
                    owner.untagResource(AwsArnUtils.regionOrDefault(arn, region), arn, tagKeys);
                } catch (AwsException e) {
                    failures.put(arn, e);
                    continue;
                }
            }
            untagged.add(arn);
        }
        untagResources(untagged, tagKeys, region);
        return failures;
    }

    // Providers are only read when some ARN has a handler, so a store-only request stays cheap.
    private Map<String, TagHandler> listedOwners(List<String> resourceArns) {
        Map<String, TagHandler> owners = new HashMap<>();
        Set<String> listed = null;
        for (String arn : resourceArns) {
            TagHandler handler = ownerHandler(arn);
            if (handler == null) {
                continue;
            }
            if (listed == null) {
                listed = new HashSet<>();
                for (ExplorerResource resource : providerResources()) {
                    listed.add(withoutWildcard(resource.arn()));
                }
            }
            if (listed.contains(withoutWildcard(arn))) {
                owners.put(arn, handler);
            }
        }
        return owners;
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

    private static String withoutWildcard(String arn) {
        return arn.endsWith(WILDCARD_SUFFIX) ? arn.substring(0, arn.length() - WILDCARD_SUFFIX.length()) : arn;
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

        int offset = Math.min(decodePaginationToken(paginationToken), all.size());
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
            if (isVisible(arn, null, region, accountId)) {
                byArn.computeIfAbsent(arn, ViewEntry::of).mapping().getTags().putAll(stored.getTags());
            }
        }
        for (ExplorerResource resource : providerResources()) {
            String arn = withoutWildcard(resource.arn());
            // The owner's region decides, so a store copy of a region-less ARN must not surface elsewhere.
            if (!isVisible(arn, resource.region(), region, accountId)) {
                byArn.remove(arn);
                continue;
            }
            ViewEntry entry = byArn.get(arn);
            if (entry == null) {
                if (resource.tags().isEmpty()) {
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

    // Tags are copied inside the try so a live tag map that changes mid-copy skips only its provider.
    private List<ExplorerResource> providerResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (ResourceProvider provider : providers) {
            try {
                List<ExplorerResource> copies = new ArrayList<>();
                for (ExplorerResource resource : provider.getResources()) {
                    copies.add(new ExplorerResource(resource.arn(), resource.resourceType(), resource.service(),
                            resource.region(), resource.owningAccountId(), resource.lastReportedAt(),
                            new HashMap<>(resource.tags())));
                }
                resources.addAll(copies);
            } catch (RuntimeException e) {
                LOG.warnv(e, "ResourceProvider {0} failed to supply resources; excluding it from tag discovery",
                        provider.getClass().getSimpleName());
            }
        }
        return resources;
    }

    // Region and account come from the ARN (arn:<partition>:svc:region:acct:resource), and a string
    // that is not an ARN stays visible as it always has. An empty ARN region falls back to
    // fallbackRegion (a provider resource's own region, null for store entries); a fallback that
    // is null, empty or "global" means the resource is global.
    private static boolean isVisible(String arn, String fallbackRegion, String region, String accountId) {
        String[] parts = arn.split(":", 6);
        if (parts.length >= 4 && !regionMatches(parts[3], fallbackRegion, region)) {
            return false;
        }
        return accountId == null || parts.length < 5 || parts[4].isEmpty() || parts[4].equals(accountId);
    }

    private static boolean regionMatches(String arnRegion, String fallbackRegion, String region) {
        if (!arnRegion.isEmpty()) {
            return arnRegion.equals(region);
        }
        return fallbackRegion == null || fallbackRegion.isEmpty() || fallbackRegion.equals(GLOBAL_REGION)
                || fallbackRegion.equals(region);
    }

    // ─── GetTagKeys ────────────────────────────────────────────────────────────

    public PageResult getTagKeys(String paginationToken, int maxResults, String region) {
        List<String> keys = visibleEntries(region).stream()
                .flatMap(e -> e.mapping().getTags().keySet().stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        int offset = Math.min(decodePaginationToken(paginationToken), keys.size());
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

        int offset = Math.min(decodePaginationToken(paginationToken), values.size());
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
