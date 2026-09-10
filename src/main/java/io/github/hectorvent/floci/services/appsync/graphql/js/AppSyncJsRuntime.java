package io.github.hectorvent.floci.services.appsync.graphql.js;

import java.util.Map;

/**
 * Evaluates {@code APPSYNC_JS} resolver code.
 *
 * <p>An interface with one implementation today ({@link NodeAppSyncJsRuntime}, a Node sidecar) so
 * tests can drive resolver execution without Docker, and so a future in-process engine can replace
 * the sidecar without touching the pipeline above it. Anything crossing this boundary is plain
 * JSON-compatible data — maps, lists, strings, numbers, booleans and null — because the sidecar
 * implementation serialises it.
 */
public interface AppSyncJsRuntime {

    /**
     * Calls {@code handler} ({@code "request"} or {@code "response"}) in {@code code} with
     * {@code context} as {@code ctx}.
     *
     * <p>Never throws for a resolver's own failure: a {@code util.error()} call, a thrown
     * exception, or a missing handler all come back on the {@link JsEvaluation}, since the pipeline
     * has to decide what each means. Throws {@link io.github.hectorvent.floci.core.common.AwsException}
     * only when the runtime itself is unusable — no Docker, the sidecar will not start, a timeout.
     */
    JsEvaluation evaluate(String code, String handler, Map<String, Object> context);

    /** Whether resolver JavaScript can run at all, so a caller can fail with a useful message. */
    boolean isAvailable();
}
