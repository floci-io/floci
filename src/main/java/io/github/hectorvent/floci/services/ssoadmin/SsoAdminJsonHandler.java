package io.github.hectorvent.floci.services.ssoadmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class SsoAdminJsonHandler {

    private final SsoAdminService service;
    private final ObjectMapper mapper;

    @Inject
    public SsoAdminJsonHandler(SsoAdminService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String callerAccountId) {
        return switch (action) {
            case "ListInstances" -> listInstances(callerAccountId);
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    private Response listInstances(String callerAccountId) {
        ObjectNode response = mapper.createObjectNode();
        ObjectNode instance = response.putArray("Instances").addObject();
        instance.put("InstanceArn", service.getInstanceArn());
        instance.put("IdentityStoreId", service.getIdentityStoreId());
        instance.put("Name", "floci-identity-center");
        instance.put("OwnerAccountId", callerAccountId);
        instance.put("Status", "ACTIVE");
        return Response.ok(response).build();
    }
}
