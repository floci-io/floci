package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class StepFunctionsService {

    private static final Logger LOG = Logger.getLogger(StepFunctionsService.class);

    private final StorageBackend<String, StateMachine> stateMachineStore;
    private final StorageBackend<String, Execution> executionStore;
    private final Map<String, List<HistoryEvent>> historyCache = new ConcurrentHashMap<>();
    private final RegionResolver regionResolver;
    private final AslExecutor aslExecutor;
    private final ObjectMapper objectMapper;

    private static final Set<String> JSONPATH_ONLY_FIELDS = Set.of(
            "InputPath", "OutputPath", "ResultPath", "ResultSelector", "Parameters", "Result", "ItemsPath");

    @Inject
    public StepFunctionsService(StorageFactory storageFactory, RegionResolver regionResolver,
                                AslExecutor aslExecutor, ObjectMapper objectMapper) {
        this.stateMachineStore = storageFactory.create("stepfunctions", "sfn-state-machines.json",
                new TypeReference<Map<String, StateMachine>>() {});
        this.executionStore = storageFactory.create("stepfunctions", "sfn-executions.json",
                new TypeReference<Map<String, Execution>>() {});
        this.regionResolver = regionResolver;
        this.aslExecutor = aslExecutor;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── State Machines ────────────────────────────

    public StateMachine createStateMachine(String name, String definition, String roleArn, String type, String region) {
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
        String arn = regionResolver.buildArn("states", region, "express:" + sm.getName() + ":" + execName);

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

    // ──────────────────────────── Tasks ────────────────────────────

    public void sendTaskSuccess(String taskToken, String output) {
        LOG.infov("Task success received for token {0}", taskToken);
    }

    public void sendTaskFailure(String taskToken, String cause, String error) {
        LOG.infov("Task failure received for token {0}: {1}", taskToken, error);
    }

    public void sendTaskHeartbeat(String taskToken) {
        LOG.debugv("Task heartbeat for token {0}", taskToken);
    }

    // ──────────────────────────── Validation ────────────────────────────

    private void validateDefinition(String definition) {
        JsonNode def;
        try {
            def = objectMapper.readTree(definition);
        } catch (Exception e) {
            throw new AwsException("InvalidDefinition",
                    "Invalid State Machine Definition: '" + e.getMessage() + "'", 400);
        }

        String topLevelQL = def.path("QueryLanguage").asText("JSONPath");
        boolean topLevelJsonata = "JSONata".equals(topLevelQL);
        JsonNode states = def.path("States");

        if (states.isObject()) {
            var fields = states.fields();
            List<String> errors = new ArrayList<>();
            while (fields.hasNext()) {
                var entry = fields.next();
                validateState(entry.getKey(), entry.getValue(), topLevelJsonata, errors);
            }
            if (!errors.isEmpty()) {
                throw new AwsException("InvalidDefinition",
                        "Invalid State Machine Definition: 'SCHEMA_VALIDATION_FAILED: "
                                + String.join(", ", errors) + "'", 400);
            }
        }
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
