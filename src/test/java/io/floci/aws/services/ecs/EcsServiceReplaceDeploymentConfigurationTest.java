package io.floci.aws.services.ecs;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.model.CapacityProviderStrategyItem;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.CreateServiceRequest;
import io.floci.aws.services.ecs.model.EcsServiceModel;
import io.floci.aws.services.ecs.model.UpdateServiceRequest;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code replaceServiceDeploymentConfiguration} writes the stored deployment configuration exactly
 * as given, null included, which {@code updateService} cannot do: there a null means unchanged.
 */
class EcsServiceReplaceDeploymentConfigurationTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER = "replace-cluster";
    private static final String SERVICE = "replace-svc";
    private static final Map<String, Object> PRIOR = Map.of("maximumPercent", 150, "minimumHealthyPercent", 50);
    private static final List<CapacityProviderStrategyItem> STRATEGY =
            List.of(new CapacityProviderStrategyItem("FARGATE", 2, 0));
    private static final List<Map<String, Object>> REGISTRIES = List.of(Map.of(
            "registryArn", "arn:aws:servicediscovery:us-east-1:000000000000:service/srv-1"));

    private EcsService ecs;

    @BeforeEach
    void createServiceWithAllThreeSettings() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        ecs = new EcsService(new RegionResolver(REGION, "000000000000"), mock(EcsContainerManager.class), config,
                mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory("000000000000"), null);
        ecs.initializeStorage();
        ecs.createCluster(CLUSTER, REGION);
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("app:1");
        ecs.registerTaskDefinition("replace-fam", List.of(container), null, null, null, null, null, List.of(), REGION);
        CreateServiceRequest create = new CreateServiceRequest();
        create.setCluster(CLUSTER);
        create.setServiceName(SERVICE);
        create.setTaskDefinition("replace-fam");
        create.setDeploymentConfiguration(PRIOR);
        create.setCapacityProviderStrategy(STRATEGY);
        create.setServiceRegistries(REGISTRIES);
        ecs.createService(create, REGION);
    }

    private EcsServiceModel stored() {
        return ecs.describeServices(CLUSTER, List.of(SERVICE), REGION).getFirst();
    }

    @Test
    void aGivenMapReplacesTheStoredDeploymentConfigurationExactly() {
        Map<String, Object> given = Map.of("maximumPercent", 175);

        EcsServiceModel returned = ecs.replaceServiceDeploymentConfiguration(CLUSTER, SERVICE, given, REGION);

        assertEquals(given, returned.getDeploymentConfiguration());
        assertEquals(given, stored().getDeploymentConfiguration(), "no member of the prior value is merged in");
    }

    @Test
    void nullClearsTheStoredDeploymentConfiguration() {
        EcsServiceModel returned = ecs.replaceServiceDeploymentConfiguration(CLUSTER, SERVICE, null, REGION);

        assertNull(returned.getDeploymentConfiguration());
        assertNull(stored().getDeploymentConfiguration());
    }

    @Test
    void anEmptyMapIsStoredAsGiven() {
        ecs.replaceServiceDeploymentConfiguration(CLUSTER, SERVICE, Map.of(), REGION);

        assertEquals(Map.of(), stored().getDeploymentConfiguration());
    }

    @Test
    void theReplacementStartsNoNewDeploymentAndTouchesNoOtherSetting() {
        String deploymentId = stored().getDeploymentId();

        ecs.replaceServiceDeploymentConfiguration(CLUSTER, SERVICE, null, REGION);

        EcsServiceModel after = stored();
        assertEquals(deploymentId, after.getDeploymentId());
        assertEquals(STRATEGY, after.getCapacityProviderStrategy());
        assertEquals(REGISTRIES, after.getServiceRegistries());
        assertEquals(1, after.getDesiredCount());
    }

    @Test
    void theClusterMayBeGivenAsAnArn() {
        String clusterArn = ecs.describeClusters(List.of(CLUSTER), REGION).getFirst().getClusterArn();

        ecs.replaceServiceDeploymentConfiguration(clusterArn, SERVICE, null, REGION);

        assertNull(stored().getDeploymentConfiguration());
    }

    @Test
    void anUnknownServiceFailsAsUpdateServiceDoes() {
        UpdateServiceRequest update = new UpdateServiceRequest();
        update.setCluster(CLUSTER);
        update.setService("missing-svc");
        AwsException expected = assertThrows(AwsException.class, () -> ecs.updateService(update, REGION));

        AwsException e = assertThrows(AwsException.class,
                () -> ecs.replaceServiceDeploymentConfiguration(CLUSTER, "missing-svc", null, REGION));

        assertEquals("ServiceNotFoundException", e.getErrorCode());
        assertEquals(expected.getErrorCode(), e.getErrorCode());
        assertEquals(expected.getHttpStatus(), e.getHttpStatus());
    }

    @Test
    void anUpdateWithoutADeploymentConfigurationKeepsTheStoredOne() {
        UpdateServiceRequest update = new UpdateServiceRequest();
        update.setCluster(CLUSTER);
        update.setService(SERVICE);
        update.setDesiredCount(2);

        ecs.updateService(update, REGION);

        assertEquals(PRIOR, stored().getDeploymentConfiguration());
    }

    @Test
    void aClearedDeploymentConfigurationStaysClearedAfterAnUnrelatedUpdate() {
        ecs.replaceServiceDeploymentConfiguration(CLUSTER, SERVICE, null, REGION);
        UpdateServiceRequest update = new UpdateServiceRequest();
        update.setCluster(CLUSTER);
        update.setService(SERVICE);
        update.setDesiredCount(2);

        ecs.updateService(update, REGION);

        assertNull(stored().getDeploymentConfiguration());
    }
}
