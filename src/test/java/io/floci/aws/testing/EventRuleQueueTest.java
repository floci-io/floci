package io.floci.aws.testing;

import io.restassured.RestAssured;
import io.restassured.builder.ResponseBuilder;
import io.restassured.filter.Filter;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class EventRuleQueueTest {

    @ParameterizedTest
    @ValueSource(strings = {"AmazonSQS.GetQueueAttributes", "AWSEvents.PutRule", "AWSEvents.PutTargets"})
    void setupFailureStillDeletesTheCreatedQueue(String failingAction) {
        List<String> actions = new ArrayList<>();
        List<Filter> original = RestAssured.filters();
        RestAssured.replaceFiltersWith((request, response, context) -> {
            String action = request.getHeaders().getValue("X-Amz-Target");
            actions.add(action);
            return response(action.equals(failingAction) ? 500 : 200);
        });
        try {
            assertThrows(AssertionError.class, () -> EventRuleQueue.route("us-east-1", "cleanup", "{}"));
            assertTrue(actions.contains("AmazonSQS.DeleteQueue"), actions.toString());
            if (!failingAction.equals("AmazonSQS.GetQueueAttributes")) {
                assertTrue(actions.contains("AWSEvents.DeleteRule"), actions.toString());
            }
        } finally {
            RestAssured.replaceFiltersWith(original);
        }
    }

    @Test
    void failingTargetRemovalStillDeletesTheRuleAndQueue() {
        List<String> actions = new ArrayList<>();
        AtomicBoolean closing = new AtomicBoolean();
        List<Filter> original = RestAssured.filters();
        RestAssured.replaceFiltersWith((request, response, context) -> {
            String action = request.getHeaders().getValue("X-Amz-Target");
            actions.add(action);
            return response(closing.get() && action.equals("AWSEvents.RemoveTargets") ? 500 : 200);
        });
        try {
            EventRuleQueue queue = EventRuleQueue.route("us-east-1", "cleanup", "{}");
            closing.set(true);
            assertThrows(AssertionError.class, queue::close);
            assertTrue(actions.contains("AWSEvents.DeleteRule"), actions.toString());
            assertTrue(actions.contains("AmazonSQS.DeleteQueue"), actions.toString());
        } finally {
            RestAssured.replaceFiltersWith(original);
        }
    }

    private static Response response(int status) {
        return new ResponseBuilder().setStatusCode(status).setContentType("application/json")
                .setBody("{\"QueueUrl\":\"http://localhost/queue\","
                        + "\"Attributes\":{\"QueueArn\":\"arn:aws:sqs:us-east-1:000000000000:q\"},"
                        + "\"FailedEntryCount\":0}").build();
    }
}
