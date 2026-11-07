package io.floci.aws.services.rds;

import io.floci.aws.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBClusterParameterGroup;
import software.amazon.awssdk.services.rds.model.DescribeDbClusterParameterGroupsResponse;
import software.amazon.awssdk.services.rds.model.RdsException;

import java.util.List;

import static io.floci.aws.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsDefaultClusterParameterArnIntegrationTest {

    private static final String GROUP_NAME = "default.aurora-postgresql16";

    @ParameterizedTest
    @CsvSource({"us-east-1,111111111111,aws", "eu-west-2,222222222222,aws",
            "cn-north-1,333333333333,aws-cn", "us-gov-west-1,444444444444,aws-us-gov"})
    void namedManagedDefaultsCarryTheRequestScopedArn(String region, String account, String partition) {
        try (RdsClient client = rdsClient(region, account)) {
            List<DBClusterParameterGroup> groups = client.describeDBClusterParameterGroups(request ->
                    request.dbClusterParameterGroupName(GROUP_NAME)).dbClusterParameterGroups();
            assertEquals(1, groups.size());
            DBClusterParameterGroup group = groups.get(0);
            assertEquals(GROUP_NAME, group.dbClusterParameterGroupName());
            assertEquals("aurora-postgresql16", group.dbParameterGroupFamily());
            assertEquals("Default cluster parameter group", group.description());
            assertEquals(arn(partition, region, account, GROUP_NAME), group.dbClusterParameterGroupArn());
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,111111111111,aws", "eu-west-2,222222222222,aws",
            "cn-north-1,333333333333,aws-cn", "us-gov-west-1,444444444444,aws-us-gov"})
    void listedManagedDefaultsCarryTheRequestScopedArns(String region, String account, String partition) {
        try (RdsClient client = rdsClient(region, account)) {
            List<DBClusterParameterGroup> groups = client.describeDBClusterParameterGroups()
                    .dbClusterParameterGroups().stream()
                    .filter(group -> group.dbClusterParameterGroupName().startsWith("default."))
                    .toList();
            assertTrue(groups.stream().anyMatch(group -> GROUP_NAME.equals(group.dbClusterParameterGroupName())));
            for (DBClusterParameterGroup group : groups) {
                assertEquals(arn(partition, region, account, group.dbClusterParameterGroupName()),
                        group.dbClusterParameterGroupArn());
            }
        }
    }

    @Test
    void describeDbClusterParameterGroupsPagesWithMaxRecordsAndMarkerAndValidatesBounds() {
        try (RdsClient client = rdsClient("us-east-1", "111111111111")) {
            DescribeDbClusterParameterGroupsResponse unpaged = client.describeDBClusterParameterGroups();
            List<DBClusterParameterGroup> all = unpaged.dbClusterParameterGroups();
            assertTrue(all.size() > 20);
            assertNull(unpaged.marker());

            DescribeDbClusterParameterGroupsResponse firstPage = client.describeDBClusterParameterGroups(
                    request -> request.maxRecords(20));
            assertEquals(all.subList(0, 20), firstPage.dbClusterParameterGroups());
            assertEquals("20", firstPage.marker());

            DescribeDbClusterParameterGroupsResponse secondPage = client.describeDBClusterParameterGroups(
                    request -> request.maxRecords(20).marker(firstPage.marker()));
            assertEquals(all.subList(20, all.size()), secondPage.dbClusterParameterGroups());
            assertNull(secondPage.marker());

            for (int invalid : List.of(19, 101)) {
                RdsException exception = assertThrows(RdsException.class, () ->
                        client.describeDBClusterParameterGroups(request -> request.maxRecords(invalid)));
                assertEquals(400, exception.statusCode());
                assertEquals("InvalidParameterValue", exception.awsErrorDetails().errorCode());
            }
        }
    }

    private static String arn(String partition, String region, String account, String name) {
        return "arn:" + partition + ":rds:" + region + ":" + account + ":cluster-pg:" + name;
    }
}
