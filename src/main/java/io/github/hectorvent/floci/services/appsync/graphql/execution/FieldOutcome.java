package io.github.hectorvent.floci.services.appsync.graphql.execution;

import java.util.List;

/**
 * Result of resolving one field: either a terminal {@code error} (field data becomes null) or
 * {@code data}, in both cases accompanied by any errors appended with {@code $util.appendError}.
 */
public record FieldOutcome(Object data, FieldError error, List<FieldError> appended) {

    public static FieldOutcome ofError(FieldError error) {
        return new FieldOutcome(null, error, List.of());
    }

    public static FieldOutcome ofError(FieldError error, List<FieldError> appended) {
        return new FieldOutcome(null, error, appended);
    }

    public static FieldOutcome ofData(Object data, List<FieldError> appended) {
        return new FieldOutcome(data, null, appended);
    }

    public boolean isError() {
        return error != null;
    }
}
