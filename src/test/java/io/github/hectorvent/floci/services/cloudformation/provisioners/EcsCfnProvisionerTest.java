package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsCluster;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        // resolveStringList delegates to the real engine method; for the literal arrays these
        // tests use it just walks the array and calls resolve(...) per element, stubbed above.
        when(engine.resolveStringList(any())).thenCallRealMethod();
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
        when(ecs.createCluster("web", REGION)).thenReturn(cluster("web"));
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");

        provisioner.provision(r, mapper.createObjectNode().put("ClusterName", "web"), ctx());

        assertEquals("web", r.getPhysicalId());
        assertEquals(CLUSTER_ARN, r.getAttributes().get("Arn"));
        assertEquals(Set.of("Arn"), r.getAttributes().keySet());
    }

    @Test
    void anUnnamedClusterGeneratesANameOnCreateAndKeepsItOnUpdate() {
        when(ecs.createCluster(anyString(), eq(REGION))).thenAnswer(inv -> cluster(inv.getArgument(0)));
        StackResource created = resource("AWS::ECS::Cluster", "Cluster");
        provisioner.provision(created, mapper.createObjectNode(), ctx());
        String generated = created.getPhysicalId();
        assertTrue(generated.startsWith("my-stack-Cluster-"), generated);

        StackResource updated = resource("AWS::ECS::Cluster", "Cluster");
        provisioner.provision(updated, mapper.createObjectNode(), ctx(generated));

        // createCluster is idempotent, so the update re-issues it under the same name instead of
        // minting a second cluster.
        assertEquals(generated, updated.getPhysicalId());
        verify(ecs, times(2)).createCluster(generated, REGION);
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
