package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudtrail.CloudTrailService;
import io.floci.aws.services.cloudtrail.model.EventSelector;
import io.floci.aws.services.cloudtrail.model.Trail;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        assertThrows(AwsException.class,
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
        return new ProvisionContext(ProvisionContexts.passthroughEngine(), "us-east-1", "111122223333", "stack");
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("AuditTrail");
        resource.setResourceType("AWS::CloudTrail::Trail");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    // --- dropped S3KeyPrefix, against a real in-memory CloudTrailService ---

    private static final String WITH_PREFIX = """
            {"TrailName":"audit","S3BucketName":"dest-bucket","SnsTopicName":"topic-1","S3KeyPrefix":"orig/"}
            """;
    private static final String WITHOUT_PREFIX = """
            {"TrailName":"audit","S3BucketName":"dest-bucket","SnsTopicName":"topic-1"}
            """;

    private CloudTrailService realTrails() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenReturn(AccountAwareStorageBackend.inMemory("111122223333"));
        return new CloudTrailService(storageFactory, new RegionResolver("us-east-1", "111122223333"),
                mock(IamService.class), new ObjectMapper());
    }

    private StackResource deployTrail(CloudTrailService real, String properties) throws Exception {
        StackResource r = resource();
        new CloudTrailCfnProvisioner(real).provision(r, mapper.readTree(properties), context());
        return r;
    }

    private void updateTrail(CloudTrailService real, StackResource r, String previous, String current)
            throws Exception {
        new CloudTrailCfnProvisioner(real).provision(r, mapper.readTree(current),
                ProvisionContexts.update("us-east-1", "111122223333", "stack",
                        r.getPhysicalId(), previous));
    }

    @Test
    void s3KeyPrefixDroppedFromTheTemplateIsClearedOnUpdate() throws Exception {
        CloudTrailService real = realTrails();
        StackResource r = deployTrail(real, WITH_PREFIX);
        assertEquals("orig/", real.getTrail("us-east-1", "audit").s3KeyPrefix());

        updateTrail(real, r, WITH_PREFIX, WITHOUT_PREFIX);

        assertNull(real.getTrail("us-east-1", "audit").s3KeyPrefix());
    }

    @Test
    void changedS3KeyPrefixStillApplies() throws Exception {
        CloudTrailService real = realTrails();
        StackResource r = deployTrail(real, WITH_PREFIX);

        updateTrail(real, r, WITH_PREFIX, WITH_PREFIX.replace("orig/", "changed/"));

        assertEquals("changed/", real.getTrail("us-east-1", "audit").s3KeyPrefix());
    }

    @Test
    void emptyS3KeyPrefixIsStoredAsGivenAndALaterDropClearsIt() throws Exception {
        CloudTrailService real = realTrails();
        StackResource r = deployTrail(real, WITH_PREFIX);
        String empty = WITH_PREFIX.replace("\"orig/\"", "\"\"");

        updateTrail(real, r, WITH_PREFIX, empty);
        assertEquals("", real.getTrail("us-east-1", "audit").s3KeyPrefix());

        updateTrail(real, r, empty, WITHOUT_PREFIX);
        assertNull(real.getTrail("us-east-1", "audit").s3KeyPrefix());
    }

    @Test
    void s3KeyPrefixSetOutsideTheStackSurvivesAnUpdateThatNeverDeclaredIt() throws Exception {
        CloudTrailService real = realTrails();
        StackResource r = deployTrail(real, WITHOUT_PREFIX);
        real.updateTrail("us-east-1", "audit", null, "outside/", null, null, null, null, null);

        updateTrail(real, r, WITHOUT_PREFIX, WITHOUT_PREFIX.replace("dest-bucket", "other-bucket"));

        Trail stored = real.getTrail("us-east-1", "audit");
        assertEquals("outside/", stored.s3KeyPrefix());
        assertEquals("other-bucket", stored.s3BucketName());
    }

    @Test
    void droppingTheS3KeyPrefixKeepsTheBucketAndTheTopic() throws Exception {
        CloudTrailService real = realTrails();
        StackResource r = deployTrail(real, WITH_PREFIX);
        String topicBefore = real.getTrail("us-east-1", "audit").snsTopicArn();
        assertNotNull(topicBefore);

        updateTrail(real, r, WITH_PREFIX, WITHOUT_PREFIX);

        Trail stored = real.getTrail("us-east-1", "audit");
        assertEquals("dest-bucket", stored.s3BucketName());
        assertEquals(topicBefore, stored.snsTopicArn());
    }
}
