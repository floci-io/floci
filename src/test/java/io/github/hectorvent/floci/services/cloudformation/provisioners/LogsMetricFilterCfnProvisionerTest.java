package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricTransformation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code AWS::Logs::MetricFilter}: the physical id is the schema's composite identifier, the
 * template's properties reach the service in the API's shape, an update with the same identity puts
 * the filter again after keeping what it replaces, and a changed identity is a replacement whose
 * displaced filter is left to the cleanup record.
 */
class LogsMetricFilterCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String STACK = "my-stack";
    private static final String TYPE = "AWS::Logs::MetricFilter";
    private static final String GROUP = "/aws/lambda/orders";

    private CloudWatchLogsMetricFilterService service;
    private LogsMetricFilterCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        service = mock(CloudWatchLogsMetricFilterService.class);
        provisioner = new LogsMetricFilterCfnProvisioner(service);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(engine.resolveStringList(any())).thenCallRealMethod();
        when(service.putMetricFilter(any(), eq(REGION))).thenAnswer(i -> i.getArgument(0));
        when(service.findMetricFilter(anyString(), anyString(), eq(REGION))).thenReturn(Optional.empty());
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
        r.setLogicalId("ErrorFilter");
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private StackResource provision(String json, String priorPhysicalId) {
        return provision(json, priorPhysicalId, Map.of());
    }

    private StackResource provision(String json, String priorPhysicalId, Map<String, String> priorAttributes) {
        StackResource r = resource();
        r.setPhysicalId(priorPhysicalId);
        r.getAttributes().putAll(priorAttributes);
        provisioner.provision(r, props(json),
                new ProvisionContext(engine, REGION, ACCOUNT_ID, STACK, priorPhysicalId));
        return r;
    }

    private MetricFilter putDefinition() {
        ArgumentCaptor<MetricFilter> definition = ArgumentCaptor.captor();
        verify(service).putMetricFilter(definition.capture(), eq(REGION));
        return definition.getValue();
    }

    private static final String FULL = """
            {"LogGroupName": "/aws/lambda/orders", "FilterName": "errors",
             "FilterPattern": "{ $.level = \\"ERROR\\" }",
             "MetricTransformations": [{
               "MetricName": "ErrorCount", "MetricNamespace": "Orders", "MetricValue": "1",
               "DefaultValue": 0, "Unit": "Count"}],
             "ApplyOnTransformedLogs": false,
             "FieldSelectionCriteria": "@aws.region = \\"us-east-1\\"",
             "EmitSystemFieldDimensions": ["@aws.account"]}
            """;

    @Test
    void servesOnlyTheMetricFilterType() {
        assertEquals(Set.of(TYPE), provisioner.resourceTypes());
    }

    @Test
    void createPutsTheTemplatePropertiesInTheApiShape() {
        StackResource r = provision(FULL, null);

        MetricFilter definition = putDefinition();
        assertEquals(GROUP, definition.getLogGroupName());
        assertEquals("errors", definition.getFilterName());
        assertEquals("{ $.level = \"ERROR\" }", definition.getFilterPattern());
        MetricTransformation t = definition.getMetricTransformations().getFirst();
        assertEquals("ErrorCount", t.getMetricName());
        assertEquals("Orders", t.getMetricNamespace());
        assertEquals("1", t.getMetricValue());
        assertEquals(0.0, t.getDefaultValue());
        assertEquals("Count", t.getUnit());
        assertEquals(Boolean.FALSE, definition.getApplyOnTransformedLogs());
        assertEquals("@aws.region = \"us-east-1\"", definition.getFieldSelectionCriteria());
        assertEquals(List.of("@aws.account"), definition.getEmitSystemFieldDimensions());
        assertEquals(GROUP + "|errors", r.getPhysicalId(), "Ref is LogGroupName|FilterName");
        assertEquals("explicit", r.getAttributes().get("FlociMetricFilterNameMode"));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    /** CloudFormation's Dimensions is a Key/Value list; the API takes a map. */
    @Test
    void dimensionsBecomeTheApiMap() {
        provision("""
                {"LogGroupName": "/aws/lambda/orders", "FilterName": "latency",
                 "FilterPattern": "{ $.latency = * }",
                 "MetricTransformations": [{
                   "MetricName": "Latency", "MetricNamespace": "Orders", "MetricValue": "$.latency",
                   "Dimensions": [{"Key": "Route", "Value": "$.route"}, {"Key": "Method", "Value": "$.method"}]}]}
                """, null);

        MetricTransformation t = putDefinition().getMetricTransformations().getFirst();
        assertEquals("$.latency", t.getMetricValue());
        assertEquals(Map.of("Route", "$.route", "Method", "$.method"), t.getDimensions());
        assertNull(t.getDefaultValue());
        assertNull(t.getUnit());
    }

    @Test
    void anEmptyFilterPatternIsAValidPattern() {
        provision("""
                {"LogGroupName": "/aws/lambda/orders", "FilterName": "all", "FilterPattern": "",
                 "MetricTransformations": [{"MetricName": "All", "MetricNamespace": "Orders", "MetricValue": "1"}]}
                """, null);

        assertEquals("", putDefinition().getFilterPattern());
    }

    @Test
    void anUnnamedFilterGetsAGeneratedNameThatLaterUpdatesKeep() {
        String template = """
                {"LogGroupName": "/aws/lambda/orders", "FilterPattern": "ERROR",
                 "MetricTransformations": [{"MetricName": "Errors", "MetricNamespace": "Orders", "MetricValue": "1"}]}
                """;
        StackResource created = provision(template, null);
        String physicalId = created.getPhysicalId();
        assertTrue(physicalId.startsWith(GROUP + "|my-stack-ErrorFilter-"), physicalId);
        assertEquals("generated", created.getAttributes().get("FlociMetricFilterNameMode"));

        StackResource updated = provision(template, physicalId, created.getAttributes());

        assertEquals(physicalId, updated.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(updated));
        verify(service, times(2)).putMetricFilter(any(), eq(REGION));
    }

    @Test
    void droppingAnExplicitNameReplacesTheFilterWithAGeneratedOne() {
        StackResource r = provision("""
                {"LogGroupName": "/aws/lambda/orders", "FilterPattern": "ERROR",
                 "MetricTransformations": [{"MetricName": "Errors", "MetricNamespace": "Orders", "MetricValue": "1"}]}
                """, GROUP + "|errors", Map.of("FlociMetricFilterNameMode", "explicit"));

        assertTrue(r.getPhysicalId().startsWith(GROUP + "|my-stack-ErrorFilter-"), r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(GROUP + "|errors", provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void requiredPropertiesAreChecked() {
        for (String missing : List.of(
                "{\"FilterPattern\": \"ERROR\", \"MetricTransformations\": [{\"MetricName\": \"E\", \"MetricNamespace\": \"O\", \"MetricValue\": \"1\"}]}",
                "{\"LogGroupName\": \"g\", \"MetricTransformations\": [{\"MetricName\": \"E\", \"MetricNamespace\": \"O\", \"MetricValue\": \"1\"}]}",
                "{\"LogGroupName\": \"g\", \"FilterPattern\": \"ERROR\"}",
                "{\"LogGroupName\": \"g\", \"FilterPattern\": \"ERROR\", \"MetricTransformations\": []}",
                "{\"LogGroupName\": \"g\", \"FilterPattern\": \"ERROR\", \"MetricTransformations\": [{}, {}]}")) {
            AwsException e = assertThrows(AwsException.class, () -> provision(missing, null), missing);
            assertEquals("ValidationError", e.getErrorCode(), missing);
        }
        verifyNoInteractions(service);
    }

    @Test
    void aDefaultValueThatIsNotANumberIsRejected() {
        AwsException e = assertThrows(AwsException.class, () -> provision("""
                {"LogGroupName": "/aws/lambda/orders", "FilterPattern": "ERROR",
                 "MetricTransformations": [{"MetricName": "E", "MetricNamespace": "O", "MetricValue": "1", "DefaultValue": "zero"}]}
                """, null));

        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("DefaultValue"), e.getMessage());
        verifyNoInteractions(service);
    }

    @Test
    void theServiceRejectionFailsTheResourceWithoutAPhysicalId() {
        doThrow(new AwsException("InvalidParameterException", "Invalid filter pattern at position 5", 400))
                .when(service).putMetricFilter(any(), eq(REGION));

        StackResource r = resource();
        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"LogGroupName": "/aws/lambda/orders", "FilterName": "bad", "FilterPattern": "{ $.a = }",
                 "MetricTransformations": [{"MetricName": "E", "MetricNamespace": "O", "MetricValue": "1"}]}
                """), new ProvisionContext(engine, REGION, ACCOUNT_ID, STACK)));

        assertEquals("InvalidParameterException", e.getErrorCode());
        assertNull(r.getPhysicalId());
    }

    @Test
    void anUpdateWithTheSameIdentityPutsAgainAndKeepsWhatItReplaces() {
        MetricFilter before = new MetricFilter();
        before.setLogGroupName(GROUP);
        before.setFilterName("errors");
        before.setFilterPattern("ERROR");
        MetricTransformation t = new MetricTransformation();
        t.setMetricName("ErrorCount");
        t.setMetricNamespace("Orders");
        t.setMetricValue("1");
        before.setMetricTransformations(List.of(t));
        before.setCreationTime(123L);
        when(service.findMetricFilter(GROUP, "errors", REGION)).thenReturn(Optional.of(before));

        StackResource r = provision(FULL, GROUP + "|errors", Map.of("FlociMetricFilterNameMode", "explicit"));

        assertEquals(GROUP + "|errors", r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertTrue(r.getAttributes().containsKey(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR));
        assertEquals("{ $.level = \"ERROR\" }", putDefinition().getFilterPattern());

        assertTrue(provisioner.rollbackUpdate(r));

        ArgumentCaptor<MetricFilter> restored = ArgumentCaptor.captor();
        verify(service, times(2)).putMetricFilter(restored.capture(), eq(REGION));
        assertEquals("ERROR", restored.getValue().getFilterPattern());
        assertEquals("ErrorCount", restored.getValue().getMetricTransformations().getFirst().getMetricName());
        assertFalse(r.getAttributes().containsKey(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR));
    }

    /** A restore that fails keeps the snapshot, so the next rollback attempt still has it. */
    @Test
    void aFailedRestoreKeepsTheSnapshot() {
        MetricFilter before = new MetricFilter();
        before.setLogGroupName(GROUP);
        before.setFilterName("errors");
        before.setFilterPattern("ERROR");
        when(service.findMetricFilter(GROUP, "errors", REGION)).thenReturn(Optional.of(before));
        StackResource r = provision(FULL, GROUP + "|errors", Map.of("FlociMetricFilterNameMode", "explicit"));
        doThrow(new AwsException("ServiceUnavailableException", "boom", 500))
                .when(service).putMetricFilter(any(), eq(REGION));

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertTrue(r.getAttributes().containsKey(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR));
    }

    @Test
    void rollingBackAnUpdateThatRecreatedAMissingFilterDeletesIt() {
        StackResource r = provision(FULL, GROUP + "|errors", Map.of("FlociMetricFilterNameMode", "explicit"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(service).deleteMetricFilter(GROUP, "errors", REGION);
    }

    @Test
    void aCommittedUpdateDropsTheSnapshot() {
        StackResource r = provision(FULL, GROUP + "|errors", Map.of("FlociMetricFilterNameMode", "explicit"));

        provisioner.clearUpdate(r);

        assertFalse(r.getAttributes().containsKey(CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR));
        assertTrue(provisioner.rollbackUpdate(r), "nothing is left to undo");
        verify(service, never()).deleteMetricFilter(anyString(), anyString(), anyString());
    }

    @Test
    void aChangedLogGroupIsAReplacement() {
        StackResource r = provision(FULL, "/aws/lambda/payments|errors", Map.of("FlociMetricFilterNameMode", "explicit"));

        assertEquals(GROUP + "|errors", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals("/aws/lambda/payments|errors", provisioner.updateCleanupPhysicalId(r));
        verify(service, never()).findMetricFilter(anyString(), anyString(), anyString());
        verify(service, never()).deleteMetricFilter(anyString(), anyString(), anyString());
    }

    @Test
    void completingAReplacementDeletesTheDisplacedFilterSplittingAtTheFirstPipe() {
        StackResource r = provision(FULL, GROUP + "|old|name", Map.of("FlociMetricFilterNameMode", "explicit"));

        UpdateCleanupResult result = provisioner.completeUpdate(r);

        assertTrue(result.complete());
        verify(service).deleteMetricFilter(GROUP, "old|name", REGION);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void rollingBackAReplacementDeletesTheNewFilterAndRestoresThePriorId() {
        StackResource r = provision(FULL, GROUP + "|old", Map.of("FlociMetricFilterNameMode", "explicit"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(service).deleteMetricFilter(GROUP, "errors", REGION);
        verify(service, never()).deleteMetricFilter(GROUP, "old", REGION);
        assertEquals(GROUP + "|old", r.getPhysicalId());
    }

    @Test
    void aProvisionThatFailedBeforeMutatingReportsRolledBack() {
        doThrow(new AwsException("ResourceNotFoundException", "The specified log group does not exist.", 400))
                .when(service).putMetricFilter(any(), eq(REGION));
        StackResource r = resource();
        r.setPhysicalId(GROUP + "|old");
        assertThrows(AwsException.class, () -> provisioner.provision(r, props(FULL),
                new ProvisionContext(engine, REGION, ACCOUNT_ID, STACK, GROUP + "|old")));

        assertTrue(provisioner.rollbackUpdate(r));
        verify(service, never()).deleteMetricFilter(anyString(), anyString(), anyString());
    }

    @Test
    void deleteToleratesAFilterOrGroupThatIsAlreadyGone() {
        doThrow(new AwsException("ResourceNotFoundException", "The specified metric filter does not exist.", 400))
                .when(service).deleteMetricFilter(GROUP, "errors", REGION);

        provisioner.delete(TYPE, GROUP + "|errors", REGION);
    }

    @Test
    void deletePropagatesAnyOtherFailure() {
        doThrow(new AwsException("ServiceUnavailableException", "boom", 500))
                .when(service).deleteMetricFilter(GROUP, "errors", REGION);

        assertThrows(AwsException.class, () -> provisioner.delete(TYPE, GROUP + "|errors", REGION));
    }

    @Test
    void deleteIgnoresAnIdWithoutBothParts() {
        provisioner.delete(TYPE, null, REGION);
        provisioner.delete(TYPE, "no-pipe", REGION);
        provisioner.delete(TYPE, GROUP + "|", REGION);

        verifyNoInteractions(service);
    }
}
