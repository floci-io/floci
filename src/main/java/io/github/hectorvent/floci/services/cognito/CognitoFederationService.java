package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationCode;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationTransaction;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.IdentityProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CognitoFederationService {

    private static final Duration TRANSACTION_LIFETIME = Duration.ofMinutes(5);
    private static final Duration AUTHORIZATION_CODE_LIFETIME = Duration.ofMinutes(5);
    private static final String OIDC_PROVIDER_TYPE = "OIDC";

    private final CognitoService cognitoService;
    private final CognitoFederationStateStore stateStore;
    private final CognitoOidcClient oidcClient;
    private final Clock clock;

    @Inject
    public CognitoFederationService(CognitoService cognitoService, CognitoFederationStateStore stateStore,
                                    CognitoOidcClient oidcClient, Clock clock) {
        this.cognitoService = cognitoService;
        this.stateStore = stateStore;
        this.oidcClient = oidcClient;
        this.clock = clock;
    }

    public String beginAuthorization(String userPoolId, String clientId, String redirectUri, List<String> scopes,
                                     String nonce, String providerName) {
        return beginAuthorization(userPoolId, clientId, redirectUri, scopes, nonce, providerName, null);
    }

    public String beginAuthorization(String userPoolId, String clientId, String redirectUri, List<String> scopes,
                                     String nonce, String providerName, String relyingPartyState) {
        IdentityProvider provider = cognitoService.describeIdentityProvider(userPoolId, providerName);
        requireOidcProvider(provider);
        String authorizeEndpoint = requiredProviderDetail(provider, "authorize_url", "authorize endpoint");
        String providerClientId = requiredProviderDetail(provider, "client_id", "client_id");
        validateAuthorizeEndpoint(authorizeEndpoint, provider.getProviderName());
        Instant expiresAt = clock.instant().plus(TRANSACTION_LIFETIME);
        CognitoAuthorizationTransaction transaction = new CognitoAuthorizationTransaction(
                userPoolId, clientId, redirectUri, scopes, nonce, providerName, relyingPartyState, expiresAt);
        String state = stateStore.putTransaction(transaction);

        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("response_type", "code");
        parameters.put("client_id", providerClientId);
        parameters.put("redirect_uri", redirectUri);
        parameters.put("scope", providerScopes(provider, scopes));
        parameters.put("state", state);
        if (nonce != null && !nonce.isBlank()) {
            parameters.put("nonce", nonce);
        }
        return appendQuery(authorizeEndpoint, parameters);
    }

    public String completeAuthorization(String state, String providerCode) {
        CognitoAuthorizationTransaction transaction = stateStore.consumeTransaction(state)
                .orElseThrow(() -> new AwsException("InvalidParameterException", "Invalid federation state", 400));
        return completeAuthorization(transaction, providerCode);
    }

    String completeAuthorization(CognitoAuthorizationTransaction transaction, String providerCode) {
        IdentityProvider provider = cognitoService.describeIdentityProvider(
                transaction.userPoolId(), transaction.providerName());
        requireOidcProvider(provider);
        JsonNode tokenResponse = oidcClient.exchangeCode(provider, providerCode, transaction.redirectUri());
        String accessToken = requiredText(tokenResponse, "access_token", "access token");
        JsonNode claims = oidcClient.fetchClaims(provider, accessToken);
        String subject = requiredText(claims, "sub", "subject");
        String issuer = optionalText(claims, "iss");
        CognitoUser user = cognitoService.provisionFederatedUser(transaction.userPoolId(), provider,
                subject, issuer, mapAttributes(provider, claims));
        CognitoAuthorizationCode authorizationCode = new CognitoAuthorizationCode(
                transaction.userPoolId(), transaction.clientId(), user.getUsername(), transaction.redirectUri(),
                transaction.scopes(), clock.instant().plus(AUTHORIZATION_CODE_LIFETIME));
        return stateStore.putAuthorizationCode(authorizationCode);
    }

    private Map<String, String> mapAttributes(IdentityProvider provider, JsonNode claims) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map.Entry<String, String> mapping : provider.getAttributeMapping().entrySet()) {
            JsonNode claim = claims.get(mapping.getValue());
            if (claim != null && !claim.isNull() && claim.isValueNode()) {
                attributes.put(mapping.getKey(), claim.asText());
            }
        }
        return attributes;
    }

    private String providerScopes(IdentityProvider provider, List<String> scopes) {
        String configuredScopes = provider.getProviderDetails().get("authorize_scopes");
        return configuredScopes != null && !configuredScopes.isBlank()
                ? configuredScopes : String.join(" ", scopes);
    }

    private String requiredText(JsonNode json, String fieldName, String description) {
        String value = optionalText(json, fieldName);
        if (value == null) {
            throw new AwsException("InvalidParameterException", "Identity provider response is missing " + description, 400);
        }
        return value;
    }

    private String optionalText(JsonNode json, String fieldName) {
        JsonNode value = json.get(fieldName);
        if (value == null || value.isNull() || !value.isValueNode() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private String requiredProviderDetail(IdentityProvider provider, String detailName, String description) {
        String value = provider.getProviderDetails().get(detailName);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidParameterException", "Identity provider " + provider.getProviderName()
                    + " does not have a " + description + " configured", 400);
        }
        return value;
    }

    private void requireOidcProvider(IdentityProvider provider) {
        if (!OIDC_PROVIDER_TYPE.equals(provider.getProviderType())) {
            throw new AwsException("InvalidParameterException", "Identity provider " + provider.getProviderName()
                    + " must have ProviderType OIDC", 400);
        }
    }

    private void validateAuthorizeEndpoint(String endpoint, String providerName) {
        try {
            URI uri = URI.create(endpoint);
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("URI must be absolute");
            }
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "Identity provider " + providerName
                    + " has an invalid authorize endpoint", 400);
        }
    }

    private String appendQuery(String endpoint, Map<String, String> parameters) {
        StringBuilder result = new StringBuilder(endpoint);
        result.append(endpoint.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (!first) {
                result.append('&');
            }
            result.append(URLEncoder.encode(parameter.getKey(), StandardCharsets.UTF_8));
            result.append('=');
            result.append(URLEncoder.encode(parameter.getValue(), StandardCharsets.UTF_8));
            first = false;
        }
        return result.toString();
    }
}
