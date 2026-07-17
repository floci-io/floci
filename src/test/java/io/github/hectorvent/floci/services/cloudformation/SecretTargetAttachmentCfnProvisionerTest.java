package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbEndpoint;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.Secret;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecretTargetAttachmentCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String SECRET_ID = "database-secret";
    private static final String SECRET_ARN =
            "arn:aws:secretsmanager:us-east-1:000000000000:secret:database-secret-a1b2c3";

    private final ObjectMapper mapper = new ObjectMapper();
    private SecretsManagerService secretsManagerService;
    private RdsService rdsService;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        secretsManagerService = mock(SecretsManagerService.class);
        rdsService = mock(RdsService.class);
        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, null, null, null, null, secretsManagerService, null,
                null, null, null, null, null, null,
                mapper,
                null, null, null, null, null, null, null,
                rdsService, null, null, null, null, null, null,
                new CloudFormationResourceRegistry(List.of()));
    }

    @Test
    void dbInstanceAttachmentAddsAndThenRemovesOnlyConnectionFields() throws Exception {
        stubSecretValues(
                "{\"username\":\"admin\",\"password\":\"secret\",\"custom\":\"keep\","
                        + "\"dbClusterIdentifier\":\"old-cluster\"}",
                "{\"username\":\"admin\",\"password\":\"secret\",\"custom\":\"keep\","
                        + "\"engine\":\"postgres\",\"host\":\"db.local\",\"port\":5432,"
                        + "\"dbname\":\"app\",\"dbInstanceIdentifier\":\"database\"}");
        when(rdsService.getDbInstance("database")).thenReturn(dbInstance());

        StackResource resource = provision(instanceProperties());
        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals(SECRET_ARN, resource.getPhysicalId());
        assertFalse(resource.getAttributes().containsKey("Arn"));

        provisioner.delete(resource, REGION);

        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(secretsManagerService, org.mockito.Mockito.times(2)).putSecretValue(
                eq(SECRET_ARN), values.capture(), isNull(), isNull(), eq(REGION), isNull());

        JsonNode attached = mapper.readTree(values.getAllValues().get(0));
        assertEquals("admin", attached.path("username").asText());
        assertEquals("secret", attached.path("password").asText());
        assertEquals("keep", attached.path("custom").asText());
        assertEquals("postgres", attached.path("engine").asText());
        assertEquals("db.local", attached.path("host").asText());
        assertEquals(5432, attached.path("port").asInt());
        assertEquals("app", attached.path("dbname").asText());
        assertEquals("database", attached.path("dbInstanceIdentifier").asText());
        assertFalse(attached.has("dbClusterIdentifier"));

        JsonNode detached = mapper.readTree(values.getAllValues().get(1));
        assertEquals("admin", detached.path("username").asText());
        assertEquals("secret", detached.path("password").asText());
        assertEquals("keep", detached.path("custom").asText());
        assertFalse(detached.has("engine"));
        assertFalse(detached.has("host"));
        assertFalse(detached.has("port"));
        assertFalse(detached.has("dbname"));
        assertFalse(detached.has("dbInstanceIdentifier"));
    }

    @Test
    void dbClusterAttachmentUsesClusterConnectionFields() throws Exception {
        stubSecretValues("{\"username\":\"admin\",\"password\":\"secret\"}");
        DbCluster cluster = new DbCluster();
        cluster.setDbClusterIdentifier("cluster");
        cluster.setEngine(DatabaseEngine.MYSQL);
        cluster.setEndpoint(new DbEndpoint("cluster.local", 3306));
        cluster.setDatabaseName("orders");
        when(rdsService.getDbCluster("cluster")).thenReturn(cluster);

        StackResource resource = provision(properties("AWS::RDS::DBCluster", "cluster"));

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(secretsManagerService).putSecretValue(
                eq(SECRET_ARN), value.capture(), isNull(), isNull(), eq(REGION), isNull());
        JsonNode attached = mapper.readTree(value.getValue());
        assertEquals("mysql", attached.path("engine").asText());
        assertEquals("cluster.local", attached.path("host").asText());
        assertEquals(3306, attached.path("port").asInt());
        assertEquals("orders", attached.path("dbname").asText());
        assertEquals("cluster", attached.path("dbClusterIdentifier").asText());
        assertFalse(attached.has("dbInstanceIdentifier"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SecretId", "TargetId", "TargetType"})
    void allRequiredPropertiesAreValidated(String missingProperty) {
        ObjectNode properties = instanceProperties();
        properties.remove(missingProperty);

        StackResource resource = provision(properties);

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("requires " + missingProperty));
        assertNull(resource.getPhysicalId());
        verify(secretsManagerService, never()).putSecretValue(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingSecretFailsInsteadOfUsingTheUnresolvedId() {
        when(rdsService.getDbInstance("database")).thenReturn(dbInstance());
        when(secretsManagerService.describeSecret(SECRET_ID, REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "missing", 400));

        StackResource resource = provision(instanceProperties());

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("missing"));
        assertNull(resource.getPhysicalId());
        verify(secretsManagerService).describeSecret(SECRET_ID, REGION);
        verify(secretsManagerService, never()).putSecretValue(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void unchangedAttachmentDoesNotCreateAnotherSecretVersion() {
        stubSecretValues("{\"username\":\"admin\",\"password\":\"secret\","
                + "\"engine\":\"postgres\",\"host\":\"db.local\",\"port\":5432,"
                + "\"dbname\":\"app\",\"dbInstanceIdentifier\":\"database\"}");
        when(rdsService.getDbInstance("database")).thenReturn(dbInstance());

        StackResource resource = provision(instanceProperties());

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals(SECRET_ARN, resource.getPhysicalId());
        verify(secretsManagerService, never()).putSecretValue(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void legacyNamePhysicalIdResolvingToTheSameSecretIsNotDetached() {
        stubSecretValues("{\"username\":\"admin\",\"password\":\"secret\","
                + "\"engine\":\"postgres\",\"host\":\"db.local\",\"port\":5432,"
                + "\"dbname\":\"app\",\"dbInstanceIdentifier\":\"database\"}");
        when(rdsService.getDbInstance("database")).thenReturn(dbInstance());

        StackResource resource = provision(instanceProperties(), SECRET_ID,
                Map.of("__FlociSecretTargetManagedKeys",
                        "engine,host,port,dbname,dbInstanceIdentifier"));

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals(SECRET_ARN, resource.getPhysicalId());
        verify(secretsManagerService, never()).putSecretValue(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void missingTargetFailsBeforeReadingTheSecret() {
        when(rdsService.getDbInstance("database"))
                .thenThrow(new AwsException("DBInstanceNotFound", "database is missing", 404));

        StackResource resource = provision(instanceProperties());

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("database is missing"));
        verifyNoInteractions(secretsManagerService);
    }

    @Test
    void incompleteTargetFailsBeforeReadingTheSecret() {
        DbInstance instance = dbInstance();
        instance.setEndpoint(null);
        when(rdsService.getDbInstance("database")).thenReturn(instance);

        StackResource resource = provision(instanceProperties());

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("incomplete connection information"));
        verifyNoInteractions(secretsManagerService);
    }

    @Test
    void replacingTheSecretAttachesTheNewSecretBeforeDetachingTheOldSecret() throws Exception {
        String oldArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:old-a1b2c3";
        String newArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:new-d4e5f6";
        ObjectNode properties = instanceProperties();
        properties.put("SecretId", "new-secret");
        when(rdsService.getDbInstance("database")).thenReturn(dbInstance());
        when(secretsManagerService.describeSecret("new-secret", REGION)).thenReturn(secret(newArn));
        when(secretsManagerService.describeSecret(oldArn, REGION)).thenReturn(secret(oldArn));
        when(secretsManagerService.getSecretValue(newArn, null, null, REGION))
                .thenReturn(secretVersion("{\"username\":\"new-user\",\"password\":\"new-password\"}"));
        when(secretsManagerService.getSecretValue(oldArn, null, null, REGION))
                .thenReturn(secretVersion("{\"username\":\"old-user\",\"password\":\"old-password\","
                        + "\"engine\":\"mysql\",\"host\":\"old.local\",\"port\":3306,"
                        + "\"dbInstanceIdentifier\":\"old-database\"}"));

        StackResource resource = provision(properties, oldArn,
                Map.of("__FlociSecretTargetManagedKeys",
                        "engine,host,port,dbInstanceIdentifier"));

        assertEquals("CREATE_COMPLETE", resource.getStatus());
        assertEquals(newArn, resource.getPhysicalId());
        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(secretsManagerService, times(2)).putSecretValue(
                ids.capture(), values.capture(), isNull(), isNull(), eq(REGION), isNull());
        assertEquals(List.of(newArn, oldArn), ids.getAllValues());

        JsonNode attached = mapper.readTree(values.getAllValues().get(0));
        assertEquals("new-user", attached.path("username").asText());
        assertEquals("postgres", attached.path("engine").asText());
        assertEquals("database", attached.path("dbInstanceIdentifier").asText());

        JsonNode detached = mapper.readTree(values.getAllValues().get(1));
        assertEquals("old-user", detached.path("username").asText());
        assertEquals("old-password", detached.path("password").asText());
        assertFalse(detached.has("engine"));
        assertFalse(detached.has("host"));
        assertFalse(detached.has("port"));
        assertFalse(detached.has("dbInstanceIdentifier"));
    }

    @Test
    void invalidOldSecretPreventsAReplacementFromPartiallyMutatingTheNewSecret() {
        String oldArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:old-a1b2c3";
        String newArn = "arn:aws:secretsmanager:us-east-1:000000000000:secret:new-d4e5f6";
        ObjectNode properties = instanceProperties();
        properties.put("SecretId", "new-secret");
        when(rdsService.getDbInstance("database")).thenReturn(dbInstance());
        when(secretsManagerService.describeSecret("new-secret", REGION)).thenReturn(secret(newArn));
        when(secretsManagerService.describeSecret(oldArn, REGION)).thenReturn(secret(oldArn));
        when(secretsManagerService.getSecretValue(newArn, null, null, REGION))
                .thenReturn(secretVersion("{\"username\":\"new-user\",\"password\":\"new-password\"}"));
        when(secretsManagerService.getSecretValue(oldArn, null, null, REGION))
                .thenReturn(secretVersion("not-json"));

        StackResource resource = provision(properties, oldArn,
                Map.of("__FlociSecretTargetManagedKeys", "engine,host,port"));

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("must be a JSON object"));
        verify(secretsManagerService, never()).putSecretValue(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void detachDoesNotCreateAnotherVersionWhenManagedFieldsAreAlreadyAbsent() {
        when(secretsManagerService.getSecretValue(SECRET_ARN, null, null, REGION))
                .thenReturn(secretVersion("{\"username\":\"admin\",\"custom\":\"keep\"}"));

        provisioner.delete(attachmentResource(), REGION);

        verify(secretsManagerService, never()).putSecretValue(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void detachTreatsAnAlreadyDeletedSecretAsComplete() {
        when(secretsManagerService.getSecretValue(SECRET_ARN, null, null, REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "missing", 400));

        assertDoesNotThrow(() -> provisioner.delete(attachmentResource(), REGION));
        verify(secretsManagerService, never()).putSecretValue(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void physicalIdOnlyDeleteRefusesToGuessWhichSecretFieldsWereManaged() {
        AwsException exception = assertThrows(AwsException.class, () -> provisioner.delete(
                "AWS::SecretsManager::SecretTargetAttachment", SECRET_ARN, REGION));

        assertEquals("ValidationError", exception.getErrorCode());
        assertTrue(exception.getMessage().contains("StackResource metadata"));
        verifyNoInteractions(secretsManagerService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "\"plain-text\"", "[]"})
    void secretValueMustBeAJsonObject(String secretString) {
        stubSecretValues(secretString);
        when(rdsService.getDbInstance("database")).thenReturn(dbInstance());

        StackResource resource = provision(instanceProperties());

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("must be a JSON object"));
        verify(secretsManagerService, never()).putSecretValue(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void unsupportedTargetTypeFailsExplicitly() {
        stubSecretValues("{\"username\":\"admin\",\"password\":\"secret\"}");

        StackResource resource = provision(properties("AWS::Redshift::Cluster", "warehouse"));

        assertEquals("CREATE_FAILED", resource.getStatus());
        assertTrue(resource.getStatusReason().contains("not supported by Floci"));
        verify(secretsManagerService, never()).putSecretValue(
                any(), any(), any(), any(), any(), any());
    }

    private StackResource provision(JsonNode properties) {
        return provision(properties, null, Map.of());
    }

    private StackResource provision(JsonNode properties, String existingPhysicalId,
                                    Map<String, String> existingAttributes) {
        return provisioner.provision(
                "Attachment", "AWS::SecretsManager::SecretTargetAttachment", properties,
                engine(), REGION, "000000000000", "stack",
                existingPhysicalId, existingAttributes);
    }

    private ObjectNode instanceProperties() {
        return properties("AWS::RDS::DBInstance", "database");
    }

    private ObjectNode properties(String targetType, String targetId) {
        ObjectNode properties = mapper.createObjectNode();
        properties.put("SecretId", SECRET_ID);
        properties.put("TargetId", targetId);
        properties.put("TargetType", targetType);
        return properties;
    }

    private DbInstance dbInstance() {
        DbInstance instance = new DbInstance();
        instance.setDbInstanceIdentifier("database");
        instance.setEngine(DatabaseEngine.POSTGRES);
        instance.setEndpoint(new DbEndpoint("db.local", 5432));
        instance.setDbName("app");
        return instance;
    }

    private void stubSecretValues(String... values) {
        when(secretsManagerService.describeSecret(SECRET_ID, REGION)).thenReturn(secret(SECRET_ARN));
        SecretVersion[] versions = java.util.Arrays.stream(values)
                .map(this::secretVersion)
                .toArray(SecretVersion[]::new);
        when(secretsManagerService.getSecretValue(SECRET_ARN, null, null, REGION))
                .thenReturn(versions[0], java.util.Arrays.copyOfRange(versions, 1, versions.length));
    }

    private Secret secret(String arn) {
        Secret secret = new Secret();
        secret.setArn(arn);
        return secret;
    }

    private SecretVersion secretVersion(String value) {
        SecretVersion version = new SecretVersion();
        version.setSecretString(value);
        return version;
    }

    private StackResource attachmentResource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Attachment");
        resource.setResourceType("AWS::SecretsManager::SecretTargetAttachment");
        resource.setPhysicalId(SECRET_ARN);
        resource.setAttributes(new java.util.HashMap<>(Map.of(
                "__FlociSecretTargetManagedKeys",
                "engine,host,port,dbname,dbInstanceIdentifier")));
        return resource;
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine(
                "000000000000", REGION, "stack", "stack/id",
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }
}
