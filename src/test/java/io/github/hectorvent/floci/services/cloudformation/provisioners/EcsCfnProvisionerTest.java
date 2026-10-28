package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.ClusterSetting;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        when(ecs.registerTaskDefinition(eq("web"), anyList(), eq(NetworkMode.awsvpc), eq("256"), eq("512"),
                eq("arn:aws:iam::000000000000:role/task"), eq("arn:aws:iam::000000000000:role/exec"),
                eq(List.of("FARGATE")), eq(REGION))).thenReturn(td);

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
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContainerDefinition>> defs = ArgumentCaptor.forClass(List.class);
        verify(ecs).registerTaskDefinition(eq("web"), defs.capture(), eq(NetworkMode.awsvpc), eq("256"),
                eq("512"), anyString(), anyString(), anyList(), eq(REGION));
        ContainerDefinition def = defs.getValue().get(0);
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
        when(ecs.registerTaskDefinition(eq("web"), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(),
                anyList(), eq(REGION))).thenReturn(td);
        ObjectNode props = mapper.createObjectNode().put("Family", "web");
        props.putArray("ContainerDefinitions").addObject()
                .put("Name", "app").put("Image", "nginx:1").put("Cpu", " 64").put("Memory", "+128")
                .put("Essential", "False");

        provisioner.provision(resource("AWS::ECS::TaskDefinition", "TaskDef"), props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContainerDefinition>> defs = ArgumentCaptor.forClass(List.class);
        verify(ecs).registerTaskDefinition(eq("web"), defs.capture(), isNull(), isNull(), isNull(), isNull(),
                isNull(), anyList(), eq(REGION));
        ContainerDefinition def = defs.getValue().get(0);
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
        when(ecs.registerTaskDefinition(anyString(), anyList(), any(), any(), any(), any(), any(), anyList(),
                eq(REGION))).thenReturn(td);

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
        when(ecs.registerTaskDefinition(anyString(), anyList(), any(), any(), any(), any(), any(), anyList(),
                eq(REGION))).thenReturn(td);

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
        when(ecs.registerTaskDefinition(anyString(), anyList(), any(), any(), any(), any(), any(), anyList(),
                eq(REGION))).thenReturn(td);

        ObjectNode props = mapper.createObjectNode().put("Family", "web");
        props.putArray("ContainerDefinitions").addObject().put("Name", "app").put("Image", "nginx:1");
        provisioner.provision(resource("AWS::ECS::TaskDefinition", "TaskDef"), props, ctx());

        assertNull(registeredContainer().getUnparsed());
    }

    private ContainerDefinition registeredContainer() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContainerDefinition>> defs = ArgumentCaptor.forClass(List.class);
        verify(ecs).registerTaskDefinition(anyString(), defs.capture(), any(), any(), any(), any(), any(),
                anyList(), eq(REGION));
        return defs.getValue().get(0);
    }

    @Test
    void taskDefinitionWithoutFamilyGeneratesOneAndIgnoresAnUnknownNetworkMode() {
        TaskDefinition td = new TaskDefinition();
        td.setTaskDefinitionArn(TASK_DEF_ARN);
        when(ecs.registerTaskDefinition(anyString(), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(),
                anyList(), eq(REGION))).thenReturn(td);

        StackResource r = resource("AWS::ECS::TaskDefinition", "TaskDef");
        provisioner.provision(r, mapper.createObjectNode().put("NetworkMode", "not-a-mode"), ctx());

        ArgumentCaptor<String> family = ArgumentCaptor.forClass(String.class);
        verify(ecs).registerTaskDefinition(family.capture(), anyList(), isNull(), isNull(), isNull(), isNull(),
                isNull(), anyList(), eq(REGION));
        assertTrue(family.getValue().startsWith("my-stack-TaskDef-"), family.getValue());
    }

    @Test
    void serviceIsCreatedWithTheParsedLoadBalancersAndNetwork() {
        when(ecs.createService(eq("web"), eq("front"), eq(TASK_DEF_ARN), eq(2), eq(LaunchType.FARGATE),
                anyList(), any(), eq(REGION))).thenReturn(service("front"));
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
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EcsLoadBalancer>> lbs = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<NetworkConfiguration> net = ArgumentCaptor.forClass(NetworkConfiguration.class);
        verify(ecs).createService(eq("web"), eq("front"), eq(TASK_DEF_ARN), eq(2), eq(LaunchType.FARGATE),
                lbs.capture(), net.capture(), eq(REGION));
        assertEquals("app", lbs.getValue().get(0).getContainerName());
        assertEquals(80, lbs.getValue().get(0).getContainerPort());
        assertEquals(List.of("subnet-1", "subnet-2"), net.getValue().getAwsvpcConfiguration().getSubnets());
        assertEquals("ENABLED", net.getValue().getAwsvpcConfiguration().getAssignPublicIp());
    }

    @Test
    void anUnchangedServiceIsUpdatedInPlaceOnTheSecondPass() {
        when(ecs.updateService(eq("web"), eq("front"), eq(TASK_DEF_ARN), eq(3), isNull(), eq(REGION)))
                .thenReturn(service("front"));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");
        ObjectNode props = mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3");

        provisioner.provision(r, props, ctx(SERVICE_ARN));

        assertEquals(SERVICE_ARN, r.getPhysicalId());
        verify(ecs).updateService("web", "front", TASK_DEF_ARN, 3, null, REGION);
        verify(ecs, never()).createService(anyString(), anyString(), anyString(), anyInt(), any(), anyList(),
                any(), anyString());
    }

    @Test
    void anUnnamedServiceKeepsTheNameItGotAtCreateTime() {
        when(ecs.updateService(eq("web"), eq("my-stack-Service-abc"), eq(TASK_DEF_ARN), eq(1), isNull(), eq(REGION)))
                .thenReturn(service("my-stack-Service-abc"));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "my-stack-Service-abc");

        provisioner.provision(r, mapper.createObjectNode().put("Cluster", "web").put("TaskDefinition", TASK_DEF_ARN),
                ctx("arn:aws:ecs:us-east-1:000000000000:service/web/my-stack-Service-abc"));

        verify(ecs).updateService("web", "my-stack-Service-abc", TASK_DEF_ARN, 1, null, REGION);
    }

    @Test
    void aRenamedServiceIsCreatedAsAReplacement() {
        when(ecs.createService(eq("web"), eq("front-v2"), eq(TASK_DEF_ARN), eq(1), isNull(), anyList(), isNull(),
                eq(REGION))).thenReturn(service("front-v2"));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front-v2").put("TaskDefinition", TASK_DEF_ARN),
                ctx(SERVICE_ARN));

        assertNotEquals(SERVICE_ARN, r.getPhysicalId());
        assertEquals("front-v2", r.getAttributes().get("Name"));
        verify(ecs, never()).updateService(anyString(), anyString(), anyString(), anyInt(), any(), anyString());
    }

    @Test
    void aServiceMovedToAnotherClusterIsCreatedAsAReplacementAndTheOldOneCleanedUpAfterCommit() {
        EcsServiceModel moved = service("front");
        moved.setServiceArn("arn:aws:ecs:us-east-1:000000000000:service/batch/front");
        when(ecs.createService(eq("batch"), eq("front"), eq(TASK_DEF_ARN), eq(1), isNull(), anyList(), isNull(),
                eq(REGION))).thenReturn(moved);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "batch").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN), ctx(SERVICE_ARN));

        // Cluster is create-only: same name, other cluster is a replacement, never an in-place update
        // against a service the new cluster may not have (or may have under another owner).
        verify(ecs, never()).updateService(anyString(), anyString(), anyString(), anyInt(), any(), anyString());
        assertEquals(moved.getServiceArn(), r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(SERVICE_ARN, provisioner.updateCleanupPhysicalId(r));

        UpdateCleanupResult cleanup = provisioner.completeUpdate(r);

        InOrder order = inOrder(ecs);
        order.verify(ecs).createService(eq("batch"), eq("front"), eq(TASK_DEF_ARN), eq(1), isNull(), anyList(), isNull(),
                eq(REGION));
        order.verify(ecs).deleteService("web", "front", true, REGION);
        assertEquals(new UpdateCleanupResult(true, true, SERVICE_ARN, 0, null), cleanup);
        provisioner.clearUpdate(r);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void theClusterGivenAsAnArnStillCountsAsTheSameCluster() {
        when(ecs.updateService(eq(CLUSTER_ARN), eq("front"), eq(TASK_DEF_ARN), eq(1), isNull(), eq(REGION)))
                .thenReturn(service("front"));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", CLUSTER_ARN).put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN), ctx(SERVICE_ARN));

        verify(ecs).updateService(CLUSTER_ARN, "front", TASK_DEF_ARN, 1, null, REGION);
        assertFalse(provisioner.hasReplacementUpdate(r), "an in-place update owes no cleanup");
    }

    @Test
    void aCreatedServiceIsAwaitedUntilStableBeforeTheResourceCompletes() {
        EcsServiceModel created = service("front");
        when(ecs.createService(eq("web"), eq("front"), eq(TASK_DEF_ARN), eq(1), isNull(), anyList(), isNull(),
                eq(REGION))).thenReturn(created);
        StackResource r = resource("AWS::ECS::Service", "Service");

        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN), ctx());

        InOrder order = inOrder(ecs);
        order.verify(ecs).createService(eq("web"), eq("front"), eq(TASK_DEF_ARN), eq(1), isNull(), anyList(),
                isNull(), eq(REGION));
        order.verify(ecs).awaitServiceStable(created);
        assertEquals(Set.of("Name", "ServiceArn"), r.getAttributes().keySet(),
                "a stable service is no longer marked for the create rollback");
    }

    @Test
    void anInPlaceUpdateIsAwaitedUntilStableBeforeTheResourceCompletes() {
        EcsServiceModel updated = service("front");
        when(ecs.updateService(eq("web"), eq("front"), eq(TASK_DEF_ARN), eq(3), isNull(), eq(REGION)))
                .thenReturn(updated);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3"), ctx(SERVICE_ARN));

        InOrder order = inOrder(ecs);
        order.verify(ecs).updateService("web", "front", TASK_DEF_ARN, 3, null, REGION);
        order.verify(ecs).awaitServiceStable(updated);
    }

    @Test
    void aCreatedServiceThatDoesNotStabilizeFailsAndStaysOwnedForTheCreateRollback() {
        EcsServiceModel created = service("front");
        when(ecs.createService(eq("web"), eq("front"), eq(TASK_DEF_ARN), eq(1), isNull(), anyList(), isNull(),
                eq(REGION))).thenReturn(created);
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
        when(ecs.createService(eq("web"), eq("front-v2"), eq(TASK_DEF_ARN), eq(1), isNull(), anyList(), isNull(),
                eq(REGION))).thenReturn(replacement);
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
        when(ecs.createService(eq("web"), eq("front-v2"), eq(TASK_DEF_ARN), eq(1), isNull(), anyList(), isNull(),
                eq(REGION))).thenReturn(replacement);
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
        when(ecs.updateService(eq("web"), eq("front"), eq(TASK_DEF_ARN), eq(3), isNull(), eq(REGION)))
                .thenReturn(updated);
        AwsException notStable = new AwsException("NotStabilized", "did not stabilize", 500);
        doThrow(notStable).when(ecs).awaitServiceStable(updated);
        when(ecs.updateService("web", "front", TASK_DEF_ARN, 1, null, REGION))
                .thenThrow(new AwsException("ServerException", "busy", 500));
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3"), ctx(SERVICE_ARN)));

        assertEquals(notStable, e, "the stabilization failure is what fails the update");
        assertEquals("busy", e.getSuppressed()[0].getMessage());
        verify(ecs).updateService("web", "front", TASK_DEF_ARN, 1, null, REGION);
        assertTrue(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR).contains(SERVICE_ARN));
    }

    @Test
    void anInPlaceUpdateThatDoesNotStabilizeAwaitsTheRestoredConfiguration() {
        EcsServiceModel prior = service("front");
        prior.setTaskDefinition(TASK_DEF_ARN);
        prior.setDesiredCount(1);
        when(ecs.serviceByArn(SERVICE_ARN)).thenReturn(prior);
        EcsServiceModel updated = service("front");
        when(ecs.updateService("web", "front", TASK_DEF_ARN, 3, null, REGION)).thenReturn(updated);
        doThrow(new AwsException("NotStabilized", "did not stabilize", 500)).when(ecs).awaitServiceStable(updated);
        EcsServiceModel restored = service("front");
        when(ecs.updateService("web", "front", TASK_DEF_ARN, 1, null, REGION)).thenReturn(restored);
        StackResource r = resource("AWS::ECS::Service", "Service");
        r.getAttributes().put("Name", "front");

        assertThrows(AwsException.class, () -> provisioner.provision(r, mapper.createObjectNode()
                .put("Cluster", "web").put("ServiceName", "front").put("TaskDefinition", TASK_DEF_ARN)
                .put("DesiredCount", "3"), ctx(SERVICE_ARN)));

        InOrder order = inOrder(ecs);
        order.verify(ecs).updateService("web", "front", TASK_DEF_ARN, 1, null, REGION);
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
        when(ecs.updateService("web", "front", TASK_DEF_ARN, 3, null, REGION)).thenReturn(updated);
        AwsException notStable = new AwsException("NotStabilized", "did not stabilize", 500);
        doThrow(notStable).when(ecs).awaitServiceStable(updated);
        EcsServiceModel restored = service("front");
        when(ecs.updateService("web", "front", TASK_DEF_ARN, 1, null, REGION)).thenReturn(restored);
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
        when(ecs.registerTaskDefinition(eq("web"), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(next);
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
        when(ecs.registerTaskDefinition(eq("web"), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(next);

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
        when(ecs.createService(eq("batch"), eq("front"), eq(TASK_DEF_ARN), eq(1), isNull(), anyList(), isNull(),
                eq(REGION))).thenReturn(moved);
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
        when(ecs.updateService(eq("web"), eq("front"), eq(TASK_DEF_ARN), eq(3), isNull(), eq(REGION)))
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
        when(ecs.registerTaskDefinition(eq("web"), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(next);
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
        when(ecs.registerTaskDefinition(eq("web"), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(next);
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
        when(ecs.registerTaskDefinition(eq("web"), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(fifth);
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
        when(ecs.registerTaskDefinition(eq("web"), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(next);
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
        when(ecs.registerTaskDefinition(eq("web"), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(fifth);
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
        when(ecs.registerTaskDefinition(eq("web"), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(next);
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
