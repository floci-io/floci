package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CloudWatchLogsMetricFilterReferenceTest {

    private static final String REGION = "eu-west-1";
    private static final String GROUP = "/synthetic/reference-validation";
    private static final String SCALAR_PATTERN = "{ $.probe = \"yes\" }";
    private static final String WILDCARD_PATTERN = "{ $.values[*] = * }";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchLogsMetricFilterService service;
    private CloudWatchMetricsService metrics;

    @BeforeEach
    void setUp() {
        RegionResolver resolver = new RegionResolver(REGION, "000000000000");
        CloudWatchLogsService logs = new CloudWatchLogsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), 10_000, resolver);
        logs.createLogGroup(GROUP, null, null, REGION);
        metrics = mock(CloudWatchMetricsService.class);
        service = new CloudWatchLogsMetricFilterService(logs, metrics, resolver);
    }

    static Stream<Arguments> verifiedLiveAwsCases() throws Exception {
        try (InputStream input = CloudWatchLogsMetricFilterReferenceTest.class.getResourceAsStream(
                "/cloudwatchlogs/metric-filter-reference-validation-aws.json")) {
            JsonNode fixture = MAPPER.readTree(input);
            assertEquals("Verified on Live AWS", fixture.path("provenance").path("verification").textValue());
            List<Arguments> cases = new ArrayList<>();
            for (JsonNode example : fixture.path("cases")) {
                cases.add(Arguments.of(example.path("id").textValue(), example));
            }
            return cases.stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("verifiedLiveAwsCases")
    void putMatchesVerifiedLiveAwsReferenceValidation(String id, JsonNode example) {
        MetricFilter definition = definition(example.path("filterPattern").textValue(),
                example.path("metricValue").textValue());
        MetricTransformation transformation = definition.getMetricTransformations().getFirst();
        if (example.has("defaultValue")) {
            transformation.setDefaultValue(example.path("defaultValue").doubleValue());
        }
        if (example.has("dimensions")) {
            transformation.setDimensions(MAPPER.convertValue(example.path("dimensions"), new TypeReference<>() {}));
        }
        JsonNode expected = example.path("expected");
        if (expected.path("accepted").booleanValue()) {
            service.putMetricFilter(definition, REGION);
            MetricFilter stored = service.findMetricFilter(GROUP, "reference", REGION).orElseThrow();
            assertEquals(definition.getFilterPattern(), stored.getFilterPattern(), id);
            MetricTransformation saved = stored.getMetricTransformations().getFirst();
            assertEquals(transformation.getMetricValue(), saved.getMetricValue(), id);
            assertEquals(transformation.getDefaultValue(), saved.getDefaultValue(), id);
            assertEquals(transformation.getDimensions(), saved.getDimensions(), id);
        } else {
            AwsException error = assertThrows(AwsException.class, () -> service.putMetricFilter(definition, REGION));
            assertEquals(expected.path("errorCode").textValue(), error.getErrorCode(), id);
            assertEquals(expected.path("message").textValue(), error.getMessage(), id);
            assertEquals(400, error.getHttpStatus());
            assertTrue(service.findMetricFilter(GROUP, "reference", REGION).isEmpty());
        }
        verifyNoInteractions(metrics);
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.values[*]", "$.values.*", "$.values[*].amount"})
    void wildcardMetricReferencesCannotReplaceThePriorDefinition(String reference) {
        service.putMetricFilter(definition(SCALAR_PATTERN, "$.value"), REGION);

        AwsException error = assertThrows(AwsException.class,
                () -> service.putMetricFilter(definition(WILDCARD_PATTERN, reference), REGION));

        assertEquals("InvalidParameterException", error.getErrorCode());
        MetricFilter retained = service.findMetricFilter(GROUP, "reference", REGION).orElseThrow();
        assertEquals(SCALAR_PATTERN, retained.getFilterPattern());
        assertEquals("$.value", retained.getMetricTransformations().getFirst().getMetricValue());
        verifyNoInteractions(metrics);
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.['*']", "$.['foo*bar']", "$.['values[*]']", "$.values[0]"})
    void singlePropertyReferencesRemainValidIncludingLiteralAsterisks(String reference) {
        MetricFilter stored = service.putMetricFilter(definition(SCALAR_PATTERN, reference), REGION);
        assertEquals(reference, stored.getMetricTransformations().getFirst().getMetricValue());
    }

    @Test
    void wildcardFilterPatternsRemainValidWithANumericMetricValue() {
        MetricFilter stored = service.putMetricFilter(definition(WILDCARD_PATTERN, "1"), REGION);
        assertEquals(WILDCARD_PATTERN, stored.getFilterPattern());
    }

    @Test
    void unmeasuredDimensionWildcardValidationRemainsUnchanged() {
        MetricFilter definition = definition(SCALAR_PATTERN, "1");
        definition.getMetricTransformations().getFirst().setDimensions(Map.of("A", "$.values[*]"));
        MetricFilter stored = service.putMetricFilter(definition, REGION);
        assertEquals(Map.of("A", "$.values[*]"), stored.getMetricTransformations().getFirst().getDimensions());
    }

    private static MetricFilter definition(String pattern, String value) {
        MetricTransformation transformation = new MetricTransformation();
        transformation.setMetricName("Value");
        transformation.setMetricNamespace("Synthetic");
        transformation.setMetricValue(value);
        MetricFilter filter = new MetricFilter();
        filter.setFilterName("reference");
        filter.setLogGroupName(GROUP);
        filter.setFilterPattern(pattern);
        filter.setMetricTransformations(List.of(transformation));
        return filter;
    }
}
