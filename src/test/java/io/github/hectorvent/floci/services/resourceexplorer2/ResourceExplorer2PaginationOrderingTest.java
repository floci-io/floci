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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code NextToken} on ListResources/Search is a bare offset, and the list it indexes into is
 * rebuilt on every call by iterating {@code Instance<ResourceProvider>} and each provider's
 * {@code StorageBackend.scan} — neither of which promises an order. Offset pagination is only
 * well-defined over a stable order, so the service imposes one (by ARN) before slicing.
 *
 * <p>The provider here returns its resources in a different order on every call, which is what an
 * unordered map-backed store is free to do. Without the sort the second page indexes into a
 * differently arranged list and resources are silently dropped or returned twice.
 */
class ResourceExplorer2PaginationOrderingTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final StorageBackend<String, Index> indexStore = new InMemoryStorage<>();
    private final StorageBackend<String, View> viewStore = new InMemoryStorage<>();
    private final StorageBackend<String, String> defaultViewStore = new InMemoryStorage<>();
    private final RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Zero-padded so lexical ARN order matches creation order, keeping the assertions readable. */
    private static String arn(int i) {
        return String.format("arn:aws:test:%s:%s:thing/%03d", REGION, ACCOUNT, i);
    }

    /**
     * A provider whose resource set can grow between calls and whose iteration order is reshuffled
     * on every call, modelling an unordered store scan.
     */
    private static final class ShufflingProvider implements ResourceProvider {

        private final List<String> arns = new ArrayList<>();
        private final Random random = new Random(42);

        void add(String resourceArn) {
            arns.add(resourceArn);
        }

        @Override
        public List<ExplorerResource> getResources() {
            List<String> order = new ArrayList<>(arns);
            Collections.shuffle(order, random);
            List<ExplorerResource> out = new ArrayList<>(order.size());
            for (String a : order) {
                out.add(new ExplorerResource(a, "test:thing", "test", REGION, ACCOUNT,
                        Instant.now(), Map.of()));
            }
            return out;
        }

        @Override
        public Set<SupportedResourceType> getSupportedResourceTypes() {
            return Set.of(new SupportedResourceType("test:thing", "test", true));
        }
    }

    private ResourceExplorer2Service newServiceWith(ResourceProvider provider) {
        @SuppressWarnings("unchecked")
        Instance<ResourceProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(inv -> List.of(provider).iterator());
        ResourceExplorer2Service service = new ResourceExplorer2Service(
                providers, regionResolver, objectMapper, indexStore, viewStore, defaultViewStore);
        service.onStartup(new StartupEvent());
        return service;
    }

    private static List<String> arnsOf(ObjectNode response) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : response.get("Resources")) {
            out.add(n.get("Arn").asText());
        }
        return out;
    }

    @Test
    void pagingAReshuffledResultSetReturnsEveryResourceExactlyOnce() {
        ShufflingProvider provider = new ShufflingProvider();
        for (int i = 0; i < 25; i++) {
            provider.add(arn(i));
        }
        ResourceExplorer2Service service = newServiceWith(provider);

        List<String> seen = new ArrayList<>();
        String token = null;
        do {
            ObjectNode r = service.listResources(null, 5, token, null, REGION);
            seen.addAll(arnsOf(r));
            token = r.has("NextToken") ? r.get("NextToken").asText() : null;
        } while (token != null);

        // Nothing dropped, nothing repeated — and in the order the offsets assume.
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            expected.add(arn(i));
        }
        assertEquals(expected, seen);
    }

    @Test
    void searchPagesAReshuffledResultSetWithoutDropsOrDuplicates() {
        ShufflingProvider provider = new ShufflingProvider();
        for (int i = 0; i < 25; i++) {
            provider.add(arn(i));
        }
        ResourceExplorer2Service service = newServiceWith(provider);

        List<String> seen = new ArrayList<>();
        String token = null;
        do {
            ObjectNode r = service.search("", 5, token, null, REGION);
            seen.addAll(arnsOf(r));
            token = r.has("NextToken") ? r.get("NextToken").asText() : null;
        } while (token != null);

        assertEquals(25, seen.size());
        assertEquals(25, Set.copyOf(seen).size());
    }

    @Test
    void resourceCreatedMidPaginationDoesNotDisturbThePagesAlreadyRead() {
        ShufflingProvider provider = new ShufflingProvider();
        for (int i = 0; i < 10; i++) {
            provider.add(arn(i));
        }
        ResourceExplorer2Service service = newServiceWith(provider);

        ObjectNode first = service.listResources(null, 5, null, null, REGION);
        List<String> seen = new ArrayList<>(arnsOf(first));
        String token = first.get("NextToken").asText();

        // A resource appears between the two calls, as it would in a live emulator.
        provider.add(arn(99));

        do {
            ObjectNode r = service.listResources(null, 5, token, null, REGION);
            seen.addAll(arnsOf(r));
            token = r.has("NextToken") ? r.get("NextToken").asText() : null;
        } while (token != null);

        // The insert sorts past the pages already read, so it is appended rather than shifting the
        // offsets: every original ARN is returned once, plus the new one.
        assertEquals(11, seen.size());
        assertEquals(11, Set.copyOf(seen).size());
        assertTrue(seen.contains(arn(99)));
        for (int i = 0; i < 10; i++) {
            assertTrue(seen.contains(arn(i)), "dropped " + arn(i));
        }
    }

    @Test
    void listSupportedResourceTypesPagesInAStableOrder() {
        // Same offset-token mechanics, over a list built by iterating providers rather than a store.
        List<ResourceProvider> providers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String type = String.format("test:type%02d", i);
            providers.add(new ResourceProvider() {
                @Override
                public List<ExplorerResource> getResources() {
                    return List.of();
                }

                @Override
                public Set<SupportedResourceType> getSupportedResourceTypes() {
                    return Set.of(new SupportedResourceType(type, "test", true));
                }
            });
        }
        List<ResourceProvider> shuffleSource = new ArrayList<>(providers);
        Random random = new Random(7);
        @SuppressWarnings("unchecked")
        Instance<ResourceProvider> instance = mock(Instance.class);
        when(instance.iterator()).thenAnswer(inv -> {
            Collections.shuffle(shuffleSource, random);
            return new ArrayList<>(shuffleSource).iterator();
        });
        ResourceExplorer2Service service = new ResourceExplorer2Service(
                instance, regionResolver, objectMapper, indexStore, viewStore, defaultViewStore);
        service.onStartup(new StartupEvent());

        List<String> seen = new ArrayList<>();
        String token = null;
        do {
            ObjectNode r = service.listSupportedResourceTypes(5, token);
            for (JsonNode n : r.get("ResourceTypes")) {
                seen.add(n.get("ResourceType").asText());
            }
            token = r.has("NextToken") ? r.get("NextToken").asText() : null;
        } while (token != null);

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            expected.add(String.format("test:type%02d", i));
        }
        assertEquals(expected, seen);
    }
}
