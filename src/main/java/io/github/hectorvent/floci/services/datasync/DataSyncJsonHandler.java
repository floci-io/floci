package io.github.hectorvent.floci.services.datasync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.datasync.model.DataSyncAgent;
import io.github.hectorvent.floci.services.datasync.model.DataSyncLocation;
import io.github.hectorvent.floci.services.datasync.model.DataSyncLocationType;
import io.github.hectorvent.floci.services.datasync.model.DataSyncTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AWS DataSync management plane.
 *
 * <p>The task-execution data plane ({@code StartTaskExecution}, {@code DescribeTaskExecution},
 * {@code ListTaskExecutions}, {@code CancelTaskExecution}, {@code UpdateTaskExecution}) moves
 * real bytes between real storage systems and is not emulated. Those operations fall through
 * to a clean {@code UnknownOperationException} rather than a stub success, so callers fail
 * fast instead of stranding a waiter on a transfer that will never progress.
 */
@ApplicationScoped
public class DataSyncJsonHandler {

    private static final String CREATE_LOCATION = "CreateLocation";
    private static final String DESCRIBE_LOCATION = "DescribeLocation";
    private static final String UPDATE_LOCATION = "UpdateLocation";

    private final DataSyncService dataSyncService;
    private final ObjectMapper mapper;

    @Inject
    public DataSyncJsonHandler(DataSyncService dataSyncService, ObjectMapper mapper) {
        this.dataSyncService = dataSyncService;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        Response locationResponse = handleLocation(action, request, region);
        if (locationResponse != null) {
            return locationResponse;
        }
        return switch (action) {
            case "CreateAgent" -> {
                DataSyncAgent agent = dataSyncService.createAgent(request, region);
                yield ok(mapper.createObjectNode().put("AgentArn", agent.getAgentArn()));
            }
            case "DescribeAgent" -> ok(describeAgent(dataSyncService.getAgent(text(request, "AgentArn"))));
            case "UpdateAgent" -> {
                dataSyncService.updateAgent(text(request, "AgentArn"), text(request, "Name"));
                yield ok(mapper.createObjectNode());
            }
            case "DeleteAgent" -> {
                dataSyncService.deleteAgent(text(request, "AgentArn"));
                yield ok(mapper.createObjectNode());
            }
            case "ListAgents" -> {
                var page = dataSyncService.listAgents(text(request, "NextToken"),
                        request.path("MaxResults").asInt(0));
                ObjectNode response = mapper.createObjectNode();
                ArrayNode entries = response.putArray("Agents");
                for (DataSyncAgent agent : page.items()) {
                    ObjectNode entry = entries.addObject();
                    entry.put("AgentArn", agent.getAgentArn());
                    entry.put("Name", agent.getName() != null ? agent.getName() : "");
                    entry.put("Status", agent.getStatus());
                    entry.putObject("Platform").put("Version", agent.getPlatformVersion());
                }
                putNextToken(response, page.nextToken());
                yield ok(response);
            }

            case "ListLocations" -> {
                var page = dataSyncService.listLocations(request.get("Filters"),
                        text(request, "NextToken"), request.path("MaxResults").asInt(0));
                ObjectNode response = mapper.createObjectNode();
                ArrayNode entries = response.putArray("Locations");
                for (DataSyncLocation location : page.items()) {
                    ObjectNode entry = entries.addObject();
                    entry.put("LocationArn", location.getLocationArn());
                    entry.put("LocationUri", location.getLocationUri());
                }
                putNextToken(response, page.nextToken());
                yield ok(response);
            }
            case "DeleteLocation" -> {
                dataSyncService.deleteLocation(text(request, "LocationArn"));
                yield ok(mapper.createObjectNode());
            }

            case "CreateTask" -> {
                DataSyncTask task = dataSyncService.createTask(request, region);
                yield ok(mapper.createObjectNode().put("TaskArn", task.getTaskArn()));
            }
            case "DescribeTask" -> ok(describeTask(dataSyncService.getTask(text(request, "TaskArn"))));
            case "UpdateTask" -> {
                dataSyncService.updateTask(request);
                yield ok(mapper.createObjectNode());
            }
            case "DeleteTask" -> {
                dataSyncService.deleteTask(text(request, "TaskArn"));
                yield ok(mapper.createObjectNode());
            }
            case "ListTasks" -> {
                var page = dataSyncService.listTasks(request.get("Filters"),
                        text(request, "NextToken"), request.path("MaxResults").asInt(0));
                ObjectNode response = mapper.createObjectNode();
                ArrayNode entries = response.putArray("Tasks");
                for (DataSyncTask task : page.items()) {
                    ObjectNode entry = entries.addObject();
                    entry.put("TaskArn", task.getTaskArn());
                    entry.put("Status", task.getStatus());
                    entry.put("Name", task.getName() != null ? task.getName() : "");
                    entry.put("TaskMode", task.getTaskMode());
                }
                putNextToken(response, page.nextToken());
                yield ok(response);
            }

            case "TagResource" -> {
                dataSyncService.tagResource(text(request, "ResourceArn"),
                        DataSyncService.tagsOf(request.path("Tags")));
                yield ok(mapper.createObjectNode());
            }
            case "UntagResource" -> {
                dataSyncService.untagResource(text(request, "ResourceArn"),
                        DataSyncService.stringList(request.path("Keys")));
                yield ok(mapper.createObjectNode());
            }
            case "ListTagsForResource" -> {
                Map<String, String> tags = dataSyncService.listTagsForResource(text(request, "ResourceArn"));
                ObjectNode response = mapper.createObjectNode();
                ArrayNode entries = response.putArray("Tags");
                tags.forEach((key, value) -> entries.addObject().put("Key", key).put("Value", value));
                yield ok(response);
            }

            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
    }

    private Response handleLocation(String action, JsonNode request, String region) {
        if (action.startsWith(CREATE_LOCATION)) {
            Optional<DataSyncLocationType> type =
                    DataSyncLocationType.fromOperationSuffix(action.substring(CREATE_LOCATION.length()));
            if (type.isPresent()) {
                DataSyncLocation location = dataSyncService.createLocation(type.get(), request, region);
                return ok(mapper.createObjectNode().put("LocationArn", location.getLocationArn()));
            }
        }
        if (action.startsWith(DESCRIBE_LOCATION)) {
            Optional<DataSyncLocationType> type =
                    DataSyncLocationType.fromOperationSuffix(action.substring(DESCRIBE_LOCATION.length()));
            if (type.isPresent()) {
                return ok(describeLocation(
                        dataSyncService.getLocation(text(request, "LocationArn"), type.get())));
            }
        }
        if (action.startsWith(UPDATE_LOCATION)) {
            Optional<DataSyncLocationType> type =
                    DataSyncLocationType.fromOperationSuffix(action.substring(UPDATE_LOCATION.length()));
            if (type.isPresent()) {
                dataSyncService.updateLocation(type.get(), request);
                return ok(mapper.createObjectNode());
            }
        }
        return null;
    }

    private ObjectNode describeAgent(DataSyncAgent agent) {
        ObjectNode response = mapper.createObjectNode();
        response.put("AgentArn", agent.getAgentArn());
        response.put("Name", agent.getName() != null ? agent.getName() : "");
        response.put("Status", agent.getStatus());
        response.put("CreationTime", epochSeconds(agent.getCreationTime()));
        response.put("LastConnectionTime", epochSeconds(agent.getLastConnectionTime()));
        response.put("EndpointType", agent.getEndpointType());
        response.putObject("Platform").put("Version", agent.getPlatformVersion());
        if (agent.getVpcEndpointId() != null) {
            ObjectNode privateLink = response.putObject("PrivateLinkConfig");
            privateLink.put("VpcEndpointId", agent.getVpcEndpointId());
            putStrings(privateLink, "SubnetArns", agent.getSubnetArns());
            putStrings(privateLink, "SecurityGroupArns", agent.getSecurityGroupArns());
        }
        return response;
    }

    private ObjectNode describeLocation(DataSyncLocation location) {
        ObjectNode response = mapper.createObjectNode();
        response.put("LocationArn", location.getLocationArn());
        response.put("LocationUri", location.getLocationUri());
        JsonNode configuration = location.getConfiguration();
        for (String member : location.getLocationType().describeMembers()) {
            JsonNode value = configuration.get(member);
            if (value != null && !value.isNull()) {
                response.set(member, value.deepCopy());
            }
        }
        response.put("CreationTime", epochSeconds(location.getCreationTime()));
        return response;
    }

    private ObjectNode describeTask(DataSyncTask task) {
        ObjectNode response = mapper.createObjectNode();
        response.put("TaskArn", task.getTaskArn());
        response.put("Status", task.getStatus());
        response.put("Name", task.getName() != null ? task.getName() : "");
        response.put("TaskMode", task.getTaskMode());
        response.put("SourceLocationArn", task.getSourceLocationArn());
        response.put("DestinationLocationArn", task.getDestinationLocationArn());
        if (task.getCloudWatchLogGroupArn() != null) {
            response.put("CloudWatchLogGroupArn", task.getCloudWatchLogGroupArn());
        }
        response.putArray("SourceNetworkInterfaceArns");
        response.putArray("DestinationNetworkInterfaceArns");
        response.set("Options", task.getOptions() != null
                ? task.getOptions().deepCopy() : mapper.createObjectNode());
        response.set("Excludes", task.getExcludes() != null
                ? task.getExcludes().deepCopy() : mapper.createArrayNode());
        response.set("Includes", task.getIncludes() != null
                ? task.getIncludes().deepCopy() : mapper.createArrayNode());
        if (task.getSchedule() != null) {
            response.set("Schedule", task.getSchedule().deepCopy());
        }
        if (task.getManifestConfig() != null) {
            response.set("ManifestConfig", task.getManifestConfig().deepCopy());
        }
        if (task.getTaskReportConfig() != null) {
            response.set("TaskReportConfig", task.getTaskReportConfig().deepCopy());
        }
        response.put("CreationTime", epochSeconds(task.getCreationTime()));
        return response;
    }

    private static void putStrings(ObjectNode parent, String member, List<String> values) {
        ArrayNode array = parent.putArray(member);
        values.forEach(array::add);
    }

    private static void putNextToken(ObjectNode response, String nextToken) {
        if (nextToken != null) {
            response.put("NextToken", nextToken);
        }
    }

    private static double epochSeconds(Instant instant) {
        return (instant != null ? instant : Instant.EPOCH).toEpochMilli() / 1000.0;
    }

    private static String text(JsonNode request, String member) {
        return request.hasNonNull(member) ? request.get(member).asText() : null;
    }

    private static Response ok(ObjectNode body) {
        return Response.ok(body).build();
    }
}
