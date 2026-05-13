package io.github.hectorvent.floci.services.ce.enumerator;

import io.github.hectorvent.floci.core.common.ResourceUsageEnumerator;
import io.github.hectorvent.floci.core.common.UsageLine;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Enumerates running EC2 instances as Cost Explorer usage lines.
 * Emits one {@code Hrs} line per instance per region, sized to the requested period.
 */
@ApplicationScoped
public class Ec2UsageEnumerator implements ResourceUsageEnumerator {

    static final String SERVICE_CODE = "AmazonEC2";

    private final Ec2Service ec2;

    @Inject
    public Ec2UsageEnumerator(Ec2Service ec2) {
        this.ec2 = ec2;
    }

    @Override
    public Stream<UsageLine> enumerate(Instant periodStart, Instant periodEnd, String region) {
        List<Reservation> reservations = ec2.describeInstances(region, List.of(), Map.of());
        Stream.Builder<UsageLine> out = Stream.builder();
        // Use seconds with fractional-hour conversion so HOURLY queries with
        // non-aligned windows (e.g. 90 minutes) and sub-hour windows still
        // surface the correct partial-hour quantity.
        long periodSeconds = Duration.between(periodStart, periodEnd).toSeconds();
        if (periodSeconds <= 0) {
            return Stream.empty();
        }
        double hours = periodSeconds / 3600.0;
        // Always emit a zero-quantity catalog line so the service surfaces in
        // GetDimensionValues SERVICE even when no instances are running.
        out.add(catalogLine(periodStart, periodEnd, region));
        for (Reservation reservation : reservations) {
            String accountId = reservation.getOwnerId() != null ? reservation.getOwnerId() : "000000000000";
            for (Instance instance : reservation.getInstances()) {
                // AWS only bills BoxUsage hours for instances in the running state.
                // pending / stopping / stopped / shutting-down / terminated incur no
                // instance-hour cost (storage and other dimensions are billed separately
                // and are out of scope for this enumerator).
                if (instance.getState() == null
                        || !"running".equalsIgnoreCase(instance.getState().getName())) {
                    continue;
                }
                String instanceType = instance.getInstanceType();
                if (instanceType == null || instanceType.isEmpty()) {
                    continue;
                }
                String usageType = "BoxUsage:" + instanceType;
                Map<String, String> tags = collectTags(instance);
                out.add(new UsageLine(
                        periodStart, periodEnd,
                        SERVICE_CODE, region,
                        usageType, "RunInstances",
                        UsageLine.RECORD_TYPE_USAGE,
                        accountId,
                        instance.getInstanceId(),
                        tags,
                        hours,
                        "Hrs"));
            }
        }
        return out.build();
    }

    private static UsageLine catalogLine(Instant start, Instant end, String region) {
        return new UsageLine(start, end, SERVICE_CODE, region,
                "BoxUsage", "RunInstances",
                UsageLine.RECORD_TYPE_USAGE, "000000000000", null, Map.of(),
                0.0, "Hrs");
    }

    private static Map<String, String> collectTags(Instance instance) {
        Map<String, String> tags = new HashMap<>();
        if (instance.getTags() != null) {
            for (Tag tag : instance.getTags()) {
                if (tag.getKey() != null) {
                    tags.put(tag.getKey(), tag.getValue() == null ? "" : tag.getValue());
                }
            }
        }
        return tags;
    }
}
