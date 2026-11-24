package io.floci.aws.services.cloudwatch.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MetricKeyIndexTest {

    private static final String ACCOUNT = "000000000000";
    private static final String SERIES = "us-east-1::AWS/SQS::NumberOfMessagesSent::QueueName=orders::";

    private static String key(String series, long timestamp, String id) {
        return series + MetricKeyIndex.timestampSegment(timestamp) + "::" + id;
    }

    @Test
    void keysBetween_includesTheStartSecondAndExcludesTheEndSecond() {
        MetricKeyIndex index = new MetricKeyIndex();
        for (String key : List.of(key(SERIES, 59, "a"), key(SERIES, 60, "b"), key(SERIES, 119, "c"),
                key(SERIES, 120, "d"))) {
            index.add(ACCOUNT, key);
        }

        assertEquals(List.of(key(SERIES, 60, "b"), key(SERIES, 119, "c")),
                index.keysBetween(ACCOUNT, SERIES, 60, 120));
    }

    @Test
    void keysBetween_skipsAMetricWhoseDimensionsExtendTheRequestedOnes() {
        String wider = "us-east-1::AWS/SQS::NumberOfMessagesSent::QueueName=orders,Region=eu::";
        MetricKeyIndex index = new MetricKeyIndex();
        index.add(ACCOUNT, key(SERIES, 60, "a"));
        index.add(ACCOUNT, key(wider, 60, "b"));

        assertEquals(List.of(key(SERIES, 60, "a")), index.keysBetween(ACCOUNT, SERIES, 0, Long.MAX_VALUE));
    }

    @Test
    void keysBetween_readsOnlyTheGivenAccount() {
        MetricKeyIndex index = new MetricKeyIndex();
        index.add(ACCOUNT, key(SERIES, 60, "a"));
        index.add("111111111111", key(SERIES, 60, "b"));

        assertEquals(List.of(key(SERIES, 60, "b")), index.keysBetween("111111111111", SERIES, 0, 120));
    }

    @Test
    void removeBefore_keepsTheCutoffSecondAndOtherNamespaces() {
        String otherNamespace = "us-east-1::AWS/SQSX::NumberOfMessagesSent::QueueName=orders::";
        MetricKeyIndex index = new MetricKeyIndex();
        index.add(ACCOUNT, key(SERIES, 59, "a"));
        index.add(ACCOUNT, key(SERIES, 60, "b"));
        index.add(ACCOUNT, key(otherNamespace, 59, "c"));

        index.removeBefore("AWS/SQS", 60);

        assertEquals(List.of(key(SERIES, 60, "b")), index.keysBetween(ACCOUNT, SERIES, 0, 120));
        assertEquals(List.of(key(otherNamespace, 59, "c")), index.keysBetween(ACCOUNT, otherNamespace, 0, 120));
    }
}
