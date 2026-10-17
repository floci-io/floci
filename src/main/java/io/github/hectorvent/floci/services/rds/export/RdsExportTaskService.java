package io.github.hectorvent.floci.services.rds.export;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.ExportTask;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * RDS snapshot exports to Amazon S3: {@code StartExportTask}, {@code DescribeExportTasks} and
 * {@code CancelExportTask}. A task is {@code STARTING} while its snapshot is restored,
 * {@code IN_PROGRESS} while its tables are written, and then {@code COMPLETE} or {@code FAILED}.
 * A cancel moves it to {@code CANCELING} until the worker stops before its next table, then to
 * {@code CANCELED}, and leaves what was already written in the bucket, as RDS does.
 *
 * <p>Deviations, documented in {@code docs/services/rds.md}: the IAM role is not assumed and the
 * KMS key does not encrypt anything, both are recorded as given; only PostgreSQL snapshots can be
 * exported, since Floci's snapshots hold PostgreSQL data only; and a live cluster cannot be the
 * source.
 */
@ApplicationScoped
public class RdsExportTaskService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RdsExportTaskService.class);

    static final String RESTART_FAILURE_CAUSE = "The emulator restarted before the export finished";
    static final long RESET_DRAIN_TIMEOUT_MILLIS = 5_000L;
    private static final long RESET_ADMISSION_WAIT_MILLIS = 30_000L;
    static final long GIB = 1_073_741_824L;
    private static final String KEY_PREFIX = "export-task::";

    // Letters, digits and single hyphens, starting with a letter and not ending with a hyphen.
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z](?:-?[A-Za-z0-9])*");
    private static final int IDENTIFIER_MAX_LENGTH = 60;
    private static final Set<String> FILTER_NAMES =
            Set.of("export-task-identifier", "s3-bucket", "source-arn", "status");

    private final StorageBackend<String, ExportTask> tasks;
    private final RdsService rdsService;
    private final S3Service s3Service;
    private final SnapshotExporter exporter;
    private final RegionResolver regionResolver;
    private final boolean mock;
    private final Clock clock;
    private final Executor workers;
    private final Object lock = new Object();
    // Reset support, following SesExportJobService: beforeReset bumps the generation so a running
    // worker stops writing, then waits for the workers already running, for a bounded time. A
    // StartExportTask arriving meanwhile waits for afterReset rather than being wiped by it.
    private final AtomicInteger generation = new AtomicInteger();
    private boolean resetting;
    private final Object workerMonitor = new Object();
    private int activeWorkers;

    @Inject
    public RdsExportTaskService(StorageFactory storageFactory, RdsService rdsService, S3Service s3Service,
                                UnsupportedSnapshotExporter exporter, RegionResolver regionResolver,
                                EmulatorConfig config, Clock clock) {
        this(storageFactory.create("rds", "rds-export-tasks.json",
                        new TypeReference<Map<String, ExportTask>>() {}),
                rdsService, s3Service, exporter, regionResolver, config.services().rds().mock(), clock,
                task -> Thread.ofVirtual().name("rds-export").start(task));
    }

    RdsExportTaskService(StorageBackend<String, ExportTask> tasks, RdsService rdsService, S3Service s3Service,
                         SnapshotExporter exporter, RegionResolver regionResolver, boolean mock, Clock clock,
                         Executor workers) {
        this.tasks = tasks;
        this.rdsService = rdsService;
        this.s3Service = s3Service;
        this.exporter = exporter;
        this.regionResolver = regionResolver;
        this.mock = mock;
        this.clock = clock;
        this.workers = workers;
        recoverInterruptedTasks();
    }

    public ExportTask startExportTask(String exportTaskIdentifier, String sourceArn, String s3BucketName,
                                      String s3Prefix, String iamRoleArn, String kmsKeyId,
                                      List<String> exportOnly, String region) {
        String effectiveRegion = effectiveRegion(region);
        String accountId = regionResolver.getAccountId();
        if (exportTaskIdentifier.length() > IDENTIFIER_MAX_LENGTH
                || !IDENTIFIER.matcher(exportTaskIdentifier).matches()) {
            throw new AwsException("InvalidParameterValue", "The export task identifier " + exportTaskIdentifier
                    + " is not valid: use 1 to 60 letters, digits or single hyphens, starting with a letter"
                    + " and not ending with a hyphen.", 400);
        }
        ExportOnlySelection.of(exportOnly);
        SnapshotExportSource source = rdsService.snapshotExportSource(sourceArn, effectiveRegion);
        if (!s3Service.bucketExists(s3BucketName)) {
            throw new AwsException("InvalidS3BucketFault",
                    "The S3 bucket " + s3BucketName + " doesn't exist.", 400);
        }
        String bucketRegion = s3Service.getBucketRegion(s3BucketName);
        if (bucketRegion == null || bucketRegion.isBlank()) {
            bucketRegion = regionResolver.getDefaultRegion();
        }
        boolean bucketInAnotherRegion = !bucketRegion.equalsIgnoreCase(effectiveRegion);

        ExportTask task = new ExportTask();
        task.setExportTaskIdentifier(exportTaskIdentifier);
        task.setSourceArn(source.sourceArn());
        task.setSourceType(ExportTask.SOURCE_TYPE_SNAPSHOT);
        task.setExportOnly(exportOnly);
        task.setSnapshotTime(source.snapshotTime());
        task.setTaskStartTime(Instant.now(clock));
        task.setS3Bucket(s3BucketName);
        task.setS3Prefix(s3Prefix);
        task.setIamRoleArn(iamRoleArn);
        task.setKmsKeyId(kmsKeyId);
        task.setStatus(ExportTask.STATUS_STARTING);
        task.setRegion(effectiveRegion);
        task.setAccountId(accountId);
        task.setRuntimeArn(regionResolver.buildArn("rds", effectiveRegion, accountId,
                "export-task:" + exportTaskIdentifier));
        task.setRuntimeVolumeId("export-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));

        synchronized (lock) {
            awaitResetFinished();
            if (tasks.get(key(effectiveRegion, exportTaskIdentifier)).isPresent()) {
                throw new AwsException("ExportTaskAlreadyExists",
                        "The export task with the ID " + exportTaskIdentifier + " already exists.", 400);
            }
            tasks.put(key(effectiveRegion, exportTaskIdentifier), task);
            ExportTask started = new ExportTask(task);
            int startedIn = generation.get();
            // The worker runs on a fresh thread, where account-aware storage would otherwise fall
            // back to the default account and never find this task again.
            workers.execute(() -> RequestScopes.runAs(accountId, effectiveRegion,
                    () -> run(effectiveRegion, exportTaskIdentifier, source, bucketInAnotherRegion, startedIn)));
            return started;
        }
    }

    public List<ExportTask> describeExportTasks(String exportTaskIdentifier, String sourceArn, String sourceType,
                                                Map<String, List<String>> filters, String region) {
        String effectiveRegion = effectiveRegion(region);
        for (String name : filters.keySet()) {
            if (!FILTER_NAMES.contains(name)) {
                throw new AwsException("InvalidParameterValue", "Unrecognized filter name: " + name, 400);
            }
        }
        if (exportTaskIdentifier != null && !exportTaskIdentifier.isBlank()) {
            requireTask(effectiveRegion, exportTaskIdentifier);
        }
        List<ExportTask> found;
        synchronized (lock) {
            found = tasks.scan(key -> key.startsWith(KEY_PREFIX + effectiveRegion + "::")).stream()
                    .map(ExportTask::new)
                    .toList();
        }
        return found.stream()
                .filter(task -> matches(exportTaskIdentifier, task.getExportTaskIdentifier()))
                .filter(task -> matches(sourceArn, task.getSourceArn()))
                .filter(task -> matches(sourceType, task.getSourceType()))
                .filter(task -> matchesFilters(task, filters))
                .sorted(Comparator.comparing(ExportTask::getTaskStartTime))
                .toList();
    }

    public ExportTask cancelExportTask(String exportTaskIdentifier, String region) {
        String effectiveRegion = effectiveRegion(region);
        synchronized (lock) {
            ExportTask task = requireTask(effectiveRegion, exportTaskIdentifier);
            if (task.isTerminal() || ExportTask.STATUS_CANCELING.equals(task.getStatus())) {
                throw new AwsException("InvalidExportTaskStateFault",
                        "The export task with the ID " + exportTaskIdentifier + " can't be canceled because its "
                                + "status is " + task.getStatus() + ".", 400);
            }
            task.setStatus(ExportTask.STATUS_CANCELING);
            tasks.put(key(effectiveRegion, exportTaskIdentifier), task);
            return new ExportTask(task);
        }
    }

    // Holds lock. A start arriving while a reset drains would capture the already-bumped generation,
    // so its task would be wiped by clear() after it was admitted. It waits for afterReset.
    private void awaitResetFinished() {
        long deadline = System.currentTimeMillis() + RESET_ADMISSION_WAIT_MILLIS;
        while (resetting) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw resettingNow();
            }
            try {
                lock.wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw resettingNow();
            }
        }
    }

    private static AwsException resettingNow() {
        return new AwsException("Throttling", "Export tasks are not accepted while the emulator is resetting.", 429);
    }

    private void run(String region, String taskId, SnapshotExportSource source, boolean bucketInAnotherRegion,
                     int startedIn) {
        synchronized (workerMonitor) {
            activeWorkers++;
        }
        try {
            runTask(region, taskId, source, bucketInAnotherRegion, startedIn);
        } finally {
            synchronized (workerMonitor) {
                activeWorkers--;
                workerMonitor.notifyAll();
            }
        }
    }

    private void runTask(String region, String taskId, SnapshotExportSource source, boolean bucketInAnotherRegion,
                         int startedIn) {
        Optional<ExportTask> current = tasks.get(key(region, taskId));
        if (current.isEmpty() || current.get().isTerminal() || generation.get() != startedIn) {
            return;
        }
        if (ExportTask.STATUS_CANCELING.equals(current.get().getStatus())) {
            // Canceled before its worker started: nothing to restore, nothing to stop.
            finish(region, taskId, startedIn, ExportTask.STATUS_CANCELED, null, null);
            return;
        }
        if (bucketInAnotherRegion) {
            finish(region, taskId, startedIn, ExportTask.STATUS_FAILED, "The S3 bucket "
                    + current.get().getS3Bucket() + " isn't located in the current AWS Region. Review your S3 bucket"
                    + " name and retry the export.", null);
            return;
        }
        if (mock) {
            finish(region, taskId, startedIn, ExportTask.STATUS_COMPLETE, null,
                    new SnapshotExporter.Outcome(0, List.of(), Instant.now(clock)));
            return;
        }
        SnapshotExporter.Progress progress = new SnapshotExporter.Progress() {
            @Override
            public void extracting(int percent) {
                synchronized (lock) {
                    if (abandonedByReset()) {
                        return;
                    }
                    tasks.get(key(region, taskId))
                            .filter(task -> !task.isTerminal() && !ExportTask.STATUS_CANCELING.equals(task.getStatus()))
                            .ifPresent(task -> {
                                task.setStatus(ExportTask.STATUS_IN_PROGRESS);
                                task.setPercentProgress(percent);
                                tasks.put(key(region, taskId), task);
                            });
                }
            }

            @Override
            public boolean canceled() {
                return abandonedByReset() || tasks.get(key(region, taskId))
                        .map(task -> ExportTask.STATUS_CANCELING.equals(task.getStatus()) || task.isTerminal())
                        .orElse(true);
            }

            @Override
            public boolean abandonedByReset() {
                return generation.get() != startedIn;
            }
        };
        try {
            SnapshotExporter.Outcome outcome = exporter.export(current.get(), source, progress);
            finish(region, taskId, startedIn, ExportTask.STATUS_COMPLETE, null, outcome);
        } catch (RuntimeException e) {
            LOG.warnv(e, "RDS export task {0} failed", taskId);
            finish(region, taskId, startedIn, ExportTask.STATUS_FAILED,
                    e.getMessage() != null ? e.getMessage() : e.toString(), null);
        }
    }

    /** A task canceled while it ran ends {@code CANCELED} whatever its worker reached. */
    private void finish(String region, String taskId, int startedIn, String status, String failureCause,
                        SnapshotExporter.Outcome outcome) {
        synchronized (lock) {
            if (generation.get() != startedIn) {
                LOG.infov("RDS export task {0} abandoned by an emulator reset", taskId);
                return;
            }
            Optional<ExportTask> current = tasks.get(key(region, taskId));
            if (current.isEmpty() || current.get().isTerminal()) {
                return;
            }
            ExportTask task = current.get();
            if (ExportTask.STATUS_CANCELING.equals(task.getStatus())) {
                task.setStatus(ExportTask.STATUS_CANCELED);
            } else {
                task.setStatus(status);
                task.setFailureCause(failureCause);
                if (ExportTask.STATUS_COMPLETE.equals(status)) {
                    task.setPercentProgress(100);
                }
            }
            if (outcome != null) {
                task.setTotalExtractedDataInGb((int) (outcome.bytesExtracted() / GIB));
                if (!outcome.warnings().isEmpty()) {
                    task.setWarningMessage(String.join(" ", outcome.warnings()));
                }
            }
            task.setTaskEndTime(outcome != null && outcome.finishedAt() != null
                    ? outcome.finishedAt() : Instant.now(clock));
            tasks.put(key(region, taskId), task);
        }
    }

    private ExportTask requireTask(String region, String taskId) {
        return tasks.get(key(region, taskId))
                .orElseThrow(() -> new AwsException("ExportTaskNotFound",
                        "The export task with the ID " + taskId + " doesn't exist.", 404));
    }

    private static boolean matches(String wanted, String actual) {
        return wanted == null || wanted.isBlank() || wanted.equals(actual);
    }

    private static boolean matchesFilters(ExportTask task, Map<String, List<String>> filters) {
        for (Map.Entry<String, List<String>> filter : filters.entrySet()) {
            List<String> values = filter.getValue();
            if (values.isEmpty()) {
                continue;
            }
            boolean matched = switch (filter.getKey()) {
                case "export-task-identifier" -> values.contains(task.getExportTaskIdentifier());
                case "s3-bucket" -> values.contains(task.getS3Bucket());
                case "source-arn" -> values.contains(task.getSourceArn());
                case "status" -> values.stream()
                        .anyMatch(value -> value.toUpperCase(Locale.ROOT).equals(task.getStatus()));
                default -> throw new IllegalStateException("Unvalidated filter name: " + filter.getKey());
            };
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private String effectiveRegion(String region) {
        return region != null && !region.isBlank() ? region : regionResolver.getDefaultRegion();
    }

    private static String key(String region, String taskId) {
        return KEY_PREFIX + region + "::" + taskId;
    }

    /**
     * Runs from the constructor, where no request scope is active, so the store has to be read
     * across accounts: a plain scan would only ever sweep the default account's tasks.
     */
    private void recoverInterruptedTasks() {
        if (!(tasks instanceof AccountAwareStorageBackend<ExportTask> accountTasks)) {
            return;
        }
        for (AccountAwareStorageBackend.AccountEntry<ExportTask> entry
                : accountTasks.scanAllAccountEntries(k -> k.startsWith(KEY_PREFIX))) {
            ExportTask task = entry.value();
            if (task.isTerminal()) {
                continue;
            }
            if (ExportTask.STATUS_CANCELING.equals(task.getStatus())) {
                task.setStatus(ExportTask.STATUS_CANCELED);
            } else {
                task.setStatus(ExportTask.STATUS_FAILED);
                task.setFailureCause(RESTART_FAILURE_CAUSE);
            }
            task.setTaskEndTime(Instant.now(clock));
            accountTasks.putForAccount(entry.accountId(), entry.key(), task);
            LOG.warnv("RDS export task {0} was interrupted by a restart and is now {1}",
                    task.getExportTaskIdentifier(), task.getStatus());
            if (!mock) {
                try {
                    exporter.discard(task);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Could not remove what RDS export task {0} left behind",
                            task.getExportTaskIdentifier());
                }
            }
        }
    }

    /**
     * Forces the bean, which is otherwise created on first use, at startup, so a task a restart
     * interrupted is failed and its restore container and volume removed straight away rather than
     * when a client first calls an export action.
     */
    void onStart(@Observes StartupEvent ignored) {
        LOG.debugv("RDS export task service ready");
    }

    @Override
    public void beforeReset() {
        synchronized (lock) {
            resetting = true;
            generation.incrementAndGet();
        }
        boolean interrupted = false;
        long deadline = System.currentTimeMillis() + RESET_DRAIN_TIMEOUT_MILLIS;
        synchronized (workerMonitor) {
            while (activeWorkers > 0) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    LOG.warnv("{0} RDS export worker(s) still running after {1}ms",
                            activeWorkers, RESET_DRAIN_TIMEOUT_MILLIS);
                    break;
                }
                try {
                    workerMonitor.wait(remaining);
                } catch (InterruptedException e) {
                    // Keep draining to the deadline; the interrupt is re-asserted on the way out.
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void afterReset() {
        synchronized (lock) {
            resetting = false;
            lock.notifyAll();
        }
    }

    @Override
    public void clear() {
        tasks.clear();
        LOG.info("Cleared all RDS export tasks");
    }
}
