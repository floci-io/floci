package io.github.hectorvent.floci.services.appsync.graphql;

import io.github.hectorvent.floci.services.appsync.graphql.util.VtlErrorSignal;

import java.util.List;
import java.util.Map;

public record AppSyncVtlResult(
        Object output,
        VtlErrorSignal error,
        List<Map<String, Object>> appendedErrors
) {
    public boolean hasError() {
        return error != null;
    }
}
