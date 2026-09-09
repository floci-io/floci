package io.github.hectorvent.floci.services.resourceexplorer2.query;

import io.github.hectorvent.floci.core.resource.ExplorerResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates whether a resource matches a parsed query.
 *
 * <p>Filters are AND'd and values within one filter are OR'd, as AWS documents. Free-form
 * keywords are OR'd with each other: {@code ec2 billing} selects a resource matching either.
 * A negated keyword or filter excludes.
 *
 * <p><strong>Deviation.</strong> AWS never excludes a resource for failing to match a free-form
 * keyword — it ranks it lower and still returns it, so a keyword-only query in a live account
 * returns everything. floci has no relevance model and returns matches only, which is the
 * behaviour that makes {@code Search} useful locally. Filter matching, which AWS documents as
 * deterministic, is exact.
 *
 * @see <a href="https://docs.aws.amazon.com/resource-explorer/latest/userguide/using-search-query-syntax.html">
 *     Search query syntax reference</a>
 */
public final class ResourceFilter {

    /** The one value AWS accepts for {@code resourcetype.supports:}. */
    private static final String SUPPORTS_TAGS = "tags";

    /** Tag key AWS writes on resources that belong to an application (myApplications). */
    private static final String APPLICATION_TAG = "awsApplication";

    private ResourceFilter() {}

    /**
     * @param taggableResourceTypes resource types whose provider advertises tag support, used by
     *     the {@code resourcetype.supports:tags} filter. Pass the set gathered from every
     *     {@link io.github.hectorvent.floci.core.resource.ResourceProvider}.
     */
    public static boolean matches(ExplorerResource resource, ParsedQuery query,
                                  Set<String> taggableResourceTypes) {
        for (var filter : query.filters()) {
            boolean anyValueMatches = filter.values().stream()
                    .anyMatch(v -> matchesValue(resource, filter.attribute(), v, taggableResourceTypes));
            if (filter.negated()) {
                anyValueMatches = !anyValueMatches;
            }
            if (!anyValueMatches) {
                return false;
            }
        }

        return matchesKeywords(resource, query.keywords());
    }

    /**
     * Positive keywords are OR'd, so a resource matching any one of them qualifies; a negated
     * keyword excludes regardless of the others. A query of negations alone matches everything
     * they do not exclude.
     */
    private static boolean matchesKeywords(ExplorerResource resource, List<ParsedQuery.Keyword> keywords) {
        boolean sawPositive = false;
        boolean anyPositiveMatched = false;
        for (var keyword : keywords) {
            boolean matched = matchesKeyword(resource, keyword.value());
            if (keyword.negated()) {
                if (matched) {
                    return false;
                }
                continue;
            }
            sawPositive = true;
            anyPositiveMatched = anyPositiveMatched || matched;
        }
        return !sawPositive || anyPositiveMatched;
    }

    public static ParsedQuery combine(ParsedQuery viewFilter, ParsedQuery requestFilter) {
        List<ParsedQuery.Keyword> keywords = new ArrayList<>(viewFilter.keywords());
        keywords.addAll(requestFilter.keywords());
        List<ParsedQuery.Filter> filters = new ArrayList<>(viewFilter.filters());
        filters.addAll(requestFilter.filters());
        return new ParsedQuery(keywords, filters);
    }

    /** Whether the query names a filter that AWS only honours on a view carrying tag data. */
    public static boolean usesTagData(ParsedQuery query) {
        return query.filters().stream().anyMatch(f -> switch (f.attribute()) {
            case TAG, TAG_KEY, TAG_VALUE, APPLICATION -> true;
            default -> false;
        });
    }

    private static boolean matchesValue(ExplorerResource resource, FilterAttribute attr,
                                        ParsedQuery.FilterValue filterValue,
                                        Set<String> taggableResourceTypes) {
        return switch (attr) {
            case REGION -> compareString(resource.region(), filterValue);
            case SERVICE -> compareString(resource.service(), filterValue);
            case RESOURCE_TYPE -> compareString(resource.resourceType(), filterValue);
            case ACCOUNT_ID -> compareString(resource.owningAccountId(), filterValue);
            case ID -> compareString(resource.arn(), filterValue);
            case APPLICATION -> matchesApplication(resource, filterValue);
            case TAG -> matchesTag(resource, filterValue);
            case TAG_KEY -> matchesTagKey(resource, filterValue);
            case TAG_VALUE -> matchesTagValue(resource, filterValue);
            case RESOURCE_TYPE_SUPPORTS -> SUPPORTS_TAGS.equalsIgnoreCase(filterValue.value())
                    && taggableResourceTypes.contains(resource.resourceType());
        };
    }

    private static boolean compareString(String actual, ParsedQuery.FilterValue filter) {
        if (actual == null) {
            return false;
        }
        String actualLower = actual.toLowerCase(Locale.ROOT);
        String filterLower = filter.value().toLowerCase(Locale.ROOT);
        if (filter.prefixMatch()) {
            return actualLower.startsWith(filterLower);
        }
        return actualLower.equals(filterLower);
    }

    private static boolean matchesTag(ExplorerResource resource, ParsedQuery.FilterValue filterValue) {
        String val = filterValue.value();
        if ("all".equalsIgnoreCase(val)) {
            return resource.tags() != null && !resource.tags().isEmpty();
        }
        if ("none".equalsIgnoreCase(val)) {
            return resource.tags() == null || resource.tags().isEmpty();
        }
        int eq = val.indexOf('=');
        if (eq < 0) {
            return false;
        }
        String tagKey = val.substring(0, eq);
        String tagValue = val.substring(eq + 1);
        Map<String, String> tags = resource.tags();
        if (tags == null) {
            return false;
        }
        return tags.entrySet().stream().anyMatch(e ->
                e.getKey().equalsIgnoreCase(tagKey) && e.getValue().equalsIgnoreCase(tagValue));
    }

    private static boolean matchesTagKey(ExplorerResource resource, ParsedQuery.FilterValue filterValue) {
        Map<String, String> tags = resource.tags();
        if (tags == null) {
            return false;
        }
        return tags.keySet().stream().anyMatch(k -> compareTagString(k, filterValue));
    }

    private static boolean matchesTagValue(ExplorerResource resource, ParsedQuery.FilterValue filterValue) {
        Map<String, String> tags = resource.tags();
        if (tags == null) {
            return false;
        }
        return tags.values().stream().anyMatch(v -> compareTagString(v, filterValue));
    }

    private static boolean compareTagString(String actual, ParsedQuery.FilterValue filter) {
        String actualLower = actual.toLowerCase(Locale.ROOT);
        String filterLower = filter.value().toLowerCase(Locale.ROOT);
        if (filter.prefixMatch()) {
            return actualLower.startsWith(filterLower);
        }
        return actualLower.equals(filterLower);
    }

    /**
     * AWS accepts either the application's resource-group ARN or its name, so both are compared
     * against the {@code awsApplication} tag — which itself always holds the ARN.
     */
    private static boolean matchesApplication(ExplorerResource resource, ParsedQuery.FilterValue filterValue) {
        Map<String, String> tags = resource.tags();
        if (tags == null) {
            return false;
        }
        String appTag = tags.get(APPLICATION_TAG);
        if (appTag == null) {
            return false;
        }
        return compareString(appTag, filterValue)
                || compareString(applicationNameOf(appTag), filterValue);
    }

    /** Extracts {@code MyApp} from {@code arn:aws:resource-groups:…:group/MyApp/0123abc}. */
    private static String applicationNameOf(String applicationArn) {
        int groupIndex = applicationArn.indexOf("group/");
        if (groupIndex < 0) {
            return null;
        }
        String remainder = applicationArn.substring(groupIndex + "group/".length());
        int separator = remainder.indexOf('/');
        return separator < 0 ? remainder : remainder.substring(0, separator);
    }

    /**
     * A free-form keyword matches any part of the resource's identity or its tags — AWS states it
     * can "match things like a tag key name or a piece of an ARN".
     */
    private static boolean matchesKeyword(ExplorerResource resource, String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        if (containsIgnoreCase(resource.arn(), lower)
                || containsIgnoreCase(resource.resourceType(), lower)
                || containsIgnoreCase(resource.service(), lower)
                || containsIgnoreCase(resource.region(), lower)) {
            return true;
        }
        Map<String, String> tags = resource.tags();
        if (tags == null) {
            return false;
        }
        return tags.entrySet().stream().anyMatch(e ->
                containsIgnoreCase(e.getKey(), lower) || containsIgnoreCase(e.getValue(), lower));
    }

    private static boolean containsIgnoreCase(String actual, String lowercaseNeedle) {
        return actual != null && actual.toLowerCase(Locale.ROOT).contains(lowercaseNeedle);
    }
}
