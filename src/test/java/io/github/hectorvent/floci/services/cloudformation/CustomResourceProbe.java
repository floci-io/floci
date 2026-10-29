package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The handler of a custom resource in a stack test. It stands in for the ServiceToken Lambda on a
 * mocked {@link LambdaService}, keeps every request it is sent, and answers each the way a handler
 * does: a PUT of a SUCCESS result to the event's {@code ResponseURL}.
 */
final class CustomResourceProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<JsonNode> events = new CopyOnWriteArrayList<>();
    private final String physicalId;

    private CustomResourceProbe(String physicalId) {
        this.physicalId = physicalId;
    }

    static CustomResourceProbe stub(LambdaService lambdaService, String serviceToken, String physicalId) {
        CustomResourceProbe probe = new CustomResourceProbe(physicalId);
        when(lambdaService.invoke(any(), eq(serviceToken), any(), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    JsonNode event = MAPPER.readTree((byte[]) invocation.getArgument(2));
                    probe.events.add(event);
                    probe.succeed(event);
                    return new InvokeResult(200, null, "null".getBytes(StandardCharsets.UTF_8), null, "req-1");
                });
        return probe;
    }

    /** The first request of {@code requestType}, such as Create. */
    JsonNode request(String requestType) {
        return events.stream().filter(event -> requestType.equals(event.path("RequestType").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("No " + requestType + " request in " + events));
    }

    private void succeed(JsonNode event) {
        ObjectNode response = MAPPER.createObjectNode().put("Status", "SUCCESS")
                .put("RequestId", event.get("RequestId").asText())
                .put("StackId", event.get("StackId").asText())
                .put("LogicalResourceId", event.get("LogicalResourceId").asText())
                .put("PhysicalResourceId", physicalId);
        given().body(response.toString()).when().put(URI.create(event.get("ResponseURL").asText()).getPath())
                .then().statusCode(200);
    }
}
