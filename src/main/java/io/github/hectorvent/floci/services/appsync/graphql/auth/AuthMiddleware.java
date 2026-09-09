package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.services.appsync.model.AdditionalAuthenticationProvider;
import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class AuthMiddleware {

    private final ApiKeyAuthValidator apiKeyAuthValidator;
    private final IamAuthValidator iamAuthValidator;
    private final CognitoAuthValidator cognitoAuthValidator;
    private final OidcAuthValidator oidcAuthValidator;
    private final LambdaAuthorizerValidator lambdaAuthorizerValidator;
    private final JwtClaimsDecoder jwtClaimsDecoder;

    @Inject
    public AuthMiddleware(ApiKeyAuthValidator apiKeyAuthValidator,
                          IamAuthValidator iamAuthValidator,
                          CognitoAuthValidator cognitoAuthValidator,
                          OidcAuthValidator oidcAuthValidator,
                          LambdaAuthorizerValidator lambdaAuthorizerValidator,
                          JwtClaimsDecoder jwtClaimsDecoder) {
        this.apiKeyAuthValidator = apiKeyAuthValidator;
        this.iamAuthValidator = iamAuthValidator;
        this.cognitoAuthValidator = cognitoAuthValidator;
        this.oidcAuthValidator = oidcAuthValidator;
        this.lambdaAuthorizerValidator = lambdaAuthorizerValidator;
        this.jwtClaimsDecoder = jwtClaimsDecoder;
    }

    public AppSyncAuthContext authenticate(Map<String, String> headers, GraphqlApi api, AuthRequestInfo info) {
        ClassifiedMode classified = CredentialClassifier.classify(headers);
        Set<AuthenticationType> configured = configuredModes(api);
        return switch (classified) {
            case NONE -> throw AppSyncAuth.missingAuthorizationHeader();
            case API_KEY -> authenticateApiKey(headers, api, configured, info);
            case AWS_IAM -> authenticateIam(headers, api, configured, info);
            case BEARER_JWT -> authenticateBearer(headers, api, configured, info);
            case AWS_LAMBDA -> authenticateLambda(headers, api, configured, info);
        };
    }

    private AppSyncAuthContext authenticateApiKey(
            Map<String, String> headers, GraphqlApi api, Set<AuthenticationType> configured, AuthRequestInfo info) {
        if (!configured.contains(AuthenticationType.API_KEY)) {
            throw AppSyncAuth.unauthorized();
        }
        String key = CredentialClassifier.header(headers, "x-api-key");
        apiKeyAuthValidator.validate(api.getApiId(), key);
        return context(null, AppSyncAuth.AUTH_TYPE_API_KEY, AuthenticationType.API_KEY, Set.of(), api, null, info);
    }

    private AppSyncAuthContext authenticateIam(
            Map<String, String> headers, GraphqlApi api, Set<AuthenticationType> configured, AuthRequestInfo info) {
        if (!configured.contains(AuthenticationType.AWS_IAM)) {
            throw AppSyncAuth.unauthorized();
        }
        String authorization = CredentialClassifier.header(headers, "authorization");
        Map<String, Object> identity = iamAuthValidator.validateRequest(authorization, api.getApiId(), info);
        String accessKeyId = String.valueOf(identity.get("user"));
        return context(identity, AppSyncAuth.AUTH_TYPE_IAM, AuthenticationType.AWS_IAM, Set.of(), api, accessKeyId, info);
    }

    private AppSyncAuthContext authenticateBearer(
            Map<String, String> headers, GraphqlApi api, Set<AuthenticationType> configured, AuthRequestInfo info) {
        String authorization = CredentialClassifier.header(headers, "authorization");
        Map<String, Object> claims = jwtClaimsDecoder.decode(authorization).orElseThrow(AppSyncAuth::unauthorized);
        List<ProviderConfig> cognito = providersOf(api, AuthenticationType.AMAZON_COGNITO_USER_POOLS);
        List<ProviderConfig> oidc = providersOf(api, AuthenticationType.OPENID_CONNECT);
        for (ProviderConfig provider : cognito) {
            if (cognitoAuthValidator.matchesProvider(claims, provider.config())) {
                Map<String, Object> identity = cognitoAuthValidator.validate(authorization, provider.config(), info.sourceIp());
                return context(identity, AppSyncAuth.AUTH_TYPE_COGNITO, AuthenticationType.AMAZON_COGNITO_USER_POOLS,
                        Set.of(), api, null, info);
            }
        }
        boolean skipIssuer = oidc.size() == 1 && configured.size() == 1
                && configured.contains(AuthenticationType.OPENID_CONNECT);
        for (ProviderConfig provider : oidc) {
            if (oidcAuthValidator.matchesProvider(claims, provider.config(), skipIssuer)) {
                Map<String, Object> identity = oidcAuthValidator.validate(
                        authorization, provider.config(), skipIssuer);
                return context(identity, AppSyncAuth.AUTH_TYPE_OIDC, AuthenticationType.OPENID_CONNECT,
                        Set.of(), api, null, info);
            }
        }
        throw AppSyncAuth.unauthorized();
    }

    private AppSyncAuthContext authenticateLambda(
            Map<String, String> headers, GraphqlApi api, Set<AuthenticationType> configured, AuthRequestInfo info) {
        if (!configured.contains(AuthenticationType.AWS_LAMBDA)) {
            throw AppSyncAuth.unauthorized();
        }
        String token = CredentialClassifier.header(headers, "authorization");
        Map<String, Object> lambdaConfig = lambdaConfig(api);
        LambdaAuthorizerResult result = lambdaAuthorizerValidator.authorize(api.getApiId(), token, lambdaConfig, info);
        Map<String, Object> identity = IdentityBuilder.lambda(result.resolverContext());
        return context(identity, AppSyncAuth.AUTH_TYPE_LAMBDA, AuthenticationType.AWS_LAMBDA,
                new HashSet<>(result.deniedFields()), api, null, info);
    }

    private static AppSyncAuthContext context(
            Map<String, Object> identity,
            String authType,
            AuthenticationType authenticationType,
            Set<String> deniedFields,
            GraphqlApi api,
            String accessKeyId,
            AuthRequestInfo info
    ) {
        return new AppSyncAuthContext(
                identity, authType, authenticationType, deniedFields, api, accessKeyId, info.region(), info.accountId());
    }

    static Set<AuthenticationType> configuredModes(GraphqlApi api) {
        Set<AuthenticationType> modes = new HashSet<>();
        if (api.getAuthenticationType() != null) {
            modes.add(api.getAuthenticationType());
        }
        if (api.getAdditionalAuthenticationProviders() != null) {
            for (AdditionalAuthenticationProvider provider : api.getAdditionalAuthenticationProviders()) {
                if (provider != null && provider.getAuthenticationType() != null) {
                    modes.add(provider.getAuthenticationType());
                }
            }
        }
        return modes;
    }

    static boolean hasAdditionalModes(GraphqlApi api) {
        return api.getAdditionalAuthenticationProviders() != null
                && !api.getAdditionalAuthenticationProviders().isEmpty();
    }

    private static List<ProviderConfig> providersOf(GraphqlApi api, AuthenticationType type) {
        List<ProviderConfig> result = new ArrayList<>();
        if (api.getAuthenticationType() == type) {
            result.add(new ProviderConfig(configFor(api, type, null)));
        }
        if (api.getAdditionalAuthenticationProviders() != null) {
            for (AdditionalAuthenticationProvider provider : api.getAdditionalAuthenticationProviders()) {
                if (provider != null && provider.getAuthenticationType() == type) {
                    result.add(new ProviderConfig(configFor(api, type, provider)));
                }
            }
        }
        return result;
    }

    private static Map<String, Object> configFor(
            GraphqlApi api, AuthenticationType type, AdditionalAuthenticationProvider additional) {
        if (additional != null) {
            return switch (type) {
                case AMAZON_COGNITO_USER_POOLS -> additional.getUserPoolConfig();
                case OPENID_CONNECT -> additional.getOpenIDConnectConfig();
                case AWS_LAMBDA -> additional.getLambdaAuthorizerConfig();
                default -> Map.of();
            };
        }
        return switch (type) {
            case AMAZON_COGNITO_USER_POOLS -> api.getUserPoolConfig();
            case OPENID_CONNECT -> api.getOpenIDConnectConfig();
            case AWS_LAMBDA -> api.getLambdaAuthorizerConfig();
            default -> Map.of();
        };
    }

    private static Map<String, Object> lambdaConfig(GraphqlApi api) {
        if (api.getAuthenticationType() == AuthenticationType.AWS_LAMBDA) {
            return api.getLambdaAuthorizerConfig();
        }
        if (api.getAdditionalAuthenticationProviders() != null) {
            for (AdditionalAuthenticationProvider provider : api.getAdditionalAuthenticationProviders()) {
                if (provider != null && provider.getAuthenticationType() == AuthenticationType.AWS_LAMBDA) {
                    return provider.getLambdaAuthorizerConfig();
                }
            }
        }
        return api.getLambdaAuthorizerConfig();
    }

    private record ProviderConfig(Map<String, Object> config) {
        private ProviderConfig {
            config = config == null ? Map.of() : config;
        }
    }
}
