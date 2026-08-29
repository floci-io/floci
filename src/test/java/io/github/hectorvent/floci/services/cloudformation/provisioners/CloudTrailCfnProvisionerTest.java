package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudTrailCfnProvisionerTest {

    private final CloudTrailService trailService = mock(CloudTrailService.class);
    private final CloudTrailCfnProvisioner provisioner = new CloudTrailCfnProvisioner(trailService);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsTrailAppliesBasicSelectorsAndStartsLoggingWhenRequested() {
        Trail created = trail("audit-trail", "arn:aws:cloudtrail:us-east-1:111122223333:trail/audit-trail");
        when(trailService.createTrail(eq("us-east-1"), eq("audit-trail"), eq("dest-bucket"),
                eq((String) null), eq((String) null), eq(true), eq(false), eq(false), eq(false)))
                .thenReturn(created);
        when(trailService.putEventSelectors(eq("us-east-1"), eq("audit-trail"), anyList()))
                .thenReturn(List.of());

        ObjectNode props = mapper.createObjectNode()
                .put("TrailName", "audit-trail")
                .put("S3BucketName", "dest-bucket")
                .put("IsLogging", true);
        props.set("EventSelectors", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("ReadWriteType", "All")));
        StackResource resource = resource();

        provisioner.provision(resource, props, context());

        ArgumentCaptor<List<EventSelector>> selectorsCaptor = ArgumentCaptor.forClass(List.class);
        verify(trailService).putEventSelectors(eq("us-east-1"), eq("audit-trail"), selectorsCaptor.capture());
        assertEquals(1, selectorsCaptor.getValue().size());
        assertEquals("All", selectorsCaptor.getValue().get(0).readWriteType());
        verify(trailService).startLogging("us-east-1", "audit-trail");
        assertEquals(created.trailArn(), resource.getPhysicalId());
        assertEquals(created.trailArn(), resource.getAttributes().get("Arn"));
    }

    @Test
    void updateUsesExistingPhysicalIdAndSkipsSelectorsWhenAbsent() {
        Trail updated = trail("audit-trail", "arn:aws:cloudtrail:us-east-1:111122223333:trail/audit-trail");
        when(trailService.updateTrail(eq("us-east-1"),
                eq("arn:aws:cloudtrail:us-east-1:111122223333:trail/audit-trail"),
                eq("dest-bucket"), any(), any(), any(), any(), any(), any()))
                .thenReturn(updated);

        ObjectNode props = mapper.createObjectNode()
                .put("TrailName", "audit-trail")
                .put("S3BucketName", "dest-bucket");
        StackResource resource = resource();
        resource.setPhysicalId("arn:aws:cloudtrail:us-east-1:111122223333:trail/audit-trail");

        provisioner.provision(resource, props, context());

        verify(trailService, never()).putEventSelectors(anyString(), anyString(), anyList());
        verify(trailService, never()).putAdvancedEventSelectors(anyString(), anyString(), anyList());
        verify(trailService, never()).startLogging(anyString(), anyString());
        assertEquals(updated.trailArn(), resource.getPhysicalId());
    }

    @Test
    void bothBasicAndAdvancedSelectorsAreRejected() {
        Trail created = trail("audit-trail", "arn:aws:cloudtrail:us-east-1:111122223333:trail/audit-trail");
        when(trailService.createTrail(eq("us-east-1"), eq("audit-trail"), eq("dest-bucket"),
                any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(created);

        ObjectNode props = mapper.createObjectNode()
                .put("TrailName", "audit-trail")
                .put("S3BucketName", "dest-bucket");
        props.set("EventSelectors", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("ReadWriteType", "All")));
        props.set("AdvancedEventSelectors", mapper.createArrayNode().add(mapper.createObjectNode()
                .put("Name", "x")
                .set("FieldSelectors", mapper.createArrayNode().add(mapper.createObjectNode()
                        .put("Field", "eventCategory")
                        .set("Equals", mapper.createArrayNode().add("Data"))))));
        StackResource resource = resource();

        assertThrows(io.github.hectorvent.floci.core.common.AwsException.class,
                () -> provisioner.provision(resource, props, context()));
        assertEquals(created.trailArn(), resource.getPhysicalId(),
                "physicalId must be recorded right after createTrail succeeds, "
                        + "before selector validation can fail and leak the created trail");
    }

    @Test
    void updateStopsLoggingWhenIsLoggingExplicitlyFalse() {
        Trail updated = trail("audit-trail", "arn:aws:cloudtrail:us-east-1:111122223333:trail/audit-trail");
        when(trailService.updateTrail(eq("us-east-1"),
                eq("arn:aws:cloudtrail:us-east-1:111122223333:trail/audit-trail"),
                eq("dest-bucket"), any(), any(), any(), any(), any(), any()))
                .thenReturn(updated);

        ObjectNode props = mapper.createObjectNode()
                .put("TrailName", "audit-trail")
                .put("S3BucketName", "dest-bucket")
                .put("IsLogging", false);
        StackResource resource = resource();
        resource.setPhysicalId("arn:aws:cloudtrail:us-east-1:111122223333:trail/audit-trail");

        provisioner.provision(resource, props, context());

        verify(trailService).stopLogging("us-east-1", "arn:aws:cloudtrail:us-east-1:111122223333:trail/audit-trail");
        verify(trailService, never()).startLogging(anyString(), anyString());
    }

    @Test
    void deleteRemovesExistingTrailAndIsIdempotentWhenMissing() {
        when(trailService.describeTrails(eq("us-east-1"), eq(List.of("trail-arn"))))
                .thenReturn(List.of(trail("audit-trail", "trail-arn")));

        provisioner.delete("AWS::CloudTrail::Trail", "trail-arn", "us-east-1");

        verify(trailService).deleteTrail("us-east-1", "trail-arn");
    }

    @Test
    void deleteIsNoOpWhenTrailAlreadyGone() {
        when(trailService.describeTrails(eq("us-east-1"), eq(List.of("missing-arn"))))
                .thenReturn(List.of());

        provisioner.delete("AWS::CloudTrail::Trail", "missing-arn", "us-east-1");

        verify(trailService, never()).deleteTrail(anyString(), anyString());
    }

    private Trail trail(String name, String arn) {
        return new Trail(name, arn, "dest-bucket", null, null, true, false,
                "us-east-1", false, false, false, false);
    }

    private ProvisionContext context() {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any(JsonNode.class))).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "111122223333", "stack");
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("AuditTrail");
        resource.setResourceType("AWS::CloudTrail::Trail");
        resource.setAttributes(new HashMap<>());
        return resource;
    }
}
