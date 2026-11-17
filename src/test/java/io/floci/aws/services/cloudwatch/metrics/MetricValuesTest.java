package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricValuesTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void querySamplesPreserveZeroWeightsAndDefaultCounts() {
        MetricDatum template = MetricDatum.of("Latency", "Milliseconds", 0, List.of(), 123);
        List<MetricDatum> weighted = MetricValues.fromQuery(Map.of(
                "Values.member.1", List.of("7"), "Values.member.2", List.of("11"),
                "Counts.member.1", List.of("0"), "Counts.member.2", List.of("2")), "", template);
        assertEquals(2, weighted.size());
        assertTrue(weighted.getFirst().hasSampleCount());
        assertEquals(0, weighted.getFirst().getSampleCount());
        assertEquals(7, weighted.getFirst().getMinimum());
        assertEquals(22, weighted.getLast().getSum());
        assertEquals(123, weighted.getLast().getTimestamp());
        List<MetricDatum> unweighted = MetricValues.fromQuery(
                Map.of("Values.member.1", List.of("7")), "", template);
        assertEquals(1, unweighted.getFirst().getSampleCount());
        assertEquals(7, unweighted.getFirst().getSum());
    }

    @Test
    void queryRejectsMissingIndicesBeforeReturningSamples() {
        AwsException error = assertThrows(AwsException.class, () -> MetricValues.fromQuery(
                Map.of("Values.member.2", List.of("7")), "", new MetricDatum()));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertTrue(error.getMessage().contains("Values has a missing member"));
    }

    @Test
    void largeCountsDoNotExpandTheStoredSamples() throws Exception {
        List<MetricDatum> samples = MetricValues.fromJson(
                MAPPER.readTree("{\"Values\":[2,5],\"Counts\":[1000000000,2]}"),
                MetricDatum.of("Latency", "Milliseconds", 0, List.of(), 123));
        assertEquals(2, samples.size());
        assertEquals(1_000_000_000, samples.getFirst().getSampleCount());
        assertEquals(2_000_000_000, samples.getFirst().getSum());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "{\"Values\":[]}|InvalidParameterValue|Values",
            "{\"Values\":[1],\"Counts\":[]}|InvalidParameterValue|Counts",
            "{\"Values\":[1],\"Counts\":[-1]}|InvalidParameterValue|Counts",
            "{\"Values\":[1],\"Counts\":[1,2]}|InvalidParameterValue|Counts",
            "{\"Values\":[\"1\"]}|InvalidParameterValue|Values",
            "{\"Values\":[1e200]}|InvalidParameterValue|Values",
            "{\"Counts\":[1]}|InvalidParameterCombination|Counts requires Values",
            "{\"Values\":[1],\"Value\":1}|InvalidParameterCombination|Values cannot be combined"
    }, delimiter = '|')
    void invalidSamplesAreRejected(String json, String code, String field) {
        AwsException error = assertThrows(AwsException.class,
                () -> MetricValues.fromJson(MAPPER.readTree(json), new MetricDatum()));
        assertEquals(code, error.getErrorCode());
        assertTrue(error.getMessage().contains(field));
    }
}
