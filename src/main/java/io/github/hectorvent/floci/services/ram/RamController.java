package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS Resource Access Manager (Smithy restJson1) — organization-sharing opt-in.
 *
 * <p>{@code EnableSharingWithAwsOrganization} is the only RAM action AWS Landing Zone
 * Accelerator's default path calls; it succeeds without a request body. The literal path takes
 * JAX-RS precedence over S3's {@code /{bucket}} template route, so no extra routing wiring is
 * needed.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RamController {

    private final RamService service;
    private final ObjectMapper objectMapper;

    @Inject
    public RamController(RamService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/enablesharingwithawsorganization")
    @Consumes(MediaType.WILDCARD)
    public Response enableSharingWithAwsOrganization() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("returnValue", service.enableSharingWithAwsOrganization());
        return Response.ok(response).build();
    }
}
