package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

/**
 * EKS managed node group REST flow (issue #1137). The key regression: {@code
 * POST /clusters/{name}/node-groups} must route to EKS and return a {@code nodegroup}
 * envelope, not fall through to S3's path-style catch-all (which returned a 400 "POST
 * requires ?uploads parameter").
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksNodegroupIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "ng-it-cluster";
    private static final String NODE_ROLE = "arn:aws:iam::000000000000:role/eks-node-role";

    @Test
    @Order(1)
    void createCluster() {
        given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\"}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER));
    }

    @Test
    @Order(2)
    void createNodeGroupRoutesToEksNotS3() {
        given().contentType(JSON)
                .body("{\"nodegroupName\":\"ng1\",\"subnets\":[\"subnet-abc\"],\"nodeRole\":\"" + NODE_ROLE + "\","
                        + "\"scalingConfig\":{\"minSize\":1,\"maxSize\":3,\"desiredSize\":2}}")
                .when().post("/clusters/" + CLUSTER + "/node-groups")
                .then()
                .statusCode(200)
                // Regression: a nodegroup envelope proves EKS handled it, not S3.
                .body("nodegroup.nodegroupName", equalTo("ng1"))
                .body("nodegroup.clusterName", equalTo(CLUSTER))
                .body("nodegroup.nodegroupArn", containsString("nodegroup/" + CLUSTER + "/ng1/"))
                .body("nodegroup.status", equalTo("ACTIVE"))
                .body("nodegroup.scalingConfig.desiredSize", equalTo(2))
                .body("nodegroup.amiType", notNullValue());
    }

    @Test
    @Order(3)
    void listNodeGroups() {
        given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER + "/node-groups")
                .then().statusCode(200)
                .body("nodegroups", hasItem("ng1"));
    }

    @Test
    @Order(4)
    void describeNodeGroup() {
        given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER + "/node-groups/ng1")
                .then().statusCode(200)
                .body("nodegroup.nodegroupName", equalTo("ng1"))
                .body("nodegroup.subnets[0]", equalTo("subnet-abc"))
                .body("nodegroup.nodeRole", equalTo(NODE_ROLE));
    }

    @Inject
    Ec2Service ec2Service;

    @Test
    @Order(5)
    void deleteNodeGroup() {
        given().contentType(JSON)
                .when().delete("/clusters/" + CLUSTER + "/node-groups/ng1")
                .then().statusCode(200)
                .body("nodegroup.status", equalTo("DELETING"));

        given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER + "/node-groups/ng1")
                .then().statusCode(404);
    }

    @Test
    @Order(6)
    void createNodeGroupWithLaunchTemplateUserData() {
        LaunchTemplateData data = new LaunchTemplateData();
        data.setUserData(Base64.getEncoder().encodeToString("#!/bin/sh\necho hi".getBytes(StandardCharsets.UTF_8)));
        LaunchTemplate lt = ec2Service.createLaunchTemplate("us-east-1", "eks-it-lt", data, List.of());

        given().contentType(JSON)
                .body("{\"nodegroupName\":\"ng-lt\",\"subnets\":[\"subnet-abc\"],\"nodeRole\":\"" + NODE_ROLE + "\","
                        + "\"launchTemplate\":{\"id\":\"" + lt.getLaunchTemplateId() + "\",\"version\":\"1\"}}")
                .when().post("/clusters/" + CLUSTER + "/node-groups")
                .then().statusCode(200)
                .body("nodegroup.nodegroupName", equalTo("ng-lt"))
                .body("nodegroup.launchTemplate.id", equalTo(lt.getLaunchTemplateId()));
    }
}
