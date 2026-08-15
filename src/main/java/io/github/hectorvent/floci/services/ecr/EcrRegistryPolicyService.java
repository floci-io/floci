package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * The ECR registry permission policy.
 *
 * <p>Unlike a repository policy this is registry-scoped: one document per account per
 * region, which is why it lives outside the repository store.
 */
@ApplicationScoped
public class EcrRegistryPolicyService {

    private static final Logger LOG = Logger.getLogger(EcrRegistryPolicyService.class);

    private final StorageBackend<String, String> policies;
    private final RegionResolver regionResolver;

    @Inject
    public EcrRegistryPolicyService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.policies = storageFactory.create("ecr", "ecr-registry-policies.json",
                new TypeReference<Map<String, String>>() {});
        this.regionResolver = regionResolver;
    }

    public String registryId() {
        return regionResolver.getAccountId();
    }

    public String put(String region, String policyText) {
        if (policyText == null || policyText.isBlank()) {
            throw new AwsException("InvalidParameterException",
                    "Invalid parameter at 'PolicyText' failed to satisfy constraint: "
                            + "'Member must not be null'", 400);
        }
        policies.put(key(region), policyText);
        LOG.infov("Set ECR registry policy for {0} in {1}", registryId(), region);
        return policyText;
    }

    public String get(String region) {
        return find(region).orElseThrow(() -> new AwsException("RegistryPolicyNotFoundException",
                "Registry policy does not exist in the registry with id '" + registryId() + "'", 400));
    }

    public String delete(String region) {
        String existing = get(region);
        policies.delete(key(region));
        LOG.infov("Deleted ECR registry policy for {0} in {1}", registryId(), region);
        return existing;
    }

    public Optional<String> find(String region) {
        return policies.get(key(region));
    }

    private String key(String region) {
        return region + "::" + registryId();
    }
}
