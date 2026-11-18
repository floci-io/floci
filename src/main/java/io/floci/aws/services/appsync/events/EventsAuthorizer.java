package io.floci.aws.services.appsync.events;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.appsync.AppSyncService;
import io.floci.aws.services.appsync.graphql.AppSyncTransportException;
import io.floci.aws.services.appsync.graphql.auth.AppSyncAuth;
import io.floci.aws.services.appsync.graphql.auth.AuthRequestInfo;
import io.floci.aws.services.appsync.graphql.auth.ClassifiedMode;
import io.floci.aws.services.appsync.graphql.auth.CognitoAuthValidator;
import io.floci.aws.services.appsync.graphql.auth.CredentialClassifier;
import io.floci.aws.services.appsync.graphql.auth.IamAuthValidator;
import io.floci.aws.services.appsync.graphql.auth.OidcAuthValidator;
import io.floci.aws.services.appsync.model.Api;
import io.floci.aws.services.appsync.model.AuthMode;
import io.floci.aws.services.appsync.model.ChannelNamespace;
import io.floci.aws.services.appsync.model.EventAuthProvider;
import io.floci.aws.services.appsync.model.EventConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a credential may connect to, subscribe to or publish on an Events API.
 *
 * <p>The modes an operation accepts are the API's {@code connectionAuthModes} for a connection,
 * and for a publish or subscribe the channel namespace's own {@code publishAuthModes} or
 * {@code subscribeAuthModes}, falling back to the API's default publish or subscribe modes. The
 * credential's kind decides which of those it is checked against: {@code x-api-key} for
 * {@code API_KEY}, a SigV4 {@code Authorization} for {@code AWS_IAM}, a JWT for Cognito or OIDC,
 * and any other {@code Authorization} value as a Lambda token.
 *
 * <p>{@code AWS_IAM} is verified only on an HTTP publish, whose signed request this has. Over the
 * WebSocket the credential is a signature of a separate {@code POST /event} request, which Floci
 * does not verify, so an IAM-only connect or subscribe is refused.
 */
@ApplicationScoped
public class EventsAuthorizer {

    /** What a successful authorization established: the mode used and the authorizer's handler context. */
    public record Authorized(String authType, Map<String, Object> handlerContext) {
    }

    /**
     * A SigV4-signed HTTP request, when there is one: the method and raw path the caller signed,
     * the body, and the details the identity carries.
     */
    public record SignedHttpRequest(String method, String path, AuthRequestInfo info) {
    }

    private final AppSyncService appSyncService;
    private final IamAuthValidator iamAuthValidator;
    private final CognitoAuthValidator cognitoAuthValidator;
    private final OidcAuthValidator oidcAuthValidator;
    private final EventsLambdaAuthorizer lambdaAuthorizer;

    @Inject
    public EventsAuthorizer(AppSyncService appSyncService, IamAuthValidator iamAuthValidator,
                            CognitoAuthValidator cognitoAuthValidator, OidcAuthValidator oidcAuthValidator,
                            EventsLambdaAuthorizer lambdaAuthorizer) {
        this.appSyncService = appSyncService;
        this.iamAuthValidator = iamAuthValidator;
        this.cognitoAuthValidator = cognitoAuthValidator;
        this.oidcAuthValidator = oidcAuthValidator;
        this.lambdaAuthorizer = lambdaAuthorizer;
    }

    /**
     * Authorizes {@code operation} for the credential in {@code credentials}, the request headers
     * on HTTP or the {@code authorization} object on the WebSocket. {@code namespace} and
     * {@code channel} are null for a connection.
     */
    public Authorized authorize(AppSyncService.EventApiOwner owner, EventsOperation operation,
                                ChannelNamespace namespace, String channel, Map<String, String> credentials,
                                SignedHttpRequest signed) {
        Api api = owner.api();
        Set<String> allowed = allowedModes(api, operation, namespace);
        ClassifiedMode classified = CredentialClassifier.classify(credentials);
        String authorization = header(credentials, "authorization");
        return switch (classified) {
            case NONE -> throw AppSyncAuth.missingAuthorizationHeader();
            case API_KEY -> {
                requireMode(allowed, "API_KEY");
                appSyncService.validateApiKeyForAccount(owner.accountId(), api.getApiId(),
                        header(credentials, "x-api-key")).orElseThrow(AppSyncAuth::unauthorized);
                yield new Authorized("API_KEY", Map.of());
            }
            case AWS_IAM -> {
                requireMode(allowed, "AWS_IAM");
                if (signed == null || namespace == null) {
                    throw AppSyncAuth.unauthorized();
                }
                String resource = IamAuthValidator.channelNamespaceArn(signed.info().region(), owner.accountId(),
                        api.getApiId(), namespace.getName());
                iamAuthValidator.validateRequest(authorization, signed.method(), signed.path(),
                        "appsync:EventPublish", resource, signed.info());
                yield new Authorized("AWS_IAM", Map.of());
            }
            case BEARER_JWT -> jwt(api, allowed, authorization);
            case AWS_LAMBDA -> {
                if (!allowed.contains("AWS_LAMBDA") && (allowed.contains("AMAZON_COGNITO_USER_POOLS")
                        || allowed.contains("OPENID_CONNECT"))) {
                    yield jwt(api, allowed, authorization);
                }
                requireMode(allowed, "AWS_LAMBDA");
                EventsLambdaAuthorizer.Request request = new EventsLambdaAuthorizer.Request(api.getApiId(),
                        owner.accountId(), regionOf(api), operation,
                        namespace == null ? null : namespace.getName(), channel, authorization, credentials);
                yield new Authorized("AWS_LAMBDA", lambdaAuthorizer.authorize(request, providerConfig(api, "AWS_LAMBDA")));
            }
        };
    }

    /** The auth modes {@code operation} accepts on this API and namespace. */
    static Set<String> allowedModes(Api api, EventsOperation operation, ChannelNamespace namespace) {
        EventConfig config = api.getEventConfig();
        List<AuthMode> modes = switch (operation) {
            case EVENT_CONNECT -> config == null ? null : config.getConnectionAuthModes();
            case EVENT_PUBLISH -> namespace != null && namespace.getPublishAuthModes() != null
                    ? namespace.getPublishAuthModes()
                    : config == null ? null : config.getDefaultPublishAuthModes();
            case EVENT_SUBSCRIBE -> namespace != null && namespace.getSubscribeAuthModes() != null
                    ? namespace.getSubscribeAuthModes()
                    : config == null ? null : config.getDefaultSubscribeAuthModes();
        };
        Set<String> allowed = new HashSet<>();
        if (modes != null) {
            for (AuthMode mode : modes) {
                if (mode != null && mode.getAuthType() != null) {
                    allowed.add(mode.getAuthType());
                }
            }
        }
        return allowed;
    }

    private Authorized jwt(Api api, Set<String> allowed, String authorization) {
        AppSyncTransportException last = AppSyncAuth.unauthorized();
        if (allowed.contains("AMAZON_COGNITO_USER_POOLS")) {
            for (Map<String, Object> config : providerConfigs(api, "AMAZON_COGNITO_USER_POOLS")) {
                try {
                    cognitoAuthValidator.validate(authorization, config, List.of());
                    return new Authorized("AMAZON_COGNITO_USER_POOLS", Map.of());
                } catch (AppSyncTransportException e) {
                    last = e;
                }
            }
        }
        if (allowed.contains("OPENID_CONNECT")) {
            for (Map<String, Object> config : providerConfigs(api, "OPENID_CONNECT")) {
                try {
                    oidcAuthValidator.validate(authorization, config, false);
                    return new Authorized("OPENID_CONNECT", Map.of());
                } catch (AppSyncTransportException e) {
                    last = e;
                }
            }
        }
        throw last;
    }

    private static void requireMode(Set<String> allowed, String mode) {
        if (!allowed.contains(mode)) {
            throw AppSyncAuth.unauthorized();
        }
    }

    private static Map<String, Object> providerConfig(Api api, String authType) {
        List<Map<String, Object>> configs = providerConfigs(api, authType);
        return configs.isEmpty() ? null : configs.getFirst();
    }

    private static List<Map<String, Object>> providerConfigs(Api api, String authType) {
        EventConfig config = api.getEventConfig();
        if (config == null || config.getAuthProviders() == null) {
            return List.of();
        }
        return config.getAuthProviders().stream()
                .filter(provider -> provider != null && authType.equals(provider.getAuthType()))
                .map(provider -> configOf(provider, authType))
                .filter(map -> map != null)
                .toList();
    }

    private static Map<String, Object> configOf(EventAuthProvider provider, String authType) {
        return switch (authType) {
            case "AWS_LAMBDA" -> provider.getLambdaAuthorizerConfig();
            case "AMAZON_COGNITO_USER_POOLS" -> provider.getCognitoConfig();
            case "OPENID_CONNECT" -> provider.getOpenIDConnectConfig();
            default -> Map.of();
        };
    }

    /** The region of the API's ARN, where its Lambda authorizer is invoked by name. */
    static String regionOf(Api api) {
        String apiArn = api.getApiArn();
        return apiArn != null && AwsArnUtils.isArn(apiArn) ? AwsArnUtils.parse(apiArn).region() : null;
    }

    static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
