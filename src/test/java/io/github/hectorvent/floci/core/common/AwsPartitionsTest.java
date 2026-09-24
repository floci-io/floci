package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionMatrix;
import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vendored partition catalog ({@code aws/partitions.json}) and the lookups over it. The
 * shape assertions pin what {@code tools/aws/regen_partitions.py} produces from botocore; the
 * lookup assertions pin the strict-then-fail-open resolution every ARN and hostname relies on.
 */
class AwsPartitionsTest {

    private static final Set<String> PUBLISHED_PARTITIONS = Set.of(
            "aws", "aws-cn", "aws-us-gov", "aws-iso", "aws-iso-b", "aws-iso-e", "aws-iso-f", "aws-eusc");

    @Test
    void allEightPartitionsArePresentExactlyOnce() {
        assertEquals(PUBLISHED_PARTITIONS, AwsPartitions.ids());
        assertEquals(8, AwsPartitions.all().size());
    }

    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void everyMatrixRegionResolvesToItsPartitionWithItsDnsSuffix(PartitionCase partitionCase) {
        AwsPartition partition = AwsPartitions.forRegion(partitionCase.region()).orElseThrow();
        assertEquals(partitionCase.partition(), partition.id());
        assertEquals(partitionCase.dnsSuffix(), partition.dnsSuffix());
        assertTrue(partition.isPublishedRegion(partitionCase.region()));
    }

    /**
     * Membership and botocore's regionRegex must agree for every published region, or a region
     * would resolve one way from the list and another way once the list is stale.
     */
    @Test
    void regionRegexAgreesWithMembershipForEveryPublishedRegion() {
        for (AwsPartition partition : AwsPartitions.all()) {
            for (String region : partition.regionIds()) {
                assertTrue(partition.matchesRegionRegex(region),
                        region + " is listed under " + partition.id() + " but does not match its regionRegex");
                for (AwsPartition other : AwsPartitions.all()) {
                    if (!other.id().equals(partition.id())) {
                        assertFalse(other.matchesRegionRegex(region),
                                region + " matches the regionRegex of both " + partition.id() + " and " + other.id());
                    }
                }
            }
        }
    }

    @Test
    void everyKnownRegionIdBelongsToExactlyOnePartition() {
        for (String region : AwsPartitions.allRegionIds()) {
            long owners = AwsPartitions.all().stream().filter(p -> p.isPublishedRegion(region)).count();
            assertEquals(1, owners, region);
            assertEquals(AwsPartitions.forRegion(region).orElseThrow().id(),
                    AwsPartitions.all().stream().filter(p -> p.isPublishedRegion(region)).findFirst().orElseThrow().id());
        }
    }

    @Test
    void theCommercialPartitionPublishesThirtyFourRegionsAndChinaTwo() {
        assertEquals(34, AwsPartitions.commercial().regions().size());
        assertEquals(List.of("cn-north-1", "cn-northwest-1"), AwsPartitions.byId("aws-cn").regionIds());
        assertEquals(List.of("us-gov-east-1", "us-gov-west-1"), AwsPartitions.byId("aws-us-gov").regionIds());
    }

    @ParameterizedTest
    @CsvSource({
            "aws-global,        aws,        us-east-1",
            "aws-cn-global,     aws-cn,     cn-northwest-1",
            "aws-us-gov-global, aws-us-gov, us-gov-west-1",
            "aws-iso-global,    aws-iso,    us-iso-east-1",
            "aws-iso-b-global,  aws-iso-b,  us-isob-east-1",
            "aws-iso-e-global,  aws-iso-e,  eu-isoe-west-1",
            "aws-iso-f-global,  aws-iso-f,  us-isof-south-1"})
    void pseudoRegionsResolveToTheirPartitionAndNormalizeToItsImplicitGlobalRegion(
            String pseudo, String partition, String implicitGlobalRegion) {
        assertEquals(partition, AwsPartitions.forRegion(pseudo).orElseThrow().id());
        assertEquals(implicitGlobalRegion, AwsPartitions.normalizeRegion(pseudo));
        assertEquals(implicitGlobalRegion, AwsPartitions.byId(partition).implicitGlobalRegion());
        assertTrue(AwsPartitions.byId(partition).isPseudoRegion(pseudo));
        assertFalse(AwsPartitions.isPublishedRegion(pseudo), "a pseudo-region is not a published region");
    }

    /** botocore publishes no {@code aws-eusc-global}, and EUSC has no partition-wide service at all. */
    @Test
    void euscHasNoPseudoRegionAndNoGlobalServices() {
        AwsPartition eusc = AwsPartitions.byId("aws-eusc");
        assertTrue(eusc.pseudoRegion().isEmpty());
        assertTrue(eusc.globalServices().isEmpty());
        assertTrue(AwsPartitions.forRegion("aws-eusc-global").isEmpty());
    }

    /** The global STS host exists only in the commercial partition; everywhere else STS is regional. */
    @Test
    void onlyTheCommercialPartitionHasGlobalSts() {
        for (AwsPartition partition : AwsPartitions.all()) {
            assertEquals("aws".equals(partition.id()), partition.hasGlobalSts(), partition.id());
        }
        AwsPartition.GlobalEndpoint sts = AwsPartitions.commercial().globalEndpoint("sts").orElseThrow();
        assertEquals("sts.amazonaws.com", sts.hostname());
        assertEquals("us-east-1", sts.signingRegion());
        assertTrue(sts.regionalized(), "STS keeps per-region endpoints beside the global host");
    }

    /** China IAM signs {@code cn-north-1} although the partition's implicit global region is Ningxia. */
    @Test
    void globalEndpointsCarryTheirOwnSigningRegion() {
        AwsPartition china = AwsPartitions.byId("aws-cn");
        AwsPartition.GlobalEndpoint iam = china.globalEndpoint("iam").orElseThrow();
        assertEquals("iam.cn-north-1.amazonaws.com.cn", iam.hostname());
        assertEquals("cn-north-1", iam.signingRegion());
        assertEquals("cn-northwest-1", china.globalEndpoint("route53").orElseThrow().signingRegion());
        assertEquals("us-gov-west-1", AwsPartitions.byId("aws-us-gov").globalEndpoint("iam").orElseThrow().signingRegion());
    }

    @Test
    void serviceAvailabilityFollowsEndpointsJson() {
        assertTrue(AwsPartitions.commercial().offers("cloudfront"));
        assertTrue(AwsPartitions.byId("aws-cn").offers("cloudfront"));
        assertFalse(AwsPartitions.byId("aws-us-gov").offers("cloudfront"));
        assertFalse(AwsPartitions.byId("aws-eusc").offers("iam"));
        assertFalse(AwsPartitions.byId("aws-iso-e").offers("iam"));
        assertFalse(AwsPartitions.byId("aws-cn").offers("cognito-idp"));
        assertFalse(AwsPartitions.commercial().offers(null));
    }

    @Test
    void optInAndWebsiteFormFlagsComeFromTheCdkRules() {
        AwsPartition aws = AwsPartitions.commercial();
        assertFalse(aws.region("us-east-1").orElseThrow().optIn());
        assertFalse(aws.region("eu-north-1").orElseThrow().optIn(), "the last region before opt-in began");
        assertTrue(aws.region("ap-east-1").orElseThrow().optIn());
        assertTrue(aws.region("il-central-1").orElseThrow().optIn());
        assertTrue(aws.region("us-east-1").orElseThrow().s3WebsiteDashForm());
        assertTrue(aws.region("us-west-2").orElseThrow().s3WebsiteDashForm());
        assertTrue(aws.region("ap-southeast-2").orElseThrow().s3WebsiteDashForm());
        assertFalse(aws.region("eu-central-1").orElseThrow().s3WebsiteDashForm());
        assertFalse(aws.region("ap-east-1").orElseThrow().s3WebsiteDashForm());
        assertTrue(AwsPartitions.byId("aws-us-gov").region("us-gov-west-1").orElseThrow().s3WebsiteDashForm());
        assertFalse(AwsPartitions.byId("aws-cn").region("cn-north-1").orElseThrow().optIn());
        assertEquals(17, aws.regions().stream().filter(AwsPartition.Region::optIn).count());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "xx-nowhere-9", "not-a-region", "us-gov", "cn-north", "aws-eusc-global"})
    void unplaceableLabelsAreEmptyStrictlyAndCommercialLeniently(String region) {
        assertTrue(AwsPartitions.forRegion(region).isEmpty());
        assertEquals("aws", AwsPartitions.forRegionOrCommercial(region).id());
    }

    /** A region AWS launches after the vendored data is placed by botocore's shape rule. */
    @Test
    void anUnlistedRegionOfAKnownShapeResolvesByRegex() {
        assertEquals("aws-cn", AwsPartitions.forRegion("cn-southwest-9").orElseThrow().id());
        assertEquals("aws-us-gov", AwsPartitions.forRegion("us-gov-north-3").orElseThrow().id());
        assertEquals("aws", AwsPartitions.forRegion("eu-nowhere-7").orElseThrow().id());
        assertFalse(AwsPartitions.isPublishedRegion("eu-nowhere-7"));
    }

    @Test
    void lookupsAreCaseInsensitive() {
        assertEquals("aws-cn", AwsPartitions.forRegion("CN-NORTH-1").orElseThrow().id());
        assertEquals("aws-cn", AwsPartitions.byId("AWS-CN").id());
        assertEquals("cn-north-1", AwsPartitions.normalizeRegion(" CN-North-1 "));
        assertEquals(Optional.empty(), Optional.ofNullable(AwsPartitions.normalizeRegion(null)));
    }

    @Test
    void unknownPartitionIdsThrow() {
        assertThrows(IllegalArgumentException.class, () -> AwsPartitions.byId("aws-mars"));
        assertTrue(AwsPartitions.find("aws-mars").isEmpty());
        assertTrue(AwsPartitions.find(null).isEmpty());
    }

    /**
     * {@code amazonaws.com.cn} must win over {@code amazonaws.com} or a China host loses its
     * {@code .cn} and is read as commercial; the dual-stack suffixes are published too.
     */
    @ParameterizedTest
    @CsvSource({
            "bucket.s3.cn-north-1.amazonaws.com.cn,     bucket.s3.cn-north-1,     amazonaws.com.cn",
            "bucket.s3.us-east-1.amazonaws.com,         bucket.s3.us-east-1,      amazonaws.com",
            "s3.us-iso-east-1.c2s.ic.gov,               s3.us-iso-east-1,         c2s.ic.gov",
            "s3.eusc-de-east-1.amazonaws.eu,            s3.eusc-de-east-1,        amazonaws.eu",
            "dynamodb.us-east-1.api.aws,                dynamodb.us-east-1,       api.aws",
            "sqs.cn-north-1.api.amazonwebservices.com.cn, sqs.cn-north-1,         api.amazonwebservices.com.cn",
            "BUCKET.S3.CN-NORTH-1.AMAZONAWS.COM.CN,     bucket.s3.cn-north-1,     amazonaws.com.cn"})
    void stripKnownDnsSuffixTakesTheLongestSuffixFirst(String host, String prefix, String suffix) {
        AwsPartitions.DnsSuffixMatch match = AwsPartitions.stripKnownDnsSuffix(host).orElseThrow();
        assertEquals(prefix, match.prefix());
        assertEquals(suffix, match.dnsSuffix());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"amazonaws.com", "localhost:4566", "bucket.localhost", "example.com", "s3.amazonaws.com.evil"})
    void hostsWithoutAPublishedSuffixDoNotStrip(String host) {
        assertTrue(AwsPartitions.stripKnownDnsSuffix(host).isEmpty());
    }

    @Test
    void aMalformedCatalogFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> AwsPartitions.parse(
                new ByteArrayInputStream("{\"partitions\": []}".getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalStateException.class, () -> AwsPartitions.parse(new ByteArrayInputStream(
                "{\"partitions\": [{\"id\": \"aws-cn\", \"name\": \"x\", \"dnsSuffix\": \"a\", \"dualStackDnsSuffix\": \"b\", \"implicitGlobalRegion\": \"c\", \"regionRegex\": \"d\"}]}"
                        .getBytes(StandardCharsets.UTF_8))), "a catalog without the commercial partition");
        assertThrows(IllegalStateException.class, () -> AwsPartitions.parse(new ByteArrayInputStream(
                "{\"partitions\": [{\"id\": \"aws\", \"name\": \"x\"}]}".getBytes(StandardCharsets.UTF_8))),
                "a partition missing required fields");
    }

    @Test
    void partitionMatrixCoversEveryPublishedPartition() {
        assertEquals(PUBLISHED_PARTITIONS,
                Set.copyOf(PartitionMatrix.all().stream().map(PartitionCase::partition).toList()));
    }
}
