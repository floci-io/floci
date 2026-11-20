package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudWatchLogsMetricFilterWaiterTest {

    @Test
    void waitsForTheSecondBatchAfterTheFirstNonemptyAggregate() throws Exception {
        JsonNode expected = new ObjectMapper().readTree("{\"Sum\":10,\"SampleCount\":2}");
        AtomicInteger reads = new AtomicInteger();
        List<Datapoint> points = CloudWatchLogsMetricFilterTest.awaitSeries(
                () -> reads.incrementAndGet() == 1 ? series(3, 1) : series(10, 2), expected, Duration.ofSeconds(1));
        assertThat(reads.get()).isEqualTo(2);
        assertThat(points.get(0).sum()).isEqualTo(10);
        assertThat(points.get(0).sampleCount()).isEqualTo(2);
    }

    @Test
    void waitsForStatisticsEvenWhenTheSampleCountIsAlreadyVisible() throws Exception {
        JsonNode expected = new ObjectMapper().readTree("{\"Sum\":10,\"SampleCount\":2}");
        AtomicInteger reads = new AtomicInteger();
        List<Datapoint> points = CloudWatchLogsMetricFilterTest.awaitSeries(
                () -> reads.incrementAndGet() == 1 ? series(3, 2) : series(10, 2), expected, Duration.ofSeconds(1));
        assertThat(reads.get()).isEqualTo(2);
        assertThat(points.get(0).sum()).isEqualTo(10);
    }

    @Test
    void returnsTheIncompleteAggregateAtTheDeadlineForTheExistingAssertionsToReject() throws Exception {
        JsonNode expected = new ObjectMapper().readTree("{\"Sum\":10,\"SampleCount\":2}");
        AtomicInteger reads = new AtomicInteger();
        List<Datapoint> points = CloudWatchLogsMetricFilterTest.awaitSeries(() -> {
            reads.incrementAndGet();
            return series(3, 1);
        }, expected, Duration.ZERO);
        assertThat(reads.get()).isEqualTo(1);
        assertThatThrownBy(() -> assertThat(points.get(0).sum()).isEqualTo(10))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void absentSeriesIsReadOnceAfterTheCallersPositiveControl() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        List<Datapoint> points = CloudWatchLogsMetricFilterTest.awaitSeries(() -> {
            reads.incrementAndGet();
            return List.of();
        }, null, Duration.ofSeconds(1));
        assertThat(points).isEmpty();
        assertThat(reads.get()).isEqualTo(1);
    }

    private static List<Datapoint> series(double sum, double count) {
        return List.of(Datapoint.builder().sum(sum).sampleCount(count).build());
    }
}
