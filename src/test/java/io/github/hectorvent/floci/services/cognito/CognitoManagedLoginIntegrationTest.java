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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final String PRE_TOKEN_GENERATION_ARN = "arn:aws:lambda:::pre-token-generation";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Only the PreTokenGeneration trigger is configured on any pool here, so nothing else invokes it. */
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
     * The authorize endpoint stores the scopes the request named without checking them against the
     * client's AllowedOAuthScopes, so a request can carry one the client may not use. A V2 trigger can
     * grant claims or scopes from what it is told was requested, so an unallowed scope must not reach it.
     */
    @Test
    void aScopeTheClientIsNotAllowedDoesNotReachTheTrigger() throws Exception {
        Pool pool = newPoolWithPreTokenGenerationTrigger();
        Map<String, String> query = authorizeRequest(pool.clientId());
        query.put("scope", "openid email admin/superuser");
        ArgumentCaptor<byte[]> event = ArgumentCaptor.forClass(byte[].class);
        when(lambdaService.invoke(anyString(), eq(PRE_TOKEN_GENERATION_ARN), event.capture(), any()))
                .thenReturn(triggerResponse(Map.of()));

        Response tokens = redeem(null, pool.clientId(), code(signIn(null, pool, query)), VERIFIER);

        tokens.then().statusCode(200);
        assertEquals(List.of("openid", "email"),
                MAPPER.convertValue(MAPPER.readTree(event.getValue()).path("request").path("scopes"), List.class),
                "admin/superuser is not in the client's AllowedOAuthScopes, so the trigger never sees it");
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
                 "AllowedOAuthFlows":["code"],"AllowedOAuthScopes":["openid"],"CallbackURLs":["%s"],
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
        String poolId = cognitoJson("CreateUserPool", "{\"PoolName\":\"ManagedLoginPool\"}").path("UserPool").path("Id").asText();
        return withUser(poolId, codeClient(poolId), "user-" + System.nanoTime());
    }

    private static Pool newPoolWithPreTokenGenerationTrigger() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"ManagedLoginTriggerPool","LambdaConfig":{"PreTokenGeneration":"%s"}}
                """.formatted(PRE_TOKEN_GENERATION_ARN)).path("UserPool").path("Id").asText();
        return withUser(poolId, codeClient(poolId), "user-" + System.nanoTime());
    }

    /** A successful trigger invocation returning {@code response} as the Lambda's payload. */
    private static InvokeResult triggerResponse(Map<String, Object> response) throws Exception {
        return new InvokeResult(200, null, MAPPER.writeValueAsBytes(Map.of("response", response)), null, "req-id");
    }

    private static String codeClient(String poolId) throws Exception {
        return cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"managed-login","AllowedOAuthFlowsUserPoolClient":true,
                 "AllowedOAuthFlows":["code"],"AllowedOAuthScopes":["openid","email"],"CallbackURLs":["%s"],
                 "LogoutURLs":["%s"],"SupportedIdentityProviders":["COGNITO"],"ExplicitAuthFlows":["ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(poolId, CALLBACK, SIGNED_OUT)).path("UserPoolClient").path("ClientId").asText();
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

    private static String code(Response callbackRedirect) {
        callbackRedirect.then().statusCode(302);
        String code = queryOf(callbackRedirect.getHeader("Location")).get("code");
        assertNotNull(code, callbackRedirect.getHeader("Location"));
        return code;
    }

    // ──────────────────────────── Parsing ────────────────────────────

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
