package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceStatus;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbProxy;
import io.github.hectorvent.floci.services.rds.model.DbProxyAuth;
import io.github.hectorvent.floci.services.rds.model.DbProxyTarget;
import io.github.hectorvent.floci.services.rds.model.DbProxyTargetGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
        when(service.listDbInstances(null, null)).thenReturn(List.of(instance));

        Response response = handler.handle("DescribeDBInstances", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBInstance>"), "Expected <DBInstance> element in response");
        assertFalse(body.contains("<member><DBInstanceIdentifier>"), "Did not expect <member> wrapping DBInstance");
    }

    @Test
    void describeDbInstances_includesDbParameterGroupAttachment() {
        DbInstance instance = makeInstance("mydb");
        instance.setParameterGroupName("postgres18");
        when(service.listDbInstances(null, null)).thenReturn(List.of(instance));

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
        when(service.listDbInstances(null, null)).thenReturn(List.of(instance));

        Response response = handler.handle("DescribeDBInstances", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBParameterGroups>"));
        assertTrue(body.contains("<DBParameterGroupName>default.postgres16</DBParameterGroupName>"));
        assertTrue(body.contains("<ParameterApplyStatus>in-sync</ParameterApplyStatus>"));
    }

    @Test
    void describeDbInstances_filterByDirectIdentifier() {
        DbInstance instance = makeInstance("mydb");
        when(service.listDbInstances("mydb", null)).thenReturn(List.of(instance));

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        handler.handle("DescribeDBInstances", p);

        verify(service).listDbInstances("mydb", null);
    }

    @Test
    void describeDbInstances_filterByFiltersParam() {
        DbInstance instance = makeInstance("mydb");
        when(service.listDbInstances("mydb", null)).thenReturn(List.of(instance));

        MultivaluedMap<String, String> p = params();
        p.add("Filters.Filter.1.Name", "db-instance-id");
        p.add("Filters.Filter.1.Values.Value.1", "mydb");
        handler.handle("DescribeDBInstances", p);

        verify(service).listDbInstances("mydb", null);
    }

    @Test
    void describeDbInstances_directIdentifierTakesPriorityOverFilters() {
        when(service.listDbInstances(any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(List.of());

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "direct-id");
        p.add("Filters.Filter.1.Name", "db-instance-id");
        p.add("Filters.Filter.1.Values.Value.1", "filter-id");
        handler.handle("DescribeDBInstances", p);

        verify(service).listDbInstances("direct-id", null);
    }

    @Test
    void describeDbInstances_dbSubnetGroupUsesSubnetTag() {
        DbInstance instance = makeInstance("mydb");
        instance.setDbSubnetGroupName("custom-group");
        when(service.getDbSubnetGroup("custom-group", null)).thenReturn(customSubnetGroup());
        when(service.listDbInstances(null, null)).thenReturn(List.of(instance));

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
        when(service.listDbClusters(null, null)).thenReturn(List.of(cluster));

        Response response = handler.handle("DescribeDBClusters", params());

        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBCluster>"), "Expected <DBCluster> element in response");
        assertFalse(body.contains("<member><DBClusterIdentifier>"), "Did not expect <member> wrapping DBCluster");
    }

    @Test
    void describeDbClusters_filterByFiltersParam() {
        when(service.listDbClusters("mycluster", null)).thenReturn(List.of());

        MultivaluedMap<String, String> p = params();
        p.add("Filters.Filter.1.Name", "db-cluster-id");
        p.add("Filters.Filter.1.Values.Value.1", "mycluster");
        handler.handle("DescribeDBClusters", p);

        verify(service).listDbClusters("mycluster", null);
    }

    @Test
    void describeDbInstances_unknownFilterFallsBackToUnfilteredList() {
        when(service.listDbInstances(null, null)).thenReturn(List.of());

        MultivaluedMap<String, String> p = params();
        p.add("Filters.Filter.1.Name", "engine");
        p.add("Filters.Filter.1.Values.Value.1", "postgres");
        handler.handle("DescribeDBInstances", p);

        verify(service).listDbInstances(null, null);
    }

    @Test
    void instanceAndClusterActionsPropagateSignedRegion() {
        DbInstance instance = makeInstance("mydb");
        DbCluster cluster = makeCluster("mycluster");
        when(service.listDbInstances(null, "us-west-2")).thenReturn(List.of());
        when(service.getDbInstance("mydb", "us-west-2")).thenReturn(instance);
        when(service.modifyDbInstance(
                eq("mydb"), isNull(), isNull(), isNull(), anyList(), eq("us-west-2")))
                .thenReturn(instance);
        when(service.rebootDbInstance("mydb", "us-west-2")).thenReturn(instance);
        when(service.listDbClusters(null, "us-west-2")).thenReturn(List.of());
        when(service.getDbCluster("mycluster", "us-west-2")).thenReturn(cluster);
        when(service.modifyDbCluster("mycluster", null, null, "us-west-2"))
                .thenReturn(cluster);

        handler.handle("DescribeDBInstances", params(), "us-west-2");
        MultivaluedMap<String, String> instanceParams = params();
        instanceParams.add("DBInstanceIdentifier", "mydb");
        handler.handle("DeleteDBInstance", instanceParams, "us-west-2");
        handler.handle("ModifyDBInstance", instanceParams, "us-west-2");
        handler.handle("RebootDBInstance", instanceParams, "us-west-2");

        handler.handle("DescribeDBClusters", params(), "us-west-2");
        MultivaluedMap<String, String> clusterParams = params();
        clusterParams.add("DBClusterIdentifier", "mycluster");
        handler.handle("DeleteDBCluster", clusterParams, "us-west-2");
        handler.handle("ModifyDBCluster", clusterParams, "us-west-2");

        verify(service).listDbInstances(null, "us-west-2");
        verify(service).getDbInstance("mydb", "us-west-2");
        verify(service).deleteDbInstance("mydb", "us-west-2");
        verify(service).modifyDbInstance(
                eq("mydb"), isNull(), isNull(), isNull(), anyList(), eq("us-west-2"));
        verify(service).rebootDbInstance("mydb", "us-west-2");
        verify(service).listDbClusters(null, "us-west-2");
        verify(service).getDbCluster("mycluster", "us-west-2");
        verify(service).deleteDbCluster("mycluster", "us-west-2");
        verify(service).modifyDbCluster("mycluster", null, null, "us-west-2");
    }

    @Test
    void subnetAndParameterGroupActionsPropagateSignedRegion() {
        DbSubnetGroup subnetGroup = customSubnetGroup();
        DbParameterGroup parameterGroup = new DbParameterGroup(
                "pg1", "postgres16", "parameter group");
        DbClusterParameterGroup clusterParameterGroup = new DbClusterParameterGroup(
                "cpg1", "aurora-postgresql16", "cluster parameter group");
        when(service.modifyDbSubnetGroup(
                "custom-group", List.of("subnet-a", "subnet-b"), "us-west-2"))
                .thenReturn(subnetGroup);
        when(service.listDbParameterGroups(null, "us-west-2"))
                .thenReturn(List.of(parameterGroup));
        when(service.getDbParameterGroup("pg1", "us-west-2"))
                .thenReturn(parameterGroup);
        when(service.modifyDbParameterGroup(
                "pg1", Map.of("max_connections", "200"), "us-west-2"))
                .thenReturn(parameterGroup);
        when(service.listDbClusterParameterGroups(null, "us-west-2"))
                .thenReturn(List.of(clusterParameterGroup));
        when(service.getDbClusterParameterGroup("cpg1", "us-west-2"))
                .thenReturn(clusterParameterGroup);
        when(service.modifyDbClusterParameterGroup(
                "cpg1", Map.of("log_statement", "all"), "us-west-2"))
                .thenReturn(clusterParameterGroup);

        MultivaluedMap<String, String> subnetParams = params();
        subnetParams.add("DBSubnetGroupName", "custom-group");
        subnetParams.add("SubnetIds.SubnetIdentifier.1", "subnet-a");
        subnetParams.add("SubnetIds.SubnetIdentifier.2", "subnet-b");
        handler.handle("ModifyDBSubnetGroup", subnetParams, "us-west-2");
        handler.handle("DeleteDBSubnetGroup", subnetParams, "us-west-2");

        handler.handle("DescribeDBParameterGroups", params(), "us-west-2");
        MultivaluedMap<String, String> parameterParams = params();
        parameterParams.add("DBParameterGroupName", "pg1");
        parameterParams.add("Parameters.member.1.ParameterName", "max_connections");
        parameterParams.add("Parameters.member.1.ParameterValue", "200");
        handler.handle("ModifyDBParameterGroup", parameterParams, "us-west-2");
        handler.handle("DescribeDBParameters", parameterParams, "us-west-2");
        handler.handle("DeleteDBParameterGroup", parameterParams, "us-west-2");

        handler.handle("DescribeDBClusterParameterGroups", params(), "us-west-2");
        MultivaluedMap<String, String> clusterParameterParams = params();
        clusterParameterParams.add("DBClusterParameterGroupName", "cpg1");
        clusterParameterParams.add("Parameters.member.1.ParameterName", "log_statement");
        clusterParameterParams.add("Parameters.member.1.ParameterValue", "all");
        handler.handle("ModifyDBClusterParameterGroup", clusterParameterParams, "us-west-2");
        handler.handle("DescribeDBClusterParameters", clusterParameterParams, "us-west-2");
        handler.handle("DeleteDBClusterParameterGroup", clusterParameterParams, "us-west-2");

        verify(service).modifyDbSubnetGroup(
                "custom-group", List.of("subnet-a", "subnet-b"), "us-west-2");
        verify(service).deleteDbSubnetGroup("custom-group", "us-west-2");
        verify(service).listDbParameterGroups(null, "us-west-2");
        verify(service).modifyDbParameterGroup(
                "pg1", Map.of("max_connections", "200"), "us-west-2");
        verify(service).getDbParameterGroup("pg1", "us-west-2");
        verify(service).deleteDbParameterGroup("pg1", "us-west-2");
        verify(service).listDbClusterParameterGroups(null, "us-west-2");
        verify(service).modifyDbClusterParameterGroup(
                "cpg1", Map.of("log_statement", "all"), "us-west-2");
        verify(service).getDbClusterParameterGroup("cpg1", "us-west-2");
        verify(service).deleteDbClusterParameterGroup("cpg1", "us-west-2");
    }

    @Test
    void describeDbInstances_usesStoredDbSubnetGroup() {
        DbInstance instance = makeInstance("mydb");
        instance.setDbSubnetGroupName("sample-db-subnets");
        when(service.listDbInstances(null, null)).thenReturn(List.of(instance));
        when(service.getDbSubnetGroup("sample-db-subnets", null)).thenReturn(new DbSubnetGroup(
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
        when(service.listDbInstances(null, null)).thenReturn(List.of(instance));

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
                eq(java.util.Map.of("example:ClusterId", "cluster-a", "Name", "mydb")), eq(List.of()), isNull()))
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
                java.util.Map.of("example:ClusterId", "cluster-a", "Name", "mydb"), List.of(), null);
    }

    @Test
    void createDbInstance_passesVpcSecurityGroupsToServiceAndXml() {
        DbInstance instance = makeInstance("mydb");
        instance.setVpcSecurityGroupIds(List.of("sg-123", "sg-456"));
        when(service.createDbInstance(eq("mydb"), eq("postgres"), eq("16.3"),
                eq(null), eq(null), eq(null), eq("db.t3.micro"),
                eq(20), eq(false), eq(null), eq(null), eq(null), eq(null), eq(false), eq(false), eq(null),
                eq(java.util.Map.of()), eq(List.of("sg-123", "sg-456")), isNull()))
                .thenReturn(instance);

        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        p.add("Engine", "postgres");
        p.add("VpcSecurityGroupIds.VpcSecurityGroupId.1", "sg-123");
        p.add("VpcSecurityGroupIds.VpcSecurityGroupId.2", "sg-456");
        Response response = handler.handle("CreateDBInstance", p);

        String body = (String) response.getEntity();
        assertTrue(body.contains("<VpcSecurityGroupId>sg-123</VpcSecurityGroupId>"));
        assertTrue(body.contains("<VpcSecurityGroupId>sg-456</VpcSecurityGroupId>"));
        verify(service).createDbInstance("mydb", "postgres", "16.3",
                null, null, null, "db.t3.micro", 20, false, null, null, null, null, false, false, null,
                java.util.Map.of(), List.of("sg-123", "sg-456"), null);
    }

    @Test
    void createDbInstanceRejectsBlankVpcSecurityGroupMembers() {
        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        p.add("Engine", "postgres");
        p.add("VpcSecurityGroupIds.VpcSecurityGroupId.1", " ");

        Response response = handler.handle("CreateDBInstance", p);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("InvalidParameterValue"));
        verify(service, never()).createDbInstance(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), any(), any(), any());
    }

    @Test
    void modifyDbInstanceRejectsBlankVpcSecurityGroupMembers() {
        MultivaluedMap<String, String> p = params();
        p.add("DBInstanceIdentifier", "mydb");
        p.add("VpcSecurityGroupIds.VpcSecurityGroupId.1", "");

        Response response = handler.handle("ModifyDBInstance", p);

        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("InvalidParameterValue"));
        verify(service, never()).modifyDbInstance(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void listTagsForResource_returnsStoredTags() {
        when(service.listTagsForResource(
                "arn:aws:rds:us-east-1:000000000000:db:mydb", "us-west-2"))
                .thenReturn(java.util.Map.of("Name", "mydb"));

        MultivaluedMap<String, String> p = params();
        p.add("ResourceName", "arn:aws:rds:us-east-1:000000000000:db:mydb");
        Response response = handler.handle("ListTagsForResource", p, "us-west-2");

        String body = (String) response.getEntity();
        assertEquals(200, response.getStatus());
        assertTrue(body.contains("<TagList>"));
        assertTrue(body.contains("<Key>Name</Key>"));
        assertTrue(body.contains("<Value>mydb</Value>"));
        verify(service).listTagsForResource(
                "arn:aws:rds:us-east-1:000000000000:db:mydb", "us-west-2");
    }

    @Test
    void addAndRemoveTagsForResource_passThrough() {
        MultivaluedMap<String, String> add = params();
        add.add("ResourceName", "arn:aws:rds:us-east-1:000000000000:db:mydb");
        add.add("Tags.member.1.Key", "Name");
        add.add("Tags.member.1.Value", "mydb");
        Response addResponse = handler.handle("AddTagsToResource", add, "us-west-2");

        assertEquals(200, addResponse.getStatus());
        verify(service).addTagsToResource(
                "arn:aws:rds:us-east-1:000000000000:db:mydb",
                java.util.Map.of("Name", "mydb"), "us-west-2");

        MultivaluedMap<String, String> remove = params();
        remove.add("ResourceName", "arn:aws:rds:us-east-1:000000000000:db:mydb");
        remove.add("TagKeys.member.1", "Name");
        Response removeResponse = handler.handle("RemoveTagsFromResource", remove, "us-west-2");

        assertEquals(200, removeResponse.getStatus());
        verify(service).removeTagsFromResource(
                "arn:aws:rds:us-east-1:000000000000:db:mydb",
                List.of("Name"), "us-west-2");
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
        when(service.listDbParameterGroups(null, null)).thenReturn(List.of(group));

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
                eq(null), eq(java.util.Map.of()), eq(List.of()), isNull()))
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
                null, java.util.Map.of(), List.of(), null);
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
                eq("kms-key-1"), eq(java.util.Map.of()), eq(List.of()), isNull()))
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
                "kms-key-1", java.util.Map.of(), List.of(), null);
    }

    @Test
    void createDbInstance_withPlacementInputsShouldReflectRequestedPlacement() {
        DbInstance instance = makeInstance("mydb");
        instance.setDbInstanceArn("arn:aws:rds:us-east-1:123456789012:db:mydb");
        instance.setDbSubnetGroupName("default");
        instance.setAvailabilityZone("ap-northeast-1a");
        instance.setMultiAz(true);
        when(service.createDbInstance(eq("mydb"), eq("postgres"), eq("16.3"),
                eq("admin"), eq("secret"), eq("dbname"), eq("db.t3.micro"),
                eq(20), eq(false), eq(null), eq("default"), eq(null), eq("ap-northeast-1a"), eq(true),
                eq(false), eq(null), eq(java.util.Map.of()), eq(List.of()), isNull()))
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
                eq(false), eq(null), eq(java.util.Map.of()), eq(List.of()), isNull()))
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
        when(service.createDbSubnetGroup("sample-db-subnets", "test", List.of("subnet-aaa", "subnet-bbb"), null))
                .thenReturn(new DbSubnetGroup(
                        "sample-db-subnets", "test", "vpc-123", List.of("subnet-aaa", "subnet-bbb"),
                        Map.of("subnet-aaa", "us-east-1a", "subnet-bbb", "us-east-1b")));

        MultivaluedMap<String, String> p = params();
        p.add("DBSubnetGroupName", "sample-db-subnets");
        p.add("DBSubnetGroupDescription", "test");
        p.add("SubnetIds.SubnetIdentifier.1", "subnet-aaa");
        p.add("SubnetIds.SubnetIdentifier.2", "subnet-bbb");
        Response response = handler.handle("CreateDBSubnetGroup", p);

        verify(service).createDbSubnetGroup("sample-db-subnets", "test", List.of("subnet-aaa", "subnet-bbb"), null);
        String body = (String) response.getEntity();
        assertEquals(200, response.getStatus());
        assertTrue(body.contains("<DBSubnetGroupName>sample-db-subnets</DBSubnetGroupName>"));
        assertTrue(body.contains("<Subnets><Subnet>"));
        assertFalse(body.contains("<Subnets><member>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-aaa</SubnetIdentifier>"));
        assertTrue(body.contains("<SubnetIdentifier>subnet-bbb</SubnetIdentifier>"));
    }

    @Test
    void createDbSubnetGroupPassesRequestRegionToService() {
        when(service.createDbSubnetGroup("sample-db-subnets", "test", List.of("subnet-aaa", "subnet-bbb"), "us-west-2"))
                .thenReturn(new DbSubnetGroup(
                        "sample-db-subnets", "test", "vpc-123", List.of("subnet-aaa", "subnet-bbb"),
                        Map.of("subnet-aaa", "us-west-2a", "subnet-bbb", "us-west-2b")));

        MultivaluedMap<String, String> p = params();
        p.add("DBSubnetGroupName", "sample-db-subnets");
        p.add("DBSubnetGroupDescription", "test");
        p.add("SubnetIds.SubnetIdentifier.1", "subnet-aaa");
        p.add("SubnetIds.SubnetIdentifier.2", "subnet-bbb");

        Response response = handler.handle("CreateDBSubnetGroup", p, "us-west-2");

        assertEquals(200, response.getStatus());
        verify(service).createDbSubnetGroup("sample-db-subnets", "test", List.of("subnet-aaa", "subnet-bbb"), "us-west-2");
    }

    @Test
    void modifyDbSubnetGroup_passesSubnetMembersToService() {
        when(service.modifyDbSubnetGroup("sample-db-subnets", List.of("subnet-new-a", "subnet-new-b"), null))
                .thenReturn(new DbSubnetGroup(
                        "sample-db-subnets", "test", "vpc-123", List.of("subnet-new-a", "subnet-new-b"),
                        Map.of("subnet-new-a", "us-east-1a", "subnet-new-b", "us-east-1b")));

        MultivaluedMap<String, String> p = params();
        p.add("DBSubnetGroupName", "sample-db-subnets");
        p.add("SubnetIds.SubnetIdentifier.1", "subnet-new-a");
        p.add("SubnetIds.SubnetIdentifier.2", "subnet-new-b");
        Response response = handler.handle("ModifyDBSubnetGroup", p);

        verify(service).modifyDbSubnetGroup("sample-db-subnets", List.of("subnet-new-a", "subnet-new-b"), null);
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
                eq(null), eq(java.util.Map.of()), eq(List.of()), isNull()))
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
        when(service.modifyDbParameterGroup(
                eq("pg1"), eq(java.util.Map.of("max_connections", "200")), isNull()))
                .thenReturn(group);

        MultivaluedMap<String, String> p = params();
        p.add("DBParameterGroupName", "pg1");
        p.add("Parameters.member.1.ParameterName", "max_connections");
        p.add("Parameters.member.1.ParameterValue", "200");
        p.add("Parameters.member.2.ParameterName", "ignored_without_value");
        handler.handle("ModifyDBParameterGroup", p);

        verify(service).modifyDbParameterGroup(
                "pg1", java.util.Map.of("max_connections", "200"), null);
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
        when(service.listDbClusterParameterGroups(null, null)).thenReturn(List.of(group));

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
        when(service.createDbClusterParameterGroup(
                "cpg1", "aurora-postgresql16", "desc", null)).thenReturn(group);

        MultivaluedMap<String, String> p = params();
        p.add("DBClusterParameterGroupName", "cpg1");
        p.add("DBParameterGroupFamily", "aurora-postgresql16");
        p.add("Description", "desc");
        Response response = handler.handle("CreateDBClusterParameterGroup", p);

        verify(service).createDbClusterParameterGroup(
                "cpg1", "aurora-postgresql16", "desc", null);
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBClusterParameterGroupName>cpg1</DBClusterParameterGroupName>"));
        assertTrue(body.contains("<DBParameterGroupFamily>aurora-postgresql16</DBParameterGroupFamily>"));
    }

    @Test
    void modifyDbClusterParameterGroup_ignoresParametersWithoutValue() {
        DbClusterParameterGroup group = new DbClusterParameterGroup("cpg1", "aurora-postgresql16", "test group");
        when(service.modifyDbClusterParameterGroup(
                eq("cpg1"), eq(java.util.Map.of("log_statement", "all")), isNull()))
                .thenReturn(group);

        MultivaluedMap<String, String> p = params();
        p.add("DBClusterParameterGroupName", "cpg1");
        p.add("Parameters.member.1.ParameterName", "log_statement");
        p.add("Parameters.member.1.ParameterValue", "all");
        p.add("Parameters.member.2.ParameterName", "ignored_without_value");
        handler.handle("ModifyDBClusterParameterGroup", p);

        verify(service).modifyDbClusterParameterGroup(
                "cpg1", java.util.Map.of("log_statement", "all"), null);
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
        when(service.listDbClusters(null, null)).thenReturn(List.of(cluster));

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
        when(service.createDbSubnetGroup("my-subnet-group", "test subnet group", List.of("subnet-a", "subnet-b"), null))
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
        when(service.listDbSubnetGroups(null, null)).thenReturn(List.of(group));

        Response response = handler.handle("DescribeDBSubnetGroups", params());

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DBSubnetGroups>"));
        assertTrue(body.contains("<DBSubnetGroupArn>arn:aws:rds:us-east-1:123456789012:subgrp:default</DBSubnetGroupArn>"));
    }

    // ──────────────────────────── Snapshots & Proxies (empty lists) ─────────────

    @Test
    void describeDbSnapshots_returnsEmptyListWith200() {
        Response response = handler.handle("DescribeDBSnapshots", params());

        String body = (String) response.getEntity();
        assertEquals(200, response.getStatus());
        assertTrue(body.contains("<DescribeDBSnapshotsResult>"));
        assertTrue(body.contains("<DBSnapshots></DBSnapshots>"));
        assertFalse(body.contains("<Marker>"));
    }

    @Test
    void describeDbProxies_returnsEmptyListWith200() {
        Response response = handler.handle("DescribeDBProxies", params());

        String body = (String) response.getEntity();
        assertEquals(200, response.getStatus());
        assertTrue(body.contains("<DescribeDBProxiesResult>"));
        assertTrue(body.contains("<DBProxies></DBProxies>"));
        assertFalse(body.contains("<Marker>"));
    }

    @Test
    void describeDbClusterSnapshots_returnsEmptyListWith200() {
        Response response = handler.handle("DescribeDBClusterSnapshots", params());

        String body = (String) response.getEntity();
        assertEquals(200, response.getStatus());
        assertTrue(body.contains("<DescribeDBClusterSnapshotsResult>"));
        assertTrue(body.contains("<DBClusterSnapshots></DBClusterSnapshots>"));
        assertFalse(body.contains("<Marker>"));
    }

    // ──────────────────────────── DBProxy wire shapes ──────────────────────────

    @Test
    void createDbProxy_mapsParamsAndReturnsProxyEnvelope() {
        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName("app-proxy");
        proxy.setEngineFamily("POSTGRESQL");
        proxy.setEndpointHost("app-proxy.host");
        proxy.setDbProxyArn("arn:aws:rds:us-west-2:000000000000:db-proxy:prx-abc");
        proxy.setDefaultAuthScheme("IAM_AUTH");
        proxy.setIdleClientTimeout(120);
        proxy.setDebugLogging(true);
        proxy.setVpcId("vpc-default");
        proxy.setVpcSecurityGroupIds(List.of("sg-a"));
        DbProxyAuth proxyAuth = new DbProxyAuth("SECRETS", "arn:secret", "DISABLED",
                "POSTGRES_SCRAM_SHA_256", "application credentials");
        proxyAuth.setUserName("database-user");
        proxy.setAuth(List.of(proxyAuth));
        when(service.createDbProxy(eq("app-proxy"), eq("POSTGRESQL"), eq(true), eq(true),
                eq("IAM_AUTH"), eq("arn:aws:iam::000000000000:role/proxy"),
                anyList(), anyList(), anyList(),
                eq(120), eq(true), eq(Map.of("owner", "platform")), eq("us-west-2")))
                .thenReturn(proxy);

        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        p.add("EngineFamily", "POSTGRESQL");
        p.add("DefaultAuthScheme", "IAM_AUTH");
        p.add("EndpointNetworkType", "IPV4");
        p.add("TargetConnectionNetworkType", "IPV4");
        p.add("RequireTLS", "true");
        p.add("DebugLogging", "true");
        p.add("IdleClientTimeout", "120");
        p.add("RoleArn", "arn:aws:iam::000000000000:role/proxy");
        p.add("VpcSubnetIds.member.1", "subnet-a");
        p.add("VpcSubnetIds.member.2", "subnet-b");
        p.add("VpcSecurityGroupIds.member.1", "sg-a");
        p.add("Tags.Tag.1.Key", "owner");
        p.add("Tags.Tag.1.Value", "platform");
        p.add("Auth.member.1.AuthScheme", "SECRETS");
        p.add("Auth.member.1.SecretArn", "arn:aws:secretsmanager:us-east-1:000000000000:secret:db-AbCdEf");
        p.add("Auth.member.1.IAMAuth", "DISABLED");
        p.add("Auth.member.1.UserName", "database-user");
        Response response = handler.handle("CreateDBProxy", p, "us-west-2");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<CreateDBProxyResult>"));
        assertTrue(body.contains("<DBProxy>"));
        assertTrue(body.contains("<DBProxyName>app-proxy</DBProxyName>"));
        assertTrue(body.contains("<Endpoint>app-proxy.host</Endpoint>"));
        assertTrue(body.contains("<DBProxyArn>arn:aws:rds:us-west-2:000000000000:db-proxy:prx-abc</DBProxyArn>"));
        assertTrue(body.contains("<DefaultAuthScheme>IAM_AUTH</DefaultAuthScheme>"));
        assertTrue(body.contains("<EndpointNetworkType>IPV4</EndpointNetworkType>"));
        assertTrue(body.contains("<TargetConnectionNetworkType>IPV4</TargetConnectionNetworkType>"));
        assertTrue(body.contains("<VpcId>vpc-default</VpcId>"));
        assertTrue(body.contains("<IdleClientTimeout>120</IdleClientTimeout>"));
        assertTrue(body.contains("<DebugLogging>true</DebugLogging>"));
        assertTrue(body.contains("<VpcSecurityGroupIds><member>sg-a</member></VpcSecurityGroupIds>"));
        assertTrue(body.contains("<ClientPasswordAuthType>POSTGRES_SCRAM_SHA_256</ClientPasswordAuthType>"));
        assertTrue(body.contains("<Description>application credentials</Description>"));
        assertTrue(body.contains("<UserName>database-user</UserName>"));
        // DefaultAuthScheme=IAM_AUTH enables IAM even when the Auth entry itself is DISABLED.
        verify(service).createDbProxy(eq("app-proxy"), eq("POSTGRESQL"), eq(true), eq(true),
                eq("IAM_AUTH"), eq("arn:aws:iam::000000000000:role/proxy"),
                anyList(), anyList(), argThat(auth -> auth.size() == 1
                        && "database-user".equals(auth.getFirst().getUserName())),
                eq(120), eq(true), eq(Map.of("owner", "platform")), eq("us-west-2"));
    }

    @Test
    void createSqlServerDbProxyEnablesIamFromAuthEntryAndPreservesUserName() {
        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName("sqlserver-proxy");
        proxy.setEngineFamily("SQLSERVER");
        proxy.setEndpointHost("sqlserver-proxy.host");
        proxy.setDbProxyArn(
                "arn:aws:rds:us-west-2:000000000000:db-proxy:prx-sqlserver");
        DbProxyAuth returnedAuth = new DbProxyAuth(
                "SECRETS", "arn:aws:secretsmanager:us-west-2:000000000000:secret:sqlserver",
                "ENABLED", "SQL_SERVER_AUTHENTICATION", "SQL Server credentials");
        returnedAuth.setUserName("database-user");
        proxy.setAuth(List.of(returnedAuth));
        when(service.createDbProxy(
                eq("sqlserver-proxy"), eq("SQLSERVER"), eq(true), eq(true), isNull(),
                eq("arn:aws:iam::000000000000:role/proxy"), anyList(), anyList(),
                argThat(auth -> auth.size() == 1
                        && "ENABLED".equals(auth.getFirst().getIamAuth())
                        && "database-user".equals(auth.getFirst().getUserName())),
                eq(1800), eq(false), eq(Map.of()), eq("us-west-2")))
                .thenReturn(proxy);

        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "sqlserver-proxy");
        p.add("EngineFamily", "SQLSERVER");
        p.add("RequireTLS", "true");
        p.add("RoleArn", "arn:aws:iam::000000000000:role/proxy");
        p.add("VpcSubnetIds.member.1", "subnet-a");
        p.add("VpcSubnetIds.member.2", "subnet-b");
        p.add("Auth.member.1.AuthScheme", "SECRETS");
        p.add("Auth.member.1.SecretArn",
                "arn:aws:secretsmanager:us-west-2:000000000000:secret:sqlserver");
        p.add("Auth.member.1.IAMAuth", "ENABLED");
        p.add("Auth.member.1.ClientPasswordAuthType", "SQL_SERVER_AUTHENTICATION");
        p.add("Auth.member.1.UserName", "database-user");

        Response response = handler.handle("CreateDBProxy", p, "us-west-2");

        assertEquals(200, response.getStatus());
        verify(service).createDbProxy(
                eq("sqlserver-proxy"), eq("SQLSERVER"), eq(true), eq(true), isNull(),
                eq("arn:aws:iam::000000000000:role/proxy"), anyList(), anyList(),
                argThat(auth -> auth.size() == 1
                        && "ENABLED".equals(auth.getFirst().getIamAuth())
                        && "database-user".equals(auth.getFirst().getUserName())),
                eq(1800), eq(false), eq(Map.of()), eq("us-west-2"));
    }

    @Test
    void createDbProxyRejectsUnsupportedOrInvalidNetworkTypesBeforeCallingService() {
        MultivaluedMap<String, String> ipv6 = params();
        ipv6.add("EndpointNetworkType", "IPV6");

        Response ipv6Response = handler.handle("CreateDBProxy", ipv6, "us-west-2");

        assertEquals(400, ipv6Response.getStatus());
        assertTrue(((String) ipv6Response.getEntity()).contains("UnsupportedOperation"));

        MultivaluedMap<String, String> invalidTargetType = params();
        invalidTargetType.add("TargetConnectionNetworkType", "DUAL");

        Response invalidResponse = handler.handle(
                "CreateDBProxy", invalidTargetType, "us-west-2");

        assertEquals(400, invalidResponse.getStatus());
        assertTrue(((String) invalidResponse.getEntity()).contains("InvalidParameterValue"));
        verifyNoInteractions(service);
    }

    @Test
    void modifyDbProxy_mapsOptionalFieldsAndReturnsUpdatedProxy() {
        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName("app-proxy");
        proxy.setDbProxyArn("arn:aws:rds:us-west-2:000000000000:db-proxy:prx-abc");
        proxy.setEngineFamily("POSTGRESQL");
        proxy.setEndpointHost("app-proxy.host");
        proxy.setDefaultAuthScheme("IAM_AUTH");
        proxy.setRequireTls(false);
        proxy.setIdleClientTimeout(900);
        proxy.setDebugLogging(true);
        proxy.setRoleArn("arn:aws:iam::000000000000:role/updated-proxy");
        proxy.setVpcSecurityGroupIds(List.of("sg-a", "sg-b"));
        proxy.setAuth(List.of(new DbProxyAuth(
                "SECRETS", "arn:secret", "DISABLED", "POSTGRES_MD5", "updated")));
        when(service.modifyDbProxy(
                eq("app-proxy"), eq("IAM_AUTH"), anyList(), eq(false), eq(900), eq(true),
                eq("arn:aws:iam::000000000000:role/updated-proxy"),
                eq(List.of("sg-a", "sg-b")), isNull(), eq("us-west-2")))
                .thenReturn(proxy);

        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        p.add("DefaultAuthScheme", "IAM_AUTH");
        p.add("RequireTLS", "false");
        p.add("IdleClientTimeout", "900");
        p.add("DebugLogging", "true");
        p.add("RoleArn", "arn:aws:iam::000000000000:role/updated-proxy");
        p.add("SecurityGroups.member.1", "sg-a");
        p.add("SecurityGroups.member.2", "sg-b");
        p.add("Auth.member.1.AuthScheme", "SECRETS");
        p.add("Auth.member.1.SecretArn", "arn:secret");
        p.add("Auth.member.1.IAMAuth", "DISABLED");
        p.add("Auth.member.1.ClientPasswordAuthType", "POSTGRES_MD5");
        p.add("Auth.member.1.Description", "updated");

        Response response = handler.handle("ModifyDBProxy", p, "us-west-2");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<ModifyDBProxyResult>"));
        assertTrue(body.contains("<DefaultAuthScheme>IAM_AUTH</DefaultAuthScheme>"));
        assertTrue(body.contains("<RequireTLS>false</RequireTLS>"));
        assertTrue(body.contains("<IdleClientTimeout>900</IdleClientTimeout>"));
        assertTrue(body.contains("<DebugLogging>true</DebugLogging>"));
        assertTrue(body.contains("<VpcSecurityGroupIds><member>sg-a</member><member>sg-b</member>"));
        assertTrue(body.contains("<ClientPasswordAuthType>POSTGRES_MD5</ClientPasswordAuthType>"));
        verify(service).modifyDbProxy(
                eq("app-proxy"), eq("IAM_AUTH"), argThat(auth -> auth.size() == 1
                        && "arn:secret".equals(auth.getFirst().getSecretArn())
                        && "DISABLED".equals(auth.getFirst().getIamAuth())),
                eq(false), eq(900), eq(true),
                eq("arn:aws:iam::000000000000:role/updated-proxy"),
                eq(List.of("sg-a", "sg-b")), isNull(), eq("us-west-2"));
    }

    @Test
    void modifyDbProxy_rejectsMalformedOptionalValuesBeforeCallingService() {
        MultivaluedMap<String, String> invalidBoolean = params();
        invalidBoolean.add("DBProxyName", "app-proxy");
        invalidBoolean.add("RequireTLS", "sometimes");

        Response booleanResponse = handler.handle(
                "ModifyDBProxy", invalidBoolean, "us-west-2");

        assertEquals(400, booleanResponse.getStatus());
        assertTrue(((String) booleanResponse.getEntity()).contains("InvalidParameterValue"));
        assertTrue(((String) booleanResponse.getEntity()).contains("RequireTLS"));

        MultivaluedMap<String, String> invalidInteger = params();
        invalidInteger.add("DBProxyName", "app-proxy");
        invalidInteger.add("IdleClientTimeout", "not-an-integer");

        Response integerResponse = handler.handle(
                "ModifyDBProxy", invalidInteger, "us-west-2");

        assertEquals(400, integerResponse.getStatus());
        assertTrue(((String) integerResponse.getEntity()).contains("InvalidParameterValue"));
        assertTrue(((String) integerResponse.getEntity()).contains("IdleClientTimeout"));
        verifyNoInteractions(service);
    }

    @Test
    void modifyDbProxy_rejectsNewDbProxyNameBeforeCallingService() {
        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        p.add("NewDBProxyName", "renamed-proxy");

        Response response = handler.handle("ModifyDBProxy", p, "us-west-2");

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("UnsupportedOperation"));
        assertTrue(body.contains("NewDBProxyName"));
        verifyNoInteractions(service);
    }

    @Test
    void describeDbProxies_rendersProxyMembers() {
        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName("app-proxy");
        proxy.setEndpointHost("app-proxy.host");
        proxy.setDbProxyArn("arn:aws:rds:us-east-1:000000000000:db-proxy:prx-abc");
        when(service.listDbProxies("app-proxy", "us-west-2")).thenReturn(List.of(proxy));

        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        Response response = handler.handle("DescribeDBProxies", p, "us-west-2");

        String body = (String) response.getEntity();
        assertEquals(200, response.getStatus());
        assertTrue(body.contains("<DescribeDBProxiesResult>"));
        assertTrue(body.contains("<DBProxies><member>"));
        assertTrue(body.contains("<DBProxyName>app-proxy</DBProxyName>"));
        verify(service).listDbProxies("app-proxy", "us-west-2");
    }

    @Test
    void registerDbProxyTargets_mapsClusterIdsAndRendersTargets() {
        DbProxyTargetGroup tg = new DbProxyTargetGroup();
        tg.setDbProxyName("app-proxy");
        tg.getTargets().add(new DbProxyTarget("TRACKED_CLUSTER", "cluster1",
                "arn:aws:rds:us-east-1:000000000000:cluster:cluster1", "cluster1.host", 5432));
        when(service.registerDbProxyTargets(
                any(), any(), anyList(), anyList(), anyInt(), anyInt(), any()))
                .thenReturn(tg);

        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        p.add("TargetGroupName", "default");
        p.add("DBClusterIdentifiers.member.1", "cluster1");
        Response response = handler.handle("RegisterDBProxyTargets", p, "us-west-2");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<RegisterDBProxyTargetsResult>"));
        assertTrue(body.contains("<DBProxyTargets><member>"));
        assertTrue(body.contains("<Type>TRACKED_CLUSTER</Type>"));
        assertTrue(body.contains("<RdsResourceId>cluster1</RdsResourceId>"));
        verify(service).registerDbProxyTargets(
                "app-proxy", "default", List.of("cluster1"), List.of(), 0, 0, "us-west-2");
    }

    @Test
    void deregisterDbProxyTargetsMapsIdentifiersAndReturnsEmptyResult() {
        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        p.add("TargetGroupName", "default");
        p.add("DBInstanceIdentifiers.member.1", "instance1");

        Response response = handler.handle("DeregisterDBProxyTargets", p, "us-west-2");

        assertEquals(200, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<DeregisterDBProxyTargetsResult>"));
        verify(service).deregisterDbProxyTargets("app-proxy", "default",
                List.of(), List.of("instance1"), "us-west-2");
    }

    @Test
    void describeDbProxyTargetGroups_rendersTargetGroupMembers() {
        DbProxyTargetGroup tg = new DbProxyTargetGroup();
        tg.setDbProxyName("app-proxy");
        tg.setTargetGroupName("default");
        tg.setTargetGroupArn("arn:aws:rds:us-east-1:000000000000:target-group:app-proxy/default");
        when(service.describeDbProxyTargetGroups("app-proxy", null, "us-west-2"))
                .thenReturn(List.of(tg));

        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        Response response = handler.handle("DescribeDBProxyTargetGroups", p, "us-west-2");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeDBProxyTargetGroupsResult>"));
        assertTrue(body.contains("<TargetGroups><member>"));
        assertTrue(body.contains("<TargetGroupName>default</TargetGroupName>"));
        assertTrue(body.contains("<ConnectionPoolConfig>"));
        verify(service).describeDbProxyTargetGroups("app-proxy", null, "us-west-2");
    }

    @Test
    void modifyDbProxyTargetGroup_mapsFullPoolConfigurationAndReturnsUpdatedGroup() {
        DbProxyTargetGroup targetGroup = new DbProxyTargetGroup();
        targetGroup.setDbProxyName("app-proxy");
        targetGroup.setTargetGroupName("default");
        targetGroup.setTargetGroupArn(
                "arn:aws:rds:us-west-2:000000000000:target-group:prx-tg-abc");
        targetGroup.setMaxConnectionsPercent(90);
        targetGroup.setMaxIdleConnectionsPercent(40);
        targetGroup.setConnectionBorrowTimeout(55);
        targetGroup.setInitQuery("SET application_name = 'floci'");
        targetGroup.setSessionPinningFilters(List.of("EXCLUDE_VARIABLE_SETS"));
        when(service.configureDbProxyTargetGroup(
                "app-proxy", "default", 90, 40, 55,
                "SET application_name = 'floci'",
                List.of("EXCLUDE_VARIABLE_SETS"),
                "us-west-2"))
                .thenReturn(targetGroup);

        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        p.add("TargetGroupName", "default");
        p.add("ConnectionPoolConfig.MaxConnectionsPercent", "90");
        p.add("ConnectionPoolConfig.MaxIdleConnectionsPercent", "40");
        p.add("ConnectionPoolConfig.ConnectionBorrowTimeout", "55");
        p.add("ConnectionPoolConfig.InitQuery", "SET application_name = 'floci'");
        p.add("ConnectionPoolConfig.SessionPinningFilters.member.1", "EXCLUDE_VARIABLE_SETS");

        Response response = handler.handle(
                "ModifyDBProxyTargetGroup", p, "us-west-2");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<ModifyDBProxyTargetGroupResult>"));
        assertTrue(body.contains("<DBProxyTargetGroup>"));
        assertTrue(body.contains("<MaxConnectionsPercent>90</MaxConnectionsPercent>"));
        assertTrue(body.contains("<MaxIdleConnectionsPercent>40</MaxIdleConnectionsPercent>"));
        assertTrue(body.contains("<ConnectionBorrowTimeout>55</ConnectionBorrowTimeout>"));
        assertTrue(body.contains("<InitQuery>SET application_name = &apos;floci&apos;</InitQuery>"));
        assertTrue(body.contains("<SessionPinningFilters><member>EXCLUDE_VARIABLE_SETS</member>"
                + "</SessionPinningFilters>"));
        verify(service).configureDbProxyTargetGroup(
                "app-proxy", "default", 90, 40, 55,
                "SET application_name = 'floci'",
                List.of("EXCLUDE_VARIABLE_SETS"),
                "us-west-2");
    }

    @Test
    void modifyDbProxyTargetGroup_rejectsNewNameBeforeCallingService() {
        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        p.add("TargetGroupName", "default");
        p.add("NewName", "renamed-target-group");

        Response response = handler.handle(
                "ModifyDBProxyTargetGroup", p, "us-west-2");

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("UnsupportedOperation"));
        assertTrue(body.contains("cannot be renamed"));
        verifyNoInteractions(service);
    }

    @Test
    void proxyTargetActionsRequireTargetGroupName() {
        for (String action : List.of(
                "RegisterDBProxyTargets", "DeregisterDBProxyTargets",
                "DescribeDBProxyTargets", "ModifyDBProxyTargetGroup")) {
            MultivaluedMap<String, String> p = params();
            p.add("DBProxyName", "app-proxy");

            Response response = handler.handle(action, p, "us-west-2");

            assertEquals(400, response.getStatus(), action);
            assertTrue(((String) response.getEntity()).contains("TargetGroupName"), action);
        }
        verifyNoInteractions(service);
    }

    @Test
    void describeDbProxyTargets_rendersTargetMembers() {
        when(service.describeDbProxyTargets("app-proxy", "default", "us-west-2")).thenReturn(List.of(
                new DbProxyTarget("RDS_INSTANCE", "inst1",
                        "arn:aws:rds:us-east-1:000000000000:db:inst1", "inst1.host", 5432)));

        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        p.add("TargetGroupName", "default");
        Response response = handler.handle("DescribeDBProxyTargets", p, "us-west-2");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DescribeDBProxyTargetsResult>"));
        assertTrue(body.contains("<Targets><member>"));
        assertTrue(body.contains("<Type>RDS_INSTANCE</Type>"));
        assertTrue(body.contains("<RdsResourceId>inst1</RdsResourceId>"));
        verify(service).describeDbProxyTargets("app-proxy", "default", "us-west-2");
    }

    @Test
    void deleteDbProxy_delegatesToServiceAndReturnsProxy() {
        DbProxy proxy = new DbProxy();
        proxy.setDbProxyName("app-proxy");
        proxy.setDbProxyArn("arn:aws:rds:us-east-1:000000000000:db-proxy:prx-abc");
        when(service.getDbProxy("app-proxy", "us-west-2")).thenReturn(proxy);

        MultivaluedMap<String, String> p = params();
        p.add("DBProxyName", "app-proxy");
        Response response = handler.handle("DeleteDBProxy", p, "us-west-2");

        assertEquals(200, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<DeleteDBProxyResult>"));
        assertTrue(body.contains("<DBProxyName>app-proxy</DBProxyName>"));
        verify(service).getDbProxy("app-proxy", "us-west-2");
        verify(service).deleteDbProxy("app-proxy", "us-west-2");
    }

    @Test
    void describeDbSubnetGroupsPassesSignedRegionToService() {
        DbSubnetGroup group = new DbSubnetGroup();
        group.setDbSubnetGroupName("default");
        group.setDbSubnetGroupArn("arn:aws:rds:us-west-2:123456789012:subgrp:default");
        when(service.listDbSubnetGroups(null, "us-west-2")).thenReturn(List.of(group));

        Response response = handler.handle("DescribeDBSubnetGroups", params(), "us-west-2");

        assertEquals(200, response.getStatus());
        verify(service).listDbSubnetGroups(null, "us-west-2");
    }

    @Test
    void describeDbSubnetGroups_missingNameReturnsNotFoundFault() {
        when(service.listDbSubnetGroups("does-not-exist", null))
                .thenThrow(new AwsException("DBSubnetGroupNotFoundFault",
                        "DB subnet group does-not-exist not found.", 404));

        MultivaluedMap<String, String> p = params();
        p.add("DBSubnetGroupName", "does-not-exist");

        Response response = handler.handle("DescribeDBSubnetGroups", p);

        assertEquals(404, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("DBSubnetGroupNotFoundFault"));
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
