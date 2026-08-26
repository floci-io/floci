package io.github.hectorvent.floci.services.signin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.signin.model.AuthorizationRequest;
import io.github.hectorvent.floci.services.signin.model.TokenResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** AWS Sign-In data-plane endpoints used by the CLI login credentials provider. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class SigninController {

    private final SigninService signinService;
    private final ObjectReader tokenRequestReader;

    @Inject
    public SigninController(SigninService signinService, ObjectMapper objectMapper) {
        this.signinService = signinService;
        this.tokenRequestReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @GET
    @Path("/v1/authorize")
    public Response authorize(@QueryParam("client_id") String clientId,
                              @QueryParam("code_challenge") String codeChallenge,
                              @QueryParam("code_challenge_method") String codeChallengeMethod,
                              @QueryParam("redirect_uri") String redirectUri,
                              @QueryParam("response_type") String responseType,
                              @QueryParam("scope") String scope,
                              @QueryParam("state") String state,
                              @QueryParam("resource") String resource) {
        String requestId = signinService.beginAuthorization(clientId, codeChallenge, codeChallengeMethod,
                redirectUri, responseType, scope, state, resource);
        String location = UriBuilder.fromPath("/_floci/signin/consent")
                .queryParam("request_id", requestId)
                .build()
                .toString();
        return redirect(location);
    }

    @GET
    @Path("/_floci/signin/consent")
    @Produces(MediaType.TEXT_HTML)
    public Response consentPage(@QueryParam("request_id") String requestId) {
        try {
            AuthorizationRequest request = signinService.pendingAuthorization(requestId);
            String accountId = escape(request.accountId());
            String escapedRequestId = escape(requestId);
            String page = """
                    <!doctype html>
                    <html lang="en">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport" content="width=device-width, initial-scale=1">
                      <meta name="theme-color" content="#5559a7">
                      <link rel="icon" href="/_floci/signin/floci.svg" type="image/svg+xml">
                      <link rel="stylesheet" href="/_floci/signin/signin.css">
                      <title>Floci local sign-in</title>
                    </head>
                    <body>
                      <main class="shell">
                        <header class="brand">
                          <img src="/_floci/signin/floci-header.svg" alt="Floci" width="190" height="56">
                          <span class="local-badge">Local only</span>
                        </header>
                        <section class="content">
                          <span class="eyebrow">AWS CLI access</span>
                          <h1>Sign in to Floci</h1>
                          <p>This local emulator is requesting temporary AWS credentials for account
                            <span class="account">%s</span>.
                          </p>
                          <div class="notice">
                            <span class="notice-dot" aria-hidden="true"></span>
                            <span>No credentials leave this machine. Continuing creates a short-lived local session
                              for the AWS CLI.</span>
                          </div>
                          <form method="post" action="/_floci/signin/consent">
                            <input type="hidden" name="request_id" value="%s">
                            <button class="cancel" type="submit" name="action" value="cancel">Cancel</button>
                            <button class="continue" type="submit" name="action" value="continue">Continue</button>
                          </form>
                        </section>
                      </main>
                    </body>
                    </html>
                    """.formatted(accountId, escapedRequestId);
            return secureHtml(Response.ok(page)).build();
        } catch (SigninException e) {
            return htmlError(e);
        }
    }

    @POST
    @Path("/_floci/signin/consent")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response consent(@FormParam("request_id") String requestId,
                            @FormParam("action") String action) {
        try {
            if (!"continue".equals(action) && !"cancel".equals(action)) {
                throw new SigninException("invalid_request", "action must be continue or cancel");
            }
            String location = "cancel".equals(action)
                    ? signinService.denyAuthorization(requestId)
                    : signinService.completeAuthorization(requestId);
            return redirect(location);
        } catch (SigninException e) {
            return htmlError(e);
        }
    }

    @POST
    @Path("/v1/token")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.APPLICATION_FORM_URLENCODED})
    public Response token(String body, @Context HttpHeaders headers) {
        Map<String, String> values = parseBody(body, headers.getHeaderString(HttpHeaders.CONTENT_TYPE));
        TokenResult result = signinService.exchange(
                value(values, "clientId", "client_id"),
                value(values, "grantType", "grant_type"),
                value(values, "code"),
                value(values, "redirectUri", "redirect_uri"),
                value(values, "codeVerifier", "code_verifier"),
                value(values, "refreshToken", "refresh_token"),
                value(values, "resource"));
        Map<String, Object> response = new LinkedHashMap<>();
        SessionCreds accessToken = result.accessToken();
        response.put("accessToken", Map.of(
                "accessKeyId", accessToken.accessKeyId(),
                "secretAccessKey", accessToken.secretAccessKey(),
                "sessionToken", accessToken.sessionToken()));
        response.put("tokenType", "aws_sigv4");
        response.put("expiresIn", result.expiresIn());
        response.put("refreshToken", result.refreshToken());
        if (result.idToken() != null) {
            response.put("idToken", result.idToken());
        }
        return Response.ok(response).type(MediaType.APPLICATION_JSON).build();
    }

    private Map<String, String> parseBody(String body, String contentType) {
        try {
            if (contentType != null && contentType.startsWith(MediaType.APPLICATION_FORM_URLENCODED)) {
                Map<String, String> values = new LinkedHashMap<>();
                if (body == null || body.isBlank()) {
                    return values;
                }
                for (String pair : body.split("&")) {
                    String[] keyValue = pair.split("=", 2);
                    String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                    String value = keyValue.length == 2
                            ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "";
                    values.put(key, value);
                }
                return values;
            }
            JsonNode root = tokenRequestReader.readTree(body == null ? "" : body);
            if (root != null && root.has("tokenInput")) {
                root = root.get("tokenInput");
            }
            Map<String, String> values = new LinkedHashMap<>();
            if (root != null && root.isObject()) {
                root.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isValueNode()) {
                        values.put(entry.getKey(), entry.getValue().asText());
                    }
                });
            }
            return values;
        } catch (IOException | IllegalArgumentException e) {
            throw SigninTokenException.unsupportedGrant();
        }
    }

    private static String value(Map<String, String> values, String... names) {
        for (String name : names) {
            if (values.containsKey(name)) {
                return values.get(name);
            }
        }
        return null;
    }

    private static Response htmlError(SigninException exception) {
        String message = escape(exception.getMessage());
        String page = """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="theme-color" content="#5559a7">
                  <link rel="icon" href="/_floci/signin/floci.svg" type="image/svg+xml">
                  <link rel="stylesheet" href="/_floci/signin/signin.css">
                  <title>Floci sign-in request error</title>
                </head>
                <body>
                  <main class="shell">
                    <header class="brand">
                      <img src="/_floci/signin/floci-header.svg" alt="Floci" width="190" height="56">
                      <span class="local-badge">Local only</span>
                    </header>
                    <section class="content">
                      <span class="eyebrow">AWS CLI access</span>
                      <h1>Floci sign-in request error</h1>
                      <p>%s</p>
                      <a class="home-link" href="/">Return to Floci</a>
                    </section>
                  </main>
                </body>
                </html>
                """.formatted(message);
        return secureHtml(Response.status(Response.Status.BAD_REQUEST).entity(page)).build();
    }

    private static Response redirect(String location) {
        return Response.status(Response.Status.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    private static Response.ResponseBuilder secureHtml(Response.ResponseBuilder response) {
        return response.type(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'self'; "
                        + "form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

}
