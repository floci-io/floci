package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for the ECS service (mock mode — no Docker required).
 *
 * Coverage:
 *  - Clusters: Create, Describe, List, Update, Delete
 *  - Task Definitions: Register, Describe, List, ListFamilies, Deregister
 *  - Tasks: RunTask, DescribeTask, ListTasks, StopTask
 *  - Services: Create, Describe, List, Update, Delete
 *  - Tags: TagResource, ListTagsForResource, UntagResource
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EcsIntegrationTest {

    private static final String TARGET_PREFIX = "AmazonEC2ContainerServiceV20141113.";
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER_NAME = "test-cluster";
    private static final String CLUSTER_ARN =
            "arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":cluster/" + CLUSTER_NAME;
    private static final String TAGGED_CLUSTER_NAME = "tagged-create-cluster";
    private static final String TAGGED_CLUSTER_ARN =
            "arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":cluster/" + TAGGED_CLUSTER_NAME;
    private static final String TASK_DEF_FAMILY = "test-task";
    private static final String SERVICE_NAME = "test-service";

    private static final String REVIEW_CLUSTER = "review-cluster";
    private static final String REVIEW_SERVICE = "review-svc";
    private static final String REVIEW_LB_SERVICE = "review-lb-svc";

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    private static String taskArn;
    private static String serviceArn;
    private static String taskDefArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static io.restassured.specification.RequestSpecification ecs(String action) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action);
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createCluster() {
        ecs("CreateCluster")
            .body("""
                {"clusterName": "%s"}
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("cluster.clusterName", equalTo(CLUSTER_NAME))
            .body("cluster.clusterArn", containsString(CLUSTER_NAME))
            .body("cluster.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(2)
    void createClusterIdempotent() {
        ecs("CreateCluster")
            .body("""
                {"clusterName": "%s"}
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("cluster.clusterName", equalTo(CLUSTER_NAME));
    }

    @Test
    @Order(3)
    void describeCluster() {
        ecs("DescribeClusters")
            .body("""
                {"clusters": ["%s"]}
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("clusters", hasSize(1))
            .body("clusters[0].clusterName", equalTo(CLUSTER_NAME))
            .body("clusters[0].status", equalTo("ACTIVE"))
            .body("failures", empty());
    }

    @Test
    @Order(4)
    void listClusters() {
        ecs("ListClusters")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("clusterArns", hasItem(containsString(CLUSTER_NAME)));
    }

    @Test
    @Order(5)
    void createClusterWithTagsAvailableThroughListTagsForResource() {
        ecs("CreateCluster")
            .body("""
                {
                    "clusterName": "%s",
                    "tags": [
                        {"key": "Environment", "value": "dev"},
                        {"key": "Project", "value": "project1"}
                    ]
                }
                """.formatted(TAGGED_CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("cluster.clusterName", equalTo(TAGGED_CLUSTER_NAME));

        ecs("ListTagsForResource")
            .body("""
                {"resourceArn": "%s"}
                """.formatted(TAGGED_CLUSTER_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags", hasSize(2))
            .body("tags.find { it.key == 'Environment' }.value", equalTo("dev"))
            .body("tags.find { it.key == 'Project' }.value", equalTo("project1"));
    }

    // ── Task Definitions ──────────────────────────────────────────────────────

    @Test
    @Order(10)
    void registerTaskDefinition() {
        taskDefArn = ecs("RegisterTaskDefinition")
            .body("""
                {
                    "family": "%s",
                    "containerDefinitions": [
                        {
                            "name": "app",
                            "image": "nginx:latest",
                            "cpu": 256,
                            "memory": 512,
                            "essential": true,
                            "portMappings": [{"containerPort": 80, "protocol": "tcp"}]
                        }
                    ],
                    "requiresCompatibilities": ["FARGATE"],
                    "cpu": "256",
                    "memory": "512",
                    "networkMode": "awsvpc",
                    "tags": [
                        {"key": "Environment", "value": "dev"},
                        {"key": "Project", "value": "project1"}
                    ]
                }
                """.formatted(TASK_DEF_FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinition.family", equalTo(TASK_DEF_FAMILY))
            .body("taskDefinition.revision", equalTo(1))
            .body("taskDefinition.status", equalTo("ACTIVE"))
            .body("taskDefinition.taskDefinitionArn", containsString(TASK_DEF_FAMILY))
            .body("taskDefinition.containerDefinitions", hasSize(1))
            .body("taskDefinition.containerDefinitions[0].name", equalTo("app"))
        .extract()
            .path("taskDefinition.taskDefinitionArn");

        ecs("ListTagsForResource")
            .body("""
                {"resourceArn": "%s"}
                """.formatted(taskDefArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags", hasSize(2))
            .body("tags.find { it.key == 'Environment' }.value", equalTo("dev"))
            .body("tags.find { it.key == 'Project' }.value", equalTo("project1"));
    }

    @Test
    @Order(11)
    void describeTaskDefinition() {
        ecs("DescribeTaskDefinition")
            .body("""
                {"taskDefinition": "%s:1"}
                """.formatted(TASK_DEF_FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinition.family", equalTo(TASK_DEF_FAMILY))
            .body("taskDefinition.revision", equalTo(1))
            .body("taskDefinition.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(12)
    void listTaskDefinitions() {
        ecs("ListTaskDefinitions")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinitionArns", hasItem(containsString(TASK_DEF_FAMILY)));
    }

    @Test
    @Order(13)
    void listTaskDefinitionFamilies() {
        ecs("ListTaskDefinitionFamilies")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("families", hasItem(TASK_DEF_FAMILY));
    }

    // ── Tasks ─────────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void runTask() {
        taskArn = ecs("RunTask")
            .body("""
                {
                    "cluster": "%s",
                    "taskDefinition": "%s",
                    "launchType": "FARGATE",
                    "count": 1
                }
                """.formatted(CLUSTER_NAME, TASK_DEF_FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tasks", hasSize(1))
            .body("tasks[0].taskArn", containsString("task/"))
            .body("tasks[0].clusterArn", containsString(CLUSTER_NAME))
            .body("tasks[0].lastStatus", notNullValue())
            .body("failures", empty())
        .extract()
            .path("tasks[0].taskArn");
    }

    @Test
    @Order(21)
    void describeTask() {
        ecs("DescribeTasks")
            .body("""
                {
                    "cluster": "%s",
                    "tasks": ["%s"]
                }
                """.formatted(CLUSTER_NAME, taskArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tasks", hasSize(1))
            .body("tasks[0].taskArn", equalTo(taskArn))
            .body("failures", empty());
    }

    @Test
    @Order(22)
    void listTasks() {
        ecs("ListTasks")
            .body("""
                {"cluster": "%s"}
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskArns", hasItem(taskArn));
    }

    @Test
    @Order(23)
    void stopTask() {
        ecs("StopTask")
            .body("""
                {
                    "cluster": "%s",
                    "task": "%s",
                    "reason": "integration-test"
                }
                """.formatted(CLUSTER_NAME, taskArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("task.taskArn", equalTo(taskArn))
            .body("task.lastStatus", equalTo("STOPPED"));
    }

    @Test
    @Order(24)
    void runTaskWithEmptyContainerDefinitionsFailsLoudly() {
        ecs("RegisterTaskDefinition")
            .body("""
                {
                    "family": "empty-containers-task",
                    "containerDefinitions": []
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ecs("RunTask")
            .body("""
                {
                    "cluster": "%s",
                    "taskDefinition": "empty-containers-task",
                    "launchType": "FARGATE",
                    "count": 1
                }
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ClientException"))
            .body("message", containsString("no container definitions"));

        // The failed launch must not leave a phantom task behind.
        ecs("ListTasks")
            .body("""
                {"cluster": "%s", "family": "empty-containers-task"}
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskArns", empty());
    }

    // ── Services ──────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    void createService() {
        serviceArn = ecs("CreateService")
            .body("""
                {
                    "cluster": "%s",
                    "serviceName": "%s",
                    "taskDefinition": "%s",
                    "desiredCount": 1,
                    "launchType": "FARGATE",
                    "tags": [
                        {"key": "Environment", "value": "dev"},
                        {"key": "Project", "value": "project1"}
                    ]
                }
                """.formatted(CLUSTER_NAME, SERVICE_NAME, TASK_DEF_FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("service.serviceName", equalTo(SERVICE_NAME))
            .body("service.serviceArn", containsString(SERVICE_NAME))
            .body("service.clusterArn", containsString(CLUSTER_NAME))
            .body("service.desiredCount", equalTo(1))
            .body("service.status", equalTo("ACTIVE"))
        .extract()
            .path("service.serviceArn");

        ecs("ListTagsForResource")
            .body("""
                {"resourceArn": "%s"}
                """.formatted(serviceArn))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags", hasSize(2))
            .body("tags.find { it.key == 'Environment' }.value", equalTo("dev"))
            .body("tags.find { it.key == 'Project' }.value", equalTo("project1"));
    }

    @Test
    @Order(31)
    void describeService() {
        ecs("DescribeServices")
            .body("""
                {
                    "cluster": "%s",
                    "services": ["%s"]
                }
                """.formatted(CLUSTER_NAME, SERVICE_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("services", hasSize(1))
            .body("services[0].serviceName", equalTo(SERVICE_NAME))
            .body("services[0].status", equalTo("ACTIVE"))
            .body("failures", empty());
    }

    @Test
    @Order(32)
    void listServices() {
        ecs("ListServices")
            .body("""
                {"cluster": "%s"}
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("serviceArns", hasItem(containsString(SERVICE_NAME)));
    }

    @Test
    @Order(33)
    void updateService() {
        ecs("UpdateService")
            .body("""
                {
                    "cluster": "%s",
                    "service": "%s",
                    "desiredCount": 2
                }
                """.formatted(CLUSTER_NAME, SERVICE_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("service.desiredCount", equalTo(2));
    }

    @Test
    @Order(34)
    void createServiceDuplicateActiveNameAlwaysFails() {
        ecs("CreateService")
                .body("""
                {
                    "cluster": "%s",
                    "serviceName": "%s",
                    "taskDefinition": "%s",
                    "desiredCount": 2,
                    "launchType": "FARGATE"
                }
                """.formatted(CLUSTER_NAME, SERVICE_NAME, TASK_DEF_FAMILY))
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", containsString("InvalidParameterException"))
                .body("message", containsString("Creation of service was not idempotent."));
    }

    @Test
    @Order(35)
    void createServiceWithDifferentParametersFailsIdempotency() {
        ecs("CreateService")
                .body("""
                {
                    "cluster": "%s",
                    "serviceName": "%s",
                    "taskDefinition": "%s",
                    "desiredCount": 99,
                    "launchType": "FARGATE"
                }
                """.formatted(CLUSTER_NAME, SERVICE_NAME, TASK_DEF_FAMILY))
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", containsString("InvalidParameterException"))
                .body("message", containsString("Creation of service was not idempotent."));
    }

    // ── Tags ─────────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    void tagResource() {
        ecs("TagResource")
            .body("""
                {
                    "resourceArn": "%s",
                    "tags": [
                        {"key": "env", "value": "test"},
                        {"key": "team", "value": "platform"}
                    ]
                }
                """.formatted(CLUSTER_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(41)
    void listTagsForResource() {
        ecs("ListTagsForResource")
            .body("""
                {"resourceArn": "%s"}
                """.formatted(CLUSTER_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags", hasSize(2))
            .body("tags.find { it.key == 'env' }.value", equalTo("test"))
            .body("tags.find { it.key == 'team' }.value", equalTo("platform"));
    }

    @Test
    @Order(42)
    void untagResource() {
        ecs("UntagResource")
            .body("""
                {
                    "resourceArn": "%s",
                    "tagKeys": ["env"]
                }
                """.formatted(CLUSTER_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ecs("ListTagsForResource")
            .body("""
                {"resourceArn": "%s"}
                """.formatted(CLUSTER_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("tags", hasSize(1))
            .body("tags[0].key", equalTo("team"));
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    void deleteService() {
        ecs("DeleteService")
            .body("""
                {
                    "cluster": "%s",
                    "service": "%s",
                    "force": true
                }
                """.formatted(CLUSTER_NAME, SERVICE_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("service.serviceName", equalTo(SERVICE_NAME))
            .body("service.status", equalTo("INACTIVE"));
    }

    @Test
    @Order(51)
    void updateInactiveServiceReturnsServiceNotActiveException() {
        String fullServiceArn = "arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":service/" + CLUSTER_NAME + "/" + SERVICE_NAME;

        ecs("UpdateService")
                .body("""
                {
                    "cluster": "%s",
                    "service": "%s",
                    "desiredCount": 0
                }
                """.formatted(CLUSTER_NAME, fullServiceArn))
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("__type", containsString("ServiceNotActiveException"));
    }

    @Test
    @Order(52)
    void deregisterTaskDefinition() {
        ecs("DeregisterTaskDefinition")
            .body("""
                {"taskDefinition": "%s:1"}
                """.formatted(TASK_DEF_FAMILY))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("taskDefinition.status", equalTo("INACTIVE"));
    }

    @Test
    @Order(53)
    void deleteCluster() {
        ecs("DeleteCluster")
            .body("""
                {"cluster": "%s"}
                """.formatted(CLUSTER_NAME))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("cluster.clusterName", equalTo(CLUSTER_NAME))
            .body("cluster.status", equalTo("INACTIVE"));
    }

    @Test
    @Order(54)
    void deleteClusterNotFound() {
        ecs("DeleteCluster")
            .body("""
                {"cluster": "non-existent-cluster"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400);
    }

    @Test
    @Order(60)
    void reviewSetup_createClusterAndService() {
        ecs("CreateCluster")
            .body("""
                {"clusterName": "%s"}
                """.formatted(REVIEW_CLUSTER))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ecs("RegisterTaskDefinition")
            .body("""
                {
                    "family": "review-td",
                    "containerDefinitions": [
                        {
                            "name": "app",
                            "image": "nginx:latest",
                            "cpu": 256,
                            "memory": 512,
                            "essential": true,
                            "portMappings": [{"containerPort": 80, "protocol": "tcp"}]
                        }
                    ],
                    "requiresCompatibilities": ["FARGATE"],
                    "cpu": "256",
                    "memory": "512",
                    "networkMode": "awsvpc"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ecs("CreateService")
            .body("""
                {
                    "cluster": "%s",
                    "serviceName": "%s",
                    "taskDefinition": "review-td",
                    "desiredCount": 1,
                    "launchType": "FARGATE"
                }
                """.formatted(REVIEW_CLUSTER, REVIEW_SERVICE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("service.status", equalTo("ACTIVE"));
    }

    @Test
    @Order(61)
    void deleteServiceHidesFromListServices() {
        ecs("DeleteService")
            .body("""
                {
                    "cluster": "%s",
                    "service": "%s",
                    "force": true
                }
                """.formatted(REVIEW_CLUSTER, REVIEW_SERVICE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("service.status", equalTo("INACTIVE"));

        ecs("ListServices")
            .body("""
                {"cluster": "%s"}
                """.formatted(REVIEW_CLUSTER))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("serviceArns", not(hasItem(containsString(REVIEW_SERVICE))));
    }

    @Test
    @Order(62)
    void deleteServiceStillDescribableAsInactive() {
        ecs("DescribeServices")
            .body("""
                {
                    "cluster": "%s",
                    "services": ["%s"]
                }
                """.formatted(REVIEW_CLUSTER, REVIEW_SERVICE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("services", hasSize(1))
            .body("services[0].serviceName", equalTo(REVIEW_SERVICE))
            .body("services[0].status", equalTo("INACTIVE"));
    }

    @Test
    @Order(63)
    void updateInactiveServiceShouldReturnError() {
        ecs("UpdateService")
            .body("""
                {
                    "cluster": "%s",
                    "service": "%s",
                    "desiredCount": 5
                }
                """.formatted(REVIEW_CLUSTER, REVIEW_SERVICE))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("ServiceNotActiveException"));
    }

    @Test
    @Order(64)
    void createServiceIgnoresLoadBalancersInIdempotencyCheck() {
        ecs("CreateService")
            .body("""
                {
                    "cluster": "%s",
                    "serviceName": "%s",
                    "taskDefinition": "review-td",
                    "desiredCount": 1,
                    "launchType": "FARGATE",
                    "loadBalancers": [
                        {
                            "targetGroupArn": "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/tg-a/1234",
                            "containerName": "app",
                            "containerPort": 80
                        }
                    ]
                }
                """.formatted(REVIEW_CLUSTER, REVIEW_LB_SERVICE))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("service.serviceName", equalTo(REVIEW_LB_SERVICE))
            .body("service.status", equalTo("ACTIVE"));

        ecs("CreateService")
            .body("""
                {
                    "cluster": "%s",
                    "serviceName": "%s",
                    "taskDefinition": "review-td",
                    "desiredCount": 1,
                    "launchType": "FARGATE",
                    "loadBalancers": [
                        {
                            "targetGroupArn": "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/tg-DIFFERENT/9999",
                            "containerName": "app",
                            "containerPort": 8080
                        }
                    ]
                }
                """.formatted(REVIEW_CLUSTER, REVIEW_LB_SERVICE))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", containsString("InvalidParameterException"))
            .body("message", containsString("Creation of service was not idempotent."));
    }

    // ── PR Review: Cleanup ───────────────────────────────────────────────────

    @Test
    @Order(69)
    void reviewCleanup() {
        ecs("DeleteService")
            .body("""
                {
                    "cluster": "%s",
                    "service": "%s",
                    "force": true
                }
                """.formatted(REVIEW_CLUSTER, REVIEW_LB_SERVICE))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        ecs("DeleteCluster")
            .body("""
                {"cluster": "%s"}
                """.formatted(REVIEW_CLUSTER))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }
}
