package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2EbsEncryptionServiceTest {

    private Ec2EbsEncryptionService service;

    @BeforeEach
    void setUp() {
        service = new Ec2EbsEncryptionService(new InMemoryStorage<>());
    }

    @Test
    void encryptionByDefaultIsDisabledInitially() {
        assertFalse(service.getEbsEncryptionByDefault("us-east-1"));
    }

    @Test
    void enableAndDisableEncryptionByDefault() {
        assertTrue(service.enableEbsEncryptionByDefault("us-east-1"));
        assertTrue(service.getEbsEncryptionByDefault("us-east-1"));

        assertFalse(service.disableEbsEncryptionByDefault("us-east-1"));
        assertFalse(service.getEbsEncryptionByDefault("us-east-1"));
    }

    @Test
    void encryptionByDefaultIsRegionScoped() {
        service.enableEbsEncryptionByDefault("us-east-1");
        assertTrue(service.getEbsEncryptionByDefault("us-east-1"));
        assertFalse(service.getEbsEncryptionByDefault("eu-west-1"));
    }

    @Test
    void defaultKmsKeyIdIsAwsManagedAlias() {
        assertEquals("alias/aws/ebs", service.getEbsDefaultKmsKeyId("us-east-1"));
    }

    @Test
    void modifyAndResetDefaultKmsKeyId() {
        String keyArn = "arn:aws:kms:us-east-1:111122223333:key/12345678-1234-1234-1234-123456789012";
        assertEquals(keyArn, service.modifyEbsDefaultKmsKeyId("us-east-1", keyArn));
        assertEquals(keyArn, service.getEbsDefaultKmsKeyId("us-east-1"));
        // The custom key applies only to the region it was set in.
        assertEquals("alias/aws/ebs", service.getEbsDefaultKmsKeyId("eu-west-1"));

        assertEquals("alias/aws/ebs", service.resetEbsDefaultKmsKeyId("us-east-1"));
        assertEquals("alias/aws/ebs", service.getEbsDefaultKmsKeyId("us-east-1"));
    }

    @Test
    void modifyDefaultKmsKeyIdRequiresKey() {
        assertThrows(AwsException.class, () -> service.modifyEbsDefaultKmsKeyId("us-east-1", null));
        assertThrows(AwsException.class, () -> service.modifyEbsDefaultKmsKeyId("us-east-1", " "));
    }
}
