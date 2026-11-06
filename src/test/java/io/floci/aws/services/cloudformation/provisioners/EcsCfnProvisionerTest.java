package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.ecs.model.CapacityProviderStrategyItem;
import io.floci.aws.services.ecs.model.ClusterSetting;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.CreateServiceRequest;
import io.floci.aws.services.ecs.model.EcsCluster;
import io.floci.aws.services.ecs.model.EcsLoadBalancer;
import io.floci.aws.services.ecs.model.EcsServiceModel;
import io.floci.aws.services.ecs.model.HealthCheck;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.services.ecs.model.NetworkConfiguration;
import io.floci.aws.services.ecs.model.NetworkMode;
import io.floci.aws.services.ecs.model.RegisterTaskDefinitionRequest;
import io.floci.aws.services.ecs.model.RuntimePlatform;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.services.ecs.model.UpdateServiceRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The ECS core-type provisioner in isolation: one mocked service, no Quarkus boot. The
 * integration test in {@code CloudFormationIntegrationTest} covers the same three types end to
 * end, including the exact {@code Fn::GetAtt} keys.
 */
class EcsCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String CLUSTER_ARN = "arn:aws:ecs:us-east-1:000000000000:cluster/web";
    private static final String TASK_DEF_ARN = "arn:aws:ecs:us-east-1:000000000000:task-definition/web:3";
    private static final String SERVICE_ARN = "arn:aws:ecs:us-east-1:000000000000:service/web/front";
    private static final String REGISTRY_ARN = "arn:aws:servicediscovery:us-east-1:000000000000:service/srv-1";
    private static final List<Map<String, Object>> REGISTRIES =
            List.of(Map.of("registryArn", REGISTRY_ARN, "containerName", "app", "containerPort", 80));
    private static final Map<String, Object> DEPLOYMENT_CONFIGURATION = Map.of(
            "maximumPercent", 150,
            "minimumHealthyPercent", 50,
            "deploymentCircuitBreaker", Map.of("enable", true, "rollback", false, "resetOnHealthyTask", false,
                    "thresholdConfiguration", Map.of("type", "COUNT", "value", 3)),
            "alarms", Map.of("alarmNames", List.of("high-errors"), "enable", true, "rollback", false));
    private static final List<CapacityProviderStrategyItem> STRATEGY =
            List.of(new CapacityProviderStrategyItem("FARGATE", 2, 0));

    private final EcsService ecs = mock(EcsService.class);
    private final EcsCfnProvisioner provisioner = new EcsCfnProvisioner(ecs);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.omitNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        // resolveStringList delegates to the real engine method; for the literal arrays these
        // tests use it just walks the array and calls resolve(...) per element, stubbed above.
        when(engine.resolveStringList(any())).thenCallRealMethod();
        when(engine.resolveLiteralList(any())).thenAnswer(inv -> {
            List<String> values = new ArrayList<>();
            ((JsonNode) inv.getArgument(0)).forEach(value -> values.add(value.asText()));
            return values;
        });
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private EcsCluster cluster(String name) {
        EcsCluster c = new EcsCluster();
        c.setClusterName(name);
        c.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/" + name);
        return c;
    }

    private EcsServiceModel service(String name) {
        EcsServiceModel s = new EcsServiceModel();
        s.setServiceName(name);
        s.setServiceArn("arn:aws:ecs:us-east-1:000000000000:service/web/" + name);
        return s;
    }

    @Test
    void declaresTheThreeCoreTypes() {
        assertEquals(Set.of("AWS::ECS::Cluster", "AWS::ECS::TaskDefinition", "AWS::ECS::Service"),
                provisioner.resourceTypes());
    }

    @Test
    void clusterUsesTheDeclaredNameAndExposesArn() {
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(cluster("web"));
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");

        provisioner.provision(r, mapper.createObjectNode().put("ClusterName", "web"), ctx());

        assertEquals("web", r.getPhysicalId());
        assertEquals(CLUSTER_ARN, r.getAttributes().get("Arn"));
        assertEquals(Set.of("Arn"), r.getAttributes().keySet());
    }

    @Test
    void anUnnamedClusterGeneratesANameOnCreateAndKeepsItOnUpdate() {
        when(ecs.createCluster(anyString(), isNull(), anyList(), eq(REGION))).thenAnswer(inv -> cluster(inv.getArgument(0)));
        StackResource created = resource("AWS::ECS::Cluster", "Cluster");
        provisioner.provision(created, mapper.createObjectNode(), ctx());
        String generated = created.getPhysicalId();
        assertTrue(generated.startsWith("my-stack-Cluster-"), generated);

        StackResource updated = resource("AWS::ECS::Cluster", "Cluster");
        provisioner.provision(updated, mapper.createObjectNode(), ctx(generated));

        // createCluster is idempotent, so the update re-issues it under the same name instead of
        // minting a second cluster.
        assertEquals(generated, updated.getPhysicalId());
        verify(ecs, times(2)).createCluster(eq(generated), isNull(), anyList(), eq(REGION));
    }

    @Test
    void taskDefinitionRegistersTheParsedContainersAndExposesTheArn() {
        TaskDefinition td = new TaskDefinition();
        td.setTaskDefinitionArn(TASK_DEF_ARN);
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(td);

        ObjectNode props = mapper.createObjectNode()
                .put("Family", "web").put("NetworkMode", "awsvpc").put("Cpu", "256").put("Memory", "512")
                .put("TaskRoleArn", "arn:aws:iam::000000000000:role/task")
                .put("ExecutionRoleArn", "arn:aws:iam::000000000000:role/exec");
        props.putArray("RequiresCompatibilities").add("FARGATE");
        ObjectNode container = props.putArray("ContainerDefinitions").addObject()
                .put("Name", "app").put("Image", "nginx:1").put("Cpu", 128).put("Memory", 256);
        container.putArray("PortMappings").addObject().put("ContainerPort", 80).put("HostPort", 8080);
        container.putArray("Environment").addObject().put("Name", "MODE").put("Value", "prod");
        container.putArray("Secrets").addObject().put("Name", "DB").put("ValueFrom", "arn:aws:secretsmanager:x");
        container.putArray("Command").add("run").add("--fast");
        ObjectNode log = container.putObject("LogConfiguration");
        log.put("LogDriver", "awsfirelens");
        log.putObject("Options").put("Name", "s3").put("bucket", "logs");
        container.putObject("FirelensConfiguration").put("Type", "fluentbit");

        StackResource r = resource("AWS::ECS::TaskDefinition", "TaskDef");
        provisioner.provision(r, props, ctx());

        assertEquals(TASK_DEF_ARN, r.getPhysicalId());
        assertEquals(TASK_DEF_ARN, r.getAttributes().get("TaskDefinitionArn"));
        RegisterTaskDefinitionRequest request = registered();
        assertEquals("web", request.getFamily());
        assertEquals(NetworkMode.awsvpc, request.getNetworkMode());
        assertEquals("256", request.getCpu());
        assertEquals("512", request.getMemory());
        assertEquals("arn:aws:iam::000000000000:role/task", request.getTaskRoleArn());
        assertEquals("arn:aws:iam::000000000000:role/exec", request.getExecutionRoleArn());
        assertEquals(List.of("FARGATE"), request.getRequiresCompatibilities());
        assertNull(request.getRuntimePlatform());
        ContainerDefinition def = request.getContainerDefinitions().get(0);
        assertEquals("app", def.getName());
        assertEquals("nginx:1", def.getImage());
        assertEquals(128, def.getCpu());
        assertEquals(256, def.getMemory());
        assertEquals(80, def.getPortMappings().get(0).containerPort());
        assertEquals(8080, def.getPortMappings().get(0).hostPort());
        assertEquals("tcp", def.getPortMappings().get(0).protocol());
        assertEquals("prod", def.getEnvironment().get(0).value());
        assertEquals("arn:aws:secretsmanager:x", def.getSecrets().get(0).valueFrom());
        assertEquals(List.of("run", "--fast"), def.getCommand());
        assertEquals("awsfirelens", def.getLogConfiguration().logDriver());
        assertEquals("s3", def.getLogConfiguration().options().get("Name"));
        assertEquals("logs", def.getLogConfiguration().options().get("bucket"));
        assertEquals("fluentbit", def.getFirelensConfiguration().type());
    }

    /**
     * Measured (eu-west-1, 2026-10-19): the ECS handler converts text to the integers and booleans
     * its model declares, and a value it cannot convert fails the resource with its model
     * validation message before anything is registered.
     */
    @Test
    void taskDefinitionValuesAreReadAsTheEcsHandlerConvertsThem() {
        TaskDefinition td = new TaskDefinition();
        td.setTaskDefinitionArn(TASK_DEF_ARN);
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(td);
        ObjectNode props = mapper.createObjectNode().put("Family", "web");
        props.putArray("ContainerDefinitions").addObject()
                .put("Name", "app").put("Image", "nginx:1").put("Cpu", " 64").put("Memory", "+128")
                .put("Essential", "False");

        provisioner.provision(resource("AWS::ECS::TaskDefinition", "TaskDef"), props, ctx());

        RegisterTaskDefinitionRequest request = registered();
        assertEquals("web", request.getFamily());
        assertNull(request.getNetworkMode());
        assertNull(request.getCpu());
        ContainerDefinition def = request.getContainerDefinitions().get(0);
        assertEquals(64, def.getCpu());
        assertEquals(128, def.getMemory());
        assertFalse(def.isEssential());

        ((ObjectNode) props.get("ContainerDefinitions").get(0)).put("Memory", "abc");
        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::ECS::TaskDefinition", "TaskDef"), props, ctx()));
        assertEquals("Model validation failed (#/ContainerDefinitions/0/Memory: expected type: Integer, found: String."
                + " This may occur when a numeric value exceeds 32-bit integer max (2147483647). Consider using"
                + " 'number' type or ensure value fits in integer range.\n#/ContainerDefinitions/0/Cpu: expected"
                + " type: Integer, found: String. This may occur when a numeric value exceeds 32-bit integer max"
                + " (2147483647). Consider using 'number' type or ensure value fits in integer range.\n"
                + "#/ContainerDefinitions/0/Essential: expected type: Boolean, found: String)", e.getMessage());
        verifyNoMoreInteractions(ecs);
    }

    @Test
    void containerLinuxParametersReachTheTaskDefinitionInTheApiShape() {
        TaskDefinition td = new TaskDefinition();
        td.setTaskDefinitionArn(TASK_DEF_ARN);
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(td);

        ObjectNode props = mapper.createObjectNode().put("Family", "web");
        ObjectNode container = props.putArray("ContainerDefinitions").addObject()
                .put("Name", "app").put("Image", "nginx:1");
        ObjectNode linux = container.putObject("LinuxParameters")
                .put("InitProcessEnabled", true).put("MaxSwap", 512).put("SharedMemorySize", 256)
                .put("Swappiness", 10);
        ObjectNode capabilities = linux.putObject("Capabilities");
        capabilities.putArray("Add").add("SYS_PTRACE");
        capabilities.putArray("Drop").add("NET_RAW").add("MKNOD");
        ObjectNode device = linux.putArray("Devices").addObject()
                .put("HostPath", "/dev/fuse").put("ContainerPath", "/dev/fuse");
        device.putArray("Permissions").add("read").add("write");
        ObjectNode tmpfs = linux.putArray("Tmpfs").addObject().put("ContainerPath", "/scratch").put("Size", 64);
        tmpfs.putArray("MountOptions").add("noexec").add("nosuid");

        provisioner.provision(resource("AWS::ECS::TaskDefinition", "TaskDef"), props, ctx());

        assertEquals(Map.of("linuxParameters", Map.of(
                        "capabilities", Map.of("add", List.of("SYS_PTRACE"), "drop", List.of("NET_RAW", "MKNOD")),
                        "devices", List.of(Map.of("hostPath", "/dev/fuse", "containerPath", "/dev/fuse",
                                "permissions", List.of("read", "write"))),
                        "initProcessEnabled", true,
                        "maxSwap", 512,
                        "sharedMemorySize", 256,
                        "swappiness", 10,
                        "tmpfs", List.of(Map.of("containerPath", "/scratch", "mountOptions", List.of("noexec", "nosuid"),
                                "size", 64)))),
                registeredContainer().getUnparsed());
    }

    /** Text a parameter resolved to is converted as the ECS handler converts it (measured). */
    @Test
    void linuxParametersGivenAsTextAreReadAsTheEcsHandlerConvertsThem() {
        TaskDefinition td = new TaskDefinition();
        td.setTaskDefinitionArn(TASK_DEF_ARN);
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(td);

        ObjectNode props = mapper.createObjectNode().put("Family", "web");
        props.putArray("ContainerDefinitions").addObject().put("Name", "app").put("Image", "nginx:1")
                .putObject("LinuxParameters").put("InitProcessEnabled", "TRUE").put("SharedMemorySize", "64");

        provisioner.provision(resource("AWS::ECS::TaskDefinition", "TaskDef"), props, ctx());

        assertEquals(Map.of("linuxParameters", Map.of("initProcessEnabled", true, "sharedMemorySize", 64,
                        "devices", List.of(), "tmpfs", List.of())),
                registeredContainer().getUnparsed());
    }

    @Test
    void aContainerWithoutLinuxParametersCarriesNone() {
        TaskDefinition td = new TaskDefinition();
        td.setTaskDefinitionArn(TASK_DEF_ARN);
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(td);

        ObjectNode props = mapper.createObjectNode().put("Family", "web");
        props.putArray("ContainerDefinitions").addObject().put("Name", "app").put("Image", "nginx:1");
        provisioner.provision(resource("AWS::ECS::TaskDefinition", "TaskDef"), props, ctx());

        assertNull(registeredContainer().getUnparsed());
    }

    private RegisterTaskDefinitionRequest registered() {
        ArgumentCaptor<RegisterTaskDefinitionRequest> request = ArgumentCaptor.forClass(RegisterTaskDefinitionRequest.class);
        verify(ecs).registerTaskDefinition(request.capture(), eq(REGION));
        return request.getValue();
    }

    /** A CreateService request for {@code service} in {@code cluster} that declares TASK_DEF_ARN and nothing else. */
    private static CreateServiceRequest creating(String cluster, String service) {
        return argThat(request -> request != null && cluster.equals(request.getCluster())
                && service.equals(request.getServiceName()) && TASK_DEF_ARN.equals(request.getTaskDefinition())
                && request.getDesiredCount() == 1 && request.getLaunchType() == null
                && request.getNetworkConfiguration() == null);
    }

    /** An UpdateService request for {@code service} in {@code cluster} with this task definition and desired count. */
    private static UpdateServiceRequest updating(String cluster, String service, String taskDefinition,
                                                 int desiredCount) {
        return argThat(request -> request != null && cluster.equals(request.getCluster())
                && service.equals(request.getService()) && taskDefinition.equals(request.getTaskDefinition())
                && Integer.valueOf(desiredCount).equals(request.getDesiredCount())
                && request.getNetworkConfiguration() == null);
    }

    private ContainerDefinition registeredContainer() {
        return registered().getContainerDefinitions().get(0);
    }

    @Test
    void taskDefinitionWithoutFamilyGeneratesOneAndIgnoresAnUnknownNetworkMode() {
        TaskDefinition td = new TaskDefinition();
        td.setTaskDefinitionArn(TASK_DEF_ARN);
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(td);

        StackResource r = resource("AWS::ECS::TaskDefinition", "TaskDef");
        provisioner.provision(r, mapper.createObjectNode().put("NetworkMode", "not-a-mode"), ctx());

        RegisterTaskDefinitionRequest request = registered();
        assertTrue(request.getFamily().startsWith("my-stack-TaskDef-"), request.getFamily());
        assertNull(request.getNetworkMode());
    }

    @Test
    void serviceIsCreatedWithTheParsedLoadBalancersAndNetwork() {
        when(ecs.createService(any(CreateServiceRequest.class), eq(REGION))).thenReturn(service("front"));
        ObjectNode props = mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "2").put("LaunchType", "FARGATE");
        props.putArray("LoadBalancers").addObject()
                .put("TargetGroupArn", "arn:aws:elasticloadbalancing:tg").put("ContainerName", "app").put("ContainerPort", 80);
        ObjectNode awsvpc = props.putObject("NetworkConfiguration").putObject("AwsvpcConfiguration");
        awsvpc.putArray("Subnets").add("subnet-1").add("subnet-2");
        awsvpc.putArray("SecurityGroups").add("sg-1");
        awsvpc.put("AssignPublicIp", "ENABLED");

        StackResource r = resource("AWS::ECS::Service", "Service");
        provisioner.provision(r, props, ctx());

        assertEquals(SERVICE_ARN, r.getPhysicalId());
        assertEquals("front", r.getAttributes().get("Name"));
        assertEquals(SERVICE_ARN, r.getAttributes().get("ServiceArn"));
        assertEquals(Set.of("Name", "ServiceArn"), r.getAttributes().keySet());
        ArgumentCaptor<CreateServiceRequest> request = ArgumentCaptor.forClass(CreateServiceRequest.class);
        verify(ecs).createService(request.capture(), eq(REGION));
        assertEquals("web", request.getValue().getCluster());
        assertEquals("front", request.getValue().getServiceName());
        assertEquals(TASK_DEF_ARN, request.getValue().getTaskDefinition());
        assertEquals(2, request.getValue().getDesiredCount());
        assertEquals(LaunchType.FARGATE, request.getValue().getLaunchType());
        assertNull(request.getValue().getServiceRegistries());
        assertNull(request.getValue().getDeploymentConfiguration());
        assertNull(request.getValue().getCapacityProviderStrategy());
        List<EcsLoadBalancer> lbs = request.getValue().getLoadBalancers();
        assertEquals("app", lbs.get(0).getContainerName());
        assertEquals(80, lbs.get(0).getContainerPort());
        NetworkConfiguration net = request.getValue().getNetworkConfiguration();
        assertEquals(List.of("subnet-1", "subnet-2"), net.getAwsvpcConfiguration().getSubnets());
        assertEquals("ENABLED", net.getAwsvpcConfiguration().getAssignPublicIp());
    }

    @Test
    void anUnchangedServiceIsUpdatedInPlaceOnTheSecondPass() {
        when(ecs.updateService(updating("web", "front", TASK_DEF_ARN, 3), eq(REGION)))
                .thenReturn(service("front"));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");
        ObjectNode props = mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3");

        provisioner.provision(r, props, ctx(SERVICE_ARN));

        assertEquals(SERVICE_ARN, r.getPhysicalId());
        verify(ecs).updateService(updating("web", "front", TASK_DEF_ARN, 3), eq(REGION));
        verify(ecs, never()).createService(any(CreateServiceRequest.class), anyString());
    }

    @Test
    void anUnnamedServiceKeepsTheNameItGotAtCreateTime() {
        when(ecs.updateService(updating("web", "my-stack-Service-abc", TASK_DEF_ARN, 1), eq(REGION)))
                .thenReturn(service("my-stack-Service-abc"));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "my-stack-Service-abc");

        provisioner.provision(r, mapper.createObjectNode().put("Cluster", "web").put("TaskDefinition", TASK_DEF_ARN),
                ctx("arn:aws:ecs:us-east-1:000000000000:service/web/my-stack-Service-abc"));

        verify(ecs).updateService(updating("web", "my-stack-Service-abc", TASK_DEF_ARN, 1), eq(REGION));
    }

    @Test
    void aRenamedServiceIsCreatedAsAReplacement() {
        when(ecs.createService(creating("web", "front-v2"), eq(REGION))).thenReturn(service("front-v2"));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front-v2").put("TaskDefinition", TASK_DEF_ARN),
                ctx(SERVICE_ARN));

        assertNotEquals(SERVICE_ARN, r.getPhysicalId());
        assertEquals("front-v2", r.getAttributes().get("Name"));
        verify(ecs, never()).updateService(any(UpdateServiceRequest.class), anyString());
    }

    @Test
    void aServiceMovedToAnotherClusterIsCreatedAsAReplacementAndTheOldOneCleanedUpAfterCommit() {
        EcsServiceModel moved = service("front");
        moved.setServiceArn("arn:aws:ecs:us-east-1:000000000000:service/batch/front");
        when(ecs.createService(creating("batch", "front"), eq(REGION))).thenReturn(moved);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "batch").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN), ctx(SERVICE_ARN));

        // Cluster is create-only: same name, other cluster is a replacement, never an in-place update
        // against a service the new cluster may not have (or may have under another owner).
        verify(ecs, never()).updateService(any(UpdateServiceRequest.class), anyString());
        assertEquals(moved.getServiceArn(), r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(SERVICE_ARN, provisioner.updateCleanupPhysicalId(r));

        UpdateCleanupResult cleanup = provisioner.completeUpdate(r);

        InOrder order = inOrder(ecs);
        order.verify(ecs).createService(creating("batch", "front"), eq(REGION));
        order.verify(ecs).deleteService("web", "front", true, REGION);
        assertEquals(new UpdateCleanupResult(true, true, SERVICE_ARN, 0, null), cleanup);
        provisioner.clearUpdate(r);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void theClusterGivenAsAnArnStillCountsAsTheSameCluster() {
        when(ecs.updateService(updating(CLUSTER_ARN, "front", TASK_DEF_ARN, 1), eq(REGION)))
                .thenReturn(service("front"));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", CLUSTER_ARN).put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN), ctx(SERVICE_ARN));

        verify(ecs).updateService(updating(CLUSTER_ARN, "front", TASK_DEF_ARN, 1), eq(REGION));
        assertFalse(provisioner.hasReplacementUpdate(r), "an in-place update owes no cleanup");
    }

    @Test
    void aCreatedServiceIsAwaitedUntilStableBeforeTheResourceCompletes() {
        EcsServiceModel created = service("front");
        when(ecs.createService(creating("web", "front"), eq(REGION))).thenReturn(created);
        StackResource r = resource("AWS::ECS::Service", "Service");

        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN), ctx());

        InOrder order = inOrder(ecs);
        order.verify(ecs).createService(creating("web", "front"), eq(REGION));
        order.verify(ecs).awaitServiceStable(created);
        assertEquals(Set.of("Name", "ServiceArn"), r.getAttributes().keySet(),
                "a stable service is no longer marked for the create rollback");
    }

    @Test
    void anInPlaceUpdateIsAwaitedUntilStableBeforeTheResourceCompletes() {
        EcsServiceModel updated = service("front");
        when(ecs.updateService(updating("web", "front", TASK_DEF_ARN, 3), eq(REGION)))
                .thenReturn(updated);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3"), ctx(SERVICE_ARN));

        InOrder order = inOrder(ecs);
        order.verify(ecs).updateService(updating("web", "front", TASK_DEF_ARN, 3), eq(REGION));
        order.verify(ecs).awaitServiceStable(updated);
    }

    @Test
    void aCreatedServiceThatDoesNotStabilizeFailsAndStaysOwnedForTheCreateRollback() {
        EcsServiceModel created = service("front");
        when(ecs.createService(creating("web", "front"), eq(REGION))).thenReturn(created);
        doThrow(new AwsException("NotStabilized", "Service " + SERVICE_ARN + " did not stabilize.", 500))
                .when(ecs).awaitServiceStable(created);
        StackResource r = resource("AWS::ECS::Service", "Service");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN), ctx()));

        assertEquals("Service " + SERVICE_ARN + " did not stabilize.", e.getMessage());
        // The stack's create rollback deletes a CREATE_FAILED resource only when it is marked owned.
        assertEquals(SERVICE_ARN, r.getPhysicalId());
        assertEquals("true", r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        verify(ecs, never()).deleteService(anyString(), anyString(), eq(true), anyString());
    }

    @Test
    void aReplacementThatDoesNotStabilizeIsDeletedBeforeTheUpdateFails() {
        EcsServiceModel replacement = service("front-v2");
        when(ecs.createService(creating("web", "front-v2"), eq(REGION))).thenReturn(replacement);
        doThrow(new AwsException("NotStabilized", "did not stabilize", 500))
                .when(ecs).awaitServiceStable(replacement);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        assertThrows(AwsException.class, () -> provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front-v2").put("TaskDefinition", TASK_DEF_ARN),
                ctx(SERVICE_ARN)));

        // The engine restores the prior resource on a failed update and never learns the new ARN,
        // so the replacement is removed here; the prior service is left alone.
        verify(ecs).deleteService("web", "front-v2", true, REGION);
        verify(ecs, never()).deleteService("web", "front", true, REGION);
        assertNull(r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void aReplacementThatCannotBeDeletedEitherReportsTheRollbackFailure() {
        EcsServiceModel replacement = service("front-v2");
        when(ecs.createService(creating("web", "front-v2"), eq(REGION))).thenReturn(replacement);
        AwsException notStable = new AwsException("NotStabilized", "did not stabilize", 500);
        doThrow(notStable).when(ecs).awaitServiceStable(replacement);
        doThrow(new AwsException("ServerException", "busy", 500))
                .when(ecs).deleteService("web", "front-v2", true, REGION);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front-v2").put("TaskDefinition", TASK_DEF_ARN),
                ctx(SERVICE_ARN)));

        assertEquals(notStable, e, "the stabilization failure is what fails the update");
        assertEquals("busy", e.getSuppressed()[0].getMessage());
        assertTrue(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR)
                .contains(replacement.getServiceArn()));
    }

    @Test
    void anInPlaceUpdateWhoseRestoreFailsReportsTheRollbackFailure() {
        EcsServiceModel prior = service("front");
        prior.setTaskDefinition(TASK_DEF_ARN);
        prior.setDesiredCount(1);
        when(ecs.serviceByArn(SERVICE_ARN)).thenReturn(prior);
        EcsServiceModel updated = service("front");
        when(ecs.updateService(updating("web", "front", TASK_DEF_ARN, 3), eq(REGION)))
                .thenReturn(updated);
        AwsException notStable = new AwsException("NotStabilized", "did not stabilize", 500);
        doThrow(notStable).when(ecs).awaitServiceStable(updated);
        when(ecs.updateService(updating("web", "front", TASK_DEF_ARN, 1), eq(REGION)))
                .thenThrow(new AwsException("ServerException", "busy", 500));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3"), ctx(SERVICE_ARN)));

        assertEquals(notStable, e, "the stabilization failure is what fails the update");
        assertEquals("busy", e.getSuppressed()[0].getMessage());
        verify(ecs).updateService(updating("web", "front", TASK_DEF_ARN, 1), eq(REGION));
        assertTrue(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR).contains(SERVICE_ARN));
    }

    @Test
    void anInPlaceUpdateThatDoesNotStabilizeAwaitsTheRestoredConfiguration() {
        EcsServiceModel prior = service("front");
        prior.setTaskDefinition(TASK_DEF_ARN);
        prior.setDesiredCount(1);
        when(ecs.serviceByArn(SERVICE_ARN)).thenReturn(prior);
        EcsServiceModel updated = service("front");
        when(ecs.updateService(updating("web", "front", TASK_DEF_ARN, 3), eq(REGION))).thenReturn(updated);
        doThrow(new AwsException("NotStabilized", "did not stabilize", 500)).when(ecs).awaitServiceStable(updated);
        EcsServiceModel restored = service("front");
        when(ecs.updateService(updating("web", "front", TASK_DEF_ARN, 1), eq(REGION))).thenReturn(restored);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        assertThrows(AwsException.class, () -> provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3"), ctx(SERVICE_ARN)));

        InOrder order = inOrder(ecs);
        order.verify(ecs).updateService(updating("web", "front", TASK_DEF_ARN, 1), eq(REGION));
        order.verify(ecs).awaitServiceStable(restored);
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void anInPlaceUpdateWhoseRestoreDoesNotStabilizeReportsTheRollbackFailure() {
        EcsServiceModel prior = service("front");
        prior.setTaskDefinition(TASK_DEF_ARN);
        prior.setDesiredCount(1);
        when(ecs.serviceByArn(SERVICE_ARN)).thenReturn(prior);
        EcsServiceModel updated = service("front");
        when(ecs.updateService(updating("web", "front", TASK_DEF_ARN, 3), eq(REGION))).thenReturn(updated);
        AwsException notStable = new AwsException("NotStabilized", "did not stabilize", 500);
        doThrow(notStable).when(ecs).awaitServiceStable(updated);
        EcsServiceModel restored = service("front");
        when(ecs.updateService(updating("web", "front", TASK_DEF_ARN, 1), eq(REGION))).thenReturn(restored);
        doThrow(new AwsException("NotStabilized", "restore did not stabilize", 500))
                .when(ecs).awaitServiceStable(restored);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3"), ctx(SERVICE_ARN)));

        assertEquals(notStable, e, "the stabilization failure is what fails the update");
        assertEquals("restore did not stabilize", e.getSuppressed()[0].getMessage());
        assertTrue(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR).contains(SERVICE_ARN));
    }

    @Test
    void aTaskDefinitionUpdateDeregistersTheDisplacedRevisionAfterCommit() {
        TaskDefinition next = new TaskDefinition();
        next.setTaskDefinitionArn(TASK_DEF_ARN.replace(":3", ":4"));
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(next);
        StackResource r = resource("AWS::ECS::TaskDefinition", "TaskDef");

        provisioner.provision(r, mapper.createObjectNode().put("Family", "web"), ctx(TASK_DEF_ARN));

        assertEquals(next.getTaskDefinitionArn(), r.getPhysicalId());
        assertEquals(TASK_DEF_ARN, provisioner.updateCleanupPhysicalId(r));
        assertEquals(new UpdateCleanupResult(true, true, TASK_DEF_ARN, 0, null), provisioner.completeUpdate(r));
        verify(ecs).deregisterTaskDefinition(TASK_DEF_ARN, REGION);
    }

    @Test
    void aRetainedDisplacedEntityIsNotDeletedAndAFailingDeleteIsRetriedThreeTimes() {
        TaskDefinition next = new TaskDefinition();
        next.setTaskDefinitionArn(TASK_DEF_ARN.replace(":3", ":4"));
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(next);

        StackResource retained = resource("AWS::ECS::TaskDefinition", "TaskDef");
        retained.setUpdateReplacePolicy("Retain");
        provisioner.provision(retained, mapper.createObjectNode().put("Family", "web"), ctx(TASK_DEF_ARN));
        assertNull(provisioner.updateCleanupPhysicalId(retained));
        assertEquals(new UpdateCleanupResult(true, true, TASK_DEF_ARN, 0, null), provisioner.completeUpdate(retained));
        verify(ecs, never()).deregisterTaskDefinition(anyString(), anyString());

        StackResource failing = resource("AWS::ECS::TaskDefinition", "TaskDef");
        provisioner.provision(failing, mapper.createObjectNode().put("Family", "web"), ctx(TASK_DEF_ARN));
        doThrow(new AwsException("ServerException", "busy", 500)).when(ecs).deregisterTaskDefinition(TASK_DEF_ARN, REGION);
        UpdateCleanupResult first = provisioner.completeUpdate(failing);
        assertFalse(first.complete());
        assertEquals(1, first.attempts());
        provisioner.completeUpdate(failing);
        UpdateCleanupResult third = provisioner.completeUpdate(failing);
        assertEquals(3, third.attempts());
        assertEquals("busy", third.failureReason());
        UpdateCleanupResult givenUp = provisioner.completeUpdate(failing);
        assertEquals(3, givenUp.attempts(), "no fourth attempt");
        verify(ecs, Mockito.times(3)).deregisterTaskDefinition(TASK_DEF_ARN, REGION);
    }

    @Test
    void aFailedStackUpdateRollsAReplacementBackToThePriorServiceAndDeletesTheReplacement() {
        EcsServiceModel moved = service("front");
        moved.setServiceArn("arn:aws:ecs:us-east-1:000000000000:service/batch/front");
        when(ecs.createService(creating("batch", "front"), eq(REGION))).thenReturn(moved);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");
        r.getAttributes().put("ServiceArn", SERVICE_ARN);
        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "batch").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN), ctx(SERVICE_ARN));
        assertEquals(moved.getServiceArn(), r.getAttributes().get("ServiceArn"));

        assertTrue(provisioner.rollbackUpdate(r));

        // The resource names the prior service again, with the attributes it had, the replacement
        // is deleted in its cluster, the prior is never touched, and the cleanup record is spent.
        assertEquals(SERVICE_ARN, r.getPhysicalId());
        assertEquals(SERVICE_ARN, r.getAttributes().get("ServiceArn"));
        assertEquals("front", r.getAttributes().get("Name"));
        verify(ecs).deleteService("batch", "front", true, REGION);
        verify(ecs, never()).deleteService("web", "front", true, REGION);
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertEquals(UpdateCleanupResult.notApplicable(), provisioner.completeUpdate(r));
    }

    @Test
    void anUnchangedClusterHasNothingToRollBack() {
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(cluster("web"));
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");
        provisioner.provision(r, mapper.createObjectNode().put("ClusterName", "web"), ctx("web"));

        // The idempotent create changed nothing, so the rollback is complete with nothing to do.
        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals("web", r.getPhysicalId());
        verify(ecs, never()).deleteCluster(anyString(), anyString());
    }

    @Test
    void clusterSettingsAreAppliedOnCreate() {
        List<ClusterSetting> enabled = List.of(new ClusterSetting("containerInsights", "enabled"));
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenAnswer(inv -> created(inv));
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");

        provisioner.provision(r, clusterWithInsights("enabled"), ctx());

        // In the create itself, so no later call can fail and leave a cluster the rollback misses.
        verify(ecs).createCluster("web", null, enabled, REGION);
        verify(ecs, never()).updateClusterSettings(anyString(), any(), anyString());
        assertEquals(Set.of("Arn"), r.getAttributes().keySet());
    }

    @Test
    void aRenamedClusterTakesItsSettingsAtCreation() {
        List<ClusterSetting> enabled = List.of(new ClusterSetting("containerInsights", "enabled"));
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenAnswer(inv -> created(inv));
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");

        provisioner.provision(r, clusterWithInsights("enabled"), ctx("old"));

        verify(ecs).createCluster("web", null, enabled, REGION);
        verify(ecs, never()).updateClusterSettings(anyString(), any(), anyString());
        assertFalse(r.getAttributes().containsKey(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR));
    }

    @Test
    void aClusterThatAlreadyExistedGetsItsSettingsBackAndIsNotDeleted() {
        // The idempotent create hands back a cluster the stack did not create, without the settings.
        List<ClusterSetting> priorSettings = List.of(new ClusterSetting("containerInsights", "disabled"));
        EcsCluster existing = cluster("web");
        existing.setSettings(priorSettings);
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(existing);
        AwsException failure = new AwsException("ServerException", "write failed", 500);
        when(ecs.updateClusterSettings("web", List.of(new ClusterSetting("containerInsights", "enabled")), REGION))
                .thenThrow(failure);
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(r, clusterWithInsights("enabled"), ctx()));

        assertSame(failure, thrown);
        // The failed call may have changed them, and nothing else puts back a cluster the stack found.
        verify(ecs).updateClusterSettings("web", priorSettings, REGION);
        // Without an id, neither a create rollback nor an update that added this resource deletes it.
        assertNull(r.getPhysicalId());
        verify(ecs, never()).deleteCluster(anyString(), anyString());
        assertFalse(r.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertFalse(r.getAttributes().containsKey(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR));
    }

    private EcsCluster created(InvocationOnMock inv) {
        EcsCluster c = cluster(inv.getArgument(0));
        List<ClusterSetting> settings = inv.getArgument(2);
        c.setSettings(settings.isEmpty() ? null : settings);
        return c;
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "disabled")
    void aFailedStackUpdateRestoresThePriorClusterSettings(String priorValue) {
        List<ClusterSetting> priorSettings = priorValue == null ? null : List.of(new ClusterSetting("containerInsights", priorValue));
        EcsCluster prior = cluster("web");
        prior.setSettings(priorSettings);
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(prior);
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");

        provisioner.provision(r, clusterWithInsights("enhanced"), ctx("web"));
        verify(ecs).updateClusterSettings("web", List.of(new ClusterSetting("containerInsights", "enhanced")), REGION);

        assertTrue(provisioner.rollbackUpdate(r));
        verify(ecs).updateClusterSettings("web", priorSettings, REGION);
        assertFalse(r.getAttributes().containsKey(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR));
    }

    @Test
    void aSettingsUpdateThatFailsPutsThePriorSettingsBackItself() {
        List<ClusterSetting> priorSettings = List.of(new ClusterSetting("containerInsights", "disabled"));
        EcsCluster prior = cluster("web");
        prior.setSettings(priorSettings);
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(prior);
        AwsException failure = new AwsException("ServerException", "write failed", 500);
        when(ecs.updateClusterSettings("web", List.of(new ClusterSetting("containerInsights", "enhanced")), REGION))
                .thenThrow(failure);
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");
        // An update hands the provisioner the resource with its prior physical id.
        r.setPhysicalId("web");

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(r, clusterWithInsights("enhanced"), ctx("web")));

        // The engine restores the resource it held before this attempt, which never carried the
        // snapshot, so the rollback walker would not put these settings back.
        assertSame(failure, thrown);
        verify(ecs).updateClusterSettings("web", priorSettings, REGION);
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        assertFalse(r.getAttributes().containsKey(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR));
    }

    @Test
    void aSettingsRestoreThatAlsoFailsIsRecordedAsARollbackFailure() {
        EcsCluster prior = cluster("web");
        prior.setSettings(List.of(new ClusterSetting("containerInsights", "disabled")));
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(prior);
        AwsException failure = new AwsException("ServerException", "write failed", 500);
        AwsException restoreFailure = new AwsException("ServerException", "still failing", 500);
        when(ecs.updateClusterSettings(eq("web"), any(), eq(REGION))).thenThrow(failure, restoreFailure);
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");
        // An update hands the provisioner the resource with its prior physical id.
        r.setPhysicalId("web");

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(r, clusterWithInsights("enhanced"), ctx("web")));

        assertSame(failure, thrown);
        assertEquals(List.of(restoreFailure), List.of(thrown.getSuppressed()));
        assertEquals("Could not restore the settings of ECS cluster web: still failing",
                r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void aRestoreThatFailedIsRetriedWhenTheRollbackIsContinued() {
        List<ClusterSetting> priorSettings = List.of(new ClusterSetting("containerInsights", "disabled"));
        EcsCluster prior = cluster("web");
        prior.setSettings(priorSettings);
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(prior);
        when(ecs.updateClusterSettings(eq("web"), any(), eq(REGION)))
                .thenThrow(new AwsException("ServerException", "write failed", 500))
                .thenThrow(new AwsException("ServerException", "still failing", 500))
                .thenReturn(prior);
        StackResource previous = resource("AWS::ECS::Cluster", "Cluster");
        previous.setPhysicalId("web");
        StackResource attempted = resource("AWS::ECS::Cluster", "Cluster");
        attempted.setPhysicalId("web");
        assertThrows(AwsException.class,
                () -> provisioner.provision(attempted, clusterWithInsights("enhanced"), ctx("web")));

        // The engine puts the previous resource back after the failed provision, and the first
        // rollback walk reports the recorded failure without calling rollbackUpdate.
        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);
        // ContinueUpdateRollback then asks for the restore again.
        assertTrue(provisioner.rollbackUpdate(previous));

        verify(ecs, times(2)).updateClusterSettings("web", priorSettings, REGION);
        assertFalse(previous.getAttributes().containsKey(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR));
    }

    @Test
    void aRestoreThatSucceededLeavesNothingToCarryOver() {
        EcsCluster prior = cluster("web");
        prior.setSettings(List.of(new ClusterSetting("containerInsights", "disabled")));
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(prior);
        when(ecs.updateClusterSettings("web", List.of(new ClusterSetting("containerInsights", "enhanced")), REGION))
                .thenThrow(new AwsException("ServerException", "write failed", 500));
        StackResource previous = resource("AWS::ECS::Cluster", "Cluster");
        StackResource attempted = resource("AWS::ECS::Cluster", "Cluster");
        assertThrows(AwsException.class,
                () -> provisioner.provision(attempted, clusterWithInsights("enhanced"), ctx("web")));

        provisioner.mergeFailedUpdateResourceTracking(previous, attempted);

        assertFalse(previous.getAttributes().containsKey(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR));
    }

    @Test
    void aCommittedSettingsUpdateDropsItsSnapshot() {
        EcsCluster prior = cluster("web");
        prior.setSettings(List.of(new ClusterSetting("containerInsights", "disabled")));
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(prior);
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");
        provisioner.provision(r, clusterWithInsights("enabled"), ctx("web"));

        assertEquals(UpdateCleanupResult.notApplicable(), provisioner.completeUpdate(r));

        assertFalse(r.getAttributes().containsKey(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR));
    }

    @Test
    void anUnchangedSettingIsNotReappliedAndDropsAnEarlierSnapshot() {
        EcsCluster current = cluster("web");
        current.setSettings(List.of(new ClusterSetting("containerInsights", "enabled")));
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(current);
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");
        r.getAttributes().put(CfnRollback.ECS_CLUSTER_SETTINGS_SNAPSHOT_ATTR,
                "{\"region\":\"us-east-1\",\"settings\":null}");

        provisioner.provision(r, clusterWithInsights("enabled"), ctx("web"));

        assertTrue(provisioner.rollbackUpdate(r));
        verify(ecs, never()).updateClusterSettings(anyString(), any(), anyString());
    }

    @Test
    void conditionalClusterSettingsAreResolvedBeforeTheyAreRead() {
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(cluster("web"));
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");
        ObjectNode props = clusterWithInsights("enabled");
        JsonNode resolved = props.get("ClusterSettings");
        ObjectNode conditional = mapper.createObjectNode();
        conditional.putArray("Fn::If").add("Insights").add(resolved).addObject().put("Ref", "AWS::NoValue");
        props.set("ClusterSettings", conditional);
        ProvisionContext ctx = ctx();
        when(ctx.engine().resolveNode(conditional)).thenReturn(resolved);

        provisioner.provision(r, props, ctx);

        verify(ecs).updateClusterSettings("web", List.of(new ClusterSetting("containerInsights", "enabled")), REGION);
    }

    @Test
    void clusterSettingsThatFailToResolveCreateNoCluster() {
        ObjectNode props = clusterWithInsights("enabled");
        ProvisionContext ctx = ctx();
        when(ctx.engine().resolveNode(props.get("ClusterSettings"))).thenThrow(new IllegalStateException("unresolvable"));

        assertThrows(IllegalStateException.class,
                () -> provisioner.provision(resource("AWS::ECS::Cluster", "Cluster"), props, ctx));

        verify(ecs, never()).createCluster(anyString(), any(), any(), anyString());
    }

    @Test
    void aClusterSettingResolvedToNoValueIsDropped() {
        when(ecs.createCluster(eq("web"), isNull(), anyList(), eq(REGION))).thenReturn(cluster("web"));
        ObjectNode props = clusterWithInsights("enabled");
        ((ArrayNode) props.get("ClusterSettings")).add("");

        provisioner.provision(resource("AWS::ECS::Cluster", "Cluster"), props, ctx());

        verify(ecs).updateClusterSettings("web", List.of(new ClusterSetting("containerInsights", "enabled")), REGION);
    }

    private ObjectNode clusterWithInsights(String value) {
        ObjectNode props = mapper.createObjectNode().put("ClusterName", "web");
        props.putArray("ClusterSettings").addObject().put("Name", "containerInsights").put("Value", value);
        return props;
    }

    @Test
    void anInPlaceUpdateIsNotRolledBackHere() {
        when(ecs.updateService(updating("web", "front", TASK_DEF_ARN, 3), eq(REGION)))
                .thenReturn(service("front"));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");
        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3"), ctx(SERVICE_ARN));

        assertFalse(provisioner.rollbackUpdate(r), "no replacement means nothing this helper can undo");
        assertEquals(SERVICE_ARN, r.getPhysicalId());
        verify(ecs, never()).deleteService(anyString(), anyString(), eq(true), anyString());
    }

    @Test
    void aRollbackWhoseDeleteFailsStillPointsTheResourceAtThePriorAndPropagates() {
        TaskDefinition next = new TaskDefinition();
        next.setTaskDefinitionArn(TASK_DEF_ARN.replace(":3", ":4"));
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(next);
        doThrow(new AwsException("ServerException", "busy", 500)).when(ecs).deregisterTaskDefinition(next.getTaskDefinitionArn(), REGION);
        StackResource r = resource("AWS::ECS::TaskDefinition", "TaskDef");
        r.getAttributes().put("TaskDefinitionArn", TASK_DEF_ARN);
        provisioner.provision(r, mapper.createObjectNode().put("Family", "web"), ctx(TASK_DEF_ARN));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertEquals("busy", e.getMessage());
        assertEquals(TASK_DEF_ARN, r.getPhysicalId());
        assertEquals(TASK_DEF_ARN, r.getAttributes().get("TaskDefinitionArn"));

        // The replacement the failed update created stays owed a delete; the restored prior does not.
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(next.getTaskDefinitionArn(), provisioner.updateCleanupPhysicalId(r));
        Mockito.reset(ecs);
        assertEquals(new UpdateCleanupResult(true, true, next.getTaskDefinitionArn(), 0, null), provisioner.completeUpdate(r));
        verify(ecs).deregisterTaskDefinition(next.getTaskDefinitionArn(), REGION);
        verify(ecs, never()).deregisterTaskDefinition(TASK_DEF_ARN, REGION);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void anOrphanFromAFailedRollbackSurvivesAnInPlaceUpdateAndIsDeletedWithTheNextReplacement() {
        String orphan = TASK_DEF_ARN.replace(":3", ":4");
        TaskDefinition next = new TaskDefinition();
        next.setTaskDefinitionArn(orphan);
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(next);
        doThrow(new AwsException("ServerException", "busy", 500)).when(ecs).deregisterTaskDefinition(orphan, REGION);
        StackResource r = resource("AWS::ECS::TaskDefinition", "TaskDef");
        r.getAttributes().put("TaskDefinitionArn", TASK_DEF_ARN);
        provisioner.provision(r, mapper.createObjectNode().put("Family", "web"), ctx(TASK_DEF_ARN));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertEquals(TASK_DEF_ARN, r.getPhysicalId());

        // A retried rollback (ContinueUpdateRollback) re-attempts the failed delete; failing again, the orphan stays owed.
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertTrue(provisioner.hasReplacementUpdate(r));

        // The next update registers revision 5: revision 3 (the prior) joins the orphan on the list
        // and the committed cleanup deletes both, never the live revision.
        TaskDefinition fifth = new TaskDefinition();
        fifth.setTaskDefinitionArn(TASK_DEF_ARN.replace(":3", ":5"));
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(fifth);
        provisioner.provision(r, mapper.createObjectNode().put("Family", "web"), ctx(TASK_DEF_ARN));
        Mockito.reset(ecs);
        UpdateCleanupResult cleanup = provisioner.completeUpdate(r);
        assertTrue(cleanup.complete(), cleanup.toString());
        verify(ecs).deregisterTaskDefinition(orphan, REGION);
        verify(ecs).deregisterTaskDefinition(TASK_DEF_ARN, REGION);
        verify(ecs, never()).deregisterTaskDefinition(fifth.getTaskDefinitionArn(), REGION);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void givingUpOnOneEntityDoesNotForgetAnotherThatStillHasAttemptsLeft() {
        String orphan = TASK_DEF_ARN.replace(":3", ":4");
        TaskDefinition next = new TaskDefinition();
        next.setTaskDefinitionArn(orphan);
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(next);
        doThrow(new AwsException("ServerException", "busy", 500)).when(ecs).deregisterTaskDefinition(orphan, REGION);
        StackResource r = resource("AWS::ECS::TaskDefinition", "TaskDef");
        r.getAttributes().put("TaskDefinitionArn", TASK_DEF_ARN);
        provisioner.provision(r, mapper.createObjectNode().put("Family", "web"), ctx(TASK_DEF_ARN));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        // The orphan uses up its three attempts in a cleanup where it is the only entry.
        for (int i = 0; i < 3; i++) {
            provisioner.completeUpdate(r);
        }
        assertEquals(3, provisioner.completeUpdate(r).attempts());

        // A later replacement adds revision 3 to the list; its delete also fails at first.
        TaskDefinition fifth = new TaskDefinition();
        fifth.setTaskDefinitionArn(TASK_DEF_ARN.replace(":3", ":5"));
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(fifth);
        provisioner.provision(r, mapper.createObjectNode().put("Family", "web"), ctx(TASK_DEF_ARN));
        doThrow(new AwsException("ServerException", "busy", 500)).when(ecs).deregisterTaskDefinition(TASK_DEF_ARN, REGION);

        // The result reports the entry with attempts left, so the engine keeps retrying it rather
        // than giving up on the strength of the exhausted orphan.
        UpdateCleanupResult first = provisioner.completeUpdate(r);
        assertFalse(first.complete());
        assertEquals(1, first.attempts());
        assertEquals(TASK_DEF_ARN, first.previousPhysicalId());

        // The engine gives up once the lowest count reaches three and clears; only the exhausted
        // entries go, and an entry that succeeded meanwhile is gone already.
        Mockito.doReturn(new TaskDefinition()).when(ecs).deregisterTaskDefinition(TASK_DEF_ARN, REGION);
        UpdateCleanupResult second = provisioner.completeUpdate(r);
        assertFalse(second.complete(), "the exhausted orphan is still listed");
        assertEquals(3, second.attempts());
        assertEquals(orphan, second.previousPhysicalId());
        provisioner.clearUpdate(r);
        assertFalse(provisioner.hasReplacementUpdate(r), "nothing with attempts left remains");
        // One delete during the failed rollback, then the three cleanup attempts, never a fifth.
        verify(ecs, Mockito.times(4)).deregisterTaskDefinition(orphan, REGION);
    }

    @Test
    void clearingAfterAGiveUpKeepsAnEntryThatStillHasAttemptsLeft() {
        String orphan = TASK_DEF_ARN.replace(":3", ":4");
        TaskDefinition next = new TaskDefinition();
        next.setTaskDefinitionArn(orphan);
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(next);
        doThrow(new AwsException("ServerException", "busy", 500)).when(ecs).deregisterTaskDefinition(orphan, REGION);
        StackResource r = resource("AWS::ECS::TaskDefinition", "TaskDef");
        r.getAttributes().put("TaskDefinitionArn", TASK_DEF_ARN);
        provisioner.provision(r, mapper.createObjectNode().put("Family", "web"), ctx(TASK_DEF_ARN));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        provisioner.completeUpdate(r);

        // Cleared with one attempt used: the orphan is not forgotten.
        provisioner.clearUpdate(r);
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(orphan, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void taskDefinitionForwardsTheRuntimePlatformAndEachContainersUserAndHealthCheck() {
        TaskDefinition td = new TaskDefinition();
        td.setTaskDefinitionArn(TASK_DEF_ARN);
        when(ecs.registerTaskDefinition(any(RegisterTaskDefinitionRequest.class), eq(REGION))).thenReturn(td);
        ObjectNode props = mapper.createObjectNode().put("Family", "web");
        props.putObject("RuntimePlatform").put("CpuArchitecture", "X86_64").put("OperatingSystemFamily", "LINUX");
        ObjectNode healthCheck = props.putArray("ContainerDefinitions").addObject()
                .put("Name", "app").put("Image", "nginx:1").put("User", "1000")
                .putObject("HealthCheck").put("Interval", "30").put("Timeout", 5).put("Retries", 3);
        healthCheck.putArray("Command").add("CMD-SHELL").add("true");

        provisioner.provision(resource("AWS::ECS::TaskDefinition", "TaskDef"), props, ctx());

        RegisterTaskDefinitionRequest request = registered();
        assertEquals(new RuntimePlatform("X86_64", "LINUX"), request.getRuntimePlatform());
        ContainerDefinition def = request.getContainerDefinitions().get(0);
        assertEquals("1000", def.getUser());
        assertEquals(new HealthCheck(List.of("CMD-SHELL", "true"), 30, 5, 3, null), def.getHealthCheck());
    }

    @Test
    void serviceSettingsGivenAsTextAreForwardedConvertedOnCreate() {
        when(ecs.createService(any(CreateServiceRequest.class), eq(REGION))).thenReturn(service("front"));

        provisioner.provision(resource("AWS::ECS::Service", "Service"), withServiceSettings(mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)), ctx());

        ArgumentCaptor<CreateServiceRequest> request = ArgumentCaptor.forClass(CreateServiceRequest.class);
        verify(ecs).createService(request.capture(), eq(REGION));
        assertEquals(REGISTRIES, request.getValue().getServiceRegistries());
        assertEquals(DEPLOYMENT_CONFIGURATION, request.getValue().getDeploymentConfiguration());
        assertEquals(STRATEGY, request.getValue().getCapacityProviderStrategy());
    }

    @Test
    void serviceSettingsAreForwardedOnAnInPlaceUpdate() {
        when(ecs.updateService(any(UpdateServiceRequest.class), eq(REGION))).thenReturn(service("front"));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        provisioner.provision(r, withServiceSettings(mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)), ctx(SERVICE_ARN));

        ArgumentCaptor<UpdateServiceRequest> request = ArgumentCaptor.forClass(UpdateServiceRequest.class);
        verify(ecs).updateService(request.capture(), eq(REGION));
        assertEquals(REGISTRIES, request.getValue().getServiceRegistries());
        assertEquals(DEPLOYMENT_CONFIGURATION, request.getValue().getDeploymentConfiguration());
        assertEquals(STRATEGY, request.getValue().getCapacityProviderStrategy());
    }

    @Test
    void aStrategyEntryResolvedToNoValueIsSkipped() {
        when(ecs.createService(any(CreateServiceRequest.class), eq(REGION))).thenReturn(service("front"));
        ObjectNode props = mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN);
        // The empty text is what Ref AWS::NoValue resolves to inside a list.
        props.putArray("CapacityProviderStrategy").add("")
                .addObject().put("CapacityProvider", "FARGATE").put("Weight", "2");

        provisioner.provision(resource("AWS::ECS::Service", "Service"), props, ctx());

        ArgumentCaptor<CreateServiceRequest> request = ArgumentCaptor.forClass(CreateServiceRequest.class);
        verify(ecs).createService(request.capture(), eq(REGION));
        assertEquals(STRATEGY, request.getValue().getCapacityProviderStrategy());
    }

    @Test
    void aStrategyWhoseEveryEntryResolvedToNoValueIsNone() {
        when(ecs.createService(any(CreateServiceRequest.class), eq(REGION))).thenReturn(service("front"));
        ObjectNode props = mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN);
        props.putArray("CapacityProviderStrategy").add("");

        provisioner.provision(resource("AWS::ECS::Service", "Service"), props, ctx());

        ArgumentCaptor<CreateServiceRequest> request = ArgumentCaptor.forClass(CreateServiceRequest.class);
        verify(ecs).createService(request.capture(), eq(REGION));
        assertNull(request.getValue().getCapacityProviderStrategy());
    }

    @Test
    void settingsTheTemplateDoesNotDeclareAreLeftUnchangedByAnUpdateAndItsRestore() {
        EcsServiceModel prior = service("front");
        prior.setServiceRegistries(REGISTRIES);
        when(ecs.serviceByArn(SERVICE_ARN)).thenReturn(prior);
        EcsServiceModel updated = service("front");
        when(ecs.updateService(any(UpdateServiceRequest.class), eq(REGION))).thenReturn(updated);
        doThrow(new AwsException("NotStabilized", "did not stabilize", 500)).when(ecs).awaitServiceStable(updated);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        assertThrows(AwsException.class, () -> provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN), ctx(SERVICE_ARN)));

        ArgumentCaptor<UpdateServiceRequest> requests = ArgumentCaptor.forClass(UpdateServiceRequest.class);
        verify(ecs, times(2)).updateService(requests.capture(), eq(REGION));
        for (UpdateServiceRequest request : requests.getAllValues()) {
            // An empty list would deregister the service's tasks and roll a deployment.
            assertNull(request.getServiceRegistries());
            assertNull(request.getDeploymentConfiguration());
            assertNull(request.getCapacityProviderStrategy());
        }
    }

    @Test
    void anInPlaceUpdateThatDoesNotStabilizePutsThePriorSettingsBack() {
        List<CapacityProviderStrategyItem> priorStrategy = List.of(new CapacityProviderStrategyItem("FARGATE_SPOT", 1, 0));
        EcsServiceModel prior = service("front");
        prior.setTaskDefinition(TASK_DEF_ARN);
        prior.setDesiredCount(1);
        prior.setCapacityProviderStrategy(priorStrategy);
        when(ecs.serviceByArn(SERVICE_ARN)).thenReturn(prior);
        EcsServiceModel updated = service("front");
        EcsServiceModel restored = service("front");
        when(ecs.updateService(any(UpdateServiceRequest.class), eq(REGION))).thenAnswer(inv -> {
            UpdateServiceRequest request = inv.getArgument(0);
            // UpdateService changes the very model the prior settings were read from.
            prior.setDeploymentConfiguration(request.getDeploymentConfiguration());
            prior.setCapacityProviderStrategy(request.getCapacityProviderStrategy());
            prior.setServiceRegistries(request.getServiceRegistries());
            return request.getDesiredCount() == 3 ? updated : restored;
        });
        doThrow(new AwsException("NotStabilized", "did not stabilize", 500)).when(ecs).awaitServiceStable(updated);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        assertThrows(AwsException.class, () -> provisioner.provision(r, withServiceSettings(mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3")), ctx(SERVICE_ARN)));

        ArgumentCaptor<UpdateServiceRequest> requests = ArgumentCaptor.forClass(UpdateServiceRequest.class);
        verify(ecs, times(2)).updateService(requests.capture(), eq(REGION));
        UpdateServiceRequest restore = requests.getAllValues().get(1);
        assertEquals(1, restore.getDesiredCount());
        assertEquals(Map.of(), restore.getDeploymentConfiguration(), "a deployment configuration the update added is removed");
        assertEquals(priorStrategy, restore.getCapacityProviderStrategy());
        assertEquals(List.of(), restore.getServiceRegistries(), "a registry the update added is deregistered");
        verify(ecs).awaitServiceStable(restored);
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    /**
     * The three service settings, every scalar the text a {@code String} parameter resolves to,
     * the booleans in each case CloudFormation's handler accepts.
     */
    private static ObjectNode withServiceSettings(ObjectNode props) {
        props.putArray("ServiceRegistries").addObject()
                .put("RegistryArn", REGISTRY_ARN).put("ContainerName", "app").put("ContainerPort", "80");
        ObjectNode deployment = props.putObject("DeploymentConfiguration")
                .put("MaximumPercent", "150").put("MinimumHealthyPercent", "50");
        ObjectNode breaker = deployment.putObject("DeploymentCircuitBreaker")
                .put("Enable", "True").put("Rollback", "false").put("ResetOnHealthyTask", "false");
        breaker.putObject("ThresholdConfiguration").put("Type", "COUNT").put("Value", "3");
        deployment.putObject("Alarms").put("Enable", "TRUE").put("Rollback", "False")
                .putArray("AlarmNames").add("high-errors");
        props.putArray("CapacityProviderStrategy").addObject().put("CapacityProvider", "FARGATE").put("Weight", "2");
        return props;
    }

    @Test
    void aNonIntegerDesiredCountIsAValidationError() {
        StackResource r = resource("AWS::ECS::Service", "Service");
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r,
                mapper.createObjectNode().put("Cluster", "web").put("DesiredCount", "two"), ctx()));
        assertEquals("ValidationError", e.getErrorCode());
        assertNull(r.getPhysicalId());
    }

    @Test
    void deleteToleratesOnlyTheServicesOwnNotFoundCodes() {
        doThrow(new AwsException("ClusterNotFoundException", "gone", 400)).when(ecs).deleteCluster("web", REGION);
        provisioner.delete("AWS::ECS::Cluster", "web", REGION);

        doThrow(new AwsException("ClientException", "Unable to describe task definition", 400))
                .when(ecs).deregisterTaskDefinition(TASK_DEF_ARN, REGION);
        provisioner.delete("AWS::ECS::TaskDefinition", TASK_DEF_ARN, REGION);

        doThrow(new AwsException("ServiceNotFoundException", "gone", 400))
                .when(ecs).deleteService("web", "front", true, REGION);
        provisioner.delete("AWS::ECS::Service", SERVICE_ARN, REGION);

        doThrow(new AwsException("ClusterContainsTasksException", "busy", 400)).when(ecs).deleteCluster("busy", REGION);
        AwsException e = assertThrows(AwsException.class, () -> provisioner.delete("AWS::ECS::Cluster", "busy", REGION));
        assertEquals("ClusterContainsTasksException", e.getErrorCode());
    }

    @Test
    void deleteServiceParsesTheClusterOutOfTheArn() {
        provisioner.delete("AWS::ECS::Service", SERVICE_ARN, REGION);
        verify(ecs).deleteService("web", "front", true, REGION);

        provisioner.delete("AWS::ECS::Service", "bare-name", REGION);
        verify(ecs).deleteService(null, "bare-name", true, REGION);
    }

    @Test
    void deleteWithoutAPhysicalIdIsANoOp() {
        provisioner.delete("AWS::ECS::Cluster", null, REGION);
        provisioner.delete("AWS::ECS::Service", "", REGION);
        verify(ecs, never()).deleteCluster(any(), any());
        verify(ecs, never()).deleteService(any(), any(), eq(true), any());
    }

    @Test
    void rejectsAResourceTypeItDoesNotOwn() {
        StackResource r = resource("AWS::ECS::CapacityProvider", "Cp");
        assertThrows(IllegalStateException.class, () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
    }
}
