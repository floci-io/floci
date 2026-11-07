package io.floci.aws.services.appsync.graphql;

import io.floci.aws.services.appsync.graphql.util.VtlErrorSignal;

import java.util.List;
import java.util.Map;

public record AppSyncVtlResult(
        Object output,
        VtlErrorSignal error,
        List<Map<String, Object>> appendedErrors,
        boolean returned
) {
    public boolean hasError() {
        return error != null;
    }
}
