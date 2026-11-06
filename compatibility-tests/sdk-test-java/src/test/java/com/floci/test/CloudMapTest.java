package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.servicediscovery.ServiceDiscoveryClient;
import software.amazon.awssdk.services.servicediscovery.model.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Cloud Map (servicediscovery) validated through the real AWS SDK v2 client.
 * Exercises the Phase 1 flow: create namespace → poll operation → create service →
 * register instance → discover → deregister → delete.
 */
@DisplayName("Cloud Map")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudMapTest {

    private static ServiceDiscoveryClient sd;
    private static String namespaceName;
    private static String serviceName;
    private static String namespaceId;
    private static String serviceId;
    private static final String INSTANCE_ID = "i-cm-sdk-1";

    @BeforeAll
    static void setup() {
        sd = TestFixtures.serviceDiscoveryClient();
        namespaceName = TestFixtures.uniqueName("cm-ns");
        serviceName = TestFixtures.uniqueName("cm-svc");
    }

    @AfterAll
    static void cleanup() {
        try {
            sd.deregisterInstance(r -> r.serviceId(serviceId).instanceId(INSTANCE_ID));
        } catch (Exception ignored) {}
        try {
            sd.deleteService(r -> r.id(serviceId));
        } catch (Exception ignored) {}
        try {
            sd.deleteNamespace(r -> r.id(namespaceId));
        } catch (Exception ignored) {}
        sd.close();
    }

    private static void awaitSuccess(String operationId) {
        for (int i = 0; i < 20; i++) {
            GetOperationResponse op = sd.getOperation(r -> r.operationId(operationId));
            if (op.operation().status() == OperationStatus.SUCCESS) {
                return;
            }
            if (op.operation().status() == OperationStatus.FAIL) {
                fail("Operation " + operationId + " failed: " + op.operation().errorMessage());
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        fail("Operation " + operationId + " did not reach SUCCESS");
    }

    @Test
    @Order(1)
    void createHttpNamespace() {
        CreateHttpNamespaceResponse resp = sd.createHttpNamespace(
                r -> r.name(namespaceName).description("floci sdk compat"));
        assertThat(resp.operationId()).isNotBlank();
        awaitSuccess(resp.operationId());
    }

    @Test
    @Order(2)
    void listNamespacesFindsIt() {
        ListNamespacesResponse resp = sd.listNamespaces(ListNamespacesRequest.builder().build());
        NamespaceSummary found = resp.namespaces().stream()
                .filter(n -> n.name().equals(namespaceName))
                .findFirst().orElseThrow();
        namespaceId = found.id();
        assertThat(namespaceId).startsWith("ns-");
        assertThat(found.type()).isEqualTo(NamespaceType.HTTP);
        assertThat(found.arn()).contains("servicediscovery");
    }

    @Test
    @Order(3)
    void getNamespace() {
        GetNamespaceResponse resp = sd.getNamespace(r -> r.id(namespaceId));
        assertThat(resp.namespace().name()).isEqualTo(namespaceName);
        assertThat(resp.namespace().properties().httpProperties().httpName()).isEqualTo(namespaceName);
    }

    @Test
    @Order(4)
    void createService() {
        CreateServiceResponse resp = sd.createService(
                r -> r.name(serviceName).namespaceId(namespaceId).type(ServiceTypeOption.HTTP));
        serviceId = resp.service().id();
        assertThat(serviceId).startsWith("srv-");
        assertThat(resp.service().name()).isEqualTo(serviceName);
        assertThat(resp.service().namespaceId()).isEqualTo(namespaceId);
    }

    @Test
    @Order(5)
    void registerInstance() {
        RegisterInstanceResponse resp = sd.registerInstance(r -> r
                .serviceId(serviceId)
                .instanceId(INSTANCE_ID)
                .attributes(Map.of("AWS_INSTANCE_IPV4", "10.0.0.7", "AWS_INSTANCE_PORT", "9090")));
        assertThat(resp.operationId()).isNotBlank();
        awaitSuccess(resp.operationId());
    }

    @Test
    @Order(6)
    void listInstances() {
        ListInstancesResponse resp = sd.listInstances(r -> r.serviceId(serviceId));
        assertThat(resp.instances()).hasSize(1);
        InstanceSummary summary = resp.instances().get(0);
        assertThat(summary.id()).isEqualTo(INSTANCE_ID);
        assertThat(summary.attributes()).containsEntry("AWS_INSTANCE_IPV4", "10.0.0.7");
    }

    @Test
    @Order(7)
    void getInstance() {
        GetInstanceResponse resp = sd.getInstance(r -> r.serviceId(serviceId).instanceId(INSTANCE_ID));
        assertThat(resp.instance().id()).isEqualTo(INSTANCE_ID);
        assertThat(resp.instance().attributes()).containsEntry("AWS_INSTANCE_PORT", "9090");
    }

    @Test
    @Order(8)
    void discoverInstances() {
        DiscoverInstancesResponse resp = sd.discoverInstances(r -> r
                .namespaceName(namespaceName)
                .serviceName(serviceName)
                .healthStatus(HealthStatusFilter.HEALTHY_OR_ELSE_ALL));
        assertThat(resp.instances()).hasSize(1);
        HttpInstanceSummary inst = resp.instances().get(0);
        assertThat(inst.instanceId()).isEqualTo(INSTANCE_ID);
        assertThat(inst.healthStatus()).isEqualTo(HealthStatus.HEALTHY);
        assertThat(resp.instancesRevision()).isNotNull();

        long revision = sd.discoverInstancesRevision(r -> r
                .namespaceName(namespaceName).serviceName(serviceName)).instancesRevision();
        assertThat(revision).isEqualTo(resp.instancesRevision());
    }

    @Test
    @Order(9)
    void getInstancesHealthStatus() {
        GetInstancesHealthStatusResponse resp = sd.getInstancesHealthStatus(r -> r.serviceId(serviceId));
        assertThat(resp.status()).containsEntry(INSTANCE_ID, HealthStatus.HEALTHY);
    }

    @Test
    @Order(10)
    void listOperationsFilteredByNamespace() {
        ListOperationsResponse resp = sd.listOperations(r -> r.filters(
                OperationFilter.builder()
                        .name(OperationFilterName.NAMESPACE_ID)
                        .condition(FilterCondition.EQ)
                        .values(namespaceId)
                        .build()));
        assertThat(resp.operations()).isNotEmpty();
    }

    @Test
    @Order(11)
    void deleteServiceWhileInstancesRegisteredFails() {
        assertThatThrownBy(() -> sd.deleteService(r -> r.id(serviceId)))
                .isInstanceOf(ResourceInUseException.class);
    }

    @Test
    @Order(12)
    void teardown() {
        DeregisterInstanceResponse dereg = sd.deregisterInstance(
                r -> r.serviceId(serviceId).instanceId(INSTANCE_ID));
        assertThat(dereg.operationId()).isNotBlank();
        awaitSuccess(dereg.operationId());

        sd.deleteService(r -> r.id(serviceId));
        DeleteNamespaceResponse del = sd.deleteNamespace(r -> r.id(namespaceId));
        assertThat(del.operationId()).isNotBlank();
    }

    @Test
    @Order(13)
    void getMissingNamespaceThrows() {
        assertThatThrownBy(() -> sd.getNamespace(r -> r.id("ns-doesnotexist00000")))
                .isInstanceOf(NamespaceNotFoundException.class);
    }
}
