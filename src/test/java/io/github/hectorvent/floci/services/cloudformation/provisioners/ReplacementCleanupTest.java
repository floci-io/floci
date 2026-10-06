package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cleanup record's orphan handling in isolation: what a provisioner records and what the engine's merge carries. */
class ReplacementCleanupTest {

    private static StackResource resource(String physicalId) {
        StackResource r = new StackResource();
        r.setLogicalId("Subnet");
        r.setResourceType("AWS::EC2::Subnet");
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }

    @Test
    void anOrphanIsOwedADeleteAndLeavesTheRecordOnceDeleted() {
        StackResource r = resource("subnet-prior");
        List<String> deleted = new ArrayList<>();

        ReplacementCleanup.recordOrphan(r, "subnet-replacement", "AWS::EC2::Subnet", "us-east-1");

        assertTrue(ReplacementCleanup.hasReplacement(r));
        assertEquals("subnet-replacement", ReplacementCleanup.cleanupPhysicalId(r));
        UpdateCleanupResult result = ReplacementCleanup.complete(r, (type, id, region) -> deleted.add(type + ":" + id + ":" + region));
        assertEquals(List.of("AWS::EC2::Subnet:subnet-replacement:us-east-1"), deleted);
        assertTrue(result.complete());
        assertFalse(ReplacementCleanup.hasReplacement(r));
    }

    @Test
    void anOrphanIsNeverRetained() {
        StackResource r = resource("subnet-prior");
        r.setUpdateReplacePolicy("Retain");

        ReplacementCleanup.recordOrphan(r, "subnet-replacement", "AWS::EC2::Subnet", "us-east-1");

        assertEquals("subnet-replacement", ReplacementCleanup.cleanupPhysicalId(r), "Retain keeps what a committed replacement displaced, not a failed update's leftovers");
    }

    @Test
    void aDispatcherStubIsNeverOwedADelete() {
        StackResource r = resource("subnet-replacement");
        List<String> deleted = new ArrayList<>();

        ReplacementCleanup.record(r, stubUpdate(), Map.of("Arn", "arn:aws:stub:::Subnet"));

        String announced = ReplacementCleanup.cleanupPhysicalId(r);
        ReplacementCleanup.complete(r, (type, id, region) -> deleted.add(id));

        assertEquals(List.of(), deleted, "a stub created nothing, so its id may name an entity the stack never owned");
        assertNull(announced);
    }

    @Test
    void aStubCarryingOnlyInternalAttributesBesideItsArnIsStillNeverOwedADelete() {
        StackResource r = resource("subnet-replacement");

        ReplacementCleanup.record(r, stubUpdate(), Map.of("Arn", "arn:aws:stub:::Subnet",
                CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true"));

        assertNull(ReplacementCleanup.cleanupPhysicalId(r));
        assertFalse(ReplacementCleanup.hasReplacement(r));
    }

    /**
     * An older Floci migrated a stub without dropping the stub's Arn, so a real resource can still
     * carry it beside its own attributes. It names a real entity, so a rename owes its delete.
     */
    @Test
    void aRealResourceThatStillCarriesAStubArnIsOwedItsDelete() {
        StackResource r = resource("DashTwo");
        List<String> deleted = new ArrayList<>();

        ReplacementCleanup.record(r, new ProvisionContext(null, "us-east-1", "000000000000", "stack", "Dashboard-1a2b3c4d"),
                Map.of("Arn", "arn:aws:stub:::Dashboard", "FlociDashboardNameMode", "generated"));

        assertEquals("Dashboard-1a2b3c4d", ReplacementCleanup.cleanupPhysicalId(r));
        ReplacementCleanup.complete(r, (type, id, region) -> deleted.add(id));
        assertEquals(List.of("Dashboard-1a2b3c4d"), deleted);
    }

    /**
     * A group attachment keeps its state in internal attributes only, so one an older Floci migrated
     * from a stub carries nothing else beside the stub Arn. Its id is not a stub's, so a replacement
     * owes its delete.
     */
    @Test
    void aMigratedResourceWithOnlyInternalAttributesBesideAStubArnIsOwedItsDelete() {
        StackResource r = resource("pool|group|other-user");
        r.setLogicalId("Membership");
        r.setResourceType("AWS::Cognito::UserPoolUserToGroupAttachment");

        ReplacementCleanup.record(r, new ProvisionContext(null, "us-east-1", "000000000000", "stack", "pool|group|user"),
                Map.of("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + "Membership",
                        "__FlociCognitoMemberships", "{\"pool|group|user\":[\"pool\",\"group\",\"user\"]}"));

        assertTrue(ReplacementCleanup.hasReplacement(r));
        assertEquals("pool|group|user", ReplacementCleanup.cleanupPhysicalId(r));
    }

    @Test
    void rollingBackAStubMigrationRestoresTheStubAndDeletesTheReplacement() {
        StackResource r = resource("subnet-replacement");
        List<String> deleted = new ArrayList<>();
        ReplacementCleanup.record(r, stubUpdate(), Map.of("Arn", "arn:aws:stub:::Subnet"));

        assertTrue(ReplacementCleanup.rollback(r, (type, id, region) -> deleted.add(id)));

        assertEquals("Subnet-1a2b3c4d", r.getPhysicalId());
        assertEquals("arn:aws:stub:::Subnet", r.getAttributes().get("Arn"));
        assertEquals(List.of("subnet-replacement"), deleted);
        assertFalse(ReplacementCleanup.hasReplacement(r));
    }

    private static ProvisionContext stubUpdate() {
        return new ProvisionContext(null, "us-east-1", "000000000000", "stack", "Subnet-1a2b3c4d");
    }

    /** A stub migrated under the stub's own id created that entity, so a rollback deletes it and puts the stub back. */
    @Test
    void rollingBackAStubMigrationThatKeptTheStubIdDeletesWhatItCreated() {
        StackResource r = resource("Subnet-1a2b3c4d");
        r.getAttributes().put("VpcId", "vpc-1a2b3c4d");
        List<String> deleted = new ArrayList<>();
        ReplacementCleanup.record(r, stubUpdate(), Map.of("Arn", "arn:aws:stub:::Subnet"));
        assertNull(ReplacementCleanup.cleanupPhysicalId(r), "the commit path must never delete what the migration created");

        assertTrue(ReplacementCleanup.rollback(r, (type, id, region) -> deleted.add(id)));

        assertEquals("Subnet-1a2b3c4d", r.getPhysicalId());
        assertEquals(Map.of("Arn", "arn:aws:stub:::Subnet"), r.getAttributes());
        assertEquals(List.of("Subnet-1a2b3c4d"), deleted);
        assertFalse(ReplacementCleanup.hasReplacement(r));
    }

    /** The restored stub has no live entity, so the entity its rollback could not delete is owed under the stub's own id. */
    @Test
    void aSameIdStubRollbackWhoseDeleteFailedIsRetriedByTheNextCleanup() {
        StackResource r = resource("Subnet-1a2b3c4d");
        r.getAttributes().put("VpcId", "vpc-1a2b3c4d");
        List<String> deleted = new ArrayList<>();
        ReplacementCleanup.record(r, stubUpdate(), Map.of("Arn", "arn:aws:stub:::Subnet"));

        assertThrows(IllegalStateException.class, () -> ReplacementCleanup.rollback(r, (type, id, region) -> {
            throw new IllegalStateException("DependencyViolation");
        }));

        assertEquals("Subnet-1a2b3c4d", ReplacementCleanup.cleanupPhysicalId(r));
        UpdateCleanupResult result = ReplacementCleanup.complete(r, (type, id, region) -> deleted.add(id));
        assertEquals(List.of("Subnet-1a2b3c4d"), deleted);
        assertTrue(result.complete());
        assertFalse(ReplacementCleanup.hasReplacement(r));
    }

    @Test
    void mergingCarriesUnknownOrphansWithTheirAttemptsAndSkipsKnownOrOwnIds() {
        // The attempt's own id is left out of the record on purpose (complete/cleanupPhysicalId skip
        // it), which is why the engine merges the record onto the restored previous resource.
        StackResource attempted = resource("subnet-attempt");
        ReplacementCleanup.recordOrphan(attempted, "subnet-replacement", "AWS::EC2::Subnet", "us-east-1");
        ReplacementCleanup.recordOrphan(attempted, "subnet-old-orphan", "AWS::EC2::Subnet", "us-east-1");
        // one failed attempt already counted on both
        ReplacementCleanup.complete(attempted, (type, id, region) -> { throw new IllegalStateException("still in use"); });
        StackResource previous = resource("subnet-prior");
        ReplacementCleanup.recordOrphan(previous, "subnet-old-orphan", "AWS::EC2::Subnet", "us-east-1");

        ReplacementCleanup.mergeDisplaced(previous, attempted);

        String record = previous.getAttributes().get(CfnRollback.REPLACEMENT_CLEANUP_ATTR);
        assertTrue(record.indexOf("subnet-old-orphan") == record.lastIndexOf("subnet-old-orphan"), "the known orphan is listed once: " + record);
        assertTrue(record.contains("\"physicalId\":\"subnet-replacement\",\"resourceType\":\"AWS::EC2::Subnet\",\"region\":\"us-east-1\",\"retainable\":false,\"cleanupAttempts\":1"),
                "the new orphan keeps its attempt count: " + record);
        List<String> owed = new ArrayList<>();
        ReplacementCleanup.complete(previous, (type, id, region) -> owed.add(id));
        assertEquals(List.of("subnet-old-orphan", "subnet-replacement"), owed);
        assertFalse(ReplacementCleanup.hasReplacement(previous));
    }

    @Test
    void mergingNothingLeavesTheTargetUntouched() {
        StackResource previous = resource("subnet-prior");

        ReplacementCleanup.mergeDisplaced(previous, resource("subnet-replacement"));

        assertNull(previous.getAttributes().get(CfnRollback.REPLACEMENT_CLEANUP_ATTR));
    }
}
