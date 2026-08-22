package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Regression coverage for lex00/floci#59 and lex00/floci#60: fields accepted on
 * {@code CreateCluster}/{@code CreateService} but silently dropped from storage or from the
 * {@code DescribeClusters}/{@code DescribeServices} response, producing perpetual phantom drift
 * (or, for {@code schedulingStrategy}, a forced replacement) on the very next
 * {@code terraform plan} after a clean apply.
 */
@QuarkusTest
class EcsCreateFieldRoundtripIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";

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

    // ── #59: CreateCluster / DescribeClusters ───────────────────────────────────

    @Test
    void createClusterStoresSettingsAndEchoesThemBackOnCreateAndDescribe() {
        Response created = call("CreateCluster", "{\"clusterName\":\"ci-settings-cluster\","
                + "\"settings\":[{\"name\":\"containerInsights\",\"value\":\"enabled\"}]}");
        created.then()
                .body("cluster.settings", hasSize(1))
                .body("cluster.settings[0].name", equalTo("containerInsights"))
                .body("cluster.settings[0].value", equalTo("enabled"));

        call("DescribeClusters", "{\"clusters\":[\"ci-settings-cluster\"]}").then()
                .body("clusters[0].settings", hasSize(1))
                .body("clusters[0].settings[0].name", equalTo("containerInsights"))
                .body("clusters[0].settings[0].value", equalTo("enabled"));
    }

    @Test
    void putClusterCapacityProvidersDefaultStrategyRoundTripsThroughDescribeClusters() {
        call("CreateCluster", "{\"clusterName\":\"ci-capacity-cluster\"}");

        call("PutClusterCapacityProviders", "{\"cluster\":\"ci-capacity-cluster\","
                + "\"capacityProviders\":[\"FARGATE\",\"FARGATE_SPOT\"],"
                + "\"defaultCapacityProviderStrategy\":["
                + "{\"capacityProvider\":\"FARGATE\",\"weight\":1,\"base\":1},"
                + "{\"capacityProvider\":\"FARGATE_SPOT\",\"weight\":4}]}")
                .then()
                .body("cluster.defaultCapacityProviderStrategy", hasSize(2))
                .body("cluster.defaultCapacityProviderStrategy[0].capacityProvider", equalTo("FARGATE"))
                .body("cluster.defaultCapacityProviderStrategy[0].weight", equalTo(1))
                .body("cluster.defaultCapacityProviderStrategy[0].base", equalTo(1))
                .body("cluster.defaultCapacityProviderStrategy[1].capacityProvider", equalTo("FARGATE_SPOT"))
                .body("cluster.defaultCapacityProviderStrategy[1].weight", equalTo(4));

        call("DescribeClusters", "{\"clusters\":[\"ci-capacity-cluster\"]}").then()
                .body("clusters[0].defaultCapacityProviderStrategy", hasSize(2))
                .body("clusters[0].defaultCapacityProviderStrategy[0].capacityProvider", equalTo("FARGATE"))
                .body("clusters[0].defaultCapacityProviderStrategy[0].weight", equalTo(1))
                .body("clusters[0].defaultCapacityProviderStrategy[0].base", equalTo(1))
                .body("clusters[0].defaultCapacityProviderStrategy[1].capacityProvider", equalTo("FARGATE_SPOT"))
                .body("clusters[0].defaultCapacityProviderStrategy[1].weight", equalTo(4));
    }

    // ── #60: CreateService / DescribeServices ───────────────────────────────────

    private static void seedClusterAndTaskDef(String cluster, String family) {
        call("CreateCluster", "{\"clusterName\":\"" + cluster + "\"}");
        call("RegisterTaskDefinition", "{\"family\":\"" + family + "\","
                + "\"containerDefinitions\":[{\"name\":\"web\",\"image\":\"nginx\",\"memory\":128}]}");
    }

    @Test
    void createServiceRoundTripsSchedulingStrategyManagedTagsExecuteCommandAndGracePeriod() {
        seedClusterAndTaskDef("ci-svc-cluster-1", "ci-svc-td-1");

        String body = "{\"cluster\":\"ci-svc-cluster-1\",\"serviceName\":\"ci-svc-1\","
                + "\"taskDefinition\":\"ci-svc-td-1\",\"desiredCount\":0,"
                + "\"schedulingStrategy\":\"REPLICA\","
                + "\"enableECSManagedTags\":true,"
                + "\"enableExecuteCommand\":true,"
                + "\"healthCheckGracePeriodSeconds\":90}";

        call("CreateService", body).then()
                .body("service.schedulingStrategy", equalTo("REPLICA"))
                .body("service.enableECSManagedTags", equalTo(true))
                .body("service.enableExecuteCommand", equalTo(true))
                .body("service.healthCheckGracePeriodSeconds", equalTo(90));

        call("DescribeServices", "{\"cluster\":\"ci-svc-cluster-1\",\"services\":[\"ci-svc-1\"]}").then()
                .body("services[0].schedulingStrategy", equalTo("REPLICA"))
                .body("services[0].enableECSManagedTags", equalTo(true))
                .body("services[0].enableExecuteCommand", equalTo(true))
                .body("services[0].healthCheckGracePeriodSeconds", equalTo(90));
    }

    @Test
    void schedulingStrategyDefaultsToReplicaWhenNotSpecified() {
        // Real ECS defaults an unspecified schedulingStrategy to REPLICA. Before this fix it
        // read back as unset/null, which the AWS provider's `scheduling_strategy = "REPLICA"`
        // (Required, ForceNew) schema attribute treats as a diff that forces replacement.
        seedClusterAndTaskDef("ci-svc-cluster-2", "ci-svc-td-2");

        call("CreateService", "{\"cluster\":\"ci-svc-cluster-2\",\"serviceName\":\"ci-svc-2\","
                + "\"taskDefinition\":\"ci-svc-td-2\",\"desiredCount\":0}").then()
                .body("service.schedulingStrategy", equalTo("REPLICA"));

        call("DescribeServices", "{\"cluster\":\"ci-svc-cluster-2\",\"services\":[\"ci-svc-2\"]}").then()
                .body("services[0].schedulingStrategy", equalTo("REPLICA"));
    }

    @Test
    void createServiceRoundTripsDeploymentController() {
        seedClusterAndTaskDef("ci-svc-cluster-3", "ci-svc-td-3");

        String body = "{\"cluster\":\"ci-svc-cluster-3\",\"serviceName\":\"ci-svc-3\","
                + "\"taskDefinition\":\"ci-svc-td-3\",\"desiredCount\":0,"
                + "\"deploymentController\":{\"type\":\"CODE_DEPLOY\"}}";

        call("CreateService", body).then()
                .body("service.deploymentController.type", equalTo("CODE_DEPLOY"));

        call("DescribeServices", "{\"cluster\":\"ci-svc-cluster-3\",\"services\":[\"ci-svc-3\"]}").then()
                .body("services[0].deploymentController.type", equalTo("CODE_DEPLOY"));
    }

    @Test
    void createServiceRoundTripsBlueGreenLoadBalancerAdvancedConfiguration() {
        seedClusterAndTaskDef("ci-svc-cluster-4", "ci-svc-td-4");

        String body = "{\"cluster\":\"ci-svc-cluster-4\",\"serviceName\":\"ci-svc-4\","
                + "\"taskDefinition\":\"ci-svc-td-4\",\"desiredCount\":0,"
                + "\"loadBalancers\":[{\"targetGroupArn\":\"arn:aws:elasticloadbalancing:us-east-1:"
                + "000000000000:targetgroup/blue/aaaaaaaaaaaaaaaa\",\"containerName\":\"web\","
                + "\"containerPort\":80,\"advancedConfiguration\":{"
                + "\"alternateTargetGroupArn\":\"arn:aws:elasticloadbalancing:us-east-1:"
                + "000000000000:targetgroup/green/bbbbbbbbbbbbbbbb\","
                + "\"productionListenerRule\":\"arn:aws:elasticloadbalancing:us-east-1:"
                + "000000000000:listener-rule/app/lb/1/2/3\","
                + "\"testListenerRule\":\"arn:aws:elasticloadbalancing:us-east-1:"
                + "000000000000:listener-rule/app/lb/1/2/4\","
                + "\"roleArn\":\"arn:aws:iam::000000000000:role/ecs-blue-green\"}}]}";

        call("CreateService", body).then()
                .body("service.loadBalancers", hasSize(1))
                .body("service.loadBalancers[0].advancedConfiguration.alternateTargetGroupArn",
                        equalTo("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/green/bbbbbbbbbbbbbbbb"))
                .body("service.loadBalancers[0].advancedConfiguration.productionListenerRule",
                        equalTo("arn:aws:elasticloadbalancing:us-east-1:000000000000:listener-rule/app/lb/1/2/3"))
                .body("service.loadBalancers[0].advancedConfiguration.testListenerRule",
                        equalTo("arn:aws:elasticloadbalancing:us-east-1:000000000000:listener-rule/app/lb/1/2/4"))
                .body("service.loadBalancers[0].advancedConfiguration.roleArn",
                        equalTo("arn:aws:iam::000000000000:role/ecs-blue-green"));

        call("DescribeServices", "{\"cluster\":\"ci-svc-cluster-4\",\"services\":[\"ci-svc-4\"]}").then()
                .body("services[0].loadBalancers[0].advancedConfiguration.alternateTargetGroupArn",
                        equalTo("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/green/bbbbbbbbbbbbbbbb"))
                .body("services[0].loadBalancers[0].advancedConfiguration.roleArn",
                        equalTo("arn:aws:iam::000000000000:role/ecs-blue-green"));
    }

    @Test
    void createServiceRoundTripsServiceConnectConfiguration() {
        seedClusterAndTaskDef("ci-svc-cluster-5", "ci-svc-td-5");

        String body = "{\"cluster\":\"ci-svc-cluster-5\",\"serviceName\":\"ci-svc-5\","
                + "\"taskDefinition\":\"ci-svc-td-5\",\"desiredCount\":0,"
                + "\"serviceConnectConfiguration\":{\"enabled\":true,\"namespace\":\"internal\","
                + "\"services\":[{\"portName\":\"web\",\"discoveryName\":\"web-svc\","
                + "\"clientAliases\":[{\"port\":80,\"dnsName\":\"web.internal\"}]}]}}";

        call("CreateService", body).then()
                .body("service.serviceConnectConfiguration.enabled", equalTo(true))
                .body("service.serviceConnectConfiguration.namespace", equalTo("internal"))
                .body("service.serviceConnectConfiguration.services[0].portName", equalTo("web"))
                .body("service.serviceConnectConfiguration.services[0].discoveryName", equalTo("web-svc"))
                .body("service.serviceConnectConfiguration.services[0].clientAliases[0].port", equalTo(80))
                .body("service.serviceConnectConfiguration.services[0].clientAliases[0].dnsName",
                        equalTo("web.internal"));

        call("DescribeServices", "{\"cluster\":\"ci-svc-cluster-5\",\"services\":[\"ci-svc-5\"]}").then()
                .body("services[0].serviceConnectConfiguration.enabled", equalTo(true))
                .body("services[0].serviceConnectConfiguration.namespace", equalTo("internal"))
                .body("services[0].serviceConnectConfiguration.services[0].discoveryName", equalTo("web-svc"))
                .body("services[0].serviceConnectConfiguration.services[0].clientAliases[0].dnsName",
                        equalTo("web.internal"));
    }
}
