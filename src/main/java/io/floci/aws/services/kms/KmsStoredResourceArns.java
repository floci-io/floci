package io.floci.aws.services.kms;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds what KMS holds for the request-resources table: the key a key id, alias or ARN names, whatever its state, in
 * the caller's account or in the one an ARN names, which a request naming it acts on; the key an alias points at, the
 * key a grant is on and the key a ciphertext was encrypted under, which a request naming the alias, the grant or the
 * ciphertext reaches; and the tags of a key.
 */
@ApplicationScoped
public class KmsStoredResourceArns implements StoredResourceArns {

    private final KmsService kms;
    private final RegionResolver regionResolver;

    @Inject
    public KmsStoredResourceArns(KmsService kms, RegionResolver regionResolver) {
        this.kms = kms;
        this.regionResolver = regionResolver;
    }

    @Override
    public String namespace() {
        return "kms";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return findInAccount(type, names, regionResolver.getAccountId(), region);
    }

    @Override
    public Optional<String> findInAccount(String type, List<String> names, String account, String region) {
        return "key".equals(type) && names.size() == 1
                ? kms.findKeyArnOfAccount(names.getFirst(), account, region)
                : Optional.empty();
    }

    /** Whether KMS uses the key for a request another service forwards: enabled, not disabled or pending deletion. */
    @Override
    public boolean usable(String arn) {
        return kms.isEnabledKey(arn);
    }

    /** Whether the key is one AWS manages, whose policy lets every principal of its account use it through a service. */
    @Override
    public boolean grantsItsAccountThroughServices(String arn) {
        return kms.isAwsManagedKey(arn);
    }

    /**
     * The key the caller's alias an {@code AliasName} names points at, the key the grant a {@code GrantId} names is
     * on, or the key the {@code CiphertextBlob} was encrypted under, which KMS reads out of the blob's metadata; none
     * when the caller holds no such alias, grant or key, or the blob is none KMS made.
     */
    @Override
    public List<String> through(String member, String value, String region) {
        Optional<String> key = switch (member) {
            case "AliasName" -> kms.findKeyArn(value, region);
            case "GrantId" -> kms.findGrantKeyArn(null, value, region);
            case "CiphertextBlob" -> ciphertextKey(value, region);
            default -> Optional.empty();
        };
        return key.stream().toList();
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        return StoredResourceArns.withEmptyValues(kms.findKeyTags(arn));
    }

    /** The key a base64 ciphertext blob was encrypted under, as KMS reads it out of the blob to decrypt it. */
    private Optional<String> ciphertextKey(String blob, String region) {
        try {
            return Optional.ofNullable(kms.decryptToKeyArn(Base64.getDecoder().decode(blob), region));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
