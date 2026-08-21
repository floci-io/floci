package io.github.hectorvent.floci.services.signin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.signin.model.TokenResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Context;

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
    private final ObjectMapper objectMapper;

    @Inject
    public SigninController(SigninService signinService, ObjectMapper objectMapper) {
        this.signinService = signinService;
        this.objectMapper = objectMapper;
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
        String location = signinService.authorize(clientId, codeChallenge, codeChallengeMethod,
                redirectUri, responseType, scope, state, resource);
        return Response.status(Response.Status.FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
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
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
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
        } catch (IOException e) {
            throw new SigninException("invalid_request", "Request body must be valid JSON");
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

}
