package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.container.RdsContainerHandle;
import io.github.hectorvent.floci.services.rds.container.RdsContainerManager;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RdsServiceTest {

    private RdsService rdsService;
    private RdsContainerManager containerManager;
    private RdsProxyManager proxyManager;
    private RegionResolver regionResolver;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        containerManager = mock(RdsContainerManager.class);
        proxyManager = mock(RdsProxyManager.class);
        regionResolver = new RegionResolver("us-east-1", "123456789012");
        config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.RdsServiceConfig rdsConfig = mock(EmulatorConfig.RdsServiceConfig.class);

        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.rds()).thenReturn(rdsConfig);
        when(rdsConfig.proxyBasePort()).thenReturn(7000);
        when(rdsConfig.proxyMaxPort()).thenReturn(7099);

        rdsService = new RdsService(containerManager, proxyManager, regionResolver, config);

        when(containerManager.start(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RdsContainerHandle("cont-id", "id", "localhost", 5432));
    }

    @Test
    void createDbInstanceGeneratesMissingFields() {
        DbInstance instance = rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null);

        assertEquals("mydb", instance.getDbInstanceIdentifier());
        assertNotNull(instance.getDbiResourceId());
        assertTrue(instance.getDbiResourceId().startsWith("db-"));
        assertEquals("arn:aws:rds:us-east-1:123456789012:db:mydb", instance.getDbInstanceArn());
    }

    @Test
    void listDbInstancesIsCaseInsensitive() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "password", "dbname", "db.t3.micro",
                20, false, null, null);

        Collection<DbInstance> result = rdsService.listDbInstances("MYDB");
        assertEquals(1, result.size());
        assertEquals("mydb", result.iterator().next().getDbInstanceIdentifier());

        result = rdsService.listDbInstances("mydb");
        assertEquals(1, result.size());
    }

    @Test
    void listDbInstancesReturnsEmptyWhenNotFound() {
        Collection<DbInstance> result = rdsService.listDbInstances("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void modifyDbInstanceBlankPasswordDoesNotOverwriteExistingPassword() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "original-password", "dbname", "db.t3.micro",
                20, false, null, null);

        DbInstance modified = rdsService.modifyDbInstance("mydb", "   ", null);

        assertEquals("original-password", modified.getMasterPassword());
        assertFalse(modified.isIamDatabaseAuthenticationEnabled());
    }

    @Test
    void modifyDbInstanceCanToggleIamWithoutChangingPassword() {
        rdsService.createDbInstance("mydb", "postgres", "13",
                "admin", "original-password", "dbname", "db.t3.micro",
                20, false, null, null);

        DbInstance modified = rdsService.modifyDbInstance("mydb", null, true);

        assertEquals("original-password", modified.getMasterPassword());
        assertTrue(modified.isIamDatabaseAuthenticationEnabled());
    }

    @Test
    void deleteDbClusterFailsWhenMembersRemain() {
        DbCluster cluster = rdsService.createDbCluster("cluster1", "postgres", "13",
                "admin", "password", "dbname", false, null);
        cluster.getDbClusterMembers().add("instance-1");

        AwsException exception = assertThrows(AwsException.class,
                () -> rdsService.deleteDbCluster("cluster1"));

        assertEquals("InvalidDBClusterStateFault", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("still has DB instances"));
    }

    @Test
    void createDbClusterParameterGroupRoundTrip() {
        DbClusterParameterGroup created = rdsService.createDbClusterParameterGroup(
                "cpg1", "aurora-postgresql16", "test cluster group");

        assertEquals("cpg1", created.getDbClusterParameterGroupName());
        assertEquals("aurora-postgresql16", created.getDbParameterGroupFamily());

        DbClusterParameterGroup fetched = rdsService.getDbClusterParameterGroup("cpg1");
        assertEquals("cpg1", fetched.getDbClusterParameterGroupName());

        Collection<DbClusterParameterGroup> listed = rdsService.listDbClusterParameterGroups(null);
        assertEquals(1, listed.size());
    }

    @Test
    void createDbClusterParameterGroupRejectsDuplicate() {
        rdsService.createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc");

        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc"));

        assertEquals("DBParameterGroupAlreadyExists", exception.getErrorCode());
    }

    @Test
    void modifyDbClusterParameterGroupAppliesParameters() {
        rdsService.createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc");

        DbClusterParameterGroup modified = rdsService.modifyDbClusterParameterGroup(
                "cpg1", java.util.Map.of("log_statement", "all", "shared_preload_libraries", "pg_stat_statements"));

        assertEquals("all", modified.getParameters().get("log_statement"));
        assertEquals("pg_stat_statements", modified.getParameters().get("shared_preload_libraries"));
    }

    @Test
    void deleteDbClusterParameterGroupMissingThrows() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.deleteDbClusterParameterGroup("nonexistent"));

        assertEquals("DBParameterGroupNotFound", exception.getErrorCode());
    }

    @Test
    void getDbClusterParameterGroupMissingThrows() {
        AwsException exception = assertThrows(AwsException.class, () ->
                rdsService.getDbClusterParameterGroup("nonexistent"));

        assertEquals("DBParameterGroupNotFound", exception.getErrorCode());
    }
}
