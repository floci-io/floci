package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBClusterParameterGroup;

import java.util.List;

import static io.github.hectorvent.floci.testing.RdsRequests.rdsClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static String arn(String partition, String region, String account, String name) {
        return "arn:" + partition + ":rds:" + region + ":" + account + ":cluster-pg:" + name;
    }
}
