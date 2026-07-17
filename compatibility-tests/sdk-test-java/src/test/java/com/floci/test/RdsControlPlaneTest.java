package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbSubnetGroupResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbSubnetGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeOrderableDbInstanceOptionsResponse;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RDS Control Plane")
class RdsControlPlaneTest {

    private static final Logger LOG = Logger.getLogger(RdsControlPlaneTest.class.getName());
    private static RdsClient rds;
    private static String subnetGroupName;
    private static String proxyName;
    private static List<String> subnetIds;

    @BeforeAll
    static void setup() {
        rds = TestFixtures.rdsClient();
        subnetGroupName = TestFixtures.uniqueName("rds-subnets");
        proxyName = TestFixtures.uniqueName("rds-proxy");
        try (Ec2Client ec2 = TestFixtures.ec2Client()) {
            DescribeSubnetsResponse response = ec2.describeSubnets();
            subnetIds = response.subnets().stream()
                    .map(subnet -> subnet.subnetId())
                    .sorted()
                    .limit(2)
                    .toList();
        }
        assertThat(subnetIds).hasSizeGreaterThanOrEqualTo(2);
    }

    @AfterAll
    static void cleanup() {
        if (rds != null) {
            try {
                rds.deleteDBProxy(b -> b.dbProxyName(proxyName));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to clean up RDS DB proxy " + proxyName, e);
            }
            try {
                rds.deleteDBSubnetGroup(b -> b.dbSubnetGroupName(subnetGroupName));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to clean up RDS subnet group " + subnetGroupName, e);
            }
            rds.close();
        }
    }

    @Test
    void sdkUnmarshalsDbSubnetGroupSubnets() {
        CreateDbSubnetGroupResponse createResponse = rds.createDBSubnetGroup(b -> b
                .dbSubnetGroupName(subnetGroupName)
                .dbSubnetGroupDescription("SDK subnet group shape")
                .subnetIds(subnetIds));

        assertThat(createResponse.dbSubnetGroup().subnets())
                .extracting("subnetIdentifier")
                .containsExactlyElementsOf(subnetIds);

        DescribeDbSubnetGroupsResponse describeResponse = rds.describeDBSubnetGroups(b -> b
                .dbSubnetGroupName(subnetGroupName));

        assertThat(describeResponse.dbSubnetGroups()).hasSize(1);
        assertThat(describeResponse.dbSubnetGroups().get(0).subnets())
                .extracting("subnetIdentifier")
                .containsExactlyElementsOf(subnetIds);
    }

    @Test
    void sdkDiscoversCurrentSmallGravitonPostgresOption() {
        DescribeOrderableDbInstanceOptionsResponse response = rds.describeOrderableDBInstanceOptions(b -> b
                .engine("postgres")
                .engineVersion("16.14")
                .dbInstanceClass("db.t4g.small"));

        assertThat(response.orderableDBInstanceOptions()).hasSize(1);
        assertThat(response.orderableDBInstanceOptions().get(0).engine()).isEqualTo("postgres");
        assertThat(response.orderableDBInstanceOptions().get(0).engineVersion()).isEqualTo("16.14");
        assertThat(response.orderableDBInstanceOptions().get(0).dbInstanceClass()).isEqualTo("db.t4g.small");
    }

    @Test
    void sdkRoundTripsDbProxyAndItsDefaultTargetGroup() {
        var created = rds.createDBProxy(b -> b
                .dbProxyName(proxyName)
                .engineFamily("POSTGRESQL")
                .roleArn("arn:aws:iam::000000000000:role/rds-proxy-test")
                .vpcSubnetIds(subnetIds)
                .vpcSecurityGroupIds("sg-proxy-test")
                .requireTLS(true)
                .debugLogging(true)
                .idleClientTimeout(120)
                .auth(a -> a.authScheme("SECRETS")
                        .secretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:rds-proxy-test")
                        .iamAuth("DISABLED")
                        .clientPasswordAuthType("POSTGRES_SCRAM_SHA_256")
                        .description("compatibility credentials"))
                .tags(t -> t.key("owner").value("compatibility")));

        assertThat(created.dbProxy().dbProxyName()).isEqualTo(proxyName);
        assertThat(created.dbProxy().engineFamily()).hasToString("POSTGRESQL");
        assertThat(created.dbProxy().requireTLS()).isTrue();
        assertThat(created.dbProxy().debugLogging()).isTrue();
        assertThat(created.dbProxy().idleClientTimeout()).isEqualTo(120);
        assertThat(created.dbProxy().vpcSecurityGroupIds()).containsExactly("sg-proxy-test");
        assertThat(created.dbProxy().auth()).singleElement().satisfies(auth -> {
            assertThat(auth.clientPasswordAuthTypeAsString()).isEqualTo("POSTGRES_SCRAM_SHA_256");
            assertThat(auth.description()).isEqualTo("compatibility credentials");
        });

        var targetGroups = rds.describeDBProxyTargetGroups(b -> b.dbProxyName(proxyName));
        assertThat(targetGroups.targetGroups()).singleElement().satisfies(targetGroup -> {
            assertThat(targetGroup.targetGroupName()).isEqualTo("default");
            assertThat(targetGroup.isDefault()).isTrue();
            assertThat(targetGroup.targetGroupArn()).contains(":target-group:prx-tg-");
        });

        var tags = rds.listTagsForResource(b -> b.resourceName(created.dbProxy().dbProxyArn()));
        assertThat(tags.tagList()).singleElement().satisfies(tag -> {
            assertThat(tag.key()).isEqualTo("owner");
            assertThat(tag.value()).isEqualTo("compatibility");
        });
    }
}
