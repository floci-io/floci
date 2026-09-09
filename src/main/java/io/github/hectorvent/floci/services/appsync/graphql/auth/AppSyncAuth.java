package io.github.hectorvent.floci.services.appsync.graphql.auth;

import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;

public final class AppSyncAuth {

    public static final String UNAUTHORIZED_TYPE = "UnauthorizedException";
    public static final String UNAUTHORIZED_MESSAGE = "You are not authorized to make this call.";
    public static final String MISSING_AUTHORIZATION_HEADER = "Missing authorization header";
    public static final String FIELD_UNAUTHORIZED_TYPE = "Unauthorized";

    public static String fieldUnauthorizedMessage(String fieldName, String typeName) {
        return "Not Authorized to access " + fieldName + " on type " + typeName;
    }

    public static final String AUTH_TYPE_API_KEY = "API Key Authorization";
    public static final String AUTH_TYPE_IAM = "IAM Authorization";
    public static final String AUTH_TYPE_COGNITO = "User Pool Authorization";
    public static final String AUTH_TYPE_OIDC = "Open ID Connect Authorization";
    public static final String AUTH_TYPE_LAMBDA = "Lambda Authorization";

    private AppSyncAuth() {
    }

    public static AppSyncTransportException unauthorized() {
        return new AppSyncTransportException(401, UNAUTHORIZED_TYPE, UNAUTHORIZED_MESSAGE);
    }

    public static AppSyncTransportException missingAuthorizationHeader() {
        return new AppSyncTransportException(401, UNAUTHORIZED_TYPE, MISSING_AUTHORIZATION_HEADER);
    }
}
