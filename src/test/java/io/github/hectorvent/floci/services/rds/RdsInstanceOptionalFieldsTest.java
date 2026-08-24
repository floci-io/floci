package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * lex00/floci#120: DescribeDBInstances/DescribeDBParameters never echoed back several
 * documented, client-set fields - Endpoint.Port and PreferredBackupWindow were hardcoded/wrong,
 * and MonitoringInterval/MonitoringRoleArn/PerformanceInsightsRetentionPeriod/
 * EngineLifecycleSupport/EnabledCloudwatchLogsExports/MaxAllocatedStorage/Parameter.ApplyMethod
 * were simply never stored anywhere. Oracle: botocore's rds/2014-10-31/service-2.json
 * DBInstance/Parameter shapes. One test per field group, per this unit's own instructions.
 */
class RdsInstanceOptionalFieldsTest {

    private RdsService rdsService;

    @BeforeEach
    void setUp() {
        RdsContainerManager containerManager = mock(RdsContainerManager.class);
        RdsProxyManager proxyManager = mock(RdsProxyManager.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "123456789012");
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.RdsServiceConfig rdsConfig = mock(EmulatorConfig.RdsServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.rds()).thenReturn(rdsConfig);
        when(rdsConfig.proxyBasePort()).thenReturn(7000);
        when(rdsConfig.proxyMaxPort()).thenReturn(7099);
        when(rdsConfig.defaultPostgresImage()).thenReturn(Optional.empty());
        when(rdsConfig.defaultMysqlImage()).thenReturn(Optional.empty());
        when(rdsConfig.defaultMariadbImage()).thenReturn(Optional.empty());
        when(containerManager.tryStart(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "localhost", 5432));
        when(ec2Service.describeSubnets(eq("us-east-1"), anyList(), any()))
                .thenReturn(defaultSubnets());

        rdsService = new RdsService(containerManager, proxyManager, ec2Service, regionResolver, config,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    private static List<Subnet> defaultSubnets() {
        return List.of(
                subnet("subnet-default-a", "vpc-default", "us-east-1a"),
                subnet("subnet-default-b", "vpc-default", "us-east-1b"));
    }

    private static Subnet subnet(String subnetId, String vpcId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setVpcId(vpcId);
        subnet.setAvailabilityZone(availabilityZone);
        return subnet;
    }

    private DbInstance create(String id) {
        return rdsService.createDbInstance(id, "postgres", "16.3",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null, null, null, false);
    }

    @Test
    void createTimePortIsHonoredWhenFree() {
        DbInstance instance = create("mydb");
        instance = rdsService.applyCreateTimePort("mydb", 5432);

        assertEquals(5432, instance.getEndpoint().port());
        assertEquals(5432, instance.getProxyPort());
    }

    // lex00/floci#124: this test used to assert the OPPOSITE - that a second instance
    // explicitly requesting the same port an already-running instance holds gets silently
    // bumped to a different Endpoint.Port. That was itself the bug: real AWS has no
    // cross-instance port-uniqueness constraint at all - every RDS instance is its own isolated
    // network endpoint - so DescribeDBInstances must echo back exactly what was requested for
    // EVERY instance, including two that both asked for the identical value (terraform-aws-
    // modules/terraform-aws-rds's own "complete-postgres" example does exactly this - hardcodes
    // port 5432 on two separate aws_db_instance resources - and choudoufu's
    // corpus-rds-complete-postgres crossing caught the resulting perpetual diff). The one real
    // constraint floci's shared-host proxy actually has - two literal TCP listeners cannot bind
    // the same host port - still exists, so it has to land on the INTERNAL bind port
    // (getProxyPort(), never exposed via the wire API) instead of the declared Endpoint.Port.
    @Test
    void createTimePortHonorsBothInstancesExplicitPortEvenWhenIdentical() {
        DbInstance a = create("db-a");
        a = rdsService.applyCreateTimePort("db-a", 5432);
        DbInstance b = create("db-b");
        b = rdsService.applyCreateTimePort("db-b", 5432);

        assertEquals(5432, a.getEndpoint().port());
        assertEquals(5432, b.getEndpoint().port());

        // The internal real listener port is where the unavoidable same-host collision has to
        // land instead - it cannot be identical for both, since only one can literally bind it.
        assertNotEquals(a.getProxyPort(), b.getProxyPort());
    }

    @Test
    void createWithoutPortDefaultsToTheEngineStandardPort() {
        DbInstance instance = create("mydb");
        instance = rdsService.applyCreateTimePort("mydb", null);

        assertEquals(5432, instance.getEndpoint().port());
    }

    @Test
    void modifyDbPortNumberChangesAnExistingInstancesPort() {
        DbInstance instance = create("mydb");
        rdsService.applyCreateTimePort("mydb", 5432);

        DbInstance modified = rdsService.applyRequestedPort("mydb", 5555);

        assertEquals(5555, modified.getEndpoint().port());
    }

    @Test
    void preferredBackupWindowIsPersistedAndDefaultsWhenOmitted() {
        create("mydb");
        DbInstance withDefault = rdsService.setCreateTimeInstanceOptionalFields("mydb", null,
                null, null, null, null, List.of(), null);
        assertEquals("04:00-06:00", withDefault.getPreferredBackupWindow());

        create("explicit-window");
        DbInstance withExplicit = rdsService.setCreateTimeInstanceOptionalFields("explicit-window",
                "03:00-06:00", null, null, null, null, List.of(), null);
        assertEquals("03:00-06:00", withExplicit.getPreferredBackupWindow());
    }

    @Test
    void monitoringAndPerformanceInsightsFieldsRoundTrip() {
        create("mydb");
        DbInstance instance = rdsService.setCreateTimeInstanceOptionalFields("mydb", null,
                60, "arn:aws:iam::123456789012:role/monitoring", 7, null, List.of(), null);

        assertEquals(60, instance.getMonitoringInterval());
        assertEquals("arn:aws:iam::123456789012:role/monitoring", instance.getMonitoringRoleArn());
        assertEquals(7, instance.getPerformanceInsightsRetentionPeriod());
    }

    @Test
    void engineLifecycleSupportRoundTrips() {
        create("mydb");
        DbInstance instance = rdsService.setCreateTimeInstanceOptionalFields("mydb", null,
                null, null, null, "open-source-rds-extended-support-disabled", List.of(), null);

        assertEquals("open-source-rds-extended-support-disabled", instance.getEngineLifecycleSupport());
    }

    @Test
    void enabledCloudwatchLogsExportsRoundTripsAndModifyEnablesAndDisablesIncrementally() {
        create("mydb");
        DbInstance instance = rdsService.setCreateTimeInstanceOptionalFields("mydb", null,
                null, null, null, null, List.of("postgresql"), null);
        assertEquals(List.of("postgresql"), instance.getEnabledCloudwatchLogsExports());

        DbInstance modified = rdsService.modifyInstanceOptionalFields("mydb", null, null, null,
                null, null, List.of("upgrade"), List.of("postgresql"), null);
        assertEquals(List.of("upgrade"), modified.getEnabledCloudwatchLogsExports());
    }

    @Test
    void maxAllocatedStorageRoundTrips() {
        create("mydb");
        DbInstance instance = rdsService.setCreateTimeInstanceOptionalFields("mydb", null,
                null, null, null, null, List.of(), 100);

        assertEquals(100, instance.getMaxAllocatedStorage());
    }

    @Test
    void modifyOnlyOverwritesFieldsTheRequestActuallySet() {
        create("mydb");
        rdsService.setCreateTimeInstanceOptionalFields("mydb", "03:00-06:00", 60,
                "arn:aws:iam::123456789012:role/monitoring", 7, "open-source-rds-extended-support-disabled",
                List.of("postgresql"), 100);

        DbInstance modified = rdsService.modifyInstanceOptionalFields("mydb", null, null, null,
                null, null, null, null, 200);

        assertEquals(200, modified.getMaxAllocatedStorage());
        // Untouched by this modify - still carries the values set at create time.
        assertEquals("03:00-06:00", modified.getPreferredBackupWindow());
        assertEquals(60, modified.getMonitoringInterval());
        assertEquals("open-source-rds-extended-support-disabled", modified.getEngineLifecycleSupport());
    }

    @Test
    void aFreshInstanceThatNeverSetAnOptionalFieldLeavesItAbsentRatherThanInventingADefault() {
        DbInstance instance = create("mydb");

        assertNull(instance.getMonitoringInterval());
        assertNull(instance.getMonitoringRoleArn());
        assertNull(instance.getPerformanceInsightsRetentionPeriod());
        assertNull(instance.getEngineLifecycleSupport());
        assertNull(instance.getMaxAllocatedStorage());
        assertEquals(List.of(), instance.getEnabledCloudwatchLogsExports());
    }

    @Test
    void describeDbParametersEchoesApplyMethodBack() {
        rdsService.createDbParameterGroup("pg1", "postgres16", "test group");

        DbParameterGroup group = rdsService.modifyDbParameterGroup("pg1",
                Map.of("autovacuum", "on"), Map.of("autovacuum", "immediate"));

        assertEquals("on", group.getParameters().get("autovacuum"));
        assertEquals("immediate", group.getParameterApplyMethods().get("autovacuum"));
    }

    @Test
    void aParameterThatNeverSetApplyMethodOmitsItRatherThanInventingADefault() {
        rdsService.createDbParameterGroup("pg1", "postgres16", "test group");

        DbParameterGroup group = rdsService.modifyDbParameterGroup("pg1",
                Map.of("client_encoding", "UTF8"));

        assertEquals("UTF8", group.getParameters().get("client_encoding"));
        assertNull(group.getParameterApplyMethods().get("client_encoding"));
    }
}
