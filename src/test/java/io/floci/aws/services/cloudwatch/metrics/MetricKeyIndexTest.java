package io.floci.aws.services.cloudwatch.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    void removeOldest_takesTheOldestKeysAcrossTheNamespacesSeriesUntilTheKeepCountIsLeft() {
        String received = "us-east-1::AWS/SQS::NumberOfMessagesReceived::QueueName=orders::";
        String otherNamespace = "us-east-1::AWS/SQSX::NumberOfMessagesSent::QueueName=orders::";
        MetricKeyIndex index = new MetricKeyIndex();
        for (String key : List.of(key(SERIES, 10, "a"), key(received, 20, "b"), key(SERIES, 30, "c"),
                key(received, 40, "d"), key(SERIES, 50, "e"), key(otherNamespace, 1, "f"))) {
            index.add(ACCOUNT, key);
        }
        index.add("111111111111", key(SERIES, 5, "g"));

        Map<String, Set<String>> removed = index.removeOldest("AWS/SQS", 4, 3);

        assertEquals(Map.of(ACCOUNT, Set.of(key(SERIES, 10, "a"), key(received, 20, "b"))), removed);
        assertEquals(List.of(key(SERIES, 30, "c"), key(SERIES, 50, "e")), index.keysBetween(ACCOUNT, SERIES, 0, 120));
        assertEquals(List.of(key(received, 40, "d")), index.keysBetween(ACCOUNT, received, 0, 120));
        assertEquals(List.of(key(otherNamespace, 1, "f")), index.keysBetween(ACCOUNT, otherNamespace, 0, 120));
        assertEquals(List.of(key(SERIES, 5, "g")), index.keysBetween("111111111111", SERIES, 0, 120));
    }

    @Test
    void removeOldest_leavesAnAccountThatHoldsExactlyTheCeiling() {
        MetricKeyIndex index = new MetricKeyIndex();
        index.add(ACCOUNT, key(SERIES, 10, "a"));
        index.add(ACCOUNT, key(SERIES, 20, "b"));

        assertEquals(Map.of(), index.removeOldest("AWS/SQS", 2, 1));
        assertEquals(2, index.keysBetween(ACCOUNT, SERIES, 0, 120).size());
    }
}
