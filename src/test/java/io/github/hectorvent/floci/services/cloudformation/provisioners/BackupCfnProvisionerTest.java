package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.backup.BackupService;
import io.github.hectorvent.floci.services.backup.model.BackupVault;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Backup CFN provisioner in isolation: {@code AWS::Backup::BackupVault} (issue #17 aws-bench
 * gap batch).
 */
class BackupCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String VAULT_ARN = "arn:aws:backup:us-east-1:000000000000:backup-vault:my-vault";

    private final BackupService backupService = mock(BackupService.class);
    private final BackupCfnProvisioner provisioner = new BackupCfnProvisioner(backupService);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("MyVault");
        r.setResourceType("AWS::Backup::BackupVault");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private BackupVault vault(String name, String arn) {
        BackupVault v = new BackupVault();
        v.setBackupVaultName(name);
        v.setBackupVaultArn(arn);
        return v;
    }

    @Test
    void createSetsPhysicalIdAndGetAttAttributes() {
        when(backupService.createBackupVault(eq("my-vault"), eq("arn:aws:kms:us-east-1:000000000000:key/abc"),
                anyString(), any(), eq(REGION))).thenReturn(vault("my-vault", VAULT_ARN));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("BackupVaultName", "my-vault")
                .put("EncryptionKeyArn", "arn:aws:kms:us-east-1:000000000000:key/abc");

        provisioner.provision(r, props, ctx());

        assertEquals("my-vault", r.getPhysicalId());
        assertEquals(VAULT_ARN, r.getAttributes().get("BackupVaultArn"));
        assertEquals("my-vault", r.getAttributes().get("BackupVaultName"));
    }

    @Test
    void backupVaultTagsIsAPlainJsonObjectNotATagList() {
        when(backupService.createBackupVault(eq("my-vault"), any(), anyString(), any(), eq(REGION)))
                .thenReturn(vault("my-vault", VAULT_ARN));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode().put("BackupVaultName", "my-vault");
        props.putObject("BackupVaultTags").put("Environment", "prod");

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(backupService).createBackupVault(eq("my-vault"), any(), anyString(), captor.capture(), eq(REGION));
        assertEquals("prod", captor.getValue().get("Environment"));
    }

    @Test
    void updateDoesNotRecreateAndConfirmsExistence() {
        when(backupService.createBackupVault(eq("my-vault"), any(), anyString(), any(), eq(REGION)))
                .thenReturn(vault("my-vault", VAULT_ARN));
        when(backupService.describeBackupVault(eq("my-vault"), eq(REGION)))
                .thenReturn(vault("my-vault", VAULT_ARN));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode().put("BackupVaultName", "my-vault");

        provisioner.provision(r, props, ctx());
        provisioner.provision(r, props, ctx());

        verify(backupService, times(1)).createBackupVault(eq("my-vault"), any(), anyString(), any(), eq(REGION));
        verify(backupService, times(1)).describeBackupVault(eq("my-vault"), eq(REGION));
        assertEquals("my-vault", r.getPhysicalId());
    }

    @Test
    void deleteDelegatesToService() {
        provisioner.delete("AWS::Backup::BackupVault", "my-vault", REGION);
        verify(backupService).deleteBackupVault("my-vault", REGION);
    }
}
