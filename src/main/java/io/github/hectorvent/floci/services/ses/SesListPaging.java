package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import jakarta.ws.rs.core.UriInfo;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Paging rules of the SES list operations. Probe-confirmed against real SES (2026-09-25 to
 * 2026-09-27): the default page size, the bound, both error messages, what a v1 out-of-range
 * value is served and the treatment of an empty token differ per operation, while the token itself
 * belongs to the kind of resource listed, so the v1 and v2 lists of the same resources accept each
 * other's tokens and refuse any other list's. A token is also bound to the region it was issued in:
 * replayed in another region (probed 2026-10-01 on the identity and configuration-set lists), it
 * is refused with the operation's unreadable-token error. Defaults nobody could measure are noted
 * per constant.
 */
public enum SesListPaging {

    V2_LIST_EMAIL_TEMPLATES(Namespace.TEMPLATE, 10, 100,
            size -> badRequest("The page size must be between 1 and 100"),
            token -> badRequest("Invalid PageToken <" + token + ">."),
            false),

    V1_LIST_TEMPLATES(Namespace.TEMPLATE, 10, 100, 100,
            token -> invalidParameterValue("Invalid PageToken <" + token + ">."),
            false),

    V2_LIST_EMAIL_IDENTITIES(Namespace.IDENTITY, 25, 1000,
            SesListPaging::emailIdentitiesPageSizeError,
            token -> badRequest("Invalid NextToken <" + token + ">."),
            false),

    /**
     * ListEmailIdentities with a Filter (probed 2026-10-03): the same list, but a token SES refuses
     * is answered without echoing it. Only a token from another filter could be observed; a token
     * that is not one at all is taken to be refused the same way.
     */
    V2_LIST_EMAIL_IDENTITIES_FILTERED(Namespace.IDENTITY, 25, 1000,
            SesListPaging::emailIdentitiesPageSizeError,
            token -> badRequest("Invalid NextToken."),
            false),

    /** 195 identities came back whole without a MaxItems, so the default is taken to be the bound. */
    V1_LIST_IDENTITIES(Namespace.IDENTITY, 1000, 1000,
            size -> invalidParameterValue("Value " + size + " for parameter MaxItems is invalid. "
                    + "MaxItems must be between 1 and 1000."),
            token -> invalidParameterValue("Invalid NextToken <" + token + ">."),
            false),

    V2_LIST_CONFIGURATION_SETS(Namespace.CONFIGURATION_SET, 50, 1000,
            size -> badRequest("The page size must be between 1 and 1000"),
            token -> badRequest("invalid nextToken " + token),
            false),

    /** 1001 was served the default, so the bound is taken to be 1000; SES never refuses a value. */
    V1_LIST_CONFIGURATION_SETS(Namespace.CONFIGURATION_SET, 50, 1000, 50,
            token -> new AwsException("InvalidParameterValue", null, 400),
            false),

    V2_LIST_CUSTOM_VERIFICATION_EMAIL_TEMPLATES(Namespace.CUSTOM_VERIFICATION_EMAIL_TEMPLATE, 50, 50,
            size -> badRequest("The page size must be between 1 and 50"),
            token -> badRequest("Invalid nextToken <" + token + ">."),
            false),

    V1_LIST_CUSTOM_VERIFICATION_EMAIL_TEMPLATES(Namespace.CUSTOM_VERIFICATION_EMAIL_TEMPLATE, 50, 50, 50,
            token -> invalidParameterValue("Invalid nextToken <" + token + ">."),
            false),

    /** AWS documents no default; it is taken to be the bound. */
    V2_LIST_EXPORT_JOBS(Namespace.EXPORT_JOB, 100, 100,
            size -> badRequest("PageSize must be between 1 and 100"),
            token -> badRequest("Failed to deserialize token. "),
            true),

    /**
     * The three tenant lists answer Smithy's own validation messages, reporting a bad page size and
     * an empty token together, and bind a token to the request it came from (probed 2026-10-02).
     * Their default page size could not be measured (it needs more than 100 tenants) and is taken
     * to be the bound.
     */
    V2_LIST_TENANTS(Namespace.TENANT),

    V2_LIST_TENANT_RESOURCES(Namespace.TENANT_RESOURCE),

    V2_LIST_RESOURCE_TENANTS(Namespace.RESOURCE_TENANT),

    /**
     * ListImportJobs refuses sandbox accounts, so nothing about it could be probed; it is modelled
     * on the tenant lists, which share its Smithy-generated validation.
     */
    V2_LIST_IMPORT_JOBS(Namespace.IMPORT_JOB),

    /** SES holds one contact list per region, so no default could be measured; it is taken to be the bound. */
    V2_LIST_CONTACT_LISTS(Namespace.CONTACT_LIST, 1000, 1000,
            size -> badRequest("The page size must be between 1 and 1000"),
            token -> badRequest("Provided NextToken is invalid"),
            true),

    V2_LIST_CONTACTS(Namespace.CONTACT, 50, 1000,
            size -> badRequest("The page size must be between 1 and 1000"),
            token -> badRequest("Provided NextToken is invalid"),
            true),

    /**
     * ListEmailIdentityCertificates (probed 2026-10-04) answers Smithy's page-size messages; its
     * default could not be measured and is taken to be the bound.
     */
    V2_LIST_EMAIL_IDENTITY_CERTIFICATES(Namespace.IDENTITY_CERTIFICATE, 1000, 1000,
            size -> badRequest("1 validation error detected: Value '" + size + "' at 'pageSize' failed to "
                    + "satisfy constraint: Member must have value "
                    + (size < 1 ? "greater than or equal to 1" : "less than or equal to 1000")),
            token -> badRequest("Invalid NextToken."),
            false),

    /** 12 pools came back whole without a PageSize, so the default is taken to be the bound. */
    V2_LIST_DEDICATED_IP_POOLS(Namespace.DEDICATED_IP_POOL, 1000, 1000,
            size -> badRequest("The page size must be in [1, 1000] range."),
            token -> badRequest("Invalid next token."),
            false),

    /** Unmeasurable without leased IPs; modelled on the pool list, whose messages it shares. */
    V2_GET_DEDICATED_IPS(Namespace.DEDICATED_IP, 1000, 1000,
            size -> badRequest("The page size must be in [1, 1000] range."),
            token -> badRequest("Invalid next token."),
            false),

    /** 24 destinations came back whole without a PageSize, so the default is taken to be the bound. */
    V2_LIST_SUPPRESSED_DESTINATIONS(Namespace.SUPPRESSED_DESTINATION, 1000, 1000,
            size -> badRequest("Page size " + size + " is invalid, expected a number between 1 and 1000"),
            token -> new AwsException("InvalidNextTokenException", "Token is invalid.", 400),
            true),

    /**
     * The request has no size member; the model documents "up to 100 receipt rule sets at a time".
     * SES allows 40 rule sets per region, so a second page could not be observed.
     */
    V1_LIST_RECEIPT_RULE_SETS(Namespace.RECEIPT_RULE_SET, 100, 100, 100,
            token -> invalidParameterValue("Invalid page token: " + token),
            false);

    private static final class Namespace {
        static final String TEMPLATE = "template";
        static final String IDENTITY = "identity";
        static final String CONFIGURATION_SET = "configuration-set";
        static final String CUSTOM_VERIFICATION_EMAIL_TEMPLATE = "custom-verification-email-template";
        static final String EXPORT_JOB = "export-job";
        static final String TENANT = "tenant";
        static final String TENANT_RESOURCE = "tenant-resource";
        static final String RESOURCE_TENANT = "resource-tenant";
        static final String IMPORT_JOB = "import-job";
        static final String CONTACT_LIST = "contact-list";
        static final String CONTACT = "contact";
        static final String IDENTITY_CERTIFICATE = "identity-certificate";
        static final String DEDICATED_IP_POOL = "dedicated-ip-pool";
        static final String DEDICATED_IP = "dedicated-ip";
        static final String SUPPRESSED_DESTINATION = "suppressed-destination";
        static final String RECEIPT_RULE_SET = "receipt-rule-set";
    }

    /** What the tenant lists share, and ListImportJobs borrows: the bound and Smithy's messages. */
    private static final class TenantLists {
        static final int BOUND = 100;
        static final String EMPTY_TOKEN = "Value '' at 'nextToken' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1";

        static String pageSizeViolation(int pageSize) {
            String constraint = pageSize < 1
                    ? "greater than or equal to 1"
                    : "less than or equal to " + BOUND;
            return "Value '" + pageSize + "' at 'pageSize' failed to satisfy constraint: "
                    + "Member must have value " + constraint;
        }

        static AwsException pageSizeError(int pageSize) {
            return badRequest("1 validation error detected: " + pageSizeViolation(pageSize));
        }

        static AwsException tokenError(String token) {
            if (token.isEmpty()) {
                return badRequest("1 validation error detected: " + EMPTY_TOKEN);
            }
            return badRequest("Invalid Next Token");
        }

        /** SES lists the token before the page size. */
        static AwsException bothError(int pageSize) {
            return badRequest("2 validation errors detected: " + EMPTY_TOKEN + "; "
                    + pageSizeViolation(pageSize));
        }
    }

    private final String namespace;
    private final int defaultPageSize;
    private final int maxPageSize;
    private final Function<Integer, AwsException> outOfRange;
    private final int servedWhenOutOfRange;
    private final Function<String, AwsException> invalidToken;
    private final boolean emptyTokenInvalid;
    private final Function<Integer, AwsException> sizeAndEmptyTokenInvalid;

    SesListPaging(String namespace, int defaultPageSize, int maxPageSize,
                  Function<Integer, AwsException> outOfRange, Function<String, AwsException> invalidToken,
                  boolean emptyTokenInvalid) {
        this(namespace, defaultPageSize, maxPageSize, outOfRange, 0, invalidToken, emptyTokenInvalid, null);
    }

    SesListPaging(String namespace, int defaultPageSize, int maxPageSize, int servedWhenOutOfRange,
                  Function<String, AwsException> invalidToken, boolean emptyTokenInvalid) {
        this(namespace, defaultPageSize, maxPageSize, null, servedWhenOutOfRange, invalidToken,
                emptyTokenInvalid, null);
    }

    SesListPaging(String tenantStyleNamespace) {
        this(tenantStyleNamespace, TenantLists.BOUND, TenantLists.BOUND, TenantLists::pageSizeError, 0,
                TenantLists::tokenError, true, TenantLists::bothError);
    }

    SesListPaging(String namespace, int defaultPageSize, int maxPageSize,
                  Function<Integer, AwsException> outOfRange, int servedWhenOutOfRange,
                  Function<String, AwsException> invalidToken, boolean emptyTokenInvalid,
                  Function<Integer, AwsException> sizeAndEmptyTokenInvalid) {
        this.namespace = namespace;
        this.defaultPageSize = defaultPageSize;
        this.maxPageSize = maxPageSize;
        this.outOfRange = outOfRange;
        this.servedWhenOutOfRange = servedWhenOutOfRange;
        this.invalidToken = invalidToken;
        this.emptyTokenInvalid = emptyTokenInvalid;
        this.sizeAndEmptyTokenInvalid = sizeAndEmptyTokenInvalid;
    }

    <T> PaginatedResult<T> page(String region, List<T> all, Function<T, String> cursorOf, Integer pageSize,
                                String nextToken) {
        return page(region, "", all, cursorOf, pageSize, nextToken);
    }

    /**
     * {@code scope} names what else the request selected, a tenant or a filter, for the lists whose
     * tokens SES refuses once that changes. The token carries the scope's length, so a scope that
     * merely starts with another, which a name holding the token's own separator can do, is not
     * taken for it.
     */
    <T> PaginatedResult<T> page(String region, String scope, List<T> all, Function<T, String> cursorOf,
                                Integer pageSize, String nextToken) {
        int limit = checkRequest(pageSize, nextToken);
        String boundTo = scope.isEmpty() ? "" : "#" + scope.length() + "#" + scope;
        return Pagination.paginate(all, cursorOf, limit, nextToken, namespace + "@" + region + boundTo,
                invalidToken);
    }

    /**
     * What SES checks before anything else in the request: the page size and an empty token, which
     * the tenant-style lists report together. Returns the page size to serve. {@link #page} runs it
     * too, so a service calls it first only when its own checks must come after these.
     */
    int checkRequest(Integer pageSize, String nextToken) {
        boolean emptyToken = nextToken != null && nextToken.isEmpty() && emptyTokenInvalid;
        if (emptyToken && sizeAndEmptyTokenInvalid != null && pageSize != null
                && (pageSize < 1 || pageSize > maxPageSize)) {
            throw sizeAndEmptyTokenInvalid.apply(pageSize);
        }
        int limit = pageSize(pageSize);
        if (emptyToken) {
            throw invalidToken.apply(nextToken);
        }
        return limit;
    }

    int pageSize(Integer requested) {
        if (requested == null) {
            return defaultPageSize;
        }
        if (requested < 1 || requested > maxPageSize) {
            if (outOfRange == null) {
                return servedWhenOutOfRange;
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

    /**
     * Oldest first, as SES lists a resource's tenants; the id orders equal timestamps. The cursor
     * keeps the whole instant, so it orders exactly as a comparison of the instants does, and a
     * missing timestamp sorts last.
     */
    static String oldestFirst(Instant created, String id) {
        String time = created == null
                ? "~"
                : String.format(Locale.ROOT, "%019d.%09d", created.getEpochSecond(), created.getNano());
        return time + "#" + id;
    }

    /**
     * The NextToken of a REST JSON query string, for a list that refuses an empty token: a
     * {@code @QueryParam} binds an empty value as null, which would read as an absent token.
     */
    static String queryToken(UriInfo uriInfo) {
        return uriInfo.getQueryParameters().getFirst("NextToken");
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

    private static AwsException emailIdentitiesPageSizeError(int size) {
        return badRequest("Value " + size + " for parameter PageSize is invalid. "
                + "PageSize must be between 1 and 1000.");
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    private static AwsException invalidParameterValue(String message) {
        return new AwsException("InvalidParameterValue", message, 400);
    }
}
