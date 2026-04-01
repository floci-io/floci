package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@ApplicationScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CognitoOAuthController {

    private static final Logger LOG = Logger.getLogger(CognitoOAuthController.class);

    private final CognitoService cognitoService;
    private final ObjectMapper objectMapper;

    @Inject
    public CognitoOAuthController(CognitoService cognitoService, ObjectMapper objectMapper) {
        this.cognitoService = cognitoService;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/cognito-idp/oauth2/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response token(@HeaderParam("Authorization") String authorization,
                          MultivaluedMap<String, String> formParams) {
        return issueToken(authorization, formParams);
    }

    private Response issueToken(String authorization, MultivaluedMap<String, String> formParams) {
        String grantType = trimToNull(formParams.getFirst("grant_type"));
        if (grantType == null) {
            return oauthError("invalid_request", "grant_type is required");
        }
        if (!"client_credentials".equals(grantType)) {
            return oauthError("unsupported_grant_type", "Only client_credentials is supported");
        }

        BasicCredentials basicCredentials;
        try {
            basicCredentials = parseBasicCredentials(authorization);
        } catch (IllegalArgumentException e) {
            return oauthError("invalid_request", e.getMessage());
        }

        String bodyClientId = trimToNull(formParams.getFirst("client_id"));
        String bodyClientSecret = trimToNull(formParams.getFirst("client_secret"));
        String basicClientId = basicCredentials != null ? basicCredentials.clientId() : null;
        String basicClientSecret = basicCredentials != null ? basicCredentials.clientSecret() : null;

        if (bodyClientSecret != null && basicClientSecret != null && !bodyClientSecret.equals(basicClientSecret)) {
            return oauthError("invalid_request", "client_secret does not match Authorization header");
        }

        if (bodyClientId != null && basicClientId != null && !bodyClientId.equals(basicClientId)) {
            return oauthError("invalid_request", "client_id does not match Authorization header");
        }

        String clientId = bodyClientId != null ? bodyClientId : basicClientId;
        if (clientId == null) {
            return oauthError("invalid_request", "client_id is required");
        }

        String clientSecret = bodyClientSecret != null ? bodyClientSecret : basicClientSecret;
        String scope = trimToNull(formParams.getFirst("scope"));

        try {
            Map<String, Object> result = cognitoService.issueClientCredentialsToken(clientId, clientSecret, scope);
            return Response.ok(objectMapper.valueToTree(result))
                    .type(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store")
                    .header("Pragma", "no-cache")
                    .build();
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return oauthError("invalid_client", "Client not found");
            }
            if ("InvalidClientException".equals(e.getErrorCode())) {
                return oauthError("invalid_client", e.getMessage());
            }
            if ("UnauthorizedClientException".equals(e.getErrorCode())) {
                return oauthError("unauthorized_client", e.getMessage());
            }
            if ("InvalidScopeException".equals(e.getErrorCode())) {
                return oauthError("invalid_scope", e.getMessage());
            }
            LOG.error("Failed to issue Cognito OAuth token", e);
            return oauthError("invalid_request", e.getMessage());
        }
    }

    private Response oauthError(String error, String description) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("error", error);
        body.put("error_description", description);
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .entity(body)
                .build();
    }

    private BasicCredentials parseBasicCredentials(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }

        String encoded = authorization.substring(6).trim();
        if (encoded.isEmpty()) {
            throw new IllegalArgumentException("Basic Authorization header is malformed");
        }

        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator < 0) {
                throw new IllegalArgumentException("Basic Authorization header is malformed");
            }
            return new BasicCredentials(
                    trimToNull(decoded.substring(0, separator)),
                    trimToNull(decoded.substring(separator + 1))
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Basic Authorization header is malformed");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record BasicCredentials(String clientId, String clientSecret) {
    }
}
