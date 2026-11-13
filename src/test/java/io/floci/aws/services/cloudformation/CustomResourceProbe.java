package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The handler of a custom resource in a stack test. It stands in for the ServiceToken Lambda on a
 * mocked {@link LambdaService}, keeps every request it is sent, and answers each the way a handler
 * does: a PUT of its result to the event's {@code ResponseURL}, SUCCESS unless {@link #failOn} names
 * the request, and only once {@link #hold} lets it, where one is set.
 */
final class CustomResourceProbe {

    /** A request {@link #hold} keeps from its answer until the test releases it. */
    static final class Hold {
        private final CountDownLatch received = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        /** Whether a held request has arrived within {@code timeout}. */
        boolean awaitRequest(long timeout, TimeUnit unit) throws InterruptedException {
            return received.await(timeout, unit);
        }

        /** Lets the held request answer. Idempotent, so an {@code @AfterEach} can call it. */
        void release() {
            released.countDown();
        }

        private void keep() throws InterruptedException {
            received.countDown();
            if (!released.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("the test never released the held custom resource request");
            }
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<JsonNode> events = new CopyOnWriteArrayList<>();
    private volatile Function<JsonNode, String> physicalId;
    private volatile Function<JsonNode, Map<String, String>> data;
    private volatile Predicate<JsonNode> failing = event -> false;
    private volatile String failureReason;
    private volatile Predicate<JsonNode> holding = event -> false;
    private volatile Hold hold;

    private CustomResourceProbe(String physicalId) {
        this.physicalId = event -> physicalId;
    }

    static CustomResourceProbe stub(LambdaService lambdaService, String serviceToken, String physicalId) {
        CustomResourceProbe probe = new CustomResourceProbe(physicalId);
        when(lambdaService.invoke(any(), eq(serviceToken), any(), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                    probe.events.add(event);
                    probe.answer(event);
                    return new InvokeResult(200, null, "null".getBytes(StandardCharsets.UTF_8), null, "req-1");
                });
        return probe;
    }

    /** Answers each request with the PhysicalResourceId {@code physicalId} builds from it. */
    CustomResourceProbe physicalId(Function<JsonNode, String> physicalId) {
        this.physicalId = physicalId;
        return this;
    }

    /** Answers each request with the Data {@code data} builds from it. */
    CustomResourceProbe data(Function<JsonNode, Map<String, String>> data) {
        this.data = data;
        return this;
    }

    /** Answers the requests {@code request} matches FAILED, with {@code reason}. */
    void failOn(Predicate<JsonNode> request, String reason) {
        failureReason = reason;
        failing = request;
    }

    /**
     * Holds the requests {@code request} matches before they are answered, until the returned hold is
     * released, so a test can act while a stack operation waits on its custom resource.
     */
    Hold hold(Predicate<JsonNode> request) {
        hold = new Hold();
        holding = request;
        return hold;
    }

    /** Every request so far, in the order it was sent. */
    List<JsonNode> events() {
        return events;
    }

    /** The first request of {@code requestType}, such as Create. */
    JsonNode request(String requestType) {
        return events.stream().filter(event -> requestType.equals(event.path("RequestType").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("No " + requestType + " request in " + events));
    }

    private void answer(JsonNode event) throws InterruptedException {
        if (holding.test(event)) {
            hold.keep();
        }
        ObjectNode response = MAPPER.createObjectNode();
        if (failing.test(event)) {
            response.put("Status", "FAILED").put("Reason", failureReason);
        } else {
            response.put("Status", "SUCCESS");
        }
        response.put("RequestId", event.get("RequestId").asText())
                .put("StackId", event.get("StackId").asText())
                .put("LogicalResourceId", event.get("LogicalResourceId").asText())
                .put("PhysicalResourceId", physicalId.apply(event));
        if (data != null) {
            response.set("Data", MAPPER.valueToTree(data.apply(event)));
        }
        given().body(response.toString()).when().put(URI.create(event.get("ResponseURL").asText()).getPath())
                .then().statusCode(200);
    }
}
