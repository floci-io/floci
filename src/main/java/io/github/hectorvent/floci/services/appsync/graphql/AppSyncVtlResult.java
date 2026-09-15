package io.github.hectorvent.floci.services.appsync.graphql;

import io.github.hectorvent.floci.services.appsync.graphql.util.VtlErrorSignal;

import java.util.List;
import java.util.Map;

/**
 * Outcome of evaluating one mapping template. Normal rendering returns a String; {@code #return}
 * returns its raw value and sets {@code returned}, including when that value is itself a String.
 */
public record AppSyncVtlResult(
        Object output,
        boolean returned,
        VtlErrorSignal error,
        List<Map<String, Object>> appendedErrors
) {
    public boolean hasError() {
        return error != null;
    }
}
