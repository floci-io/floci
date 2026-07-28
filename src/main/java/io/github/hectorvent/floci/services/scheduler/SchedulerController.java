package io.github.hectorvent.floci.services.scheduler;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.scheduler.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.scheduler.model.DeadLetterConfig;
import io.github.hectorvent.floci.services.scheduler.model.EcsParameters;
import io.github.hectorvent.floci.services.scheduler.model.EventBridgeParameters;
import io.github.hectorvent.floci.services.scheduler.model.FlexibleTimeWindow;
import io.github.hectorvent.floci.services.scheduler.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.scheduler.model.RetryPolicy;
import io.github.hectorvent.floci.services.scheduler.model.Schedule;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleGroup;
import io.github.hectorvent.floci.services.scheduler.model.ScheduleRequest;
import io.github.hectorvent.floci.services.scheduler.model.SqsParameters;
import io.github.hectorvent.floci.services.scheduler.model.Target;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AWS EventBridge Scheduler REST endpoints.
 * Paths mirror the AWS SDK v2 SchedulerClient (e.g. {@code POST /schedule-groups/{Name}}).
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SchedulerController {

    private final SchedulerService schedulerService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SchedulerController(SchedulerService schedulerService,
                               RegionResolver regionResolver,
                               ObjectMapper objectMapper) {
        this.schedulerService = schedulerService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── CreateScheduleGroup ────────────────────────────

    @POST
    @Path("/schedule-groups/{name}")
    public Response createScheduleGroup(@Context HttpHeaders headers,
                                        @PathParam("name") String name,
                                        String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Map<String, String> tags = parseTags(body);
            ScheduleGroup group = schedulerService.createScheduleGroup(name, tags, region);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("ScheduleGroupArn", group.getArn());
            return Response.ok(response).build();
        } catch (AwsException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
    }

    // ──────────────────────────── GetScheduleGroup ────────────────────────────

    @GET
    @Path("/schedule-groups/{name}")
    public Response getScheduleGroup(@Context HttpHeaders headers,
                                     @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        ScheduleGroup group = schedulerService.getScheduleGroup(name, region);
        return Response.ok(buildGroupResponse(group)).build();
    }

    // ──────────────────────────── DeleteScheduleGroup ────────────────────────────

    @DELETE
    @Path("/schedule-groups/{name}")
    public Response deleteScheduleGroup(@Context HttpHeaders headers,
                                        @PathParam("name") String name) {
        String region = regionResolver.resolveRegion(headers);
        schedulerService.deleteScheduleGroup(name, region);
        return Response.ok().build();
    }

    // ──────────────────────────── ListScheduleGroups ────────────────────────────

    @GET
    @Path("/schedule-groups")
    public Response listScheduleGroups(@Context HttpHeaders headers,
                                       @QueryParam("NamePrefix") String namePrefix) {
        String region = regionResolver.resolveRegion(headers);
        List<ScheduleGroup> groups = schedulerService.listScheduleGroups(namePrefix, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("ScheduleGroups");
        for (ScheduleGroup group : groups) {
            items.add(objectMapper.valueToTree(buildGroupResponse(group)));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── CreateSchedule ────────────────────────────

    @POST
    @Path("/schedules/{name}")
    public Response createSchedule(@Context HttpHeaders headers,
                                   @PathParam("name") String name,
                                   String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode node = objectMapper.readTree(body != null ? body : "{}");
            ScheduleRequest req = parseScheduleRequest(node);
            req.setName(name);
            Schedule schedule = schedulerService.createSchedule(req, region);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("ScheduleArn", schedule.getArn());
            return Response.ok(response).build();
        } catch (AwsException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
    }

    // ──────────────────────────── GetSchedule ────────────────────────────

    @GET
    @Path("/schedules/{name}")
    public Response getSchedule(@Context HttpHeaders headers,
                                @PathParam("name") String name,
                                @QueryParam("groupName") String groupName) {
        String region = regionResolver.resolveRegion(headers);
        Schedule schedule = schedulerService.getSchedule(name, groupName, region);
        return Response.ok(buildScheduleResponse(schedule)).build();
    }

    // ──────────────────────────── UpdateSchedule ────────────────────────────

    @PUT
    @Path("/schedules/{name}")
    public Response updateSchedule(@Context HttpHeaders headers,
                                   @PathParam("name") String name,
                                   String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode node = objectMapper.readTree(body != null ? body : "{}");
            ScheduleRequest req = parseScheduleRequest(node);
            req.setName(name);
            Schedule schedule = schedulerService.updateSchedule(req, region);
            ObjectNode response = objectMapper.createObjectNode();
            response.put("ScheduleArn", schedule.getArn());
            return Response.ok(response).build();
        } catch (AwsException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationException", e.getMessage(), 400);
        }
    }

    // ──────────────────────────── DeleteSchedule ────────────────────────────

    @DELETE
    @Path("/schedules/{name}")
    public Response deleteSchedule(@Context HttpHeaders headers,
                                   @PathParam("name") String name,
                                   @QueryParam("groupName") String groupName) {
        String region = regionResolver.resolveRegion(headers);
        schedulerService.deleteSchedule(name, groupName, region);
        return Response.ok().build();
    }

    // ──────────────────────────── ListSchedules ────────────────────────────

    @GET
    @Path("/schedules")
    public Response listSchedules(@Context HttpHeaders headers,
                                  @QueryParam("ScheduleGroup") String groupName,
                                  @QueryParam("NamePrefix") String namePrefix,
                                  @QueryParam("State") String state) {
        String region = regionResolver.resolveRegion(headers);
        List<Schedule> schedules = schedulerService.listSchedules(groupName, namePrefix, state, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("Schedules");
        for (Schedule schedule : schedules) {
            items.add(objectMapper.valueToTree(buildScheduleSummary(schedule)));
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private Map<String, Object> buildGroupResponse(ScheduleGroup group) {
        Map<String, Object> response = new HashMap<>();
        response.put("Name", group.getName());
        response.put("Arn", group.getArn());
        response.put("State", group.getState());
        if (group.getCreationDate() != null) {
            response.put("CreationDate", toEpochDouble(group.getCreationDate()));
        }
        if (group.getLastModificationDate() != null) {
            response.put("LastModificationDate", toEpochDouble(group.getLastModificationDate()));
        }
        return response;
    }

    private Map<String, Object> buildScheduleResponse(Schedule s) {
        Map<String, Object> r = new HashMap<>();
        r.put("Name", s.getName());
        r.put("Arn", s.getArn());
        r.put("GroupName", s.getGroupName());
        r.put("State", s.getState());
        r.put("ScheduleExpression", s.getScheduleExpression());
        if (s.getScheduleExpressionTimezone() != null) {
            r.put("ScheduleExpressionTimezone", s.getScheduleExpressionTimezone());
        }
        if (s.getFlexibleTimeWindow() != null) {
            Map<String, Object> ftw = new HashMap<>();
            ftw.put("Mode", s.getFlexibleTimeWindow().getMode());
            if (s.getFlexibleTimeWindow().getMaximumWindowInMinutes() != null) {
                ftw.put("MaximumWindowInMinutes", s.getFlexibleTimeWindow().getMaximumWindowInMinutes());
            }
            r.put("FlexibleTimeWindow", ftw);
        }
        if (s.getTarget() != null) {
            Map<String, Object> t = new HashMap<>();
            t.put("Arn", s.getTarget().getArn());
            t.put("RoleArn", s.getTarget().getRoleArn());
            if (s.getTarget().getInput() != null) {
                t.put("Input", s.getTarget().getInput());
            }
            if (s.getTarget().getRetryPolicy() != null) {
                Map<String, Object> rp = new HashMap<>();
                if (s.getTarget().getRetryPolicy().getMaximumEventAgeInSeconds() != null) {
                    rp.put("MaximumEventAgeInSeconds", s.getTarget().getRetryPolicy().getMaximumEventAgeInSeconds());
                }
                if (s.getTarget().getRetryPolicy().getMaximumRetryAttempts() != null) {
                    rp.put("MaximumRetryAttempts", s.getTarget().getRetryPolicy().getMaximumRetryAttempts());
                }
                if (!rp.isEmpty()) {
                    t.put("RetryPolicy", rp);
                }
            }
            if (s.getTarget().getDeadLetterConfig() != null) {
                Map<String, Object> dlc = new HashMap<>();
                dlc.put("Arn", s.getTarget().getDeadLetterConfig().getArn());
                t.put("DeadLetterConfig", dlc);
            }
            if (s.getTarget().getSqsParameters() != null
                    && s.getTarget().getSqsParameters().getMessageGroupId() != null) {
                Map<String, Object> sp = new HashMap<>();
                sp.put("MessageGroupId", s.getTarget().getSqsParameters().getMessageGroupId());
                t.put("SqsParameters", sp);
            }
            if (s.getTarget().getEcsParameters() != null) {
                Map<String, Object> ep = buildEcsParametersResponse(s.getTarget().getEcsParameters());
                if (!ep.isEmpty()) {
                    t.put("EcsParameters", ep);
                }
            }
            if (s.getTarget().getEventBridgeParameters() != null) {
                EventBridgeParameters ebp = s.getTarget().getEventBridgeParameters();
                Map<String, Object> eb = new HashMap<>();
                if (ebp.getDetailType() != null) {
                    eb.put("DetailType", ebp.getDetailType());
                }
                if (ebp.getSource() != null) {
                    eb.put("Source", ebp.getSource());
                }
                if (!eb.isEmpty()) {
                    t.put("EventBridgeParameters", eb);
                }
            }
            r.put("Target", t);
        }
        if (s.getDescription() != null) {
            r.put("Description", s.getDescription());
        }
        if (s.getActionAfterCompletion() != null) {
            r.put("ActionAfterCompletion", s.getActionAfterCompletion());
        }
        if (s.getStartDate() != null) {
            r.put("StartDate", toEpochDouble(s.getStartDate()));
        }
        if (s.getEndDate() != null) {
            r.put("EndDate", toEpochDouble(s.getEndDate()));
        }
        if (s.getKmsKeyArn() != null) {
            r.put("KmsKeyArn", s.getKmsKeyArn());
        }
        if (s.getCreationDate() != null) {
            r.put("CreationDate", toEpochDouble(s.getCreationDate()));
        }
        if (s.getLastModificationDate() != null) {
            r.put("LastModificationDate", toEpochDouble(s.getLastModificationDate()));
        }
        return r;
    }

    private Map<String, Object> buildScheduleSummary(Schedule s) {
        Map<String, Object> r = new HashMap<>();
        r.put("Name", s.getName());
        r.put("Arn", s.getArn());
        r.put("GroupName", s.getGroupName());
        r.put("State", s.getState());
        if (s.getCreationDate() != null) {
            r.put("CreationDate", toEpochDouble(s.getCreationDate()));
        }
        if (s.getLastModificationDate() != null) {
            r.put("LastModificationDate", toEpochDouble(s.getLastModificationDate()));
        }
        if (s.getTarget() != null) {
            Map<String, Object> t = new HashMap<>();
            t.put("Arn", s.getTarget().getArn());
            r.put("Target", t);
        }
        return r;
    }

    private double toEpochDouble(Instant instant) {
        return instant.getEpochSecond() + instant.getNano() / 1_000_000_000.0;
    }

    private String textField(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f != null && !f.isNull()) ? f.asText() : null;
    }

    private Instant instantField(JsonNode node, String field) {
        JsonNode f = node.get(field);
        if (f != null && !f.isNull() && f.isNumber()) {
            long millis = Math.round(f.doubleValue() * 1_000);
            return Instant.ofEpochMilli(millis);
        }
        return null;
    }

    private ScheduleRequest parseScheduleRequest(JsonNode node) {
        ScheduleRequest req = new ScheduleRequest();
        req.setGroupName(textField(node, "GroupName"));
        req.setScheduleExpression(textField(node, "ScheduleExpression"));
        req.setScheduleExpressionTimezone(textField(node, "ScheduleExpressionTimezone"));
        req.setFlexibleTimeWindow(parseFlexibleTimeWindow(node.get("FlexibleTimeWindow")));
        req.setTarget(parseTarget(node.get("Target")));
        req.setDescription(textField(node, "Description"));
        req.setState(textField(node, "State"));
        req.setActionAfterCompletion(textField(node, "ActionAfterCompletion"));
        req.setStartDate(instantField(node, "StartDate"));
        req.setEndDate(instantField(node, "EndDate"));
        req.setKmsKeyArn(textField(node, "KmsKeyArn"));
        return req;
    }

    private FlexibleTimeWindow parseFlexibleTimeWindow(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        FlexibleTimeWindow ftw = new FlexibleTimeWindow();
        if (node.has("Mode")) {
            ftw.setMode(node.get("Mode").asText());
        }
        if (node.has("MaximumWindowInMinutes") && !node.get("MaximumWindowInMinutes").isNull()) {
            ftw.setMaximumWindowInMinutes(node.get("MaximumWindowInMinutes").asInt());
        }
        return ftw;
    }

    private Target parseTarget(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Target target = new Target();
        if (node.has("Arn")) {
            target.setArn(node.get("Arn").asText());
        }
        if (node.has("RoleArn")) {
            target.setRoleArn(node.get("RoleArn").asText());
        }
        if (node.has("Input") && !node.get("Input").isNull()) {
            target.setInput(node.get("Input").asText());
        }
        if (node.has("RetryPolicy") && !node.get("RetryPolicy").isNull()) {
            JsonNode rpNode = node.get("RetryPolicy");
            RetryPolicy rp = new RetryPolicy();
            if (rpNode.has("MaximumEventAgeInSeconds") && !rpNode.get("MaximumEventAgeInSeconds").isNull()) {
                rp.setMaximumEventAgeInSeconds(rpNode.get("MaximumEventAgeInSeconds").asInt());
            }
            if (rpNode.has("MaximumRetryAttempts") && !rpNode.get("MaximumRetryAttempts").isNull()) {
                rp.setMaximumRetryAttempts(rpNode.get("MaximumRetryAttempts").asInt());
            }
            target.setRetryPolicy(rp);
        }
        if (node.has("DeadLetterConfig") && !node.get("DeadLetterConfig").isNull()) {
            JsonNode dlcNode = node.get("DeadLetterConfig");
            DeadLetterConfig dlc = new DeadLetterConfig();
            if (dlcNode.has("Arn") && !dlcNode.get("Arn").isNull()) {
                dlc.setArn(dlcNode.get("Arn").asText());
            }
            target.setDeadLetterConfig(dlc);
        }
        if (node.has("SqsParameters") && !node.get("SqsParameters").isNull()) {
            JsonNode spNode = node.get("SqsParameters");
            SqsParameters sp = new SqsParameters();
            if (spNode.has("MessageGroupId") && !spNode.get("MessageGroupId").isNull()) {
                sp.setMessageGroupId(spNode.get("MessageGroupId").asText());
            }
            target.setSqsParameters(sp);
        }
        if (node.has("EcsParameters") && !node.get("EcsParameters").isNull()) {
            target.setEcsParameters(parseEcsParameters(node.get("EcsParameters")));
        }
        if (node.has("EventBridgeParameters") && !node.get("EventBridgeParameters").isNull()) {
            JsonNode ebNode = node.get("EventBridgeParameters");
            EventBridgeParameters ebp = new EventBridgeParameters();
            if (
                ebNode.has("DetailType") &&
                !ebNode.get("DetailType").isNull() &&
                !ebNode.get("DetailType").asText().isBlank()
            ) {
                ebp.setDetailType(ebNode.get("DetailType").asText());
            }
            if (
                ebNode.has("Source") &&
                !ebNode.get("Source").isNull() &&
                !ebNode.get("Source").asText().isBlank()
            ) {
                ebp.setSource(ebNode.get("Source").asText());
            }
            if (ebp.getDetailType() == null || ebp.getSource() == null) {
                 throw new AwsException("ValidationException",
                    "EventBridgeParameters requires both DetailType and Source.", 400);
            }

            if(ebp.getDetailType().length() > 128) {
                throw new AwsException("ValidationException",
                    "EventBridgeParameters DetailType must be less than or equal to 128 characters.", 400);
            }
            if(ebp.getSource().length() > 256) {
                throw new AwsException("ValidationException",
                    "EventBridgeParameters Source must be less than or equal to 256 characters.", 400);
            }

            if(ebp.getSource().startsWith("aws.") || ebp.getSource().startsWith("aws:")) {
                throw new AwsException("ValidationException",
                    "EventBridgeParameters Source cannot start with 'aws.' or 'aws:'.", 400);
            }

            target.setEventBridgeParameters(ebp);
        }
        return target;
    }

    private EcsParameters parseEcsParameters(JsonNode node) {
        EcsParameters ecs = new EcsParameters();
        if (node.has("CapacityProviderStrategy") && node.get("CapacityProviderStrategy").isArray()) {
            ecs.setCapacityProviderStrategy(mapList(node.get("CapacityProviderStrategy")));
        }
        if (node.has("EnableECSManagedTags") && !node.get("EnableECSManagedTags").isNull()) {
            ecs.setEnableECSManagedTags(node.get("EnableECSManagedTags").asBoolean());
        }
        if (node.has("EnableExecuteCommand") && !node.get("EnableExecuteCommand").isNull()) {
            ecs.setEnableExecuteCommand(node.get("EnableExecuteCommand").asBoolean());
        }
        if (node.has("Group") && !node.get("Group").isNull()) {
            ecs.setGroup(node.get("Group").asText());
        }
        if (node.has("TaskDefinitionArn") && !node.get("TaskDefinitionArn").isNull()) {
            ecs.setTaskDefinitionArn(node.get("TaskDefinitionArn").asText());
        }
        if (node.has("LaunchType") && !node.get("LaunchType").isNull()) {
            ecs.setLaunchType(node.get("LaunchType").asText());
        }
        if (node.has("PlacementConstraints") && node.get("PlacementConstraints").isArray()) {
            ecs.setPlacementConstraints(mapList(node.get("PlacementConstraints")));
        }
        if (node.has("PlacementStrategy") && node.get("PlacementStrategy").isArray()) {
            ecs.setPlacementStrategy(mapList(node.get("PlacementStrategy")));
        }
        if (node.has("TaskCount") && !node.get("TaskCount").isNull()) {
            ecs.setTaskCount(node.get("TaskCount").asInt());
        }
        if (node.has("PlatformVersion") && !node.get("PlatformVersion").isNull()) {
            ecs.setPlatformVersion(node.get("PlatformVersion").asText());
        }
        if (node.has("PropagateTags") && !node.get("PropagateTags").isNull()) {
            ecs.setPropagateTags(node.get("PropagateTags").asText());
        }
        if (node.has("ReferenceId") && !node.get("ReferenceId").isNull()) {
            ecs.setReferenceId(node.get("ReferenceId").asText());
        }
        if (node.has("Tags") && node.get("Tags").isArray()) {
            ecs.setTags(mapList(node.get("Tags")));
        }
        if (node.has("NetworkConfiguration") && !node.get("NetworkConfiguration").isNull()) {
            ecs.setNetworkConfiguration(parseNetworkConfiguration(node.get("NetworkConfiguration")));
        }
        return ecs;
    }

    private List<Map<String, Object>> mapList(JsonNode node) {
        return objectMapper.convertValue(node,
                objectMapper.getTypeFactory().constructCollectionType(List.class,
                        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)));
    }

    private NetworkConfiguration parseNetworkConfiguration(JsonNode node) {
        NetworkConfiguration network = new NetworkConfiguration();
        if (node.has("awsvpcConfiguration") && !node.get("awsvpcConfiguration").isNull()) {
            network.setAwsvpcConfiguration(parseAwsVpcConfiguration(node.get("awsvpcConfiguration")));
        }
        return network;
    }

    private AwsVpcConfiguration parseAwsVpcConfiguration(JsonNode node) {
        AwsVpcConfiguration vpc = new AwsVpcConfiguration();
        if (node.has("Subnets") && node.get("Subnets").isArray()) {
            vpc.setSubnets(objectMapper.convertValue(node.get("Subnets"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        }
        if (node.has("SecurityGroups") && node.get("SecurityGroups").isArray()) {
            vpc.setSecurityGroups(objectMapper.convertValue(node.get("SecurityGroups"), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        }
        if (node.has("AssignPublicIp") && !node.get("AssignPublicIp").isNull()) {
            vpc.setAssignPublicIp(node.get("AssignPublicIp").asText());
        }
        return vpc;
    }

    private Map<String, Object> buildEcsParametersResponse(EcsParameters ecs) {
        Map<String, Object> ep = new HashMap<>();
        if (ecs.getCapacityProviderStrategy() != null && !ecs.getCapacityProviderStrategy().isEmpty()) {
            ep.put("CapacityProviderStrategy", ecs.getCapacityProviderStrategy());
        }
        if (ecs.getEnableECSManagedTags() != null) {
            ep.put("EnableECSManagedTags", ecs.getEnableECSManagedTags());
        }
        if (ecs.getEnableExecuteCommand() != null) {
            ep.put("EnableExecuteCommand", ecs.getEnableExecuteCommand());
        }
        if (ecs.getGroup() != null) {
            ep.put("Group", ecs.getGroup());
        }
        if (ecs.getTaskDefinitionArn() != null) {
            ep.put("TaskDefinitionArn", ecs.getTaskDefinitionArn());
        }
        if (ecs.getLaunchType() != null) {
            ep.put("LaunchType", ecs.getLaunchType());
        }
        if (ecs.getPlacementConstraints() != null && !ecs.getPlacementConstraints().isEmpty()) {
            ep.put("PlacementConstraints", ecs.getPlacementConstraints());
        }
        if (ecs.getPlacementStrategy() != null && !ecs.getPlacementStrategy().isEmpty()) {
            ep.put("PlacementStrategy", ecs.getPlacementStrategy());
        }
        if (ecs.getTaskCount() != null) {
            ep.put("TaskCount", ecs.getTaskCount());
        }
        if (ecs.getPlatformVersion() != null) {
            ep.put("PlatformVersion", ecs.getPlatformVersion());
        }
        if (ecs.getPropagateTags() != null) {
            ep.put("PropagateTags", ecs.getPropagateTags());
        }
        if (ecs.getReferenceId() != null) {
            ep.put("ReferenceId", ecs.getReferenceId());
        }
        if (ecs.getTags() != null && !ecs.getTags().isEmpty()) {
            ep.put("Tags", ecs.getTags());
        }
        if (ecs.getNetworkConfiguration() != null) {
            Map<String, Object> network = buildNetworkConfigurationResponse(ecs.getNetworkConfiguration());
            if (!network.isEmpty()) {
                ep.put("NetworkConfiguration", network);
            }
        }
        return ep;
    }

    private Map<String, Object> buildNetworkConfigurationResponse(NetworkConfiguration network) {
        Map<String, Object> result = new HashMap<>();
        if (network.getAwsvpcConfiguration() != null) {
            AwsVpcConfiguration vpc = network.getAwsvpcConfiguration();
            Map<String, Object> awsvpc = new HashMap<>();
            if (vpc.getSubnets() != null && !vpc.getSubnets().isEmpty()) {
                awsvpc.put("Subnets", vpc.getSubnets());
            }
            if (vpc.getSecurityGroups() != null && !vpc.getSecurityGroups().isEmpty()) {
                awsvpc.put("SecurityGroups", vpc.getSecurityGroups());
            }
            if (vpc.getAssignPublicIp() != null) {
                awsvpc.put("AssignPublicIp", vpc.getAssignPublicIp());
            }
            result.put("awsvpcConfiguration", awsvpc);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseTags(String body) throws JsonProcessingException {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
        Object tagsObj = parsed.get("Tags");
        if (!(tagsObj instanceof List<?> tagList)) {
            return Map.of();
        }
        Map<String, String> tags = new HashMap<>();
        for (Object entry : tagList) {
            if (entry instanceof Map<?, ?> tagMap) {
                Object key = tagMap.get("Key");
                Object value = tagMap.get("Value");
                if (key != null && value != null) {
                    tags.put(key.toString(), value.toString());
                }
            }
        }
        return tags;
    }
}
