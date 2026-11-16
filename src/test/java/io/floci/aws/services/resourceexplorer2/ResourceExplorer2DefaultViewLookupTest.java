package io.floci.aws.services.resourceexplorer2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.resource.ResourceProvider;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.resourceexplorer2.model.Index;
import io.floci.aws.services.resourceexplorer2.model.View;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The default view a request naming no view searches, as IAM reads it before the request runs: read without writing
 * anything, and the view the request then searches, whatever another request associates in between.
 */
class ResourceExplorer2DefaultViewLookupTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String VIEWS = "arn:aws:resource-explorer-2:" + REGION + ":" + ACCOUNT + ":view/";

    private final StorageBackend<String, Index> indexStore = new InMemoryStorage<>();
    private final StorageBackend<String, View> viewStore = new InMemoryStorage<>();
    private final StorageBackend<String, String> defaultViewStore = new InMemoryStorage<>();
    @SuppressWarnings("unchecked")
    private final Instance<ResourceProvider> providers = mock(Instance.class);
    private final RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
    private final ResourceExplorer2Service service = new ResourceExplorer2Service(providers, regionResolver,
            new ObjectMapper(), indexStore, viewStore, defaultViewStore, new InMemoryStorage<>(),
            new ResourceExplorer2RequestContext());

    @Test
    void theDefaultViewIsReadWithoutProvisioningTheBaseline() {
        // No index yet: a request naming no view would provision the baseline, whose view's id is not minted yet.
        assertEquals(Optional.of(VIEWS + "default-view/*"), service.findDefault("view", REGION));
        assertTrue(indexStore.scan(key -> true).isEmpty());
        assertTrue(viewStore.scan(key -> true).isEmpty());
        assertTrue(defaultViewStore.get(REGION).isEmpty());

        String provisioned = service.getDefaultView(REGION);
        assertEquals(Optional.of(provisioned), service.findDefault("view", REGION));
        assertEquals(Optional.empty(), service.findDefault("index", REGION));
        assertEquals(Optional.empty(), service.findDefault("view", "eu-west-1"));
    }

    @Test
    void aRequestNamingNoViewSearchesTheDefaultViewItsCheckFound() {
        when(providers.iterator()).thenAnswer(invocation -> List.<ResourceProvider>of().iterator());
        String checked = service.getDefaultView(REGION);
        String other = service.createView(REGION, "other", null, null, List.of(), Map.of()).viewArn();

        assertEquals(Optional.of(checked), service.findDefault("view", REGION));
        service.associateDefaultView(REGION, other);
        assertEquals(checked, service.search(null, null, null, null, REGION).path("ViewArn").asText());
        assertEquals(checked, service.listResources(null, null, null, null, REGION).path("ViewArn").asText());
        // A request no check read the default for searches the one it finds.
        ResourceExplorer2Service unchecked = new ResourceExplorer2Service(providers, regionResolver, new ObjectMapper(),
                indexStore, viewStore, defaultViewStore);
        assertEquals(other, unchecked.search(null, null, null, null, REGION).path("ViewArn").asText());
    }

    @Test
    void aBaselineTheCheckFoundAboutToBeProvisionedIsTheOneSearched() {
        when(providers.iterator()).thenAnswer(invocation -> List.<ResourceProvider>of().iterator());
        service.findDefault("view", REGION);

        String searched = service.search(null, null, null, null, REGION).path("ViewArn").asText();
        assertEquals(defaultViewStore.get(REGION).orElseThrow(), searched);
        assertTrue(searched.startsWith(VIEWS + "default-view/"), searched);
    }

    @Test
    void concurrentFirstRequestsProvisionTheBaselineOnce() throws Exception {
        int requests = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        Set<String> defaults = new HashSet<>();
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return service.getDefaultView(REGION);
                }));
            }
            start.countDown();
            for (Future<String> answer : answers) {
                defaults.add(answer.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, defaults.size());
        assertEquals(1, indexStore.scan(key -> true).size());
        assertEquals(1, viewStore.scan(key -> true).size());
    }
}
