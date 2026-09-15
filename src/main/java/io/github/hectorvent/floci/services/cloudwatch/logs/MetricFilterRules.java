package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.filter.FilterPatternException;
import io.github.hectorvent.floci.services.cloudwatch.logs.filter.SystemFieldSelection;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * The rules PutMetricFilter applies to the three options that describe a filter rather than its
 * pattern: {@code emitSystemFieldDimensions}, {@code fieldSelectionCriteria} and the dimensions
 * they share a limit with, plus what those options do at ingestion. They live beside
 * {@link CloudWatchLogsMetricFilterService} so the service keeps to storing filters and publishing
 * their metrics.
 *
 * <p>{@code applyOnTransformedLogs} has no rule here on purpose. It selects the transformed view of
 * a log group that has a log transformer, Floci has no transformers, and the Logs API documents no
 * error for setting it without one, so it is stored and returned unchanged.
 */
final class MetricFilterRules {

    private static final Logger LOG = Logger.getLogger(MetricFilterRules.class);

    private MetricFilterRules() {
    }

    /** AWS's cap on dimensions per metric filter. */
    public static final int MAX_DIMENSIONS = 3;

    private static final int MAX_FIELD_SELECTION_CRITERIA_LENGTH = 2000;

    private static final String ACCOUNT_FIELD = "@aws.account";
    private static final String REGION_FIELD = "@aws.region";
    private static final Set<String> SYSTEM_FIELDS = Set.of(ACCOUNT_FIELD, REGION_FIELD);

    static void validateSystemFields(List<String> systemFields, MetricTransformation t) {
        if (systemFields == null) {
            return;
        }
        for (String field : systemFields) {
            if (!SYSTEM_FIELDS.contains(field)) {
                throw invalid("emitSystemFieldDimensions must contain only @aws.account and @aws.region, got '"
                        + field + "'.");
            }
        }
        int dimensions = t.getDimensions() == null ? 0 : t.getDimensions().size();
        if (dimensions + systemFields.size() > MAX_DIMENSIONS) {
            throw invalid("System field dimensions count toward the limit of " + MAX_DIMENSIONS
                    + " dimensions per metric filter.");
        }
    }

/**
     * A criterion selects which events the filter processes from the batch's system fields, so it
     * has to parse before it is stored; AWS caps it at 2000 characters.
     */
    static void validateFieldSelectionCriteria(String criteria) {
        if (criteria == null || criteria.isBlank()) {
            return;
        }
        if (criteria.length() > MAX_FIELD_SELECTION_CRITERIA_LENGTH) {
            throw invalid("fieldSelectionCriteria must be at most " + MAX_FIELD_SELECTION_CRITERIA_LENGTH
                    + " characters.");
        }
        try {
            SystemFieldSelection.parse(criteria);
        } catch (FilterPatternException e) {
            throw invalid("fieldSelectionCriteria is not valid: " + e.getMessage());
        }
    }

/**
     * Whether a batch is one this filter processes. A filter with no selection criteria processes
     * every batch, and so does one whose stored criteria no longer parse: dropping its metric
     * silently would be worse than publishing it.
     */
    static boolean selects(MetricFilter filter, String account, String region) {
        String criteria = filter.getFieldSelectionCriteria();
        if (criteria == null || criteria.isBlank()) {
            return true;
        }
        try {
            return SystemFieldSelection.parse(criteria).test(account, region);
        } catch (FilterPatternException unparsable) {
            LOG.warnv("Metric filter {0} has a fieldSelectionCriteria that no longer parses: {1}",
                    filter.getFilterName(), unparsable.getMessage());
            return true;
        }
    }

/**
     * Appends the system fields the filter emits as dimensions to the ones its transformation
     * names. AWS counts both against the same limit of three, which {@code validateSystemFields}
     * enforces when the filter is stored.
     */
    static List<Dimension> withSystemDimensions(MetricFilter filter, String account, String region,
                                                        List<Dimension> dimensions) {
        List<String> fields = filter.getEmitSystemFieldDimensions();
        if (fields == null) {
            return dimensions;
        }
        for (String field : fields) {
            if (ACCOUNT_FIELD.equals(field)) {
                dimensions.add(new Dimension(field, account));
            } else if (REGION_FIELD.equals(field)) {
                dimensions.add(new Dimension(field, region));
            }
        }
        return dimensions;
    }

    static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
