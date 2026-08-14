package io.github.hectorvent.floci.services.swf;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.swf.model.SwfActivityTask;
import io.github.hectorvent.floci.services.swf.model.SwfActivityType;
import io.github.hectorvent.floci.services.swf.model.SwfConstants;
import io.github.hectorvent.floci.services.swf.model.SwfDecisionTask;
import io.github.hectorvent.floci.services.swf.model.SwfDomain;
import io.github.hectorvent.floci.services.swf.model.SwfHistoryEvent;
import io.github.hectorvent.floci.services.swf.model.SwfTimer;
import io.github.hectorvent.floci.services.swf.model.SwfWorkflowExecution;
import io.github.hectorvent.floci.services.swf.model.SwfWorkflowType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Amazon SWF domain logic: registration, the workflow execution state machine, and
 * the decision/activity task lifecycle.
 *
 * <p>Storage layout. Domains and types are keyed by ARN-like composite keys and live in
 * {@link StorageBackend}s so they survive restarts under persistent storage modes.
 * Executions are keyed {@code domain/workflowId/runId}. Task tokens are an in-memory
 * index only: SWF tokens do not outlive a service restart, and a token that resolved
 * after a restart would point at a decision the emulator can no longer honour.
 *
 * <p>Event ordering. Every state transition appends to the execution's history under the
 * execution's monotonic {@code nextEventId}. All mutation is serialized per domain via
 * {@link #domainLock(String, String)} because deciders and workers poll concurrently, and a
 * torn history (two events sharing an id, or an activity closing twice) is
 * indistinguishable from corruption to an SDK-driven decider.
 *
 * <p>The lock is per domain rather than per execution because several invariants span
 * more than one execution: StartWorkflowExecution must see any concurrently inserted run
 * of the same workflowId to reject it, and a closing execution mutates its parent
 * (ChildWorkflowExecution* events) and its children (childPolicy). A parent and a child
 * always live in the same domain, so one lock covers a whole family and never has to be
 * nested — nesting would deadlock, since a closing child takes its parent while a closing
 * parent takes its children.
 *
 * <p>Timeouts are enforced by {@link SwfTimeoutSweeper} calling {@link #sweep()}, not by
 * per-task threads.
 */
@ApplicationScoped
public class SwfService implements Resettable {

    private static final Logger LOG = Logger.getLogger(SwfService.class);

    private static final int MAX_TAGS = 50;
    /** SWF caps maximumPageSize at 1000 on every paginated operation. */
    private static final int MAX_PAGE_SIZE = 1000;

    private final StorageBackend<String, SwfDomain> domainStore;
    private final StorageBackend<String, SwfWorkflowType> workflowTypeStore;
    private final StorageBackend<String, SwfActivityType> activityTypeStore;
    private final StorageBackend<String, SwfWorkflowExecution> executionStore;

    private final Map<String, String> decisionTokens = new ConcurrentHashMap<>();
    private final Map<String, String> activityTokens = new ConcurrentHashMap<>();
    private final Map<String, Object> domainLocks = new ConcurrentHashMap<>();

    private final RegionResolver regionResolver;
    private final Clock clock;
    private final LambdaInvoker lambdaInvoker;


    @Inject
    public SwfService(StorageFactory storageFactory, RegionResolver regionResolver, Clock clock,
                      LambdaInvoker lambdaInvoker) {
        this.domainStore = storageFactory.create("swf", "swf-domains.json",
                new TypeReference<Map<String, SwfDomain>>() {});
        this.workflowTypeStore = storageFactory.create("swf", "swf-workflow-types.json",
                new TypeReference<Map<String, SwfWorkflowType>>() {});
        this.activityTypeStore = storageFactory.create("swf", "swf-activity-types.json",
                new TypeReference<Map<String, SwfActivityType>>() {});
        this.executionStore = storageFactory.create("swf", "swf-executions.json",
                new TypeReference<Map<String, SwfWorkflowExecution>>() {});
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.lambdaInvoker = lambdaInvoker;
    }

    @Override
    public void clear() {
        decisionTokens.clear();
        activityTokens.clear();
        domainLocks.clear();
    }

    // ──────────────────────────────── Domains ────────────────────────────────

    public void registerDomain(String name, String description, String retentionDays,
                               Map<String, String> tags, String region) {
        requireName(name, "name");
        if (retentionDays == null || retentionDays.isEmpty()) {
            throw SwfFaults.missingRequired("workflowExecutionRetentionPeriodInDays");
        }
        validateRetentionPeriod(retentionDays);
        if (tags != null && tags.size() > MAX_TAGS) {
            throw SwfFaults.fault(SwfConstants.TOO_MANY_TAGS, name);
        }
        if (domainStore.get(domainKey(region, name)).isPresent()) {
            throw SwfFaults.domainAlreadyExists(name);
        }

        SwfDomain domain = new SwfDomain();
        domain.setName(name);
        domain.setDescription(description);
        domain.setWorkflowExecutionRetentionPeriodInDays(retentionDays);
        domain.setCreationDate(now());
        domain.setArn(domainArn(name, region));
        if (tags != null) {
            domain.getTags().putAll(tags);
        }
        domainStore.put(domainKey(region, name), domain);
        LOG.debugv("Registered SWF domain {0} in {1}", name, region);
    }

    public SwfDomain describeDomain(String region, String name) {
        requireName(name, "name");
        return domainStore.get(domainKey(region, name)).orElseThrow(() -> SwfFaults.unknownDomain(name));
    }

    /**
     * Resolves a domain for operations that act on its contents. Deprecated domains stay
     * readable — SWF only rejects registration and execution-starting operations on them —
     * so the deprecation check lives at those call sites instead of here.
     */
    private SwfDomain requireDomain(String region, String name) {
        requireName(name, "domain");
        return domainStore.get(domainKey(region, name)).orElseThrow(() -> SwfFaults.unknownDomain(name));
    }

    public List<SwfDomain> listDomains(String region, String registrationStatus) {
        String status = requireRegistrationStatus(registrationStatus);
        String prefix = region + ":";
        List<SwfDomain> domains = domainStore.scan(k -> k.startsWith(prefix));
        domains.removeIf(d -> !status.equals(d.getStatus()));
        domains.sort(Comparator.comparing(SwfDomain::getName));
        return domains;
    }

    public void deprecateDomain(String region, String name) {
        SwfDomain domain = requireDomain(region, name);
        if (domain.isDeprecated()) {
            throw SwfFaults.domainDeprecated(name);
        }
        domain.setStatus(SwfConstants.STATUS_DEPRECATED);
        domain.setDeprecationDate(now());
        domainStore.put(domainKey(region, name), domain);
    }

    public void undeprecateDomain(String region, String name) {
        SwfDomain domain = requireDomain(region, name);
        if (!domain.isDeprecated()) {
            throw SwfFaults.domainAlreadyExists(name);
        }
        domain.setStatus(SwfConstants.STATUS_REGISTERED);
        domain.setDeprecationDate(null);
        domainStore.put(domainKey(region, name), domain);
    }

    // ───────────────────────────── Workflow types ────────────────────────────

    public void registerWorkflowType(String region, String domainName, SwfWorkflowType type) {
        SwfDomain domain = requireDomain(region, domainName);
        if (domain.isDeprecated()) {
            throw SwfFaults.domainDeprecated(domainName);
        }
        requireName(type.getName(), "name");
        requireName(type.getVersion(), "version");

        String key = typeKey(region, domainName, type.getName(), type.getVersion());
        if (workflowTypeStore.get(key).isPresent()) {
            throw SwfFaults.workflowTypeAlreadyExists(type.getName(), type.getVersion());
        }
        type.setDomain(domainName);
        type.setCreationDate(now());
        workflowTypeStore.put(key, type);
    }

    public SwfWorkflowType describeWorkflowType(String region, String domainName, String name, String version) {
        requireDomain(region, domainName);
        return findWorkflowType(region, domainName, name, version);
    }

    private SwfWorkflowType findWorkflowType(String region, String domainName, String name, String version) {
        requireName(name, "workflowType.name");
        requireName(version, "workflowType.version");
        return workflowTypeStore.get(typeKey(region, domainName, name, version))
                .orElseThrow(() -> SwfFaults.unknownWorkflowType(name, version));
    }

    public List<SwfWorkflowType> listWorkflowTypes(String region, String domainName, String name,
                                                   String registrationStatus, boolean reverseOrder) {
        requireDomain(region, domainName);
        String status = requireRegistrationStatus(registrationStatus);
        String prefix = region + ":" + domainName + "/";
        List<SwfWorkflowType> types = workflowTypeStore.scan(k -> k.startsWith(prefix));
        types.removeIf(t -> !status.equals(t.getStatus()));
        if (name != null && !name.isEmpty()) {
            types.removeIf(t -> !name.equals(t.getName()));
        }
        types.sort(Comparator.comparing(SwfWorkflowType::getName).thenComparing(SwfWorkflowType::getVersion));
        if (reverseOrder) {
            java.util.Collections.reverse(types);
        }
        return types;
    }

    public void deprecateWorkflowType(String region, String domainName, String name, String version) {
        requireDomain(region, domainName);
        SwfWorkflowType type = findWorkflowType(region, domainName, name, version);
        if (type.isDeprecated()) {
            throw SwfFaults.workflowTypeDeprecated(name, version);
        }
        type.setStatus(SwfConstants.STATUS_DEPRECATED);
        type.setDeprecationDate(now());
        workflowTypeStore.put(typeKey(region, domainName, name, version), type);
    }

    public void undeprecateWorkflowType(String region, String domainName, String name, String version) {
        requireDomain(region, domainName);
        SwfWorkflowType type = findWorkflowType(region, domainName, name, version);
        if (!type.isDeprecated()) {
            throw SwfFaults.workflowTypeAlreadyExists(name, version);
        }
        type.setStatus(SwfConstants.STATUS_REGISTERED);
        type.setDeprecationDate(null);
        workflowTypeStore.put(typeKey(region, domainName, name, version), type);
    }

    /** SWF only deletes a type that is already deprecated. */
    public void deleteWorkflowType(String region, String domainName, String name, String version) {
        requireDomain(region, domainName);
        SwfWorkflowType type = findWorkflowType(region, domainName, name, version);
        if (!type.isDeprecated()) {
            throw SwfFaults.typeNotDeprecated();
        }
        workflowTypeStore.delete(typeKey(region, domainName, name, version));
    }

    // ───────────────────────────── Activity types ────────────────────────────

    public void registerActivityType(String region, String domainName, SwfActivityType type) {
        SwfDomain domain = requireDomain(region, domainName);
        if (domain.isDeprecated()) {
            throw SwfFaults.domainDeprecated(domainName);
        }
        requireName(type.getName(), "name");
        requireName(type.getVersion(), "version");

        String key = typeKey(region, domainName, type.getName(), type.getVersion());
        if (activityTypeStore.get(key).isPresent()) {
            throw SwfFaults.activityTypeAlreadyExists(type.getName(), type.getVersion());
        }
        type.setDomain(domainName);
        type.setCreationDate(now());
        activityTypeStore.put(key, type);
    }

    public SwfActivityType describeActivityType(String region, String domainName, String name, String version) {
        requireDomain(region, domainName);
        return findActivityType(region, domainName, name, version);
    }

    private SwfActivityType findActivityType(String region, String domainName, String name, String version) {
        requireName(name, "activityType.name");
        requireName(version, "activityType.version");
        return activityTypeStore.get(typeKey(region, domainName, name, version))
                .orElseThrow(() -> SwfFaults.unknownActivityType(name, version));
    }

    public List<SwfActivityType> listActivityTypes(String region, String domainName, String name,
                                                   String registrationStatus, boolean reverseOrder) {
        requireDomain(region, domainName);
        String status = requireRegistrationStatus(registrationStatus);
        String prefix = region + ":" + domainName + "/";
        List<SwfActivityType> types = activityTypeStore.scan(k -> k.startsWith(prefix));
        types.removeIf(t -> !status.equals(t.getStatus()));
        if (name != null && !name.isEmpty()) {
            types.removeIf(t -> !name.equals(t.getName()));
        }
        types.sort(Comparator.comparing(SwfActivityType::getName).thenComparing(SwfActivityType::getVersion));
        if (reverseOrder) {
            java.util.Collections.reverse(types);
        }
        return types;
    }

    public void deprecateActivityType(String region, String domainName, String name, String version) {
        requireDomain(region, domainName);
        SwfActivityType type = findActivityType(region, domainName, name, version);
        if (type.isDeprecated()) {
            throw SwfFaults.activityTypeDeprecated(name, version);
        }
        type.setStatus(SwfConstants.STATUS_DEPRECATED);
        type.setDeprecationDate(now());
        activityTypeStore.put(typeKey(region, domainName, name, version), type);
    }

    public void undeprecateActivityType(String region, String domainName, String name, String version) {
        requireDomain(region, domainName);
        SwfActivityType type = findActivityType(region, domainName, name, version);
        if (!type.isDeprecated()) {
            throw SwfFaults.activityTypeAlreadyExists(name, version);
        }
        type.setStatus(SwfConstants.STATUS_REGISTERED);
        type.setDeprecationDate(null);
        activityTypeStore.put(typeKey(region, domainName, name, version), type);
    }

    public void deleteActivityType(String region, String domainName, String name, String version) {
        requireDomain(region, domainName);
        SwfActivityType type = findActivityType(region, domainName, name, version);
        if (!type.isDeprecated()) {
            throw SwfFaults.typeNotDeprecated();
        }
        activityTypeStore.delete(typeKey(region, domainName, name, version));
    }

    // ─────────────────────────── Execution lifecycle ─────────────────────────

    public String startWorkflowExecution(StartWorkflowExecutionRequest request) {
        SwfDomain domain = requireDomain(request.region(), request.domain());
        if (domain.isDeprecated()) {
            throw SwfFaults.domainDeprecated(request.domain());
        }
        requireName(request.workflowId(), "workflowId");

        SwfWorkflowType type = findWorkflowType(request.region(), request.domain(), request.typeName(), request.typeVersion());
        if (type.isDeprecated()) {
            throw SwfFaults.workflowTypeDeprecated(type.getName(), type.getVersion());
        }

        // The open-run check and the insert have to be one atomic step, or two concurrent
        // starts for the same workflowId both scan clean and persist distinct run keys,
        // leaving two open runs where SWF guarantees one.
        synchronized (domainLock(request.region(), request.domain())) {
            if (findOpenExecution(request.region(), request.domain(), request.workflowId()).isPresent()) {
                throw SwfFaults.executionAlreadyStarted();
            }

            SwfWorkflowExecution execution = buildExecution(request, type);
            appendWorkflowExecutionStarted(execution, request.input(), null);
            scheduleDecisionTask(execution);
            executionStore.put(executionKey(execution), execution);
            LOG.debugv("Started SWF execution {0}/{1} run {2}",
                    execution.getDomain(), execution.getWorkflowId(), execution.getRunId());
            return execution.getRunId();
        }
    }

    private SwfWorkflowExecution buildExecution(StartWorkflowExecutionRequest request, SwfWorkflowType type) {
        SwfWorkflowExecution execution = new SwfWorkflowExecution();
        execution.setRegion(request.region());
        execution.setDomain(request.domain());
        execution.setWorkflowId(request.workflowId());
        execution.setRunId(newRunId());
        execution.setWorkflowTypeName(type.getName());
        execution.setWorkflowTypeVersion(type.getVersion());
        execution.setInput(request.input());
        execution.setStartTimestamp(now());
        if (request.tagList() != null) {
            execution.setTagList(new ArrayList<>(request.tagList()));
        }

        execution.setTaskList(resolveRequired(request.taskList(), type.getDefaultTaskList(),
                "defaultTaskList"));
        execution.setExecutionStartToCloseTimeout(resolveRequired(request.executionStartToCloseTimeout(),
                type.getDefaultExecutionStartToCloseTimeout(), "defaultExecutionStartToCloseTimeout"));
        execution.setTaskStartToCloseTimeout(resolveRequired(request.taskStartToCloseTimeout(),
                type.getDefaultTaskStartToCloseTimeout(), "defaultTaskStartToCloseTimeout"));
        execution.setChildPolicy(validateChildPolicy(resolveRequired(request.childPolicy(),
                type.getDefaultChildPolicy(), "defaultChildPolicy")));
        execution.setTaskPriority(firstNonEmpty(request.taskPriority(), type.getDefaultTaskPriority()));
        execution.setLambdaRole(firstNonEmpty(request.lambdaRole(), type.getDefaultLambdaRole()));
        return execution;
    }

    /**
     * StartWorkflowExecution fields that fall back to the workflow type's registration
     * default. When neither is set SWF rejects the call with DefaultUndefinedFault naming
     * the missing type-level default rather than the request field.
     */
    private String resolveRequired(String requested, String typeDefault, String defaultFieldName) {
        String value = firstNonEmpty(requested, typeDefault);
        if (value == null) {
            throw SwfFaults.defaultUndefined(defaultFieldName);
        }
        return value;
    }

    public SwfWorkflowExecution describeWorkflowExecution(String region, String domainName, String workflowId, String runId) {
        requireDomain(region, domainName);
        return requireExecution(region, domainName, workflowId, runId);
    }

    private SwfWorkflowExecution requireExecution(String region, String domainName, String workflowId, String runId) {
        requireName(workflowId, "execution.workflowId");
        if (runId == null || runId.isEmpty()) {
            return findOpenExecution(region, domainName, workflowId)
                    .orElseThrow(() -> SwfFaults.unknownExecution(workflowId, ""));
        }
        return executionStore.get(executionKey(region, domainName, workflowId, runId))
                .orElseThrow(() -> SwfFaults.unknownExecution(workflowId, runId));
    }

    private Optional<SwfWorkflowExecution> findOpenExecution(String region, String domainName, String workflowId) {
        String prefix = region + ":" + domainName + "/" + workflowId + "/";
        return executionStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(SwfWorkflowExecution::isOpen)
                .findFirst();
    }

    public List<SwfHistoryEvent> getWorkflowExecutionHistory(String region, String domainName, String workflowId,
                                                             String runId, boolean reverseOrder) {
        requireDomain(region, domainName);
        SwfWorkflowExecution execution = requireExecution(region, domainName, workflowId, runId);
        List<SwfHistoryEvent> events = new ArrayList<>(execution.getEvents());
        if (reverseOrder) {
            java.util.Collections.reverse(events);
        }
        return events;
    }

    /**
     * The page size for every paginated operation: the registration lists, the history, the
     * execution listings, and PollForDecisionTask.
     *
     * <p>Measured against the live service on all four: an absent or zero
     * {@code maximumPageSize} means "no caller limit" and returns everything up to the service
     * maximum, above 1000 is rejected rather than clamped, and a negative value is rejected
     * with its own constraint message.
     */
    public int pageSize(Integer requested) {
        if (requested == null || requested == 0) {
            return MAX_PAGE_SIZE;
        }
        if (requested < 0) {
            throw SwfFaults.validationConstraint(String.valueOf(requested), "maximumPageSize",
                    "Member must have value greater than or equal to 0");
        }
        if (requested > MAX_PAGE_SIZE) {
            throw SwfFaults.validationConstraint(String.valueOf(requested), "maximumPageSize",
                    "Member must have value less than or equal to " + MAX_PAGE_SIZE);
        }
        return requested;
    }

    public List<SwfWorkflowExecution> listExecutions(String region, String domainName, ExecutionFilter filter, boolean closed) {
        requireDomain(region, domainName);
        String prefix = region + ":" + domainName + "/";
        List<SwfWorkflowExecution> executions = executionStore.scan(k -> k.startsWith(prefix));
        executions.removeIf(e -> closed == e.isOpen());
        executions.removeIf(filter.negate());
        executions.sort(Comparator.comparingDouble(SwfWorkflowExecution::getStartTimestamp).reversed());
        return executions;
    }

    public int countPendingActivityTasks(String region, String domainName, String taskList) {
        requireDomain(region, domainName);
        requireName(taskList, "taskList.name");
        int count = 0;
        for (SwfWorkflowExecution execution : executionsIn(region, domainName)) {
            for (SwfActivityTask task : execution.getActivities().values()) {
                if (SwfActivityTask.STATE_SCHEDULED.equals(task.getState()) && taskList.equals(task.getTaskList())) {
                    count++;
                }
            }
        }
        return count;
    }

    public int countPendingDecisionTasks(String region, String domainName, String taskList) {
        requireDomain(region, domainName);
        requireName(taskList, "taskList.name");
        int count = 0;
        for (SwfWorkflowExecution execution : executionsIn(region, domainName)) {
            SwfDecisionTask task = execution.getDecisionTask();
            if (task != null && SwfDecisionTask.STATE_SCHEDULED.equals(task.getState())
                    && taskList.equals(task.getTaskList())) {
                count++;
            }
        }
        return count;
    }

    private List<SwfWorkflowExecution> executionsIn(String region, String domainName) {
        String prefix = region + ":" + domainName + "/";
        return executionStore.scan(k -> k.startsWith(prefix));
    }

    // ────────────────────────────── Decision tasks ───────────────────────────

    /**
     * Claims the oldest scheduled decision task on {@code taskList}, or returns empty when
     * none is available. SWF long-polls for up to 60s; the emulator answers immediately and
     * lets the caller decide whether to poll again, which keeps request threads free.
     */
    public Optional<SwfDecisionTask> pollForDecisionTask(String region, String domainName, String taskList, String identity) {
        requireDomain(region, domainName);
        requireName(taskList, "taskList.name");

        List<SwfWorkflowExecution> candidates = executionsIn(region, domainName);
        candidates.sort(Comparator.comparingDouble(e -> {
            SwfDecisionTask task = e.getDecisionTask();
            return task != null ? task.getScheduledTimestamp() : Double.MAX_VALUE;
        }));

        for (SwfWorkflowExecution candidate : candidates) {
            SwfDecisionTask claimed = tryClaimDecisionTask(candidate, taskList, identity);
            if (claimed != null) {
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    private SwfDecisionTask tryClaimDecisionTask(SwfWorkflowExecution candidate, String taskList, String identity) {
        synchronized (domainLock(candidate)) {
            SwfWorkflowExecution execution = executionStore.get(executionKey(candidate)).orElse(null);
            if (execution == null || !execution.isOpen()) {
                return null;
            }
            SwfDecisionTask task = execution.getDecisionTask();
            if (task == null || !SwfDecisionTask.STATE_SCHEDULED.equals(task.getState())
                    || !taskList.equals(task.getTaskList())) {
                return null;
            }

            task.setState(SwfDecisionTask.STATE_STARTED);
            task.setIdentity(identity);
            task.setStartedTimestamp(now());
            SwfHistoryEvent started = appendEvent(execution, "DecisionTaskStarted");
            started.attr("identity", identity)
                    .attr("scheduledEventId", task.getScheduledEventId())
                    .attr("startedOnPreviousTaskCompletion", Boolean.FALSE);
            task.setStartedEventId(started.getEventId());

            String token = newTaskToken();
            task.setTaskToken(token);
            decisionTokens.put(token, executionKey(execution));
            executionStore.put(executionKey(execution), execution);
            return task;
        }
    }

    public SwfWorkflowExecution executionForDecisionToken(String taskToken) {
        if (taskToken == null || taskToken.isEmpty()) {
            throw SwfFaults.missingRequired("taskToken");
        }
        String key = decisionTokens.get(taskToken);
        if (key == null) {
            throw SwfFaults.unknownTaskToken();
        }
        return executionStore.get(key).orElseThrow(SwfFaults::unknownTaskToken);
    }

    /**
     * Applies a decider's decisions in order.
     *
     * <p>A closing decision (Complete/Fail/Cancel/ContinueAsNew) must be last: the live
     * service rejects the whole batch with {@code ValidationException} rather than applying
     * the prefix, so this is validated before any state changes.
     */
    public void respondDecisionTaskCompleted(String taskToken, List<Decision> decisions, String executionContext) {
        // Scoped to this call, not to the thread: a request that throws before the drain must
        // not leave an invocation for whichever request reuses this pooled thread next.
        List<PendingLambda> queued = new ArrayList<>();
        SwfWorkflowExecution located = executionForDecisionToken(taskToken);
        synchronized (domainLock(located)) {
            SwfWorkflowExecution execution = executionStore.get(executionKey(located))
                    .orElseThrow(SwfFaults::unknownTaskToken);
            SwfDecisionTask task = execution.getDecisionTask();
            if (task == null || !taskToken.equals(task.getTaskToken())) {
                throw SwfFaults.unknownTaskToken();
            }

            // Rejected before anything is mutated: the live service answers 400 and leaves
            // the task outstanding, so the decider can retry with a corrected batch.
            requireValidBatch(decisions);

            SwfHistoryEvent completed = appendEvent(execution, "DecisionTaskCompleted");
            completed.attr("executionContext", executionContext)
                    .attr("scheduledEventId", task.getScheduledEventId())
                    .attr("startedEventId", task.getStartedEventId());
            long decisionEventId = completed.getEventId();

            decisionTokens.remove(taskToken);
            execution.setDecisionTask(null);
            execution.setDecisionTaskOutstanding(false);
            if (executionContext != null) {
                execution.setLatestExecutionContext(executionContext);
            }

            applyDecisions(execution, decisions, queued, decisionEventId);

            if (execution.isOpen() && (execution.isDecisionNeeded() || hasNothingPending(execution))) {
                execution.setDecisionNeeded(false);
                scheduleDecisionTask(execution);
            }
            executionStore.put(executionKey(execution), execution);
        }
        // Only reached when the batch was applied and stored: anything thrown above leaves
        // `queued` unreferenced, so a failed request cannot invoke a function.
        queued.forEach(this::runPendingLambda);
    }

    /**
     * The decision task a token identifies, without claiming anything.
     *
     * <p>Used by {@code PollForDecisionTask} continuation paging: the live service returns the
     * same task's next history page for a {@code nextPageToken} rather than handing out a new
     * task, so a continuation resolves the original task instead of polling again.
     */
    public SwfDecisionTask decisionTaskForToken(String taskToken) {
        SwfWorkflowExecution execution = executionForDecisionToken(taskToken);
        SwfDecisionTask task = execution.getDecisionTask();
        if (task == null || !taskToken.equals(task.getTaskToken())) {
            throw SwfFaults.unknownTaskToken();
        }
        return task;
    }

    /**
     * Validates a whole batch before any of it is applied.
     *
     * <p>The live service rejects an invalid batch with {@code ValidationException} and leaves
     * the execution untouched: no {@code DecisionTaskCompleted}, no earlier decision applied,
     * and the token still claimable for a corrected batch. Validating up front is what makes
     * that possible — the mutations land on the in-memory execution and on
     * {@code decisionTokens}, so there is nothing to roll back once they have happened.
     *
     * <p>Two rules, both measured against the live service: every {@code decisionType} must be
     * one the service knows, and a closing decision may appear only last.
     */
    private void requireValidBatch(List<Decision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return;
        }
        for (int i = 0; i < decisions.size(); i++) {
            String type = decisions.get(i).type();
            if (!SwfConstants.DECISION_TYPES.contains(type)) {
                // AWS names the offending member by its 1-based position in the list.
                throw SwfFaults.validationConstraint(type,
                        "decisions." + (i + 1) + ".member.decisionType",
                        "Member must satisfy enum value set: " + SwfConstants.DECISION_TYPE_SET_MESSAGE);
            }
            if (i < decisions.size() - 1 && SwfConstants.CLOSING_DECISIONS.contains(type)) {
                throw SwfFaults.validation("Close must be last decision in list");
            }
        }
    }

    private void applyDecisions(SwfWorkflowExecution execution, List<Decision> decisions,
                                List<PendingLambda> queued, long decisionEventId) {
        if (decisions == null) {
            return;
        }
        for (Decision decision : decisions) {
            if (!execution.isOpen()) {
                LOG.debugv("Dropping {0} decision: execution {1} already closed",
                        decision.type(), execution.getRunId());
                return;
            }
            applyDecision(execution, decision, queued, decisionEventId);
        }
    }

    private void applyDecision(SwfWorkflowExecution execution, Decision decision,
                               List<PendingLambda> queued, long decisionEventId) {
        switch (decision.type()) {
            case "ScheduleActivityTask" -> scheduleActivityTask(execution, decision, decisionEventId);
            case "RequestCancelActivityTask" -> requestCancelActivityTask(execution, decision, decisionEventId);
            case "CompleteWorkflowExecution" -> completeWorkflowExecution(execution, decision, decisionEventId);
            case "FailWorkflowExecution" -> failWorkflowExecution(execution, decision, decisionEventId);
            case "CancelWorkflowExecution" -> cancelWorkflowExecution(execution, decision, decisionEventId);
            case "ContinueAsNewWorkflowExecution" -> continueAsNew(execution, decision, decisionEventId);
            case "RecordMarker" -> recordMarker(execution, decision, decisionEventId);
            case "StartTimer" -> startTimer(execution, decision, decisionEventId);
            case "CancelTimer" -> cancelTimer(execution, decision, decisionEventId);
            case "SignalExternalWorkflowExecution" -> signalExternal(execution, decision, decisionEventId);
            case "RequestCancelExternalWorkflowExecution" -> cancelExternal(execution, decision, decisionEventId);
            case "StartChildWorkflowExecution" -> startChild(execution, decision, decisionEventId);
            case "ScheduleLambdaFunction" -> scheduleLambdaFunction(execution, decision, queued, decisionEventId);
            // requireValidBatch has already rejected anything outside DECISION_TYPES.
            default -> throw new IllegalStateException("unvalidated decision type " + decision.type());
        }
    }

    private void scheduleActivityTask(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        String activityId = decision.string("activityId");
        String typeName = decision.nested("activityType", "name");
        String typeVersion = decision.nested("activityType", "version");

        SwfActivityType type = activityTypeStore.get(typeKey(execution.getRegion(), execution.getDomain(), typeName, typeVersion))
                .orElse(null);
        if (type == null) {
            appendScheduleActivityTaskFailed(execution, decision, decisionEventId, "ACTIVITY_TYPE_DOES_NOT_EXIST");
            return;
        }
        if (type.isDeprecated()) {
            appendScheduleActivityTaskFailed(execution, decision, decisionEventId, "ACTIVITY_TYPE_DEPRECATED");
            return;
        }
        SwfActivityTask existing = execution.getActivities().get(activityId);
        if (existing != null && existing.isOpen()) {
            appendScheduleActivityTaskFailed(execution, decision, decisionEventId, "ACTIVITY_ID_ALREADY_IN_USE");
            return;
        }

        String taskList = firstNonEmpty(decision.nested("taskList", "name"), type.getDefaultTaskList());
        if (taskList == null) {
            appendScheduleActivityTaskFailed(execution, decision, decisionEventId, "DEFAULT_TASK_LIST_UNDEFINED");
            return;
        }
        String scheduleToStart = firstNonEmpty(decision.string("scheduleToStartTimeout"),
                type.getDefaultTaskScheduleToStartTimeout());
        String scheduleToClose = firstNonEmpty(decision.string("scheduleToCloseTimeout"),
                type.getDefaultTaskScheduleToCloseTimeout());
        String startToClose = firstNonEmpty(decision.string("startToCloseTimeout"),
                type.getDefaultTaskStartToCloseTimeout());
        String heartbeat = firstNonEmpty(decision.string("heartbeatTimeout"),
                type.getDefaultTaskHeartbeatTimeout());
        if (scheduleToStart == null) {
            appendScheduleActivityTaskFailed(execution, decision, decisionEventId,
                    "DEFAULT_SCHEDULE_TO_START_TIMEOUT_UNDEFINED");
            return;
        }
        if (startToClose == null) {
            appendScheduleActivityTaskFailed(execution, decision, decisionEventId,
                    "DEFAULT_START_TO_CLOSE_TIMEOUT_UNDEFINED");
            return;
        }

        SwfHistoryEvent scheduled = appendEvent(execution, "ActivityTaskScheduled");
        scheduled.attr("activityType", Map.of("name", typeName, "version", typeVersion))
                .attr("activityId", activityId)
                .attr("input", decision.string("input"))
                .attr("control", decision.string("control"))
                .attr("scheduleToStartTimeout", scheduleToStart)
                .attr("scheduleToCloseTimeout", scheduleToClose)
                .attr("startToCloseTimeout", startToClose)
                .attr("heartbeatTimeout", heartbeat)
                .attr("taskList", Map.of("name", taskList))
                .attr("taskPriority", firstNonEmpty(decision.string("taskPriority"), type.getDefaultTaskPriority()))
                .attr("decisionTaskCompletedEventId", decisionEventId)
                .attr("immediatelyStart", Boolean.FALSE);

        SwfActivityTask task = new SwfActivityTask();
        task.setDomain(execution.getDomain());
        task.setWorkflowId(execution.getWorkflowId());
        task.setRunId(execution.getRunId());
        task.setActivityId(activityId);
        task.setActivityTypeName(typeName);
        task.setActivityTypeVersion(typeVersion);
        task.setTaskList(taskList);
        task.setTaskPriority(firstNonEmpty(decision.string("taskPriority"), type.getDefaultTaskPriority()));
        task.setInput(decision.string("input"));
        task.setControl(decision.string("control"));
        task.setScheduleToStartTimeout(scheduleToStart);
        task.setScheduleToCloseTimeout(scheduleToClose);
        task.setStartToCloseTimeout(startToClose);
        task.setHeartbeatTimeout(heartbeat);
        task.setScheduledEventId(scheduled.getEventId());
        task.setDecisionTaskCompletedEventId(decisionEventId);
        task.setScheduledTimestamp(scheduled.getEventTimestamp());
        execution.getActivities().put(activityId, task);
    }

    private void appendScheduleActivityTaskFailed(SwfWorkflowExecution execution, Decision decision,
                                                  long decisionEventId, String cause) {
        appendEvent(execution, "ScheduleActivityTaskFailed")
                .attr("activityType", Map.of(
                        "name", nullToEmpty(decision.nested("activityType", "name")),
                        "version", nullToEmpty(decision.nested("activityType", "version"))))
                .attr("activityId", nullToEmpty(decision.string("activityId")))
                .attr("cause", cause)
                .attr("decisionTaskCompletedEventId", decisionEventId);
        execution.setDecisionNeeded(true);
    }

    private void requestCancelActivityTask(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        String activityId = decision.string("activityId");
        SwfActivityTask task = execution.getActivities().get(activityId);
        if (task == null || !task.isOpen()) {
            appendEvent(execution, "RequestCancelActivityTaskFailed")
                    .attr("activityId", nullToEmpty(activityId))
                    .attr("cause", "ACTIVITY_ID_UNKNOWN")
                    .attr("decisionTaskCompletedEventId", decisionEventId);
            execution.setDecisionNeeded(true);
            return;
        }

        SwfHistoryEvent requested = appendEvent(execution, "ActivityTaskCancelRequested");
        requested.attr("decisionTaskCompletedEventId", decisionEventId).attr("activityId", activityId);
        task.setCancelRequested(true);
        task.setLatestCancelRequestedEventId(requested.getEventId());

        // A scheduled-but-unstarted activity has no worker to observe the cancellation,
        // so SWF cancels it immediately rather than waiting for a heartbeat.
        if (SwfActivityTask.STATE_SCHEDULED.equals(task.getState())) {
            appendEvent(execution, "ActivityTaskCanceled")
                    .attr("scheduledEventId", task.getScheduledEventId())
                    .attr("startedEventId", 0L)
                    .attr("latestCancelRequestedEventId", requested.getEventId());
            task.setState(SwfActivityTask.STATE_CLOSED);
            execution.setDecisionNeeded(true);
        }
    }

    private void completeWorkflowExecution(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        if (hasOpenWork(execution)) {
            appendEvent(execution, "CompleteWorkflowExecutionFailed")
                    .attr("cause", "UNHANDLED_DECISION")
                    .attr("decisionTaskCompletedEventId", decisionEventId);
            execution.setDecisionNeeded(true);
            return;
        }
        appendEvent(execution, "WorkflowExecutionCompleted")
                .attr("result", decision.string("result"))
                .attr("decisionTaskCompletedEventId", decisionEventId);
        closeExecution(execution, SwfConstants.CLOSE_STATUS_COMPLETED);
    }

    private void failWorkflowExecution(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        if (hasOpenWork(execution)) {
            appendEvent(execution, "FailWorkflowExecutionFailed")
                    .attr("cause", "UNHANDLED_DECISION")
                    .attr("decisionTaskCompletedEventId", decisionEventId);
            execution.setDecisionNeeded(true);
            return;
        }
        appendEvent(execution, "WorkflowExecutionFailed")
                .attr("reason", decision.string("reason"))
                .attr("details", decision.string("details"))
                .attr("decisionTaskCompletedEventId", decisionEventId);
        closeExecution(execution, SwfConstants.CLOSE_STATUS_FAILED);
    }

    private void cancelWorkflowExecution(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        if (hasOpenWork(execution)) {
            appendEvent(execution, "CancelWorkflowExecutionFailed")
                    .attr("cause", "UNHANDLED_DECISION")
                    .attr("decisionTaskCompletedEventId", decisionEventId);
            execution.setDecisionNeeded(true);
            return;
        }
        appendEvent(execution, "WorkflowExecutionCanceled")
                .attr("details", decision.string("details"))
                .attr("decisionTaskCompletedEventId", decisionEventId);
        closeExecution(execution, SwfConstants.CLOSE_STATUS_CANCELED);
    }

    /**
     * Moves a parent relationship from a run onto its continue-as-new successor.
     *
     * <p>Continuing as new closes one run and opens another, and the live service keeps the
     * parent link across that boundary: the successor reports the same
     * {@code parentWorkflowExecution} and {@code parentInitiatedEventId}. The parent's own child
     * entry has to move too, otherwise it keeps pointing at the closed run and would never see
     * the successor's outcome or have child policy applied to it.
     */
    private void carryParentTo(SwfWorkflowExecution closing, SwfWorkflowExecution successor) {
        if (closing.getParentWorkflowId() == null) {
            return;
        }
        successor.setParentWorkflowId(closing.getParentWorkflowId());
        successor.setParentRunId(closing.getParentRunId());
        successor.setParentInitiatedEventId(closing.getParentInitiatedEventId());
        successor.setParentStartedEventId(closing.getParentStartedEventId());

        // The parent shares this run's domain, so the domain lock is already held.
        String parentKey = executionKey(closing.getRegion(), closing.getDomain(),
                closing.getParentWorkflowId(), closing.getParentRunId());
        executionStore.get(parentKey).ifPresent(parent -> {
            parent.getChildExecutions().put(successor.getWorkflowId(), successor.getRunId());
            executionStore.put(parentKey, parent);
        });
    }

    private void continueAsNew(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        String version = firstNonEmpty(decision.string("workflowTypeVersion"), execution.getWorkflowTypeVersion());
        SwfWorkflowType type = workflowTypeStore
                .get(typeKey(execution.getRegion(), execution.getDomain(), execution.getWorkflowTypeName(), version)).orElse(null);
        if (type == null) {
            appendEvent(execution, "ContinueAsNewWorkflowExecutionFailed")
                    .attr("cause", "WORKFLOW_TYPE_DOES_NOT_EXIST")
                    .attr("decisionTaskCompletedEventId", decisionEventId);
            execution.setDecisionNeeded(true);
            return;
        }
        if (type.isDeprecated()) {
            appendEvent(execution, "ContinueAsNewWorkflowExecutionFailed")
                    .attr("cause", "WORKFLOW_TYPE_DEPRECATED")
                    .attr("decisionTaskCompletedEventId", decisionEventId);
            execution.setDecisionNeeded(true);
            return;
        }

        StartWorkflowExecutionRequest request = new StartWorkflowExecutionRequest(
                execution.getRegion(), execution.getDomain(), execution.getWorkflowId(), type.getName(), version,
                decision.nested("taskList", "name"), decision.string("taskPriority"),
                decision.string("input"), decision.string("executionStartToCloseTimeout"),
                decision.string("taskStartToCloseTimeout"), decision.string("childPolicy"),
                decision.stringList("tagList"), decision.string("lambdaRole"));

        SwfWorkflowExecution next = buildExecution(request, type);
        next.setContinuedExecutionRunId(execution.getRunId());
        carryParentTo(execution, next);

        appendEvent(execution, "WorkflowExecutionContinuedAsNew")
                .attr("input", decision.string("input"))
                .attr("decisionTaskCompletedEventId", decisionEventId)
                .attr("newExecutionRunId", next.getRunId())
                .attr("executionStartToCloseTimeout", next.getExecutionStartToCloseTimeout())
                .attr("taskList", Map.of("name", next.getTaskList()))
                .attr("taskPriority", next.getTaskPriority())
                .attr("taskStartToCloseTimeout", next.getTaskStartToCloseTimeout())
                .attr("childPolicy", next.getChildPolicy())
                .attr("tagList", next.getTagList().isEmpty() ? null : new ArrayList<>(next.getTagList()))
                .attr("workflowType", Map.of("name", type.getName(), "version", version))
                .attr("lambdaRole", next.getLambdaRole());
        closeExecution(execution, SwfConstants.CLOSE_STATUS_CONTINUED_AS_NEW);

        appendWorkflowExecutionStarted(next, decision.string("input"), execution.getRunId());
        scheduleDecisionTask(next);
        executionStore.put(executionKey(next), next);
    }

    private void recordMarker(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        appendEvent(execution, "MarkerRecorded")
                .attr("markerName", decision.string("markerName"))
                .attr("details", decision.string("details"))
                .attr("decisionTaskCompletedEventId", decisionEventId);
    }

    private void startTimer(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        String timerId = decision.string("timerId");
        SwfTimer existing = execution.getTimers().get(timerId);
        if (existing != null && !existing.isCanceled()) {
            appendEvent(execution, "StartTimerFailed")
                    .attr("timerId", nullToEmpty(timerId))
                    .attr("cause", "TIMER_ID_ALREADY_IN_USE")
                    .attr("decisionTaskCompletedEventId", decisionEventId);
            execution.setDecisionNeeded(true);
            return;
        }

        String startToFire = decision.string("startToFireTimeout");
        SwfHistoryEvent started = appendEvent(execution, "TimerStarted");
        started.attr("timerId", timerId)
                .attr("control", decision.string("control"))
                .attr("startToFireTimeout", startToFire)
                .attr("decisionTaskCompletedEventId", decisionEventId);

        SwfTimer timer = new SwfTimer();
        timer.setTimerId(timerId);
        timer.setControl(decision.string("control"));
        timer.setStartToFireTimeout(startToFire);
        timer.setStartedEventId(started.getEventId());
        timer.setDecisionTaskCompletedEventId(decisionEventId);
        timer.setStartedTimestamp(started.getEventTimestamp());
        timer.setFireTimestamp(started.getEventTimestamp() + parseSeconds(startToFire, 0));
        execution.getTimers().put(timerId, timer);
    }

    private void cancelTimer(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        String timerId = decision.string("timerId");
        SwfTimer timer = execution.getTimers().get(timerId);
        if (timer == null || timer.isCanceled()) {
            appendEvent(execution, "CancelTimerFailed")
                    .attr("timerId", nullToEmpty(timerId))
                    .attr("cause", "TIMER_ID_UNKNOWN")
                    .attr("decisionTaskCompletedEventId", decisionEventId);
            execution.setDecisionNeeded(true);
            return;
        }
        appendEvent(execution, "TimerCanceled")
                .attr("timerId", timerId)
                .attr("startedEventId", timer.getStartedEventId())
                .attr("decisionTaskCompletedEventId", decisionEventId);
        timer.setCanceled(true);
        execution.getTimers().remove(timerId);
    }

    private void signalExternal(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        String targetWorkflowId = decision.string("workflowId");
        String targetRunId = decision.string("runId");
        String signalName = decision.string("signalName");

        SwfHistoryEvent initiated = appendEvent(execution, "SignalExternalWorkflowExecutionInitiated");
        initiated.attr("workflowId", targetWorkflowId)
                .attr("runId", targetRunId)
                .attr("signalName", signalName)
                .attr("input", decision.string("input"))
                .attr("decisionTaskCompletedEventId", decisionEventId)
                .attr("control", decision.string("control"));

        SwfWorkflowExecution target = resolveTarget(execution.getRegion(), execution.getDomain(), targetWorkflowId, targetRunId);
        if (target == null || !target.isOpen()) {
            appendEvent(execution, "SignalExternalWorkflowExecutionFailed")
                    .attr("workflowId", nullToEmpty(targetWorkflowId))
                    .attr("runId", targetRunId)
                    .attr("cause", "UNKNOWN_EXTERNAL_WORKFLOW_EXECUTION")
                    .attr("initiatedEventId", initiated.getEventId())
                    .attr("decisionTaskCompletedEventId", decisionEventId)
                    .attr("control", decision.string("control"));
            execution.setDecisionNeeded(true);
            return;
        }

        deliverSignal(target, signalName, decision.string("input"),
                Map.of("workflowId", execution.getWorkflowId(), "runId", execution.getRunId()),
                initiated.getEventId());
        appendEvent(execution, "ExternalWorkflowExecutionSignaled")
                .attr("workflowExecution", Map.of("workflowId", target.getWorkflowId(),
                        "runId", target.getRunId()))
                .attr("initiatedEventId", initiated.getEventId());
    }

    private void cancelExternal(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        String targetWorkflowId = decision.string("workflowId");
        String targetRunId = decision.string("runId");

        SwfHistoryEvent initiated = appendEvent(execution, "RequestCancelExternalWorkflowExecutionInitiated");
        initiated.attr("workflowId", targetWorkflowId)
                .attr("runId", targetRunId)
                .attr("decisionTaskCompletedEventId", decisionEventId)
                .attr("control", decision.string("control"));

        SwfWorkflowExecution target = resolveTarget(execution.getRegion(), execution.getDomain(), targetWorkflowId, targetRunId);
        if (target == null || !target.isOpen()) {
            appendEvent(execution, "RequestCancelExternalWorkflowExecutionFailed")
                    .attr("workflowId", nullToEmpty(targetWorkflowId))
                    .attr("runId", targetRunId)
                    .attr("cause", "UNKNOWN_EXTERNAL_WORKFLOW_EXECUTION")
                    .attr("initiatedEventId", initiated.getEventId())
                    .attr("decisionTaskCompletedEventId", decisionEventId)
                    .attr("control", decision.string("control"));
            execution.setDecisionNeeded(true);
            return;
        }

        deliverCancelRequest(target, Map.of("workflowId", execution.getWorkflowId(),
                "runId", execution.getRunId()), initiated.getEventId(), null);
        appendEvent(execution, "ExternalWorkflowExecutionCancelRequested")
                .attr("workflowExecution", Map.of("workflowId", target.getWorkflowId(),
                        "runId", target.getRunId()))
                .attr("initiatedEventId", initiated.getEventId());
    }

    private void startChild(SwfWorkflowExecution execution, Decision decision, long decisionEventId) {
        String childWorkflowId = decision.string("workflowId");
        String typeName = decision.nested("workflowType", "name");
        String typeVersion = decision.nested("workflowType", "version");

        SwfWorkflowType type = workflowTypeStore.get(typeKey(execution.getRegion(), execution.getDomain(), typeName, typeVersion))
                .orElse(null);
        if (type == null || type.isDeprecated()) {
            appendStartChildFailed(execution, decision, decisionEventId,
                    type == null ? "WORKFLOW_TYPE_DOES_NOT_EXIST" : "WORKFLOW_TYPE_DEPRECATED", 0L);
            return;
        }
        if (findOpenExecution(execution.getRegion(), execution.getDomain(), childWorkflowId).isPresent()) {
            appendStartChildFailed(execution, decision, decisionEventId, "WORKFLOW_ALREADY_RUNNING", 0L);
            return;
        }

        SwfHistoryEvent initiated = appendEvent(execution, "StartChildWorkflowExecutionInitiated");
        String childPolicy = firstNonEmpty(decision.string("childPolicy"), type.getDefaultChildPolicy());
        String taskList = firstNonEmpty(decision.nested("taskList", "name"), type.getDefaultTaskList());
        initiated.attr("workflowId", childWorkflowId)
                .attr("workflowType", Map.of("name", typeName, "version", typeVersion))
                .attr("control", decision.string("control"))
                .attr("input", decision.string("input"))
                .attr("executionStartToCloseTimeout", firstNonEmpty(
                        decision.string("executionStartToCloseTimeout"),
                        type.getDefaultExecutionStartToCloseTimeout()))
                .attr("taskList", taskList != null ? Map.of("name", taskList) : null)
                .attr("taskPriority", firstNonEmpty(decision.string("taskPriority"), type.getDefaultTaskPriority()))
                .attr("decisionTaskCompletedEventId", decisionEventId)
                .attr("childPolicy", childPolicy)
                .attr("taskStartToCloseTimeout", firstNonEmpty(decision.string("taskStartToCloseTimeout"),
                        type.getDefaultTaskStartToCloseTimeout()))
                .attr("tagList", decision.stringList("tagList"))
                .attr("lambdaRole", firstNonEmpty(decision.string("lambdaRole"), type.getDefaultLambdaRole()));

        StartWorkflowExecutionRequest request = new StartWorkflowExecutionRequest(
                execution.getRegion(), execution.getDomain(), childWorkflowId, typeName, typeVersion,
                decision.nested("taskList", "name"), decision.string("taskPriority"),
                decision.string("input"), decision.string("executionStartToCloseTimeout"),
                decision.string("taskStartToCloseTimeout"), decision.string("childPolicy"),
                decision.stringList("tagList"), decision.string("lambdaRole"));

        SwfWorkflowExecution child;
        try {
            child = buildExecution(request, type);
        } catch (AwsException e) {
            LOG.debugv("Child workflow {0} rejected: {1}", childWorkflowId, e.getMessage());
            appendStartChildFailed(execution, decision, decisionEventId,
                    causeForDefaultUndefined(e.getMessage()), initiated.getEventId());
            return;
        }
        child.setParentWorkflowId(execution.getWorkflowId());
        child.setParentRunId(execution.getRunId());
        child.setParentInitiatedEventId(initiated.getEventId());

        appendWorkflowExecutionStarted(child, decision.string("input"), null);
        scheduleDecisionTask(child);
        execution.getChildExecutions().put(childWorkflowId, child.getRunId());

        SwfHistoryEvent childStarted = appendEvent(execution, "ChildWorkflowExecutionStarted");
        childStarted.attr("workflowExecution", Map.of("workflowId", child.getWorkflowId(),
                        "runId", child.getRunId()))
                .attr("workflowType", Map.of("name", typeName, "version", typeVersion))
                .attr("initiatedEventId", initiated.getEventId());

        // The child reports this id back on startedEventId of every
        // ChildWorkflowExecution* event, so it has to be stored before the child is saved.
        child.setParentStartedEventId(childStarted.getEventId());
        executionStore.put(executionKey(child), child);
    }

    /** Maps a DefaultUndefinedFault message onto the matching child-start failure cause. */
    private String causeForDefaultUndefined(String message) {
        if (message == null) {
            return "OPERATION_NOT_PERMITTED";
        }
        return switch (message) {
            case "defaultExecutionStartToCloseTimeout" -> "DEFAULT_EXECUTION_START_TO_CLOSE_TIMEOUT_UNDEFINED";
            case "defaultTaskStartToCloseTimeout" -> "DEFAULT_TASK_START_TO_CLOSE_TIMEOUT_UNDEFINED";
            case "defaultTaskList" -> "DEFAULT_TASK_LIST_UNDEFINED";
            case "defaultChildPolicy" -> "DEFAULT_CHILD_POLICY_UNDEFINED";
            default -> "OPERATION_NOT_PERMITTED";
        };
    }

    private void appendStartChildFailed(SwfWorkflowExecution execution, Decision decision,
                                        long decisionEventId, String cause, long initiatedEventId) {
        appendEvent(execution, "StartChildWorkflowExecutionFailed")
                .attr("workflowType", Map.of(
                        "name", nullToEmpty(decision.nested("workflowType", "name")),
                        "version", nullToEmpty(decision.nested("workflowType", "version"))))
                .attr("cause", cause)
                .attr("workflowId", nullToEmpty(decision.string("workflowId")))
                .attr("initiatedEventId", initiatedEventId)
                .attr("decisionTaskCompletedEventId", decisionEventId)
                .attr("control", decision.string("control"));
        execution.setDecisionNeeded(true);
    }

    /**
     * Invokes the Lambda function the decider asked for, through Floci's own Lambda service,
     * and records the outcome as the live service does.
     *
     * <p>Verified against the live service: a successful call appends Scheduled, Started, then
     * Completed with the function's response as {@code result}; a function that cannot be
     * resolved still gets Scheduled and Started, then Failed with the SDK's error code as
     * {@code reason}; and an execution with no {@code lambdaRole} never reaches Started at all,
     * appending StartLambdaFunctionFailed with cause ASSUME_ROLE_FAILED instead. Reusing an
     * {@code id} is accepted — SWF does not report ID_ALREADY_IN_USE for Lambda invocations the
     * way it does for activity ids.
     *
     * <p>The invocation is synchronous here rather than dispatched to the sweeper: floci's
     * Lambda service runs the function in a container it manages and returns the payload, so
     * the natural place to record Completed/Failed is the same decision that scheduled it.
     */
    private void scheduleLambdaFunction(SwfWorkflowExecution execution, Decision decision,
                                        List<PendingLambda> queued, long decisionEventId) {
        String id = nullToEmpty(decision.string("id"));
        String name = decision.string("name");

        SwfHistoryEvent scheduled = appendEvent(execution, "LambdaFunctionScheduled");
        scheduled.attr("id", id)
                .attr("name", nullToEmpty(name))
                .attr("control", decision.string("control"))
                .attr("input", decision.string("input"))
                .attr("startToCloseTimeout", decision.string("startToCloseTimeout"))
                .attr("decisionTaskCompletedEventId", decisionEventId);

        // No role means SWF cannot assume anything to invoke with, so the function is never
        // started. This is the one path that skips LambdaFunctionStarted entirely.
        if (execution.getLambdaRole() == null || execution.getLambdaRole().isEmpty()) {
            appendEvent(execution, "StartLambdaFunctionFailed")
                    .attr("scheduledEventId", scheduled.getEventId())
                    .attr("cause", "ASSUME_ROLE_FAILED")
                    .attr("message", "No IAM role is attached to the current workflow execution.");
            execution.setDecisionNeeded(true);
            return;
        }

        SwfHistoryEvent started = appendEvent(execution, "LambdaFunctionStarted");
        started.attr("scheduledEventId", scheduled.getEventId());

        // An empty input is delivered to the handler as {}, matching the live service.
        String input = decision.string("input");
        byte[] payload = (input == null || input.isEmpty() ? "{}" : input)
                .getBytes(StandardCharsets.UTF_8);

        // The invocation runs once the domain lock is released: a cold start takes seconds and
        // this lock deliberately covers every execution in the domain.
        queued.add(new PendingLambda(executionKey(execution), id, name, payload,
                scheduled.getEventId(), started.getEventId()));
    }

    /** A Lambda invocation a decision queued, to run once the domain lock is released. */
    private record PendingLambda(String executionKey, String id, String name, byte[] payload,
                                 long scheduledEventId, long startedEventId) {
    }

    /**
     * Invokes a queued function with no lock held, then re-takes the domain lock to record the
     * outcome as one atomic transition.
     */
    private void runPendingLambda(PendingLambda pending) {
        String eventType;
        String reason = null;
        String details = null;
        String result = null;
        try {
            LambdaInvocationResult invoked = lambdaInvoker.invoke(
                    regionOf(pending.executionKey()), pending.name(), pending.payload());
            if (invoked.functionError() != null && !invoked.functionError().isEmpty()) {
                eventType = "LambdaFunctionFailed";
                reason = invoked.functionError();
                details = invoked.payload();
            } else {
                eventType = "LambdaFunctionCompleted";
                result = invoked.payload();
            }
        } catch (AwsException e) {
            // The live service surfaces the Lambda SDK's own error code and message here, so a
            // decider sees ResourceNotFoundException for a function that does not exist.
            eventType = "LambdaFunctionFailed";
            reason = e.getErrorCode();
            details = e.getMessage();
        } catch (RuntimeException e) {
            LOG.warnv("SWF Lambda invocation {0} ({1}) failed: {2}",
                    pending.id(), pending.name(), e.getMessage());
            eventType = "LambdaFunctionFailed";
            reason = e.getClass().getSimpleName();
            details = nullToEmpty(e.getMessage());
        }

        synchronized (domainLockForKey(pending.executionKey())) {
            SwfWorkflowExecution execution = executionStore.get(pending.executionKey()).orElse(null);
            if (execution == null || !execution.isOpen()) {
                return;
            }
            appendEvent(execution, eventType)
                    .attr("scheduledEventId", pending.scheduledEventId())
                    .attr("startedEventId", pending.startedEventId())
                    .attr("reason", reason)
                    .attr("details", details)
                    .attr("result", result);
            if (hasNothingPending(execution)) {
                scheduleDecisionTask(execution);
            } else {
                execution.setDecisionNeeded(true);
            }
            executionStore.put(pending.executionKey(), execution);
        }
    }

    /** The region segment of a {@code <region>:<domain>/...} storage key. */
    private static String regionOf(String executionKey) {
        int colon = executionKey.indexOf(':');
        return colon < 0 ? executionKey : executionKey.substring(0, colon);
    }

    // ────────────────────────────── Activity tasks ───────────────────────────

    public Optional<SwfActivityTask> pollForActivityTask(String region, String domainName, String taskList, String identity) {
        requireDomain(region, domainName);
        requireName(taskList, "taskList.name");

        List<SwfWorkflowExecution> candidates = executionsIn(region, domainName);
        candidates.sort(Comparator.comparingDouble(SwfWorkflowExecution::getStartTimestamp));
        for (SwfWorkflowExecution candidate : candidates) {
            SwfActivityTask claimed = tryClaimActivityTask(candidate, taskList, identity);
            if (claimed != null) {
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    private SwfActivityTask tryClaimActivityTask(SwfWorkflowExecution candidate, String taskList, String identity) {
        synchronized (domainLock(candidate)) {
            SwfWorkflowExecution execution = executionStore.get(executionKey(candidate)).orElse(null);
            if (execution == null || !execution.isOpen()) {
                return null;
            }
            SwfActivityTask task = execution.getActivities().values().stream()
                    .filter(t -> SwfActivityTask.STATE_SCHEDULED.equals(t.getState()))
                    .filter(t -> taskList.equals(t.getTaskList()))
                    .min(Comparator.comparingDouble(SwfActivityTask::getScheduledTimestamp))
                    .orElse(null);
            if (task == null) {
                return null;
            }

            task.setState(SwfActivityTask.STATE_STARTED);
            task.setIdentity(identity);
            double startedAt = now();
            task.setStartedTimestamp(startedAt);
            task.setLastHeartbeatTimestamp(startedAt);
            SwfHistoryEvent started = appendEvent(execution, "ActivityTaskStarted");
            started.attr("identity", identity).attr("scheduledEventId", task.getScheduledEventId());
            task.setStartedEventId(started.getEventId());
            execution.setLatestActivityTaskTimestamp(started.getEventTimestamp());

            String token = newTaskToken();
            task.setTaskToken(token);
            activityTokens.put(token, executionKey(execution) + "|" + task.getActivityId());
            executionStore.put(executionKey(execution), execution);
            return task;
        }
    }

    /** True when the worker holding {@code taskToken} should abandon the activity. */
    public boolean recordActivityTaskHeartbeat(String taskToken, String details) {
        return withActivityToken(taskToken, (execution, task) -> {
            task.setLastHeartbeatTimestamp(now());
            if (details != null) {
                task.setControl(details);
            }
            executionStore.put(executionKey(execution), execution);
            return task.isCancelRequested();
        });
    }

    public void respondActivityTaskCompleted(String taskToken, String result) {
        withActivityToken(taskToken, (execution, task) -> {
            appendEvent(execution, "ActivityTaskCompleted")
                    .attr("result", result)
                    .attr("scheduledEventId", task.getScheduledEventId())
                    .attr("startedEventId", task.getStartedEventId());
            closeActivity(execution, task);
            return null;
        });
    }

    public void respondActivityTaskFailed(String taskToken, String reason, String details) {
        withActivityToken(taskToken, (execution, task) -> {
            appendEvent(execution, "ActivityTaskFailed")
                    .attr("reason", reason)
                    .attr("details", details)
                    .attr("scheduledEventId", task.getScheduledEventId())
                    .attr("startedEventId", task.getStartedEventId());
            closeActivity(execution, task);
            return null;
        });
    }

    public void respondActivityTaskCanceled(String taskToken, String details) {
        withActivityToken(taskToken, (execution, task) -> {
            appendEvent(execution, "ActivityTaskCanceled")
                    .attr("details", details)
                    .attr("scheduledEventId", task.getScheduledEventId())
                    .attr("startedEventId", task.getStartedEventId())
                    .attr("latestCancelRequestedEventId", task.getLatestCancelRequestedEventId());
            closeActivity(execution, task);
            return null;
        });
    }

    private void closeActivity(SwfWorkflowExecution execution, SwfActivityTask task) {
        task.setState(SwfActivityTask.STATE_CLOSED);
        // The token mapping is deliberately retained: withActivityToken needs it to tell a
        // genuine token whose task has closed (Unknown activity, scheduledEventId = N) from
        // a token that never existed. Reuse is still refused by the isOpen() check there.
        scheduleDecisionTaskIfIdle(execution);
        executionStore.put(executionKey(execution), execution);
    }

    private <T> T withActivityToken(String taskToken, ActivityTokenAction<T> action) {
        if (taskToken == null || taskToken.isEmpty()) {
            throw SwfFaults.missingRequired("taskToken");
        }
        String pointer = activityTokens.get(taskToken);
        if (pointer == null) {
            throw SwfFaults.unknownTaskToken();
        }
        int separator = pointer.lastIndexOf('|');
        String executionKey = pointer.substring(0, separator);
        String activityId = pointer.substring(separator + 1);

        synchronized (domainLockForKey(executionKey)) {
            SwfWorkflowExecution execution = executionStore.get(executionKey)
                    .orElseThrow(SwfFaults::unknownTaskToken);
            SwfActivityTask task = execution.getActivities().get(activityId);
            if (task == null || !taskToken.equals(task.getTaskToken())) {
                throw SwfFaults.unknownTaskToken();
            }
            if (!task.isOpen()) {
                // The token is genuine but the task already closed, so the live service
                // names the scheduled event instead of calling the token unknown.
                throw SwfFaults.unknownActivity(task.getScheduledEventId());
            }
            return action.apply(execution, task);
        }
    }

    private interface ActivityTokenAction<T> {
        T apply(SwfWorkflowExecution execution, SwfActivityTask task);
    }

    // ───────────────────────── External execution control ────────────────────

    public void signalWorkflowExecution(String region, String domainName, String workflowId, String runId,
                                        String signalName, String input) {
        requireDomain(region, domainName);
        requireName(signalName, "signalName");
        SwfWorkflowExecution located = requireExecution(region, domainName, workflowId, runId);
        synchronized (domainLock(located)) {
            SwfWorkflowExecution execution = executionStore.get(executionKey(located))
                    .orElseThrow(() -> SwfFaults.unknownExecution(workflowId, runId));
            if (!execution.isOpen()) {
                throw SwfFaults.unknownExecution(workflowId, nullToEmpty(runId));
            }
            deliverSignal(execution, signalName, input, null, null);
        }
    }

    private void deliverSignal(SwfWorkflowExecution execution, String signalName, String input,
                               Map<String, String> externalExecution, Long externalInitiatedEventId) {
        appendEvent(execution, "WorkflowExecutionSignaled")
                .attr("signalName", signalName)
                .attr("input", input)
                .attr("externalWorkflowExecution", externalExecution)
                .attr("externalInitiatedEventId", externalInitiatedEventId);
        scheduleDecisionTaskIfIdle(execution);
        executionStore.put(executionKey(execution), execution);
    }

    public void requestCancelWorkflowExecution(String region, String domainName, String workflowId, String runId) {
        requireDomain(region, domainName);
        SwfWorkflowExecution located = requireExecution(region, domainName, workflowId, runId);
        synchronized (domainLock(located)) {
            SwfWorkflowExecution execution = executionStore.get(executionKey(located))
                    .orElseThrow(() -> SwfFaults.unknownExecution(workflowId, runId));
            if (!execution.isOpen()) {
                throw SwfFaults.unknownExecution(workflowId, nullToEmpty(runId));
            }
            deliverCancelRequest(execution, null, null, null);
        }
    }

    private void deliverCancelRequest(SwfWorkflowExecution execution, Map<String, String> externalExecution,
                                      Long externalInitiatedEventId, String cause) {
        appendEvent(execution, "WorkflowExecutionCancelRequested")
                .attr("externalWorkflowExecution", externalExecution)
                .attr("externalInitiatedEventId", externalInitiatedEventId)
                .attr("cause", cause);
        execution.setCancelRequested(true);
        scheduleDecisionTaskIfIdle(execution);
        executionStore.put(executionKey(execution), execution);
    }

    public void terminateWorkflowExecution(String region, String domainName, String workflowId, String runId,
                                           String reason, String details, String childPolicy) {
        requireDomain(region, domainName);
        SwfWorkflowExecution located = requireExecution(region, domainName, workflowId, runId);
        synchronized (domainLock(located)) {
            SwfWorkflowExecution execution = executionStore.get(executionKey(located))
                    .orElseThrow(() -> SwfFaults.unknownExecution(workflowId, runId));
            if (!execution.isOpen()) {
                throw SwfFaults.unknownExecution(workflowId, nullToEmpty(runId));
            }
            String policy = validateChildPolicy(firstNonEmpty(childPolicy, execution.getChildPolicy()));
            terminate(execution, reason, details, policy, null);
        }
    }

    private void terminate(SwfWorkflowExecution execution, String reason, String details,
                           String childPolicy, String cause) {
        appendEvent(execution, "WorkflowExecutionTerminated")
                .attr("reason", reason)
                .attr("details", details)
                .attr("childPolicy", childPolicy)
                .attr("cause", cause);
        closeExecution(execution, SwfConstants.CLOSE_STATUS_TERMINATED);
        executionStore.put(executionKey(execution), execution);
    }

    // ──────────────────────────────── Tagging ────────────────────────────────

    public Map<String, String> listTagsForResource(String resourceArn) {
        return domainForArn(resourceArn).getTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags) {
        SwfDomain domain = domainForArn(resourceArn);
        if (tags != null) {
            if (domain.getTags().size() + tags.size() > MAX_TAGS) {
                throw SwfFaults.fault(SwfConstants.TOO_MANY_TAGS, domain.getName());
            }
            domain.getTags().putAll(tags);
        }
        domainStore.put(domain.getName(), domain);
    }

    public void untagResource(String resourceArn, List<String> tagKeys) {
        SwfDomain domain = domainForArn(resourceArn);
        if (tagKeys != null) {
            tagKeys.forEach(domain.getTags()::remove);
        }
        domainStore.put(domain.getName(), domain);
    }

    /**
     * SWF tagging targets a domain by ARN. The domain name is the segment after
     * {@code /domain/}; anything else is not a taggable SWF resource.
     *
     * <p>The region is read out of the ARN rather than the request: an ARN already names its
     * region, and tagging a domain in another region must not resolve to the caller's.
     */
    private SwfDomain domainForArn(String resourceArn) {
        if (resourceArn == null || resourceArn.isEmpty()) {
            throw SwfFaults.missingRequired("resourceArn");
        }
        int marker = resourceArn.indexOf("/domain/");
        if (marker < 0) {
            throw SwfFaults.unknownDomain(resourceArn);
        }
        String name = resourceArn.substring(marker + "/domain/".length());
        // arn:aws:swf:<region>:<account>:/domain/<name>
        String[] parts = resourceArn.split(":", 5);
        String arnRegion = parts.length >= 4 ? parts[3] : regionResolver.getDefaultRegion();
        return domainStore.get(domainKey(arnRegion, name))
                .orElseThrow(() -> SwfFaults.unknownDomain(name));
    }

    // ──────────────────────────────── Timeouts ───────────────────────────────

    /**
     * Applies every timeout that has come due: activity schedule-to-start,
     * start-to-close, schedule-to-close and heartbeat; decision task start-to-close;
     * workflow execution start-to-close; and pending timers.
     *
     * <p>Called on a fixed interval by {@link SwfTimeoutSweeper} rather than from a
     * per-task timer, so a restart cannot leave orphaned timeout threads behind.
     */
    public void sweep() {
        double nowSeconds = now();
        for (String key : executionStore.keys()) {
            synchronized (domainLockForKey(key)) {
                SwfWorkflowExecution execution = executionStore.get(key).orElse(null);
                if (execution == null || !execution.isOpen()) {
                    continue;
                }
                boolean changed = fireTimers(execution, nowSeconds)
                        | timeOutActivities(execution, nowSeconds)
                        | timeOutDecisionTask(execution, nowSeconds);
                if (timeOutExecution(execution, nowSeconds)) {
                    changed = true;
                } else if (changed && execution.isOpen()) {
                    scheduleDecisionTaskIfIdle(execution);
                }
                if (changed) {
                    executionStore.put(key, execution);
                }
            }
        }
    }

    private boolean fireTimers(SwfWorkflowExecution execution, double nowSeconds) {
        List<SwfTimer> due = execution.getTimers().values().stream()
                .filter(t -> !t.isCanceled() && t.getFireTimestamp() <= nowSeconds)
                .sorted(Comparator.comparingDouble(SwfTimer::getFireTimestamp))
                .toList();
        for (SwfTimer timer : due) {
            appendEvent(execution, "TimerFired")
                    .attr("timerId", timer.getTimerId())
                    .attr("startedEventId", timer.getStartedEventId());
            execution.getTimers().remove(timer.getTimerId());
        }
        return !due.isEmpty();
    }

    private boolean timeOutActivities(SwfWorkflowExecution execution, double nowSeconds) {
        boolean changed = false;
        for (SwfActivityTask task : new ArrayList<>(execution.getActivities().values())) {
            if (!task.isOpen()) {
                continue;
            }
            String timeoutType = dueActivityTimeout(task, nowSeconds);
            if (timeoutType == null) {
                continue;
            }
            appendEvent(execution, "ActivityTaskTimedOut")
                    .attr("timeoutType", timeoutType)
                    .attr("scheduledEventId", task.getScheduledEventId())
                    .attr("startedEventId", task.getStartedEventId() != null ? task.getStartedEventId() : 0L);
            task.setState(SwfActivityTask.STATE_CLOSED);
            if (task.getTaskToken() != null) {
                activityTokens.remove(task.getTaskToken());
            }
            changed = true;
        }
        return changed;
    }

    /**
     * The first activity timeout that has elapsed, or null. SCHEDULE_TO_CLOSE is checked
     * first because it bounds the others; HEARTBEAT only applies once the task is started
     * and a heartbeat timeout was configured.
     */
    private String dueActivityTimeout(SwfActivityTask task, double nowSeconds) {
        Double scheduleToClose = timeoutSeconds(task.getScheduleToCloseTimeout());
        if (scheduleToClose != null && nowSeconds - task.getScheduledTimestamp() >= scheduleToClose) {
            return "SCHEDULE_TO_CLOSE";
        }
        if (SwfActivityTask.STATE_SCHEDULED.equals(task.getState())) {
            Double scheduleToStart = timeoutSeconds(task.getScheduleToStartTimeout());
            if (scheduleToStart != null && nowSeconds - task.getScheduledTimestamp() >= scheduleToStart) {
                return "SCHEDULE_TO_START";
            }
            return null;
        }
        Double startToClose = timeoutSeconds(task.getStartToCloseTimeout());
        if (startToClose != null && task.getStartedTimestamp() != null
                && nowSeconds - task.getStartedTimestamp() >= startToClose) {
            return "START_TO_CLOSE";
        }
        Double heartbeat = timeoutSeconds(task.getHeartbeatTimeout());
        if (heartbeat != null && task.getLastHeartbeatTimestamp() != null
                && nowSeconds - task.getLastHeartbeatTimestamp() >= heartbeat) {
            return "HEARTBEAT";
        }
        return null;
    }

    private boolean timeOutDecisionTask(SwfWorkflowExecution execution, double nowSeconds) {
        SwfDecisionTask task = execution.getDecisionTask();
        if (task == null || !SwfDecisionTask.STATE_STARTED.equals(task.getState())) {
            return false;
        }
        Double startToClose = timeoutSeconds(task.getStartToCloseTimeout());
        if (startToClose == null || task.getStartedTimestamp() == null
                || nowSeconds - task.getStartedTimestamp() < startToClose) {
            return false;
        }
        appendEvent(execution, "DecisionTaskTimedOut")
                .attr("timeoutType", "START_TO_CLOSE")
                .attr("scheduledEventId", task.getScheduledEventId())
                .attr("startedEventId", task.getStartedEventId());
        if (task.getTaskToken() != null) {
            decisionTokens.remove(task.getTaskToken());
        }
        execution.setDecisionTask(null);
        execution.setDecisionTaskOutstanding(false);
        scheduleDecisionTask(execution);
        return true;
    }

    private boolean timeOutExecution(SwfWorkflowExecution execution, double nowSeconds) {
        Double startToClose = timeoutSeconds(execution.getExecutionStartToCloseTimeout());
        if (startToClose == null || nowSeconds - execution.getStartTimestamp() < startToClose) {
            return false;
        }
        appendEvent(execution, "WorkflowExecutionTimedOut")
                .attr("timeoutType", "START_TO_CLOSE")
                .attr("childPolicy", execution.getChildPolicy());
        closeExecution(execution, SwfConstants.CLOSE_STATUS_TIMED_OUT);
        return true;
    }

    // ─────────────────────────── History and lifecycle ───────────────────────

    private void appendWorkflowExecutionStarted(SwfWorkflowExecution execution, String input,
                                                String continuedFromRunId) {
        SwfHistoryEvent event = appendEvent(execution, "WorkflowExecutionStarted");
        event.attr("input", input)
                .attr("executionStartToCloseTimeout", execution.getExecutionStartToCloseTimeout())
                .attr("taskStartToCloseTimeout", execution.getTaskStartToCloseTimeout())
                .attr("childPolicy", execution.getChildPolicy())
                .attr("taskList", Map.of("name", execution.getTaskList()))
                .attr("taskPriority", execution.getTaskPriority())
                .attr("workflowType", Map.of("name", execution.getWorkflowTypeName(),
                        "version", execution.getWorkflowTypeVersion()))
                .attr("tagList", execution.getTagList().isEmpty() ? null
                        : new ArrayList<>(execution.getTagList()))
                .attr("continuedExecutionRunId", continuedFromRunId)
                .attr("lambdaRole", execution.getLambdaRole());
        if (execution.getParentWorkflowId() != null) {
            event.attr("parentWorkflowExecution", Map.of("workflowId", execution.getParentWorkflowId(),
                            "runId", execution.getParentRunId()))
                    .attr("parentInitiatedEventId", execution.getParentInitiatedEventId());
        } else {
            // The live service reports 0 rather than omitting the member on root executions.
            event.attr("parentInitiatedEventId", 0L);
        }
    }

    private void scheduleDecisionTask(SwfWorkflowExecution execution) {
        SwfHistoryEvent scheduled = appendEvent(execution, "DecisionTaskScheduled");
        scheduled.attr("taskList", Map.of("name", execution.getTaskList()))
                .attr("taskPriority", execution.getTaskPriority())
                .attr("startToCloseTimeout", execution.getTaskStartToCloseTimeout())
                .attr("scheduleToStartTimeout", SwfConstants.TIMEOUT_NONE);

        SwfDecisionTask task = new SwfDecisionTask();
        task.setDomain(execution.getDomain());
        task.setWorkflowId(execution.getWorkflowId());
        task.setRunId(execution.getRunId());
        task.setTaskList(execution.getTaskList());
        task.setTaskPriority(execution.getTaskPriority());
        task.setStartToCloseTimeout(execution.getTaskStartToCloseTimeout());
        task.setScheduledEventId(scheduled.getEventId());
        task.setScheduledTimestamp(scheduled.getEventTimestamp());
        task.setPreviousStartedEventId(lastStartedDecisionEventId(execution));
        execution.setDecisionTask(task);
        execution.setDecisionTaskOutstanding(true);
    }

    /**
     * Schedules a decision task unless one is already outstanding. SWF permits only one
     * decision task per execution at a time; while one is outstanding the need is recorded
     * and satisfied when that task completes.
     */
    private void scheduleDecisionTaskIfIdle(SwfWorkflowExecution execution) {
        if (!execution.isOpen()) {
            return;
        }
        if (execution.isDecisionTaskOutstanding()) {
            execution.setDecisionNeeded(true);
            return;
        }
        scheduleDecisionTask(execution);
    }

    private long lastStartedDecisionEventId(SwfWorkflowExecution execution) {
        long last = 0;
        for (SwfHistoryEvent event : execution.getEvents()) {
            if ("DecisionTaskStarted".equals(event.getEventType())) {
                last = event.getEventId();
            }
        }
        return last;
    }

    private SwfHistoryEvent appendEvent(SwfWorkflowExecution execution, String eventType) {
        SwfHistoryEvent event = new SwfHistoryEvent(execution.allocateEventId(), now(), eventType);
        execution.getEvents().add(event);
        return event;
    }

    private void closeExecution(SwfWorkflowExecution execution, String closeStatus) {
        execution.setExecutionStatus(SwfConstants.EXECUTION_STATUS_CLOSED);
        execution.setCloseStatus(closeStatus);
        execution.setCloseTimestamp(now());

        SwfDecisionTask task = execution.getDecisionTask();
        if (task != null && task.getTaskToken() != null) {
            decisionTokens.remove(task.getTaskToken());
        }
        execution.setDecisionTask(null);
        execution.setDecisionTaskOutstanding(false);
        for (SwfActivityTask activity : execution.getActivities().values()) {
            if (activity.getTaskToken() != null) {
                activityTokens.remove(activity.getTaskToken());
            }
            activity.setState(SwfActivityTask.STATE_CLOSED);
        }
        execution.getTimers().clear();

        notifyParent(execution);
        applyChildPolicy(execution);
    }

    /**
     * Reports a child's outcome to its parent so the parent's decider observes the
     * matching ChildWorkflowExecution* event. CONTINUED_AS_NEW is deliberately silent:
     * the continuation run carries the parent link and reports for itself.
     */
    private void notifyParent(SwfWorkflowExecution execution) {
        if (execution.getParentWorkflowId() == null || execution.getParentRunId() == null) {
            return;
        }
        if (SwfConstants.CLOSE_STATUS_CONTINUED_AS_NEW.equals(execution.getCloseStatus())) {
            return;
        }
        String parentKey = executionKey(execution.getRegion(), execution.getDomain(),
                execution.getParentWorkflowId(), execution.getParentRunId());
        SwfWorkflowExecution parent = executionStore.get(parentKey).orElse(null);
        if (parent == null || !parent.isOpen()) {
            return;
        }

        String eventType = switch (execution.getCloseStatus()) {
            case SwfConstants.CLOSE_STATUS_COMPLETED -> "ChildWorkflowExecutionCompleted";
            case SwfConstants.CLOSE_STATUS_FAILED -> "ChildWorkflowExecutionFailed";
            case SwfConstants.CLOSE_STATUS_CANCELED -> "ChildWorkflowExecutionCanceled";
            case SwfConstants.CLOSE_STATUS_TERMINATED -> "ChildWorkflowExecutionTerminated";
            case SwfConstants.CLOSE_STATUS_TIMED_OUT -> "ChildWorkflowExecutionTimedOut";
            default -> null;
        };
        if (eventType == null) {
            return;
        }

        SwfHistoryEvent event = appendEvent(parent, eventType);
        event.attr("workflowExecution", Map.of("workflowId", execution.getWorkflowId(),
                        "runId", execution.getRunId()))
                .attr("workflowType", Map.of("name", execution.getWorkflowTypeName(),
                        "version", execution.getWorkflowTypeVersion()))
                .attr("initiatedEventId", execution.getParentInitiatedEventId())
                .attr("startedEventId", execution.getParentStartedEventId() != null
                        ? execution.getParentStartedEventId()
                        : execution.getParentInitiatedEventId());
        if (SwfConstants.CLOSE_STATUS_COMPLETED.equals(execution.getCloseStatus())) {
            event.attr("result", lastEventAttribute(execution, "WorkflowExecutionCompleted", "result"));
        } else if (SwfConstants.CLOSE_STATUS_FAILED.equals(execution.getCloseStatus())) {
            event.attr("reason", lastEventAttribute(execution, "WorkflowExecutionFailed", "reason"));
            event.attr("details", lastEventAttribute(execution, "WorkflowExecutionFailed", "details"));
        } else if (SwfConstants.CLOSE_STATUS_TIMED_OUT.equals(execution.getCloseStatus())) {
            event.attr("timeoutType", "START_TO_CLOSE");
        }

        scheduleDecisionTaskIfIdle(parent);
        executionStore.put(parentKey, parent);
    }

    private Object lastEventAttribute(SwfWorkflowExecution execution, String eventType, String attribute) {
        for (int i = execution.getEvents().size() - 1; i >= 0; i--) {
            SwfHistoryEvent event = execution.getEvents().get(i);
            if (eventType.equals(event.getEventType())) {
                return event.getAttributes().get(attribute);
            }
        }
        return null;
    }

    /**
     * Applies the closing execution's childPolicy to its still-open children:
     * TERMINATE ends them, REQUEST_CANCEL asks their deciders to wind down, and
     * ABANDON leaves them running.
     */
    private void applyChildPolicy(SwfWorkflowExecution execution) {
        if (execution.getChildExecutions().isEmpty()
                || SwfConstants.CHILD_POLICY_ABANDON.equals(execution.getChildPolicy())) {
            return;
        }
        for (Map.Entry<String, String> entry : execution.getChildExecutions().entrySet()) {
            String childKey = executionKey(execution.getRegion(), execution.getDomain(), entry.getKey(), entry.getValue());
            SwfWorkflowExecution child = executionStore.get(childKey).orElse(null);
            if (child == null || !child.isOpen()) {
                continue;
            }
            if (SwfConstants.CHILD_POLICY_TERMINATE.equals(execution.getChildPolicy())) {
                terminate(child, null, null, child.getChildPolicy(), "CHILD_POLICY_APPLIED");
            } else {
                deliverCancelRequest(child, null, null, "CHILD_POLICY_APPLIED");
            }
        }
    }

    /**
     * True when the execution still has activities, timers, or children the decider must
     * resolve before it may close the execution. SWF rejects a closing decision in that
     * state with UNHANDLED_DECISION.
     */
    private boolean hasOpenWork(SwfWorkflowExecution execution) {
        if (execution.getActivities().values().stream().anyMatch(SwfActivityTask::isOpen)) {
            return true;
        }
        if (!execution.getTimers().isEmpty()) {
            return true;
        }
        return execution.getChildExecutions().entrySet().stream().anyMatch(entry -> executionStore
                .get(executionKey(execution.getRegion(), execution.getDomain(), entry.getKey(), entry.getValue()))
                .filter(SwfWorkflowExecution::isOpen)
                .isPresent());
    }

    /**
     * True when nothing is pending, so the decider gets another decision task rather than
     * an execution stalled with no outstanding work and no scheduled decision.
     */
    private boolean hasNothingPending(SwfWorkflowExecution execution) {
        return !hasOpenWork(execution);
    }

    public int openActivityCount(SwfWorkflowExecution execution) {
        return (int) execution.getActivities().values().stream().filter(SwfActivityTask::isOpen).count();
    }

    public int openTimerCount(SwfWorkflowExecution execution) {
        return execution.getTimers().size();
    }

    public int openDecisionTaskCount(SwfWorkflowExecution execution) {
        return execution.getDecisionTask() != null ? 1 : 0;
    }

    public int openChildCount(SwfWorkflowExecution execution) {
        return (int) execution.getChildExecutions().entrySet().stream()
                .filter(entry -> executionStore
                        .get(executionKey(execution.getRegion(), execution.getDomain(), entry.getKey(), entry.getValue()))
                        .filter(SwfWorkflowExecution::isOpen)
                        .isPresent())
                .count();
    }

    // ──────────────────────────────── Helpers ───────────────────────────────

    private SwfWorkflowExecution resolveTarget(String region, String domainName, String workflowId, String runId) {
        if (workflowId == null || workflowId.isEmpty()) {
            return null;
        }
        if (runId != null && !runId.isEmpty()) {
            return executionStore.get(executionKey(region, domainName, workflowId, runId)).orElse(null);
        }
        return findOpenExecution(region, domainName, workflowId).orElse(null);
    }

    /**
     * One lock per region-qualified domain. See the class Javadoc: parent/child and multi-run
     * invariants span executions, so the lock has to cover the whole family to stay
     * non-nested. The region is part of the lock identity because the same domain name in two
     * regions is two independent domains — and because it must agree with
     * {@link #domainLockForKey(String)}, which derives the same scope from a storage key.
     */
    private Object domainLock(String region, String domain) {
        return domainLocks.computeIfAbsent(region + ":" + domain, k -> new Object());
    }

    /** Locks the region-qualified domain an execution already knows it belongs to. */
    private Object domainLock(SwfWorkflowExecution execution) {
        return domainLock(execution.getRegion(), execution.getDomain());
    }

    /**
     * Locks the domain of an execution identified only by its storage key. The key is
     * {@code <region>:<domain>/<workflowId>/<runId>}, so the segment before the first
     * {@code /} is the region-qualified domain — which is the right lock scope now that the
     * same domain name can exist in more than one region.
     */
    private Object domainLockForKey(String executionKey) {
        int separator = executionKey.indexOf('/');
        String qualifiedDomain = separator < 0 ? executionKey : executionKey.substring(0, separator);
        return domainLocks.computeIfAbsent(qualifiedDomain, k -> new Object());
    }

    private double now() {
        return clock.millis() / 1000.0;
    }

    /** SWF task tokens are opaque; a random base64 blob matches how SDKs treat them. */
    private String newTaskToken() {
        byte[] raw = (UUID.randomUUID() + ":" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(raw);
    }

    /** SWF runIds are base64-ish opaque strings ending in '='. */
    private String newRunId() {
        String raw = Base64.getEncoder().withoutPadding()
                .encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return raw.substring(0, Math.min(raw.length(), 48)) + "=";
    }

    private String domainArn(String name, String region) {
        return "arn:aws:swf:" + region + ":" + regionResolver.getAccountId() + ":/domain/" + name;
    }

    public String domainArnFor(SwfDomain domain, String region) {
        return domain.getArn() != null ? domain.getArn() : domainArn(domain.getName(), region);
    }

    /**
     * Storage keys are region-scoped: SWF names are unique per region, so the same domain
     * (and its types and executions) can exist independently in two regions.
     */
    private static String domainKey(String region, String domain) {
        return region + ":" + domain;
    }

    private static String typeKey(String region, String domain, String name, String version) {
        return region + ":" + domain + "/" + name + "/" + version;
    }

    private static String executionKey(SwfWorkflowExecution execution) {
        return executionKey(execution.getRegion(), execution.getDomain(),
                execution.getWorkflowId(), execution.getRunId());
    }

    private static String executionKey(String region, String domain, String workflowId, String runId) {
        return region + ":" + domain + "/" + workflowId + "/" + runId;
    }

    private static void requireName(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw SwfFaults.missingRequired(field);
        }
    }

    private static String requireRegistrationStatus(String status) {
        if (status == null || status.isEmpty()) {
            throw SwfFaults.missingRequired("registrationStatus");
        }
        if (!SwfConstants.STATUS_REGISTERED.equals(status) && !SwfConstants.STATUS_DEPRECATED.equals(status)) {
            throw SwfFaults.validationConstraint(status, "registrationStatus",
                    "Member must satisfy enum value set: [REGISTERED, DEPRECATED]");
        }
        return status;
    }

    private static String validateChildPolicy(String childPolicy) {
        if (childPolicy == null) {
            return null;
        }
        if (!SwfConstants.CHILD_POLICY_TERMINATE.equals(childPolicy)
                && !SwfConstants.CHILD_POLICY_REQUEST_CANCEL.equals(childPolicy)
                && !SwfConstants.CHILD_POLICY_ABANDON.equals(childPolicy)) {
            throw SwfFaults.validationConstraint(childPolicy, "childPolicy",
                    "Member must satisfy enum value set: [TERMINATE, REQUEST_CANCEL, ABANDON]");
        }
        return childPolicy;
    }

    private static void validateRetentionPeriod(String retentionDays) {
        if (SwfConstants.TIMEOUT_NONE.equals(retentionDays)) {
            return;
        }
        try {
            long days = Long.parseLong(retentionDays);
            if (days < 0 || days > 90) {
                throw SwfFaults.validationConstraint(retentionDays,
                        "workflowExecutionRetentionPeriodInDays",
                        "Member must be between 0 and 90 days, or NONE");
            }
        } catch (NumberFormatException e) {
            throw SwfFaults.validationConstraint(retentionDays, "workflowExecutionRetentionPeriodInDays",
                    "Member must be a number of days or NONE");
        }
    }

    /**
     * Timeout fields are strings holding seconds, or the literal "NONE" for no timeout.
     * Returns null when no timeout applies.
     */
    private static Double timeoutSeconds(String value) {
        if (value == null || value.isEmpty() || SwfConstants.TIMEOUT_NONE.equals(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            LOG.debugv("Ignoring unparseable SWF timeout value {0}", value);
            return null;
        }
    }

    private static double parseSeconds(String value, double fallback) {
        Double parsed = timeoutSeconds(value);
        return parsed != null ? parsed : fallback;
    }

    private static String firstNonEmpty(String preferred, String fallback) {
        if (preferred != null && !preferred.isEmpty()) {
            return preferred;
        }
        return fallback != null && !fallback.isEmpty() ? fallback : null;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    /** Fields StartWorkflowExecution accepts, before type defaults are resolved. */
    public record StartWorkflowExecutionRequest(
            String region,
            String domain,
            String workflowId,
            String typeName,
            String typeVersion,
            String taskList,
            String taskPriority,
            String input,
            String executionStartToCloseTimeout,
            String taskStartToCloseTimeout,
            String childPolicy,
            List<String> tagList,
            String lambdaRole) {
    }

    /**
     * One decision from RespondDecisionTaskCompleted, already unwrapped from its
     * {@code <type>DecisionAttributes} envelope so the service reads plain fields.
     */
    public record Decision(String type, Map<String, Object> attributes) {

        public String string(String field) {
            Object value = attributes.get(field);
            return value instanceof String s && !s.isEmpty() ? s : null;
        }

        @SuppressWarnings("unchecked")
        public String nested(String field, String key) {
            Object value = attributes.get(field);
            if (value instanceof Map<?, ?> map) {
                Object nested = ((Map<String, Object>) map).get(key);
                return nested instanceof String s && !s.isEmpty() ? s : null;
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        public List<String> stringList(String field) {
            Object value = attributes.get(field);
            if (value instanceof List<?> list && !list.isEmpty()) {
                return new ArrayList<>((List<String>) list);
            }
            return null;
        }
    }

    /** Composable predicate over executions, built from the API's filter members. */
    public interface ExecutionFilter extends Predicate<SwfWorkflowExecution> {

        static ExecutionFilter all() {
            return execution -> true;
        }

        default ExecutionFilter and(ExecutionFilter other) {
            return execution -> test(execution) && other.test(execution);
        }

        @Override
        default Predicate<SwfWorkflowExecution> negate() {
            return execution -> !test(execution);
        }
    }
}
