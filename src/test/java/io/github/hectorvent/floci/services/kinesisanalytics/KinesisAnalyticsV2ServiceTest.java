package io.github.hectorvent.floci.services.kinesisanalytics;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.kinesisanalytics.container.FlinkContainerManager;
import io.github.hectorvent.floci.services.kinesisanalytics.model.ApplicationStatus;
import io.github.hectorvent.floci.services.kinesisanalytics.model.FlinkApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class KinesisAnalyticsV2ServiceTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/x";

    private KinesisAnalyticsV2Service service;

    @BeforeEach
    void setUp() {
        service = mockModeService();
    }

    private FlinkApplication create(String name) {
        return service.createApplication(name, "FLINK-1_18", ROLE, "desc", "STREAMING");
    }

    @Test
    void createApplicationLandsInReady() {
        // AWS-faithful: a freshly created application is READY, not RUNNING.
        FlinkApplication app = create("demo");
        assertEquals("demo", app.getApplicationName());
        assertEquals("FLINK-1_18", app.getRuntimeEnvironment());
        assertEquals(ApplicationStatus.READY, app.getApplicationStatus());
        assertEquals(1L, app.getApplicationVersionId());
        assertTrue(app.getApplicationArn().contains(":kinesisanalytics:"));
        assertTrue(app.getApplicationArn().contains("application/demo"));
    }

    @Test
    void createApplicationRequiresName() {
        assertThrows(AwsException.class,
                () -> service.createApplication(" ", "FLINK-1_18", ROLE, null, null));
    }

    @Test
    void createApplicationRequiresRuntimeEnvironment() {
        assertThrows(AwsException.class,
                () -> service.createApplication("demo", null, ROLE, null, null));
    }

    @Test
    void createApplicationRequiresServiceExecutionRole() {
        assertThrows(AwsException.class,
                () -> service.createApplication("demo", "FLINK-1_18", null, null, null));
    }

    @Test
    void createApplicationRejectsUnsupportedRuntime() {
        // SQL/ZEPPELIN studio runtimes and bogus values are not backable with a Flink image.
        assertThrows(AwsException.class,
                () -> service.createApplication("demo", "ZEPPELIN-FLINK-3_0", ROLE, null, null));
    }

    @Test
    void createApplicationRejectsDuplicateName() {
        create("demo");
        assertThrows(AwsException.class, () -> create("demo"));
    }

    @Test
    void describeApplicationThrowsWhenMissing() {
        assertThrows(AwsException.class, () -> service.describeApplication("nope"));
    }

    @Test
    void startApplicationRunsInMockMode() {
        create("demo");
        FlinkApplication started = service.startApplication("demo");
        // In mock mode there is no container: the application comes up RUNNING immediately.
        assertEquals(ApplicationStatus.RUNNING, started.getApplicationStatus());
    }

    @Test
    void startApplicationRejectedWhenNotReady() {
        create("demo");
        service.startApplication("demo");
        // Already RUNNING → cannot start again.
        assertThrows(AwsException.class, () -> service.startApplication("demo"));
    }

    @Test
    void stopApplicationReturnsToReady() {
        create("demo");
        service.startApplication("demo");
        FlinkApplication stopped = service.stopApplication("demo");
        assertEquals(ApplicationStatus.READY, stopped.getApplicationStatus());
    }

    @Test
    void stopApplicationRejectedWhenNotRunning() {
        create("demo");
        // Still READY (never started) → cannot stop.
        assertThrows(AwsException.class, () -> service.stopApplication("demo"));
    }

    @Test
    void updateApplicationBumpsVersion() {
        create("demo");
        FlinkApplication updated = service.updateApplication("demo", "arn:aws:iam::000000000000:role/y");
        assertEquals(2L, updated.getApplicationVersionId());
        assertEquals("arn:aws:iam::000000000000:role/y", updated.getServiceExecutionRole());
    }

    @Test
    void deleteApplicationRemovesIt() {
        create("demo");
        service.deleteApplication("demo");
        assertTrue(service.listApplications().isEmpty());
    }

    @Test
    void startApplicationWrapsProvisioningFailure() {
        // Real mode with a container manager that fails: the AWS envelope must not leak the
        // internal cause, but the operation must throw.
        KinesisAnalyticsV2Service realMode = realModeServiceWithFailingManager();
        realMode.createApplication("demo", "FLINK-1_18", ROLE, null, null);
        assertThrows(AwsException.class, () -> realMode.startApplication("demo"));
    }

    private KinesisAnalyticsV2Service mockModeService() {
        return buildService(true, Mockito.mock(FlinkContainerManager.class));
    }

    private KinesisAnalyticsV2Service realModeServiceWithFailingManager() {
        FlinkContainerManager failing = Mockito.mock(FlinkContainerManager.class);
        Mockito.doThrow(new RuntimeException("docker unavailable"))
                .when(failing).startCluster(Mockito.any());
        return buildService(false, failing);
    }

    private KinesisAnalyticsV2Service buildService(boolean mock, FlinkContainerManager manager) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var kaConfig = Mockito.mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.kinesisAnalytics()).thenReturn(kaConfig);
        when(kaConfig.mock()).thenReturn(mock);
        when(config.defaultRegion()).thenReturn("us-east-1");

        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        return new KinesisAnalyticsV2Service(storageFactory, config, regionResolver, manager);
    }
}
