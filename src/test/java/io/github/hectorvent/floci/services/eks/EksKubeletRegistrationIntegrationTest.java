package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreateClusterRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@QuarkusTest
@TestProfile(EksKubeletRegistrationIntegrationTest.ImageCatalogProfile.class)
class EksKubeletRegistrationIntegrationTest {

    public static final class ImageCatalogProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.image-catalog-path", "src/test/resources/ec2/kubelet-image-catalog.yaml");
        }
    }

    @Inject
    EksService eksService;

    @Inject
    Ec2Service ec2Service;

    @InjectSpy
    EksClusterManager manager;

    @Test
    void bootstrapJoinUsesConfiguredCatalogAndKeepsReplayFreeOfSyntheticWrites() {
        String account = "123456789012";
        String region = "us-east-1";
        String clusterName = "kubelet-" + UUID.randomUUID().toString().substring(0, 8);
        CreateClusterRequest request = new CreateClusterRequest();
        request.setName(clusterName);
        request.setRoleArn("arn:aws:iam::" + account + ":role/EksClusterRole");
        Cluster cluster = RequestScopes.callAs(account, region, () -> eksService.createCluster(request));
        cluster.setContainerId("mock-kubelet-control-plane");
        doReturn(new ContainerExec.Result(0L, "", "", false)).when(manager)
                .execInContainerForResult(anyString(), any(), anyInt());
        String script = "#!/bin/bash\n/etc/eks/bootstrap.sh " + clusterName;
        String instanceId = null;
        try {
            instanceId = given()
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260101/" + region
                            + "/ec2/aws4_request, SignedHeaders=host, Signature=abc")
                    .formParam("Action", "RunInstances")
                    .formParam("ImageId", "ami-kubelet-alias")
                    .formParam("InstanceType", "t4g.medium")
                    .formParam("MinCount", "1").formParam("MaxCount", "1")
                    .formParam("UserData", Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8)))
                    .post("/").then().statusCode(200)
                    .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
            String joinedId = instanceId;
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertTrue(RequestScopes.callAs(account, region, () -> eksService.isInstanceJoined(clusterName, joinedId)));
                Cluster stored = RequestScopes.callAs(account, region, () -> eksService.describeCluster(clusterName));
                assertEquals(region, stored.getJoinedInstanceIds().get(joinedId));
            });
            Instance instance = RequestScopes.callAs(account, region,
                    () -> ec2Service.getInstance(region, joinedId).orElseThrow());
            assertEquals("ami-kubelet-alias", instance.getImageId());
            manager.registerAllJoinedNodes(cluster, "recreated-control-plane");
            verify(manager, never()).execInContainerForResult(anyString(), any(), anyInt());
        } finally {
            String launchedId = instanceId;
            RequestScopes.runAs(account, region, () -> assertAll("Clean up worker and cluster independently",
                    () -> manager.unregisterMetadataEndpoint(cluster),
                    () -> {
                        if (launchedId != null) {
                            ec2Service.terminateInstances(region, List.of(launchedId));
                            assertEquals("terminated", ec2Service.getInstance(region, launchedId).orElseThrow().getState().getName());
                        }
                    },
                    () -> eksService.deleteCluster(clusterName)));
        }
    }
}
