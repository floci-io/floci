package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
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

    private static final String STACK_ID =
            "arn:aws:cloudformation:us-east-1:000000000000:stack/my-stack/1b2c3d4e-0000-0000-0000-000000000001";

    private final ObjectMapper mapper = new ObjectMapper();
    private LambdaService lambdaService;
    private CustomResourceResponseStore store;
    private CfnResourceDispatcher provisioner;

    @BeforeEach
    void setUp() {
        lambdaService = mock(LambdaService.class);
        store = new CustomResourceResponseStore(new ProviderFrameworkDetector(lambdaService));
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.baseUrl()).thenReturn("http://floci:4566");

        provisioner = CfnProvisionerFixture.builder()
                .lambda(lambdaService)
                .objectMapper(mapper)
                .customResourceResponseStore(store)
                .reachableEndpoint(endpoint)
                .build();
    }

    private CloudFormationTemplateEngine engine() {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                STACK_ID, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
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
        // The real stack id, so a handler can DescribeStacks(event.StackId).
        assertEquals(STACK_ID, event.get("StackId").asText());
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
        assertEquals(STACK_ID, event.get("StackId").asText());
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
    void updateWithUnchangedResolvedPropertiesSkipsHandlerInvocation() {
        stubHandler("phys-123", Map.of("Greeting", "hello"));

        // Initial Create — stashes the resolved ResourceProperties on the resource.
        StackResource created = provisioner.provision("MyCr", "Custom::Test", props(),
                engine(), "us-east-1", "000000000000", "my-stack");
        assertEquals("phys-123", created.getPhysicalId());

        // "Update" with byte-identical resolved properties: real CloudFormation would not send
        // any request to the custom resource at all (UserGuide/template-custom-resources-sns.md:
        // "During a stack update, if no changes are made to a custom resource, CloudFormation
        // will not send any requests to it.").
        StackResource updated = provisioner.provision("MyCr", "Custom::Test", props(),
                engine(), "us-east-1", "000000000000", "my-stack",
                created.getPhysicalId(), created.getAttributes());

        verify(lambdaService, times(1)).invoke(any(), eq(SERVICE_TOKEN), any(),
                eq(InvocationType.RequestResponse));
        assertEquals("CREATE_COMPLETE", updated.getStatus());
        assertEquals("phys-123", updated.getPhysicalId());
        assertEquals("hello", updated.getAttributes().get("Greeting"));
    }

    @Test
    void updateWithChangedResolvedPropertiesStillInvokesHandler() {
        stubHandler("phys-123", Map.of("Greeting", "hello"));

        StackResource created = provisioner.provision("MyCr", "Custom::Test", props(),
                engine(), "us-east-1", "000000000000", "my-stack");

        ObjectNode changedProps = props();
        changedProps.put("Message", "bye");
        provisioner.provision("MyCr", "Custom::Test", changedProps,
                engine(), "us-east-1", "000000000000", "my-stack",
                created.getPhysicalId(), created.getAttributes());

        verify(lambdaService, times(2)).invoke(any(), eq(SERVICE_TOKEN), any(),
                eq(InvocationType.RequestResponse));
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
        assertEquals(STACK_ID, deleteEvent.get("StackId").asText());
        assertEquals("phys-123", deleteEvent.get("PhysicalResourceId").asText());
        assertEquals("hi", deleteEvent.get("ResourceProperties").get("Message").asText());
    }

    @Test
    void resourceOutsideAStackGetsOneStackIdForCreateAndDelete() {
        stubHandler("phys-123", Map.of());
        // Cloud Control's engine has no stack, so its stack id is not an ARN.
        CloudFormationTemplateEngine standalone = CloudFormationTemplateEngine.standalone(
                "000000000000", "us-east-1", "cloudcontrol", mapper, null);
        StackResource r = provisioner.provision("resource", "Custom::Test", props(),
                standalone, "us-east-1", "000000000000", "cloudcontrol");

        provisioner.delete(r, "us-east-1");

        List<JsonNode> events = capturedEvents(2);
        String createStackId = events.get(0).get("StackId").asText();
        assertTrue(AwsArnUtils.isArnFor(createStackId, "cloudformation"), createStackId);
        assertEquals(createStackId, events.get(1).get("StackId").asText());
    }

    @Test
    void deleteOfAResourceWithNoRecordedStackIdStillSendsAStackArn() {
        stubHandler("phys-123", Map.of());
        // Stashed by a release that did not record the stack id.
        StackResource legacy = new StackResource();
        legacy.setLogicalId("MyCr");
        legacy.setResourceType("Custom::Test");
        legacy.setPhysicalId("phys-123");
        legacy.getAttributes().put("__FlociServiceToken", SERVICE_TOKEN);
        legacy.getAttributes().put("__FlociResourceProperties", props().toString());

        provisioner.delete(legacy, "us-east-1");

        String stackId = capturedEvents(1).get(0).get("StackId").asText();
        assertTrue(AwsArnUtils.isArnFor(stackId, "cloudformation"), stackId);
    }

    private List<JsonNode> capturedEvents(int count) {
        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService, times(count)).invoke(any(), eq(SERVICE_TOKEN), payloads.capture(),
                eq(InvocationType.RequestResponse));
        List<JsonNode> events = new ArrayList<>();
        for (byte[] payload : payloads.getAllValues()) {
            events.add(readEvent(() -> payload));
        }
        return events;
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
