package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.CreateApplicationRequest;
import io.floci.aws.services.emrserverless.model.ImageConfiguration;
import io.floci.aws.services.emrserverless.model.InitialCapacityConfig;
import io.floci.aws.services.emrserverless.model.MaximumCapacity;
import io.floci.aws.services.emrserverless.model.UpdateApplicationRequest;
import io.floci.aws.services.emrserverless.model.WorkerTypeSpecification;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmrServerlessServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final TypeReference<Map<String, Application>> APPLICATIONS = new TypeReference<>() {};
    private static final String CUSTOM_IMAGE = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.0";
    private static final String OTHER_IMAGE = "123456789012.dkr.ecr.us-east-1.amazonaws.com/spark/custom:7.5.1";
    // The settings a STARTED application's update may name, besides an idempotency token and the path's application.
    private static final Set<String> STARTED_UPDATE_MEMBERS = Set.of("setApplicationId", "setClientToken",
            "setMaximumCapacity", "setImageConfiguration", "setWorkerTypeSpecifications");

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

    @Test
    void anUpdateLeavingTheImageUriOutRemovesTheImageConfiguration() {
        EmrServerlessService service = service();
        CreateApplicationRequest create = createRequest();
        create.setImageConfiguration(image(CUSTOM_IMAGE));
        create.setWorkerTypeSpecifications(Map.of("Driver", workerType(CUSTOM_IMAGE)));
        String applicationId = service.createApplication(create).getApplicationId();
        ImageConfiguration withoutUri = new ImageConfiguration();
        withoutUri.setApplicationLevelDigestResolution(true);
        UpdateApplicationRequest update = new UpdateApplicationRequest();
        update.setImageConfiguration(withoutUri);
        update.setWorkerTypeSpecifications(Map.of("Driver", workerType(null)));

        Application updated = service.updateApplication(applicationId, update);

        assertNull(updated.getImageConfiguration());
        assertNull(updated.getWorkerTypeSpecifications().get("Driver").getImageConfiguration());
    }

    @Test
    void aNewApplicationsImageConfigurationNeedsAnImageUri() {
        EmrServerlessService service = service();
        CreateApplicationRequest application = createRequest();
        application.setImageConfiguration(image(null));
        CreateApplicationRequest workerType = createRequest();
        workerType.setWorkerTypeSpecifications(Map.of("Driver", workerType(null)));

        AwsException noApplicationImage = assertThrows(AwsException.class,
                () -> service.createApplication(application));
        AwsException noDriverImage = assertThrows(AwsException.class, () -> service.createApplication(workerType));

        assertEquals("ValidationException", noApplicationImage.getErrorCode());
        assertEquals("imageConfiguration.imageUri is required", noApplicationImage.getMessage());
        assertEquals("workerTypeSpecifications.Driver.imageConfiguration.imageUri is required",
                noDriverImage.getMessage());
    }

    @Test
    void aCustomImageMustBeInTheApplicationsRegion() {
        EmrServerlessService service = service();
        CreateApplicationRequest elsewhere = createRequest();
        elsewhere.setImageConfiguration(image("123456789012.dkr.ecr.us-west-2.amazonaws.com/spark/custom:7.5.0"));
        CreateApplicationRequest driverElsewhere = createRequest();
        driverElsewhere.setWorkerTypeSpecifications(
                Map.of("Driver", workerType("localhost:4566/000000000000/eu-west-1/spark/custom:7.5.0")));

        AwsException refused = assertThrows(AwsException.class, () -> service.createApplication(elsewhere));
        assertThrows(AwsException.class, () -> service.createApplication(driverElsewhere));

        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals("Image 123456789012.dkr.ecr.us-west-2.amazonaws.com/spark/custom:7.5.0 is in an Amazon ECR "
                + "repository in us-west-2; it must be in the application's Region, us-east-1.", refused.getMessage());
        // The repositoryUri Floci's ECR hands out, in the Region, and an image outside ECR, which has none.
        for (String imageUri : List.of("000000000000.dkr.ecr.us-east-1.localhost:4566/spark/custom:7.5.0",
                "python:3.12")) {
            CreateApplicationRequest accepted = createRequest();
            accepted.setImageConfiguration(image(imageUri));
            assertEquals(imageUri, service.createApplication(accepted).getImageConfiguration().getImageUri());
        }
    }

    @Test
    void anUpdateNamingAnImageInAnotherRegionChangesNothing() {
        EmrServerlessService service = service();
        CreateApplicationRequest create = createRequest();
        create.setImageConfiguration(image(CUSTOM_IMAGE));
        String applicationId = service.createApplication(create).getApplicationId();
        UpdateApplicationRequest update = new UpdateApplicationRequest();
        update.setReleaseLabel("emr-7.10.0");
        update.setImageConfiguration(image("123456789012.dkr.ecr.us-west-2.amazonaws.com/spark/custom:7.10.0"));

        assertThrows(AwsException.class, () -> service.updateApplication(applicationId, update));

        Application unchanged = service.getApplication(applicationId);
        assertEquals("emr-7.5.0", unchanged.getReleaseLabel());
        assertEquals(CUSTOM_IMAGE, unchanged.getImageConfiguration().getImageUri());
    }

    @Test
    void aStartedApplicationTakesANewMaximumCapacityAndCustomImagesButNoOtherSetting() throws Exception {
        EmrServerlessService service = service();
        CreateApplicationRequest create = createRequest();
        create.setImageConfiguration(image(CUSTOM_IMAGE));
        create.setMaximumCapacity(capacity("400 vCPU"));
        // An empty initial capacity pre-initializes nothing.
        create.setInitialCapacity(Map.of());
        String applicationId = service.createApplication(create).getApplicationId();
        service.startApplication(applicationId);
        UpdateApplicationRequest running = new UpdateApplicationRequest();
        running.setClientToken(UUID.randomUUID().toString());
        running.setMaximumCapacity(capacity("100 vCPU"));
        running.setImageConfiguration(image(OTHER_IMAGE));
        running.setWorkerTypeSpecifications(Map.of("Driver", workerType(OTHER_IMAGE)));

        Application updated = service.updateApplication(applicationId, running);

        assertEquals("STARTED", updated.getState());
        assertEquals("100 vCPU", updated.getMaximumCapacity().getCpu());
        assertEquals(OTHER_IMAGE, updated.getImageConfiguration().getImageUri());
        assertEquals(OTHER_IMAGE,
                updated.getWorkerTypeSpecifications().get("Driver").getImageConfiguration().getImageUri());
        // Every other setting waits for the application to stop, even beside a new maximum capacity.
        List<String> refused = new ArrayList<>();
        for (Method setter : UpdateApplicationRequest.class.getMethods()) {
            if (!setter.getName().startsWith("set") || STARTED_UPDATE_MEMBERS.contains(setter.getName())) {
                continue;
            }
            UpdateApplicationRequest other = new UpdateApplicationRequest();
            other.setMaximumCapacity(capacity("200 vCPU"));
            setter.invoke(other, someValue(setter.getParameterTypes()[0]));
            AwsException refusal = assertThrows(AwsException.class,
                    () -> service.updateApplication(applicationId, other), setter.getName());
            assertEquals("ValidationException", refusal.getErrorCode());
            assertEquals("Application must be in a stopped or created state in order to be updated.",
                    refusal.getMessage());
            refused.add(setter.getName());
        }
        assertEquals(13, refused.size(), refused::toString);
        Application unchanged = service.getApplication(applicationId);
        assertEquals("100 vCPU", unchanged.getMaximumCapacity().getCpu());
        assertEquals("emr-7.5.0", unchanged.getReleaseLabel());
    }

    @Test
    void aStartedApplicationWithInitialCapacityTakesANewMaximumCapacityButNoNewImages() {
        EmrServerlessService service = service();
        CreateApplicationRequest create = createRequest();
        create.setImageConfiguration(image(CUSTOM_IMAGE));
        InitialCapacityConfig drivers = new InitialCapacityConfig();
        drivers.setWorkerCount(1L);
        create.setInitialCapacity(Map.of("Driver", drivers));
        String applicationId = service.createApplication(create).getApplicationId();
        service.startApplication(applicationId);
        UpdateApplicationRequest newImage = new UpdateApplicationRequest();
        newImage.setImageConfiguration(image(OTHER_IMAGE));
        UpdateApplicationRequest newDriverImage = new UpdateApplicationRequest();
        newDriverImage.setWorkerTypeSpecifications(Map.of("Driver", workerType(OTHER_IMAGE)));
        UpdateApplicationRequest newCapacity = new UpdateApplicationRequest();
        newCapacity.setMaximumCapacity(capacity("100 vCPU"));

        AwsException refused = assertThrows(AwsException.class,
                () -> service.updateApplication(applicationId, newImage));
        assertThrows(AwsException.class, () -> service.updateApplication(applicationId, newDriverImage));
        Application updated = service.updateApplication(applicationId, newCapacity);

        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals("Custom image updates on running applications are not supported on applications with "
                + "pre-initialized capacity configured.", refused.getMessage());
        assertEquals(CUSTOM_IMAGE, updated.getImageConfiguration().getImageUri());
        assertNull(updated.getWorkerTypeSpecifications());
        assertEquals("100 vCPU", updated.getMaximumCapacity().getCpu());
        // Stopped, it takes one.
        service.stopApplication(applicationId);
        assertEquals(OTHER_IMAGE,
                service.updateApplication(applicationId, newImage).getImageConfiguration().getImageUri());
    }

    // A value of the type an update's setting takes, set rather than left out.
    private static Object someValue(Class<?> type) throws ReflectiveOperationException {
        if (type == String.class) {
            return "x";
        }
        if (type == Map.class) {
            return Map.of();
        }
        if (type == List.class) {
            return List.of();
        }
        return type.getDeclaredConstructor().newInstance();
    }

    static MaximumCapacity capacity(String cpu) {
        MaximumCapacity capacity = new MaximumCapacity();
        capacity.setCpu(cpu);
        return capacity;
    }

    private static EmrServerlessService service() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT_ID);
        return new EmrServerlessService(config, new InMemoryStorageFactory(ACCOUNT_ID),
                new RegionResolver(REGION, ACCOUNT_ID), new MutableClock());
    }

    private static CreateApplicationRequest createRequest() {
        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setReleaseLabel("emr-7.5.0");
        create.setType("SPARK");
        create.setClientToken(UUID.randomUUID().toString());
        return create;
    }

    static ImageConfiguration image(String imageUri) {
        ImageConfiguration image = new ImageConfiguration();
        image.setImageUri(imageUri);
        return image;
    }

    static WorkerTypeSpecification workerType(String imageUri) {
        WorkerTypeSpecification specification = new WorkerTypeSpecification();
        specification.setImageConfiguration(image(imageUri));
        return specification;
    }
}
