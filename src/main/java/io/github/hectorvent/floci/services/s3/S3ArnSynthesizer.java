package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.ArnSynthesizer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Builds bucket ARNs for the estate-wide tagging scan.
 *
 * <p>{@code Bucket} carries no ARN — S3's own APIs never return one — and the bucket store is
 * keyed by bucket name, which is the whole of an S3 bucket ARN's resource part. S3 bucket ARNs
 * carry neither region nor account, so both segments are empty, as AWS emits them.
 *
 * <p>Scoped to {@code s3-buckets.json}: the object store is owned by the same service and needs a
 * different ARN shape, so a service-wide match would produce wrong ARNs for objects.
 */
@ApplicationScoped
public class S3ArnSynthesizer implements ArnSynthesizer {

    private static final String BUCKET_STORE = "s3-buckets.json";

    @Override
    public Optional<String> synthesize(String serviceName, String storeName, String logicalKey,
                                       String accountId, JsonNode node) {
        if (!"s3".equals(serviceName) || !BUCKET_STORE.equals(storeName)) {
            return Optional.empty();
        }
        if (logicalKey == null || logicalKey.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("arn:aws:s3:::" + logicalKey);
    }
}
