package io.github.hectorvent.floci.core.common;

import java.time.Instant;
import java.util.stream.Stream;

/**
 * SPI for any Floci service that wants to participate in Cost Explorer / CUR
 * cost-and-usage reporting.
 * <p>
 * Implementations are discovered by CDI. Drop a class annotated
 * {@code @ApplicationScoped} that implements this interface anywhere on the
 * classpath; Cost Explorer auto-injects it via {@code Instance<>}. There is no
 * central registration step.
 *
 * <p>An implementation enumerates the resource state held by its service and
 * emits one {@link UsageLine} per (resource, region, usage-type, day) cell that
 * was active during the requested period. Lines for unpriced services should
 * still be emitted with {@code quantity = 0} — that keeps the service visible
 * in {@code GetDimensionValues}({@code SERVICE}) responses without contributing
 * to billed cost.
 *
 * <p>Implementations should be cheap to call repeatedly: Cost Explorer invokes
 * {@link #enumerate} per request and does not cache across calls. Implementations
 * are responsible for honoring {@code region} themselves; the caller does not
 * pre-filter.
 *
 * <p><b>Usage-type contract:</b> future enumerators should emit AWS-native usage
 * types (e.g. {@code BoxUsage:t3.micro}, {@code TimedStorage-Standard},
 * {@code AWS-Lambda-Requests}) that match the {@code usagetype} attribute carried
 * on the corresponding product offer in the Pricing snapshot. The fallback
 * synthesis path in {@code PricingRateLookup} is compatibility-only for
 * incomplete external snapshots supplied via
 * {@code FLOCI_SERVICES_PRICING_SNAPSHOT_PATH} that omit {@code usagetype}; new
 * code should not rely on it.
 */
public interface ResourceUsageEnumerator {

    /**
     * Returns the usage rows for {@code [periodStart, periodEnd)} in {@code region}.
     * The boundaries are half-open: {@code periodStart} inclusive, {@code periodEnd}
     * exclusive. Both are UTC instants aligned to a day boundary by the caller.
     *
     * <p>The stream is consumed eagerly by Cost Explorer; implementations may
     * return a closed stream once exhausted. Empty stream is valid (and is the
     * default for services with no resources in {@code region}).
     */
    Stream<UsageLine> enumerate(Instant periodStart, Instant periodEnd, String region);
}
