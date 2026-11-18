package io.floci.aws.services.appsync.events;

import jakarta.enterprise.context.RequestScoped;

/**
 * The raw path an Events publish was sent to before {@link AppSyncEventsHostFilter} moved it onto
 * the internal publish route. SigV4 covers the path the caller signed, {@code /event} on the API's
 * own host, not the internal form, so the IAM check rebuilds the canonical request from this.
 */
@RequestScoped
public class AppSyncEventsRequestPath {

    private String signedPath;

    public String signedPath() {
        return signedPath;
    }

    public void recordSignedPath(String path) {
        this.signedPath = path;
    }
}
