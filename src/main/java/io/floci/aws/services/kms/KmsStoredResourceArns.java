package io.floci.aws.services.kms;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Finds the key a KMS key id, alias or ARN names, for a request another service forwards to KMS as the caller on a
 * key its own resource records (a snapshot's): the key's stored ARN, in the caller's account or in the one that
 * resource is in, when KMS would use it; none for a key disabled or pending deletion, which KMS refuses such a
 * request with.
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
                ? kms.findKeyArnOfAccount(names.getFirst(), account, region).filter(kms::isEnabledKey)
                : Optional.empty();
    }

    /** Whether the key is one AWS manages, whose policy lets every principal of its account use it through a service. */
    @Override
    public boolean grantsItsAccountThroughServices(String arn) {
        return kms.isAwsManagedKey(arn);
    }
}
