package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::IAM::AccessKey}. {@code Ref} returns the access key
 * id, and {@code Fn::GetAtt} exposes the same id plus the one-time {@code SecretAccessKey}.
 *
 * <p>{@code UserName} and {@code Serial} are createOnly. An update that changes neither reuses the
 * existing key rather than minting another one, since a user is capped at two keys and recreating on
 * every update would fail with {@code LimitExceeded} and orphan the earlier keys. A change to either
 * replaces the key (AWS rotates a key by incrementing {@code Serial}): a new key is created and the
 * displaced one is deleted once the update commits, through {@link ReplacementCleanup}. {@code Status}
 * (Active/Inactive) is a mutable property, applied to the key in place and reconciled on a reuse.
 */
@ApplicationScoped
public class IamAccessKeyCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::IAM::AccessKey";
    private static final String SERIAL_ATTR = "__FlociAccessKeySerial";

    private final IamService iamService;

    @Inject
    public IamAccessKeyCfnProvisioner(IamService iamService) {
        this.iamService = iamService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String userName = ctx.resolveOptional(props, "UserName");
        if (userName == null || userName.isBlank()) {
            throw new AwsException("ValidationError", "AWS::IAM::AccessKey requires a UserName.", 400);
        }
        String serial = serialOf(props, ctx);
        // Status (Active/Inactive) is a mutable property. Validate it up front, before any key is
        // created, so a bad value fails cleanly: a user is capped at two keys, so creating one and
        // only then rejecting the status could orphan it.
        String status = ctx.resolveOptional(props, "Status");
        if (status != null && !"Active".equals(status) && !"Inactive".equals(status)) {
            throw new AwsException("ValidationError",
                    "AWS::IAM::AccessKey Status must be Active or Inactive.", 400);
        }

        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
        if (ctx.isUpdate() && !createOnlyChanged(r, ctx, userName, serial)) {
            // Neither UserName nor Serial changed: keep the existing key and its id and secret. Only
            // reconcile Status when the template declares it; an update that omits it leaves the key
            // as-is, the way AWS applies only the properties the template changed (so a key
            // deactivated out of band is not silently reactivated by an unrelated update).
            if (status != null) {
                iamService.updateAccessKey(userName, ctx.priorPhysicalId(), status);
            }
            ReplacementCleanup.record(r, ctx, attributesBefore);
            return;
        }

        AccessKey key = iamService.createAccessKey(userName);
        r.setPhysicalId(key.getAccessKeyId());
        r.getAttributes().put("Id", key.getAccessKeyId());
        r.getAttributes().put("SecretAccessKey", key.getSecretAccessKey());
        r.getAttributes().put(SERIAL_ATTR, serial);
        // A new key is Active; only touch it when the template asked for Inactive.
        if ("Inactive".equals(status)) {
            iamService.updateAccessKey(userName, key.getAccessKeyId(), status);
        }
        // On a createOnly change this new key replaced the prior one; record it so the displaced
        // key is deleted after the update commits, and rolled back to if a later resource fails.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private String serialOf(JsonNode props, ProvisionContext ctx) {
        String serial = ctx.resolveOptional(props, "Serial");
        return serial == null ? "" : serial;
    }

    private boolean createOnlyChanged(StackResource r, ProvisionContext ctx, String userName, String serial) {
        String priorUser = iamService.findUserNameByAccessKeyId(ctx.priorPhysicalId()).orElse(null);
        String priorSerial = r.getAttributes().getOrDefault(SERIAL_ATTR, "");
        // A missing prior user means the old key is already gone, so a fresh one is created.
        return priorUser == null || !priorUser.equals(userName) || !priorSerial.equals(serial);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // The owning user is recovered from the key id so both the stack-delete and the
        // replacement-cleanup paths can delete by physical id alone.
        iamService.findUserNameByAccessKeyId(physicalId).ifPresent(userName ->
                CfnDeletes.safeDelete("IAM access key", physicalId,
                        () -> iamService.deleteAccessKey(userName, physicalId), "NoSuchEntity"));
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }
}
