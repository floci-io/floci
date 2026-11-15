package io.floci.aws.services.ecs;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.CreateServiceRequest;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.Service;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class EcsServicePlatformVersionIntegrationTest {

    private static final String SERVICE = "platform-service";

    @Inject
    EcsService ecsService;

    private EcsClient client;
    private String cluster;
    private String taskDefinition;
    private boolean serviceCreated;

    @BeforeEach
    void setUp() {
        client = AwsTestClients.create(EcsClient.builder(), "test", Region.US_EAST_1);
        String family = "service-platform-" + UUID.randomUUID();
        cluster = client.createCluster(request -> request.clusterName(family)).cluster().clusterArn();
        taskDefinition = client.registerTaskDefinition(request -> request.family(family)
                .requiresCompatibilities(Compatibility.FARGATE).networkMode("awsvpc").cpu("256").memory("512")
                .containerDefinitions(ContainerDefinition.builder().name("app").image("example:test").build()))
                .taskDefinition().taskDefinitionArn();
    }

    @AfterEach
    void tearDown() {
        try (EcsClient ignored = client) {
            if (serviceCreated) {
                client.deleteService(request -> request.cluster(cluster).service(SERVICE).force(true));
            }
            if (taskDefinition != null) {
                client.deregisterTaskDefinition(request -> request.taskDefinition(taskDefinition));
                client.deleteTaskDefinitions(request -> request.taskDefinitions(taskDefinition));
            }
            if (cluster != null) {
                client.deleteCluster(request -> request.cluster(cluster));
            }
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"LATEST", "1.4.0", "1.3.0"})
    void createAndDescribePreserveTheRequestedServiceVersion(String requested) {
        String expected = requested == null ? "LATEST" : requested;
        String resolved = "LATEST".equals(expected) ? "1.4.0" : expected;
        Service created = createService(requested, null, 0);

        assertEquals(expected, created.platformVersion());
        assertEquals(resolved, created.deployments().getFirst().platformVersion());
        assertEquals(expected, describeService().platformVersion());

        Service updated = client.updateService(request -> request.cluster(cluster).service(SERVICE)
                .taskDefinition(taskDefinition).desiredCount(0))
                .service();
        assertEquals(expected, updated.platformVersion(), "an unrelated update must preserve the service setting");
        assertEquals(created.deployments().getFirst().id(), updated.deployments().getFirst().id());
        assertEquals(expected, describeService().platformVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FARGATE", "FARGATE_SPOT"})
    void capacityProviderServicesAlsoDefaultToLatest(String provider) {
        Service created = createService(null, provider, 0);
        assertEquals("LATEST", created.platformVersion());
        assertEquals("LATEST", describeService().platformVersion());
        assertEquals("1.4.0", created.deployments().getFirst().platformVersion());
    }

    @Test
    void updatingBetweenLatestAndItsResolvedVersionPreservesTheSettingWithoutRolling() {
        Service created = createService("1.4.0", null, 0);
        String deployment = created.deployments().getFirst().id();

        Service latest = client.updateService(request -> request.cluster(cluster).service(SERVICE)
                .platformVersion("LATEST")).service();
        assertEquals("LATEST", latest.platformVersion());
        assertEquals("LATEST", describeService().platformVersion());
        assertEquals(deployment, latest.deployments().getFirst().id());

        Service pinned = client.updateService(request -> request.cluster(cluster).service(SERVICE)
                .platformVersion("1.4.0")).service();
        assertEquals("1.4.0", pinned.platformVersion());
        assertEquals(deployment, pinned.deployments().getFirst().id());
    }

    @Test
    void updatingToLatestFromAnOlderVersionRollsTheDeployment() {
        Service created = createService("1.3.0", null, 0);
        String deployment = created.deployments().getFirst().id();

        Service updated = client.updateService(request -> request.cluster(cluster).service(SERVICE)
                .platformVersion("LATEST")).service();
        assertEquals("LATEST", updated.platformVersion());
        assertEquals("LATEST", describeService().platformVersion());
        assertNotEquals(deployment, updated.deployments().getFirst().id());
        assertEquals("1.4.0", updated.deployments().getFirst().platformVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LATEST", "1.3.0"})
    void serviceTasksReportTheResolvedPlatformVersion(String requested) {
        createService(requested, null, 1);
        ecsService.reconcileServices();
        ecsService.reconcileServices();

        List<String> tasks = client.listTasks(request -> request.cluster(cluster).serviceName(SERVICE)).taskArns();
        assertEquals(1, tasks.size());
        assertEquals("LATEST".equals(requested) ? "1.4.0" : requested,
                client.describeTasks(request -> request.cluster(cluster).tasks(tasks))
                        .tasks().getFirst().platformVersion());
        assertEquals(requested, describeService().platformVersion());
    }

    private Service createService(String platformVersion, String provider, int desiredCount) {
        CreateServiceRequest.Builder request = CreateServiceRequest.builder().cluster(cluster).serviceName(SERVICE)
                .taskDefinition(taskDefinition).desiredCount(desiredCount).platformVersion(platformVersion)
                .networkConfiguration(NetworkConfiguration.builder().awsvpcConfiguration(network -> network
                        .subnets("subnet-default-us-east-1-a")).build());
        if (provider == null) {
            request.launchType("FARGATE");
        } else {
            request.capacityProviderStrategy(strategy -> strategy.capacityProvider(provider).weight(1));
        }
        Service created = client.createService(request.build()).service();
        serviceCreated = true;
        return created;
    }

    private Service describeService() {
        return client.describeServices(request -> request.cluster(cluster).services(SERVICE)).services().getFirst();
    }
}
