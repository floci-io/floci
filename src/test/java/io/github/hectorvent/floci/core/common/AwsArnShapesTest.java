package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwsArnShapesTest {

    @ParameterizedTest
    @ValueSource(strings = {"aws", "aws-us-gov", "aws-cn", "aws-iso", "aws-iso-b", "aws-iso-e", "aws-iso-f", "aws-eusc"})
    void iamRolePathsWorkAcrossPartitions(String partition) {
        assertTrue(AwsArnShapes.isIamRole(AwsArnUtils.parse(
                "arn:" + partition + ":iam::000000000000:role/service-role/application")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:sts::000000000000:role/example",
            "arn:aws:iam:us-east-1:000000000000:role/example",
            "arn:aws:iam:::role/example",
            "arn:aws:iam::000000000000:user/example",
            "arn:aws:iam::000000000000:role/",
            "arn:aws:iam::000000000000:role/ ",
            "arn:aws:iam::000000000000:"
    })
    void rejectsNonRoleShapes(String value) {
        assertFalse(AwsArnShapes.isIamRole(AwsArnUtils.parse(value)));
    }

    @Test
    void accountAndPartitionPoliciesRemainWithCallers() {
        assertTrue(AwsArnShapes.isIamRole(AwsArnUtils.parse("arn:custom:iam::123:role/example")));
    }

    @ParameterizedTest
    @CsvSource({"1, false, true", "80, false, true", "81, false, false",
            "6, true, true", "80, true, true", "81, true, false"})
    void queueNameLimitIncludesFifoSuffix(int length, boolean fifo, boolean valid) {
        String name = "q".repeat(length - (fifo ? 5 : 0)) + (fifo ? ".fifo" : "");
        assertEquals(valid, AwsArnShapes.isSqsQueueName(name));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"queue/name", "queue:name", "queue name", "queue.name", ".fifo"})
    void rejectsInvalidQueueNames(String name) {
        assertFalse(AwsArnShapes.isSqsQueueName(name));
    }
}
