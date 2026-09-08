package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Implements the Cognito hosted-UI {@code /oauth2/userInfo} endpoint. Real
 * Cognito serves this route but the SDK API does not expose it, so SDK-based
 * mocks alone are insufficient for OIDC clients that resolve a user's profile
 * via this endpoint.
 *
 * <p>Bearer access tokens are verified by the shared Cognito service verifier before
 * {@code iss} and {@code sub} are used to resolve the user. Attributes are returned
 * with their raw Cognito names (including the {@code custom:*} prefix) so downstream
 * Jackson mappings such as {@code @JsonProperty("custom:my_attribute")} resolve correctly.
 *
 * <p>The response shape mirrors real AWS Cognito: snake_case keys for OIDC
 * standard claims and string-valued {@code email_verified} /
 * {@code phone_number_verified}. Error responses follow the OAuth 2.0
 * Bearer-token error convention — status code plus a {@code WWW-Authenticate}
 * header with {@code error} / {@code error_description} parameters and no
 * response body — matching the behaviour documented for Cognito.
 */
@ApplicationScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CognitoUserInfoController {

    private static final Logger LOG = Logger.getLogger(CognitoUserInfoController.class);

    private final CognitoService cognitoService;
    private final ObjectMapper objectMapper;

    @Inject
    public CognitoUserInfoController(CognitoService cognitoService, ObjectMapper objectMapper) {
        this.cognitoService = cognitoService;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/cognito-idp/oauth2/userInfo")
    public Response userInfo(@HeaderParam("Authorization") String authorization,
                             @Context ContainerRequestContext requestContext) {
        if (authorization == null || authorization.isBlank()
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return bearerError(401, "invalid_token", "Bearer token required");
        }
        String token = authorization.substring(7).trim();
        CognitoService.VerifiedAccessToken verified;
        try {
            verified = cognitoService.verifyAccessToken(token);
        } catch (AwsException e) {
            LOG.debug("Access token verification failed", e);
            return bearerError(401, "invalid_token", e.getMessage());
        }

        String sub = verified.subject();
        String poolId = verified.poolId();
        String domainPoolId = (String) requestContext.getProperty(CognitoCustomDomainFilter.POOL_PROPERTY);
        if (domainPoolId != null && !domainPoolId.equals(poolId)) {
            return bearerError(401, "invalid_token", "Token was not issued by the user pool of this domain");
        }

        CognitoUser user;
        try {
            List<CognitoUser> matches = cognitoService.listUsers(poolId, "sub=\"" + sub + "\"");
            if (matches.isEmpty()) {
                return bearerError(401, "invalid_token", "Token subject does not resolve to a user in pool " + poolId);
            }
            user = matches.getFirst();
        } catch (AwsException e) {
            return bearerError(e.getHttpStatus() == 404 ? 401 : e.getHttpStatus(),
                    "invalid_token", e.getMessage());
        }

        ObjectNode body = objectMapper.createObjectNode();
        Map<String, String> attrs = user.getAttributes();
        body.put("sub", attrs.getOrDefault("sub", sub));
        body.put("username", user.getUsername());
        putIfPresent(body, "email", attrs.get("email"));
        putVerifiedFlag(body, "email_verified", attrs.get("email_verified"));
        putIfPresent(body, "phone_number", attrs.get("phone_number"));
        putVerifiedFlag(body, "phone_number_verified", attrs.get("phone_number_verified"));
        for (String standardClaim : OIDC_PROFILE_CLAIMS) {
            putIfPresent(body, standardClaim, attrs.get(standardClaim));
        }
        for (Map.Entry<String, String> e : attrs.entrySet()) {
            if (e.getKey().startsWith("custom:") && e.getValue() != null) {
                body.put(e.getKey(), e.getValue());
            }
        }
        return Response.ok(body)
                .header("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate")
                .header("Pragma", "no-cache")
                .build();
    }

    private static final List<String> OIDC_PROFILE_CLAIMS = List.of(
            "name", "family_name", "given_name", "middle_name", "nickname",
            "preferred_username", "profile", "picture", "website", "gender",
            "birthdate", "zoneinfo", "locale", "updated_at", "address");


    private static void putIfPresent(ObjectNode body, String key, String value) {
        if (value != null && !value.isEmpty()) {
            body.put(key, value);
        }
    }

    private static void putVerifiedFlag(ObjectNode body, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        body.put(key, "true".equalsIgnoreCase(value) ? "true" : "false");
    }

    private Response bearerError(int status, String code, String description) {
        String sanitized = description == null ? "" : description.replace("\"", "'");
        return Response.status(status)
                .header("WWW-Authenticate",
                        "Bearer error=\"" + code + "\", error_description=\"" + sanitized + "\"")
                .header("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate")
                .header("Pragma", "no-cache")
                .build();
    }
}
