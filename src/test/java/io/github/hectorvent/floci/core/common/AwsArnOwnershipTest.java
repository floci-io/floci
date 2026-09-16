package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ownership predicates for a parsed ARN. Both are generic on purpose: RdsService already carries
 * its own copy of the same two checks inside targetGroupBelongsTo, and Lambda layer resolution
 * needs them too, so the resource-shaped part stays with the caller and only these live here.
 */
class AwsArnOwnershipTest {

    private static final String LOCAL = "000000000000";

    private static AwsArnUtils.Arn arn(String value) {
        return AwsArnUtils.parse(value);
    }

    @Test
    void awsPartitionIsNotForeign() {
        assertFalse(AwsArnUtils.isForeignPartition(
                arn("arn:aws:lambda:us-east-1:000000000000:layer:l:1")));
    }

    @Test
    void otherPartitionsAreForeign() {
        assertTrue(AwsArnUtils.isForeignPartition(
                arn("arn:aws-cn:lambda:cn-north-1:000000000000:layer:l:1")));
        assertTrue(AwsArnUtils.isForeignPartition(
                arn("arn:aws-us-gov:lambda:us-gov-west-1:000000000000:layer:l:1")));
    }

    @Test
    void anEmptyPartitionIsNotForeign() {
        assertFalse(AwsArnUtils.isForeignPartition(arn("arn::lambda:us-east-1:000000000000:layer:l:1")));
    }

    @Test
    void theCallersOwnAccountIsNotForeign() {
        assertFalse(AwsArnUtils.isForeignAccount(
                arn("arn:aws:lambda:us-east-1:000000000000:layer:l:1"), LOCAL));
    }

    @Test
    void anotherAccountIsForeign() {
        assertTrue(AwsArnUtils.isForeignAccount(
                arn("arn:aws:lambda:us-east-1:017000801446:layer:l:1"), LOCAL));
    }

    @Test
    void anEmptyAccountIsNotForeign() {
        // AWS-managed layers use arn:aws:lambda:::awslayer:<name>, which names no account and is
        // not somebody else's resource.
        assertFalse(AwsArnUtils.isForeignAccount(arn("arn:aws:lambda:::awslayer:AWSLambda-Python"), LOCAL));
    }

    @Test
    void nullIsNotForeign() {
        assertFalse(AwsArnUtils.isForeignPartition(null));
        assertFalse(AwsArnUtils.isForeignAccount(null, LOCAL));
    }
}
