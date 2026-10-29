package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentStrategy;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.appconfig.model.Monitor;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The update methods CloudFormation uses write a new copy of the entity rather than change the
 * stored one field by field. The in-memory stores hand out the stored instance itself, so a caller
 * that read an entity before the update, such as a ListDeployments building a summary from a
 * strategy, must keep seeing all of its prior values rather than a mix of old and new.
 */
class AppConfigServiceUpdateIsolationTest {

    private static final String ALARM = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-errors";

    private final AppConfigService service = new AppConfigService(new InMemoryStorageFactory("000000000000"), null);

    @Test
    void aStrategyReadBeforeAnUpdateKeepsItsPriorSettings() {
        DeploymentStrategy created = service.createDeploymentStrategy(Map.of("Name", "gradual",
                "DeploymentDurationInMinutes", 10, "FinalBakeTimeInMinutes", 5, "GrowthFactor", 25,
                "GrowthType", "EXPONENTIAL", "ReplicateTo", "NONE"));
        DeploymentStrategy before = service.getDeploymentStrategy(created.getId());

        service.updateDeploymentStrategy(created.getId(), "faster", 1, 0, 100f, "LINEAR");

        assertEquals(10, before.getDeploymentDurationInMinutes());
        assertEquals(5, before.getFinalBakeTimeInMinutes());
        assertEquals(25f, before.getGrowthFactor());
        assertEquals("EXPONENTIAL", before.getGrowthType());
        assertNull(before.getDescription());
        DeploymentStrategy after = service.getDeploymentStrategy(created.getId());
        assertEquals(1, after.getDeploymentDurationInMinutes());
        assertEquals(100f, after.getGrowthFactor());
        assertEquals("faster", after.getDescription());
        assertEquals("gradual", after.getName());
        assertEquals("NONE", after.getReplicateTo());
    }

    @Test
    void anApplicationReadBeforeAnUpdateKeepsItsPriorNameAndTags() {
        Application created = service.createApplication(Map.of("Name", "orders", "Description", "First"));
        service.tagApplication(created.getId(), Map.of("team", "payments"));
        Application before = service.getApplication(created.getId());

        service.updateApplication(created.getId(), "orders-v2", null);

        assertEquals("orders", before.getName());
        assertEquals("First", before.getDescription());
        Application after = service.getApplication(created.getId());
        assertEquals("orders-v2", after.getName());
        assertNull(after.getDescription());
        assertEquals(Map.of("team", "payments"), after.getTags(), "an update keeps the application's tags");
    }

    @Test
    void anEnvironmentReadBeforeAnUpdateKeepsItsPriorMonitors() {
        String appId = service.createApplication(Map.of("Name", "orders")).getId();
        Environment created = service.createEnvironment(appId, Map.of("Name", "beta",
                "Monitors", List.of(Map.of("AlarmArn", ALARM))));
        Environment before = service.getEnvironment(appId, created.getId());

        service.updateEnvironment(appId, created.getId(), "gamma", "Second", null);

        assertEquals("beta", before.getName());
        assertEquals(List.of(new Monitor(ALARM, null)), before.getMonitors());
        Environment after = service.getEnvironment(appId, created.getId());
        assertEquals("gamma", after.getName());
        assertNull(after.getMonitors());
        assertEquals("READY", after.getState(), "an update keeps the environment's state");
    }

    @Test
    void aProfileReadBeforeAnUpdateKeepsItsPriorName() {
        String appId = service.createApplication(Map.of("Name", "orders")).getId();
        ConfigurationProfile created = service.createConfigurationProfile(appId,
                Map.of("Name", "settings", "LocationUri", "hosted", "Type", "AWS.Freeform"));
        ConfigurationProfile before = service.getConfigurationProfile(appId, created.getId());

        service.updateConfigurationProfile(appId, created.getId(), "renamed", "Now described");

        assertEquals("settings", before.getName());
        assertNull(before.getDescription());
        ConfigurationProfile after = service.getConfigurationProfile(appId, created.getId());
        assertEquals("renamed", after.getName());
        assertEquals("hosted", after.getLocationUri());
        assertEquals("AWS.Freeform", after.getType());
    }
}
