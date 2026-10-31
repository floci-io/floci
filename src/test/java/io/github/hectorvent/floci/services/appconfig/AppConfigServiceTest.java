package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentStrategy;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentSummary;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.appconfig.model.Monitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppConfigServiceTest {
    private static final String ALARM_ARN = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-errors";
    private static final String SECOND_ALARM_ARN = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-latency";
    private static final String ALARM_ROLE_ARN = "arn:aws:iam::000000000000:role/appconfig-alarm-monitor";

    private AccountAwareStorageBackend<Application> applicationStore;
    private AccountAwareStorageBackend<Environment> environmentStore;
    private AccountAwareStorageBackend<Deployment> deploymentStore;
    private AccountAwareStorageBackend<String> activeConfigStore;
    private AppConfigService service;

    @BeforeEach
    void setUp() {
        applicationStore = mock(AccountAwareStorageBackend.class);
        environmentStore = mock(AccountAwareStorageBackend.class);
        AccountAwareStorageBackend<ConfigurationProfile> profileStore = mock(AccountAwareStorageBackend.class);
        deploymentStore = mock(AccountAwareStorageBackend.class);
        activeConfigStore = mock(AccountAwareStorageBackend.class);
        Map<String, Deployment> deployments = new HashMap<>();
        doAnswer(invocation -> {
            deployments.put(invocation.getArgument(0, String.class), invocation.getArgument(1, Deployment.class));
            return null;
        }).when(deploymentStore).put(anyString(), any(Deployment.class));
        when(deploymentStore.scan(any())).thenAnswer(invocation -> List.copyOf(deployments.values()));
        when(deploymentStore.keys()).thenAnswer(invocation -> Set.copyOf(deployments.keySet()));
        doAnswer(invocation -> deployments.remove(invocation.getArgument(0, String.class)))
                .when(deploymentStore).delete(anyString());
        AccountAwareStorageBackend<DeploymentStrategy> strategyStore = mock(AccountAwareStorageBackend.class);
        Map<String, DeploymentStrategy> strategies = new HashMap<>();
        doAnswer(invocation -> {
            strategies.put(invocation.getArgument(0, String.class), invocation.getArgument(1, DeploymentStrategy.class));
            return null;
        }).when(strategyStore).put(anyString(), any(DeploymentStrategy.class));
        when(strategyStore.get(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(strategies.get(invocation.getArgument(0, String.class))));
        doAnswer(invocation -> strategies.remove(invocation.getArgument(0, String.class)))
                .when(strategyStore).delete(anyString());
        when(activeConfigStore.keys()).thenReturn(Set.of());
        StorageFactory storageFactory = mock(StorageFactory.class);
        doAnswer(invocation -> switch (invocation.getArgument(1, String.class)) {
            case "appconfig-applications.json" -> applicationStore;
            case "appconfig-environments.json" -> environmentStore;
            case "appconfig-profiles.json" -> profileStore;
            case "appconfig-strategies.json" -> strategyStore;
            case "appconfig-deployments.json" -> deploymentStore;
            case "appconfig-active-configs.json" -> activeConfigStore;
            default -> mock(AccountAwareStorageBackend.class);
        }).when(storageFactory).create(anyString(), anyString(), any(TypeReference.class));

        Application application = new Application();
        application.setId("app");
        applicationStore.put("app", application);
        when(applicationStore.get("app")).thenReturn(Optional.of(application));
        Environment environment = new Environment();
        environment.setId("env");
        environment.setApplicationId("app");
        environmentStore.put("env", environment);
        when(environmentStore.get("env")).thenReturn(Optional.of(environment));
        ConfigurationProfile profile = new ConfigurationProfile();
        profile.setId("profile");
        profile.setApplicationId("app");
        when(profileStore.get("profile")).thenReturn(Optional.of(profile));
        service = new AppConfigService(storageFactory, mock(EmulatorConfig.class));
    }

    @Test
    void listDeploymentsReturnsDescendingPages() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("app::env::2", deployment("app", "env", 2));

        AppConfigService.DeploymentPage page = service.listDeployments("app", "env", 1, null);

        assertEquals(List.of(2), page.items().stream().map(DeploymentSummary::getDeploymentNumber).toList());
        assertNotNull(page.nextToken());
    }

    @Test
    void listDeploymentsCursorSurvivesNewDeployment() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("app::env::2", deployment("app", "env", 2));
        deploymentStore.put("app::env::3", deployment("app", "env", 3));

        AppConfigService.DeploymentPage firstPage = service.listDeployments("app", "env", 1, null);
        deploymentStore.put("app::env::4", deployment("app", "env", 4));

        AppConfigService.DeploymentPage secondPage = service.listDeployments(
                "app", "env", 1, firstPage.nextToken());

        assertEquals(List.of(2), secondPage.items().stream()
                .map(DeploymentSummary::getDeploymentNumber).toList());
    }

    @Test
    void listDeploymentsFiltersApplicationAndEnvironment() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("other::env::2", deployment("other", "env", 2));
        deploymentStore.put("app::other-env::3", deployment("app", "other-env", 3));

        AppConfigService.DeploymentPage page = service.listDeployments("app", "env", null, null);

        assertEquals(List.of(1), page.items().stream().map(DeploymentSummary::getDeploymentNumber).toList());
    }

    @Test
    void listDeploymentsRejectsInvalidPageSize() {
        assertThrows(RuntimeException.class, () -> service.listDeployments("app", "env", 0, null));
        assertThrows(RuntimeException.class, () -> service.listDeployments("app", "env", 51, null));
    }

    @Test
    void listDeploymentsRejectsUnknownToken() {
        assertThrows(RuntimeException.class, () -> service.listDeployments("app", "env", 1, "unknown"));
    }

    @Test
    void listDeploymentsStillReportsADeploymentWhoseStrategyWasDeleted() {
        String strategyId = createGradualStrategy().getId();
        startDeployment(strategyId);

        service.deleteDeploymentStrategy(strategyId);
        DeploymentSummary summary = onlyDeployment();

        assertEquals(15, summary.getDeploymentDurationInMinutes());
        assertEquals(5, summary.getFinalBakeTimeInMinutes());
        assertEquals(25f, summary.getGrowthFactor());
        assertEquals("LINEAR", summary.getGrowthType());
    }

    @Test
    void listDeploymentsReportsTheStrategySettingsADeploymentStartedWith() {
        DeploymentStrategy strategy = createGradualStrategy();
        startDeployment(strategy.getId());

        // What an in-place update of the strategy changes after the deployment has started.
        strategy.setDeploymentDurationInMinutes(30);
        strategy.setFinalBakeTimeInMinutes(10);
        strategy.setGrowthFactor(50f);
        strategy.setGrowthType("EXPONENTIAL");
        DeploymentSummary summary = onlyDeployment();

        assertEquals(15, summary.getDeploymentDurationInMinutes());
        assertEquals(5, summary.getFinalBakeTimeInMinutes());
        assertEquals(25f, summary.getGrowthFactor());
        assertEquals("LINEAR", summary.getGrowthType());
    }

    @Test
    void listDeploymentsReportsTheProfileNameADeploymentStartedWith() {
        ConfigurationProfile profile = service.getConfigurationProfile("app", "profile");
        profile.setName("service-settings");
        startDeployment("AppConfig.AllAtOnce");

        profile.setName("renamed-settings");

        assertEquals("service-settings", onlyDeployment().getConfigurationName());
    }

    @Test
    void listDeploymentsReadsTheLiveProfileAndStrategyForARecordThatDoesNotHoldThem() {
        // Stored by an earlier build, which recorded neither the profile's name nor the strategy's settings.
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        service.getConfigurationProfile("app", "profile").setName("service-settings");

        DeploymentSummary summary = onlyDeployment();

        assertEquals("service-settings", summary.getConfigurationName());
        assertEquals(0, summary.getDeploymentDurationInMinutes());
        assertEquals(10, summary.getFinalBakeTimeInMinutes());
        assertEquals(100f, summary.getGrowthFactor());
        assertEquals("LINEAR", summary.getGrowthType());
    }

    @Test
    void listDeploymentsLeavesOutTheStrategySettingsWhenNeitherTheRecordNorTheStrategyHoldsThem() {
        Deployment stored = deployment("app", "env", 1);
        stored.setDeploymentStrategyId("deleted");
        deploymentStore.put("app::env::1", stored);

        DeploymentSummary summary = onlyDeployment();

        assertEquals(1, summary.getDeploymentNumber());
        assertNull(summary.getDeploymentDurationInMinutes());
        assertNull(summary.getFinalBakeTimeInMinutes());
        assertNull(summary.getGrowthFactor());
        assertNull(summary.getGrowthType());
    }

    @Test
    void environmentKeepsAnImmutableCopyOfItsMonitors() {
        Map<String, Object> withRole = new HashMap<>(Map.of("AlarmArn", ALARM_ARN, "AlarmRoleArn", ALARM_ROLE_ARN));
        List<Object> monitors = new ArrayList<>(List.of(withRole, Map.of("AlarmArn", SECOND_ALARM_ARN)));

        Environment environment = service.createEnvironment("app", Map.of("Name", "monitored", "Monitors", monitors));
        monitors.clear();
        withRole.put("AlarmArn", "changed");

        assertEquals(List.of(new Monitor(ALARM_ARN, ALARM_ROLE_ARN), new Monitor(SECOND_ALARM_ARN, null)),
                environment.getMonitors());
        assertThrows(UnsupportedOperationException.class, () -> environment.getMonitors().clear());
    }

    @Test
    void environmentCreatedWithoutMonitorsHasNone() {
        assertNull(service.createEnvironment("app", Map.of("Name", "unmonitored")).getMonitors());
    }

    @Test
    void monitorsAtTheDocumentedLimitsAreAccepted() {
        String rolePrefix = "arn:aws:iam::000000000000:role/";
        List<Map<String, Object>> monitors = List.of(
                Map.of("AlarmArn", "a".repeat(2048)),
                Map.of("AlarmArn", "a", "AlarmRoleArn", rolePrefix + "r".repeat(2048 - rolePrefix.length())),
                Map.of("AlarmArn", ALARM_ARN, "AlarmRoleArn", "arn:aws-cn:iam::000000000000:role/monitor"),
                Map.of("AlarmArn", ALARM_ARN, "AlarmRoleArn", "arn:aws-us-gov:iam::000000000000:role/path/monitor"),
                Map.of("AlarmArn", ALARM_ARN, "AlarmRoleArn", "arn:aws-eusc:iam::000000000000:role/monitor"));

        assertEquals(5, service.createEnvironment("app", Map.of("Name", "limits", "Monitors", monitors))
                .getMonitors().size());
        assertEquals(List.of(), service.createEnvironment("app", Map.of("Name", "empty", "Monitors", List.of()))
                .getMonitors());
    }

    @Test
    void invalidMonitorsAreRefusedBeforeTheEnvironmentIsStored() {
        clearInvocations(environmentStore);
        Map<String, Object> monitor = Map.of("AlarmArn", ALARM_ARN);
        List<Object> invalid = new ArrayList<>(List.of(
                "not-a-list",
                Collections.nCopies(6, monitor),
                List.of("not-a-monitor"),
                List.of(Map.of()),
                List.of(Map.of("AlarmArn", "")),
                List.of(Map.of("AlarmArn", 1)),
                List.of(Map.of("AlarmArn", "a".repeat(2049))),
                List.of(Map.of("AlarmArn", ALARM_ARN, "AlarmRoleArn", 1)),
                List.of(Map.of("AlarmArn", ALARM_ARN, "AlarmRoleArn", "appconfig-alarm-monitor")),
                List.of(Map.of("AlarmArn", ALARM_ARN, "AlarmRoleArn", "arn:aws:iam::000000000000:user/monitor")),
                List.of(Map.of("AlarmArn", ALARM_ARN, "AlarmRoleArn", ALARM_ROLE_ARN + "x".repeat(2048)))));
        invalid.add(Collections.singletonList(null));

        for (Object monitors : invalid) {
            AwsException refused = assertThrows(AwsException.class,
                    () -> service.createEnvironment("app", Map.of("Name", "refused", "Monitors", monitors)));
            assertEquals("BadRequestException", refused.getErrorCode());
            assertEquals(400, refused.getHttpStatus());
        }
        verify(environmentStore, never()).put(anyString(), any());
    }

    @Test
    void invalidMonitorsAreRefusedBeforeTheApplicationIsLookedUp() {
        AwsException refused = assertThrows(AwsException.class, () -> service.createEnvironment("missing",
                Map.of("Name", "refused", "Monitors", Collections.nCopies(6, Map.of("AlarmArn", ALARM_ARN)))));

        assertEquals("BadRequestException", refused.getErrorCode());
    }

    @Test
    void startDeploymentRecordsTheDeployedProfileNameAndLocation() {
        ConfigurationProfile profile = service.getConfigurationProfile("app", "profile");
        profile.setName("service-settings");
        profile.setLocationUri("ssm-parameter://service-settings");

        Deployment deployment = service.startDeployment("app", "env", Map.of(
                "ConfigurationProfileId", "profile",
                "ConfigurationVersion", "1",
                "DeploymentStrategyId", "AppConfig.AllAtOnce"));

        assertEquals("service-settings", deployment.getConfigurationName());
        assertEquals("ssm-parameter://service-settings", deployment.getConfigurationLocationUri());
    }

    @Test
    void deleteEnvironmentRemovesRuntimeState() {
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        when(activeConfigStore.keys()).thenReturn(Set.of("env::profile"));

        service.deleteEnvironment("app", "env");

        verify(deploymentStore).delete("app::env::1");
        verify(activeConfigStore).delete("env::profile");
        verify(environmentStore).delete("env");
    }

    @Test
    void startDeploymentUsesHighestNumberAfterAnotherEnvironmentIsDeleted() {
        Environment otherEnvironment = new Environment();
        otherEnvironment.setId("other-env");
        otherEnvironment.setApplicationId("app");
        when(environmentStore.get("other-env")).thenReturn(Optional.of(otherEnvironment));
        deploymentStore.put("app::env::1", deployment("app", "env", 1));
        deploymentStore.put("app::other-env::2", deployment("app", "other-env", 2));
        deploymentStore.put("app::env::3", deployment("app", "env", 3));

        service.deleteEnvironment("app", "other-env");
        Deployment deployment = service.startDeployment("app", "env", Map.of(
                "ConfigurationProfileId", "profile",
                "ConfigurationVersion", "version",
                "DeploymentStrategyId", "AppConfig.AllAtOnce"));

        assertEquals(4, deployment.getDeploymentNumber());
        assertEquals(Set.of("app::env::1", "app::env::3", "app::env::4"), deploymentStore.keys());
    }

    @Test
    void concurrentEnvironmentDeletesDoNotBothSucceed() throws Exception {
        AtomicReference<Environment> stored = new AtomicReference<>(environmentStore.get("env").orElseThrow());
        CountDownLatch reads = new CountDownLatch(2);
        when(environmentStore.get("env")).thenAnswer(invocation -> {
            Environment environment = stored.get();
            reads.countDown();
            reads.await(200, TimeUnit.MILLISECONDS);
            return Optional.ofNullable(environment);
        });
        doAnswer(invocation -> {
            stored.set(null);
            return null;
        }).when(environmentStore).delete("env");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(this::deleteEnvironment);
            Future<Boolean> second = executor.submit(this::deleteEnvironment);
            long successes = List.of(first.get(), second.get()).stream()
                    .filter(Boolean::booleanValue)
                    .count();
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean deleteEnvironment() {
        try {
            service.deleteEnvironment("app", "env");
            return true;
        } catch (AwsException e) {
            return false;
        }
    }

    private DeploymentStrategy createGradualStrategy() {
        return service.createDeploymentStrategy(Map.of(
                "Name", "gradual",
                "DeploymentDurationInMinutes", 15,
                "FinalBakeTimeInMinutes", 5,
                "GrowthFactor", 25,
                "GrowthType", "LINEAR"));
    }

    private void startDeployment(String strategyId) {
        service.startDeployment("app", "env", Map.of(
                "ConfigurationProfileId", "profile",
                "ConfigurationVersion", "1",
                "DeploymentStrategyId", strategyId));
    }

    private DeploymentSummary onlyDeployment() {
        List<DeploymentSummary> items = service.listDeployments("app", "env", null, null).items();
        assertEquals(1, items.size());
        return items.get(0);
    }

    private static Deployment deployment(String applicationId, String environmentId, int number) {
        Deployment deployment = new Deployment();
        deployment.setApplicationId(applicationId);
        deployment.setEnvironmentId(environmentId);
        deployment.setDeploymentNumber(number);
        deployment.setConfigurationProfileId("profile");
        deployment.setConfigurationVersion("version");
        deployment.setDeploymentStrategyId("AppConfig.AllAtOnce");
        deployment.setState("COMPLETE");
        return deployment;
    }
}
