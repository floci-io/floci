package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.resource.ResourceProvider;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import io.github.hectorvent.floci.services.stepfunctions.model.ActivityTask;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.MapRun;
import io.github.hectorvent.floci.services.stepfunctions.model.MockedTestCase;
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
public class StepFunctionsService implements Resettable, ResourceProvider {

    private static final Logger LOG = Logger.getLogger(StepFunctionsService.class);

    private final StorageBackend<String, StateMachine> stateMachineStore;
    private final StorageBackend<String, Execution> executionStore;
    private final StorageBackend<String, Activity> activityStore;
    private final StorageBackend<String, MapRun> mapRunStore;
    private final Map<String, ExecutionHistory> historyCache = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<ActivityTask>> activityQueues = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<JsonNode>> pendingTaskTokens = new ConcurrentHashMap<>();
    // When each pending token last showed progress, so a Task's HeartbeatSeconds can bound the gap
    // between heartbeats instead of the whole wait.
    private final Map<String, Long> taskHeartbeatNanos = new ConcurrentHashMap<>();
    // Serializes the StartExecution check-and-create so two concurrent calls with the same name cannot
    // both create and launch an execution; also enables AWS idempotent-success for STANDARD workflows.
    private final Object executionStartLock = new Object();
    private final RegionResolver regionResolver;
    private final AslExecutor aslExecutor;
    private final ObjectMapper objectMapper;
    private final SfnMockLoader mockLoader;

    // Fields that are valid only in JSONPath mode. Validated against real AWS:
    // creating a JSONata state machine with any of these fields returns SCHEMA_VALIDATION_FAILED.
    private static final Set<String> JSONPATH_ONLY_FIELDS = Set.of(
            "InputPath", "OutputPath", "ResultPath", "ResultSelector", "Parameters", "Result", "ItemsPath",
            "MaxConcurrencyPath");
    // A {% %} string in one of these ASL fields is not an expression on AWS: Comment, Next,
    // Default and Resource keep it as text, ErrorEquals and Retry hold error names and integers,
    // ReaderConfig.CSVHeaders holds literal column names, and the JSONata support of
    // Credentials.RoleArn is hidden behind an ARN check that fires first. ItemProcessor, Iterator
    // and Branches carry nested states, walked as states of their own.
    private static final Set<String> ASL_FIELDS_AWS_DOES_NOT_PARSE_AS_JSONATA = Set.of(
            "Comment", "Next", "Default", "Resource", "ErrorEquals", "Retry", "Credentials",
            "ItemProcessor", "Iterator", "Branches", "CSVHeaders");
    // The fields whose value is a user payload rather than ASL. AWS parses every string inside
    // one, at any depth, so a payload key that happens to be named Next or Comment is an
    // expression there and the deny list above stops applying once the walk enters one.
    private static final Set<String> JSONATA_PAYLOAD_FIELDS = Set.of(
            "Output", "Assign", "Arguments", "ItemSelector", "BatchInput");
    private static final Set<String> ITEM_READER_RESOURCES = Set.of(
            "arn:aws:states:::s3:getObject",
            "arn:aws:states:::s3:listObjectsV2");
    private static final Set<String> ITEM_READER_INPUT_TYPES = Set.of(
            "MANIFEST", "JSON", "CSV", "JSONL", "PARQUET");
    private static final String RESULT_WRITER_RESOURCE = "arn:aws:states:::s3:putObject";
    private static final Set<String> RESULT_WRITER_TRANSFORMATIONS = Set.of("NONE", "COMPACT", "FLATTEN");
    private static final Set<String> RESULT_WRITER_OUTPUT_TYPES = Set.of("JSON", "JSONL");
    // Measured against real AWS: TimeoutSeconds is accepted on Task only, Catch and Retry are
    // accepted on Task, Parallel and Map only. Every other state type refuses these fields with
    // "Field '<name>' is not supported". A List, not a Map.of: Map.of's iteration order is salted
    // per JVM, so a state carrying more than one disallowed field needs a fixed emission order
    // instead. TimeoutSeconds, Catch, Retry is not an AWS-observed order — field declaration order
    // is not observable in the response — it is simply the one this code commits to.
    private record FieldStateTypeRule(String field, Set<String> allowedTypes) {}

    private static final List<FieldStateTypeRule> FIELDS_ALLOWED_STATE_TYPES = List.of(
            new FieldStateTypeRule("TimeoutSeconds", Set.of("Task")),
            new FieldStateTypeRule("Catch", Set.of("Task", "Parallel", "Map")),
            new FieldStateTypeRule("Retry", Set.of("Task", "Parallel", "Map")));

    @Inject
    public StepFunctionsService(StorageFactory storageFactory, RegionResolver regionResolver,
                                AslExecutor aslExecutor, ObjectMapper objectMapper,
                                SfnMockLoader mockLoader) {
        this.stateMachineStore = storageFactory.create("stepfunctions", "sfn-state-machines.json",
                new TypeReference<Map<String, StateMachine>>() {});
        this.executionStore = storageFactory.create("stepfunctions", "sfn-executions.json",
                new TypeReference<Map<String, Execution>>() {});
        this.activityStore = storageFactory.create("stepfunctions", "sfn-activities.json",
                new TypeReference<Map<String, Activity>>() {});
        this.mapRunStore = storageFactory.create("stepfunctions", "sfn-map-runs.json",
                new TypeReference<Map<String, MapRun>>() {});
        this.regionResolver = regionResolver;
        this.aslExecutor = aslExecutor;
        this.objectMapper = objectMapper;
        this.mockLoader = mockLoader;
    }

    public void clear() {
        historyCache.clear();
        activityQueues.clear();
        pendingTaskTokens.values().forEach(f -> f.completeExceptionally(new RuntimeException("StepFunctionsService cleared")));
        pendingTaskTokens.clear();
        taskHeartbeatNanos.clear();
    }

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (StateMachine sm : stateMachineStore.scan(k -> true)) {
            String arn = sm.getStateMachineArn();
            if (arn == null) {
                continue;
            }
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            resources.add(new ExplorerResource(
                    arn, "states:stateMachine", "states",
                    parsed.region(), parsed.accountId(),
                    sm.getCreationDate() > 0 ? Instant.ofEpochMilli((long) (sm.getCreationDate() * 1000)) : Instant.now(),
                    sm.getTags() != null ? sm.getTags() : Map.of()));
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(new SupportedResourceType("states:stateMachine", "states", true));
    }

    // ──────────────────────────── State Machines ────────────────────────────

    public StateMachine createStateMachine(String name, String definition, String roleArn, String type,
                                           String region, Map<String, String> tags) {
        return createStateMachine(name, definition, roleArn, type, region, tags, null, null, null);
    }

    public StateMachine createStateMachine(
            String name,
            String definition,
            String roleArn,
            String type,
            String region,
            Map<String, String> tags,
            JsonNode loggingConfiguration,
            JsonNode tracingConfiguration,
            JsonNode encryptionConfiguration) {
        return createStateMachineInternal(
                name,
                definition,
                roleArn,
                type,
                region,
                tags,
                loggingConfiguration,
                tracingConfiguration,
                encryptionConfiguration,
                false,
                null,
                null,
                false).stateMachine();
    }

    public CreateStateMachineResult createStateMachine(
            String name,
            String definition,
            String roleArn,
            String type,
            String region,
            Map<String, String> tags,
            JsonNode loggingConfiguration,
            JsonNode tracingConfiguration,
            JsonNode encryptionConfiguration,
            boolean publish,
            String versionDescription) {
        return createStateMachineInternal(
                name,
                definition,
                roleArn,
                type,
                region,
                tags,
                loggingConfiguration,
                tracingConfiguration,
                encryptionConfiguration,
                publish,
                versionDescription,
                null,
                true);
    }

    public StateMachine createStateMachineWithRevisionId(
            String name,
            String definition,
            String roleArn,
            String type,
            String region,
            Map<String, String> tags,
            JsonNode loggingConfiguration,
            JsonNode tracingConfiguration,
            JsonNode encryptionConfiguration,
            String revisionId) {
        if (revisionId == null || revisionId.isBlank()) {
            throw new IllegalArgumentException("revisionId is required");
        }
        return createStateMachineInternal(
                name,
                definition,
                roleArn,
                type,
                region,
                tags,
                loggingConfiguration,
                tracingConfiguration,
                encryptionConfiguration,
                false,
                null,
                revisionId,
                false).stateMachine();
    }

    private synchronized CreateStateMachineResult createStateMachineInternal(
            String name,
            String definition,
            String roleArn,
            String type,
            String region,
            Map<String, String> tags,
            JsonNode loggingConfiguration,
            JsonNode tracingConfiguration,
            JsonNode encryptionConfiguration,
            boolean publish,
            String versionDescription,
            String initialRevisionId,
            boolean allowIdempotentCreate) {
        validateStateMachineName(name);
        validateDefinition(definition);
        validateRoleArn(roleArn);
        validateStateMachineType(type);
        validateConfigurations(loggingConfiguration, tracingConfiguration, encryptionConfiguration);
        validateVersionDescription(publish, versionDescription);

        String effectiveType = type == null || type.isBlank() ? "STANDARD" : type;
        JsonNode effectiveLoggingConfiguration = loggingConfiguration != null
                ? copyNode(loggingConfiguration) : defaultLoggingConfiguration();
        JsonNode effectiveTracingConfiguration = tracingConfiguration != null
                ? copyNode(tracingConfiguration) : defaultTracingConfiguration();
        JsonNode effectiveEncryptionConfiguration = encryptionConfiguration != null
                ? copyNode(encryptionConfiguration) : defaultEncryptionConfiguration();
        String arn = regionResolver.buildArn("states", region, "stateMachine:" + name);
        Optional<StateMachine> existing = stateMachineStore.get(arn);
        if (existing.isPresent()) {
            StateMachine stateMachine = existing.get();
            if (allowIdempotentCreate
                    && isIdempotentCreateRequest(
                            stateMachine,
                            definition,
                            effectiveType,
                            effectiveLoggingConfiguration,
                            effectiveTracingConfiguration,
                            effectiveEncryptionConfiguration,
                            publish,
                            versionDescription)) {
                return new CreateStateMachineResult(
                        copyStateMachine(stateMachine),
                        creationVersion(stateMachine));
            }
            throw new AwsException(
                    "StateMachineAlreadyExists",
                    "State machine already exists: " + arn,
                    400);
        }

        StateMachine sm = new StateMachine();
        sm.setStateMachineArn(arn);
        sm.setName(name);
        sm.setDefinition(definition);
        sm.setRoleArn(roleArn);
        sm.setType(effectiveType);
        if (tags != null && !tags.isEmpty()) {
            sm.getTags().putAll(tags);
        }
        sm.setRevisionId(initialRevisionId != null
                ? initialRevisionId
                : UUID.randomUUID().toString());
        sm.setLoggingConfiguration(effectiveLoggingConfiguration);
        sm.setTracingConfiguration(effectiveTracingConfiguration);
        sm.setEncryptionConfiguration(effectiveEncryptionConfiguration);
        sm.setCreationVersionDescription(versionDescription);

        StateMachineVersion version = publish
                ? addVersion(sm, versionDescription)
                : null;
        if (version != null) {
            sm.setCreationVersionArn(version.getStateMachineVersionArn());
        }
        stateMachineStore.put(arn, sm);
        LOG.infov("Created State Machine: {0}", arn);
        return new CreateStateMachineResult(copyStateMachine(sm), version);
    }

    public record CreateStateMachineResult(
            StateMachine stateMachine,
            StateMachineVersion version) {
    }

    public record UpdateStateMachineRequest(
            String definition,
            String roleArn,
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

    public synchronized UpdateStateMachineResult updateStateMachine(
            String arn, UpdateStateMachineRequest request) {
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
                .orElseThrow(() -> new AwsException(
                        "StateMachineDoesNotExist", "State machine does not exist: " + arn, 400));
        StateMachine updated = copyStateMachine(current);

        if (request.definition() != null) {
            updated.setDefinition(request.definition());
        }
        if (request.roleArn() != null) {
            updated.setRoleArn(request.roleArn());
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
                ? addVersion(updated, request.versionDescription())
                : null;
        stateMachineStore.put(arn, updated);
        LOG.infov("Updated State Machine: {0}", arn);
        return new UpdateStateMachineResult(copyStateMachine(updated), version);
    }

    public StateMachine describeStateMachine(String arn) {
        validateDescribeStateMachineArn(arn);
        Optional<StateMachine> stateMachine = stateMachineStore.get(arn);
        if (stateMachine.isPresent()) {
            return copyStateMachine(stateMachine.get());
        }

        int versionSeparator = arn != null ? arn.lastIndexOf(':') : -1;
        if (versionSeparator > 0 && versionSeparator < arn.length() - 1) {
            String versionText = arn.substring(versionSeparator + 1);
            if (versionText.chars().allMatch(Character::isDigit)) {
                String baseArn = arn.substring(0, versionSeparator);
                Optional<StateMachine> baseStateMachine = stateMachineStore.get(baseArn);
                if (baseStateMachine.isPresent()) {
                    return baseStateMachine.get().getVersions().stream()
                            .filter(version -> arn.equals(version.getStateMachineVersionArn()))
                            .findFirst()
                            .map(version -> stateMachineFromVersion(baseStateMachine.get(), version))
                            .orElseThrow(() -> new AwsException(
                                    "StateMachineDoesNotExist",
                                    "State machine does not exist", 400));
                }
            }
        }
        throw new AwsException(
                "StateMachineDoesNotExist", "State machine does not exist", 400);
    }

    public List<StateMachine> listStateMachines(String region) {
        String prefix = "arn:aws:states:" + region + ":";
        return stateMachineStore.scan(k -> k.startsWith(prefix));
    }

    // ── State machine versions ──────────────────────────────────────────────

    public StateMachineVersion publishStateMachineVersion(String stateMachineArn) {
        return publishStateMachineVersion(stateMachineArn, null, null);
    }

    public synchronized StateMachineVersion publishStateMachineVersion(
            String stateMachineArn,
            String revisionId,
            String description) {
        validateStateMachineArn(stateMachineArn);
        validateVersionDescription(true, description);
        StateMachine current = stateMachineStore.get(stateMachineArn)
                .orElseThrow(() -> new AwsException(
                        "StateMachineDoesNotExist", "State machine does not exist", 400));
        if (revisionId != null && !Objects.equals(revisionId, current.getRevisionId())) {
            throw new AwsException(
                    "ConflictException",
                    "The state machine revision does not match revisionId " + revisionId,
                    409);
        }

        StateMachine updated = copyStateMachine(current);
        if (updated.getRevisionId() == null) {
            updated.setRevisionId(UUID.randomUUID().toString());
        }
        Optional<StateMachineVersion> existingVersion = updated.getVersions().stream()
                .filter(version -> Objects.equals(
                        updated.getRevisionId(), version.getRevisionId()))
                .findFirst();
        if (existingVersion.isPresent()) {
            return copyVersion(existingVersion.get());
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
        List<StateMachineVersion> versions = new ArrayList<>();
        sm.getVersions().forEach(version -> versions.add(copyVersion(version)));
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
            updated.getVersions().removeIf(
                    version -> stateMachineVersionArn.equals(
                            version.getStateMachineVersionArn()));
            stateMachineStore.put(baseArn, updated);
        });
    }

    public void deleteStateMachine(String arn) {
        stateMachineStore.delete(arn);
    }

    public synchronized boolean deleteStateMachineIfRevisionMatches(
            String arn, String expectedRevisionId) {
        Optional<StateMachine> existing = stateMachineStore.get(arn);
        if (existing.isEmpty()) {
            return false;
        }
        if (expectedRevisionId == null
                || !Objects.equals(expectedRevisionId, existing.get().getRevisionId())) {
            return false;
        }
        stateMachineStore.delete(arn);
        return true;
    }

    // ──────────────────────────── Executions ────────────────────────────

    public Execution startExecution(String stateMachineArn, String name, String input, String region) {
        var selection = splitTestCaseSuffix(stateMachineArn);
        var sm = describeStateMachine(selection.stateMachineArn());
        var mockedTestCase = resolveMockedTestCase(sm, selection);
        var execName = (name != null && !name.isBlank()) ? name : UUID.randomUUID().toString();
        var arn = regionResolver.buildArn("states", region, "execution:" + sm.getName() + ":" + execName);

        Execution exec;
        ExecutionHistory history;
        synchronized (executionStartLock) {
            Optional<Execution> existing = executionStore.get(arn);
            if (existing.isPresent()) {
                Execution prior = existing.get();
                // AWS idempotency (STANDARD only): the same name + same input while the original is still
                // RUNNING returns the original execution as a success; a different input, or a closed
                // execution with that name, is a conflict. EXPRESS workflows get no idempotency guarantee.
                if ("STANDARD".equals(sm.getType())
                        && "RUNNING".equals(prior.getStatus())
                        && Objects.equals(prior.getInput(), input)) {
                    return prior;
                }
                throw new AwsException("ExecutionAlreadyExists", "Execution already exists: " + arn, 400);
            }

            exec = new Execution();
            exec.setExecutionArn(arn);
            exec.setStateMachineArn(selection.stateMachineArn());
            exec.setName(execName);
            exec.setInput(input);
            exec.setStatus("RUNNING");
            executionStore.put(arn, exec);

            history = new ExecutionHistory();
            var startEvent = new HistoryEvent();
            startEvent.setId(1L);
            startEvent.setPreviousEventId(0L);
            startEvent.setType("ExecutionStarted");
            startEvent.setDetails(Map.of("input", input != null ? input : "{}",
                                         "roleArn", sm.getRoleArn() != null ? sm.getRoleArn() : "",
                                         "inputDetails", Map.of("truncated", false)));
            history.add(startEvent);
            historyCache.put(arn, history);
        }

        LOG.infov("Started execution: {0}", arn);

        // The executor appends to this same history, so the cache entry put above is already the
        // finished history by the time this runs.
        aslExecutor.executeAsync(sm, exec, history, mockedTestCase, (updatedExec, updatedHistory) -> {
            executionStore.put(updatedExec.getExecutionArn(), updatedExec);
            LOG.infov("Execution {0} completed with status {1}", updatedExec.getExecutionArn(), updatedExec.getStatus());
        });

        return exec;
    }

    public Execution startSyncExecution(String stateMachineArn, String name, String input, String region) {
        var selection = splitTestCaseSuffix(stateMachineArn);
        if (selection.testCaseName() != null) {
            // Matches Step Functions Local, which rejects a test case suffix here.
            throw new AwsException("UnsupportedOperation",
                    "Service integration mocking is not supported for the StartSyncExecution operation.",
                    400);
        }
        // A bare trailing '#' is not stripped on this operation: Step Functions Local looks up
        // the raw ARN and fails with StateMachineDoesNotExist, so Floci does the same.
        var sm = describeStateMachine(stateMachineArn);
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

        var exec = new Execution();
        exec.setExecutionArn(arn);
        exec.setStateMachineArn(stateMachineArn);
        exec.setName(execName);
        exec.setInput(input);
        exec.setStatus("RUNNING");

        var history = new ArrayList<HistoryEvent>();
        var startEvent = new HistoryEvent();
        startEvent.setId(1L);
        startEvent.setPreviousEventId(0L);
        startEvent.setType("ExecutionStarted");
        startEvent.setDetails(Map.of("input", input != null ? input : "{}",
                                     "roleArn", sm.getRoleArn() != null ? sm.getRoleArn() : "",
                                     "inputDetails", Map.of("truncated", false)));
        history.add(startEvent);

        aslExecutor.executeSync(sm, exec, history, (updatedExec, updatedHistory) -> {
            LOG.infov("Sync execution {0} completed with status {1}", updatedExec.getExecutionArn(), updatedExec.getStatus());
        });

        return exec;
    }

    private record TestCaseSelection(String stateMachineArn, String testCaseName) {
    }

    /**
     * Splits the Step Functions Local mocked service integration suffix off a
     * {@code StartExecution} ARN: {@code <stateMachineArn>#<testCaseName>} selects the named
     * test case from the configured mock configuration file. A bare trailing {@code #} selects
     * no test case and the execution runs unmocked, matching Step Functions Local.
     */
    private static TestCaseSelection splitTestCaseSuffix(String stateMachineArn) {
        var separator = stateMachineArn != null ? stateMachineArn.indexOf('#') : -1;
        if (separator < 0) {
            return new TestCaseSelection(stateMachineArn, null);
        }
        var testCaseName = stateMachineArn.substring(separator + 1);
        return new TestCaseSelection(stateMachineArn.substring(0, separator),
                testCaseName.isBlank() ? null : testCaseName);
    }

    private MockedTestCase resolveMockedTestCase(StateMachine sm, TestCaseSelection selection) {
        if (selection.testCaseName() == null) {
            return null;
        }
        var mockedTestCase = mockLoader.requireTestCase(sm.getName(), selection.testCaseName());
        warnOnMockedStatesMissingFromDefinition(sm, mockedTestCase);
        return mockedTestCase;
    }

    private void warnOnMockedStatesMissingFromDefinition(StateMachine sm, MockedTestCase testCase) {
        try {
            var stateNames = new HashSet<String>();
            collectStateNames(objectMapper.readTree(sm.getDefinition()), stateNames);
            for (var stateName : testCase.stateResponses().keySet()) {
                if (!stateNames.contains(stateName)) {
                    LOG.warnv("Mock test case {0} references state {1} which does not exist in state machine {2}",
                            testCase.testCaseName(), stateName, sm.getName());
                }
            }
        } catch (Exception e) {
            LOG.debugv("Could not check mocked state names for {0}: {1}", sm.getName(), e.getMessage());
        }
    }

    private static void collectStateNames(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            var states = node.get("States");
            if (states != null && states.isObject()) {
                states.fieldNames().forEachRemaining(names::add);
            }
            node.forEach(child -> collectStateNames(child, names));
        } else if (node.isArray()) {
            node.forEach(child -> collectStateNames(child, names));
        }
    }

    public Execution describeExecution(String arn) {
        return executionStore.get(arn)
                .orElseThrow(() -> new AwsException("ExecutionDoesNotExist", "Execution does not exist", 400));
    }

    public List<Execution> listExecutions(String stateMachineArn) {
        return executionStore.scan(k -> executionStore.get(k)
                .map(e -> e.getStateMachineArn().equals(stateMachineArn)).orElse(false));
    }

    /**
     * Aborts a running execution. The caller's {@code error} and {@code cause} land on the
     * Execution itself, not only on the history event, so DescribeExecution reports them.
     *
     * <p>The worker thread may still be inside a state when this runs. It shares this Execution
     * instance and reads the status it publishes here, so the writes are made under the same
     * monitor the worker's terminal write takes: ABORTED is the status that stands.
     *
     * <p>ExecutionAborted seals the history for the same reason: the worker still has the state it
     * is inside left to record, and those events belong to an execution the caller has already
     * been told is finished.
     */
    public void stopExecution(String arn, String cause, String error) {
        Execution exec = describeExecution(arn);
        synchronized (exec) {
            if (!"RUNNING".equals(exec.getStatus())) {
                return;
            }
            exec.setError(error);
            exec.setCause(cause);
            exec.setStopDate(System.currentTimeMillis() / 1000.0);
            exec.setStatus("ABORTED");
        }
        executionStore.put(arn, exec);

        Map<String, Object> details = new HashMap<>();
        if (error != null) details.put("error", error);
        if (cause != null) details.put("cause", cause);
        // An execution can outlive its history: the executions are stored, the histories are held in
        // memory only, so a restart in persistent mode brings a RUNNING execution back with nothing
        // behind it. The abort still gets recorded, against a history that starts here.
        historyCache.computeIfAbsent(arn, key -> new ExecutionHistory())
                .sealWith("ExecutionAborted", details);
    }

    public List<HistoryEvent> getExecutionHistory(String arn) {
        describeExecution(arn);
        ExecutionHistory history = historyCache.get(arn);
        return history != null ? history : Collections.emptyList();
    }

    /**
     * The history of one execution, appended to by the worker thread running the state machine and
     * by the thread serving StopExecution. Sealing it with the terminal event is what makes that
     * event the last one: the worker is still inside a state when the abort lands, and the events
     * it has left to record belong to an execution the caller has already been told is finished.
     *
     * <p>The seal is enforced in {@link #add}, the one way the worker appends. {@code addAll} does
     * not route through it, so a bulk append would write past the seal; nothing does one today.
     */
    static final class ExecutionHistory extends ArrayList<HistoryEvent> {

        private static final long serialVersionUID = 1L;

        private boolean sealed;

        @Override
        public synchronized boolean add(HistoryEvent event) {
            if (sealed) {
                return false;
            }
            return super.add(event);
        }

        /** Appends the terminal event, numbered from the end of the history, and takes no more. */
        synchronized void sealWith(String terminalEventType, Map<String, Object> details) {
            HistoryEvent terminalEvent = new HistoryEvent();
            terminalEvent.setId(size() + 1L);
            terminalEvent.setPreviousEventId((long) size());
            terminalEvent.setType(terminalEventType);
            terminalEvent.setDetails(details);
            add(terminalEvent);
            sealed = true;
        }
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
        taskHeartbeatNanos.put(token, System.nanoTime());
        return future;
    }

    /**
     * When the token last showed progress: the moment its task was scheduled, then every
     * SendTaskHeartbeat that named it. A token that is no longer pending reads as having just
     * reported, so a task whose result already arrived is never failed on a heartbeat gap.
     */
    public long lastTaskHeartbeatNanos(String taskToken) {
        Long reportedAt = taskToken != null ? taskHeartbeatNanos.get(taskToken) : null;
        return reportedAt != null ? reportedAt : System.nanoTime();
    }

    /**
     * Forgets a token the waiting task has stopped listening for, so a later SendTaskSuccess,
     * SendTaskFailure or SendTaskHeartbeat naming it reports no pending task.
     */
    public void discardPendingToken(String taskToken) {
        if (taskToken == null) {
            return;
        }
        pendingTaskTokens.remove(taskToken);
        taskHeartbeatNanos.remove(taskToken);
    }

    // ──────────────────────────── Tasks ────────────────────────────

    /**
     * @return whether the token named a task that was waiting for it. The
     *         {@code aws-sdk:sfn:sendTaskSuccess} Task integration fails the calling state when it
     *         did not, the way AWS answers an unknown token with {@code InvalidToken}.
     */
    public boolean sendTaskSuccess(String taskToken, String output) {
        CompletableFuture<JsonNode> future = taskToken != null ? pendingTaskTokens.remove(taskToken) : null;
        if (future == null) {
            LOG.warnv("SendTaskSuccess: no pending task for token {0}", taskToken);
            return false;
        }
        try {
            future.complete(objectMapper.readTree(output));
        } catch (Exception e) {
            future.completeExceptionally(new RuntimeException("Invalid JSON output: " + e.getMessage()));
        }
        return true;
    }

    /**
     * @return whether the token named a task that was waiting for it, as in
     *         {@link #sendTaskSuccess(String, String)}.
     */
    public boolean sendTaskFailure(String taskToken, String cause, String error) {
        CompletableFuture<JsonNode> future = taskToken != null ? pendingTaskTokens.remove(taskToken) : null;
        if (future == null) {
            LOG.warnv("SendTaskFailure: no pending task for token {0}", taskToken);
            return false;
        }
        future.completeExceptionally(new AslExecutor.FailStateException(error, cause));
        return true;
    }

    /**
     * Resets the gap a Task's {@code HeartbeatSeconds} allows. A heartbeat for a token nobody is
     * waiting for is logged and changes nothing, as in {@link #sendTaskSuccess(String, String)}.
     */
    public void sendTaskHeartbeat(String taskToken) {
        if (taskToken == null || !pendingTaskTokens.containsKey(taskToken)) {
            LOG.warnv("SendTaskHeartbeat: no pending task for token {0}", taskToken);
            return;
        }
        taskHeartbeatNanos.put(taskToken, System.nanoTime());
    }

    // ──────────────────────────── Map runs ────────────────────────────

    /**
     * Retains a finished Map run so {@code DescribeMapRun} can still report it once the execution
     * that opened it is over. Reconciliation state machines read those counters from a later state,
     * or from a later execution altogether, so the run has to outlive the Map that produced it.
     */
    public void recordMapRun(MapRun mapRun) {
        mapRunStore.put(mapRun.getMapRunArn(), mapRun);
    }

    public MapRun describeMapRun(String mapRunArn) {
        return mapRunStore.get(mapRunArn)
                .orElseThrow(() -> new AwsException("ResourceNotFound",
                        "Resource not found: '" + mapRunArn + "'", 400));
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

    public synchronized void replaceStateMachineTags(String arn, Map<String, String> tags) {
        StateMachine current = stateMachineStore.get(arn)
                .orElseThrow(() -> new AwsException(
                        "StateMachineDoesNotExist", "State machine does not exist: " + arn, 400));
        StateMachine updated = copyStateMachine(current);
        updated.setTags(new HashMap<>(tags != null ? tags : Map.of()));
        stateMachineStore.put(arn, updated);
    }

    private StateMachineVersion addVersion(StateMachine stateMachine, String description) {
        int next = stateMachine.getVersionCounter() + 1;
        stateMachine.setVersionCounter(next);
        StateMachineVersion version = new StateMachineVersion(
                stateMachine.getStateMachineArn() + ":" + next,
                next,
                System.currentTimeMillis() / 1000.0);
        version.setDescription(description);
        version.setName(stateMachine.getName());
        version.setDefinition(stateMachine.getDefinition());
        version.setRoleArn(stateMachine.getRoleArn());
        version.setType(stateMachine.getType());
        version.setStatus(stateMachine.getStatus());
        version.setRevisionId(stateMachine.getRevisionId());
        version.setLoggingConfiguration(copyNode(stateMachine.getLoggingConfiguration()));
        version.setTracingConfiguration(copyNode(stateMachine.getTracingConfiguration()));
        version.setEncryptionConfiguration(copyNode(stateMachine.getEncryptionConfiguration()));
        stateMachine.getVersions().add(version);
        return copyVersion(version);
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
        copy.setLoggingConfiguration(source.getLoggingConfiguration() != null
                ? copyNode(source.getLoggingConfiguration()) : defaultLoggingConfiguration());
        copy.setTracingConfiguration(source.getTracingConfiguration() != null
                ? copyNode(source.getTracingConfiguration()) : defaultTracingConfiguration());
        copy.setEncryptionConfiguration(source.getEncryptionConfiguration() != null
                ? copyNode(source.getEncryptionConfiguration()) : defaultEncryptionConfiguration());
        copy.setTags(new HashMap<>(source.getTags() != null ? source.getTags() : Map.of()));
        copy.setCreationVersionArn(source.getCreationVersionArn());
        copy.setCreationVersionDescription(source.getCreationVersionDescription());
        copy.setVersionCounter(source.getVersionCounter());
        List<StateMachineVersion> versions = new ArrayList<>();
        for (StateMachineVersion version :
                source.getVersions() != null ? source.getVersions() : List.<StateMachineVersion>of()) {
            versions.add(copyVersion(version));
        }
        copy.setVersions(versions);
        return copy;
    }

    private boolean isIdempotentCreateRequest(
            StateMachine existing,
            String definition,
            String type,
            JsonNode loggingConfiguration,
            JsonNode tracingConfiguration,
            JsonNode encryptionConfiguration,
            boolean publish,
            String versionDescription) {
        return Objects.equals(existing.getDefinition(), definition)
                && Objects.equals(existing.getType(), type)
                && Objects.equals(existing.getLoggingConfiguration(), loggingConfiguration)
                && Objects.equals(existing.getTracingConfiguration(), tracingConfiguration)
                && Objects.equals(existing.getEncryptionConfiguration(), encryptionConfiguration)
                && (existing.getCreationVersionArn() != null) == publish
                && Objects.equals(
                        existing.getCreationVersionDescription(),
                        versionDescription);
    }

    private StateMachineVersion creationVersion(StateMachine stateMachine) {
        if (stateMachine.getCreationVersionArn() == null) {
            return null;
        }
        return stateMachine.getVersions().stream()
                .filter(version -> Objects.equals(
                        stateMachine.getCreationVersionArn(),
                        version.getStateMachineVersionArn()))
                .findFirst()
                .map(this::copyVersion)
                .orElseGet(() -> new StateMachineVersion(
                        stateMachine.getCreationVersionArn(),
                        1,
                        stateMachine.getCreationDate()));
    }

    private StateMachine stateMachineFromVersion(
            StateMachine current, StateMachineVersion version) {
        StateMachine described = new StateMachine();
        described.setStateMachineArn(version.getStateMachineVersionArn());
        described.setName(version.getName() != null ? version.getName() : current.getName());
        described.setDefinition(version.getDefinition() != null
                ? version.getDefinition() : current.getDefinition());
        described.setRoleArn(version.getRoleArn() != null
                ? version.getRoleArn() : current.getRoleArn());
        described.setType(version.getType() != null ? version.getType() : current.getType());
        described.setStatus(version.getStatus() != null
                ? version.getStatus() : current.getStatus());
        described.setCreationDate(version.getCreationDate());
        described.setRevisionId(version.getRevisionId() != null
                ? version.getRevisionId() : current.getRevisionId());
        described.setDescription(version.getDescription());
        described.setLoggingConfiguration(version.getLoggingConfiguration() != null
                ? copyNode(version.getLoggingConfiguration())
                : copyNode(current.getLoggingConfiguration()));
        described.setTracingConfiguration(version.getTracingConfiguration() != null
                ? copyNode(version.getTracingConfiguration())
                : copyNode(current.getTracingConfiguration()));
        described.setEncryptionConfiguration(version.getEncryptionConfiguration() != null
                ? copyNode(version.getEncryptionConfiguration())
                : copyNode(current.getEncryptionConfiguration()));
        return described;
    }

    private StateMachineVersion copyVersion(StateMachineVersion source) {
        StateMachineVersion copy = new StateMachineVersion(
                source.getStateMachineVersionArn(),
                source.getVersion(),
                source.getCreationDate());
        copy.setDescription(source.getDescription());
        copy.setName(source.getName());
        copy.setDefinition(source.getDefinition());
        copy.setRoleArn(source.getRoleArn());
        copy.setType(source.getType());
        copy.setStatus(source.getStatus());
        copy.setRevisionId(source.getRevisionId());
        copy.setLoggingConfiguration(copyNode(source.getLoggingConfiguration()));
        copy.setTracingConfiguration(copyNode(source.getTracingConfiguration()));
        copy.setEncryptionConfiguration(copyNode(source.getEncryptionConfiguration()));
        return copy;
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
        try {
            return new VersionArn(
                    arn.substring(0, separator),
                    Integer.parseInt(suffix));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static JsonNode copyNode(JsonNode node) {
        return node == null ? null : node.deepCopy();
    }

    private JsonNode defaultLoggingConfiguration() {
        var configuration = objectMapper.createObjectNode();
        configuration.put("level", "OFF");
        configuration.put("includeExecutionData", false);
        configuration.putArray("destinations");
        return configuration;
    }

    private JsonNode defaultTracingConfiguration() {
        return objectMapper.createObjectNode().put("enabled", false);
    }

    private JsonNode defaultEncryptionConfiguration() {
        return objectMapper.createObjectNode().put("type", "AWS_OWNED_KEY");
    }

    // ──────────────────────────── Validation ────────────────────────────

    public record Diagnostic(String severity, String code, String message, String location) {}
    public record ValidationResult(boolean valid, List<Diagnostic> diagnostics, boolean truncated) {}

    private static final int MAX_DEFINITION_LENGTH = 1_048_576;
    private static final int MAX_ARN_LENGTH = 256;
    private static final int MAX_VERSION_DESCRIPTION_LENGTH = 256;
    private static final String INVALID_STATE_MACHINE_NAME_CHARACTERS =
            "<>[]{}?*\"#%\\^|~`$&,;:/";
    private static final Set<String> STATE_TYPES = Set.of(
            "Pass", "Task", "Choice", "Wait", "Succeed", "Fail", "Parallel", "Map");
    private static final String PARSE_ERROR_MARKER = "INVALID_JSON_DESCRIPTION:";
    private static final String UNSUPPORTED_JSONATA_MARKER = "UNSUPPORTED_JSONATA_EXPRESSION:";
    private static final String UNSUPPORTED_FIELD_MARKER = "UNSUPPORTED_FIELD:";
    private static final String MISSING_END_STATE_MARKER = "MISSING_END_STATE:";
    private static final String UNREACHABLE_STATE_MARKER = "UNREACHABLE_STATE:";
    // Payload is "<value><SOH><location>", shared by every marker that must carry structured data
    // (a state name, a JSONata parser message) past its flat-string marker without re-parsing free
    // text; SOH cannot appear in a state name or in JSONata parser output.
    private static final String DANGLING_TARGET_MARKER = "DANGLING_TARGET:";
    private static final char MARKER_PAYLOAD_SEPARATOR = '\u0001';
    private static final String INVALID_JSONATA_MARKER = "INVALID_JSONATA_EXPRESSION:";

    // Parse the structured location out of validator flat error strings,
    // which currently encode it as "...field 'X' ... at /States/Y".
    // AWS's published Diagnostic.location format is "/States/<StateName>/<FieldName>".
    private static final Pattern FIELD_PATTERN = Pattern.compile("field '([^']+)'");
    private static final Pattern LOCATION_SUFFIX_PATTERN = Pattern.compile(" at (/States/\\S+)$");
    // The JSONata errors already carry the full AWS location, state names with spaces included,
    // so their suffix is read with its own pattern rather than the whitespace-delimited one.
    private static final Pattern JSONATA_LOCATION_SUFFIX_PATTERN = Pattern.compile(" at (/States/.+)$");

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
        // null location when there's no specific location to point to — handler omits the
        // field from the response in that case, matching AWS's "optional" semantics.
        if (error.startsWith(PARSE_ERROR_MARKER)) {
            return new Diagnostic("ERROR", "INVALID_JSON_DESCRIPTION",
                    error.substring(PARSE_ERROR_MARKER.length()).trim(), null);
        }
        if (error.startsWith(UNSUPPORTED_JSONATA_MARKER)) {
            return toJsonataDiagnostic("UNSUPPORTED_JSONATA_EXPRESSION",
                    error.substring(UNSUPPORTED_JSONATA_MARKER.length()).trim());
        }
        if (error.startsWith(INVALID_JSONATA_MARKER)) {
            String payload = error.substring(INVALID_JSONATA_MARKER.length());
            int separator = payload.indexOf(MARKER_PAYLOAD_SEPARATOR);
            return new Diagnostic("ERROR", "INVALID_JSONATA_EXPRESSION",
                    payload.substring(0, separator), payload.substring(separator + 1));
        }
        if (error.startsWith(UNSUPPORTED_FIELD_MARKER)) {
            return toUnsupportedFieldDiagnostic(error.substring(UNSUPPORTED_FIELD_MARKER.length()));
        }
        if (error.equals(MISSING_END_STATE_MARKER)) {
            return new Diagnostic("ERROR", "MISSING_END_STATE", "Workflow has no terminal state", null);
        }
        if (error.startsWith(UNREACHABLE_STATE_MARKER)) {
            String payload = error.substring(UNREACHABLE_STATE_MARKER.length());
            int separator = payload.indexOf(MARKER_PAYLOAD_SEPARATOR);
            return new Diagnostic("ERROR", "MISSING_TRANSITION_TARGET",
                    "State \"" + payload.substring(0, separator) + "\" is not reachable.",
                    payload.substring(separator + 1));
        }
        if (error.startsWith(DANGLING_TARGET_MARKER)) {
            String payload = error.substring(DANGLING_TARGET_MARKER.length());
            int separator = payload.indexOf(MARKER_PAYLOAD_SEPARATOR);
            return new Diagnostic("ERROR", "MISSING_TRANSITION_TARGET",
                    "Missing 'Next' target: " + payload.substring(0, separator),
                    payload.substring(separator + 1));
        }
        String message = error;
        String location = null;
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
        return new Diagnostic("ERROR", "SCHEMA_VALIDATION_FAILED", message, location);
    }

    private static Diagnostic toJsonataDiagnostic(String code, String message) {
        Matcher locationMatcher = JSONATA_LOCATION_SUFFIX_PATTERN.matcher(message);
        if (!locationMatcher.find()) {
            return new Diagnostic("ERROR", code, message, null);
        }
        return new Diagnostic("ERROR", code,
                message.substring(0, locationMatcher.start()).trim(), locationMatcher.group(1));
    }

    // Payload is "<field name> <location>"; unlike the generic schema-error shape, AWS points this
    // diagnostic at the state itself rather than appending the field name to the location.
    private static Diagnostic toUnsupportedFieldDiagnostic(String payload) {
        int separator = payload.indexOf(' ');
        String field = payload.substring(0, separator);
        String location = payload.substring(separator + 1);
        return new Diagnostic("ERROR", "SCHEMA_VALIDATION_FAILED",
                "Field '" + field + "' is not supported", location);
    }

    private static void validateStateMachineName(String name) {
        boolean invalidCharacter = name != null && name.codePoints().anyMatch(codePoint ->
                Character.isWhitespace(codePoint)
                        || Character.isISOControl(codePoint)
                        || INVALID_STATE_MACHINE_NAME_CHARACTERS.indexOf(codePoint) >= 0);
        if (name == null || name.isBlank() || name.length() > 80 || invalidCharacter) {
            throw new AwsException(
                    "InvalidName",
                    "Invalid state machine name: '" + name + "'",
                    400);
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
                    ? resource.substring("stateMachine:".length())
                    : null;
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

    private static void validateDescribeStateMachineArn(String arn) {
        if (arn == null || arn.isBlank() || arn.length() > MAX_ARN_LENGTH) {
            throw new AwsException("InvalidArn", "Invalid Arn: '" + arn + "'", 400);
        }
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            String resource = parsed.resource();
            String name = resource != null && resource.startsWith("stateMachine:")
                    ? resource.substring("stateMachine:".length())
                    : null;
            if ("states".equals(parsed.service())
                    && name != null
                    && name.indexOf('/') >= 0) {
                throw new AwsException(
                        "ValidationException",
                        "DescribeStateMachine does not accept a qualified Distributed Map ARN.",
                        400);
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidArn", "Invalid Arn: '" + arn + "'", 400);
        }

        VersionArn versionArn = parseVersionArn(arn);
        validateStateMachineArn(
                versionArn != null ? versionArn.baseArn() : arn);
    }

    private static void validateUpdateStateMachineArn(String arn) {
        if (arn != null && arn.length() <= MAX_ARN_LENGTH) {
            try {
                AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
                String resource = parsed.resource();
                String name = resource != null && resource.startsWith("stateMachine:")
                        ? resource.substring("stateMachine:".length())
                        : null;
                int qualifierSeparator = name != null ? name.indexOf('/') : -1;
                if ("states".equals(parsed.service())
                        && !parsed.region().isBlank()
                        && !parsed.accountId().isBlank()
                        && qualifierSeparator > 0
                        && qualifierSeparator < name.length() - 1) {
                    throw new AwsException("ValidationException",
                            "UpdateStateMachine does not accept a qualified Distributed Map ARN.",
                            400);
                }
            } catch (IllegalArgumentException ignored) {
                // The general validator below maps malformed input to InvalidArn.
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

    private static void validateStateMachineType(String type) {
        if (type != null && !type.isBlank()
                && !"STANDARD".equals(type) && !"EXPRESS".equals(type)) {
            throw new AwsException("StateMachineTypeNotSupported",
                    "State machine type must be STANDARD or EXPRESS.", 400);
        }
    }

    private static void validateConfigurations(
            JsonNode loggingConfiguration,
            JsonNode tracingConfiguration,
            JsonNode encryptionConfiguration) {
        if (loggingConfiguration != null) {
            validateLoggingConfiguration(loggingConfiguration);
        }
        if (tracingConfiguration != null) {
            validateTracingConfiguration(tracingConfiguration);
        }
        if (encryptionConfiguration != null) {
            validateEncryptionConfiguration(encryptionConfiguration);
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
        JsonNode includeExecutionData = configuration.get("includeExecutionData");
        if (includeExecutionData != null && !includeExecutionData.isBoolean()) {
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
        JsonNode typeNode = configuration.get("type");
        String type = typeNode != null && typeNode.isTextual() ? typeNode.asText() : null;
        if (!"AWS_OWNED_KEY".equals(type) && !"CUSTOMER_MANAGED_KMS_KEY".equals(type)) {
            throw new AwsException("InvalidEncryptionConfiguration",
                    "encryptionConfiguration.type must be AWS_OWNED_KEY or CUSTOMER_MANAGED_KMS_KEY.", 400);
        }

        JsonNode keyId = configuration.get("kmsKeyId");
        if (keyId != null
                && (!keyId.isTextual() || keyId.asText().isBlank() || keyId.asText().length() > 2048)) {
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
                    "definition exceeds maximum length of "
                            + MAX_DEFINITION_LENGTH + " characters.", 400);
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
        // A JSONata error already carries its own AWS code and location, so it is reported on its
        // own rather than folded into the rest.
        List<String> nonJsonataErrors = errors.stream()
                .filter(error -> !error.startsWith(UNSUPPORTED_JSONATA_MARKER)
                        && !error.startsWith(INVALID_JSONATA_MARKER))
                .toList();
        if (nonJsonataErrors.isEmpty()) {
            throw new AwsException("InvalidDefinition",
                    "Invalid State Machine Definition: '" + flatten(toDiagnostic(first)) + "'", 400);
        }
        throw new AwsException("InvalidDefinition",
                "Invalid State Machine Definition: '"
                        + String.join(", ", nonJsonataErrors.stream()
                                .map(error -> flatten(toDiagnostic(error))).toList())
                        + "'", 400);
    }

    // Renders one diagnostic back to a flat string for the CreateStateMachine wire message:
    // "<code>: <message> at <location>". Unlike ValidateStateMachineDefinition, which omits
    // location entirely when there is none, AWS's CreateStateMachine always renders the suffix,
    // printing the literal "null" for a diagnostic with no location — measured against real AWS.
    private static String flatten(Diagnostic diagnostic) {
        return diagnostic.code() + ": " + diagnostic.message() + " at " + diagnostic.location();
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
                ? startAtNode.asText()
                : null;
        if (startAt == null || startAt.isBlank()) {
            errors.add("The field 'StartAt' is required and must be a non-empty string");
        }

        JsonNode states = def.get("States");
        if (states == null || !states.isObject() || states.isEmpty()) {
            errors.add("The field 'States' is required and must be a non-empty object");
            return errors;
        }

        String topLevelQL = def.path("QueryLanguage").asText("JSONPath");
        boolean topLevelJsonata = "JSONata".equals(topLevelQL);

        Set<String> topLevelStateNames = new HashSet<>();
        states.fieldNames().forEachRemaining(topLevelStateNames::add);

        var fields = states.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            validateState("/States/" + entry.getKey(), entry.getValue(), topLevelJsonata,
                    topLevelStateNames, errors);
        }

        // MISSING_END_STATE is a top-level-only rule, and it suppresses the reachability walk at
        // that same level: a workflow with no terminal state anywhere is refused outright, with no
        // "not reachable" diagnostics piled on top of it.
        if (hasTerminalState(states)) {
            validateReachability("/States", states, startAt, "/StartAt", errors);
        } else {
            errors.add(MISSING_END_STATE_MARKER);
        }
        return errors;
    }

    private static boolean hasTerminalState(JsonNode states) {
        for (JsonNode state : states) {
            if (!state.isObject()) {
                continue;
            }
            String type = state.path("Type").asText(null);
            if ("Succeed".equals(type) || "Fail".equals(type) || state.path("End").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the transition graph of one container of states (the top-level {@code States}, a
     * Map's {@code ItemProcessor}, or a Parallel branch) from its {@code StartAt}, flagging every
     * state nothing routes to. A {@code StartAt} that does not resolve to a state in the same
     * container is itself a dangling transition target, reported at {@code startAtLocation}; the
     * walk then starts from nothing, so every state in the container comes back unreachable too.
     */
    private static void validateReachability(String containerPath, JsonNode states, String startAt,
                                              String startAtLocation, List<String> errors) {
        Set<String> reachable = new HashSet<>();
        if (startAt != null && !startAt.isBlank() && states.has(startAt)) {
            Deque<String> pending = new ArrayDeque<>();
            reachable.add(startAt);
            pending.add(startAt);
            while (!pending.isEmpty()) {
                for (String target : transitionTargets(states.path(pending.poll()))) {
                    if (states.has(target) && reachable.add(target)) {
                        pending.add(target);
                    }
                }
            }
        } else if (startAt != null && !startAt.isBlank()) {
            errors.add(DANGLING_TARGET_MARKER + startAt + MARKER_PAYLOAD_SEPARATOR + startAtLocation);
        }
        states.fieldNames().forEachRemaining(name -> {
            if (!reachable.contains(name)) {
                errors.add(UNREACHABLE_STATE_MARKER + name + MARKER_PAYLOAD_SEPARATOR + containerPath + "/" + name);
            }
        });
    }

    private static List<String> transitionTargets(JsonNode state) {
        List<String> targets = new ArrayList<>();
        addIfTextual(state, "Next", targets);
        addIfTextual(state, "Default", targets);
        for (JsonNode choice : state.path("Choices")) {
            addIfTextual(choice, "Next", targets);
        }
        for (JsonNode catcher : state.path("Catch")) {
            addIfTextual(catcher, "Next", targets);
        }
        return targets;
    }

    private static void addIfTextual(JsonNode node, String field, List<String> targets) {
        if (node.path(field).isTextual()) {
            targets.add(node.path(field).asText());
        }
    }

    /**
     * Flags every {@code Next}, {@code Default} and {@code Catch[].Next} in one state that names a
     * state absent from its own container. Independent of {@link #validateReachability}: a
     * dangling target is invalid on its own, whether or not MISSING_END_STATE suppressed the
     * unreachable-state walk for this container.
     */
    private static void validateTransitionTargets(String statePath, JsonNode stateDef,
                                                   Set<String> siblingStateNames, List<String> errors) {
        validateTarget(stateDef, "Next", statePath + "/Next", siblingStateNames, errors);
        validateTarget(stateDef, "Default", statePath + "/Default", siblingStateNames, errors);
        JsonNode choices = stateDef.path("Choices");
        for (int i = 0; i < choices.size(); i++) {
            validateTarget(choices.path(i), "Next", statePath + "/Choices[" + i + "]/Next",
                    siblingStateNames, errors);
        }
        JsonNode catches = stateDef.path("Catch");
        for (int i = 0; i < catches.size(); i++) {
            validateTarget(catches.path(i), "Next", statePath + "/Catch[" + i + "]/Next",
                    siblingStateNames, errors);
        }
    }

    private static void validateTarget(JsonNode node, String field, String location,
                                       Set<String> siblingStateNames, List<String> errors) {
        JsonNode value = node.path(field);
        if (value.isTextual() && !siblingStateNames.contains(value.asText())) {
            errors.add(DANGLING_TARGET_MARKER + value.asText() + MARKER_PAYLOAD_SEPARATOR + location);
        }
    }

    private static void validateFieldsAllowedForType(String statePath, JsonNode stateDef,
                                                      String stateType, List<String> errors) {
        for (FieldStateTypeRule rule : FIELDS_ALLOWED_STATE_TYPES) {
            if (stateDef.has(rule.field()) && !rule.allowedTypes().contains(stateType)) {
                errors.add(UNSUPPORTED_FIELD_MARKER + rule.field() + " " + statePath);
            }
        }
    }

    private void validateState(String statePath, JsonNode stateDef, boolean topLevelJsonata,
                               Set<String> siblingStateNames, List<String> errors) {
        if (!stateDef.isObject()) {
            errors.add("State must be an object at " + statePath);
            return;
        }
        String stateType = stateDef.path("Type").asText(null);
        if (stateType == null || !STATE_TYPES.contains(stateType)) {
            errors.add("State must declare a valid field 'Type' at " + statePath);
            return;
        }
        boolean terminalType = "Succeed".equals(stateType) || "Fail".equals(stateType);
        boolean choiceType = "Choice".equals(stateType);
        boolean hasTerminalTransition = stateDef.path("End").asBoolean(false)
                || stateDef.path("Next").isTextual();
        if (!terminalType && !choiceType && !hasTerminalTransition) {
            errors.add("State must declare either 'Next' or 'End' at " + statePath);
        }
        if (choiceType
                && (!stateDef.path("Choices").isArray()
                || stateDef.path("Choices").isEmpty())) {
            errors.add("Choice state must declare a non-empty field 'Choices' at " + statePath);
        }
        String stateQL = stateDef.path("QueryLanguage").asText(null);
        boolean stateIsJsonata = stateQL != null ? "JSONata".equals(stateQL) : topLevelJsonata;

        validateFieldsAllowedForType(statePath, stateDef, stateType, errors);
        validateTransitionTargets(statePath, stateDef, siblingStateNames, errors);

        // JSONPath-only fields are not allowed when the state uses JSONata
        if (stateIsJsonata) {
            for (String field : JSONPATH_ONLY_FIELDS) {
                if (stateDef.has(field)) {
                    errors.add("The QueryLanguage is set to 'JSONata', but field '" + field
                            + "' is only supported for the 'JSONPath' QueryLanguage at " + statePath);
                }
            }
            collectTopLevelReferences(statePath, stateDef, errors);
        }

        // Structurally validate JSONPath Choice rules (comparator allowlist, exactly-one operator,
        // per-family operand types, And/Or/Not shapes, Next placement). JSONata Choice uses a
        // Condition string and is validated elsewhere.
        if (choiceType && !stateIsJsonata && stateDef.path("Choices").isArray()) {
            JsonNode choices = stateDef.path("Choices");
            for (int i = 0; i < choices.size(); i++) {
                ChoiceOperators.validateChoiceRule(statePath + "/Choices/" + i, choices.get(i), true, errors);
            }
        }

        if ("Map".equals(stateType)) {
            validateMapConcurrency(statePath, stateDef, stateIsJsonata, errors);
            if (stateDef.has("ItemReader")) {
                validateItemReader(statePath, stateDef, errors);
            }
            if (stateDef.has("ResultWriter")) {
                validateResultWriter(statePath, stateDef.get("ResultWriter"), stateIsJsonata, errors);
            }
            String processorField = stateDef.has("ItemProcessor") ? "ItemProcessor" : "Iterator";
            String processorPath = statePath + "/" + processorField;
            JsonNode processor = stateDef.path(processorField);
            validateNestedStates(processor.path("States"), processorPath + "/States", processorPath + "/StartAt",
                    processor.path("StartAt").asText(null), topLevelJsonata, errors);
        } else if ("Parallel".equals(stateType)) {
            JsonNode branches = stateDef.path("Branches");
            if (branches.isArray()) {
                for (int i = 0; i < branches.size(); i++) {
                    JsonNode branch = branches.path(i);
                    String branchPath = statePath + "/Branches[" + i + "]";
                    validateNestedStates(branch.path("States"), branchPath + "/States", branchPath + "/StartAt",
                            branch.path("StartAt").asText(null), topLevelJsonata, errors);
                }
            }
        }
    }

    /**
     * Reports every JSONata expression in the state that does not parse, or that reads the
     * top-level context, or that reads {@code $states.errorOutput} outside the one place it
     * exists: all three are refused by real AWS at CreateStateMachine time. Only the fields AWS
     * parses as JSONata are walked: measured against {@code validate-state-machine-definition}, a
     * {@code {% %}} string in Comment, Next, Default, Resource, ErrorEquals, Retry, Credentials or
     * ReaderConfig.CSVHeaders is left alone, while Output, Assign, Arguments, Items, Seconds,
     * Condition, ItemBatcher, ItemReader, ItemSelector, ResultWriter and the rest are parsed and
     * reported.
     *
     * <p>Those names are ASL fields, not payload keys. AWS parses a payload whole, so
     * {@code Assign: {"Next": "{% phone %}"}} and {@code Arguments: {"Payload": {"Comment":
     * "{% phone %}"}}} are both refused by name, and the deny list stops applying as soon as the
     * walk enters one of {@link #JSONATA_PAYLOAD_FIELDS}.
     */
    private static void collectTopLevelReferences(String path, JsonNode node, List<String> errors) {
        collectTopLevelReferences(path, node, JsonataScope.STATE_ROOT, errors);
    }

    private static void collectTopLevelReferences(String path, JsonNode node, JsonataScope scope,
                                                  List<String> errors) {
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> {
                if (scope.parsesAsJsonata(field.getKey())) {
                    collectTopLevelReferences(path + "/" + field.getKey(), field.getValue(),
                            scope.enter(field.getKey()), errors);
                }
            });
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectTopLevelReferences(path + "[" + index + "]", node.get(index), scope, errors);
            }
            return;
        }
        if (!node.isTextual() || !JsonataEvaluator.isExpression(node.asText())) {
            return;
        }
        JsonataTopLevelReferences.Analysis analysis =
                JsonataTopLevelReferences.analyze(JsonataEvaluator.unwrap(node.asText()));
        if (analysis.parseError() != null) {
            errors.add(INVALID_JSONATA_MARKER + analysis.parseError() + MARKER_PAYLOAD_SEPARATOR + path);
            return;
        }
        for (String reference : analysis.topLevelReferences()) {
            addUnsupportedReferenceError(path, reference, scope, errors);
        }
    }

    /**
     * The three things the walk carries down from the ASL fields it has already entered, each of
     * which changes how a {@code {% %}} string below is read: inside a payload every key is a
     * payload key, so {@link #ASL_FIELDS_AWS_DOES_NOT_PARSE_AS_JSONATA} no longer applies;
     * {@code atCatchEntry} marks a catcher object, whose own {@code Output} and {@code Assign} are
     * the one place {@code $states.errorOutput} resolves; and {@code insideCatchOutputOrAssign}
     * says the walk is in one of those two, at any depth.
     */
    private record JsonataScope(boolean insidePayload, boolean atCatchEntry,
                                boolean insideCatchOutputOrAssign) {

        private static final JsonataScope STATE_ROOT = new JsonataScope(false, false, false);

        private boolean parsesAsJsonata(String field) {
            return insidePayload || !ASL_FIELDS_AWS_DOES_NOT_PARSE_AS_JSONATA.contains(field);
        }

        private JsonataScope enter(String field) {
            if (insidePayload) {
                return this;
            }
            return new JsonataScope(
                    JSONATA_PAYLOAD_FIELDS.contains(field),
                    "Catch".equals(field),
                    insideCatchOutputOrAssign
                            || (atCatchEntry && ("Output".equals(field) || "Assign".equals(field))));
        }
    }

    private static void addUnsupportedReferenceError(String path, String reference,
                                                     JsonataScope scope, List<String> errors) {
        if (JsonataTopLevelReferences.STATES_ERROR_OUTPUT.equals(reference)) {
            if (!scope.insideCatchOutputOrAssign()) {
                errors.add(UNSUPPORTED_JSONATA_MARKER
                        + " Field '$states.errorOutput' does not exist. at " + path);
            }
            return;
        }
        if (JsonataTopLevelReferences.ROOT_REFERENCE.equals(reference)) {
            errors.add(UNSUPPORTED_JSONATA_MARKER + " Reference to '$$' is not supported. at " + path);
            return;
        }
        errors.add(UNSUPPORTED_JSONATA_MARKER + " Reference to '" + reference
                + "' at the top level is not supported. at " + path);
    }

    private void validateNestedStates(JsonNode states, String statesPath, String startAtLocation,
                                      String startAt, boolean inheritedJsonata, List<String> errors) {
        if (!states.isObject()) {
            return;
        }
        Set<String> stateNames = new HashSet<>();
        states.fieldNames().forEachRemaining(stateNames::add);
        states.fields().forEachRemaining(entry -> validateState(
                statesPath + "/" + entry.getKey(), entry.getValue(), inheritedJsonata, stateNames, errors));
        // Unlike the top level, MISSING_END_STATE does not apply here: ItemProcessor and Branches
        // are always walked for reachability regardless of whether they have a terminal state.
        validateReachability(statesPath, states, startAt, startAtLocation, errors);
    }

    private void validateMapConcurrency(String statePath, JsonNode stateDef,
                                        boolean jsonata, List<String> errors) {
        if (stateDef.has("MaxConcurrency") && stateDef.has("MaxConcurrencyPath")) {
            errors.add("A Map state cannot include both field 'MaxConcurrency' and MaxConcurrencyPath"
                    + " at " + statePath);
        }

        if (stateDef.has("MaxConcurrency")) {
            JsonNode value = stateDef.get("MaxConcurrency");
            boolean expression = jsonata && value.isTextual()
                    && JsonataEvaluator.isExpression(value.asText());
            if (!value.isIntegralNumber() && !expression) {
                errors.add("The field 'MaxConcurrency' must be a non-negative integer"
                        + (jsonata ? " or a JSONata expression" : "")
                        + " at " + statePath);
            } else if (value.isIntegralNumber() && value.bigIntegerValue().signum() < 0) {
                errors.add("The field 'MaxConcurrency' must be a non-negative integer"
                        + " at " + statePath);
            }
        }

        if (!jsonata && stateDef.has("MaxConcurrencyPath")) {
            JsonNode path = stateDef.get("MaxConcurrencyPath");
            String pathValue = path.isTextual() ? path.asText() : null;
            if (!isReferencePath(pathValue)) {
                errors.add("The field 'MaxConcurrencyPath' must be a JSONPath reference path"
                        + " at " + statePath);
            }
        }
    }

    private static boolean isReferencePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '$') {
            return false;
        }
        int index = 1;
        while (index < path.length()) {
            if (path.charAt(index) == '.') {
                index++;
                if (index < path.length() && path.charAt(index) == '[') {
                    continue;
                }
                if (index >= path.length()
                        || !(Character.isLetter(path.charAt(index)) || path.charAt(index) == '_')) {
                    return false;
                }
                index++;
                while (index < path.length()
                        && (Character.isLetterOrDigit(path.charAt(index)) || path.charAt(index) == '_')) {
                    index++;
                }
                continue;
            }
            if (path.charAt(index) != '[') {
                return false;
            }
            index++;
            if (index >= path.length()) {
                return false;
            }
            char first = path.charAt(index);
            if (Character.isDigit(first)) {
                while (index < path.length() && Character.isDigit(path.charAt(index))) {
                    index++;
                }
            } else if (first == '\'' || first == '"') {
                char quote = first;
                index++;
                boolean hasCharacter = false;
                boolean closed = false;
                while (index < path.length()) {
                    char current = path.charAt(index++);
                    if (current == '\\' && index < path.length()) {
                        index++;
                        hasCharacter = true;
                    } else if (current == quote) {
                        closed = true;
                        break;
                    } else {
                        hasCharacter = true;
                    }
                }
                if (!closed || !hasCharacter) {
                    return false;
                }
            } else {
                return false;
            }
            if (index >= path.length() || path.charAt(index) != ']') {
                return false;
            }
            index++;
        }
        return true;
    }

    private void validateItemReader(String statePath, JsonNode stateDef, List<String> errors) {
        JsonNode itemReader = stateDef.get("ItemReader");
        String resource = itemReader.path("Resource").asText(null);
        if (resource != null && !ITEM_READER_RESOURCES.contains(resource)) {
            errors.add("The field 'Resource' does not match any of the allowed values. Examples: "
                    + "[arn:<partition>:states:::s3:getObject, arn:<partition>:states:::s3:listObjectsV2]"
                    + " at " + statePath + "/ItemReader/Resource");
        }

        String inputType = itemReader.path("ReaderConfig").path("InputType").asText(null);
        if (inputType != null && !ITEM_READER_INPUT_TYPES.contains(inputType)) {
            errors.add("The field 'InputType' should have one of these values: "
                    + "[MANIFEST, JSON, CSV, JSONL, PARQUET]"
                    + " at " + statePath + "/ItemReader/ReaderConfig/InputType");
        }
    }

    private void validateResultWriter(String statePath, JsonNode writer,
                                      boolean jsonata, List<String> errors) {
        String writerPath = statePath + "/ResultWriter";
        if (!writer.isObject() || writer.isEmpty()) {
            errors.add("The field 'ResultWriter' must specify WriterConfig or Resource with "
                    + (jsonata ? "Arguments" : "Parameters") + " at " + statePath);
            return;
        }

        String destinationField = jsonata ? "Arguments" : "Parameters";
        String unsupportedDestinationField = jsonata ? "Parameters" : "Arguments";
        if (writer.has(unsupportedDestinationField)) {
            errors.add("The field '" + unsupportedDestinationField + "' is not supported for the '"
                    + (jsonata ? "JSONata" : "JSONPath") + "' QueryLanguage at "
                    + writerPath + "/" + unsupportedDestinationField);
        }
        boolean hasConfig = writer.has("WriterConfig") && writer.get("WriterConfig").isObject();
        boolean hasResource = writer.has("Resource");
        boolean destinationIsExpression = jsonata
                && writer.path(destinationField).isTextual()
                && JsonataEvaluator.isExpression(writer.path(destinationField).asText());
        boolean hasDestination = writer.has(destinationField)
                && (writer.get(destinationField).isObject() || destinationIsExpression);

        if (writer.has("WriterConfig") && !hasConfig) {
            errors.add("The field 'WriterConfig' must be an object at " + writerPath);
        }
        if (writer.has(destinationField) && !hasDestination) {
            errors.add("The field '" + destinationField + "' must be an object"
                    + (jsonata ? " or a JSONata expression" : "") + " at " + writerPath);
        }
        if ((!hasConfig && !hasResource && !hasDestination) || hasResource != hasDestination) {
            errors.add("The field 'ResultWriter' must specify WriterConfig alone, Resource with "
                    + destinationField + ", or all three fields at " + statePath);
        }

        if (hasResource && (!writer.get("Resource").isTextual()
                || !RESULT_WRITER_RESOURCE.equals(writer.get("Resource").asText()))) {
            errors.add("The field 'Resource' does not match the allowed value "
                    + RESULT_WRITER_RESOURCE + " at " + writerPath);
        }

        if (hasDestination && !destinationIsExpression) {
            JsonNode destination = writer.get(destinationField);
            boolean hasBucket = destination.hasNonNull("Bucket")
                    || (!jsonata && destination.hasNonNull("Bucket.$"));
            if (!hasBucket) {
                errors.add("The field 'Bucket' is required at " + writerPath + "/" + destinationField);
            } else if (destination.has("Bucket") && !destination.get("Bucket").isTextual()) {
                errors.add("The field 'Bucket' must be a string at "
                        + writerPath + "/" + destinationField + "/Bucket");
            } else if (!jsonata && destination.has("Bucket.$")
                    && !destination.get("Bucket.$").isTextual()) {
                errors.add("The field 'Bucket.$' must be a string at "
                        + writerPath + "/" + destinationField + "/Bucket.$");
            }
            if (destination.has("Prefix") && !destination.get("Prefix").isTextual()) {
                errors.add("The field 'Prefix' must be a string at "
                        + writerPath + "/" + destinationField + "/Prefix");
            }
            if (!jsonata && destination.has("Prefix.$")
                    && !destination.get("Prefix.$").isTextual()) {
                errors.add("The field 'Prefix.$' must be a string at "
                        + writerPath + "/" + destinationField + "/Prefix.$");
            }
        }

        if (hasConfig) {
            JsonNode config = writer.get("WriterConfig");
            JsonNode transformationNode = config.get("Transformation");
            JsonNode outputTypeNode = config.get("OutputType");
            if (transformationNode == null || !transformationNode.isTextual()
                    || outputTypeNode == null || !outputTypeNode.isTextual()) {
                errors.add("The field 'WriterConfig' must specify string Transformation and OutputType at "
                        + writerPath + "/WriterConfig");
            } else {
                String transformation = transformationNode.asText();
                if (!RESULT_WRITER_TRANSFORMATIONS.contains(transformation)) {
                    errors.add("The field 'Transformation' should have one of these values: "
                            + RESULT_WRITER_TRANSFORMATIONS + " at " + writerPath + "/WriterConfig");
                }
                String outputType = outputTypeNode.asText();
                if (!RESULT_WRITER_OUTPUT_TYPES.contains(outputType)) {
                    errors.add("The field 'OutputType' should have one of these values: "
                            + RESULT_WRITER_OUTPUT_TYPES + " at " + writerPath + "/WriterConfig");
                }
            }
        }
    }
}
