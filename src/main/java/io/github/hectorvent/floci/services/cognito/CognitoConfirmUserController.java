package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The page a {@code CONFIRM_WITH_LINK} sign-up email links to. On AWS this is the hosted UI
 * {@code /confirmUser} endpoint: it confirms the user with the code in the query string and shows
 * the result. A custom domain is rewritten onto this path by {@link CognitoCustomDomainFilter}.
 */
@ApplicationScoped
@Path("/")
public class CognitoConfirmUserController {

    private final CognitoService cognitoService;

    @Inject
    public CognitoConfirmUserController(CognitoService cognitoService) {
        this.cognitoService = cognitoService;
    }

    @GET
    @Path("/cognito-idp/confirmUser")
    @Produces(MediaType.TEXT_HTML)
    public Response confirmUser(@Context ContainerRequestContext requestContext,
                                @QueryParam("client_id") String clientId,
                                @QueryParam("user_name") String username,
                                @QueryParam("confirmation_code") String confirmationCode) {
        if (isBlank(clientId) || isBlank(username) || isBlank(confirmationCode)) {
            return page(400, "Confirmation failed",
                    "client_id, user_name, and confirmation_code are required.");
        }
        try {
            UserPoolClient client = cognitoService.findClientById(clientId);
            String domainPoolId = (String) requestContext.getProperty(CognitoCustomDomainFilter.POOL_PROPERTY);
            if (domainPoolId != null && !domainPoolId.equals(client.getUserPoolId())) {
                return page(400, "Confirmation failed", "Client not found.");
            }
            cognitoService.confirmSignUp(clientId, username, confirmationCode, Map.of());
            return page(200, "Account confirmed", "Your account has been confirmed.");
        } catch (AwsException e) {
            return page(400, "Confirmation failed", e.getMessage());
        }
    }

    private static Response page(int status, String title, String message) {
        String html = "<!DOCTYPE html><html><head><title>" + escape(title) + "</title></head><body><h1>"
                + escape(title) + "</h1><p>" + escape(message) + "</p></body></html>";
        return Response.status(status).type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).entity(html).build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
