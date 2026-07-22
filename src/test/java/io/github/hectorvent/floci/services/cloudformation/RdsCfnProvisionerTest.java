package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbProxy;
import io.github.hectorvent.floci.services.rds.model.DbProxyAuth;
import io.github.hectorvent.floci.services.rds.model.DbProxyTargetGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies CloudFormation provisions RDS resources by delegating to {@link RdsService} (mocked, so
 * no containers start) and maps CFN properties to the right create-method arguments, with
 * Ref/GetAtt set from the returned resource.
 */
class RdsCfnProvisionerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private RdsService rdsService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        rdsService = mock(RdsService.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                mapper,
                null, null, null, null, null, null, null,
                rdsService, null, null, null, null, null, null,
                new io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry(java.util.List.of()));
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    private JsonNode props(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private StackResource provision(String logicalId, String type, String json) {
        return provision(logicalId, type, json, "us-east-1");
    }

    private StackResource provision(String logicalId, String type, String json, String region) {
        return provisioner.provision(logicalId, type, props(json), engine(),
                region, "000000000000", "my-stack");
    }

    private StackResource provisionExisting(String logicalId, String type, String json,
                                            String region, String physicalId,
                                            Map<String, String> attributes) {
        return provisioner.provision(logicalId, type, props(json), engine(),
                region, "000000000000", "my-stack", physicalId, attributes);
    }

    @Test
    void provisionsDbInstanceWithMappedArgsAndEndpointAttributes() {
        DbInstance instance = mock(DbInstance.class);
        when(instance.getDbInstanceIdentifier()).thenReturn("mydb");
        when(instance.getEndpoint()).thenReturn(new DbEndpoint("mydb.local", 5432));
        when(instance.getDbInstanceArn()).thenReturn("arn:aws:rds:us-east-1:000000000000:db:mydb");
        when(rdsService.createDbInstance(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), anyMap(), nullable(String.class))).thenReturn(instance);

        StackResource r = provision("Db", "AWS::RDS::DBInstance", """
                {"DBInstanceIdentifier":"mydb","Engine":"postgres","EngineVersion":"16",
                 "MasterUsername":"admin","MasterUserPassword":"secret","DBName":"appdb",
                 "AllocatedStorage":50,"DBInstanceClass":"db.t3.small"}
                """);

        assertEquals("CREATE_COMPLETE", r.getStatus());
        assertEquals("mydb", r.getPhysicalId());
        assertEquals("mydb.local", r.getAttributes().get("Endpoint.Address"));
        assertEquals("5432", r.getAttributes().get("Endpoint.Port"));
        assertEquals("arn:aws:rds:us-east-1:000000000000:db:mydb", r.getAttributes().get("DBInstanceArn"));
        // CFN properties mapped to the create-method arguments; absent optionals are null.
        verify(rdsService).createDbInstance("mydb", "postgres", "16", "admin", "secret",
                "appdb", "db.t3.small", 50, false, null, null, null,
                null, false, false, null, Map.of(), "us-east-1");
    }

    @Test
    void provisionsDbInstanceInStackRegion() {
        DbInstance instance = mock(DbInstance.class);
        when(instance.getDbInstanceIdentifier()).thenReturn("mydb");
        when(rdsService.createDbInstance(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), anyMap(), nullable(String.class))).thenReturn(instance);

        provision("Db", "AWS::RDS::DBInstance", """
                {"DBInstanceIdentifier":"mydb","Engine":"postgres","MasterUsername":"admin",
                 "MasterUserPassword":"secret"}
                """, "us-west-2");

        verify(rdsService).createDbInstance("mydb", "postgres", null, "admin", "secret",
                null, "db.t3.micro", 20, false, null, null, null,
                null, false, false, null, Map.of(), "us-west-2");
    }

    @Test
    void provisionsDbClusterWithReaderEndpoint() {
        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(cluster.getEndpoint()).thenReturn(new DbEndpoint("mycluster.local", 5432));
        when(cluster.getReaderEndpoint()).thenReturn(new DbEndpoint("mycluster-ro.local", 5432));
        when(cluster.getDbClusterArn()).thenReturn("arn:aws:rds:us-east-1:000000000000:cluster:mycluster");
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        StackResource r = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql","EngineVersion":"16.3",
                 "MasterUsername":"admin","MasterUserPassword":"secret","DatabaseName":"appdb"}
                """);

        assertEquals("mycluster", r.getPhysicalId());
        assertEquals("mycluster.local", r.getAttributes().get("Endpoint.Address"));
        assertEquals("mycluster-ro.local", r.getAttributes().get("ReadEndpoint.Address"));
        assertEquals("5432", r.getAttributes().get("Endpoint.Port"));
        verify(rdsService).createDbCluster("mycluster", "aurora-postgresql", "16.3",
                "admin", "secret", "appdb", false, null, null, null, false, "us-east-1");
    }

    @Test
    void provisionsDbClusterInStackRegion() {
        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql"}
                """, "us-west-2");

        verify(rdsService).createDbCluster("mycluster", "aurora-postgresql", null,
                null, null, null, false, null, null, null, false, "us-west-2");
    }

    @Test
    void provisionsDbSubnetGroupWithResolvedSubnetIds() {
        DbSubnetGroup group = mock(DbSubnetGroup.class);
        when(group.getDbSubnetGroupName()).thenReturn("my-subnet-group");
        when(rdsService.createDbSubnetGroup(any(), any(), anyList(), any())).thenReturn(group);

        StackResource r = provision("Sg", "AWS::RDS::DBSubnetGroup", """
                {"DBSubnetGroupName":"my-subnet-group","DBSubnetGroupDescription":"db subnets",
                 "SubnetIds":["subnet-a","subnet-b"]}
                """);

        assertEquals("my-subnet-group", r.getPhysicalId());
        assertEquals("my-subnet-group", r.getAttributes().get("DBSubnetGroupName"));
        verify(rdsService).createDbSubnetGroup("my-subnet-group", "db subnets",
                List.of("subnet-a", "subnet-b"), "us-east-1");
    }

    @Test
    void provisionsDbSubnetGroupInStackRegion() {
        DbSubnetGroup group = mock(DbSubnetGroup.class);
        when(group.getDbSubnetGroupName()).thenReturn("my-subnet-group");
        when(rdsService.createDbSubnetGroup(any(), any(), anyList(), any())).thenReturn(group);

        provision("Sg", "AWS::RDS::DBSubnetGroup", """
                {"DBSubnetGroupName":"my-subnet-group","SubnetIds":["subnet-a"]}
                """, "us-west-2");

        verify(rdsService).createDbSubnetGroup("my-subnet-group", "Managed by CloudFormation",
                List.of("subnet-a"), "us-west-2");
    }

    @Test
    void provisionsDbParameterGroup() {
        DbParameterGroup group = mock(DbParameterGroup.class);
        when(group.getDbParameterGroupName()).thenReturn("my-pg");
        when(rdsService.createDbParameterGroup(any(), any(), any(), any())).thenReturn(group);

        StackResource r = provision("Pg", "AWS::RDS::DBParameterGroup", """
                {"DBParameterGroupName":"my-pg","Family":"postgres16","Description":"params"}
                """);

        assertEquals("my-pg", r.getPhysicalId());
        assertEquals("my-pg", r.getAttributes().get("DBParameterGroupName"));
        verify(rdsService).createDbParameterGroup(
                "my-pg", "postgres16", "params", "us-east-1");
    }

    @Test
    void provisionsDbProxyWithDefaultAuthSchemeEndpointAndArnAttributes() {
        DbProxy proxy = mock(DbProxy.class);
        when(proxy.getDbProxyName()).thenReturn("app-proxy");
        // RDS Proxy endpoint is a bare hostname (clients connect on the engine's default port).
        when(proxy.getEndpoint()).thenReturn("host.docker.internal");
        when(proxy.getDbProxyArn()).thenReturn("arn:aws:rds:us-east-1:000000000000:db-proxy:prx-abc");
        when(proxy.getVpcId()).thenReturn("vpc-default");
        when(rdsService.createDbProxy(any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                anyList(), anyList(), anyList(), anyInt(), anyBoolean(), anyMap(), any()))
                .thenReturn(proxy);

        StackResource r = provision("Proxy", "AWS::RDS::DBProxy", """
                {"DBProxyName":"app-proxy","EngineFamily":"POSTGRESQL","RequireTLS":true,
                 "DebugLogging":true,"IdleClientTimeout":120,"DefaultAuthScheme":"IAM_AUTH",
                 "EndpointNetworkType":"IPV4","TargetConnectionNetworkType":"IPV4",
                 "RoleArn":"arn:aws:iam::000000000000:role/proxy",
                 "VpcSubnetIds":["subnet-a","subnet-b"],
                 "Tags":[{"Key":"owner","Value":"platform"}]}
                """);

        assertEquals("CREATE_COMPLETE", r.getStatus());
        assertEquals("app-proxy", r.getPhysicalId());
        // GetAtt "Endpoint" is the (bare-host) proxy endpoint, passed through from the model.
        assertEquals("host.docker.internal", r.getAttributes().get("Endpoint"));
        assertEquals("arn:aws:rds:us-east-1:000000000000:db-proxy:prx-abc", r.getAttributes().get("DBProxyArn"));
        assertEquals("vpc-default", r.getAttributes().get("VpcId"));
        verify(rdsService).createDbProxy(eq("app-proxy"), eq("POSTGRESQL"), eq(true), eq(true),
                eq("IAM_AUTH"), eq("arn:aws:iam::000000000000:role/proxy"),
                eq(List.of("subnet-a", "subnet-b")), eq(List.of()), eq(List.of()),
                eq(120), eq(true), eq(Map.of("owner", "platform")), eq("us-east-1"));
    }

    @Test
    void rejectsExplicitlyBlankDbProxyDefaultAuthSchemeBeforeMutation() {
        StackResource resource = provision("Proxy", "AWS::RDS::DBProxy", """
                {"DBProxyName":"app-proxy","EngineFamily":"POSTGRESQL",
                 "DefaultAuthScheme":"   ",
                 "RoleArn":"arn:aws:iam::000000000000:role/proxy",
                 "VpcSubnetIds":["subnet-a","subnet-b"],
                 "Auth":[{"AuthScheme":"SECRETS",
                           "SecretArn":"arn:aws:secretsmanager:us-east-1:000000000000:secret:db-AbCdEf",
                           "IAMAuth":"DISABLED"}]}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "DefaultAuthScheme must be NONE or IAM_AUTH"));
        verify(rdsService, never()).createDbProxy(
                any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                anyList(), anyList(), anyList(), anyInt(), anyBoolean(), anyMap(), any());
    }

    @Test
    void preservesDbProxyAuthUserNameAndDerivesSqlServerEnabledIamAuth() {
        DbProxy proxy = mock(DbProxy.class);
        when(proxy.getDbProxyName()).thenReturn("sqlserver-proxy");
        when(rdsService.createDbProxy(
                any(), any(), anyBoolean(), anyBoolean(), any(), any(),
                anyList(), anyList(), anyList(), anyInt(), anyBoolean(), anyMap(), any()))
                .thenReturn(proxy);

        StackResource resource = provision("Proxy", "AWS::RDS::DBProxy", """
                {"DBProxyName":"sqlserver-proxy","EngineFamily":"SQLSERVER",
                 "RoleArn":"arn:aws:iam::000000000000:role/proxy",
                 "VpcSubnetIds":["subnet-a","subnet-b"],
                 "Auth":[{"AuthScheme":"SECRETS",
                           "SecretArn":"arn:aws:secretsmanager:us-east-1:000000000000:secret:db-AbCdEf",
                           "IAMAuth":"ENABLED","UserName":"database-user",
                           "ClientPasswordAuthType":"SQL_SERVER_AUTHENTICATION"}]}
                """);

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DbProxyAuth>> authCaptor = ArgumentCaptor.forClass(List.class);
        verify(rdsService).createDbProxy(
                eq("sqlserver-proxy"), eq("SQLSERVER"), eq(false), eq(true), eq("NONE"),
                eq("arn:aws:iam::000000000000:role/proxy"),
                eq(List.of("subnet-a", "subnet-b")), eq(List.of()), authCaptor.capture(),
                eq(1800), eq(false), eq(Map.of()), eq("us-east-1"));
        assertEquals("database-user", authCaptor.getValue().getFirst().getUserName());
        assertEquals("ENABLED", authCaptor.getValue().getFirst().getIamAuth());
    }

    @Test
    void rejectsUnsupportedDbProxyNetworkTypesBeforeMutation() {
        StackResource ipv6 = provision("Proxy", "AWS::RDS::DBProxy", """
                {"DBProxyName":"app-proxy","EngineFamily":"POSTGRESQL",
                 "DefaultAuthScheme":"IAM_AUTH","EndpointNetworkType":"IPV6",
                 "RoleArn":"arn:aws:iam::000000000000:role/proxy",
                 "VpcSubnetIds":["subnet-a","subnet-b"]}
                """);

        assertEquals("CREATE_FAILED", ipv6.getStatus());
        assertTrue(ipv6.getStatusReason().contains("IPv4 proxy networking only"));
        verify(rdsService, never()).createDbProxy(any(), any(), anyBoolean(), anyBoolean(),
                any(), any(), anyList(), anyList(), anyList(), anyInt(), anyBoolean(), anyMap(), any());
    }

    @Test
    void mutableDbProxyUpdatePreservesPhysicalIdentity() {
        String proxyArn = "arn:aws:rds:us-west-2:000000000000:db-proxy:prx-abc";
        DbProxy existing = mock(DbProxy.class);
        when(existing.getDbProxyName()).thenReturn("app-proxy");
        when(existing.getEngineFamily()).thenReturn("POSTGRESQL");
        when(existing.getVpcSubnetIds()).thenReturn(List.of("subnet-a", "subnet-b"));
        when(rdsService.getDbProxy("app-proxy", "us-west-2")).thenReturn(existing);

        DbProxy updated = mock(DbProxy.class);
        when(updated.getDbProxyName()).thenReturn("app-proxy");
        when(updated.getEndpoint()).thenReturn("updated.proxy.local");
        when(updated.getDbProxyArn()).thenReturn(proxyArn);
        when(rdsService.modifyDbProxy(eq("app-proxy"), eq("NONE"), anyList(), eq(true),
                eq(90), eq(true), eq("arn:aws:iam::000000000000:role/proxy"),
                eq(List.of("sg-updated")), eq(Map.of("owner", "platform")), eq("us-west-2")))
                .thenReturn(updated);

        StackResource resource = provisionExisting("Proxy", "AWS::RDS::DBProxy", """
                {"DBProxyName":"app-proxy","EngineFamily":"POSTGRESQL","RequireTLS":true,
                 "DebugLogging":true,"IdleClientTimeout":90,"DefaultAuthScheme":"NONE",
                 "RoleArn":"arn:aws:iam::000000000000:role/proxy",
                 "VpcSubnetIds":["subnet-a","subnet-b"],
                 "VpcSecurityGroupIds":["sg-updated"],
                 "Tags":[{"Key":"owner","Value":"platform"}],
                 "Auth":[{"AuthScheme":"SECRETS","SecretArn":"arn:aws:secretsmanager:us-west-2:000000000000:secret:db-AbCdEf","IAMAuth":"DISABLED"}]}
                """, "us-west-2", "app-proxy",
                Map.of("Endpoint", "old.proxy.local", "DBProxyArn", proxyArn));

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals("app-proxy", resource.getPhysicalId());
        assertEquals("updated.proxy.local", resource.getAttributes().get("Endpoint"));
        assertEquals(proxyArn, resource.getAttributes().get("DBProxyArn"));
        verify(rdsService).getDbProxy("app-proxy", "us-west-2");
        verify(rdsService).modifyDbProxy(eq("app-proxy"), eq("NONE"), anyList(), eq(true),
                eq(90), eq(true), eq("arn:aws:iam::000000000000:role/proxy"),
                eq(List.of("sg-updated")), eq(Map.of("owner", "platform")), eq("us-west-2"));
        verify(rdsService, never()).createDbProxy(any(), any(), anyBoolean(), anyBoolean(),
                any(), any(), anyList(), anyList(), anyList(), anyInt(), anyBoolean(), anyMap(), any());
    }

    @Test
    void replacementOnlyDbProxyChangeFailsBeforeMutation() {
        DbProxy existing = mock(DbProxy.class);
        when(existing.getDbProxyName()).thenReturn("app-proxy");
        when(existing.getEngineFamily()).thenReturn("POSTGRESQL");
        when(existing.getVpcSubnetIds()).thenReturn(List.of("subnet-a", "subnet-b"));
        when(rdsService.getDbProxy("app-proxy", "us-east-1")).thenReturn(existing);

        StackResource resource = provisionExisting("Proxy", "AWS::RDS::DBProxy", """
                {"DBProxyName":"app-proxy","EngineFamily":"MYSQL","DefaultAuthScheme":"IAM_AUTH",
                 "RoleArn":"arn:aws:iam::000000000000:role/proxy",
                 "VpcSubnetIds":["subnet-a","subnet-b"]}
                """, "us-east-1", "app-proxy", Map.of());

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("requires CloudFormation replacement"));
        verify(rdsService, never()).modifyDbProxy(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(rdsService, never()).createDbProxy(any(), any(), anyBoolean(), anyBoolean(),
                any(), any(), anyList(), anyList(), anyList(), anyInt(), anyBoolean(), anyMap(), any());
    }

    @Test
    void reconcilesCompleteTargetGroupPoolConfigurationInStackRegion() {
        String targetGroupArn =
                "arn:aws:rds:us-west-2:000000000000:target-group:prx-tg-abc";
        DbProxyTargetGroup existing = mock(DbProxyTargetGroup.class);
        when(existing.getDbProxyName()).thenReturn("app-proxy");
        when(existing.getTargetGroupName()).thenReturn("default");
        when(rdsService.getDbProxyTargetGroupByArn(targetGroupArn, "us-west-2"))
                .thenReturn(existing);

        DbProxy proxy = mock(DbProxy.class);
        when(proxy.getEngineFamily()).thenReturn("MYSQL");
        when(rdsService.getDbProxy("app-proxy", "us-west-2")).thenReturn(proxy);

        DbProxyTargetGroup reconciled = mock(DbProxyTargetGroup.class);
        when(reconciled.getDbProxyName()).thenReturn("app-proxy");
        when(reconciled.getTargetGroupArn()).thenReturn(targetGroupArn);
        when(rdsService.reconcileDbProxyTargetGroup("app-proxy", "default",
                List.of("mycluster"), List.of(), 90, 40, 17,
                "SET sql_mode='STRICT_ALL_TABLES'", List.of("EXCLUDE_VARIABLE_SETS"),
                "us-west-2")).thenReturn(reconciled);

        StackResource resource = provisionExisting("Tg", "AWS::RDS::DBProxyTargetGroup", """
                {"DBProxyName":"app-proxy","TargetGroupName":"default",
                 "DBClusterIdentifiers":["mycluster"],
                 "ConnectionPoolConfigurationInfo":{
                   "MaxConnectionsPercent":90,
                   "MaxIdleConnectionsPercent":40,
                   "ConnectionBorrowTimeout":17,
                   "InitQuery":"SET sql_mode='STRICT_ALL_TABLES'",
                   "SessionPinningFilters":["EXCLUDE_VARIABLE_SETS"]}}
                """, "us-west-2", targetGroupArn,
                Map.of("TargetGroupArn", targetGroupArn, "DBProxyName", "app-proxy"));

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals(targetGroupArn, resource.getPhysicalId());
        assertEquals(targetGroupArn, resource.getAttributes().get("TargetGroupArn"));
        assertEquals("app-proxy", resource.getAttributes().get("DBProxyName"));
        verify(rdsService).getDbProxyTargetGroupByArn(targetGroupArn, "us-west-2");
        verify(rdsService).reconcileDbProxyTargetGroup("app-proxy", "default",
                List.of("mycluster"), List.of(), 90, 40, 17,
                "SET sql_mode='STRICT_ALL_TABLES'", List.of("EXCLUDE_VARIABLE_SETS"),
                "us-west-2");
    }

    @Test
    void deleteDelegatesToRdsServiceForEachRdsType() {
        // Stack deletion tears down RDS resources via the physical id set at provision time.
        provisioner.delete("AWS::RDS::DBInstance", "mydb", "us-east-1");
        verify(rdsService).deleteDbInstance("mydb", "us-east-1");

        provisioner.delete("AWS::RDS::DBCluster", "mycluster", "us-east-1");
        verify(rdsService).deleteDbCluster("mycluster", "us-east-1");

        provisioner.delete("AWS::RDS::DBSubnetGroup", "my-subnet-group", "us-east-1");
        verify(rdsService).deleteDbSubnetGroup("my-subnet-group", "us-east-1");

        provisioner.delete("AWS::RDS::DBParameterGroup", "my-pg", "us-east-1");
        verify(rdsService).deleteDbParameterGroup("my-pg", "us-east-1");

        provisioner.delete("AWS::RDS::DBClusterParameterGroup", "my-cpg", "us-east-1");
        verify(rdsService).deleteDbClusterParameterGroup("my-cpg", "us-east-1");

        provisioner.delete("AWS::RDS::DBProxy", "app-proxy", "us-east-1");
        verify(rdsService).deleteDbProxy("app-proxy", "us-east-1");

        provisioner.delete("AWS::RDS::DBProxyTargetGroup",
                "arn:aws:rds:us-east-1:000000000000:target-group:prx-tg-abc", "us-east-1");
        verify(rdsService).clearDbProxyTargetGroupByArn(
                "arn:aws:rds:us-east-1:000000000000:target-group:prx-tg-abc", "us-east-1");
    }

    @Test
    void targetGroupDeleteIsIdempotentWhenProxyDeletionAlreadyRemovedIt() {
        String targetGroupArn =
                "arn:aws:rds:us-east-1:000000000000:target-group:prx-tg-already-gone";
        org.mockito.Mockito.doThrow(new AwsException("DBProxyTargetGroupNotFoundFault",
                "target group not found", 404))
                .when(rdsService).clearDbProxyTargetGroupByArn(targetGroupArn, "us-east-1");

        assertDoesNotThrow(() -> provisioner.delete(
                "AWS::RDS::DBProxyTargetGroup", targetGroupArn, "us-east-1"));
        verify(rdsService).clearDbProxyTargetGroupByArn(targetGroupArn, "us-east-1");
    }
}
