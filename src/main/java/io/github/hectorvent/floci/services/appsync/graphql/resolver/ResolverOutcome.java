package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import java.util.ArrayList;
import java.util.List;

/**
 * What a resolver produced for one field.
 *
 * <p>{@code error} set means the field failed, which is {@code util.error()} or an unsuppressed
 * data source failure. {@code appendedErrors} is separate because {@code util.appendError} means
 * the opposite: report these <em>and</em> keep the data. The sidecar's callback response can only
 * say one or the other per field, so appended errors travel back to the caller instead and are
 * merged into the response envelope by {@link ResolverCallbackResource}.
 */
public record ResolverOutcome(Object data, AppSyncResolverError error,
                              List<AppSyncResolverError> appendedErrors) {

    public ResolverOutcome {
        appendedErrors = appendedErrors == null ? List.of() : List.copyOf(appendedErrors);
    }

    public boolean failed() {
        return error != null;
    }

    /** Every error on the field, appended ones first and the failing one last, the order AppSync reports. */
    public List<AppSyncResolverError> errors() {
        if (error == null) {
            return appendedErrors;
        }
        List<AppSyncResolverError> all = new ArrayList<>(appendedErrors);
        all.add(error);
        return List.copyOf(all);
    }
}
