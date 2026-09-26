package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.ses.model.EmailTemplate;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Paging rules of the SES list operations. Probe-confirmed against real SES (2026-09-25): the
 * page-size bound, both error messages and the treatment of an empty token differ per operation,
 * while the token itself belongs to the kind of resource listed, so the v1 and v2 lists of the
 * same resources accept each other's tokens and refuse any other list's. Without a page size the
 * template lists serve 10 (probed 2026-09-26); the export-job default is not documented or
 * measured and is taken to be the bound.
 */
enum SesListPaging {

    V2_LIST_EMAIL_TEMPLATES(Namespace.TEMPLATE, 10, 100,
            size -> badRequest("The page size must be between 1 and 100"),
            token -> badRequest("Invalid PageToken <" + token + ">."),
            false),

    /** v1 serves a page of the bound for an out-of-range {@code MaxItems} instead of refusing it. */
    V1_LIST_TEMPLATES(Namespace.TEMPLATE, 10, 100,
            null,
            token -> invalidParameterValue("Invalid PageToken <" + token + ">."),
            false),

    V2_LIST_EXPORT_JOBS(Namespace.EXPORT_JOB, 100, 100,
            size -> badRequest("PageSize must be between 1 and 100"),
            token -> badRequest("Failed to deserialize token. "),
            true);

    private static final class Namespace {
        static final String TEMPLATE = "template";
        static final String EXPORT_JOB = "export-job";
    }

    private final String namespace;
    private final int defaultPageSize;
    private final int maxPageSize;
    private final Function<Integer, AwsException> outOfRange;
    private final Function<String, AwsException> invalidToken;
    private final boolean emptyTokenInvalid;

    SesListPaging(String namespace, int defaultPageSize, int maxPageSize,
                  Function<Integer, AwsException> outOfRange, Function<String, AwsException> invalidToken,
                  boolean emptyTokenInvalid) {
        this.namespace = namespace;
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
        this.outOfRange = outOfRange;
        this.invalidToken = invalidToken;
        this.emptyTokenInvalid = emptyTokenInvalid;
    }

    <T> PaginatedResult<T> page(List<T> all, Function<T, String> cursorOf, Integer pageSize,
                                String nextToken) {
        int limit = pageSize(pageSize);
        if (nextToken != null && nextToken.isEmpty() && emptyTokenInvalid) {
            throw invalidToken.apply(nextToken);
        }
        return Pagination.paginate(all, cursorOf, limit, nextToken, namespace, invalidToken);
    }

    int pageSize(Integer requested) {
        if (requested == null) {
            return defaultPageSize;
        }
        if (requested < 1 || requested > maxPageSize) {
            if (outOfRange == null) {
                return maxPageSize;
            }
            throw outOfRange.apply(requested);
        }
        return requested;
    }

    /** Newest first, as SES lists templates and export jobs; the id orders equal timestamps. */
    static String newestFirst(Instant created, String id) {
        long descending = Long.MAX_VALUE - (created == null ? 0L : created.toEpochMilli());
        return descending + "#" + id;
    }

    static String templateCursor(EmailTemplate template) {
        return newestFirst(template.getCreatedTimestamp(), template.getTemplateName());
    }

    /** A REST JSON query-string page size; a value that is not an int is a serialization error. */
    static Integer parseQueryPageSize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new AwsException("SerializationException",
                    "'" + raw + "' can not be converted to Integer", 400);
        }
    }

    /**
     * A Query-protocol page size. Unlike the REST JSON query string, a present but empty value is
     * refused, and a value that is not an int is refused without a message.
     */
    static Integer parseQueryProtocolPageSize(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isEmpty()) {
            throw new AwsException("MalformedInput", "missing value for decimal type", 400);
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            throw new AwsException("MalformedInput", null, 400);
        }
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    private static AwsException invalidParameterValue(String message) {
        return new AwsException("InvalidParameterValue", message, 400);
    }
}
