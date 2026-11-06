package io.floci.aws.services.xray;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XrayGraphTest {

    private static final String TRACE_ID = "1-5f3b8a0e-3c9d4d8e9b7a2c1d4e5f6a7b";

    private static XraySegments.Node node(String id, String parentId, String name, boolean fault,
                                          boolean error, boolean throttle) {
        return new XraySegments.Node(TRACE_ID, id, parentId, name, 1.0, 1.5, fault, error, throttle,
                null, null, null, false, Map.of());
    }

    private static XrayGraph.Node single(XraySegments.Node node) {
        List<XrayGraph.Node> nodes = XrayGraph.build(List.of(List.of(node)));
        assertEquals(1, nodes.size());
        return nodes.get(0);
    }

    @Test
    void aSegmentThatIsBothErrorAndThrottledIsAThrottleAndCountsOnce() {
        XrayGraph.Node node = single(node("0f1e2d3c4b5a6978", "", "orders-api", false, true, true));

        assertEquals(0, node.okCount());
        assertEquals(0, node.errorCount(), "a 429 that also carries the error flag is a throttle");
        assertEquals(1, node.throttleCount());
        assertEquals(0, node.faultCount());
        assertEquals(1, node.totalCount(), "a subsegment is one call, however many flags it carries");
    }

    @Test
    void aThrottleOnlySegmentIsCountedInTheTotalAndNotAsOk() {
        XrayGraph.Node node = single(node("0f1e2d3c4b5a6978", "", "orders-api", false, false, true));

        assertEquals(0, node.okCount());
        assertEquals(1, node.throttleCount());
        assertEquals(1, node.totalCount(), "a throttled call is not an ok call and must still be counted");
        assertEquals("throttle", node.state());
    }

    @Test
    void anEdgeCountsItsCallsTheSameWayItsNodeDoes() {
        XraySegments.Node root = node("0f1e2d3c4b5a6978", "", "orders-api", false, false, false);
        XraySegments.Node call = node("8a7b6c5d4e3f2019", "0f1e2d3c4b5a6978", "payments", false, true, true);

        List<XrayGraph.Node> nodes = XrayGraph.build(List.of(List.of(root, call)));

        XrayGraph.Node orders = nodes.stream().filter(node -> node.name().equals("orders-api")).findFirst()
                .orElseThrow();
        XrayGraph.Node payments = nodes.stream().filter(node -> node.name().equals("payments")).findFirst()
                .orElseThrow();
        assertEquals(1, orders.edges().size());
        XrayGraph.Edge edge = orders.edges().get(0);
        assertEquals(edge.referenceId(), payments.referenceId());
        assertEquals(0, edge.errorCount());
        assertEquals(1, edge.throttleCount());
        assertEquals(1, edge.totalCount());
        assertEquals(0, payments.errorCount());
        assertEquals(1, payments.throttleCount());
        assertEquals(1, payments.totalCount());
    }

    @Test
    void aFaultKeepsItsOwnColumnAndTheStateReportsItFirst() {
        XrayGraph.Node node = single(node("0f1e2d3c4b5a6978", "", "orders-api", true, true, true));

        assertEquals(1, node.faultCount());
        assertEquals(0, node.errorCount());
        assertEquals(0, node.throttleCount(), "one call is one column, and fault wins");
        assertEquals(1, node.totalCount());
        assertEquals("fault", node.state());
    }

    @Test
    void aSegmentWithNoFlagIsTheOnlyOneCountedAsOk() {
        XrayGraph.Node node = single(node("0f1e2d3c4b5a6978", "", "orders-api", false, false, false));

        assertEquals(1, node.okCount());
        assertEquals(1, node.totalCount());
        assertEquals("ok", node.state());
    }
}
