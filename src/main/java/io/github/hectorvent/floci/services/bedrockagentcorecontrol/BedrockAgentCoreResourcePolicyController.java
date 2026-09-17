package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/resourcepolicy")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreResourcePolicyController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreResourcePolicyController.class);

    private final BedrockAgentCoreResourcePolicyService service;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreResourcePolicyController(BedrockAgentCoreResourcePolicyService service,
                                                    ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/{resourceArn:.+}")
    public Response getResourcePolicy(@PathParam("resourceArn") String resourceArn) {
        try {
            ObjectNode out = objectMapper.createObjectNode();
            out.put("policy", service.get(resourceArn));
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "getting resource policy");
        }
    }

    @PUT
    @Path("/{resourceArn:.+}")
    public Response putResourcePolicy(@PathParam("resourceArn") String resourceArn, String body) {
        try {
            JsonNode request = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            if (!request.isObject() || !request.hasNonNull("policy")) {
                throw new AwsException("ValidationException", "policy is required", 400);
            }
            ObjectNode out = objectMapper.createObjectNode();
            out.put("policy", service.put(resourceArn, request.get("policy").asText()));
            return Response.status(201).entity(out).build();
        } catch (Exception e) {
            return error(e, "putting resource policy");
        }
    }

    @DELETE
    @Path("/{resourceArn:.+}")
    public Response deleteResourcePolicy(@PathParam("resourceArn") String resourceArn) {
        try {
            service.delete(resourceArn);
            return Response.noContent().build();
        } catch (Exception e) {
            return error(e, "deleting resource policy");
        }
    }

    private Response error(Exception e, String action) {
        return BedrockAgentCoreControllerSupport.error(LOG, e, action);
    }
}
