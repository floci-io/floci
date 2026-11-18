package io.floci.aws.services.emr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsJson11Controller;
import io.floci.aws.core.common.AwsJsonTimestamp;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.common.SparkConnectEndpoint;
import io.floci.aws.services.emr.model.EmrBlockPublicAccess;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrErrorDetail;
import io.floci.aws.services.emr.model.EmrInstanceFleet;
import io.floci.aws.services.emr.model.EmrInstanceGroup;
import io.floci.aws.services.emr.model.EmrNotebookExecution;
import io.floci.aws.services.emr.model.EmrPersistentAppUi;
import io.floci.aws.services.emr.model.EmrSession;
import io.floci.aws.services.emr.model.EmrStep;
import io.floci.aws.services.emr.model.EmrStudio;
import io.floci.aws.services.emr.model.EmrStudioSessionMapping;
import io.floci.aws.services.emr.model.SecurityConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    /** "There is a limit of 25 volumes per instance." (Amazon EMR Management Guide, Instance storage) */
    private static final int EBS_VOLUMES_PER_INSTANCE = 25;
    // ListInstances "Provides information for all active Amazon EC2 instances and Amazon EC2 instances terminated in the
    // last 30 days, up to a maximum of 2,000": that many to a call, the rest after its Marker.
    static final int INSTANCES_PER_PAGE = 2000;

    private final EmrService service;
    private final EmrStudioService studios;
    private final EmrNotebookExecutionService notebookExecutions;
    private final EmrAppUiService appUis;
    private final ObjectMapper objectMapper;

    @Inject
    public EmrHandler(EmrService service, EmrStudioService studios, EmrNotebookExecutionService notebookExecutions,
                      EmrAppUiService appUis, ObjectMapper objectMapper) {
        this.service = service;
        this.studios = studios;
        this.notebookExecutions = notebookExecutions;
        this.appUis = appUis;
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
                case "StartSession" -> handleStartSession(request);
                case "GetSession" -> handleGetSession(request);
                case "ListSessions" -> handleListSessions(request);
                case "TerminateSession" -> handleTerminateSession(request);
                case "GetSessionEndpoint" -> handleGetSessionEndpoint(request);
                case "CreateStudio" -> handleCreateStudio(request);
                case "DescribeStudio" -> handleDescribeStudio(request);
                case "ListStudios" -> handleListStudios(request);
                case "UpdateStudio" -> handleUpdateStudio(request);
                case "DeleteStudio" -> handleDeleteStudio(request);
                case "CreateStudioSessionMapping" -> handleCreateStudioSessionMapping(request);
                case "GetStudioSessionMapping" -> handleGetStudioSessionMapping(request);
                case "ListStudioSessionMappings" -> handleListStudioSessionMappings(request);
                case "UpdateStudioSessionMapping" -> handleUpdateStudioSessionMapping(request);
                case "DeleteStudioSessionMapping" -> handleDeleteStudioSessionMapping(request);
                case "StartNotebookExecution" -> handleStartNotebookExecution(request);
                case "DescribeNotebookExecution" -> handleDescribeNotebookExecution(request);
                case "StopNotebookExecution" -> handleStopNotebookExecution(request);
                case "ListNotebookExecutions" -> handleListNotebookExecutions(request);
                case "CreatePersistentAppUI" -> handleCreatePersistentAppUi(request);
                case "DescribePersistentAppUI" -> handleDescribePersistentAppUi(request);
                case "GetPersistentAppUIPresignedURL" -> handleGetPersistentAppUiPresignedUrl(request);
                case "GetOnClusterAppUIPresignedURL" -> handleGetOnClusterAppUiPresignedUrl(request);
                case "GetClusterSessionCredentials" -> handleGetClusterSessionCredentials(request);
                case "AddInstanceGroups" -> handleAddInstanceGroups(request);
                case "ListInstanceGroups" -> handleListInstanceGroups(request);
                case "AddInstanceFleet" -> handleAddInstanceFleet(request);
                case "ListInstanceFleets" -> handleListInstanceFleets(request);
                case "ListInstances" -> handleListInstances(request);
                case "CreateSecurityConfiguration" -> handleCreateSecurityConfiguration(request, region);
                case "DescribeSecurityConfiguration" -> handleDescribeSecurityConfiguration(request, region);
                case "DeleteSecurityConfiguration" -> handleDeleteSecurityConfiguration(request, region);
                case "ListSecurityConfigurations" -> handleListSecurityConfigurations(region);
                case "AddTags" -> handleAddTags(request);
                case "RemoveTags" -> handleRemoveTags(request);
                case "ModifyInstanceGroups" -> handleModifyInstanceGroups(request, region);
                case "ModifyInstanceFleet" -> handleModifyInstanceFleet(request);
                case "ListBootstrapActions" -> handleListBootstrapActions(request);
                case "PutManagedScalingPolicy" -> handlePutManagedScalingPolicy(request);
                case "GetManagedScalingPolicy" -> handleGetManagedScalingPolicy(request);
                case "RemoveManagedScalingPolicy" -> handleRemoveManagedScalingPolicy(request);
                case "PutAutoTerminationPolicy" -> handlePutAutoTerminationPolicy(request);
                case "GetAutoTerminationPolicy" -> handleGetAutoTerminationPolicy(request);
                case "RemoveAutoTerminationPolicy" -> handleRemoveAutoTerminationPolicy(request);
                case "PutAutoScalingPolicy" -> handlePutAutoScalingPolicy(request);
                case "RemoveAutoScalingPolicy" -> handleRemoveAutoScalingPolicy(request);
                case "ListReleaseLabels" -> handleListReleaseLabels(request);
                case "DescribeReleaseLabel" -> handleDescribeReleaseLabel(request);
                case "ListSupportedInstanceTypes" -> handleListSupportedInstanceTypes(request);
                case "GetBlockPublicAccessConfiguration" -> handleGetBlockPublicAccessConfiguration(region);
                case "PutBlockPublicAccessConfiguration" -> handlePutBlockPublicAccessConfiguration(request, region);
                default -> Response.status(400)
                        .entity(new AwsErrorResponse("InvalidRequestException",
                                "Operation " + action + " is not supported."))
                        .build();
            };
        } catch (AwsException e) {
            if (e.getExtendedData() != null) {
                // Members an error adds to its message, such as InvalidRequestException's ErrorCode.
                return JsonErrorResponseUtils.createErrorResponse(e);
            }
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
        JsonNode bootstrapActions = request.path("BootstrapActions");
        if (!bootstrapActions.isMissingNode() && !bootstrapActions.isNull()) {
            EmrService.validateBootstrapActions(bootstrapActions);
            cluster.setBootstrapActions(rawArray(bootstrapActions));
        }
        JsonNode managedScaling = request.path("ManagedScalingPolicy");
        if (!managedScaling.isMissingNode() && !managedScaling.isNull()) {
            EmrService.validateManagedScalingPolicy(managedScaling);
            cluster.setManagedScalingPolicy(managedScaling.toString());
        }
        JsonNode autoTermination = request.path("AutoTerminationPolicy");
        if (!autoTermination.isMissingNode() && !autoTermination.isNull()) {
            EmrService.validateAutoTerminationPolicy(autoTermination);
            cluster.setAutoTerminationPolicy(autoTermination.toString());
        }

        JsonNode instances = request.path("Instances");
        cluster.setKeepJobFlowAliveWhenNoSteps(instances.path("KeepJobFlowAliveWhenNoSteps").asBoolean(false));
        cluster.setTerminationProtected(instances.path("TerminationProtected").asBoolean(false));
        cluster.setUnhealthyNodeReplacement(instances.path("UnhealthyNodeReplacement").asBoolean(false));
        cluster.setSessionEnabled(request.path("SessionEnabled").asBoolean(false));
        cluster.setEc2Attributes(buildEc2Attributes(instances, region));
        cluster.setInstanceGroups(parseInstanceGroups(instances.path("InstanceGroups")));
        cluster.setInstanceFleets(parseInstanceFleets(instances.path("InstanceFleets")));
        cluster.setSteps(parseSteps(request.path("Steps"), text(request, "StepExecutionRoleArn")));

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
        Instant createdAfter = AwsJsonTimestamp.epochSeconds(request, "CreatedAfter", "InvalidRequestException");
        Instant createdBefore = AwsJsonTimestamp.epochSeconds(request, "CreatedBefore", "InvalidRequestException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Clusters");
        for (EmrCluster c : service.listClusters(region, states, createdAfter, createdBefore)) {
            arr.add(clusterSummaryNode(c));
        }
        return Response.ok(response).build();
    }

    private Response handleTerminateJobFlows(JsonNode request) {
        requireMembers(request, "JobFlowIds");
        service.terminateJobFlows(stringList(request.path("JobFlowIds")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetTerminationProtection(JsonNode request) {
        requireMembers(request, "JobFlowIds", "TerminationProtected");
        service.setTerminationProtection(stringList(request.path("JobFlowIds")),
                request.path("TerminationProtected").asBoolean(false));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetVisibleToAllUsers(JsonNode request) {
        requireMembers(request, "JobFlowIds", "VisibleToAllUsers");
        service.setVisibleToAllUsers(stringList(request.path("JobFlowIds")),
                request.path("VisibleToAllUsers").asBoolean(true));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetKeepAlive(JsonNode request) {
        requireMembers(request, "JobFlowIds", "KeepJobFlowAliveWhenNoSteps");
        service.setKeepJobFlowAliveWhenNoSteps(stringList(request.path("JobFlowIds")),
                request.path("KeepJobFlowAliveWhenNoSteps").asBoolean(false));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSetUnhealthyNodeReplacement(JsonNode request) {
        requireMembers(request, "JobFlowIds", "UnhealthyNodeReplacement");
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
        List<String> ids = service.addJobFlowSteps(text(request, "JobFlowId"),
                parseSteps(request.path("Steps"), text(request, "ExecutionRoleArn")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("StepIds");
        ids.forEach(arr::add);
        return Response.ok(response).build();
    }

    private Response handleDescribeStep(JsonNode request) {
        EmrStep step = service.describeStep(text(request, "ClusterId"), text(request, "StepId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Step", stepNode(step, true));
        return Response.ok(response).build();
    }

    private Response handleListSteps(JsonNode request) {
        List<EmrStep> steps = service.listSteps(text(request, "ClusterId"),
                stringList(request.path("StepStates")), stringList(request.path("StepIds")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("Steps");
        for (EmrStep step : steps) {
            arr.add(stepNode(step, false));
        }
        return Response.ok(response).build();
    }

    private Response handleCancelSteps(JsonNode request) {
        requireMembers(request, "StepIds");
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

    // ──────────────────────────── Sessions ────────────────────────────

    private Response handleStartSession(JsonNode request) {
        EmrSessions.Request start = new EmrSessions.Request(string(request, "Name"),
                string(request, "ExecutionRoleArn"), configurationList(request.path("EngineConfigurations"),
                "EngineConfigurations"), monitoringConfiguration(request.path("MonitoringConfiguration")),
                longOrNull(request.path("SessionIdleTimeoutInMinutes"), "SessionIdleTimeoutInMinutes"),
                string(request, "ClientRequestToken"), tagList(request.path("Tags"), "Tags"));
        EmrSession session = service.startSession(string(request, "ClusterId"), start);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Id", session.getId());
        response.put("ClusterId", session.getClusterId());
        response.put("Arn", session.getArn());
        response.put("AccountId", session.getAccountId());
        response.put("State", session.getState());
        return Response.ok(response).build();
    }

    private Response handleGetSession(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Session", sessionNode(service.getSession(string(request, "ClusterId"),
                string(request, "SessionId"))));
        return Response.ok(response).build();
    }

    private Response handleListSessions(JsonNode request) {
        PaginatedResult<EmrSession> page = service.listSessions(string(request, "ClusterId"),
                strings(request.path("SessionStates"), "SessionStates"), intOrNull(request.path("MaxResults")),
                string(request, "NextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode sessions = response.putArray("Sessions");
        page.items().forEach(session -> sessions.add(sessionNode(session)));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleTerminateSession(JsonNode request) {
        EmrSession session = service.terminateSession(string(request, "ClusterId"), string(request, "SessionId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ClusterId", session.getClusterId());
        response.put("SessionId", session.getId());
        response.put("State", session.getState());
        return Response.ok(response).build();
    }

    private Response handleGetSessionEndpoint(JsonNode request) {
        SparkConnectEndpoint endpoint = service.sessionEndpoint(string(request, "ClusterId"),
                string(request, "SessionId"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Endpoint", endpoint.url());
        response.put("AuthToken", endpoint.authToken());
        putEpoch(response, "AuthTokenExpirationTime", endpoint.authTokenExpiresAt());
        return Response.ok(response).build();
    }

    private ObjectNode sessionNode(EmrSession session) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", session.getId());
        node.put("ClusterId", session.getClusterId());
        if (session.getName() != null) {
            node.put("Name", session.getName());
        }
        node.put("Arn", session.getArn());
        node.put("State", session.getState());
        if (session.getStateChangeReason() != null) {
            node.put("StateChangeReason", session.getStateChangeReason());
        }
        node.put("ReleaseLabel", session.getReleaseLabel());
        if (session.getExecutionRoleArn() != null) {
            node.put("ExecutionRoleArn", session.getExecutionRoleArn());
        }
        node.put("AccountId", session.getAccountId());
        putEpoch(node, "CreatedAt", session.getCreatedAt());
        putEpoch(node, "UpdatedAt", session.getUpdatedAt());
        putEpoch(node, "StartedAt", session.getStartedAt());
        putEpoch(node, "EndedAt", session.getEndedAt());
        putEpoch(node, "IdleSince", session.getIdleSince());
        setRawJson(node, "EngineConfigurations", session.getEngineConfigurations());
        setRawJson(node, "MonitoringConfiguration", session.getMonitoringConfiguration());
        node.put("SessionIdleTimeoutInMinutes", session.getSessionIdleTimeoutInMinutes());
        node.set("Tags", tagArray(session.getTags()));
        return node;
    }

    /**
     * A member a string where the model has one; another JSON type is refused with SerializationException, as an
     * awsJson1_1 service refuses a member of the wrong type (Smithy's malformed-request tests), in Floci's own wording.
     */
    private static String string(JsonNode request, String member) {
        JsonNode value = request.path(member);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw malformed(member + " must be a string");
        }
        return value.asText();
    }

    private static Long longOrNull(JsonNode value, String member) {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw malformed(member + " must be an integer");
        }
        return value.asLong();
    }

    /**
     * StartSession's SessionMonitoringConfiguration, kept as its raw JSON; null when it is not there or empty. Each of
     * its S3, CloudWatch and managed logging configurations is a structure of an {@code Enabled} boolean, strings, and
     * {@code LogTypes}, a map of lists of strings.
     */
    private static String monitoringConfiguration(JsonNode value) {
        String member = "MonitoringConfiguration";
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        requireStructure(value, member);
        for (String logging : List.of("S3LoggingConfiguration", "CloudWatchLoggingConfiguration",
                "ManagedLoggingConfiguration")) {
            JsonNode configuration = value.path(logging);
            if (configuration.isMissingNode() || configuration.isNull()) {
                continue;
            }
            String path = member + "." + logging;
            requireStructure(configuration, path);
            configuration.properties().forEach(entry -> {
                JsonNode field = entry.getValue();
                if (field.isNull()) {
                    return;
                }
                // Not a switch on the member names: the action tables read a string case here as an EMR action.
                if ("Enabled".equals(entry.getKey())) {
                    if (!field.isBoolean()) {
                        throw malformed(path + ".Enabled must be a boolean");
                    }
                } else if ("LogTypes".equals(entry.getKey())) {
                    requireStructure(field, path + ".LogTypes");
                    field.forEach(types -> strings(types, path + ".LogTypes"));
                } else if (!field.isTextual()) {
                    throw malformed(path + "." + entry.getKey() + " must be a string");
                }
            });
        }
        return value.isEmpty() ? null : value.toString();
    }

    private static void requireStructure(JsonNode value, String member) {
        if (!value.isObject()) {
            throw malformed(member + " must be a structure");
        }
    }

    // A list of strings; null when it is not there.
    private static List<String> strings(JsonNode value, String member) {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isArray()) {
            throw malformed(member + " must be a list of strings");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isTextual()) {
                throw malformed(member + " must be a list of strings");
            }
            values.add(element.asText());
        }
        return values;
    }

    /**
     * A ConfigurationList member, kept as its raw JSON; null when it is not there or empty. Each Configuration's
     * {@code Classification} is a string, its {@code Properties} a map of strings, and its {@code Configurations}
     * a ConfigurationList in turn.
     */
    private static String configurationList(JsonNode value, String member) {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        checkConfigurations(value, member);
        return value.isEmpty() ? null : value.toString();
    }

    private static void checkConfigurations(JsonNode value, String member) {
        if (!value.isArray()) {
            throw malformed(member + " must be a list of configurations");
        }
        for (JsonNode configuration : value) {
            if (!configuration.isObject()) {
                throw malformed(member + " must be a list of configurations");
            }
            JsonNode classification = configuration.path("Classification");
            if (!classification.isMissingNode() && !classification.isNull() && !classification.isTextual()) {
                throw malformed(member + "'s Classification must be a string");
            }
            JsonNode properties = configuration.path("Properties");
            if (!properties.isMissingNode() && !properties.isNull()) {
                if (!properties.isObject()) {
                    throw malformed(member + "'s Properties must be a map of strings");
                }
                properties.forEach(property -> {
                    if (!property.isTextual()) {
                        throw malformed(member + "'s Properties must be a map of strings");
                    }
                });
            }
            JsonNode nested = configuration.path("Configurations");
            if (!nested.isMissingNode() && !nested.isNull()) {
                checkConfigurations(nested, member);
            }
        }
    }

    /**
     * A TagList member: each Tag's Key and Value strings. A tag without its Key, "the minimum required information for
     * a valid tag", is refused, in Floci's own wording.
     */
    private static Map<String, String> tagList(JsonNode value, String member) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (value.isMissingNode() || value.isNull()) {
            return tags;
        }
        if (!value.isArray()) {
            throw malformed(member + " must be a list of tags");
        }
        for (JsonNode tag : value) {
            if (!tag.isObject()) {
                throw malformed(member + " must be a list of tags");
            }
            String key = string(tag, "Key");
            if (key == null) {
                throw new AwsException("InvalidRequestException", "A tag in " + member + " has no Key.", 400);
            }
            tags.put(key, string(tag, "Value"));
        }
        return tags;
    }

    private static AwsException malformed(String message) {
        return new AwsException("SerializationException", message, 400);
    }

    // ──────────────────────────── Studios ────────────────────────────

    private Response handleCreateStudio(JsonNode request) {
        EmrStudio studio = new EmrStudio();
        studio.setName(string(request, "Name"));
        studio.setDescription(string(request, "Description"));
        studio.setAuthMode(string(request, "AuthMode"));
        studio.setVpcId(string(request, "VpcId"));
        studio.setSubnetIds(strings(request.path("SubnetIds"), "SubnetIds"));
        studio.setServiceRole(string(request, "ServiceRole"));
        studio.setUserRole(string(request, "UserRole"));
        studio.setWorkspaceSecurityGroupId(string(request, "WorkspaceSecurityGroupId"));
        studio.setEngineSecurityGroupId(string(request, "EngineSecurityGroupId"));
        studio.setDefaultS3Location(string(request, "DefaultS3Location"));
        studio.setIdpAuthUrl(string(request, "IdpAuthUrl"));
        studio.setIdpRelayStateParameterName(string(request, "IdpRelayStateParameterName"));
        studio.setTags(tagList(request.path("Tags"), "Tags"));
        studio.setTrustedIdentityPropagationEnabled(bool(request, "TrustedIdentityPropagationEnabled"));
        studio.setIdcUserAssignment(string(request, "IdcUserAssignment"));
        studio.setIdcInstanceArn(string(request, "IdcInstanceArn"));
        studio.setEncryptionKeyArn(string(request, "EncryptionKeyArn"));
        EmrStudio created = studios.createStudio(studio);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("StudioId", created.getStudioId());
        response.put("Url", created.getUrl());
        return Response.ok(response).build();
    }

    private Response handleDescribeStudio(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Studio", studioNode(studios.describeStudio(string(request, "StudioId"))));
        return Response.ok(response).build();
    }

    private Response handleListStudios(JsonNode request) {
        PaginatedResult<EmrStudio> page = studios.listStudios(string(request, "Marker"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("Studios");
        for (EmrStudio studio : page.items()) {
            ObjectNode summary = summaries.addObject();
            summary.put("StudioId", studio.getStudioId());
            summary.put("Name", studio.getName());
            summary.put("VpcId", studio.getVpcId());
            if (studio.getDescription() != null) {
                summary.put("Description", studio.getDescription());
            }
            summary.put("Url", studio.getUrl());
            summary.put("AuthMode", studio.getAuthMode());
            putEpoch(summary, "CreationTime", studio.getCreationTime());
        }
        if (page.nextToken() != null) {
            response.put("Marker", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateStudio(JsonNode request) {
        studios.updateStudio(string(request, "StudioId"), string(request, "Name"), string(request, "Description"),
                strings(request.path("SubnetIds"), "SubnetIds"), string(request, "DefaultS3Location"),
                string(request, "EncryptionKeyArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteStudio(JsonNode request) {
        studios.deleteStudio(string(request, "StudioId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleCreateStudioSessionMapping(JsonNode request) {
        studios.createSessionMapping(string(request, "StudioId"), identity(request),
                string(request, "SessionPolicyArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetStudioSessionMapping(JsonNode request) {
        String studioId = string(request, "StudioId");
        EmrStudioSessionMapping mapping = studios.getSessionMapping(studioId, identity(request));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode detail = sessionMappingNode(studioId, mapping);
        putEpoch(detail, "LastModifiedTime", mapping.getLastModifiedTime());
        response.set("SessionMapping", detail);
        return Response.ok(response).build();
    }

    private Response handleListStudioSessionMappings(JsonNode request) {
        PaginatedResult<EmrStudioService.StudioMapping> page = studios.listSessionMappings(
                string(request, "StudioId"), string(request, "IdentityType"), string(request, "Marker"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode mappings = response.putArray("SessionMappings");
        page.items().forEach(item -> mappings.add(sessionMappingNode(item.studioId(), item.mapping())));
        if (page.nextToken() != null) {
            response.put("Marker", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleUpdateStudioSessionMapping(JsonNode request) {
        studios.updateSessionMapping(string(request, "StudioId"), identity(request),
                string(request, "SessionPolicyArn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteStudioSessionMapping(JsonNode request) {
        studios.deleteSessionMapping(string(request, "StudioId"), identity(request));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private static EmrStudioService.Identity identity(JsonNode request) {
        return new EmrStudioService.Identity(string(request, "IdentityType"), string(request, "IdentityId"),
                string(request, "IdentityName"));
    }

    private ObjectNode studioNode(EmrStudio studio) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("StudioId", studio.getStudioId());
        node.put("StudioArn", studio.getStudioArn());
        node.put("Name", studio.getName());
        putIfSet(node, "Description", studio.getDescription());
        node.put("AuthMode", studio.getAuthMode());
        node.put("VpcId", studio.getVpcId());
        ArrayNode subnets = node.putArray("SubnetIds");
        studio.getSubnetIds().forEach(subnets::add);
        node.put("ServiceRole", studio.getServiceRole());
        putIfSet(node, "UserRole", studio.getUserRole());
        node.put("WorkspaceSecurityGroupId", studio.getWorkspaceSecurityGroupId());
        node.put("EngineSecurityGroupId", studio.getEngineSecurityGroupId());
        node.put("Url", studio.getUrl());
        putEpoch(node, "CreationTime", studio.getCreationTime());
        node.put("DefaultS3Location", studio.getDefaultS3Location());
        putIfSet(node, "IdpAuthUrl", studio.getIdpAuthUrl());
        putIfSet(node, "IdpRelayStateParameterName", studio.getIdpRelayStateParameterName());
        node.set("Tags", tagArray(studio.getTags()));
        putIfSet(node, "IdcInstanceArn", studio.getIdcInstanceArn());
        if (studio.getTrustedIdentityPropagationEnabled() != null) {
            node.put("TrustedIdentityPropagationEnabled", studio.getTrustedIdentityPropagationEnabled());
        }
        putIfSet(node, "IdcUserAssignment", studio.getIdcUserAssignment());
        putIfSet(node, "EncryptionKeyArn", studio.getEncryptionKeyArn());
        return node;
    }

    // A SessionMappingSummary; GetStudioSessionMapping's detail adds its LastModifiedTime.
    private ObjectNode sessionMappingNode(String studioId, EmrStudioSessionMapping mapping) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("StudioId", studioId);
        node.put("IdentityId", mapping.getIdentityId());
        putIfSet(node, "IdentityName", mapping.getIdentityName());
        node.put("IdentityType", mapping.getIdentityType());
        node.put("SessionPolicyArn", mapping.getSessionPolicyArn());
        putEpoch(node, "CreationTime", mapping.getCreationTime());
        return node;
    }

    private static void putIfSet(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static Boolean bool(JsonNode request, String member) {
        JsonNode value = request.path(member);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isBoolean()) {
            throw malformed(member + " must be a boolean");
        }
        return value.asBoolean();
    }

    // ──────────────────────────── Notebook executions ────────────────────────────

    private Response handleStartNotebookExecution(JsonNode request) {
        EmrNotebookExecution execution = new EmrNotebookExecution();
        execution.setEditorId(string(request, "EditorId"));
        execution.setRelativePath(string(request, "RelativePath"));
        execution.setName(string(request, "NotebookExecutionName"));
        execution.setNotebookParams(string(request, "NotebookParams"));
        JsonNode engine = request.path("ExecutionEngine");
        if (!engine.isMissingNode() && !engine.isNull()) {
            requireStructure(engine, "ExecutionEngine");
            execution.setEngineId(string(engine, "Id"));
            execution.setEngineType(string(engine, "Type"));
            execution.setMasterInstanceSecurityGroupId(string(engine, "MasterInstanceSecurityGroupId"));
            execution.setExecutionRoleArn(string(engine, "ExecutionRoleArn"));
        }
        execution.setServiceRole(string(request, "ServiceRole"));
        execution.setNotebookInstanceSecurityGroupId(string(request, "NotebookInstanceSecurityGroupId"));
        execution.setTags(tagList(request.path("Tags"), "Tags"));
        JsonNode input = request.path("NotebookS3Location");
        if (!input.isMissingNode() && !input.isNull()) {
            requireStructure(input, "NotebookS3Location");
            execution.setNotebookS3Bucket(string(input, "Bucket"));
            execution.setNotebookS3Key(string(input, "Key"));
        }
        JsonNode output = request.path("OutputNotebookS3Location");
        if (!output.isMissingNode() && !output.isNull()) {
            requireStructure(output, "OutputNotebookS3Location");
            execution.setOutputNotebookS3Bucket(string(output, "Bucket"));
            execution.setOutputNotebookS3Key(string(output, "Key"));
        }
        execution.setOutputNotebookFormat(string(request, "OutputNotebookFormat"));
        execution.setEnvironmentVariables(stringMap(request.path("EnvironmentVariables"), "EnvironmentVariables"));
        EmrNotebookExecution started = notebookExecutions.start(execution);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("NotebookExecutionId", started.getId());
        return Response.ok(response).build();
    }

    private Response handleDescribeNotebookExecution(JsonNode request) {
        EmrNotebookExecution execution = notebookExecutions.describe(string(request, "NotebookExecutionId"));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("NotebookExecutionId", execution.getId());
        putIfSet(node, "EditorId", execution.getEditorId());
        ObjectNode engine = node.putObject("ExecutionEngine");
        engine.put("Id", execution.getEngineId());
        engine.put("Type", execution.getEngineType());
        putIfSet(engine, "MasterInstanceSecurityGroupId", execution.getMasterInstanceSecurityGroupId());
        putIfSet(engine, "ExecutionRoleArn", execution.getExecutionRoleArn());
        putIfSet(node, "NotebookExecutionName", execution.getName());
        putIfSet(node, "NotebookParams", execution.getNotebookParams());
        node.put("Status", execution.getStatus());
        putEpoch(node, "StartTime", execution.getStartTime());
        putEpoch(node, "EndTime", execution.getEndTime());
        node.put("Arn", execution.getArn());
        putIfSet(node, "LastStateChangeReason", execution.getLastStateChangeReason());
        putIfSet(node, "NotebookInstanceSecurityGroupId", execution.getNotebookInstanceSecurityGroupId());
        node.set("Tags", tagArray(execution.getTags()));
        putS3Location(node, "NotebookS3Location", execution.getNotebookS3Bucket(), execution.getNotebookS3Key());
        putS3Location(node, "OutputNotebookS3Location", execution.getOutputNotebookS3Bucket(),
                execution.getOutputNotebookS3Key());
        putIfSet(node, "OutputNotebookFormat", execution.getOutputNotebookFormat());
        putIfSet(node, "OutputNotebookURI", execution.getOutputNotebookUri());
        if (execution.getEnvironmentVariables() != null) {
            ObjectNode variables = node.putObject("EnvironmentVariables");
            execution.getEnvironmentVariables().forEach(variables::put);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("NotebookExecution", node);
        return Response.ok(response).build();
    }

    private Response handleStopNotebookExecution(JsonNode request) {
        notebookExecutions.stop(string(request, "NotebookExecutionId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListNotebookExecutions(JsonNode request) {
        PaginatedResult<EmrNotebookExecution> page = notebookExecutions.list(string(request, "EditorId"),
                string(request, "Status"),
                // As ListClusters reads its CreatedAfter and CreatedBefore.
                AwsJsonTimestamp.epochSeconds(request, "From", "InvalidRequestException"),
                AwsJsonTimestamp.epochSeconds(request, "To", "InvalidRequestException"),
                string(request, "ExecutionEngineId"), string(request, "Marker"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("NotebookExecutions");
        for (EmrNotebookExecution execution : page.items()) {
            ObjectNode summary = summaries.addObject();
            summary.put("NotebookExecutionId", execution.getId());
            putIfSet(summary, "EditorId", execution.getEditorId());
            putIfSet(summary, "NotebookExecutionName", execution.getName());
            summary.put("Status", execution.getStatus());
            putEpoch(summary, "StartTime", execution.getStartTime());
            putEpoch(summary, "EndTime", execution.getEndTime());
            putS3Location(summary, "NotebookS3Location", execution.getNotebookS3Bucket(),
                    execution.getNotebookS3Key());
            summary.put("ExecutionEngineId", execution.getEngineId());
        }
        if (page.nextToken() != null) {
            response.put("Marker", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private void putS3Location(ObjectNode node, String field, String bucket, String key) {
        if (bucket != null || key != null) {
            ObjectNode location = node.putObject(field);
            putIfSet(location, "Bucket", bucket);
            putIfSet(location, "Key", key);
        }
    }

    // A map of strings; null when it is not there.
    private static Map<String, String> stringMap(JsonNode value, String member) {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        requireStructure(value, member);
        Map<String, String> values = new LinkedHashMap<>();
        value.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) {
                throw malformed(member + " must be a map of strings");
            }
            values.put(entry.getKey(), entry.getValue().asText());
        });
        return values;
    }

    // ──────────────────────────── Application UIs ────────────────────────────

    private Response handleCreatePersistentAppUi(JsonNode request) {
        JsonNode containers = request.path("EMRContainersConfig");
        if (!containers.isMissingNode() && !containers.isNull()) {
            requireStructure(containers, "EMRContainersConfig");
            string(containers, "JobRunId");
        }
        string(request, "XReferer");
        EmrAppUiService.Created created = appUis.create(string(request, "TargetResourceArn"),
                string(request, "ProfilerType"), tagList(request.path("Tags"), "Tags"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PersistentAppUIId", created.appUi().getId());
        response.put("RuntimeRoleEnabledCluster", created.runtimeRoleEnabledCluster());
        return Response.ok(response).build();
    }

    private Response handleDescribePersistentAppUi(JsonNode request) {
        EmrPersistentAppUi appUi = appUis.describe(string(request, "PersistentAppUIId"));
        ObjectNode node = objectMapper.createObjectNode();
        node.put("PersistentAppUIId", appUi.getId());
        ArrayNode types = node.putArray("PersistentAppUITypeList");
        appUi.getTypes().forEach(types::add);
        node.put("PersistentAppUIStatus", appUi.getStatus());
        node.put("AuthorId", appUi.getAuthorId());
        putEpoch(node, "CreationTime", appUi.getCreationTime());
        putEpoch(node, "LastModifiedTime", appUi.getLastModifiedTime());
        putIfSet(node, "LastStateChangeReason", appUi.getLastStateChangeReason());
        node.set("Tags", tagArray(appUi.getTags()));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("PersistentAppUI", node);
        return Response.ok(response).build();
    }

    private Response handleGetPersistentAppUiPresignedUrl(JsonNode request) {
        bool(request, "AuthProxyCall");
        string(request, "ExecutionRoleArn");
        return presignedUrl(appUis.persistentUrl(string(request, "PersistentAppUIId"),
                string(request, "PersistentAppUIType"), string(request, "ApplicationId")));
    }

    private Response handleGetOnClusterAppUiPresignedUrl(JsonNode request) {
        string(request, "ExecutionRoleArn");
        Boolean dryRun = bool(request, "DryRun");
        return presignedUrl(appUis.onClusterUrl(string(request, "ClusterId"), string(request, "OnClusterAppUIType"),
                string(request, "ApplicationId"), Boolean.TRUE.equals(dryRun)));
    }

    // A presigned URL's answer: whether it is ready, and the URL, none for a dry run.
    private Response presignedUrl(EmrAppUiService.Presigned presigned) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PresignedURLReady", presigned.ready());
        putIfSet(response, "PresignedURL", presigned.url());
        return Response.ok(response).build();
    }

    private Response handleGetClusterSessionCredentials(JsonNode request) {
        EmrService.ClusterSessionCredentials credentials = service.clusterSessionCredentials(
                string(request, "ClusterId"), string(request, "ExecutionRoleArn"));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode usernamePassword = response.putObject("Credentials").putObject("UsernamePassword");
        usernamePassword.put("Username", credentials.username());
        usernamePassword.put("Password", credentials.password());
        putEpoch(response, "ExpiresAt", credentials.expiresAt());
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
        List<String> violations = new ArrayList<>();
        EmrInstanceFleet fleet = parseInstanceFleet(request.path("InstanceFleet"), "instanceFleet", violations);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        requireEbsVolumeLimit(request.path("InstanceFleet"));
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
        boolean terminated = EmrClusterLifecycle.isTerminated(cluster.getState());
        List<String> states = stringList(request.path("InstanceStates"));
        if (terminated && !withinTerminatedInstanceRetention(cluster.getEndDateTime(), Instant.now())) {
            return Response.ok(response).build();
        }
        String groupId = text(request, "InstanceGroupId");
        List<String> groupTypes = stringList(request.path("InstanceGroupTypes"));
        String fleetId = text(request, "InstanceFleetId");
        String fleetType = text(request, "InstanceFleetType");
        // Listed in their groups' or fleets' order, the primary node's group or fleet numbered first, so the primary
        // node keeps its id and address, DescribeCluster's MasterPublicDnsName, while the others resize, as an
        // instance does; any other's follow, each after those before it. Only the page asked for is built, so a
        // group or fleet of any size costs a page. Counted in longs, as a fleet's two capacities add up past an int.
        long skip = instancesMarker(text(request, "Marker"));
        long start = skip;
        int page = 0;
        List<ListedInstances> listed = listedInstances(cluster, terminated);
        ListedInstances primary = listed.stream().filter(set -> set.number() == 0).findFirst().orElse(null);
        long next = primary == null ? 1 : 1 + primary.count();
        for (ListedInstances set : listed) {
            long first = set == primary ? 1 : next;
            if (set != primary) {
                next += set.count();
            }
            if (!set.matches(groupId, groupTypes, fleetId, fleetType)
                    || !states.isEmpty() && !states.contains(set.state())) {
                continue;
            }
            if (skip >= set.count()) {
                skip -= set.count();
                continue;
            }
            for (long ordinal = skip + 1; ordinal <= set.count(); ordinal++) {
                if (page == INSTANCES_PER_PAGE) {
                    response.put("Marker", String.valueOf(start + page));
                    return Response.ok(response).build();
                }
                arr.add(syntheticInstanceNode(cluster, set, first + ordinal - 1, ordinal, terminated));
                page++;
            }
            skip = 0;
        }
        return Response.ok(response).build();
    }

    // How many of a listing's instances its Marker passes, the count the page before it ended at; none without one.
    private static long instancesMarker(String marker) {
        if (marker == null) {
            return 0;
        }
        try {
            long passed = Long.parseLong(marker);
            if (passed >= 0) {
                return passed;
            }
        } catch (NumberFormatException e) {
            // Refused below, as any marker no page gave.
        }
        throw new AwsException("InvalidRequestException", "Invalid pagination token: " + marker, 400);
    }

    /**
     * A cluster's instance groups' and fleets' instances, in the cluster's order. A fleet has one instance for each unit
     * of its capacity, as its nodes do, its on-demand ones first, each of its first instance type.
     */
    static List<ListedInstances> listedInstances(EmrCluster cluster, boolean terminated) {
        List<ListedInstances> listed = new ArrayList<>();
        List<EmrInstanceGroup> groups = cluster.getInstanceGroups();
        for (EmrInstanceGroup group : groups) {
            int count = listedInstanceCount(group, terminated);
            listed.add(new ListedInstances(group.getId(), null, group.getInstanceGroupType(), group.getInstanceType(),
                    EmrService.groupNumber(groups, group), count, group.getMarket(), count,
                    instanceState(group.getState(), beingProvisioned(group), terminated)));
        }
        List<EmrInstanceFleet> fleets = cluster.getInstanceFleets();
        for (EmrInstanceFleet fleet : fleets) {
            // An ended fleet has no capacity provisioned any more, and one being provisioned none yet: its targets,
            // which an ended one can no longer change, say what it ran, or what it is being provisioned with.
            boolean provisioning = beingProvisioned(fleet);
            boolean targets = terminated || provisioning;
            long onDemand = targets ? fleet.getTargetOnDemandCapacity() : fleet.getProvisionedOnDemandCapacity();
            long spot = targets ? fleet.getTargetSpotCapacity() : fleet.getProvisionedSpotCapacity();
            listed.add(new ListedInstances(null, fleet.getId(), fleet.getInstanceFleetType(),
                    fleet.getInstanceTypes().isEmpty() ? null : fleet.getInstanceTypes().getFirst(),
                    EmrService.fleetNumber(fleets, fleet), onDemand + spot, "ON_DEMAND", onDemand,
                    instanceState(fleet.getState(), provisioning, terminated)));
        }
        return listed;
    }

    // The state ListInstances gives the instances of a group or fleet in groupState, InstanceState's: being
    // provisioned, bootstrapped, running, as they still are while their cluster ends, or gone.
    private static String instanceState(String groupState, boolean provisioning, boolean terminated) {
        if (terminated || EmrClusterLifecycle.TERMINATED.equals(groupState)) {
            return "TERMINATED";
        }
        if (provisioning) {
            return EmrClusterLifecycle.PROVISIONING;
        }
        return EmrClusterLifecycle.BOOTSTRAPPING.equals(groupState) ? groupState : "RUNNING";
    }

    /**
     * Whether a group's instances are still being provisioned: while it is PROVISIONING, and while its cluster ends
     * before any of them ran, TERMINATING with none running. Those it asks for stay listed, the instances ListInstances
     * showed being provisioned, until they have ended, with none of them counted as running.
     */
    static boolean beingProvisioned(EmrInstanceGroup group) {
        return EmrClusterLifecycle.PROVISIONING.equals(group.getState())
                || EmrClusterLifecycle.TERMINATING.equals(group.getState()) && group.getRunningInstanceCount() == 0;
    }

    /** Whether a fleet's capacity is still being provisioned, as {@link #beingProvisioned(EmrInstanceGroup)}. */
    static boolean beingProvisioned(EmrInstanceFleet fleet) {
        return EmrClusterLifecycle.PROVISIONING.equals(fleet.getState())
                || EmrClusterLifecycle.TERMINATING.equals(fleet.getState())
                && fleet.getProvisionedOnDemandCapacity() + fleet.getProvisionedSpotCapacity() == 0;
    }

    /**
     * The instances ListInstances lists for one instance group or fleet: {@code count} of them, with addresses numbered
     * {@code number}, those after the first {@code spotAfter} on the spot market and the others on {@code market}, each
     * in {@code state}.
     */
    record ListedInstances(String groupId, String fleetId, String type, String instanceType, int number, long count,
                           String market, long spotAfter, String state) {

        String marketOf(long ordinal) {
            return ordinal <= spotAfter ? market : "SPOT";
        }

        /** Whether ListInstances' filters keep them: the group ones keep groups' instances, the fleet ones fleets'. */
        boolean matches(String instanceGroupId, List<String> groupTypes, String instanceFleetId, String fleetType) {
            return (instanceGroupId == null || instanceGroupId.equals(groupId))
                    && (groupTypes.isEmpty() || (groupId != null && groupTypes.contains(type)))
                    && (instanceFleetId == null || instanceFleetId.equals(fleetId))
                    && (fleetType == null || (fleetId != null && fleetType.equals(type)));
        }
    }

    static boolean withinTerminatedInstanceRetention(Instant endedAt, Instant now) {
        return endedAt != null && !endedAt.isBefore(now.minus(30, ChronoUnit.DAYS));
    }

    static int listedInstanceCount(EmrInstanceGroup group, boolean terminated) {
        if (terminated && group.getTerminatedInstanceCount() > 0) {
            return group.getTerminatedInstanceCount();
        }
        // Being provisioned, none of them runs yet, and each is listed.
        if (beingProvisioned(group)) {
            return group.getRequestedInstanceCount();
        }
        return group.getRunningInstanceCount();
    }

    // ──────────────────────────── Security configurations ────────────────────────────

    private Response handleCreateSecurityConfiguration(JsonNode request, String region) {
        SecurityConfiguration sc = service.createSecurityConfiguration(
                text(request, "Name"), text(request, "SecurityConfiguration"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Name", sc.getName());
        response.put("CreationDateTime", epoch(sc.getCreationDateTime()));
        return Response.ok(response).build();
    }

    private Response handleDescribeSecurityConfiguration(JsonNode request, String region) {
        SecurityConfiguration sc = service.describeSecurityConfiguration(text(request, "Name"), region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("Name", sc.getName());
        response.put("SecurityConfiguration", sc.getSecurityConfiguration());
        response.put("CreationDateTime", epoch(sc.getCreationDateTime()));
        return Response.ok(response).build();
    }

    private Response handleDeleteSecurityConfiguration(JsonNode request, String region) {
        service.deleteSecurityConfiguration(text(request, "Name"), region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Release catalogue ────────────────────────────

    private Response handleListReleaseLabels(JsonNode request) {
        JsonNode filters = request.path("Filters");
        PaginatedResult<String> page = service.listReleaseLabels(
                filters.path("Prefix").asText(null), filters.path("Application").asText(null),
                request.path("NextToken").asText(null), intOrNull(request.path("MaxResults")));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode labels = response.putArray("ReleaseLabels");
        page.items().forEach(labels::add);
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private Response handleDescribeReleaseLabel(JsonNode request) {
        // MaxResults and NextToken are "reserved for future use" and AWS answers every application.
        EmrReleaseCatalog.Release release = service.describeReleaseLabel(request.path("ReleaseLabel").asText(null));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ReleaseLabel", release.label());
        ArrayNode apps = response.putArray("Applications");
        for (EmrReleaseCatalog.Application app : release.applications()) {
            apps.addObject().put("Name", app.name()).put("Version", app.version());
        }
        ArrayNode os = response.putArray("AvailableOSReleases");
        release.osReleases().forEach(label -> os.addObject().put("Label", label));
        return Response.ok(response).build();
    }

    private Response handleListSupportedInstanceTypes(JsonNode request) {
        PaginatedResult<EmrReleaseCatalog.InstanceType> page = service.listSupportedInstanceTypes(
                request.path("ReleaseLabel").asText(null), request.path("Marker").asText(null));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode types = response.putArray("SupportedInstanceTypes");
        for (EmrReleaseCatalog.InstanceType t : page.items()) {
            types.addObject()
                    .put("Type", t.type())
                    .put("MemoryGB", t.memoryGb())
                    .put("StorageGB", t.storageGb())
                    .put("VCPU", t.vcpu())
                    .put("Is64BitsOnly", true)
                    .put("InstanceFamilyId", t.instanceFamilyId())
                    .put("EbsOptimizedAvailable", true)
                    .put("EbsOptimizedByDefault", true)
                    .put("NumberOfDisks", t.numberOfDisks())
                    .put("EbsStorageOnly", t.ebsStorageOnly())
                    .put("Architecture", t.architecture());
        }
        if (page.nextToken() != null) {
            response.put("Marker", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.canConvertToInt()) {
            throw new AwsException("InvalidRequestException", "MaxResults must be an integer.", 400);
        }
        return node.asInt();
    }

    private Response handleListSecurityConfigurations(String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("SecurityConfigurations");
        for (SecurityConfiguration sc : service.listSecurityConfigurations(region)) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Name", sc.getName());
            node.put("CreationDateTime", epoch(sc.getCreationDateTime()));
            arr.add(node);
        }
        return Response.ok(response).build();
    }

    // ──────────────────────────── Tags ────────────────────────────

    // A tag call's ResourceId is "a cluster identifier or an Amazon EMR Studio ID".
    private Response handleAddTags(JsonNode request) {
        String resourceId = text(request, "ResourceId");
        if (EmrStudioService.isStudioId(resourceId)) {
            studios.addTags(resourceId, parseTags(request.path("Tags")));
            return Response.ok(objectMapper.createObjectNode()).build();
        }
        service.addTags(resourceId, parseTags(request.path("Tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleRemoveTags(JsonNode request) {
        String resourceId = text(request, "ResourceId");
        if (EmrStudioService.isStudioId(resourceId)) {
            studios.removeTags(resourceId, stringList(request.path("TagKeys")));
            return Response.ok(objectMapper.createObjectNode()).build();
        }
        service.removeTags(resourceId, stringList(request.path("TagKeys")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    // ──────────────────────────── Builders ────────────────────────────

    private Response handleModifyInstanceGroups(JsonNode request, String region) {
        List<EmrService.InstanceGroupModification> modifications = new ArrayList<>();
        // Only a list names groups, as the other list members here and the IAM check read one: any other
        // InstanceGroups names none, which the service refuses.
        JsonNode groups = request.path("InstanceGroups");
        if (groups.isArray()) {
            for (JsonNode g : groups) {
                JsonNode count = g.path("InstanceCount");
                JsonNode configurations = g.path("Configurations");
                modifications.add(new EmrService.InstanceGroupModification(
                        g.path("InstanceGroupId").asText(null),
                        count.isMissingNode() || count.isNull() ? null : count.asInt(),
                        configurations.isArray() ? configurations.toString() : null));
            }
        }
        service.modifyInstanceGroups(text(request, "ClusterId"), region, modifications);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleModifyInstanceFleet(JsonNode request) {
        JsonNode fleet = request.path("InstanceFleet");
        JsonNode onDemand = fleet.path("TargetOnDemandCapacity");
        JsonNode spot = fleet.path("TargetSpotCapacity");
        service.modifyInstanceFleet(text(request, "ClusterId"), fleet.path("InstanceFleetId").asText(null),
                onDemand.isMissingNode() || onDemand.isNull() ? null : onDemand.asInt(),
                spot.isMissingNode() || spot.isNull() ? null : spot.asInt());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    /** Each BootstrapActionConfig given to RunJobFlow, as the Command shape: Name, ScriptPath and Args. */
    private Response handleListBootstrapActions(JsonNode request) {
        String raw = service.listBootstrapActions(text(request, "ClusterId"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode arr = response.putArray("BootstrapActions");
        if (raw == null) {
            return Response.ok(response).build();
        }
        try {
            for (JsonNode action : objectMapper.readTree(raw)) {
                ObjectNode command = arr.addObject();
                command.put("Name", action.path("Name").asText());
                JsonNode script = action.path("ScriptBootstrapAction");
                command.put("ScriptPath", script.path("Path").asText());
                ArrayNode args = command.putArray("Args");
                script.path("Args").forEach(args::add);
            }
        } catch (Exception e) {
            LOG.warnv("Failed to parse stored BootstrapActions JSON; list left empty. Value: {0}", raw);
        }
        return Response.ok(response).build();
    }

    private Response handlePutManagedScalingPolicy(JsonNode request) {
        service.putManagedScalingPolicy(text(request, "ClusterId"), request.get("ManagedScalingPolicy"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetManagedScalingPolicy(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        setRawJson(response, "ManagedScalingPolicy", service.getManagedScalingPolicy(text(request, "ClusterId")));
        return Response.ok(response).build();
    }

    private Response handleRemoveManagedScalingPolicy(JsonNode request) {
        service.removeManagedScalingPolicy(text(request, "ClusterId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handlePutAutoTerminationPolicy(JsonNode request) {
        service.putAutoTerminationPolicy(text(request, "ClusterId"), request.path("AutoTerminationPolicy"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetAutoTerminationPolicy(JsonNode request) {
        ObjectNode response = objectMapper.createObjectNode();
        setRawJson(response, "AutoTerminationPolicy", service.getAutoTerminationPolicy(text(request, "ClusterId")));
        return Response.ok(response).build();
    }

    private Response handleRemoveAutoTerminationPolicy(JsonNode request) {
        service.removeAutoTerminationPolicy(text(request, "ClusterId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handlePutAutoScalingPolicy(JsonNode request) {
        String clusterId = text(request, "ClusterId");
        EmrInstanceGroup group = service.putAutoScalingPolicy(clusterId, text(request, "InstanceGroupId"),
                request.get("AutoScalingPolicy"));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ClusterId", clusterId);
        response.put("InstanceGroupId", group.getId());
        response.set("AutoScalingPolicy", autoScalingPolicyNode(group.getAutoScalingPolicy()));
        response.put("ClusterArn", service.describeCluster(clusterId).getClusterArn());
        return Response.ok(response).build();
    }

    private Response handleRemoveAutoScalingPolicy(JsonNode request) {
        service.removeAutoScalingPolicy(text(request, "ClusterId"), text(request, "InstanceGroupId"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetBlockPublicAccessConfiguration(String region) {
        EmrBlockPublicAccess setting = service.getBlockPublicAccess(region);
        ObjectNode response = objectMapper.createObjectNode();
        setRawJson(response, "BlockPublicAccessConfiguration", setting.getConfiguration());
        ObjectNode metadata = response.putObject("BlockPublicAccessConfigurationMetadata");
        putEpoch(metadata, "CreationDateTime", setting.getCreationDateTime());
        metadata.put("CreatedByArn", setting.getCreatedByArn());
        return Response.ok(response).build();
    }

    private Response handlePutBlockPublicAccessConfiguration(JsonNode request, String region) {
        service.putBlockPublicAccess(region, request.get("BlockPublicAccessConfiguration"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

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
        if (c.getSecurityConfiguration() != null) {
            node.put("SecurityConfiguration", c.getSecurityConfiguration());
        }
        node.put("NormalizedInstanceHours", c.getNormalizedInstanceHours());
        if (c.getMasterPublicDnsName() != null) {
            node.put("MasterPublicDnsName", EmrService.masterDnsName(c.getRegion()));
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
        node.put("SessionEnabled", c.isSessionEnabled());
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
        if (!c.getErrorDetails().isEmpty()) {
            ArrayNode details = status.putArray("ErrorDetails");
            for (EmrErrorDetail detail : c.getErrorDetails()) {
                ObjectNode node = details.addObject();
                node.put("ErrorCode", detail.getErrorCode());
                ArrayNode data = node.putArray("ErrorData");
                for (Map<String, String> pairs : detail.getErrorData()) {
                    ObjectNode map = data.addObject();
                    pairs.forEach(map::put);
                }
                node.put("ErrorMessage", detail.getErrorMessage());
            }
        }
        return status;
    }

    private ObjectNode stepNode(EmrStep step, boolean includeExecutionRole) {
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
        if (includeExecutionRole && step.getExecutionRoleArn() != null) {
            node.put("ExecutionRoleArn", step.getExecutionRoleArn());
        }
        ObjectNode status = node.putObject("Status");
        status.put("State", step.getState());
        if (step.getStateChangeReason() != null) {
            status.putObject("StateChangeReason").put("Message", step.getStateChangeReason());
        }
        if (step.getFailureReason() != null) {
            ObjectNode failure = status.putObject("FailureDetails").put("Reason", step.getFailureReason());
            if (step.getFailureMessage() != null) {
                failure.put("Message", step.getFailureMessage());
            }
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
        if (g.getAutoScalingPolicy() != null) {
            node.set("AutoScalingPolicy", autoScalingPolicyNode(g.getAutoScalingPolicy()));
        }
        if (g.getConfigurations() != null) {
            // Floci applies a reconfiguration at once, so the applied configuration is the requested one.
            setRawJson(node, "Configurations", g.getConfigurations());
            node.put("ConfigurationsVersion", g.getConfigurationsVersion());
            setRawJson(node, "LastSuccessfullyAppliedConfigurations", g.getConfigurations());
            node.put("LastSuccessfullyAppliedConfigurationsVersion", g.getConfigurationsVersion());
        }
        return node;
    }

    /** An AutoScalingPolicyDescription: the stored Constraints and Rules with an ATTACHED status. */
    private ObjectNode autoScalingPolicyNode(String rawPolicy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.putObject("Status").put("State", "ATTACHED");
        try {
            JsonNode policy = objectMapper.readTree(rawPolicy);
            node.set("Constraints", policy.path("Constraints"));
            node.set("Rules", policy.path("Rules"));
        } catch (Exception e) {
            LOG.warnv("Failed to parse stored AutoScalingPolicy JSON; policy omitted. Value: {0}", rawPolicy);
        }
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
        node.set("InstanceTypeSpecifications", instanceTypeSpecifications(f));
        node.putObject("Status").put("State", f.getState());
        return node;
    }

    /**
     * Each InstanceTypeConfig the fleet was given, as the InstanceTypeSpecification it describes. A config that sets
     * no WeightedCapacity has 1, and one that sets neither bid price has BidPriceAsPercentageOfOnDemandPrice 100, the
     * defaults the API reference gives for both. Its EbsConfiguration becomes one EbsBlockDevice per volume, the way
     * VolumesPerInstance counts them, named {@code /dev/sdb} on: the reference gives no device names. A fleet stored
     * before its configs were kept has its instance types alone, with those same defaults.
     */
    private ArrayNode instanceTypeSpecifications(EmrInstanceFleet f) {
        ArrayNode specifications = objectMapper.createArrayNode();
        JsonNode configs = readRawArray(f.getInstanceTypeConfigs());
        if (configs == null) {
            ArrayNode named = objectMapper.createArrayNode();
            f.getInstanceTypes().forEach(type -> named.addObject().put("InstanceType", type));
            configs = named;
        }
        for (JsonNode config : configs) {
            ObjectNode specification = specifications.addObject();
            specification.put("InstanceType", config.path("InstanceType").asText(null));
            specification.put("WeightedCapacity", config.path("WeightedCapacity").asInt(1));
            boolean bidPrice = config.hasNonNull("BidPrice");
            if (bidPrice) {
                specification.put("BidPrice", config.path("BidPrice").asText());
            }
            if (config.hasNonNull("BidPriceAsPercentageOfOnDemandPrice")) {
                specification.put("BidPriceAsPercentageOfOnDemandPrice",
                        config.path("BidPriceAsPercentageOfOnDemandPrice").asDouble());
            } else if (!bidPrice) {
                specification.put("BidPriceAsPercentageOfOnDemandPrice", 100.0);
            }
            if (config.hasNonNull("Configurations")) {
                specification.set("Configurations", config.get("Configurations"));
            }
            JsonNode ebs = config.path("EbsConfiguration");
            ArrayNode devices = objectMapper.createArrayNode();
            for (JsonNode device : ebs.path("EbsBlockDeviceConfigs")) {
                // Bounded for a config stored before the limit was held at create.
                int volumes = Math.min(device.path("VolumesPerInstance").asInt(1),
                        EBS_VOLUMES_PER_INSTANCE - devices.size());
                for (int volume = 0; volume < volumes; volume++) {
                    ObjectNode blockDevice = devices.addObject();
                    if (device.hasNonNull("VolumeSpecification")) {
                        blockDevice.set("VolumeSpecification", device.get("VolumeSpecification"));
                    }
                    blockDevice.put("Device", deviceName(devices.size() - 1));
                }
            }
            if (!devices.isEmpty()) {
                specification.set("EbsBlockDevices", devices);
            }
            if (ebs.hasNonNull("EbsOptimized")) {
                specification.put("EbsOptimized", ebs.path("EbsOptimized").asBoolean());
            }
            if (config.hasNonNull("CustomAmiId")) {
                specification.put("CustomAmiId", config.path("CustomAmiId").asText());
            }
            if (config.hasNonNull("Priority")) {
                specification.put("Priority", config.path("Priority").asDouble());
            }
        }
        return specifications;
    }

    /** {@code /dev/sdb}, {@code /dev/sdc} and on for the volumes of an instance, past {@code sdz} as Linux goes on. */
    static String deviceName(int index) {
        StringBuilder letters = new StringBuilder();
        for (int n = index + 1; n >= 0; n = n / 26 - 1) {
            letters.insert(0, (char) ('a' + n % 26));
        }
        return "/dev/sd" + letters;
    }

    private ObjectNode syntheticInstanceNode(EmrCluster cluster, ListedInstances set, long index, long ordinal,
                                             boolean terminated) {
        String privateIp = EmrService.privateIpAddress(set.number(), ordinal);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Id", "ci-" + index + cluster.getId());
        node.put("Ec2InstanceId", EmrService.ec2InstanceId(index));
        node.put("PublicDnsName", EmrService.publicDnsName(index, cluster.getRegion()));
        node.put("PrivateDnsName", AwsRegions.ec2PrivateIpDnsName(privateIp, cluster.getRegion()));
        node.put("PrivateIpAddress", privateIp);
        if (set.groupId() != null) {
            node.put("InstanceGroupId", set.groupId());
        }
        if (set.fleetId() != null) {
            node.put("InstanceFleetId", set.fleetId());
        }
        if (set.marketOf(ordinal) != null) {
            node.put("Market", set.marketOf(ordinal));
        }
        if (set.instanceType() != null) {
            node.put("InstanceType", set.instanceType());
        }
        node.putObject("Status").put("State", set.state());
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

    private List<EmrStep> parseSteps(JsonNode stepsNode, String executionRoleArn) {
        List<EmrStep> steps = new ArrayList<>();
        if (!stepsNode.isArray()) {
            return steps;
        }
        for (JsonNode s : stepsNode) {
            EmrStep step = new EmrStep();
            step.setName(s.path("Name").asText(null));
            step.setActionOnFailure(s.path("ActionOnFailure").asText(null));
            step.setExecutionRoleArn(executionRoleArn);
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
            // Kept as given, an empty list included, so creation reads back like ModifyInstanceGroups.
            JsonNode configurations = g.path("Configurations");
            group.setConfigurations(configurations.isArray() ? configurations.toString() : null);
            JsonNode autoScaling = g.path("AutoScalingPolicy");
            if (!autoScaling.isMissingNode() && !autoScaling.isNull()) {
                EmrService.validateAutoScalingPolicy(autoScaling, group.getInstanceGroupType());
                group.setAutoScalingPolicy(autoScaling.toString());
            }
            groups.add(group);
        }
        return groups;
    }

    private List<EmrInstanceFleet> parseInstanceFleets(JsonNode fleetsNode) {
        List<EmrInstanceFleet> fleets = new ArrayList<>();
        if (!fleetsNode.isArray()) {
            return fleets;
        }
        List<String> violations = new ArrayList<>();
        int position = 1;
        for (JsonNode f : fleetsNode) {
            fleets.add(parseInstanceFleet(f, "instances.instanceFleets." + position++ + ".member", violations));
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
        fleetsNode.forEach(EmrHandler::requireEbsVolumeLimit);
        return fleets;
    }

    /**
     * Refuses an InstanceTypeConfig whose EbsConfiguration attaches more volumes to an instance than the Management
     * Guide allows: "There is a limit of 25 volumes per instance." AWS's error for it is not documented, so the
     * ValidationException carrying that sentence is Floci's.
     */
    private static void requireEbsVolumeLimit(JsonNode fleet) {
        for (JsonNode config : fleet.path("InstanceTypeConfigs")) {
            long volumes = 0;
            for (JsonNode device : config.path("EbsConfiguration").path("EbsBlockDeviceConfigs")) {
                volumes += device.path("VolumesPerInstance").asLong(1);
            }
            if (volumes > EBS_VOLUMES_PER_INSTANCE) {
                throw new AwsException("ValidationException", "There is a limit of 25 volumes per instance.", 400);
            }
        }
    }

    /**
     * An InstanceFleetConfig at {@code path} in its request, as the framework names a member's place; one without its
     * required InstanceFleetType, or with an InstanceTypeConfigs member without its required InstanceType, adds to
     * {@code violations}, as the framework refuses such a request.
     */
    private EmrInstanceFleet parseInstanceFleet(JsonNode f, String path, List<String> violations) {
        EmrInstanceFleet fleet = new EmrInstanceFleet();
        fleet.setName(f.path("Name").asText(null));
        fleet.setInstanceFleetType(f.path("InstanceFleetType").asText(null));
        if (fleet.getInstanceFleetType() == null) {
            violations.add(SmithyValidationErrors.notNull(path + ".instanceFleetType"));
        }
        List<String> instanceTypes = new ArrayList<>();
        int position = 1;
        for (JsonNode config : f.path("InstanceTypeConfigs")) {
            String instanceType = config.path("InstanceType").asText(null);
            if (instanceType == null) {
                violations.add(SmithyValidationErrors.notNull(
                        path + ".instanceTypeConfigs." + position + ".member.instanceType"));
            }
            instanceTypes.add(instanceType);
            position++;
        }
        fleet.setInstanceTypes(instanceTypes);
        fleet.setInstanceTypeConfigs(rawArray(f.path("InstanceTypeConfigs")));
        fleet.setTargetOnDemandCapacity(f.path("TargetOnDemandCapacity").asInt(0));
        fleet.setTargetSpotCapacity(f.path("TargetSpotCapacity").asInt(0));
        return fleet;
    }

    // The cluster's EC2 attributes, its availability zone among them: the one Placement names, the first of its
    // AvailabilityZones, else the Region's first, as its instances' metadata gives it too.
    private String buildEc2Attributes(JsonNode instances, String region) {
        ObjectNode ec2 = objectMapper.createObjectNode();
        JsonNode placement = instances.path("Placement");
        String zone = placement.path("AvailabilityZone").asText("");
        if (zone.isEmpty()) {
            zone = placement.path("AvailabilityZones").path(0).asText("");
        }
        ec2.put("Ec2AvailabilityZone", zone.isEmpty() ? region + "a" : zone);
        copyText(instances, "Ec2KeyName", ec2, "Ec2KeyName");
        copyText(instances, "Ec2SubnetId", ec2, "Ec2SubnetId");
        copyText(instances, "EmrManagedMasterSecurityGroup", ec2, "EmrManagedMasterSecurityGroup");
        copyText(instances, "EmrManagedSlaveSecurityGroup", ec2, "EmrManagedSlaveSecurityGroup");
        copyText(instances, "ServiceAccessSecurityGroup", ec2, "ServiceAccessSecurityGroup");
        return ec2.toString();
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

    /**
     * Refuses a request that leaves out members its model requires, which would otherwise read as an
     * empty list or a default flag and succeed on nothing. The wording is AWS's framework validation, as
     * for RunJobFlow's Name; an explicitly empty JobFlowIds is still a valid list.
     */
    private static void requireMembers(JsonNode request, String... members) {
        List<String> violations = new ArrayList<>();
        for (String member : members) {
            JsonNode value = request.path(member);
            if (value.isMissingNode() || value.isNull()) {
                violations.add(SmithyValidationErrors.notNull(
                        Character.toLowerCase(member.charAt(0)) + member.substring(1)));
            }
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
    }

    private String text(JsonNode request, String field) {
        JsonNode node = request.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private String rawArray(JsonNode node) {
        return (node != null && node.isArray() && !node.isEmpty()) ? node.toString() : null;
    }

    /** A stored raw JSON array, or null when there is none or it cannot be read. */
    private JsonNode readRawArray(String rawJson) {
        if (rawJson == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(rawJson);
            return node.isArray() ? node : null;
        } catch (JsonProcessingException e) {
            LOG.warnv(e, "Failed to parse stored JSON array; ignored. Value: {0}", rawJson);
            return null;
        }
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

    private BigDecimal epoch(Instant instant) {
        return instant == null ? BigDecimal.ZERO : BigDecimal.valueOf(instant.toEpochMilli(), 3);
    }

    private void putEpoch(ObjectNode node, String field, Instant instant) {
        if (instant != null) {
            node.put(field, epoch(instant));
        }
    }
}
