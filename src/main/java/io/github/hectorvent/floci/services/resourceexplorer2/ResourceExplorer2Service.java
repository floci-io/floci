package io.github.hectorvent.floci.services.resourceexplorer2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.resourceexplorer2.model.IncludedProperty;
import io.github.hectorvent.floci.services.resourceexplorer2.model.Index;
import io.github.hectorvent.floci.services.resourceexplorer2.model.IndexState;
import io.github.hectorvent.floci.services.resourceexplorer2.model.IndexType;
import io.github.hectorvent.floci.services.resourceexplorer2.model.SearchFilter;
import io.github.hectorvent.floci.services.resourceexplorer2.model.SetupOutcome;
import io.github.hectorvent.floci.services.resourceexplorer2.model.SetupTask;
import io.github.hectorvent.floci.services.resourceexplorer2.model.Taggable;
import io.github.hectorvent.floci.services.resourceexplorer2.model.View;
import io.github.hectorvent.floci.services.resourceexplorer2.query.ParsedQuery;
import io.github.hectorvent.floci.services.resourceexplorer2.query.QueryParser;
import io.github.hectorvent.floci.services.resourceexplorer2.query.ResourceFilter;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@ApplicationScoped
public class ResourceExplorer2Service {

    private static final Logger LOG = Logger.getLogger(ResourceExplorer2Service.class);

    private final Instance<ResourceProvider> providers;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final StorageBackend<String, Index> indexStore;
    private final StorageBackend<String, View> viewStore;
    /** Default view ARN per region. Persisted, not derived, so an explicit choice survives restarts. */
    private final StorageBackend<String, String> defaultViewStore;
    /** Completed setup tasks, keyed by the TaskId GetResourceExplorerSetup reads back. */
    private final StorageBackend<String, SetupTask> setupTaskStore;

    /**
     * The maximum number of matching resources {@code Search} returns across all pages. Per the AWS
     * API reference: "The operation can return only the first 1,000 results." Also the maximum
     * {@code MaxResults} page size accepted by both operations, and the page size at which
     * {@code ListResources} suppresses its {@code NextToken}.
     *
     * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_Search.html">AWS API: Search</a>
     */
    static final int MAX_RESOURCE_RESULTS = 1000;

    /**
     * Sentinel result cap for {@code ListResources}, which — unlike {@code Search} — has no total cap
     * and pages through every match. {@code Integer.MAX_VALUE} is safe as "unlimited": {@code pageBounds}
     * caps {@code total} at {@code min(size, cap)}, and a {@link java.util.List} size can never exceed
     * it, so {@code total == size}. Passing this instead of {@link #MAX_RESOURCE_RESULTS} is what
     * distinguishes ListResources' contract from Search's.
     *
     * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_ListResources.html">AWS API: ListResources</a>
     */
    private static final int UNLIMITED_RESULTS = Integer.MAX_VALUE;
    /** Allowed view name characters per the CreateView API: letters, digits, hyphen, 1-64 chars. */
    private static final Pattern VIEW_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9\\-]{1,64}");
    /** Region pattern the setup APIs apply to every entry of RegionList and AggregatorRegions. */
    private static final Pattern REGION_PATTERN = Pattern.compile("[a-z-]+-[a-z]+-[0-9]");
    /** AWS caps AggregatorRegions at one entry, since an account has at most one aggregator index. */
    private static final int MAX_AGGREGATOR_REGIONS = 1;

    @Inject
    public ResourceExplorer2Service(Instance<ResourceProvider> providers,
                                     RegionResolver regionResolver,
                                     ObjectMapper objectMapper,
                                     StorageFactory storageFactory) {
        this.providers = providers;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.indexStore = storageFactory.create("resourceexplorer2",
                "resourceexplorer2-indexes.json",
                new TypeReference<>() {});
        this.viewStore = storageFactory.create("resourceexplorer2",
                "resourceexplorer2-views.json",
                new TypeReference<>() {});
        this.defaultViewStore = storageFactory.create("resourceexplorer2",
                "resourceexplorer2-default-views.json",
                new TypeReference<>() {});
        this.setupTaskStore = storageFactory.create("resourceexplorer2",
                "resourceexplorer2-setup-tasks.json",
                new TypeReference<>() {});
    }

    /**
     * Visible for testing. Injects storage backends directly so a test can share them across two
     * service instances — modeling a restart where persisted state survives but in-memory state does not.
     */
    ResourceExplorer2Service(Instance<ResourceProvider> providers,
                             RegionResolver regionResolver,
                             ObjectMapper objectMapper,
                             StorageBackend<String, Index> indexStore,
                             StorageBackend<String, View> viewStore,
                             StorageBackend<String, String> defaultViewStore) {
        this(providers, regionResolver, objectMapper, indexStore, viewStore, defaultViewStore,
                new InMemoryStorage<>());
    }

    ResourceExplorer2Service(Instance<ResourceProvider> providers,
                             RegionResolver regionResolver,
                             ObjectMapper objectMapper,
                             StorageBackend<String, Index> indexStore,
                             StorageBackend<String, View> viewStore,
                             StorageBackend<String, String> defaultViewStore,
                             StorageBackend<String, SetupTask> setupTaskStore) {
        this.providers = providers;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.indexStore = indexStore;
        this.viewStore = viewStore;
        this.defaultViewStore = defaultViewStore;
        this.setupTaskStore = setupTaskStore;
    }

    void onStartup(@Observes StartupEvent event) {
        ensureDefaultRegionProvisioned();
    }

    /**
     * Ensures the default region has an active index and default view. Called at startup and lazily
     * before every operation that needs that baseline. The lazy calls matter because a LocalStack
     * {@code state/reset} wipes all storage in-process (see {@code EmulatorInfoController}) without a
     * restart, so {@code onStartup} never re-runs; without this, GetIndex/GetDefaultView/Search/
     * ListResources would fail for the whole service until the process is restarted.
     *
     * <p>The guard keys on an ACTIVE index, not "store is empty": a restart with persisted storage
     * keeps the index active, so this is a no-op and an explicit DisassociateDefaultView stays in
     * effect. Only a genuine loss of the index (fresh boot or a reset) triggers re-provisioning.
     */
    private void ensureDefaultRegionProvisioned() {
        String region = regionResolver.getDefaultRegion();
        String accountId = regionResolver.getAccountId();
        boolean hasActiveDefaultRegionIndex = indexStore.scan(_ -> true).stream()
                .anyMatch(i -> region.equals(i.region()) && i.state() != IndexState.DELETED);
        if (hasActiveDefaultRegionIndex) {
            return;
        }
        String indexArn = regionResolver.buildArn("resource-explorer-2", region,
                "index/" + UUID.randomUUID());
        Index index = new Index(indexArn, region, IndexType.AGGREGATOR,
                IndexState.ACTIVE, new HashMap<>(), Instant.now());
        indexStore.put(indexArn, index);

        String viewId = UUID.randomUUID().toString();
        String viewArn = regionResolver.buildArn("resource-explorer-2", region,
                "view/default-view/" + viewId);
        View view = new View(viewArn, "default-view", accountId,
                "arn:aws:iam::" + accountId + ":root",
                null,
                List.of(new IncludedProperty("tags")),
                new HashMap<>(), Instant.now());
        viewStore.put(viewArn, view);
        defaultViewStore.put(region, viewArn);
    }

    private static String regionOf(View view) {
        return AwsArnUtils.parse(view.viewArn()).region();
    }

    private List<Index> activeIndexes(String type, List<String> regions) {
        return indexStore.scan(_ -> true).stream()
                .filter(i -> i.state() != IndexState.DELETED)
                .filter(i -> type == null || type.equalsIgnoreCase(i.type().name()))
                .filter(i -> regions == null || regions.isEmpty()
                        || regions.stream().anyMatch(r -> r.equalsIgnoreCase(i.region())))
                .sorted(Comparator.comparing(Index::arn))
                .toList();
    }

    private Optional<Index> activeIndex(String region) {
        return indexStore.scan(_ -> true).stream()
                .filter(i -> region.equals(i.region()) && i.state() != IndexState.DELETED)
                .findFirst();
    }

    /** Creates a LOCAL index. Real AWS always creates LOCAL; promote via {@link #updateIndexType}. */
    public Index createIndex(String region, Map<String, String> tags) {
        if (activeIndex(region).isPresent()) {
            throw new AwsException("ConflictException",
                    "An index already exists for region: " + region, 409);
        }
        String indexArn = regionResolver.buildArn("resource-explorer-2", region,
                "index/" + UUID.randomUUID());
        // Stored ACTIVE (floci is synchronous); the controller emits CREATING on the create response.
        Index index = new Index(indexArn, region, IndexType.LOCAL, IndexState.ACTIVE,
                tags != null ? new HashMap<>(tags) : new HashMap<>(), Instant.now());
        indexStore.put(indexArn, index);
        return index;
    }

    public Index getIndex(String region) {
        ensureDefaultRegionProvisioned();
        return activeIndex(region)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No index found for region: " + region, 404));
    }

    public Index deleteIndex(String indexArn) {
        Index index = indexStore.get(indexArn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Index not found: " + indexArn, 404));
        Index deleted = index.withState(IndexState.DELETED).withLastUpdatedAt(Instant.now());
        indexStore.put(indexArn, deleted);

        boolean hasOtherIndexes = indexStore.scan(_ -> true).stream()
                .anyMatch(i -> !indexArn.equals(i.arn()) && i.state() != IndexState.DELETED);
        if (!hasOtherIndexes) {
            viewStore.scan(_ -> true).forEach(v -> viewStore.delete(v.viewArn()));
            defaultViewStore.clear();
        }
        return deleted;
    }

    public Index updateIndexType(String indexArn, IndexType type) {
        Index index = indexStore.get(indexArn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Index not found: " + indexArn, 404));
        if (type == IndexType.AGGREGATOR) {
            boolean aggregatorExistsElsewhere = indexStore.scan(_ -> true).stream()
                    .anyMatch(i -> !indexArn.equals(i.arn())
                            && i.type() == IndexType.AGGREGATOR
                            && i.state() != IndexState.DELETED);
            if (aggregatorExistsElsewhere) {
                throw new AwsException("ConflictException",
                        "You already have an AGGREGATOR index in a different AWS Region. "
                                + "Only one aggregator index is allowed per account.", 409);
            }
        }
        Index updated = index.withType(type).withLastUpdatedAt(Instant.now());
        indexStore.put(indexArn, updated);
        return updated;
    }

    private record Paginated<T>(List<T> items, String nextToken) {}

    /**
     * Slices one page out of {@code all} at the offset carried by {@code nextToken}.
     * <p>
     * The offset only identifies the same element across two calls if {@code all} is in a
     * deterministic order, so every caller sorts on a unique key before calling this. The lists
     * come from {@code StorageBackend.scan} or from iterating {@code Instance<ResourceProvider>},
     * neither of which promises an order.
     */
    private <T> Paginated<T> paginate(List<T> all, Integer maxResults, String nextToken, int defaultMax) {
        int effectiveMax = (maxResults != null && maxResults > 0) ? maxResults : defaultMax;
        int offset = Math.min(decodeNextToken(nextToken), all.size());
        int end = Math.min(offset + effectiveMax, all.size());
        String outToken = (end < all.size()) ? encodeNextToken(end) : null;
        return new Paginated<>(all.subList(offset, end), outToken);
    }

    public ObjectNode listIndexes(String type, List<String> regions, Integer maxResults, String nextToken) {
        ensureDefaultRegionProvisioned();
        List<Index> all = activeIndexes(type, regions);
        Paginated<Index> page = paginate(all, maxResults, nextToken, 100);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode arr = result.putArray("Indexes");
        for (Index idx : page.items()) {
            arr.add(indexNode(idx));
        }
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return result;
    }

    private ObjectNode indexNode(Index index) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", index.arn());
        node.put("Region", index.region());
        node.put("Type", index.type().name());
        return node;
    }

    public View createView(String region, String viewName, String scope, SearchFilter filters,
                           List<IncludedProperty> includedProperties, Map<String, String> tags) {
        if (viewName == null || !VIEW_NAME_PATTERN.matcher(viewName).matches()) {
            throw new AwsException("ValidationException",
                    "View name must match [a-zA-Z0-9-] and be 1-64 characters: " + viewName, 400);
        }
        validateIncludedProperties(includedProperties);
        boolean nameTaken = viewStore.scan(_ -> true).stream()
                .anyMatch(v -> viewName.equals(v.viewName()) && region.equals(regionOf(v)));
        if (nameTaken) {
            throw new AwsException("ConflictException",
                    "A view with the name " + viewName + " already exists in this AWS Region.", 409);
        }
        String accountId = regionResolver.getAccountId();
        String viewArn = regionResolver.buildArn("resource-explorer-2", region,
                "view/" + viewName + "/" + UUID.randomUUID());
        String effectiveScope = (scope != null) ? scope : "arn:aws:iam::" + accountId + ":root";
        View view = new View(viewArn, viewName, accountId,
                effectiveScope,
                filters, includedProperties,
                tags != null ? new HashMap<>(tags) : new HashMap<>(),
                Instant.now());
        viewStore.put(viewArn, view);
        return view;
    }

    private static void validateIncludedProperties(List<IncludedProperty> includedProperties) {
        if (includedProperties == null) {
            return;
        }
        for (IncludedProperty p : includedProperties) {
            if (!"tags".equals(p.name())) {
                throw new AwsException("ValidationException",
                        "Invalid included property name: " + p.name() + ". The only valid value is 'tags'.", 400);
            }
        }
    }

    public View getView(String viewArn) {
        return viewStore.get(viewArn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "View not found: " + viewArn, 404));
    }

    public void deleteView(String viewArn) {
        viewStore.get(viewArn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "View not found: " + viewArn, 404));
        viewStore.delete(viewArn);
        // AWS allows deleting a view that is the default; the region is simply left without one.
        for (String region : Set.copyOf(defaultViewStore.keys())) {
            if (defaultViewStore.get(region).filter(viewArn::equals).isPresent()) {
                defaultViewStore.delete(region);
            }
        }
    }

    public View updateView(String viewArn, SearchFilter filters,
                           List<IncludedProperty> includedProperties) {
        validateIncludedProperties(includedProperties);
        View view = viewStore.get(viewArn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "View not found: " + viewArn, 404));
        View updated = view.withFilters(filters)
                .withIncludedProperties(includedProperties)
                .withLastUpdatedAt(Instant.now());
        viewStore.put(viewArn, updated);
        return updated;
    }

    /**
     * Lists the views in one Region. AWS scopes this to "the AWS Region in which you call this
     * operation", so a view created in another Region is not listed here even though floci keeps
     * every Region's views in one store.
     *
     * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_ListViews.html">AWS API: ListViews</a>
     */
    public ObjectNode listViews(String region, Integer maxResults, String nextToken) {
        ensureDefaultRegionProvisioned();
        List<String> all = viewStore.scan(_ -> true).stream()
                .filter(v -> region.equals(regionOf(v)))
                .map(View::viewArn)
                .sorted()
                .toList();
        Paginated<String> page = paginate(all, maxResults, nextToken, 100);

        ObjectNode result = objectMapper.createObjectNode();
        result.set("Views", objectMapper.valueToTree(page.items()));
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return result;
    }

    public ObjectNode batchGetView(List<String> viewArns) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode views = result.putArray("Views");
        ArrayNode errors = result.putArray("Errors");
        for (String arn : viewArns) {
            Optional<View> view = viewStore.get(arn);
            if (view.isPresent()) {
                views.add(buildViewNode(view.get()));
            } else {
                ObjectNode error = objectMapper.createObjectNode();
                error.put("ErrorMessage", "View not found: " + arn);
                error.put("ViewArn", arn);
                errors.add(error);
            }
        }
        return result;
    }

    ObjectNode buildViewNode(View view) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ViewArn", view.viewArn());
        if (view.viewName() != null) {
            node.put("ViewName", view.viewName());
        }
        node.put("Owner", view.owner());
        node.put("Scope", view.scope());
        node.put("LastUpdatedAt", view.lastUpdatedAt().toString());
        ObjectNode filters = objectMapper.createObjectNode();
        filters.put("FilterString",
                view.filters() != null ? view.filters().filterString() : "");
        node.set("Filters", filters);
        if (view.includedProperties() != null) {
            ArrayNode props = objectMapper.createArrayNode();
            for (IncludedProperty p : view.includedProperties()) {
                ObjectNode propNode = objectMapper.createObjectNode();
                propNode.put("Name", p.name());
                props.add(propNode);
            }
            node.set("IncludedProperties", props);
        }
        return node;
    }

    public void associateDefaultView(String region, String viewArn) {
        View view = viewStore.get(viewArn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "View not found: " + viewArn, 404));
        // AWS scopes the default view per region: the view ARN's region must match the request
        // region, otherwise it rejects the association with a ValidationException.
        if (!region.equals(regionOf(view))) {
            throw new AwsException("ValidationException",
                    "View " + viewArn + " is in region " + regionOf(view)
                            + " and cannot be the default view for region " + region + ".", 400);
        }
        defaultViewStore.put(region, viewArn);
    }

    public void disassociateDefaultView(String region) {
        defaultViewStore.delete(region);
    }

    public String getDefaultView(String region) {
        ensureDefaultRegionProvisioned();
        return defaultViewStore.get(region).orElse(null);
    }

    public ObjectNode listResources(String filterString, Integer maxResults,
                                     String nextToken, String viewArn, String region) {
        View view = resolveView(viewArn, region);
        ParsedQuery requestFilter = (filterString != null && !filterString.isBlank())
                ? QueryParser.parseFilterOnly(filterString) : new ParsedQuery(List.of(), List.of());
        ParsedQuery viewFilter = (view.filters() != null && view.filters().filterString() != null)
                ? QueryParser.parseFilterOnly(view.filters().filterString())
                : new ParsedQuery(List.of(), List.of());
        ParsedQuery combined = ResourceFilter.combine(viewFilter, requestFilter);
        rejectTagFilterWithoutTagData(combined, view);
        Page p = paginateResources(combined, maxResults, nextToken, UNLIMITED_RESULTS);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode resources = result.putArray("Resources");
        for (ExplorerResource r : p.page()) {
            resources.add(buildResourceNode(r, view.includesTags()));
        }
        if (listResourcesEmitsToken(p.end(), p.total(), maxResults)) {
            result.put("NextToken", encodeNextToken(p.end()));
        }
        result.put("ViewArn", view.viewArn());
        return result;
    }

    public ObjectNode search(String queryString, Integer maxResults,
                              String nextToken, String viewArn, String region) {
        View view = resolveView(viewArn, region);
        ParsedQuery requestFilter = (queryString != null && !queryString.isBlank())
                ? QueryParser.parse(queryString) : new ParsedQuery(List.of(), List.of());
        ParsedQuery viewFilter = (view.filters() != null && view.filters().filterString() != null)
                ? QueryParser.parse(view.filters().filterString())
                : new ParsedQuery(List.of(), List.of());
        ParsedQuery combined = ResourceFilter.combine(viewFilter, requestFilter);
        rejectTagFilterWithoutTagData(combined, view);
        Page p = paginateResources(combined, maxResults, nextToken, MAX_RESOURCE_RESULTS);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode resources = result.putArray("Resources");
        for (ExplorerResource r : p.page()) {
            resources.add(buildResourceNode(r, view.includesTags()));
        }
        ObjectNode count = objectMapper.createObjectNode();
        count.put("TotalResources", p.total());
        count.put("Complete", p.uncappedTotal() <= MAX_RESOURCE_RESULTS);
        result.set("Count", count);
        if (p.end() < p.total()) {
            result.put("NextToken", encodeNextToken(p.end()));
        }
        result.put("ViewArn", view.viewArn());
        return result;
    }

    /**
     * AWS documents every tag filter — {@code tag:}, {@code tag.key:}, {@code tag.value:} and
     * {@code application:}, which reads a tag — as requiring a view whose {@code IncludedProperties}
     * name {@code tags}. Against a view without it the query is rejected rather than silently
     * evaluated on data the view is not allowed to expose.
     *
     * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/userguide/using-search-query-syntax.html">
     *     Search query syntax reference</a>
     */
    private static void rejectTagFilterWithoutTagData(ParsedQuery query, View view) {
        if (!ResourceFilter.usesTagData(query) || view.includesTags()) {
            return;
        }
        throw new AwsException("ValidationException",
                "The view " + view.viewArn() + " does not include tag data, so the query cannot "
                        + "filter on tags. Add an IncludedProperty named 'tags' to the view.", 400);
    }

    /** Resource types whose provider advertises tag support, for {@code resourcetype.supports:tags}. */
    private Set<String> taggableResourceTypes() {
        Set<String> taggable = new HashSet<>();
        for (ResourceProvider provider : providers) {
            for (SupportedResourceType type : provider.getSupportedResourceTypes()) {
                if (type.supportsTags()) {
                    taggable.add(type.resourceType());
                }
            }
        }
        return taggable;
    }

    private record Page(List<ExplorerResource> page, int end, int total, int uncappedTotal) {}

    /**
     * Gathers every provider's resources, applies {@code combined}, and returns one page.
     * <p>
     * Sorted by ARN before slicing. {@code NextToken} is an offset, and the list is rebuilt on
     * every call by iterating {@code Instance<ResourceProvider>} and each provider's store scan —
     * neither ordered — so without a total order over a unique key the second page would index
     * into a differently arranged list and silently drop or repeat resources.
     *
     * @param resultCap the maximum number of matching resources the operation returns across all
     *     pages. This is a real per-operation difference in the AWS API, not an internal tuning knob:
     *     {@code Search} caps at {@link #MAX_RESOURCE_RESULTS}, while {@code ListResources} is uncapped
     *     and passes {@link #UNLIMITED_RESULTS}. It is a parameter precisely because hardcoding a single
     *     value here previously imposed Search's 1000-result cap on ListResources, silently dropping
     *     every match beyond the first 1000.
     * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_Search.html">AWS API: Search</a>
     * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_ListResources.html">AWS API: ListResources</a>
     */
    private Page paginateResources(ParsedQuery combined, Integer maxResults, String nextToken, int resultCap) {
        List<ExplorerResource> all = new ArrayList<>();
        for (ResourceProvider provider : providers) {
            try {
                all.addAll(provider.getResources());
            } catch (RuntimeException e) {
                // One misbehaving provider must not fail discovery for every other service. Skip it
                // and continue — Search/ListResources is best-effort, so a partial view beats a 500.
                LOG.warnf(e, "ResourceProvider %s failed to supply resources; excluding it from this result",
                        provider.getClass().getSimpleName());
            }
        }
        Set<String> taggableResourceTypes = taggableResourceTypes();
        List<ExplorerResource> filtered = all.stream()
                .filter(r -> ResourceFilter.matches(r, combined, taggableResourceTypes))
                .sorted(Comparator.comparing(ExplorerResource::arn))
                .toList();
        int uncappedTotal = filtered.size();
        PageBounds b = pageBounds(filtered.size(), maxResults, nextToken, resultCap);
        return new Page(filtered.subList(b.start(), b.end()), b.end(), b.total(), uncappedTotal);
    }

    record PageBounds(int start, int end, int total) {}

    /**
     * Computes pagination bounds: clamps the decoded offset into range (the live result set can shrink
     * between paginated calls) and caps the total at {@code hardCap}. {@code Search} passes
     * {@link #MAX_RESOURCE_RESULTS} — it "can return only the first 1,000 results"; {@code ListResources}
     * has no total cap and passes {@link #UNLIMITED_RESULTS}.
     *
     * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_Search.html">AWS API: Search</a>
     */
    static PageBounds pageBounds(int size, Integer maxResults, String nextToken, int hardCap) {
        int total = Math.min(size, hardCap);
        int effectiveMax = (maxResults != null && maxResults > 0) ? maxResults : 100;
        int start = Math.min(decodeNextToken(nextToken), total);
        int end = Math.min(start + effectiveMax, total);
        return new PageBounds(start, end, total);
    }

    /**
     * Whether {@code ListResources} emits a {@code NextToken} for a page ending at {@code end} within
     * {@code total} results. Reproduces a documented AWS quirk: "The ListResources operation does not
     * generate a NextToken if you set MaxResults to 1000." At the maximum page size the caller silently
     * receives at most 1000 results with no way to page further.
     *
     * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_ListResources.html">AWS API: ListResources (NextToken)</a>
     */
    static boolean listResourcesEmitsToken(int end, int total, Integer maxResults) {
        if (maxResults != null && maxResults == MAX_RESOURCE_RESULTS) {
            return false;
        }
        return end < total;
    }

    public ObjectNode listSupportedResourceTypes(Integer maxResults, String nextToken) {
        List<SupportedResourceType> all = new ArrayList<>();
        for (ResourceProvider provider : providers) {
            all.addAll(provider.getSupportedResourceTypes());
        }
        Map<String, SupportedResourceType> deduplicated = new LinkedHashMap<>();
        for (SupportedResourceType t : all) {
            deduplicated.put(t.resourceType(), t);
        }
        List<SupportedResourceType> list = deduplicated.values().stream()
                .sorted(Comparator.comparing(SupportedResourceType::resourceType))
                .toList();
        Paginated<SupportedResourceType> page = paginate(list, maxResults, nextToken, 100);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode arr = result.putArray("ResourceTypes");
        for (SupportedResourceType t : page.items()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("ResourceType", t.resourceType());
            node.put("Service", t.service());
            arr.add(node);
        }
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return result;
    }

    // ── Managed, service-owned, and organization-scoped reads ────────────────
    //
    // These describe resources only AWS itself creates: views a trusted service registers
    // (managed and service views), streaming grants it holds, and indexes belonging to other
    // accounts in an organization. floci models neither trusted services nor Organizations, so
    // each answers with the wire-accurate empty result rather than an error — a caller
    // enumerating them completes, and one asking for a specific ARN gets the same
    // ResourceNotFoundException a live account with no such view would return.

    public ObjectNode listManagedViews(Integer maxResults, String nextToken) {
        return emptyList("ManagedViews", maxResults, nextToken);
    }

    public ObjectNode getManagedView(String managedViewArn) {
        throw new AwsException("ResourceNotFoundException",
                "Managed view not found: " + managedViewArn, 404);
    }

    public ObjectNode listServiceViews(Integer maxResults, String nextToken) {
        return emptyList("ServiceViews", maxResults, nextToken);
    }

    public ObjectNode getServiceView(String serviceViewArn) {
        throw new AwsException("ResourceNotFoundException",
                "Service view not found: " + serviceViewArn, 404);
    }

    public ObjectNode listStreamingAccessForServices(Integer maxResults, String nextToken) {
        return emptyList("StreamingAccessForServices", maxResults, nextToken);
    }

    /**
     * The index a trusted service reads in the request Region. Same index {@code GetIndex}
     * returns, reported with the narrower {@code ServiceIndex} shape.
     */
    public ObjectNode getServiceIndex(String region) {
        ensureDefaultRegionProvisioned();
        Index index = activeIndex(region)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No index found for region: " + region, 404));
        ObjectNode result = objectMapper.createObjectNode();
        result.put("Arn", index.arn());
        result.put("Type", index.type().name());
        return result;
    }

    public ObjectNode listServiceIndexes(List<String> regions, Integer maxResults, String nextToken) {
        ensureDefaultRegionProvisioned();
        Paginated<Index> page = paginate(activeIndexes(null, regions), maxResults, nextToken, 100);
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode indexes = result.putArray("Indexes");
        for (Index index : page.items()) {
            indexes.add(indexNode(index));
        }
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return result;
    }

    /**
     * Indexes belonging to the listed member accounts. floci emulates a single account, so the
     * result is this account's indexes when its id is among those asked for, and empty otherwise —
     * which is what a management account sees for an account with no index.
     */
    public ObjectNode listIndexesForMembers(List<String> accountIds, Integer maxResults, String nextToken) {
        ensureDefaultRegionProvisioned();
        String accountId = regionResolver.getAccountId();
        List<Index> owned = accountIds.contains(accountId) ? activeIndexes(null, null) : List.of();
        Paginated<Index> page = paginate(owned, maxResults, nextToken, 10);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode indexes = result.putArray("Indexes");
        for (Index index : page.items()) {
            ObjectNode node = indexNode(index);
            node.put("AccountId", accountId);
            indexes.add(node);
        }
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return result;
    }

    /**
     * floci models no AWS Organization, so multi-account search is reported as {@code DISABLED}
     * and no service-linked role is returned — the answer a standalone account gives.
     */
    public ObjectNode getAccountLevelServiceConfiguration() {
        ObjectNode result = objectMapper.createObjectNode();
        result.putObject("OrgConfiguration").put("AWSServiceAccessStatus", "DISABLED");
        return result;
    }

    private ObjectNode emptyList(String field, Integer maxResults, String nextToken) {
        Paginated<String> page = paginate(List.of(), maxResults, nextToken, 50);
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray(field);
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return result;
    }

    // ── Multi-Region setup ───────────────────────────────────────────────────

    /**
     * Turns Resource Explorer on across several Regions in one call: an index per Region, a view
     * per Region, and at most one Region promoted to aggregator.
     *
     * <p>A Region that already has an index reuses it rather than failing, because the operation
     * describes a target state rather than a create. Each Region's index and view step is recorded
     * independently, so one Region's failure — a name already taken, an aggregator already
     * elsewhere — is reported through that Region's {@code ErrorDetails} instead of abandoning the
     * rest of the task.
     *
     * @return the task id to read back with {@link #getResourceExplorerSetup}
     * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_CreateResourceExplorerSetup.html">
     *     AWS API: CreateResourceExplorerSetup</a>
     */
    public String createResourceExplorerSetup(List<String> regionList, String viewName,
                                              List<String> aggregatorRegions) {
        if (regionList == null || regionList.isEmpty()) {
            throw new AwsException("ValidationException", "RegionList must contain at least one Region.", 400);
        }
        validateRegions(regionList, "RegionList");
        List<String> aggregators = aggregatorRegions != null ? aggregatorRegions : List.of();
        if (aggregators.size() > MAX_AGGREGATOR_REGIONS) {
            throw new AwsException("ValidationException",
                    "AggregatorRegions accepts at most " + MAX_AGGREGATOR_REGIONS + " Region.", 400);
        }
        validateRegions(aggregators, "AggregatorRegions");
        for (String aggregator : aggregators) {
            if (!regionList.contains(aggregator)) {
                throw new AwsException("ValidationException",
                        "AggregatorRegions entry " + aggregator + " must also appear in RegionList.", 400);
            }
        }
        if (viewName == null || !VIEW_NAME_PATTERN.matcher(viewName).matches()) {
            throw new AwsException("ValidationException",
                    "View name must match [a-zA-Z0-9-] and be 1-64 characters: " + viewName, 400);
        }

        List<SetupTask.RegionOutcome> outcomes = new ArrayList<>();
        for (String region : regionList) {
            outcomes.add(setUpRegion(region, viewName, aggregators.contains(region)));
        }
        return recordTask(outcomes);
    }

    private SetupTask.RegionOutcome setUpRegion(String region, String viewName, boolean aggregator) {
        SetupOutcome indexOutcome;
        Index index;
        try {
            index = activeIndex(region).orElseGet(() -> createIndex(region, Map.of()));
            if (aggregator && index.type() != IndexType.AGGREGATOR) {
                index = updateIndexType(index.arn(), IndexType.AGGREGATOR);
            }
            indexOutcome = SetupOutcome.succeeded(index.arn());
        } catch (AwsException e) {
            LOG.warnf("Setup failed to prepare the index in %s: %s", region, e.getMessage());
            return new SetupTask.RegionOutcome(region, SetupOutcome.failed(e.getErrorCode(), e.getMessage()), null);
        }

        SetupOutcome viewOutcome;
        try {
            View view = createView(region, viewName, null, null,
                    List.of(new IncludedProperty("tags")), Map.of());
            if (defaultViewStore.get(region).isEmpty()) {
                defaultViewStore.put(region, view.viewArn());
            }
            viewOutcome = SetupOutcome.succeeded(view.viewArn());
        } catch (AwsException e) {
            LOG.warnf("Setup failed to create view %s in %s: %s", viewName, region, e.getMessage());
            viewOutcome = SetupOutcome.failed(e.getErrorCode(), e.getMessage());
        }
        return new SetupTask.RegionOutcome(region, indexOutcome, viewOutcome);
    }

    /**
     * Turns Resource Explorer off in the given Regions, or everywhere it is on.
     *
     * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_DeleteResourceExplorerSetup.html">
     *     AWS API: DeleteResourceExplorerSetup</a>
     */
    public String deleteResourceExplorerSetup(Boolean deleteInAllRegions, List<String> regionList) {
        boolean allRegions = Boolean.TRUE.equals(deleteInAllRegions);
        boolean hasRegionList = regionList != null && !regionList.isEmpty();
        if (allRegions && hasRegionList) {
            throw new AwsException("ValidationException",
                    "RegionList must not be provided when DeleteInAllRegions is true.", 400);
        }
        if (!allRegions && !hasRegionList) {
            throw new AwsException("ValidationException",
                    "Provide either RegionList or DeleteInAllRegions.", 400);
        }
        List<String> regions;
        if (allRegions) {
            regions = activeIndexes(null, null).stream().map(Index::region).distinct().toList();
        } else {
            validateRegions(regionList, "RegionList");
            regions = regionList;
        }

        List<SetupTask.RegionOutcome> outcomes = new ArrayList<>();
        for (String region : regions) {
            outcomes.add(tearDownRegion(region));
        }
        return recordTask(outcomes);
    }

    private SetupTask.RegionOutcome tearDownRegion(String region) {
        SetupOutcome viewOutcome = SetupOutcome.succeeded(null);
        for (View view : viewStore.scan(_ -> true)) {
            if (region.equals(regionOf(view))) {
                deleteView(view.viewArn());
            }
        }
        Optional<Index> index = activeIndex(region);
        if (index.isEmpty()) {
            return new SetupTask.RegionOutcome(region,
                    SetupOutcome.failed("ResourceNotFoundException", "No index found for region: " + region),
                    viewOutcome);
        }
        deleteIndex(index.get().arn());
        return new SetupTask.RegionOutcome(region, SetupOutcome.succeeded(index.get().arn()), viewOutcome);
    }

    private String recordTask(List<SetupTask.RegionOutcome> outcomes) {
        String taskId = UUID.randomUUID().toString();
        setupTaskStore.put(taskId, new SetupTask(taskId, outcomes, Instant.now()));
        return taskId;
    }

    private void validateRegions(List<String> regions, String field) {
        for (String region : regions) {
            if (region == null || !REGION_PATTERN.matcher(region).matches()) {
                throw new AwsException("ValidationException",
                        field + " contains an invalid AWS Region: " + region, 400);
            }
        }
    }

    /**
     * Reports a setup task's per-Region outcome. floci runs the task synchronously, so the status
     * is already terminal by the time the task id can be used here.
     */
    public ObjectNode getResourceExplorerSetup(String taskId, Integer maxResults, String nextToken) {
        SetupTask task = setupTaskStore.get(taskId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Setup task not found: " + taskId, 404));
        Paginated<SetupTask.RegionOutcome> page = paginate(task.regions(), maxResults, nextToken, 100);

        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode regions = result.putArray("Regions");
        for (SetupTask.RegionOutcome outcome : page.items()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Region", outcome.region());
            if (outcome.index() != null) {
                node.set("Index", setupStatusNode(outcome.index(), "Index", storedIndexNode(outcome.index().arn())));
            }
            if (outcome.view() != null) {
                node.set("View", setupStatusNode(outcome.view(), "View", storedViewNode(outcome.view().arn())));
            }
            regions.add(node);
        }
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return result;
    }

    /** The index a create step produced, or null once a delete step has removed it. */
    private ObjectNode storedIndexNode(String arn) {
        return arn == null ? null : indexStore.get(arn).map(this::indexNode).orElse(null);
    }

    /** The view a create step produced, or null once a delete step has removed it. */
    private ObjectNode storedViewNode(String arn) {
        return arn == null ? null : viewStore.get(arn).map(this::buildViewNode).orElse(null);
    }

    /** Wraps a step's status with the resource it produced, when that resource still exists. */
    private ObjectNode setupStatusNode(SetupOutcome outcome, String resourceField, ObjectNode resource) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Status", outcome.status());
        if (resource != null) {
            node.set(resourceField, resource);
        }
        if (outcome.errorCode() != null) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("Code", outcome.errorCode());
            error.put("Message", outcome.errorMessage());
            node.set("ErrorDetails", error);
        }
        return node;
    }

    public Map<String, String> listTags(String arn) {
        Map<String, String> tags = resolveTaggable(arn).entity().tags();
        return tags != null ? tags : Map.of();
    }

    public void tagResource(String arn, Map<String, String> tags) {
        mutateTags(arn, current -> current.putAll(tags));
    }

    public void untagResource(String arn, List<String> tagKeys) {
        mutateTags(arn, current -> tagKeys.forEach(current::remove));
    }

    private void mutateTags(String arn, Consumer<Map<String, String>> mutation) {
        StoredTaggable stored = resolveTaggable(arn);
        Map<String, String> updated = stored.entity().tags() != null
                ? new HashMap<>(stored.entity().tags()) : new HashMap<>();
        mutation.accept(updated);
        stored.persist().accept(stored.entity().withTags(updated));
    }

    /** A tagged entity (index or view) paired with the action that writes the updated copy back to its store. */
    private record StoredTaggable(Taggable entity, Consumer<Taggable> persist) {}

    private StoredTaggable resolveTaggable(String arn) {
        Optional<Index> index = indexStore.get(arn);
        if (index.isPresent()) {
            return new StoredTaggable(index.get(), updated -> indexStore.put(arn, (Index) updated));
        }
        Optional<View> view = viewStore.get(arn);
        if (view.isPresent()) {
            return new StoredTaggable(view.get(), updated -> viewStore.put(arn, (View) updated));
        }
        throw new AwsException("ResourceNotFoundException", "Resource not found: " + arn, 404);
    }

    private View resolveView(String viewArn, String region) {
        ensureDefaultRegionProvisioned();
        String arn = (viewArn != null) ? viewArn : defaultViewStore.get(region).orElse(null);
        if (arn == null) {
            throw new AwsException("UnauthorizedException",
                    "No default view is configured for region " + region
                            + " and no view was specified.", 401);
        }
        return viewStore.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "View not found: " + arn, 404));
    }

    private static int decodeNextToken(String token) {
        if (token == null) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(Base64.getDecoder().decode(token)));
        } catch (Exception e) {
            LOG.debugf("Invalid NextToken '%s', restarting from offset 0: %s", token, e.getMessage());
            return 0;
        }
    }

    private static String encodeNextToken(int offset) {
        return Base64.getEncoder().encodeToString(String.valueOf(offset).getBytes());
    }

    private ObjectNode buildResourceNode(ExplorerResource r, boolean includeTags) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Arn", r.arn());
        node.put("LastReportedAt", r.lastReportedAt().toString());
        node.put("OwningAccountId", r.owningAccountId());
        node.put("Region", r.region());
        node.put("ResourceType", r.resourceType());
        node.put("Service", r.service());

        ArrayNode properties = node.putArray("Properties");
        if (includeTags && r.tags() != null && !r.tags().isEmpty()) {
            ObjectNode tagProp = objectMapper.createObjectNode();
            ArrayNode tagData = objectMapper.createArrayNode();
            for (var entry : r.tags().entrySet()) {
                ObjectNode tagEntry = objectMapper.createObjectNode();
                tagEntry.put("Key", entry.getKey());
                tagEntry.put("Value", entry.getValue());
                tagData.add(tagEntry);
            }
            tagProp.set("Data", tagData);
            tagProp.put("LastReportedAt", r.lastReportedAt().toString());
            tagProp.put("Name", "tags");
            properties.add(tagProp);
        }
        return node;
    }
}
