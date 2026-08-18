package io.github.hectorvent.floci.services.route53;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.ArnSynthesizer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.Set;

/**
 * Builds ARNs for entries in Route53's tag side-store ({@code route53-tags.json}), whose keys are
 * {@code hostedzone/<id>} or {@code healthcheck/<id>} rather than an ARN, and whose stored value
 * is a flat tag map with nothing on it to derive an identity from (see {@link Route53Service}'s
 * {@code tagStore}).
 *
 * <p>Route53 hosted zones and health checks are partition-scoped, not region- or account-scoped,
 * so their ARNs carry neither segment: {@code arn:aws:route53:::hostedzone/<id>}. The resource
 * type and id both come from the storage key, which already carries the exact segment
 * {@code ListTagsForResource}/{@code ChangeTagsForResource} were called with.
 */
@ApplicationScoped
public class Route53ArnSynthesizer implements ArnSynthesizer {

    private static final String TAG_STORE = "route53-tags.json";
    private static final Set<String> RESOURCE_TYPES = Set.of("hostedzone", "healthcheck");

    @Override
    public Optional<String> synthesize(String serviceName, String storeName, String logicalKey,
                                       String accountId, JsonNode node) {
        if (!"route53".equals(serviceName) || !TAG_STORE.equals(storeName) || logicalKey == null) {
            return Optional.empty();
        }
        int slash = logicalKey.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        String resourceType = logicalKey.substring(0, slash);
        String id = logicalKey.substring(slash + 1);
        if (id.isEmpty() || !RESOURCE_TYPES.contains(resourceType)) {
            return Optional.empty();
        }
        return Optional.of("arn:aws:route53:::" + resourceType + "/" + id);
    }
}
