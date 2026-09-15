package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.IdentityProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class CognitoOidcClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Inject
    public CognitoOidcClient(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), objectMapper);
    }

    CognitoOidcClient(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public JsonNode exchangeCode(IdentityProvider provider, String code, String redirectUri) {
        String tokenEndpoint = requiredEndpoint(provider, "token_url", "token");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", requiredProviderDetail(provider, "client_id"));
        String clientSecret = provider.getProviderDetails().get("client_secret");
        if (clientSecret != null && !clientSecret.isBlank()) {
            form.put("client_secret", clientSecret);
        }
        form.put("code", code);
        form.put("redirect_uri", redirectUri);

        HttpRequest request = requestBuilder(tokenEndpoint, provider.getProviderName(), "token")
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formEncode(form)))
                .build();
        return send(request, "token exchange");
    }

    public JsonNode fetchClaims(IdentityProvider provider, String accessToken) {
        String claimsEndpoint = requiredEndpoint(provider, "attributes_url", "claims");
        if (accessToken == null || accessToken.isBlank()) {
            throw new AwsException("NotAuthorizedException", "Identity provider did not return an access token", 400);
        }

        HttpRequest request = requestBuilder(claimsEndpoint, provider.getProviderName(), "claims")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        return send(request, "claims request");
    }

    private JsonNode send(HttpRequest request, String operation) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AwsException("NotAuthorizedException", "Identity provider " + operation + " failed", 400);
            }
            JsonNode responseJson = objectMapper.readTree(response.body());
            if (responseJson == null || !responseJson.isObject()) {
                throw new AwsException("NotAuthorizedException", "Identity provider " + operation
                        + " returned an invalid response", 400);
            }
            return responseJson;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AwsException("NotAuthorizedException", "Identity provider " + operation + " was interrupted", 400);
        } catch (IOException | IllegalArgumentException e) {
            throw new AwsException("NotAuthorizedException", "Identity provider " + operation + " failed", 400);
        }
    }

    private String requiredEndpoint(IdentityProvider provider, String detailName, String endpointName) {
        String endpoint = provider.getProviderDetails().get(detailName);
        if (endpoint == null || endpoint.isBlank()) {
            throw new AwsException("InvalidParameterException", "Identity provider " + provider.getProviderName()
                    + " does not have a " + endpointName + " endpoint configured", 400);
        }
        return endpoint;
    }

    private HttpRequest.Builder requestBuilder(String endpoint, String providerName, String endpointName) {
        try {
            return HttpRequest.newBuilder(URI.create(endpoint)).timeout(REQUEST_TIMEOUT);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "Identity provider " + providerName
                    + " has an invalid " + endpointName + " endpoint", 400);
        }
    }

    private String requiredProviderDetail(IdentityProvider provider, String detailName) {
        String value = provider.getProviderDetails().get(detailName);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidParameterException", "Identity provider " + provider.getProviderName()
                    + " does not have " + detailName + " configured", 400);
        }
        return value;
    }

    private String formEncode(Map<String, String> form) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : form.entrySet()) {
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            encoded.append('=');
            encoded.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return encoded.toString();
    }
}
