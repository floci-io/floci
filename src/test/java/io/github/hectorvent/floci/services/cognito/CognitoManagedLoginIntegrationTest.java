package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.customDomain;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.jwtPayload;
import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.requestCertificate;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Managed login for a pool's own users, driven over HTTP the way a browser drives it:
 * {@code /oauth2/authorize} redirects to the sign-in form, the form posts the credentials, the
 * callback receives a code, and {@code /oauth2/token} redeems it, with PKCE, the session cookie
 * that skips the form, and {@code /logout}.
 */
@QuarkusTest
class CognitoManagedLoginIntegrationTest {

    private static final String CALLBACK = "https://app.example.test/callback";
    private static final String SIGNED_OUT = "https://app.example.test/signed-out";
    private static final String PASSWORD = "Perm1234!";
    /** RFC 7636 appendix B. */
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String INCORRECT_CREDENTIALS = "Incorrect username or password";
    private static final Pattern HIDDEN_FIELD = Pattern.compile("<input type=\"hidden\" name=\"([^\"]*)\" value=\"([^\"]*)\">");
    private static final Pattern FORM_ACTION = Pattern.compile("<form method=\"post\" action=\"([^\"]*)\">");
    private static final Pattern SIX_DIGIT_CODE = Pattern.compile("\\b(\\d{6})\\b");
    private static final String PRE_TOKEN_GENERATION_ARN = "arn:aws:lambda:::pre-token-generation";
    private static final String ADMIN_SCOPE = "aws.cognito.signin.user.admin";
    private static final String POST_AUTHENTICATION_ARN = "arn:aws:lambda:::post-authentication";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Only the PreTokenGeneration and PostAuthentication triggers are configured on any pool here, each by
     * the tests that stub it, so nothing else invokes it.
     */
    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void authorizeWithoutIdentityProviderRedirectsToTheLoginPageWithTheRequest() throws Exception {
        Pool pool = newPool();

        for (String provider : new String[] {null, "COGNITO"}) {
            Map<String, String> query = authorizeRequest(pool.clientId());
            if (provider != null) {
                query.put("identity_provider", provider);
            }
            String location = browserGet(null, "/cognito-idp/oauth2/authorize", query, null)
                    .then().statusCode(302).extract().header("Location");

            assertEquals("/cognito-idp/login", URI.create(location).getPath());
            query.remove("identity_provider");
            assertEquals(query, queryOf(location));
        }
    }

    @Test
    void loginPageRendersAPlainFormAndEscapesTheState() throws Exception {
        Pool pool = newPool();
        Map<String, String> query = authorizeRequest(pool.clientId());
        query.put("state", "\"><script>alert(1)</script>");

        Response page = browserGet(null, "/cognito-idp/login", query, null);

        page.then().statusCode(200)
                .contentType(startsWith("text/html"))
                .header("Cache-Control", equalTo("no-store"))
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("Content-Security-Policy", equalTo("default-src 'none'; base-uri 'none'; frame-ancestors 'none'"));
        String html = page.asString();
        assertTrue(html.contains("name=\"username\""), html);
        assertTrue(html.contains("name=\"password\" type=\"password\""), html);
        assertFalse(html.contains("<script>"), html);
        assertTrue(html.contains("value=\"&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\""), html);
        assertEquals("/cognito-idp/login", formAction(html));
        Map<String, String> fields = hiddenFields(html);
        assertEquals(query.get("state"), fields.get("state"));
        assertEquals(CHALLENGE, fields.get("code_challenge"));
        String csrfCookie = setCookie(page, "XSRF-TOKEN");
        assertTrue(csrfCookie.contains("; Path=/; HttpOnly; SameSite=Lax"), csrfCookie);
        assertEquals(page.getCookie("XSRF-TOKEN"), fields.get("_csrf"));
    }

    @Test
    void wrongPasswordOrUnknownUserShowsTheSameErrorWithoutACodeOrSession() throws Exception {
        Pool pool = newPool();

        for (String username : new String[] {pool.username(), "nobody-" + System.nanoTime()}) {
            Response page = loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId()));
            Response failed = submit(null, page, username, "Wrong1234!");

            failed.then().statusCode(400).contentType(startsWith("text/html"));
            assertNull(failed.getHeader("Location"));
            assertNull(setCookie(failed, "cognito"));
            assertTrue(failed.asString().contains("<p role=\"alert\">" + INCORRECT_CREDENTIALS + "</p>"), failed.asString());
            assertNotNull(hiddenFields(failed.asString()).get("_csrf"), "the form is shown again");
        }
    }

    @Test
    void signInWithoutTheCsrfTokenOrWithAnotherIsRejected() throws Exception {
        Pool pool = newPool();
        Response page = loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId()));
        Map<String, String> fields = hiddenFields(page.asString());

        Response withoutCookie = given().redirects().follow(false)
                .formParams(fields).formParam("username", pool.username()).formParam("password", PASSWORD)
                .when().post("/cognito-idp/login");
        Response otherToken = given().redirects().follow(false)
                .cookie("XSRF-TOKEN", page.getCookie("XSRF-TOKEN"))
                .formParams(withField(fields, "_csrf", "forged-token"))
                .formParam("username", pool.username()).formParam("password", PASSWORD)
                .when().post("/cognito-idp/login");
        Response withoutField = given().redirects().follow(false)
                .cookie("XSRF-TOKEN", page.getCookie("XSRF-TOKEN"))
                .formParams(withField(fields, "_csrf", null))
                .formParam("username", pool.username()).formParam("password", PASSWORD)
                .when().post("/cognito-idp/login");

        for (Response rejected : List.of(withoutCookie, otherToken, withoutField)) {
            rejected.then().statusCode(403).body(containsString("<p role=\"alert\">"));
            assertNull(rejected.getHeader("Location"));
            assertNull(setCookie(rejected, "cognito"));
        }
    }

    @Test
    void signInRedirectsToTheCallbackWithACodeTheStateAndASessionCookie() throws Exception {
        Pool pool = newPool();

        Response signedIn = signIn(null, pool, authorizeRequest(pool.clientId()));

        signedIn.then().statusCode(302);
        String location = signedIn.getHeader("Location");
        assertTrue(location.startsWith(CALLBACK + "?code="), location);
        assertEquals("client-state", queryOf(location).get("state"));
        String sessionCookie = setCookie(signedIn, "cognito");
        assertTrue(sessionCookie.matches("cognito=[A-Za-z0-9_-]{43}; Path=/; Max-Age=3600; HttpOnly; SameSite=Lax"),
                sessionCookie);
    }

    /** The client allows only refresh: USER_PASSWORD_AUTH is refused, yet managed login signs in. */
    @Test
    void managedLoginDoesNotDependOnTheClientsExplicitAuthFlows() throws Exception {
        Pool pool = newPool();

        cognitoAction("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH","AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(pool.clientId(), pool.username(), PASSWORD)).then().statusCode(400);
        signIn(null, pool, authorizeRequest(pool.clientId())).then().statusCode(302);
    }

    @Test
    void codeRedeemsWithTheVerifierForTokensOfTheSignedInUser() throws Exception {
        Pool pool = newPool();
        String code = code(signIn(null, pool, authorizeRequest(pool.clientId())));

        Response tokens = redeem(null, pool.clientId(), code, VERIFIER);

        tokens.then().statusCode(200).body("token_type", equalTo("Bearer"));
        JsonNode idToken = jwtPayload(tokens.path("id_token"));
        assertEquals(pool.sub(), idToken.path("sub").asText());
        assertEquals(pool.username(), idToken.path("cognito:username").asText());
        assertEquals("client-nonce", idToken.path("nonce").asText());
        assertEquals(pool.clientId(), idToken.path("aud").asText());
        JsonNode accessToken = jwtPayload(tokens.path("access_token"));
        assertEquals(pool.username(), accessToken.path("username").asText());
        assertFalse(accessToken.has("nonce"));
    }

    /**
     * A pool whose PreTokenGeneration trigger adds a claim must get it from a managed-login sign-in
     * too, not only from {@code InitiateAuth}: the token endpoint is what mints these tokens, so that
     * is where the trigger has to fire. The authorization request's {@code nonce} outranks the
     * trigger, as it does on AWS.
     */
    @Test
    void redeemingTheCodeFiresPreTokenGenerationAndAppliesItsClaims() throws Exception {
        Pool pool = newPoolWithPreTokenGenerationTrigger();
        ArgumentCaptor<byte[]> event = ArgumentCaptor.forClass(byte[].class);
        when(lambdaService.invoke(anyString(), eq(PRE_TOKEN_GENERATION_ARN), event.capture(), any()))
                .thenReturn(triggerResponse(Map.of("claimsOverrideDetails", Map.of(
                        "claimsToAddOrOverride", Map.of("tenant", "acme", "nonce", "trigger-nonce")))));

        Response tokens = redeem(null, pool.clientId(),
                code(signIn(null, pool, authorizeRequest(pool.clientId()))), VERIFIER);

        tokens.then().statusCode(200);
        JsonNode triggerEvent = MAPPER.readTree(event.getValue());
        assertEquals("TokenGeneration_HostedAuth", triggerEvent.path("triggerSource").asText(),
                "AWS uses TokenGeneration_HostedAuth for a sign-in through the hosted UI");
        assertEquals(List.of("openid", "email"),
                MAPPER.convertValue(triggerEvent.path("request").path("scopes"), List.class),
                "the authorization request's scopes reach the trigger");
        JsonNode idToken = jwtPayload(tokens.path("id_token"));
        assertEquals("acme", idToken.path("tenant").asText(), "the trigger's claim should reach the ID token");
        assertEquals("client-nonce", idToken.path("nonce").asText(),
                "the request's nonce must outrank a trigger that tries to replace it");
        assertEquals("acme", jwtPayload(tokens.path("access_token")).path("tenant").asText(),
                "the trigger's claim should reach the access token");
    }

    /**
     * AWS refuses a scope outside the client's AllowedOAuthScopes at the authorize endpoint, before
     * the sign-in form and even for a browser that is already signed in, by redirecting to the
     * callback with {@code error=invalid_request} and {@code error_description=invalid_scope}. No
     * code is issued, so a V2 trigger never hears of the scope.
     */
    @Test
    void aScopeTheClientIsNotAllowedIsRedirectedToTheCallbackAsInvalidScope() throws Exception {
        Pool withoutAdmin = newPoolWithPreTokenGenerationTrigger();
        Pool withAdmin = newPool(List.of("openid", "email", ADMIN_SCOPE));
        String session = signIn(null, withoutAdmin, authorizeRequest(withoutAdmin.clientId())).getCookie("cognito");
        Map<String, Pool> requests = new LinkedHashMap<>();
        requests.put("openid " + ADMIN_SCOPE, withoutAdmin);
        requests.put("openid email admin/superuser", withoutAdmin);
        requests.put("openid phone", withAdmin);

        for (Map.Entry<String, Pool> request : requests.entrySet()) {
            Map<String, String> query = withField(authorizeRequest(request.getValue().clientId()), "scope",
                    request.getKey());
            for (String sessionCookie : new String[] {null, session}) {
                String location = browserGet(null, "/cognito-idp/oauth2/authorize", query, sessionCookie)
                        .then().statusCode(302).extract().header("Location");

                assertTrue(location.startsWith(CALLBACK + "?"), location);
                assertEquals(Map.of("error", "invalid_request", "error_description", "invalid_scope",
                        "state", "client-state"), queryOf(location), "scope=" + request.getKey());
            }
        }
        verify(lambdaService, never()).invoke(anyString(), anyString(), any(byte[].class), any());
    }

    /** AWS: the requested scopes, or every scope the client allows when the request names none. */
    @Test
    void theAccessTokenCarriesTheRequestedScopesOrEveryAllowedScopeWhenNoneAreRequested() throws Exception {
        Pool pool = newPool(List.of("openid", "email", ADMIN_SCOPE));
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        expected.put("openid email", Set.of("openid", "email"));
        expected.put("openid email " + ADMIN_SCOPE, Set.of("openid", "email", ADMIN_SCOPE));
        expected.put(null, Set.of("openid", "email", ADMIN_SCOPE));

        for (Map.Entry<String, Set<String>> request : expected.entrySet()) {
            Map<String, String> query = withField(authorizeRequest(pool.clientId()), "scope", request.getKey());

            Response tokens = redeem(null, pool.clientId(), code(signIn(null, pool, query)), VERIFIER);

            tokens.then().statusCode(200);
            assertEquals(request.getValue(), scopes(tokens.path("access_token")), "scope=" + request.getKey());
            assertEquals(Set.of("access_token", "id_token", "refresh_token", "expires_in", "token_type"),
                    fieldNames(tokens), "scope=" + request.getKey());
        }
    }

    /** AWS issues an ID token only for a request with {@code openid}. */
    @Test
    void withoutOpenidTheTokenEndpointIssuesNoIdToken() throws Exception {
        Pool pool = newPool(List.of("openid", "email", ADMIN_SCOPE));

        for (String requested : new String[] {"email", ADMIN_SCOPE}) {
            Map<String, String> query = withField(authorizeRequest(pool.clientId()), "scope", requested);

            Response tokens = redeem(null, pool.clientId(), code(signIn(null, pool, query)), VERIFIER);

            tokens.then().statusCode(200);
            assertEquals(Set.of("access_token", "refresh_token", "expires_in", "token_type"), fieldNames(tokens),
                    "scope=" + requested);
            assertEquals(Set.of(requested), scopes(tokens.path("access_token")));
        }
    }

    /**
     * A code carries the scopes granted when it was issued. A request without a scope is granted
     * every scope the client allows then, so a scope the client is allowed only afterwards is not
     * added when the code is redeemed.
     */
    @Test
    void aScopeTheClientIsAllowedAfterTheCodeWasIssuedIsNotAdded() throws Exception {
        Pool pool = newPool();
        String code = code(signIn(null, pool, withField(authorizeRequest(pool.clientId()), "scope", null)));
        allowScopes(pool, List.of("openid", "email", ADMIN_SCOPE));

        Response tokens = redeem(null, pool.clientId(), code, VERIFIER);

        tokens.then().statusCode(200);
        assertEquals(Set.of("openid", "email"), scopes(tokens.path("access_token")));
    }

    /**
     * A scope the client no longer allows is dropped from a code issued before the change, here
     * one issued to a browser that was already signed in.
     */
    @Test
    void aScopeTheClientNoLongerAllowsIsDroppedFromAnIssuedCode() throws Exception {
        Pool pool = newPool(List.of("openid", "email", ADMIN_SCOPE));
        String session = signIn(null, pool, authorizeRequest(pool.clientId())).getCookie("cognito");
        String code = code(browserGet(null, "/cognito-idp/oauth2/authorize",
                withField(authorizeRequest(pool.clientId()), "scope", null), session));
        allowScopes(pool, List.of("openid", ADMIN_SCOPE));

        Response tokens = redeem(null, pool.clientId(), code, VERIFIER);

        tokens.then().statusCode(200);
        assertEquals(Set.of("openid", ADMIN_SCOPE), scopes(tokens.path("access_token")));
    }

    /**
     * Floci lets an OAuth client allow no scopes. Its code grant has no scope claim and no ID token,
     * and none of the user's own operations accepts a token without a scope.
     */
    @Test
    void aCodeGrantOfAClientThatAllowsNoScopesCannotCallTheUsersOwnOperations() throws Exception {
        Pool pool = newPool(List.of());

        Response tokens = redeem(null, pool.clientId(),
                code(signIn(null, pool, withField(authorizeRequest(pool.clientId()), "scope", null))), VERIFIER);

        tokens.then().statusCode(200);
        assertEquals(Set.of("access_token", "refresh_token", "expires_in", "token_type"), fieldNames(tokens));
        String accessToken = tokens.path("access_token");
        assertFalse(jwtPayload(accessToken).has("scope"));
        cognitoAction("GetUser", "{\"AccessToken\":\"%s\"}".formatted(accessToken))
                .then().statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Access Token does not have required scopes"));
    }

    /**
     * {@code aws.cognito.signin.user.admin} is what lets an access token call the user's own API
     * operations. A code-grant token requested without it is refused with AWS's message, and the
     * refused calls change nothing.
     */
    @Test
    void aCodeGrantTokenWithoutTheAdminScopeCannotCallTheUsersOwnOperations() throws Exception {
        Pool pool = newPool(List.of("openid", "email", ADMIN_SCOPE));
        String withoutAdmin = accessToken(pool, "openid email");
        String withAdmin = accessToken(pool, "openid email " + ADMIN_SCOPE);
        Map<String, String> requests = new LinkedHashMap<>();
        requests.put("GetUser", "{\"AccessToken\":\"%s\"}");
        requests.put("GetUserAuthFactors", "{\"AccessToken\":\"%s\"}");
        requests.put("UpdateUserAttributes",
                "{\"AccessToken\":\"%s\",\"UserAttributes\":[{\"Name\":\"given_name\",\"Value\":\"Changed\"}]}");
        requests.put("DeleteUserAttributes", "{\"AccessToken\":\"%s\",\"UserAttributeNames\":[\"email\"]}");
        requests.put("ChangePassword",
                "{\"AccessToken\":\"%s\",\"PreviousPassword\":\"" + PASSWORD + "\",\"ProposedPassword\":\"Other1234!\"}");
        requests.put("GetUserAttributeVerificationCode", "{\"AccessToken\":\"%s\",\"AttributeName\":\"email\"}");
        requests.put("VerifyUserAttribute", "{\"AccessToken\":\"%s\",\"AttributeName\":\"email\",\"Code\":\"123456\"}");
        requests.put("SetUserMFAPreference", "{\"AccessToken\":\"%s\",\"EmailMfaSettings\":{\"Enabled\":true}}");
        requests.put("AssociateSoftwareToken", "{\"AccessToken\":\"%s\"}");
        requests.put("VerifySoftwareToken", "{\"AccessToken\":\"%s\",\"UserCode\":\"123456\"}");
        requests.put("GlobalSignOut", "{\"AccessToken\":\"%s\"}");

        for (Map.Entry<String, String> request : requests.entrySet()) {
            cognitoAction(request.getKey(), request.getValue().formatted(withoutAdmin))
                    .then().statusCode(400)
                    .body("__type", equalTo("NotAuthorizedException"))
                    .body("message", equalTo("Access Token does not have required scopes"));
        }

        JsonNode user = cognitoJson("GetUser", "{\"AccessToken\":\"%s\"}".formatted(withAdmin));
        assertEquals(pool.username(), user.path("Username").asText(), "the user was not signed out");
        assertTrue(user.path("UserAttributes").toString().contains("\"email\""), user.toString());
        assertFalse(user.path("UserAttributes").toString().contains("given_name"), user.toString());
        code(signIn(null, pool, authorizeRequest(pool.clientId())));
    }

    /**
     * A V2 trigger's {@code scopesToAdd} and {@code scopesToSuppress} apply on top of the scopes the
     * request was granted, so a trigger can still give a token the scope that API calls need.
     */
    @Test
    void preTokenGenerationV2ScopeChangesApplyOnTopOfTheGrantedScopes() throws Exception {
        Pool pool = newPoolWithPreTokenGenerationTrigger();
        when(lambdaService.invoke(anyString(), eq(PRE_TOKEN_GENERATION_ARN), any(byte[].class), any()))
                .thenReturn(triggerResponse(Map.of("claimsAndScopeOverrideDetails", Map.of(
                        "accessTokenGeneration", Map.of(
                                "scopesToSuppress", List.of("email"),
                                "scopesToAdd", List.of(ADMIN_SCOPE))))));

        Response tokens = redeem(null, pool.clientId(),
                code(signIn(null, pool, authorizeRequest(pool.clientId()))), VERIFIER);

        tokens.then().statusCode(200);
        String accessToken = tokens.path("access_token");
        assertEquals(Set.of("openid", ADMIN_SCOPE), scopes(accessToken));
        cognitoAction("GetUser", "{\"AccessToken\":\"%s\"}".formatted(accessToken)).then().statusCode(200);
    }

    /** A trigger that suppresses every scope the request was granted leaves no scope claim, which grants nothing. */
    @Test
    void codeGrantAccessTokenWithEveryScopeSuppressedCannotReadAuthFactors() throws Exception {
        String accessToken = codeGrantAccessTokenReshapedByTrigger(Map.of(
                "scopesToSuppress", List.of("openid", "email")));
        assertFalse(jwtPayload(accessToken).has("scope"));

        cognitoAction("GetUserAuthFactors", """
                {"AccessToken":"%s"}
                """.formatted(accessToken))
                .then().statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Access Token does not have required scopes"));
    }

    @Test
    void codeGrantAccessTokenWithEveryScopeSuppressedCannotVerifyAnAttribute() throws Exception {
        String accessToken = codeGrantAccessTokenReshapedByTrigger(Map.of(
                "scopesToSuppress", List.of("openid", "email")));
        assertFalse(jwtPayload(accessToken).has("scope"));

        cognitoAction("VerifyUserAttribute", """
                {"AccessToken":"%s","AttributeName":"email","Code":"123456"}
                """.formatted(accessToken))
                .then().statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("Access Token does not have required scopes"));
    }

    @Test
    void codeGrantAccessTokenWithTheAdminScopeReadsAuthFactors() throws Exception {
        Pool pool = newPool(List.of("openid", "email", ADMIN_SCOPE));
        String accessToken = accessToken(pool, "openid email " + ADMIN_SCOPE);

        cognitoAction("GetUserAuthFactors", """
                {"AccessToken":"%s"}
                """.formatted(accessToken))
                .then().statusCode(200)
                .body("Username", equalTo(pool.username()))
                .body("ConfiguredUserAuthFactors", equalTo(List.of("PASSWORD")));
    }

    @Test
    void codeIsRefusedWithAWrongOrMissingVerifierAndAWrongOneSpendsIt() throws Exception {
        Pool pool = newPool();
        Response signedIn = signIn(null, pool, authorizeRequest(pool.clientId()));
        String session = signedIn.getCookie("cognito");
        String wrongVerifierCode = code(signedIn);
        String missingVerifierCode = code(browserGet(null, "/cognito-idp/oauth2/authorize",
                authorizeRequest(pool.clientId()), session));

        redeem(null, pool.clientId(), wrongVerifierCode, VERIFIER.replace('d', 'e'))
                .then().statusCode(400).body("error", equalTo("invalid_grant"));
        redeem(null, pool.clientId(), wrongVerifierCode, VERIFIER)
                .then().statusCode(400).body("error", equalTo("invalid_grant"));
        redeem(null, pool.clientId(), missingVerifierCode, null)
                .then().statusCode(400).body("error", equalTo("invalid_grant"));
    }

    @Test
    void codeCannotBeRedeemedTwice() throws Exception {
        Pool pool = newPool();
        String code = code(signIn(null, pool, authorizeRequest(pool.clientId())));

        redeem(null, pool.clientId(), code, VERIFIER).then().statusCode(200);
        redeem(null, pool.clientId(), code, VERIFIER).then().statusCode(400).body("error", equalTo("invalid_grant"));
    }

    @Test
    void codeWithoutPkceRedeemsWithoutAVerifierButNotWithOne() throws Exception {
        Pool pool = newPool();
        Map<String, String> query = authorizeRequest(pool.clientId());
        query.remove("code_challenge");
        query.remove("code_challenge_method");
        Response signedIn = signIn(null, pool, query);
        String withVerifier = code(browserGet(null, "/cognito-idp/oauth2/authorize", query, signedIn.getCookie("cognito")));

        redeem(null, pool.clientId(), code(signedIn), null).then().statusCode(200);
        redeem(null, pool.clientId(), withVerifier, VERIFIER).then().statusCode(400).body("error", equalTo("invalid_grant"));
    }

    @Test
    void plainOrMissingCodeChallengeMethodIsRejected() throws Exception {
        Pool pool = newPool();

        for (String method : new String[] {"plain", null}) {
            Map<String, String> query = authorizeRequest(pool.clientId());
            query.remove("code_challenge_method");
            if (method != null) {
                query.put("code_challenge_method", method);
            }
            browserGet(null, "/cognito-idp/oauth2/authorize", query, null)
                    .then().statusCode(400).body("error", equalTo("invalid_request"));
        }
    }

    @Test
    void authorizeWithTheSessionCookieSkipsTheForm() throws Exception {
        Pool pool = newPool();
        String session = signIn(null, pool, authorizeRequest(pool.clientId())).getCookie("cognito");
        Map<String, String> query = authorizeRequest(pool.clientId());
        query.put("state", "second-state");

        Response again = browserGet(null, "/cognito-idp/oauth2/authorize", query, session);

        again.then().statusCode(302);
        assertEquals("second-state", queryOf(again.getHeader("Location")).get("state"));
        assertTrue(again.getHeader("Location").startsWith(CALLBACK + "?code="));
        JsonNode idToken = jwtPayload(redeem(null, pool.clientId(), code(again), VERIFIER)
                .then().statusCode(200).extract().path("id_token"));
        assertEquals(pool.username(), idToken.path("cognito:username").asText());
    }

    @Test
    void sessionCookieOfAnotherPoolNeitherSignsInNorSignsOutThere() throws Exception {
        Pool poolA = newPool();
        Pool poolB = newPool();
        String sessionOfA = signIn(null, poolA, authorizeRequest(poolA.clientId())).getCookie("cognito");

        String location = browserGet(null, "/cognito-idp/oauth2/authorize", authorizeRequest(poolB.clientId()), sessionOfA)
                .then().statusCode(302).extract().header("Location");
        assertTrue(location.startsWith("/cognito-idp/login?"), location);

        Response logoutOfB = browserGet(null, "/cognito-idp/logout",
                Map.of("client_id", poolB.clientId(), "logout_uri", SIGNED_OUT), sessionOfA);
        logoutOfB.then().statusCode(302).header("Location", equalTo(SIGNED_OUT));
        assertNull(setCookie(logoutOfB, "cognito"), "the browser keeps pool A's cookie");
        String stillSignedIn = browserGet(null, "/cognito-idp/oauth2/authorize", authorizeRequest(poolA.clientId()),
                sessionOfA).then().statusCode(302).extract().header("Location");
        assertTrue(stillSignedIn.startsWith(CALLBACK + "?code="), stillSignedIn);
    }

    @Test
    void logoutWithAnUnregisteredLogoutUriIsRejected() throws Exception {
        Pool pool = newPool();

        browserGet(null, "/cognito-idp/logout",
                Map.of("client_id", pool.clientId(), "logout_uri", "https://evil.example.test/"), null)
                .then().statusCode(400).body("error", equalTo("invalid_request"));
        browserGet(null, "/cognito-idp/logout", Map.of("client_id", pool.clientId()), null)
                .then().statusCode(400).body("error", equalTo("invalid_request"));
    }

    @Test
    void logoutEndsTheSessionClearsTheCookieAndRedirectsToTheLogoutUri() throws Exception {
        Pool pool = newPool();
        String session = signIn(null, pool, authorizeRequest(pool.clientId())).getCookie("cognito");

        Response logout = browserGet(null, "/cognito-idp/logout",
                Map.of("client_id", pool.clientId(), "logout_uri", SIGNED_OUT), session);

        logout.then().statusCode(302).header("Location", equalTo(SIGNED_OUT));
        assertEquals("cognito=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax", setCookie(logout, "cognito"));
        String location = browserGet(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId()), session)
                .then().statusCode(302).extract().header("Location");
        assertTrue(location.startsWith("/cognito-idp/login?"), "the old cookie no longer signs in: " + location);
    }

    @Test
    void logoutWithRedirectUriSendsTheUserBackToSignIn() throws Exception {
        Pool pool = newPool();
        String session = signIn(null, pool, authorizeRequest(pool.clientId())).getCookie("cognito");
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", pool.clientId());
        query.put("redirect_uri", CALLBACK);
        query.put("state", "after-logout");

        Response logout = browserGet(null, "/cognito-idp/logout", query, session);

        logout.then().statusCode(302);
        assertEquals("/cognito-idp/login", URI.create(logout.getHeader("Location")).getPath());
        assertEquals(query, queryOf(logout.getHeader("Location")));
        assertEquals("cognito=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax", setCookie(logout, "cognito"));
        browserGet(null, "/cognito-idp/logout", withField(query, "redirect_uri", "https://evil.example.test/"), null)
                .then().statusCode(400).body("error", equalTo("invalid_request"));
    }

    @Test
    void clientWithoutCognitoAmongItsProvidersCannotUseManagedLogin() throws Exception {
        String poolId = cognitoJson("CreateUserPool", "{\"PoolName\":\"FederatedOnlyPool\"}").path("UserPool").path("Id").asText();
        cognitoJson("CreateIdentityProvider", """
                {"UserPoolId":"%s","ProviderName":"ExampleOidc","ProviderType":"OIDC",
                 "ProviderDetails":{"authorize_url":"https://idp.example.test/authorize","client_id":"idp-client","attributes_request_method":"GET","oidc_issuer":"https://idp.example.test"}}
                """.formatted(poolId));
        String clientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"federated-only","AllowedOAuthFlowsUserPoolClient":true,
                 "AllowedOAuthFlows":["code"],"AllowedOAuthScopes":["openid","email"],"CallbackURLs":["%s"],
                 "SupportedIdentityProviders":["ExampleOidc"]}
                """.formatted(poolId, CALLBACK)).path("UserPoolClient").path("ClientId").asText();
        Map<String, String> query = authorizeRequest(clientId);

        browserGet(null, "/cognito-idp/oauth2/authorize", query, null)
                .then().statusCode(400).body("error", equalTo("invalid_request"));
        browserGet(null, "/cognito-idp/login", query, null)
                .then().statusCode(400).body("error", equalTo("invalid_request"));
    }

    @Test
    void emailUsernamePoolSignsInByEmail() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"EmailUsernamePool","UsernameAttributes":["email"]}
                """).path("UserPool").path("Id").asText();
        String clientId = codeClient(poolId);
        String email = "alias-" + System.nanoTime() + "@example.com";
        Pool pool = withUser(poolId, clientId, email);
        assertFalse(pool.username().equals(email), "the canonical username of an email pool is generated");

        Response signedIn = signIn(null, new Pool(poolId, clientId, email, pool.sub()), authorizeRequest(clientId));

        JsonNode idToken = jwtPayload(redeem(null, clientId, code(signedIn), VERIFIER)
                .then().statusCode(200).extract().path("id_token"));
        assertEquals(pool.username(), idToken.path("cognito:username").asText());
        assertEquals(pool.sub(), idToken.path("sub").asText());
        assertEquals(email, idToken.path("email").asText());
    }

    @Test
    void userWhoMustSetANewPasswordIsToldWhy() throws Exception {
        String poolId = cognitoJson("CreateUserPool", "{\"PoolName\":\"TemporaryPasswordPool\"}").path("UserPool").path("Id").asText();
        String clientId = codeClient(poolId);
        String username = "temporary-" + System.nanoTime();
        cognitoAction("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","TemporaryPassword":"Temp1234!"}
                """.formatted(poolId, username)).then().statusCode(200);

        Response page = loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(clientId));
        Response refused = submit(null, page, username, "Temp1234!");

        refused.then().statusCode(400).body(containsString("must set a new password"));
        assertNull(setCookie(refused, "cognito"));
    }

    /** AWS's own form posts the authorization request in the query string and only the credentials in the body. */
    @Test
    void signInAcceptsTheRequestInTheQueryStringAsAwsFormPostsIt() throws Exception {
        Pool pool = newPool();
        Map<String, String> query = authorizeRequest(pool.clientId());
        Response page = browserGet(null, "/cognito-idp/login", query, null);

        Response signedIn = given().redirects().follow(false)
                .cookie("XSRF-TOKEN", page.getCookie("XSRF-TOKEN"))
                .queryParams(query)
                .formParam("_csrf", page.getCookie("XSRF-TOKEN"))
                .formParam("username", pool.username())
                .formParam("password", PASSWORD)
                .when().post("/cognito-idp/login");

        signedIn.then().statusCode(302);
        redeem(null, pool.clientId(), code(signedIn), VERIFIER).then().statusCode(200);
    }

    @Test
    void customDomainServesLoginAndLogoutAtItsRoot() throws Exception {
        Pool pool = newPool();
        String domain = "managed-login-" + System.nanoTime() + ".teos.localhost.floci.io";
        cognitoJson("CreateUserPoolDomain", customDomain(domain, pool.poolId(), requestCertificate(domain)));

        Response authorize = browserGet(domain, "/oauth2/authorize", authorizeRequest(pool.clientId()), null);
        String location = authorize.then().statusCode(302).extract().header("Location");
        assertEquals("/login", URI.create(location).getPath());
        Response page = follow(domain, location);
        page.then().statusCode(200);
        assertEquals("/login", formAction(page.asString()));
        Response signedIn = submit(domain, page, pool.username(), PASSWORD);
        signedIn.then().statusCode(302);
        redeem(domain, pool.clientId(), code(signedIn), VERIFIER).then().statusCode(200);

        Response logout = browserGet(domain, "/logout",
                Map.of("client_id", pool.clientId(), "logout_uri", SIGNED_OUT), signedIn.getCookie("cognito"));
        logout.then().statusCode(302).header("Location", equalTo(SIGNED_OUT));
        assertEquals("cognito=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax", setCookie(logout, "cognito"));

        String anotherPoolsClient = newPool().clientId();
        browserGet(domain, "/login", authorizeRequest(anotherPoolsClient), null)
                .then().statusCode(400).body("error", equalTo("invalid_client"));
    }

    @Test
    void customDomainScopesTheCodeGrantTheSameWay() throws Exception {
        Pool pool = newPool(List.of("openid", "email", ADMIN_SCOPE));
        String domain = "managed-login-scope-" + System.nanoTime() + ".teos.localhost.floci.io";
        cognitoJson("CreateUserPoolDomain", customDomain(domain, pool.poolId(), requestCertificate(domain)));

        Response tokens = redeem(domain, pool.clientId(),
                code(signIn(domain, pool, withField(authorizeRequest(pool.clientId()), "scope", "email"))), VERIFIER);

        tokens.then().statusCode(200);
        assertEquals(Set.of("access_token", "refresh_token", "expires_in", "token_type"), fieldNames(tokens));
        assertEquals(Set.of("email"), scopes(tokens.path("access_token")));
        String refused = browserGet(domain, "/oauth2/authorize",
                withField(authorizeRequest(pool.clientId()), "scope", "openid phone"), null)
                .then().statusCode(302).extract().header("Location");
        assertTrue(refused.startsWith(CALLBACK + "?"), refused);
        assertEquals(Map.of("error", "invalid_request", "error_description", "invalid_scope", "state", "client-state"),
                queryOf(refused));
    }

    /**
     * AWS: with {@code login_hint}, "managed login fills the username field with your hint value", on
     * {@code /oauth2/authorize} and on {@code /login}. The hint is escaped like the rest of the page.
     */
    @Test
    void loginHintReachesTheLoginPageAndFillsTheUsernameOnEitherHost() throws Exception {
        Pool pool = newPool();
        String domain = "login-hint-" + System.nanoTime() + ".teos.localhost.floci.io";
        cognitoJson("CreateUserPoolDomain", customDomain(domain, pool.poolId(), requestCertificate(domain)));
        String hint = "o'hara+\"<b>\"@example.com";

        for (String host : new String[] {null, domain}) {
            Map<String, String> query = authorizeRequest(pool.clientId());
            query.put("login_hint", hint);
            String location = browserGet(host, host == null ? "/cognito-idp/oauth2/authorize" : "/oauth2/authorize",
                    query, null).then().statusCode(302).extract().header("Location");
            assertEquals(hint, queryOf(location).get("login_hint"), location);

            String html = follow(host, location).then().statusCode(200).extract().asString();
            assertEquals("<input id=\"username\" name=\"username\" type=\"text\" autocomplete=\"username\" "
                    + "autocapitalize=\"none\" value=\"o&#39;hara+&quot;&lt;b&gt;&quot;@example.com\" required>",
                    input(html, "username"));
            assertFalse(html.contains("<b>"), html);
            assertTrue(html.contains("name=\"password\""), "a pool without a sign-in policy keeps the password form");
        }
    }

    /**
     * The issue's pool: email usernames, a sign-in policy of EMAIL_OTP alone, and a client that allows
     * USER_AUTH. The page asks for the username, emails a code through Floci's SES, and signs in with it
     * exactly as the password form does: a code and the state on the callback, redeemed with PKCE.
     */
    @Test
    void choiceBasedSignInAsksForTheUsernameThenSignsInWithAnEmailedCode() throws Exception {
        PasswordlessPool pool = newPasswordlessPool(List.of("EMAIL_OTP"), true);
        JsonNode userAuth = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_AUTH","AuthParameters":{"USERNAME":"%s"}}
                """.formatted(pool.clientId(), pool.email()));
        assertEquals(List.of("EMAIL_OTP"), MAPPER.convertValue(userAuth.path("AvailableChallenges"), List.class));
        Map<String, String> query = authorizeRequest(pool.clientId());
        query.put("login_hint", pool.email());

        Response usernameStep = loginPage(null, "/cognito-idp/oauth2/authorize", query);
        String usernameHtml = usernameStep.asString();
        assertTrue(input(usernameHtml, "username").contains("value=\"" + pool.email() + "\""), usernameHtml);
        assertNull(input(usernameHtml, "password"), "no password field for a pool that allows only email codes");

        Response codeStep = post(null, usernameStep, Map.of("username", pool.email()));
        codeStep.then().statusCode(200).contentType(startsWith("text/html"));
        String codeHtml = codeStep.asString();
        assertNotNull(input(codeHtml, "code"), codeHtml);
        assertNull(input(codeHtml, "password"), codeHtml);
        assertNull(setCookie(codeStep, "cognito"));
        assertNotNull(hiddenFields(codeHtml).get("session"), "the USER_AUTH session travels in the form");

        Response signedIn = post(null, codeStep, Map.of("code", latestEmailCode(pool.email())));

        signedIn.then().statusCode(302);
        String location = signedIn.getHeader("Location");
        assertTrue(location.startsWith(CALLBACK + "?code="), location);
        assertEquals("client-state", queryOf(location).get("state"));
        assertNotNull(setCookie(signedIn, "cognito"));
        JsonNode idToken = jwtPayload(redeem(null, pool.clientId(), code(signedIn), VERIFIER)
                .then().statusCode(200).extract().path("id_token"));
        assertEquals(pool.sub(), idToken.path("sub").asText());
        assertEquals(pool.email(), idToken.path("email").asText());
        assertEquals("client-nonce", idToken.path("nonce").asText());
    }

    @Test
    void wrongEmailCodeShowsTheCodeFormAgainAndTheRightCodeStillSignsIn() throws Exception {
        PasswordlessPool pool = newPasswordlessPool(List.of("EMAIL_OTP"), false);
        Response codeStep = post(null, loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId())),
                Map.of("username", pool.email()));
        String code = latestEmailCode(pool.email());

        Response wrong = post(null, codeStep, Map.of("code", differentCode(code)));

        wrong.then().statusCode(400).contentType(startsWith("text/html"));
        assertNull(wrong.getHeader("Location"));
        assertNull(setCookie(wrong, "cognito"));
        assertTrue(wrong.asString().contains("<p role=\"alert\">Invalid verification code provided, please try again.</p>"),
                wrong.asString());
        assertNotNull(input(wrong.asString(), "code"), "the code form is shown again");

        Response signedIn = post(null, wrong, Map.of("code", code));
        redeem(null, pool.clientId(), code(signedIn), VERIFIER).then().statusCode(200);
    }

    /**
     * With PASSWORD and EMAIL_OTP allowed, every user chooses between them, password or not: a user with a
     * password signs in with it, and one without fails it as a wrong password does and signs in with a code.
     */
    @Test
    void everyUserChoosesAFactorWhetherOrNotTheyHaveAPassword() throws Exception {
        PasswordlessPool withPassword = newPasswordlessPool(List.of("PASSWORD", "EMAIL_OTP"), true);

        Response choice = post(null, loginPage(null, "/cognito-idp/oauth2/authorize",
                authorizeRequest(withPassword.clientId())), Map.of("username", withPassword.email()));
        choice.then().statusCode(200);
        String choiceHtml = choice.asString();
        assertTrue(choiceHtml.contains("<button type=\"submit\" name=\"challenge\" value=\"PASSWORD\">"), choiceHtml);
        assertTrue(choiceHtml.contains("<button type=\"submit\" name=\"challenge\" value=\"EMAIL_OTP\">"), choiceHtml);
        Response passwordStep = post(null, choice, Map.of("challenge", "PASSWORD"));
        String passwordHtml = passwordStep.then().statusCode(200).extract().asString();
        assertTrue(input(passwordHtml, "username").contains("value=\"" + withPassword.email() + "\""), passwordHtml);
        Response signedIn = post(null, passwordStep, Map.of("username", withPassword.email(), "password", PASSWORD));
        redeem(null, withPassword.clientId(), code(signedIn), VERIFIER).then().statusCode(200);

        PasswordlessPool withoutPassword = newPasswordlessPool(List.of("PASSWORD", "EMAIL_OTP"), false);
        Response noPasswordChoice = post(null, loginPage(null, "/cognito-idp/oauth2/authorize",
                authorizeRequest(withoutPassword.clientId())), Map.of("username", withoutPassword.email()));
        String noPasswordChoiceHtml = noPasswordChoice.then().statusCode(200).extract().asString();
        assertTrue(noPasswordChoiceHtml.contains("<button type=\"submit\" name=\"challenge\" value=\"PASSWORD\">"),
                noPasswordChoiceHtml);
        Response refused = post(null, post(null, noPasswordChoice, Map.of("challenge", "PASSWORD")),
                Map.of("username", withoutPassword.email(), "password", PASSWORD));
        refused.then().statusCode(400);
        assertTrue(refused.asString().contains("<p role=\"alert\">" + INCORRECT_CREDENTIALS + "</p>"), refused.asString());
        Response codeStep = post(null, noPasswordChoice, Map.of("challenge", "EMAIL_OTP"));
        assertNotNull(input(codeStep.then().statusCode(200).extract().asString(), "code"), codeStep.asString());
        Response codeSignedIn = post(null, codeStep, Map.of("code", latestEmailCode(withoutPassword.email())));
        redeem(null, withoutPassword.clientId(), code(codeSignedIn), VERIFIER).then().statusCode(200);
    }

    /**
     * The step after the username is the same page for every username, so it cannot tell who has an account,
     * which factors they have, or whether they can sign in: with PASSWORD and EMAIL_OTP allowed, each is offered
     * both, and no code is sent before one is chosen.
     */
    @Test
    void everyUsernameIsOfferedTheSameFactors() throws Exception {
        PasswordlessPool pool = newPasswordlessPool(List.of("PASSWORD", "EMAIL_OTP"), true);
        List<String> usernames = List.of(pool.email(), addEmailUser(pool.poolId(), false, true),
                addEmailUser(pool.poolId(), true, false), disabledUser(pool.poolId()),
                resetRequiredUser(pool.poolId()), "nobody-" + System.nanoTime() + "@example.com");

        String expected = null;
        for (String username : usernames) {
            Response choice = post(null, loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId())),
                    Map.of("username", username));

            String html = choice.then().statusCode(200).extract().asString();
            assertEquals(username, hiddenFields(html).get("username"), html);
            String page = withoutPerRequestValues(html);
            if (expected == null) {
                expected = page;
                assertTrue(page.contains("<button type=\"submit\" name=\"challenge\" value=\"PASSWORD\">"), page);
                assertTrue(page.contains("<button type=\"submit\" name=\"challenge\" value=\"EMAIL_OTP\">"), page);
            }
            assertEquals(expected, page, username);
            assertEquals(0, emailsTo(username).size(), username);
        }
    }

    /**
     * With EMAIL_OTP alone, every username goes straight to the same code step, whose session does not name
     * the user. Only a user who can sign in and has a verified email is sent a code; anyone else is sent
     * nothing, and every code is wrong for them in the same words as for a user with an account who typed a
     * wrong one.
     */
    @Test
    void everyUsernameGetsTheCodeStepButOnlyAUserWhoCanSignInIsSentACode() throws Exception {
        PasswordlessPool pool = newPasswordlessPool(List.of("EMAIL_OTP"), false);
        List<String> usernames = List.of(pool.email(), addEmailUser(pool.poolId(), false, false),
                disabledUser(pool.poolId()), resetRequiredUser(pool.poolId()),
                "nobody-" + System.nanoTime() + "@example.com");

        String expectedCodeStep = null;
        String expectedRefusal = null;
        String wrongCode = null;
        Set<Integer> sessionLengths = new HashSet<>();
        for (String username : usernames) {
            Response codeStep = post(null, loginPage(null, "/cognito-idp/oauth2/authorize",
                    authorizeRequest(pool.clientId())), Map.of("username", username));
            String html = codeStep.then().statusCode(200).extract().asString();
            assertNotNull(input(html, "code"), username + ": " + html);
            String session = hiddenFields(html).get("session");
            String decodedSession = new String(Base64.getDecoder().decode(session), StandardCharsets.UTF_8);
            assertFalse(decodedSession.contains(username) || decodedSession.contains(pool.sub()),
                    "the session must not name the user: " + decodedSession);
            sessionLengths.add(session.length());
            if (username.equals(pool.email())) {
                wrongCode = differentCode(latestEmailCode(username));
                expectedCodeStep = withoutPerRequestValues(html);
            } else {
                assertEquals(0, emailsTo(username).size(), username);
            }
            assertEquals(expectedCodeStep, withoutPerRequestValues(html), username);

            Response refused = post(null, codeStep, Map.of("code", wrongCode));

            String refusedHtml = refused.then().statusCode(400).extract().asString();
            assertTrue(refusedHtml.contains("<p role=\"alert\">Invalid verification code provided, please try again.</p>"),
                    username + ": " + refusedHtml);
            if (expectedRefusal == null) {
                expectedRefusal = withoutPerRequestValues(refusedHtml);
            }
            assertEquals(expectedRefusal, withoutPerRequestValues(refusedHtml), username);
        }
        assertEquals(1, sessionLengths.size(), "every session is as long as any other: " + sessionLengths);
    }

    /**
     * A user who can no longer sign in learns why only from the right code: after the user is disabled, a
     * wrong code still reads as wrong, and the right one gives the reason, back at the username step since
     * the code is used up.
     */
    @Test
    void userDisabledAfterTheCodeWasSentLearnsWhyOnlyFromTheRightCode() throws Exception {
        PasswordlessPool pool = newPasswordlessPool(List.of("EMAIL_OTP"), false);
        Response codeStep = post(null, loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId())),
                Map.of("username", pool.email()));
        String code = latestEmailCode(pool.email());
        cognitoAction("AdminDisableUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(pool.poolId(), pool.email())).then().statusCode(200);

        Response wrong = post(null, codeStep, Map.of("code", differentCode(code)));
        wrong.then().statusCode(400);
        assertTrue(wrong.asString().contains("<p role=\"alert\">Invalid verification code provided, please try again.</p>"),
                wrong.asString());

        Response refused = post(null, wrong, Map.of("code", code));

        String html = refused.then().statusCode(400).extract().asString();
        assertTrue(html.contains("<p role=\"alert\">User is disabled</p>"), html);
        assertNull(input(html, "code"), "the code is used up, so the page does not ask for it again");
        assertTrue(input(html, "username").contains("value=\"" + pool.email() + "\""), html);
        assertNull(refused.getHeader("Location"));
        assertNull(setCookie(refused, "cognito"));
    }

    /**
     * The right code is used up before PostAuthentication runs, so when the trigger fails the page goes back to
     * the username with the trigger's error, instead of asking again for a code that can no longer sign in.
     */
    @Test
    void failedPostAuthenticationAfterTheRightCodeGoesBackToTheUsernameStep() throws Exception {
        PasswordlessPool pool = newPasswordlessPool(List.of("EMAIL_OTP"), false,
                Map.of("PostAuthentication", POST_AUTHENTICATION_ARN));
        when(lambdaService.invoke(anyString(), eq(POST_AUTHENTICATION_ARN), any(), any()))
                .thenReturn(new InvokeResult(200, "Unhandled",
                        MAPPER.writeValueAsBytes(Map.of("errorMessage", "audit log unavailable")), null, "req-id"));
        Response codeStep = post(null, loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId())),
                Map.of("username", pool.email()));

        Response failed = post(null, codeStep, Map.of("code", latestEmailCode(pool.email())));

        String html = failed.then().statusCode(400).contentType(startsWith("text/html")).extract().asString();
        assertTrue(html.contains("<p role=\"alert\">PostAuthentication failed with error audit log unavailable</p>"), html);
        assertNull(input(html, "code"), "the code is used up, so the page does not ask for it again");
        assertNull(hiddenFields(html).get("session"), html);
        assertTrue(input(html, "username").contains("value=\"" + pool.email() + "\""), html);
        assertNull(failed.getHeader("Location"));
        assertNull(setCookie(failed, "cognito"));
    }

    /**
     * Asking for a code again within the resend limit shows the code step as the first request did, and the code
     * already sent signs in from it, so the limit cannot tell a username with an account from one without.
     */
    @Test
    void askingForACodeAgainWithinTheResendLimitStillShowsTheCodeStep() throws Exception {
        PasswordlessPool pool = newPasswordlessPool(List.of("EMAIL_OTP"), false);
        post(null, loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId())),
                Map.of("username", pool.email())).then().statusCode(200);

        Response again = post(null, loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId())),
                Map.of("username", pool.email()));

        assertNotNull(input(again.then().statusCode(200).extract().asString(), "code"), again.asString());
        Response signedIn = post(null, again, Map.of("code", latestEmailCode(pool.email())));
        redeem(null, pool.clientId(), code(signedIn), VERIFIER).then().statusCode(200);
    }

    /** An unknown user is asked for a code like anyone else, so the page does not tell who has an account. */
    @Test
    void unknownUserIsAskedForACodeThatNeverSignsIn() throws Exception {
        PasswordlessPool pool = newPasswordlessPool(List.of("EMAIL_OTP"), false);
        String nobody = "nobody-" + System.nanoTime() + "@example.com";

        Response codeStep = post(null, loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId())),
                Map.of("username", nobody));
        assertNotNull(input(codeStep.then().statusCode(200).extract().asString(), "code"), codeStep.asString());
        assertEquals(0, emailsTo(nobody).size(), "nothing is sent to an address with no account");

        Response refused = post(null, codeStep, Map.of("code", "123456"));

        refused.then().statusCode(400);
        assertNull(refused.getHeader("Location"));
        assertTrue(refused.asString().contains("<p role=\"alert\">Invalid verification code provided, please try again.</p>"),
                refused.asString());
    }

    @Test
    void passwordIsRefusedWhenThePolicyAllowsOnlyEmailCodes() throws Exception {
        PasswordlessPool pool = newPasswordlessPool(List.of("EMAIL_OTP"), true);

        Response refused = post(null, loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId())),
                Map.of("username", pool.email(), "password", PASSWORD));

        refused.then().statusCode(400).contentType(startsWith("text/html"));
        assertNull(refused.getHeader("Location"));
        assertNull(setCookie(refused, "cognito"));
    }

    /**
     * Choice-based sign-in needs EMAIL_OTP in the sign-in policy and USER_AUTH on the client; without
     * either, the page is the username and password form of before, and it signs in the same way.
     */
    @Test
    void passwordFormStaysWithoutAnEmailCodePolicyOrWithoutUserAuthOnTheClient() throws Exception {
        PasswordlessPool passwordPolicy = newPasswordlessPool(List.of("PASSWORD"), true);
        PasswordlessPool withoutUserAuth = newPasswordlessPool(List.of("PASSWORD", "EMAIL_OTP"), true);
        cognitoAction("UpdateUserPoolClient", """
                {"UserPoolId":"%s","ClientId":"%s","AllowedOAuthFlowsUserPoolClient":true,"AllowedOAuthFlows":["code"],
                 "AllowedOAuthScopes":["openid","email"],"CallbackURLs":["%s"],"SupportedIdentityProviders":["COGNITO"],
                 "ExplicitAuthFlows":["ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(withoutUserAuth.poolId(), withoutUserAuth.clientId(), CALLBACK)).then().statusCode(200);

        for (PasswordlessPool pool : List.of(passwordPolicy, withoutUserAuth)) {
            Response page = loginPage(null, "/cognito-idp/oauth2/authorize", authorizeRequest(pool.clientId()));
            assertEquals("<input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"current-password\" "
                    + "required>", input(page.asString(), "password"));
            Response signedIn = submit(null, page, pool.email(), PASSWORD);
            redeem(null, pool.clientId(), code(signedIn), VERIFIER).then().statusCode(200);
        }
    }

    /** Without a custom domain Host, /login stays S3's path-style bucket route. */
    @Test
    void loginOnFlocisOwnHostIsNotManagedLogin() {
        given().redirects().follow(false)
                .when().get("/login")
                .then().statusCode(404).body(containsString("NoSuchBucket"));
    }

    @Test
    void discoveryAdvertisesS256() throws Exception {
        Pool pool = newPool();

        given().when().get("/" + pool.poolId() + "/.well-known/openid-configuration")
                .then().statusCode(200).body("code_challenge_methods_supported", equalTo(List.of("S256")));
    }

    // ──────────────────────────── Fixtures ────────────────────────────

    /** A pool with a confirmed user, and a public client that uses the code grant and allows only refresh. */
    private record Pool(String poolId, String clientId, String username, String sub) {
    }

    private static Pool newPool() throws Exception {
        return newPool(List.of("openid", "email"));
    }

    private static Pool newPool(List<String> allowedScopes) throws Exception {
        String poolId = cognitoJson("CreateUserPool", "{\"PoolName\":\"ManagedLoginPool\"}").path("UserPool").path("Id").asText();
        return withUser(poolId, codeClient(poolId, allowedScopes), "user-" + System.nanoTime());
    }

    private static Pool newPoolWithPreTokenGenerationTrigger() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"ManagedLoginTriggerPool","LambdaConfig":{"PreTokenGeneration":"%s"}}
                """.formatted(PRE_TOKEN_GENERATION_ARN)).path("UserPool").path("Id").asText();
        return withUser(poolId, codeClient(poolId), "user-" + System.nanoTime());
    }

    /**
     * Signs a new pool's user in through the code grant, with a V2 PreTokenGeneration trigger
     * answering {@code accessTokenGeneration}, and returns the access token it mints.
     */
    private String codeGrantAccessTokenReshapedByTrigger(Map<String, Object> accessTokenGeneration) throws Exception {
        Pool pool = newPoolWithPreTokenGenerationTrigger();
        when(lambdaService.invoke(anyString(), eq(PRE_TOKEN_GENERATION_ARN), any(), any()))
                .thenReturn(triggerResponse(Map.of("claimsAndScopeOverrideDetails",
                        Map.of("accessTokenGeneration", accessTokenGeneration))));
        return redeem(null, pool.clientId(),
                code(signIn(null, pool, authorizeRequest(pool.clientId()))), VERIFIER).path("access_token");
    }

    /** A successful trigger invocation returning {@code response} as the Lambda's payload. */
    private static InvokeResult triggerResponse(Map<String, Object> response) throws Exception {
        return new InvokeResult(200, null, MAPPER.writeValueAsBytes(Map.of("response", response)), null, "req-id");
    }

    private static String codeClient(String poolId) throws Exception {
        return codeClient(poolId, List.of("openid", "email"));
    }

    private static String codeClient(String poolId, List<String> allowedScopes) throws Exception {
        return cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"managed-login","AllowedOAuthFlowsUserPoolClient":true,
                 "AllowedOAuthFlows":["code"],"AllowedOAuthScopes":%s,"CallbackURLs":["%s"],
                 "LogoutURLs":["%s"],"SupportedIdentityProviders":["COGNITO"],"ExplicitAuthFlows":["ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(poolId, MAPPER.writeValueAsString(allowedScopes), CALLBACK, SIGNED_OUT))
                .path("UserPoolClient").path("ClientId").asText();
    }

    /** Replaces the client's AllowedOAuthScopes, sending the rest of its OAuth configuration unchanged. */
    private static void allowScopes(Pool pool, List<String> allowedScopes) throws Exception {
        cognitoJson("UpdateUserPoolClient", """
                {"UserPoolId":"%s","ClientId":"%s","ClientName":"managed-login","AllowedOAuthFlowsUserPoolClient":true,
                 "AllowedOAuthFlows":["code"],"AllowedOAuthScopes":%s,"CallbackURLs":["%s"],
                 "LogoutURLs":["%s"],"SupportedIdentityProviders":["COGNITO"],"ExplicitAuthFlows":["ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(pool.poolId(), pool.clientId(), MAPPER.writeValueAsString(allowedScopes), CALLBACK,
                SIGNED_OUT));
    }

    private static Pool withUser(String poolId, String clientId, String username) throws Exception {
        String email = username.contains("@") ? username : username + "@example.com";
        cognitoAction("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","UserAttributes":[{"Name":"email","Value":"%s"}]}
                """.formatted(poolId, username, email)).then().statusCode(200);
        cognitoAction("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, username, PASSWORD)).then().statusCode(200);
        JsonNode user = cognitoJson("AdminGetUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, username));
        String sub = null;
        for (JsonNode attribute : user.path("UserAttributes")) {
            if ("sub".equals(attribute.path("Name").asText())) {
                sub = attribute.path("Value").asText();
            }
        }
        return new Pool(poolId, clientId, user.path("Username").asText(), sub);
    }

    /** A pool with email usernames and a sign-in policy, a client that allows USER_AUTH, and a user with a verified email. */
    private record PasswordlessPool(String poolId, String clientId, String email, String sub) {
    }

    private static PasswordlessPool newPasswordlessPool(List<String> firstFactors, boolean withPassword)
            throws Exception {
        return newPasswordlessPool(firstFactors, withPassword, Map.of());
    }

    private static PasswordlessPool newPasswordlessPool(List<String> firstFactors, boolean withPassword,
                                                        Map<String, String> lambdaConfig) throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"PasswordlessPool","UsernameAttributes":["email"],
                 "Policies":{"SignInPolicy":{"AllowedFirstAuthFactors":%s}},"LambdaConfig":%s}
                """.formatted(MAPPER.writeValueAsString(firstFactors), MAPPER.writeValueAsString(lambdaConfig)))
                .path("UserPool").path("Id").asText();
        String clientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"passwordless","AllowedOAuthFlowsUserPoolClient":true,
                 "AllowedOAuthFlows":["code"],"AllowedOAuthScopes":["openid","email"],"CallbackURLs":["%s"],
                 "SupportedIdentityProviders":["COGNITO"],"ExplicitAuthFlows":["ALLOW_USER_AUTH","ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(poolId, CALLBACK)).path("UserPoolClient").path("ClientId").asText();
        String email = addEmailUser(poolId, withPassword, true);
        JsonNode user = cognitoJson("AdminGetUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, email));
        String sub = null;
        for (JsonNode attribute : user.path("UserAttributes")) {
            if ("sub".equals(attribute.path("Name").asText())) {
                sub = attribute.path("Value").asText();
            }
        }
        return new PasswordlessPool(poolId, clientId, email, sub);
    }

    /**
     * Adds a user whose username is a new email address, verified or not, with {@link #PASSWORD} as their
     * permanent password when {@code withPassword}, and returns the address.
     */
    private static String addEmailUser(String poolId, boolean withPassword, boolean emailVerified) throws Exception {
        String email = "passwordless-" + System.nanoTime() + "@example.com";
        cognitoAction("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","MessageAction":"SUPPRESS",
                 "UserAttributes":[{"Name":"email","Value":"%s"},{"Name":"email_verified","Value":"%s"}]}
                """.formatted(poolId, email, email, emailVerified)).then().statusCode(200);
        if (withPassword) {
            cognitoAction("AdminSetUserPassword", """
                    {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                    """.formatted(poolId, email, PASSWORD)).then().statusCode(200);
        }
        return email;
    }

    /** A user with a password and a verified email who is disabled, and so cannot sign in. */
    private static String disabledUser(String poolId) throws Exception {
        String email = addEmailUser(poolId, true, true);
        cognitoAction("AdminDisableUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, email)).then().statusCode(200);
        return email;
    }

    /** A user with a verified email whose password an administrator reset, so they cannot sign in until they set one. */
    private static String resetRequiredUser(String poolId) throws Exception {
        String email = addEmailUser(poolId, true, true);
        cognitoAction("AdminResetUserPassword", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(poolId, email)).then().statusCode(200);
        return email;
    }

    /**
     * {@code html} with the values that differ from one rendering to the next blanked: the CSRF token, the
     * username and the USER_AUTH session.
     */
    private static String withoutPerRequestValues(String html) {
        return html.replaceAll("name=\"(_csrf|username|session)\" value=\"[^\"]*\"", "name=\"$1\" value=\"\"");
    }

    /** The code in the one message Floci's SES holds for {@code email}. */
    private static String latestEmailCode(String email) throws Exception {
        JsonNode messages = emailsTo(email);
        assertEquals(1, messages.size(), messages.toString());
        Matcher matcher = SIX_DIGIT_CODE.matcher(messages.get(0).path("Body").path("text_part").asText());
        assertTrue(matcher.find(), messages.toString());
        return matcher.group(1);
    }

    private static JsonNode emailsTo(String email) throws Exception {
        String body = given().queryParam("email", email).when().get("/_aws/ses")
                .then().statusCode(200).extract().asString();
        return MAPPER.readTree(body).path("messages");
    }

    private static String differentCode(String code) {
        return "000000".equals(code) ? "000001" : "000000";
    }

    /** A PKCE authorization request, as a single-page app sends one. */
    private static Map<String, String> authorizeRequest(String clientId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", clientId);
        query.put("redirect_uri", CALLBACK);
        query.put("scope", "openid email");
        query.put("state", "client-state");
        query.put("nonce", "client-nonce");
        query.put("code_challenge", CHALLENGE);
        query.put("code_challenge_method", "S256");
        return query;
    }

    // ──────────────────────────── Browser ────────────────────────────

    private static Response browserGet(String host, String path, Map<String, String> query, String sessionCookie) {
        RequestSpecification request = given().redirects().follow(false).queryParams(query);
        if (host != null) {
            request.header("Host", host);
        }
        if (sessionCookie != null) {
            request.cookie("cognito", sessionCookie);
        }
        return request.when().get(path);
    }

    /** Follows a relative redirect, as a browser resolves it against the page's own origin. */
    private static Response follow(String host, String location) {
        URI uri = URI.create(location);
        assertFalse(uri.isAbsolute(), location);
        return browserGet(host, uri.getPath(), queryOf(location), null);
    }

    private static Response loginPage(String host, String authorizePath, Map<String, String> query) {
        String location = browserGet(host, authorizePath, query, null).then().statusCode(302).extract().header("Location");
        Response page = follow(host, location);
        page.then().statusCode(200);
        return page;
    }

    /** Submits the sign-in form the way a browser does: its hidden fields, its CSRF cookie, and the credentials. */
    private static Response submit(String host, Response page, String username, String password) {
        String html = page.asString();
        RequestSpecification request = given().redirects().follow(false)
                .cookie("XSRF-TOKEN", page.getCookie("XSRF-TOKEN"))
                .formParams(hiddenFields(html))
                .formParam("username", username)
                .formParam("password", password);
        if (host != null) {
            request.header("Host", host);
        }
        return request.when().post(formAction(html));
    }

    /** Submits a page's form as a browser does: its hidden fields and CSRF cookie, with {@code fields} filled in. */
    private static Response post(String host, Response page, Map<String, String> fields) {
        String html = page.asString();
        Map<String, String> form = new LinkedHashMap<>(hiddenFields(html));
        form.putAll(fields);
        RequestSpecification request = given().redirects().follow(false)
                .cookie("XSRF-TOKEN", page.getCookie("XSRF-TOKEN"))
                .formParams(form);
        if (host != null) {
            request.header("Host", host);
        }
        return request.when().post(formAction(html));
    }

    /** Signs a fresh browser in through the authorize endpoint and returns the redirect to the callback. */
    private static Response signIn(String host, Pool pool, Map<String, String> query) {
        Response page = loginPage(host, host == null ? "/cognito-idp/oauth2/authorize" : "/oauth2/authorize", query);
        return submit(host, page, pool.username(), PASSWORD);
    }

    private static Response redeem(String host, String clientId, String code, String verifier) {
        RequestSpecification request = given()
                .formParam("grant_type", "authorization_code")
                .formParam("client_id", clientId)
                .formParam("code", code)
                .formParam("redirect_uri", CALLBACK);
        if (verifier != null) {
            request.formParam("code_verifier", verifier);
        }
        if (host != null) {
            request.header("Host", host);
        }
        return request.when().post(host == null ? "/cognito-idp/oauth2/token" : "/oauth2/token");
    }

    /** An access token for the pool's user from a fresh sign-in that requested {@code scope}. */
    private static String accessToken(Pool pool, String scope) {
        Map<String, String> query = withField(authorizeRequest(pool.clientId()), "scope", scope);
        return redeem(null, pool.clientId(), code(signIn(null, pool, query)), VERIFIER)
                .then().statusCode(200).extract().path("access_token");
    }

    private static String code(Response callbackRedirect) {
        callbackRedirect.then().statusCode(302);
        String code = queryOf(callbackRedirect.getHeader("Location")).get("code");
        assertNotNull(code, callbackRedirect.getHeader("Location"));
        return code;
    }

    // ──────────────────────────── Parsing ────────────────────────────

    /** The access token's scopes, as a set: AWS does not promise their order. */
    private static Set<String> scopes(String accessToken) throws Exception {
        return Set.of(jwtPayload(accessToken).path("scope").asText().split(" "));
    }

    private static Set<String> fieldNames(Response tokens) throws Exception {
        Set<String> names = new HashSet<>();
        MAPPER.readTree(tokens.asString()).fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** The Set-Cookie header that sets {@code name}, or null. */
    private static String setCookie(Response response, String name) {
        for (String header : response.getHeaders().getValues("Set-Cookie")) {
            if (header.startsWith(name + "=")) {
                return header;
            }
        }
        return null;
    }

    private static Map<String, String> hiddenFields(String html) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = HIDDEN_FIELD.matcher(html);
        while (matcher.find()) {
            fields.put(unescapeHtml(matcher.group(1)), unescapeHtml(matcher.group(2)));
        }
        return fields;
    }

    /** The {@code <input>} element with {@code id}, or null when the page has none. */
    private static String input(String html, String id) {
        Matcher matcher = Pattern.compile("<input id=\"" + Pattern.quote(id) + "\"[^>]*>").matcher(html);
        return matcher.find() ? matcher.group() : null;
    }

    private static String formAction(String html) {
        Matcher matcher = FORM_ACTION.matcher(html);
        assertTrue(matcher.find(), html);
        return unescapeHtml(matcher.group(1));
    }

    private static String unescapeHtml(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private static Map<String, String> queryOf(String location) {
        Map<String, String> parameters = new LinkedHashMap<>();
        String query = URI.create(location).getRawQuery();
        if (query == null) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            parameters.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return parameters;
    }

    /** A copy of {@code parameters} with {@code name} set to {@code value}, or removed when it is null. */
    private static Map<String, String> withField(Map<String, String> parameters, String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(parameters);
        if (value == null) {
            copy.remove(name);
        } else {
            copy.put(name, value);
        }
        return copy;
    }
}
