package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ec2BlockPublicAccessServiceTest {

    private Ec2BlockPublicAccessService service;

    @BeforeEach
    void setUp() {
        service = new Ec2BlockPublicAccessService(new InMemoryStorage<>(), new InMemoryStorage<>());
    }

    @Test
    void unconfiguredRegionsAreUnblocked() {
        assertEquals("unblocked",
                service.getState(Ec2BlockPublicAccessService.Setting.IMAGE, "us-east-1"));
        assertEquals("unblocked",
                service.getState(Ec2BlockPublicAccessService.Setting.SNAPSHOT, "us-east-1"));
    }

    @Test
    void imageSettingAcceptsOnlyBlockNewSharing() {
        assertEquals("block-new-sharing", service.enable(Ec2BlockPublicAccessService.Setting.IMAGE,
                "us-east-1", "block-new-sharing"));

        AwsException unblocked = assertThrows(AwsException.class,
                () -> service.enable(Ec2BlockPublicAccessService.Setting.IMAGE, "us-east-1", "unblocked"));
        assertEquals("InvalidParameterValue", unblocked.getErrorCode());

        AwsException blockAll = assertThrows(AwsException.class,
                () -> service.enable(Ec2BlockPublicAccessService.Setting.IMAGE,
                        "us-east-1", "block-all-sharing"));
        assertEquals("InvalidParameterValue", blockAll.getErrorCode());
        assertEquals("block-new-sharing",
                service.getState(Ec2BlockPublicAccessService.Setting.IMAGE, "us-east-1"));
    }

    @Test
    void snapshotSettingAcceptsBothBlockingStates() {
        assertEquals("block-all-sharing", service.enable(Ec2BlockPublicAccessService.Setting.SNAPSHOT,
                "us-east-1", "block-all-sharing"));
        assertEquals("block-new-sharing", service.enable(Ec2BlockPublicAccessService.Setting.SNAPSHOT,
                "us-east-1", "block-new-sharing"));
        assertEquals("block-new-sharing",
                service.getState(Ec2BlockPublicAccessService.Setting.SNAPSHOT, "us-east-1"));
    }

    @Test
    void missingStateUsesTheSettingParameterName() {
        AwsException image = assertThrows(AwsException.class,
                () -> service.validateEnableState(Ec2BlockPublicAccessService.Setting.IMAGE, null));
        assertEquals("MissingParameter", image.getErrorCode());
        assertEquals("The request must contain the parameter ImageBlockPublicAccessState", image.getMessage());

        AwsException snapshot = assertThrows(AwsException.class,
                () -> service.validateEnableState(Ec2BlockPublicAccessService.Setting.SNAPSHOT, " "));
        assertEquals("MissingParameter", snapshot.getErrorCode());
        assertEquals("The request must contain the parameter State", snapshot.getMessage());
    }

    @Test
    void validationDoesNotChangeStoredState() {
        service.enable(Ec2BlockPublicAccessService.Setting.SNAPSHOT, "us-east-1", "block-all-sharing");

        assertThrows(AwsException.class,
                () -> service.validateEnableState(Ec2BlockPublicAccessService.Setting.SNAPSHOT, "unblocked"));
        assertThrows(AwsException.class,
                () -> service.validateEnableState(Ec2BlockPublicAccessService.Setting.SNAPSHOT, null));
        assertEquals("block-all-sharing",
                service.getState(Ec2BlockPublicAccessService.Setting.SNAPSHOT, "us-east-1"));
    }

    @Test
    void disableReturnsUnblocked() {
        service.enable(Ec2BlockPublicAccessService.Setting.IMAGE, "us-east-1", "block-new-sharing");

        assertEquals("unblocked",
                service.disable(Ec2BlockPublicAccessService.Setting.IMAGE, "us-east-1"));
        assertEquals("unblocked",
                service.getState(Ec2BlockPublicAccessService.Setting.IMAGE, "us-east-1"));
    }

    @Test
    void statesAreScopedBySettingAndRegion() {
        service.enable(Ec2BlockPublicAccessService.Setting.IMAGE, "us-east-1", "block-new-sharing");
        service.enable(Ec2BlockPublicAccessService.Setting.SNAPSHOT, "eu-west-1", "block-all-sharing");

        assertEquals("block-new-sharing",
                service.getState(Ec2BlockPublicAccessService.Setting.IMAGE, "us-east-1"));
        assertEquals("unblocked",
                service.getState(Ec2BlockPublicAccessService.Setting.IMAGE, "eu-west-1"));
        assertEquals("unblocked",
                service.getState(Ec2BlockPublicAccessService.Setting.SNAPSHOT, "us-east-1"));
        assertEquals("block-all-sharing",
                service.getState(Ec2BlockPublicAccessService.Setting.SNAPSHOT, "eu-west-1"));
    }
}
