package io.github.hectorvent.floci.services.cloudmap;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * End-to-end Phase 1 flow for Cloud Map over the AWS JSON 1.1 wire protocol:
 * create namespace → poll operation → create service → register instance →
 * discover → deregister → delete. Mirrors the RestAssured convention used by the
 * other JSON 1.1 service tests (EventBridge).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CloudMapIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "Route53AutoNaming_v20170314.";

    private static String namespaceId;
    private static String serviceId;
    private static final String INSTANCE_ID = "i-cloudmap-1";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    @Test
    @Order(1)
    void createHttpNamespace() {
        String operationId = call("CreateHttpNamespace",
                "{\"Name\":\"floci-cm-ns\",\"Description\":\"test\"}")
                .then().statusCode(200)
                .body("OperationId", notNullValue())
                .extract().jsonPath().getString("OperationId");

        // Async op should be retrievable and SUCCESS (default 0s completion delay).
        call("GetOperation", "{\"OperationId\":\"" + operationId + "\"}")
                .then().statusCode(200)
                .body("Operation.Status", equalTo("SUCCESS"))
                .body("Operation.Type", equalTo("CREATE_NAMESPACE"));
    }

    @Test
    @Order(2)
    void listNamespacesFindsIt() {
        namespaceId = call("ListNamespaces", "{}")
                .then().statusCode(200)
                .body("Namespaces.find { it.Name == 'floci-cm-ns' }.Id", startsWith("ns-"))
                .extract().jsonPath().getString("Namespaces.find { it.Name == 'floci-cm-ns' }.Id");
    }

    @Test
    @Order(3)
    void createService() {
        serviceId = call("CreateService",
                "{\"Name\":\"floci-cm-svc\",\"NamespaceId\":\"" + namespaceId + "\"}")
                .then().statusCode(200)
                .body("Service.Id", startsWith("srv-"))
                .body("Service.Name", equalTo("floci-cm-svc"))
                .body("Service.NamespaceId", equalTo(namespaceId))
                .extract().jsonPath().getString("Service.Id");
    }

    @Test
    @Order(4)
    void registerInstance() {
        String operationId = call("RegisterInstance",
                "{\"ServiceId\":\"" + serviceId + "\",\"InstanceId\":\"" + INSTANCE_ID
                        + "\",\"Attributes\":{\"AWS_INSTANCE_IPV4\":\"10.0.0.1\",\"AWS_INSTANCE_PORT\":\"8080\"}}")
                .then().statusCode(200)
                .body("OperationId", notNullValue())
                .extract().jsonPath().getString("OperationId");

        call("GetOperation", "{\"OperationId\":\"" + operationId + "\"}")
                .then().statusCode(200)
                .body("Operation.Status", equalTo("SUCCESS"))
                .body("Operation.Type", equalTo("REGISTER_INSTANCE"));
    }

    @Test
    @Order(5)
    void listInstances() {
        call("ListInstances", "{\"ServiceId\":\"" + serviceId + "\"}")
                .then().statusCode(200)
                .body("Instances", hasSize(1))
                .body("Instances[0].Id", equalTo(INSTANCE_ID))
                .body("Instances[0].Attributes.AWS_INSTANCE_IPV4", equalTo("10.0.0.1"));
    }

    @Test
    @Order(6)
    void discoverInstancesHealthy() {
        call("DiscoverInstances",
                "{\"NamespaceName\":\"floci-cm-ns\",\"ServiceName\":\"floci-cm-svc\",\"HealthStatus\":\"HEALTHY\"}")
                .then().statusCode(200)
                .body("Instances", hasSize(1))
                .body("Instances[0].InstanceId", equalTo(INSTANCE_ID))
                .body("Instances[0].HealthStatus", equalTo("HEALTHY"))
                .body("InstancesRevision", notNullValue());
    }

    @Test
    @Order(7)
    void discoverInstancesRevisionMatches() {
        long discovered = call("DiscoverInstances",
                "{\"NamespaceName\":\"floci-cm-ns\",\"ServiceName\":\"floci-cm-svc\",\"HealthStatus\":\"HEALTHY_OR_ELSE_ALL\"}")
                .then().statusCode(200).extract().jsonPath().getLong("InstancesRevision");

        call("DiscoverInstancesRevision",
                "{\"NamespaceName\":\"floci-cm-ns\",\"ServiceName\":\"floci-cm-svc\"}")
                .then().statusCode(200)
                .body("InstancesRevision", equalTo((int) discovered));
    }

    @Test
    @Order(8)
    void listOperationsFilteredByNamespace() {
        call("ListOperations",
                "{\"Filters\":[{\"Name\":\"NAMESPACE_ID\",\"Condition\":\"EQ\",\"Values\":[\"" + namespaceId + "\"]}]}")
                .then().statusCode(200)
                .body("Operations.find { it.Status == 'SUCCESS' }", notNullValue());
    }

    @Test
    @Order(9)
    void deleteServiceWhileInstancesRegisteredFails() {
        call("DeleteService", "{\"Id\":\"" + serviceId + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("ResourceInUse"));
    }

    @Test
    @Order(10)
    void deleteNamespaceWithServiceFails() {
        call("DeleteNamespace", "{\"Id\":\"" + namespaceId + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("ResourceInUse"));
    }

    @Test
    @Order(11)
    void teardown() {
        call("DeregisterInstance",
                "{\"ServiceId\":\"" + serviceId + "\",\"InstanceId\":\"" + INSTANCE_ID + "\"}")
                .then().statusCode(200).body("OperationId", notNullValue());

        call("DeleteService", "{\"Id\":\"" + serviceId + "\"}").then().statusCode(200);
        call("DeleteNamespace", "{\"Id\":\"" + namespaceId + "\"}")
                .then().statusCode(200).body("OperationId", notNullValue());
    }

    @Test
    @Order(12)
    void getMissingNamespaceReturnsNotFound() {
        call("GetNamespace", "{\"Id\":\"ns-doesnotexist00000\"}")
                .then().statusCode(404)
                .body("__type", equalTo("NamespaceNotFound"));
    }
}
