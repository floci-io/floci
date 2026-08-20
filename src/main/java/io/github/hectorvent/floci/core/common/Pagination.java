package io.github.hectorvent.floci.core.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Opaque-cursor pagination shared by list endpoints that model AWS's {@code maxResults}/
 * {@code nextToken} pair. The cursor is the last returned item's sort key, base64
 * URL-encoded - not a raw offset, so a page stays resumable even if items are inserted or
 * removed elsewhere in the collection between requests.
 *
 * <p>{@code maxResults} is a boxed {@link Integer} deliberately: {@code null} means the
 * caller omitted it (falls back to {@code maxPage}), while {@code 0} is a real, out-of-range
 * value a caller explicitly sent and must be rejected the same as a value above {@code maxPage}
 * - AWS's own models declare {@code MaxResults} with a minimum of 1. Collapsing "omitted" and
 * "explicitly zero" onto the same primitive {@code int} sentinel is what let that distinction
 * get lost in every service that inlined this logic before this class existed.
 */
public final class Pagination {

    private Pagination() {}

    public static <T> PaginatedResult<T> paginate(List<T> all, Function<T, String> cursorOf,
                                                    Integer maxResults, String nextToken,
                                                    int maxPage, String errorCode) {
        if (maxResults != null && (maxResults < 1 || maxResults > maxPage)) {
            throw new AwsException(errorCode, "maxResults must be between 1 and " + maxPage, 400);
        }
        int limit = maxResults != null ? maxResults : maxPage;

        List<T> sorted = all.stream().sorted(Comparator.comparing(cursorOf)).collect(Collectors.toList());
        String after = decodeToken(nextToken, errorCode);
        int start = 0;
        if (after != null) {
            for (int i = 0; i < sorted.size(); i++) {
                if (cursorOf.apply(sorted.get(i)).compareTo(after) > 0) {
                    start = i;
                    break;
                }
                start = i + 1;
            }
        }
        List<T> page = sorted.stream().skip(start).limit(limit).collect(Collectors.toList());
        String token = null;
        if (start + limit < sorted.size() && !page.isEmpty()) {
            token = encodeToken(cursorOf.apply(page.get(page.size() - 1)));
        }
        return new PaginatedResult<>(page, token);
    }

    /**
     * Parses a raw {@code maxResults} query-string value. Bind the JAX-RS {@code @QueryParam}
     * as {@code String}, not {@code Integer}: RESTEasy Reactive's default handling of a
     * non-numeric value for an {@code Integer}-typed {@code @QueryParam} is a 404 (the request
     * never reaches the resource method at all), not the AWS-shaped 400 a malformed parameter
     * should produce.
     */
    public static Integer parseMaxResults(String value, String errorCode) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new AwsException(errorCode, "maxResults must be an integer.", 400);
        }
    }

    private static String encodeToken(String cursor) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String token, String errorCode) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new AwsException(errorCode, "Invalid nextToken.", 400);
        }
    }
}
