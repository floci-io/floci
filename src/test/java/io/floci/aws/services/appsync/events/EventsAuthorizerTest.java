package io.floci.aws.services.appsync.events;

import io.floci.aws.services.appsync.AppSyncService;
import io.floci.aws.services.appsync.graphql.AppSyncTransportException;
import io.floci.aws.services.appsync.graphql.auth.AuthRequestInfo;
import io.floci.aws.services.appsync.graphql.auth.CognitoAuthValidator;
import io.floci.aws.services.appsync.graphql.auth.IamAuthValidator;
import io.floci.aws.services.appsync.graphql.auth.OidcAuthValidator;
import io.floci.aws.services.appsync.model.Api;
import io.floci.aws.services.appsync.model.ApiKey;
import io.floci.aws.services.appsync.model.AuthMode;
import io.floci.aws.services.appsync.model.ChannelNamespace;
import io.floci.aws.services.appsync.model.EventAuthProvider;
import io.floci.aws.services.appsync.model.EventConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EventsAuthorizerTest {

    private static final String AUTHORIZER_ARN = "arn:aws:lambda:us-east-1:111122223333:function:auth";

    private final AppSyncService appSyncService = mock(AppSyncService.class);
    private final IamAuthValidator iamAuthValidator = mock(IamAuthValidator.class);
    private final EventsLambdaAuthorizer lambdaAuthorizer = mock(EventsLambdaAuthorizer.class);
    private final EventsAuthorizer authorizer = new EventsAuthorizer(appSyncService, iamAuthValidator,
            mock(CognitoAuthValidator.class), mock(OidcAuthValidator.class), lambdaAuthorizer);

    private static List<AuthMode> modes(String... types) {
        return Arrays.stream(types).map(type -> {
            AuthMode mode = new AuthMode();
            mode.setAuthType(type);
            return mode;
        }).toList();
    }

    /** The SWDEV-1541 shape: IAM publishing, Lambda connect and subscribe, plus an API key provider. */
    private static AppSyncService.EventApiOwner owner() {
        EventAuthProvider iam = new EventAuthProvider();
        iam.setAuthType("AWS_IAM");
        EventAuthProvider lambda = new EventAuthProvider();
        lambda.setAuthType("AWS_LAMBDA");
        lambda.setLambdaAuthorizerConfig(Map.of("authorizerUri", AUTHORIZER_ARN));
        EventAuthProvider apiKey = new EventAuthProvider();
        apiKey.setAuthType("API_KEY");
        EventConfig config = new EventConfig();
        config.setAuthProviders(List.of(iam, lambda, apiKey));
        config.setConnectionAuthModes(modes("AWS_LAMBDA", "API_KEY"));
        config.setDefaultPublishAuthModes(modes("AWS_IAM"));
        config.setDefaultSubscribeAuthModes(modes("AWS_LAMBDA"));
        Api api = new Api();
        api.setApiId("api-1");
        api.setApiArn("arn:aws:appsync:us-east-1:111122223333:apis/api-1");
        api.setEventConfig(config);
        return new AppSyncService.EventApiOwner("111122223333", api);
    }

    private static ChannelNamespace namespace(List<AuthMode> publish, List<AuthMode> subscribe) {
        ChannelNamespace ns = new ChannelNamespace();
        ns.setName("ns");
        ns.setPublishAuthModes(publish);
        ns.setSubscribeAuthModes(subscribe);
        return ns;
    }

    @ParameterizedTest
    @CsvSource({
            "EVENT_CONNECT,   false, AWS_LAMBDA;API_KEY",
            "EVENT_PUBLISH,   false, AWS_IAM",
            "EVENT_SUBSCRIBE, false, AWS_LAMBDA",
            "EVENT_PUBLISH,   true,  API_KEY",
            "EVENT_SUBSCRIBE, true,  API_KEY"})
    void theNamespacesOwnModesReplaceTheApiDefaults(EventsOperation operation, boolean override, String expected) {
        ChannelNamespace ns = override ? namespace(modes("API_KEY"), modes("API_KEY")) : namespace(null, null);

        Set<String> allowed = EventsAuthorizer.allowedModes(owner().api(), operation, ns);

        assertEquals(Set.of(expected.split(";")), allowed);
    }

    @Test
    void aLambdaTokenIsHandedToTheAuthorizerWithItsOperationAndChannel() {
        when(lambdaAuthorizer.authorize(any(), any())).thenReturn(Map.of("team", "t-1"));

        EventsAuthorizer.Authorized authorized = authorizer.authorize(owner(), EventsOperation.EVENT_SUBSCRIBE,
                namespace(null, null), "/ns/run-1", Map.of("Authorization", "token-1", "host", "h"), null);

        assertEquals("AWS_LAMBDA", authorized.authType());
        assertEquals(Map.of("team", "t-1"), authorized.handlerContext());
        ArgumentCaptor<EventsLambdaAuthorizer.Request> request = ArgumentCaptor.forClass(EventsLambdaAuthorizer.Request.class);
        verify(lambdaAuthorizer).authorize(request.capture(), eq(Map.of("authorizerUri", AUTHORIZER_ARN)));
        assertEquals("token-1", request.getValue().authorizationToken());
        assertEquals(EventsOperation.EVENT_SUBSCRIBE, request.getValue().operation());
        assertEquals("/ns/run-1", request.getValue().channel());
        assertEquals("ns", request.getValue().channelNamespaceName());
        assertEquals("us-east-1", request.getValue().region());
        assertEquals("111122223333", request.getValue().accountId());
    }

    @Test
    void aCredentialOfAModeTheOperationDoesNotAcceptIsUnauthorized() {
        AppSyncTransportException refused = assertThrows(AppSyncTransportException.class,
                () -> authorizer.authorize(owner(), EventsOperation.EVENT_PUBLISH, namespace(null, null), "/ns/a",
                        Map.of("x-api-key", "da2-abc"), null));

        assertEquals(401, refused.getHttpStatus());
        verifyNoInteractions(appSyncService, lambdaAuthorizer);
    }

    @Test
    void anApiKeyIsCheckedInTheApisOwnAccount() {
        when(appSyncService.validateApiKeyForAccount("111122223333", "api-1", "da2-good"))
                .thenReturn(Optional.of(new ApiKey()));

        assertEquals("API_KEY", authorizer.authorize(owner(), EventsOperation.EVENT_CONNECT, null, null,
                Map.of("x-api-key", "da2-good"), null).authType());
        assertThrows(AppSyncTransportException.class, () -> authorizer.authorize(owner(),
                EventsOperation.EVENT_CONNECT, null, null, Map.of("x-api-key", "da2-bad"), null));
    }

    @Test
    void noCredentialIsAMissingAuthorizationHeader() {
        AppSyncTransportException refused = assertThrows(AppSyncTransportException.class,
                () -> authorizer.authorize(owner(), EventsOperation.EVENT_CONNECT, null, null, Map.of(), null));

        assertEquals("Missing authorization header", refused.getMessage());
    }

    /** Without the signed HTTP request there is nothing to verify, so IAM over the WebSocket is refused. */
    @Test
    void iamWithoutASignedHttpRequestIsUnauthorized() {
        assertThrows(AppSyncTransportException.class, () -> authorizer.authorize(owner(),
                EventsOperation.EVENT_PUBLISH, namespace(null, null), "/ns/a",
                Map.of("Authorization", "AWS4-HMAC-SHA256 Credential=x"), null));

        verifyNoInteractions(iamAuthValidator);
    }

    @Test
    void anHttpPublishIsVerifiedAsEventPublishOnItsNamespace() {
        EventsAuthorizer.SignedHttpRequest signed = new EventsAuthorizer.SignedHttpRequest("POST", "/event",
                new AuthRequestInfo(null, null, Map.of(), List.of(),
                        "r", "111122223333", "us-east-1", Map.of(), "{}"));

        authorizer.authorize(owner(), EventsOperation.EVENT_PUBLISH, namespace(null, null), "/ns/a",
                Map.of("Authorization", "AWS4-HMAC-SHA256 Credential=x"), signed);

        verify(iamAuthValidator).validateRequest(eq("AWS4-HMAC-SHA256 Credential=x"), eq("POST"), eq("/event"),
                eq("appsync:EventPublish"), eq("arn:aws:appsync:us-east-1:111122223333:apis/api-1/channelNamespace/ns"),
                any());
    }
}
