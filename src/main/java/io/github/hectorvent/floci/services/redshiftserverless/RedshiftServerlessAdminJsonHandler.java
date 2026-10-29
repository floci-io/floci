package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsJsonTimestamp;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.redshiftserverless.model.CustomDomainAssociation;
import io.github.hectorvent.floci.services.redshiftserverless.model.EndpointAccess;
import io.github.hectorvent.floci.services.redshiftserverless.model.ResourcePolicy;
import io.github.hectorvent.floci.services.redshiftserverless.model.ScheduledAction;
import io.github.hectorvent.floci.services.redshiftserverless.model.SnapshotCopyConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Redshift Serverless actions that only describe or record metadata. The main
 * {@link RedshiftServerlessJsonHandler} hands every action it does not know to this class. The
 * request constraints (required members, patterns, lengths) are checked against the AWS model by
 * {@link RedshiftServerlessRequestShapes} before the action reaches it.
 */
@ApplicationScoped
public class RedshiftServerlessAdminJsonHandler {

    private static final List<String> TRACK_NAMES = RedshiftServerlessService.TRACK_NAMES;

    private final ObjectMapper objectMapper;
    private final RedshiftServerlessCustomDomainService customDomains;
    private final RedshiftServerlessEndpointAccessService endpointAccess;
    private final RedshiftServerlessResourcePolicyService resourcePolicies;
    private final RedshiftServerlessSnapshotCopyService snapshotCopies;
    private final RedshiftServerlessScheduledActionService scheduledActions;

    @Inject
    public RedshiftServerlessAdminJsonHandler(ObjectMapper objectMapper,
                                              RedshiftServerlessResourcePolicyService resourcePolicies,
                                              RedshiftServerlessEndpointAccessService endpointAccess,
                                              RedshiftServerlessCustomDomainService customDomains,
                                              RedshiftServerlessSnapshotCopyService snapshotCopies,
                                              RedshiftServerlessScheduledActionService scheduledActions) {
        this.objectMapper = objectMapper;
        this.resourcePolicies = resourcePolicies;
        this.endpointAccess = endpointAccess;
        this.customDomains = customDomains;
        this.snapshotCopies = snapshotCopies;
        this.scheduledActions = scheduledActions;
    }

    Supplier<Response> operation(String action, JsonNode request, String region) {
        return switch (action) {
            case "PutResourcePolicy" -> () -> resourcePolicyResponse(resourcePolicies.putResourcePolicy(
                    ServerlessSupport.text(request, "resourceArn"), ServerlessSupport.text(request, "policy"),
                    region));
            case "GetResourcePolicy" -> () -> resourcePolicyResponse(resourcePolicies.getResourcePolicy(
                    ServerlessSupport.text(request, "resourceArn"), region));
            case "DeleteResourcePolicy" -> () -> {
                resourcePolicies.deleteResourcePolicy(ServerlessSupport.text(request, "resourceArn"), region);
                return emptyResponse();
            };
            case "GetTrack" -> () -> getTrack(ServerlessSupport.text(request, "trackName"));
            case "ListTracks" -> () -> listTracks(request);
            case "ListManagedWorkgroups" -> () -> listWithoutItems(request, "managedWorkgroups");
            case "ListReservations" -> () -> listWithoutItems(request, "reservationsList");
            case "ListReservationOfferings" -> () -> listWithoutItems(request, "reservationOfferingsList");
            case "GetReservation" -> () -> getReservation(request);
            case "GetReservationOffering" -> () -> getReservationOffering(request);
            case "CreateReservation" -> () -> createReservation(request);
            case "CreateEndpointAccess" -> () -> endpointResponse(endpointAccess.createEndpointAccess(
                    ServerlessSupport.text(request, "endpointName"), ServerlessSupport.text(request, "workgroupName"),
                    ServerlessSupport.stringList(request, "subnetIds"),
                    ServerlessSupport.stringList(request, "vpcSecurityGroupIds"),
                    ServerlessSupport.text(request, "ownerAccount"), region));
            case "GetEndpointAccess" -> () -> endpointResponse(endpointAccess.getEndpointAccess(
                    ServerlessSupport.text(request, "endpointName"), region));
            case "ListEndpointAccess" -> () -> listEndpoints(request, region);
            case "UpdateEndpointAccess" -> () -> endpointResponse(endpointAccess.updateEndpointAccess(
                    ServerlessSupport.text(request, "endpointName"),
                    ServerlessSupport.stringList(request, "vpcSecurityGroupIds"), region));
            case "DeleteEndpointAccess" -> () -> endpointResponse(endpointAccess.deleteEndpointAccess(
                    ServerlessSupport.text(request, "endpointName"), region));
            case "CreateCustomDomainAssociation" -> () -> customDomainResponse(
                    customDomains.createCustomDomainAssociation(
                            ServerlessSupport.text(request, "workgroupName"),
                            ServerlessSupport.text(request, "customDomainName"),
                            ServerlessSupport.text(request, "customDomainCertificateArn"), region));
            case "GetCustomDomainAssociation" -> () -> customDomainResponse(
                    customDomains.getCustomDomainAssociation(ServerlessSupport.text(request, "workgroupName"),
                            ServerlessSupport.text(request, "customDomainName"), region));
            case "ListCustomDomainAssociations" -> () -> listCustomDomains(request, region);
            case "UpdateCustomDomainAssociation" -> () -> customDomainResponse(
                    customDomains.updateCustomDomainAssociation(
                            ServerlessSupport.text(request, "workgroupName"),
                            ServerlessSupport.text(request, "customDomainName"),
                            ServerlessSupport.text(request, "customDomainCertificateArn"), region));
            case "DeleteCustomDomainAssociation" -> () -> {
                customDomains.deleteCustomDomainAssociation(ServerlessSupport.text(request, "workgroupName"),
                        ServerlessSupport.text(request, "customDomainName"), region);
                return emptyResponse();
            };
            case "CreateSnapshotCopyConfiguration" -> () -> snapshotCopyResponse(
                    snapshotCopies.createSnapshotCopyConfiguration(
                            ServerlessSupport.text(request, "namespaceName"),
                            ServerlessSupport.text(request, "destinationRegion"),
                            ServerlessSupport.text(request, "destinationKmsKeyId"),
                            ServerlessSupport.integer(request, "snapshotRetentionPeriod"), region));
            case "ListSnapshotCopyConfigurations" -> () -> listSnapshotCopies(request, region);
            case "UpdateSnapshotCopyConfiguration" -> () -> snapshotCopyResponse(
                    snapshotCopies.updateSnapshotCopyConfiguration(
                            ServerlessSupport.text(request, "snapshotCopyConfigurationId"),
                            ServerlessSupport.integer(request, "snapshotRetentionPeriod"), region));
            case "DeleteSnapshotCopyConfiguration" -> () -> snapshotCopyResponse(
                    snapshotCopies.deleteSnapshotCopyConfiguration(
                            ServerlessSupport.text(request, "snapshotCopyConfigurationId"), region));
            case "CreateScheduledAction" -> () -> scheduledActionResponse(
                    scheduledActions.createScheduledAction(parseScheduledAction(request), region));
            case "GetScheduledAction" -> () -> scheduledActionResponse(
                    scheduledActions.getScheduledAction(ServerlessSupport.text(request, "scheduledActionName"), region));
            case "ListScheduledActions" -> () -> listScheduledActions(request, region);
            case "UpdateScheduledAction" -> () -> scheduledActionResponse(
                    scheduledActions.updateScheduledAction(parseScheduledAction(request), region));
            case "DeleteScheduledAction" -> () -> scheduledActionResponse(
                    scheduledActions.deleteScheduledAction(ServerlessSupport.text(request, "scheduledActionName"), region));
            default -> null;
        };
    }

    private Response getReservation(JsonNode request) {
        throw ServerlessSupport.notFound("The reservation "
                + ServerlessSupport.text(request, "reservationId") + " was not found.");
    }

    private Response getReservationOffering(JsonNode request) {
        throw ServerlessSupport.notFound("The reservation offering "
                + ServerlessSupport.text(request, "offeringId") + " was not found.");
    }

    private Response createReservation(JsonNode request) {
        ServerlessSupport.integer(request, "capacity");
        ServerlessSupport.text(request, "clientToken");
        throw ServerlessSupport.notFound("The reservation offering "
                + ServerlessSupport.text(request, "offeringId") + " was not found.");
    }

    // ── Resource policy ────────────────────────────────────────────────────

    private Response resourcePolicyResponse(ResourcePolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("policy", policy.getPolicy());
        node.put("resourceArn", policy.getResourceArn());
        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourcePolicy", node);
        return Response.ok(response).build();
    }

    // ── Endpoint access ────────────────────────────────────────────────────

    private Response listEndpoints(JsonNode request, String region) {
        PaginatedResult<EndpointAccess> page = endpointAccess.listEndpointAccess(
                ServerlessSupport.text(request, "workgroupName"), ServerlessSupport.text(request, "vpcId"),
                ServerlessSupport.text(request, "ownerAccount"), region, ServerlessSupport.maxResults(request),
                ServerlessSupport.text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("endpoints");
        page.items().forEach(endpoint -> items.add(endpointNode(endpoint)));
        putNextToken(response, page);
        return Response.ok(response).build();
    }

    private Response endpointResponse(EndpointAccess endpoint) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("endpoint", endpointNode(endpoint));
        return Response.ok(response).build();
    }

    /** No VPC is modelled, so {@code vpcEndpoint.vpcId} is left out rather than invented. */
    private ObjectNode endpointNode(EndpointAccess endpoint) {
        ObjectNode node = objectMapper.createObjectNode();
        if (endpoint.getAddress() != null) {
            node.put("address", endpoint.getAddress());
        }
        node.put("endpointArn", endpoint.getEndpointArn());
        node.put("endpointCreateTime", ServerlessSupport.ISO_8601.format(endpoint.getCreateTime()));
        node.put("endpointName", endpoint.getEndpointName());
        node.put("endpointStatus", endpoint.getStatus());
        node.put("port", endpoint.getPort());
        ArrayNode subnets = node.putArray("subnetIds");
        endpoint.getSubnetIds().forEach(subnets::add);
        ObjectNode vpcEndpoint = node.putObject("vpcEndpoint");
        vpcEndpoint.put("vpcEndpointId", endpoint.getVpcEndpointId());
        ArrayNode interfaces = vpcEndpoint.putArray("networkInterfaces");
        for (int i = 0; i < endpoint.getSubnetIds().size() && i < endpoint.getNetworkInterfaceIds().size(); i++) {
            ObjectNode networkInterface = interfaces.addObject();
            networkInterface.put("networkInterfaceId", endpoint.getNetworkInterfaceIds().get(i));
            networkInterface.put("subnetId", endpoint.getSubnetIds().get(i));
        }
        ArrayNode groups = node.putArray("vpcSecurityGroups");
        for (String groupId : endpoint.getVpcSecurityGroupIds()) {
            ObjectNode group = groups.addObject();
            group.put("vpcSecurityGroupId", groupId);
            group.put("status", "active");
        }
        node.put("workgroupName", endpoint.getWorkgroupName());
        return node;
    }

    // ── Custom domain association ──────────────────────────────────────────

    private Response listCustomDomains(JsonNode request, String region) {
        PaginatedResult<CustomDomainAssociation> page = customDomains.listCustomDomainAssociations(
                ServerlessSupport.text(request, "customDomainName"),
                ServerlessSupport.text(request, "customDomainCertificateArn"), region,
                ServerlessSupport.maxResults(request), ServerlessSupport.text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("associations");
        page.items().forEach(association -> items.add(customDomainNode(association)));
        putNextToken(response, page);
        return Response.ok(response).build();
    }

    private Response customDomainResponse(CustomDomainAssociation association) {
        return Response.ok(customDomainNode(association)).build();
    }

    private ObjectNode customDomainNode(CustomDomainAssociation association) {
        ObjectNode node = objectMapper.createObjectNode();
        ServerlessSupport.populateCustomDomain(node, association);
        node.put("workgroupName", association.getWorkgroupName());
        return node;
    }

    // ── Snapshot copy configuration ────────────────────────────────────────

    private Response listSnapshotCopies(JsonNode request, String region) {
        PaginatedResult<SnapshotCopyConfiguration> page = snapshotCopies.listSnapshotCopyConfigurations(
                ServerlessSupport.text(request, "namespaceName"), region, ServerlessSupport.maxResults(request),
                ServerlessSupport.text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("snapshotCopyConfigurations");
        page.items().forEach(configuration -> items.add(snapshotCopyNode(configuration)));
        putNextToken(response, page);
        return Response.ok(response).build();
    }

    private Response snapshotCopyResponse(SnapshotCopyConfiguration configuration) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("snapshotCopyConfiguration", snapshotCopyNode(configuration));
        return Response.ok(response).build();
    }

    private ObjectNode snapshotCopyNode(SnapshotCopyConfiguration configuration) {
        ObjectNode node = objectMapper.createObjectNode();
        if (configuration.getDestinationKmsKeyId() != null) {
            node.put("destinationKmsKeyId", configuration.getDestinationKmsKeyId());
        }
        node.put("destinationRegion", configuration.getDestinationRegion());
        node.put("namespaceName", configuration.getNamespaceName());
        node.put("snapshotCopyConfigurationArn", configuration.getSnapshotCopyConfigurationArn());
        node.put("snapshotCopyConfigurationId", configuration.getSnapshotCopyConfigurationId());
        if (configuration.getSnapshotRetentionPeriod() != null) {
            node.put("snapshotRetentionPeriod", configuration.getSnapshotRetentionPeriod());
        }
        return node;
    }

    // ── Scheduled action ───────────────────────────────────────────────────

    private ScheduledAction parseScheduledAction(JsonNode request) {
        ScheduledAction action = new ScheduledAction();
        action.setScheduledActionName(ServerlessSupport.text(request, "scheduledActionName"));
        action.setNamespaceName(ServerlessSupport.text(request, "namespaceName"));
        action.setRoleArn(ServerlessSupport.text(request, "roleArn"));
        action.setDescription(ServerlessSupport.text(request, "scheduledActionDescription"));
        action.setEnabled(ServerlessSupport.bool(request, "enabled"));
        action.setStartTime(AwsJsonTimestamp.epochSeconds(request, "startTime", "ValidationException"));
        action.setEndTime(AwsJsonTimestamp.epochSeconds(request, "endTime", "ValidationException"));
        JsonNode schedule = ServerlessSupport.object(request, "schedule");
        if (schedule != null) {
            action.setScheduleSpecified(true);
            action.setScheduleAt(AwsJsonTimestamp.epochSeconds(schedule, "at", "ValidationException"));
            action.setScheduleCron(ServerlessSupport.text(schedule, "cron"));
        }
        JsonNode target = ServerlessSupport.object(request, "targetAction");
        if (target != null) {
            JsonNode snapshot = ServerlessSupport.object(target, "createSnapshot");
            if (snapshot == null) {
                throw ServerlessSupport.validation("targetAction must set createSnapshot.");
            }
            action.setTargetSpecified(true);
            action.setTargetNamespaceName(ServerlessSupport.text(snapshot, "namespaceName"));
            action.setSnapshotNamePrefix(ServerlessSupport.text(snapshot, "snapshotNamePrefix"));
            action.setRetentionPeriod(ServerlessSupport.integer(snapshot, "retentionPeriod"));
            action.setSnapshotTags(parseTags(snapshot.path("tags")));
        }
        return action;
    }

    private static Map<String, String> parseTags(JsonNode tags) {
        if (tags.isMissingNode() || tags.isNull()) {
            return null;
        }
        if (!tags.isArray()) {
            throw ServerlessSupport.validation("tags must be an array of tags.");
        }
        Map<String, String> parsed = new LinkedHashMap<>();
        for (JsonNode tag : tags) {
            if (!tag.isObject()) {
                throw ServerlessSupport.validation("tags must contain only tag objects.");
            }
            if (!tag.path("key").isTextual() || !tag.path("value").isTextual()) {
                throw ServerlessSupport.validation("every tag needs a string key and a string value.");
            }
            parsed.put(tag.path("key").textValue(), tag.path("value").textValue());
        }
        return parsed;
    }

    private Response listScheduledActions(JsonNode request, String region) {
        PaginatedResult<ScheduledAction> page = scheduledActions.listScheduledActions(
                ServerlessSupport.text(request, "namespaceName"), region, ServerlessSupport.maxResults(request),
                ServerlessSupport.text(request, "nextToken"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("scheduledActions");
        for (ScheduledAction action : page.items()) {
            ObjectNode item = items.addObject();
            item.put("namespaceName", action.getNamespaceName());
            item.put("scheduledActionName", action.getScheduledActionName());
        }
        putNextToken(response, page);
        return Response.ok(response).build();
    }

    private Response scheduledActionResponse(ScheduledAction action) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("scheduledAction", scheduledActionNode(action));
        return Response.ok(response).build();
    }

    /** Reports the next invocation inside the active window of an enabled action, for both schedule types. */
    private ObjectNode scheduledActionNode(ScheduledAction action) {
        ObjectNode node = objectMapper.createObjectNode();
        if (action.getEndTime() != null) {
            node.put("endTime", epoch(action.getEndTime()));
        }
        node.put("namespaceName", action.getNamespaceName());
        ArrayNode invocations = node.putArray("nextInvocations");
        RedshiftServerlessSchedule.next(action, Instant.now()).ifPresent(time -> invocations.add(epoch(time)));
        node.put("roleArn", action.getRoleArn());
        ObjectNode schedule = node.putObject("schedule");
        if (action.getScheduleAt() != null) {
            schedule.put("at", epoch(action.getScheduleAt()));
        } else {
            schedule.put("cron", action.getScheduleCron());
        }
        if (action.getDescription() != null) {
            node.put("scheduledActionDescription", action.getDescription());
        }
        node.put("scheduledActionName", action.getScheduledActionName());
        node.put("scheduledActionUuid", action.getScheduledActionUuid());
        if (action.getStartTime() != null) {
            node.put("startTime", epoch(action.getStartTime()));
        }
        node.put("state", Boolean.FALSE.equals(action.getEnabled()) ? "DISABLED" : "ACTIVE");
        ObjectNode createSnapshot = node.putObject("targetAction").putObject("createSnapshot");
        createSnapshot.put("namespaceName", action.getTargetNamespaceName());
        if (action.getRetentionPeriod() != null) {
            createSnapshot.put("retentionPeriod", action.getRetentionPeriod());
        }
        createSnapshot.put("snapshotNamePrefix", action.getSnapshotNamePrefix());
        if (action.getSnapshotTags() != null) {
            ArrayNode tags = createSnapshot.putArray("tags");
            action.getSnapshotTags().forEach((key, value) -> {
                ObjectNode tag = tags.addObject();
                tag.put("key", key);
                tag.put("value", value);
            });
        }
        return node;
    }

    private static double epoch(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }
    // ── Tracks, managed workgroups and reservations ────────────────────────

    /**
     * The release tracks AWS defines. The model gives a track name only a pattern, not an enum, so an
     * unknown name is a not-found here. Floci runs one PostgreSQL version, so a track names no workgroup version.
     */
    private Response getTrack(String trackName) {
        if (!TRACK_NAMES.contains(trackName)) {
            throw ServerlessSupport.notFound("The track " + trackName + " was not found.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.set("track", trackNode(trackName));
        return Response.ok(response).build();
    }

    private Response listTracks(JsonNode request) {
        PaginatedResult<String> page = Pagination.paginate(TRACK_NAMES, name -> name,
                ServerlessSupport.maxResults(request), ServerlessSupport.text(request, "nextToken"),
                100, 100, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("tracks");
        page.items().forEach(name -> items.add(trackNode(name)));
        putNextToken(response, page);
        return Response.ok(response).build();
    }

    private ObjectNode trackNode(String trackName) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("trackName", trackName);
        node.putArray("updateTargets");
        return node;
    }

    /**
     * A list that is always empty, still validating the paging members the way AWS does. Floci models no
     * Glue zero-ETL managed workgroups, publishes no reservation offerings and sells no reservations.
     */
    private Response listWithoutItems(JsonNode request, String listName) {
        Pagination.paginate(List.<String>of(), name -> name, ServerlessSupport.maxResults(request),
                ServerlessSupport.text(request, "nextToken"), 100, 100, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(listName);
        return Response.ok(response).build();
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    private Response emptyResponse() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private static void putNextToken(ObjectNode response, PaginatedResult<?> page) {
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
    }
}
