package io.floci.aws.services.appsync.graphql.auth;

import io.floci.aws.services.appsync.graphql.AppSyncTransportException;

public final class AppSyncAuth {

    public static final String UNAUTHORIZED_TYPE = "UnauthorizedException";
    public static final String UNAUTHORIZED_MESSAGE = "You are not authorized to make this call.";
    public static final String MISSING_AUTHORIZATION_HEADER = "Missing authorization header";
    public static final String FIELD_UNAUTHORIZED_TYPE = "Unauthorized";
    public static final String UNRECOGNIZED_CLIENT_TYPE = "UnrecognizedClientException";
    public static final String INVALID_SECURITY_TOKEN = "The security token included in the request is invalid";

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

    /** An access key AppSync does not know, a temporary key with no session token, or an empty token. */
    public static AppSyncTransportException unrecognizedClient() {
        return new AppSyncTransportException(403, UNRECOGNIZED_CLIENT_TYPE, INVALID_SECURITY_TOKEN + ".");
    }

    /** A session token that does not fit the key. AWS drops the period here. */
    public static AppSyncTransportException invalidSessionToken() {
        return new AppSyncTransportException(403, UNRECOGNIZED_CLIENT_TYPE, INVALID_SECURITY_TOKEN);
    }

    /** An {@code AWS_IAM} caller whose policies do not allow {@code appsync:GraphQL}. */
    public static AppSyncTransportException permissionDenied() {
        return new AppSyncTransportException(401, UNAUTHORIZED_TYPE, "Permission denied");
    }

    public static AppSyncTransportException incompleteSignature(String message) {
        return new AppSyncTransportException(403, "IncompleteSignatureException", message);
    }

    public static AppSyncTransportException badRequest(String message) {
        return new AppSyncTransportException(400, "BadRequestException", message);
    }

    public static AppSyncTransportException missingAuthorizationHeader() {
        return new AppSyncTransportException(401, UNAUTHORIZED_TYPE, MISSING_AUTHORIZATION_HEADER);
    }
}
