package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.ActivityTask;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class StepFunctionsService {

    private static final Logger LOG = Logger.getLogger(StepFunctionsService.class);

    private final StorageBackend<String, StateMachine> stateMachineStore;
    private final StorageBackend<String, Execution> executionStore;
    private final StorageBackend<String, Activity> activityStore;
    private final Map<String, List<HistoryEvent>> historyCache = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<ActivityTask>> activityQueues = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonNode>> pendingTaskTokens = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;
    private final AslExecutor aslExecutor;
    private final ObjectMapper objectMapper;

    // Fields that are valid only in JSONPath mode. Validated against real AWS:
    // creating a JSONata state machine with any of these fields returns SCHEMA_VALIDATION_FAILED.
    private static final Set<String> JSONPATH_ONLY_FIELDS = Set.of(
            "InputPath", "OutputPath", "ResultPath", "ResultSelector", "Parameters", "Result", "ItemsPath");

    @Inject
    public StepFunctionsService(StorageFactory storageFactory, RegionResolver regionResolver,
                                AslExecutor aslExecutor, ObjectMapper objectMapper) {
        this.stateMachineStore = storageFactory.create("stepfunctions", "sfn-state-machines.json",
                new TypeReference<Map<String, StateMachine>>() {});
        this.executionStore = storageFactory.create("stepfunctions", "sfn-executions.json",
                new TypeReference<Map<String, Execution>>() {});
        this.activityStore = storageFactory.create("stepfunctions", "sfn-activities.json",
                new TypeReference<Map<String, Activity>>() {});
        this.regionResolver = regionResolver;
        this.aslExecutor = aslExecutor;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── State Machines ────────────────────────────

    public StateMachine createStateMachine(String name, String definition, String roleArn, String type, String region, Map<String, String> tags) {
        String arn = regionResolver.buildArn("states", region, "stateMachine:" + name);
        if (stateMachineStore.get(arn).isPresent()) {
            throw new AwsException("StateMachineAlreadyExists", "State machine already exists: " + arn, 400);
        }

        validateDefinition(definition);

        StateMachine sm = new StateMachine();
        sm.setStateMachineArn(arn);
        sm.setName(name);
        sm.setDefinition(definition);
        sm.setRoleArn(roleArn);
        if (type != null && !type.isEmpty()) {
            sm.setType(type);
        }
        if (tags != null && !tags.isEmpty()) {
            sm.getTags().putAll(tags);
        }

        stateMachineStore.put(arn, sm);
        LOG.infov("Created State Machine: {0}", arn);
        return sm;
    }

    public StateMachine describeStateMachine(String arn) {
        return stateMachineStore.get(arn)
                .orElseThrow(() -> new AwsException("StateMachineDoesNotExist", "State machine does not exist", 400));
    }

    public List<StateMachine> listStateMachines(String region) {
        String prefix = "arn:aws:states:" + region + ":";
        return stateMachineStore.scan(k -> k.startsWith(prefix));
    }

    public void deleteStateMachine(String arn) {
        stateMachineStore.delete(arn);
    }

    // ──────────────────────────── Executions ────────────────────────────

    public Execution startExecution(String stateMachineArn, String name, String input, String region) {
        StateMachine sm = describeStateMachine(stateMachineArn);
        String execName = (name != null && !name.isBlank()) ? name : UUID.randomUUID().toString();
        String arn = regionResolver.buildArn("states", region, "execution:" + sm.getName() + ":" + execName);

        if (executionStore.get(arn).isPresent()) {
            throw new AwsException("ExecutionAlreadyExists", "Execution already exists: " + arn, 400);
        }

        Execution exec = new Execution();
        exec.setExecutionArn(arn);
        exec.setStateMachineArn(stateMachineArn);
        exec.setName(execName);
        exec.setInput(input);
        exec.setStatus("RUNNING");

        executionStore.put(arn, exec);

        List<HistoryEvent> history = new ArrayList<>();
        HistoryEvent startEvent = new HistoryEvent();
        startEvent.setId(1L);
        startEvent.setType("ExecutionStarted");
        startEvent.setDetails(Map.of("input", input != null ? input : "{}",
                                     "roleArn", sm.getRoleArn() != null ? sm.getRoleArn() : ""));
        history.add(startEvent);
        historyCache.put(arn, history);

        LOG.infov("Started execution: {0}", arn);

        aslExecutor.executeAsync(sm, exec, history, (updatedExec, updatedHistory) -> {
            executionStore.put(updatedExec.getExecutionArn(), updatedExec);
            historyCache.put(updatedExec.getExecutionArn(), updatedHistory);
            LOG.infov("Execution {0} completed with status {1}", updatedExec.getExecutionArn(), updatedExec.getStatus());
        });

        return exec;
    }

    public Execution startSyncExecution(String stateMachineArn, String name, String input, String region) {
        StateMachine sm = describeStateMachine(stateMachineArn);
        if (!"EXPRESS".equals(sm.getType())) {
            throw new AwsException("StateMachineTypeNotSupported",
                    "StartSyncExecution is only supported for EXPRESS state machines", 400);
        }

        String execName = (name != null && !name.isBlank()) ? name : UUID.randomUUID().toString();
        // Real AWS express execution ARN format: express:<smName>:<startDate>:<execName>
        // where startDate is ISO-8601 UTC, e.g. 2024-01-15T10:30:00.123Z
        String startDate = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String arn = regionResolver.buildArn("states", region,
                "express:" + sm.getName() + ":" + startDate + ":" + execName);

        Execution exec = new Execution();
        exec.setExecutionArn(arn);
        exec.setStateMachineArn(stateMachineArn);
        exec.setName(execName);
        exec.setInput(input);
        exec.setStatus("RUNNING");

        List<HistoryEvent> history = new ArrayList<>();
        HistoryEvent startEvent = new HistoryEvent();
        startEvent.setId(1L);
        startEvent.setType("ExecutionStarted");
        startEvent.setDetails(Map.of("input", input != null ? input : "{}",
                                     "roleArn", sm.getRoleArn() != null ? sm.getRoleArn() : ""));
        history.add(startEvent);

        aslExecutor.executeSync(sm, exec, history, (updatedExec, updatedHistory) -> {
            LOG.infov("Sync execution {0} completed with status {1}", updatedExec.getExecutionArn(), updatedExec.getStatus());
        });

        return exec;
    }

    public Execution describeExecution(String arn) {
        return executionStore.get(arn)
                .orElseThrow(() -> new AwsException("ExecutionDoesNotExist", "Execution does not exist", 400));
    }

    public List<Execution> listExecutions(String stateMachineArn) {
        return executionStore.scan(k -> executionStore.get(k)
                .map(e -> e.getStateMachineArn().equals(stateMachineArn)).orElse(false));
    }

    public void stopExecution(String arn, String cause, String error) {
        Execution exec = describeExecution(arn);
        if (!"RUNNING".equals(exec.getStatus())) {
            return;
        }
        exec.setStatus("ABORTED");
        exec.setStopDate(System.currentTimeMillis() / 1000.0);
        executionStore.put(arn, exec);

        List<HistoryEvent> history = historyCache.getOrDefault(arn, new ArrayList<>());
        HistoryEvent event = new HistoryEvent();
        event.setId(history.size() + 1L);
        event.setType("ExecutionAborted");
        Map<String, Object> details = new HashMap<>();
        if (error != null) details.put("error", error);
        if (cause != null) details.put("cause", cause);
        event.setDetails(details);
        history.add(event);
    }

    public List<HistoryEvent> getExecutionHistory(String arn) {
        describeExecution(arn);
        return historyCache.getOrDefault(arn, Collections.emptyList());
    }

    // ──────────────────────────── Activities ────────────────────────────

    public Activity createActivity(String name, String region, Map<String, String> tags) {
        String arn = regionResolver.buildArn("states", region, "activity:" + name);
        if (activityStore.get(arn).isPresent()) {
            throw new AwsException("ActivityAlreadyExists", "Activity already exists: " + arn, 400);
        }
        Activity activity = new Activity();
        activity.setActivityArn(arn);
        activity.setName(name);
        if (tags != null && !tags.isEmpty()) {
            activity.getTags().putAll(tags);
        }
        activityStore.put(arn, activity);
        LOG.infov("Created activity: {0}", arn);
        return activity;
    }

    public Activity describeActivity(String arn) {
        return activityStore.get(arn)
                .orElseThrow(() -> new AwsException("ActivityDoesNotExist", "Activity does not exist: " + arn, 400));
    }

    public List<Activity> listActivities(String region) {
        String prefix = "arn:aws:states:" + region + ":";
        return activityStore.scan(k -> k.startsWith(prefix) && k.contains(":activity:"));
    }

    public void deleteActivity(String arn) {
        activityStore.delete(arn);
        activityQueues.remove(arn);
    }

    /**
     * Long-poll: blocks up to 60 seconds waiting for a task to be enqueued for this activity.
     * Returns null if no task arrives within the timeout.
     */
    public ActivityTask getActivityTask(String activityArn, String workerName) {
        describeActivity(activityArn); // validate exists
        BlockingQueue<ActivityTask> queue = activityQueues.computeIfAbsent(activityArn,
                k -> new LinkedBlockingQueue<>());
        try {
            return queue.poll(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void enqueueActivityTask(String activityArn, String taskToken, String input) {
        BlockingQueue<ActivityTask> queue = activityQueues.computeIfAbsent(activityArn,
                k -> new LinkedBlockingQueue<>());
        queue.add(new ActivityTask(taskToken, input));
    }

    public CompletableFuture<JsonNode> registerPendingToken(String token) {
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingTaskTokens.put(token, future);
        return future;
    }

    // ──────────────────────────── Tasks ────────────────────────────

    public void sendTaskSuccess(String taskToken, String output) {
        CompletableFuture<JsonNode> future = pendingTaskTokens.remove(taskToken);
        if (future != null) {
            try {
                future.complete(objectMapper.readTree(output));
            } catch (Exception e) {
                future.completeExceptionally(new RuntimeException("Invalid JSON output: " + e.getMessage()));
            }
        } else {
            LOG.warnv("SendTaskSuccess: no pending task for token {0}", taskToken);
        }
    }

    public void sendTaskFailure(String taskToken, String cause, String error) {
        CompletableFuture<JsonNode> future = pendingTaskTokens.remove(taskToken);
        if (future != null) {
            future.completeExceptionally(new AslExecutor.FailStateException(error, cause));
        } else {
            LOG.warnv("SendTaskFailure: no pending task for token {0}", taskToken);
        }
    }

    public void sendTaskHeartbeat(String taskToken) {
        LOG.debugv("Task heartbeat for token {0}", taskToken);
    }

    // ──────────────────────────── Tags ────────────────────────────

    public Map<String, String> listTags(String arn) {
        Optional<StateMachine> sm = stateMachineStore.get(arn);
        if (sm.isPresent()) {
            return sm.get().getTags();
        }
        Optional<Activity> activity = activityStore.get(arn);
        if (activity.isPresent()) {
            return activity.get().getTags();
        }
        throw new AwsException("ResourceNotFound", "Resource not found: " + arn, 400);
    }

    public void tagResource(String arn, Map<String, String> tags) {
        Optional<StateMachine> smOpt = stateMachineStore.get(arn);
        if (smOpt.isPresent()) {
            StateMachine sm = smOpt.get();
            sm.getTags().putAll(tags);
            stateMachineStore.put(arn, sm);
            return;
        }
        Optional<Activity> actOpt = activityStore.get(arn);
        if (actOpt.isPresent()) {
            Activity activity = actOpt.get();
            activity.getTags().putAll(tags);
            activityStore.put(arn, activity);
            return;
        }
        throw new AwsException("ResourceNotFound", "Resource not found: " + arn, 400);
    }

    public void untagResource(String arn, List<String> tagKeys) {
        Optional<StateMachine> smOpt = stateMachineStore.get(arn);
        if (smOpt.isPresent()) {
            StateMachine sm = smOpt.get();
            tagKeys.forEach(sm.getTags()::remove);
            stateMachineStore.put(arn, sm);
            return;
        }
        Optional<Activity> actOpt = activityStore.get(arn);
        if (actOpt.isPresent()) {
            Activity activity = actOpt.get();
            tagKeys.forEach(activity.getTags()::remove);
            activityStore.put(arn, activity);
            return;
        }
        throw new AwsException("ResourceNotFound", "Resource not found: " + arn, 400);
    }

    // ──────────────────────────── Validation ────────────────────────────

    public record Diagnostic(String severity, String code, String message, String location) {}
    public record ValidationResult(boolean valid, List<Diagnostic> diagnostics, boolean truncated) {}

    private static final int MAX_DEFINITION_LENGTH = 1_048_576;
    private static final String PARSE_ERROR_MARKER = "INVALID_JSON_DESCRIPTION:";

    // Parse the structured location out of the validator's flat error strings,
    // which currently encode it as "...field 'X' is only supported... at /States/Y".
    // AWS's published Diagnostic.location format is "/States/<StateName>/<FieldName>".
    private static final Pattern FIELD_PATTERN = Pattern.compile("field '([^']+)' is only supported");
    private static final Pattern LOCATION_SUFFIX_PATTERN = Pattern.compile(" at (/States/\\S+)$");

    /**
     * Exposes the existing ASL validator as a public, non-throwing API for
     * AWS {@code ValidateStateMachineDefinition}. Errors are returned as
     * diagnostics rather than thrown; no state machine is created. Mirrors
     * the wire shape of the AWS API.
     */
    public ValidationResult validateStateMachineDefinition(String definition, String type,
                                                           String severity, Integer maxResults) {
        if (definition == null || definition.isBlank()) {
            throw new AwsException("ValidationException", "definition is required.", 400);
        }
        if (definition.length() > MAX_DEFINITION_LENGTH) {
            throw new AwsException("ValidationException",
                    "definition exceeds maximum length of " + MAX_DEFINITION_LENGTH + " characters.", 400);
        }
        if (maxResults != null && (maxResults < 0 || maxResults > 100)) {
            throw new AwsException("ValidationException",
                    "maxResults must be between 0 and 100.", 400);
        }
        if (severity != null && !severity.isBlank()
                && !"ERROR".equals(severity) && !"WARNING".equals(severity)) {
            throw new AwsException("ValidationException",
                    "severity must be ERROR or WARNING.", 400);
        }
        if (type != null && !type.isBlank()
                && !"STANDARD".equals(type) && !"EXPRESS".equals(type)) {
            throw new AwsException("ValidationException",
                    "type must be STANDARD or EXPRESS.", 400);
        }
        // Per AWS spec: maxResults=0 (or absent/null) → use default of 100.
        // Out-of-range values are rejected above; no clamping needed here.
        int cap = (maxResults == null || maxResults == 0) ? 100 : maxResults;
        List<String> errors = collectValidationErrors(definition);
        List<Diagnostic> all = errors.stream()
                .map(StepFunctionsService::toDiagnostic)
                .toList();

        // Apply the severity filter per spec: ERROR (the default) returns only
        // error diagnostics; WARNING returns both warnings and errors. Floci's
        // validator only emits ERROR today so the filter is currently a no-op,
        // but it's wired now so adding warning-level checks later doesn't break
        // the contract for callers who passed severity=ERROR.
        String effectiveSeverity = severity == null || severity.isBlank() ? "ERROR" : severity;
        List<Diagnostic> filtered = "WARNING".equals(effectiveSeverity)
                ? all
                : all.stream().filter(d -> "ERROR".equals(d.severity())).toList();

        boolean truncated = filtered.size() > cap;
        List<Diagnostic> page = truncated ? filtered.subList(0, cap) : filtered;
        // valid == "no ERROR-level diagnostics". Floci only produces ERRORs today;
        // explicit check future-proofs us when warnings are added.
        boolean valid = page.stream().noneMatch(d -> "ERROR".equals(d.severity()));
        return new ValidationResult(valid, page, truncated);
    }

    private static Diagnostic toDiagnostic(String error) {
        boolean isParseError = error.startsWith(PARSE_ERROR_MARKER);
        String code = isParseError ? "INVALID_JSON_DESCRIPTION" : "SCHEMA_VALIDATION_FAILED";
        String message = isParseError
                ? error.substring(PARSE_ERROR_MARKER.length()).trim() : error;
        // null when there's no specific location to point to — handler omits the
        // field from the response in that case, matching AWS's "optional" semantics.
        String location = null;
        if (!isParseError) {
            Matcher locM = LOCATION_SUFFIX_PATTERN.matcher(message);
            Matcher fieldM = FIELD_PATTERN.matcher(message);
            if (locM.find() && fieldM.find()) {
                // Build the structured location and strip the redundant suffix
                // from the message, matching AWS's wire format.
                location = locM.group(1) + "/" + fieldM.group(1);
                message = message.substring(0, locM.start()).trim();
            }
        }
        return new Diagnostic("ERROR", code, message, location);
    }

    private void validateDefinition(String definition) {
        List<String> errors = collectValidationErrors(definition);
        if (errors.isEmpty()) {
            return;
        }
        String first = errors.get(0);
        if (first.startsWith(PARSE_ERROR_MARKER)) {
            // Preserve historical wire shape for parse errors triggered via CreateStateMachine.
            throw new AwsException("InvalidDefinition",
                    "Invalid State Machine Definition: '" + first.substring(PARSE_ERROR_MARKER.length()).trim() + "'", 400);
        }
        throw new AwsException("InvalidDefinition",
                "Invalid State Machine Definition: 'SCHEMA_VALIDATION_FAILED: "
                        + String.join(", ", errors) + "'", 400);
    }

    private List<String> collectValidationErrors(String definition) {
        List<String> errors = new ArrayList<>();
        JsonNode def;
        try {
            def = objectMapper.readTree(definition);
        } catch (Exception e) {
            errors.add(PARSE_ERROR_MARKER + e.getMessage());
            return errors;
        }

        String topLevelQL = def.path("QueryLanguage").asText("JSONPath");
        boolean topLevelJsonata = "JSONata".equals(topLevelQL);
        JsonNode states = def.path("States");

        if (states.isObject()) {
            var fields = states.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                validateState(entry.getKey(), entry.getValue(), topLevelJsonata, errors);
            }
        }
        return errors;
    }

    private void validateState(String stateName, JsonNode stateDef, boolean topLevelJsonata, List<String> errors) {
        String stateQL = stateDef.path("QueryLanguage").asText(null);
        boolean stateIsJsonata = stateQL != null ? "JSONata".equals(stateQL) : topLevelJsonata;

        // JSONPath-only fields are not allowed when the state uses JSONata
        if (stateIsJsonata) {
            for (String field : JSONPATH_ONLY_FIELDS) {
                if (stateDef.has(field)) {
                    errors.add("The QueryLanguage is set to 'JSONata', but field '" + field
                            + "' is only supported for the 'JSONPath' QueryLanguage at /States/" + stateName);
                }
            }
        }
    }
}
