package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Enforcement of the input constraints the 2014-03-28 model pins on the
 * pre-existing operations: logEvents list 1-10000 on PutLogEvents,
 * logStreamNames list 1-100 on FilterLogEvents, and the Random/ByLogStream
 * distribution enum on PutSubscriptionFilter. All were previously accepted
 * verbatim.
 */
class CloudWatchLogsInputConstraintTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String GROUP = "constraint-group";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchLogsHandler handler;

    @BeforeEach
    void setUp() {
        CloudWatchLogsService service = new CloudWatchLogsService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                10_000,
                new RegionResolver(REGION, ACCOUNT)
        );
        handler = new CloudWatchLogsHandler(service, MAPPER);
        service.createLogGroup(GROUP, null, null, REGION);
        service.createLogStream(GROUP, "s1", REGION);
    }

    private static AwsException expect400(Runnable call) {
        AwsException e = assertThrows(AwsException.class, call::run);
        assertEquals(400, e.getHttpStatus());
        assertEquals("InvalidParameterException", e.getErrorCode());
        return e;
    }

    @Test
    void putLogEvents_emptyList_isRejected() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP).put("logStreamName", "s1");
        request.putArray("logEvents");
        expect400(() -> handler.handle("PutLogEvents", request, REGION));
    }

    @Test
    void filterLogEvents_tooManyStreamNames_isRejected() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP);
        ArrayNode names = request.putArray("logStreamNames");
        for (int i = 0; i <= 100; i++) {
            names.add("s" + i);
        }
        expect400(() -> handler.handle("FilterLogEvents", request, REGION));
    }

    @Test
    void filterLogEvents_explicitNullStreamNames_isTreatedAsAbsent() {
        // request.has("logStreamNames") is true for an explicit JSON null, and mapping
        // NullNode to size 0 trips the min-1 bound below. AWS treats an explicit null on
        // an optional member as absent, so this must succeed, not 400.
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP);
        request.putNull("logStreamNames");
        assertEquals(200, handler.handle("FilterLogEvents", request, REGION).getStatus());
    }

    @Test
    void putSubscriptionFilter_unknownDistribution_isRejected() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("logGroupName", GROUP).put("filterName", "f")
                .put("filterPattern", "").put("destinationArn",
                        "arn:aws:lambda:us-east-1:000000000000:function:fn")
                .put("distribution", "RoundRobin");
        expect400(() -> handler.handle("PutSubscriptionFilter", request, REGION));
    }
}
