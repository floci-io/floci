package io.github.hectorvent.floci.services.signin;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SigninIntegrationTest {

    private static final String CLIENT_ID = "arn:aws:signin:::devtools/same-device";
    private static final String REDIRECT_URI = "http://127.0.0.1:4567/oauth/callback";

    @Test
    void authorizePresentsLocalConsentPageBeforeRedirectingWithCode() throws Exception {
        String verifier = verifier();
        String state = UUID.randomUUID().toString();

        String location = request()
                .redirects().follow(false)
                .queryParam("client_id", CLIENT_ID)
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "SHA-256")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid")
                .queryParam("state", state)
                .when()
                .get("/v1/authorize")
                .then()
                .statusCode(302)
                .header("Cache-Control", equalTo("no-store"))
                .extract()
                .header("Location");

        URI consent = URI.create(location);
        assertFalse(consent.isAbsolute());
        assertEquals("/_floci/signin/consent", consent.getPath());
        String page = request()
                .get(location)
                .then()
                .statusCode(200)
                .header("Cache-Control", equalTo("no-store"))
                .header("Referrer-Policy", equalTo("no-referrer"))
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("Content-Security-Policy", equalTo("default-src 'none'; img-src 'self'; "
                        + "style-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .extract()
                .asString();
        assertTrue(page.contains("Sign in to Floci"));
        assertTrue(page.contains("src=\"/_floci/signin/floci-header.svg\""));
        assertTrue(page.contains("alt=\"Floci\""));
        assertTrue(page.contains("href=\"/_floci/signin/floci.svg\""));
        assertTrue(page.contains("href=\"/_floci/signin/signin.css\""));
        assertTrue(page.contains("<form method=\"post\" action=\"/_floci/signin/consent\">"));
        String requestId = queryParams(consent.getRawQuery()).get("request_id");
        assertTrue(page.contains("name=\"request_id\" value=\"" + requestId + "\""));
        assertTrue(page.contains("name=\"action\" value=\"cancel\""));
        assertTrue(page.contains("name=\"action\" value=\"continue\""));
        assertTrue(page.contains("Continue"));

        request()
                .get("/_floci/signin/floci-header.svg")
                .then()
                .statusCode(200)
                .header("Content-Type", org.hamcrest.Matchers.startsWith("image/svg+xml"))
                .body(org.hamcrest.Matchers.containsString("viewBox=\"0 0 531.25 156.71\""))
                .body(org.hamcrest.Matchers.containsString("#FF9900"));

        request()
                .get("/_floci/signin/floci.svg")
                .then()
                .statusCode(200)
                .header("Content-Type", org.hamcrest.Matchers.startsWith("image/svg+xml"));

        request()
                .get("/_floci/signin/signin.css")
                .then()
                .statusCode(200)
                .header("Content-Type", org.hamcrest.Matchers.startsWith("text/css"))
                .body(org.hamcrest.Matchers.containsString("--floci-brand: #5559a7"))
                .body(org.hamcrest.Matchers.containsString("--floci-orange: #ff9900"))
                .body(org.hamcrest.Matchers.containsString("border-color: #667085"));

        String redirectLocation = approve(location);
        URI redirect = URI.create(redirectLocation);
        assertEquals(REDIRECT_URI, redirect.getScheme() + "://" + redirect.getAuthority() + redirect.getPath());
        Map<String, String> params = queryParams(redirect.getRawQuery());
        assertTrue(params.get("code").length() >= 1);
        assertEquals(state, params.get("state"));
    }

    @Test
    void authorizeRejectsOauthS256Alias() throws Exception {
        request()
                .queryParam("client_id", CLIENT_ID)
                .queryParam("code_challenge", challenge(verifier()))
                .queryParam("code_challenge_method", "S256")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid")
                .queryParam("state", UUID.randomUUID().toString())
                .when()
                .get("/v1/authorize")
                .then()
                .statusCode(400)
                .body("error", equalTo("invalid_request"))
                .body("message", equalTo("code_challenge_method must be SHA-256"))
                .body("$", not(hasKey("__type")));
    }

    @Test
    void denyingConsentRedirectsWithAccessDenied() throws Exception {
        String state = UUID.randomUUID().toString();
        String consentLocation = request()
                .redirects().follow(false)
                .queryParam("client_id", CLIENT_ID)
                .queryParam("code_challenge", challenge(verifier()))
                .queryParam("code_challenge_method", "SHA-256")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid")
                .queryParam("state", state)
                .when()
                .get("/v1/authorize")
                .then()
                .statusCode(302)
                .extract()
                .header("Location");

        URI consent = URI.create(consentLocation);
        String requestId = queryParams(consent.getRawQuery()).get("request_id");
        String redirectLocation = request()
                .redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("request_id", requestId)
                .formParam("action", "cancel")
                .when()
                .post("/_floci/signin/consent")
                .then()
                .statusCode(302)
                .header("Cache-Control", equalTo("no-store"))
                .extract()
                .header("Location");

        Map<String, String> params = queryParams(URI.create(redirectLocation).getRawQuery());
        assertEquals("access_denied", params.get("error"));
        assertEquals(state, params.get("state"));
    }

    @Test
    void consentRejectsMissingRequestIdAndUnknownAction() throws Exception {
        request()
                .get("/_floci/signin/consent")
                .then()
                .statusCode(400)
                .header("Cache-Control", equalTo("no-store"))
                .header("Referrer-Policy", equalTo("no-referrer"))
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("Content-Security-Policy", equalTo("default-src 'none'; img-src 'self'; "
                        + "style-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"))
                .body(org.hamcrest.Matchers.containsString("request_id is required"));

        String verifier = verifier();
        String state = UUID.randomUUID().toString();
        String consentLocation = beginAuthorization(verifier, state);
        String requestId = queryParams(URI.create(consentLocation).getRawQuery()).get("request_id");

        request()
                .contentType("application/x-www-form-urlencoded")
                .formParam("request_id", requestId)
                .formParam("action", "approve")
                .when()
                .post("/_floci/signin/consent")
                .then()
                .statusCode(400)
                .body(org.hamcrest.Matchers.containsString("action must be continue or cancel"));

        String redirectLocation = approve(consentLocation);
        Map<String, String> params = queryParams(URI.create(redirectLocation).getRawQuery());
        assertTrue(params.containsKey("code"));
        assertEquals(state, params.get("state"));
    }

    @Test
    void malformedTokenJsonUsesOauthErrorEnvelope() {
        request()
                .contentType("application/json")
                .body("{")
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .header("Cache-Control", equalTo("no-store"))
                .header("X-Amzn-ErrorType", nullValue())
                .body("error", equalTo("unsupported_grant_type"))
                .body("error_description", equalTo(
                        "The authorization grant type is not supported by the authorization server."))
                .body("$", not(hasKey("message")))
                .body("$", not(hasKey("__type")));

        String supportedGrantWithTrailingJson = """
                {"clientId":"%s","grantType":"authorization_code","code":"bogus",
                 "redirectUri":"%s","codeVerifier":"%s"}{}
                """.formatted(CLIENT_ID, REDIRECT_URI, verifier());
        request()
                .contentType("application/json")
                .body(supportedGrantWithTrailingJson)
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .header("Cache-Control", equalTo("no-store"))
                .header("X-Amzn-ErrorType", nullValue())
                .body("error", equalTo("unsupported_grant_type"))
                .body("error_description", equalTo(
                        "The authorization grant type is not supported by the authorization server."));
    }

    @Test
    void invalidAuthorizationCodeUsesModeledValidationErrorForJsonAndForm() {
        Map<String, String> invalidGrant = Map.of(
                "clientId", CLIENT_ID,
                "grantType", "authorization_code",
                "code", "bogus",
                "redirectUri", REDIRECT_URI,
                "codeVerifier", verifier());

        request()
                .contentType("application/json")
                .body(invalidGrant)
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .header("Cache-Control", equalTo("no-store"))
                .header("X-Amzn-ErrorType", equalTo("ValidationException"))
                .body("error", equalTo("INVALID_REQUEST"))
                .body("message", equalTo(
                        "The request is missing a required parameter, includes an invalid parameter value, "
                                + "or is otherwise malformed."))
                .body("$", not(hasKey("error_description")));

        request()
                .contentType("application/x-www-form-urlencoded")
                .body("client_id=" + CLIENT_ID
                        + "&grant_type=authorization_code"
                        + "&code=bogus"
                        + "&redirect_uri=" + java.net.URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8)
                        + "&code_verifier=" + verifier())
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .header("Cache-Control", equalTo("no-store"))
                .header("X-Amzn-ErrorType", equalTo("ValidationException"))
                .body("error", equalTo("INVALID_REQUEST"));
    }

    @Test
    void unsupportedGrantAndMalformedFormUseOauthErrorEnvelope() {
        request()
                .contentType("application/json")
                .body(Map.of("clientId", CLIENT_ID, "grantType", "unsupported"))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .header("Cache-Control", equalTo("no-store"))
                .header("X-Amzn-ErrorType", nullValue())
                .body("error", equalTo("unsupported_grant_type"))
                .body("error_description", equalTo(
                        "The authorization grant type is not supported by the authorization server."));

        request()
                .contentType("application/x-www-form-urlencoded")
                .body("grant_type=%ZZ")
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .header("Cache-Control", equalTo("no-store"))
                .header("X-Amzn-ErrorType", nullValue())
                .body("error", equalTo("unsupported_grant_type"))
                .body("error_description", equalTo(
                        "The authorization grant type is not supported by the authorization server."));
    }

    @Test
    void s3BucketNamedSigninDoesNotCollideWithConsentUi() {
        String objectBody = "S3 object under the signin bucket";
        String formerAssetBody = "S3 object at the former static asset path";
        request()
                .when()
                .put("/signin")
                .then()
                .statusCode(200);

        try {
            request()
                    .contentType("text/plain")
                    .body(objectBody)
                    .when()
                    .put("/signin/consent")
                    .then()
                    .statusCode(200);

            request()
                    .when()
                    .get("/signin/consent")
                    .then()
                    .statusCode(200)
                    .body(equalTo(objectBody));

            request()
                    .contentType("text/plain")
                    .body(formerAssetBody)
                    .when()
                    .put("/signin/signin.css")
                    .then()
                    .statusCode(200);

            request()
                    .when()
                    .get("/signin/signin.css")
                    .then()
                    .statusCode(200)
                    .body(equalTo(formerAssetBody));
        } finally {
            request().delete("/signin/signin.css").then().statusCode(204);
            request().delete("/signin/consent").then().statusCode(204);
            request().delete("/signin").then().statusCode(204);
        }
    }

    @Test
    void authorizationCodeExchangeReturnsTemporaryAwsCredentials() throws Exception {
        String verifier = verifier();
        String state = UUID.randomUUID().toString();
        String location = authorize(verifier, state);
        String code = queryParams(URI.create(location).getRawQuery()).get("code");

        var response = request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "authorization_code",
                        "code", code,
                        "redirectUri", REDIRECT_URI,
                        "codeVerifier", verifier))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(200)
                .body("accessToken.accessKeyId", notNullValue())
                .body("accessToken.secretAccessKey", notNullValue())
                .body("accessToken.sessionToken", notNullValue())
                .body("tokenType", equalTo("aws_sigv4"))
                .body("expiresIn", equalTo(900))
                .body("refreshToken", notNullValue())
                .body("idToken", notNullValue())
                .extract()
                .response();

        String accessKey = response.path("accessToken.accessKeyId");
        assertTrue(accessKey.startsWith("ASIA"));

        request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "authorization_code",
                        "code", code,
                        "redirectUri", REDIRECT_URI,
                        "codeVerifier", verifier))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .header("Cache-Control", equalTo("no-store"))
                .header("X-Amzn-ErrorType", equalTo("ValidationException"))
                .body("error", equalTo("INVALID_REQUEST"));
    }

    @Test
    void refreshTokenReturnsStableCredentialsWithinAccessTokenLifetime() throws Exception {
        String verifier = verifier();
        String code = queryParams(URI.create(authorize(verifier, UUID.randomUUID().toString())).getRawQuery())
                .get("code");
        String refreshToken = request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "authorization_code",
                        "code", code,
                        "redirectUri", REDIRECT_URI,
                        "codeVerifier", verifier))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(200)
                .extract()
                .path("refreshToken");

        var firstRefresh = request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "refresh_token",
                        "refreshToken", refreshToken))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(200)
                .body("accessToken.accessKeyId", notNullValue())
                .body("tokenType", equalTo("aws_sigv4"))
                .body("expiresIn", equalTo(900))
                .body("refreshToken", not(equalTo(refreshToken)))
                .body("idToken", equalTo(null))
                .extract()
                .response();

        String rotatedRefreshToken = firstRefresh.path("refreshToken");

        request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "refresh_token",
                        "refreshToken", refreshToken))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(200)
                .body("accessToken.accessKeyId", equalTo(firstRefresh.path("accessToken.accessKeyId")))
                .body("accessToken.secretAccessKey", equalTo(firstRefresh.path("accessToken.secretAccessKey")))
                .body("accessToken.sessionToken", equalTo(firstRefresh.path("accessToken.sessionToken")))
                .body("refreshToken", equalTo(rotatedRefreshToken))
                .body("idToken", equalTo(null));
    }

    @Test
    void rejectsInvalidPkceProof() throws Exception {
        String verifier = verifier();
        String code = queryParams(URI.create(authorize(verifier, UUID.randomUUID().toString())).getRawQuery())
                .get("code");

        request()
                .contentType("application/json")
                .body(Map.of(
                        "clientId", CLIENT_ID,
                        "grantType", "authorization_code",
                        "code", code,
                        "redirectUri", REDIRECT_URI,
                        "codeVerifier", verifier + "wrong"))
                .when()
                .post("/v1/token")
                .then()
                .statusCode(400)
                .header("Cache-Control", equalTo("no-store"))
                .header("X-Amzn-ErrorType", equalTo("ValidationException"))
                .body("error", equalTo("INVALID_REQUEST"));
    }

    private String authorize(String verifier, String state) throws Exception {
        return approve(beginAuthorization(verifier, state));
    }

    private String beginAuthorization(String verifier, String state) throws Exception {
        return request()
                .redirects().follow(false)
                .queryParam("client_id", CLIENT_ID)
                .queryParam("code_challenge", challenge(verifier))
                .queryParam("code_challenge_method", "SHA-256")
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid")
                .queryParam("state", state)
                .when()
                .get("/v1/authorize")
                .then()
                .statusCode(302)
                .extract()
                .header("Location");
    }

    private String approve(String consentLocation) {
        URI consent = URI.create(consentLocation);
        String requestId = queryParams(consent.getRawQuery()).get("request_id");
        return request()
                .redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("request_id", requestId)
                .formParam("action", "continue")
                .when()
                .post("/_floci/signin/consent")
                .then()
                .statusCode(302)
                .header("Cache-Control", equalTo("no-store"))
                .extract()
                .header("Location");
    }

    private RequestSpecification request() {
        return given();
    }

    private static String verifier() {
        return "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    }

    private static String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static Map<String, String> queryParams(String query) {
        return java.util.Arrays.stream(query.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
    }
}
