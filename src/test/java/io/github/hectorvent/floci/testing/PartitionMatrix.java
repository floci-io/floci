package io.github.hectorvent.floci.testing;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * One representative region per AWS partition, for tests that assert partition-dependent
 * behaviour (ARN partition segments, DNS suffixes, signing scopes). Tests that are not about
 * partitions keep their {@code us-east-1} fixtures; nothing here is meant to rewrite them.
 */
public final class PartitionMatrix {

    public record PartitionCase(String partition, String region, String dnsSuffix) {
        @Override
        public String toString() {
            return partition + " (" + region + ")";
        }
    }

    public static final String ACCOUNT = "000000000000";

    private static final List<PartitionCase> ALL = List.of(
            new PartitionCase("aws", "us-east-1", "amazonaws.com"),
            new PartitionCase("aws-cn", "cn-north-1", "amazonaws.com.cn"),
            new PartitionCase("aws-us-gov", "us-gov-west-1", "amazonaws.com"),
            new PartitionCase("aws-iso", "us-iso-east-1", "c2s.ic.gov"),
            new PartitionCase("aws-iso-b", "us-isob-east-1", "sc2s.sgov.gov"),
            new PartitionCase("aws-iso-e", "eu-isoe-west-1", "cloud.adc-e.uk"),
            new PartitionCase("aws-iso-f", "us-isof-south-1", "csp.hci.ic.gov"),
            new PartitionCase("aws-eusc", "eusc-de-east-1", "amazonaws.eu"));

    private PartitionMatrix() {
    }

    public static List<PartitionCase> all() {
        return ALL;
    }

    /** For {@code @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")}. */
    public static Stream<PartitionCase> cases() {
        return ALL.stream();
    }

    /** A SigV4 Authorization header whose credential scope signs {@code region} for {@code service}. */
    public static String sigV4Auth(String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260215/" + region + "/" + service + "/aws4_request, "
                + "SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=abc";
    }

    /** A resolver for a deployment whose default region is the case's region. */
    public static RegionResolver regionResolver(PartitionCase partitionCase) {
        return new RegionResolver(partitionCase.region(), ACCOUNT);
    }

    /** Asserts {@code arn} is a regional ARN of the case's partition and region. */
    public static void assertArnIn(PartitionCase partitionCase, String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        assertEquals(partitionCase.partition(), parsed.partition(), "partition of " + arn);
        assertEquals(partitionCase.region(), parsed.region(), "region of " + arn);
    }

    /** Asserts {@code arn} is a regionless ARN of the case's partition. */
    public static void assertGlobalArnIn(PartitionCase partitionCase, String arn) {
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        assertEquals(partitionCase.partition(), parsed.partition(), "partition of " + arn);
        assertEquals("", parsed.region(), "a global ARN carries no region: " + arn);
    }

    /** Asserts {@code host} ends with the case's DNS suffix. */
    public static void assertHostIn(PartitionCase partitionCase, String host) {
        assertEquals(true, host.endsWith("." + partitionCase.dnsSuffix()),
                host + " should end with ." + partitionCase.dnsSuffix());
    }
}
