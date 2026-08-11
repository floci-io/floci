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

    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Route53Resolver action: {0}", action);
        return switch (action) {
            case "ListFirewallDomainLists" -> handleListFirewallDomainLists(region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Route53Resolver." + action))
                    .build();
        };
    }

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
        return Response.ok(result).build();
    }
}
