package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.backup.BackupService;
import io.github.hectorvent.floci.services.backup.model.BackupVault;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CloudFormation provisioning for {@code AWS::Backup::BackupVault} (aws-bench gap batch, issue #17).
 */
@ApplicationScoped
public class BackupCfnProvisioner implements CfnResourceProvisioner {

    private final BackupService backupService;

    @Inject
    public BackupCfnProvisioner(BackupService backupService) {
        this.backupService = backupService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Backup::BackupVault");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        if (r.getPhysicalId() != null) {
            // BackupService exposes no update path for a vault's fields (only create/describe/delete),
            // so treat it as immutable after creation and just confirm it is still there. Re-calling
            // createBackupVault would throw AlreadyExistsException.
            BackupVault existing = backupService.describeBackupVault(r.getPhysicalId(), ctx.region());
            setBackupVaultAttributes(r, existing);
            return;
        }

        String vaultName = ctx.resolveOptional(props, "BackupVaultName");
        if (vaultName == null || vaultName.isBlank()) {
            vaultName = ctx.generatePhysicalName(r.getLogicalId(), 50, false);
        }
        String encryptionKeyArn = ctx.resolveOptional(props, "EncryptionKeyArn");
        Map<String, String> tags = parseJsonTagMap(props, "BackupVaultTags", ctx);

        BackupVault vault = backupService.createBackupVault(vaultName, encryptionKeyArn,
                UUID.randomUUID().toString(), tags, ctx.region());
        setBackupVaultAttributes(r, vault);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        backupService.deleteBackupVault(physicalId, region);
    }

    private void setBackupVaultAttributes(StackResource r, BackupVault vault) {
        r.setPhysicalId(vault.getBackupVaultName());
        // AWS::Backup::BackupVault's documented Fn::GetAtt attributes (AWS public schema); no local
        // registry schema is present under local/aws/cfn-resource-schemas. BackupVaultName mirrors
        // Ref but is still exposed as an attribute, matching how other provisioners (e.g. SQS Queue)
        // echo input properties as Fn::GetAtt targets.
        r.getAttributes().put("BackupVaultArn", vault.getBackupVaultArn());
        r.getAttributes().put("BackupVaultName", vault.getBackupVaultName());
    }

    /**
     * {@code BackupVaultTags} is a plain JSON object of key/value pairs (unlike the CFN {@code Tags}
     * list-of-{Key,Value} shape used elsewhere), per AWS::Backup::BackupVault's schema.
     */
    private Map<String, String> parseJsonTagMap(JsonNode props, String field, ProvisionContext ctx) {
        Map<String, String> tags = new HashMap<>();
        if (props == null || !props.has(field) || props.get(field).isNull()) {
            return tags;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get(field));
        if (resolved.isObject()) {
            resolved.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }
}
