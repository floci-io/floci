package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnRollback;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            "arn:aws:cloudformation:us-east-1:000000000000:stack/my-stack/0f6b3c2e-7d41-4a5e-9c1b-2e8f4a6d1b37";

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
        return engine("stack/id");
    }

    private CloudFormationTemplateEngine engine(String stackId) {
        return new CloudFormationTemplateEngine("000000000000", "us-east-1", "my-stack",
                stackId, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), mapper,
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
        respond(event -> success(physicalId, data));
    }

    /** Stubs the Lambda to PUT, for each event it is sent, the response {@code answer} builds. */
    private void respond(Function<JsonNode, ObjectNode> answer) {
        when(lambdaService.invoke(any(), eq(SERVICE_TOKEN), any(), eq(InvocationType.RequestResponse)))
                .thenAnswer(inv -> {
                    JsonNode event = mapper.readTree((byte[]) inv.getArgument(2));
                    String responseUrl = event.get("ResponseURL").asText();
                    String token = responseUrl.substring(responseUrl.lastIndexOf('/') + 1);
                    store.complete(token, answer.apply(event));
                    return new InvokeResult(200, null, "null".getBytes(), null, "req-1");
                });
    }

    private ObjectNode success(String physicalId, Map<String, String> data) {
        ObjectNode response = mapper.createObjectNode();
        response.put("Status", "SUCCESS");
        response.put("PhysicalResourceId", physicalId);
        ObjectNode dataNode = response.putObject("Data");
        data.forEach(dataNode::put);
        return response;
    }

    private ObjectNode failed(String reason) {
        ObjectNode response = mapper.createObjectNode();
        response.put("Status", "FAILED");
        response.put("Reason", reason);
        return response;
    }

    private StackResource create() {
        return provisioner.provision("MyCr", "Custom::Test", props(),
                engine(STACK_ID), "us-east-1", "000000000000", "my-stack");
    }

    private StackResource update(StackResource current, String message) {
        ObjectNode changed = props();
        changed.put("Message", message);
        return provisioner.provision("MyCr", "Custom::Test", changed,
                engine(STACK_ID), "us-east-1", "000000000000", "my-stack",
                current.getPhysicalId(), current.getAttributes());
    }

    private static String message(JsonNode event, String properties) {
        return event.path(properties).path("Message").asText(null);
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
        assertEquals("phys-123", deleteEvent.get("PhysicalResourceId").asText());
        assertEquals("hi", deleteEvent.get("ResourceProperties").get("Message").asText());
    }

    @Test
    void createAndUpdateEventsCarryTheStacksOwnStackId() {
        stubHandler("phys-123", Map.of());

        StackResource created = provisioner.provision("MyCr", "Custom::Test", props(),
                engine(STACK_ID), "us-east-1", "000000000000", "my-stack");
        ObjectNode changedProps = props();
        changedProps.put("Message", "bye");
        provisioner.provision("MyCr", "Custom::Test", changedProps,
                engine(STACK_ID), "us-east-1", "000000000000", "my-stack",
                created.getPhysicalId(), created.getAttributes());

        List<JsonNode> events = capturedEvents(2);
        assertEquals("Create", events.get(0).get("RequestType").asText());
        assertEquals(STACK_ID, events.get(0).get("StackId").asText());
        assertEquals("Update", events.get(1).get("RequestType").asText());
        assertEquals(STACK_ID, events.get(1).get("StackId").asText());
    }

    @Test
    void deleteEventCarriesTheStackIdTheResourceWasCreatedIn() {
        stubHandler("phys-123", Map.of());
        StackResource r = provisioner.provision("MyCr", "Custom::Test", props(),
                engine(STACK_ID), "us-east-1", "000000000000", "my-stack");

        provisioner.delete(r, "us-east-1");

        List<JsonNode> events = capturedEvents(2);
        assertEquals("Delete", events.get(1).get("RequestType").asText());
        assertEquals(STACK_ID, events.get(1).get("StackId").asText());
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

    @Test
    void changedUpdateRecordsTheRollbackSnapshotBeforeTheHandlerRuns() {
        respond(event -> "Update".equals(event.get("RequestType").asText())
                ? failed("boom") : success("phys-123", Map.of()));

        StackResource updated = update(create(), "bye");

        assertEquals("CREATE_FAILED", updated.getStatus());
        assertTrue(updated.getAttributes().containsKey(CfnRollback.CUSTOM_RESOURCE_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackUpdateSendsTheOldPropertiesBackToTheSamePhysicalId() {
        respond(event -> {
            String message = message(event, "ResourceProperties");
            return success("phys-123", "bye".equals(message)
                    ? Map.of("Greeting", message, "Farewell", "yes")
                    : Map.of("Greeting", message));
        });
        StackResource created = create();
        String createdStash = created.getAttributes().get("__FlociResourceProperties");
        StackResource updated = update(created, "bye");
        List<String> progress = new ArrayList<>();

        assertTrue(provisioner.rollbackUpdate(updated, event -> progress.add(event.getResourceStatus())));

        JsonNode rollback = capturedEvents(3).get(2);
        assertEquals("Update", rollback.get("RequestType").asText());
        assertEquals("phys-123", rollback.get("PhysicalResourceId").asText());
        assertEquals("hi", message(rollback, "ResourceProperties"));
        assertEquals("bye", message(rollback, "OldResourceProperties"));
        assertEquals(STACK_ID, rollback.get("StackId").asText());
        assertEquals(List.of("UPDATE_IN_PROGRESS"), progress);
        assertEquals("phys-123", updated.getPhysicalId());
        assertEquals(createdStash, updated.getAttributes().get("__FlociResourceProperties"));
        assertEquals("hi", updated.getAttributes().get("Greeting"));
        assertFalse(updated.getAttributes().containsKey("Farewell"));
        assertFalse(updated.getAttributes().containsKey(CfnRollback.CUSTOM_RESOURCE_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackUpdateThatTheHandlerFailsThrowsItsReasonAndKeepsTheSnapshot() {
        respond(event -> "bye".equals(message(event, "OldResourceProperties"))
                ? failed("cannot undo") : success("phys-123", Map.of()));
        StackResource updated = update(create(), "bye");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.rollbackUpdate(updated, event -> { }));

        assertTrue(failure.getMessage().contains("cannot undo"), failure.getMessage());
        assertTrue(updated.getAttributes().containsKey(CfnRollback.CUSTOM_RESOURCE_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void retryOfAFailedRollbackSendsTheSameUpdateAgain() {
        respond(event -> "bye".equals(message(event, "OldResourceProperties"))
                ? failed("cannot undo") : success("phys-123", Map.of()));
        StackResource updated = update(create(), "bye");
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(updated, event -> { }));

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(updated, event -> { }));

        JsonNode retry = capturedEvents(4).get(3);
        assertEquals("Update", retry.get("RequestType").asText());
        assertEquals("hi", message(retry, "ResourceProperties"));
        assertEquals("bye", message(retry, "OldResourceProperties"));
    }

    @Test
    void deleteAfterAFailedRollbackCarriesTheOldProperties() {
        // AWS treats the rollback target as the resource's properties even though the handler failed it.
        respond(event -> "bye".equals(message(event, "OldResourceProperties"))
                ? failed("cannot undo") : success("phys-123", Map.of()));
        StackResource updated = update(create(), "bye");
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(updated, event -> { }));

        provisioner.delete(updated, "us-east-1");

        JsonNode delete = capturedEvents(4).get(3);
        assertEquals("Delete", delete.get("RequestType").asText());
        assertEquals("phys-123", delete.get("PhysicalResourceId").asText());
        assertEquals("hi", message(delete, "ResourceProperties"));
    }

    @Test
    void failedUpdateIsRetainedForRollbackOnlyWhenItReachedTheHandler() {
        respond(event -> "bye".equals(message(event, "ResourceProperties"))
                ? failed("forced failure") : success("phys-123", Map.of()));
        StackResource created = create();

        StackResource failed = update(created, "bye");

        assertEquals("CREATE_FAILED", failed.getStatus());
        assertTrue(provisioner.retainsFailedUpdateState(failed));
        assertFalse(provisioner.retainsFailedUpdateState(created));
    }

    @Test
    void updateRejectedBeforeDispatchIsNotRetainedForRollback() {
        stubHandler("phys-123", Map.of());
        StackResource created = create();
        ObjectNode invalidToken = props();
        invalidToken.put("ServiceToken", "arn:aws:lambda:us-east-1:000000000000:layer:NotAFunction");

        StackResource failed = provisioner.provision("MyCr", "Custom::Test", invalidToken,
                engine(STACK_ID), "us-east-1", "000000000000", "my-stack",
                created.getPhysicalId(), created.getAttributes());

        assertEquals("CREATE_FAILED", failed.getStatus());
        assertTrue(failed.getStatusReason().contains("ARN resource type must be 'function'"),
                failed.getStatusReason());
        assertFalse(provisioner.retainsFailedUpdateState(failed));
        verify(lambdaService, times(1)).invoke(any(), any(), any(), any());
    }

    @Test
    void rollbackOfAnUpdateTheHandlerFailedSendsTheAttemptedPropertiesAsOld() {
        respond(event -> "bye".equals(message(event, "ResourceProperties"))
                ? failed("forced failure") : success("phys-123", Map.of()));
        StackResource created = create();
        String createdStash = created.getAttributes().get("__FlociResourceProperties");
        StackResource failed = update(created, "bye");

        assertTrue(provisioner.rollbackUpdate(failed, event -> { }));

        JsonNode rollback = capturedEvents(3).get(2);
        assertEquals("Update", rollback.get("RequestType").asText());
        assertEquals("phys-123", rollback.get("PhysicalResourceId").asText());
        assertEquals("hi", message(rollback, "ResourceProperties"));
        assertEquals("bye", message(rollback, "OldResourceProperties"));
        assertEquals("phys-123", failed.getPhysicalId());
        assertEquals(createdStash, failed.getAttributes().get("__FlociResourceProperties"));
        assertFalse(failed.getAttributes().containsKey(CfnRollback.CUSTOM_RESOURCE_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackOfAReplacingUpdateDeletesTheReplacementAndSendsNoUpdate() {
        respond(event -> {
            String message = message(event, "ResourceProperties");
            return success("bye".equals(message) ? "phys-456" : "phys-123", Map.of("Greeting", message));
        });
        StackResource created = create();
        String createdStash = created.getAttributes().get("__FlociResourceProperties");
        StackResource updated = update(created, "bye");
        assertEquals("phys-456", updated.getPhysicalId());

        assertTrue(provisioner.rollbackUpdate(updated, event -> { }));

        JsonNode delete = capturedEvents(3).get(2);
        assertEquals("Delete", delete.get("RequestType").asText());
        assertEquals("phys-456", delete.get("PhysicalResourceId").asText());
        assertEquals("bye", message(delete, "ResourceProperties"));
        assertEquals("phys-123", updated.getPhysicalId());
        assertEquals(createdStash, updated.getAttributes().get("__FlociResourceProperties"));
        assertEquals("hi", updated.getAttributes().get("Greeting"));
        assertFalse(updated.getAttributes().containsKey(CfnRollback.CUSTOM_RESOURCE_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollbackResponseNamingAnotherPhysicalIdIsTakenAndTheIdSentIsDeleted() {
        respond(event -> success("bye".equals(message(event, "OldResourceProperties"))
                ? "phys-789" : "phys-123", Map.of()));
        StackResource updated = update(create(), "bye");

        assertTrue(provisioner.rollbackUpdate(updated, event -> { }));

        List<JsonNode> events = capturedEvents(4);
        assertEquals("Update", events.get(2).get("RequestType").asText());
        JsonNode delete = events.get(3);
        assertEquals("Delete", delete.get("RequestType").asText());
        assertEquals("phys-123", delete.get("PhysicalResourceId").asText());
        assertEquals("bye", message(delete, "ResourceProperties"));
        assertEquals("phys-789", updated.getPhysicalId());
    }

    @Test
    void rollbackAfterAnUpdateThatSentNothingSendsNothing() {
        stubHandler("phys-123", Map.of());
        StackResource changed = update(create(), "bye");
        StackResource unchanged = update(changed, "bye");

        assertTrue(provisioner.rollbackUpdate(unchanged, event -> { }));

        capturedEvents(2);
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
