package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrNotebookExecution;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import io.floci.aws.services.emrserverless.SparkJobRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notebook executions on EMR clusters (Amazon EMR Management Guide, "Sample programmatic commands for EMR Notebooks").
 * In the immediate runner mode Floci keeps an execution and its lifecycle but runs no notebook: it is RUNNING from the
 * moment it starts and FINISHED once the cluster's step duration ({@code step-duration-seconds}) has passed, unless it
 * is stopped first or its cluster shuts down, and writes no output notebook. In the docker runner mode it runs the
 * notebook on its cluster ({@link EmrNotebookRunner}, {@link EmrService#runOnCluster}) and writes it as run. Executions
 * are kept apart from their clusters.
 */
@ApplicationScoped
public class EmrNotebookExecutionService {

    static final String START_PENDING = "START_PENDING";
    static final String RUNNING = "RUNNING";
    static final String FINISHED = "FINISHED";
    static final String FAILED = "FAILED";
    static final String STOPPED = "STOPPED";
    static final String STOPPING = "STOPPING";
    static final String INTERRUPTED = "Floci stopped while the notebook execution was running.";
    static final String NO_NOTEBOOK = "Execution failed for cluster %s: Floci keeps no EMR Notebook's S3 location, so"
            + " it runs a notebook only from its NotebookS3Location.";
    static final String CELL_FAILED = "Execution failed for cluster %s: a cell of the notebook raised an error, which"
            + " the output notebook holds.";
    // Its run failed otherwise, before a cell ran or as the notebook as run was written, which may then be missing.
    static final String RUN_FAILED = "Execution failed for cluster %s: the notebook's run exited with code %s, as"
            + " Floci's log says.";
    static final List<String> STATUSES = List.of(START_PENDING, "STARTING", RUNNING, "FINISHING", FINISHED,
            "FAILING", FAILED, "STOP_PENDING", "STOPPING", STOPPED);
    private static final Set<String> ENDED = Set.of(FINISHED, FAILED, STOPPED);
    static final List<String> ENGINE_TYPES = List.of("EMR");
    static final List<String> OUTPUT_FORMATS = List.of("HTML");
    // "A maximum of 100 concurrent executions are supported per AWS Region per account."
    static final int MAX_CONCURRENT_EXECUTIONS = 100;
    // "An execution is terminated if it runs for more than 30 days."
    static final Duration MAX_RUN = Duration.ofDays(30);
    // ListNotebookExecutions "Returns a maximum of 50 notebook executions", and From's default "is the timestamp of 30
    // days ago".
    static final int PAGE_SIZE = 50;
    static final Duration DEFAULT_LISTING_WINDOW = Duration.ofDays(30);
    private static final int ID_LENGTH = 29;
    // A notebook execution's ARN's resource, before its id.
    static final String EXECUTION_RESOURCE = "notebook-execution/";
    private static final int MAX_NAME_LENGTH = 256;

    private final AccountAwareStorageBackend<EmrNotebookExecution> store;
    private final EmrService clusters;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final Duration runDuration;
    // The executions whose notebook a thread of this process runs; one not here that has not ended was left by a
    // restart.
    private final Set<String> running = new HashSet<>();

    @Inject
    public EmrNotebookExecutionService(StorageFactory storageFactory, EmrService clusters,
                                       RegionResolver regionResolver, EmulatorConfig config) {
        this(storageFactory.create("emr", "emr-notebook-executions.json",
                        new TypeReference<Map<String, EmrNotebookExecution>>() {}),
                clusters, regionResolver, Clock.systemUTC(),
                Duration.ofSeconds(config.services().emr().stepDurationSeconds()));
    }

    EmrNotebookExecutionService(AccountAwareStorageBackend<EmrNotebookExecution> store, EmrService clusters,
                                RegionResolver regionResolver, Clock clock, Duration runDuration) {
        this.store = store;
        this.clusters = clusters;
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.runDuration = runDuration;
    }

    /**
     * Starts a notebook execution on a cluster. The model's required members and enums are checked first, with the
     * ValidationException AWS's validation layer answers. Then the cluster, which must be RUNNING or WAITING and meet
     * the EMR Notebooks cluster requirements: "Only clusters created using Amazon EMR release version 5.18.0 and later
     * are supported", "The cluster must be launched with Hadoop, Spark, and Livy" and, "For Amazon EMR release
     * versions 5.32.0 and later, or 6.2.0 and later", Jupyter Enterprise Gateway too (the AWS runtime for Apache
     * Spark's releases name no Hadoop among their applications); and at most {@link #MAX_CONCURRENT_EXECUTIONS} that
     * have not ended. Each of these refusals is InvalidRequestException in Floci's own wording, as AWS's is not
     * recorded.
     */
    public synchronized EmrNotebookExecution start(EmrNotebookExecution requested) {
        List<String> violations = new ArrayList<>();
        if (requested.getEngineId() == null) {
            violations.add(SmithyValidationErrors.notNull("executionEngine.id"));
        }
        if (requested.getServiceRole() == null) {
            violations.add(SmithyValidationErrors.notNull("serviceRole"));
        }
        EmrService.checkEnum(violations, "executionEngine.type", requested.getEngineType(), ENGINE_TYPES);
        EmrService.checkEnum(violations, "outputNotebookFormat", requested.getOutputNotebookFormat(), OUTPUT_FORMATS);
        if (requested.getName() != null && requested.getName().length() > MAX_NAME_LENGTH) {
            violations.add(SmithyValidationErrors.lengthOutOfRange("notebookExecutionName", requested.getName(), 0,
                    MAX_NAME_LENGTH));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        // The model's XmlString takes an empty role, which names none; AWS's answer for one is not recorded.
        if (requested.getServiceRole().isBlank()) {
            throw EmrService.invalid("ServiceRole is required.");
        }
        EmrCluster cluster = clusters.describeCluster(requested.getEngineId());
        checkCluster(cluster);
        String region = regionResolver.getRegion();
        List<EmrNotebookExecution> executions = settledExecutions(region);
        long running = executions.stream().filter(execution -> !ENDED.contains(execution.getStatus())).count();
        if (running >= MAX_CONCURRENT_EXECUTIONS) {
            throw EmrService.invalid("The account already has " + MAX_CONCURRENT_EXECUTIONS
                    + " concurrent notebook executions in the Region.");
        }

        EmrNotebookExecution execution = EmrService.SNAPSHOT_MAPPER.convertValue(requested,
                EmrNotebookExecution.class);
        String id = "ex-" + EmrService.randomId(ID_LENGTH);
        execution.setId(id);
        execution.setArn(regionResolver.buildArn("elasticmapreduce", region, EXECUTION_RESOURCE + id));
        execution.setEngineType(requested.getEngineType() != null ? requested.getEngineType() : "EMR");
        execution.setStatus(START_PENDING);
        execution.setStartTime(now());
        execution.setRegion(region);
        execution.setTags(requested.getTags() != null ? new LinkedHashMap<>(requested.getTags())
                : new LinkedHashMap<>());
        execution.setSequence(store.scan(key -> true).stream().mapToLong(EmrNotebookExecution::getSequence)
                .max().orElse(0) + 1);
        execution.setContainerExecution(clusters.runsOnClusters());
        store.put(id, execution);
        if (execution.isContainerExecution()) {
            runNotebook(execution);
        }
        // As stored: a run that could not be handed to its thread has ended it already.
        return copy(store.get(id).orElse(execution));
    }

    /**
     * Runs the execution's notebook on its cluster. The notebook is its {@code NotebookS3Location}: no source gives the
     * S3 location of an EMR Notebook named by {@code EditorId} alone, which Floci keeps none of, so such an execution
     * fails at once, in Floci's own wording. No source gives where AWS writes the notebook as run either: Floci writes
     * it under the {@code OutputNotebookS3Location} prefix, else beside the notebook, in a folder named for the
     * execution, as {@code <name>.ipynb} and {@code <name>.html}, and gives the one the {@code OutputNotebookFormat}
     * names, the HTML one, else the notebook, as its {@code OutputNotebookURI}.
     */
    private void runNotebook(EmrNotebookExecution execution) {
        if (execution.getNotebookS3Bucket() == null || execution.getNotebookS3Key() == null) {
            end(execution, FAILED, String.format(NO_NOTEBOOK, execution.getEngineId()), now());
            return;
        }
        String key = execution.getNotebookS3Key();
        String file = key.substring(key.lastIndexOf('/') + 1);
        String name = file.endsWith(".ipynb") ? file.substring(0, file.length() - ".ipynb".length()) : file;
        String base = execution.getOutputNotebookS3Bucket() != null
                ? join(execution.getOutputNotebookS3Bucket(), trimSlashes(execution.getOutputNotebookS3Key()))
                : join(execution.getNotebookS3Bucket(), trimSlashes(key.substring(0, key.length() - file.length())));
        String folder = "s3://" + join(base, execution.getId());
        execution.setOutputNotebookUri(folder + "/" + name
                + ("HTML".equals(execution.getOutputNotebookFormat()) ? ".html" : ".ipynb"));
        store.put(execution.getId(), execution);
        Map<String, String> environment = new LinkedHashMap<>(execution.getEnvironmentVariables() != null
                ? execution.getEnvironmentVariables() : Map.of());
        environment.put(EmrNotebookRunner.PROGRAM_VARIABLE, EmrNotebookRunner.PROGRAM);
        running.add(execution.getId());
        clusters.runOnCluster(execution.getEngineId(), execution.getId(), execution.getExecutionRoleArn(),
                EmrNotebookRunner.command("s3://" + execution.getNotebookS3Bucket() + "/" + key, folder, name,
                        execution.getNotebookParams()), environment, MAX_RUN, new NotebookRun(execution.getId()));
    }

    private static String join(String first, String second) {
        return second.isEmpty() ? first : first + "/" + second;
    }

    private static String trimSlashes(String key) {
        String trimmed = key == null ? "" : key;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private synchronized void notebookStarted(String executionId) {
        store.get(executionId).filter(execution -> START_PENDING.equals(execution.getStatus()))
                .ifPresent(execution -> {
                    execution.setStatus(RUNNING);
                    store.put(executionId, execution);
                });
    }

    /**
     * The notebook's run has ended: FINISHED where every cell ran, "Execution is finished for cluster ...", as AWS's
     * example puts it; STOPPED where StopNotebookExecution stopped it; FAILED where a cell raised (the program's
     * {@link EmrNotebookRunner#CELL_RAISED}), where the run failed otherwise, where it could not start, where its
     * cluster shut down, or after 30 days, in Floci's own wording.
     */
    private synchronized void notebookEnded(String executionId, SparkJobRunner.Outcome outcome) {
        running.remove(executionId);
        store.get(executionId).filter(execution -> !ENDED.contains(execution.getStatus())).ifPresent(execution -> {
            String cluster = execution.getEngineId();
            Instant now = now();
            if (STOPPING.equals(execution.getStatus())) {
                end(execution, STOPPED, "Execution is stopped for cluster " + cluster + ".", now);
            } else if (outcome.timedOut()) {
                end(execution, FAILED, "Execution is terminated for cluster " + cluster
                        + ", as it ran for more than 30 days.", now);
            } else if (outcome.stopped()) {
                end(execution, FAILED, "Execution failed for cluster " + cluster
                        + ", as the cluster was terminated.", now);
            } else if (outcome.failure() != null) {
                end(execution, FAILED, "Execution failed for cluster " + cluster + ": " + outcome.failure(), now);
            } else if (outcome.exitCode() != null && outcome.exitCode() == 0) {
                end(execution, FINISHED, "Execution is finished for cluster " + cluster + ".", now);
            } else if (outcome.exitCode() != null && outcome.exitCode() == EmrNotebookRunner.CELL_RAISED) {
                end(execution, FAILED, String.format(CELL_FAILED, cluster), now);
            } else {
                end(execution, FAILED, String.format(RUN_FAILED, cluster, outcome.exitCode()), now);
            }
        });
    }

    private synchronized boolean notebookGoing(String executionId) {
        return running.contains(executionId) && store.get(executionId)
                .map(execution -> !ENDED.contains(execution.getStatus())
                        && !STOPPING.equals(execution.getStatus()))
                .orElse(false);
    }

    public synchronized EmrNotebookExecution describe(String executionId) {
        return copy(settle(require(executionId)));
    }

    /**
     * Stops an execution that has not ended, which is STOPPED at once, as no notebook runs: "Execution is stopped for
     * cluster ...", as AWS's example puts it. One that has ended is refused, in Floci's own wording, as AWS's answer
     * is not recorded.
     */
    public synchronized void stop(String executionId) {
        EmrNotebookExecution execution = settle(require(executionId));
        if (ENDED.contains(execution.getStatus())) {
            throw EmrService.invalid("Notebook execution " + executionId + " has already ended.");
        }
        if (running.contains(executionId)) {
            // STOPPING until its run has stopped, as AWS's example has it: "Execution is being stopped for cluster".
            execution.setStatus(STOPPING);
            execution.setLastStateChangeReason("Execution is being stopped for cluster " + execution.getEngineId()
                    + ".");
            store.put(executionId, execution);
            clusters.stopOnCluster(executionId);
            return;
        }
        end(execution, STOPPED, "Execution is stopped for cluster " + execution.getEngineId() + ".", now());
    }

    /**
     * The Region's executions that started from {@code from} (by default 30 days ago) to {@code to} (by default now),
     * filtered by editor, status and execution engine, newest first, the later of two started in the same
     * millisecond first too, 50 to a page after the request's Marker.
     */
    public synchronized PaginatedResult<EmrNotebookExecution> list(String editorId, String status, Instant from,
                                                                   Instant to, String engineId, String marker) {
        List<String> violations = new ArrayList<>();
        EmrService.checkEnum(violations, "status", status, STATUSES);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        Instant now = clock.instant();
        Instant since = from != null ? from : now.minus(DEFAULT_LISTING_WINDOW);
        Instant until = to != null ? to : now;
        List<EmrNotebookExecution> matching = settledExecutions(regionResolver.getRegion()).stream()
                .filter(execution -> editorId == null || editorId.equals(execution.getEditorId()))
                .filter(execution -> status == null || status.equals(execution.getStatus()))
                .filter(execution -> engineId == null || engineId.equals(execution.getEngineId()))
                .filter(execution -> !execution.getStartTime().isBefore(since)
                        && !execution.getStartTime().isAfter(until))
                .toList();
        String namespace = listingNamespace(editorId, status, from, to, engineId);
        PaginatedResult<EmrNotebookExecution> page = Pagination.paginate(matching,
                execution -> String.format("%020d%020d", Long.MAX_VALUE - execution.getStartTime().toEpochMilli(),
                        Long.MAX_VALUE - execution.getSequence()),
                PAGE_SIZE, marker, namespace, token -> EmrService.invalid("Invalid Marker."));
        return new PaginatedResult<>(page.items().stream().map(EmrNotebookExecutionService::copy).toList(),
                page.nextToken());
    }

    /** The tags held on the execution stored under {@code arn}, in the account and Region it names, for IAM. */
    public Map<String, String> storedTags(String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String executionId = EmrService.resourceIdOf(parsed, EXECUTION_RESOURCE);
        if (executionId == null) {
            return Map.of();
        }
        return AccountAwareStorageBackend.readInAccount(store, parsed.accountId(), executionId)
                .filter(execution -> parsed.region().equals(execution.getRegion()) && arn.equals(execution.getArn()))
                .map(execution -> EmrService.heldTags(execution.getTags()))
                .orElse(Map.of());
    }

    private static void checkCluster(EmrCluster cluster) {
        if (!EmrClusterLifecycle.RUNNING.equals(cluster.getState())
                && !EmrClusterLifecycle.WAITING.equals(cluster.getState())) {
            throw EmrService.invalid("Cluster " + cluster.getId() + " is " + cluster.getState()
                    + ", and a notebook execution needs it RUNNING or WAITING.");
        }
        String release = cluster.getReleaseLabel();
        if (!EmrService.releaseAtLeast(release, 5, 18, 0)) {
            throw EmrService.invalid("Notebook executions need a cluster of release emr-5.18.0 or later.");
        }
        List<String> required = new ArrayList<>(List.of("Spark", "Livy"));
        if (release == null || !EmrServerlessSparkRunner.sparkRuntime(release)) {
            required.add(0, "Hadoop");
        }
        boolean gateway = EmrService.releaseAtLeast(release, 6, 2, 0)
                || (EmrService.releaseAtLeast(release, 5, 32, 0) && !EmrService.releaseAtLeast(release, 6, 0, 0));
        if (gateway) {
            required.add("JupyterEnterpriseGateway");
        }
        for (String application : required) {
            if (!EmrSparkSteps.names(cluster.getApplications(), EmrService.SNAPSHOT_MAPPER, application)) {
                throw EmrService.invalid("Notebook executions need a cluster with " + String.join(", ", required)
                        + " installed, and cluster " + cluster.getId() + " has no " + application + ".");
            }
        }
    }

    // The Region's executions, each brought up to date, stored where it changed.
    private List<EmrNotebookExecution> settledExecutions(String region) {
        List<EmrNotebookExecution> executions = store.scan(key -> true).stream()
                .filter(execution -> region.equals(execution.getRegion()))
                .toList();
        executions.forEach(this::settle);
        return executions;
    }

    /**
     * Brings an execution up to now: RUNNING from its start, FINISHED once the run duration has passed, "Execution is
     * finished for cluster ...", as AWS's example puts it, and FAILED once its cluster shuts down first, or once it
     * has run 30 days, in Floci's own wording.
     */
    private EmrNotebookExecution settle(EmrNotebookExecution execution) {
        if (ENDED.contains(execution.getStatus())) {
            return execution;
        }
        // Its run ends it; without one, Floci stopped while it ran, and nothing is left to.
        if (execution.isContainerExecution()) {
            if (!running.contains(execution.getId())) {
                end(execution, FAILED, INTERRUPTED, now());
            }
            return execution;
        }
        boolean changed = false;
        if (START_PENDING.equals(execution.getStatus())) {
            execution.setStatus(RUNNING);
            changed = true;
        }
        Instant now = clock.instant();
        Instant finish = execution.getStartTime().plus(runDuration);
        Instant limit = execution.getStartTime().plus(MAX_RUN);
        EmrCluster cluster = clusterOf(execution);
        Instant clusterEnd = cluster != null ? EmrService.shutDownAt(cluster, now) : null;
        if (limit.isBefore(finish) && !now.isBefore(limit) && (clusterEnd == null || limit.isBefore(clusterEnd))) {
            end(execution, FAILED, "Execution is terminated for cluster " + execution.getEngineId()
                    + ", as it ran for more than 30 days.", limit);
        } else if (!now.isBefore(finish) && !finish.isAfter(limit)
                && (clusterEnd == null || finish.isBefore(clusterEnd))) {
            end(execution, FINISHED, "Execution is finished for cluster " + execution.getEngineId() + ".", finish);
        } else if (clusterEnd != null) {
            end(execution, FAILED, "Execution failed for cluster " + execution.getEngineId()
                    + ", as the cluster was terminated.", clusterEnd.isBefore(execution.getStartTime())
                    ? execution.getStartTime() : clusterEnd);
        } else if (changed) {
            store.put(execution.getId(), execution);
        }
        return execution;
    }

    // What a marker resumes only: the account, the Region and each filter, EmrService.listingNamespace.
    private String listingNamespace(String editorId, String status, Instant from, Instant to, String engineId) {
        return EmrService.listingNamespace("ListNotebookExecutions", regionResolver.getAccountId(),
                regionResolver.getRegion(), editorId, status, from != null ? from.toString() : null,
                to != null ? to.toString() : null, engineId);
    }

    // The execution's cluster as it now stands, or null once it is gone; any other failure is the caller's.
    private EmrCluster clusterOf(EmrNotebookExecution execution) {
        return clusters.findDescribedCluster(execution.getEngineId()).orElse(null);
    }

    private void end(EmrNotebookExecution execution, String status, String reason, Instant at) {
        execution.setStatus(status);
        execution.setLastStateChangeReason(reason);
        execution.setEndTime(at);
        store.put(execution.getId(), execution);
    }

    /** What a notebook's run on its cluster tells the service, as the execution's account. */
    private final class NotebookRun implements EmrService.ClusterRun {

        private final String executionId;

        NotebookRun(String executionId) {
            this.executionId = executionId;
        }

        @Override
        public void started() {
            notebookStarted(executionId);
        }

        @Override
        public void ended(SparkJobRunner.Outcome outcome) {
            notebookEnded(executionId, outcome);
        }

        @Override
        public boolean cancelled() {
            return !notebookGoing(executionId);
        }
    }

    private EmrNotebookExecution require(String executionId) {
        if (executionId == null) {
            throw SmithyValidationErrors.validationException(SmithyValidationErrors.notNull("notebookExecutionId"));
        }
        return store.get(executionId)
                .filter(execution -> regionResolver.getRegion().equals(execution.getRegion()))
                .orElseThrow(() -> EmrService.invalid("Notebook execution " + executionId + " does not exist."));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static EmrNotebookExecution copy(EmrNotebookExecution execution) {
        return EmrService.SNAPSHOT_MAPPER.convertValue(execution, EmrNotebookExecution.class);
    }
}
