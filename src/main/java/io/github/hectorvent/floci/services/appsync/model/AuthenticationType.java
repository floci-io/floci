package io.github.hectorvent.floci.services.appsync.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public enum AuthenticationType {
    API_KEY,
    AWS_IAM,
    AMAZON_COGNITO_USER_POOLS,
    OPENID_CONNECT,
    AWS_LAMBDA
}
