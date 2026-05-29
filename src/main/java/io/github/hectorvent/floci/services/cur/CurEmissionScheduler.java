package io.github.hectorvent.floci.services.cur;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.bcmdataexports.BcmDataExportsService;
import io.github.hectorvent.floci.services.bcmdataexports.model.DestinationConfiguration;
import io.github.hectorvent.floci.services.bcmdataexports.model.Export;
import io.github.hectorvent.floci.services.bcmdataexports.model.ExportExecution;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.cur.model.ReportDefinition;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CUR / BCM Data Exports emission orchestrator.
 * <p>
 * Two run modes, controlled separately for CUR ({@code FLOCI_SERVICES_CUR_EMIT_MODE})
 * and BCM ({@code FLOCI_SERVICES_BCM_DATA_EXPORTS_EMIT_MODE}):
 * <ul>
 *   <li>{@code synchronous} (default): emit on every report/export mutation
 *       (called inline from the management plane).</li>
 *   <li>{@code daily}: emit every 24h via a CUR-owned single-thread
 *       {@link ScheduledExecutorService}.</li>
 *   <li>{@code off}: never emit; management plane only.</li>
 * </ul>
 *
 * <p>This class is intentionally separate from
 * {@code services.scheduler.ScheduleDispatcher}: that dispatcher is the
 * EventBridge Scheduler target invoker visible to user-facing schedules and
 * pipes, and conflating internal billing-export jobs with it would couple
 * implementation to an unrelated user surface.
 */
@Startup
@ApplicationScoped
public class CurEmissionScheduler {

    private static final Logger LOG = Logger.getLogger(CurEmissionScheduler.class);
    private static final long DAILY_INTERVAL_SECONDS = 24L * 60L * 60L;
    private static final long DAILY_INITIAL_DELAY_SECONDS = 5L;

    private final EmulatorConfig config;
    private final CurService curService;
    private final BcmDataExportsService bcmService;
    private final EmissionEngine engine;

    private ScheduledExecutorService executor;

    @Inject
    public CurEmissionScheduler(EmulatorConfig config,
                                CurService curService,
                                BcmDataExportsService bcmService,
                                EmissionEngine engine) {
        this.config = config;
        this.curService = curService;
        this.bcmService = bcmService;
        this.engine = engine;
    }

    @PostConstruct
    void start() {
        boolean curDaily = "daily".equals(config.services().cur().emitMode());
        boolean bcmDaily = "daily".equals(config.services().bcmDataExports().emitMode());
        if (!curDaily && !bcmDaily) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cur-emission-scheduler");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::runDaily,
                DAILY_INITIAL_DELAY_SECONDS, DAILY_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.infov("CUR daily emission scheduler started (cur={0}, bcm={1})", curDaily, bcmDaily);
    }

    void stop(@Observes ShutdownEvent event) {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Synchronous emission hook for CUR. Called by {@link CurService} after
     * {@code PutReportDefinition} / {@code ModifyReportDefinition} when
     * {@code emit-mode=synchronous}; in {@code daily} mode the periodic loop
     * handles emission instead.
     */
    public void emitForReportSync(ReportDefinition definition, String region) {
        if (!"synchronous".equals(config.services().cur().emitMode())) {
            return;
        }
        emitForReport(definition, region);
    }

    /**
     * Synchronous emission hook for BCM Data Exports. Records an
     * {@link ExportExecution} bracketing the emission so callers can poll
     * {@code GetExecution} for the result.
     */
    public ExportExecution emitForExportSync(Export export, String region, String createdBy) {
        if (!"synchronous".equals(config.services().bcmDataExports().emitMode())) {
            return null;
        }
        return emitForExport(export, region, createdBy);
    }

    /**
     * Periodic loop body. Catches all exceptions so the scheduler stays alive.
     * Iterates every account that owns a report or export — necessary because
     * the loop runs outside any request scope, so the storage backend can't
     * resolve the calling account by itself.
     */
    private void runDaily() {
        try {
            if ("daily".equals(config.services().cur().emitMode())) {
                for (Map.Entry<String, java.util.List<ReportDefinition>> perAccount
                        : curService.listAllReportsByAccount().entrySet()) {
                    String accountId = perAccount.getKey();
                    for (ReportDefinition def : perAccount.getValue()) {
                        try {
                            runUnderAccount(accountId,
                                    () -> { emitForReport(accountId, def, def.getS3Region()); return null; });
                        } catch (RuntimeException e) {
                            LOG.warnv(e, "Daily CUR emission failed for {0} (account={1})",
                                    def.getReportName(), accountId);
                        }
                    }
                }
            }
            if ("daily".equals(config.services().bcmDataExports().emitMode())) {
                for (Map.Entry<String, java.util.List<Export>> perAccount
                        : bcmService.listAllExportsByAccount().entrySet()) {
                    String accountId = perAccount.getKey();
                    for (Export export : perAccount.getValue()) {
                        try {
                            runUnderAccount(accountId,
                                    () -> { emitForExport(accountId, export, exportRegionOf(export), "SCHEDULE"); return null; });
                        } catch (RuntimeException e) {
                            LOG.warnv(e, "Daily BCM emission failed for {0} (account={1})",
                                    export.getExportArn(), accountId);
                        }
                    }
                }
            }
        } catch (RuntimeException outer) {
            LOG.errorv(outer, "Daily emission loop failed");
        }
    }

    /**
     * Activates a synthetic CDI request scope with
     * {@link RequestContext#setAccountId} set to {@code accountId}, runs
     * {@code body}, and terminates the scope.
     *
     * <p>The Java-side staging writes that {@link ParquetEmitter} performs
     * (NDJSON staging into {@code floci-cur-staging} via {@code S3Service},
     * cleanup of that staged object) flow through
     * {@code AccountAwareStorageBackend}, which reads the calling account from
     * the active request context. The daily scheduler runs outside any
     * incoming HTTP request and would otherwise fall back to the configured
     * default account — meanwhile the DuckDB-side reads/writes already use
     * the explicit owner account passed via {@code FlociDuckClient}, so the
     * two sides would mismatch and DuckDB would 404 on the staging object.
     * Activating a request scope here keeps both sides on the same account.
     */
    private void runUnderAccount(String accountId, java.util.function.Supplier<Void> body) {
        ManagedContext requestContext = Arc.container().requestContext();
        boolean alreadyActive = requestContext.isActive();
        if (!alreadyActive) {
            requestContext.activate();
        }
        try {
            RequestContext ctx = Arc.container().instance(RequestContext.class).get();
            ctx.setAccountId(accountId);
            body.get();
        } finally {
            if (!alreadyActive) {
                requestContext.terminate();
            }
        }
    }

    private void emitForReport(ReportDefinition definition, String region) {
        // ownerAccountId is set by CurService at PutReportDefinition time, so
        // it always reflects the request account that created the report.
        emitForReport(definition.getOwnerAccountId(), definition, region);
    }

    private void emitForReport(String accountId, ReportDefinition definition, String region) {
        try {
            engine.emitForCurrentMonth(
                    definition.getReportName(),
                    definition.getS3Bucket(),
                    definition.getS3Prefix(),
                    region,
                    accountId);
            if (accountId != null) {
                curService.markReportStatusForAccount(accountId,
                        definition.getReportName(), region, "SUCCESS");
            } else {
                curService.markReportStatus(definition.getReportName(), region, "SUCCESS");
            }
        } catch (RuntimeException e) {
            if (accountId != null) {
                curService.markReportStatusForAccount(accountId,
                        definition.getReportName(), region, "ERROR");
            } else {
                curService.markReportStatus(definition.getReportName(), region, "ERROR");
            }
            throw e;
        }
    }

    private ExportExecution emitForExport(Export export, String region, String createdBy) {
        String accountId = export.getOwnerAccountId() != null
                ? export.getOwnerAccountId()
                : bcmService.accountIdFromArn(export.getExportArn());
        return emitForExport(accountId, export, region, createdBy);
    }

    private ExportExecution emitForExport(String accountId, Export export, String region, String createdBy) {
        ExportExecution exec = bcmService.recordExecution(accountId, export.getExportArn(), createdBy);
        try {
            DestinationConfiguration.S3Destination dest =
                    export.getDestinationConfigurations().getS3Destination();
            engine.emitForCurrentMonth(
                    export.getName(),
                    dest.getS3Bucket(),
                    dest.getS3Prefix(),
                    dest.getS3Region() != null ? dest.getS3Region() : region,
                    accountId);
            bcmService.completeExecution(accountId, exec, true, null);
            return exec;
        } catch (RuntimeException e) {
            bcmService.completeExecution(accountId, exec, false, e.getMessage());
            throw e;
        }
    }

    private String exportRegionOf(Export export) {
        if (export.getDestinationConfigurations() == null
                || export.getDestinationConfigurations().getS3Destination() == null) {
            return null;
        }
        return export.getDestinationConfigurations().getS3Destination().getS3Region();
    }
}
