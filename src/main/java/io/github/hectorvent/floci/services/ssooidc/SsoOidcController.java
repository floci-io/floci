package io.github.hectorvent.floci.services.ssooidc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.ssooidc.model.AuthorizationCode;
import io.github.hectorvent.floci.services.ssooidc.model.DeviceAuthorization;
import io.github.hectorvent.floci.services.ssooidc.model.RegisteredClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.ssoadmin.SsoAdminService;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationAccessScope;
import io.github.hectorvent.floci.services.ssoadmin.model.ApplicationGrant;
import io.github.hectorvent.floci.services.ssooidc.model.TokenSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class SsoOidcController {
    private static final Set<String> IAM_GRANT_TYPES = Set.of(
            "authorization_code",
            "refresh_token",
            "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "urn:ietf:params:oauth:grant-type:token-exchange");

    private final SsoOidcService service;
    private final SsoAdminService ssoAdminService;
    private final RequestContext requestContext;
    private final ObjectMapper objectMapper;
    private final String localPrincipalId;

    @Inject
    public SsoOidcController(SsoOidcService service, SsoAdminService ssoAdminService,
                             RequestContext requestContext, ObjectMapper objectMapper, EmulatorConfig config) {
        this.service = service;
        this.ssoAdminService = ssoAdminService;
        this.requestContext = requestContext;
        this.objectMapper = objectMapper;
        this.localPrincipalId = config.services().ssooidc().localPrincipalId().filter(id -> !id.isBlank()).orElse(null);
    }

    @POST
    @Path("/client/register")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response registerClient(String body) {
        try {
            RegisteredClient client = service.registerClient(readTree(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("clientId", client.clientId());
            response.put("clientSecret", client.clientSecret());
            response.put("clientIdIssuedAt", client.clientIdIssuedAt());
            response.put("clientSecretExpiresAt", client.clientSecretExpiresAt());
            response.put("authorizationEndpoint", service.authorizationEndpoint());
            response.put("tokenEndpoint", service.tokenEndpoint());
            return Response.ok(response).build();
        } catch (SsoOidcException e) {
            return oidcError(e.status(), e.error(), e.getMessage());
        } catch (RuntimeException e) {
            return oidcError(500, "server_error", "The request could not be processed");
        }
    }

    @POST
    @Path("/device_authorization")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response startDeviceAuthorization(String body) {
        try {
            DeviceAuthorization authorization = service.startDeviceAuthorization(readTree(body));
            ObjectNode response = objectMapper.createObjectNode();
            response.put("deviceCode", authorization.deviceCode());
            response.put("userCode", authorization.userCode());
            response.put("verificationUri", service.verificationUri());
            response.put("verificationUriComplete", service.verificationUriComplete(authorization));
            response.put("expiresIn", (int) (authorization.expiresAtEpochSeconds()
                    - System.currentTimeMillis() / 1000L));
            response.put("interval", authorization.intervalSeconds());
            return Response.ok(response).build();
        } catch (SsoOidcException e) {
            return oidcError(e.status(), e.error(), e.getMessage());
        } catch (RuntimeException e) {
            return oidcError(500, "server_error", "The request could not be processed");
        }
    }

    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createToken(String body,
                                @QueryParam("aws_iam") String awsIam,
                                @HeaderParam("Authorization") String authorizationHeader) {
        try {
            JsonNode request = readTree(body);
            if ("t".equals(awsIam)) {
                return createTokenWithIam(request, authorizationHeader);
            }
            TokenSession session = service.createToken(request);
            return tokenResponse(session, null, null, null);
        } catch (SsoOidcException e) {
            return oidcError(e.status(), e.error(), e.getMessage());
        } catch (RuntimeException e) {
            return oidcError(500, "server_error", "The request could not be processed");
        }
    }

    private Response createTokenWithIam(JsonNode request, String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("AWS4-HMAC-SHA256 ")) {
            throw new SsoOidcException("access_denied", "CreateTokenWithIAM requires Signature Version 4", 400);
        }
        String applicationArn = requiredText(request, "clientId");
        String grantType = requiredText(request, "grantType");
        if (!IAM_GRANT_TYPES.contains(grantType)) {
            throw new SsoOidcException("unsupported_grant_type", "Unsupported grant type: " + grantType, 400);
        }
        try {
            ssoAdminService.applicationForOidc(applicationArn);
        } catch (RuntimeException e) {
            throw new SsoOidcException("invalid_client", "Application is invalid", 401);
        }
        if (!ssoAdminService.iamActorPolicyAllows(applicationArn, requestContext.getAccountId())) {
            throw new SsoOidcException("access_denied", "The IAM principal is not authorized by the application policy", 400);
        }

        ApplicationGrant grant;
        try {
            grant = ssoAdminService.applicationGrantForOidc(applicationArn, grantType);
        } catch (RuntimeException e) {
            throw new SsoOidcException("unauthorized_client", "The application is not configured for this grant", 400);
        }
        List<String> scopes;
        if ("refresh_token".equals(grantType) && (request.get("scope") == null || request.get("scope").isNull())) {
            scopes = service.requireRefreshToken(applicationArn, requiredText(request, "refreshToken")).scopes();
        } else {
            scopes = resolveIamScopes(applicationArn, request);
        }
        if ("urn:ietf:params:oauth:grant-type:jwt-bearer".equals(grantType)) {
            validateJwtBearerGrant(request, grant);
        }
        if ("urn:ietf:params:oauth:grant-type:token-exchange".equals(grantType)) {
            validateTokenExchangeTarget(request, applicationArn);
        }
        TokenSession session = service.createIamToken(request, applicationArn, scopes);
        String issuedTokenType = "urn:ietf:params:oauth:token-type:access_token";
        if ("urn:ietf:params:oauth:grant-type:token-exchange".equals(grantType)) {
            String requested = SsoOidcService.optionalText(request, "requestedTokenType");
            if (requested != null) {
                issuedTokenType = requested;
            }
        }
        String idToken = localIdToken(applicationArn, requestContext.getAccountId());
        String identityContext = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (requestContext.getAccountId() + ":" + applicationArn).getBytes(StandardCharsets.UTF_8));
        return tokenResponse(session, scopes, issuedTokenType, new String[]{idToken, identityContext});
    }

    private Response tokenResponse(TokenSession session, List<String> scopes,
                                   String issuedTokenType, String[] iamDetails) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("accessToken", session.accessToken());
        if (session.refreshToken() != null) {
            response.put("refreshToken", session.refreshToken());
        }
        response.put("expiresIn", Math.max(0, (int) (session.accessTokenExpiresAtEpochSeconds()
                - System.currentTimeMillis() / 1000L)));
        response.put("tokenType", "Bearer");
        if (scopes != null) {
            var scopeArray = response.putArray("scope");
            scopes.forEach(scopeArray::add);
        }
        if (issuedTokenType != null) {
            response.put("issuedTokenType", issuedTokenType);
        }
        if (iamDetails != null) {
            response.put("idToken", iamDetails[0]);
            response.putObject("awsAdditionalDetails").put("identityContext", iamDetails[1]);
        }
        return Response.ok(response).build();
    }

    private List<String> resolveIamScopes(String applicationArn, JsonNode request) {
        LinkedHashSet<String> configured = new LinkedHashSet<>();
        configured.add("openid");
        configured.add("aws");
        configured.add("sts:identity_context");
        ssoAdminService.applicationAccessScopesForOidc(applicationArn).stream()
                .map(ApplicationAccessScope::scope)
                .forEach(configured::add);
        JsonNode requested = request.get("scope");
        if (requested == null || requested.isNull()) {
            return List.copyOf(configured);
        }
        if (!requested.isArray()) {
            throw new SsoOidcException("invalid_request", "scope must be an array", 400);
        }
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        for (JsonNode scope : requested) {
            if (!scope.isTextual() || !configured.contains(scope.textValue())) {
                throw new SsoOidcException("invalid_scope", "Requested scope is not configured for the application", 400);
            }
            scopes.add(scope.textValue());
        }
        return List.copyOf(scopes);
    }

    private void validateJwtBearerGrant(JsonNode request, ApplicationGrant grant) {
        String assertion = requiredText(request, "assertion");
        String[] parts = assertion.split("\\.", -1);
        if (parts.length != 3) {
            throw new SsoOidcException("invalid_grant", "assertion must be a JWT", 400);
        }
        JsonNode claims;
        try {
            claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (Exception e) {
            throw new SsoOidcException("invalid_grant", "assertion JWT payload is invalid", 400);
        }
        if (claims.has("exp") && claims.path("exp").asLong(0) <= Instant.now().getEpochSecond()) {
            throw new SsoOidcException("expired_token", "assertion has expired", 400);
        }
        String issuer = claims.path("iss").asText(null);
        if (issuer == null) {
            throw new SsoOidcException("invalid_grant", "assertion must contain iss", 400);
        }
        Set<String> audiences = jwtAudiences(claims.get("aud"));
        JsonNode configuredIssuers = grant.grant().path("JwtBearer").path("AuthorizedTokenIssuers");
        boolean authorized = false;
        for (JsonNode configuredIssuer : configuredIssuers) {
            String issuerArn = configuredIssuer.path("TrustedTokenIssuerArn").asText(null);
            if (issuerArn == null) {
                continue;
            }
            try {
                var trusted = ssoAdminService.getTrustedTokenIssuer(issuerArn);
                if (!issuer.equals(trusted.oidcJwtConfiguration().issuerUrl())) {
                    continue;
                }
                JsonNode configuredAudiences = configuredIssuer.get("AuthorizedAudiences");
                if (configuredAudiences == null || configuredAudiences.isNull()) {
                    authorized = true;
                    break;
                }
                for (JsonNode audience : configuredAudiences) {
                    if (audience.isTextual() && audiences.contains(audience.textValue())) {
                        authorized = true;
                        break;
                    }
                }
                if (authorized) {
                    break;
                }
            } catch (RuntimeException ignored) {
                // A missing configured issuer cannot authorize this assertion.
            }
        }
        if (!authorized) {
            throw new SsoOidcException("invalid_grant", "assertion issuer or audience is not authorized", 400);
        }
    }

    private static Set<String> jwtAudiences(JsonNode aud) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (aud == null) {
            return result;
        }
        if (aud.isTextual()) {
            result.add(aud.textValue());
        } else if (aud.isArray()) {
            for (JsonNode value : aud) {
                if (value.isTextual()) {
                    result.add(value.textValue());
                }
            }
        }
        return result;
    }

    private void validateTokenExchangeTarget(JsonNode request, String targetApplicationArn) {
        String subjectToken = requiredText(request, "subjectToken");
        TokenSession subject = service.requireAccessToken(subjectToken);
        if (targetApplicationArn.equals(subject.clientId()) || !subject.clientId().startsWith("arn:")) {
            throw new SsoOidcException("invalid_grant", "Subject token must come from a different IAM application", 400);
        }
        boolean authorizedTarget = ssoAdminService.applicationAccessScopesForOidc(subject.clientId()).stream()
                .filter(scope -> subject.scopes().contains(scope.scope()))
                .anyMatch(scope -> scope.authorizedTargets().contains(targetApplicationArn));
        if (!authorizedTarget) {
            throw new SsoOidcException("invalid_grant", "Subject token does not authorize the requested application", 400);
        }
    }

    private String localIdToken(String applicationArn, String accountId) {
        ObjectNode header = objectMapper.createObjectNode().put("alg", "none").put("typ", "JWT");
        long now = Instant.now().getEpochSecond();
        ObjectNode claims = objectMapper.createObjectNode()
                .put("sub", accountId)
                .put("aud", applicationArn)
                .put("iat", now)
                .put("exp", now + 3600);
        return base64Url(header.toString()) + "." + base64Url(claims.toString()) + ".";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String resolveLocalPrincipal(String requestedPrincipalId) {
        if (requestedPrincipalId != null && !requestedPrincipalId.equals(localPrincipalId)) {
            throw new SsoOidcException("access_denied",
                    "principal_id is not authorized by the local OIDC configuration", 403);
        }
        return localPrincipalId;
    }

    private static String requiredText(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new SsoOidcException("invalid_request", field + " is required", 400);
        }
        return value.textValue();
    }

    @GET
    @Path("/device")
    public Response authorizeDevice(@QueryParam("user_code") String userCode,
                                    @QueryParam("principal_id") String principalId) {
        try {
            DeviceAuthorization authorization = service.authorizeDevice(userCode, resolveLocalPrincipal(principalId));
            return Response.ok(objectMapper.createObjectNode()
                    .put("status", "authorized")
                    .put("userCode", authorization.userCode())).build();
        } catch (SsoOidcException e) {
            return oidcError(e.status(), e.error(), e.getMessage());
        }
    }

    @GET
    @Path("/authorize")
    public Response authorizeCode(@QueryParam("response_type") String responseType,
                                  @QueryParam("client_id") String clientId,
                                  @QueryParam("redirect_uri") String redirectUri,
                                  @QueryParam("code_challenge") String codeChallenge,
                                  @QueryParam("code_challenge_method") String codeChallengeMethod,
                                  @QueryParam("state") String state,
                                  @QueryParam("principal_id") String principalId) {
        try {
            if (!"code".equals(responseType)) {
                throw new SsoOidcException("invalid_request", "response_type must be code", 400);
            }
            if (!"S256".equals(codeChallengeMethod)) {
                throw new SsoOidcException("invalid_request", "code_challenge_method must be S256", 400);
            }
            AuthorizationCode authorization;
            if (clientId != null && clientId.startsWith("arn:")) {
                ApplicationGrant grant = ssoAdminService.applicationGrantForOidc(clientId, "authorization_code");
                List<String> redirects = new ArrayList<>();
                grant.grant().path("AuthorizationCode").path("RedirectUris")
                        .forEach(uri -> { if (uri.isTextual()) redirects.add(uri.textValue()); });
                authorization = service.createAuthorizationCodeForPrincipal(
                        clientId, redirectUri, codeChallenge, redirects, resolveLocalPrincipal(principalId));
            } else {
                var client = service.requireClient(clientId);
                authorization = service.createAuthorizationCodeForPrincipal(
                        clientId, redirectUri, codeChallenge, client.redirectUris(), resolveLocalPrincipal(principalId));
            }
            String separator = redirectUri.contains("?") ? "&" : "?";
            String location = redirectUri + separator + "code="
                    + URLEncoder.encode(authorization.code(), StandardCharsets.UTF_8);
            if (state != null) {
                location += "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
            }
            return Response.seeOther(URI.create(location)).build();
        } catch (SsoOidcException e) {
            return oidcError(e.status(), e.error(), e.getMessage());
        }
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new SsoOidcException("invalid_request", "Request body must be valid JSON", 400);
        }
    }

    private Response oidcError(int status, String error, String description) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("error", error);
        response.put("error_description", description);
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(response).build();
    }
}
