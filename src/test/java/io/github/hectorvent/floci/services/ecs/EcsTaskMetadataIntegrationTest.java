package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The task metadata endpoint over the wire. Floci serves it at the root, next to S3's
 * {@code /{bucket}/{key}} catch-all, so route registration and precedence are what these cover:
 * a miss would hand a workload S3's XML instead of the document it read the endpoint for. The
 * response shapes themselves are pinned down in {@link EcsTaskMetadataControllerTest}.
 */
@QuarkusTest
class EcsTaskMetadataIntegrationTest {

    private static final String METADATA_ID = "4c1b0a9e8d7f4a3b9c2d1e0f5a4b3c2d";
    private static final String DOCKER_ID = "0f9a8b7c6d5e";
    private static final String TASK_ARN =
            "arn:aws:ecs:us-east-1:000000000000:task/metadata-cluster/route123";

    @InjectMock
    EcsService ecsService;

    @InjectMock
    EcsContainerManager containerManager;

    private EcsTask task;

    @BeforeEach
    void resolveOneMetadataId() {
        task = task();
        when(ecsService.findByMetadataId(anyString())).thenAnswer(invocation ->
                METADATA_ID.equals(invocation.getArgument(0))
                        ? Optional.of(new EcsService.MetadataTarget(task,
                                task.getContainers().getFirst(), taskDefinition()))
                        : Optional.empty());
        when(containerManager.sampleContainerStats(anyString())).thenReturn(Optional.empty());
        when(containerManager.sampleContainerStats(anyList())).thenReturn(Map.of());
    }

    @Test
    void containerDocumentIsServedAtTheRoot() {
        given()
        .when()
            .get("/v4/" + METADATA_ID)
        .then()
            .statusCode(200)
            .contentType(containsString("json"))
            .body("DockerId", equalTo(DOCKER_ID))
            .body("Name", equalTo("app"))
            .body("Labels.'com.amazonaws.ecs.task-arn'", equalTo(TASK_ARN));
    }

    @Test
    void taskDocumentIsServedUnderTheContainerPath() {
        given()
        .when()
            .get("/v4/" + METADATA_ID + "/task")
        .then()
            .statusCode(200)
            .contentType(containsString("json"))
            .body("TaskARN", equalTo(TASK_ARN))
            .body("Family", equalTo("web"))
            .body("Revision", equalTo("3"))
            .body("Containers[0].Name", equalTo("app"));
    }

    @Test
    void taskWithTagsIsRoutedAlongsideTheTaskPath() {
        when(ecsService.listTagsForResource(TASK_ARN)).thenReturn(Map.of("owner", "platform"));
        // taskWithTags is the container agent's path, so only an EC2 task has it.
        task.setLaunchType(LaunchType.EC2);

        given()
        .when()
            .get("/v4/" + METADATA_ID + "/taskWithTags")
        .then()
            .statusCode(200)
            .contentType(containsString("json"))
            .body("TaskARN", equalTo(TASK_ARN))
            .body("TaskTags.owner", equalTo("platform"));
    }

    @Test
    void bothStatsPathsAreRoutedAndAnswerWithJson() {
        given()
        .when()
            .get("/v4/" + METADATA_ID + "/stats")
        .then()
            .statusCode(200)
            .contentType(containsString("json"))
            .body("$", anEmptyMap());

        given()
        .when()
            .get("/v4/" + METADATA_ID + "/task/stats")
        .then()
            .statusCode(200)
            .contentType(containsString("json"))
            .body(DOCKER_ID, anEmptyMap());
    }

    /**
     * An unknown id must reach the endpoint and get its JSON error, not fall through to S3 and
     * come back as {@code NoSuchBucket} XML for a bucket named {@code v4}.
     */
    @Test
    void anUnknownIdAnswersWithTheEndpointsOwnNotFound() {
        given()
        .when()
            .get("/v4/not-a-metadata-id")
        .then()
            .statusCode(404)
            .contentType(containsString("json"))
            .body("error", containsString("Unable to get metadata"));
    }

    private static EcsTask task() {
        EcsTask task = new EcsTask();
        task.setTaskArn(TASK_ARN);
        task.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/metadata-cluster");
        task.setTaskDefinitionArn("arn:aws:ecs:us-east-1:000000000000:task-definition/web:3");
        task.setLastStatus("RUNNING");
        task.setDesiredStatus("RUNNING");
        task.setLaunchType(LaunchType.FARGATE);
        task.setCpu("256");
        task.setMemory("512");

        Container container = new Container();
        container.setName("app");
        container.setImage("nginx:latest");
        container.setLastStatus("RUNNING");
        container.setDockerId(DOCKER_ID);
        container.setMetadataId(METADATA_ID);
        container.setContainerArn("arn:aws:ecs:us-east-1:000000000000:container/route123/app");
        task.setContainers(List.of(container));
        return task;
    }

    private static TaskDefinition taskDefinition() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("nginx:latest");

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("web");
        taskDef.setRevision(3);
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(List.of(app));
        return taskDef;
    }
}
