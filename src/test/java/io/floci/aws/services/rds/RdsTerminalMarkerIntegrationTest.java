package io.floci.aws.services.rds;

import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeDbClusterParameterGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbClusterParametersResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbClustersResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbParameterGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbParametersResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbSubnetGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeOptionGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeOrderableDbInstanceOptionsResponse;
import software.amazon.awssdk.services.rds.model.Parameter;

import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.RdsRequests.rds;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsTerminalMarkerIntegrationTest {

    private RdsClient client;
    private String instance;
    private String cluster;
    private String subnetGroup;
    private String parameterGroup;
    private String clusterParameterGroup;

    @BeforeEach
    void createResources() {
        client = AwsTestClients.create(RdsClient.builder(), "000000000000", Region.US_EAST_1);
        String name = "terminal-" + UUID.randomUUID().toString().substring(0, 12);
        parameterGroup = client.createDBParameterGroup(request -> request.dbParameterGroupName(name)
                .dbParameterGroupFamily("postgres16").description("terminal marker test"))
                .dbParameterGroup().dbParameterGroupName();
        clusterParameterGroup = client.createDBClusterParameterGroup(request -> request.dbClusterParameterGroupName(name)
                .dbParameterGroupFamily("aurora-mysql8.0").description("terminal marker test"))
                .dbClusterParameterGroup().dbClusterParameterGroupName();
        subnetGroup = client.createDBSubnetGroup(request -> request.dbSubnetGroupName(name)
                .dbSubnetGroupDescription("terminal marker test").subnetIds(
                        Ec2Service.defaultSubnetId("us-east-1", "a"),
                        Ec2Service.defaultSubnetId("us-east-1", "b")))
                .dbSubnetGroup().dbSubnetGroupName();
        instance = client.createDBInstance(request -> request.dbInstanceIdentifier(name).engine("postgres")
                .dbInstanceClass("db.t3.micro").allocatedStorage(20)
                .masterUsername("admin").masterUserPassword("Password12345")
                .dbParameterGroupName(parameterGroup).dbSubnetGroupName(subnetGroup))
                .dbInstance().dbInstanceIdentifier();
        cluster = client.createDBCluster(request -> request.dbClusterIdentifier(name).engine("aurora-mysql")
                .masterUsername("admin").masterUserPassword("Password12345")
                .dbClusterParameterGroupName(clusterParameterGroup))
                .dbCluster().dbClusterIdentifier();
    }

    @AfterEach
    void removeResources() {
        try (RdsClient owned = client) {
            assertAll("Delete instances and clusters before their groups",
                    () -> {
                        if (instance != null) {
                            owned.deleteDBInstance(request -> request.dbInstanceIdentifier(instance).skipFinalSnapshot(true));
                        }
                    },
                    () -> {
                        if (cluster != null) {
                            owned.deleteDBCluster(request -> request.dbClusterIdentifier(cluster).skipFinalSnapshot(true));
                        }
                    },
                    () -> {
                        if (subnetGroup != null) {
                            owned.deleteDBSubnetGroup(request -> request.dbSubnetGroupName(subnetGroup));
                        }
                    },
                    () -> {
                        if (parameterGroup != null) {
                            owned.deleteDBParameterGroup(request -> request.dbParameterGroupName(parameterGroup));
                        }
                    },
                    () -> {
                        if (clusterParameterGroup != null) {
                            owned.deleteDBClusterParameterGroup(request ->
                                    request.dbClusterParameterGroupName(clusterParameterGroup));
                        }
                    });
        }
    }

    @Test
    void completeResponsesOmitMarkersAndRetainTheirResults() {
        DescribeDbInstancesResponse instances = client.describeDBInstances(request -> request.dbInstanceIdentifier(instance));
        DescribeDbClustersResponse clusters = client.describeDBClusters(request -> request.dbClusterIdentifier(cluster));
        DescribeDbSubnetGroupsResponse subnets = client.describeDBSubnetGroups(request -> request.dbSubnetGroupName(subnetGroup));
        DescribeDbParameterGroupsResponse groups = client.describeDBParameterGroups(request ->
                request.dbParameterGroupName(parameterGroup));
        DescribeDbClusterParameterGroupsResponse clusterGroups = client.describeDBClusterParameterGroups(request ->
                request.dbClusterParameterGroupName(clusterParameterGroup));
        DescribeDbParametersResponse parameters = client.describeDBParameters(request -> request.dbParameterGroupName(parameterGroup));
        DescribeDbClusterParametersResponse clusterParameters = client.describeDBClusterParameters(request ->
                request.dbClusterParameterGroupName(clusterParameterGroup));
        DescribeOrderableDbInstanceOptionsResponse options = client.describeOrderableDBInstanceOptions(request ->
                request.engine("postgres"));
        DescribeOptionGroupsResponse optionGroups = client.describeOptionGroups(request -> request.engineName("postgres"));

        assertEquals(instance, instances.dbInstances().getFirst().dbInstanceIdentifier());
        assertEquals(cluster, clusters.dbClusters().getFirst().dbClusterIdentifier());
        assertEquals(subnetGroup, subnets.dbSubnetGroups().getFirst().dbSubnetGroupName());
        assertEquals(parameterGroup, groups.dbParameterGroups().getFirst().dbParameterGroupName());
        assertEquals(clusterParameterGroup, clusterGroups.dbClusterParameterGroups().getFirst().dbClusterParameterGroupName());
        assertTrue(parameters.parameters().isEmpty());
        assertTrue(clusterParameters.parameters().isEmpty());
        assertFalse(options.orderableDBInstanceOptions().isEmpty());
        assertFalse(optionGroups.optionGroupsList().isEmpty());

        assertAll("A complete SDK response has no continuation marker",
                () -> assertNull(instances.marker(), "DescribeDBInstances"),
                () -> assertNull(clusters.marker(), "DescribeDBClusters"),
                () -> assertNull(subnets.marker(), "DescribeDBSubnetGroups"),
                () -> assertNull(groups.marker(), "DescribeDBParameterGroups"),
                () -> assertNull(clusterGroups.marker(), "DescribeDBClusterParameterGroups"),
                () -> assertNull(parameters.marker(), "DescribeDBParameters"),
                () -> assertNull(clusterParameters.marker(), "DescribeDBClusterParameters"),
                () -> assertNull(options.marker(), "DescribeOrderableDBInstanceOptions"),
                () -> assertNull(optionGroups.marker(), "DescribeOptionGroups"));
        assertAll("Complete wire responses omit the marker element",
                () -> assertTerminalXml("DescribeDBInstances", Map.of("DBInstanceIdentifier", instance)),
                () -> assertTerminalXml("DescribeDBClusters", Map.of("DBClusterIdentifier", cluster)),
                () -> assertTerminalXml("DescribeDBSubnetGroups", Map.of("DBSubnetGroupName", subnetGroup)),
                () -> assertTerminalXml("DescribeDBParameterGroups", Map.of("DBParameterGroupName", parameterGroup)),
                () -> assertTerminalXml("DescribeDBClusterParameterGroups",
                        Map.of("DBClusterParameterGroupName", clusterParameterGroup)),
                () -> assertTerminalXml("DescribeDBParameters", Map.of("DBParameterGroupName", parameterGroup)),
                () -> assertTerminalXml("DescribeDBClusterParameters",
                        Map.of("DBClusterParameterGroupName", clusterParameterGroup)),
                () -> assertTerminalXml("DescribeOrderableDBInstanceOptions", Map.of("Engine", "postgres")),
                () -> assertTerminalXml("DescribeOptionGroups", Map.of("EngineName", "postgres")));
    }

    @Test
    void populatedParametersAlsoEndWithoutAMarker() {
        Parameter parameter = Parameter.builder().parameterName("max_connections").parameterValue("150")
                .applyMethod("immediate").build();
        client.modifyDBParameterGroup(request -> request.dbParameterGroupName(parameterGroup).parameters(parameter));
        client.modifyDBClusterParameterGroup(request ->
                request.dbClusterParameterGroupName(clusterParameterGroup).parameters(parameter));
        DescribeDbParametersResponse parameters = client.describeDBParameters(request -> request.dbParameterGroupName(parameterGroup));
        DescribeDbClusterParametersResponse clusterParameters = client.describeDBClusterParameters(request ->
                request.dbClusterParameterGroupName(clusterParameterGroup));
        assertEquals("150", parameters.parameters().getFirst().parameterValue());
        assertEquals("150", clusterParameters.parameters().getFirst().parameterValue());
        assertAll(
                () -> assertNull(parameters.marker(), "DescribeDBParameters"),
                () -> assertNull(clusterParameters.marker(), "DescribeDBClusterParameters"),
                () -> assertTerminalXml("DescribeDBParameters", Map.of("DBParameterGroupName", parameterGroup)),
                () -> assertTerminalXml("DescribeDBClusterParameters",
                        Map.of("DBClusterParameterGroupName", clusterParameterGroup)));
    }

    private static void assertTerminalXml(String action, Map<String, String> parameters) {
        String xml = rds(action).formParams(parameters).post("/").then().statusCode(200).extract().asString();
        assertFalse(xml.contains("<Marker"), xml);
    }
}
