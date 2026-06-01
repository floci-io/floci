package io.github.hectorvent.floci.services.resourceexplorer2.query;

import io.github.hectorvent.floci.core.resource.ExplorerResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evaluates whether a resource matches a parsed query.
 *
 * <p>Filters are AND'd. Values within a filter are OR'd.
 * Negation inverts the result. Keywords are used for relevance
 * ranking but negated keywords exclude resources.
 */
public final class ResourceFilter {

    private ResourceFilter() {}

    public static boolean matches(ExplorerResource resource, ParsedQuery query) {
        for (var filter : query.filters()) {
            boolean anyValueMatches = filter.values().stream()
                    .anyMatch(v -> matchesValue(resource, filter.attribute(), v));
            if (filter.negated()) {
                anyValueMatches = !anyValueMatches;
            }
            if (!anyValueMatches) {
                return false;
            }
        }

        for (var keyword : query.keywords()) {
            if (keyword.negated()) {
                if (matchesKeyword(resource, keyword.value())) {
                    return false;
                }
            } else {
                if (!matchesKeyword(resource, keyword.value())) {
                    return false;
                }
            }
        }

        return true;
    }

    public static ParsedQuery combine(ParsedQuery viewFilter, ParsedQuery requestFilter) {
        List<ParsedQuery.Keyword> keywords = new ArrayList<>(viewFilter.keywords());
        keywords.addAll(requestFilter.keywords());
        List<ParsedQuery.Filter> filters = new ArrayList<>(viewFilter.filters());
        filters.addAll(requestFilter.filters());
        return new ParsedQuery(keywords, filters);
    }

    private static boolean matchesValue(ExplorerResource resource, FilterAttribute attr,
                                         ParsedQuery.FilterValue filterValue) {
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
            // Every resource type floci exposes is taggable, so this is a pass-through today.
            case RESOURCE_TYPE_SUPPORTS -> "tags".equalsIgnoreCase(filterValue.value());
        };
    }

    private static boolean compareString(String actual, ParsedQuery.FilterValue filter) {
        if (actual == null) return false;
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
        if (eq < 0) return false;
        String tagKey = val.substring(0, eq);
        String tagValue = val.substring(eq + 1);
        Map<String, String> tags = resource.tags();
        if (tags == null) return false;
        return tags.entrySet().stream().anyMatch(e ->
                e.getKey().equalsIgnoreCase(tagKey) && e.getValue().equalsIgnoreCase(tagValue));
    }

    private static boolean matchesTagKey(ExplorerResource resource, ParsedQuery.FilterValue filterValue) {
        Map<String, String> tags = resource.tags();
        if (tags == null) return false;
        return tags.keySet().stream().anyMatch(k ->
                compareTagString(k, filterValue));
    }

    private static boolean matchesTagValue(ExplorerResource resource, ParsedQuery.FilterValue filterValue) {
        Map<String, String> tags = resource.tags();
        if (tags == null) return false;
        return tags.values().stream().anyMatch(v ->
                compareTagString(v, filterValue));
    }

    private static boolean compareTagString(String actual, ParsedQuery.FilterValue filter) {
        String actualLower = actual.toLowerCase(Locale.ROOT);
        String filterLower = filter.value().toLowerCase(Locale.ROOT);
        if (filter.prefixMatch()) {
            return actualLower.startsWith(filterLower);
        }
        return actualLower.equals(filterLower);
    }

    private static boolean matchesApplication(ExplorerResource resource, ParsedQuery.FilterValue filterValue) {
        Map<String, String> tags = resource.tags();
        if (tags == null) return false;
        String appTag = tags.get("awsApplication");
        if (appTag == null) return false;
        return compareString(appTag, filterValue);
    }

    private static boolean matchesKeyword(ExplorerResource resource, String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        return (resource.arn() != null && resource.arn().toLowerCase(Locale.ROOT).contains(lower))
                || (resource.resourceType() != null && resource.resourceType().toLowerCase(Locale.ROOT).contains(lower))
                || (resource.service() != null && resource.service().toLowerCase(Locale.ROOT).contains(lower))
                || (resource.region() != null && resource.region().toLowerCase(Locale.ROOT).contains(lower));
    }
}
