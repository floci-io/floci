package io.floci.aws.services.ecs;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * Under IAM enforcement an ECS request is checked on the resources it names, at the ARNs ECS mints for them: a
 * cluster by its name or ARN, a service or a task in the request's cluster or the default one, a task definition as
 * the revision its reference resolves to, and a capacity provider. A grant scoped to one reaches it and no other.
 * ECS runs in mock mode, with no container behind a task.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EcsIamIntegrationTest {

    private static final String ACCOUNT = "000000000472";
    private static final String REGION = "us-east-1";
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";

    @TestHTTPResource
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aClusterScopedGrantReachesThatClusterByNameOrArnAndNoOther() {
        String mine = cluster();
        String other = cluster();
        String arn = arn("cluster/" + mine);
        String user = user(allow(List.of(arn), "ecs:DescribeClusters", "ecs:ListContainerInstances"));

        call(user, "DescribeClusters", Map.of("clusters", List.of(mine))).statusCode(200)
                .body("clusters[0].clusterArn", equalTo(arn));
        call(user, "DescribeClusters", Map.of("clusters", List.of(arn))).statusCode(200);
        call(user, "ListContainerInstances", Map.of("cluster", mine)).statusCode(200);
        denied(call(user, "DescribeClusters", Map.of("clusters", List.of(other))), "ecs:DescribeClusters");
        denied(call(user, "ListContainerInstances", Map.of("cluster", other)), "ecs:ListContainerInstances");
    }

    /** A service is in the cluster the request names, by name or ARN, or in the default cluster when it names none. */
    @Test
    void aServiceIsCheckedInTheClusterTheRequestNamesOrTheDefaultOne() {
        String family = family();
        String cluster = cluster();
        String name = "svc-" + UUID.randomUUID().toString().substring(0, 8);
        String inCluster = service(cluster, name, family);
        String inDefault = service(null, name, family);
        String user = user(allow(List.of(inCluster), "ecs:DescribeServices", "ecs:UpdateService"));

        call(user, "DescribeServices", Map.of("cluster", cluster, "services", List.of(name))).statusCode(200)
                .body("services[0].serviceArn", equalTo(inCluster));
        call(user, "DescribeServices", Map.of("cluster", arn("cluster/" + cluster), "services", List.of(inCluster)))
                .statusCode(200);
        call(user, "UpdateService", Map.of("cluster", cluster, "service", name, "desiredCount", 0)).statusCode(200);
        // The same name in the default cluster is another service.
        denied(call(user, "DescribeServices", Map.of("services", List.of(name))), "ecs:DescribeServices");
        denied(call(user, "UpdateService", Map.of("service", name, "desiredCount", 0)), "ecs:UpdateService");

        String inDefaultOnly = user(allow(List.of(inDefault), "ecs:DescribeServices"));
        call(inDefaultOnly, "DescribeServices", Map.of("services", List.of(name))).statusCode(200)
                .body("services[0].serviceArn", equalTo(inDefault));
        call(inDefaultOnly, "DescribeServices", Map.of("cluster", "default", "services", List.of(name))).statusCode(200);
        denied(call(inDefaultOnly, "DescribeServices", Map.of("cluster", cluster, "services", List.of(name))),
                "ecs:DescribeServices");
    }

    /** A task definition is the revision its reference resolves to, a family alone being its latest. */
    @Test
    void aTaskDefinitionIsTheRevisionItsReferenceResolvesTo() {
        String family = family();
        register(family);
        String cluster = cluster();
        String user = user(allow(List.of(arn("task-definition/" + family + ":2")), "ecs:RunTask"));

        for (String reference : List.of(family, family + ":2", arn("task-definition/" + family + ":2"))) {
            stopAfter(cluster, call(user, "RunTask", Map.of("cluster", cluster, "taskDefinition", reference))
                    .statusCode(200).extract().path("tasks[0].taskArn"));
        }
        denied(call(user, "RunTask", Map.of("cluster", cluster, "taskDefinition", family + ":1")), "ecs:RunTask");
    }

    /**
     * A new revision a registration makes is checked on its family with the revision open, never on the revision
     * that happens to be the latest: a deny on an old revision does not refuse registering the next.
     */
    @Test
    void aRegistrationIsCheckedOnItsFamilyNotOnTheLatestRevision() {
        String family = family();
        String user = user("{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"ecs:RegisterTaskDefinition\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"ecs:RegisterTaskDefinition\",\"Resource\":\""
                + arn("task-definition/" + family + ":1") + "\"}]}");

        call(user, "RegisterTaskDefinition", Map.of("family", family, "containerDefinitions",
                List.of(Map.of("name", "runner", "image", "floci-test/runner:latest", "memory", 128))))
                .statusCode(200).body("taskDefinition.revision", equalTo(2));
    }

    /** A service's deployments are under its cluster and name, as AWS mints them, and checked on those ARNs. */
    @Test
    void aServiceDeploymentIsCheckedOnTheArnEcsMintsUnderItsService() {
        String family = family();
        String cluster = cluster();
        String name = "svc-" + UUID.randomUUID().toString().substring(0, 8);
        service(cluster, name, family);
        String deployment = call(ACCOUNT, "ListServiceDeployments", Map.of("cluster", cluster, "service", name))
                .statusCode(200).extract().path("serviceDeployments[0].serviceDeploymentArn");
        assertThat(deployment, startsWith(arn("service-deployment/" + cluster + "/" + name + "/")));

        String mine = user(allow(List.of(deployment), "ecs:DescribeServiceDeployments"));
        call(mine, "DescribeServiceDeployments", Map.of("serviceDeploymentArns", List.of(deployment))).statusCode(200)
                .body("serviceDeployments[0].serviceDeploymentArn", equalTo(deployment));
        String another = user(allow(List.of(arn("service-deployment/" + cluster + "/other/*")),
                "ecs:DescribeServiceDeployments"));
        denied(call(another, "DescribeServiceDeployments", Map.of("serviceDeploymentArns", List.of(deployment))),
                "ecs:DescribeServiceDeployments");
    }

    @Test
    void aTaskIsCheckedOnTheArnEcsMintsForIt() {
        String family = family();
        String cluster = cluster();
        String mine = run(cluster, family);
        String other = run(cluster, family);
        String user = user(allow(List.of(mine), "ecs:DescribeTasks", "ecs:StopTask"));

        call(user, "DescribeTasks", Map.of("cluster", cluster, "tasks", List.of(mine.substring(mine.lastIndexOf('/') + 1))))
                .statusCode(200).body("tasks[0].taskArn", equalTo(mine));
        call(user, "DescribeTasks", Map.of("cluster", cluster, "tasks", List.of(mine))).statusCode(200);
        denied(call(user, "DescribeTasks", Map.of("cluster", cluster, "tasks", List.of(other))), "ecs:DescribeTasks");
        denied(call(user, "StopTask", Map.of("cluster", cluster, "task", other)), "ecs:StopTask");
    }

    @Test
    void aCapacityProviderIsCheckedOnTheArnEcsMintsForIt() {
        String mine = capacityProvider();
        String other = capacityProvider();
        String arn = arn("capacity-provider/" + mine);
        String user = user(allow(List.of(arn), "ecs:DescribeCapacityProviders"));

        call(user, "DescribeCapacityProviders", Map.of("capacityProviders", List.of(mine))).statusCode(200)
                .body("capacityProviders[0].capacityProviderArn", equalTo(arn));
        denied(call(user, "DescribeCapacityProviders", Map.of("capacityProviders", List.of(other))),
                "ecs:DescribeCapacityProviders");
    }

    private String cluster() {
        String name = "iam-" + UUID.randomUUID().toString().substring(0, 8);
        call(ACCOUNT, "CreateCluster", Map.of("clusterName", name)).statusCode(200);
        cleanup.register(() -> call(ACCOUNT, "DeleteCluster", Map.of("cluster", name)));
        return name;
    }

    private String family() {
        String family = "iam-" + UUID.randomUUID().toString().substring(0, 8);
        register(family);
        return family;
    }

    private void register(String family) {
        call(ACCOUNT, "RegisterTaskDefinition", Map.of("family", family, "containerDefinitions",
                List.of(Map.of("name", "runner", "image", "floci-test/runner:latest", "memory", 128))))
                .statusCode(200);
    }

    /** A service of no tasks in the cluster, or in the default one for none; its ARN as ECS mints it. */
    private String service(String cluster, String name, String family) {
        Map<String, Object> request = cluster == null
                ? Map.of("serviceName", name, "taskDefinition", family, "desiredCount", 0)
                : Map.of("cluster", cluster, "serviceName", name, "taskDefinition", family, "desiredCount", 0);
        String arn = call(ACCOUNT, "CreateService", request).statusCode(200).extract().path("service.serviceArn");
        cleanup.register(() -> call(ACCOUNT, "DeleteService", cluster == null
                ? Map.of("service", name, "force", true) : Map.of("cluster", cluster, "service", name, "force", true)));
        return arn;
    }

    private String run(String cluster, String family) {
        String task = call(ACCOUNT, "RunTask", Map.of("cluster", cluster, "taskDefinition", family)).statusCode(200)
                .extract().path("tasks[0].taskArn");
        stopAfter(cluster, task);
        return task;
    }

    private void stopAfter(String cluster, String task) {
        cleanup.register(() -> call(ACCOUNT, "StopTask", Map.of("cluster", cluster, "task", task)));
    }

    private String capacityProvider() {
        String name = "iam-" + UUID.randomUUID().toString().substring(0, 8);
        call(ACCOUNT, "CreateCapacityProvider", Map.of("name", name, "autoScalingGroupProvider", Map.of(
                "autoScalingGroupArn", "arn:aws:autoscaling:" + REGION + ":" + ACCOUNT
                        + ":autoScalingGroup:00000000-0000-0000-0000-000000000000:autoScalingGroupName/" + name)))
                .statusCode(200);
        cleanup.register(() -> call(ACCOUNT, "DeleteCapacityProvider", Map.of("capacityProvider", name)));
        return name;
    }

    private static String arn(String resource) {
        return "arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":" + resource;
    }

    private static String allow(List<String> resources, String... actions) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\""
                + String.join("\",\"", actions) + "\"],\"Resource\":[\"" + String.join("\",\"", resources) + "\"]}]}";
    }

    private static void denied(ValidatableResponse response, String action) {
        response.statusCode(400).body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString(action));
    }

    private String user(String policy) {
        String name = "ecs-" + UUID.randomUUID().toString().substring(0, 8);
        String authorization = auth(ACCOUNT, "iam");
        String accessKeyId = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, name)
                .accessKeyId();
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, name, "p", policy);
        return accessKeyId;
    }

    private ValidatableResponse call(String accessKeyId, String action, Map<String, ?> body) {
        return given().port(endpoint.getPort())
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", auth(accessKeyId, "ecs"))
                .contentType(CONTENT_TYPE)
                .body(body)
                .when().post("/")
                .then();
    }
}
