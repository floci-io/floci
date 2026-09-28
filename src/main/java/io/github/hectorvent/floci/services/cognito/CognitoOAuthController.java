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
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@ApplicationScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class CognitoOAuthController {

    private static final Logger LOG = Logger.getLogger(CognitoOAuthController.class);

    /** The {@code identity_provider} value, and the SupportedIdentityProviders entry, of the pool's own users. */
    private static final String COGNITO_PROVIDER = "COGNITO";
    /** AWS's managed login cookie names: the session, and the CSRF token that the sign-in form echoes. */
    private static final String SESSION_COOKIE = "cognito";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_FIELD = "_csrf";
    /** The only PKCE method Cognito accepts; its challenge is an unpadded base64url SHA-256 hash. */
    private static final String PKCE_METHOD = "S256";
    private static final Pattern CODE_CHALLENGE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    /** RFC 7636 section 4.1: 43 to 128 characters of the unreserved set. */
    private static final Pattern CODE_VERIFIER_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

    private final CognitoService cognitoService;
    private final ObjectMapper objectMapper;
    private final CognitoFederationService federationService;
    private final CognitoFederationStateStore stateStore;
    private final CognitoManagedLoginService managedLoginService;

    @Inject
    public CognitoOAuthController(CognitoService cognitoService, ObjectMapper objectMapper,
                                  CognitoFederationService federationService,
                                  CognitoFederationStateStore stateStore,
                                  CognitoManagedLoginService managedLoginService) {
        this.cognitoService = cognitoService;
        this.objectMapper = objectMapper;
        this.federationService = federationService;
        this.stateStore = stateStore;
        this.managedLoginService = managedLoginService;
    }

    /**
     * Without an {@code identity_provider}, or with {@code COGNITO}, signs in one of the pool's own
     * users through managed login; any other value names an external provider to federate with.
     * A client that does not list COGNITO cannot use managed login, even when it supports exactly
     * one other provider: the caller names that provider instead.
     */
    @GET
    @Path("/cognito-idp/oauth2/authorize")
    public Response authorize(@Context ContainerRequestContext requestContext,
                              @QueryParam("client_id") String clientId,
                              @QueryParam("redirect_uri") String redirectUri,
                              @QueryParam("response_type") String responseType,
                              @QueryParam("scope") String scope,
                              @QueryParam("nonce") String nonce,
                              @QueryParam("identity_provider") String providerName,
                              @QueryParam("state") String relyingPartyState,
                              @QueryParam("code_challenge") String codeChallenge,
                              @QueryParam("code_challenge_method") String codeChallengeMethod,
                              @CookieParam(SESSION_COOKIE) String sessionId) {
        AuthorizationRequest request = new AuthorizationRequest(responseType, clientId, redirectUri, scope,
                relyingPartyState, nonce, codeChallenge, codeChallengeMethod);
        String provider = trimToNull(providerName);
        if (provider == null || COGNITO_PROVIDER.equals(provider)) {
            return authorizeWithManagedLogin(requestContext, request, sessionId);
        }

        AuthorizationCheck check = checkAuthorizationRequest(requestContext, request);
        if (check.error() != null) {
            return check.error();
        }
        try {
            String location = federationService.beginAuthorization(check.client().getUserPoolId(), clientId,
                    redirectUri, splitScopes(scope), trimToNull(nonce), providerName, relyingPartyState,
                    trimToNull(codeChallenge));
            return Response.status(Response.Status.FOUND).location(URI.create(location)).build();
        } catch (AwsException e) {
            return oauthError("invalid_request", e.getMessage());
        }
    }

    /**
     * AWS skips the sign-in form for a browser that already has a session in the pool, and otherwise
     * redirects to the login endpoint with the request's parameters.
     */
    private Response authorizeWithManagedLogin(ContainerRequestContext requestContext, AuthorizationRequest request,
                                               String sessionId) {
        AuthorizationCheck check = checkManagedLoginRequest(requestContext, request);
        if (check.error() != null) {
            return check.error();
        }
        Optional<String> code = issueAuthorizationCode(sessionId, check.client(), request);
        if (code.isPresent()) {
            return redirectWithCode(request, code.get());
        }
        return Response.status(Response.Status.FOUND)
                .header(HttpHeaders.LOCATION, loginPath(requestContext) + "?" + formEncode(request.parameters()))
                .build();
    }

    @GET
    @Path("/cognito-idp/login")
    @Produces({MediaType.TEXT_HTML, MediaType.APPLICATION_JSON})
    public Response loginPage(@Context ContainerRequestContext requestContext,
                              @QueryParam("client_id") String clientId,
                              @QueryParam("redirect_uri") String redirectUri,
                              @QueryParam("response_type") String responseType,
                              @QueryParam("scope") String scope,
                              @QueryParam("nonce") String nonce,
                              @QueryParam("state") String state,
                              @QueryParam("code_challenge") String codeChallenge,
                              @QueryParam("code_challenge_method") String codeChallengeMethod) {
        AuthorizationRequest request = new AuthorizationRequest(responseType, clientId, redirectUri, scope, state,
                nonce, codeChallenge, codeChallengeMethod);
        AuthorizationCheck check = checkManagedLoginRequest(requestContext, request);
        if (check.error() != null) {
            return check.error();
        }
        return signInPage(requestContext, request, Response.Status.OK, null);
    }

    /**
     * Signs the user in and redirects to the callback with a code. A failed sign-in shows the form
     * again with the reason; a missing or wrong CSRF token shows it with a fresh token instead.
     */
    @POST
    @Path("/cognito-idp/login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces({MediaType.TEXT_HTML, MediaType.APPLICATION_JSON})
    public Response login(@Context ContainerRequestContext requestContext,
                          @CookieParam(CSRF_COOKIE) String csrfCookie,
                          MultivaluedMap<String, String> formParams) {
        // The form carries the request in hidden fields. AWS's own form posts it in the query string
        // instead, so a script that replays AWS's form by posting only the credentials works too.
        AuthorizationRequest request = AuthorizationRequest.from(formParams.containsKey("client_id")
                ? formParams : requestContext.getUriInfo().getQueryParameters());
        AuthorizationCheck check = checkManagedLoginRequest(requestContext, request);
        if (check.error() != null) {
            return check.error();
        }
        if (!csrfTokenMatches(csrfCookie, formParams.getFirst(CSRF_FIELD))) {
            return signInPage(requestContext, request, Response.Status.FORBIDDEN,
                    "Your sign-in page expired. Sign in again.");
        }
        String username = formParams.getFirst("username");
        String password = formParams.getFirst("password");
        if (trimToNull(username) == null || password == null || password.isEmpty()) {
            return signInPage(requestContext, request, Response.Status.BAD_REQUEST,
                    "Enter your username and password.");
        }

        String sessionId;
        try {
            sessionId = managedLoginService.signIn(check.client(), username, password);
        } catch (AwsException e) {
            return signInPage(requestContext, request, Response.Status.BAD_REQUEST, signInError(e));
        }
        Optional<String> code = issueAuthorizationCode(sessionId, check.client(), request);
        if (code.isEmpty()) {
            return signInPage(requestContext, request, Response.Status.BAD_REQUEST,
                    CognitoAuthFlowHandler.INCORRECT_CREDENTIALS);
        }
        return Response.fromResponse(redirectWithCode(request, code.get()))
                .header(HttpHeaders.SET_COOKIE, cookie(SESSION_COOKIE, sessionId,
                        CognitoManagedLoginService.SESSION_LIFETIME.toSeconds(), isHttps(requestContext)))
                .build();
    }

    /**
     * Signs the browser out of managed login. With {@code logout_uri}, which wins when both are
     * given, redirects there and nowhere else, as AWS does; with {@code redirect_uri}, redirects to
     * the login endpoint with the request's authorization parameters so the user signs in again.
     * A session cookie of another pool is left as it is: on AWS that pool has its own domain.
     */
    @GET
    @Path("/cognito-idp/logout")
    public Response logout(@Context ContainerRequestContext requestContext,
                           @QueryParam("client_id") String clientId,
                           @QueryParam("logout_uri") String logoutUri,
                           @QueryParam("redirect_uri") String redirectUri,
                           @QueryParam("response_type") String responseType,
                           @QueryParam("scope") String scope,
                           @QueryParam("nonce") String nonce,
                           @QueryParam("state") String state,
                           @QueryParam("code_challenge") String codeChallenge,
                           @QueryParam("code_challenge_method") String codeChallengeMethod,
                           @CookieParam(SESSION_COOKIE) String sessionId) {
        UserPoolClient client;
        Response redirect;
        if (trimToNull(logoutUri) != null) {
            if (trimToNull(clientId) == null) {
                return oauthError("invalid_request", "client_id is required");
            }
            AuthorizationCheck check = lookUpClient(requestContext, clientId);
            if (check.error() != null) {
                return check.error();
            }
            if (!check.client().getLogoutURLs().contains(logoutUri)) {
                return oauthError("invalid_request", "logout_uri is not registered for this client");
            }
            client = check.client();
            redirect = Response.status(Response.Status.FOUND).location(URI.create(logoutUri)).build();
        } else if (trimToNull(redirectUri) != null) {
            AuthorizationRequest request = new AuthorizationRequest(responseType, clientId, redirectUri, scope,
                    state, nonce, codeChallenge, codeChallengeMethod);
            AuthorizationCheck check = checkManagedLoginRequest(requestContext, request);
            if (check.error() != null) {
                return check.error();
            }
            client = check.client();
            redirect = Response.status(Response.Status.FOUND)
                    .header(HttpHeaders.LOCATION, loginPath(requestContext) + "?" + formEncode(request.parameters()))
                    .build();
        } else {
            return oauthError("invalid_request", "logout_uri or redirect_uri is required");
        }
        if (!managedLoginService.signOut(sessionId, client.getUserPoolId())) {
            return redirect;
        }
        return Response.fromResponse(redirect)
                .header(HttpHeaders.SET_COOKIE, cookie(SESSION_COOKIE, "", 0L, isHttps(requestContext)))
                .build();
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
        // Checked after the code is spent: a code that fails its PKCE check may have been intercepted,
        // so it gets no second attempt, unlike a request that names the wrong client or redirect_uri.
        String pkceError = pkceError(consumedCode.codeChallenge(), trimToNull(formParams.getFirst("code_verifier")));
        if (pkceError != null) {
            return oauthError("invalid_grant", pkceError);
        }
        try {
            UserPool pool = cognitoService.describeUserPool(consumedCode.userPoolId());
            CognitoUser user = cognitoService.adminGetUser(consumedCode.userPoolId(), consumedCode.userId());
            Map<String, Object> authentication = cognitoService.generateAuthResultForHostedAuth(user, pool, client,
                    nonceClaim(consumedCode.nonce()), consumedCode.scopes());
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
        String location = redirectUri + (redirectUri.contains("?") ? '&' : '?') + formEncode(parameters);
        return Response.status(Response.Status.FOUND).location(URI.create(location)).build();
    }

    private static String formEncode(Map<String, String> parameters) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8));
            query.append('=').append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
        }
        return query.toString();
    }

    private Response redirectWithCode(AuthorizationRequest request, String code) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("code", code);
        if (request.state() != null) {
            parameters.put("state", request.state());
        }
        return redirect(request.redirectUri(), parameters);
    }

    private Optional<String> issueAuthorizationCode(String sessionId, UserPoolClient client,
                                                    AuthorizationRequest request) {
        return managedLoginService.issueAuthorizationCode(sessionId, client, request.redirectUri(),
                splitScopes(request.scope()), trimToNull(request.nonce()), trimToNull(request.codeChallenge()));
    }

    /**
     * The checks every authorization request passes, for managed login and federation alike. Errors
     * are 400 JSON, including those found after redirect_uri is known to be registered, where AWS
     * redirects the error to it instead.
     */
    private AuthorizationCheck checkAuthorizationRequest(ContainerRequestContext requestContext,
                                                         AuthorizationRequest request) {
        if (!"code".equals(trimToNull(request.responseType()))) {
            return AuthorizationCheck.rejected(
                    oauthError("unsupported_response_type", "Only response_type=code is supported"));
        }
        if (trimToNull(request.clientId()) == null || trimToNull(request.redirectUri()) == null) {
            return AuthorizationCheck.rejected(oauthError("invalid_request", "client_id and redirect_uri are required"));
        }
        AuthorizationCheck check = lookUpClient(requestContext, request.clientId());
        if (check.error() != null) {
            return check;
        }
        UserPoolClient client = check.client();
        if (!client.isAllowedOAuthFlowsUserPoolClient() || !client.getAllowedOAuthFlows().contains("code")) {
            return AuthorizationCheck.rejected(
                    oauthError("unauthorized_client", "Client is not allowed to use the authorization code flow"));
        }
        if (!client.getCallbackURLs().contains(request.redirectUri())) {
            return AuthorizationCheck.rejected(
                    oauthError("invalid_request", "redirect_uri is not registered for this client"));
        }
        String pkceError = pkceRequestError(request);
        if (pkceError != null) {
            return AuthorizationCheck.rejected(oauthError("invalid_request", pkceError));
        }
        return check;
    }

    /** An authorization request that managed login answers, at the authorize, login, and logout endpoints. */
    private AuthorizationCheck checkManagedLoginRequest(ContainerRequestContext requestContext,
                                                        AuthorizationRequest request) {
        AuthorizationCheck check = checkAuthorizationRequest(requestContext, request);
        if (check.error() == null && !check.client().getSupportedIdentityProviders().contains(COGNITO_PROVIDER)) {
            return AuthorizationCheck.rejected(
                    oauthError("invalid_request", "COGNITO is not a supported identity provider for this client"));
        }
        return check;
    }

    /** As on AWS, a client of another pool does not exist on a pool's custom domain. */
    private AuthorizationCheck lookUpClient(ContainerRequestContext requestContext, String clientId) {
        UserPoolClient client;
        try {
            client = cognitoService.findClientById(clientId);
        } catch (AwsException e) {
            return AuthorizationCheck.rejected(oauthError("invalid_client", "Client not found"));
        }
        String domainPoolId = (String) requestContext.getProperty(CognitoCustomDomainFilter.POOL_PROPERTY);
        if (domainPoolId != null && !domainPoolId.equals(client.getUserPoolId())) {
            return AuthorizationCheck.rejected(oauthError("invalid_client", "Client not found"));
        }
        return new AuthorizationCheck(client, null);
    }

    /** Why the request's PKCE parameters are unusable, or null when it has none or valid ones. */
    private String pkceRequestError(AuthorizationRequest request) {
        String challenge = trimToNull(request.codeChallenge());
        String method = trimToNull(request.codeChallengeMethod());
        if (challenge == null) {
            return method == null ? null : "code_challenge is required with code_challenge_method";
        }
        if (!PKCE_METHOD.equals(method)) {
            return "code_challenge_method must be S256";
        }
        if (!CODE_CHALLENGE_PATTERN.matcher(challenge).matches()) {
            return "code_challenge must be the unpadded base64url encoding of a SHA-256 hash";
        }
        return null;
    }

    /**
     * RFC 7636 section 4.6 with S256, or null when the verifier proves the challenge. A verifier sent
     * for a code issued without a challenge is refused, as RFC 9700 section 2.1.1 requires, so a code
     * obtained without PKCE cannot be injected into a client's PKCE flow.
     */
    static String pkceError(String codeChallenge, String codeVerifier) {
        if (codeChallenge == null) {
            return codeVerifier == null ? null
                    : "code_verifier was sent for an authorization code issued without a code_challenge";
        }
        if (codeVerifier == null) {
            return "code_verifier is required for this authorization code";
        }
        if (!CODE_VERIFIER_PATTERN.matcher(codeVerifier).matches()) {
            return "code_verifier must be 43 to 128 unreserved characters";
        }
        byte[] computed = Base64.getUrlEncoder().withoutPadding()
                .encode(sha256(codeVerifier.getBytes(StandardCharsets.US_ASCII)));
        if (!MessageDigest.isEqual(computed, codeChallenge.getBytes(StandardCharsets.US_ASCII))) {
            return "code_verifier does not match the code_challenge";
        }
        return null;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for PKCE", e);
        }
    }

    /** AWS puts the authorization request's nonce in the ID token. */
    private static CognitoService.ClaimsOverride nonceClaim(String nonce) {
        if (nonce == null) {
            return null;
        }
        return new CognitoService.ClaimsOverride(Map.of("nonce", nonce), null, null, null, null, null, null, null,
                null);
    }

    /** Managed login's endpoints are served at the root of a custom domain, and under /cognito-idp elsewhere. */
    private static String loginPath(ContainerRequestContext requestContext) {
        return requestContext.getProperty(CognitoCustomDomainFilter.POOL_PROPERTY) != null
                ? "/login" : "/cognito-idp/login";
    }

    /**
     * The sign-in form, deliberately plain: a username, a password, and the authorization request in
     * hidden fields. Each rendering sets a fresh CSRF token cookie that the form echoes back.
     */
    private Response signInPage(ContainerRequestContext requestContext, AuthorizationRequest request,
                                Response.Status status, String error) {
        String csrfToken = stateStore.generateOpaqueKey();
        List<String> hiddenFields = new ArrayList<>();
        for (Map.Entry<String, String> parameter : request.parameters().entrySet()) {
            hiddenFields.add("      <input type=\"hidden\" name=\"" + escapeHtml(parameter.getKey())
                    + "\" value=\"" + escapeHtml(parameter.getValue()) + "\">");
        }
        String page = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Sign in</title>
                </head>
                <body>
                  <main>
                    <h1>Sign in</h1>
                %s
                    <form method="post" action="%s">
                      <input type="hidden" name="%s" value="%s">
                %s
                      <label for="username">Username</label>
                      <input id="username" name="username" type="text" autocomplete="username" autocapitalize="none" required>
                      <label for="password">Password</label>
                      <input id="password" name="password" type="password" autocomplete="current-password" required>
                      <button type="submit">Sign in</button>
                    </form>
                  </main>
                </body>
                </html>
                """.formatted(error == null ? "" : "    <p role=\"alert\">" + escapeHtml(error) + "</p>",
                escapeHtml(loginPath(requestContext)), CSRF_FIELD, escapeHtml(csrfToken),
                String.join("\n", hiddenFields));
        // No form-action directive: browsers apply it to the redirect after the form is submitted,
        // which goes to the client's callback on another origin.
        return Response.status(status)
                .type(MediaType.TEXT_HTML)
                .entity(page)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; base-uri 'none'; frame-ancestors 'none'")
                .header(HttpHeaders.SET_COOKIE, cookie(CSRF_COOKIE, csrfToken, null, isHttps(requestContext)))
                .build();
    }

    /** The reason a sign-in failed, as the form shows it. An unknown user reads the same as a wrong password. */
    private static String signInError(AwsException e) {
        if ("UserNotFoundException".equals(e.getErrorCode())) {
            return CognitoAuthFlowHandler.INCORRECT_CREDENTIALS;
        }
        return e.getMessage();
    }

    private static boolean csrfTokenMatches(String cookieToken, String formToken) {
        if (cookieToken == null || cookieToken.isEmpty() || formToken == null) {
            return false;
        }
        return MessageDigest.isEqual(cookieToken.getBytes(StandardCharsets.UTF_8),
                formToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A managed login cookie: host-only, sent on every path, hidden from scripts, left off cross-site
     * subrequests, and Secure on HTTPS. A null {@code maxAgeSeconds} lasts until the browser closes.
     */
    private static String cookie(String name, String value, Long maxAgeSeconds, boolean secure) {
        StringBuilder cookie = new StringBuilder(name).append('=').append(value).append("; Path=/");
        if (maxAgeSeconds != null) {
            cookie.append("; Max-Age=").append(maxAgeSeconds);
        }
        cookie.append("; HttpOnly; SameSite=Lax");
        if (secure) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }

    private static boolean isHttps(ContainerRequestContext requestContext) {
        return "https".equalsIgnoreCase(requestContext.getUriInfo().getRequestUri().getScheme());
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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

    /** The authorization request that managed login carries from the authorize endpoint to the sign-in form and back. */
    private record AuthorizationRequest(String responseType, String clientId, String redirectUri, String scope,
                                        String state, String nonce, String codeChallenge,
                                        String codeChallengeMethod) {

        static AuthorizationRequest from(MultivaluedMap<String, String> parameters) {
            return new AuthorizationRequest(parameters.getFirst("response_type"), parameters.getFirst("client_id"),
                    parameters.getFirst("redirect_uri"), parameters.getFirst("scope"), parameters.getFirst("state"),
                    parameters.getFirst("nonce"), parameters.getFirst("code_challenge"),
                    parameters.getFirst("code_challenge_method"));
        }

        /** The parameters the request carried, in the order of AWS's redirect to the login endpoint. */
        Map<String, String> parameters() {
            Map<String, String> parameters = new LinkedHashMap<>();
            putIfPresent(parameters, "response_type", responseType);
            putIfPresent(parameters, "client_id", clientId);
            putIfPresent(parameters, "redirect_uri", redirectUri);
            putIfPresent(parameters, "scope", scope);
            putIfPresent(parameters, "state", state);
            putIfPresent(parameters, "nonce", nonce);
            putIfPresent(parameters, "code_challenge", codeChallenge);
            putIfPresent(parameters, "code_challenge_method", codeChallengeMethod);
            return parameters;
        }

        private static void putIfPresent(Map<String, String> parameters, String name, String value) {
            if (value != null) {
                parameters.put(name, value);
            }
        }
    }

    /** The client an authorization request names, or the error response that rejects the request. */
    private record AuthorizationCheck(UserPoolClient client, Response error) {

        static AuthorizationCheck rejected(Response error) {
            return new AuthorizationCheck(null, error);
        }
    }
}
