package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.EventSourceMapping;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaEventSourceMappingCfnProvisionerTest {

    private static final List<String> CLEARABLE_OPTIONS = List.of(
            "MaximumBatchingWindowInSeconds", "MaximumRetryAttempts", "MaximumRecordAgeInSeconds",
            "BisectBatchOnFunctionError", "FilterCriteria", "DestinationConfig", "ScalingConfig");

    private final LambdaService lambdaService = mock(LambdaService.class);
    private final LambdaEventSourceMappingCfnProvisioner provisioner =
            new LambdaEventSourceMappingCfnProvisioner(lambdaService);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(false, null);
    }

    private ProvisionContext ctx(boolean isUpdate, String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            if (node == null) {
                return null;
            }
            if (node.isObject() && node.has("Ref")) {
                return "resolved-" + node.get("Ref").asText();
            }
            if (node.isObject() && node.has("Fn::GetAtt")) {
                JsonNode getAtt = node.get("Fn::GetAtt");
                return "resolved-" + getAtt.get(0).asText() + "." + getAtt.get(1).asText();
            }
            return node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> resolveMockNode((JsonNode) inv.getArgument(0), engine));

        ProvisionContext context = mock(ProvisionContext.class);
        when(context.engine()).thenReturn(engine);
        when(context.region()).thenReturn("us-east-1");
        when(context.accountId()).thenReturn("000000000000");
        when(context.stackName()).thenReturn("test-stack");
        when(context.isUpdate()).thenReturn(isUpdate);
        when(context.priorPhysicalId()).thenReturn(priorPhysicalId);

        when(context.resolveOptional(any(), anyString())).thenAnswer(inv -> {
            JsonNode props = inv.getArgument(0);
            String propName = inv.getArgument(1);
            return (props != null && props.has(propName)) ? engine.resolve(props.get(propName)) : null;
        });
        when(context.resolveStringList(any(), anyString())).thenAnswer(inv -> {
            JsonNode props = inv.getArgument(0);
            String propName = inv.getArgument(1);
            if (props == null || !props.has(propName) || !props.get(propName).isArray()) {
                return List.of();
            }
            java.util.List<String> list = new java.util.ArrayList<>();
            for (JsonNode elem : props.get(propName)) {
                list.add(elem.isTextual() ? elem.asText() : engine.resolve(elem));
            }
            return list;
        });

        return context;
    }

    private JsonNode resolveMockNode(JsonNode node, CloudFormationTemplateEngine engine) {
        if (node == null || node.isNull() || node.isMissingNode() || node.isValueNode()) {
            return node;
        }
        if (node.isObject()) {
            if (node.has("Ref") || node.has("Fn::GetAtt")) {
                return mapper.getNodeFactory().textNode(engine.resolve(node));
            }
            ObjectNode resolved = mapper.createObjectNode();
            node.fields().forEachRemaining(e -> resolved.set(e.getKey(), resolveMockNode(e.getValue(), engine)));
            return resolved;
        }
        if (node.isArray()) {
            var arr = mapper.createArrayNode();
            for (JsonNode item : node) {
                arr.add(resolveMockNode(item, engine));
            }
            return arr;
        }
        return node;
    }

    @Test
    void resourceTypesReturnsEventSourceMapping() {
        assertEquals(Set.of("AWS::Lambda::EventSourceMapping"), provisioner.resourceTypes());
    }

    @Test
    void provisionCreatesConventionalEventSourceMapping() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("EventSourceArn", "arn:aws:sqs:us-east-1:000000000000:my-queue");
        props.put("BatchSize", "5");
        props.put("Enabled", "true");
        props.put("StartingPosition", "TRIM_HORIZON");

        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("test-esm-uuid-1234");
        when(lambdaService.createEventSourceMapping(eq("us-east-1"), any())).thenReturn(esm);

        provisioner.provision(r, props, ctx());

        assertEquals("test-esm-uuid-1234", r.getPhysicalId());
        assertEquals("test-esm-uuid-1234", r.getAttributes().get("Id"));
        assertEquals("arn:aws:lambda:us-east-1:000000000000:event-source-mapping:test-esm-uuid-1234",
                r.getAttributes().get("EventSourceMappingArn"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(lambdaService).createEventSourceMapping(eq("us-east-1"), captor.capture());
        Map<String, Object> req = captor.getValue();
        assertEquals("my-function", req.get("FunctionName"));
        assertEquals("arn:aws:sqs:us-east-1:000000000000:my-queue", req.get("EventSourceArn"));
        assertEquals(5, req.get("BatchSize"));
        assertEquals(true, req.get("Enabled"));
        assertEquals("TRIM_HORIZON", req.get("StartingPosition"));
    }

    @Test
    void provisionResolvesNestedIntrinsicsForKafka() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-kafka-function");

        ObjectNode selfManaged = mapper.createObjectNode();
        ObjectNode endpoints = mapper.createObjectNode();
        endpoints.putObject("KAFKA_BOOTSTRAP_SERVERS");
        // Nested Ref in Endpoints
        ObjectNode refNode = mapper.createObjectNode();
        refNode.put("Ref", "MyBootstrapParam");
        endpoints.set("KAFKA_BOOTSTRAP_SERVERS", mapper.createArrayNode().add(refNode));
        selfManaged.set("Endpoints", endpoints);
        props.set("SelfManagedEventSource", selfManaged);

        props.set("Topics", mapper.createArrayNode().add("my-topic"));

        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("kafka-esm-uuid");
        when(lambdaService.createEventSourceMapping(eq("us-east-1"), any())).thenReturn(esm);

        provisioner.provision(r, props, ctx());

        assertEquals("kafka-esm-uuid", r.getPhysicalId());
        assertEquals("kafka-esm-uuid", r.getAttributes().get("Id"));
        assertEquals("arn:aws:lambda:us-east-1:000000000000:event-source-mapping:kafka-esm-uuid",
                r.getAttributes().get("EventSourceMappingArn"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(lambdaService).createEventSourceMapping(eq("us-east-1"), captor.capture());
        Map<String, Object> req = captor.getValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) req.get("SelfManagedEventSource");
        assertNotNull(source);
        @SuppressWarnings("unchecked")
        Map<String, Object> eps = (Map<String, Object>) source.get("Endpoints");
        assertNotNull(eps);
        @SuppressWarnings("unchecked")
        List<String> servers = (List<String>) eps.get("KAFKA_BOOTSTRAP_SERVERS");
        assertEquals(List.of("resolved-MyBootstrapParam"), servers);
    }

    @Test
    void provisionUpdatesExistingEventSourceMapping() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");
        r.setPhysicalId("existing-esm-uuid");
        r.getAttributes().put("Id", "existing-esm-uuid");

        EventSourceMapping existing = new EventSourceMapping();
        existing.setUuid("existing-esm-uuid");
        existing.setFunctionName("my-function");
        when(lambdaService.getEventSourceMapping("existing-esm-uuid")).thenReturn(existing);

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("BatchSize", "20");
        props.put("Enabled", "false");

        provisioner.provision(r, props, ctx(true, "existing-esm-uuid"));

        assertEquals("existing-esm-uuid", r.getPhysicalId());
        assertEquals("existing-esm-uuid", r.getAttributes().get("Id"));
        assertEquals("arn:aws:lambda:us-east-1:000000000000:event-source-mapping:existing-esm-uuid",
                r.getAttributes().get("EventSourceMappingArn"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(lambdaService).updateEventSourceMapping(eq("existing-esm-uuid"), captor.capture());
        verify(lambdaService, never()).createEventSourceMapping(anyString(), any());
        Map<String, Object> req = captor.getValue();
        assertEquals(20, req.get("BatchSize"));
        assertEquals(false, req.get("Enabled"));
    }

    @Test
    void deleteDeletesEventSourceMapping() {
        provisioner.delete("AWS::Lambda::EventSourceMapping", "uuid-to-delete", "us-east-1");
        verify(lambdaService).deleteEventSourceMapping("uuid-to-delete");
    }

    @Test
    void deleteToleratesResourceNotFound() {
        doThrow(new AwsException("ResourceNotFoundException", "ESM not found", 404))
                .when(lambdaService).deleteEventSourceMapping("absent-uuid");

        provisioner.delete("AWS::Lambda::EventSourceMapping", "absent-uuid", "us-east-1");
        verify(lambdaService).deleteEventSourceMapping("absent-uuid");
    }

    @Test
    void deletePropagatesNonResourceNotFoundExceptions() {
        doThrow(new AwsException("InternalError", "boom", 500))
                .when(lambdaService).deleteEventSourceMapping("broken-uuid");

        assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::Lambda::EventSourceMapping", "broken-uuid", "us-east-1"));
    }

    @Test
    void provisionRejectsMissingFunctionName() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");

        AwsException ex = assertThrows(AwsException.class,
                () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("Property FunctionName is required for AWS::Lambda::EventSourceMapping", ex.getMessage());
    }

    @Test
    void provisionRejectsWrongResourceType() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::Function");
        assertThrows(IllegalStateException.class, () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
    }

    @Test
    void provisionRejectsInvalidBatchSize() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("BatchSize", "not-a-number");

        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("Value of property BatchSize must be an integer.", ex.getMessage());
    }

    private Map<String, Object> provisionAndCaptureCreateRequest(ObjectNode props) {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");
        EventSourceMapping esm = new EventSourceMapping();
        esm.setUuid("options-esm-uuid");
        when(lambdaService.createEventSourceMapping(eq("us-east-1"), any())).thenReturn(esm);

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(lambdaService).createEventSourceMapping(eq("us-east-1"), captor.capture());
        return captor.getValue();
    }

    @Test
    void provisionForwardsBatchingRetryAndStructureOptionsOnCreate() {
        String pattern = "{\"body\":{\"kind\":[\"order\"]}}";
        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("EventSourceArn", "arn:aws:sqs:us-east-1:000000000000:my-queue");
        props.put("MaximumBatchingWindowInSeconds", 10);
        props.put("MaximumRetryAttempts", 3);
        props.put("MaximumRecordAgeInSeconds", 120);
        props.put("BisectBatchOnFunctionError", true);
        props.putObject("FilterCriteria").putArray("Filters").addObject().put("Pattern", pattern);
        props.putObject("DestinationConfig").putObject("OnFailure")
                .put("Destination", "arn:aws:sqs:us-east-1:000000000000:my-dlq");
        props.putObject("ScalingConfig").put("MaximumConcurrency", 5);

        Map<String, Object> req = provisionAndCaptureCreateRequest(props);

        assertEquals(Integer.valueOf(10), req.get("MaximumBatchingWindowInSeconds"));
        assertEquals(Integer.valueOf(3), req.get("MaximumRetryAttempts"));
        assertEquals(Integer.valueOf(120), req.get("MaximumRecordAgeInSeconds"));
        assertEquals(Boolean.TRUE, req.get("BisectBatchOnFunctionError"));
        assertEquals(Map.of("Filters", List.of(Map.of("Pattern", pattern))), req.get("FilterCriteria"));
        assertEquals(Map.of("OnFailure", Map.of("Destination", "arn:aws:sqs:us-east-1:000000000000:my-dlq")),
                req.get("DestinationConfig"));
        assertEquals(Map.of("MaximumConcurrency", 5), req.get("ScalingConfig"));
    }

    @Test
    void provisionResolvesGetAttInDestinationConfig() {
        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        ObjectNode getAtt = mapper.createObjectNode();
        getAtt.set("Fn::GetAtt", mapper.createArrayNode().add("MyDlq").add("Arn"));
        props.putObject("DestinationConfig").putObject("OnFailure").set("Destination", getAtt);

        Map<String, Object> req = provisionAndCaptureCreateRequest(props);

        assertEquals(Map.of("OnFailure", Map.of("Destination", "resolved-MyDlq.Arn")), req.get("DestinationConfig"));
    }

    @Test
    void provisionOmitsAbsentOptionsOnCreate() {
        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("EventSourceArn", "arn:aws:sqs:us-east-1:000000000000:my-queue");

        Map<String, Object> req = provisionAndCaptureCreateRequest(props);

        for (String option : CLEARABLE_OPTIONS) {
            assertFalse(req.containsKey(option), option + " must not be sent when absent");
        }
    }

    @Test
    void provisionTreatsNoValueStructureAsAbsentOnCreate() {
        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("DestinationConfig", "");

        Map<String, Object> req = provisionAndCaptureCreateRequest(props);

        assertFalse(req.containsKey("DestinationConfig"));
    }

    @Test
    void provisionClearsNoValueStructureOnUpdate() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");
        r.setPhysicalId("existing-esm-uuid");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("DestinationConfig", "");

        provisioner.provision(r, props, ctx(true, "existing-esm-uuid"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(lambdaService).updateEventSourceMapping(eq("existing-esm-uuid"), captor.capture());
        assertTrue(captor.getValue().containsKey("DestinationConfig"));
        assertNull(captor.getValue().get("DestinationConfig"));
    }

    @Test
    void provisionTreatsNoValueScalarsAsAbsentOnCreate() {
        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("MaximumRetryAttempts", "");
        props.put("BisectBatchOnFunctionError", "");

        Map<String, Object> req = provisionAndCaptureCreateRequest(props);

        assertFalse(req.containsKey("MaximumRetryAttempts"));
        assertFalse(req.containsKey("BisectBatchOnFunctionError"));
    }

    @Test
    void provisionRejectsWhitespaceBatchSize() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("BatchSize", " ");

        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("Value of property BatchSize must be an integer.", ex.getMessage());
    }

    @Test
    void provisionRejectsNonBooleanBisectBatchOnFunctionError() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("BisectBatchOnFunctionError", "yes");

        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("BisectBatchOnFunctionError must be a boolean", ex.getMessage());
    }

    @Test
    void provisionRejectsNonObjectDestinationConfig() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("DestinationConfig", "arn:aws:sqs:us-east-1:000000000000:my-dlq");

        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("DestinationConfig must be an object", ex.getMessage());
    }

    @Test
    void provisionKeepsWhitespaceMemberForLambdaValidation() {
        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.putObject("DestinationConfig").put("OnFailure", " ");

        Map<String, Object> req = provisionAndCaptureCreateRequest(props);

        assertEquals(Map.of("OnFailure", " "), req.get("DestinationConfig"));
    }

    @Test
    void provisionDropsNestedNoValueFromDestinationConfig() {
        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.putObject("DestinationConfig").put("OnFailure", "");

        Map<String, Object> req = provisionAndCaptureCreateRequest(props);

        // LambdaService treats a DestinationConfig without OnFailure as no destination.
        assertEquals(Map.of(), req.get("DestinationConfig"));
    }

    @Test
    void provisionDropsNoValueFilterElement() {
        String pattern = "{\"body\":{\"kind\":[\"order\"]}}";
        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        ArrayNode filters = props.putObject("FilterCriteria").putArray("Filters");
        filters.addObject().put("Pattern", pattern);
        filters.add("");

        Map<String, Object> req = provisionAndCaptureCreateRequest(props);

        assertEquals(Map.of("Filters", List.of(Map.of("Pattern", pattern))), req.get("FilterCriteria"));
    }

    @Test
    void provisionDropsNestedNoValueMaximumConcurrency() {
        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("EventSourceArn", "arn:aws:sqs:us-east-1:000000000000:my-queue");
        props.putObject("ScalingConfig").put("MaximumConcurrency", "");

        Map<String, Object> req = provisionAndCaptureCreateRequest(props);

        // LambdaService treats an empty ScalingConfig on an SQS mapping as clearing the cap.
        assertEquals(Map.of(), req.get("ScalingConfig"));
    }

    @Test
    void provisionCoercesTextMaximumConcurrencyToInteger() {
        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.putObject("ScalingConfig").put("MaximumConcurrency", "5");

        Map<String, Object> req = provisionAndCaptureCreateRequest(props);

        assertEquals(Map.of("MaximumConcurrency", 5), req.get("ScalingConfig"));
    }

    @Test
    void provisionRejectsInvalidMaximumConcurrency() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.putObject("ScalingConfig").put("MaximumConcurrency", "many");

        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("Value of property ScalingConfig.MaximumConcurrency must be an integer.", ex.getMessage());
    }

    @Test
    void provisionRejectsInvalidMaximumRetryAttempts() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("MaximumRetryAttempts", "not-a-number");

        AwsException ex = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("Value of property MaximumRetryAttempts must be an integer.", ex.getMessage());
    }

    @Test
    void provisionUpdateClearsRemovedBatchingRetryAndStructureOptions() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");
        r.setPhysicalId("existing-esm-uuid");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");

        provisioner.provision(r, props, ctx(true, "existing-esm-uuid"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(lambdaService).updateEventSourceMapping(eq("existing-esm-uuid"), captor.capture());
        Map<String, Object> req = captor.getValue();
        for (String option : CLEARABLE_OPTIONS) {
            assertTrue(req.containsKey(option), option + " must be sent so the update clears it");
            assertNull(req.get(option), option + " must be cleared");
        }
    }

    @Test
    void provisionUpdatesClearsSourceAccessConfigurationsWhenRemoved() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");
        r.setPhysicalId("existing-esm-uuid");

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");

        provisioner.provision(r, props, ctx(true, "existing-esm-uuid"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(lambdaService).updateEventSourceMapping(eq("existing-esm-uuid"), captor.capture());
        Map<String, Object> req = captor.getValue();
        assertTrue(req.containsKey("SourceAccessConfigurations"));
        assertNull(req.get("SourceAccessConfigurations"));
    }

    @Test
    void provisionRejectsUpdatingSelfManagedEventSource() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");
        r.setPhysicalId("existing-kafka-esm-uuid");

        EventSourceMapping existing = new EventSourceMapping();
        existing.setUuid("existing-kafka-esm-uuid");
        existing.setSelfManagedEventSource(Map.of(
                "Endpoints", Map.of("KAFKA_BOOTSTRAP_SERVERS", List.of("localhost:9092"))
        ));
        when(lambdaService.getEventSourceMapping("existing-kafka-esm-uuid")).thenReturn(existing);

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-kafka-function");
        ObjectNode selfManaged = mapper.createObjectNode();
        ObjectNode endpoints = mapper.createObjectNode();
        endpoints.set("KAFKA_BOOTSTRAP_SERVERS", mapper.createArrayNode().add("localhost:9093"));
        selfManaged.set("Endpoints", endpoints);
        props.set("SelfManagedEventSource", selfManaged);

        AwsException ex = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props, ctx(true, "existing-kafka-esm-uuid")));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("Updating SelfManagedEventSource requires resource replacement, which is not supported.", ex.getMessage());
    }

    @Test
    void provisionRejectsUpdatingEventSourceArn() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");
        r.setPhysicalId("existing-esm-uuid");

        EventSourceMapping existing = new EventSourceMapping();
        existing.setUuid("existing-esm-uuid");
        existing.setEventSourceArn("arn:aws:sqs:us-east-1:000000000000:old-queue");
        when(lambdaService.getEventSourceMapping("existing-esm-uuid")).thenReturn(existing);

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("EventSourceArn", "arn:aws:sqs:us-east-1:000000000000:new-queue");

        AwsException ex = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props, ctx(true, "existing-esm-uuid")));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("Updating EventSourceArn requires resource replacement, which is not supported.", ex.getMessage());
    }

    @Test
    void provisionRejectsUpdatingStartingPosition() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");
        r.setPhysicalId("existing-esm-uuid");

        EventSourceMapping existing = new EventSourceMapping();
        existing.setUuid("existing-esm-uuid");
        existing.setStartingPosition("TRIM_HORIZON");
        when(lambdaService.getEventSourceMapping("existing-esm-uuid")).thenReturn(existing);

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("StartingPosition", "LATEST");

        AwsException ex = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props, ctx(true, "existing-esm-uuid")));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("Updating StartingPosition requires resource replacement, which is not supported.", ex.getMessage());
    }

    @Test
    void provisionRejectsUpdatingStartingPositionTimestamp() {
        StackResource r = new StackResource();
        r.setResourceType("AWS::Lambda::EventSourceMapping");
        r.setPhysicalId("existing-esm-uuid");

        EventSourceMapping existing = new EventSourceMapping();
        existing.setUuid("existing-esm-uuid");
        existing.setStartingPositionTimestamp(1000000000L);
        when(lambdaService.getEventSourceMapping("existing-esm-uuid")).thenReturn(existing);

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-function");
        props.put("StartingPositionTimestamp", "2000000");

        AwsException ex = assertThrows(AwsException.class,
                () -> provisioner.provision(r, props, ctx(true, "existing-esm-uuid")));
        assertEquals("ValidationError", ex.getErrorCode());
        assertEquals("Updating StartingPositionTimestamp requires resource replacement, which is not supported.", ex.getMessage());
    }
}
