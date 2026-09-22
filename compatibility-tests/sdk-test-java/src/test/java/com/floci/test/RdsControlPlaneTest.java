package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.ConnectionPoolConfigurationInfo;
import software.amazon.awssdk.services.rds.model.CreateDbProxyResponse;
import software.amazon.awssdk.services.rds.model.CreateDbSubnetGroupResponse;
import software.amazon.awssdk.services.rds.model.CreateOptionGroupResponse;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBClusterSnapshot;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.DBProxyTarget;
import software.amazon.awssdk.services.rds.model.DBSnapshot;
import software.amazon.awssdk.services.rds.model.DbClusterSnapshotNotFoundException;
import software.amazon.awssdk.services.rds.model.DbSnapshotAlreadyExistsException;
import software.amazon.awssdk.services.rds.model.DbSnapshotNotFoundException;
import software.amazon.awssdk.services.rds.model.InvalidDbInstanceStateException;
import software.amazon.awssdk.services.rds.model.DescribeDbSubnetGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeOptionGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeOrderableDbInstanceOptionsResponse;
import software.amazon.awssdk.services.rds.model.InvalidOptionGroupStateException;
import software.amazon.awssdk.services.rds.model.ModifyOptionGroupResponse;
import software.amazon.awssdk.services.rds.model.OptionConfiguration;
import software.amazon.awssdk.services.rds.model.OptionGroupNotFoundException;
import software.amazon.awssdk.services.rds.model.OptionSetting;
import software.amazon.awssdk.services.rds.model.Tag;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("RDS Control Plane")
class RdsControlPlaneTest {

    private static final Logger LOG = Logger.getLogger(RdsControlPlaneTest.class.getName());
    private static RdsClient rds;
    private static String subnetGroupName;
    private static String proxyName;
    private static String serverlessClusterName;
    private static List<String> subnetIds;

    @BeforeAll
    static void setup() {
        rds = TestFixtures.rdsClient();
        subnetGroupName = TestFixtures.uniqueName("rds-subnets");
        proxyName = TestFixtures.uniqueName("rds-proxy");
        serverlessClusterName = TestFixtures.uniqueName("rds-serverless");
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
                rds.deleteDBCluster(b -> b
                        .dbClusterIdentifier(serverlessClusterName)
                        .skipFinalSnapshot(true));
            } catch (Exception e) {
                LOG.log(Level.FINE, "RDS Serverless v2 cluster already absent during cleanup "
                        + serverlessClusterName, e);
            }
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
    void sdkRoundTripsAuroraServerlessV2ScalingConfiguration() {
        var created = rds.createDBCluster(b -> b
                .dbClusterIdentifier(serverlessClusterName)
                .engine("aurora-postgresql")
                .masterUsername("admin")
                .masterUserPassword("password")
                .serverlessV2ScalingConfiguration(c -> c
                        .minCapacity(0.0)
                        .maxCapacity(16.0)));

        assertThat(created.dbCluster().engine()).isEqualTo("aurora-postgresql");
        assertThat(created.dbCluster().serverlessV2ScalingConfiguration().minCapacity())
                .isEqualTo(0.0);
        assertThat(created.dbCluster().serverlessV2ScalingConfiguration().maxCapacity())
                .isEqualTo(16.0);
        assertThat(created.dbCluster().serverlessV2ScalingConfiguration().secondsUntilAutoPause())
                .isEqualTo(300);

        var modified = rds.modifyDBCluster(b -> b
                .dbClusterIdentifier(serverlessClusterName)
                .serverlessV2ScalingConfiguration(c -> c
                        .secondsUntilAutoPause(600)));
        assertThat(modified.dbCluster().serverlessV2ScalingConfiguration().minCapacity())
                .isEqualTo(0.0);
        assertThat(modified.dbCluster().serverlessV2ScalingConfiguration().maxCapacity())
                .isEqualTo(16.0);
        assertThat(modified.dbCluster().serverlessV2ScalingConfiguration().secondsUntilAutoPause())
                .isEqualTo(600);

        var described = rds.describeDBClusters(b -> b
                .dbClusterIdentifier(serverlessClusterName)).dbClusters().get(0);
        assertThat(described.serverlessV2ScalingConfiguration().minCapacity()).isEqualTo(0.0);
        assertThat(described.serverlessV2ScalingConfiguration().maxCapacity()).isEqualTo(16.0);
        assertThat(described.serverlessV2ScalingConfiguration().secondsUntilAutoPause())
                .isEqualTo(600);
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
                .endpointNetworkType("IPV4")
                .targetConnectionNetworkType("IPV4")
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
        assertThat(created.dbProxy().endpointNetworkTypeAsString()).isEqualTo("IPV4");
        assertThat(created.dbProxy().targetConnectionNetworkTypeAsString()).isEqualTo("IPV4");
        assertThat(created.dbProxy().vpcId()).isNotBlank();
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

    @Test
    void sdkRoundTripsIamDefaultAuthAndProxyUpdates() {
        String mutableProxyName = TestFixtures.uniqueName("rds-proxy-mutable");
        try {
            var created = rds.createDBProxy(b -> b
                    .dbProxyName(mutableProxyName)
                    .engineFamily("MYSQL")
                    .roleArn("arn:aws:iam::000000000000:role/rds-proxy-initial")
                    .vpcSubnetIds(subnetIds)
                    .vpcSecurityGroupIds("sg-proxy-initial")
                    .defaultAuthScheme("IAM_AUTH")
                    .requireTLS(true)
                    .debugLogging(false)
                    .idleClientTimeout(300));

            assertThat(created.dbProxy().defaultAuthScheme()).isEqualTo("IAM_AUTH");
            assertThat(created.dbProxy().auth()).isEmpty();

            var modified = rds.modifyDBProxy(b -> b
                    .dbProxyName(mutableProxyName)
                    .roleArn("arn:aws:iam::000000000000:role/rds-proxy-updated")
                    .securityGroups("sg-proxy-updated-a", "sg-proxy-updated-b")
                    .requireTLS(false)
                    .debugLogging(true)
                    .idleClientTimeout(600));

            assertThat(modified.dbProxy().dbProxyName()).isEqualTo(mutableProxyName);
            assertThat(modified.dbProxy().defaultAuthScheme()).isEqualTo("IAM_AUTH");
            assertThat(modified.dbProxy().roleArn())
                    .isEqualTo("arn:aws:iam::000000000000:role/rds-proxy-updated");
            assertThat(modified.dbProxy().vpcSecurityGroupIds())
                    .containsExactly("sg-proxy-updated-a", "sg-proxy-updated-b");
            assertThat(modified.dbProxy().requireTLS()).isFalse();
            assertThat(modified.dbProxy().debugLogging()).isTrue();
            assertThat(modified.dbProxy().idleClientTimeout()).isEqualTo(600);
            assertThat(modified.dbProxy().updatedDate()).isAfterOrEqualTo(created.dbProxy().updatedDate());

            var described = rds.describeDBProxies(b -> b.dbProxyName(mutableProxyName));
            assertThat(described.dbProxies()).singleElement().satisfies(proxy -> {
                assertThat(proxy.defaultAuthScheme()).isEqualTo("IAM_AUTH");
                assertThat(proxy.auth()).isEmpty();
                assertThat(proxy.roleArn())
                        .isEqualTo("arn:aws:iam::000000000000:role/rds-proxy-updated");
                assertThat(proxy.vpcSecurityGroupIds())
                        .containsExactly("sg-proxy-updated-a", "sg-proxy-updated-b");
                assertThat(proxy.requireTLS()).isFalse();
                assertThat(proxy.debugLogging()).isTrue();
                assertThat(proxy.idleClientTimeout()).isEqualTo(600);
            });

            var modifiedTargetGroup = rds.modifyDBProxyTargetGroup(b -> b
                    .dbProxyName(mutableProxyName)
                    .targetGroupName("default")
                    .connectionPoolConfig(pool -> pool
                            .maxConnectionsPercent(73)
                            .maxIdleConnectionsPercent(41)
                            .connectionBorrowTimeout(37)
                            .initQuery("SET sql_mode='STRICT_ALL_TABLES'")
                            .sessionPinningFilters("EXCLUDE_VARIABLE_SETS")));

            assertPoolConfiguration(modifiedTargetGroup.dbProxyTargetGroup().connectionPoolConfig());

            var describedTargetGroups = rds.describeDBProxyTargetGroups(b -> b
                    .dbProxyName(mutableProxyName)
                    .targetGroupName("default"));
            assertThat(describedTargetGroups.targetGroups()).singleElement().satisfies(targetGroup ->
                    assertPoolConfiguration(targetGroup.connectionPoolConfig()));
        } finally {
            deleteProxy(rds, mutableProxyName);
        }
    }

    @Test
    void sdkScopesTheSameDbProxyNameBySignedRegion() {
        String regionalProxyName = TestFixtures.uniqueName("rds-proxy-regional");
        try (RdsClient east = rdsClient(Region.US_EAST_1);
             RdsClient west = rdsClient(Region.US_WEST_2)) {
            // Subnet ids are region-scoped on real AWS (and on floci, since #21), so each proxy
            // needs subnets from its own signed region rather than the shared us-east-1 subnetIds.
            var eastProxy = createIamProxy(east, regionalProxyName, subnetIdsFor(Region.US_EAST_1));
            var westProxy = createIamProxy(west, regionalProxyName, subnetIdsFor(Region.US_WEST_2));

            assertThat(eastProxy.dbProxy().dbProxyArn()).contains(":rds:us-east-1:");
            assertThat(westProxy.dbProxy().dbProxyArn()).contains(":rds:us-west-2:");
            assertThat(westProxy.dbProxy().dbProxyArn()).isNotEqualTo(eastProxy.dbProxy().dbProxyArn());

            assertThat(east.describeDBProxies(b -> b.dbProxyName(regionalProxyName)).dbProxies())
                    .singleElement()
                    .satisfies(proxy -> assertThat(proxy.dbProxyArn()).contains(":rds:us-east-1:"));
            assertThat(west.describeDBProxies(b -> b.dbProxyName(regionalProxyName)).dbProxies())
                    .singleElement()
                    .satisfies(proxy -> assertThat(proxy.dbProxyArn()).contains(":rds:us-west-2:"));
        } finally {
            try (RdsClient east = rdsClient(Region.US_EAST_1);
                 RdsClient west = rdsClient(Region.US_WEST_2)) {
                deleteProxy(east, regionalProxyName);
                deleteProxy(west, regionalProxyName);
            }
        }
    }

    @Test
    void sdkScopesTheSameDbInstanceNameBySignedRegion() {
        String regionalInstanceName = TestFixtures.uniqueName("rds-db-regional");
        try (RdsClient east = rdsClient(Region.US_EAST_1);
             RdsClient west = rdsClient(Region.US_WEST_2)) {
            var eastInstance = createDbInstance(east, regionalInstanceName, "east-secret");
            var westInstance = createDbInstance(west, regionalInstanceName, "west-secret");

            assertThat(eastInstance.dbInstance().dbInstanceArn()).contains(":rds:us-east-1:");
            assertThat(westInstance.dbInstance().dbInstanceArn()).contains(":rds:us-west-2:");
            assertThat(westInstance.dbInstance().dbInstanceArn())
                    .isNotEqualTo(eastInstance.dbInstance().dbInstanceArn());

            east.modifyDBInstance(b -> b
                    .dbInstanceIdentifier(regionalInstanceName)
                    .masterUserPassword("east-updated"));
            assertThat(east.describeDBInstances(b -> b
                    .dbInstanceIdentifier(regionalInstanceName)).dbInstances())
                    .singleElement()
                    .satisfies(instance -> assertThat(instance.dbInstanceArn())
                            .contains(":rds:us-east-1:"));
            assertThat(west.describeDBInstances(b -> b
                    .dbInstanceIdentifier(regionalInstanceName)).dbInstances())
                    .singleElement()
                    .satisfies(instance -> assertThat(instance.dbInstanceArn())
                            .contains(":rds:us-west-2:"));

            east.deleteDBInstance(b -> b
                    .dbInstanceIdentifier(regionalInstanceName)
                    .skipFinalSnapshot(true));
            assertThat(east.describeDBInstances().dbInstances())
                    .noneMatch(instance -> regionalInstanceName.equals(
                            instance.dbInstanceIdentifier()));
            assertThat(west.describeDBInstances(b -> b
                    .dbInstanceIdentifier(regionalInstanceName)).dbInstances())
                    .singleElement();
        } finally {
            try (RdsClient east = rdsClient(Region.US_EAST_1);
                 RdsClient west = rdsClient(Region.US_WEST_2)) {
                deleteDbInstance(east, regionalInstanceName);
                deleteDbInstance(west, regionalInstanceName);
            }
        }
    }

    @Test
    void sdkRegistersDescribesAndDeregistersDbProxyInstanceTarget() {
        String targetProxyName = TestFixtures.uniqueName("rds-proxy-target");
        String targetInstanceName = TestFixtures.uniqueName("rds-db-target");
        boolean registered = false;
        try {
            var instance = createDbInstance(rds, targetInstanceName, "target-secret");
            rds.createDBProxy(b -> b
                    .dbProxyName(targetProxyName)
                    .engineFamily("POSTGRESQL")
                    .roleArn("arn:aws:iam::000000000000:role/rds-proxy-target")
                    .vpcSubnetIds(subnetIds)
                    .auth(a -> a.authScheme("SECRETS")
                            .secretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:rds-proxy-target")
                            .iamAuth("DISABLED")));

            var registerResponse = rds.registerDBProxyTargets(b -> b
                    .dbProxyName(targetProxyName)
                    .dbInstanceIdentifiers(targetInstanceName));
            registered = true;
            assertThat(registerResponse.dbProxyTargets()).singleElement().satisfies(target ->
                    assertInstanceProxyTarget(target, targetInstanceName,
                            instance.dbInstance().dbInstanceArn()));

            var described = rds.describeDBProxyTargets(b -> b
                    .dbProxyName(targetProxyName));
            assertThat(described.targets()).singleElement().satisfies(target ->
                    assertInstanceProxyTarget(target, targetInstanceName,
                            instance.dbInstance().dbInstanceArn()));

            rds.deregisterDBProxyTargets(b -> b
                    .dbProxyName(targetProxyName)
                    .dbInstanceIdentifiers(targetInstanceName));
            registered = false;
            assertThat(rds.describeDBProxyTargets(b -> b
                    .dbProxyName(targetProxyName)).targets()).isEmpty();
        } finally {
            if (registered) {
                deregisterProxyTarget(rds, targetProxyName, targetInstanceName);
            }
            deleteProxy(rds, targetProxyName);
            deleteDbInstance(rds, targetInstanceName);
        }
    }

    @Test
    void sdkStopsAndStartsAStandaloneInstance() throws Exception {
        String instanceName = TestFixtures.uniqueName("rds-stop-db");
        try {
            createDbInstance(rds, instanceName, "stop-secret");
            String endpoint = rds.describeDBInstances(b -> b.dbInstanceIdentifier(instanceName))
                    .dbInstances().get(0).endpoint().address();

            DBInstance stopping = rds.stopDBInstance(b -> b.dbInstanceIdentifier(instanceName)).dbInstance();
            assertThat(stopping.dbInstanceStatus()).isEqualTo("stopping");
            assertThat(rds.describeDBInstances(b -> b.dbInstanceIdentifier(instanceName))
                    .dbInstances().get(0).dbInstanceStatus()).isEqualTo("stopped");

            assertThatThrownBy(() -> rds.stopDBInstance(b -> b.dbInstanceIdentifier(instanceName)))
                    .isInstanceOf(InvalidDbInstanceStateException.class);
            assertThatThrownBy(() -> rds.modifyDBInstance(b -> b
                    .dbInstanceIdentifier(instanceName).masterUserPassword("changed-secret")))
                    .isInstanceOf(InvalidDbInstanceStateException.class);

            DBInstance starting = rds.startDBInstance(b -> b.dbInstanceIdentifier(instanceName)).dbInstance();
            assertThat(starting.dbInstanceStatus()).isEqualTo("starting");
            DBInstance started = rds.describeDBInstances(b -> b.dbInstanceIdentifier(instanceName))
                    .dbInstances().get(0);
            assertThat(started.dbInstanceStatus()).isEqualTo("available");
            assertThat(started.endpoint().address()).isEqualTo(endpoint);
        } finally {
            deleteDbInstance(rds, instanceName);
        }
    }

    @Test
    void sdkCopiesModifiesAndDeletesManualSnapshots() {
        String instanceName = TestFixtures.uniqueName("rds-snap-db");
        String snapshotName = TestFixtures.uniqueName("rds-snap");
        String copyName = snapshotName + "-copy";
        try {
            createDbInstance(rds, instanceName, "snap-secret");
            String sourceArn = rds.createDBSnapshot(b -> b
                    .dbInstanceIdentifier(instanceName)
                    .dbSnapshotIdentifier(snapshotName)
                    .tags(Tag.builder().key("owner").value("platform").build()))
                    .dbSnapshot().dbSnapshotArn();

            DBSnapshot copy = rds.copyDBSnapshot(b -> b
                    .sourceDBSnapshotIdentifier(sourceArn)
                    .targetDBSnapshotIdentifier(copyName)
                    .copyTags(true)
                    .tags(Tag.builder().key("stage").value("test").build()))
                    .dbSnapshot();
            assertThat(copy.dbSnapshotIdentifier()).isEqualTo(copyName);
            assertThat(copy.status()).isEqualTo("available");
            assertThat(copy.snapshotType()).isEqualTo("manual");
            assertThat(copy.tagList()).extracting(Tag::key).containsExactlyInAnyOrder("owner", "stage");

            assertThatThrownBy(() -> rds.copyDBSnapshot(b -> b
                    .sourceDBSnapshotIdentifier(snapshotName)
                    .targetDBSnapshotIdentifier(copyName)))
                    .isInstanceOf(DbSnapshotAlreadyExistsException.class);

            DBSnapshot modified = rds.modifyDBSnapshot(b -> b
                    .dbSnapshotIdentifier(snapshotName)
                    .engineVersion("16.4"))
                    .dbSnapshot();
            assertThat(modified.engineVersion()).isEqualTo("16.4");

            DBSnapshot deleted = rds.deleteDBSnapshot(b -> b.dbSnapshotIdentifier(snapshotName)).dbSnapshot();
            assertThat(deleted.status()).isEqualTo("deleted");
            assertThatThrownBy(() -> rds.describeDBSnapshots(b -> b.dbSnapshotIdentifier(snapshotName)))
                    .isInstanceOf(DbSnapshotNotFoundException.class);
            assertThat(rds.describeDBSnapshots(b -> b.dbInstanceIdentifier(instanceName)).dbSnapshots())
                    .extracting(DBSnapshot::dbSnapshotIdentifier)
                    .containsExactly(copyName);
        } finally {
            try {
                rds.deleteDBSnapshot(b -> b.dbSnapshotIdentifier(copyName));
            } catch (Exception ignored) {}
            deleteDbInstance(rds, instanceName);
        }
    }

    @Test
    void sdkCreatesCopiesRestoresAndDeletesClusterSnapshots() {
        String clusterName = TestFixtures.uniqueName("rds-csnap-cluster");
        String snapshotName = TestFixtures.uniqueName("rds-csnap");
        String copyName = snapshotName + "-copy";
        String restoredName = clusterName + "-restored";
        try {
            rds.createDBCluster(b -> b
                    .dbClusterIdentifier(clusterName)
                    .engine("aurora-postgresql")
                    .engineVersion("16.3")
                    .masterUsername("admin")
                    .masterUserPassword("csnap-secret")
                    .databaseName("app"));

            DBClusterSnapshot snapshot = rds.createDBClusterSnapshot(b -> b
                    .dbClusterSnapshotIdentifier(snapshotName)
                    .dbClusterIdentifier(clusterName)
                    .tags(Tag.builder().key("owner").value("platform").build()))
                    .dbClusterSnapshot();
            assertThat(snapshot.status()).isEqualTo("available");
            assertThat(snapshot.snapshotType()).isEqualTo("manual");
            assertThat(snapshot.percentProgress()).isEqualTo(100);
            assertThat(snapshot.engine()).isEqualTo("aurora-postgresql");
            assertThat(snapshot.dbClusterSnapshotArn()).contains(":cluster-snapshot:" + snapshotName);

            assertThat(rds.describeDBClusterSnapshots(b -> b.dbClusterIdentifier(clusterName)).dbClusterSnapshots())
                    .extracting(DBClusterSnapshot::dbClusterSnapshotIdentifier)
                    .containsExactly(snapshotName);

            DBClusterSnapshot copy = rds.copyDBClusterSnapshot(b -> b
                    .sourceDBClusterSnapshotIdentifier(snapshot.dbClusterSnapshotArn())
                    .targetDBClusterSnapshotIdentifier(copyName)
                    .copyTags(true))
                    .dbClusterSnapshot();
            assertThat(copy.sourceDBClusterSnapshotArn()).isEqualTo(snapshot.dbClusterSnapshotArn());
            assertThat(copy.tagList()).extracting(Tag::key).containsExactly("owner");

            rds.modifyDBClusterSnapshotAttribute(b -> b
                    .dbClusterSnapshotIdentifier(copyName)
                    .attributeName("restore")
                    .valuesToAdd("all"));
            assertThat(rds.describeDBClusterSnapshotAttributes(b -> b.dbClusterSnapshotIdentifier(copyName))
                    .dbClusterSnapshotAttributesResult().dbClusterSnapshotAttributes())
                    .singleElement()
                    .satisfies(attribute -> assertThat(attribute.attributeValues()).containsExactly("all"));

            assertThat(rds.deleteDBClusterSnapshot(b -> b.dbClusterSnapshotIdentifier(snapshotName))
                    .dbClusterSnapshot().status()).isEqualTo("deleted");
            assertThatThrownBy(() -> rds.describeDBClusterSnapshots(b -> b.dbClusterSnapshotIdentifier(snapshotName)))
                    .isInstanceOf(DbClusterSnapshotNotFoundException.class);

            DBCluster restored = rds.restoreDBClusterFromSnapshot(b -> b
                    .dbClusterIdentifier(restoredName)
                    .snapshotIdentifier(copy.dbClusterSnapshotArn())
                    .engine("aurora-postgresql"))
                    .dbCluster();
            assertThat(restored.dbClusterIdentifier()).isEqualTo(restoredName);
            assertThat(restored.masterUsername()).isEqualTo("admin");
            assertThat(restored.databaseName()).isEqualTo("app");
        } finally {
            for (String name : List.of(snapshotName, copyName)) {
                try {
                    rds.deleteDBClusterSnapshot(b -> b.dbClusterSnapshotIdentifier(name));
                } catch (Exception ignored) {}
            }
            for (String name : List.of(restoredName, clusterName)) {
                try {
                    rds.deleteDBCluster(b -> b.dbClusterIdentifier(name).skipFinalSnapshot(true));
                } catch (Exception ignored) {}
            }
        }
    }

    private static CreateDbProxyResponse createIamProxy(RdsClient client, String name, List<String> vpcSubnetIds) {
        return client.createDBProxy(b -> b
                .dbProxyName(name)
                .engineFamily("POSTGRESQL")
                .roleArn("arn:aws:iam::000000000000:role/rds-proxy-regional")
                .vpcSubnetIds(vpcSubnetIds)
                .defaultAuthScheme("IAM_AUTH"));
    }

    private static List<String> subnetIdsFor(Region region) {
        try (Ec2Client ec2 = ec2Client(region)) {
            return ec2.describeSubnets().subnets().stream()
                    .map(subnet -> subnet.subnetId())
                    .sorted()
                    .limit(2)
                    .toList();
        }
    }

    private static Ec2Client ec2Client(Region region) {
        return Ec2Client.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static software.amazon.awssdk.services.rds.model.CreateDbInstanceResponse createDbInstance(
            RdsClient client, String name, String password) {
        return client.createDBInstance(b -> b
                .dbInstanceIdentifier(name)
                .engine("postgres")
                .engineVersion("16.3")
                .masterUsername("admin")
                .masterUserPassword(password)
                .dbName("app")
                .dbInstanceClass("db.t3.micro")
                .allocatedStorage(20));
    }

    private static RdsClient rdsClient(Region region) {
        return RdsClient.builder()
                .endpointOverride(TestFixtures.endpoint())
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @Test
    void sdkRoundTripsOptionGroupCrudAndItsOptions() {
        String optionGroupName = TestFixtures.uniqueName("rds-og");
        try {
            CreateOptionGroupResponse created = rds.createOptionGroup(b -> b
                    .optionGroupName(optionGroupName)
                    .engineName("mysql")
                    .majorEngineVersion("8.0")
                    .optionGroupDescription("SDK option group shape"));

            assertThat(created.optionGroup().optionGroupName()).isEqualTo(optionGroupName);
            assertThat(created.optionGroup().engineName()).isEqualTo("mysql");
            assertThat(created.optionGroup().majorEngineVersion()).isEqualTo("8.0");
            assertThat(created.optionGroup().optionGroupArn())
                    .endsWith(":og:" + optionGroupName);
            assertThat(created.optionGroup().allowsVpcAndNonVpcInstanceMemberships()).isTrue();
            assertThat(created.optionGroup().options()).isEmpty();

            ModifyOptionGroupResponse modified = rds.modifyOptionGroup(b -> b
                    .optionGroupName(optionGroupName)
                    .applyImmediately(true)
                    .optionsToInclude(OptionConfiguration.builder()
                            .optionName("MEMCACHED")
                            .port(11211)
                            .optionSettings(OptionSetting.builder()
                                    .name("BACKLOG_QUEUE_LIMIT")
                                    .value("1024")
                                    .build())
                            .vpcSecurityGroupMemberships("sg-00000000")
                            .build()));

            assertThat(modified.optionGroup().options()).singleElement().satisfies(option -> {
                assertThat(option.optionName()).isEqualTo("MEMCACHED");
                assertThat(option.port()).isEqualTo(11211);
                assertThat(option.optionSettings())
                        .extracting("name", "value")
                        .containsExactly(tuple("BACKLOG_QUEUE_LIMIT", "1024"));
                assertThat(option.vpcSecurityGroupMemberships())
                        .extracting("vpcSecurityGroupId")
                        .containsExactly("sg-00000000");
            });

            DescribeOptionGroupsResponse described = rds.describeOptionGroups(b -> b
                    .optionGroupName(optionGroupName));
            assertThat(described.optionGroupsList()).singleElement().satisfies(group ->
                    assertThat(group.options()).extracting("optionName")
                            .containsExactly("MEMCACHED"));

            rds.modifyOptionGroup(b -> b
                    .optionGroupName(optionGroupName)
                    .optionsToRemove("MEMCACHED"));
            assertThat(rds.describeOptionGroups(b -> b.optionGroupName(optionGroupName))
                    .optionGroupsList()).singleElement()
                    .satisfies(group -> assertThat(group.options()).isEmpty());

            rds.deleteOptionGroup(b -> b.optionGroupName(optionGroupName));
            assertThatThrownBy(() -> rds.describeOptionGroups(b -> b
                    .optionGroupName(optionGroupName)))
                    .isInstanceOf(OptionGroupNotFoundException.class);
        } finally {
            deleteOptionGroup(rds, optionGroupName);
        }
    }

    @Test
    void sdkDescribesImplicitDefaultOptionGroups() {
        DescribeOptionGroupsResponse all = rds.describeOptionGroups();
        assertThat(all.optionGroupsList()).extracting("optionGroupName")
                .contains("default:mysql-8-0", "default:postgres-16");

        DescribeOptionGroupsResponse filtered = rds.describeOptionGroups(b -> b
                .engineName("mysql")
                .majorEngineVersion("8.0"));
        assertThat(filtered.optionGroupsList()).isNotEmpty();
        assertThat(filtered.optionGroupsList())
                .allSatisfy(group -> assertThat(group.engineName()).isEqualTo("mysql"));

        assertThatThrownBy(() -> rds.deleteOptionGroup(b -> b
                .optionGroupName("default:mysql-8-0")))
                .isInstanceOf(InvalidOptionGroupStateException.class);
    }

    @Test
    void sdkFaultsDeletingAnOptionGroupStillAttachedToAnInstance() {
        String optionGroupName = TestFixtures.uniqueName("rds-og-attached");
        String instanceName = TestFixtures.uniqueName("rds-db-og");
        try {
            rds.createOptionGroup(b -> b
                    .optionGroupName(optionGroupName)
                    .engineName("postgres")
                    .majorEngineVersion("16")
                    .optionGroupDescription("attached to an instance"));

            var instance = rds.createDBInstance(b -> b
                    .dbInstanceIdentifier(instanceName)
                    .engine("postgres")
                    .engineVersion("16.3")
                    .masterUsername("admin")
                    .masterUserPassword("og-secret")
                    .dbName("app")
                    .dbInstanceClass("db.t3.micro")
                    .allocatedStorage(20)
                    .optionGroupName(optionGroupName));

            assertThat(instance.dbInstance().optionGroupMemberships())
                    .singleElement()
                    .satisfies(membership -> {
                        assertThat(membership.optionGroupName()).isEqualTo(optionGroupName);
                        assertThat(membership.status()).isEqualTo("in-sync");
                    });

            assertThatThrownBy(() -> rds.deleteOptionGroup(b -> b
                    .optionGroupName(optionGroupName)))
                    .isInstanceOf(InvalidOptionGroupStateException.class);

            deleteDbInstance(rds, instanceName);
            rds.deleteOptionGroup(b -> b.optionGroupName(optionGroupName));
        } finally {
            deleteDbInstance(rds, instanceName);
            deleteOptionGroup(rds, optionGroupName);
        }
    }

    @Test
    void sdkReportsTheDefaultOptionGroupForAnUnattachedInstance() {
        String instanceName = TestFixtures.uniqueName("rds-db-default-og");
        try {
            var instance = createDbInstance(rds, instanceName, "default-og-secret");

            assertThat(instance.dbInstance().optionGroupMemberships())
                    .singleElement()
                    .satisfies(membership -> assertThat(membership.optionGroupName())
                            .isEqualTo("default:postgres-16"));
        } finally {
            deleteDbInstance(rds, instanceName);
        }
    }

    private static void deleteOptionGroup(RdsClient client, String name) {
        try {
            client.deleteOptionGroup(b -> b.optionGroupName(name));
        } catch (Exception e) {
            LOG.log(Level.FINE, "RDS option group already absent during cleanup " + name, e);
        }
    }

    private static void deleteProxy(RdsClient client, String name) {
        try {
            client.deleteDBProxy(b -> b.dbProxyName(name));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to clean up RDS DB proxy " + name, e);
        }
    }

    private static void deleteDbInstance(RdsClient client, String name) {
        try {
            client.deleteDBInstance(b -> b
                    .dbInstanceIdentifier(name)
                    .skipFinalSnapshot(true));
        } catch (Exception e) {
            LOG.log(Level.FINE, "RDS DB instance already absent during cleanup " + name, e);
        }
    }

    private static void deregisterProxyTarget(
            RdsClient client, String proxyName, String instanceName) {
        try {
            client.deregisterDBProxyTargets(b -> b
                    .dbProxyName(proxyName)
                    .targetGroupName("default")
                    .dbInstanceIdentifiers(instanceName));
        } catch (Exception e) {
            LOG.log(Level.FINE,
                    "RDS DB proxy target already absent during cleanup " + proxyName, e);
        }
    }

    private static void assertInstanceProxyTarget(
            DBProxyTarget target, String instanceName, String instanceArn) {
        assertThat(target.typeAsString()).isEqualTo("RDS_INSTANCE");
        assertThat(target.rdsResourceId()).isEqualTo(instanceName);
        assertThat(target.targetArn()).isEqualTo(instanceArn);
        assertThat(target.targetHealth().stateAsString()).isEqualTo("AVAILABLE");
    }

    private static void assertPoolConfiguration(ConnectionPoolConfigurationInfo pool) {
        assertThat(pool.maxConnectionsPercent()).isEqualTo(73);
        assertThat(pool.maxIdleConnectionsPercent()).isEqualTo(41);
        assertThat(pool.connectionBorrowTimeout()).isEqualTo(37);
        assertThat(pool.initQuery()).isEqualTo("SET sql_mode='STRICT_ALL_TABLES'");
        assertThat(pool.sessionPinningFilters()).containsExactly("EXCLUDE_VARIABLE_SETS");
    }
}
