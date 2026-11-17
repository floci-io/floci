package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrNotebookExecution;
import io.floci.aws.services.emrserverless.EmrServerlessSparkRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Notebook executions on EMR clusters (Amazon EMR Management Guide, "Sample programmatic commands for EMR Notebooks").
 * Floci keeps an execution and its lifecycle, but runs no notebook: in either runner mode an execution is RUNNING from
 * the moment it starts and FINISHED once the cluster's step duration ({@code step-duration-seconds}) has passed, unless
 * it is stopped first or its cluster shuts down; it writes no output notebook. Executions are kept apart from their
 * clusters.
 */
@ApplicationScoped
public class EmrNotebookExecutionService {

    static final String START_PENDING = "START_PENDING";
    static final String RUNNING = "RUNNING";
    static final String FINISHED = "FINISHED";
    static final String FAILED = "FAILED";
    static final String STOPPED = "STOPPED";
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
        store.put(id, execution);
        return copy(execution);
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

    /**
     * What a marker resumes only: the account, the Region and each filter, an absent one told apart from any value, as
     * a JSON list.
     */
    private String listingNamespace(String editorId, String status, Instant from, Instant to, String engineId) {
        List<String> parts = Arrays.asList("ListNotebookExecutions", regionResolver.getAccountId(),
                regionResolver.getRegion(), editorId, status, from != null ? from.toString() : null,
                to != null ? to.toString() : null, engineId);
        try {
            return EmrService.SNAPSHOT_MAPPER.writeValueAsString(parts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write a listing's namespace", e);
        }
    }

    /**
     * The execution's cluster as it now stands, or null once it is gone, which DescribeCluster answers with
     * InvalidRequestException; any other failure is the caller's, not the execution's.
     */
    private EmrCluster clusterOf(EmrNotebookExecution execution) {
        try {
            return clusters.describeCluster(execution.getEngineId());
        } catch (AwsException e) {
            if ("InvalidRequestException".equals(e.getErrorCode())) {
                return null;
            }
            throw e;
        }
    }

    private void end(EmrNotebookExecution execution, String status, String reason, Instant at) {
        execution.setStatus(status);
        execution.setLastStateChangeReason(reason);
        execution.setEndTime(at);
        store.put(execution.getId(), execution);
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
