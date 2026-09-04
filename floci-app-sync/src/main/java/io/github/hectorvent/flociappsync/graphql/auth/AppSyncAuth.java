package io.github.hectorvent.flociappsync.graphql.auth;

/** Ported verbatim (besides package, trimmed to what field-level auth needs) from Floci's
 * {@code services.appsync.graphql.auth.AppSyncAuth}. */
public final class AppSyncAuth {

    public static final String FIELD_UNAUTHORIZED_TYPE = "Unauthorized";

    public static String fieldUnauthorizedMessage(String fieldName, String typeName) {
        return "Not Authorized to access " + fieldName + " on type " + typeName;
    }

    private AppSyncAuth() {
    }
}
