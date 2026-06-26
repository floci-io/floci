package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
        when(service.resolveDbSubnetGroupView(nullable(String.class))).thenReturn(defaultSubnetGroup());
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
    void describeDbInstances_includesDbParameterGroupAttachment() {
        DbInstance instance = makeInstance("mydb");
        instance.setParameterGroupName("postgres18");
        when(service.listDbInstances(null)).thenReturn(List.of(instance));

        Response response = handler.handle("DescribeDBInstances", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBParameterGroups>"));
        assertTrue(body.contains("<DBParameterGroupName>postgres18</DBParameterGroupName>"));
        assertTrue(body.contains("<ParameterApplyStatus>in-sync</ParameterApplyStatus>"));
    }

    @Test
    void describeDbInstances_reportsDefaultDbParameterGroupWhenUnattached() {
        DbInstance instance = makeInstance("mydb");
        instance.setEngineVersion("16.3");
        when(service.listDbInstances(null)).thenReturn(List.of(instance));

        Response response = handler.handle("DescribeDBInstances", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBParameterGroups>"));
        assertTrue(body.contains("<DBParameterGroupName>default.postgres16</DBParameterGroupName>"));
        assertTrue(body.contains("<ParameterApplyStatus>in-sync</ParameterApplyStatus>"));
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

    @Test
    void describeDbInstances_dbSubnetGroupUsesSubnetTag() {
        DbInstance instance = makeInstance("mydb");
        instance.setDbSubnetGroupName("custom-group");
        when(service.resolveDbSubnetGroupView("custom-group")).thenReturn(customSubnetGroup());
        when(service.listDbInstances(null)).thenReturn(List.of(instance));

        Response response = handler.handle("DescribeDBInstances", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<Subnets><Subnet>") || body.contains("<Subnets>\n<Subnet>"));
        assertFalse(body.contains("<Subnets><member>"), "Did not expect <member> elements inside DBSubnetGroup.Subnets");
        assertTrue(body.contains("<SubnetIdentifier>subnet-a</SubnetIdentifier>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-b</SubnetIdentifier>"));
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

    @Test
    void describeDbInstances_usesStoredDbSubnetGroup() {
        DbInstance instance = makeInstance("mydb");
        instance.setDbSubnetGroupName("sample-db-subnets");
        when(service.listDbInstances(null)).thenReturn(List.of(instance));
        when(service.getDbSubnetGroup("sample-db-subnets")).thenReturn(new DbSubnetGroup(
                "sample-db-subnets", "test subnets", "vpc-123", List.of("subnet-aaa", "subnet-bbb"),
                Map.of("subnet-aaa", "us-east-1a", "subnet-bbb", "us-east-1b")));

        Response response = handler.handle("DescribeDBInstances", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBSubnetGroupName>sample-db-subnets</DBSubnetGroupName>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-aaa</SubnetIdentifier>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-bbb</SubnetIdentifier>"));
        assertFalse(body.contains("<SubnetIdentifier>subnet-00000000</SubnetIdentifier>"));
    }

    @Test
    void describeDbInstances_includesTagList() {
        DbInstance instance = makeInstance("mydb");
        instance.setTags(java.util.Map.of("example:ClusterId", "cluster-a", "Name", "mydb"));
        when(service.listDbInstances(null)).thenReturn(List.of(instance));

        Response response = handler.handle("DescribeDBInstances", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<TagList>"));
        assertTrue(body.contains("<Key>example:ClusterId</Key>"));
        assertTrue(body.contains("<Value>cluster-a</Value>"));
        assertTrue(body.contains("<Key>Name</Key>"));
        assertTrue(body.contains("<Value>mydb</Value>"));
    }

    @Test
    void createDbInstance_passesCreateTagsToService() {
        DbInstance instance = makeInstance("mydb");
        when(service.createDbInstance(eq("mydb"), eq("postgres"), eq("16.3"),
                eq(null), eq(null), eq(null), eq("db.t3.micro"),
                eq(20), eq(false), eq(null), eq(null), eq(null), eq(null), eq(false), eq(false), eq(null),
                eq(java.util.Map.of("example:ClusterId", "cluster-a", "Name", "mydb"))))
                .thenReturn(instance);

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        p.add("Engine", "postgres");
        p.add("Tags.member.1.Key", "example:ClusterId");
        p.add("Tags.member.1.Value", "cluster-a");
        p.add("Tags.member.2.Key", "Name");
        p.add("Tags.member.2.Value", "mydb");
        handler.handle("CreateDBInstance", p);

        verify(service).createDbInstance("mydb", "postgres", "16.3",
                null, null, null, "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                java.util.Map.of("example:ClusterId", "cluster-a", "Name", "mydb"));
    }

    @Test
    void listTagsForResource_returnsStoredTags() {
        when(service.listTagsForResource("arn:aws:rds:us-east-1:000000000000:db:mydb"))
                .thenReturn(java.util.Map.of("Name", "mydb"));

        MultivaluedMap<String, String> p = params();
        p.add("ResourceName", "arn:aws:rds:us-east-1:000000000000:db:mydb");
        Response response = handler.handle("ListTagsForResource", p);

        String body = (String) response.getEntity();
        assertTrue(body.contains("<TagList>"));
        assertTrue(body.contains("<Key>Name</Key>"));
        assertTrue(body.contains("<Value>mydb</Value>"));
    }

    @Test
    void addAndRemoveTagsForResource_passThrough() {
        MultivaluedMap<String, String> add = params();
        add.add("ResourceName", "arn:aws:rds:us-east-1:000000000000:db:mydb");
        add.add("Tags.member.1.Key", "Name");
        add.add("Tags.member.1.Value", "mydb");
        handler.handle("AddTagsToResource", add);

        verify(service).addTagsToResource("arn:aws:rds:us-east-1:000000000000:db:mydb", java.util.Map.of("Name", "mydb"));

        MultivaluedMap<String, String> remove = params();
        remove.add("ResourceName", "arn:aws:rds:us-east-1:000000000000:db:mydb");
        remove.add("TagKeys.member.1", "Name");
        handler.handle("RemoveTagsFromResource", remove);

        verify(service).removeTagsFromResource("arn:aws:rds:us-east-1:000000000000:db:mydb", List.of("Name"));
    }

    @Test
    void describeOrderableDbInstanceOptions_usesServiceCatalog() {
        when(service.describeOrderableDbInstanceOptions("postgres", "16.3", "db.t4g.medium"))
                .thenReturn(List.of(java.util.Map.of(
                        "engine", "postgres",
                        "engineVersion", "16.3",
                        "dbInstanceClass", "db.t4g.medium")));

        MultivaluedMap<String, String> p = params();
        p.add("Engine", "postgres");
        p.add("EngineVersion", "16.3");
        p.add("DBInstanceClass", "db.t4g.medium");
        Response response = handler.handle("DescribeOrderableDBInstanceOptions", p);

        String body = (String) response.getEntity();
        assertEquals(200, response.getStatus());
        assertTrue(body.contains("<OrderableDBInstanceOption>"));
        assertTrue(body.contains("<DBInstanceClass>db.t4g.medium</DBInstanceClass>"));
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
                eq(20), eq(false), eq(null), eq(null), eq(null), eq(null), eq(false), eq(false),
                eq(null), eq(java.util.Map.of())))
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
                "admin", "secret", "dbname", "db.t3.micro", 20, false, null, null, null, null, false, false,
                null, java.util.Map.of());
    }

    @Test
    void createDbInstancePassesManagedMasterUserSecretOptions() {
        DbInstance instance = makeInstance("mydb");
        instance.setMasterUserSecretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:rds!db-123456");
        instance.setMasterUserSecretStatus("active");
        instance.setMasterUserSecretKmsKeyId("kms-key-1");
        when(service.createDbInstance(eq("mydb"), eq("postgres"), eq("16.3"),
                eq("admin"), eq(null), eq("dbname"), eq("db.t3.micro"),
                eq(20), eq(false), eq(null), eq(null), eq(null), eq(null), eq(false), eq(true),
                eq("kms-key-1"), eq(java.util.Map.of())))
                .thenReturn(instance);

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        p.add("Engine", "postgres");
        p.add("MasterUsername", "admin");
        p.add("DBName", "dbname");
        p.add("ManageMasterUserPassword", "true");
        p.add("MasterUserSecretKmsKeyId", "kms-key-1");
        Response response = handler.handle("CreateDBInstance", p);

        String body = (String) response.getEntity();
        assertTrue(body.contains("<MasterUserSecret>"));
        assertTrue(body.contains("<SecretArn>arn:aws:secretsmanager:us-east-1:000000000000:secret:rds!db-123456</SecretArn>"));
        assertTrue(body.contains("<SecretStatus>active</SecretStatus>"));
        assertTrue(body.contains("<KmsKeyId>kms-key-1</KmsKeyId>"));
        verify(service).createDbInstance("mydb", "postgres", "16.3",
                "admin", null, "dbname", "db.t3.micro", 20, false, null, null, null, null, false, true,
                "kms-key-1", java.util.Map.of());
    }

    @Test
    void createDbInstance_withPlacementInputsShouldReflectRequestedPlacement() {
        DbInstance instance = makeInstance("mydb");
        instance.setDbSubnetGroupName("default");
        instance.setAvailabilityZone("ap-northeast-1a");
        instance.setMultiAz(true);
        when(service.createDbInstance(eq("mydb"), eq("postgres"), eq("16.3"),
                eq("admin"), eq("secret"), eq("dbname"), eq("db.t3.micro"),
                eq(20), eq(false), eq(null), eq("default"), eq(null), eq("ap-northeast-1a"), eq(true),
                eq(false), eq(null), eq(java.util.Map.of())))
                .thenReturn(instance);

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        p.add("Engine", "postgres");
        p.add("MasterUsername", "admin");
        p.add("MasterUserPassword", "secret");
        p.add("DBName", "dbname");
        p.add("DBSubnetGroupName", "default");
        p.add("AvailabilityZone", "ap-northeast-1a");
        p.add("MultiAZ", "true");

        Response response = handler.handle("CreateDBInstance", p);

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<AvailabilityZone>ap-northeast-1a</AvailabilityZone>"));
        assertTrue(body.contains("<DBSubnetGroupName>default</DBSubnetGroupName>"));
        assertTrue(body.contains("<DBSubnetGroupArn>arn:aws:rds:us-east-1:123456789012:subgrp:default</DBSubnetGroupArn>"));
        assertTrue(body.contains("<MultiAZ>true</MultiAZ>"));
    }

    @Test
    void createDbInstance_unknownSubnetGroupShouldFailValidation() {
        when(service.createDbInstance(eq("mydb"), eq("postgres"), eq("16.3"),
                eq("admin"), eq("secret"), eq("dbname"), eq("db.t3.micro"),
                eq(20), eq(false), eq(null), eq("missing-subnet-group"), eq(null), eq(null), eq(false),
                eq(false), eq(null), eq(java.util.Map.of())))
                .thenThrow(new AwsException("DBSubnetGroupNotFoundFault",
                        "DB subnet group missing-subnet-group not found.", 404));

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        p.add("Engine", "postgres");
        p.add("MasterUsername", "admin");
        p.add("MasterUserPassword", "secret");
        p.add("DBName", "dbname");
        p.add("DBSubnetGroupName", "missing-subnet-group");

        Response response = handler.handle("CreateDBInstance", p);

        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("DBSubnetGroupNotFoundFault"));
    }

    @Test
    void createDbSubnetGroup_passesSubnetMembersToService() {
        when(service.createDbSubnetGroup("sample-db-subnets", "test", List.of("subnet-aaa", "subnet-bbb")))
                .thenReturn(new DbSubnetGroup(
                        "sample-db-subnets", "test", "vpc-123", List.of("subnet-aaa", "subnet-bbb"),
                        Map.of("subnet-aaa", "us-east-1a", "subnet-bbb", "us-east-1b")));

        MultivaluedMap<String, String> p = params();
        p.add("DBSubnetGroupName", "sample-db-subnets");
        p.add("DBSubnetGroupDescription", "test");
        p.add("SubnetIds.SubnetIdentifier.1", "subnet-aaa");
        p.add("SubnetIds.SubnetIdentifier.2", "subnet-bbb");
        Response response = handler.handle("CreateDBSubnetGroup", p);

        verify(service).createDbSubnetGroup("sample-db-subnets", "test", List.of("subnet-aaa", "subnet-bbb"));
        String body = (String) response.getEntity();
        assertEquals(200, response.getStatus());
        assertTrue(body.contains("<DBSubnetGroupName>sample-db-subnets</DBSubnetGroupName>"));
        assertTrue(body.contains("<Subnets><Subnet>"));
        assertFalse(body.contains("<Subnets><member>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-aaa</SubnetIdentifier>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-bbb</SubnetIdentifier>"));
    }

    @Test
    void modifyDbSubnetGroup_passesSubnetMembersToService() {
        when(service.modifyDbSubnetGroup("sample-db-subnets", List.of("subnet-new-a", "subnet-new-b")))
                .thenReturn(new DbSubnetGroup(
                        "sample-db-subnets", "test", "vpc-123", List.of("subnet-new-a", "subnet-new-b"),
                        Map.of("subnet-new-a", "us-east-1a", "subnet-new-b", "us-east-1b")));

        MultivaluedMap<String, String> p = params();
        p.add("DBSubnetGroupName", "sample-db-subnets");
        p.add("SubnetIds.SubnetIdentifier.1", "subnet-new-a");
        p.add("SubnetIds.SubnetIdentifier.2", "subnet-new-b");
        Response response = handler.handle("ModifyDBSubnetGroup", p);

        verify(service).modifyDbSubnetGroup("sample-db-subnets", List.of("subnet-new-a", "subnet-new-b"));
        String body = (String) response.getEntity();
        assertEquals(200, response.getStatus());
        assertTrue(body.contains("<DBSubnetGroupName>sample-db-subnets</DBSubnetGroupName>"));
        assertTrue(body.contains("<Subnets><Subnet>"));
        assertFalse(body.contains("<Subnets><member>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-new-a</SubnetIdentifier>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-new-b</SubnetIdentifier>"));
    }

    @Test
    void createDbInstance_unknownEngineReturnsInvalidParameterValue() {
        // Handler defaults version to "1.0" for unknown engines, then the service
        // rejects the engine. Verify the full error path: version defaulting +
        // AwsException wrapping into a 400 query error.
        when(service.createDbInstance(eq("mydb"), eq("oracle"), eq("1.0"),
                eq(null), eq(null), eq(null), eq("db.t3.micro"),
                eq(20), eq(false), eq(null), eq(null), eq(null), eq(null), eq(false), eq(false),
                eq(null), eq(java.util.Map.of())))
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
    void createDbSubnetGroup_requiresNameWithMissingParameter() {
        Response response = handler.handle("CreateDBSubnetGroup", params());

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("MissingParameter"));
        assertTrue(((String) response.getEntity()).contains("DBSubnetGroupName"));
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

    @Test
    void createDbSubnetGroup_shouldBeSupportedForCustomSubnetGroups() {
        DbSubnetGroup group = new DbSubnetGroup();
        group.setDbSubnetGroupName("my-subnet-group");
        group.setDescription("test subnet group");
        group.setDbSubnetGroupArn("arn:aws:rds:us-east-1:123456789012:subgrp:my-subnet-group");
        group.setVpcId("vpc-12345678");
        group.setSubnetIds(List.of("subnet-a", "subnet-b"));
        group.setSubnetAvailabilityZones(Map.of("subnet-a", "us-east-1a", "subnet-b", "us-east-1b"));
        when(service.createDbSubnetGroup("my-subnet-group", "test subnet group", List.of("subnet-a", "subnet-b")))
                .thenReturn(group);

        MultivaluedMap<String, String> p = params();
        p.add("DBSubnetGroupName", "my-subnet-group");
        p.add("DBSubnetGroupDescription", "test subnet group");
        p.add("SubnetIds.SubnetIdentifier.1", "subnet-a");
        p.add("SubnetIds.SubnetIdentifier.2", "subnet-b");

        Response response = handler.handle("CreateDBSubnetGroup", p);

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBSubnetGroupName>my-subnet-group</DBSubnetGroupName>"));
        assertTrue(body.contains("<DBSubnetGroupArn>arn:aws:rds:us-east-1:123456789012:subgrp:my-subnet-group</DBSubnetGroupArn>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-a</SubnetIdentifier>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-b</SubnetIdentifier>"));
    }

    @Test
    void describeDbSubnetGroups_shouldBeSupported() {
        DbSubnetGroup group = new DbSubnetGroup();
        group.setDbSubnetGroupName("default");
        group.setDbSubnetGroupArn("arn:aws:rds:us-east-1:123456789012:subgrp:default");
        when(service.listDbSubnetGroups(null)).thenReturn(List.of(group));

        Response response = handler.handle("DescribeDBSubnetGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBSubnetGroups>"));
        assertTrue(body.contains("<DBSubnetGroupArn>arn:aws:rds:us-east-1:123456789012:subgrp:default</DBSubnetGroupArn>"));
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

    private static DbSubnetGroup defaultSubnetGroup() {
        DbSubnetGroup group = new DbSubnetGroup();
        group.setDbSubnetGroupName("default");
        group.setDbSubnetGroupArn("arn:aws:rds:us-east-1:123456789012:subgrp:default");
        group.setVpcId("vpc-default");
        group.setSubnetGroupStatus("Complete");
        group.setSubnetIds(List.of("subnet-default-a", "subnet-default-b"));
        group.setSubnetAvailabilityZones(Map.of("subnet-default-a", "us-east-1a", "subnet-default-b", "us-east-1b"));
        return group;
    }

    private static DbSubnetGroup customSubnetGroup() {
        DbSubnetGroup group = new DbSubnetGroup();
        group.setDbSubnetGroupName("custom-group");
        group.setDbSubnetGroupArn("arn:aws:rds:us-east-1:123456789012:subgrp:custom-group");
        group.setVpcId("vpc-12345678");
        group.setSubnetGroupStatus("Complete");
        group.setSubnetIds(List.of("subnet-a", "subnet-b"));
        group.setSubnetAvailabilityZones(Map.of("subnet-a", "us-east-1a", "subnet-b", "us-east-1b"));
        return group;
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
