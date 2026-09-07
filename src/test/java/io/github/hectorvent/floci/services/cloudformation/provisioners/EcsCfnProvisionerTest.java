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
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.inOrder;
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
        verify(ecs, org.mockito.Mockito.times(3)).deregisterTaskDefinition(TASK_DEF_ARN, REGION);
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
        when(ecs.createCluster("web", REGION)).thenReturn(cluster("web"));
        StackResource r = resource("AWS::ECS::Cluster", "Cluster");
        provisioner.provision(r, mapper.createObjectNode().put("ClusterName", "web"), ctx("web"));

        // The idempotent create changed nothing, so the rollback is complete with nothing to do.
        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals("web", r.getPhysicalId());
        verify(ecs, never()).deleteCluster(anyString(), anyString());
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
        org.mockito.Mockito.reset(ecs);
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

        // A rollback with no replacement of its own deletes nothing and keeps the orphan owed.
        assertFalse(provisioner.rollbackUpdate(r));
        assertTrue(provisioner.hasReplacementUpdate(r));

        // The next update registers revision 5: revision 3 (the prior) joins the orphan on the list
        // and the committed cleanup deletes both, never the live revision.
        TaskDefinition fifth = new TaskDefinition();
        fifth.setTaskDefinitionArn(TASK_DEF_ARN.replace(":3", ":5"));
        when(ecs.registerTaskDefinition(eq("web"), anyList(), isNull(), isNull(), isNull(), isNull(), isNull(), anyList(),
                eq(REGION))).thenReturn(fifth);
        provisioner.provision(r, mapper.createObjectNode().put("Family", "web"), ctx(TASK_DEF_ARN));
        org.mockito.Mockito.reset(ecs);
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
        org.mockito.Mockito.doReturn(new TaskDefinition()).when(ecs).deregisterTaskDefinition(TASK_DEF_ARN, REGION);
        UpdateCleanupResult second = provisioner.completeUpdate(r);
        assertFalse(second.complete(), "the exhausted orphan is still listed");
        assertEquals(3, second.attempts());
        assertEquals(orphan, second.previousPhysicalId());
        provisioner.clearUpdate(r);
        assertFalse(provisioner.hasReplacementUpdate(r), "nothing with attempts left remains");
        // One delete during the failed rollback, then the three cleanup attempts, never a fifth.
        verify(ecs, org.mockito.Mockito.times(4)).deregisterTaskDefinition(orphan, REGION);
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
