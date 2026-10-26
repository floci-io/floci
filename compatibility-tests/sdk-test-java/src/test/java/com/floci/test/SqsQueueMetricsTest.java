package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.DimensionFilter;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.MetricDataResult;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SQS - AWS/SQS CloudWatch metrics")
class SqsQueueMetricsTest {

    private static final String NAMESPACE = "AWS/SQS";
    private static final List<String> STANDARD_QUEUE_METRICS = List.of("ApproximateNumberOfMessagesVisible",
            "ApproximateNumberOfMessagesNotVisible", "ApproximateNumberOfMessagesDelayed",
            "ApproximateAgeOfOldestMessage", "NumberOfMessagesSent", "NumberOfMessagesReceived",
            "NumberOfMessagesDeleted", "NumberOfEmptyReceives", "SentMessageSize");

    @Test
    @DisplayName("A queue's depth and sends reach AWS/SQS without PutMetricData")
    void queueMetricsArePublishedUnderAwsSqs() throws InterruptedException {
        try (SqsClient sqs = TestFixtures.sqsClient(); CloudWatchClient cloudWatch = TestFixtures.cloudWatchClient()) {
            String queueName = TestFixtures.uniqueName("sdk-sqs-metrics");
            String queueUrl = sqs.createQueue(request -> request.queueName(queueName)).queueUrl();
            try {
                sqs.sendMessage(request -> request.queueUrl(queueUrl).messageBody("held"));
                Dimension queue = Dimension.builder().name("QueueName").value(queueName).build();
                Instant start = Instant.now().minus(Duration.ofMinutes(5));

                // SQS publishes once a minute. On AWS a newly active queue can take up to 15 minutes,
                // and ListMetrics can trail the statistics by as long.
                TestFixtures.await(() -> visibleMaximum(cloudWatch, queue, start).contains(1.0)
                                && sent(cloudWatch, queue, start).stream().mapToDouble(Datapoint::sum).sum() == 1.0
                                && listed(cloudWatch, queueName).containsAll(STANDARD_QUEUE_METRICS),
                        queueName, "the AWS/SQS metrics of one sent message",
                        Duration.ofMinutes(TestFixtures.isRealAws() ? 20 : 3).toNanos(),
                        TestFixtures.isRealAws() ? 30_000 : 1_000);

                assertThat(sent(cloudWatch, queue, start)).extracting(Datapoint::unit)
                        .containsOnly(StandardUnit.COUNT);
            } finally {
                sqs.deleteQueue(request -> request.queueUrl(queueUrl));
            }
        }
    }

    private static List<Double> visibleMaximum(CloudWatchClient cloudWatch, Dimension queue, Instant start) {
        List<MetricDataResult> results = cloudWatch.getMetricData(request -> request
                .startTime(start).endTime(Instant.now().plus(Duration.ofMinutes(1)))
                .metricDataQueries(query -> query.id("visible").metricStat(stat -> stat
                        .metric(metric -> metric.namespace(NAMESPACE)
                                .metricName("ApproximateNumberOfMessagesVisible").dimensions(queue))
                        .period(60).stat("Maximum")))).metricDataResults();
        return results.isEmpty() ? List.of() : results.get(0).values();
    }

    private static List<Datapoint> sent(CloudWatchClient cloudWatch, Dimension queue, Instant start) {
        return cloudWatch.getMetricStatistics(request -> request.namespace(NAMESPACE)
                .metricName("NumberOfMessagesSent").dimensions(queue).startTime(start)
                .endTime(Instant.now().plus(Duration.ofMinutes(1))).period(60).statistics(Statistic.SUM))
                .datapoints();
    }

    private static List<String> listed(CloudWatchClient cloudWatch, String queueName) {
        return cloudWatch.listMetrics(request -> request.namespace(NAMESPACE)
                        .dimensions(DimensionFilter.builder().name("QueueName").value(queueName).build()))
                .metrics().stream().map(Metric::metricName).toList();
    }
}
