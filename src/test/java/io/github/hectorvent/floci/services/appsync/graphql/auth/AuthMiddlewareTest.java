package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.appsync.model.AdditionalAuthenticationProvider;
import io.github.hectorvent.floci.services.appsync.model.ApiKey;
import io.github.hectorvent.floci.services.appsync.model.AuthenticationType;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthMiddlewareTest {

    @Mock ApiKeyAuthValidator apiKeyAuthValidator;
    @Mock IamAuthValidator iamAuthValidator;
    @Mock CognitoAuthValidator cognitoAuthValidator;
    @Mock OidcAuthValidator oidcAuthValidator;
    @Mock LambdaAuthorizerValidator lambdaAuthorizerValidator;

    private AuthMiddleware middleware;
    private AuthRequestInfo info;

    @BeforeEach
    void setUp() {
        middleware = new AuthMiddleware(
                apiKeyAuthValidator, iamAuthValidator, cognitoAuthValidator, oidcAuthValidator,
                lambdaAuthorizerValidator, new JwtClaimsDecoder(new ObjectMapper()));
        info = new AuthRequestInfo("{ hello }", null, Map.of(), List.of("127.0.0.1"),
                "req", "000000000000", "us-east-1", Map.of());
    }

    @Test
    void headerMapApiKeySuccess() {
        GraphqlApi api = api(AuthenticationType.API_KEY);
        when(apiKeyAuthValidator.validate("api-1", "da2-abc")).thenReturn(new ApiKey());

        AppSyncAuthContext ctx = middleware.authenticate(Map.of("x-api-key", "da2-abc"), api, info);

        assertEquals(AppSyncAuth.AUTH_TYPE_API_KEY, ctx.authType());
        assertNull(ctx.identity());
        assertEquals(AuthenticationType.API_KEY, ctx.authenticationType());
    }

    @Test
    void missingCredentialsIs401() {
        GraphqlApi api = api(AuthenticationType.API_KEY);
        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class,
                () -> middleware.authenticate(Map.of(), api, info));
        assertEquals(401, ex.getHttpStatus());
        assertEquals(AppSyncAuth.MISSING_AUTHORIZATION_HEADER, ex.getMessage());
    }

    @Test
    void classifiedUnconfiguredModeIs401() {
        GraphqlApi api = api(AuthenticationType.API_KEY);
        assertThrows(AppSyncTransportException.class, () -> middleware.authenticate(
                Map.of("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request"),
                api, info));
    }

    @Test
    void sigV4WinsOverApiKeyAndDoesNotFallBack() {
        GraphqlApi api = api(AuthenticationType.AWS_IAM);
        AdditionalAuthenticationProvider extra = new AdditionalAuthenticationProvider();
        extra.setAuthenticationType(AuthenticationType.API_KEY);
        api.setAdditionalAuthenticationProviders(List.of(extra));
        when(iamAuthValidator.validateRequest(any(), eq("api-1"), any())).thenReturn(Map.of("user", "test"));

        AppSyncAuthContext ctx = middleware.authenticate(Map.of(
                "x-api-key", "da2-abc",
                "Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request"),
                api, info);

        assertEquals(AuthenticationType.AWS_IAM, ctx.authenticationType());
        verify(apiKeyAuthValidator, never()).validate(any(), any());
    }

    @Test
    void sigV4DoesNotFallBackToApiKeyWhenIamIsNotConfigured() {
        GraphqlApi api = api(AuthenticationType.API_KEY);
        AppSyncTransportException ex = assertThrows(AppSyncTransportException.class, () -> middleware.authenticate(Map.of(
                "x-api-key", "da2-abc",
                "Authorization", "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/appsync/aws4_request"),
                api, info));
        assertEquals(401, ex.getHttpStatus());
        verify(apiKeyAuthValidator, never()).validate(any(), any());
    }

    @Test
    void bearerJwtIsNotSentToLambda() {
        GraphqlApi api = api(AuthenticationType.AMAZON_COGNITO_USER_POOLS);
        api.setUserPoolConfig(Map.of("userPoolId", "pool", "awsRegion", "us-east-1"));
        AdditionalAuthenticationProvider lambda = new AdditionalAuthenticationProvider();
        lambda.setAuthenticationType(AuthenticationType.AWS_LAMBDA);
        api.setAdditionalAuthenticationProviders(List.of(lambda));

        Map<String, Object> claims = Map.of(
                "sub", "s",
                "iss", "https://cognito-idp.us-east-1.amazonaws.com/pool",
                "exp", 9999999999L);
        String jwt = JwtClaimsDecoder.encode(new java.util.HashMap<>(claims), new ObjectMapper());
        when(cognitoAuthValidator.matchesProvider(any(), any())).thenReturn(true);
        when(cognitoAuthValidator.validate(any(), any(), any())).thenReturn(Map.of("sub", "s"));

        AppSyncAuthContext ctx = middleware.authenticate(Map.of("Authorization", "Bearer " + jwt), api, info);

        assertEquals(AuthenticationType.AMAZON_COGNITO_USER_POOLS, ctx.authenticationType());
        verify(lambdaAuthorizerValidator, never()).authorize(any(), any(), any(), any());
    }

    @Test
    void opaqueTokenInvokesLambda() {
        GraphqlApi api = api(AuthenticationType.AWS_LAMBDA);
        api.setLambdaAuthorizerConfig(Map.of("authorizerUri", "fn"));
        when(lambdaAuthorizerValidator.authorize(eq("api-1"), eq("ABC123"), any(), any()))
                .thenReturn(new LambdaAuthorizerResult(true, List.of(), Map.of("apple", "green"), null, 10));

        AppSyncAuthContext ctx = middleware.authenticate(Map.of("Authorization", "ABC123"), api, info);

        assertEquals(AppSyncAuth.AUTH_TYPE_LAMBDA, ctx.authType());
        assertEquals("green", ((Map<?, ?>) ctx.identity().get("resolverContext")).get("apple"));
    }

    private static GraphqlApi api(AuthenticationType type) {
        GraphqlApi api = new GraphqlApi();
        api.setApiId("api-1");
        api.setAuthenticationType(type);
        return api;
    }
}
