package io.github.hectorvent.floci.services.route53resolver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * JSON 1.1 handler for Route 53 Resolver operations.
 * Dispatches {@code X-Amz-Target: Route53Resolver.*} actions to {@link Route53ResolverService}.
 *
 * @see <a href="https://docs.aws.amazon.com/Route53/latest/APIReference/API_Operations_Amazon_Route_53_Resolver.html">Route 53 Resolver API</a>
 */
@ApplicationScoped
public class Route53ResolverJsonHandler {

    private static final Logger LOG = Logger.getLogger(Route53ResolverJsonHandler.class);

    private final Route53ResolverService service;
    private final ObjectMapper objectMapper;

    @Inject
    public Route53ResolverJsonHandler(Route53ResolverService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region, String accountId) {
        LOG.debugv("Route53Resolver action: {0}", action);
        try {
            return switch (action) {
                case "ListFirewallDomainLists" -> handleListFirewallDomainLists(region);
                case "GetFirewallDomainList" -> handleGetFirewallDomainList(request, region);
                case "CreateFirewallDomainList" -> firewallDomainListResponse(
                        service.createFirewallDomainList(request, region, accountId));
                case "DeleteFirewallDomainList" -> firewallDomainListResponse(
                        service.deleteFirewallDomainList(text(request, "FirewallDomainListId")));

                case "CreateResolverEndpoint" -> resolverEndpointResponse(
                        service.createResolverEndpoint(request, region, accountId));
                case "DeleteResolverEndpoint" -> resolverEndpointResponse(
                        service.deleteResolverEndpoint(text(request, "ResolverEndpointId")));
                case "GetResolverEndpoint" -> resolverEndpointResponse(
                        service.getResolverEndpoint(text(request, "ResolverEndpointId")));
                case "ListResolverEndpoints" -> handleListResolverEndpoints();
                case "UpdateResolverEndpoint" -> resolverEndpointResponse(
                        service.updateResolverEndpoint(text(request, "ResolverEndpointId"), request));

                case "CreateResolverRule" -> resolverRuleResponse(
                        service.createResolverRule(request, region, accountId));
                case "DeleteResolverRule" -> resolverRuleResponse(
                        service.deleteResolverRule(text(request, "ResolverRuleId")));
                case "GetResolverRule" -> resolverRuleResponse(
                        service.getResolverRule(text(request, "ResolverRuleId")));
                case "ListResolverRules" -> handleListResolverRules();
                case "UpdateResolverRule" -> resolverRuleResponse(
                        service.updateResolverRule(text(request, "ResolverRuleId"), request.path("Config")));

                case "AssociateResolverRule" -> resolverRuleAssociationResponse(
                        service.associateResolverRule(request));
                case "DisassociateResolverRule" -> resolverRuleAssociationResponse(
                        service.disassociateResolverRule(request));
                case "GetResolverRuleAssociation" -> resolverRuleAssociationResponse(
                        service.getResolverRuleAssociation(text(request, "ResolverRuleAssociationId")));
                case "ListResolverRuleAssociations" -> handleListResolverRuleAssociations();

                default -> Response.status(400)
                        .entity(new AwsErrorResponse("UnknownOperationException",
                                "Unknown operation: Route53Resolver." + action))
                        .build();
            };
        } catch (io.github.hectorvent.floci.core.common.AwsException e) {
            return Response.status(e.getHttpStatus())
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage())).build();
        }
    }

    private String text(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    // ---------- Firewall domain lists ----------

    private Response handleListFirewallDomainLists(String region) {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode lists = result.putArray("FirewallDomainLists");
        for (Route53ResolverService.FirewallDomainList list : service.listFirewallDomainLists(region)) {
            ObjectNode node = lists.addObject();
            node.put("Arn", list.arn());
            node.put("CreatorRequestId", "AWSManaged");
            node.put("Id", list.id());
            node.put("ManagedOwnerName", list.managedOwnerName());
            node.put("Name", list.name());
        }
        for (ObjectNode custom : service.listCustomFirewallDomainLists()) {
            lists.add(custom);
        }
        return Response.ok(result).build();
    }

    private Response handleGetFirewallDomainList(JsonNode request, String region) {
        String id = text(request, "FirewallDomainListId");
        var custom = service.getCustomFirewallDomainList(id);
        if (custom.isPresent()) {
            return firewallDomainListResponse(custom.get());
        }
        Route53ResolverService.FirewallDomainList list = service.getFirewallDomainList(region, id);

        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode node = result.putObject("FirewallDomainList");
        node.put("Arn", list.arn());
        node.put("CreatorRequestId", "AWSManaged");
        node.put("Id", list.id());
        node.put("ManagedOwnerName", list.managedOwnerName());
        node.put("Name", list.name());

        return Response.ok(result).build();
    }

    private Response firewallDomainListResponse(ObjectNode list) {
        return Response.ok(objectMapper.createObjectNode().set("FirewallDomainList", list)).build();
    }

    // ---------- Resolver endpoints ----------

    private Response resolverEndpointResponse(ObjectNode endpoint) {
        return Response.ok(objectMapper.createObjectNode().set("ResolverEndpoint", endpoint)).build();
    }

    private Response handleListResolverEndpoints() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode endpoints = result.putArray("ResolverEndpoints");
        for (ObjectNode endpoint : service.listResolverEndpoints()) {
            endpoints.add(endpoint);
        }
        return Response.ok(result).build();
    }

    // ---------- Resolver rules ----------

    private Response resolverRuleResponse(ObjectNode rule) {
        return Response.ok(objectMapper.createObjectNode().set("ResolverRule", rule)).build();
    }

    private Response handleListResolverRules() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode rules = result.putArray("ResolverRules");
        for (ObjectNode rule : service.listResolverRules()) {
            rules.add(rule);
        }
        return Response.ok(result).build();
    }

    // ---------- Resolver rule associations ----------

    private Response resolverRuleAssociationResponse(ObjectNode association) {
        return Response.ok(objectMapper.createObjectNode()
                .set("ResolverRuleAssociation", association)).build();
    }

    private Response handleListResolverRuleAssociations() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode associations = result.putArray("ResolverRuleAssociations");
        for (ObjectNode association : service.listResolverRuleAssociations()) {
            associations.add(association);
        }
        return Response.ok(result).build();
    }
}
