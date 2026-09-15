package io.github.hectorvent.floci.services.floci.ui;

/**
 * The resolved shape of the web console Floci is running as its sidecar: the port it listens on,
 * the extra name it wants Floci's endpoint under, and how to read its health response.
 *
 * <p>Every field has a contract-v1 default, so a console that implements
 * <a href="https://floci.io/floci/ui/console-contract/">the console contract</a> needs no
 * configuration at all. The values are resolved once per sidecar start by
 * {@link ConsoleProfileResolver}.
 *
 * @param internalPort            port the console listens on inside its container, handed to it as
 *                                {@code PORT} and published as {@code floci.services.ui.port}
 * @param endpointEnv             additional environment variable the Floci endpoint is repeated in,
 *                                for a console that reads neither {@code AWS_ENDPOINT_URL} nor
 *                                {@code FLOCI_ENDPOINT}; blank when the baseline names are enough
 * @param healthPath              path the readiness probe requests, always with a leading slash
 * @param healthReadyField        JSON field in the health response that reports readiness; blank
 *                                means any {@code 200} counts as ready
 * @param healthReadyValue        value of that field which means the console reached Floci
 * @param healthUnavailableValue  value of that field which means the console is up but cannot reach
 *                                Floci, so the failure is worth showing rather than polling through
 * @param displayName             what to call the console in logs and user-facing errors
 */
public record ConsoleProfile(int internalPort,
                             String endpointEnv,
                             String healthPath,
                             String healthReadyField,
                             String healthReadyValue,
                             String healthUnavailableValue,
                             String displayName) {

    /** Whether the health response carries a readiness field, rather than being a bare liveness check. */
    public boolean hasReadyField() {
        return healthReadyField != null && !healthReadyField.isBlank();
    }

    /** Whether the console wants Floci's endpoint under a name the AWS baseline does not already set. */
    public boolean hasEndpointAlias() {
        return endpointEnv != null && !endpointEnv.isBlank();
    }
}
