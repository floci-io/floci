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
import software.amazon.awssdk.services.rds.model.DBProxyTarget;
import software.amazon.awssdk.services.rds.model.DescribeDbSubnetGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeOptionGroupsResponse;
import software.amazon.awssdk.services.rds.model.DescribeOrderableDbInstanceOptionsResponse;
import software.amazon.awssdk.services.rds.model.InvalidOptionGroupStateException;
import software.amazon.awssdk.services.rds.model.ModifyOptionGroupResponse;
import software.amazon.awssdk.services.rds.model.OptionConfiguration;
import software.amazon.awssdk.services.rds.model.OptionGroupNotFoundException;
import software.amazon.awssdk.services.rds.model.OptionSetting;

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
