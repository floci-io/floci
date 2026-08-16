package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.ArnSynthesizer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Builds ARNs for EC2's stored models, which carry ids rather than ARNs.
 *
 * <p>Every EC2 store is keyed {@code <region>::<id>}, so the region and the id both come from the
 * storage key and nothing has to be guessed from field names — which matters, because an EC2
 * model routinely carries several ids ({@code Subnet} has both {@code subnetId} and
 * {@code vpcId}) and picking the wrong one would attribute a subnet's tags to its VPC.
 *
 * <p>An unrecognised id prefix produces no ARN rather than a plausible-looking wrong one.
 */
@ApplicationScoped
public class Ec2ArnSynthesizer implements ArnSynthesizer {

    private static final String KEY_SEPARATOR = "::";

    @Override
    public Optional<String> synthesize(String serviceName, String logicalKey, String accountId, JsonNode node) {
        if (!"ec2".equals(serviceName) || logicalKey == null) {
            return Optional.empty();
        }
        int separator = logicalKey.indexOf(KEY_SEPARATOR);
        if (separator < 0) {
            return Optional.empty();
        }
        String region = logicalKey.substring(0, separator);
        String resourceId = logicalKey.substring(separator + KEY_SEPARATOR.length());
        if (resourceId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(Ec2ResourceIds.arn(region, accountId, resourceId));
    }
}
