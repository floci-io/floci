package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.account.model.AlternateContact;
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

    private JsonNode readTree(String body) {
        try {
            return objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
