package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsArnUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePolicyProviderTest {

    @Test
    void malformedRegionalOwnersAreRejected() {
        for (String arn : List.of(
                "arn:aws:sqs:us-east-1:123:orders",
                "arn:aws:sqs:us-east-1:11112222333x:orders",
                "arn:aws:sqs:us-east-1:１１１１２２２２３３３３:orders",
                "arn:aws:sqs::111122223333:orders",
                "arn:aws:sqs: :111122223333:orders")) {
            assertTrue(ResourcePolicyProvider.regionalArn(arn, "sqs").isEmpty(), arn);
        }
        assertTrue(ResourcePolicyProvider.regionalArn(null, "sqs").isEmpty());
        assertTrue(ResourcePolicyProvider.regionalArn("not-an-arn", "sqs").isEmpty());
        assertTrue(ResourcePolicyProvider.regionalArn("arn:aws:sns:us-east-1:111122223333:orders", "sqs")
                .isEmpty());
    }

    @Test
    void regionalOwnerIsPreservedAcrossPartitions() {
        for (String value : List.of(
                "arn:aws:sqs:us-east-1:111122223333:orders",
                "arn:aws-us-gov:sqs:us-gov-west-1:111122223333:orders",
                "arn:aws-cn:sqs:cn-north-1:111122223333:orders")) {
            AwsArnUtils.Arn arn = ResourcePolicyProvider.regionalArn(value, "sqs").orElseThrow();
            assertEquals(AwsArnUtils.parse(value), arn);
        }
    }
}
