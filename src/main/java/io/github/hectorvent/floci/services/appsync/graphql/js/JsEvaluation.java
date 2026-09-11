package io.github.hectorvent.floci.services.appsync.graphql.js;

import java.util.List;
import java.util.Map;

/**
 * One resolver-handler call's outcome.
 *
 * @param result       what the handler returned: a data source request for {@code request()}, the
 *                     field's value for {@code response()}
 * @param stash        {@code ctx.stash} as the handler left it, carried to the next pipeline stage
 * @param earlyReturn  the handler called {@code runtime.earlyReturn()}, so the pipeline stops here
 *                     and {@code result} is the field's value
 * @param appendedErrors errors collected by {@code util.appendError}, which do not stop execution
 *                     but are reported alongside the data
 * @param error        the handler threw; null on success. A {@code util.error()} call arrives with
 *                     the type and data the resolver chose
 * @param missingHandler the module exports no such function, which the caller reads as "pass the
 *                     value through" rather than as a failure
 */
public record JsEvaluation(Object result,
                           Map<String, Object> stash,
                           boolean earlyReturn,
                           List<JsError> appendedErrors,
                           JsError error,
                           boolean missingHandler) {

    public boolean failed() {
        return error != null;
    }

    /** An error raised by resolver code, shaped like the GraphQL error AppSync reports for it. */
    public record JsError(String message, String type, Object data, Object errorInfo) {
        public static JsError of(String message, String type) {
            return new JsError(message, type, null, null);
        }
    }
}
