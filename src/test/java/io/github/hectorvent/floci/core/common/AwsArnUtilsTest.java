package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AwsArnUtils} is the emulator's shared ARN helper and had no test of its own, despite
 * routing the whole cross-service tagging API through {@code TagDispatcher}. These pin the
 * parsing contract the callers depend on, most of it in the resource field: the helper splits
 * six segments and deliberately hands the resource back whole, because its structure is
 * service-specific.
 */
class AwsArnUtilsTest {

    @Test
    void parsesTheSixSegments() {
        AwsArnUtils.Arn arn = AwsArnUtils.parse("arn:aws:sqs:us-east-1:000000000000:my-queue");

        assertEquals("aws", arn.partition());
        assertEquals("sqs", arn.service());
        assertEquals("us-east-1", arn.region());
        assertEquals("000000000000", arn.accountId());
        assertEquals("my-queue", arn.resource());
    }

    /**
     * The resource keeps its own colons. {@code split(":", 6)} is what makes a Lambda qualified
     * ARN survive; a plain {@code split(":")} would strip the alias off the end.
     */
    @Test
    void resourceKeepsItsOwnColons() {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(
                "arn:aws:lambda:us-east-1:000000000000:function:order-processor:PROD");

        assertEquals("function:order-processor:PROD", arn.resource());
    }

    /** Global services omit region and account, and those arrive as empty strings, never null. */
    @Test
    void omittedRegionAndAccountAreEmptyNotNull() {
        AwsArnUtils.Arn arn = AwsArnUtils.parse("arn:aws:s3:::my-bucket");

        assertEquals("", arn.region());
        assertEquals("", arn.accountId());
        assertEquals("my-bucket", arn.resource());
    }

    /** An empty resource is legal input and must not collapse the segment count. */
    @Test
    void emptyResourceStillParses() {
        AwsArnUtils.Arn arn = AwsArnUtils.parse("arn:aws:glue:us-east-1:000000000000:");

        assertEquals("glue", arn.service());
        assertEquals("", arn.resource());
    }

    @Test
    void parseIsTheInverseOfToString() {
        String text = "arn:aws:iam::000000000000:role/service-role/my-role";

        assertEquals(text, AwsArnUtils.parse(text).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:sqs:us-east-1:000000000000",
            "arn:aws:sqs",
            "my-queue",
            "urn:aws:sqs:us-east-1:000000000000:my-queue",
            "  "})
    void malformedArnsAreRejected(String value) {
        assertThrows(IllegalArgumentException.class, () -> AwsArnUtils.parse(value));
    }

    @Test
    void nullIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AwsArnUtils.parse(null));
    }

    /**
     * Non-commercial partitions parse like any other. Only construction pinned the partition,
     * never parsing.
     */
    @ParameterizedTest
    @CsvSource({
            "arn:aws-us-gov:kms:us-gov-west-1:000000000000:key/abc, aws-us-gov, us-gov-west-1",
            "arn:aws-cn:s3:cn-north-1:000000000000:bucket,          aws-cn,     cn-north-1",
            "arn:aws-iso:sns:us-iso-east-1:000000000000:topic,      aws-iso,    us-iso-east-1"})
    void foreignPartitionsParse(String text, String partition, String region) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(text);

        assertEquals(partition, arn.partition());
        assertEquals(region, arn.region());
    }

    @Test
    void regionOrDefaultFallsBackOnEveryFailureMode() {
        assertEquals("us-east-1", AwsArnUtils.regionOrDefault(null, "us-east-1"));
        assertEquals("us-east-1", AwsArnUtils.regionOrDefault("not-an-arn", "us-east-1"));
        assertEquals("us-east-1", AwsArnUtils.regionOrDefault("arn:aws:s3:::bucket", "us-east-1"));
        assertEquals("eu-west-1",
                AwsArnUtils.regionOrDefault("arn:aws:sqs:eu-west-1:000000000000:q", "us-east-1"));
    }

    @Test
    void accountOrDefaultFallsBackOnEveryFailureMode() {
        assertEquals("000000000000", AwsArnUtils.accountOrDefault(null, "000000000000"));
        assertEquals("000000000000", AwsArnUtils.accountOrDefault("not-an-arn", "000000000000"));
        assertEquals("000000000000",
                AwsArnUtils.accountOrDefault("arn:aws:s3:::bucket", "000000000000"));
        assertEquals("123456789012",
                AwsArnUtils.accountOrDefault("arn:aws:sqs:eu-west-1:123456789012:q", "000000000000"));
    }

    /**
     * The partition is derived from the region, so a resource in GovCloud or China is named with
     * the partition it actually lives in rather than always {@code aws}.
     */
    @ParameterizedTest
    @CsvSource({
            "us-east-1,      arn:aws:sqs:us-east-1:000000000000:q",
            "eu-west-1,      arn:aws:sqs:eu-west-1:000000000000:q",
            "us-gov-west-1,  arn:aws-us-gov:sqs:us-gov-west-1:000000000000:q",
            "cn-north-1,     arn:aws-cn:sqs:cn-north-1:000000000000:q",
            "us-iso-east-1,  arn:aws-iso:sqs:us-iso-east-1:000000000000:q",
            "us-isob-east-1, arn:aws-iso-b:sqs:us-isob-east-1:000000000000:q",
            "eu-isoe-west-1, arn:aws-iso-e:sqs:eu-isoe-west-1:000000000000:q",
            "us-isof-south-1,arn:aws-iso-f:sqs:us-isof-south-1:000000000000:q",
            "eusc-de-east-1, arn:aws-eusc:sqs:eusc-de-east-1:000000000000:q"})
    void partitionIsDerivedFromTheRegion(String region, String expected) {
        assertEquals(expected, AwsArnUtils.Arn.of("sqs", region, "000000000000", "q").toString());
    }

    /**
     * Global services pass no region, and they keep the commercial partition. Thirty call sites
     * do this; anything else would rewrite every IAM, S3 and CloudFront ARN the emulator mints.
     */
    @Test
    void aRegionlessArnStaysInTheCommercialPartition() {
        assertEquals("arn:aws:iam::000000000000:role/r",
                AwsArnUtils.Arn.of("iam", "", "000000000000", "role/r").toString());
    }

    /** A region AWS has not launched yet must not fail closed. */
    @Test
    void anUnrecognisedRegionFallsBackToTheCommercialPartition() {
        assertEquals("arn:aws:sqs:xx-nowhere-9:000000000000:q",
                AwsArnUtils.Arn.of("sqs", "xx-nowhere-9", "000000000000", "q").toString());
    }

    /** The record constructor still takes an explicit partition, for echoing a caller's own. */
    @Test
    void theRecordConstructorKeepsAnExplicitPartition() {
        assertEquals("arn:aws-cn:secretsmanager:us-east-1:000000000000:secret:s",
                new AwsArnUtils.Arn("aws-cn", "secretsmanager", "us-east-1", "000000000000",
                        "secret:s").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:sqs:us-east-1:000000000000:my-queue",
            "arn:aws-us-gov:sqs:us-gov-west-1:000000000000:my-queue",
            "arn:aws-cn:s3:::bucket",
            "arn:aws:lambda:us-east-1:000000000000:function:f:PROD"})
    void isArnAcceptsAnyPartition(String value) {
        assertTrue(AwsArnUtils.isArn(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"my-queue", "arn:aws:sqs:us-east-1:000000000000", "arn:", "", "  "})
    void isArnRejectsNonArns(String value) {
        assertFalse(AwsArnUtils.isArn(value));
    }

    @Test
    void isArnRejectsNullRatherThanThrowing() {
        assertFalse(AwsArnUtils.isArn(null));
    }

    /**
     * The shape the {@code startsWith("arn:aws:dynamodb:")} probes were reaching for: is this
     * identifier an ARN for my service, whatever partition it came from.
     */
    @Test
    void isArnForMatchesTheServiceInAnyPartition() {
        assertTrue(AwsArnUtils.isArnFor("arn:aws:dynamodb:us-east-1:000000000000:table/t",
                "dynamodb"));
        assertTrue(AwsArnUtils.isArnFor("arn:aws-cn:dynamodb:cn-north-1:000000000000:table/t",
                "dynamodb"));
        assertFalse(AwsArnUtils.isArnFor("arn:aws:kinesis:us-east-1:000000000000:stream/s",
                "dynamodb"));
        assertFalse(AwsArnUtils.isArnFor("my-table", "dynamodb"));
        assertFalse(AwsArnUtils.isArnFor(null, "dynamodb"));
    }

    @Test
    void queueUrlIsAccountThenQueueName() {
        assertEquals("http://localhost:4566/000000000000/my-queue",
                AwsArnUtils.arnToQueueUrl("arn:aws:sqs:us-east-1:000000000000:my-queue",
                        "http://localhost:4566"));
    }
}
