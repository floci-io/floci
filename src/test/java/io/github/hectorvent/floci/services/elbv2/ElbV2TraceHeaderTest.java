package io.github.hectorvent.floci.services.elbv2;

import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;

class ElbV2TraceHeaderTest {

    private static final String ID = "1-[0-9a-f]{8}-[0-9a-f]{24}";
    private static final String ROOT = "Root=1-67891233-abcdef012345678912345678";
    private static final String OLD_SELF = "1-67891233-000000000000000000000000";

    @Test
    void absentHeaderGetsARootField() {
        assertThat(ElbV2TraceHeader.next("", false), matchesPattern("Root=" + ID));
    }

    @Test
    void rootFieldGetsASelfFieldInFrontOfIt() {
        assertThat(ElbV2TraceHeader.next(ROOT, false), matchesPattern("Self=" + ID + ";" + ROOT));
    }

    @Test
    void customFieldsArePreserved() {
        assertThat(ElbV2TraceHeader.next(ROOT + ";CalledFrom=app", false),
                matchesPattern("Self=" + ID + ";" + ROOT + ";CalledFrom=app"));
    }

    @Test
    void existingSelfFieldIsUpdatedInPlace() {
        String updated = ElbV2TraceHeader.next("Self=" + OLD_SELF + ";" + ROOT, false);

        assertThat(updated, matchesPattern("Self=" + ID + ";" + ROOT));
        assertThat(updated, not(matchesPattern(".*" + OLD_SELF + ".*")));
    }

    @Test
    void customFieldsWithoutARootGetARootField() {
        assertThat(ElbV2TraceHeader.next("CalledFrom=app", false), matchesPattern("Root=" + ID + ";CalledFrom=app"));
    }

    @Test
    void oversizedRequestIsRewrittenToARootField() {
        assertThat(ElbV2TraceHeader.next(ROOT + ";CalledFrom=app", true), matchesPattern("Root=" + ID));
    }

    @Test
    void updateMeasuresTheRequestHeaders() {
        MultiMap small = MultiMap.caseInsensitiveMultiMap().add("x-amzn-trace-id", ROOT);
        ElbV2TraceHeader.update(small);
        assertThat(small.get(ElbV2TraceHeader.NAME), matchesPattern("Self=" + ID + ";" + ROOT));

        MultiMap large = MultiMap.caseInsensitiveMultiMap()
                .add("x-amzn-trace-id", ROOT)
                .add("X-Large", "x".repeat(8 * 1024));
        ElbV2TraceHeader.update(large);
        assertThat(large.get(ElbV2TraceHeader.NAME), matchesPattern("Root=" + ID));
    }
}
