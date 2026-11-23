package io.floci.aws.services.s3;

import io.floci.aws.services.s3.S3Service.ObjectEncryption;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class S3ObjectEncryptionTest {

    @Test
    void anSseKmsEncryptionKeepsItsKeyId() {
        assertEquals("key-1", new ObjectEncryption("aws:kms", "key-1").keptKmsKeyId());
    }

    @Test
    void aDualLayerSseKmsEncryptionKeepsItsKeyId() {
        assertEquals("key-1", new ObjectEncryption("aws:kms:dsse", "key-1").keptKmsKeyId());
    }

    @Test
    void anEncryptionThatIsNotSseKmsKeepsNoKeyId() {
        assertNull(new ObjectEncryption("AES256", "key-1").keptKmsKeyId());
    }

    @Test
    void noAlgorithmKeepsNoKeyId() {
        assertNull(new ObjectEncryption(null, "key-1").keptKmsKeyId());
    }
}
