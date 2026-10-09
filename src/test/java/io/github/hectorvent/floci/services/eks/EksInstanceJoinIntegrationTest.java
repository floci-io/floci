package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

@QuarkusTest
class EksInstanceJoinIntegrationTest {

    @Inject
    EksService eksService;

    @InjectSpy
    EksClusterManager eksClusterManager;

    @Inject
    Ec2Service ec2Service;

    @Test
    void instanceJoinsClusterViaUserDataAndExposesNodeMetadata() {
        String account = "123456789012";
        String clusterName = "join-cluster-" + UUID.randomUUID().toString().substring(0, 8);

        Cluster cluster = createTestCluster(clusterName, account);
        cluster.setContainerId("mock-k3s-container");
        ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
        doReturn(new ContainerExec.Result(0L, "", "", false)).when(eksClusterManager)
                .execInContainerForResult(eq("mock-k3s-container"), cmdCaptor.capture(), anyInt());

        eksClusterManager.registerClusterNodeInstance(cluster, "mock-container-id");
        Instance initialClusterNode = eksClusterManager.getRegisteredClusterNodeInstance(cluster);
        assertNotNull(initialClusterNode);
        String initialNodeName = eksClusterManager.deriveClusterNodePrivateDnsName(cluster);
        String initialProviderId = eksClusterManager.deriveClusterNodeProviderId(cluster);

        try {
            String script = "#!/bin/bash\n/etc/eks/bootstrap.sh " + clusterName;
            String instanceId = launchInstance(account, "us-east-1", "t3.medium", script);

            await().atMost(Duration.ofSeconds(10)).until(() ->
                    RequestScopes.callAs(account, () -> eksService.isInstanceJoined(clusterName, instanceId)));
            List<Instance> joined = RequestScopes.callAs(account, () -> eksService.getJoinedInstances(clusterName));
            assertEquals(1, joined.size());
            Instance joinedInst = joined.getFirst();
            assertEquals(instanceId, joinedInst.getInstanceId());

            String az = joinedInst.getPlacement() != null ? joinedInst.getPlacement().getAvailabilityZone() : "us-east-1a";
            String expectedNodeName = joinedInst.getPrivateDnsName() != null ? joinedInst.getPrivateDnsName() : instanceId + ".ec2.internal";
            assertEquals(expectedNodeName, eksClusterManager.deriveInstanceNodeName(joinedInst, "us-east-1"));
            assertEquals("aws:///" + az + "/" + instanceId, eksClusterManager.deriveInstanceNodeProviderId(joinedInst, "us-east-1"));

            List<String[]> cmds = cmdCaptor.getAllValues();
            assertEquals(2, cmds.size());
            assertTrue(cmds.getFirst()[2].contains(expectedNodeName)
                    && cmds.getFirst()[2].contains("aws:///" + az + "/" + instanceId)
                    && cmds.getFirst()[2].contains("\"topology.kubernetes.io/zone\":\"" + az + "\"")
                    && cmds.getFirst()[2].contains("\"topology.kubernetes.io/region\":\"us-east-1\"")
                    && cmds.getFirst()[2].contains("\"node.kubernetes.io/instance-type\":\"t3.medium\"")
                    && cmds.getFirst()[2].contains("\"kubernetes.io/arch\":\"amd64\""));
            assertEquals("node", cmds.get(1)[2]);
            assertEquals(expectedNodeName, cmds.get(1)[3]);
            assertTrue(cmds.get(1)[7].contains("Ready") && cmds.get(1)[7].contains("True"));

            assertEquals(initialNodeName, eksClusterManager.deriveClusterNodePrivateDnsName(cluster));
            assertEquals(initialProviderId, eksClusterManager.deriveClusterNodeProviderId(cluster));
            assertEquals(initialClusterNode.getInstanceId(), eksClusterManager.getRegisteredClusterNodeInstance(cluster).getInstanceId());

            ec2Form(account, "DescribeInstances", "InstanceId.1", instanceId).statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId", equalTo(instanceId),
                            "DescribeInstancesResponse.reservationSet.item.instancesSet.item.privateDnsName", equalTo(expectedNodeName),
                            "DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceType", equalTo("t3.medium"));
        } finally {
            eksClusterManager.unregisterMetadataEndpoint(cluster);
            try {
                RequestScopes.runAs(account, () -> eksService.deleteCluster(clusterName));
            } catch (Exception ignored) {
                // Best effort cluster cleanup
            }
        }
    }

    @Test
    void subnetlessLaunchesGetDistinctAddressesAndNodeNames() {
        String account = "123456789012";
        String id1 = launchInstance(account, "us-east-1", "t3.micro", null);
        String id2 = launchInstance(account, "us-east-1", "t3.micro", null);
        Instance inst1 = RequestScopes.callAs(account, () -> ec2Service.getInstance("us-east-1", id1).orElseThrow());
        Instance inst2 = RequestScopes.callAs(account, () -> ec2Service.getInstance("us-east-1", id2).orElseThrow());

        assertNotNull(inst1.getPrivateIpAddress());
        assertNotNull(inst2.getPrivateIpAddress());
        assertNotEquals(inst1.getPrivateIpAddress(), inst2.getPrivateIpAddress());
        assertNotEquals(eksClusterManager.deriveInstanceNodeName(inst1, "us-east-1"),
                eksClusterManager.deriveInstanceNodeName(inst2, "us-east-1"));
    }

    @Test
    void unrelatedOrMissingClusterDoesNotJoin() {
        String account = "123456789012";
        String clusterName = "unrelated-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = createTestCluster(clusterName, account);
        try {
            String instId = launchInstance(account, "us-east-1", "t3.micro", "#!/bin/bash\necho hello world");
            assertFalse(RequestScopes.callAs(account, () -> eksService.isInstanceJoined(clusterName, instId)));
            assertTrue(RequestScopes.callAs(account, () -> eksService.getJoinedInstances(clusterName)).isEmpty());
        } finally {
            eksClusterManager.unregisterMetadataEndpoint(cluster);
        }

        String instId2 = launchInstance(account, "us-east-1", "t3.micro",
                "#!/bin/bash\n/etc/eks/bootstrap.sh non-existent-cluster-999");
        assertNotNull(instId2);
        ec2Form(account, "DescribeInstances", "InstanceId.1", instId2).statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId", equalTo(instId2));
    }

    @Test
    void programmaticJoinInvocableOutsideCreatePath() {
        String account = "123456789012";
        String clusterName = "prog-join-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = createTestCluster(clusterName, account);
        try {
            String instId = launchInstance(account, "us-east-1", "m5.large", null);
            assertFalse(RequestScopes.callAs(account, () -> eksService.isInstanceJoined(clusterName, instId)));
            RequestScopes.runAs(account, () -> eksService.joinInstance(clusterName, instId));
            assertTrue(RequestScopes.callAs(account, () -> eksService.isInstanceJoined(clusterName, instId)));
            List<Instance> joined = RequestScopes.callAs(account, () -> eksService.getJoinedInstances(clusterName));
            assertEquals(1, joined.size());
            assertEquals(instId, joined.getFirst().getInstanceId());
        } finally {
            eksClusterManager.unregisterMetadataEndpoint(cluster);
        }
    }

    @Test
    void ebsVolumeAttachmentOnClusterNodeRemainsFunctionalWithJoinedInstances() {
        String account = "123456789012";
        String clusterName = "ebs-test-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = createTestCluster(clusterName, account);
        eksClusterManager.registerClusterNodeInstance(cluster, "mock-container-id");
        Instance clusterNode = eksClusterManager.getRegisteredClusterNodeInstance(cluster);
        assertNotNull(clusterNode);
        String nodeId = clusterNode.getInstanceId();

        try {
            String instId = launchInstance(account, "us-east-1", "t3.medium", null);
            RequestScopes.runAs(account, () -> eksService.joinInstance(clusterName, instId));
            assertTrue(RequestScopes.callAs(account, () -> eksService.isInstanceJoined(clusterName, instId)));

            String volumeId = ec2Form(account, "CreateVolume", "AvailabilityZone", "us-east-1a", "Size", "10", "VolumeType", "gp3")
                    .statusCode(200).extract().path("CreateVolumeResponse.volumeId");
            ec2Form(account, "AttachVolume", "VolumeId", volumeId, "InstanceId", nodeId, "Device", "/dev/xvdf")
                    .statusCode(200).body("AttachVolumeResponse.status", equalTo("attaching"));
            ec2Form(account, "DetachVolume", "VolumeId", volumeId, "InstanceId", nodeId, "Device", "/dev/xvdf")
                    .statusCode(200).body("DetachVolumeResponse.status", equalTo("detaching"));
        } finally {
            eksClusterManager.unregisterMetadataEndpoint(cluster);
        }
    }

    @Test
    void joinRejectsCrossClusterAndCrossRegionInstances() {
        String account = "123456789012";
        String cName1 = "multi-1-" + UUID.randomUUID().toString().substring(0, 8);
        String cName2 = "multi-2-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster c1 = createTestCluster(cName1, account), c2 = createTestCluster(cName2, account);
        try {
            String instId = launchInstance(account, "us-east-1", "t3.medium", null);
            RequestScopes.runAs(account, () -> eksService.joinInstance(cName1, instId));
            assertTrue(RequestScopes.callAs(account, () -> eksService.isInstanceJoined(cName1, instId)));
            assertThrows(AwsException.class, () -> RequestScopes.runAs(account, () -> eksService.joinInstance(cName2, instId)));
            assertFalse(RequestScopes.callAs(account, () -> eksService.isInstanceJoined(cName2, instId)));

            String westInstId = launchInstance(account, "us-west-2", "t3.medium", null);
            assertThrows(AwsException.class, () ->
                    RequestScopes.runAs(account, "us-east-1", () -> eksService.joinInstance(cName1, westInstId)));
        } finally {
            eksClusterManager.unregisterMetadataEndpoint(c1);
            eksClusterManager.unregisterMetadataEndpoint(c2);
        }
    }

    private ValidatableResponse ec2Form(String account, String action, String... params) {
        RequestSpecification req = given().header("Authorization", auth(account, "ec2", "us-east-1")).formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            req.formParam(params[i], params[i + 1]);
        }
        return req.post("/").then();
    }

    private String launchInstance(String account, String region, String type, String userData) {
        RequestSpecification req = given().header("Authorization", auth(account, "ec2", region))
                .formParam("Action", "RunInstances").formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", type != null ? type : "t3.micro")
                .formParam("MinCount", "1").formParam("MaxCount", "1");
        if (userData != null) {
            req.formParam("UserData", Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8)));
        }
        return req.post("/").then().statusCode(200).extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    private Cluster createTestCluster(String name, String account) {
        CreateClusterRequest req = new CreateClusterRequest();
        req.setName(name);
        req.setRoleArn("arn:aws:iam::" + account + ":role/EksClusterRole");
        return RequestScopes.callAs(account, () -> eksService.createCluster(req));
    }

    private static String auth(String account, String service, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260917/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
