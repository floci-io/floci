package io.github.hectorvent.floci.services.iot;

import io.github.hectorvent.floci.core.common.AwsException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Token-based pagination shared by the IoT REST endpoints.
 */
final class IotPaging {

    private IotPaging() {
    }

    record Page<T>(List<T> items, String nextToken) {
    }

    static <T> Page<T> paginate(List<T> items, String nextToken, Integer maxResults) {
        int start = decodeToken(nextToken);
        if (start < 0 || start > items.size()) {
            throw new AwsException("InvalidRequestException", "Invalid token.", 400);
        }
        if (maxResults != null && maxResults < 0) {
            throw new AwsException("InvalidRequestException", "maxResults must be non-negative.", 400);
        }
        int limit = (maxResults == null || maxResults <= 0)
                ? items.size() - start
                : Math.min(maxResults, items.size() - start);
        int end = Math.min(items.size(), start + limit);
        List<T> sliced = new ArrayList<>(items.subList(start, end));
        String next = (end < items.size()) ? encodeToken(end) : null;
        return new Page<>(sliced, next);
    }

    private static String encodeToken(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return 0;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            return Integer.parseInt(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }
}
