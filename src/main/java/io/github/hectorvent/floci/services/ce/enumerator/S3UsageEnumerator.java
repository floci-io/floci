package io.github.hectorvent.floci.services.ce.enumerator;

import io.github.hectorvent.floci.core.common.ResourceUsageEnumerator;
import io.github.hectorvent.floci.core.common.UsageLine;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.Bucket;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Enumerates S3 buckets as Cost Explorer storage-usage lines.
 * Aggregates object sizes per bucket and emits one {@code GB-Mo} line scaled
 * to the fraction of a 30-day month covered by the requested period.
 */
@ApplicationScoped
public class S3UsageEnumerator implements ResourceUsageEnumerator {

    static final String SERVICE_CODE = "AmazonS3";
    /**
     * AWS-native usage type for S3 Standard-class storage. Must match the
     * {@code usagetype} attribute on the corresponding product offer in the
     * Pricing snapshot.
     */
    static final String STANDARD_STORAGE_USAGE_TYPE = "TimedStorage-Standard";
    private static final double BYTES_PER_GB = 1_073_741_824.0;
    private static final double SECONDS_PER_MONTH = 30.0 * 86_400.0;

    private final S3Service s3;

    @Inject
    public S3UsageEnumerator(S3Service s3) {
        this.s3 = s3;
    }

    @Override
    public Stream<UsageLine> enumerate(Instant periodStart, Instant periodEnd, String region) {
        long periodSeconds = Duration.between(periodStart, periodEnd).toSeconds();
        if (periodSeconds <= 0) {
            return Stream.empty();
        }
        double monthFraction = periodSeconds / SECONDS_PER_MONTH;

        List<Bucket> buckets = s3.listBuckets();
        Stream.Builder<UsageLine> out = Stream.builder();
        // Always emit a zero-quantity catalog line so the service surfaces in
        // GetDimensionValues SERVICE even when no buckets are present.
        out.add(new UsageLine(periodStart, periodEnd, SERVICE_CODE, region,
                STANDARD_STORAGE_USAGE_TYPE, "StandardStorage",
                UsageLine.RECORD_TYPE_USAGE, "000000000000", null, new HashMap<>(),
                0.0, "GB-Mo"));
        for (Bucket bucket : buckets) {
            String bucketRegion = bucket.getRegion();
            if (bucketRegion == null) {
                bucketRegion = "us-east-1";
            }
            if (!bucketRegion.equals(region)) {
                continue;
            }
            double totalGb = totalBucketGb(bucket.getName());
            if (totalGb <= 0) {
                continue;
            }
            double gbMonths = totalGb * monthFraction;
            Map<String, String> tags = bucket.getTags() == null ? new HashMap<>() : new HashMap<>(bucket.getTags());
            out.add(new UsageLine(
                    periodStart, periodEnd,
                    SERVICE_CODE, region,
                    STANDARD_STORAGE_USAGE_TYPE, "StandardStorage",
                    UsageLine.RECORD_TYPE_USAGE,
                    "000000000000",
                    "arn:aws:s3:::" + bucket.getName(),
                    tags,
                    gbMonths,
                    "GB-Mo"));
        }
        return out.build();
    }

    private double totalBucketGb(String bucketName) {
        try {
            List<S3Object> objects = s3.listObjects(bucketName, null, null, Integer.MAX_VALUE);
            long bytes = 0;
            for (S3Object obj : objects) {
                bytes += Math.max(0, obj.getSize());
            }
            return bytes / BYTES_PER_GB;
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }
}
