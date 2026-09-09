package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.backup.BackupService;
import io.github.hectorvent.floci.services.backup.model.BackupVault;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Backup vault CFN provisioner in isolation, against a mocked {@link BackupService}. */
class BackupVaultCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String TYPE = "AWS::Backup::BackupVault";
    private static final String ARN = "arn:aws:backup:us-east-1:000000000000:backup-vault:my-vault";
    private static final String KMS = "arn:aws:kms:us-east-1:000000000000:key/abc";
    private static final AwsException NOT_FOUND =
            new AwsException("ResourceNotFoundException", "Backup vault not found: my-vault", 404);

    private final BackupService backup = mock(BackupService.class);
    private final BackupVaultCfnProvisioner provisioner = new BackupVaultCfnProvisioner(backup);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("MyVault");
        r.setResourceType(TYPE);
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static BackupVault vault(String name, String encryptionKeyArn, Map<String, String> tags) {
        BackupVault vault = new BackupVault();
        vault.setBackupVaultName(name);
        vault.setBackupVaultArn(ARN);
        vault.setEncryptionKeyArn(encryptionKeyArn);
        vault.setTags(new HashMap<>(tags));
        return vault;
    }

    private ObjectNode props(String name, String encryptionKeyArn, Map<String, String> tags) {
        ObjectNode props = mapper.createObjectNode();
        if (name != null) {
            props.put("BackupVaultName", name);
        }
        if (encryptionKeyArn != null) {
            props.put("EncryptionKeyArn", encryptionKeyArn);
        }
        if (tags != null) {
            ObjectNode node = props.putObject("BackupVaultTags");
            tags.forEach(node::put);
        }
        return props;
    }

    @Test
    void createSendsNameKeyAndTagsAndRecordsBothAttributes() {
        when(backup.createBackupVault(eq("my-vault"), eq(KMS), anyString(), anyMap(), eq(REGION)))
                .thenReturn(vault("my-vault", KMS, Map.of("env", "prod")));
        StackResource r = resource(null);

        provisioner.provision(r, props("my-vault", KMS, Map.of("env", "prod")), ctx());

        assertEquals("my-vault", r.getPhysicalId());
        assertEquals("my-vault", r.getAttributes().get("BackupVaultName"));
        assertEquals(ARN, r.getAttributes().get("BackupVaultArn"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> tags = ArgumentCaptor.forClass(Map.class);
        verify(backup).createBackupVault(eq("my-vault"), eq(KMS), anyString(), tags.capture(), eq(REGION));
        assertEquals(Map.of("env", "prod"), tags.getValue());
    }

    @Test
    void unnamedVaultGetsAGeneratedName() {
        when(backup.createBackupVault(anyString(), isNull(), anyString(), anyMap(), eq(REGION)))
                .thenReturn(vault("generated", null, Map.of()));

        provisioner.provision(resource(null), mapper.createObjectNode(), ctx());

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(backup).createBackupVault(name.capture(), isNull(), anyString(), eq(Map.of()), eq(REGION));
        assertTrue(name.getValue().startsWith("my-stack-MyVault-"), name.getValue());
        assertTrue(name.getValue().length() <= 50, name.getValue());
    }

    @Test
    void updateKeepsTheVaultAndDrivesTagsToTheTemplate() {
        when(backup.describeBackupVault("my-vault", REGION))
                .thenReturn(vault("my-vault", KMS, Map.of("env", "prod", "stale", "x")));
        StackResource r = resource("my-vault");

        provisioner.provision(r, props("my-vault", KMS, Map.of("env", "dev", "team", "core")), ctx("my-vault"));

        verify(backup, never()).createBackupVault(any(), any(), any(), anyMap(), any());
        verify(backup).untagResource(ARN, List.of("stale"));
        verify(backup).tagResource(ARN, Map.of("env", "dev", "team", "core"));
        assertEquals("my-vault", r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("BackupVaultArn"));
    }

    @Test
    void anUnnamedVaultKeepsItsGeneratedNameAcrossAnUpdate() {
        when(backup.describeBackupVault("my-stack-MyVault-abc", REGION))
                .thenReturn(vault("my-stack-MyVault-abc", null, Map.of()));
        StackResource r = resource("my-stack-MyVault-abc");

        provisioner.provision(r, mapper.createObjectNode(), ctx("my-stack-MyVault-abc"));

        verify(backup, never()).createBackupVault(any(), any(), any(), anyMap(), any());
        assertEquals("my-stack-MyVault-abc", r.getPhysicalId());
    }

    @Test
    void anUnchangedUpdateTouchesNothing() {
        when(backup.describeBackupVault("my-vault", REGION)).thenReturn(vault("my-vault", KMS, Map.of("env", "prod")));

        provisioner.provision(resource("my-vault"), props("my-vault", KMS, Map.of("env", "prod")), ctx("my-vault"));

        verify(backup, never()).createBackupVault(any(), any(), any(), anyMap(), any());
        verify(backup, never()).tagResource(any(), anyMap());
        verify(backup, never()).untagResource(any(), anyList());
    }

    @Test
    void aRenameIsRefusedAsReplacementWorthy() {
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("my-vault"), props("other-vault", null, null), ctx("my-vault")));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("BackupVaultName"), e.getMessage());
        verify(backup, never()).describeBackupVault(any(), any());
        verify(backup, never()).createBackupVault(any(), any(), any(), anyMap(), any());
    }

    @Test
    void aChangedEncryptionKeyIsRefusedAsReplacementWorthy() {
        when(backup.describeBackupVault("my-vault", REGION)).thenReturn(vault("my-vault", KMS, Map.of()));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(
                resource("my-vault"), props("my-vault", "arn:aws:kms:us-east-1:000000000000:key/other", null),
                ctx("my-vault")));

        assertTrue(e.getMessage().contains("EncryptionKeyArn"), e.getMessage());
        verify(backup, never()).tagResource(any(), anyMap());
    }

    @Test
    void aVaultDeletedOutOfBandIsCreatedAgain() {
        when(backup.describeBackupVault("my-vault", REGION)).thenThrow(NOT_FOUND);
        when(backup.createBackupVault(eq("my-vault"), isNull(), anyString(), anyMap(), eq(REGION)))
                .thenReturn(vault("my-vault", null, Map.of()));
        StackResource r = resource("my-vault");

        provisioner.provision(r, props("my-vault", null, null), ctx("my-vault"));

        verify(backup).createBackupVault(eq("my-vault"), isNull(), anyString(), anyMap(), eq(REGION));
        assertEquals(ARN, r.getAttributes().get("BackupVaultArn"));
    }

    @Test
    void deleteDelegatesAndToleratesAMissingVault() {
        provisioner.delete(TYPE, "my-vault", REGION);
        verify(backup).deleteBackupVault("my-vault", REGION);

        doThrow(NOT_FOUND).when(backup).deleteBackupVault("gone", REGION);
        assertDoesNotThrow(() -> provisioner.delete(TYPE, "gone", REGION));
    }

    @Test
    void deleteOfANonEmptyVaultStillFails() {
        doThrow(new AwsException("InvalidRequestException", "Non-empty backup vault cannot be deleted: full", 400))
                .when(backup).deleteBackupVault("full", REGION);

        assertThrows(AwsException.class, () -> provisioner.delete(TYPE, "full", REGION));
    }
}
