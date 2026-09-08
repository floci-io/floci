package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
