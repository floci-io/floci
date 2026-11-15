package io.floci.aws.services.account;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.JsonErrorResponseUtils;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.account.model.AlternateContact;
import io.floci.aws.services.account.model.ContactInformation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AccountController {
    // Floci has no phone verification flow, so every account reports this status.
    private static final String VERIFICATION_STATUS_NOT_SUPPORTED = "NOT_SUPPORTED";

    private final AccountService accountService;
    private final ObjectMapper objectMapper;
    private final RequestContext requestContext;

    @Inject
    public AccountController(AccountService accountService, ObjectMapper objectMapper, RequestContext requestContext) {
        this.accountService = accountService;
        this.objectMapper = objectMapper;
        this.requestContext = requestContext;
    }

    @POST
    @Path("/putAlternateContact")
    public Response putAlternateContact(String body) {
        accountService.putAlternateContact(requestContext.getAccountId(), readTree(body));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/getAlternateContact")
    public Response getAlternateContact(String body) {
        AlternateContact contact = accountService.getAlternateContact(requestContext.getAccountId(), readTree(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("AlternateContact", objectMapper.valueToTree(contact));
        return Response.ok(response).build();
    }

    @POST
    @Path("/deleteAlternateContact")
    public Response deleteAlternateContact(String body) {
        accountService.deleteAlternateContact(requestContext.getAccountId(), readTree(body));
        return Response.ok().build();
    }

    @POST
    @Path("/putContactInformation")
    public Response putContactInformation(String body) {
        accountService.putContactInformation(requestContext.getAccountId(), readTree(body));
        return Response.ok().build();
    }

    @POST
    @Path("/getContactInformation")
    public Response getContactInformation(String body) {
        ContactInformation information = accountService.getContactInformation(
                requestContext.getAccountId(), readTree(body));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ContactInformation", objectMapper.valueToTree(information));
        response.put("VerificationStatus", VERIFICATION_STATUS_NOT_SUPPORTED);
        return Response.ok(response).build();
    }

    private JsonNode readTree(String body) {
        JsonNode request;
        try {
            request = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
        if (!request.isObject()) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
        return request;
    }
}
