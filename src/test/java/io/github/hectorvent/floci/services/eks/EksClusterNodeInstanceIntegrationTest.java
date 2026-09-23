package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class EksClusterNodeInstanceIntegrationTest {

    @Inject
    EksClusterManager eksClusterManager;

    @Test
    void clusterNodeInstancesAreExposedThroughEc2QueryApi() {
        String account = "123456789012";
        String clusterName = "node-exp-" + UUID.randomUUID().toString().substring(0, 8);
        Cluster cluster = new Cluster();
        cluster.setName(clusterName);
        cluster.setArn("arn:aws:eks:us-east-1:" + account + ":cluster/" + clusterName);

        eksClusterManager.registerClusterNodeInstance(cluster, "mock-container-id");
        Instance nodeInstance = eksClusterManager.getRegisteredClusterNodeInstance(cluster);
        String nodeId = nodeInstance.getInstanceId();

        try {
            // DescribeInstances resolves external cluster node instance
            given().header("Authorization", auth(account, "ec2"))
                    .formParam("Action", "DescribeInstances")
                    .post("/")
                    .then()
                    .statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId",
                            equalTo(nodeId))
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                            equalTo("running"))
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceType",
                            equalTo("m5.large"));

            // DescribeInstances with InstanceId filter
            given().header("Authorization", auth(account, "ec2"))
                    .formParam("Action", "DescribeInstances")
                    .formParam("InstanceId.1", nodeId)
                    .post("/")
                    .then()
                    .statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceId",
                            equalTo(nodeId));

            // DescribeInstanceStatus includes running cluster node instance
            given().header("Authorization", auth(account, "ec2"))
                    .formParam("Action", "DescribeInstanceStatus")
                    .formParam("InstanceId.1", nodeId)
                    .post("/")
                    .then()
                    .statusCode(200)
                    .body("DescribeInstanceStatusResponse.instanceStatusSet.item.instanceId",
                            equalTo(nodeId));

            // AttachVolume succeeds against cluster node instance
            String volumeId = given().header("Authorization", auth(account, "ec2"))
                    .formParam("Action", "CreateVolume")
                    .formParam("AvailabilityZone", "us-east-1a")
                    .formParam("Size", "10")
                    .formParam("VolumeType", "gp3")
                    .post("/")
                    .then()
                    .statusCode(200)
                    .extract().path("CreateVolumeResponse.volumeId");

            given().header("Authorization", auth(account, "ec2"))
                    .formParam("Action", "AttachVolume")
                    .formParam("VolumeId", volumeId)
                    .formParam("InstanceId", nodeId)
                    .formParam("Device", "/dev/xvdf")
                    .post("/")
                    .then()
                    .statusCode(200)
                    .body("AttachVolumeResponse.status", equalTo("attaching"));

            given().header("Authorization", auth(account, "ec2"))
                    .formParam("Action", "DetachVolume")
                    .formParam("VolumeId", volumeId)
                    .formParam("InstanceId", nodeId)
                    .formParam("Device", "/dev/xvdf")
                    .post("/")
                    .then()
                    .statusCode(200)
                    .body("DetachVolumeResponse.status", equalTo("detaching"));

            // Lifecycle actions are rejected with OperationNotPermitted
            given().header("Authorization", auth(account, "ec2"))
                    .formParam("Action", "TerminateInstances")
                    .formParam("InstanceId.1", nodeId)
                    .post("/")
                    .then()
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("OperationNotPermitted"));

            given().header("Authorization", auth(account, "ec2"))
                    .formParam("Action", "StopInstances")
                    .formParam("InstanceId.1", nodeId)
                    .post("/")
                    .then()
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("OperationNotPermitted"));

            given().header("Authorization", auth(account, "ec2"))
                    .formParam("Action", "StartInstances")
                    .formParam("InstanceId.1", nodeId)
                    .post("/")
                    .then()
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("OperationNotPermitted"));

            given().header("Authorization", auth(account, "ec2"))
                    .formParam("Action", "RebootInstances")
                    .formParam("InstanceId.1", nodeId)
                    .post("/")
                    .then()
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("OperationNotPermitted"));
        } finally {
            eksClusterManager.unregisterMetadataEndpoint(cluster);
        }

        // After unregistering, node instance disappears from EC2
        given().header("Authorization", auth(account, "ec2"))
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", nodeId)
                .post("/")
                .then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260917/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
