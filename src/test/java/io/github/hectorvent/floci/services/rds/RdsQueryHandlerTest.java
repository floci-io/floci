package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the XML format and Filters parsing in RdsQueryHandler.
 */
class RdsQueryHandlerTest {

    private RdsService service;
    private RdsQueryHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(RdsService.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.RdsServiceConfig rdsConfig = mock(EmulatorConfig.RdsServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.rds()).thenReturn(rdsConfig);
        when(config.defaultAvailabilityZone()).thenReturn("us-east-1a");
        handler = new RdsQueryHandler(service, config);
    }

    // ──────────────────────────── DBInstances XML tag ────────────────────────────

    @Test
    void describeDbInstances_usesDBInstanceTag() {
        DbInstance instance = makeInstance("mydb");
        when(service.listDbInstances(null)).thenReturn(List.of(instance));

        Response response = handler.handle("DescribeDBInstances", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBInstance>"), "Expected <DBInstance> element in response");
        assertFalse(body.contains("<member><DBInstanceIdentifier>"), "Did not expect <member> wrapping DBInstance");
    }

    @Test
    void describeDbInstances_filterByDirectIdentifier() {
        DbInstance instance = makeInstance("mydb");
        when(service.listDbInstances("mydb")).thenReturn(List.of(instance));

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        handler.handle("DescribeDBInstances", p);

        verify(service).listDbInstances("mydb");
    }

    @Test
    void describeDbInstances_filterByFiltersParam() {
        DbInstance instance = makeInstance("mydb");
        when(service.listDbInstances("mydb")).thenReturn(List.of(instance));

        MultivaluedMap<String, String> p = params();
        p.add("Filters.Filter.1.Name", "db-instance-id");
        p.add("Filters.Filter.1.Values.Value.1", "mydb");
        handler.handle("DescribeDBInstances", p);

        verify(service).listDbInstances("mydb");
    }

    @Test
    void describeDbInstances_directIdentifierTakesPriorityOverFilters() {
        when(service.listDbInstances(any())).thenReturn(List.of());

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "direct-id");
        p.add("Filters.Filter.1.Name", "db-instance-id");
        p.add("Filters.Filter.1.Values.Value.1", "filter-id");
        handler.handle("DescribeDBInstances", p);

        verify(service).listDbInstances("direct-id");
    }

    // ──────────────────────────── DBClusters XML tag ────────────────────────────

    @Test
    void describeDbClusters_usesDBClusterTag() {
        DbCluster cluster = makeCluster("mycluster");
        when(service.listDbClusters(null)).thenReturn(List.of(cluster));

        Response response = handler.handle("DescribeDBClusters", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBCluster>"), "Expected <DBCluster> element in response");
        assertFalse(body.contains("<member><DBClusterIdentifier>"), "Did not expect <member> wrapping DBCluster");
    }

    @Test
    void describeDbClusters_filterByFiltersParam() {
        when(service.listDbClusters("mycluster")).thenReturn(List.of());

        MultivaluedMap<String, String> p = params();
        p.add("Filters.Filter.1.Name", "db-cluster-id");
        p.add("Filters.Filter.1.Values.Value.1", "mycluster");
        handler.handle("DescribeDBClusters", p);

        verify(service).listDbClusters("mycluster");
    }

    @Test
    void describeDbInstances_unknownFilterFallsBackToUnfilteredList() {
        when(service.listDbInstances(null)).thenReturn(List.of());

        MultivaluedMap<String, String> p = params();
        p.add("Filters.Filter.1.Name", "engine");
        p.add("Filters.Filter.1.Values.Value.1", "postgres");
        handler.handle("DescribeDBInstances", p);

        verify(service).listDbInstances(null);
    }

    // ──────────────────────────── DBParameterGroups XML tag ──────────────────────

    @Test
    void describeDbParameterGroups_usesDBParameterGroupTag() {
        DbParameterGroup group = new DbParameterGroup("pg1", "postgres15", "test group");
        when(service.listDbParameterGroups(null)).thenReturn(List.of(group));

        Response response = handler.handle("DescribeDBParameterGroups", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBParameterGroup>"), "Expected <DBParameterGroup> element in response");
        assertFalse(body.contains("<member><DBParameterGroupName>"), "Did not expect <member> wrapping DBParameterGroup");
    }

    @Test
    void createDbInstance_invalidAllocatedStorageFallsBackToDefaultAndEngineVersionDefaults() {
        DbInstance instance = makeInstance("mydb");
        when(service.createDbInstance(eq("mydb"), eq("postgres"), eq("16.3"),
                eq("admin"), eq("secret"), eq("dbname"), eq("db.t3.micro"),
                eq(20), eq(false), eq(null), eq(null)))
                .thenReturn(instance);

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        p.add("Engine", "postgres");
        p.add("MasterUsername", "admin");
        p.add("MasterUserPassword", "secret");
        p.add("DBName", "dbname");
        p.add("AllocatedStorage", "not-a-number");
        handler.handle("CreateDBInstance", p);

        verify(service).createDbInstance("mydb", "postgres", "16.3",
                "admin", "secret", "dbname", "db.t3.micro", 20, false, null, null);
    }

    @Test
    void createDbInstance_unknownEngineReturnsInvalidParameterValue() {
        // Handler defaults version to "1.0" for unknown engines, then the service
        // rejects the engine. Verify the full error path: version defaulting +
        // AwsException wrapping into a 400 query error.
        when(service.createDbInstance(eq("mydb"), eq("oracle"), eq("1.0"),
                eq(null), eq(null), eq(null), eq("db.t3.micro"),
                eq(20), eq(false), eq(null), eq(null)))
                .thenThrow(new AwsException("InvalidParameterValue",
                        "Unsupported engine: oracle. Supported: postgres, mysql, mariadb.", 400));

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        p.add("Engine", "oracle");
        Response response = handler.handle("CreateDBInstance", p);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("InvalidParameterValue"));
    }

    @Test
    void modifyDbParameterGroup_ignoresParametersWithoutValue() {
        DbParameterGroup group = new DbParameterGroup("pg1", "postgres15", "test group");
        when(service.modifyDbParameterGroup(eq("pg1"), eq(java.util.Map.of("max_connections", "200"))))
                .thenReturn(group);

        MultivaluedMap<String, String> p = params();
        p.add("DBParameterGroupName", "pg1");
        p.add("Parameters.member.1.ParameterName", "max_connections");
        p.add("Parameters.member.1.ParameterValue", "200");
        p.add("Parameters.member.2.ParameterName", "ignored_without_value");
        handler.handle("ModifyDBParameterGroup", p);

        verify(service).modifyDbParameterGroup("pg1", java.util.Map.of("max_connections", "200"));
    }

    @Test
    void describeDbParameters_requiresParameterGroupName() {
        Response response = handler.handle("DescribeDBParameters", params());

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("DBParameterGroupName is required."));
    }

    @Test
    void unsupportedOperationReturnsQueryError() {
        Response response = handler.handle("NoSuchAction", params());

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("UnsupportedOperation"));
    }

    // ──────────────────────────── DBClusterParameterGroups ──────────────────────

    @Test
    void describeDbClusterParameterGroups_usesDBClusterParameterGroupTag() {
        DbClusterParameterGroup group = new DbClusterParameterGroup("cpg1", "aurora-postgresql16", "test cluster group");
        when(service.listDbClusterParameterGroups(null)).thenReturn(List.of(group));

        Response response = handler.handle("DescribeDBClusterParameterGroups", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBClusterParameterGroup>"), "Expected <DBClusterParameterGroup> element in response");
        assertFalse(body.contains("<member><DBClusterParameterGroupName>"), "Did not expect <member> wrapping DBClusterParameterGroup");
    }

    @Test
    void createDbClusterParameterGroup_requiresName() {
        Response response = handler.handle("CreateDBClusterParameterGroup", params());

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("DBClusterParameterGroupName is required."));
    }

    @Test
    void createDbClusterParameterGroup_passesArgumentsToService() {
        DbClusterParameterGroup group = new DbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc");
        when(service.createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc")).thenReturn(group);

        MultivaluedMap<String, String> p = params();
        p.add("DBClusterParameterGroupName", "cpg1");
        p.add("DBParameterGroupFamily", "aurora-postgresql16");
        p.add("Description", "desc");
        Response response = handler.handle("CreateDBClusterParameterGroup", p);

        verify(service).createDbClusterParameterGroup("cpg1", "aurora-postgresql16", "desc");
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBClusterParameterGroupName>cpg1</DBClusterParameterGroupName>"));
        assertTrue(body.contains("<DBParameterGroupFamily>aurora-postgresql16</DBParameterGroupFamily>"));
    }

    @Test
    void modifyDbClusterParameterGroup_ignoresParametersWithoutValue() {
        DbClusterParameterGroup group = new DbClusterParameterGroup("cpg1", "aurora-postgresql16", "test group");
        when(service.modifyDbClusterParameterGroup(eq("cpg1"), eq(java.util.Map.of("log_statement", "all"))))
                .thenReturn(group);

        MultivaluedMap<String, String> p = params();
        p.add("DBClusterParameterGroupName", "cpg1");
        p.add("Parameters.member.1.ParameterName", "log_statement");
        p.add("Parameters.member.1.ParameterValue", "all");
        p.add("Parameters.member.2.ParameterName", "ignored_without_value");
        handler.handle("ModifyDBClusterParameterGroup", p);

        verify(service).modifyDbClusterParameterGroup("cpg1", java.util.Map.of("log_statement", "all"));
    }

    @Test
    void describeDbClusterParameters_requiresParameterGroupName() {
        Response response = handler.handle("DescribeDBClusterParameters", params());

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("DBClusterParameterGroupName is required."));
    }

    @Test
    void deleteDbClusterParameterGroup_requiresName() {
        Response response = handler.handle("DeleteDBClusterParameterGroup", params());

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("DBClusterParameterGroupName is required."));
    }

    // ──────────────────────────── DBSubnetGroup shape ───────────────────────────

    @Test
    void describeDbClusters_dbSubnetGroupIsPlainString() {
        DbCluster cluster = makeCluster("mycluster");
        when(service.listDbClusters(null)).thenReturn(List.of(cluster));

        Response response = handler.handle("DescribeDBClusters", params());

        String body = (String) response.getEntity();
        // DBCluster.DBSubnetGroup is shape: String in the AWS service model — not a nested struct
        assertTrue(body.contains("<DBSubnetGroup>default</DBSubnetGroup>"),
                "Expected DBSubnetGroup as plain string element");
        assertFalse(body.contains("<DBSubnetGroupName>"),
                "Did not expect nested DBSubnetGroupName inside DBCluster");
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static MultivaluedMap<String, String> params() {
        return new MultivaluedHashMap<>();
    }

    private static DbInstance makeInstance(String id) {
        DbInstance i = new DbInstance();
        i.setDbInstanceIdentifier(id);
        i.setStatus(DbInstanceStatus.AVAILABLE);
        i.setEngine(io.github.hectorvent.floci.services.rds.model.DatabaseEngine.POSTGRES);
        i.setEngineVersion("15");
        i.setMasterUsername("admin");
        i.setDbInstanceClass("db.t3.micro");
        i.setAllocatedStorage(20);
        return i;
    }

    private static DbCluster makeCluster(String id) {
        DbCluster c = new DbCluster();
        c.setDbClusterIdentifier(id);
        c.setStatus(DbInstanceStatus.AVAILABLE);
        c.setEngine(io.github.hectorvent.floci.services.rds.model.DatabaseEngine.POSTGRES);
        c.setEngineVersion("15");
        c.setMasterUsername("admin");
        return c;
    }
}
