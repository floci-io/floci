package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.emrserverless.model.Application;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmrServerlessServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final TypeReference<Map<String, Application>> APPLICATIONS = new TypeReference<>() {};

    @TempDir
    Path tempDir;

    @Test
    void applicationIsStampedFromTheClockToTheMillisecond() {
        MutableClock clock = new MutableClock();
        clock.advance(Duration.ofNanos(250_400_000));
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT_ID);
        EmrServerlessService service = new EmrServerlessService(config, new InMemoryStorageFactory(ACCOUNT_ID),
                new RegionResolver(REGION, ACCOUNT_ID), clock);
        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setReleaseLabel("emr-7.5.0");
        create.setType("SPARK");
        create.setClientToken(UUID.randomUUID().toString());

        Application created = service.createApplication(create);
        assertEquals(Instant.parse("2026-01-01T00:00:00.250Z"), created.getCreatedAt());
        assertEquals(created.getCreatedAt(), created.getUpdatedAt());

        // The test clock moves a millisecond on every read.
        clock.advance(Duration.ofMinutes(5));
        service.startApplication(created.getApplicationId());
        Application started = service.getApplication(created.getApplicationId());
        assertEquals(Instant.parse("2026-01-01T00:00:00.250Z"), started.getCreatedAt());
        assertEquals(Instant.parse("2026-01-01T00:05:00.251Z"), started.getUpdatedAt());
    }

    @Test
    void applicationPersistedWithMillisecondTimestampsReadsBackAtTheSameInstant() throws IOException {
        // As Floci stored applications before their timestamps were epoch seconds.
        Path file = tempDir.resolve("emr-serverless-applications.json");
        Files.writeString(file, """
                {"00abc": {"applicationId": "00abc", "createdAt": 1792584000123, "updatedAt": 1792584060456}}
                """);
        PersistentStorage<String, Application> storage = new PersistentStorage<>(file, APPLICATIONS);
        storage.load();

        Application app = storage.get("00abc").orElseThrow();
        assertEquals(Instant.parse("2026-10-21T12:00:00.123Z"), app.getCreatedAt());
        assertEquals(Instant.parse("2026-10-21T12:01:00.456Z"), app.getUpdatedAt());
    }

    @Test
    void applicationTimestampsAndClientTokenSurviveAReload() {
        Path file = tempDir.resolve("emr-serverless-applications.json");
        Application app = new Application();
        app.setApplicationId("00abc");
        app.setClientToken("create-00abc");
        app.setCreatedAt(Instant.parse("2026-10-21T12:00:00.123Z"));
        app.setUpdatedAt(Instant.parse("2026-10-21T12:01:00.456Z"));
        new PersistentStorage<>(file, APPLICATIONS).put("00abc", app);

        PersistentStorage<String, Application> reader = new PersistentStorage<>(file, APPLICATIONS);
        reader.load();

        Application reloaded = reader.get("00abc").orElseThrow();
        assertEquals(app.getCreatedAt(), reloaded.getCreatedAt());
        assertEquals(app.getUpdatedAt(), reloaded.getUpdatedAt());
        assertEquals("create-00abc", reloaded.getClientToken());
    }
}
