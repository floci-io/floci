package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsMetricFilterService.MutationOutcome;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real backing filters, with storage faults at the mutation boundary. No live AWS calls. */
class LogsMetricFilterCfnProvisionerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String GROUP = "/aws/lambda/orders";
    private static final String OTHER = "/aws/lambda/payments";
    private static final String SNAPSHOT = CfnRollback.METRIC_FILTER_UPDATE_SNAPSHOT_ATTR;
    private static final String STATE = "__FlociMetricFilterState";
    private static final String GROUP_ATTR = "__FlociMetricFilterLogGroupName";
    private static final String NAME_MODE = "__FlociMetricFilterNameMode";
    private static final String FULL = """
            {"LogGroupName": "/aws/lambda/orders", "FilterName": "errors",
             "FilterPattern": "{ $.level = \\"ERROR\\" }",
             "MetricTransformations": [{"MetricName": "ErrorCount", "MetricNamespace": "Orders",
               "MetricValue": "2", "DefaultValue": 7, "Unit": "Count"}],
             "ApplyOnTransformedLogs": false,
             "FieldSelectionCriteria": "@aws.region = \\"us-east-1\\"",
             "EmitSystemFieldDimensions": ["@aws.account"]}
            """;

    private RecordingStore store;
    private AccountAwareStorageBackend<MetricFilter> canonicalStore;
    private CloudWatchLogsService logs;
    private CloudWatchLogsMetricFilterService service;
    private LogsMetricFilterCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        canonicalStore = new AccountAwareStorageBackend<>(store, null, "000000000000");
        store.monitor = canonicalStore;
        RegionResolver resolver = new RegionResolver(REGION, "000000000000");
        StorageFactory storage = mock(StorageFactory.class);
        when(storage.create(any(), any(), any())).thenAnswer(i ->
                "cwlogs-metric-filters.json".equals(i.getArgument(1))
                        ? canonicalStore
                        : AccountAwareStorageBackend.inMemory("000000000000"));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudwatchlogs().maxStoredEvents()).thenReturn(Integer.MAX_VALUE);
        logs = new CloudWatchLogsService(storage, config, resolver, null, null);
        logs.createLogGroup(GROUP, null, null, REGION);
        logs.createLogGroup(OTHER, null, null, REGION);
        service = new CloudWatchLogsMetricFilterService(logs, mock(CloudWatchMetricsService.class), resolver);
        provisioner = new LogsMetricFilterCfnProvisioner(service);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> ((JsonNode) i.getArgument(0)).asText());
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(engine.resolveStringList(any())).thenCallRealMethod();
    }

    private static ObjectNode props() {
        try {
            return (ObjectNode) MAPPER.readTree(FULL);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private StackResource create(ObjectNode props) {
        StackResource r = new StackResource();
        r.setLogicalId("Filter");
        r.setResourceType("AWS::Logs::MetricFilter");
        update(r, props);
        return r;
    }

    private void update(StackResource r, ObjectNode props) {
        provisioner.provision(r, props,
                new ProvisionContext(engine, REGION, "000000000000", "my-stack", r.getPhysicalId()));
    }

    private MetricFilter filter(String group, String name) {
        return service.findMetricFilter(group, name, REGION).orElseThrow();
    }

    private static JsonNode configuration(MetricFilter filter) {
        ObjectNode node = MAPPER.valueToTree(filter);
        node.remove("creationTime");
        return node;
    }

    @Test
    void createUsesNameOnlyPublicIdentityAndPreservesAllProperties() {
        StackResource r = create(props());
        assertEquals("errors", r.getPhysicalId());
        assertEquals(GROUP, r.getAttributes().get(GROUP_ATTR));
        assertEquals("explicit", r.getAttributes().get(NAME_MODE));
        assertEquals(Set.of("AWS::Logs::MetricFilter"), provisioner.resourceTypes());
        MetricFilter f = filter(GROUP, "errors");
        assertEquals("{ $.level = \"ERROR\" }", f.getFilterPattern());
        assertEquals("2", f.getMetricTransformations().getFirst().getMetricValue());
        assertEquals(7.0, f.getMetricTransformations().getFirst().getDefaultValue());
        assertEquals("Count", f.getMetricTransformations().getFirst().getUnit());
        assertEquals(false, f.getApplyOnTransformedLogs());
        assertEquals(List.of("@aws.account"), f.getEmitSystemFieldDimensions());
        assertEquals("@aws.region = \"us-east-1\"", f.getFieldSelectionCriteria());
    }

    @Test
    void generatedNameIsStableAcrossARealMutableUpdate() {
        ObjectNode p = props();
        p.remove("FilterName");
        StackResource r = create(p);
        String name = r.getPhysicalId();
        assertTrue(name.startsWith("my-stack-Filter-"), name);
        assertEquals("generated", r.getAttributes().get(NAME_MODE));
        update(r, p.put("FilterPattern", "WARN"));
        assertEquals(name, r.getPhysicalId());
        assertEquals("WARN", filter(GROUP, name).getFilterPattern());
    }

    @Test
    void droppingAnExplicitNameDeletesItBeforeCreatingGeneratedName() {
        StackResource r = create(props());
        ObjectNode p = props();
        p.remove("FilterName");
        store.operations.clear();
        update(r, p);
        assertTrue(r.getPhysicalId().startsWith("my-stack-Filter-"));
        assertEquals(List.of("delete:errors", "put:" + r.getPhysicalId()), store.operations);
    }

    @Test
    void replacementDeletesBeforeCreatingEvenWithPreviouslyInstalledUpdateReplaceRetain() {
        StackResource r = create(props());
        r.setUpdateReplacePolicy("Retain");
        store.operations.clear();
        update(r, props().put("FilterName", "new"));
        assertEquals(List.of("delete:errors", "put:new"), store.operations);
        assertTrue(service.findMetricFilter(GROUP, "errors", REGION).isEmpty());
        assertFalse(provisioner.hasReplacementUpdate(r), "no post-commit deletion is owed");
    }

    @Test
    void renameAtCapacityPreservesAllNinetyNineUnrelatedFilters() {
        StackResource r = create(props());
        Map<String, JsonNode> unrelated = new LinkedHashMap<>();
        for (int i = 0; i < 99; i++) {
            String name = "unrelated-" + i;
            create(props().put("FilterName", name));
            unrelated.put(name, MAPPER.valueToTree(filter(GROUP, name)));
        }
        assertDoesNotThrow(() -> update(r, props().put("FilterName", "renamed")));
        assertTrue(service.findMetricFilter(GROUP, "errors", REGION).isEmpty());
        assertNotNull(filter(GROUP, "renamed"));
        assertEquals(100, store.keys().size());
        unrelated.forEach((name, expected) -> assertEquals(expected, MAPPER.valueToTree(filter(GROUP, name))));
    }

    @Test
    void sameNameInAnotherGroupIsAReplacementAndDeleteOnlyUsesItsOwnGroup() {
        StackResource r = create(props().put("FilterName", "same|name"));
        StackResource other = create(props().put("LogGroupName", OTHER).put("FilterName", "same|name"));
        provisioner.delete(other, REGION);
        assertNotNull(filter(GROUP, "same|name"));
        assertTrue(service.findMetricFilter(OTHER, "same|name", REGION).isEmpty());
        store.operations.clear();
        update(r, props().put("LogGroupName", OTHER).put("FilterName", "same|name"));
        assertEquals(List.of("delete:same|name", "put:same|name"), store.operations);
        assertEquals("same|name", r.getPhysicalId());
        assertTrue(service.findMetricFilter(GROUP, "same|name", REGION).isEmpty());
        provisioner.delete(r, REGION);
        assertTrue(service.findMetricFilter(OTHER, "same|name", REGION).isEmpty());
    }

    @Test
    void replacementPatternFailureDeletesOldThenRollbackRecreatesCompleteDefinition() {
        StackResource r = create(props());
        MetricFilter old = filter(GROUP, "errors");
        old.setCreationTime(1L);
        JsonNode expected = configuration(old);
        Map<String, String> attributes = Map.copyOf(r.getAttributes());
        store.operations.clear();
        assertThrows(AwsException.class, () -> update(r, props().put("FilterName", "bad").put("FilterPattern", "{")));
        assertEquals(List.of("delete:errors"), store.operations, "parsing failure happens after delete");
        assertTrue(service.findMetricFilter(GROUP, "errors", REGION).isEmpty());
        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals(expected, configuration(filter(GROUP, "errors")));
        assertNotEquals(1L, filter(GROUP, "errors").getCreationTime());
        assertEquals("errors", r.getPhysicalId());
        assertEquals(attributes, r.getAttributes());
        assertTrue(service.findMetricFilter(GROUP, "bad", REGION).isEmpty());
    }

    @Test
    void inPlaceRollbackRestoresEveryTransformationAndFlagWithoutRecreating() {
        StackResource r = create(props());
        JsonNode expected = MAPPER.valueToTree(filter(GROUP, "errors"));
        ObjectNode p = props().put("FilterPattern", "{ $.latency = * }").put("ApplyOnTransformedLogs", true);
        p.remove(List.of("FieldSelectionCriteria", "EmitSystemFieldDimensions"));
        ObjectNode t = (ObjectNode) p.path("MetricTransformations").get(0);
        t.put("MetricName", "Latency").put("MetricNamespace", "Other").put("MetricValue", "$.latency");
        t.remove("DefaultValue");
        t.putArray("Dimensions").addObject().put("Key", "Route").put("Value", "$.route");
        update(r, p);
        assertNotEquals(expected, MAPPER.valueToTree(filter(GROUP, "errors")), "mutation precedes rollback");
        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals(expected, MAPPER.valueToTree(filter(GROUP, "errors")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRestoreKeepsFullSnapshotForDeterministicRetry(boolean replacement) {
        StackResource r = create(props());
        JsonNode expected = configuration(filter(GROUP, "errors"));
        update(r, props().put("FilterName", replacement ? "new" : "errors").put("FilterPattern", "WARN"));
        store.failBefore = "errors";
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertTrue(r.getAttributes().containsKey(SNAPSHOT));
        store.failBefore = null;
        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals(expected, configuration(filter(GROUP, "errors")));
        assertFalse(r.getAttributes().containsKey(SNAPSHOT));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void createWriteFailureTracksOnlyTheFilterActuallyWritten(boolean afterWrite) {
        StackResource r = new StackResource();
        r.setLogicalId("Filter");
        r.setResourceType("AWS::Logs::MetricFilter");
        if (afterWrite) {
            store.failAfter = "errors";
        } else {
            store.failBefore = "errors";
        }
        assertThrows(AwsException.class, () -> update(r, props()));
        assertEquals(afterWrite ? "errors" : null, r.getPhysicalId());
        assertEquals(afterWrite, "true".equals(r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR)));
        provisioner.delete(r, REGION);
        assertTrue(store.keys().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUninspectableCreateOutcomeCannotClaimSuccessfulRollbackOrDelete(boolean replacement) {
        StackResource r = replacement ? create(props()) : new StackResource();
        r.setLogicalId("Filter");
        r.setResourceType("AWS::Logs::MetricFilter");
        store.failAfter = "bad";
        store.failInspection = true;
        assertThrows(AwsException.class, () -> update(r, props().put("FilterName", "bad")));
        store.failReads = false;
        assertNotNull(filter(GROUP, "bad"));
        if (replacement) {
            assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(r));
            assertTrue(r.getAttributes().containsKey(SNAPSHOT));
        }
        assertThrows(IllegalStateException.class, () -> provisioner.delete(r, REGION));
        assertNotNull(filter(GROUP, "bad"), "unknown ownership must not be guessed");
        service.deleteMetricFilter(GROUP, "bad", REGION);
        if (replacement) {
            assertTrue(provisioner.rollbackUpdate(r));
            assertNotNull(filter(GROUP, "errors"));
        } else {
            assertDoesNotThrow(() -> provisioner.delete(r, REGION));
        }
    }

    @Test
    void anUninspectableRestorationRemainsVisibleToStackDeletion() {
        StackResource r = create(props());
        update(r, props().put("FilterName", "new"));
        store.failAfter = "errors";
        store.failInspection = true;
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        store.failReads = false;
        assertThrows(IllegalStateException.class, () -> provisioner.delete(r, REGION));
        assertNotNull(filter(GROUP, "errors"));
        assertTrue(r.getAttributes().containsKey(SNAPSHOT));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rollbackOfAnAlreadyAbsentPriorFilterRestoresAbsence(boolean replacement) {
        StackResource r = create(props());
        service.deleteMetricFilter(GROUP, "errors", REGION);
        update(r, props().put("FilterName", replacement ? "new" : "errors"));
        assertTrue(provisioner.rollbackUpdate(r));
        assertTrue(store.keys().isEmpty());
        assertEquals("errors", r.getPhysicalId());
        create(props().put("FilterPattern", "UNRELATED"));
        JsonNode otherOwner = MAPPER.valueToTree(filter(GROUP, "errors"));
        assertEquals("AlreadyExistsException", assertThrows(AwsException.class,
                () -> update(r, props().put("FilterPattern", "WARN"))).getErrorCode());
        assertTrue(provisioner.rollbackUpdate(r));
        provisioner.delete(r, REGION);
        assertEquals(otherOwner, service.findMetricFilter(GROUP, "errors", REGION)
                .map(MAPPER::valueToTree).orElse(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "update", "replacement"})
    void absentPriorCollisionCannotBecomeOwnedAfterRollback(String laterOperation) throws Exception {
        StackResource originalOwner = create(props());
        service.deleteMetricFilter(GROUP, "errors", REGION);
        service = spy(service);
        provisioner = new LogsMetricFilterCfnProvisioner(service);
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch otherOwnerCreated = new CountDownLatch(1);
        doAnswer(invocation -> {
            Optional<?> prior = (Optional<?>) invocation.callRealMethod();
            assertTrue(prior.isEmpty(), "A snapshots the externally deleted filter as absent");
            snapshotRead.countDown();
            assertTrue(otherOwnerCreated.await(5, TimeUnit.SECONDS));
            return prior;
        }).doCallRealMethod().when(service).findMetricFilter(GROUP, "errors", REGION);
        JsonNode expected;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AwsException> updateA = executor.submit(() -> assertThrows(AwsException.class,
                    () -> update(originalOwner, props().put("FilterPattern", "WARN"))));
            try {
                assertTrue(snapshotRead.await(5, TimeUnit.SECONDS));
                create(props().put("FilterPattern", "UNRELATED"));
                expected = MAPPER.valueToTree(filter(GROUP, "errors"));
            } finally {
                otherOwnerCreated.countDown();
            }
            assertEquals("AlreadyExistsException", updateA.get(5, TimeUnit.SECONDS).getErrorCode());
        }
        JsonNode snapshot = MAPPER.readTree(originalOwner.getAttributes().get(SNAPSHOT));
        assertTrue(snapshot.path("absent").asBoolean());
        assertFalse(snapshot.path("replacement").asBoolean());
        assertEquals("UNOWNED", snapshot.path("prior").path("ownership").asText());
        assertTrue(provisioner.rollbackUpdate(originalOwner));
        assertEquals(expected, MAPPER.valueToTree(filter(GROUP, "errors")));
        originalOwner.setStatus("UPDATE_COMPLETE");
        provisioner.completeUpdate(originalOwner);
        provisioner.clearUpdate(originalOwner);

        switch (laterOperation) {
            case "delete" -> provisioner.delete(originalOwner, REGION);
            case "update" -> {
                assertEquals("AlreadyExistsException", assertThrows(AwsException.class,
                        () -> update(originalOwner, props().put("FilterPattern", "FATAL"))).getErrorCode());
                assertTrue(provisioner.rollbackUpdate(originalOwner));
                provisioner.delete(originalOwner, REGION);
            }
            case "replacement" -> {
                update(originalOwner, props().put("FilterName", "new").put("FilterPattern", "WARN"));
                assertEquals("WARN", filter(GROUP, "new").getFilterPattern());
                provisioner.completeUpdate(originalOwner);
                assertFalse(originalOwner.getAttributes().containsKey(SNAPSHOT));
                provisioner.delete(originalOwner, REGION);
                assertTrue(service.findMetricFilter(GROUP, "new", REGION).isEmpty());
            }
            default -> throw new AssertionError(laterOperation);
        }
        assertEquals(expected, service.findMetricFilter(GROUP, "errors", REGION)
                .map(MAPPER::valueToTree).orElse(null), "B's complete backing definition must remain untouched");
        assertEquals(1, store.keys().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rollbackDoesNotUpsertOrDeleteACollidingUnownedTarget(boolean replacement) {
        create(props().put("FilterName", "taken").put("FilterPattern", "UNRELATED"));
        JsonNode unrelated = MAPPER.valueToTree(filter(GROUP, "taken"));
        StackResource r = replacement ? create(props()) : new StackResource();
        if (!replacement) {
            r.setLogicalId("Filter");
            r.setResourceType("AWS::Logs::MetricFilter");
        }
        assertThrows(AwsException.class, () -> update(r, props().put("FilterName", "taken")));
        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals(unrelated, MAPPER.valueToTree(filter(GROUP, "taken")));
        if (replacement) {
            assertNotNull(filter(GROUP, "errors"));
        } else {
            assertNull(r.getPhysicalId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replacementWriteFailureBeforeOrAfterStorageIsRecoverable(boolean afterWrite) {
        StackResource r = create(props());
        JsonNode expected = configuration(filter(GROUP, "errors"));
        if (afterWrite) {
            store.failAfter = "new";
        } else {
            store.failBefore = "new";
        }
        assertThrows(AwsException.class, () -> update(r, props().put("FilterName", "new")));
        assertTrue(provisioner.rollbackUpdate(r));
        assertTrue(service.findMetricFilter(GROUP, "new", REGION).isEmpty());
        assertEquals(expected, configuration(filter(GROUP, "errors")));
    }

    @Test
    void deleteToleratesOnlyAlreadyGoneErrors() {
        StackResource r = create(props());
        store.failDelete = true;
        assertThrows(AwsException.class, () -> provisioner.delete(r, REGION));
        store.failDelete = false;
        provisioner.delete(r, REGION);
        assertDoesNotThrow(() -> provisioner.delete(r, REGION));
        logs.deleteLogGroup(GROUP, REGION);
        assertDoesNotThrow(() -> provisioner.delete(r, REGION));
    }

    @Test
    void emptyPatternIsValidAndCommittedUpdateDropsSnapshot() {
        StackResource r = create(props().put("FilterPattern", ""));
        update(r, props());
        assertTrue(r.getAttributes().containsKey(SNAPSHOT));
        provisioner.clearUpdate(r);
        assertFalse(r.getAttributes().containsKey(SNAPSHOT));
        store.operations.clear();
        assertTrue(provisioner.rollbackUpdate(r));
        assertTrue(store.operations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LogGroupName", "FilterPattern", "MetricTransformations"})
    void eachRequiredPropertyIsValidatedBeforeMutation(String missing) {
        ObjectNode p = props();
        p.remove(missing);
        AwsException e = assertThrows(AwsException.class, () -> create(p));
        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(store.operations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MetricName", "MetricNamespace", "MetricValue"})
    void eachRequiredTransformationMemberIsValidatedByCfn(String missing) {
        ObjectNode p = props();
        ((ObjectNode) p.path("MetricTransformations").get(0)).remove(missing);
        AwsException e = assertThrows(AwsException.class, () -> create(p));
        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(store.operations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "[{},{}]", "null", "\"wrong\""})
    void transformationMustBeExactlyOneObject(String json) throws Exception {
        ObjectNode p = props();
        p.set("MetricTransformations", MAPPER.readTree(json));
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> create(p)).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "[{}]", "[{\"Key\":\"K\"}]", "[{\"Value\":\"$.v\"}]",
            "[{\"Key\":\"é\",\"Value\":\"$.v\"}]", "[{\"Key\":\":K\",\"Value\":\"$.v\"}]",
            "[{\"Key\":\" \",\"Value\":\"$.v\"}]", "[{\"Key\":\"K\",\"Value\":\"\"}]",
            "[{\"Key\":\"K\",\"Value\":\"$.v\"},{\"Key\":\"K\",\"Value\":\"$.v\"}]",
            "[{\"Key\":\"A\",\"Value\":\"$.v\"},{\"Key\":\"B\",\"Value\":\"$.v\"},{\"Key\":\"C\",\"Value\":\"$.v\"},{\"Key\":\"D\",\"Value\":\"$.v\"}]"})
    void cfnDimensionsAreStrictKeyValueArrays(String json) throws Exception {
        ObjectNode p = props();
        ObjectNode t = (ObjectNode) p.path("MetricTransformations").get(0);
        t.remove("DefaultValue");
        t.set("Dimensions", MAPPER.readTree(json));
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> create(p)).getErrorCode());
        assertTrue(store.operations.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "1e999", "garbage"})
    void nonfiniteOrInvalidDefaultIsRejectedByCfn(String value) {
        ObjectNode p = props();
        ((ObjectNode) p.path("MetricTransformations").get(0)).put("DefaultValue", value);
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> create(p)).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "1", "TRUE", ""})
    void booleansDoNotSilentlyCoerceGarbageToFalse(String value) {
        assertEquals("ValidationError",
                assertThrows(AwsException.class, () -> create(props().put("ApplyOnTransformedLogs", value))).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void resolvedCfnBooleanStringsAreAccepted(String value) {
        create(props().put("ApplyOnTransformedLogs", value));
        assertEquals(Boolean.valueOf(value), filter(GROUP, "errors").getApplyOnTransformedLogs());
    }

    @Test
    void cfnNamespace256PassesModelButLogsProviderStillRejectsIts255Limit() {
        ObjectNode p = props();
        ((ObjectNode) p.path("MetricTransformations").get(0)).put("MetricNamespace", "A".repeat(256));
        assertEquals("InvalidParameterException", assertThrows(AwsException.class, () -> create(p)).getErrorCode());
        ((ObjectNode) p.path("MetricTransformations").get(0)).put("MetricNamespace", "A".repeat(257));
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> create(p)).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Unit", "UnknownTransformationProperty", "HexadecimalDefault"})
    void transformationModelErrorsAreRejectedBeforeReplacementDeletesAnything(String invalid) {
        StackResource r = create(props());
        ObjectNode p = props().put("FilterName", "new");
        ObjectNode t = (ObjectNode) p.path("MetricTransformations").get(0);
        switch (invalid) {
            case "Unit" -> t.put("Unit", "Bananas");
            case "HexadecimalDefault" -> t.put("DefaultValue", "0x1.0p2");
            default -> t.put(invalid, "wrong");
        }
        store.operations.clear();
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> update(r, p)).getErrorCode());
        assertTrue(store.operations.isEmpty(), store.operations.toString());
        assertNotNull(filter(GROUP, "errors"));
    }

    @Test
    void priorDimensionsAreRestoredWhenUpdateRemovedThem() {
        ObjectNode before = props().put("FilterPattern", "{ $.level = * }");
        ObjectNode t = (ObjectNode) before.path("MetricTransformations").get(0);
        t.remove("DefaultValue");
        t.putArray("Dimensions").addObject().put("Key", "Level").put("Value", "$.level");
        StackResource r = create(before);
        JsonNode expected = MAPPER.valueToTree(filter(GROUP, "errors"));
        update(r, props().put("FilterPattern", "WARN"));
        assertTrue(filter(GROUP, "errors").getMetricTransformations().getFirst().getDimensions().isEmpty());
        provisioner.rollbackUpdate(r);
        assertEquals(expected, MAPPER.valueToTree(filter(GROUP, "errors")));
    }

    @Test
    void restoreFailureAfterWriteRemainsRetryableWithoutAdoptingAnotherFilter() {
        StackResource r = create(props());
        JsonNode expected = configuration(filter(GROUP, "errors"));
        update(r, props().put("FilterName", "new"));
        store.failAfter = "errors";
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertTrue(r.getAttributes().containsKey(SNAPSHOT));
        store.failAfter = null;
        assertTrue(provisioner.rollbackUpdate(r));
        assertEquals(expected, configuration(filter(GROUP, "errors")));
        assertEquals(1, store.keys().size());
    }

    @Test
    void stackDeletionAfterFailedCollisionRollbackCannotDeleteTheUnownedTarget() {
        create(props().put("FilterName", "taken"));
        StackResource r = create(props());
        assertThrows(AwsException.class, () -> update(r, props().put("FilterName", "taken")));
        store.failBefore = "errors";
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        JsonNode unrelated = MAPPER.valueToTree(filter(GROUP, "taken"));
        provisioner.delete(r, REGION);
        assertEquals(unrelated, service.findMetricFilter(GROUP, "taken", REGION)
                .map(MAPPER::valueToTree).orElse(null));
    }

    @Test
    void stackDeletionAfterAnAcknowledgedRestoreWriteFailureRemovesTheOwnedRestoration() {
        StackResource r = create(props());
        update(r, props().put("FilterName", "new"));
        store.failAfter = "errors";
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));
        assertNotNull(filter(GROUP, "errors"));
        provisioner.delete(r, REGION);
        assertTrue(store.keys().isEmpty(), "delete cannot orphan the restoration in its retry snapshot");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Resource", "Dimension"})
    void unknownModelPropertiesAreRejectedBeforeMutation(String scope) {
        ObjectNode p = props();
        if ("Resource".equals(scope)) {
            p.put("Unknown", "value");
        } else {
            ObjectNode t = (ObjectNode) p.path("MetricTransformations").get(0);
            t.remove("DefaultValue");
            t.putArray("Dimensions").addObject().put("Key", "Level").put("Value", "$.level").put("Unknown", "value");
        }
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> create(p)).getErrorCode());
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void retainsFailedUpdateStateOnlyWhileAnUpdateSnapshotIsHeld() {
        StackResource r = create(props());
        assertFalse(provisioner.retainsFailedUpdateState(r), "a committed create keeps nothing to roll back");
        store.failBefore = "new";
        assertThrows(AwsException.class, () -> update(r, props().put("FilterName", "new")));
        assertTrue(r.getAttributes().containsKey(SNAPSHOT));
        assertTrue(provisioner.retainsFailedUpdateState(r), "the failed attempt owns the snapshot the rollback needs");
        store.failBefore = null;
        assertTrue(provisioner.rollbackUpdate(r));
        assertFalse(r.getAttributes().containsKey(SNAPSHOT));
        assertFalse(provisioner.retainsFailedUpdateState(r));
    }

    @Test
    void concurrentNamedCreatesHaveExactlyOneOwner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> concurrentCreate(start));
            Future<Integer> second = executor.submit(() -> concurrentCreate(start));
            start.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS)
                    + second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(List.of("put:errors"), store.operations);
        assertEquals(1, store.keys().size());
    }

    private int concurrentCreate(CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            create(props());
            return 1;
        } catch (AwsException e) {
            assertEquals("AlreadyExistsException", e.getErrorCode());
            return 0;
        }
    }

    @Test
    void forwardDeleteAfterEffectCannotRestoreOverAConcurrentCreator() throws Exception {
        StackResource original = create(props());
        CountDownLatch deleted = new CountDownLatch(1);
        store.deleteFailureName = "errors";
        store.deleteFault = MutationFault.AFTER;
        store.afterDeleteEffect = deleted::countDown;
        JsonNode otherOwner;
        boolean inspectedWhileLocked;
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> creator = executor.submit(() -> {
                assertTrue(deleted.await(5, TimeUnit.SECONDS));
                create(props().put("FilterPattern", "UNRELATED"));
                return null;
            });
            try {
                assertThrows(AwsException.class, () -> update(original, props().put("FilterName", "new")));
            } finally {
                deleted.countDown();
            }
            creator.get(5, TimeUnit.SECONDS);
            inspectedWhileLocked = store.inspectionHeldMonitor;
            otherOwner = MAPPER.valueToTree(filter(GROUP, "errors"));
        }
        store.clearMutationFaults();
        AwsException collision = null;
        try {
            provisioner.rollbackUpdate(original);
        } catch (AwsException e) {
            assertEquals("AlreadyExistsException", e.getErrorCode());
            collision = e;
        }
        assertEquals(otherOwner, MAPPER.valueToTree(filter(GROUP, "errors")));
        assertNotNull(collision, "restoration must use create-if-absent rather than adopt B");
        assertTrue(original.getAttributes().containsKey(SNAPSHOT));
        provisioner.delete(original, REGION);
        assertEquals(otherOwner, service.findMetricFilter(GROUP, "errors", REGION)
                .map(MAPPER::valueToTree).orElse(null));
        assertTrue(inspectedWhileLocked, "delete outcome inspection must precede releasing the canonical lock");
    }

    private static Stream<Arguments> deleteFaultMatrix() {
        return Stream.of("forward", "rollback", "stack")
                .flatMap(phase -> Arrays.stream(MutationFault.values())
                        .map(fault -> Arguments.of(phase, fault)));
    }

    @ParameterizedTest
    @MethodSource("deleteFaultMatrix")
    void deleteOutcomesControlEveryRetryWithoutAdoptingAnotherOwner(String phase, MutationFault fault) {
        StackResource resource = create(props());
        String address = "rollback".equals(phase) ? "new" : "errors";
        if ("rollback".equals(phase)) {
            update(resource, props().put("FilterName", "new"));
        }
        store.deleteFailureName = address;
        store.deleteFault = fault;
        assertThrows(AwsException.class, () -> {
            switch (phase) {
                case "forward" -> update(resource, props().put("FilterName", "new"));
                case "rollback" -> provisioner.rollbackUpdate(resource);
                case "stack" -> provisioner.delete(resource, REGION);
                default -> throw new AssertionError(phase);
            }
        });
        assertTrue(store.inspectionHeldMonitor, phase + "/" + fault + " must inspect under the canonical monitor");
        ObjectNode recorded = mutationState(resource, "rollback".equals(phase));
        assertEquals("DELETE", recorded.path("operation").asText());
        assertEquals(fault.unknown() ? "UNKNOWN" : fault.after() ? "APPLIED" : "NOT_APPLIED",
                recorded.path("outcome").asText());
        assertEquals(fault.unknown() ? "UNKNOWN" : fault.after() ? "UNOWNED" : "OWNED",
                recorded.path("ownership").asText());
        store.clearMutationFaults();

        if (fault == MutationFault.BEFORE) {
            assertNotNull(filter(GROUP, address));
            resumeAfterDeleteFailure(phase, resource);
            if (!"stack".equals(phase)) {
                assertEquals("{ $.level = \"ERROR\" }", filter(GROUP, "errors").getFilterPattern());
                provisioner.delete(resource, REGION);
            }
            assertTrue(store.keys().isEmpty());
            return;
        }
        if (service.findMetricFilter(GROUP, address, REGION).isPresent()) {
            service.deleteMetricFilter(GROUP, address, REGION);
        }
        create(props().put("FilterName", address).put("FilterPattern", "UNRELATED"));
        JsonNode otherOwner = MAPPER.valueToTree(filter(GROUP, address));
        if (fault.unknown()) {
            assertThrows(IllegalStateException.class, () -> resumeAfterDeleteFailure(phase, resource));
            assertThrows(IllegalStateException.class, () -> provisioner.delete(resource, REGION));
            assertEquals(otherOwner, MAPPER.valueToTree(filter(GROUP, address)));
            service.deleteMetricFilter(GROUP, address, REGION);
            resumeAfterDeleteFailure(phase, resource);
            if (!"stack".equals(phase)) {
                assertEquals("{ $.level = \"ERROR\" }", filter(GROUP, "errors").getFilterPattern());
                provisioner.delete(resource, REGION);
            }
            assertTrue(store.keys().isEmpty());
        } else {
            if ("forward".equals(phase)) {
                assertEquals("AlreadyExistsException", assertThrows(AwsException.class,
                        () -> provisioner.rollbackUpdate(resource)).getErrorCode());
            } else {
                resumeAfterDeleteFailure(phase, resource);
            }
            provisioner.delete(resource, REGION);
            assertEquals(otherOwner, service.findMetricFilter(GROUP, address, REGION)
                    .map(MAPPER::valueToTree).orElse(null), "known applied deletes must relinquish ownership before retry");
            assertEquals(1, store.keys().size());
        }
    }

    private void resumeAfterDeleteFailure(String phase, StackResource resource) {
        if ("stack".equals(phase)) {
            provisioner.delete(resource, REGION);
        } else {
            assertTrue(provisioner.rollbackUpdate(resource));
        }
    }

    private static Stream<Arguments> writeFaultMatrix() {
        return Stream.of("create", "update", "restore", "restoreUpdate")
                .flatMap(phase -> Arrays.stream(MutationFault.values())
                        .map(fault -> Arguments.of(phase, fault)));
    }

    @ParameterizedTest
    @MethodSource("writeFaultMatrix")
    void createUpdateAndRestoreUseTheSameMutationOutcomeRules(String phase, MutationFault fault) {
        StackResource resource;
        if ("create".equals(phase)) {
            resource = new StackResource();
            resource.setLogicalId("Filter");
            resource.setResourceType("AWS::Logs::MetricFilter");
        } else {
            resource = create(props());
        }
        if ("restore".equals(phase)) {
            update(resource, props().put("FilterName", "new"));
        } else if ("restoreUpdate".equals(phase)) {
            update(resource, props().put("FilterPattern", "WARN"));
        }
        store.failInspection = fault.unknown();
        if (fault.after()) {
            store.failAfter = "errors";
        } else {
            store.failBefore = "errors";
        }
        assertThrows(AwsException.class, () -> {
            if ("restore".equals(phase) || "restoreUpdate".equals(phase)) {
                provisioner.rollbackUpdate(resource);
            } else {
                update(resource, props().put("FilterPattern", "WARN"));
            }
        });
        assertTrue(store.inspectionHeldMonitor, phase + "/" + fault + " must inspect under the canonical monitor");
        ObjectNode recorded = mutationState(resource, false);
        boolean ownedUpdate = "update".equals(phase) || "restoreUpdate".equals(phase);
        assertEquals(ownedUpdate ? "UPDATE" : "CREATE", recorded.path("operation").asText());
        assertEquals(fault.unknown() ? "UNKNOWN" : fault.after() ? "APPLIED" : "NOT_APPLIED",
                recorded.path("outcome").asText());
        assertEquals(fault.unknown() ? "UNKNOWN" : fault.after() || ownedUpdate ? "OWNED" : "UNOWNED",
                recorded.path("ownership").asText());
        store.clearMutationFaults();
        if (fault.unknown()) {
            if (service.findMetricFilter(GROUP, "errors", REGION).isPresent()) {
                service.deleteMetricFilter(GROUP, "errors", REGION);
            }
            create(props().put("FilterPattern", "UNRELATED"));
            JsonNode otherOwner = MAPPER.valueToTree(filter(GROUP, "errors"));
            if (!"create".equals(phase)) {
                assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
            }
            assertThrows(IllegalStateException.class, () -> provisioner.delete(resource, REGION));
            assertEquals(otherOwner, MAPPER.valueToTree(filter(GROUP, "errors")));
            service.deleteMetricFilter(GROUP, "errors", REGION);
        }
        if (!"create".equals(phase)) {
            assertTrue(provisioner.rollbackUpdate(resource));
            assertEquals("{ $.level = \"ERROR\" }", filter(GROUP, "errors").getFilterPattern());
        }
        provisioner.delete(resource, REGION);
        assertTrue(store.keys().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "before", "after", "unknown"})
    void outcomeCallbackFailureNeverChangesStorageEvidence(String storageResult) {
        create(props());
        MetricFilter definition = filter(GROUP, "errors");
        service.deleteMetricFilter(GROUP, "errors", REGION);
        if ("before".equals(storageResult)) {
            store.failBefore = "errors";
        } else if (!"success".equals(storageResult)) {
            store.failAfter = "errors";
        }
        store.failInspection = "unknown".equals(storageResult);
        List<MutationOutcome> recorded = new ArrayList<>();
        IllegalStateException callbackFailure = new IllegalStateException("outcome metadata recording failed");
        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.createMetricFilter(
                definition, REGION, result -> {
                    assertTrue(Thread.holdsLock(canonicalStore));
                    recorded.add(result.outcome());
                    throw callbackFailure;
                }));
        assertEquals(List.of(switch (storageResult) {
            case "before" -> MutationOutcome.NOT_APPLIED;
            case "unknown" -> MutationOutcome.UNKNOWN;
            default -> MutationOutcome.APPLIED;
        }), recorded, "a callback exception must not reclassify or repeat storage evidence");
        if (!"success".equals(storageResult)) {
            assertSame(store.lastMutationFailure, failure);
            assertTrue(List.of(failure.getSuppressed()).contains(callbackFailure));
        } else {
            assertSame(callbackFailure, failure);
        }
        store.clearMutationFaults();
        assertEquals(!"before".equals(storageResult), service.findMetricFilter(GROUP, "errors", REGION).isPresent());
    }

    @Test
    void reusedMutationAndInspectionExceptionStillReportsUnknownExactlyOnce() {
        create(props());
        MetricFilter definition = filter(GROUP, "errors");
        service.deleteMetricFilter(GROUP, "errors", REGION);
        store.failAfter = "errors";
        store.failInspection = true;
        store.reuseMutationFailure = true;
        List<MutationOutcome> outcomes = new ArrayList<>();
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.createMetricFilter(definition, REGION, result -> {
                    assertTrue(Thread.holdsLock(canonicalStore));
                    assertNull(result.present());
                    outcomes.add(result.outcome());
                }));
        assertAll(
                () -> assertSame(store.lastMutationFailure, failure),
                () -> assertEquals(List.of(MutationOutcome.UNKNOWN), outcomes),
                () -> assertEquals(0, failure.getSuppressed().length));
    }

    private static ObjectNode mutationState(StackResource resource, boolean target) {
        try {
            String snapshot = resource.getAttributes().get(SNAPSHOT);
            if (snapshot == null) {
                return (ObjectNode) MAPPER.readTree(resource.getAttributes().get(STATE));
            }
            JsonNode root = MAPPER.readTree(snapshot);
            return (ObjectNode) root.get(target && root.path("replacement").asBoolean() ? "target" : "prior");
        } catch (Exception e) {
            throw new AssertionError("Invalid mutation evidence", e);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedOutcomeRecordingLeavesAConservativeStateForCleanup(boolean storageAlsoFailed) {
        IllegalStateException recordingFailure = new IllegalStateException("metadata sink unavailable");
        AtomicBoolean rejecting = new AtomicBoolean(true);
        StackResource resource = new StackResource();
        resource.setLogicalId("Filter");
        resource.setResourceType("AWS::Logs::MetricFilter");
        resource.setAttributes(new HashMap<>() {
            @Override
            public String put(String key, String value) {
                if (rejecting.get() && STATE.equals(key) && value.contains("\"outcome\":\"APPLIED\"")) {
                    throw recordingFailure;
                }
                return super.put(key, value);
            }
        });
        if (storageAlsoFailed) {
            store.failAfter = "errors";
        }
        RuntimeException failure = assertThrows(RuntimeException.class, () -> update(resource, props()));
        assertSame(storageAlsoFailed ? store.lastMutationFailure : recordingFailure, failure);
        assertEquals("UNKNOWN", mutationState(resource, false).path("ownership").asText());
        assertEquals("errors", resource.getPhysicalId(), "failed metadata recording must still reach stack cleanup");
        assertEquals("true", resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        rejecting.set(false);
        store.clearMutationFaults();
        assertThrows(IllegalStateException.class, () -> provisioner.delete(resource, REGION));
        assertNotNull(filter(GROUP, "errors"));
        service.deleteMetricFilter(GROUP, "errors", REGION);
        assertDoesNotThrow(() -> provisioner.delete(resource, REGION));
        assertEquals("UNOWNED", mutationState(resource, false).path("ownership").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE_FAILED", "UPDATE_FAILED"})
    void anUntrackedFailedResourceCannotDeleteAnotherOwner(String status) {
        StackResource resource = create(props());
        resource.getAttributes().remove(STATE);
        resource.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
        resource.setStatus(status);
        service.deleteMetricFilter(GROUP, "errors", REGION);
        create(props().put("FilterPattern", "UNRELATED"));
        JsonNode otherOwner = MAPPER.valueToTree(filter(GROUP, "errors"));
        assertTrue(provisioner.hasPendingRollbackCleanup(resource), "untracked identity must reach safe cleanup inspection");
        assertThrows(IllegalStateException.class, () -> provisioner.delete(resource, REGION));
        assertEquals("UNKNOWN", mutationState(resource, false).path("ownership").asText());
        assertEquals(otherOwner, MAPPER.valueToTree(filter(GROUP, "errors")));
        service.deleteMetricFilter(GROUP, "errors", REGION);
        assertDoesNotThrow(() -> provisioner.delete(resource, REGION));
        assertEquals("UNOWNED", mutationState(resource, false).path("ownership").asText());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"CREATE_IN_PROGRESS", "UPDATE_IN_PROGRESS", "unrecognized"})
    void missingOrUnprovenStatusWithoutTrackingCannotAuthorizeAnUpdate(String status) {
        StackResource resource = create(props());
        resource.getAttributes().remove(STATE);
        resource.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
        resource.setStatus(status);
        service.deleteMetricFilter(GROUP, "errors", REGION);
        create(props().put("FilterPattern", "UNRELATED"));
        JsonNode otherOwner = MAPPER.valueToTree(filter(GROUP, "errors"));
        assertThrows(IllegalStateException.class, () -> update(resource, props().put("FilterPattern", "FATAL")));
        assertEquals("UNKNOWN", mutationState(resource, false).path("ownership").asText());
        assertThrows(IllegalStateException.class, () -> provisioner.delete(resource, REGION));
        assertEquals(otherOwner, MAPPER.valueToTree(filter(GROUP, "errors")));
    }

    private enum MutationFault {
        BEFORE, AFTER, UNKNOWN_BEFORE, UNKNOWN_AFTER;

        boolean after() {
            return name().endsWith("AFTER");
        }

        boolean unknown() {
            return name().startsWith("UNKNOWN");
        }
    }

    private static final class RecordingStore extends InMemoryStorage<String, MetricFilter> {
        final List<String> operations = new ArrayList<>();
        String failBefore;
        String failAfter;
        boolean failDelete;
        boolean failInspection;
        boolean failReads;
        boolean reuseMutationFailure;
        Object monitor;
        Thread mutationThread;
        boolean inspectionHeldMonitor;
        RuntimeException lastMutationFailure;
        String deleteFailureName;
        MutationFault deleteFault;
        Runnable afterDeleteEffect = () -> {};

        void clearMutationFaults() {
            failBefore = null;
            failAfter = null;
            failDelete = false;
            failInspection = false;
            failReads = false;
            reuseMutationFailure = false;
            deleteFailureName = null;
            deleteFault = null;
            mutationThread = null;
            afterDeleteEffect = () -> {};
        }

        private AwsException mutationFailure(String message, boolean unknown) {
            mutationThread = Thread.currentThread();
            inspectionHeldMonitor = false;
            failReads = unknown;
            AwsException failure = new AwsException("ServiceUnavailableException", message, 500);
            lastMutationFailure = failure;
            return failure;
        }

        @Override
        public void put(String key, MetricFilter value) {
            if (value.getFilterName().equals(failBefore)) {
                throw mutationFailure("before write", failInspection);
            }
            operations.add("put:" + value.getFilterName());
            super.put(key, value);
            if (value.getFilterName().equals(failAfter)) {
                throw mutationFailure("after write", failInspection);
            }
        }

        @Override
        public Optional<MetricFilter> get(String key) {
            if (Thread.currentThread() == mutationThread) {
                inspectionHeldMonitor = Thread.holdsLock(monitor);
                mutationThread = null;
            }
            if (failReads) {
                if (reuseMutationFailure) {
                    throw lastMutationFailure;
                }
                throw new AwsException("ServiceUnavailableException", "ownership inspection unavailable", 500);
            }
            return super.get(key);
        }

        @Override
        public void delete(String key) {
            if (failDelete) {
                throw new AwsException("ServiceUnavailableException", "delete failed", 500);
            }
            MetricFilter before = super.get(key).orElse(null);
            boolean inject = before != null && before.getFilterName().equals(deleteFailureName);
            if (inject && !deleteFault.after()) {
                throw mutationFailure("before delete", deleteFault.unknown());
            }
            get(key).ifPresent(f -> operations.add("delete:" + f.getFilterName()));
            super.delete(key);
            if (inject) {
                afterDeleteEffect.run();
                throw mutationFailure("after delete", deleteFault.unknown());
            }
        }
    }
}
