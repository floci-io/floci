package io.github.hectorvent.floci.services.resourceexplorer2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.resourceexplorer2.model.Index;
import io.github.hectorvent.floci.services.resourceexplorer2.model.View;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ListResources and Search share floci's resource pagination but differ in AWS: Search caps the total
 * result set at 1000, ListResources does not. These service-level tests pin that difference with a
 * provider that exposes 1500 resources.
 */
class ResourceExplorer2ListVsSearchCapTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final int RESOURCE_COUNT = 1500;

    private final StorageBackend<String, Index> indexStore = new InMemoryStorage<>();
    private final StorageBackend<String, View> viewStore = new InMemoryStorage<>();
    private final StorageBackend<String, String> defaultViewStore = new InMemoryStorage<>();
    private final RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ResourceExplorer2Service newServiceWith(int resourceCount) {
        ResourceProvider provider = new ResourceProvider() {
            @Override
            public List<ExplorerResource> getResources() {
                List<ExplorerResource> out = new ArrayList<>(resourceCount);
                for (int i = 0; i < resourceCount; i++) {
                    out.add(new ExplorerResource(
                            "arn:aws:test:" + REGION + ":" + ACCOUNT + ":thing/" + i,
                            "test:thing", "test", REGION, ACCOUNT, Instant.now(), Map.of()));
                }
                return out;
            }

            @Override
            public Set<SupportedResourceType> getSupportedResourceTypes() {
                return Set.of(new SupportedResourceType("test:thing", "test", true));
            }
        };
        @SuppressWarnings("unchecked")
        Instance<ResourceProvider> providers = mock(Instance.class);
        // Enhanced-for calls iterator() once per pagination call; hand back a fresh iterator each time.
        when(providers.iterator()).thenAnswer(inv -> List.of(provider).iterator());

        ResourceExplorer2Service service = new ResourceExplorer2Service(
                providers, regionResolver, objectMapper, indexStore, viewStore, defaultViewStore);
        service.onStartup(new StartupEvent());
        return service;
    }

    @Test
    void listResourcesPagesThroughAllResourcesWithNoTotalCap() {
        // ListResources has no 1000-result cap (unlike Search); page until NextToken is null.
        // https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_ListResources.html
        ResourceExplorer2Service service = newServiceWith(RESOURCE_COUNT);

        Set<String> seen = new HashSet<>();
        String token = null;
        int pages = 0;
        do {
            ObjectNode r = service.listResources(null, 500, token, null, REGION);
            for (JsonNode n : r.get("Resources")) {
                seen.add(n.get("Arn").asText());
            }
            token = r.has("NextToken") ? r.get("NextToken").asText() : null;
            pages++;
        } while (token != null);

        assertEquals(RESOURCE_COUNT, seen.size());
        assertEquals(3, pages);
    }

    @Test
    void listResourcesAtMaxPageSizeTruncatesSilentlyWithNoNextToken() {
        // Documented AWS quirk: "The ListResources operation does not generate a NextToken if you set
        // MaxResults to 1000." So the caller silently gets at most 1000 with no way to page further.
        // https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_ListResources.html
        ResourceExplorer2Service service = newServiceWith(RESOURCE_COUNT);

        ObjectNode r = service.listResources(null, 1000, null, null, REGION);

        assertEquals(1000, r.get("Resources").size());
        assertFalse(r.has("NextToken"));
    }

    @Test
    void searchCapsTotalAtOneThousandAndReportsIncomplete() {
        // Search "can return only the first 1,000 results."
        // https://docs.aws.amazon.com/resource-explorer/latest/apireference/API_Search.html
        ResourceExplorer2Service service = newServiceWith(RESOURCE_COUNT);

        Set<String> seen = new HashSet<>();
        String token = null;
        boolean lastComplete = true;
        int lastTotal = -1;
        do {
            // Empty query string matches all resources (see search()'s blank-query branch).
            ObjectNode r = service.search("", null, token, null, REGION);
            for (JsonNode n : r.get("Resources")) {
                seen.add(n.get("Arn").asText());
            }
            lastComplete = r.get("Count").get("Complete").asBoolean();
            lastTotal = r.get("Count").get("TotalResources").asInt();
            token = r.has("NextToken") ? r.get("NextToken").asText() : null;
        } while (token != null);

        assertEquals(1000, seen.size());
        assertFalse(lastComplete);
        assertEquals(1000, lastTotal);
    }

    @Test
    void oneThrowingProviderIsSkippedAndHealthyProvidersStillSurface() {
        // Discovery aggregates every provider. A single provider blowing up (bad stored data, a bug)
        // must not turn Search/ListResources into a 500 for every other service — the failing
        // provider is skipped and the healthy ones still surface.
        ResourceProvider healthy = new ResourceProvider() {
            @Override
            public List<ExplorerResource> getResources() {
                return List.of(new ExplorerResource(
                        "arn:aws:test:" + REGION + ":" + ACCOUNT + ":thing/ok",
                        "test:thing", "test", REGION, ACCOUNT, Instant.now(), Map.of()));
            }

            @Override
            public Set<SupportedResourceType> getSupportedResourceTypes() {
                return Set.of(new SupportedResourceType("test:thing", "test", true));
            }
        };
        ResourceProvider broken = new ResourceProvider() {
            @Override
            public List<ExplorerResource> getResources() {
                throw new IllegalStateException("provider is broken");
            }

            @Override
            public Set<SupportedResourceType> getSupportedResourceTypes() {
                return Set.of();
            }
        };
        @SuppressWarnings("unchecked")
        Instance<ResourceProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(inv -> List.of(broken, healthy).iterator());

        ResourceExplorer2Service service = new ResourceExplorer2Service(
                providers, regionResolver, objectMapper, indexStore, viewStore, defaultViewStore);
        service.onStartup(new StartupEvent());

        ObjectNode r = service.listResources(null, 1000, null, null, REGION);

        assertEquals(1, r.get("Resources").size());
        assertEquals("arn:aws:test:" + REGION + ":" + ACCOUNT + ":thing/ok",
                r.get("Resources").get(0).get("Arn").asText());
    }
}
