package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;

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
        return paginate(all, cursorOf, maxResults, nextToken, maxPage, maxPage, errorCode);
    }

    public static <T> PaginatedResult<T> paginate(List<T> all, Function<T, String> cursorOf,
                                                    Integer maxResults, String nextToken,
                                                    int defaultPageSize, int maxResultsLimit,
                                                    String errorCode) {
        return paginate(all, cursorOf, maxResults, nextToken, defaultPageSize, maxResultsLimit, errorCode,
                "nextToken");
    }

    /**
     * As above, naming the token parameter in the invalid-token message: query-protocol and DMS
     * operations call it {@code Marker}, and an error naming {@code nextToken} points the caller
     * at a parameter the operation does not have.
     */
    public static <T> PaginatedResult<T> paginate(List<T> all, Function<T, String> cursorOf,
                                                    Integer maxResults, String nextToken,
                                                    int defaultPageSize, int maxResultsLimit,
                                                    String errorCode, String tokenName) {
        if (maxResults != null && (maxResults < 1 || maxResults > maxResultsLimit)) {
            throw new AwsException(errorCode,
                    "maxResults must be between 1 and " + maxResultsLimit, 400);
        }
        int limit = maxResults != null ? maxResults : defaultPageSize;
        return slice(all, cursorOf, limit,
                decodeCursor(nextToken, "", token -> new AwsException(errorCode, "Invalid " + tokenName + ".", 400)),
                "", Pagination::encodeToken);
    }

    /**
     * For callers that validate the page size and render token errors themselves, because the
     * wording differs per operation. Every cursor is prefixed with {@code namespace}, so a token
     * minted for another namespace is refused instead of being read as a position in this list,
     * while two operations listing the same resources can share one namespace and accept each
     * other's tokens.
     */
    public static <T> PaginatedResult<T> paginate(List<T> all, Function<T, String> cursorOf,
                                                  int limit, String nextToken, String namespace,
                                                  Function<String, AwsException> invalidToken) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
        String prefix = namespace + ":";
        return slice(all, cursorOf, limit, decodeCursor(nextToken, prefix, invalidToken),
                prefix, Pagination::encodeToken);
    }

    /** Paginates with instance-signed markers, optionally scoped by a prefix within the signed cursor. */
    public static <T> PaginatedResult<T> paginate(List<T> all, Function<T, String> cursorOf,
                                                  int limit, String marker, SignedMarkers markers,
                                                  String scope, Function<String, AwsException> invalidMarker) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
        String after = null;
        if (marker != null) {
            String cursor = markers.cursorOf(marker);
            if (cursor == null || !cursor.startsWith(scope)) {
                throw invalidMarker.apply(marker);
            }
            after = cursor.substring(scope.length());
        }
        return slice(all, cursorOf, limit, after, scope, markers::issue);
    }

    /** Validates service-specific bounds before applying the existing namespaced cursor format. */
    public static <T> PaginatedResult<T> paginate(List<T> all, Function<T, String> cursorOf,
                                                  Integer maxResults, String nextToken,
                                                  int defaultPageSize, int maxResultsLimit, int maxTokenLength,
                                                  String errorCode, String namespace) {
        if (maxResults != null && (maxResults < 1 || maxResults > maxResultsLimit)) {
            throw new AwsException(errorCode, "Page size must be between 1 and " + maxResultsLimit + ".", 400);
        }
        if (nextToken != null && nextToken.length() > maxTokenLength) {
            throw new AwsException(errorCode, "Pagination token must not exceed " + maxTokenLength + " characters.", 400);
        }
        return paginate(all, cursorOf, maxResults == null ? defaultPageSize : maxResults, nextToken, namespace,
                token -> new AwsException(errorCode, "Invalid pagination token.", 400));
    }

    /** Reads an optional JSON page size without coercing strings, fractions, or overflowing integers. */
    public static Integer parseMaxResults(JsonNode request, String field, String errorCode) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new AwsException(errorCode, field + " must be an integer.", 400);
        }
        return value.intValue();
    }

    /** Reads an optional JSON cursor; bounds and scope are checked by the namespaced pager. */
    public static String parseNextToken(JsonNode request, String field, String errorCode) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new AwsException(errorCode, field + " must be a string.", 400);
        }
        return value.textValue();
    }

    private static <T> PaginatedResult<T> slice(List<T> all, Function<T, String> cursorOf, int limit,
                                                String after, String tokenPrefix,
                                                Function<String, String> issueToken) {
        List<T> sorted = all.stream().sorted(Comparator.comparing(cursorOf)).collect(Collectors.toList());
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
        if (limit < sorted.size() - start && !page.isEmpty()) {
            token = issueToken.apply(tokenPrefix + cursorOf.apply(page.get(page.size() - 1)));
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

    /**
     * True when {@code token} decodes to a cursor that starts with {@code prefix}, for callers that
     * need to tell a token they issued under another scope from one that is not theirs at all.
     */
    public static boolean tokenStartsWith(String token, String prefix) {
        String decoded = decodeToken(token);
        return decoded != null && decoded.startsWith(prefix);
    }

    private static String encodeToken(String cursor) {
        return Base64Url.encode(cursor);
    }

    private static String decodeCursor(String token, String prefix,
                                       Function<String, AwsException> invalidToken) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String decoded = decodeToken(token);
        if (decoded == null || !decoded.startsWith(prefix)) {
            throw invalidToken.apply(token);
        }
        return decoded.substring(prefix.length());
    }

    private static String decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            return Base64Url.decode(token);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
