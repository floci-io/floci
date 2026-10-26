package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical user ID is the S3 wire identity of an account: 64 lowercase hex characters,
 * stable for the same account and distinct per account, whatever the partition or storage mode.
 */
class AwsCanonicalUserIdTest {

    @Test
    void isSixtyFourLowercaseHexCharacters() {
        assertTrue(AwsCanonicalUserId.forAccount("000000000000").matches("[0-9a-f]{64}"));
        assertTrue(AwsCanonicalUserId.forAccount("123456789012").matches("[0-9a-f]{64}"));
    }

    @Test
    void isStableForTheSameAccount() {
        assertEquals(AwsCanonicalUserId.forAccount("000000000000"),
                AwsCanonicalUserId.forAccount("000000000000"));
    }

    @Test
    void differsPerAccount() {
        assertNotEquals(AwsCanonicalUserId.forAccount("000000000000"),
                AwsCanonicalUserId.forAccount("000000000001"));
    }
}
