package io.floci.aws.services.redshift;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.redshift.model.Snapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks Redshift of the resources a request names: what a snapshot records as its {@code KmsKeyId}, the key a
 * restore asks KMS to decrypt with as the caller, the tags of the resource an ARN names, and whether a snapshot another
 * account holds is acted on there.
 */
@ApplicationScoped
public class RedshiftStoredResourceArns implements StoredResourceArns {

    private final RedshiftService redshift;

    @Inject
    public RedshiftStoredResourceArns(RedshiftService redshift) {
        this.redshift = redshift;
    }

    @Override
    public String namespace() {
        return "redshift";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /** The key the snapshot an ARN names, in any account, records as its {@code KmsKeyId}. */
    @Override
    public Optional<String> recorded(String arn, String member) {
        if (!"KmsKeyId".equals(member)) {
            return Optional.empty();
        }
        return redshift.findSnapshotAt(arn).map(Snapshot::getKmsKeyId).filter(key -> !key.isBlank());
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        return redshift.storedTags(arn);
    }

    /**
     * A snapshot of another account, which a restore naming its {@code OwnerAccount} restores from that account, and
     * never the caller's snapshot of that name: the tag handlers refuse an ARN of another account.
     */
    @Override
    public boolean reachedInItsAccount(String arn, String accountId) {
        if (!AwsArnUtils.isArnFor(arn, "redshift")) {
            return false;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        return !parsed.accountId().isEmpty() && !parsed.accountId().equals(accountId)
                && parsed.resource().startsWith("snapshot:");
    }
}
