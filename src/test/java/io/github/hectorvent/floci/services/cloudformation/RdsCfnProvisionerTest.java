package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbProxy;
import io.github.hectorvent.floci.services.rds.model.DbProxyAuth;
import io.github.hectorvent.floci.services.rds.model.DbProxyTargetGroup;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies CloudFormation provisions RDS resources by delegating to {@link RdsService} (mocked, so
 * no containers start) and maps CFN properties to the right create-method arguments, with
 * Ref/GetAtt set from the returned resource.
 */
class RdsCfnProvisionerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private RdsService rdsService;
    private SecretsManagerService secretsManagerService;
    private SsmService ssmService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        rdsService = mock(RdsService.class);
        secretsManagerService = mock(SecretsManagerService.class);
        ssmService = mock(SsmService.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, null, null, ssmService, null, secretsManagerService, null,
                null, null, null, null, null, null,
                mapper,
                null, null, null, null, null, null, null,
                rdsService, null, null, null, null, null, null,
                null, null,
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

    /**
     * Simulates an UpdateStack re-invocation: {@code provision()} is called again for the same
     * resource with the physical id it was already assigned, exactly as CloudFormationService does
     * on every update regardless of whether the resource's properties actually changed.
     */
    private StackResource provisionUpdate(String logicalId, String type, String json, String existingPhysicalId) {
        return provisioner.provision(logicalId, type, props(json), engine(),
                "us-east-1", "000000000000", "my-stack", existingPhysicalId);
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
    void resolvesSecretsManagerDynamicReferencesInDbClusterCredentials() {
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn(
                "{\"username\":\"resolved-user\",\"password\":\"resolved-secret\"}");
        when(secretsManagerService.getSecretValue(any(), any(), any(), any())).thenReturn(version);

        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(cluster.getEndpoint()).thenReturn(new DbEndpoint("mycluster.local", 5432));
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql","EngineVersion":"16.3",
                 "MasterUsername":"{{resolve:secretsmanager:my-secret:SecretString:username}}",
                 "MasterUserPassword":"{{resolve:secretsmanager:my-secret:SecretString:password}}",
                 "DatabaseName":"appdb"}
                """, "us-west-2");

        verify(rdsService).createDbCluster(eq("mycluster"), eq("aurora-postgresql"), eq("16.3"),
                eq("resolved-user"), eq("resolved-secret"), eq("appdb"), anyBoolean(), any(),
                any(), any(), anyBoolean(), any());
        verify(secretsManagerService, times(2))
                .getSecretValue("my-secret", null, null, "us-west-2");
    }

    @Test
    void resolvesSecretsManagerDynamicReferencesInDbInstanceCredentials() {
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn(
                "{\"username\":\"resolved-user\",\"password\":\"resolved-secret\"}");
        when(secretsManagerService.getSecretValue(any(), any(), any(), any())).thenReturn(version);

        DbInstance instance = mock(DbInstance.class);
        when(instance.getDbInstanceIdentifier()).thenReturn("mydb");
        when(rdsService.createDbInstance(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), anyMap(), nullable(String.class))).thenReturn(instance);

        provision("Db", "AWS::RDS::DBInstance", """
                {"DBInstanceIdentifier":"mydb","Engine":"postgres",
                 "MasterUsername":"{{resolve:secretsmanager:my-secret:SecretString:username}}",
                 "MasterUserPassword":"{{resolve:secretsmanager:my-secret:SecretString:password}}"}
                """, "us-west-2");

        verify(rdsService).createDbInstance(
                eq("mydb"), eq("postgres"), any(), eq("resolved-user"), eq("resolved-secret"),
                any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), anyMap(), eq("us-west-2"));
        verify(secretsManagerService, times(2))
                .getSecretValue("my-secret", null, null, "us-west-2");
    }

    @Test
    void resolvesSecretsManagerWholeSecretShorthand() {
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn("resolved-secret");
        when(secretsManagerService.getSecretValue(any(), any(), any(), any())).thenReturn(version);

        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:secretsmanager:my-secret::::}}"}
                """);

        verify(rdsService).createDbCluster(
                eq("mycluster"), eq("aurora-postgresql"), any(), eq("admin"),
                eq("resolved-secret"), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), eq("us-east-1"));
        verify(secretsManagerService)
                .getSecretValue("my-secret", null, null, "us-east-1");
    }

    @Test
    void resolvesSecretsManagerArnWholeSecretShorthandInArnRegion() {
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn("resolved-secret");
        when(secretsManagerService.getSecretValue(any(), any(), any(), any())).thenReturn(version);

        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        String secretArn =
                "arn:aws:secretsmanager:us-west-2:000000000000:secret:MySecret";
        provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:secretsmanager:%s::::}}"}
                """.formatted(secretArn));

        verify(rdsService).createDbCluster(
                eq("mycluster"), eq("aurora-postgresql"), any(), eq("admin"),
                eq("resolved-secret"), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), eq("us-east-1"));
        verify(secretsManagerService)
                .getSecretValue(secretArn, null, null, "us-west-2");
    }

    @Test
    void resolvesWholeSecretArnWhoseNameIsSecretString() {
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn("resolved-secret");
        when(secretsManagerService.getSecretValue(any(), any(), any(), any())).thenReturn(version);

        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        String secretArn =
                "arn:aws:secretsmanager:us-west-2:000000000000:secret:SecretString";
        provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:secretsmanager:%s}}"}
                """.formatted(secretArn));

        verify(rdsService).createDbCluster(
                eq("mycluster"), eq("aurora-postgresql"), any(), eq("admin"),
                eq("resolved-secret"), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), eq("us-east-1"));
        verify(secretsManagerService)
                .getSecretValue(secretArn, null, null, "us-west-2");
    }

    @Test
    void resolvesJsonKeyFromArnWhoseNameStartsWithSecretString() {
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn("{\"password\":\"resolved-secret\"}");
        when(secretsManagerService.getSecretValue(any(), any(), any(), any())).thenReturn(version);

        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        String secretArn =
                "arn:aws:secretsmanager:us-west-2:000000000000:secret:SecretStringName";
        provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:secretsmanager:%s:SecretString:password}}"}
                """.formatted(secretArn));

        verify(rdsService).createDbCluster(
                eq("mycluster"), eq("aurora-postgresql"), any(), eq("admin"),
                eq("resolved-secret"), any(), anyBoolean(), any(), any(), any(),
                anyBoolean(), eq("us-east-1"));
        verify(secretsManagerService)
                .getSecretValue(secretArn, null, null, "us-west-2");
    }

    @Test
    void binaryOnlySecretFailsBeforeRdsProvisioning() {
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn(null);
        when(secretsManagerService.getSecretValue(any(), any(), any(), any())).thenReturn(version);

        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:secretsmanager:my-secret}}"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "secret my-secret has no SecretString value to resolve"));
        verifyNoInteractions(rdsService);
    }

    @Test
    void missingSecretJsonKeyFailsBeforeRdsProvisioning() {
        SecretVersion version = mock(SecretVersion.class);
        when(version.getSecretString()).thenReturn("{\"username\":\"admin\"}");
        when(secretsManagerService.getSecretValue(any(), any(), any(), any())).thenReturn(version);

        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:secretsmanager:my-secret:SecretString:password}}"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "JSON key 'password' not found in secret my-secret"));
        verifyNoInteractions(rdsService);
    }

    @Test
    void extraSecretsManagerSuffixFailsWithoutReadingSecretOrCreatingDatabase() {
        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:secretsmanager:my-secret:SecretString:password:::extra}}"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "Invalid Secrets Manager dynamic reference"));
        verifyNoInteractions(secretsManagerService, rdsService);
    }

    @Test
    void unsupportedSecretsManagerSecretStringTypeFailsBeforeSecretLookup() {
        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:secretsmanager:my-secret:SecretBinary:password}}"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "Invalid Secrets Manager dynamic reference"));
        verifyNoInteractions(secretsManagerService, rdsService);
    }

    @Test
    void resolvesSsmDynamicReferenceInMasterPassword() {
        Parameter param = mock(Parameter.class);
        when(param.getValue()).thenReturn("resolved-ssm");
        when(param.getType()).thenReturn("String");
        when(ssmService.getParameter(eq("/db/password"), any())).thenReturn(param);

        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(cluster.getEndpoint()).thenReturn(new DbEndpoint("mycluster.local", 5432));
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql","EngineVersion":"16.3",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm:/db/password}}",
                 "DatabaseName":"appdb"}
                """);

        // The {{resolve:ssm:<name>}} dynamic reference is substituted with the parameter value.
        verify(rdsService).createDbCluster(eq("mycluster"), eq("aurora-postgresql"), eq("16.3"),
                eq("admin"), eq("resolved-ssm"), eq("appdb"), anyBoolean(), any(),
                any(), any(), anyBoolean(), any());
    }

    @Test
    void resolvesPlaintextSsmDynamicReferenceInMasterUsername() {
        Parameter param = new Parameter("/db/username", "resolved-user", "String");
        when(ssmService.getParameter("/db/username", "us-east-1")).thenReturn(param);

        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"{{resolve:ssm:/db/username}}",
                 "MasterUserPassword":"secret"}
                """);

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        verify(rdsService).createDbCluster(eq("mycluster"), eq("aurora-postgresql"), any(),
                eq("resolved-user"), eq("secret"), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), eq("us-east-1"));
    }

    @Test
    void resolvesStringListSsmDynamicReference() {
        Parameter param = new Parameter("/db/password-list", "first,second", "StringList");
        when(ssmService.getParameter("/db/password-list", "us-east-1")).thenReturn(param);

        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm:/db/password-list}}"}
                """);

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        verify(rdsService).createDbCluster(eq("mycluster"), eq("aurora-postgresql"), any(),
                eq("admin"), eq("first,second"), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), eq("us-east-1"));
    }

    @Test
    void resolvesSecureSsmDynamicReferenceInMasterPassword() {
        Parameter param = new Parameter("/db/password", "resolved-secure", "SecureString");
        when(ssmService.getParameter("/db/password", "us-east-1")).thenReturn(param);

        DbInstance instance = mock(DbInstance.class);
        when(instance.getDbInstanceIdentifier()).thenReturn("mydb");
        when(rdsService.createDbInstance(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), anyMap(), nullable(String.class))).thenReturn(instance);

        StackResource resource = provision("Db", "AWS::RDS::DBInstance", """
                {"DBInstanceIdentifier":"mydb","Engine":"postgres",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm-secure:/db/password}}"}
                """);

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        verify(rdsService).createDbInstance(
                eq("mydb"), eq("postgres"), any(), eq("admin"), eq("resolved-secure"),
                any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), anyMap(), eq("us-east-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWS::RDS::DBInstance", "AWS::RDS::DBCluster"})
    void rejectsSecureSsmDynamicReferenceInMasterUsername(String resourceType) {
        StackResource resource = provision("Database", resourceType, """
                {"Engine":"postgres",
                 "MasterUsername":"{{resolve:ssm-secure:/db/username}}",
                 "MasterUserPassword":"secret"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "ssm-secure dynamic references are supported only for MasterUserPassword"));
        verifyNoInteractions(ssmService, rdsService);
    }

    @Test
    void rejectsSecureStringParameterForPlaintextSsmDynamicReference() {
        Parameter param = new Parameter("/db/password", "resolved-secure", "SecureString");
        when(ssmService.getParameter("/db/password", "us-east-1")).thenReturn(param);

        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm:/db/password}}"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "must be type String or StringList for an ssm dynamic reference"));
        verifyNoInteractions(rdsService);
    }

    @Test
    void rejectsPlaintextParameterForSecureSsmDynamicReference() {
        Parameter param = new Parameter("/db/password", "resolved-plain", "String");
        when(ssmService.getParameter("/db/password", "us-east-1")).thenReturn(param);

        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm-secure:/db/password}}"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "must be type SecureString for an ssm-secure dynamic reference"));
        verifyNoInteractions(rdsService);
    }

    @Test
    void resolvesPinnedSsmVersionInStackRegion() {
        ParameterHistory versionOne = new ParameterHistory();
        versionOne.setVersion(1);
        versionOne.setValue("first-password");
        versionOne.setType("String");
        ParameterHistory versionTwo = new ParameterHistory();
        versionTwo.setVersion(2);
        versionTwo.setValue("latest-password");
        versionTwo.setType("String");
        when(ssmService.getParameterHistory("/db/password", "us-west-2"))
                .thenReturn(List.of(versionOne, versionTwo));

        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm:/db/password:1}}"}
                """, "us-west-2");

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        verify(rdsService).createDbCluster(eq("mycluster"), eq("aurora-postgresql"), any(),
                eq("admin"), eq("first-password"), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), eq("us-west-2"));
        verify(ssmService, never()).getParameter(any(), any());
    }

    @Test
    void resolvesPinnedSecureSsmVersionInStackRegion() {
        ParameterHistory versionOne = new ParameterHistory();
        versionOne.setVersion(1);
        versionOne.setValue("first-password");
        versionOne.setType("SecureString");
        when(ssmService.getParameterHistory("/db/password", "us-west-2"))
                .thenReturn(List.of(versionOne));

        DbCluster cluster = mock(DbCluster.class);
        when(cluster.getDbClusterIdentifier()).thenReturn("mycluster");
        when(rdsService.createDbCluster(any(), any(), any(), any(), any(), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), any()))
                .thenReturn(cluster);

        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm-secure:/db/password:1}}"}
                """, "us-west-2");

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        verify(rdsService).createDbCluster(eq("mycluster"), eq("aurora-postgresql"), any(),
                eq("admin"), eq("first-password"), any(), anyBoolean(), any(),
                any(), any(), anyBoolean(), eq("us-west-2"));
        verify(ssmService, never()).getParameter(any(), any());
    }

    @Test
    void rejectsMismatchedPinnedSsmParameterType() {
        ParameterHistory versionOne = new ParameterHistory();
        versionOne.setVersion(1);
        versionOne.setValue("first-password");
        versionOne.setType("SecureString");
        when(ssmService.getParameterHistory("/db/password", "us-east-1"))
                .thenReturn(List.of(versionOne));

        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm:/db/password:1}}"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "must be type String or StringList for an ssm dynamic reference"));
        verifyNoInteractions(rdsService);
    }

    @Test
    void missingPinnedSsmVersionFailsResourceWithoutUsingLatestValue() {
        ParameterHistory versionOne = new ParameterHistory();
        versionOne.setVersion(1);
        versionOne.setValue("first-password");
        versionOne.setType("String");
        when(ssmService.getParameterHistory("/db/password", "us-east-1"))
                .thenReturn(List.of(versionOne));

        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm:/db/password:99}}"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("Parameter version 99 not found"));
        verify(ssmService, never()).getParameter(any(), any());
        verifyNoInteractions(rdsService);
    }

    @ParameterizedTest(name = "malformed SSM version suffix [{0}]")
    @ValueSource(strings = {
            "", "+1", " ", " 1", "1 ", "-1", "not-a-version", "1:2"
    })
    void malformedPinnedSsmVersionFailsWithValidationError(String version) {
        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm:/db/password:%s}}"}
                """.formatted(version));

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "Invalid SSM dynamic reference"));
        verifyNoInteractions(ssmService, rdsService);
    }

    @ParameterizedTest(name = "invalid numeric SSM version suffix [{0}]")
    @ValueSource(strings = {"0", "9223372036854775808"})
    void invalidNumericPinnedSsmVersionFailsWithValidationError(String version) {
        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm:/db/password:%s}}"}
                """.formatted(version));

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "SSM parameter version must be a positive integer"));
        verifyNoInteractions(ssmService, rdsService);
    }

    @ParameterizedTest(name = "invalid SSM parameter name [{0}]")
    @ValueSource(strings = {"", "invalid name", "/db/password+extra", "/db/password?extra", "/db/password:label"})
    void invalidSsmParameterNameFailsBeforeParameterLookup(String parameterName) {
        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm:%s}}"}
                """.formatted(parameterName));

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "Invalid SSM dynamic reference"));
        verifyNoInteractions(ssmService, rdsService);
    }

    @Test
    void unclosedSsmDynamicReferenceFailsBeforeParameterLookup() {
        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:ssm:/db/password"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "Invalid SSM dynamic reference"));
        verifyNoInteractions(ssmService, rdsService);
    }

    @Test
    void unclosedSecretsManagerDynamicReferenceFailsBeforeSecretLookup() {
        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:secretsmanager:my-secret"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains(
                "Invalid Secrets Manager dynamic reference"));
        verifyNoInteractions(secretsManagerService, rdsService);
    }

    @Test
    void secretsManagerStageAndVersionIdTogetherFailResource() {
        StackResource resource = provision("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin",
                 "MasterUserPassword":"{{resolve:secretsmanager:my-secret:SecretString:password:AWSCURRENT:version-id}}"}
                """);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("cannot both be specified"));
        verifyNoInteractions(secretsManagerService, rdsService);
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
    void updateStackReconcilesExistingDbSubnetGroupInsteadOfRecreating() {
        // Regression for lex00/floci#16: CloudFormationService re-invokes provision() for every
        // resource on every UpdateStack regardless of whether its properties changed, so a fixed-name
        // DBSubnetGroup already on file used to hit createDbSubnetGroup again and throw
        // DBSubnetGroupAlreadyExists, rolling back an otherwise no-op update.
        DbSubnetGroup existing = mock(DbSubnetGroup.class);
        when(rdsService.getDbSubnetGroup("my-subnet-group", "us-east-1")).thenReturn(existing);
        DbSubnetGroup reconciled = mock(DbSubnetGroup.class);
        when(reconciled.getDbSubnetGroupName()).thenReturn("my-subnet-group");
        when(rdsService.modifyDbSubnetGroup(eq("my-subnet-group"), anyList(), eq("us-east-1")))
                .thenReturn(reconciled);

        StackResource r = provisionUpdate("Sg", "AWS::RDS::DBSubnetGroup", """
                {"DBSubnetGroupName":"my-subnet-group","DBSubnetGroupDescription":"db subnets",
                 "SubnetIds":["subnet-a","subnet-b"]}
                """, "my-subnet-group");

        assertEquals("CREATE_COMPLETE", r.getStatus());
        assertEquals("my-subnet-group", r.getPhysicalId());
        verify(rdsService, never()).createDbSubnetGroup(any(), any(), anyList(), any());
        verify(rdsService).modifyDbSubnetGroup("my-subnet-group", List.of("subnet-a", "subnet-b"), "us-east-1");
    }

    @Test
    void updateStackNoOpsExistingDbParameterGroupInsteadOfRecreating() {
        // DBParameterGroupName/Family/Description are all immutable on real AWS, so an unchanged
        // re-apply must no-op rather than hit createDbParameterGroup's DBParameterGroupAlreadyExists.
        DbParameterGroup existing = mock(DbParameterGroup.class);
        when(existing.getDbParameterGroupName()).thenReturn("my-pg");
        when(rdsService.getDbParameterGroup("my-pg", "us-east-1")).thenReturn(existing);

        StackResource r = provisionUpdate("Pg", "AWS::RDS::DBParameterGroup", """
                {"DBParameterGroupName":"my-pg","Family":"postgres16","Description":"params"}
                """, "my-pg");

        assertEquals("my-pg", r.getPhysicalId());
        assertEquals("my-pg", r.getAttributes().get("DBParameterGroupName"));
        verify(rdsService, never()).createDbParameterGroup(any(), any(), any(), any());
    }

    @Test
    void updateStackNoOpsExistingDbClusterParameterGroupInsteadOfRecreating() {
        DbClusterParameterGroup existing = mock(DbClusterParameterGroup.class);
        when(existing.getDbClusterParameterGroupName()).thenReturn("my-cpg");
        when(rdsService.getDbClusterParameterGroup("my-cpg", "us-east-1")).thenReturn(existing);

        StackResource r = provisionUpdate("Cpg", "AWS::RDS::DBClusterParameterGroup", """
                {"DBClusterParameterGroupName":"my-cpg","Family":"aurora-postgresql16","Description":"params"}
                """, "my-cpg");

        assertEquals("my-cpg", r.getPhysicalId());
        assertEquals("my-cpg", r.getAttributes().get("DBClusterParameterGroupName"));
        verify(rdsService, never()).createDbClusterParameterGroup(any(), any(), any(), any());
    }

    @Test
    void updateStackReconcilesExistingDbInstanceInsteadOfRecreating() {
        DbInstance existing = mock(DbInstance.class);
        when(rdsService.getDbInstance("mydb")).thenReturn(existing);
        DbInstance reconciled = mock(DbInstance.class);
        when(reconciled.getDbInstanceIdentifier()).thenReturn("mydb");
        when(rdsService.modifyDbInstance(eq("mydb"), any(), anyBoolean(), any())).thenReturn(reconciled);

        StackResource r = provisionUpdate("Db", "AWS::RDS::DBInstance", """
                {"DBInstanceIdentifier":"mydb","Engine":"postgres","MasterUsername":"admin",
                 "MasterUserPassword":"secret"}
                """, "mydb");

        assertEquals("mydb", r.getPhysicalId());
        verify(rdsService, never()).createDbInstance(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyBoolean(),
                any(), anyMap(), nullable(String.class));
        verify(rdsService).modifyDbInstance("mydb", "secret", false, null);
    }

    @Test
    void updateStackReconcilesExistingDbClusterInsteadOfRecreating() {
        DbCluster existing = mock(DbCluster.class);
        when(rdsService.getDbCluster("mycluster")).thenReturn(existing);
        DbCluster reconciled = mock(DbCluster.class);
        when(reconciled.getDbClusterIdentifier()).thenReturn("mycluster");
        when(rdsService.modifyDbCluster(eq("mycluster"), any(), anyBoolean())).thenReturn(reconciled);

        StackResource r = provisionUpdate("Cluster", "AWS::RDS::DBCluster", """
                {"DBClusterIdentifier":"mycluster","Engine":"aurora-postgresql",
                 "MasterUsername":"admin","MasterUserPassword":"secret"}
                """, "mycluster");

        assertEquals("mycluster", r.getPhysicalId());
        verify(rdsService, never()).createDbCluster(any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), any(), anyBoolean(), any());
        verify(rdsService).modifyDbCluster("mycluster", "secret", false);
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
