package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecr.model.RegistrySettings;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EcrPullTimeExclusionsPersistenceTest {
    private static final String REGION = "eu-west-2";
    private static final String ACCOUNT = "741683100099";
    private static final String KEY = REGION + "::" + ACCOUNT;
    private static final String ROLE = "arn:aws:iam::123456789012:role/Scanner";

    @Test
    void oldRegistryRecordsAndSerializedExclusionsKeepOtherSettings() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        InMemoryStorage<String, RegistrySettings> store = new InMemoryStorage<>();
        store.put(KEY, mapper.readValue("{\"scanType\":\"ENHANCED\",\"policyText\":\"{}\"}", RegistrySettings.class));
        EcrService service = service(store);
        assertTrue(service.listPullTimeUpdateExclusions(null, null, REGION).items().isEmpty());
        Instant created = service.registerPullTimeUpdateExclusion(ROLE, REGION).createdAt();
        String saved = mapper.writeValueAsString(store.get(KEY).orElseThrow());
        InMemoryStorage<String, RegistrySettings> restored = new InMemoryStorage<>();
        restored.put(KEY, mapper.readValue(saved, RegistrySettings.class));
        EcrService reloaded = service(restored);
        assertEquals(List.of(ROLE), reloaded.listPullTimeUpdateExclusions(null, null, REGION).items());
        assertEquals(created, restored.get(KEY).orElseThrow().getPullTimeUpdateExclusions().getFirst().createdAt());
        assertEquals("ENHANCED", reloaded.registryScanningConfiguration(REGION).getScanType());
        assertEquals("{}", reloaded.getRegistryPolicy(REGION));
        reloaded.putRegistryScanningConfiguration("BASIC", List.of(), REGION);
        assertEquals(List.of(ROLE), reloaded.listPullTimeUpdateExclusions(null, null, REGION).items());
        reloaded.deregisterPullTimeUpdateExclusion(ROLE, REGION);
        assertEquals("{}", reloaded.getRegistryPolicy(REGION));
    }

    @Test
    void concurrentRegistrationsOfOnePrincipalHaveOneWinner() throws Exception {
        EcrService service = service(new InMemoryStorage<>());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> registerAfter(start, service));
            Future<String> second = executor.submit(() -> registerAfter(start, service));
            start.countDown();
            assertEquals(List.of("ExclusionAlreadyExistsException", "registered"),
                    List.of(first.get(), second.get()).stream().sorted().toList());
        }
        assertEquals(List.of(ROLE), service.listPullTimeUpdateExclusions(null, null, REGION).items());
    }

    private String registerAfter(CountDownLatch start, EcrService service) throws InterruptedException {
        start.await();
        try {
            service.registerPullTimeUpdateExclusion(ROLE, REGION);
            return "registered";
        } catch (AwsException duplicate) {
            return duplicate.getErrorCode();
        }
    }

    private EcrService service(InMemoryStorage<String, RegistrySettings> settings) {
        return new EcrService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                settings, new InMemoryStorage<>(), mock(EcrRegistryManager.class), mock(EmulatorConfig.class),
                new RegionResolver(REGION, ACCOUNT));
    }
}
