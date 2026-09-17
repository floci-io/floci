package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationCode;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationTransaction;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CognitoOAuthController {

    private static final Logger LOG = Logger.getLogger(CognitoOAuthController.class);

    private final CognitoService cognitoService;
    private final ObjectMapper objectMapper;
    private final CognitoFederationService federationService;
    private final CognitoFederationStateStore stateStore;

    @Inject
    public CognitoOAuthController(CognitoService cognitoService, ObjectMapper objectMapper,
                                  CognitoFederationService federationService,
                                  CognitoFederationStateStore stateStore) {
        this.cognitoService = cognitoService;
        this.objectMapper = objectMapper;
        this.federationService = federationService;
        this.stateStore = stateStore;
    }

    @GET
    @Path("/cognito-idp/oauth2/authorize")
    public Response authorize(@Context ContainerRequestContext requestContext,
                              @QueryParam("client_id") String clientId,
                              @QueryParam("redirect_uri") String redirectUri,
                              @QueryParam("response_type") String responseType,
                              @QueryParam("scope") String scope,
                              @QueryParam("nonce") String nonce,
                              @QueryParam("identity_provider") String providerName,
                              @QueryParam("state") String relyingPartyState) {
        if (!"code".equals(trimToNull(responseType))) {
            return oauthError("unsupported_response_type", "Only response_type=code is supported");
        }
        if (trimToNull(clientId) == null || trimToNull(redirectUri) == null || trimToNull(providerName) == null) {
            return oauthError("invalid_request", "client_id, redirect_uri, and identity_provider are required");
        }

        UserPoolClient client;
        try {
            client = cognitoService.findClientById(clientId);
        } catch (AwsException e) {
            return oauthError("invalid_client", "Client not found");
        }
        String domainPoolId = (String) requestContext.getProperty(CognitoCustomDomainFilter.POOL_PROPERTY);
        if (domainPoolId != null && !domainPoolId.equals(client.getUserPoolId())) {
            return oauthError("invalid_client", "Client not found");
        }
        if (!client.isAllowedOAuthFlowsUserPoolClient() || !client.getAllowedOAuthFlows().contains("code")) {
            return oauthError("unauthorized_client", "Client is not allowed to use the authorization code flow");
        }
        if (!client.getCallbackURLs().contains(redirectUri)) {
            return oauthError("invalid_request", "redirect_uri is not registered for this client");
        }

        try {
            String location = federationService.beginAuthorization(client.getUserPoolId(), clientId, redirectUri,
                    splitScopes(scope), trimToNull(nonce), providerName, relyingPartyState);
            return Response.status(Response.Status.FOUND).location(URI.create(location)).build();
        } catch (AwsException e) {
            return oauthError("invalid_request", e.getMessage());
        }
    }

    @GET
    @Path("/cognito-idp/oauth2/idpresponse")
    public Response idpResponse(@Context ContainerRequestContext requestContext,
                                @QueryParam("state") String state,
                                @QueryParam("code") String providerCode,
                                @QueryParam("error") String error,
                                @QueryParam("error_description") String errorDescription) {
        String normalizedState = trimToNull(state);
        if (normalizedState == null) {
            return oauthError("invalid_request", "state is required");
        }
        Optional<CognitoAuthorizationTransaction> transaction = stateStore.consumeTransaction(normalizedState);
        if (transaction.isEmpty()) {
            return oauthError("invalid_request", "Invalid federation state");
        }
        String domainPoolId = (String) requestContext.getProperty(CognitoCustomDomainFilter.POOL_PROPERTY);
        if (domainPoolId != null && !domainPoolId.equals(transaction.get().userPoolId())) {
            return oauthError("invalid_request", "Invalid federation state");
        }
        if (trimToNull(error) != null) {
            Map<String, String> errorParameters = new LinkedHashMap<>();
            errorParameters.put("error", error);
            errorParameters.put("error_description", errorDescription == null ? "" : errorDescription);
            putRelyingPartyState(errorParameters, transaction.get());
            return redirect(transaction.get().redirectUri(), errorParameters);
        }
        if (trimToNull(providerCode) == null) {
            return oauthError("invalid_request", "code is required");
        }
        try {
            String authorizationCode = federationService.completeAuthorization(transaction.get(), providerCode);
            Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("code", authorizationCode);
            putRelyingPartyState(parameters, transaction.get());
            return redirect(transaction.get().redirectUri(), parameters);
        } catch (AwsException e) {
            return oauthError("invalid_request", e.getMessage());
        }
    }

    @POST
    @Path("/cognito-idp/oauth2/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response token(@HeaderParam("Authorization") String authorization,
                          @Context ContainerRequestContext requestContext,
                          MultivaluedMap<String, String> formParams) {
        String domainPoolId = (String) requestContext.getProperty(CognitoCustomDomainFilter.POOL_PROPERTY);
        return issueToken(authorization, formParams, domainPoolId);
    }

    private Response issueToken(String authorization, MultivaluedMap<String, String> formParams,
                                String domainPoolId) {
        String grantType = trimToNull(formParams.getFirst("grant_type"));
        if (grantType == null) {
            return oauthError("invalid_request", "grant_type is required");
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

        if (bodyClientSecret != null && basicClientSecret != null && !secretsEqual(bodyClientSecret, basicClientSecret)) {
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
        if ("authorization_code".equals(grantType)) {
            return redeemAuthorizationCode(clientId, clientSecret, formParams, domainPoolId);
        }
        if (!"client_credentials".equals(grantType)) {
            return oauthError("unsupported_grant_type", "Only client_credentials and authorization_code are supported");
        }
        String scope = trimToNull(formParams.getFirst("scope"));

        try {
            Map<String, Object> result = cognitoService.issueClientCredentialsToken(
                    clientId, clientSecret, scope, domainPoolId);
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

    private Response redeemAuthorizationCode(String clientId, String clientSecret,
                                             MultivaluedMap<String, String> formParams, String domainPoolId) {
        String code = trimToNull(formParams.getFirst("code"));
        String redirectUri = trimToNull(formParams.getFirst("redirect_uri"));
        if (code == null || redirectUri == null) {
            return oauthError("invalid_request", "code and redirect_uri are required");
        }
        UserPoolClient client;
        try {
            client = cognitoService.findClientById(clientId);
        } catch (AwsException e) {
            return oauthError("invalid_client", "Client not found");
        }
        if (client.getClientSecret() != null && !client.getClientSecret().isBlank()
                && !secretsEqual(client.getClientSecret(), clientSecret)) {
            return oauthError("invalid_client", "Client secret is invalid");
        }

        Optional<CognitoAuthorizationCode> authorizationCode = stateStore.findAuthorizationCode(code);
        if (authorizationCode.isEmpty()) {
            return oauthError("invalid_grant", "Authorization code is invalid or has expired");
        }
        CognitoAuthorizationCode storedCode = authorizationCode.get();
        if (!clientId.equals(storedCode.clientId()) || !redirectUri.equals(storedCode.redirectUri())
                || (domainPoolId != null && !domainPoolId.equals(storedCode.userPoolId()))
                || !storedCode.userPoolId().equals(client.getUserPoolId())) {
            return oauthError("invalid_grant", "Authorization code was not issued to this client");
        }
        Optional<CognitoAuthorizationCode> consumed = stateStore.consumeAuthorizationCode(code);
        if (consumed.isEmpty()) {
            return oauthError("invalid_grant", "Authorization code is invalid or has expired");
        }
        CognitoAuthorizationCode consumedCode = consumed.get();
        try {
            UserPool pool = cognitoService.describeUserPool(consumedCode.userPoolId());
            CognitoUser user = cognitoService.adminGetUser(consumedCode.userPoolId(), consumedCode.userId());
            Map<String, Object> authentication = cognitoService.generateAuthResult(user, pool, client, null);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("access_token", (String) authentication.get("AccessToken"));
            body.put("id_token", (String) authentication.get("IdToken"));
            body.put("refresh_token", (String) authentication.get("RefreshToken"));
            body.put("expires_in", ((Number) authentication.get("ExpiresIn")).longValue());
            body.put("token_type", (String) authentication.get("TokenType"));
            return Response.ok(body).type(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store").header("Pragma", "no-cache").build();
        } catch (AwsException e) {
            LOG.error("Failed to redeem Cognito authorization code", e);
            return oauthError("invalid_grant", e.getMessage());
        }
    }

    private Response redirect(String redirectUri, Map<String, String> parameters) {
        StringBuilder location = new StringBuilder(redirectUri);
        location.append(redirectUri.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (!first) {
                location.append('&');
            }
            location.append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8));
            location.append('=').append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return Response.status(Response.Status.FOUND).location(URI.create(location.toString())).build();
    }

    private boolean secretsEqual(String expected, String actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private void putRelyingPartyState(Map<String, String> parameters, CognitoAuthorizationTransaction transaction) {
        if (transaction.relyingPartyState() != null) {
            parameters.put("state", transaction.relyingPartyState());
        }
    }

    private List<String> splitScopes(String scope) {
        String normalizedScope = trimToNull(scope);
        if (normalizedScope == null) {
            return List.of();
        }
        List<String> scopes = new ArrayList<>();
        for (String value : normalizedScope.split("\\s+")) {
            if (!value.isBlank()) {
                scopes.add(value);
            }
        }
        return scopes;
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
