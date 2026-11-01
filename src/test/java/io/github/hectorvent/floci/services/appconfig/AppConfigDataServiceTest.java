package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationSession;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class AppConfigDataServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void concurrentPollsConsumeAConfigurationTokenOnlyOnce() throws Exception {
        InMemoryStorageFactory factory = new InMemoryStorageFactory("000000000000");
        AppConfigService appConfig = mock(AppConfigService.class);
        AppConfigDataService service = new AppConfigDataService(factory, appConfig, MAPPER);
        AccountAwareStorageBackend<ConfigurationSession> sessions = factory.create("appconfigdata",
                "appconfigdata-sessions.json", new TypeReference<Map<String, ConfigurationSession>>() {});
        ConfigurationSession session = new ConfigurationSession();
        session.setApplicationId("app");
        session.setEnvironmentId("env");
        session.setConfigurationProfileId("profile");
        session.setCurrentToken("initial");
        sessions.put("initial", session);
        ConfigurationSession otherSession = new ConfigurationSession();
        otherSession.setApplicationId("app");
        otherSession.setEnvironmentId("env");
        otherSession.setConfigurationProfileId("profile");
        otherSession.setCurrentToken("other");
        sessions.put("other", otherSession);

        CountDownLatch firstReadToken = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                firstReadToken.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
            }
            return null;
        }).when(appConfig).getEnvironment("app", "env");

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<Object> firstCall = pool.submit(() -> pollOrError(service));
            assertTrue(firstReadToken.await(5, TimeUnit.SECONDS));
            AtomicReference<Thread> secondThread = new AtomicReference<>();
            Future<Object> secondCall = pool.submit(() -> {
                secondThread.set(Thread.currentThread());
                return pollOrError(service);
            });
            awaitBlockedOrDone(secondThread, secondCall);
            assertTrue(secondCall.isDone() || (secondThread.get() != null
                    && secondThread.get().getState() == Thread.State.BLOCKED), "second poll overlaps the first");
            Future<AppConfigDataService.ConfigurationData> otherCall = pool.submit(
                    () -> service.getLatestConfiguration("other"));
            assertInstanceOf(AppConfigDataService.ConfigurationData.class, otherCall.get(5, TimeUnit.SECONDS));
            releaseFirst.countDown();

            List<Object> results = List.of(firstCall.get(5, TimeUnit.SECONDS), secondCall.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(AppConfigDataService.ConfigurationData.class::isInstance).count());
            assertEquals(1, results.stream().filter(AwsException.class::isInstance).count());
            AwsException rejected = (AwsException) results.stream().filter(AwsException.class::isInstance)
                    .findFirst().orElseThrow();
            assertEquals("BadRequestException", rejected.getErrorCode());
            assertEquals("Invalid configuration token", rejected.getMessage());
            assertEquals(400, rejected.getHttpStatus());
            AppConfigDataService.ConfigurationData accepted = (AppConfigDataService.ConfigurationData) results.stream()
                    .filter(AppConfigDataService.ConfigurationData.class::isInstance).findFirst().orElseThrow();
            assertTrue(sessions.get("initial").isEmpty());
            assertTrue(sessions.get(accepted.nextPollConfigurationToken()).isPresent());
            assertEquals("BadRequestException", assertThrows(AwsException.class,
                    () -> service.getLatestConfiguration("initial")).getErrorCode());
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private static Object pollOrError(AppConfigDataService service) {
        try {
            return service.getLatestConfiguration("initial");
        } catch (AwsException e) {
            return e;
        }
    }

    @Test
    void transformFeatureFlagsProducesRetrievalTimeValues() throws Exception {
        byte[] deploymentContent = """
                {
                  "flags": {
                    "enabled": {"name": "enabled"},
                    "disabled": {"name": "disabled"}
                  },
                  "values": {
                    "enabled": {
                      "enabled": true,
                      "number": 0,
                      "beta": false,
                      "empty": "",
                      "choices": [],
                      "details": {"tier": "gold"},
                      "_createdAt": "created",
                      "_updatedAt": "updated"
                    },
                    "disabled": {"enabled": false, "secret": "must-not-leak"}
                  },
                  "version": "1"
                }
                """.getBytes(StandardCharsets.UTF_8);

        byte[] retrievalContent = AppConfigDataService.transformFeatureFlags(deploymentContent, MAPPER);

        assertEquals(MAPPER.readTree("""
                {
                  "enabled": {
                    "enabled": true,
                    "number": 0,
                    "beta": false,
                    "empty": "",
                    "choices": [],
                    "details": {"tier": "gold"}
                  },
                  "disabled": {"enabled": false}
                }
                """), MAPPER.readTree(retrievalContent));
    }

    @Test
    void transformFeatureFlagsPreservesWholeMultiVariantDocument() {
        byte[] content = """
                {"values":{"basic":{"enabled":true},"variant":{"enabled":true,"_variants":[]}}}
                """.getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(content, AppConfigDataService.transformFeatureFlags(content, MAPPER));
    }

    @Test
    void transformFeatureFlagsPreservesInvalidDocuments() {
        List<String> documents = List.of(
                "{not-json",
                "[]",
                "{}",
                "{\"values\":[]}",
                "{\"values\":{\"flag\":true}}",
                "{\"values\":{\"flag\":{}}}",
                "{\"values\":{\"flag\":{\"enabled\":\"true\"}}}");

        for (String document : documents) {
            byte[] content = document.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(content, AppConfigDataService.transformFeatureFlags(content, MAPPER));
        }
    }

    @Test
    void transformFeatureFlagsPreservesNullAndEmptyContent() {
        assertNull(AppConfigDataService.transformFeatureFlags(null, MAPPER));
        assertArrayEquals(new byte[0], AppConfigDataService.transformFeatureFlags(new byte[0], MAPPER));
    }
}
