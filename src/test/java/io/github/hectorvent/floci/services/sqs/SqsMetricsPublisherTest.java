package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService.Datapoint;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The AWS/SQS metrics as real SQS sends them, measured on AWS: per active queue and minute one
 * sample of each depth gauge, and for each counter one sample per API call, valued at the
 * messages that call handled, plus the zero sample AWS adds to every minute.
 */
class SqsMetricsPublisherTest {

    private static final String REGION = "eu-west-1";
    private static final String ACCOUNT = "111111111111";
    private static final String BASE_URL = "http://localhost:4566";

    private SqsService sqs;
    private CloudWatchMetricsService cloudWatch;
    private SqsMetricsPublisher publisher;

    @BeforeEach
    void setUp() {
        RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);
        sqs = SqsServiceFactory.createInMemory(BASE_URL, resolver);
        cloudWatch = new CloudWatchMetricsService(new InMemoryStorageFactory(ACCOUNT), resolver);
        publisher = new SqsMetricsPublisher(sqs, cloudWatch, mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS));
    }

    @Test
    void activeQueueReportsItsDepthAsGauges() {
        String url = sqs.createQueue("depth", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "visible", null, REGION);
        sqs.sendMessage(url, "in-flight", null, REGION);
        sqs.sendMessage(url, "delayed", 600, REGION);
        sqs.receiveMessage(url, 1, 600, 0, REGION);
        Instant now = Instant.now().plusSeconds(100);

        publisher.publish(now);

        assertSamples(datapoint("depth", "ApproximateNumberOfMessagesVisible", now), 1, 1, 1, 1, "Count");
        assertSamples(datapoint("depth", "ApproximateNumberOfMessagesNotVisible", now), 1, 1, 1, 1, "Count");
        assertSamples(datapoint("depth", "ApproximateNumberOfMessagesDelayed", now), 1, 1, 1, 1, "Count");
        Datapoint age = datapoint("depth", "ApproximateAgeOfOldestMessage", now);
        assertEquals("Seconds", age.unit());
        assertTrue(age.maximum() >= 99 && age.maximum() <= 101, "age " + age.maximum());
    }

    @Test
    void eachCallIsOneSampleValuedAtTheMessagesItHandled() {
        String url = sqs.createQueue("calls", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "one", null, REGION);
        sqs.sendMessage(url, "two", null, REGION);
        List<Message> received = sqs.receiveMessage(url, 2, 600, 0, REGION);
        assertEquals(2, received.size());
        sqs.receiveMessage(url, 1, 600, 0, REGION);
        sqs.deleteMessage(url, received.getFirst().getReceiptHandle(), REGION);
        sqs.deleteMessage(url, received.getFirst().getReceiptHandle(), REGION);
        Instant now = Instant.now();

        publisher.publish(now);

        assertSamples(datapoint("calls", "NumberOfMessagesSent", now), 3, 2, 0, 1, "Count");
        assertSamples(datapoint("calls", "NumberOfMessagesReceived", now), 2, 2, 0, 2, "Count");
        assertSamples(datapoint("calls", "NumberOfEmptyReceives", now), 2, 1, 0, 1, "Count");
        assertSamples(datapoint("calls", "NumberOfMessagesDeleted", now), 3, 2, 0, 1, "Count");
    }

    @Test
    void aQuietMinuteGetsOneZeroSamplePerCounterAndNoMessageSize() {
        sqs.createQueue("quiet", null, REGION);
        Instant now = Instant.now();

        publisher.publish(now);

        for (String counter : List.of("NumberOfMessagesSent", "NumberOfMessagesReceived",
                "NumberOfMessagesDeleted", "NumberOfEmptyReceives")) {
            assertSamples(datapoint("quiet", counter, now), 1, 0, 0, 0, "Count");
        }
        assertSamples(datapoint("quiet", "ApproximateNumberOfMessagesVisible", now), 1, 0, 0, 0, "Count");
        assertSamples(datapoint("quiet", "ApproximateAgeOfOldestMessage", now), 1, 0, 0, 0, "Seconds");
        assertNull(datapoint("quiet", "SentMessageSize", now));
        assertNull(datapoint("quiet", "NumberOfDeduplicatedSentMessages", now));
        assertNull(datapoint("quiet", "ApproximateNumberOfGroupsWithInflightMessages", now));
    }

    @Test
    void sentMessageSizeIsOneSamplePerMessage() {
        String url = sqs.createQueue("sizes", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "aa", null, REGION);
        sqs.sendMessage(url, "cccccc", null, REGION);
        Instant now = Instant.now();

        publisher.publish(now);

        assertSamples(datapoint("sizes", "SentMessageSize", now), 2, 8, 2, 6, "Bytes");
    }

    @Test
    void countersStartOverAfterEachPublication() {
        String url = sqs.createQueue("drained", null, REGION).getQueueUrl();
        sqs.sendMessage(url, "once", null, REGION);
        Instant first = Instant.now();
        publisher.publish(first);
        Instant second = first.plusSeconds(60);

        publisher.publish(second);

        assertSamples(datapoint("drained", "NumberOfMessagesSent", second), 1, 0, 0, 0, "Count");
        assertNull(datapoint("drained", "SentMessageSize", second));
        assertSamples(datapoint("drained", "ApproximateNumberOfMessagesVisible", second), 1, 1, 1, 1, "Count");
    }

    @Test
    void fifoQueueAlsoReportsDeduplicatedSendsAndGroupsWithInflightMessages() {
        String url = sqs.createQueue("dedup.fifo", Map.of("FifoQueue", "true"), REGION).getQueueUrl();
        sqs.sendMessage(url, "one", null, "g", "X", REGION);
        sqs.sendMessage(url, "one", null, "g", "X", REGION);
        sqs.sendMessage(url, "two", null, "h", "Y", REGION);
        sqs.receiveMessage(url, 1, 600, 0, REGION);
        Instant now = Instant.now();

        publisher.publish(now);

        assertSamples(datapoint("dedup.fifo", "NumberOfMessagesSent", now), 4, 3, 0, 1, "Count");
        assertSamples(datapoint("dedup.fifo", "NumberOfDeduplicatedSentMessages", now), 4, 1, 0, 1, "Count");
        assertSamples(datapoint("dedup.fifo", "SentMessageSize", now), 3, 9, 3, 3, "Bytes");
        assertSamples(datapoint("dedup.fifo", "ApproximateNumberOfGroupsWithInflightMessages", now),
                1, 1, 1, 1, "Count");
    }

    @Test
    void anEmptyQueueStopsReportingSixHoursAfterItsLastAction() {
        sqs.createQueue("idle", null, REGION);
        String busy = sqs.createQueue("holding", null, REGION).getQueueUrl();
        sqs.sendMessage(busy, "kept", null, REGION);
        Instant later = Instant.now().plus(Duration.ofHours(6)).plusSeconds(60);

        publisher.publish(later);

        assertNull(datapoint("idle", "ApproximateNumberOfMessagesVisible", later));
        assertSamples(datapoint("holding", "ApproximateNumberOfMessagesVisible", later), 1, 1, 1, 1, "Count");
    }

    @Test
    void aRestoredEmptyQueueIsReportedOnlyOnceAnActionReachesIt() {
        InMemoryStorage<String, Queue> queues = new InMemoryStorage<>();
        InMemoryStorage<String, List<Message>> messages = new InMemoryStorage<>();
        SqsService before = new SqsService(queues, messages, new InMemoryStorage<>(), 30, 1048576, BASE_URL,
                new RegionResolver(REGION, ACCOUNT));
        String url = before.createQueue("restored", null, REGION).getQueueUrl();
        before.sendMessage(url, "handled", null, REGION);
        before.deleteMessage(url, before.receiveMessage(url, 1, 600, 0, REGION).getFirst().getReceiptHandle(),
                REGION);
        SqsService restarted = new SqsService(queues, messages, new InMemoryStorage<>(), 30, 1048576, BASE_URL,
                new RegionResolver(REGION, ACCOUNT));
        SqsMetricsPublisher restartedPublisher = new SqsMetricsPublisher(restarted, cloudWatch,
                mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS));
        Instant first = Instant.now();
        restartedPublisher.publish(first);
        assertNull(datapoint("restored", "ApproximateNumberOfMessagesVisible", first));

        restarted.getQueueAttributes(url, List.of("All"), REGION);
        Instant second = first.plusSeconds(60);
        restartedPublisher.publish(second);

        assertSamples(datapoint("restored", "ApproximateNumberOfMessagesVisible", second), 1, 0, 0, 0, "Count");
    }

    @Test
    void aMessageRedrivenToAStandardDeadLetterQueueKeepsItsAge() {
        String dlq = sqs.createQueue("redriven-dlq", null, REGION).getQueueUrl();
        String dlqArn = sqs.getQueueAttributes(dlq, List.of("QueueArn"), REGION).get("QueueArn");
        String source = sqs.createQueue("redriven-source", Map.of("RedrivePolicy",
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"2\"}"), REGION).getQueueUrl();
        sqs.sendMessage(source, "pill", null, REGION);
        assertEquals(1, sqs.receiveMessage(source, 1, 0, 0, REGION).size());
        assertEquals(1, sqs.receiveMessage(source, 1, 0, 0, REGION).size());
        assertTrue(sqs.receiveMessage(source, 1, 0, 0, REGION).isEmpty());
        Instant now = Instant.now().plusSeconds(100);

        publisher.publish(now);

        assertSamples(datapoint("redriven-dlq", "ApproximateNumberOfMessagesVisible", now), 1, 1, 1, 1, "Count");
        assertTrue(datapoint("redriven-dlq", "ApproximateAgeOfOldestMessage", now).maximum() >= 99);
    }

    @Test
    void aDeletedQueueIsNoLongerReported() {
        String url = sqs.createQueue("gone", null, REGION).getQueueUrl();
        sqs.deleteQueue(url, REGION);
        Instant now = Instant.now();

        publisher.publish(now);

        assertNull(datapoint("gone", "ApproximateNumberOfMessagesVisible", now));
    }

    @Test
    void aTickStampsItsSamplesWithTheMinuteThatJustEnded() {
        sqs.createQueue("stamped", null, REGION);

        publisher.publish(Instant.parse("2026-10-22T12:01:01Z"));

        Instant ended = Instant.parse("2026-10-22T12:00:00Z");
        List<Datapoint> points = cloudWatch.getMetricStatistics("AWS/SQS", "NumberOfMessagesSent",
                List.of(new Dimension("QueueName", "stamped")), ended, ended, 60, List.of("Sum"), null, REGION);
        assertEquals(1, points.size());
        assertEquals(ended, points.getFirst().timestamp());
    }

    @Test
    void theHourlyTickDropsAwsSqsDatapointsOlderThanFifteenDays() {
        Instant tick = Instant.parse("2026-10-22T13:01:01Z");
        Instant hour = Instant.parse("2026-10-22T13:00:00Z");
        Instant expired = hour.minus(Duration.ofDays(16));
        Instant kept = hour.minus(Duration.ofDays(14));
        List<Dimension> queue = List.of(new Dimension("QueueName", "old"));
        cloudWatch.putMetricDataForAccount(ACCOUNT, "AWS/SQS", List.of(
                MetricDatum.of("NumberOfMessagesSent", "Count", 1, queue, expired.getEpochSecond()),
                MetricDatum.of("NumberOfMessagesSent", "Count", 1, queue, kept.getEpochSecond())), REGION);
        cloudWatch.putMetricDataForAccount(ACCOUNT, "Custom", List.of(
                MetricDatum.of("NumberOfMessagesSent", "Count", 1, queue, expired.getEpochSecond())), REGION);

        publisher.publish(tick);

        assertTrue(points("AWS/SQS", queue, expired).isEmpty());
        assertEquals(1, points("AWS/SQS", queue, kept).size());
        assertEquals(1, points("Custom", queue, expired).size());
    }

    private List<Datapoint> points(String namespace, List<Dimension> dimensions, Instant at) {
        return cloudWatch.getMetricStatistics(namespace, "NumberOfMessagesSent", dimensions, at, at, 60,
                List.of("Sum"), null, REGION);
    }

    @Test
    void aQueueWhosePublicationFailsDoesNotStopTheOthers() {
        sqs.createQueue("first", null, REGION);
        sqs.createQueue("second", null, REGION);
        CloudWatchMetricsService failingOnce = spy(cloudWatch);
        doThrow(new IllegalStateException("store failed")).doCallRealMethod()
                .when(failingOnce).putMetricDataForAccount(any(), any(), any(), any());
        Instant now = Instant.now();

        new SqsMetricsPublisher(sqs, failingOnce, mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS))
                .publish(now);

        long published = List.of("first", "second").stream()
                .filter(queue -> datapoint(queue, "ApproximateNumberOfMessagesVisible", now) != null)
                .count();
        assertEquals(1, published);
    }

    @Test
    void aFailingPublicationDoesNotEscapeTheScheduledTask() {
        SqsService failing = mock(SqsService.class);
        when(failing.collectQueueMetrics(any())).thenThrow(new IllegalStateException("collect failed"));
        SqsMetricsPublisher failingPublisher = new SqsMetricsPublisher(failing, cloudWatch,
                mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS));

        assertDoesNotThrow(() -> failingPublisher.publish(Instant.now()));
    }

    /** The datapoint a tick at {@code tick} published, under the minute that had just ended. */
    private Datapoint datapoint(String queueName, String metricName, Instant tick) {
        Instant minute = tick.minusSeconds(30).truncatedTo(ChronoUnit.MINUTES);
        List<Datapoint> points = cloudWatch.getMetricStatistics("AWS/SQS", metricName,
                List.of(new Dimension("QueueName", queueName)), minute, minute, 60, List.of("Sum"), null, REGION);
        assertTrue(points.size() <= 1, metricName + " has " + points.size() + " datapoints");
        return points.isEmpty() ? null : points.getFirst();
    }

    private static void assertSamples(Datapoint point, double sampleCount, double sum, double minimum,
                                      double maximum, String unit) {
        assertNotNull(point);
        assertAll(
                () -> assertEquals(sampleCount, point.sampleCount(), "SampleCount"),
                () -> assertEquals(sum, point.sum(), "Sum"),
                () -> assertEquals(minimum, point.minimum(), "Minimum"),
                () -> assertEquals(maximum, point.maximum(), "Maximum"),
                () -> assertEquals(unit, point.unit(), "Unit"));
    }
}
