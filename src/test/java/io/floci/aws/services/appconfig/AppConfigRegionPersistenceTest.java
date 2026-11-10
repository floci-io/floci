package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigRegionPersistenceTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void deploymentsAndUnconsumedSessionsKeepTheirRegionAfterRestart(String mode) {
        AtomicReference<String> current = new AtomicReference<>("us-east-1");
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getDefaultRegion()).thenReturn("us-east-1");
        when(regions.getRegion()).thenAnswer(invocation -> current.get());
        when(regions.getAccountId()).thenReturn("000000000000");
        Map<String, Selection> selections = new LinkedHashMap<>();
        StorageFactory first = StorageFactories.open(directory, mode, "000000000000");
        try {
            AppConfigService service = new AppConfigService(first, regions);
            AppConfigDataService data = new AppConfigDataService(first, service, new ObjectMapper(), regions);
            for (String region : List.of("us-east-1", "us-west-2", "cn-north-1")) {
                current.set(region);
                String app = service.createApplication(Map.of("Name", "same-application")).getId();
                String env = service.createEnvironment(app, Map.of("Name", "same-environment")).getId();
                String profile = service.createConfigurationProfile(app,
                        Map.of("Name", "same-profile", "LocationUri", "hosted")).getId();
                int version = service.createHostedConfigurationVersion(
                        app, profile, content(region), "text/plain", null).getVersionNumber();
                String strategy = service.createDeploymentStrategy(Map.of(
                        "Name", "same-strategy", "FinalBakeTimeInMinutes", 0)).getId();
                int deployment = service.startDeployment(app, env, Map.of(
                        "ConfigurationProfileId", profile, "ConfigurationVersion", String.valueOf(version),
                        "DeploymentStrategyId", strategy)).getDeploymentNumber();
                String token = data.startConfigurationSession(Map.of(
                        "ApplicationIdentifier", app, "EnvironmentIdentifier", env,
                        "ConfigurationProfileIdentifier", profile));
                selections.put(region, new Selection(app, env, profile, strategy, deployment, token));
            }
        } finally {
            first.shutdownAll();
        }

        StorageFactory restarted = StorageFactories.open(directory, mode, "000000000000");
        try {
            AppConfigService service = new AppConfigService(restarted, regions);
            AppConfigDataService data = new AppConfigDataService(restarted, service, new ObjectMapper(), regions);
            selections.forEach((region, selection) -> {
                current.set(region);
                assertEquals(selection.app(), service.resolveApplication("same-application").getId());
                assertEquals(selection.env(), service.resolveEnvironment(selection.app(), "same-environment").getId());
                assertEquals(selection.profile(),
                        service.resolveConfigurationProfile(selection.app(), "same-profile").getId());
                assertEquals("same-strategy", service.getDeploymentStrategy(selection.strategy()).getName());
                assertEquals("COMPLETE", service.getDeployment(
                        selection.app(), selection.env(), selection.deployment()).getState());
                assertArrayEquals(content(region), data.getConfiguration(
                        "same-application", "same-environment", "same-profile", "client", null, null).content());
                selections.forEach((otherRegion, other) -> {
                    if (!region.equals(otherRegion)) {
                        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                                () -> service.getDeploymentStrategy(other.strategy())).getErrorCode());
                        assertEquals("BadRequestException", assertThrows(AwsException.class,
                                () -> data.getLatestConfiguration(other.token())).getErrorCode());
                    }
                });
                assertArrayEquals(content(region), data.getLatestConfiguration(selection.token()).content());
                service.deleteEnvironment(selection.app(), selection.env());
                service.deleteConfigurationProfile(selection.app(), selection.profile());
                service.deleteDeploymentStrategy(selection.strategy());
                service.deleteApplication(selection.app());
                assertEquals(List.of(), service.listApplications());
            });
        } finally {
            restarted.shutdownAll();
        }
    }

    private static byte[] content(String region) {
        return region.getBytes(StandardCharsets.UTF_8);
    }

    private record Selection(String app, String env, String profile, String strategy, int deployment, String token) {}
}
