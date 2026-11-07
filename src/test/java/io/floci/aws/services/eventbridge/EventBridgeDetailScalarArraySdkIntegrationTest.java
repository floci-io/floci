package io.floci.aws.services.eventbridge;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EventRuleQueue;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EventBridgeDetailScalarArraySdkIntegrationTest {

    private static final String PATTERN = "{\"source\":[\"scalar-array.test\"],"
            + "\"detail\":{\"EventCategories\":[\"notification\"]}}";

    private EventBridgeClient events;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createClient() {
        events = AwsTestClients.create(EventBridgeClient.builder(), "000000000000", Region.US_EAST_1);
    }

    @AfterEach
    void closeClient() {
        events.close();
    }

    private boolean testEventPattern(String detail) {
        String event = "{\"id\":\"1\",\"source\":\"scalar-array.test\",\"detail-type\":\"X\",\"detail\":" + detail + "}";
        return events.testEventPattern(r -> r.eventPattern(PATTERN).event(event)).result();
    }

    @Test
    void testEventPatternMatchesArrayHoldingPatternValue() {
        assertTrue(testEventPattern("{\"EventCategories\":[\"notification\"]}"));
    }

    @Test
    void testEventPatternMatchesOnAnySharedMember() {
        assertTrue(testEventPattern("{\"EventCategories\":[\"other\",\"notification\"]}"));
    }

    @Test
    void testEventPatternStillMatchesScalar() {
        assertTrue(testEventPattern("{\"EventCategories\":\"notification\"}"));
    }

    @Test
    void testEventPatternRejectsArrayWithoutSharedValue() {
        assertFalse(testEventPattern("{\"EventCategories\":[\"other\"]}"));
    }

    @Test
    void testEventPatternRejectsEmptyArray() {
        assertFalse(testEventPattern("{\"EventCategories\":[]}"));
    }

    @Test
    void putEventsRoutesOnlyArraysSharingAValue() throws Exception {
        String[] details = new String[] {
                "{\"EventCategories\":[\"notification\"]}",
                "{\"EventCategories\":[\"other\",\"notification\"]}",
                "{\"EventCategories\":[\"other\"]}",
                "{\"EventCategories\":[]}"};
        try (EventRuleQueue queue = EventRuleQueue.route("us-east-1", "scalar-array-rule", PATTERN)) {
            for (String detail : details) {
                events.putEvents(r -> r.entries(PutEventsRequestEntry.builder()
                        .source("scalar-array.test").detailType("X").detail(detail).build()));
            }
            assertEquals(2, queue.drain().size());
        }
    }
}
