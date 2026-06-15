package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hermetic test of the custom-resource provisioning path. The backing Lambda is mocked: when
 * invoked it parses the event, extracts the ResponseURL token, and completes the response store
 * exactly as a real handler's HTTP PUT would. No Docker / Quarkus involved.
 */
class CustomResourceProvisionerTest {

    private static final String SERVICE_TOKEN =
            "arn:aws:lambda:us-east-1:000000000000:function:MyHandler";

    private final ObjectMapper mapper = new ObjectMapper();
    private LambdaService lambdaService;
    private CustomResourceResponseStore store;
    private CloudFormationResourceProvisioner provisioner;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        store = new CustomResourceResponseStore();
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.baseUrl()).thenReturn("http://floci:4566");

        provisioner = new CloudFormationResourceProvisioner(
                null, null, null, null, lambdaService, null, null, null, null, null,
                null, null, null, null, null, null, mapper, store, endpoint, null, null, null, null, null);
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                "stack/id", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
                (Function<String, String>) name -> null);
    }

    private ObjectNode props() {
        ObjectNode props = mapper.createObjectNode();
        props.put("ServiceToken", SERVICE_TOKEN);
        props.put("Message", "hi");
        props.put("Prune", true); // CloudFormation stringifies scalars; handler expects "true"
        return props;
    }

    /** Stubs the Lambda to behave like a handler that PUTs a SUCCESS response to the ResponseURL. */
    private void stubHandler(String physicalId, Map<String, String> data) {
        when(lambdaService.invoke(any(), eq(SERVICE_TOKEN), any(), eq(InvocationType.RequestResponse)))
                .thenAnswer(inv -> {
                    JsonNode event = mapper.readTree((byte[]) inv.getArgument(2));
                    String responseUrl = event.get("ResponseURL").asText();
                    String token = responseUrl.substring(responseUrl.lastIndexOf('/') + 1);

                    ObjectNode response = mapper.createObjectNode();
                    response.put("Status", "SUCCESS");
                    response.put("PhysicalResourceId", physicalId);
                    ObjectNode dataNode = response.putObject("Data");
                    data.forEach(dataNode::put);
                    store.complete(token, response);

                    return new InvokeResult(200, null, "null".getBytes(), null, "req-1");
                });
    }

    @Test
    void createInvokesHandlerAndCapturesPhysicalIdAndData() {
        stubHandler("phys-123", Map.of("Greeting", "hello"));

        StackResource r = provisioner.provision("MyCr", "Custom::Test", props(),
                engine(), "us-east-1", "000000000000", "my-stack");

        assertEquals("CREATE_COMPLETE", r.getStatus());
        assertEquals("phys-123", r.getPhysicalId());
        assertEquals("hello", r.getAttributes().get("Greeting"));

        // The event carried the expected request shape.
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq("us-east-1"), eq(SERVICE_TOKEN), payload.capture(),
                eq(InvocationType.RequestResponse));
        JsonNode event = readEvent(payload);
        assertEquals("Create", event.get("RequestType").asText());
        assertTrue(event.get("ResponseURL").asText().startsWith("http://floci:4566/cfn-response/"));
        assertEquals("hi", event.get("ResourceProperties").get("Message").asText());
        // CloudFormation carries ServiceToken both at the top level and inside ResourceProperties.
        assertEquals(SERVICE_TOKEN, event.get("ServiceToken").asText());
        assertEquals(SERVICE_TOKEN, event.get("ResourceProperties").get("ServiceToken").asText());
        // Scalars are stringified to match CloudFormation (true -> "true"), not native JSON booleans.
        JsonNode prune = event.get("ResourceProperties").get("Prune");
        assertTrue(prune.isTextual(), "Prune should be stringified");
        assertEquals("true", prune.asText());
    }

    @Test
    void updateInvokesHandlerWithOldResourceProperties() {
        stubHandler("phys-123", Map.of("Greeting", "hello"));

        // Seed the prior create's stashed ResourceProperties so this provision is an Update.
        ObjectNode oldProps = mapper.createObjectNode();
        oldProps.put("ServiceToken", SERVICE_TOKEN);
        oldProps.put("Message", "old");
        Map<String, String> existingAttributes =
                Map.of("__FlociResourceProperties", oldProps.toString());

        StackResource r = provisioner.provision("MyCr", "Custom::Test", props(),
                engine(), "us-east-1", "000000000000", "my-stack", "phys-123", existingAttributes);

        assertEquals("CREATE_COMPLETE", r.getStatus());

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq("us-east-1"), eq(SERVICE_TOKEN), payload.capture(),
                eq(InvocationType.RequestResponse));
        JsonNode event = readEvent(payload);
        assertEquals("Update", event.get("RequestType").asText());
        assertEquals("phys-123", event.get("PhysicalResourceId").asText());
        assertEquals("hi", event.get("ResourceProperties").get("Message").asText());
        // CloudFormation includes the previous properties on Update so handlers can diff.
        assertEquals("old", event.get("OldResourceProperties").get("Message").asText());
    }

    @Test
    void handlerFailureMarksResourceCreateFailed() {
        when(lambdaService.invoke(any(), eq(SERVICE_TOKEN), any(), eq(InvocationType.RequestResponse)))
                .thenAnswer(inv -> {
                    JsonNode event = mapper.readTree((byte[]) inv.getArgument(2));
                    String responseUrl = event.get("ResponseURL").asText();
                    String token = responseUrl.substring(responseUrl.lastIndexOf('/') + 1);
                    ObjectNode response = mapper.createObjectNode();
                    response.put("Status", "FAILED");
                    response.put("Reason", "boom");
                    store.complete(token, response);
                    return new InvokeResult(200, null, "null".getBytes(), null, "req-1");
                });

        StackResource r = provisioner.provision("MyCr", "Custom::Test", props(),
                engine(), "us-east-1", "000000000000", "my-stack");

        assertEquals("CREATE_FAILED", r.getStatus());
        assertTrue(r.getStatusReason().contains("boom"));
    }

    @Test
    void deleteReinvokesHandlerWithDeleteRequestType() {
        stubHandler("phys-123", Map.of("Greeting", "hello"));
        StackResource r = provisioner.provision("MyCr", "Custom::Test", props(),
                engine(), "us-east-1", "000000000000", "my-stack");

        provisioner.delete(r, "us-east-1");

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService, times(2)).invoke(any(), eq(SERVICE_TOKEN), payloads.capture(),
                eq(InvocationType.RequestResponse));
        JsonNode deleteEvent = readEvent(() -> payloads.getAllValues().get(1));
        assertEquals("Delete", deleteEvent.get("RequestType").asText());
        assertEquals("phys-123", deleteEvent.get("PhysicalResourceId").asText());
        assertEquals("hi", deleteEvent.get("ResourceProperties").get("Message").asText());
    }

    private JsonNode readEvent(ArgumentCaptor<byte[]> captor) {
        return readEvent(captor::getValue);
    }

    private JsonNode readEvent(java.util.function.Supplier<byte[]> supplier) {
        try {
            return mapper.readTree(supplier.get());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
