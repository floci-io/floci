package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EksClusterSecurityGroupIntegrationTest {

    private static final String JSON = "application/json";
    private static final String CLUSTER = "sg-it-cluster";

    private static String vpcId;
    private static String subnetId;
    private static String clusterSecurityGroupId;

    @Test
    @Order(1)
    void setupVpcAndSubnet() {
        vpcId = given()
                .formParam("Action", "CreateVpc")
                .formParam("CidrBlock", "10.0.0.0/16")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");

        subnetId = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", vpcId)
                .formParam("CidrBlock", "10.0.1.0/24")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    @Test
    @Order(2)
    void createClusterProvisionsSecurityGroup() {
        clusterSecurityGroupId = given().contentType(JSON)
                .body("{\"name\":\"" + CLUSTER + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\","
                        + "\"resourcesVpcConfig\":{\"subnetIds\":[\"" + subnetId + "\"]}}")
                .when().post("/clusters")
                .then().statusCode(200)
                .body("cluster.name", equalTo(CLUSTER))
                .body("cluster.resourcesVpcConfig.clusterSecurityGroupId", notNullValue())
                .extract().path("cluster.resourcesVpcConfig.clusterSecurityGroupId");

        assertFalse(clusterSecurityGroupId.isBlank());
        assertTrue(clusterSecurityGroupId.startsWith("sg-"));
    }

    @Test
    @Order(3)
    void describeClusterReturnsClusterSecurityGroupId() {
        given().contentType(JSON)
                .when().get("/clusters/" + CLUSTER)
                .then().statusCode(200)
                .body("cluster.resourcesVpcConfig.clusterSecurityGroupId", equalTo(clusterSecurityGroupId));
    }

    @Test
    @Order(4)
    void ec2DescribesClusterSecurityGroupWithTagsAndDescription() {
        given()
                .formParam("Action", "DescribeSecurityGroups")
                .formParam("GroupId.1", clusterSecurityGroupId)
                .when().post("/")
                .then().statusCode(200)
                .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.groupId", equalTo(clusterSecurityGroupId))
                .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.vpcId", equalTo(vpcId))
                .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.groupDescription",
                        equalTo("EKS created security group applied to ENI that is attached to EKS Control Plane master nodes, as well as any managed workloads."))
                .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.groupName",
                        startsWith("eks-cluster-sg-" + CLUSTER + "-"))
                .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.tagSet.item.find { it.key == 'aws:eks:cluster-name' }.value",
                        equalTo(CLUSTER))
                .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.tagSet.item.find { it.key == 'kubernetes.io/cluster/" + CLUSTER + "' }.value",
                        equalTo("owned"));
    }

    @Test
    @Order(5)
    void deleteClusterRemovesSecurityGroup() {
        given().contentType(JSON)
                .when().delete("/clusters/" + CLUSTER)
                .then().statusCode(200);

        given()
                .formParam("Action", "DescribeSecurityGroups")
                .formParam("GroupId.1", clusterSecurityGroupId)
                .when().post("/")
                .then().statusCode(200)
                .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.size()", equalTo(0));

        given()
                .formParam("Action", "DeleteSecurityGroup")
                .formParam("GroupId", clusterSecurityGroupId)
                .when().post("/")
                .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidGroup.NotFound"));
    }
}
