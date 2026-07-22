package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
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
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachineVersion;
import io.github.hectorvent.floci.core.common.Resettable;
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
public class StepFunctionsService implements Resettable {

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
    private static final Set<String> ITEM_READER_RESOURCES = Set.of(
            "arn:aws:states:::s3:getObject",
            "arn:aws:states:::s3:listObjectsV2");
    private static final Set<String> ITEM_READER_INPUT_TYPES = Set.of(
            "MANIFEST", "JSON", "CSV", "JSONL", "PARQUET");

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

    public void clear() {
        historyCache.clear();
        activityQueues.clear();
        pendingTaskTokens.values().forEach(f -> f.completeExceptionally(new RuntimeException("StepFunctionsService cleared")));
        pendingTaskTokens.clear();
    }

    // ──────────────────────────── State Machines ────────────────────────────

    public StateMachine createStateMachine(String name, String definition, String roleArn, String type,
                                           String region, Map<String, String> tags) {
        return createStateMachine(name, definition, roleArn, type, region, tags, null, null, null);
    }

    public StateMachine createStateMachine(String name, String definition, String roleArn, String type,
                                           String region, Map<String, String> tags,
                                           JsonNode loggingConfiguration,
                                           JsonNode tracingConfiguration,
                                           JsonNode encryptionConfiguration) {
        return createStateMachine(
                name, definition, roleArn, type, region, tags,
                loggingConfiguration, tracingConfiguration, encryptionConfiguration,
                false, null).stateMachine();
    }

    public synchronized CreateStateMachineResult createStateMachine(
            String name, String definition, String roleArn, String type,
            String region, Map<String, String> tags,
            JsonNode loggingConfiguration,
            JsonNode tracingConfiguration,
            JsonNode encryptionConfiguration,
            boolean publish,
            String versionDescription) {
        validateStateMachineName(name);
        String arn = regionResolver.buildArn("states", region, "stateMachine:" + name);
        if (stateMachineStore.get(arn).isPresent()) {
            throw new AwsException("StateMachineAlreadyExists", "State machine already exists: " + arn, 400);
        }

        validateDefinition(definition);
        validateRoleArn(roleArn);
        if (loggingConfiguration != null) {
            validateLoggingConfiguration(loggingConfiguration);
        }
        if (tracingConfiguration != null) {
            validateTracingConfiguration(tracingConfiguration);
        }
        if (encryptionConfiguration != null) {
            validateEncryptionConfiguration(encryptionConfiguration);
        }
        validateVersionDescription(publish, versionDescription);

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
        sm.setRevisionId(UUID.randomUUID().toString());
        sm.setLoggingConfiguration(copyNode(loggingConfiguration));
        sm.setTracingConfiguration(copyNode(tracingConfiguration));
        sm.setEncryptionConfiguration(copyNode(encryptionConfiguration));

        StateMachineVersion version = publish ? addVersion(sm, versionDescription) : null;
        stateMachineStore.put(arn, sm);
        LOG.infov("Created State Machine: {0}", arn);
        return new CreateStateMachineResult(copyStateMachine(sm), version);
    }

    public record CreateStateMachineResult(StateMachine stateMachine, StateMachineVersion version) {
    }

    public record UpdateStateMachineRequest(
            String definition,
            String roleArn,
            Map<String, String> tags,
            JsonNode loggingConfiguration,
            boolean loggingConfigurationProvided,
            JsonNode tracingConfiguration,
            boolean tracingConfigurationProvided,
            JsonNode encryptionConfiguration,
            boolean encryptionConfigurationProvided,
            boolean publish,
            String versionDescription) {
    }

    public record UpdateStateMachineResult(StateMachine stateMachine, StateMachineVersion version) {
    }

    public StateMachine updateStateMachine(String arn, String definition, String roleArn, Map<String, String> tags) {
        return updateStateMachine(arn, new UpdateStateMachineRequest(
                definition, roleArn, tags,
                null, false, null, false, null, false,
                false, null)).stateMachine();
    }

    public synchronized UpdateStateMachineResult updateStateMachine(String arn, UpdateStateMachineRequest request) {
        validateUpdateStateMachineArn(arn);
        if (request.definition() == null && request.roleArn() == null) {
            throw new AwsException("MissingRequiredParameter",
                    "Either the definition or the roleArn must be specified.", 400);
        }
        if (request.definition() != null) {
            validateDefinition(request.definition());
        }
        if (request.roleArn() != null) {
            validateRoleArn(request.roleArn());
        }
        if (request.loggingConfigurationProvided()) {
            validateLoggingConfiguration(request.loggingConfiguration());
        }
        if (request.tracingConfigurationProvided()) {
            validateTracingConfiguration(request.tracingConfiguration());
        }
        if (request.encryptionConfigurationProvided()) {
            validateEncryptionConfiguration(request.encryptionConfiguration());
        }
        validateVersionDescription(request.publish(), request.versionDescription());

        StateMachine current = stateMachineStore.get(arn)
                .orElseThrow(() -> new AwsException("StateMachineDoesNotExist", "State machine does not exist: " + arn, 400));
        StateMachine updated = copyStateMachine(current);

        if (request.definition() != null) {
            updated.setDefinition(request.definition());
        }
        if (request.roleArn() != null) {
            updated.setRoleArn(request.roleArn());
        }
        if (request.tags() != null) {
            updated.setTags(new HashMap<>(request.tags()));
        }
        if (request.loggingConfigurationProvided()) {
            updated.setLoggingConfiguration(copyNode(request.loggingConfiguration()));
        }
        if (request.tracingConfigurationProvided()) {
            updated.setTracingConfiguration(copyNode(request.tracingConfiguration()));
        }
        if (request.encryptionConfigurationProvided()) {
            updated.setEncryptionConfiguration(copyNode(request.encryptionConfiguration()));
        }

        updated.setRevisionId(UUID.randomUUID().toString());
        updated.setUpdateDate(System.currentTimeMillis() / 1000.0);
        StateMachineVersion version = request.publish()
                ? addVersion(updated, request.versionDescription()) : null;
        stateMachineStore.put(arn, updated);
        LOG.infov("Updated State Machine: {0}", arn);
        return new UpdateStateMachineResult(copyStateMachine(updated), version);
    }

    public StateMachine describeStateMachine(String arn) {
        Optional<StateMachine> stateMachine = stateMachineStore.get(arn);
        if (stateMachine.isPresent()) {
            return copyStateMachine(stateMachine.get());
        }

        VersionArn versionArn = parseVersionArn(arn);
        if (versionArn != null) {
            StateMachine base = stateMachineStore.get(versionArn.baseArn())
                    .orElseThrow(() -> new AwsException(
                            "StateMachineDoesNotExist", "State machine does not exist", 400));
            return base.getVersions().stream()
                    .filter(version -> arn.equals(version.getStateMachineVersionArn()))
                    .findFirst()
                    .map(version -> stateMachineFromVersion(base, version))
                    .orElseThrow(() -> new AwsException(
                            "StateMachineDoesNotExist", "State machine version does not exist", 400));
        }

        throw new AwsException("StateMachineDoesNotExist", "State machine does not exist", 400);
    }

    public List<StateMachine> listStateMachines(String region) {
        String prefix = "arn:aws:states:" + region + ":";
        return stateMachineStore.scan(k -> k.startsWith(prefix));
    }

    // ── State machine versions ──────────────────────────────────────────────

    public StateMachineVersion publishStateMachineVersion(String stateMachineArn) {
        return publishStateMachineVersion(stateMachineArn, null, null);
    }

    public synchronized StateMachineVersion publishStateMachineVersion(String stateMachineArn,
                                                                        String revisionId,
                                                                        String description) {
        validateStateMachineArn(stateMachineArn);
        validateVersionDescription(true, description);
        StateMachine current = stateMachineStore.get(stateMachineArn)
                .orElseThrow(() -> new AwsException("StateMachineDoesNotExist",
                        "State machine does not exist: " + stateMachineArn, 400));
        if (revisionId != null
                && !(current.getRevisionId() == null && "INITIAL".equals(revisionId))
                && !Objects.equals(revisionId, current.getRevisionId())) {
            throw new AwsException("ConflictException",
                    "The state machine revision does not match revisionId " + revisionId, 400);
        }

        StateMachine updated = copyStateMachine(current);
        if (updated.getRevisionId() == null) {
            updated.setRevisionId(UUID.randomUUID().toString());
        }
        Optional<StateMachineVersion> existingVersion = updated.getVersions().stream()
                .filter(version -> Objects.equals(updated.getRevisionId(), version.getRevisionId()))
                .findFirst();
        if (existingVersion.isPresent()) {
            return existingVersion.get();
        }
        StateMachineVersion version = addVersion(updated, description);
        stateMachineStore.put(stateMachineArn, updated);
        return version;
    }

    public List<StateMachineVersion> listStateMachineVersions(String stateMachineArn) {
        // AWS returns InvalidArn for a non-existent state machine here — StateMachineDoesNotExist is
        // not one of ListStateMachineVersions' declared errors (Publish, which does declare it, keeps
        // using describeStateMachine).
        StateMachine sm = stateMachineStore.get(stateMachineArn)
                .orElseThrow(() -> new AwsException("InvalidArn",
                        "Invalid Arn: '" + stateMachineArn + "'", 400));
        // AWS lists versions newest first (descending by creationDate). creationDate is only
        // second-resolution, so tie-break on the version number (also descending) to keep the order
        // correct when several versions are published within the same second — otherwise the
        // Terraform provider can latch onto the wrong version ARN.
        List<StateMachineVersion> versions = new ArrayList<>(sm.getVersions());
        versions.sort(Comparator
                .comparingDouble(StateMachineVersion::getCreationDate)
                .thenComparingInt(StateMachineVersion::getVersion)
                .reversed());
        // Defensive copy so callers can't mutate (or trip over concurrent mutation of) the stored list.
        return List.copyOf(versions);
    }

    public synchronized void deleteStateMachineVersion(String stateMachineVersionArn) {
        int lastColon = stateMachineVersionArn.lastIndexOf(':');
        if (lastColon < 0) {
            return;
        }
        String baseArn = stateMachineVersionArn.substring(0, lastColon);
        stateMachineStore.get(baseArn).ifPresent(current -> {
            StateMachine updated = copyStateMachine(current);
            updated.getVersions().removeIf(v -> stateMachineVersionArn.equals(v.getStateMachineVersionArn()));
            stateMachineStore.put(baseArn, updated);
        });
    }

    public synchronized void deleteStateMachine(String arn) {
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

    public synchronized void tagResource(String arn, Map<String, String> tags) {
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

    public synchronized void untagResource(String arn, List<String> tagKeys) {
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

    private StateMachineVersion addVersion(StateMachine stateMachine, String description) {
        int next = stateMachine.getVersionCounter() + 1;
        stateMachine.setVersionCounter(next);

        StateMachineVersion version = new StateMachineVersion(
                stateMachine.getStateMachineArn() + ":" + next,
                next,
                System.currentTimeMillis() / 1000.0);
        version.setDefinition(stateMachine.getDefinition());
        version.setRoleArn(stateMachine.getRoleArn());
        version.setType(stateMachine.getType());
        version.setRevisionId(stateMachine.getRevisionId());
        version.setDescription(description);
        version.setLoggingConfiguration(copyNode(stateMachine.getLoggingConfiguration()));
        version.setTracingConfiguration(copyNode(stateMachine.getTracingConfiguration()));
        version.setEncryptionConfiguration(copyNode(stateMachine.getEncryptionConfiguration()));
        stateMachine.getVersions().add(version);
        return version;
    }

    private StateMachine stateMachineFromVersion(StateMachine base, StateMachineVersion version) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setStateMachineArn(version.getStateMachineVersionArn());
        stateMachine.setName(base.getName());
        stateMachine.setDefinition(version.getDefinition());
        stateMachine.setRoleArn(version.getRoleArn());
        stateMachine.setType(version.getType());
        stateMachine.setStatus(base.getStatus());
        stateMachine.setCreationDate(version.getCreationDate());
        stateMachine.setRevisionId(version.getRevisionId());
        stateMachine.setDescription(version.getDescription());
        stateMachine.setLoggingConfiguration(copyNode(version.getLoggingConfiguration()));
        stateMachine.setTracingConfiguration(copyNode(version.getTracingConfiguration()));
        stateMachine.setEncryptionConfiguration(copyNode(version.getEncryptionConfiguration()));
        return stateMachine;
    }

    private StateMachine copyStateMachine(StateMachine source) {
        StateMachine copy = new StateMachine();
        copy.setStateMachineArn(source.getStateMachineArn());
        copy.setName(source.getName());
        copy.setDefinition(source.getDefinition());
        copy.setRoleArn(source.getRoleArn());
        copy.setType(source.getType());
        copy.setStatus(source.getStatus());
        copy.setCreationDate(source.getCreationDate());
        copy.setUpdateDate(source.getUpdateDate());
        copy.setRevisionId(source.getRevisionId());
        copy.setDescription(source.getDescription());
        copy.setLoggingConfiguration(copyNode(source.getLoggingConfiguration()));
        copy.setTracingConfiguration(copyNode(source.getTracingConfiguration()));
        copy.setEncryptionConfiguration(copyNode(source.getEncryptionConfiguration()));
        copy.setTags(new HashMap<>(source.getTags() != null ? source.getTags() : Map.of()));
        copy.setVersionCounter(source.getVersionCounter());
        copy.setVersions(new ArrayList<>(source.getVersions() != null ? source.getVersions() : List.of()));
        return copy;
    }

    private static JsonNode copyNode(JsonNode node) {
        return node == null ? null : node.deepCopy();
    }

    private record VersionArn(String baseArn, int version) {
    }

    private static VersionArn parseVersionArn(String arn) {
        if (arn == null) {
            return null;
        }
        int separator = arn.lastIndexOf(':');
        if (separator < 0 || separator == arn.length() - 1) {
            return null;
        }
        String suffix = arn.substring(separator + 1);
        if (!suffix.chars().allMatch(Character::isDigit)) {
            return null;
        }
        String baseArn = arn.substring(0, separator);
        try {
            return new VersionArn(baseArn, Integer.parseInt(suffix));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    // ──────────────────────────── Validation ────────────────────────────

    public record Diagnostic(String severity, String code, String message, String location) {}
    public record ValidationResult(boolean valid, List<Diagnostic> diagnostics, boolean truncated) {}

    private static final int MAX_DEFINITION_LENGTH = 1_048_576;
    private static final int MAX_ARN_LENGTH = 256;
    private static final int MAX_VERSION_DESCRIPTION_LENGTH = 256;
    private static final String INVALID_STATE_MACHINE_NAME_CHARACTERS = "<>[]{}?*\"#%\\^|~`$&,;:/";
    private static final Set<String> STATE_TYPES = Set.of(
            "Pass", "Task", "Choice", "Wait", "Succeed", "Fail", "Parallel", "Map");
    private static final String PARSE_ERROR_MARKER = "INVALID_JSON_DESCRIPTION:";

    // Parse the structured location out of validator flat error strings,
    // which currently encode it as "...field 'X' ... at /States/Y".
    // AWS's published Diagnostic.location format is "/States/<StateName>/<FieldName>".
    private static final Pattern FIELD_PATTERN = Pattern.compile("field '([^']+)'");
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
                location = locM.group(1);
                String field = fieldM.group(1);
                if (!location.endsWith("/" + field)) {
                    location = location + "/" + field;
                }
                message = message.substring(0, locM.start()).trim();
            }
        }
        return new Diagnostic("ERROR", code, message, location);
    }

    private static void validateStateMachineName(String name) {
        boolean invalidCharacter = name != null && name.codePoints().anyMatch(codePoint ->
                Character.isWhitespace(codePoint)
                        || Character.isISOControl(codePoint)
                        || INVALID_STATE_MACHINE_NAME_CHARACTERS.indexOf(codePoint) >= 0);
        if (name == null || name.isBlank() || name.length() > 80 || invalidCharacter) {
            throw new AwsException("InvalidName", "Invalid state machine name: '" + name + "'", 400);
        }
    }

    private static void validateStateMachineArn(String arn) {
        if (arn == null || arn.isBlank() || arn.length() > MAX_ARN_LENGTH) {
            throw new AwsException("InvalidArn", "Invalid Arn: '" + arn + "'", 400);
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            String resource = parsed.resource();
            String name = resource != null && resource.startsWith("stateMachine:")
                    ? resource.substring("stateMachine:".length()) : null;
            if (!"states".equals(parsed.service())
                    || parsed.region().isBlank()
                    || parsed.accountId().isBlank()
                    || name == null
                    || name.isBlank()
                    || name.length() > 80
                    || name.indexOf(':') >= 0
                    || name.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Invalid state machine ARN");
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidArn", "Invalid Arn: '" + arn + "'", 400);
        }
    }

    private static void validateUpdateStateMachineArn(String arn) {
        if (arn != null && arn.length() <= MAX_ARN_LENGTH) {
            try {
                AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
                String resource = parsed.resource();
                String name = resource != null && resource.startsWith("stateMachine:")
                        ? resource.substring("stateMachine:".length()) : null;
                int qualifierSeparator = name != null ? name.indexOf('/') : -1;
                if ("states".equals(parsed.service())
                        && !parsed.region().isBlank()
                        && !parsed.accountId().isBlank()
                        && qualifierSeparator > 0
                        && qualifierSeparator < name.length() - 1) {
                    throw new AwsException("ValidationException",
                            "UpdateStateMachine does not accept a qualified Distributed Map ARN.", 400);
                }
            } catch (IllegalArgumentException ignored) {
                // The general ARN validator below maps malformed input to InvalidArn.
            }
        }
        validateStateMachineArn(arn);
    }

    private static void validateRoleArn(String roleArn) {
        if (roleArn == null || roleArn.isBlank() || roleArn.length() > MAX_ARN_LENGTH) {
            throw new AwsException("InvalidArn", "Invalid roleArn: '" + roleArn + "'", 400);
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(roleArn);
            String resource = parsed.resource();
            if (!"iam".equals(parsed.service())
                    || !parsed.region().isEmpty()
                    || parsed.accountId().isBlank()
                    || resource == null
                    || !resource.startsWith("role/")
                    || resource.length() == "role/".length()
                    || roleArn.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("Invalid IAM role ARN");
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidArn", "Invalid roleArn: '" + roleArn + "'", 400);
        }
    }

    private static void validateVersionDescription(boolean publish, String description) {
        if (description != null && !publish) {
            throw new AwsException("ValidationException",
                    "versionDescription can only be specified when publish is true.", 400);
        }
        if (description != null && description.length() > MAX_VERSION_DESCRIPTION_LENGTH) {
            throw new AwsException("ValidationException",
                    "versionDescription exceeds maximum length of "
                            + MAX_VERSION_DESCRIPTION_LENGTH + " characters.", 400);
        }
    }

    private static void validateLoggingConfiguration(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()) {
            throw new AwsException("InvalidLoggingConfiguration",
                    "loggingConfiguration must be an object.", 400);
        }

        JsonNode levelNode = configuration.get("level");
        String level = levelNode == null ? "OFF" : levelNode.asText(null);
        if (level == null || !Set.of("ALL", "ERROR", "FATAL", "OFF").contains(level)) {
            throw new AwsException("InvalidLoggingConfiguration",
                    "loggingConfiguration.level must be ALL, ERROR, FATAL, or OFF.", 400);
        }
        JsonNode includeData = configuration.get("includeExecutionData");
        if (includeData != null && !includeData.isBoolean()) {
            throw new AwsException("InvalidLoggingConfiguration",
                    "loggingConfiguration.includeExecutionData must be a boolean.", 400);
        }

        JsonNode destinations = configuration.get("destinations");
        if (destinations != null && (!destinations.isArray() || destinations.size() > 1)) {
            throw new AwsException("InvalidLoggingConfiguration",
                    "loggingConfiguration.destinations must contain at most one destination.", 400);
        }
        if (!"OFF".equals(level) && (destinations == null || destinations.isEmpty())) {
            throw new AwsException("InvalidLoggingConfiguration",
                    "A logging destination is required when the log level is not OFF.", 400);
        }
        if (destinations != null) {
            for (JsonNode destination : destinations) {
                String logGroupArn = destination.path("cloudWatchLogsLogGroup")
                        .path("logGroupArn").asText(null);
                if (logGroupArn == null || logGroupArn.isBlank()) {
                    throw new AwsException("InvalidLoggingConfiguration",
                            "Each logging destination must include a CloudWatch Logs logGroupArn.", 400);
                }
            }
        }
    }

    private static void validateTracingConfiguration(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()) {
            throw new AwsException("InvalidTracingConfiguration",
                    "tracingConfiguration must be an object.", 400);
        }
        JsonNode enabled = configuration.get("enabled");
        if (enabled != null && !enabled.isBoolean()) {
            throw new AwsException("InvalidTracingConfiguration",
                    "tracingConfiguration.enabled must be a boolean.", 400);
        }
    }

    private static void validateEncryptionConfiguration(JsonNode configuration) {
        if (configuration == null || !configuration.isObject()) {
            throw new AwsException("InvalidEncryptionConfiguration",
                    "encryptionConfiguration must be an object.", 400);
        }
        String type = configuration.path("type").asText(null);
        if (type == null || !Set.of("AWS_OWNED_KEY", "CUSTOMER_MANAGED_KMS_KEY").contains(type)) {
            throw new AwsException("InvalidEncryptionConfiguration",
                    "encryptionConfiguration.type must be AWS_OWNED_KEY or CUSTOMER_MANAGED_KMS_KEY.", 400);
        }

        JsonNode keyId = configuration.get("kmsKeyId");
        if (keyId != null && (!keyId.isTextual() || keyId.asText().isBlank() || keyId.asText().length() > 2048)) {
            throw new AwsException("InvalidEncryptionConfiguration",
                    "encryptionConfiguration.kmsKeyId must contain between 1 and 2048 characters.", 400);
        }
        if ("CUSTOMER_MANAGED_KMS_KEY".equals(type) && keyId == null) {
            throw new AwsException("InvalidEncryptionConfiguration",
                    "encryptionConfiguration.kmsKeyId is required for a customer-managed key.", 400);
        }

        JsonNode reusePeriod = configuration.get("kmsDataKeyReusePeriodSeconds");
        if (reusePeriod != null
                && (!reusePeriod.isIntegralNumber()
                || reusePeriod.asInt() < 60
                || reusePeriod.asInt() > 900)) {
            throw new AwsException("InvalidEncryptionConfiguration",
                    "encryptionConfiguration.kmsDataKeyReusePeriodSeconds must be between 60 and 900.", 400);
        }
    }

    private void validateDefinition(String definition) {
        if (definition == null || definition.isBlank()) {
            throw new AwsException("InvalidDefinition",
                    "Invalid State Machine Definition: definition must not be empty.", 400);
        }
        if (definition.length() > MAX_DEFINITION_LENGTH) {
            throw new AwsException("ValidationException",
                    "definition exceeds maximum length of " + MAX_DEFINITION_LENGTH + " characters.", 400);
        }
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

        if (def == null || !def.isObject()) {
            errors.add("The state machine definition must be a JSON object");
            return errors;
        }

        JsonNode startAtNode = def.get("StartAt");
        String startAt = startAtNode != null && startAtNode.isTextual()
                ? startAtNode.asText() : null;
        if (startAt == null || startAt.isBlank()) {
            errors.add("The field 'StartAt' is required and must be a non-empty string");
        }

        JsonNode states = def.get("States");
        if (states == null || !states.isObject() || states.isEmpty()) {
            errors.add("The field 'States' is required and must be a non-empty object");
            return errors;
        }
        if (startAt != null && !startAt.isBlank() && !states.has(startAt)) {
            errors.add("The value of 'StartAt' must match a state in 'States'");
        }

        String topLevelQL = def.path("QueryLanguage").asText("JSONPath");
        boolean topLevelJsonata = "JSONata".equals(topLevelQL);

        var fields = states.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            validateState(entry.getKey(), entry.getValue(), topLevelJsonata, errors);
        }
        return errors;
    }

    private void validateState(String stateName, JsonNode stateDef, boolean topLevelJsonata, List<String> errors) {
        if (!stateDef.isObject()) {
            errors.add("State '" + stateName + "' must be an object at /States/" + stateName);
            return;
        }
        String stateType = stateDef.path("Type").asText(null);
        if (stateType == null || !STATE_TYPES.contains(stateType)) {
            errors.add("State '" + stateName + "' must declare a valid field 'Type' at /States/" + stateName);
            return;
        }
        boolean terminalType = "Succeed".equals(stateType) || "Fail".equals(stateType);
        boolean choiceType = "Choice".equals(stateType);
        boolean hasTerminalTransition = stateDef.path("End").asBoolean(false)
                || stateDef.path("Next").isTextual();
        if (!terminalType && !choiceType && !hasTerminalTransition) {
            errors.add("State '" + stateName
                    + "' must declare either 'Next' or 'End' at /States/" + stateName);
        }
        if (choiceType && (!stateDef.path("Choices").isArray() || stateDef.path("Choices").isEmpty())) {
            errors.add("Choice state '" + stateName
                    + "' must declare a non-empty field 'Choices' at /States/" + stateName);
        }

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

        if ("Map".equals(stateType) && stateDef.has("ItemReader")) {
            validateItemReader(stateName, stateDef, errors);
        }
    }

    private void validateItemReader(String stateName, JsonNode stateDef, List<String> errors) {
        JsonNode itemReader = stateDef.get("ItemReader");
        String resource = itemReader.path("Resource").asText(null);
        if (resource != null && !ITEM_READER_RESOURCES.contains(resource)) {
            errors.add("The field 'Resource' does not match any of the allowed values. Examples: "
                    + "[arn:<partition>:states:::s3:getObject, arn:<partition>:states:::s3:listObjectsV2]"
                    + " at /States/" + stateName + "/ItemReader/Resource");
        }

        String inputType = itemReader.path("ReaderConfig").path("InputType").asText(null);
        if (inputType != null && !ITEM_READER_INPUT_TYPES.contains(inputType)) {
            errors.add("The field 'InputType' should have one of these values: "
                    + "[MANIFEST, JSON, CSV, JSONL, PARQUET]"
                    + " at /States/" + stateName + "/ItemReader/ReaderConfig/InputType");
        }
    }
}
