package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration tests for the ECS service {@code loadBalancers} block (mock mode).
 * Verifies the field is parsed on CreateService and echoed on DescribeServices.
 * Actual ELBv2 target registration requires Docker-backed tasks and is covered by
 * {@link EcsLoadBalancerRegistrarTest} (registrar logic) and the end-to-end scenario.
 */
@QuarkusTest
class EcsLoadBalancersIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static void call(String action, String body) {
        given().contentType(CT).header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then().statusCode(200);
    }

    private void seedClusterAndTaskDef(String cluster, String family) {
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}");
        call("RegisterTaskDefinition", "{\"family\":\"" + family + "\","
                + "\"containerDefinitions\":[{\"name\":\"web\",\"image\":\"nginx\","
                + "\"memory\":128,\"portMappings\":[{\"containerPort\":8080}]}]}");
    }

    @Test
    void createServiceParsesAndEchoesLoadBalancers() {
        seedClusterAndTaskDef("lb-cluster-1", "lb-td-1");
        String tgArn = "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/lbtg1/abc123";

        given().contentType(CT).header("X-Amz-Target", TARGET + "CreateService")
                .body("{\"cluster\":\"lb-cluster-1\",\"serviceName\":\"lb-svc-1\","
                        + "\"taskDefinition\":\"lb-td-1\",\"desiredCount\":1,"
                        + "\"loadBalancers\":[{\"targetGroupArn\":\"" + tgArn + "\","
                        + "\"containerName\":\"web\",\"containerPort\":8080}]}")
                .when().post("/")
                .then().statusCode(200)
                .body("service.serviceName", equalTo("lb-svc-1"))
                .body("service.loadBalancers", hasSize(1))
                .body("service.loadBalancers[0].targetGroupArn", equalTo(tgArn))
                .body("service.loadBalancers[0].containerName", equalTo("web"))
                .body("service.loadBalancers[0].containerPort", equalTo(8080));
    }

    @Test
    void describeServicesEchoesLoadBalancers() {
        seedClusterAndTaskDef("lb-cluster-2", "lb-td-2");
        String tgArn = "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/lbtg2/def456";
        call("CreateService", "{\"cluster\":\"lb-cluster-2\",\"serviceName\":\"lb-svc-2\","
                + "\"taskDefinition\":\"lb-td-2\",\"desiredCount\":1,"
                + "\"loadBalancers\":[{\"targetGroupArn\":\"" + tgArn + "\","
                + "\"containerName\":\"web\",\"containerPort\":8080}]}");

        given().contentType(CT).header("X-Amz-Target", TARGET + "DescribeServices")
                .body("{\"cluster\":\"lb-cluster-2\",\"services\":[\"lb-svc-2\"]}")
                .when().post("/")
                .then().statusCode(200)
                .body("services", hasSize(1))
                .body("services[0].loadBalancers", hasSize(1))
                .body("services[0].loadBalancers[0].targetGroupArn", equalTo(tgArn));
    }

    @Test
    void createServiceWithoutLoadBalancersOmitsField() {
        seedClusterAndTaskDef("lb-cluster-3", "lb-td-3");
        given().contentType(CT).header("X-Amz-Target", TARGET + "CreateService")
                .body("{\"cluster\":\"lb-cluster-3\",\"serviceName\":\"lb-svc-3\","
                        + "\"taskDefinition\":\"lb-td-3\",\"desiredCount\":1}")
                .when().post("/")
                .then().statusCode(200)
                .body("service.serviceName", equalTo("lb-svc-3"))
                .body("service.loadBalancers", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void createServiceRejectsLoadBalancerEntryMissingRequiredFields() {
        seedClusterAndTaskDef("lb-cluster-5", "lb-td-5");
        // entry has containerName but no containerPort and no targetGroupArn/loadBalancerName
        given().contentType(CT).header("X-Amz-Target", TARGET + "CreateService")
                .body("{\"cluster\":\"lb-cluster-5\",\"serviceName\":\"lb-svc-5\","
                        + "\"taskDefinition\":\"lb-td-5\",\"desiredCount\":1,"
                        + "\"loadBalancers\":[{\"containerName\":\"web\"}]}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", org.hamcrest.Matchers.containsString("InvalidParameterException"));
    }

    @Test
    void createServiceRejectsLoadBalancerEntryWithoutTargetGroup() {
        seedClusterAndTaskDef("lb-cluster-6", "lb-td-6");
        // containerName + containerPort present, but neither targetGroupArn nor loadBalancerName
        given().contentType(CT).header("X-Amz-Target", TARGET + "CreateService")
                .body("{\"cluster\":\"lb-cluster-6\",\"serviceName\":\"lb-svc-6\","
                        + "\"taskDefinition\":\"lb-td-6\",\"desiredCount\":1,"
                        + "\"loadBalancers\":[{\"containerName\":\"web\",\"containerPort\":8080}]}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", org.hamcrest.Matchers.containsString("InvalidParameterException"));
    }

    @Test
    void serviceWithLoadBalancersIsStableInMockMode() {
        // Mock mode launches no real containers; the registrar must no-op cleanly.
        seedClusterAndTaskDef("lb-cluster-4", "lb-td-4");
        String tgArn = "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/lbtg4/ghi789";
        call("CreateService", "{\"cluster\":\"lb-cluster-4\",\"serviceName\":\"lb-svc-4\","
                + "\"taskDefinition\":\"lb-td-4\",\"desiredCount\":1,"
                + "\"loadBalancers\":[{\"targetGroupArn\":\"" + tgArn + "\","
                + "\"containerName\":\"web\",\"containerPort\":8080}]}");
        // Service is describable and intact — no crash from the registrar hook.
        given().contentType(CT).header("X-Amz-Target", TARGET + "DescribeServices")
                .body("{\"cluster\":\"lb-cluster-4\",\"services\":[\"lb-svc-4\"]}")
                .when().post("/")
                .then().statusCode(200)
                .body("services[0].serviceArn", notNullValue())
                .body("services[0].status", equalTo("ACTIVE"));
    }
}
