package io.floci.aws.services.xray;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the service graph X-Ray answers from traces: one node per service, one edge per call a
 * segment made into another service, with the counts and timings aggregated over the window.
 *
 * <p>A node is a segment's {@code name} plus the type a document can imply, so the same service
 * across many traces collapses into one node. An edge exists when a segment's {@code parent_id}
 * names another segment in the same trace, which is how the SDK records "A called B".
 */
final class XrayGraph {

    /** One node of the graph, aggregated over every trace in the window. */
    record Node(int referenceId, String name, String type, String state, double startTime,
                double endTime, long okCount, long errorCount, long throttleCount, long faultCount,
                long totalCount, double totalResponseTime, List<Edge> edges,
                List<HistogramEntry> durationHistogram) {
    }

    /** A call from one node into another. */
    record Edge(int referenceId, double startTime, double endTime, long okCount, long errorCount,
                long throttleCount, long faultCount, long totalCount, double totalResponseTime,
                List<HistogramEntry> responseTimeHistogram) {
    }

    /** One bucket of a histogram: the upper bound in seconds and how many calls fell at or below. */
    record HistogramEntry(double value, long count) {
    }

    /** AWS's fixed bucket edges, in seconds. */
    private static final double[] BUCKETS = {0.005, 0.01, 0.05, 0.1, 0.5, 1, 5, 10, 30};

    private XrayGraph() {
    }

    static List<Node> build(List<List<XraySegments.Node>> traces) {
        Map<String, Accumulator> nodes = new LinkedHashMap<>();
        Map<EdgeKey, EdgeAccumulator> edges = new LinkedHashMap<>();
        for (List<XraySegments.Node> nodesOfTrace : traces) {
            Map<String, XraySegments.Node> byId = new LinkedHashMap<>();
            for (XraySegments.Node node : nodesOfTrace) {
                byId.put(node.id(), node);
            }
            for (XraySegments.Node node : nodesOfTrace) {
                Accumulator accumulator = nodes.computeIfAbsent(key(node), ignored -> new Accumulator(node));
                accumulator.add(node);
                XraySegments.Node parent = byId.get(node.parentId());
                if (parent != null && !key(parent).equals(key(node))) {
                    EdgeAccumulator edge = edges.computeIfAbsent(new EdgeKey(key(parent), key(node)),
                            ignored -> new EdgeAccumulator(referenceId(key(node))));
                    edge.add(node);
                }
            }
        }
        for (Map.Entry<EdgeKey, EdgeAccumulator> entry : edges.entrySet()) {
            Accumulator from = nodes.get(entry.getKey().from());
            Accumulator to = nodes.get(entry.getKey().to());
            if (from != null && to != null) {
                from.edges.add(entry.getValue().toEdge());
            }
        }
        List<Node> result = new ArrayList<>();
        for (Accumulator accumulator : nodes.values()) {
            result.add(accumulator.toNode());
        }
        return result;
    }

    /**
     * A node is a service, so its name alone identifies it: the same service seen once with an
     * {@code http} block and once without is one node, not two.
     */
    private static String key(XraySegments.Node node) {
        return node.name();
    }

    /** An edge's ends, kept as the two names: joining them into one string breaks on a name that
     * contains the separator, which a service name is free to do. */
    private record EdgeKey(String from, String to) {
    }

    /** AWS's graph answers an integer reference per node, so a stable one is derived from its key. */
    private static int referenceId(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return ((digest[0] & 0xff) << 24 | (digest[1] & 0xff) << 16
                    | (digest[2] & 0xff) << 8 | (digest[3] & 0xff)) & 0x7fffffff;
        } catch (Exception e) {
            return key.hashCode() & 0x7fffffff;
        }
    }

    /**
     * The four outcome counts, one per subsegment: fault, throttle, error or ok, in that order of
     * precedence. A subsegment is counted once, so the four add up to the number of calls, and a
     * 429 that also carries the error flag is a throttle because that is what it was.
     */
    private static final class Counts {
        private long ok;
        private long error;
        private long throttle;
        private long fault;

        void add(XraySegments.Node node) {
            if (node.fault()) {
                fault++;
            } else if (node.throttle()) {
                throttle++;
            } else if (node.error()) {
                error++;
            } else {
                ok++;
            }
        }

        /** Every subsegment, counted once: the four columns partition the set rather than overlap. */
        long total() {
            return ok + error + throttle + fault;
        }
    }

    private static class Accumulator {
        private final int referenceId;
        private final String name;
        private String type;
        private final List<Edge> edges = new ArrayList<>();
        private double startTime = Double.MAX_VALUE;
        private double endTime = -Double.MAX_VALUE;
        private final Counts counts = new Counts();
        private double totalResponseTime;
        private final List<Double> durations = new ArrayList<>();

        Accumulator(XraySegments.Node node) {
            this.referenceId = referenceId(key(node));
            this.name = node.name();
            this.type = node.type();
        }

        void add(XraySegments.Node node) {
            if (type == null) {
                type = node.type();
            }
            startTime = Math.min(startTime, node.startTime());
            endTime = Math.max(endTime, node.endTime());
            totalResponseTime += node.duration();
            durations.add(node.duration());
            counts.add(node);
        }

        String state() {
            if (counts.fault > 0) {
                return "fault";
            }
            if (counts.throttle > 0) {
                return "throttle";
            }
            if (counts.error > 0) {
                return "error";
            }
            return "ok";
        }

        Node toNode() {
            return new Node(referenceId, name, type, state(), startTime, endTime, counts.ok, counts.error,
                    counts.throttle, counts.fault, counts.total(), totalResponseTime, List.copyOf(edges),
                    histogram(durations));
        }
    }

    private static class EdgeAccumulator {
        private final int toReferenceId;
        private double startTime = Double.MAX_VALUE;
        private double endTime = -Double.MAX_VALUE;
        private final Counts counts = new Counts();
        private double totalResponseTime;
        private final List<Double> durations = new ArrayList<>();

        EdgeAccumulator(int toReferenceId) {
            this.toReferenceId = toReferenceId;
        }

        void add(XraySegments.Node node) {
            startTime = Math.min(startTime, node.startTime());
            endTime = Math.max(endTime, node.endTime());
            totalResponseTime += node.duration();
            durations.add(node.duration());
            counts.add(node);
        }

        Edge toEdge() {
            // AWS's Edge names the destination: that is the node the arrow lands on.
            return new Edge(toReferenceId, startTime, endTime, counts.ok, counts.error, counts.throttle,
                    counts.fault, counts.total(), totalResponseTime, histogram(durations));
        }
    }

    /** Cumulative buckets, so the last entry counts every call in the set. */
    static List<HistogramEntry> histogram(List<Double> durations) {
        List<HistogramEntry> entries = new ArrayList<>();
        for (double bucket : BUCKETS) {
            long count = durations.stream().filter(duration -> duration <= bucket).count();
            entries.add(new HistogramEntry(bucket, count));
        }
        return entries;
    }
}
