package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DescribeServices has to report a Service Connect configuration on each {@code deployments[]}
 * entry. AWS's {@code Service} shape declares no {@code serviceConnectConfiguration} member, so a
 * generated client parses the field only at the deployment location and drops it anywhere else.
 * Terraform's provider reads it from there too, and reports a service with no readable value as a
 * pending change on every plan.
 */
@QuarkusTest
class EcsServiceConnectConfigurationIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";
    private static final String CLUSTER = "sc-cluster";
    private static final String FAMILY = "sc-fam";

    private static final String CONFIG = "{\"enabled\":true,\"namespace\":\"sc-ns\","
            + "\"services\":[{\"portName\":\"http\",\"discoveryName\":\"web\","
            + "\"clientAliases\":[{\"port\":8080,\"dnsName\":\"web.sc-ns\"}]}]}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().response();
    }

    private static void seed() {
        call("CreateCluster", "{\"clusterName\":\"" + CLUSTER + "\"}");
        call("RegisterTaskDefinition", "{\"family\":\"" + FAMILY + "\","
                + "\"containerDefinitions\":[{\"name\":\"web\",\"image\":\"nginx\",\"memory\":128}]}");
    }

    private static Response describe(String serviceName) {
        return call("DescribeServices", "{\"cluster\":\"" + CLUSTER + "\","
                + "\"services\":[\"" + serviceName + "\"]}");
    }

    @Test
    void createService_reportsServiceConnectConfigurationOnTheDeployment() {
        seed();
        String name = "sc-create";
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"" + name + "\","
                + "\"taskDefinition\":\"" + FAMILY + "\",\"desiredCount\":0,"
                + "\"serviceConnectConfiguration\":" + CONFIG + "}");

        Response described = describe(name);
        String base = "services[0].deployments[0].serviceConnectConfiguration.";

        assertEquals(true, described.jsonPath().getBoolean(base + "enabled"));
        assertEquals("sc-ns", described.jsonPath().getString(base + "namespace"));
        assertEquals("http", described.jsonPath().getString(base + "services[0].portName"));
        assertEquals("web", described.jsonPath().getString(base + "services[0].discoveryName"));
        assertEquals("web.sc-ns",
                described.jsonPath().getString(base + "services[0].clientAliases[0].dnsName"));
        assertEquals(8080, described.jsonPath().getInt(base + "services[0].clientAliases[0].port"));

        // The Service shape has no member for it, so nothing should appear at the top level.
        assertNull(described.jsonPath().get("services[0].serviceConnectConfiguration"));
    }

    @Test
    void updateService_replacesTheStoredServiceConnectConfiguration() {
        seed();
        String name = "sc-update";
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"" + name + "\","
                + "\"taskDefinition\":\"" + FAMILY + "\",\"desiredCount\":0,"
                + "\"serviceConnectConfiguration\":" + CONFIG + "}");

        call("UpdateService", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"" + name + "\","
                + "\"serviceConnectConfiguration\":{\"enabled\":false}}");

        Response described = describe(name);
        String base = "services[0].deployments[0].serviceConnectConfiguration.";
        assertEquals(false, described.jsonPath().getBoolean(base + "enabled"));
        assertNull(described.jsonPath().get(base + "namespace"));
    }

    @Test
    void updateService_withoutTheField_keepsTheStoredConfiguration() {
        seed();
        String name = "sc-keep";
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"" + name + "\","
                + "\"taskDefinition\":\"" + FAMILY + "\",\"desiredCount\":0,"
                + "\"serviceConnectConfiguration\":" + CONFIG + "}");

        call("UpdateService", "{\"cluster\":\"" + CLUSTER + "\",\"service\":\"" + name + "\","
                + "\"desiredCount\":0}");

        Response described = describe(name);
        assertEquals("sc-ns", described.jsonPath()
                .getString("services[0].deployments[0].serviceConnectConfiguration.namespace"));
    }

    @Test
    void createService_withoutTheField_reportsNoServiceConnectConfiguration() {
        seed();
        String name = "sc-absent";
        call("CreateService", "{\"cluster\":\"" + CLUSTER + "\",\"serviceName\":\"" + name + "\","
                + "\"taskDefinition\":\"" + FAMILY + "\",\"desiredCount\":0}");

        Response described = describe(name);
        assertTrue(described.jsonPath().getList("services[0].deployments").size() == 1);
        assertNull(described.jsonPath()
                .get("services[0].deployments[0].serviceConnectConfiguration"));
    }
}
