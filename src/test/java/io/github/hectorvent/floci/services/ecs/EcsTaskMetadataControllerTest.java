package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.EphemeralStorage;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.dockerjava.api.model.Statistics;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The task metadata endpoint's response shapes. A workload reads these through
 * {@code ECS_CONTAINER_METADATA_URI_V4} expecting AWS's exact PascalCase members, so the mapping
 * from Floci's task model onto them is what this pins down.
 */
class EcsTaskMetadataControllerTest {

    private static final String METADATA_ID = "9e2b1f0c4d5e4a1b8c7d6e5f4a3b2c1d";
    private static final String TASK_ARN =
            "arn:aws:ecs:us-east-1:000000000000:task/metadata-cluster/abc123";

    private ObjectMapper objectMapper;
    private EcsService service;
    private EcsContainerManager containerManager;
    private Ec2Service ec2Service;
    private EcsTask task;
    private EcsTaskMetadataController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = mock(EcsService.class);
        containerManager = mock(EcsContainerManager.class);
        ec2Service = mock(Ec2Service.class);
        when(containerManager.sampleContainerStats(anyString())).thenReturn(Optional.empty());
        when(containerManager.sampleContainerStats(anyList())).thenReturn(Map.of());
        when(ec2Service.describeSubnets(anyString(), anyList(), anyMap())).thenReturn(List.of(subnet()));
        when(ec2Service.describeVpcs(anyString(), anyList(), anyMap())).thenReturn(List.of(vpc()));
        task = task();
        TaskDefinition taskDef = taskDefinition();
        when(service.findByMetadataId(anyString())).thenAnswer(inv ->
                METADATA_ID.equals(inv.getArgument(0))
                        ? Optional.of(new EcsService.MetadataTarget(task,
                                task.getContainers().getFirst(), taskDef))
                        : Optional.empty());
        controller = new EcsTaskMetadataController(service, containerManager, ec2Service, objectMapper);
    }

    private static Subnet subnet() {
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-default-us-east-1-a");
        subnet.setVpcId("vpc-0a1b2c3d");
        subnet.setCidrBlock("172.31.32.0/20");
        subnet.setAvailabilityZone("us-east-1a");
        return subnet;
    }

    private static Vpc vpc() {
        Vpc vpc = new Vpc();
        vpc.setVpcId("vpc-0a1b2c3d");
        vpc.setCidrBlock("172.31.0.0/16");
        return vpc;
    }

    private static EcsTask task() {
        EcsTask task = new EcsTask();
        task.setTaskArn(TASK_ARN);
        task.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/metadata-cluster");
        task.setTaskDefinitionArn("arn:aws:ecs:us-east-1:000000000000:task-definition/web:3");
        task.setLastStatus("RUNNING");
        task.setDesiredStatus("RUNNING");
        task.setLaunchType(LaunchType.FARGATE);
        task.setPlatformVersion("1.4.0");
        task.setPlatformFamily("Linux");
        task.setCpu("256");
        task.setMemory("512");
        task.setEphemeralStorage(new EphemeralStorage(20));
        task.setAvailabilityZone("us-east-1a");
        task.setCreatedAt(Instant.parse("2026-01-01T10:00:00Z"));
        task.setStartedAt(Instant.parse("2026-01-01T10:00:05Z"));
        task.setPullStartedAt(Instant.parse("2026-01-01T10:00:01Z"));
        task.setPullStoppedAt(Instant.parse("2026-01-01T10:00:04Z"));
        task.setPrivateIpAddress("172.31.0.42");
        task.setMacAddress("0e:98:9f:33:76:d3");
        task.setPrivateDnsName("ip-172-31-0-42.ec2.internal");

        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of("subnet-default-us-east-1-a"));
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpc);
        task.setNetworkConfiguration(networkConfiguration);

        Container container = new Container();
        container.setName("app");
        container.setImage("nginx:latest");
        container.setLastStatus("RUNNING");
        container.setDockerId("docker-id-1");
        container.setMetadataId(METADATA_ID);
        container.setContainerArn("arn:aws:ecs:us-east-1:000000000000:container/abc123/app");
        container.setCreatedAt(Instant.parse("2026-01-01T10:00:03Z"));
        container.setStartedAt(Instant.parse("2026-01-01T10:00:07Z"));
        task.setContainers(List.of(container));
        return task;
    }

    private static TaskDefinition taskDefinition() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("nginx:latest");
        app.setCpu(128);
        app.setMemory(256);
        app.setLogConfiguration(new LogConfiguration("awslogs",
                Map.of("awslogs-group", "/ecs/web"), null));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("web");
        taskDef.setRevision(3);
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(List.of(app));
        return taskDef;
    }

    private JsonNode body(Response response) {
        return objectMapper.valueToTree(response.getEntity());
    }

    @Test
    void containerMetadataReportsTheAwsMembers() {
        Response response = controller.container(METADATA_ID);
        assertEquals(200, response.getStatus());

        JsonNode container = body(response);
        assertEquals("docker-id-1", container.path("DockerId").asText());
        assertEquals("app", container.path("Name").asText());
        assertEquals("nginx:latest", container.path("Image").asText());
        assertEquals("RUNNING", container.path("KnownStatus").asText());
        assertEquals("NORMAL", container.path("Type").asText());
        assertEquals(128, container.path("Limits").path("CPU").asInt());
        assertEquals(256, container.path("Limits").path("Memory").asInt());
        // SubnetId is not a member of the v4 network object; only the documented ones are written.
        assertTrue(container.path("Networks").get(0).path("SubnetId").isMissingNode(),
                "the network object must carry only the members AWS documents");
        assertEquals("awslogs", container.path("LogDriver").asText());
        assertEquals("/ecs/web", container.path("LogOptions").path("awslogs-group").asText());
        assertEquals("web", container.path("Labels").path("com.amazonaws.ecs.task-definition-family").asText());
        assertEquals("3", container.path("Labels").path("com.amazonaws.ecs.task-definition-version").asText());
        assertEquals(TASK_ARN, container.path("Labels").path("com.amazonaws.ecs.task-arn").asText());
        assertEquals("awsvpc", container.path("Networks").get(0).path("NetworkMode").asText());
        assertEquals("172.31.0.42", container.path("Networks").get(0).path("IPv4Addresses").get(0).asText());
        assertEquals(0, container.path("Networks").get(0).path("AttachmentIndex").asInt());
        assertEquals("0e:98:9f:33:76:d3", container.path("Networks").get(0).path("MACAddress").asText());
        assertEquals("ip-172-31-0-42.ec2.internal",
                container.path("Networks").get(0).path("PrivateDNSName").asText());
        // A container's own clock, not the task's: they start one at a time behind dependsOn.
        assertEquals("2026-01-01T10:00:03Z", container.path("CreatedAt").asText());
        assertEquals("2026-01-01T10:00:07Z", container.path("StartedAt").asText());
    }

    @Test
    void aContainerWithoutItsOwnClockFallsBackToTheTasks() {
        Container container = task.getContainers().getFirst();
        container.setCreatedAt(null);
        container.setStartedAt(null);

        JsonNode node = body(controller.container(METADATA_ID));

        assertEquals("2026-01-01T10:00:00Z", node.path("CreatedAt").asText());
        assertEquals("2026-01-01T10:00:05Z", node.path("StartedAt").asText());
    }

    @Test
    void aStoppedContainerReportsWhenItFinished() {
        task.getContainers().getFirst().setFinishedAt(Instant.parse("2026-01-01T10:30:00Z"));

        assertEquals("2026-01-01T10:30:00Z",
                body(controller.container(METADATA_ID)).path("FinishedAt").asText());
    }

    /**
     * The subnet half of the network object. AWS derives the gateway from the subnet's own CIDR,
     * the resolver from the VPC's, and names us-east-1's search domain {@code ec2.internal}.
     */
    @Test
    void theNetworkObjectDescribesTheSubnetTheTaskSitsIn() {
        JsonNode network = body(controller.container(METADATA_ID)).path("Networks").get(0);

        assertEquals("172.31.32.0/20", network.path("IPv4SubnetCIDRBlock").asText());
        assertEquals("172.31.32.1/20", network.path("SubnetGatewayIpv4Address").asText());
        assertEquals("172.31.0.2", network.path("DomainNameServers").get(0).asText());
        assertEquals("ec2.internal", network.path("DomainNameSearchList").get(0).asText());
    }

    @Test
    void aTaskWithNoNetworkConfigurationReportsNoSubnetMembers() {
        task.setNetworkConfiguration(null);

        JsonNode network = body(controller.container(METADATA_ID)).path("Networks").get(0);

        assertTrue(network.path("AttachmentIndex").isMissingNode());
        assertTrue(network.path("IPv4SubnetCIDRBlock").isMissingNode());
        assertTrue(network.path("DomainNameServers").isMissingNode());
    }

    @Test
    void taskMetadataReportsTheTaskAndItsContainers() {
        Response response = controller.task(METADATA_ID);
        assertEquals(200, response.getStatus());

        JsonNode task = body(response);
        assertEquals(TASK_ARN, task.path("TaskARN").asText());
        assertEquals("web", task.path("Family").asText());
        assertEquals("3", task.path("Revision").asText());
        assertEquals("FARGATE", task.path("LaunchType").asText());
        assertEquals("us-east-1a", task.path("AvailabilityZone").asText());
        // The task's CPU limit is a vCPU count, not the CPU units the API takes.
        assertEquals(0.25, task.path("Limits").path("CPU").asDouble(), 0.0001);
        assertEquals(512, task.path("Limits").path("Memory").asInt());
        assertEquals("2026-01-01T10:00:01Z", task.path("PullStartedAt").asText());
        assertEquals(1, task.path("Containers").size());
        assertEquals("app", task.path("Containers").get(0).path("Name").asText());
        // Fargate reports the clock's accuracy and the task's ephemeral storage alongside it.
        assertEquals("SYNCHRONIZED", task.path("ClockDrift").path("ClockSynchronizationStatus").asText());
        assertTrue(task.path("ClockDrift").has("ReferenceTimestamp"));
        assertEquals(20 * 1024, task.path("EphemeralStorageMetrics").path("Reserved").asInt());
        // VPCID is the container instance's, which a Fargate task does not have.
        assertTrue(task.path("VPCID").isMissingNode());
    }

    @Test
    void anEc2TaskReportsTheVpcItsSubnetBelongsTo() {
        task.setLaunchType(LaunchType.EC2);

        assertEquals("vpc-0a1b2c3d", body(controller.task(METADATA_ID)).path("VPCID").asText());
    }

    @Test
    void aDeletedVpcOmitsResolverMetadataWithoutLosingTheSubnetDetails() {
        task.setLaunchType(LaunchType.EC2);
        when(ec2Service.describeVpcs(anyString(), anyList(), anyMap())).thenReturn(List.of());

        Response response = controller.task(METADATA_ID);
        assertEquals(200, response.getStatus());
        JsonNode metadata = body(response);
        assertEquals("vpc-0a1b2c3d", metadata.path("VPCID").asText());
        JsonNode network = metadata.path("Containers").get(0).path("Networks").get(0);
        assertFalse(network.has("DomainNameServers"));
        assertEquals("172.31.32.0/20", network.path("IPv4SubnetCIDRBlock").asText());
        assertEquals("172.31.32.1/20", network.path("SubnetGatewayIpv4Address").asText());
    }

    @Test
    void taskWithTagsAddsTheTaskAndContainerInstanceTags() {
        task.setLaunchType(LaunchType.EC2);
        task.setContainerInstanceArn(
                "arn:aws:ecs:us-east-1:000000000000:container-instance/metadata-cluster/ci-1");
        when(service.listTagsForResource(TASK_ARN)).thenReturn(Map.of("owner", "platform"));
        when(service.listTagsForResource(task.getContainerInstanceArn()))
                .thenReturn(Map.of("fleet", "spot"));

        JsonNode withTags = body(controller.taskWithTags(METADATA_ID));

        assertEquals(TASK_ARN, withTags.path("TaskARN").asText(), "the task document is carried whole");
        assertEquals("platform", withTags.path("TaskTags").path("owner").asText());
        assertEquals("spot", withTags.path("ContainerInstanceTags").path("fleet").asText());
        assertTrue(withTags.path("Errors").isMissingNode());
    }

    /** The container agent serves taskWithTags, and a Fargate task has no agent behind it. */
    @Test
    void taskWithTagsIsNotFoundForAFargateTask() {
        assertEquals(404, controller.taskWithTags(METADATA_ID).getStatus());
    }

    @Test
    void taskWithTagsReportsAFailedTagLookupUnderErrors() {
        task.setLaunchType(LaunchType.EC2);
        when(service.listTagsForResource(TASK_ARN))
                .thenThrow(new AwsException("InvalidParameterException", "Resource not found", 400));

        JsonNode withTags = body(controller.taskWithTags(METADATA_ID));

        assertTrue(withTags.path("TaskTags").isMissingNode());
        assertEquals("TaskTags", withTags.path("Errors").get(0).path("ErrorField").asText());
        assertEquals("InvalidParameterException",
                withTags.path("Errors").get(0).path("ErrorCode").asText());
        assertEquals(400, withTags.path("Errors").get(0).path("StatusCode").asInt());
    }

    @Test
    void aContainerWithoutLimitsReportsNoneRatherThanZeroes() {
        EcsService limitlessService = mock(EcsService.class);
        EcsTask limitlessTask = task();
        TaskDefinition taskDef = taskDefinition();
        taskDef.getContainerDefinitions().getFirst().setCpu(null);
        taskDef.getContainerDefinitions().getFirst().setMemory(null);
        when(limitlessService.findByMetadataId(anyString())).thenReturn(Optional.of(
                new EcsService.MetadataTarget(limitlessTask, limitlessTask.getContainers().getFirst(), taskDef)));

        JsonNode container = body(new EcsTaskMetadataController(limitlessService, containerManager,
                ec2Service, objectMapper).container(METADATA_ID));

        assertTrue(container.path("Limits").isMissingNode(),
                "Limits is omitted when the container definition sets none");
        assertTrue(container.path("ImageID").isMissingNode(),
                "ImageID is omitted until the image digest is known");
    }

    @Test
    void statsReportTheSampleDockerGaveUnderItsOwnMemberNames() throws JsonProcessingException {
        EcsContainerManager.ContainerStats stats1 =
                new EcsContainerManager.ContainerStats(sample(), 43.5, 215.25);
        when(containerManager.sampleContainerStats("docker-id-1")).thenReturn(Optional.of(stats1));
        when(containerManager.sampleContainerStats(List.of("docker-id-1")))
                .thenReturn(Map.of("docker-id-1", stats1));

        JsonNode stats = body(controller.containerStats(METADATA_ID));
        assertEquals("2026-01-01T10:05:00.000000000Z", stats.path("read").asText());
        assertEquals(42000000, stats.path("cpu_stats").path("cpu_usage").path("total_usage").asLong());
        assertEquals(1048576, stats.path("memory_stats").path("usage").asLong());
        assertEquals(84, stats.path("networks").path("eth0").path("rx_bytes").asLong());
        // The rates are the agent's, derived from consecutive samples; Docker reports only totals.
        assertEquals(43.5, stats.path("network_rate_stats").path("rx_bytes_per_sec").asDouble(), 0.0001);
        assertEquals(215.25, stats.path("network_rate_stats").path("tx_bytes_per_sec").asDouble(), 0.0001);
        // docker-java's model carries a field per API version; the ones this daemon left out are
        // dropped rather than written as nulls onto a document a client parses as Docker's.
        assertFalse(stats.has("network"), "a member the daemon did not send must not appear as null");

        JsonNode taskStats = body(controller.taskStats(METADATA_ID));
        assertEquals(1048576, taskStats.path("docker-id-1").path("memory_stats").path("usage").asLong(),
                "task stats are keyed by Docker id, each carrying that container's sample");
    }

    @Test
    void statsOmitTheRatesWhenOnlyOneSampleArrived() throws JsonProcessingException {
        when(containerManager.sampleContainerStats("docker-id-1")).thenReturn(Optional.of(
                new EcsContainerManager.ContainerStats(sample(), null, null)));

        JsonNode stats = body(controller.containerStats(METADATA_ID));

        assertEquals(1048576, stats.path("memory_stats").path("usage").asLong());
        assertTrue(stats.path("network_rate_stats").isMissingNode(),
                "a rate that could not be measured is left out rather than reported as zero");
    }

    @Test
    void statsAnswerWithAnEmptyDocumentWhenTheDaemonHasNoSample() {
        JsonNode stats = body(controller.containerStats(METADATA_ID));
        assertTrue(stats.isObject() && stats.isEmpty(), "an unsampled container reports {}");

        JsonNode taskStats = body(controller.taskStats(METADATA_ID));
        assertTrue(taskStats.path("docker-id-1").isObject() && taskStats.path("docker-id-1").isEmpty(),
                "every container in the task keeps an entry, empty rather than null");
    }

    /** A trimmed Docker stats response, read through docker-java's model the way the daemon's is. */
    private static Statistics sample() throws JsonProcessingException {
        String json = """
                {
                  "read": "2026-01-01T10:05:00.000000000Z",
                  "preread": "2026-01-01T10:04:59.000000000Z",
                  "num_procs": 3,
                  "cpu_stats": {"cpu_usage": {"total_usage": 42000000}, "system_cpu_usage": 90000000},
                  "precpu_stats": {"cpu_usage": {"total_usage": 41000000}, "system_cpu_usage": 89000000},
                  "memory_stats": {"usage": 1048576, "limit": 268435456},
                  "pids_stats": {"current": 3},
                  "networks": {"eth0": {"rx_bytes": 84, "tx_bytes": 120}}
                }
                """;
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build()
                .readValue(json, Statistics.class);
    }

    @Test
    void anUnknownIdIsNotFound() {
        Response response = controller.container("not-a-metadata-id");
        assertEquals(404, response.getStatus());
        assertTrue(body(response).path("error").asText().contains("Unable to get metadata"));
    }
}
