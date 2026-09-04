package io.github.hectorvent.flociappsync.graphql.auth;

/** Ported from Floci's {@code services.appsync.model.AuthenticationType}. */
public enum AuthenticationType {
    API_KEY,
    AWS_IAM,
    AMAZON_COGNITO_USER_POOLS,
    OPENID_CONNECT,
    AWS_LAMBDA
}
