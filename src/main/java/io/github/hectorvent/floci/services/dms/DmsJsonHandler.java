package io.github.hectorvent.floci.services.dms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.dms.model.DmsResource;
import io.github.hectorvent.floci.services.dms.model.ReplicationSubnetGroup;
import io.github.hectorvent.floci.services.dms.model.ResourceTag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.function.Function;

@ApplicationScoped
public class DmsJsonHandler {

    private final DmsService service;
    private final ObjectMapper objectMapper;

    @Inject
    public DmsJsonHandler(DmsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateReplicationSubnetGroup" -> {
                ObjectNode response = objectMapper.createObjectNode();
                response.set("ReplicationSubnetGroup",
                        subnetGroup(service.createReplicationSubnetGroup(request, region)));
                yield Response.ok(response).build();
            }
            case "DescribeReplicationSubnetGroups" -> {
                PaginatedResult<ReplicationSubnetGroup> page =
                        service.describeReplicationSubnetGroups(request, region);
                ObjectNode response = objectMapper.createObjectNode();
                ArrayNode items = response.putArray("ReplicationSubnetGroups");
                page.items().forEach(group -> items.add(subnetGroup(group)));
                // Marker is present only while another page remains: an SDK paginator treats any
                // Marker at all as "ask again", so emitting one on the final page loops forever.
                if (page.nextToken() != null) {
                    response.put("Marker", page.nextToken());
                }
                yield Response.ok(response).build();
            }
            case "DeleteReplicationSubnetGroup" -> {
                service.deleteReplicationSubnetGroup(request, region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "ListTagsForResource" -> {
                ObjectNode response = objectMapper.createObjectNode();
                ArrayNode tagList = response.putArray("TagList");
                service.listTagsForResource(request, region).forEach(tag -> tagList.add(tagNode(tag)));
                yield Response.ok(response).build();
            }
            case "AddTagsToResource" -> {
                service.addTagsToResource(request, region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "RemoveTagsFromResource" -> {
                service.removeTagsFromResource(request, region);
                yield Response.ok(objectMapper.createObjectNode()).build();
            }
            case "CreateEndpoint" -> single("Endpoint", service.createEndpoint(request, region).getAttributes());
            case "DescribeEndpoints" -> page("Endpoints", service.describeEndpoints(request, region),
                    DmsResource::getAttributes);
            case "ModifyEndpoint" -> single("Endpoint", service.modifyEndpoint(request, region).getAttributes());
            case "DeleteEndpoint" -> single("Endpoint", service.deleteEndpoint(request, region).getAttributes());
            case "CreateReplicationInstance" -> single("ReplicationInstance",
                    instance(service.createReplicationInstance(request, region), region));
            case "DescribeReplicationInstances" -> page("ReplicationInstances",
                    service.describeReplicationInstances(request, region), item -> instance(item, region));
            case "ModifyReplicationInstance" -> single("ReplicationInstance",
                    instance(service.modifyReplicationInstance(request, region), region));
            case "DeleteReplicationInstance" -> single("ReplicationInstance",
                    instance(service.deleteReplicationInstance(request, region), region));
            case "CreateReplicationTask" -> single("ReplicationTask",
                    service.createReplicationTask(request, region).getAttributes());
            case "DescribeReplicationTasks" -> page("ReplicationTasks",
                    service.describeReplicationTasks(request, region), DmsResource::getAttributes);
            case "ModifyReplicationTask" -> single("ReplicationTask",
                    service.modifyReplicationTask(request, region).getAttributes());
            case "DeleteReplicationTask" -> single("ReplicationTask",
                    service.deleteReplicationTask(request, region).getAttributes());
            case "StartReplicationTask" -> single("ReplicationTask",
                    service.startReplicationTask(request, region).getAttributes());
            case "StopReplicationTask" -> single("ReplicationTask",
                    service.stopReplicationTask(request, region).getAttributes());
            default -> null;
        };
    }

    private Response single(String member, ObjectNode item) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(member, item);
        return Response.ok(response).build();
    }

    private Response page(String member, PaginatedResult<DmsResource> page,
                          Function<DmsResource, ObjectNode> render) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray(member);
        page.items().forEach(item -> items.add(render.apply(item)));
        if (page.nextToken() != null) {
            response.put("Marker", page.nextToken());
        }
        return Response.ok(response).build();
    }

    /**
     * Expands the stored subnet group identifier into the full {@code ReplicationSubnetGroup}
     * structure DMS returns, read at describe time so it reflects the group as it is now.
     */
    private ObjectNode instance(DmsResource resource, String region) {
        ObjectNode node = resource.getAttributes().deepCopy();
        String groupId = node.path("ReplicationSubnetGroup").path("ReplicationSubnetGroupIdentifier").asText(null);
        if (groupId != null) {
            service.findReplicationSubnetGroup(region, groupId)
                    .ifPresent(group -> node.set("ReplicationSubnetGroup", subnetGroup(group)));
        }
        return node;
    }

    private ObjectNode tagNode(ResourceTag tag) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Key", tag.key());
        node.put("Value", tag.value());
        if (tag.resourceArn() != null) {
            node.put("ResourceArn", tag.resourceArn());
        }
        return node;
    }

    private ObjectNode subnetGroup(ReplicationSubnetGroup group) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ReplicationSubnetGroupIdentifier", group.getReplicationSubnetGroupIdentifier());
        node.put("ReplicationSubnetGroupDescription", group.getReplicationSubnetGroupDescription());
        node.put("VpcId", group.getVpcId());
        node.put("SubnetGroupStatus", group.getSubnetGroupStatus());
        ArrayNode subnets = node.putArray("Subnets");
        group.getSubnetIds().forEach(subnetId -> {
            ObjectNode subnet = subnets.addObject();
            subnet.put("SubnetIdentifier", subnetId);
            subnet.putObject("SubnetAvailabilityZone")
                    .put("Name", group.getSubnetAvailabilityZones().get(subnetId));
            subnet.put("SubnetStatus", "Active");
        });
        ArrayNode networkTypes = node.putArray("SupportedNetworkTypes");
        group.getSupportedNetworkTypes().forEach(networkTypes::add);
        // Always false: a read-only group is one DMS manages for a zero-ETL integration, which
        // Floci does not emulate, so every group here is caller-owned and modifiable.
        node.put("IsReadOnly", false);
        return node;
    }
}
