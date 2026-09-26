package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.JobRun;
import io.github.hectorvent.floci.services.glue.model.Trigger;
import io.github.hectorvent.floci.services.glue.model.TriggerAction;
import io.github.hectorvent.floci.services.glue.model.TriggerCondition;
import io.github.hectorvent.floci.services.glue.model.Workflow;
import io.github.hectorvent.floci.services.glue.model.WorkflowRun;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Glue trigger lifecycle and firing. An ON_DEMAND trigger fires when started. A CONDITIONAL trigger,
 * once ACTIVATED, fires when a job run or crawl its predicate watches finishes in the named state:
 * after each Glue request that starts, stops or reads job runs or crawls,
 * {@link #fireConditionalTriggers()} processes every completion of a watched job or crawler this
 * trigger has not seen yet, in finishing order. A SCHEDULED trigger is stored and can be activated,
 * but no timer fires it.
 *
 * <p>This service also runs workflows. StartWorkflowRun fires the workflow's starting (non
 * CONDITIONAL) triggers inside a new workflow run; a workflow's CONDITIONAL triggers fire only on
 * completions of runs and crawls that belong to a RUNNING run of that workflow, evaluate AND within
 * that run, and start their actions inside it. A workflow run is COMPLETED once an evaluation ends
 * with nothing left running in it and nothing more to fire.
 *
 * <p>Every public method is synchronized. This service calls the job run and crawler run services
 * and they never call back, so the lock order is always this service first.
 */
@ApplicationScoped
public class GlueTriggerService {

    private static final Logger LOG = Logger.getLogger(GlueTriggerService.class);

    static final String TYPE_ON_DEMAND = "ON_DEMAND";
    static final String TYPE_CONDITIONAL = "CONDITIONAL";
    static final String STATE_CREATED = "CREATED";
    static final String STATE_ACTIVATED = "ACTIVATED";
    static final String STATE_DEACTIVATED = "DEACTIVATED";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_COMPLETED = "COMPLETED";
    static final String STATUS_STOPPED = "STOPPED";
    static final String STATUS_ERROR = "ERROR";
    private static final String STATE_JOB_RUNNING = "RUNNING";

    // Work done within one request; a longer chain continues on the next run-related request.
    private static final int MAX_FIRING_ROUNDS = 25;
    // Emulator safeguard, not an AWS limit: triggers start at most this many runs on behalf of one run
    // that no trigger started (the origin of a chain). Here every run succeeds, so without it a trigger
    // loop of any shape, branching or not, would never stop.
    static final int MAX_TRIGGERED_RUNS_PER_ORIGIN = 100;
    private static final String JOB_KEY = "job:";
    private static final String CRAWLER_KEY = "crawler:";
    // Per watched job or crawler, the seen map holds the last processed position and that outcome's state.
    private static final String POSITION = "position:";
    private static final String STATE = "state:";

    private final StorageBackend<String, Map<String, String>> seenStore;
    private final StorageBackend<String, WorkflowRun> workflowRunStore;
    private final GlueService glueService;
    private final GlueJobRunService jobRunService;
    private final GlueCrawlerRunService crawlerRunService;

    @Inject
    public GlueTriggerService(StorageFactory storageFactory, GlueService glueService,
                              GlueJobRunService jobRunService, GlueCrawlerRunService crawlerRunService) {
        this(storageFactory.create("glue", "trigger_seen_runs.json", new TypeReference<>() {}),
                storageFactory.create("glue", "workflow_runs.json", new TypeReference<>() {}),
                glueService, jobRunService, crawlerRunService);
    }

    GlueTriggerService(StorageBackend<String, Map<String, String>> seenStore,
                       StorageBackend<String, WorkflowRun> workflowRunStore, GlueService glueService,
                       GlueJobRunService jobRunService, GlueCrawlerRunService crawlerRunService) {
        this.seenStore = seenStore;
        this.workflowRunStore = workflowRunStore;
        this.glueService = glueService;
        this.jobRunService = jobRunService;
        this.crawlerRunService = crawlerRunService;
    }

    public synchronized void createTrigger(Trigger trigger, boolean startOnCreation, Map<String, String> tags,
                                           String region) {
        if (startOnCreation && TYPE_ON_DEMAND.equals(trigger.getType())) {
            throw new AwsException("InvalidInputException",
                    "StartOnCreation is not supported for ON_DEMAND triggers.", 400);
        }
        trigger.setState(startOnCreation ? STATE_ACTIVATED : STATE_CREATED);
        glueService.createTrigger(trigger, tags, region);
        if (isActiveConditional(trigger)) {
            markAlreadyFinishedAsSeen(trigger, false);
        }
    }

    public synchronized Trigger updateTrigger(String name, Trigger update) {
        Trigger trigger = glueService.updateTrigger(name, update);
        if (isActiveConditional(trigger)) {
            // Completions not yet evaluated for what the trigger already watched must still fire it;
            // only a newly watched job or crawler starts from what has finished so far.
            markAlreadyFinishedAsSeen(trigger, true);
        }
        return trigger;
    }

    public synchronized void deleteTrigger(String name, String region) {
        glueService.deleteTrigger(name, region);
        seenStore.delete(name);
    }

    /** ON_DEMAND: runs the actions now. SCHEDULED and CONDITIONAL: activates the trigger. */
    public synchronized void startTrigger(String name) {
        Trigger trigger = glueService.getTrigger(name);
        if (TYPE_ON_DEMAND.equals(trigger.getType())) {
            fire(trigger, null, null, true);
            return;
        }
        boolean wasActive = STATE_ACTIVATED.equals(trigger.getState());
        trigger.setState(STATE_ACTIVATED);
        glueService.putTrigger(trigger);
        if (isActiveConditional(trigger)) {
            markAlreadyFinishedAsSeen(trigger, wasActive);
        }
    }

    public synchronized void stopTrigger(String name) {
        Trigger trigger = glueService.getTrigger(name);
        if (TYPE_ON_DEMAND.equals(trigger.getType())) {
            throw new AwsException("InvalidInputException", "An ON_DEMAND trigger cannot be stopped.", 400);
        }
        trigger.setState(STATE_DEACTIVATED);
        glueService.putTrigger(trigger);
    }

    /**
     * Fires every activated CONDITIONAL trigger for each new job run or crawl completion that meets
     * its predicate, repeating while firing finishes more runs (a chain of triggers with the default
     * run duration of 0 completes within one request, up to {@link #MAX_FIRING_ROUNDS} rounds; the rest
     * continues on the next run-related request). A trigger loop of any shape stops once triggers have
     * started {@link #MAX_TRIGGERED_RUNS_PER_ORIGIN} runs on behalf of the run that set it off.
     */
    public synchronized void fireConditionalTriggers() {
        for (int round = 0; round < MAX_FIRING_ROUNDS; round++) {
            boolean anyFired = false;
            Set<String> runningWorkflowRuns = runningWorkflowRunIds();
            for (Trigger trigger : glueService.allTriggers()) {
                if (!isActiveConditional(trigger)) {
                    continue;
                }
                for (GlueRunCompletion cause : newFirings(trigger, runningWorkflowRuns)) {
                    String origin = cause.originRunId();
                    if (!claimTriggeredRuns(origin, trigger.getActions().size())) {
                        LOG.warnv("Glue trigger {0} not fired: triggers already started {1} runs on behalf of {2}, "
                                + "so they probably form a loop", trigger.getName(), MAX_TRIGGERED_RUNS_PER_ORIGIN, origin);
                        continue;
                    }
                    fire(trigger, origin, trigger.getWorkflowName() != null ? cause.workflowRunId() : null, false);
                    anyFired = true;
                }
            }
            if (!anyFired) {
                // Nothing more fires, so a workflow run with nothing left running is finished. Not
                // checked when the round limit cut the evaluation short: more may still fire then.
                completeFinishedWorkflowRuns();
                return;
            }
        }
    }

    // ---- Workflow runs ----------------------------------------------------------------------

    /** Starts a run of the workflow: its starting (non CONDITIONAL) triggers fire inside the new run. */
    public synchronized String startWorkflowRun(String workflowName, Map<String, String> runProperties) {
        Workflow workflow = glueService.getWorkflow(workflowName);
        requireCapacity(workflow);
        Map<String, String> properties = new LinkedHashMap<>();
        if (workflow.getDefaultRunProperties() != null) {
            properties.putAll(workflow.getDefaultRunProperties());
        }
        if (runProperties != null) {
            properties.putAll(runProperties);
        }
        WorkflowRun run = newWorkflowRun(workflowName, null, properties);
        int actions = 0;
        int started = 0;
        for (Trigger trigger : glueService.triggersOfWorkflow(workflowName)) {
            if (!TYPE_CONDITIONAL.equals(trigger.getType())) {
                actions += trigger.getActions().size();
                started += fire(trigger, null, run.getWorkflowRunId(), false);
            }
        }
        if (actions > 0 && started == 0) {
            // Nothing the workflow starts with could be launched; the reasons are in ErrorMessage.
            finishWorkflowRun(workflowRunStore.get(run.getWorkflowRunId()).orElse(run), STATUS_ERROR, Instant.now());
            return run.getWorkflowRunId();
        }
        fireConditionalTriggers();
        return run.getWorkflowRunId();
    }

    public synchronized void stopWorkflowRun(String workflowName, String runId) {
        findWorkflowRun(workflowName, runId);
        // A run whose last action finished since the previous evaluation is COMPLETED, not stoppable.
        fireConditionalTriggers();
        WorkflowRun run = findWorkflowRun(workflowName, runId);
        if (!STATUS_RUNNING.equals(run.getStatus())) {
            throw new AwsException("IllegalWorkflowStateException",
                    "Workflow run " + runId + " is not running; its status is " + run.getStatus(), 400);
        }
        stopWhatIsRunning(runId);
        finishWorkflowRun(run, STATUS_STOPPED, Instant.now());
    }

    /**
     * Starts the given job and crawler nodes of a finished run again in a new run of the workflow, whose
     * conditional triggers then carry on downstream. Each node must have run in the original run.
     */
    public synchronized ResumedRun resumeWorkflowRun(String workflowName, String runId, List<String> requestedNodeIds) {
        WorkflowRun original = findWorkflowRun(workflowName, runId);
        if (STATUS_RUNNING.equals(original.getStatus())) {
            throw new AwsException("IllegalWorkflowStateException",
                    "Workflow run " + runId + " is still running.", 400);
        }
        if (requestedNodeIds == null || requestedNodeIds.isEmpty()) {
            throw new AwsException("InvalidInputException", "NodeIds is required.", 400);
        }
        // A node named twice is restarted once: launching it a second time would collide with the first.
        List<String> nodeIds = new ArrayList<>(new LinkedHashSet<>(requestedNodeIds));
        Map<String, Map<String, Object>> nodes = new HashMap<>();
        for (Map<String, Object> node : graphNodes(workflowName)) {
            nodes.put((String) node.get("UniqueId"), node);
        }
        Set<String> ranJobs = new LinkedHashSet<>();
        for (JobRun jobRun : jobRunService.runsInWorkflowRun(runId)) {
            ranJobs.add(jobRun.getJobName());
        }
        Set<String> ranCrawlers = new LinkedHashSet<>();
        for (GlueCrawlerRunService.WorkflowCrawl crawl : crawlerRunService.crawlsInWorkflowRun(runId)) {
            ranCrawlers.add(crawl.crawlerName());
        }
        for (String nodeId : nodeIds) {
            Map<String, Object> node = nodes.get(nodeId);
            boolean ran = node != null && ("JOB".equals(node.get("Type")) ? ranJobs.contains((String) node.get("Name"))
                    : "CRAWLER".equals(node.get("Type")) && ranCrawlers.contains((String) node.get("Name")));
            if (!ran) {
                throw new AwsException("InvalidInputException",
                        "Node " + nodeId + " is not a job or crawler node that ran in workflow run " + runId, 400);
            }
        }
        requireCapacity(glueService.getWorkflow(workflowName));
        // Refuse before anything starts when a node cannot launch, so a failed resume normally leaves
        // no run and no action behind.
        for (String nodeId : nodeIds) {
            Map<String, Object> node = nodes.get(nodeId);
            String name = (String) node.get("Name");
            if ("JOB".equals(node.get("Type")) && !jobRunService.hasCapacity(name)) {
                throw new AwsException("ConcurrentRunsExceededException", "Concurrent runs exceeded for " + name, 400);
            }
            if ("CRAWLER".equals(node.get("Type")) && crawlerRunService.isRunning(name)) {
                throw new AwsException("CrawlerRunningException", "Crawler with name " + name + " has already started", 400);
            }
        }
        Map<String, JobRun> originalJobRuns = new HashMap<>();
        for (JobRun jobRun : jobRunService.runsInWorkflowRun(runId)) {
            originalJobRuns.put(jobRun.getJobName(), jobRun);
        }
        WorkflowRun resumed = newWorkflowRun(workflowName, runId, original.getWorkflowRunProperties());
        String resumedId = resumed.getWorkflowRunId();
        try {
            for (String nodeId : nodeIds) {
                Map<String, Object> node = nodes.get(nodeId);
                String name = (String) node.get("Name");
                if ("JOB".equals(node.get("Type"))) {
                    // The job runs again with the arguments it had in the original run, now in the new run.
                    JobRun before = originalJobRuns.get(name);
                    JobRun overrides = new JobRun();
                    overrides.setArguments(withWorkflowArguments(before != null ? before.getArguments() : null, resumedId));
                    if (before != null) {
                        overrides.setTimeout(before.getTimeout());
                        overrides.setSecurityConfiguration(before.getSecurityConfiguration());
                        overrides.setNotificationProperty(before.getNotificationProperty());
                        overrides.setTriggerName(before.getTriggerName());
                    }
                    jobRunService.startJobRun(name, null, overrides, null, resumedId);
                } else {
                    crawlerRunService.startCrawler(name, null, resumedId);
                }
            }
        } catch (AwsException e) {
            // Only reachable if a node became unable to launch after the check above. What already
            // started keeps its run, so the run is kept too, stopped and marked ERROR with the reason.
            noteLaunchFailure(resumedId, "Resume could not start every node: " + e.getMessage());
            stopWhatIsRunning(resumedId);
            finishWorkflowRun(workflowRunStore.get(resumedId).orElse(resumed), STATUS_ERROR, Instant.now());
            throw e;
        }
        fireConditionalTriggers();
        return new ResumedRun(resumedId, nodeIds);
    }

    public synchronized WorkflowRun getWorkflowRun(String workflowName, String runId, boolean includeGraph) {
        fireConditionalTriggers();
        return view(findWorkflowRun(workflowName, runId), includeGraph);
    }

    public synchronized GlueService.Page<WorkflowRun> getWorkflowRuns(String workflowName, boolean includeGraph,
                                                                      Integer maxResults, String nextToken) {
        glueService.getWorkflow(workflowName);
        fireConditionalTriggers();
        List<WorkflowRun> runs = new ArrayList<>();
        for (WorkflowRun run : runsOfWorkflow(workflowName)) {
            runs.add(view(run, includeGraph));
        }
        runs.sort(Comparator.comparing(WorkflowRun::getStartedOn).reversed()
                .thenComparing(WorkflowRun::getWorkflowRunId));
        return glueService.paginate(runs, maxResults, nextToken);
    }

    public synchronized Map<String, String> getWorkflowRunProperties(String workflowName, String runId) {
        WorkflowRun run = findWorkflowRun(workflowName, runId);
        return run.getWorkflowRunProperties() != null ? run.getWorkflowRunProperties() : Map.of();
    }

    /** Adds the properties to the run's, replacing any with the same name. */
    public synchronized void putWorkflowRunProperties(String workflowName, String runId, Map<String, String> properties) {
        if (properties == null) {
            throw new AwsException("InvalidInputException", "RunProperties is required.", 400);
        }
        WorkflowRun run = findWorkflowRun(workflowName, runId);
        Map<String, String> merged = new LinkedHashMap<>();
        if (run.getWorkflowRunProperties() != null) {
            merged.putAll(run.getWorkflowRunProperties());
        }
        merged.putAll(properties);
        run.setWorkflowRunProperties(merged);
        workflowRunStore.put(run.getWorkflowRunId(), run);
    }

    /** The workflow as GetWorkflow and BatchGetWorkflows report it: with LastRun, and the graph if asked. */
    public synchronized Workflow workflowView(Workflow stored, boolean includeGraph) {
        fireConditionalTriggers();
        Workflow workflow = new Workflow();
        workflow.setName(stored.getName());
        workflow.setDescription(stored.getDescription());
        workflow.setDefaultRunProperties(stored.getDefaultRunProperties());
        workflow.setCreatedOn(stored.getCreatedOn());
        workflow.setLastModifiedOn(stored.getLastModifiedOn());
        workflow.setMaxConcurrentRuns(stored.getMaxConcurrentRuns());
        runsOfWorkflow(stored.getName()).stream()
                .max(Comparator.comparing(WorkflowRun::getStartedOn))
                .ifPresent(last -> workflow.setLastRun(view(last, false)));
        if (includeGraph) {
            workflow.setGraph(graph(stored.getName(), null));
        }
        return workflow;
    }

    /** Deletes the workflow and its runs; its triggers stay, as they do not belong to the workflow's storage. */
    public synchronized void deleteWorkflow(String workflowName, String region) {
        for (WorkflowRun run : runsOfWorkflow(workflowName)) {
            workflowRunStore.delete(run.getWorkflowRunId());
            forgetWorkflowRunState(run.getWorkflowRunId());
            crawlerRunService.forgetWorkflowRun(run.getWorkflowRunId());
        }
        glueService.deleteWorkflow(workflowName, region);
    }

    private void stopWhatIsRunning(String workflowRunId) {
        for (JobRun jobRun : jobRunService.runsInWorkflowRun(workflowRunId)) {
            if (STATE_JOB_RUNNING.equals(jobRun.getJobRunState())) {
                jobRunService.batchStopJobRun(jobRun.getJobName(), List.of(jobRun.getId()));
            }
        }
        for (GlueCrawlerRunService.WorkflowCrawl crawl : crawlerRunService.crawlsInWorkflowRun(workflowRunId)) {
            if (STATE_JOB_RUNNING.equals(crawl.state())) {
                crawlerRunService.stopCrawler(crawl.crawlerName());
            }
        }
    }

    private void requireCapacity(Workflow workflow) {
        // Settles runs, fires what is due and completes finished workflow runs before counting.
        fireConditionalTriggers();
        if (workflow.getMaxConcurrentRuns() == null) {
            return;
        }
        long running = runsOfWorkflow(workflow.getName()).stream()
                .filter(run -> STATUS_RUNNING.equals(run.getStatus())).count();
        if (running >= workflow.getMaxConcurrentRuns()) {
            throw new AwsException("ConcurrentRunsExceededException",
                    "Concurrent runs exceeded for workflow " + workflow.getName(), 400);
        }
    }

    private WorkflowRun newWorkflowRun(String workflowName, String previousRunId, Map<String, String> properties) {
        WorkflowRun run = new WorkflowRun();
        run.setName(workflowName);
        run.setWorkflowRunId("wr_" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        run.setPreviousRunId(previousRunId);
        run.setWorkflowRunProperties(properties != null ? new LinkedHashMap<>(properties) : new LinkedHashMap<>());
        run.setStartedOn(Instant.now());
        run.setStatus(STATUS_RUNNING);
        workflowRunStore.put(run.getWorkflowRunId(), run);
        LOG.infov("Started Glue workflow run {0} of {1}", run.getWorkflowRunId(), workflowName);
        return run;
    }

    private WorkflowRun findWorkflowRun(String workflowName, String runId) {
        glueService.getWorkflow(workflowName);
        if (runId == null) {
            throw new AwsException("InvalidInputException", "RunId is required.", 400);
        }
        return workflowRunStore.get(runId)
                .filter(run -> workflowName.equals(run.getName()))
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Workflow run " + runId + " not found for workflow " + workflowName, 400));
    }

    private List<WorkflowRun> runsOfWorkflow(String workflowName) {
        List<WorkflowRun> runs = new ArrayList<>();
        for (WorkflowRun run : workflowRunStore.scan(key -> true)) {
            if (workflowName.equals(run.getName())) {
                runs.add(run);
            }
        }
        return runs;
    }

    private Set<String> runningWorkflowRunIds() {
        Set<String> running = new LinkedHashSet<>();
        for (WorkflowRun run : workflowRunStore.scan(key -> true)) {
            if (STATUS_RUNNING.equals(run.getStatus())) {
                running.add(run.getWorkflowRunId());
            }
        }
        return running;
    }

    private String workflowNameOf(String workflowRunId) {
        return workflowRunStore.get(workflowRunId).map(WorkflowRun::getName).orElse(null);
    }

    private void completeFinishedWorkflowRuns() {
        for (WorkflowRun run : workflowRunStore.scan(key -> true)) {
            if (!STATUS_RUNNING.equals(run.getStatus())) {
                continue;
            }
            Instant lastFinished = run.getStartedOn();
            boolean anyRunning = false;
            for (JobRun jobRun : jobRunService.runsInWorkflowRun(run.getWorkflowRunId())) {
                if (jobRun.getCompletedOn() == null) {
                    anyRunning = true;
                } else if (jobRun.getCompletedOn().isAfter(lastFinished)) {
                    lastFinished = jobRun.getCompletedOn();
                }
            }
            for (GlueCrawlerRunService.WorkflowCrawl crawl : crawlerRunService.crawlsInWorkflowRun(run.getWorkflowRunId())) {
                if (crawl.completedOn() == null) {
                    anyRunning = true;
                } else if (crawl.completedOn().isAfter(lastFinished)) {
                    lastFinished = crawl.completedOn();
                }
            }
            if (!anyRunning) {
                finishWorkflowRun(run, STATUS_COMPLETED, lastFinished);
            }
        }
    }

    private void finishWorkflowRun(WorkflowRun run, String status, Instant completedOn) {
        run.setStatus(status);
        run.setCompletedOn(completedOn);
        workflowRunStore.put(run.getWorkflowRunId(), run);
        forgetWorkflowRunState(run.getWorkflowRunId());
        LOG.infov("Glue workflow run {0} is {1}", run.getWorkflowRunId(), status);
    }

    /** A finished workflow run's triggers no longer need the outcomes they kept for it. */
    private void forgetWorkflowRunState(String workflowRunId) {
        String prefix = STATE + workflowRunId + "|";
        for (String triggerName : seenStore.keys()) {
            Map<String, String> seen = seenStore.get(triggerName).orElse(Map.of());
            if (seen.keySet().stream().anyMatch(key -> key.startsWith(prefix))) {
                Map<String, String> kept = new HashMap<>(seen);
                kept.keySet().removeIf(key -> key.startsWith(prefix));
                seenStore.put(triggerName, kept);
            }
        }
    }

    private WorkflowRun view(WorkflowRun stored, boolean includeGraph) {
        WorkflowRun run = new WorkflowRun();
        run.setName(stored.getName());
        run.setWorkflowRunId(stored.getWorkflowRunId());
        run.setPreviousRunId(stored.getPreviousRunId());
        run.setWorkflowRunProperties(stored.getWorkflowRunProperties());
        run.setStartedOn(stored.getStartedOn());
        run.setCompletedOn(stored.getCompletedOn());
        run.setStatus(stored.getStatus());
        run.setErrorMessage(stored.getErrorMessage());
        run.setStatistics(statistics(stored.getWorkflowRunId()));
        if (includeGraph) {
            run.setGraph(graph(stored.getName(), stored.getWorkflowRunId()));
        }
        return run;
    }

    private Map<String, Integer> statistics(String workflowRunId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : List.of("TotalActions", "TimeoutActions", "FailedActions", "StoppedActions",
                "SucceededActions", "RunningActions", "ErroredActions", "WaitingActions")) {
            counts.put(key, 0);
        }
        List<String> states = new ArrayList<>();
        for (JobRun jobRun : jobRunService.runsInWorkflowRun(workflowRunId)) {
            states.add(jobRun.getJobRunState());
        }
        for (GlueCrawlerRunService.WorkflowCrawl crawl : crawlerRunService.crawlsInWorkflowRun(workflowRunId)) {
            states.add("CANCELLED".equals(crawl.state()) ? "STOPPED" : crawl.state());
        }
        for (String state : states) {
            counts.merge("TotalActions", 1, Integer::sum);
            String bucket = switch (state) {
                case "SUCCEEDED" -> "SucceededActions";
                case "FAILED" -> "FailedActions";
                case "STOPPED", "STOPPING" -> "StoppedActions";
                case "TIMEOUT" -> "TimeoutActions";
                case "ERROR" -> "ErroredActions";
                case "WAITING" -> "WaitingActions";
                default -> "RunningActions";
            };
            counts.merge(bucket, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * The workflow graph: a TRIGGER node per trigger of the workflow, a JOB or CRAWLER node per job or
     * crawler its triggers start or watch, an edge from each watched node to the trigger and from the
     * trigger to each node it starts. With a workflow run, job nodes list the run's job runs and crawler
     * nodes its crawls.
     */
    private Map<String, Object> graph(String workflowName, String workflowRunId) {
        List<Map<String, Object>> nodes = new ArrayList<>(graphNodes(workflowName));
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Trigger trigger : glueService.triggersOfWorkflow(workflowName)) {
            String triggerId = nodeId(workflowName, "TRIGGER", trigger.getName());
            if (trigger.getPredicate() != null && trigger.getPredicate().getConditions() != null) {
                for (TriggerCondition condition : trigger.getPredicate().getConditions()) {
                    edges.add(edge(nodeId(workflowName, condition.getJobName() != null ? "JOB" : "CRAWLER",
                            condition.getJobName() != null ? condition.getJobName() : condition.getCrawlerName()), triggerId));
                }
            }
            for (TriggerAction action : trigger.getActions()) {
                edges.add(edge(triggerId, nodeId(workflowName, action.getJobName() != null ? "JOB" : "CRAWLER",
                        action.getJobName() != null ? action.getJobName() : action.getCrawlerName())));
            }
        }
        if (workflowRunId != null) {
            List<JobRun> jobRuns = jobRunService.runsInWorkflowRun(workflowRunId);
            List<GlueCrawlerRunService.WorkflowCrawl> crawls = crawlerRunService.crawlsInWorkflowRun(workflowRunId);
            for (Map<String, Object> node : nodes) {
                String name = (String) node.get("Name");
                if ("JOB".equals(node.get("Type"))) {
                    node.put("JobDetails", Map.of("JobRuns",
                            jobRuns.stream().filter(run -> name.equals(run.getJobName())).toList()));
                } else if ("CRAWLER".equals(node.get("Type"))) {
                    List<Map<String, Object>> crawlList = new ArrayList<>();
                    for (GlueCrawlerRunService.WorkflowCrawl crawl : crawls) {
                        if (name.equals(crawl.crawlerName())) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("State", crawl.state());
                            entry.put("StartedOn", epochSeconds(crawl.startedOn()));
                            if (crawl.completedOn() != null) {
                                entry.put("CompletedOn", epochSeconds(crawl.completedOn()));
                            }
                            entry.put("LogGroup", "/aws-glue/crawlers");
                            entry.put("LogStream", name);
                            crawlList.add(entry);
                        }
                    }
                    node.put("CrawlerDetails", Map.of("Crawls", crawlList));
                }
            }
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("Nodes", nodes);
        graph.put("Edges", edges);
        return graph;
    }

    private List<Map<String, Object>> graphNodes(String workflowName) {
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Trigger trigger : glueService.triggersOfWorkflow(workflowName)) {
            Map<String, Object> triggerNode = node(workflowName, "TRIGGER", trigger.getName());
            triggerNode.put("TriggerDetails", Map.of("Trigger", trigger));
            nodes.putIfAbsent((String) triggerNode.get("UniqueId"), triggerNode);
            if (trigger.getPredicate() != null && trigger.getPredicate().getConditions() != null) {
                for (TriggerCondition condition : trigger.getPredicate().getConditions()) {
                    Map<String, Object> watched = condition.getJobName() != null
                            ? node(workflowName, "JOB", condition.getJobName())
                            : node(workflowName, "CRAWLER", condition.getCrawlerName());
                    nodes.putIfAbsent((String) watched.get("UniqueId"), watched);
                }
            }
            for (TriggerAction action : trigger.getActions()) {
                Map<String, Object> started = action.getJobName() != null
                        ? node(workflowName, "JOB", action.getJobName())
                        : node(workflowName, "CRAWLER", action.getCrawlerName());
                nodes.putIfAbsent((String) started.get("UniqueId"), started);
            }
        }
        return new ArrayList<>(nodes.values());
    }

    private static Map<String, Object> node(String workflowName, String type, String name) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("Type", type);
        node.put("Name", name);
        node.put("UniqueId", nodeId(workflowName, type, name));
        return node;
    }

    private static Map<String, Object> edge(String sourceId, String destinationId) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("SourceId", sourceId);
        edge.put("DestinationId", destinationId);
        return edge;
    }

    /** Stable per workflow, type and name, so a node keeps its id across reads and resumes. */
    private static String nodeId(String workflowName, String type, String name) {
        return "wnode_" + UUID.nameUUIDFromBytes((workflowName + "\u0000" + type + "\u0000" + name)
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
    }

    private static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    /**
     * Processes the completions this trigger has not seen yet, in finishing order, and returns the
     * completions that fire it. ANY fires on each completion that matches one of its conditions; AND
     * fires on a matching completion once every condition's latest outcome matches. A workflow's
     * trigger only counts completions from a running run of its workflow and keeps its latest outcomes
     * per workflow run, so AND is met within one run.
     */
    private List<GlueRunCompletion> newFirings(Trigger trigger, Set<String> runningWorkflowRuns) {
        Map<String, String> before = seenStore.get(trigger.getName()).orElse(Map.of());
        Map<String, String> seen = new HashMap<>(before);
        List<KeyedCompletion> fresh = new ArrayList<>();
        for (String key : watchedKeys(trigger)) {
            for (GlueRunCompletion completion : completionsAfter(key, seen.get(POSITION + key))) {
                fresh.add(new KeyedCompletion(key, completion));
            }
        }
        fresh.sort(Comparator.comparing((KeyedCompletion k) -> k.completion().finishedAt())
                .thenComparing(k -> k.completion().position()));
        boolean any = "ANY".equals(trigger.getPredicate().getLogical());
        List<GlueRunCompletion> firings = new ArrayList<>();
        for (KeyedCompletion event : fresh) {
            // Events run in finishing order, which need not be completion order (a run can be stopped
            // before an earlier started one is seen succeeding), so keep the highest position processed.
            String processed = seen.get(POSITION + event.key());
            if (processed == null || event.completion().position().compareTo(processed) > 0) {
                seen.put(POSITION + event.key(), event.completion().position());
            }
            String scope = "";
            if (trigger.getWorkflowName() != null) {
                String workflowRunId = event.completion().workflowRunId();
                if (workflowRunId == null || !runningWorkflowRuns.contains(workflowRunId)
                        || !trigger.getWorkflowName().equals(workflowNameOf(workflowRunId))) {
                    continue;
                }
                scope = workflowRunId + "|";
            }
            seen.put(STATE + scope + event.key(), event.completion().state());
            boolean eventMatches = false;
            boolean allMatch = true;
            for (TriggerCondition condition : trigger.getPredicate().getConditions()) {
                boolean matches = expectedState(condition).equals(seen.get(STATE + scope + keyOf(condition)));
                allMatch &= matches;
                eventMatches |= matches && keyOf(condition).equals(event.key())
                        && expectedState(condition).equals(event.completion().state());
            }
            if (eventMatches && (any || allMatch)) {
                firings.add(event.completion());
            }
        }
        if (!seen.equals(before)) {
            seenStore.put(trigger.getName(), seen);
        }
        return firings;
    }

    /**
     * Marks what has already finished as seen, so that only later completions count. With
     * {@code keepProcessed}, a watched job or crawler the trigger already tracks keeps its position, so
     * its unevaluated completions still fire the trigger; only newly watched ones are marked.
     */
    private void markAlreadyFinishedAsSeen(Trigger trigger, boolean keepProcessed) {
        Map<String, String> seen = new HashMap<>();
        Map<String, String> previous = keepProcessed ? seenStore.get(trigger.getName()).orElse(Map.of()) : Map.of();
        for (String key : watchedKeys(trigger)) {
            if (previous.containsKey(POSITION + key)) {
                seen.put(POSITION + key, previous.get(POSITION + key));
                if (previous.containsKey(STATE + key)) {
                    seen.put(STATE + key, previous.get(STATE + key));
                }
                continue;
            }
            List<GlueRunCompletion> completions = completionsAfter(key, null);
            if (completions.isEmpty()) {
                // Tracked from the start: an empty position means every completion is still unseen.
                seen.put(POSITION + key, "");
            } else {
                GlueRunCompletion last = completions.getLast();
                seen.put(POSITION + key, last.position());
                seen.put(STATE + key, last.state());
            }
        }
        seenStore.put(trigger.getName(), seen);
    }

    private static Set<String> watchedKeys(Trigger trigger) {
        Set<String> keys = new LinkedHashSet<>();
        for (TriggerCondition condition : trigger.getPredicate().getConditions()) {
            keys.add(keyOf(condition));
        }
        return keys;
    }

    private List<GlueRunCompletion> completionsAfter(String key, String position) {
        if (key.startsWith(JOB_KEY)) {
            return jobRunService.completionsAfter(key.substring(JOB_KEY.length()), position);
        }
        return crawlerRunService.completionsAfter(key.substring(CRAWLER_KEY.length()), position);
    }

    private boolean claimTriggeredRuns(String originRunId, int runs) {
        if (originRunId == null) {
            return false;
        }
        return originRunId.startsWith(GlueCrawlerRunService.CRAWL_ID_PREFIX)
                ? crawlerRunService.claimTriggeredRuns(originRunId, runs, MAX_TRIGGERED_RUNS_PER_ORIGIN)
                : jobRunService.claimTriggeredRuns(originRunId, runs, MAX_TRIGGERED_RUNS_PER_ORIGIN);
    }

    /**
     * Starts the trigger's actions on behalf of {@code originRunId} (with a null origin each run or crawl
     * started is its own origin), inside {@code workflowRunId} when it is not null. A job run in a
     * workflow run gets the {@code --WORKFLOW_NAME} and {@code --WORKFLOW_RUN_ID} arguments, as on AWS.
     */
    private int fire(Trigger trigger, String originRunId, String workflowRunId, boolean propagateFailures) {
        LOG.infov("Firing Glue trigger {0}", trigger.getName());
        int started = 0;
        for (TriggerAction action : trigger.getActions()) {
            try {
                if (action.getJobName() != null) {
                    JobRun overrides = new JobRun();
                    overrides.setArguments(withWorkflowArguments(action.getArguments(), workflowRunId));
                    overrides.setTimeout(action.getTimeout());
                    overrides.setSecurityConfiguration(action.getSecurityConfiguration());
                    overrides.setNotificationProperty(action.getNotificationProperty());
                    overrides.setTriggerName(trigger.getName());
                    jobRunService.startJobRun(action.getJobName(), null, overrides, originRunId, workflowRunId);
                } else {
                    crawlerRunService.startCrawler(action.getCrawlerName(), originRunId, workflowRunId);
                }
                started++;
            } catch (AwsException e) {
                if (propagateFailures) {
                    throw e;
                }
                String target = action.getJobName() != null ? action.getJobName() : action.getCrawlerName();
                LOG.warnv("Glue trigger {0} could not start {1}: {2}", trigger.getName(), target, e.getMessage());
                if (workflowRunId != null) {
                    noteLaunchFailure(workflowRunId, "Trigger " + trigger.getName() + " could not start " + target
                            + ": " + e.getMessage());
                }
            }
        }
        return started;
    }

    /** Records on the workflow run that one of its actions could not be launched. */
    private void noteLaunchFailure(String workflowRunId, String message) {
        workflowRunStore.get(workflowRunId).ifPresent(run -> {
            run.setErrorMessage(run.getErrorMessage() == null ? message : run.getErrorMessage() + "; " + message);
            workflowRunStore.put(workflowRunId, run);
        });
    }

    private Map<String, String> withWorkflowArguments(Map<String, String> arguments, String workflowRunId) {
        if (workflowRunId == null) {
            return arguments;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        if (arguments != null) {
            merged.putAll(arguments);
        }
        merged.put("--WORKFLOW_NAME", workflowNameOf(workflowRunId));
        merged.put("--WORKFLOW_RUN_ID", workflowRunId);
        return merged;
    }

    private static String expectedState(TriggerCondition condition) {
        return condition.getJobName() != null ? condition.getState() : condition.getCrawlState();
    }

    private static String keyOf(TriggerCondition condition) {
        return condition.getJobName() != null ? JOB_KEY + condition.getJobName() : CRAWLER_KEY + condition.getCrawlerName();
    }

    private static boolean isActiveConditional(Trigger trigger) {
        return TYPE_CONDITIONAL.equals(trigger.getType()) && STATE_ACTIVATED.equals(trigger.getState());
    }

    private record KeyedCompletion(String key, GlueRunCompletion completion) {}

    /** ResumeWorkflowRun's answer: the new run and the nodes restarted in it. */
    public record ResumedRun(String runId, List<String> nodeIds) {}
}
