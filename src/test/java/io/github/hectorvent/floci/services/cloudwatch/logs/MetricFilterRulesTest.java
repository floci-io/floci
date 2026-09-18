package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricFilterRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"1e309", "-1e309", "NaN", "Infinity", " 1", "1x"})
    void numbersThatCannotBePublishedAreNotValues(String text) {
        assertNull(MetricFilterRules.number(text));
    }

    @Test
    void finiteNumbersRetainExistingGrammar() {
        assertEquals(1000.0, MetricFilterRules.number("1e3"));
        assertEquals(-0.5, MetricFilterRules.number("-.5"));
        assertEquals(Double.MAX_VALUE, MetricFilterRules.number("1.7976931348623157e308"));
        assertEquals(0.0, MetricFilterRules.number("1e-999"));
    }

    @Test
    void corruptedStoredCriteriaSkipRatherThanPublishAcrossAccounts() {
        MetricFilter filter = new MetricFilter();
        filter.setFilterName("synthetic");
        filter.setLogGroupName("/synthetic");
        filter.setFieldSelectionCriteria("@aws.account === \"111111111111\"");
        assertFalse(MetricFilterRules.selects(filter, "222222222222", "eu-west-1"));
        filter.setFieldSelectionCriteria(null);
        assertTrue(MetricFilterRules.selects(filter, "222222222222", "eu-west-1"));
    }

    @Test
    void nullSystemDimensionsAreDomainErrorsRatherThanNullPointerExceptions() {
        AwsException error = assertThrows(AwsException.class, () -> MetricFilterRules.validateSystemFields(
                Arrays.asList("@aws.account", null), new MetricTransformation()));
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        MetricFilterRules.validateSystemFields(List.of("@aws.account", "@aws.region"), new MetricTransformation());
    }
}
