package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.RequestShapeModel;
import io.github.hectorvent.floci.services.emrserverless.EmrServerlessController;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.StartJobRunRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static io.github.hectorvent.floci.services.stepfunctions.OptimizedIntegrations.failed;
import static io.github.hectorvent.floci.services.stepfunctions.OptimizedIntegrations.refused;
import static io.github.hectorvent.floci.services.stepfunctions.OptimizedIntegrations.requiredText;
import static io.github.hectorvent.floci.services.stepfunctions.OptimizedIntegrations.unsupported;

/**
 * The optimized {@code arn:<partition>:states:::emr-serverless:*} integrations, the six APIs the Step
 * Functions guide's "EMR Serverless service integration APIs" table lists, each with {@code .sync}, run
 * through {@link EmrServerlessController} in the state machine's Region. A Task names the API's
 * members in PascalCase where the API writes them in camelCase, so a request and a response are
 * walked against the vendored model: a structure's members are recased, a map's keys (tags,
 * {@code initialCapacity}, a configuration's {@code properties}) are kept. A member, the ids included,
 * may be written in either case. A {@code .sync} Task calls
 * {@link #start}, then {@link #poll} until its application or job run is done; {@link AslExecutor}
 * owns the wait between polls.
 */
@ApplicationScoped
public class EmrServerlessTaskInvoker {

    private static final Logger LOG = Logger.getLogger(EmrServerlessTaskInvoker.class);

    private static final RequestShapeModel SHAPES =
            RequestShapeModel.load("aws/emr-serverless-shapes.json", "EMR Serverless shapes");
    // The exception prefix the Step Functions guide gives EMR Serverless, for its AWS SDK integration.
    // No record shows the optimized integration's, and EMR's differs between the two (EMR. and Emr.).
    private static final String ERROR_PREFIX = "EmrServerless.";
    // What EMR Serverless answers an undo when the application or run is gone, or past the state it undoes.
    private static final Set<String> NOTHING_TO_UNDO =
            Set.of(ERROR_PREFIX + "ResourceNotFoundException", ERROR_PREFIX + "ValidationException");
    // What each .sync waits for: the state the guide names, and the states it can no longer reach it
    // from. A created application may already be starting when it is read.
    private static final Map<String, Wait> WAITS = Map.of(
            "createApplication", new Wait(Set.of("CREATED", "STARTING", "STARTED", "STOPPING", "STOPPED"),
                    Set.of("TERMINATED")),
            "startApplication", new Wait(Set.of("STARTED"), Set.of("STOPPING", "STOPPED", "TERMINATED")),
            "stopApplication", new Wait(Set.of("STOPPED"), Set.of("STARTING", "STARTED", "TERMINATED")),
            "deleteApplication", new Wait(Set.of("TERMINATED"), Set.of()),
            "startJobRun", new Wait(Set.of("SUCCESS"), Set.of("FAILED", "CANCELLED")),
            "cancelJobRun", new Wait(Set.of("CANCELLED"), Set.of("SUCCESS", "FAILED")));

    private final EmrServerlessController emrServerless;
    private final ObjectMapper objectMapper;

    @Inject
    public EmrServerlessTaskInvoker(EmrServerlessController emrServerless, ObjectMapper objectMapper) {
        this.emrServerless = emrServerless;
        this.objectMapper = objectMapper;
    }

    /**
     * A started call: the integration's response, and the application, or job run, a {@code .sync}
     * Task waits on.
     */
    record Job(String api, String applicationId, String jobRunId, ObjectNode response) {}

    private record Wait(Set<String> done, Set<String> deadEnds) {}

    Job start(StatesIntegration integration, JsonNode input, String region) {
        String api = integration.api();
        if (!WAITS.containsKey(api) || !integration.suffix().isEmpty() && !".sync".equals(integration.suffix())) {
            throw unsupported(integration);
        }
        JsonNode request = input != null && input.isObject() ? input : objectMapper.createObjectNode();
        return switch (api) {
            case "createApplication" -> createApplication(request, region);
            case "startApplication" -> {
                String applicationId = requiredId(request, "ApplicationId");
                call(region, () -> emrServerless.startApplication(applicationId));
                yield applicationJob(api, applicationId);
            }
            case "stopApplication" -> {
                String applicationId = requiredId(request, "ApplicationId");
                call(region, () -> emrServerless.stopApplication(applicationId));
                yield applicationJob(api, applicationId);
            }
            case "deleteApplication" -> {
                String applicationId = requiredId(request, "ApplicationId");
                call(region, () -> emrServerless.deleteApplication(applicationId));
                yield applicationJob(api, applicationId);
            }
            case "startJobRun" -> startJobRun(request, region);
            case "cancelJobRun" -> {
                String applicationId = requiredId(request, "ApplicationId");
                String jobRunId = requiredId(request, "JobRunId");
                ObjectNode response = toStates(call(region, () -> emrServerless.cancelJobRun(applicationId, jobRunId)),
                        "CancelJobRunResponse");
                yield new Job(api, applicationId, jobRunId, response);
            }
            default -> throw unsupported(integration);
        };
    }

    /**
     * The Task's result once the job is done, empty while it runs. The guide finds no difference
     * between a {@code .sync} response and the API's, so the result is the starting call's own:
     * state machines read a {@code createApplication.sync} result's {@code ApplicationId} and a
     * {@code startJobRun.sync} result's {@code JobRunId}.
     */
    Optional<JsonNode> poll(Job job, String region) {
        JsonNode described;
        String state;
        if (job.jobRunId() != null) {
            described = toStates(call(region, () -> emrServerless.getJobRun(job.applicationId(), job.jobRunId(), null)),
                    "GetJobRunResponse");
            state = described.path("JobRun").path("State").asText();
        } else {
            described = toStates(call(region, () -> emrServerless.getApplication(job.applicationId())),
                    "GetApplicationResponse");
            state = described.path("Application").path("State").asText();
        }
        Wait wait = WAITS.get(job.api());
        if (wait.deadEnds().contains(state)) {
            throw failed(described);
        }
        return wait.done().contains(state) ? Optional.of(job.response()) : Optional.empty();
    }

    /**
     * Best effort, for a {@code .sync} Task that ends before its job does: Step Functions "will make a
     * best-effort attempt to cancel the task". The call is the one each integration's IAM policy grants
     * beyond its own and the read it waits with: DeleteApplication for {@code createApplication.sync},
     * StopApplication for {@code startApplication.sync} and CancelJobRun for {@code startJobRun.sync}.
     */
    void abandon(Job job, String region) {
        Supplier<Object> undo = switch (job.api()) {
            case "createApplication" -> () -> emrServerless.deleteApplication(job.applicationId());
            case "startApplication" -> () -> emrServerless.stopApplication(job.applicationId());
            case "startJobRun" -> () -> emrServerless.cancelJobRun(job.applicationId(), job.jobRunId());
            default -> null;
        };
        if (undo == null) {
            return;
        }
        try {
            call(region, undo);
        } catch (AslExecutor.FailStateException e) {
            // Being best effort, nothing left to undo leaves the Task ending as it was. Any other answer, like
            // any other failure, is a fault and is raised.
            if (!NOTHING_TO_UNDO.contains(e.error)) {
                throw e;
            }
            LOG.warnv("emr-serverless:{0}.sync ended before application {1} (job run {2}) was done, and it could "
                    + "not be undone: {3}", job.api(), job.applicationId(), job.jobRunId(), e.getMessage());
        }
    }

    private Job createApplication(JsonNode request, String region) {
        CreateApplicationRequest create = read(request, "CreateApplicationRequest", CreateApplicationRequest.class);
        if (create.getClientToken() == null) {
            create.setClientToken(idempotencyToken());
        }
        ObjectNode response = toStates(call(region, () -> emrServerless.createApplication(create)),
                "CreateApplicationResponse");
        return new Job("createApplication", response.path("ApplicationId").asText(), null, response);
    }

    private Job startJobRun(JsonNode request, String region) {
        String applicationId = requiredId(request, "ApplicationId");
        StartJobRunRequest start = read(request, "StartJobRunRequest", StartJobRunRequest.class);
        if (start.getClientToken() == null) {
            start.setClientToken(idempotencyToken());
        }
        ObjectNode response = toStates(call(region, () -> emrServerless.startJobRun(applicationId, start)),
                "StartJobRunResponse");
        return new Job("startJobRun", applicationId, response.path("JobRunId").asText(), response);
    }

    /** The guide's {@code {"ApplicationId": "string"}}, where the API answers nothing. */
    private Job applicationJob(String api, String applicationId) {
        return new Job(api, applicationId, null, objectMapper.createObjectNode().put("ApplicationId", applicationId));
    }

    /**
     * The model marks {@code clientToken} an idempotency token, which the SDK fills in when the caller
     * leaves it out; the guide's examples leave it out of requests the API cannot take without one.
     */
    private static String idempotencyToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * The request as the API's model class, its members renamed to the model's own. Only the model's
     * members are left by then, so one the class has no property for is refused rather than dropped.
     */
    private <T> T read(JsonNode request, String shape, Class<T> type) {
        try {
            return objectMapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .treeToValue(recase(request, shape, false), type);
        } catch (JsonProcessingException e) {
            throw refused("The request does not match the EMR Serverless model: " + e.getOriginalMessage());
        }
    }

    private ObjectNode toStates(Object response, String shape) {
        return (ObjectNode) recase(objectMapper.valueToTree(response), shape, true);
    }

    /**
     * {@code node} with every structure's members renamed, to PascalCase when {@code toStates}, else to
     * the model's names, which a member written in either case matches. A map keeps its keys. A member
     * the model does not have is dropped, as the API has no place for it, such as a request's
     * {@code ApplicationId}, which travels in the path.
     */
    private JsonNode recase(JsonNode node, String shapeName, boolean toStates) {
        RequestShapeModel.Shape shape = shapeName == null ? null : SHAPES.shapes().get(shapeName);
        if (shape == null) {
            return node.deepCopy();
        }
        return switch (shape.type()) {
            case "structure" -> node.isObject() ? recaseStructure(node, shape, toStates) : node.deepCopy();
            case "list" -> {
                if (!node.isArray()) {
                    yield node.deepCopy();
                }
                ArrayNode items = objectMapper.createArrayNode();
                node.forEach(item -> items.add(recase(item, shape.element(), toStates)));
                yield items;
            }
            case "map" -> {
                if (!node.isObject()) {
                    yield node.deepCopy();
                }
                ObjectNode entries = objectMapper.createObjectNode();
                node.properties().forEach(entry ->
                        entries.set(entry.getKey(), recase(entry.getValue(), shape.element(), toStates)));
                yield entries;
            }
            default -> node.deepCopy();
        };
    }

    /** The structure's members in the model's order, each from the first field naming it in any case. */
    private ObjectNode recaseStructure(JsonNode node, RequestShapeModel.Shape shape, boolean toStates) {
        ObjectNode members = objectMapper.createObjectNode();
        for (Map.Entry<String, String> member : shape.members().entrySet()) {
            String name = member.getKey();
            JsonNode value = field(node, name);
            if (!value.isMissingNode()) {
                String recased = toStates ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
                members.set(recased, recase(value, member.getValue(), toStates));
            }
        }
        return members;
    }

    /** The id {@code name} names, read as every other member is, in either case. */
    private static String requiredId(JsonNode request, String name) {
        return requiredText(field(request, name), name);
    }

    /** The first of {@code node}'s fields named {@code name} in any case, or a missing node. */
    private static JsonNode field(JsonNode node, String name) {
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (field.getKey().equalsIgnoreCase(name)) {
                return field.getValue();
            }
        }
        return MissingNode.getInstance();
    }

    /** Runs in the state machine's Region, which EMR Serverless reads to find its applications. */
    private <T> T call(String region, Supplier<T> body) {
        try {
            return RequestScopes.callAs(null, region, body);
        } catch (AwsException e) {
            throw new AslExecutor.FailStateException(ERROR_PREFIX + e.getErrorCode(), e.getMessage());
        }
    }
}
