package io.floci.aws.services.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bypass a delete or retention change hands S3Service. With an access check on, it is the
 * header only for a request that overrides a governance lock and whose bypass permission the checks
 * approved, so a lock set after the checks is refused rather than bypassed without the permission.
 */
class S3ControllerGovernanceBypassTest {

    @Test
    void withNoCheckOnTheHeaderIsHandedOnAsSent() {
        assertTrue(S3Controller.effectiveBypass(true, false, false, null));
        assertFalse(S3Controller.effectiveBypass(false, false, false, null));
    }

    @Test
    void s3sOwnCheckHandsItOnOnlyForARequestThatOverridesALock() {
        assertTrue(S3Controller.effectiveBypass(true, true, true, null));
        assertFalse(S3Controller.effectiveBypass(true, false, true, null));
        assertFalse(S3Controller.effectiveBypass(false, true, true, null));
    }

    @Test
    void anIamCheckThatFoundNoLockToOverrideHandsItOnForNone() {
        // IAM saw no lock, so it checked no bypass permission: a lock S3Controller sees afterwards was
        // set after IAM's check, and the operation must refuse it.
        assertFalse(S3Controller.effectiveBypass(true, true, false, false));
        assertFalse(S3Controller.effectiveBypass(true, true, true, false));
        assertTrue(S3Controller.effectiveBypass(true, true, false, true));
        assertFalse(S3Controller.effectiveBypass(true, false, false, true));
    }
}
