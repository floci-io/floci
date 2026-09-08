package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.FunctionEventInvokeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Lambda::EventInvokeConfig}: the physical id is the schema's composite identifier,
 * a create keeps only the properties the template names, an update with the same identity goes
 * through the merge-style update call, and a changed identity is a replacement whose displaced
 * configuration is left to the cleanup record.
 */
class LambdaEventInvokeConfigCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String STACK = "my-stack";
    private static final String TYPE = "AWS::Lambda::EventInvokeConfig";

    private LambdaService lambda;
    private LambdaEventInvokeConfigCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        lambda = mock(LambdaService.class);
        provisioner = new LambdaEventInvokeConfigCfnProvisioner(lambda);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        // The service never answers null: a missing configuration throws. Tests that care about
        // the settings before an update stub their own.
        when(lambda.getEventInvokeConfig(anyString(), anyString(), anyString()))
                .thenReturn(config(2, 21600, null, null));
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("AsyncConfig");
        r.setResourceType(TYPE);
        return r;
    }

    private StackResource provision(String json, String priorPhysicalId) {
        StackResource r = resource();
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        provisioner.provision(r, props(json),
                new ProvisionContext(engine, REGION, ACCOUNT_ID, STACK, priorPhysicalId));
        return r;
    }

    @Test
    void servesOnlyTheEventInvokeConfigType() {
        assertEquals(Set.of(TYPE), provisioner.resourceTypes());
    }

    @Test
    void createPutsTheConfigurationAndJoinsTheIdentityWithAPipe() {
        StackResource r = provision("""
                {"FunctionName": "orders", "Qualifier": "$LATEST",
                 "MaximumRetryAttempts": 1, "MaximumEventAgeInSeconds": 300,
                 "DestinationConfig": {
                   "OnSuccess": {"Destination": "arn:aws:sqs:us-east-1:000000000000:ok"},
                   "OnFailure": {"Destination": "arn:aws:sqs:us-east-1:000000000000:dlq"}}}
                """, null);

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.captor();
        verify(lambda).putEventInvokeConfig(eq(REGION), eq("orders"), eq("$LATEST"), request.capture());
        verify(lambda, never()).updateEventInvokeConfig(anyString(), anyString(), anyString(), anyMap());
        assertEquals(1, request.getValue().get("MaximumRetryAttempts"));
        assertEquals(300, request.getValue().get("MaximumEventAgeInSeconds"));
        assertEquals(Map.of(
                "OnSuccess", Map.of("Destination", "arn:aws:sqs:us-east-1:000000000000:ok"),
                "OnFailure", Map.of("Destination", "arn:aws:sqs:us-east-1:000000000000:dlq")),
                request.getValue().get("DestinationConfig"));
        assertEquals("orders|$LATEST", r.getPhysicalId(), "Ref is FunctionName|Qualifier");
        assertTrue(r.getAttributes().isEmpty(), "the schema declares no Fn::GetAtt attributes");
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    /** Numbers arrive as strings after intrinsic resolution as often as they arrive as numbers. */
    @Test
    void numericSettingsGivenAsStringsAreParsed() {
        provision("""
                {"FunctionName": "orders", "Qualifier": "live",
                 "MaximumRetryAttempts": "0", "MaximumEventAgeInSeconds": "60"}
                """, null);

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.captor();
        verify(lambda).putEventInvokeConfig(eq(REGION), eq("orders"), eq("live"), request.capture());
        assertEquals(0, request.getValue().get("MaximumRetryAttempts"));
        assertEquals(60, request.getValue().get("MaximumEventAgeInSeconds"));
    }

    /** A setting the template leaves out is left out of the request; the service stores nothing for it. */
    @Test
    void omittedSettingsAreNotSent() {
        provision("""
                {"FunctionName": "orders", "Qualifier": "3"}
                """, null);

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.captor();
        verify(lambda).putEventInvokeConfig(eq(REGION), eq("orders"), eq("3"), request.capture());
        assertTrue(request.getValue().isEmpty(), "no setting means an empty request: " + request.getValue());
    }

    @Test
    void aDestinationSideThatIsOmittedIsNotSent() {
        provision("""
                {"FunctionName": "orders", "Qualifier": "live",
                 "DestinationConfig": {"OnFailure": {"Destination": "arn:aws:sns:us-east-1:000000000000:alerts"}}}
                """, null);

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.captor();
        verify(lambda).putEventInvokeConfig(eq(REGION), eq("orders"), eq("live"), request.capture());
        assertEquals(Map.of("OnFailure", Map.of("Destination", "arn:aws:sns:us-east-1:000000000000:alerts")),
                request.getValue().get("DestinationConfig"));
    }

    @Test
    void functionNameIsRequired() {
        AwsException e = assertThrows(AwsException.class, () -> provision("""
                {"Qualifier": "$LATEST"}
                """, null));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("FunctionName"), e.getMessage());
        verifyNoInteractions(lambda);
    }

    @Test
    void qualifierIsRequired() {
        AwsException e = assertThrows(AwsException.class, () -> provision("""
                {"FunctionName": "orders"}
                """, null));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("Qualifier"), e.getMessage());
        verifyNoInteractions(lambda);
    }

    /** The registry schema caps MaximumRetryAttempts at 0..2; CloudFormation rejects the template before any call. */
    @Test
    void retryAttemptsOutsideTheSchemaRangeAreRejectedBeforeAnyCall() {
        for (String value : new String[] {"-1", "3", "two"}) {
            AwsException e = assertThrows(AwsException.class, () -> provision("""
                    {"FunctionName": "orders", "Qualifier": "$LATEST", "MaximumRetryAttempts": "%s"}
                    """.formatted(value), null), value);
            assertEquals("ValidationError", e.getErrorCode(), value);
            assertTrue(e.getMessage().contains("MaximumRetryAttempts"), e.getMessage());
        }
        verifyNoInteractions(lambda);
    }

    /** MaximumEventAgeInSeconds is 60..21600 in the schema. */
    @Test
    void eventAgeOutsideTheSchemaRangeIsRejectedBeforeAnyCall() {
        for (String value : new String[] {"59", "21601", "1h"}) {
            AwsException e = assertThrows(AwsException.class, () -> provision("""
                    {"FunctionName": "orders", "Qualifier": "$LATEST", "MaximumEventAgeInSeconds": "%s"}
                    """.formatted(value), null), value);
            assertEquals("ValidationError", e.getErrorCode(), value);
            assertTrue(e.getMessage().contains("MaximumEventAgeInSeconds"), e.getMessage());
        }
        verifyNoInteractions(lambda);
    }

    @Test
    void theRangeBoundsThemselvesAreAccepted() {
        provision("""
                {"FunctionName": "orders", "Qualifier": "$LATEST",
                 "MaximumRetryAttempts": 2, "MaximumEventAgeInSeconds": 21600}
                """, null);

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.captor();
        verify(lambda).putEventInvokeConfig(eq(REGION), eq("orders"), eq("$LATEST"), request.capture());
        assertEquals(2, request.getValue().get("MaximumRetryAttempts"));
        assertEquals(21600, request.getValue().get("MaximumEventAgeInSeconds"));
    }

    @Test
    void aDestinationWithoutAnArnIsRejected() {
        AwsException e = assertThrows(AwsException.class, () -> provision("""
                {"FunctionName": "orders", "Qualifier": "$LATEST",
                 "DestinationConfig": {"OnFailure": {}}}
                """, null));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("Destination"), e.getMessage());
        verifyNoInteractions(lambda);
    }

    /** Same function and qualifier: the update path, applied through the merge-style update call. */
    @Test
    void anUpdateWithTheSameIdentityUpdatesInPlace() {
        when(lambda.getEventInvokeConfig(REGION, "orders", "$LATEST")).thenReturn(config(2, 21600, null, null));
        StackResource r = provision("""
                {"FunctionName": "orders", "Qualifier": "$LATEST", "MaximumRetryAttempts": 0}
                """, "orders|$LATEST");

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.captor();
        verify(lambda).updateEventInvokeConfig(eq(REGION), eq("orders"), eq("$LATEST"), request.capture());
        verify(lambda, never()).putEventInvokeConfig(anyString(), anyString(), anyString(), anyMap());
        assertEquals(Map.of("MaximumRetryAttempts", 0), request.getValue());
        assertEquals("orders|$LATEST", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertNull(provisioner.updateCleanupPhysicalId(r));
    }

    /** Both identifier parts are create-only: changing the qualifier creates a new configuration. */
    @Test
    void aChangedQualifierIsAReplacementThatLeavesThePriorToTheCleanup() {
        StackResource r = provision("""
                {"FunctionName": "orders", "Qualifier": "live", "MaximumRetryAttempts": 1}
                """, "orders|$LATEST");

        verify(lambda).putEventInvokeConfig(eq(REGION), eq("orders"), eq("live"), anyMap());
        verify(lambda, never()).updateEventInvokeConfig(anyString(), anyString(), anyString(), anyMap());
        verify(lambda, never()).deleteEventInvokeConfig(anyString(), anyString(), anyString());
        assertEquals("orders|live", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("orders|$LATEST", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void aChangedFunctionIsAReplacement() {
        StackResource r = provision("""
                {"FunctionName": "payments", "Qualifier": "$LATEST"}
                """, "orders|$LATEST");

        verify(lambda).putEventInvokeConfig(eq(REGION), eq("payments"), eq("$LATEST"), anyMap());
        assertEquals("payments|$LATEST", r.getPhysicalId());
        assertEquals("orders|$LATEST", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void completingAReplacementDeletesTheDisplacedConfiguration() {
        StackResource r = provision("""
                {"FunctionName": "orders", "Qualifier": "live"}
                """, "orders|$LATEST");

        UpdateCleanupResult result = provisioner.completeUpdate(r);

        assertTrue(result.applicable());
        assertTrue(result.complete());
        verify(lambda).deleteEventInvokeConfig(REGION, "orders", "$LATEST");
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void rollingBackAReplacementDeletesTheNewConfigurationAndRestoresThePriorId() {
        StackResource r = provision("""
                {"FunctionName": "orders", "Qualifier": "live"}
                """, "orders|$LATEST");

        assertTrue(provisioner.rollbackUpdate(r));

        verify(lambda).deleteEventInvokeConfig(REGION, "orders", "live");
        verify(lambda, never()).deleteEventInvokeConfig(REGION, "orders", "$LATEST");
        assertEquals("orders|$LATEST", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    /**
     * The service keys a configuration by the function's ARN, so a short name and the ARN address
     * one configuration: treating the change as a replacement would put over that configuration
     * and then delete it through the old name once the update committed.
     */
    @Test
    void aFunctionNameGivenAsTheArnOfTheSameFunctionUpdatesInPlace() {
        StackResource r = provision("""
                {"FunctionName": "arn:aws:lambda:us-east-1:000000000000:function:orders", "Qualifier": "$LATEST",
                 "MaximumRetryAttempts": 0}
                """, "orders|$LATEST");

        verify(lambda).updateEventInvokeConfig(eq(REGION),
                eq("arn:aws:lambda:us-east-1:000000000000:function:orders"), eq("$LATEST"), anyMap());
        verify(lambda, never()).putEventInvokeConfig(anyString(), anyString(), anyString(), anyMap());
        assertEquals("orders|$LATEST", r.getPhysicalId(), "the prior id stays so nothing is recorded as displaced");
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertNull(provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void theSameNameInAnotherRegionIsAnotherTarget() {
        StackResource r = provision("""
                {"FunctionName": "arn:aws:lambda:eu-west-1:000000000000:function:orders", "Qualifier": "$LATEST"}
                """, "arn:aws:lambda:us-east-1:000000000000:function:orders|$LATEST");

        verify(lambda).putEventInvokeConfig(eq(REGION),
                eq("arn:aws:lambda:eu-west-1:000000000000:function:orders"), eq("$LATEST"), anyMap());
        assertTrue(provisioner.hasReplacementUpdate(r));
    }

    /** The settings before an in-place update are kept, and a failed stack update puts every one back. */
    @Test
    void anInPlaceUpdateIsRolledBackFromTheSettingsItReplaced() {
        when(lambda.getEventInvokeConfig(REGION, "orders", "$LATEST")).thenReturn(config(2, 21600,
                "arn:aws:sqs:us-east-1:000000000000:ok", "arn:aws:sqs:us-east-1:000000000000:dlq"));

        StackResource r = provision("""
                {"FunctionName": "orders", "Qualifier": "$LATEST", "MaximumRetryAttempts": 0,
                 "DestinationConfig": {"OnFailure": {"Destination": "arn:aws:sns:us-east-1:000000000000:alerts"}}}
                """, "orders|$LATEST");
        assertTrue(r.getAttributes().containsKey(CfnRollback.EVENT_INVOKE_CONFIG_SNAPSHOT_ATTR));

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<Map<String, Object>> restored = ArgumentCaptor.captor();
        verify(lambda).putEventInvokeConfig(eq(REGION), eq("orders"), eq("$LATEST"), restored.capture());
        assertEquals(2, restored.getValue().get("MaximumRetryAttempts"));
        assertEquals(21600, restored.getValue().get("MaximumEventAgeInSeconds"));
        assertEquals(Map.of(
                "OnSuccess", Map.of("Destination", "arn:aws:sqs:us-east-1:000000000000:ok"),
                "OnFailure", Map.of("Destination", "arn:aws:sqs:us-east-1:000000000000:dlq")),
                restored.getValue().get("DestinationConfig"));
        assertFalse(r.getAttributes().containsKey(CfnRollback.EVENT_INVOKE_CONFIG_SNAPSHOT_ATTR),
                "the snapshot is spent by the rollback");
    }

    /** A restore that fails keeps the snapshot, so the next rollback attempt still has it. */
    @Test
    void aFailedRestoreKeepsTheSnapshot() {
        StackResource r = provision("""
                {"FunctionName": "orders", "Qualifier": "$LATEST", "MaximumRetryAttempts": 0}
                """, "orders|$LATEST");
        doThrow(new AwsException("ServiceException", "boom", 500))
                .when(lambda).putEventInvokeConfig(eq(REGION), eq("orders"), eq("$LATEST"), anyMap());

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertTrue(r.getAttributes().containsKey(CfnRollback.EVENT_INVOKE_CONFIG_SNAPSHOT_ATTR));
    }

    /** Settings the configuration did not carry are restored as absent, not as defaults. */
    @Test
    void rollbackRestoresAbsentSettingsAsAbsent() {
        when(lambda.getEventInvokeConfig(REGION, "orders", "live")).thenReturn(config(null, null, null, null));

        StackResource r = provision("""
                {"FunctionName": "orders", "Qualifier": "live", "MaximumEventAgeInSeconds": 120}
                """, "orders|live");
        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<Map<String, Object>> restored = ArgumentCaptor.captor();
        verify(lambda).putEventInvokeConfig(eq(REGION), eq("orders"), eq("live"), restored.capture());
        assertTrue(restored.getValue().isEmpty(), restored.getValue().toString());
    }

    /** A successful update clears the snapshot, so a later rollback cannot restore stale settings. */
    @Test
    void aCommittedUpdateDropsTheSnapshot() {
        when(lambda.getEventInvokeConfig(REGION, "orders", "$LATEST")).thenReturn(config(2, 21600, null, null));
        StackResource r = provision("""
                {"FunctionName": "orders", "Qualifier": "$LATEST", "MaximumRetryAttempts": 0}
                """, "orders|$LATEST");

        provisioner.clearUpdate(r);

        assertFalse(r.getAttributes().containsKey(CfnRollback.EVENT_INVOKE_CONFIG_SNAPSHOT_ATTR));
        assertTrue(provisioner.rollbackUpdate(r), "nothing is left to undo");
        verify(lambda, never()).putEventInvokeConfig(anyString(), anyString(), anyString(), anyMap());
    }

    /** A provision that failed before it changed anything has nothing to undo. */
    @Test
    void aProvisionThatFailedBeforeMutatingReportsRolledBack() {
        doThrow(new AwsException("ResourceNotFoundException", "Function not found: payments", 404))
                .when(lambda).putEventInvokeConfig(eq(REGION), eq("payments"), eq("$LATEST"), anyMap());
        StackResource r = resource();
        r.setPhysicalId("orders|$LATEST");
        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"FunctionName": "payments", "Qualifier": "$LATEST"}
                """), new ProvisionContext(engine, REGION, ACCOUNT_ID, STACK, "orders|$LATEST")));

        assertTrue(provisioner.rollbackUpdate(r));
        verify(lambda, never()).deleteEventInvokeConfig(anyString(), anyString(), anyString());
    }

    /** A conditional DestinationConfig that resolves to AWS::NoValue is omitted, not sent as an empty set. */
    @Test
    void aDestinationConfigResolvingToNoValueIsNotSent() {
        when(engine.resolveNode(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node.has("Ref") ? TextNode.valueOf("") : node;
        });

        provision("""
                {"FunctionName": "orders", "Qualifier": "$LATEST",
                 "DestinationConfig": {"Ref": "AWS::NoValue"}}
                """, "orders|$LATEST");

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.captor();
        verify(lambda).updateEventInvokeConfig(eq(REGION), eq("orders"), eq("$LATEST"), request.capture());
        assertFalse(request.getValue().containsKey("DestinationConfig"), request.getValue().toString());
    }

    @Test
    void aDestinationSideResolvingToNoValueIsNotSent() {
        when(engine.resolveNode(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            if (node.isObject() && node.has("OnSuccess")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).set("OnSuccess", TextNode.valueOf(""));
            }
            return node;
        });

        provision("""
                {"FunctionName": "orders", "Qualifier": "$LATEST",
                 "DestinationConfig": {"OnSuccess": {"Ref": "AWS::NoValue"},
                                       "OnFailure": {"Destination": "arn:aws:sqs:us-east-1:000000000000:dlq"}}}
                """, null);

        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.captor();
        verify(lambda).putEventInvokeConfig(eq(REGION), eq("orders"), eq("$LATEST"), request.capture());
        assertEquals(Map.of("OnFailure", Map.of("Destination", "arn:aws:sqs:us-east-1:000000000000:dlq")),
                request.getValue().get("DestinationConfig"));
    }

    private static FunctionEventInvokeConfig config(Integer retries, Integer eventAge,
                                                    String onSuccess, String onFailure) {
        FunctionEventInvokeConfig config = new FunctionEventInvokeConfig();
        config.setFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:orders:$LATEST");
        config.setMaximumRetryAttempts(retries);
        config.setMaximumEventAgeInSeconds(eventAge);
        if (onSuccess != null || onFailure != null) {
            FunctionEventInvokeConfig.DestinationConfig destinations = new FunctionEventInvokeConfig.DestinationConfig();
            if (onSuccess != null) {
                destinations.setOnSuccess(new FunctionEventInvokeConfig.Destination(onSuccess));
            }
            if (onFailure != null) {
                destinations.setOnFailure(new FunctionEventInvokeConfig.Destination(onFailure));
            }
            config.setDestinationConfig(destinations);
        }
        return config;
    }

    @Test
    void aFailedPutLeavesThePriorIdInPlace() {
        doThrow(new AwsException("ResourceNotFoundException", "Function not found: payments", 404))
                .when(lambda).putEventInvokeConfig(eq(REGION), eq("payments"), eq("$LATEST"), anyMap());

        StackResource r = resource();
        r.setPhysicalId("orders|$LATEST");
        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"FunctionName": "payments", "Qualifier": "$LATEST"}
                """), new ProvisionContext(engine, REGION, ACCOUNT_ID, STACK, "orders|$LATEST")));

        assertEquals("orders|$LATEST", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void deleteSplitsTheIdentityAtThePipe() {
        provisioner.delete(TYPE, "arn:aws:lambda:us-east-1:000000000000:function:orders|live", REGION);

        verify(lambda).deleteEventInvokeConfig(REGION,
                "arn:aws:lambda:us-east-1:000000000000:function:orders", "live");
    }

    @Test
    void deleteToleratesAConfigurationThatIsAlreadyGone() {
        doThrow(new AwsException("ResourceNotFoundException",
                "The function arn:aws:lambda:us-east-1:000000000000:function:orders doesn't have an EventInvokeConfig", 404))
                .when(lambda).deleteEventInvokeConfig(REGION, "orders", "$LATEST");

        provisioner.delete(TYPE, "orders|$LATEST", REGION);
    }

    @Test
    void deletePropagatesAnyOtherFailure() {
        doThrow(new AwsException("ServiceException", "boom", 500))
                .when(lambda).deleteEventInvokeConfig(REGION, "orders", "$LATEST");

        assertThrows(AwsException.class, () -> provisioner.delete(TYPE, "orders|$LATEST", REGION));
    }

    @Test
    void deleteIgnoresAnIdWithoutAQualifier() {
        provisioner.delete(TYPE, "orders", REGION);
        provisioner.delete(TYPE, null, REGION);

        verifyNoInteractions(lambda);
    }
}
