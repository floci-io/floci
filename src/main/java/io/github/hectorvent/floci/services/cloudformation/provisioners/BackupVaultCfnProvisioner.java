package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.backup.BackupService;
import io.github.hectorvent.floci.services.backup.model.BackupVault;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * CloudFormation provisioning for {@code AWS::Backup::BackupVault}, backed by {@link BackupService}.
 * {@code Ref} and {@code Fn::GetAtt BackupVaultName} are the vault name and
 * {@code Fn::GetAtt BackupVaultArn} its ARN, matching AWS.
 *
 * <p>The name is the physical id and createOnly, so an unnamed vault keeps its generated name across
 * updates instead of getting a second vault, and a rename is refused as a replacement. EncryptionKeyArn
 * is createOnly too. BackupVaultTags update in place. AccessPolicy, Notifications and
 * LockConfiguration have no counterpart in the emulated vault and are accepted without effect.
 */
@ApplicationScoped
public class BackupVaultCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(BackupVaultCfnProvisioner.class);

    private static final String TYPE = "AWS::Backup::BackupVault";
    private static final String NOT_FOUND = "ResourceNotFoundException";
    /** The Backup API's limit on a vault name. */
    private static final int VAULT_NAME_MAX = 50;

    private final BackupService backupService;

    @Inject
    public BackupVaultCfnProvisioner(BackupService backupService) {
        this.backupService = backupService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "BackupVaultName"),
                r.getLogicalId(), VAULT_NAME_MAX, false);
        String encryptionKeyArn = blankToNull(ctx.resolveOptional(props, "EncryptionKeyArn"));
        Map<String, String> tags = vaultTags(props, ctx);

        if (ctx.isUpdate()) {
            if (!ctx.reusesPriorEntity(name)) {
                throw replacementNotSupported("BackupVaultName");
            }
            BackupVault existing = findVault(name, ctx.region());
            if (existing != null) {
                rejectIfChanged("EncryptionKeyArn", existing.getEncryptionKeyArn(), encryptionKeyArn);
                reconcileTags(existing, tags);
                record(r, existing);
                return;
            }
        }
        record(r, backupService.createBackupVault(name, encryptionKeyArn, UUID.randomUUID().toString(),
                tags, ctx.region()));
    }

    /**
     * Drives the vault's tags to the template's set. TagResource only adds, so a key the template
     * dropped has to be untagged explicitly.
     */
    private void reconcileTags(BackupVault vault, Map<String, String> desired) {
        Map<String, String> current = vault.getTags() == null ? Map.of() : vault.getTags();
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            backupService.untagResource(vault.getBackupVaultArn(), stale);
        }
        boolean changed = desired.entrySet().stream()
                .anyMatch(e -> !Objects.equals(current.get(e.getKey()), e.getValue()));
        if (changed) {
            backupService.tagResource(vault.getBackupVaultArn(), desired);
        }
    }

    private BackupVault findVault(String name, String region) {
        try {
            return backupService.describeBackupVault(name, region);
        } catch (AwsException e) {
            if (!NOT_FOUND.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Backup vault {0} from the previous provision is gone, creating it again", name);
            return null;
        }
    }

    /**
     * {@code BackupVaultTags} is a plain JSON object of key/value pairs, not the {@code [{Key, Value}]}
     * list most types use, so {@code ProvisionContext.resolveTags} does not apply. Values still go
     * through the engine so a {@code Ref} or {@code Fn::Sub} inside the map resolves.
     */
    private static Map<String, String> vaultTags(JsonNode props, ProvisionContext ctx) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (props == null || !props.hasNonNull("BackupVaultTags")) {
            return tags;
        }
        JsonNode resolved = ctx.engine().resolveNode(props.get("BackupVaultTags"));
        if (resolved == null || !resolved.isObject()) {
            return tags;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = resolved.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            String value = ctx.engine().resolve(entry.getValue());
            tags.put(entry.getKey(), value == null ? "" : value);
        }
        return tags;
    }

    private static void record(StackResource r, BackupVault vault) {
        r.setPhysicalId(vault.getBackupVaultName());
        r.getAttributes().put("BackupVaultName", vault.getBackupVaultName());
        r.getAttributes().put("BackupVaultArn", vault.getBackupVaultArn());
    }

    private static void rejectIfChanged(String property, String existing, String requested) {
        if (!Objects.equals(blankToNull(existing), blankToNull(requested))) {
            throw replacementNotSupported(property);
        }
    }

    private static AwsException replacementNotSupported(String property) {
        return new AwsException("ValidationError",
                "Updating " + property + " requires resource replacement, which is not supported.", 400);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Without this the vault outlives its stack. A vault that still holds recovery points refuses to
     * go, as on AWS, and that failure is left to surface on the stack delete.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        CfnDeletes.safeDelete("backup vault", physicalId,
                () -> backupService.deleteBackupVault(physicalId, region), NOT_FOUND);
    }
}
