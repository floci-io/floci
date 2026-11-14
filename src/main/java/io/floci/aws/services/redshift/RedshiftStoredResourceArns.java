package io.floci.aws.services.redshift;

import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.services.redshift.model.Snapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * What IAM asks Redshift of the resources a request names: what a snapshot records as its {@code KmsKeyId}, the key a
 * restore asks KMS to decrypt with as the caller.
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
}
