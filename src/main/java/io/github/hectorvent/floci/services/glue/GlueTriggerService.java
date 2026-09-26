package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.JobRun;
import io.github.hectorvent.floci.services.glue.model.Trigger;
import io.github.hectorvent.floci.services.glue.model.TriggerAction;
import io.github.hectorvent.floci.services.glue.model.TriggerCondition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Glue trigger lifecycle and firing. An ON_DEMAND trigger fires when started. A CONDITIONAL trigger,
 * once ACTIVATED, fires when a job run or crawl its predicate watches finishes in the named state:
 * after each Glue request that starts, stops or reads job runs or crawls,
 * {@link #fireConditionalTriggers()} processes every completion of a watched job or crawler this
 * trigger has not seen yet, in finishing order. A SCHEDULED trigger is stored and can be activated,
 * but no timer fires it.
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
    private final GlueService glueService;
    private final GlueJobRunService jobRunService;
    private final GlueCrawlerRunService crawlerRunService;

    @Inject
    public GlueTriggerService(StorageFactory storageFactory, GlueService glueService,
                              GlueJobRunService jobRunService, GlueCrawlerRunService crawlerRunService) {
        this(storageFactory.create("glue", "trigger_seen_runs.json", new TypeReference<>() {}),
                glueService, jobRunService, crawlerRunService);
    }

    GlueTriggerService(StorageBackend<String, Map<String, String>> seenStore, GlueService glueService,
                       GlueJobRunService jobRunService, GlueCrawlerRunService crawlerRunService) {
        this.seenStore = seenStore;
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
            fire(trigger, null, true);
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
            for (Trigger trigger : glueService.allTriggers()) {
                if (!isActiveConditional(trigger)) {
                    continue;
                }
                for (GlueRunCompletion cause : newFirings(trigger)) {
                    String origin = cause.originRunId();
                    if (!claimTriggeredRuns(origin, trigger.getActions().size())) {
                        LOG.warnv("Glue trigger {0} not fired: triggers already started {1} runs on behalf of {2}, "
                                + "so they probably form a loop", trigger.getName(), MAX_TRIGGERED_RUNS_PER_ORIGIN, origin);
                        continue;
                    }
                    fire(trigger, origin, false);
                    anyFired = true;
                }
            }
            if (!anyFired) {
                return;
            }
        }
    }

    /**
     * Processes the completions this trigger has not seen yet, in finishing order, and returns the
     * completions that fire it. ANY fires on
     * each completion that matches one of its conditions; AND fires on a matching completion once
     * every condition's latest outcome matches.
     */
    private List<GlueRunCompletion> newFirings(Trigger trigger) {
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
            seen.put(STATE + event.key(), event.completion().state());
            boolean eventMatches = false;
            boolean allMatch = true;
            for (TriggerCondition condition : trigger.getPredicate().getConditions()) {
                boolean matches = expectedState(condition).equals(seen.get(STATE + keyOf(condition)));
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
     * Starts the trigger's actions on behalf of {@code originRunId}; with a null origin (StartTrigger on
     * an ON_DEMAND trigger) each run or crawl started is its own origin.
     */
    private void fire(Trigger trigger, String originRunId, boolean propagateFailures) {
        LOG.infov("Firing Glue trigger {0}", trigger.getName());
        for (TriggerAction action : trigger.getActions()) {
            try {
                if (action.getJobName() != null) {
                    JobRun overrides = new JobRun();
                    overrides.setArguments(action.getArguments());
                    overrides.setTimeout(action.getTimeout());
                    overrides.setSecurityConfiguration(action.getSecurityConfiguration());
                    overrides.setNotificationProperty(action.getNotificationProperty());
                    overrides.setTriggerName(trigger.getName());
                    jobRunService.startJobRun(action.getJobName(), null, overrides, originRunId);
                } else {
                    crawlerRunService.startCrawler(action.getCrawlerName(), originRunId);
                }
            } catch (AwsException e) {
                if (propagateFailures) {
                    throw e;
                }
                LOG.warnv("Glue trigger {0} could not start {1}: {2}", trigger.getName(),
                        action.getJobName() != null ? action.getJobName() : action.getCrawlerName(), e.getMessage());
            }
        }
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
}
