package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class AppConfigRegionIntegrationTest {
    @Inject
    AppConfigService service;

    @Inject
    AppConfigDataService data;

    @Inject
    RegionResolver regions;

    @Test
    void identicalNamesResolveOnlyWithinTheirAccountAndRegion() {
        String name = "regional-" + UUID.randomUUID();
        Map<Scope, String> applications = new LinkedHashMap<>();
        try {
            for (Scope scope : List.of(
                    new Scope("111111111111", regions.getDefaultRegion()),
                    new Scope("111111111111", "us-west-2"),
                    new Scope("222222222222", regions.getDefaultRegion()),
                    new Scope("111111111111", "cn-north-1"))) {
                applications.put(scope, RequestScopes.callAs(scope.account(), scope.region(),
                        () -> service.createApplication(Map.of("Name", name)).getId()));
            }
            applications.forEach((scope, id) -> RequestScopes.runAs(scope.account(), scope.region(), () -> {
                assertEquals(id, service.resolveApplication(name).getId());
                assertEquals(List.of(id), service.listApplications().stream()
                        .filter(application -> name.equals(application.getName()))
                        .map(application -> application.getId()).toList());
                applications.values().stream().filter(other -> !other.equals(id)).forEach(other ->
                        assertEquals("ResourceNotFoundException",
                                assertThrows(AwsException.class, () -> service.getApplication(other)).getErrorCode()));
            }));
        } finally {
            applications.forEach((scope, id) -> RequestScopes.runAs(scope.account(), scope.region(),
                    () -> service.deleteApplication(id)));
        }
    }

    @Test
    void foreignRegionCannotConsumeAConfigurationSession() {
        String account = "333333333333";
        String region = regions.getDefaultRegion();
        RequestScopes.runAs(account, region, () -> {
            String app = service.createApplication(Map.of("Name", "session-" + UUID.randomUUID())).getId();
            String env = service.createEnvironment(app, Map.of("Name", "environment")).getId();
            String profile = service.createConfigurationProfile(app,
                    Map.of("Name", "profile", "LocationUri", "hosted")).getId();
            try {
                String token = data.startConfigurationSession(Map.of(
                        "ApplicationIdentifier", app, "EnvironmentIdentifier", env,
                        "ConfigurationProfileIdentifier", profile));
                RequestScopes.runAs(account, "us-west-2", () -> assertEquals("BadRequestException",
                        assertThrows(AwsException.class, () -> data.getLatestConfiguration(token)).getErrorCode()));
                assertEquals(0, data.getLatestConfiguration(token).content().length);
            } finally {
                service.deleteEnvironment(app, env);
                service.deleteConfigurationProfile(app, profile);
                service.deleteApplication(app);
            }
        });
    }

    private record Scope(String account, String region) {}
}
