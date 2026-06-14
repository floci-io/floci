package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsJson11Controller;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceFleet;
import io.github.hectorvent.floci.services.emr.model.EmrInstanceGroup;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.emr.model.SecurityConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EMR (elasticmapreduce) JSON 1.1 handler. Dispatched from {@link AwsJson11Controller}
 * under the {@code ElasticMapReduce.} target prefix.
 */
@ApplicationScoped
public class EmrHandler {

    private static final Logger LOG = Logger.getLogger(EmrHandler.class);

    private final EmrService service;
    private final ObjectMapper objectMapper;

    @Inject
    public EmrHandler(EmrService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("EMR action: {0}", action);
        try {
            return switch (action) {
                case "RunJobFlow" -> handleRunJobFlow(request, region);
                case "DescribeCluster" -> handleDescribeCluster(request);
                case "ListClusters" -> handleListClusters(request, region);
                case "TerminateJobFlows" -> handleTerminateJobFlows(request);
                case "SetTerminationProtection" -> handleSetTerminationProtection(request);
                case "SetVisibleToAllUsers" -> handleSetVisibleToAllUsers(request);
                case "SetKeepJobFlowAliveWhenNoSteps" -> handleSetKeepAlive(request);
                case "SetUnhealthyNodeReplacement" -> handleSetUnhealthyNodeReplacement(request);
                case "ModifyCluster" -> handleModifyCluster(request);
                case "AddJobFlowSteps" -> handleAddJobFlowSteps(request);
                case "DescribeStep" -> handleDescribeStep(request);
                case "ListSteps" -> handleListSteps(request);
                case "CancelSteps" -> handleCancelSteps(request);
                case "AddInstanceGroups" -> handleAddInstanceGroups(request);
                case "ListInstanceGroups" -> handleListInstanceGroups(request);
                case "AddInstanceFleet" -> handleAddInstanceFleet(request);
                case "ListInstanceFleets" -> handleListInstanceFleets(request);
                case "ListInstances" -> handleListInstances(request);
                case "CreateSecurityConfiguration" -> handleCreateSecurityConfiguration(request);
                case "DescribeSecurityConfiguration" -> handleDescribeSecurityConfiguration(request);
                case "DeleteSecurityConfiguration" -> handleDeleteSecurityConfiguration(request);
                case "ListSecurityConfigurations" -> handleListSecurityConfigurations();
                case "AddTags" -> handleAddTags(request);
                case "RemoveTags" -> handleRemoveTags(request);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("InvalidRequestException",
                                "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorv("EMR error processing action {0}: {1}", action, e.getMessage());
            return Response.status(500)
                    .entity(new AwsErrorResponse("InternalServerException", e.getMessage()))
                    .build();
        }
    }

    // ──────────────────────────── Cluster lifecycle ────────────────────────────

    private Response handleRunJobFlow(JsonNode request, String region) {
        EmrCluster cluster = new EmrCluster();
        cluster.setName(text(request, "Name"));
        cluster.setReleaseLabel(text(request, "ReleaseLabel"));
        cluster.setLogUri(text(request, "LogUri"));
        cluster.setServiceRole(text(request, "ServiceRole"));
        cluster.setJobFlowRole(text(request, "JobFlowRole"));
        cluster.setAutoScalingRole(text(request, "AutoScalingRole"));
        cluster.setScaleDownBehavior(text(request, "ScaleDownBehavior"));
        cluster.setSecurityConfiguration(text(request, "SecurityConfiguration"));
        cluster.setCustomAmiId(text(request, "CustomAmiId"));
        cluster.setVisibleToAllUsers(request.path("VisibleToAllUsers").asBoolean(true));
        cluster.setStepConcurrencyLevel(request.path("StepConcurrencyLevel").asInt(1));
        cluster.setEbsRootVolumeSize(request.path("EbsRootVolumeSize").asInt(0));
        cluster.setApplications(rawArray(request.path("Applications")));
        cluster.setConfigurations(rawArray(request.path("Configurations")));
        cluster.setTags(parseTags(request.path("Tags")));

        JsonNode instances = request.path("Instances");
        cluster.setKeepJobFlowAliveWhenNoSteps(instances.path("KeepJobFlowAliveWhenNoSteps").asBoolean(false));
        cluster.setTerminationProtected(instances.path("TerminationProtected").asBoolean(false));
        cluster.setUnhealthyNodeReplacement(instances.path("UnhealthyNodeReplacement").asBoolean(false));
        cluster.setEc2Attributes(buildEc2Attributes(instances));
        cluster.setInstanceGroups(parseInstanceGroups(instances.path("InstanceGroups")));
        cluster.setInstanceFleets(parseInstanceFleets(instances.path("InstanceFleets")));
        cluster.setSteps(parseSteps(request.path("Steps")));

        EmrCluster created = service.runJobFlow(cluster, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("JobFlowId", created.getId());
        response.put("ClusterArn", created.getClusterArn());
        return Response.ok(response).build();
    }

    private Response handleDescribeCluster(JsonNode request) {
        EmrCluster cluster = service.describeCluster(text(request, "ClusterId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Cluster", clusterNode(cluster));
        return Response.ok(response).build();
    }

    private Response handleListClusters(JsonNode request, String region) {
        List<String> states = stringList(request.path("ClusterStates"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Clusters");
        for (EmrCluster c : service.listClusters(region, states)) {
            arr.add(clusterSummaryNode(c));
        }
        return Response.ok(response).build();
    }

    private Response handleTerminateJobFlows(JsonNode request) {
        service.terminateJobFlows(stringList(request.path("JobFlowIds")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetTerminationProtection(JsonNode request) {
        service.setTerminationProtection(stringList(request.path("JobFlowIds")),
                request.path("TerminationProtected").asBoolean(false));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetVisibleToAllUsers(JsonNode request) {
        service.setVisibleToAllUsers(stringList(request.path("JobFlowIds")),
                request.path("VisibleToAllUsers").asBoolean(true));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetKeepAlive(JsonNode request) {
        service.setKeepJobFlowAliveWhenNoSteps(stringList(request.path("JobFlowIds")),
                request.path("KeepJobFlowAliveWhenNoSteps").asBoolean(false));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetUnhealthyNodeReplacement(JsonNode request) {
        service.setUnhealthyNodeReplacement(stringList(request.path("JobFlowIds")),
                request.path("UnhealthyNodeReplacement").asBoolean(false));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleModifyCluster(JsonNode request) {
        Integer level = request.has("StepConcurrencyLevel") ? request.path("StepConcurrencyLevel").asInt() : null;
        int result = service.modifyCluster(text(request, "ClusterId"), level);
        return Response.ok(objectMapper.createObjectNode().put("StepConcurrencyLevel", result)).build();
    }

    // ──────────────────────────── Steps ────────────────────────────

    private Response handleAddJobFlowSteps(JsonNode request) {
        List<String> ids = service.addJobFlowSteps(text(request, "JobFlowId"), parseSteps(request.path("Steps")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("StepIds");
        ids.forEach(arr::add);
        return Response.ok(response).build();
    }

    private Response handleDescribeStep(JsonNode request) {
        EmrStep step = service.describeStep(text(request, "ClusterId"), text(request, "StepId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Step", stepNode(step));
        return Response.ok(response).build();
    }

    private Response handleListSteps(JsonNode request) {
        List<EmrStep> steps = service.listSteps(text(request, "ClusterId"),
                stringList(request.path("StepStates")), stringList(request.path("StepIds")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Steps");
        for (EmrStep step : steps) {
            arr.add(stepNode(step));
        }
        return Response.ok(response).build();
    }

    private Response handleCancelSteps(JsonNode request) {
        List<EmrService.CancelStepInfo> infos = service.cancelSteps(
                text(request, "ClusterId"), stringList(request.path("StepIds")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("CancelStepsInfoList");
        for (EmrService.CancelStepInfo info : infos) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("StepId", info.stepId());
            node.put("Status", info.status());
            if (info.reason() != null) {
                node.put("Reason", info.reason());
            }
            arr.add(node);
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Instance groups / fleets ────────────────────────────

    private Response handleAddInstanceGroups(JsonNode request) {
        List<String> ids = service.addInstanceGroups(text(request, "JobFlowId"),
                parseInstanceGroups(request.path("InstanceGroups")));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("JobFlowId", text(request, "JobFlowId"));
        ArrayNode arr = response.putArray("InstanceGroupIds");
        ids.forEach(arr::add);
        return Response.ok(response).build();
    }

    private Response handleListInstanceGroups(JsonNode request) {
        List<EmrInstanceGroup> groups = service.listInstanceGroups(text(request, "ClusterId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("InstanceGroups");
        for (EmrInstanceGroup g : groups) {
            arr.add(instanceGroupNode(g));
        }
        return Response.ok(response).build();
    }

    private Response handleAddInstanceFleet(JsonNode request) {
        EmrInstanceFleet fleet = parseInstanceFleet(request.path("InstanceFleet"));
        String id = service.addInstanceFleet(text(request, "ClusterId"), fleet);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ClusterId", text(request, "ClusterId"));
        response.put("InstanceFleetId", id);
        return Response.ok(response).build();
    }

    private Response handleListInstanceFleets(JsonNode request) {
        List<EmrInstanceFleet> fleets = service.listInstanceFleets(text(request, "ClusterId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("InstanceFleets");
        for (EmrInstanceFleet f : fleets) {
            arr.add(instanceFleetNode(f));
        }
        return Response.ok(response).build();
    }

    private Response handleListInstances(JsonNode request) {
        EmrCluster cluster = service.getClusterForInstances(text(request, "ClusterId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Instances");
        int n = 1;
        for (EmrInstanceGroup group : cluster.getInstanceGroups()) {
            for (int i = 0; i < Math.max(1, group.getRunningInstanceCount()); i++) {
                arr.add(syntheticInstanceNode(cluster, group, n++));
            }
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Security configurations ────────────────────────────

    private Response handleCreateSecurityConfiguration(JsonNode request) {
        SecurityConfiguration sc = service.createSecurityConfiguration(
                text(request, "Name"), text(request, "SecurityConfiguration"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Name", sc.getName());
        response.put("CreationDateTime", epoch(sc.getCreationDateTime()));
        return Response.ok(response).build();
    }

    private Response handleDescribeSecurityConfiguration(JsonNode request) {
        SecurityConfiguration sc = service.describeSecurityConfiguration(text(request, "Name"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Name", sc.getName());
        response.put("SecurityConfiguration", sc.getSecurityConfiguration());
        response.put("CreationDateTime", epoch(sc.getCreationDateTime()));
        return Response.ok(response).build();
    }

    private Response handleDeleteSecurityConfiguration(JsonNode request) {
        service.deleteSecurityConfiguration(text(request, "Name"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListSecurityConfigurations() {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("SecurityConfigurations");
        for (SecurityConfiguration sc : service.listSecurityConfigurations()) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Name", sc.getName());
            node.put("CreationDateTime", epoch(sc.getCreationDateTime()));
            arr.add(node);
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Tags ────────────────────────────

    private Response handleAddTags(JsonNode request) {
        service.addTags(text(request, "ResourceId"), parseTags(request.path("Tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleRemoveTags(JsonNode request) {
        service.removeTags(text(request, "ResourceId"), stringList(request.path("TagKeys")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Builders ────────────────────────────

    private ObjectNode clusterNode(EmrCluster c) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", c.getId());
        node.put("Name", c.getName());
        node.set("Status", clusterStatusNode(c));
        setRawJson(node, "Ec2InstanceAttributes", c.getEc2Attributes());
        if (c.getInstanceCollectionType() != null) {
            node.put("InstanceCollectionType", c.getInstanceCollectionType());
        }
        if (c.getLogUri() != null) {
            node.put("LogUri", c.getLogUri());
        }
        if (c.getReleaseLabel() != null) {
            node.put("ReleaseLabel", c.getReleaseLabel());
        }
        node.put("AutoTerminate", c.isAutoTerminate());
        node.put("TerminationProtected", c.isTerminationProtected());
        node.put("UnhealthyNodeReplacement", c.isUnhealthyNodeReplacement());
        node.put("VisibleToAllUsers", c.isVisibleToAllUsers());
        setRawJson(node, "Applications", c.getApplications());
        setRawJson(node, "Configurations", c.getConfigurations());
        node.set("Tags", tagArray(c.getTags()));
        if (c.getServiceRole() != null) {
            node.put("ServiceRole", c.getServiceRole());
        }
        node.put("NormalizedInstanceHours", c.getNormalizedInstanceHours());
        if (c.getMasterPublicDnsName() != null) {
            node.put("MasterPublicDnsName", c.getMasterPublicDnsName());
        }
        if (c.getAutoScalingRole() != null) {
            node.put("AutoScalingRole", c.getAutoScalingRole());
        }
        if (c.getScaleDownBehavior() != null) {
            node.put("ScaleDownBehavior", c.getScaleDownBehavior());
        }
        if (c.getCustomAmiId() != null) {
            node.put("CustomAmiId", c.getCustomAmiId());
        }
        node.put("EbsRootVolumeSize", c.getEbsRootVolumeSize());
        node.put("StepConcurrencyLevel", c.getStepConcurrencyLevel());
        node.put("ClusterArn", c.getClusterArn());
        return node;
    }

    private ObjectNode clusterSummaryNode(EmrCluster c) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", c.getId());
        node.put("Name", c.getName());
        node.set("Status", clusterStatusNode(c));
        node.put("NormalizedInstanceHours", c.getNormalizedInstanceHours());
        node.put("ClusterArn", c.getClusterArn());
        return node;
    }

    private ObjectNode clusterStatusNode(EmrCluster c) {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("State", c.getState());
        ObjectNode reason = status.putObject("StateChangeReason");
        if (c.getStateChangeReasonCode() != null) {
            reason.put("Code", c.getStateChangeReasonCode());
        }
        if (c.getStateChangeMessage() != null) {
            reason.put("Message", c.getStateChangeMessage());
        }
        ObjectNode timeline = status.putObject("Timeline");
        putEpoch(timeline, "CreationDateTime", c.getCreationDateTime());
        putEpoch(timeline, "ReadyDateTime", c.getReadyDateTime());
        putEpoch(timeline, "EndDateTime", c.getEndDateTime());
        return status;
    }

    private ObjectNode stepNode(EmrStep step) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", step.getId());
        node.put("Name", step.getName());
        ObjectNode config = node.putObject("Config");
        if (step.getJar() != null) {
            config.put("Jar", step.getJar());
        }
        if (step.getMainClass() != null) {
            config.put("MainClass", step.getMainClass());
        }
        ArrayNode args = config.putArray("Args");
        step.getArgs().forEach(args::add);
        ObjectNode props = config.putObject("Properties");
        step.getProperties().forEach(props::put);
        node.put("ActionOnFailure", step.getActionOnFailure());
        ObjectNode status = node.putObject("Status");
        status.put("State", step.getState());
        if (step.getStateChangeReason() != null) {
            status.putObject("StateChangeReason").put("Message", step.getStateChangeReason());
        }
        ObjectNode timeline = status.putObject("Timeline");
        putEpoch(timeline, "CreationDateTime", step.getCreationDateTime());
        putEpoch(timeline, "StartDateTime", step.getStartDateTime());
        putEpoch(timeline, "EndDateTime", step.getEndDateTime());
        return node;
    }

    private ObjectNode instanceGroupNode(EmrInstanceGroup g) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", g.getId());
        if (g.getName() != null) {
            node.put("Name", g.getName());
        }
        node.put("InstanceGroupType", g.getInstanceGroupType());
        node.put("InstanceType", g.getInstanceType());
        if (g.getMarket() != null) {
            node.put("Market", g.getMarket());
        }
        if (g.getBidPrice() != null) {
            node.put("BidPrice", g.getBidPrice());
        }
        node.put("RequestedInstanceCount", g.getRequestedInstanceCount());
        node.put("RunningInstanceCount", g.getRunningInstanceCount());
        node.putObject("Status").put("State", g.getState());
        return node;
    }

    private ObjectNode instanceFleetNode(EmrInstanceFleet f) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", f.getId());
        if (f.getName() != null) {
            node.put("Name", f.getName());
        }
        node.put("InstanceFleetType", f.getInstanceFleetType());
        node.put("TargetOnDemandCapacity", f.getTargetOnDemandCapacity());
        node.put("TargetSpotCapacity", f.getTargetSpotCapacity());
        node.put("ProvisionedOnDemandCapacity", f.getProvisionedOnDemandCapacity());
        node.put("ProvisionedSpotCapacity", f.getProvisionedSpotCapacity());
        node.putObject("Status").put("State", f.getState());
        return node;
    }

    private ObjectNode syntheticInstanceNode(EmrCluster cluster, EmrInstanceGroup group, int index) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", "ci-" + index + cluster.getId());
        node.put("Ec2InstanceId", "i-" + String.format("%017d", index));
        node.put("PublicDnsName", "ec2-203-0-113-" + index + ".compute-1.amazonaws.com");
        node.put("PrivateDnsName", "ip-10-0-0-" + index + ".ec2.internal");
        node.put("PrivateIpAddress", "10.0.0." + index);
        node.put("InstanceGroupId", group.getId());
        if (group.getMarket() != null) {
            node.put("Market", group.getMarket());
        }
        node.put("InstanceType", group.getInstanceType());
        node.putObject("Status").put("State", "RUNNING");
        return node;
    }

    private ArrayNode tagArray(Map<String, String> tags) {
        ArrayNode out = objectMapper.createArrayNode();
        tags.forEach((k, v) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("Key", k);
            tag.put("Value", v);
            out.add(tag);
        });
        return out;
    }

    // ──────────────────────────── Parsing ────────────────────────────

    private List<EmrStep> parseSteps(JsonNode stepsNode) {
        List<EmrStep> steps = new ArrayList<>();
        if (!stepsNode.isArray()) {
            return steps;
        }
        for (JsonNode s : stepsNode) {
            EmrStep step = new EmrStep();
            step.setName(s.path("Name").asText(null));
            step.setActionOnFailure(s.path("ActionOnFailure").asText(null));
            step.setExecutionRoleArn(s.path("ExecutionRoleArn").asText(null));
            JsonNode jar = s.path("HadoopJarStep");
            step.setJar(jar.path("Jar").asText(null));
            step.setMainClass(jar.path("MainClass").asText(null));
            step.setArgs(stringList(jar.path("Args")));
            JsonNode props = jar.path("Properties");
            if (props.isArray()) {
                Map<String, String> map = new LinkedHashMap<>();
                props.forEach(p -> map.put(p.path("Key").asText(), p.path("Value").asText()));
                step.setProperties(map);
            }
            steps.add(step);
        }
        return steps;
    }

    private List<EmrInstanceGroup> parseInstanceGroups(JsonNode groupsNode) {
        List<EmrInstanceGroup> groups = new ArrayList<>();
        if (!groupsNode.isArray()) {
            return groups;
        }
        for (JsonNode g : groupsNode) {
            EmrInstanceGroup group = new EmrInstanceGroup();
            group.setName(g.path("Name").asText(null));
            group.setInstanceGroupType(g.path("InstanceRole").asText(null));
            group.setInstanceType(g.path("InstanceType").asText(null));
            group.setMarket(g.path("Market").asText(null));
            group.setBidPrice(g.path("BidPrice").asText(null));
            group.setRequestedInstanceCount(g.path("InstanceCount").asInt(0));
            groups.add(group);
        }
        return groups;
    }

    private List<EmrInstanceFleet> parseInstanceFleets(JsonNode fleetsNode) {
        List<EmrInstanceFleet> fleets = new ArrayList<>();
        if (!fleetsNode.isArray()) {
            return fleets;
        }
        for (JsonNode f : fleetsNode) {
            fleets.add(parseInstanceFleet(f));
        }
        return fleets;
    }

    private EmrInstanceFleet parseInstanceFleet(JsonNode f) {
        EmrInstanceFleet fleet = new EmrInstanceFleet();
        fleet.setName(f.path("Name").asText(null));
        fleet.setInstanceFleetType(f.path("InstanceFleetType").asText(null));
        fleet.setTargetOnDemandCapacity(f.path("TargetOnDemandCapacity").asInt(0));
        fleet.setTargetSpotCapacity(f.path("TargetSpotCapacity").asInt(0));
        return fleet;
    }

    private String buildEc2Attributes(JsonNode instances) {
        ObjectNode ec2 = objectMapper.createObjectNode();
        copyText(instances, "Ec2KeyName", ec2, "Ec2KeyName");
        copyText(instances, "Ec2SubnetId", ec2, "Ec2SubnetId");
        copyText(instances, "EmrManagedMasterSecurityGroup", ec2, "EmrManagedMasterSecurityGroup");
        copyText(instances, "EmrManagedSlaveSecurityGroup", ec2, "EmrManagedSlaveSecurityGroup");
        copyText(instances, "ServiceAccessSecurityGroup", ec2, "ServiceAccessSecurityGroup");
        return ec2.isEmpty() ? null : ec2.toString();
    }

    private void copyText(JsonNode src, String srcField, ObjectNode dst, String dstField) {
        JsonNode v = src.path(srcField);
        if (!v.isMissingNode() && !v.isNull() && !v.asText().isEmpty()) {
            dst.put(dstField, v.asText());
        }
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = tag.path("Key").asText(null);
                if (key != null) {
                    tags.put(key, tag.path("Value").asText(null));
                }
            }
        }
        return tags;
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    private String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private String rawArray(JsonNode node) {
        return (node != null && node.isArray() && !node.isEmpty()) ? node.toString() : null;
    }

    private void setRawJson(ObjectNode target, String field, String rawJson) {
        if (rawJson == null) {
            return;
        }
        try {
            target.set(field, objectMapper.readTree(rawJson));
        } catch (Exception e) {
            LOG.warnv("Failed to parse stored {0} JSON; field omitted. Value: {1}", field, rawJson);
        }
    }

    private long epoch(Instant instant) {
        return instant == null ? 0L : instant.getEpochSecond();
    }

    private void putEpoch(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            node.put(field, instant.getEpochSecond());
        }
    }
}
