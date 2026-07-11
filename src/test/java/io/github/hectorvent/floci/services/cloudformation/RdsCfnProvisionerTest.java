package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbProxy;
import io.github.hectorvent.floci.services.rds.model.DbProxyTargetGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
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
        when(rdsService.createDbParameterGroup(any(), any(), any())).thenReturn(group);

        StackResource r = provision("Pg", "AWS::RDS::DBParameterGroup", """
                {"DBParameterGroupName":"my-pg","Family":"postgres16","Description":"params"}
                """);

        assertEquals("my-pg", r.getPhysicalId());
        assertEquals("my-pg", r.getAttributes().get("DBParameterGroupName"));
        verify(rdsService).createDbParameterGroup("my-pg", "postgres16", "params");
    }

    @Test
    void provisionsDbProxyWithEndpointAndArnAttributes() {
        DbProxy proxy = mock(DbProxy.class);
        when(proxy.getDbProxyName()).thenReturn("app-proxy");
        // RDS Proxy endpoint is a bare hostname (clients connect on the engine's default port).
        when(proxy.getEndpoint()).thenReturn("host.docker.internal");
        when(proxy.getDbProxyArn()).thenReturn("arn:aws:rds:us-east-1:000000000000:db-proxy:prx-abc");
        when(rdsService.createDbProxy(any(), any(), anyBoolean(), anyBoolean(), any(),
                anyList(), anyList(), anyList(), any())).thenReturn(proxy);

        StackResource r = provision("Proxy", "AWS::RDS::DBProxy", """
                {"DBProxyName":"app-proxy","EngineFamily":"POSTGRESQL","RequireTLS":true,
                 "RoleArn":"arn:aws:iam::000000000000:role/proxy",
                 "VpcSubnetIds":["subnet-a","subnet-b"],
                 "Auth":[{"AuthScheme":"SECRETS","SecretArn":"arn:aws:secretsmanager:us-east-1:000000000000:secret:db-AbCdEf","IAMAuth":"DISABLED"}]}
                """);

        assertEquals("CREATE_COMPLETE", r.getStatus());
        assertEquals("app-proxy", r.getPhysicalId());
        // GetAtt "Endpoint" is the (bare-host) proxy endpoint, passed through from the model.
        assertEquals("host.docker.internal", r.getAttributes().get("Endpoint"));
        assertEquals("arn:aws:rds:us-east-1:000000000000:db-proxy:prx-abc", r.getAttributes().get("DBProxyArn"));
        verify(rdsService).createDbProxy(eq("app-proxy"), eq("POSTGRESQL"), eq(true), eq(false),
                eq("arn:aws:iam::000000000000:role/proxy"), eq(List.of("subnet-a", "subnet-b")),
                anyList(), anyList(), any());
    }

    @Test
    void provisionsDbProxyTargetGroupRegistersClusterTarget() {
        DbProxyTargetGroup tg = mock(DbProxyTargetGroup.class);
        when(tg.getDbProxyName()).thenReturn("app-proxy");
        when(tg.getTargetGroupArn()).thenReturn("arn:aws:rds:us-east-1:000000000000:target-group:app-proxy/default");
        when(rdsService.registerDbProxyTargets(any(), any(), anyList(), anyList(), anyInt(), anyInt()))
                .thenReturn(tg);

        StackResource r = provision("Tg", "AWS::RDS::DBProxyTargetGroup", """
                {"DBProxyName":"app-proxy","TargetGroupName":"default",
                 "DBClusterIdentifiers":["mycluster"],
                 "ConnectionPoolConfigurationInfo":{"MaxConnectionsPercent":90,"MaxIdleConnectionsPercent":40}}
                """);

        assertEquals("CREATE_COMPLETE", r.getStatus());
        assertEquals("app-proxy", r.getPhysicalId());
        verify(rdsService).registerDbProxyTargets("app-proxy", "default",
                List.of("mycluster"), List.of(), 90, 40);
    }

    @Test
    void deleteDelegatesToRdsServiceForEachRdsType() {
        // Stack deletion tears down RDS resources via the physical id set at provision time.
        provisioner.delete("AWS::RDS::DBInstance", "mydb", "us-east-1");
        verify(rdsService).deleteDbInstance("mydb");

        provisioner.delete("AWS::RDS::DBCluster", "mycluster", "us-east-1");
        verify(rdsService).deleteDbCluster("mycluster");

        provisioner.delete("AWS::RDS::DBSubnetGroup", "my-subnet-group", "us-east-1");
        verify(rdsService).deleteDbSubnetGroup("my-subnet-group");

        provisioner.delete("AWS::RDS::DBParameterGroup", "my-pg", "us-east-1");
        verify(rdsService).deleteDbParameterGroup("my-pg");

        provisioner.delete("AWS::RDS::DBClusterParameterGroup", "my-cpg", "us-east-1");
        verify(rdsService).deleteDbClusterParameterGroup("my-cpg");

        provisioner.delete("AWS::RDS::DBProxy", "app-proxy", "us-east-1");
        verify(rdsService).deleteDbProxy("app-proxy");
    }
}
