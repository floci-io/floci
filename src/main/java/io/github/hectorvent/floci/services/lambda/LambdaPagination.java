package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.SignedMarkers;

import java.util.List;
import java.util.function.Function;

final class LambdaPagination {

    private LambdaPagination() {}

    static Integer parseMaxItems(String raw, int maxAllowed, List<String> errors) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        int requested;
        try {
            requested = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new AwsException("SerializationException", "'" + raw + "' can not be converted to Integer", 400);
        }
        if (requested < 1 || requested > maxAllowed) {
            String bound = requested < 1 ? "greater than or equal to 1"
                    : "less than or equal to " + maxAllowed;
            errors.add("Value '" + raw + "' at 'maxItems' failed to satisfy constraint: "
                    + "Member must have value " + bound);
        }
        return requested;
    }

    static <T> PaginatedResult<T> paginate(List<T> items, Function<T, String> cursorOf,
                                            Integer maxItems, int pageSize, String marker,
                                            SignedMarkers markers, String scope) {
        int limit = maxItems == null ? pageSize : Math.min(maxItems, pageSize);
        return Pagination.paginate(items, cursorOf, limit, marker, markers, scope,
                token -> new AwsException("InvalidParameterValueException", "Invalid pagination key.", 400));
    }
}
