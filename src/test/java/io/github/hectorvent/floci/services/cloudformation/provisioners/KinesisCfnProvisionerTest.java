package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::Kinesis::Stream}, whose provision body doubles as its update path. */
class KinesisCfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";

    private KinesisService kinesis;
    private KinesisCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;

    @BeforeEach
    void setUp() {
        kinesis = mock(KinesisService.class);
        provisioner = new KinesisCfnProvisioner(kinesis);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(kinesis.listTagsForStream(anyString(), anyString())).thenReturn(Map.of());
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static KinesisStream stream(String arn, int retentionHours) {
        KinesisStream s = new KinesisStream();
        s.setStreamArn(arn);
        s.setRetentionPeriodHours(retentionHours);
        return s;
    }

    private StackResource provision(String json, String priorPhysicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("Stream");
        r.setResourceType("AWS::Kinesis::Stream");
        r.setPhysicalId(priorPhysicalId);
        r.setAttributes(new HashMap<>());
        provisioner.provision(r, props(json),
                new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId));
        return r;
    }

    @Test
    void createUsesTheExplicitNameAndExposesTheArn() {
        when(kinesis.createStream(eq("s"), anyInt(), any(), eq(REGION)))
                .thenReturn(stream("arn:aws:kinesis:us-east-1:000000000000:stream/s", 24));

        StackResource r = provision("""
                {"Name": "s", "ShardCount": "3"}
                """, null);

        verify(kinesis).createStream("s", 3, null, REGION);
        assertEquals("s", r.getPhysicalId(), "Ref is the stream name");
        // aws-kinesis-stream.json readOnlyProperties
        assertEquals(Map.of("Arn", "arn:aws:kinesis:us-east-1:000000000000:stream/s"), r.getAttributes());
    }

    @Test
    void shardCountDefaultsToOneWhenAbsentOrUnparseable() {
        when(kinesis.createStream(anyString(), anyInt(), any(), anyString())).thenReturn(stream("arn", 24));

        provision("""
                {"Name": "a"}
                """, null);
        verify(kinesis).createStream("a", 1, null, REGION);

        provision("""
                {"Name": "b", "ShardCount": "many"}
                """, null);
        verify(kinesis).createStream("b", 1, null, REGION);
    }

    @Test
    void streamModeDetailsAreForwarded() {
        when(kinesis.createStream(anyString(), anyInt(), any(), anyString())).thenReturn(stream("arn", 24));

        provision("""
                {"Name": "s", "StreamModeDetails": {"StreamMode": "ON_DEMAND"}}
                """, null);

        verify(kinesis).createStream("s", 1, "ON_DEMAND", REGION);
    }

    /**
     * provision() re-runs on every UpdateStack, so an unchanged name must reconcile rather than
     * call createStream again, which the service rejects with ResourceInUseException.
     */
    @Test
    void anUnchangedNameReconcilesInsteadOfRecreating() {
        when(kinesis.describeStream("s", REGION)).thenReturn(stream("arn", 24));

        provision("""
                {"Name": "s"}
                """, "s");

        verify(kinesis, never()).createStream(anyString(), anyInt(), any(), anyString());
        verify(kinesis).updateStreamMode("s", "PROVISIONED", REGION);
    }

    @Test
    void retentionMovesInTheRightDirectionOnly() {
        when(kinesis.describeStream("s", REGION)).thenReturn(stream("arn", 24));

        provision("""
                {"Name": "s", "RetentionPeriodHours": "48"}
                """, "s");
        verify(kinesis).increaseStreamRetentionPeriod("s", 48, REGION);

        provision("""
                {"Name": "s", "RetentionPeriodHours": "12"}
                """, "s");
        verify(kinesis).decreaseStreamRetentionPeriod("s", 12, REGION);

        provision("""
                {"Name": "s", "RetentionPeriodHours": "24"}
                """, "s");
        verify(kinesis, never()).increaseStreamRetentionPeriod("s", 24, REGION);
        verify(kinesis, never()).decreaseStreamRetentionPeriod("s", 24, REGION);
    }

    @Test
    void reconcileRemovesTagsDroppedFromTheTemplate() {
        when(kinesis.describeStream("s", REGION)).thenReturn(stream("arn", 24));
        when(kinesis.listTagsForStream("s", REGION)).thenReturn(Map.of("stale", "1", "kept", "2"));

        provision("""
                {"Name": "s", "Tags": [{"Key": "kept", "Value": "2"}]}
                """, "s");

        verify(kinesis).removeTagsFromStream("s", List.of("stale"), REGION);
        verify(kinesis).addTagsToStream("s", Map.of("kept", "2"), REGION);
    }

    /** A stream deleted out of band since the last update falls back to a fresh create. */
    @Test
    void aMissingStreamFallsBackToCreate() {
        when(kinesis.describeStream("s", REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 400));
        when(kinesis.createStream(anyString(), anyInt(), any(), anyString())).thenReturn(stream("arn", 24));

        provision("""
                {"Name": "s"}
                """, "s");

        verify(kinesis).createStream("s", 1, null, REGION);
    }

    @Test
    void aRenameCreatesTheNewStreamAndBestEffortDeletesTheOld() {
        when(kinesis.createStream(eq("new"), anyInt(), any(), anyString())).thenReturn(stream("arn", 24));

        StackResource r = provision("""
                {"Name": "new"}
                """, "old");

        assertEquals("new", r.getPhysicalId());
        verify(kinesis).deleteStream("old", REGION);
    }

    /** A failure deleting the renamed-away stream must not fail the resource. */
    @Test
    void aFailedCleanupOfTheRenamedStreamIsSwallowed() {
        when(kinesis.createStream(eq("new"), anyInt(), any(), anyString())).thenReturn(stream("arn", 24));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(kinesis).deleteStream("old", REGION);

        StackResource r = provision("""
                {"Name": "new"}
                """, "old");

        assertEquals("new", r.getPhysicalId(), "the new stream is still the resource's physical id");
    }

    @Test
    void anUnnamedStreamGetsAGeneratedNameKeptAcrossUpdates() {
        when(kinesis.createStream(anyString(), anyInt(), any(), anyString())).thenReturn(stream("arn", 24));

        StackResource created = provision("{}", null);
        assertTrue(created.getPhysicalId().startsWith("my-stack-Stream-"), created.getPhysicalId());

        when(kinesis.describeStream(created.getPhysicalId(), REGION)).thenReturn(stream("arn", 24));
        StackResource updated = provision("{}", created.getPhysicalId());
        assertEquals(created.getPhysicalId(), updated.getPhysicalId());
    }

    @Test
    void deleteReachesTheService() {
        provisioner.delete("AWS::Kinesis::Stream", "s", REGION);
        verify(kinesis).deleteStream("s", REGION);
    }
}
