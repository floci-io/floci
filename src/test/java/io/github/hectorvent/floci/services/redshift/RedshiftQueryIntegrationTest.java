package io.github.hectorvent.floci.services.redshift;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedshiftQueryIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/redshift/aws4_request";
    private static final String CLUSTER = "floci-redshift-cluster";
    private static final String SUBNET_GROUP = "floci-redshift-subnets";
    private static final String PARAMETER_GROUP = "floci-redshift-params";
    private static final String CLUSTER_ARN = "arn:aws:redshift:us-east-1:000000000000:cluster:" + CLUSTER;
    private static final String PARAMETER_GROUP_ARN =
            "arn:aws:redshift:us-east-1:000000000000:parametergroup:" + PARAMETER_GROUP;

    @Test
    @Order(1)
    void createClusterSubnetGroupResolvesVpcFromItsSubnets() {
        given()
                .formParam("Action", "CreateClusterSubnetGroup")
                .formParam("ClusterSubnetGroupName", SUBNET_GROUP)
                .formParam("Description", "Floci Redshift subnets")
                .formParam("SubnetIds.SubnetIdentifier.1", "subnet-default-a")
                .formParam("SubnetIds.SubnetIdentifier.2", "subnet-default-b")
                .formParam("Tags.Tag.1.Key", "Project")
                .formParam("Tags.Tag.1.Value", "Floci")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body("CreateClusterSubnetGroupResponse.CreateClusterSubnetGroupResult.ClusterSubnetGroup"
                        + ".ClusterSubnetGroupName", equalTo(SUBNET_GROUP))
                .body("CreateClusterSubnetGroupResponse.CreateClusterSubnetGroupResult.ClusterSubnetGroup.VpcId",
                        equalTo("vpc-default"))
                .body("CreateClusterSubnetGroupResponse.CreateClusterSubnetGroupResult.ClusterSubnetGroup"
                        + ".SubnetGroupStatus", equalTo("Complete"))
                .body("CreateClusterSubnetGroupResponse.CreateClusterSubnetGroupResult.ClusterSubnetGroup"
                        + ".Subnets.Subnet.SubnetIdentifier", hasItem("subnet-default-b"))
                .body("CreateClusterSubnetGroupResponse.CreateClusterSubnetGroupResult.ClusterSubnetGroup"
                        + ".Subnets.Subnet[0].SubnetAvailabilityZone.Name", equalTo("us-east-1a"))
                .body("CreateClusterSubnetGroupResponse.CreateClusterSubnetGroupResult.ClusterSubnetGroup"
                        + ".Tags.Tag.Key", equalTo("Project"));
    }

    @Test
    @Order(2)
    void createClusterSubnetGroupRejectsAnUnknownSubnet() {
        given()
                .formParam("Action", "CreateClusterSubnetGroup")
                .formParam("ClusterSubnetGroupName", "floci-redshift-missing-subnets")
                .formParam("Description", "Bad subnets")
                .formParam("SubnetIds.SubnetIdentifier.1", "subnet-does-not-exist")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidSubnet"));
    }

    @Test
    @Order(3)
    void describeClusterSubnetGroupsSeesTheCreatedGroup() {
        given()
                .formParam("Action", "DescribeClusterSubnetGroups")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeClusterSubnetGroupsResponse.DescribeClusterSubnetGroupsResult"
                        + ".ClusterSubnetGroups.ClusterSubnetGroup.ClusterSubnetGroupName", equalTo(SUBNET_GROUP));
    }

    @Test
    @Order(4)
    void modifyClusterSubnetGroupReplacesTheSubnetListAndKeepsTags() {
        given()
                .formParam("Action", "ModifyClusterSubnetGroup")
                .formParam("ClusterSubnetGroupName", SUBNET_GROUP)
                .formParam("Description", "Floci Redshift subnets (all AZs)")
                .formParam("SubnetIds.SubnetIdentifier.1", "subnet-default-a")
                .formParam("SubnetIds.SubnetIdentifier.2", "subnet-default-b")
                .formParam("SubnetIds.SubnetIdentifier.3", "subnet-default-c")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ModifyClusterSubnetGroupResponse.ModifyClusterSubnetGroupResult.ClusterSubnetGroup"
                        + ".Description", equalTo("Floci Redshift subnets (all AZs)"))
                .body("ModifyClusterSubnetGroupResponse.ModifyClusterSubnetGroupResult.ClusterSubnetGroup"
                        + ".Subnets.Subnet.SubnetIdentifier", hasItem("subnet-default-c"))
                .body("ModifyClusterSubnetGroupResponse.ModifyClusterSubnetGroupResult.ClusterSubnetGroup"
                        + ".Tags.Tag.Key", equalTo("Project"));
    }

    @Test
    @Order(5)
    void createClusterParameterGroup() {
        given()
                .formParam("Action", "CreateClusterParameterGroup")
                .formParam("ParameterGroupName", PARAMETER_GROUP)
                .formParam("ParameterGroupFamily", "redshift-1.0")
                .formParam("Description", "Floci Redshift parameters")
                .formParam("Tags.Tag.1.Key", "Team")
                .formParam("Tags.Tag.1.Value", "Data")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateClusterParameterGroupResponse.CreateClusterParameterGroupResult.ClusterParameterGroup"
                        + ".ParameterGroupName", equalTo(PARAMETER_GROUP))
                .body("CreateClusterParameterGroupResponse.CreateClusterParameterGroupResult.ClusterParameterGroup"
                        + ".ParameterGroupFamily", equalTo("redshift-1.0"))
                .body("CreateClusterParameterGroupResponse.CreateClusterParameterGroupResult.ClusterParameterGroup"
                        + ".Tags.Tag.Value", equalTo("Data"));
    }

    @Test
    @Order(6)
    void modifyClusterParameterGroupStoresUserParameters() {
        given()
                .formParam("Action", "ModifyClusterParameterGroup")
                .formParam("ParameterGroupName", PARAMETER_GROUP)
                .formParam("Parameters.Parameter.1.ParameterName", "require_ssl")
                .formParam("Parameters.Parameter.1.ParameterValue", "true")
                .formParam("Parameters.Parameter.2.ParameterName", "query_group")
                .formParam("Parameters.Parameter.2.ParameterValue", "reporting")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ModifyClusterParameterGroupResponse.ModifyClusterParameterGroupResult.ParameterGroupName",
                        equalTo(PARAMETER_GROUP))
                .body("ModifyClusterParameterGroupResponse.ModifyClusterParameterGroupResult.ParameterGroupStatus",
                        containsString("reboot"));
    }

    @Test
    @Order(7)
    void describeClusterParametersFiltersOnSource() {
        given()
                .formParam("Action", "DescribeClusterParameters")
                .formParam("ParameterGroupName", PARAMETER_GROUP)
                .formParam("Source", "user")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeClusterParametersResponse.DescribeClusterParametersResult.Parameters"
                        + ".Parameter.ParameterName", hasItem("require_ssl"))
                .body("DescribeClusterParametersResponse.DescribeClusterParametersResult.Parameters"
                        + ".Parameter.ParameterName", hasItem("query_group"))
                .body("DescribeClusterParametersResponse.DescribeClusterParametersResult.Parameters"
                        + ".Parameter[0].Source", equalTo("user"));

        given()
                .formParam("Action", "DescribeClusterParameters")
                .formParam("ParameterGroupName", PARAMETER_GROUP)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeClusterParametersResponse.DescribeClusterParametersResult.Parameters"
                        + ".Parameter.ParameterName", hasItem("wlm_json_configuration"));
    }

    @Test
    @Order(8)
    void describeClusterParameterGroupsSeesTheCreatedGroupAndTheEngineDefault() {
        given()
                .formParam("Action", "DescribeClusterParameterGroups")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeClusterParameterGroupsResponse.DescribeClusterParameterGroupsResult.ParameterGroups"
                        + ".ClusterParameterGroup.ParameterGroupName", hasItem(PARAMETER_GROUP))
                .body("DescribeClusterParameterGroupsResponse.DescribeClusterParameterGroupsResult.ParameterGroups"
                        + ".ClusterParameterGroup.ParameterGroupName", hasItem("default.redshift-1.0"));
    }

    @Test
    @Order(9)
    void createClusterReportsTheTerminalStateOnTheFirstResponse() {
        given()
                .formParam("Action", "CreateCluster")
                .formParam("ClusterIdentifier", CLUSTER)
                .formParam("NodeType", "ra3.xlplus")
                .formParam("MasterUsername", "flociadmin")
                .formParam("MasterUserPassword", "Floci-Secret-1")
                .formParam("DBName", "analytics")
                .formParam("ClusterSubnetGroupName", SUBNET_GROUP)
                .formParam("ClusterParameterGroupName", PARAMETER_GROUP)
                .formParam("VpcSecurityGroupIds.VpcSecurityGroupId.1", "sg-default")
                .formParam("Tags.Tag.1.Key", "Project")
                .formParam("Tags.Tag.1.Value", "Floci")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateClusterResponse.CreateClusterResult.Cluster.ClusterIdentifier", equalTo(CLUSTER))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.ClusterStatus", equalTo("available"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.ClusterAvailabilityStatus",
                        equalTo("Available"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.NodeType", equalTo("ra3.xlplus"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.MasterUsername", equalTo("flociadmin"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.DBName", equalTo("analytics"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.Endpoint.Address",
                        containsString(CLUSTER + "."))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.Endpoint.Port", equalTo("5439"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.VpcId", equalTo("vpc-default"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.ClusterSubnetGroupName",
                        equalTo(SUBNET_GROUP))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.AvailabilityZone", equalTo("us-east-1a"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.ClusterParameterGroups"
                        + ".ClusterParameterGroup.ParameterGroupName", equalTo(PARAMETER_GROUP))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.VpcSecurityGroups.VpcSecurityGroup"
                        + ".VpcSecurityGroupId", equalTo("sg-default"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.NumberOfNodes", equalTo("1"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.ClusterNodes.member.NodeRole",
                        equalTo("SHARED"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.Tags.Tag.Key", equalTo("Project"))
                .body("CreateClusterResponse.CreateClusterResult.Cluster.ClusterNamespaceArn",
                        containsString("arn:aws:redshift:us-east-1:000000000000:namespace:"));
    }

    @Test
    @Order(10)
    void createClusterRejectsADuplicateIdentifier() {
        given()
                .formParam("Action", "CreateCluster")
                .formParam("ClusterIdentifier", CLUSTER)
                .formParam("NodeType", "ra3.xlplus")
                .formParam("MasterUsername", "flociadmin")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ClusterAlreadyExists"));
    }

    @Test
    @Order(11)
    void describeClustersReturnsTheTerminalStateFromTheFirstRead() {
        given()
                .formParam("Action", "DescribeClusters")
                .formParam("ClusterIdentifier", CLUSTER)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeClustersResponse.DescribeClustersResult.Clusters.Cluster.ClusterStatus",
                        equalTo("available"))
                .body("DescribeClustersResponse.DescribeClustersResult.Clusters.Cluster.ClusterAvailabilityStatus",
                        equalTo("Available"))
                .body("DescribeClustersResponse.DescribeClustersResult.Clusters.Cluster.Endpoint.Address",
                        notNullValue())
                .body("DescribeClustersResponse.DescribeClustersResult.Clusters.Cluster.Tags.Tag.Value",
                        equalTo("Floci"));
    }

    @Test
    @Order(12)
    void describeClustersRejectsAnUnknownIdentifier() {
        given()
                .formParam("Action", "DescribeClusters")
                .formParam("ClusterIdentifier", "floci-redshift-absent")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ClusterNotFound"));
    }

    @Test
    @Order(13)
    void createTagsDescribeTagsAndDeleteTagsRoundTripOnAClusterArn() {
        given()
                .formParam("Action", "CreateTags")
                .formParam("ResourceName", CLUSTER_ARN)
                .formParam("Tags.Tag.1.Key", "Environment")
                .formParam("Tags.Tag.1.Value", "test")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateTagsResponse.ResponseMetadata.RequestId", notNullValue());

        given()
                .formParam("Action", "DescribeTags")
                .formParam("ResourceName", CLUSTER_ARN)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTagsResponse.DescribeTagsResult.TaggedResources.TaggedResource.Tag.Key",
                        hasItem("Environment"))
                .body("DescribeTagsResponse.DescribeTagsResult.TaggedResources.TaggedResource[0].ResourceType",
                        equalTo("cluster"));

        given()
                .formParam("Action", "DeleteTags")
                .formParam("ResourceName", CLUSTER_ARN)
                .formParam("TagKeys.TagKey.1", "Environment")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeClusters")
                .formParam("ClusterIdentifier", CLUSTER)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeClustersResponse.DescribeClustersResult.Clusters.Cluster.Tags.Tag.Key",
                        equalTo("Project"));
    }

    @Test
    @Order(14)
    void describeTagsFiltersByResourceType() {
        given()
                .formParam("Action", "DescribeTags")
                .formParam("ResourceType", "parametergroup")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTagsResponse.DescribeTagsResult.TaggedResources.TaggedResource.ResourceName",
                        equalTo(PARAMETER_GROUP_ARN));
    }

    @Test
    @Order(15)
    void createTagsRejectsAnUnknownResource() {
        given()
                .formParam("Action", "CreateTags")
                .formParam("ResourceName", "arn:aws:redshift:us-east-1:000000000000:cluster:floci-redshift-absent")
                .formParam("Tags.Tag.1.Key", "Environment")
                .formParam("Tags.Tag.1.Value", "test")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ResourceNotFoundFault"));
    }

    @Test
    @Order(16)
    void modifyClusterAppliesTheChangeAndStaysAvailable() {
        given()
                .formParam("Action", "ModifyCluster")
                .formParam("ClusterIdentifier", CLUSTER)
                .formParam("ClusterType", "multi-node")
                .formParam("NumberOfNodes", "2")
                .formParam("PubliclyAccessible", "true")
                .formParam("AutomatedSnapshotRetentionPeriod", "7")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ModifyClusterResponse.ModifyClusterResult.Cluster.NumberOfNodes", equalTo("2"))
                .body("ModifyClusterResponse.ModifyClusterResult.Cluster.PubliclyAccessible", equalTo("true"))
                .body("ModifyClusterResponse.ModifyClusterResult.Cluster.AutomatedSnapshotRetentionPeriod",
                        equalTo("7"))
                .body("ModifyClusterResponse.ModifyClusterResult.Cluster.ClusterStatus", equalTo("available"));
    }

    @Test
    @Order(17)
    void rebootClusterReportsAvailableRatherThanAPendingReboot() {
        given()
                .formParam("Action", "RebootCluster")
                .formParam("ClusterIdentifier", CLUSTER)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("RebootClusterResponse.RebootClusterResult.Cluster.ClusterStatus", equalTo("available"));
    }

    @Test
    @Order(18)
    void deleteClusterSubnetGroupIsRefusedWhileAClusterUsesIt() {
        given()
                .formParam("Action", "DeleteClusterSubnetGroup")
                .formParam("ClusterSubnetGroupName", SUBNET_GROUP)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidClusterSubnetGroupStateFault"));
    }

    @Test
    @Order(19)
    void deleteClusterRemovesIt() {
        given()
                .formParam("Action", "DeleteCluster")
                .formParam("ClusterIdentifier", CLUSTER)
                .formParam("SkipFinalClusterSnapshot", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DeleteClusterResponse.DeleteClusterResult.Cluster.ClusterIdentifier", equalTo(CLUSTER))
                .body("DeleteClusterResponse.DeleteClusterResult.Cluster.ClusterStatus", equalTo("deleting"));

        given()
                .formParam("Action", "DescribeClusters")
                .formParam("ClusterIdentifier", CLUSTER)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ClusterNotFound"));
    }

    @Test
    @Order(20)
    void deleteClusterSubnetGroupRemovesIt() {
        given()
                .formParam("Action", "DeleteClusterSubnetGroup")
                .formParam("ClusterSubnetGroupName", SUBNET_GROUP)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DeleteClusterSubnetGroupResponse.ResponseMetadata.RequestId", notNullValue());

        given()
                .formParam("Action", "DescribeClusterSubnetGroups")
                .formParam("ClusterSubnetGroupName", SUBNET_GROUP)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ClusterSubnetGroupNotFoundFault"));
    }

    @Test
    @Order(21)
    void deleteClusterParameterGroupRemovesItAndRefusesTheEngineDefault() {
        given()
                .formParam("Action", "DeleteClusterParameterGroup")
                .formParam("ParameterGroupName", "default.redshift-1.0")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidClusterParameterGroupState"));

        given()
                .formParam("Action", "DeleteClusterParameterGroup")
                .formParam("ParameterGroupName", PARAMETER_GROUP)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeClusterParameterGroups")
                .formParam("ParameterGroupName", PARAMETER_GROUP)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ClusterParameterGroupNotFound"));
    }

    @Test
    @Order(22)
    void anUnimplementedActionFailsLoudlyInsteadOfReturningAStub() {
        given()
                .formParam("Action", "CreateClusterSnapshot")
                .formParam("SnapshotIdentifier", "floci-snap")
                .formParam("ClusterIdentifier", CLUSTER)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("UnknownOperationException"));
    }
}
