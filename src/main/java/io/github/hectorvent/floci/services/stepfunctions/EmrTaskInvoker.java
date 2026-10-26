package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.emr.EmrHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.github.hectorvent.floci.services.stepfunctions.OptimizedIntegrations.failed;
import static io.github.hectorvent.floci.services.stepfunctions.OptimizedIntegrations.refused;
import static io.github.hectorvent.floci.services.stepfunctions.OptimizedIntegrations.required;
import static io.github.hectorvent.floci.services.stepfunctions.OptimizedIntegrations.unsupported;

/**
 * The optimized {@code arn:<partition>:states:::elasticmapreduce:*} integrations. Each one is an EMR
 * API under renamed fields, as the Step Functions guide's "Optimized Amazon EMR APIs" table lists
 * them, run through {@link EmrHandler} in the state machine's Region. A {@code .sync} Task calls
 * {@link #start}, then {@link #poll} until its cluster or step is done; {@link AslExecutor} owns the
 * wait between polls.
 */
@ApplicationScoped
public class EmrTaskInvoker {

    private static final Logger LOG = Logger.getLogger(EmrTaskInvoker.class);

    // A failure is named after the SDK exception class: the errors EMR models keep their own, and any
    // other code is the base class. AWS re:Post, "AWS Step Function does not recognize job flow ID":
    // a ValidationException fails addStep.sync as EMR.AmazonElasticMapReduceException.
    private static final Set<String> MODELED_ERRORS = Set.of("InvalidRequestException", "InternalServerException");
    private static final String BASE_ERROR = "AmazonElasticMapReduceException";
    private static final Set<String> APIS = Set.of("createCluster", "setClusterTerminationProtection",
            "terminateCluster", "addStep", "cancelStep", "modifyInstanceFleetByName", "modifyInstanceGroupByName");
    private static final Set<String> SYNC_APIS = Set.of("createCluster", "terminateCluster", "addStep");
    private static final Set<String> TERMINATED_STATES = Set.of("TERMINATED", "TERMINATED_WITH_ERRORS");
    private static final Set<String> FAILED_STEP_STATES = Set.of("FAILED", "CANCELLED", "INTERRUPTED");

    private final EmrHandler emr;
    private final ObjectMapper objectMapper;

    @Inject
    public EmrTaskInvoker(EmrHandler emr, ObjectMapper objectMapper) {
        this.emr = emr;
        this.objectMapper = objectMapper;
    }

    /**
     * A started call: the integration's response, and the cluster, or step, a {@code .sync} Task
     * waits on.
     */
    record Job(String api, String clusterId, String stepId, ObjectNode response) {}

    Job start(StatesIntegration integration, JsonNode input, String region) {
        String api = integration.api();
        if (!APIS.contains(api)
                || !integration.suffix().isEmpty() && !(".sync".equals(integration.suffix()) && SYNC_APIS.contains(api))) {
            throw unsupported(integration);
        }
        JsonNode request = input != null && input.isObject() ? input : objectMapper.createObjectNode();
        // The integration's own fields, named as the Task wrote them: renamed into JobFlowIds or StepIds,
        // a missing one would reach EMR as a "null" id.
        String clusterId = "createCluster".equals(api) ? null : required(request, "ClusterId");
        return switch (api) {
            case "createCluster" -> createCluster(request, region);
            case "setClusterTerminationProtection" -> new Job(api, clusterId, null,
                    call("SetTerminationProtection", withJobFlowIds(request), region));
            case "terminateCluster" -> new Job(api, clusterId, null,
                    call("TerminateJobFlows", withJobFlowIds(request), region));
            case "addStep" -> addStep(request, clusterId, region);
            case "cancelStep" -> cancelStep(request, clusterId, region);
            case "modifyInstanceFleetByName" -> modifyInstanceFleetByName(request, clusterId, region);
            case "modifyInstanceGroupByName" -> modifyInstanceGroupByName(request, clusterId, region);
            default -> throw unsupported(integration);
        };
    }

    /**
     * The Task's result once the job is done, empty while it runs. The result is the describe call's
     * response with the starting call's merged in: a {@code createCluster.sync} output recorded on
     * AWS is {@code Cluster}, the SDK metadata, then {@code ClusterId}.
     */
    Optional<JsonNode> poll(Job job, String region) {
        if ("addStep".equals(job.api())) {
            ObjectNode request = clusterRequest(job.clusterId());
            request.put("StepId", job.stepId());
            JsonNode described = call("DescribeStep", request, region);
            String state = described.path("Step").path("Status").path("State").asText();
            if (FAILED_STEP_STATES.contains(state)) {
                throw failed(described);
            }
            return "COMPLETED".equals(state) ? Optional.of(merged(described, job.response())) : Optional.empty();
        }
        JsonNode described = call("DescribeCluster", clusterRequest(job.clusterId()), region);
        String state = described.path("Cluster").path("Status").path("State").asText();
        if ("terminateCluster".equals(job.api())) {
            return TERMINATED_STATES.contains(state)
                    ? Optional.of(merged(described, job.response())) : Optional.empty();
        }
        if (TERMINATED_STATES.contains(state)) {
            throw failed(described);
        }
        return "WAITING".equals(state) ? Optional.of(merged(described, job.response())) : Optional.empty();
    }

    /**
     * Best effort, for a {@code .sync} Task that ends before its job does: Step Functions "will make a
     * best-effort attempt to cancel the task", and addStep's IAM policy grants it CancelSteps. A cluster
     * is left running, since "Step Functions does not terminate an Amazon EMR cluster automatically if
     * execution is stopped."
     */
    void abandon(Job job, String region) {
        if (!"addStep".equals(job.api())) {
            return;
        }
        ObjectNode request = clusterRequest(job.clusterId());
        request.putArray("StepIds").add(job.stepId());
        try {
            call("CancelSteps", request, region);
        } catch (RuntimeException e) {
            LOG.warnv("elasticmapreduce:addStep.sync ended before its step {0} did, and the step could not be "
                    + "cancelled: {1}", job.stepId(), e.getMessage());
        }
    }

    private Job createCluster(JsonNode request, String region) {
        // The two differences the guide lists from RunJobFlow. No source gives the error AWS answers either with.
        if (!request.path("Instances").path("KeepJobFlowAliveWhenNoSteps").booleanValue()) {
            throw refused("The field Instances.KeepJobFlowAliveWhenNoSteps is mandatory, "
                    + "and must have the Boolean value TRUE.");
        }
        if (request.has("Steps")) {
            throw refused("The field Steps is not allowed.");
        }
        String clusterId = call("RunJobFlow", request, region).path("JobFlowId").asText();
        return new Job("createCluster", clusterId, null, objectMapper.createObjectNode().put("ClusterId", clusterId));
    }

    private Job addStep(JsonNode request, String clusterId, String region) {
        if (!request.path("Step").isObject()) {
            throw refused("The field Step is required.");
        }
        ObjectNode add = request.deepCopy();
        add.remove(List.of("ClusterId", "Step"));
        add.put("JobFlowId", clusterId);
        add.putArray("Steps").add(request.get("Step"));
        String stepId = call("AddJobFlowSteps", add, region).path("StepIds").path(0).asText();
        return new Job("addStep", clusterId, stepId, objectMapper.createObjectNode().put("StepId", stepId));
    }

    private Job cancelStep(JsonNode request, String clusterId, String region) {
        String stepId = required(request, "StepId");
        ObjectNode cancel = request.deepCopy();
        cancel.remove("StepId");
        cancel.putArray("StepIds").add(stepId);
        JsonNode info = call("CancelSteps", cancel, region).path("CancelStepsInfoList").path(0);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("CancelStepsInfo", info.isMissingNode() ? objectMapper.createObjectNode() : info);
        return new Job("cancelStep", clusterId, null, response);
    }

    private Job modifyInstanceFleetByName(JsonNode request, String clusterId, String region) {
        JsonNode fleet = request.path("InstanceFleet");
        if (fleet.has("InstanceFleetId")) {
            throw refused("The field InstanceFleet.InstanceFleetId is not allowed.");
        }
        String name = required(request, "InstanceFleetName");
        JsonNode fleets = call("ListInstanceFleets", clusterRequest(clusterId), region).path("InstanceFleets");
        ObjectNode modification = fleet.isObject() ? fleet.deepCopy() : objectMapper.createObjectNode();
        modification.put("InstanceFleetId", idOfNamed(fleets, name, "instance fleet"));
        ObjectNode modify = clusterRequest(clusterId);
        modify.set("InstanceFleet", modification);
        return new Job("modifyInstanceFleetByName", clusterId, null, call("ModifyInstanceFleet", modify, region));
    }

    private Job modifyInstanceGroupByName(JsonNode request, String clusterId, String region) {
        JsonNode group = request.path("InstanceGroup");
        if (group.has("InstanceGroupId")) {
            throw refused("The field InstanceGroup.InstanceGroupId is not allowed.");
        }
        String name = required(request, "InstanceGroupName");
        JsonNode groups = call("ListInstanceGroups", clusterRequest(clusterId), region).path("InstanceGroups");
        ObjectNode modification = group.isObject() ? group.deepCopy() : objectMapper.createObjectNode();
        modification.put("InstanceGroupId", idOfNamed(groups, name, "instance group"));
        ObjectNode modify = clusterRequest(clusterId);
        modify.putArray("InstanceGroups").add(modification);
        return new Job("modifyInstanceGroupByName", clusterId, null, call("ModifyInstanceGroups", modify, region));
    }

    /** The guide asks for unique names whenever a ByName API is used; the lookup reads ListInstance*. */
    private static String idOfNamed(JsonNode members, String name, String kind) {
        List<String> ids = new ArrayList<>();
        for (JsonNode member : members) {
            if (name.equals(member.path("Name").asText(null))) {
                ids.add(member.path("Id").asText());
            }
        }
        if (ids.size() == 1) {
            return ids.get(0);
        }
        throw refused(ids.isEmpty()
                ? "The cluster has no " + kind + " named '" + name + "'."
                : "The cluster has " + ids.size() + " " + kind + "s named '" + name + "'.");
    }

    /** The request with {@code ClusterId} renamed to EMR's {@code JobFlowIds}. */
    private ObjectNode withJobFlowIds(JsonNode request) {
        ObjectNode renamed = request.deepCopy();
        renamed.putArray("JobFlowIds").add(renamed.remove("ClusterId"));
        return renamed;
    }

    private ObjectNode clusterRequest(String clusterId) {
        return objectMapper.createObjectNode().put("ClusterId", clusterId);
    }

    private ObjectNode merged(JsonNode described, ObjectNode response) {
        ObjectNode result = described.deepCopy();
        result.setAll(response);
        return result;
    }

    /** Runs in the state machine's Region, which EMR reads to find its clusters. */
    private ObjectNode call(String action, JsonNode request, String region) {
        Response response = RequestScopes.callAs(null, region, () -> emr.handle(action, request, region));
        if (response.getStatus() >= 400) {
            JsonNode error = objectMapper.valueToTree(response.getEntity());
            String type = error.path("__type").asText();
            String name = MODELED_ERRORS.contains(type) ? type : BASE_ERROR;
            throw new AslExecutor.FailStateException("EMR." + name, error.path("message").asText());
        }
        return response.getEntity() instanceof ObjectNode body ? body : objectMapper.createObjectNode();
    }
}
