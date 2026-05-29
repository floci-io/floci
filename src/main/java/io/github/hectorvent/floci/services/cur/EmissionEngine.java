package io.github.hectorvent.floci.services.cur;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ResourceUsageEnumerator;
import io.github.hectorvent.floci.core.common.UsageLine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared emission entrypoint for CUR and BCM Data Exports.
 * <p>
 * Collects {@link UsageLine}s from every {@link ResourceUsageEnumerator}
 * registered with CDI (the same SPI that backs Cost Explorer), then delegates
 * the Parquet write to {@link ParquetEmitter}. Both CUR's
 * {@code PutReportDefinition} hook and BCM's {@code CreateExport} hook call
 * through this class so the cost-line gathering logic lives in exactly one
 * place.
 *
 * <p>Window resolution: emission runs span the calendar month of {@code now},
 * matching the CUR billing period. Future PRs can extend this to honor
 * {@code TimeUnit=DAILY} or {@code HOURLY}.
 */
@ApplicationScoped
public class EmissionEngine {

    private static final Logger LOG = Logger.getLogger(EmissionEngine.class);

    private final Instance<ResourceUsageEnumerator> enumerators;
    private final ParquetEmitter parquetEmitter;
    private final RegionResolver regionResolver;

    @Inject
    public EmissionEngine(Instance<ResourceUsageEnumerator> enumerators,
                          ParquetEmitter parquetEmitter,
                          RegionResolver regionResolver) {
        this.enumerators = enumerators;
        this.parquetEmitter = parquetEmitter;
        this.regionResolver = regionResolver;
    }

    /**
     * Emits a Parquet artifact covering the current calendar month into the
     * configured destination. Returns the resulting {@link ParquetEmitter.Result}.
     */
    public ParquetEmitter.Result emitForCurrentMonth(String reportName, String destBucket,
                                                      String destPrefix, String region) {
        return emitForCurrentMonth(reportName, destBucket, destPrefix, region, null);
    }

    /**
     * Variant of {@link #emitForCurrentMonth(String, String, String, String)}
     * that runs the DuckDB-side S3 read/write under the given owning account.
     * Daily emissions outside a request scope must thread {@code ownerAccountId}
     * here so the Parquet artifact lands in the correct account-aware S3
     * partition (and not the default account's).
     */
    public ParquetEmitter.Result emitForCurrentMonth(String reportName, String destBucket,
                                                      String destPrefix, String region,
                                                      String ownerAccountId) {
        Instant now = Instant.now();
        YearMonth ym = YearMonth.from(now.atOffset(ZoneOffset.UTC));
        Instant start = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        String resolvedRegion = (region == null || region.isEmpty())
                ? regionResolver.getDefaultRegion() : region;

        List<UsageLine> lines = collectLines(start, end, resolvedRegion);
        LOG.infov("Emission: report={0} destination=s3://{1}/{2} lines={3} owner={4}",
                reportName, destBucket, destPrefix, lines.size(), ownerAccountId);
        return parquetEmitter.emit(reportName, destBucket, destPrefix, lines, ownerAccountId);
    }

    private List<UsageLine> collectLines(Instant start, Instant end, String region) {
        List<UsageLine> all = new ArrayList<>();
        for (ResourceUsageEnumerator enumerator : enumerators) {
            try {
                enumerator.enumerate(start, end, region).forEach(all::add);
            } catch (Exception e) {
                LOG.warnv(e, "Enumerator {0} failed during emission",
                        enumerator.getClass().getSimpleName());
            }
        }
        return all;
    }
}
