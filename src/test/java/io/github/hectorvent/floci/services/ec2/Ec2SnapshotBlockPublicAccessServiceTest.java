package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2SnapshotBlockPublicAccessServiceTest {

    private Ec2SnapshotBlockPublicAccessService service;

    @BeforeEach
    void setUp() {
        service = new Ec2SnapshotBlockPublicAccessService(new InMemoryStorage<>());
    }

    @Test
    void unconfiguredRegionIsUnblocked() {
        assertEquals("unblocked", service.getSnapshotBlockPublicAccessState("us-east-1"));
    }

    @Test
    void enableStoresBlockAllSharing() {
        assertEquals("block-all-sharing",
                service.enableSnapshotBlockPublicAccess("us-east-1", "block-all-sharing"));
        assertEquals("block-all-sharing", service.getSnapshotBlockPublicAccessState("us-east-1"));
    }

    @Test
    void enableNarrowsAnExistingBlockToNewSharing() {
        service.enableSnapshotBlockPublicAccess("us-east-1", "block-all-sharing");

        assertEquals("block-new-sharing",
                service.enableSnapshotBlockPublicAccess("us-east-1", "block-new-sharing"));
        assertEquals("block-new-sharing", service.getSnapshotBlockPublicAccessState("us-east-1"));
    }

    @Test
    void enableRejectsUnblocked() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.enableSnapshotBlockPublicAccess("us-east-1", "unblocked"));
        assertEquals("InvalidParameterValue", thrown.getErrorCode());
        assertEquals("unblocked", service.getSnapshotBlockPublicAccessState("us-east-1"));
    }

    @Test
    void enableRejectsAnUnknownState() {
        AwsException thrown = assertThrows(AwsException.class,
                () -> service.enableSnapshotBlockPublicAccess("us-east-1", "block-everything"));
        assertEquals("InvalidParameterValue", thrown.getErrorCode());
    }

    @Test
    void enableRequiresState() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.enableSnapshotBlockPublicAccess("us-east-1", null));
        assertEquals("MissingParameter", missing.getErrorCode());

        AwsException blank = assertThrows(AwsException.class,
                () -> service.enableSnapshotBlockPublicAccess("us-east-1", " "));
        assertEquals("MissingParameter", blank.getErrorCode());
    }

    @Test
    void disableReturnsUnblocked() {
        service.enableSnapshotBlockPublicAccess("us-east-1", "block-all-sharing");

        assertEquals("unblocked", service.disableSnapshotBlockPublicAccess("us-east-1"));
        assertEquals("unblocked", service.getSnapshotBlockPublicAccessState("us-east-1"));
    }

    @Test
    void stateIsRegionScoped() {
        service.enableSnapshotBlockPublicAccess("us-east-1", "block-all-sharing");

        assertEquals("block-all-sharing", service.getSnapshotBlockPublicAccessState("us-east-1"));
        assertEquals("unblocked", service.getSnapshotBlockPublicAccessState("eu-west-1"));

        service.enableSnapshotBlockPublicAccess("eu-west-1", "block-new-sharing");
        assertEquals("block-all-sharing", service.getSnapshotBlockPublicAccessState("us-east-1"));
        assertEquals("block-new-sharing", service.getSnapshotBlockPublicAccessState("eu-west-1"));

        service.disableSnapshotBlockPublicAccess("eu-west-1");
        assertEquals("block-all-sharing", service.getSnapshotBlockPublicAccessState("us-east-1"));
    }
}
