package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.PartitionMatrix.PartitionCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Service principals are {@code <service>.amazonaws.com} in every partition (the CDK's rule in
 * {@code region-info.ts:130-132} and {@code principals.ts:990-1003}); the retired per-partition
 * forms from {@code region-info/lib/default.ts:53-117}, reproduced here by {@link #legacyForm},
 * must all fold back to it, so a policy written in either form is matched.
 */
class ServicePrincipalsTest {

    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void thePrincipalIsUniversalInEveryPartition(PartitionCase partition) {
        assertEquals("s3.amazonaws.com", ServicePrincipals.of("s3"));
        assertEquals("s3.amazonaws.com", ServicePrincipals.of("s3." + partition.dnsSuffix()));
        assertEquals("logs.amazonaws.com",
                ServicePrincipals.canonical("logs." + partition.region() + "." + partition.dnsSuffix()));
        assertEquals("logs", ServicePrincipals.serviceName("logs." + partition.region() + "." + partition.dnsSuffix()));
    }

    @Test
    void aDottedServiceNameKeepsItsLabels() {
        assertEquals("rds.application-autoscaling.amazonaws.com", ServicePrincipals.of("rds.application-autoscaling"));
        assertEquals("stacksets.cloudformation.amazonaws.com",
                ServicePrincipals.of("stacksets.cloudformation.amazonaws.com.cn"));
        assertEquals("rds.application-autoscaling",
                ServicePrincipals.serviceName("rds.application-autoscaling.amazonaws.com"));
    }

    @Test
    void aValueWithNoPublishedSuffixPassesThroughForGlobMatching() {
        assertEquals("*", ServicePrincipals.canonical("*"));
        assertEquals("*.amazonaws.com", ServicePrincipals.canonical("*.amazonaws.com"));
        assertEquals("ec2.internal", ServicePrincipals.canonical("EC2.internal"));
        assertNotEquals(ServicePrincipals.of("ec2"), ServicePrincipals.canonical("ec2.internal"));
    }

    /** The CDK's retired table, pinned case by case so a later cleanup cannot drift it silently. */
    @Test
    void legacyFormsFollowTheCdkTable() {
        assertEquals("codedeploy.cn-north-1.amazonaws.com.cn", legacyForm("codedeploy", "cn-north-1"));
        assertEquals("codedeploy.us-east-1.amazonaws.com", legacyForm("codedeploy", "us-east-1"));
        assertEquals("codedeploy.amazonaws.com", legacyForm("codedeploy", "us-iso-east-1"));
        assertEquals("logs.us-gov-west-1.amazonaws.com", legacyForm("logs", "us-gov-west-1"));
        assertEquals("logs.cn-northwest-1.amazonaws.com.cn", legacyForm("logs", "cn-northwest-1"));
        assertEquals("states.eu-west-1.amazonaws.com", legacyForm("states", "eu-west-1"));
        assertEquals("states.amazonaws.com", legacyForm("states", "us-iso-east-1"));
        assertEquals("states.amazonaws.com", legacyForm("states", "us-isob-east-1"));
        assertEquals("elasticmapreduce.amazonaws.com.cn", legacyForm("elasticmapreduce", "cn-north-1"));
        assertEquals("elasticmapreduce.amazonaws.com", legacyForm("elasticmapreduce", "eu-central-1"));
        assertEquals("config.c2s.ic.gov", legacyForm("config", "us-iso-east-1"));
        assertEquals("workspaces.c2s.ic.gov", legacyForm("workspaces", "us-iso-west-1"));
        assertEquals("dms.sc2s.sgov.gov", legacyForm("dms", "us-isob-east-1"));
        assertEquals("dms.amazonaws.com", legacyForm("dms", "us-iso-east-1"));
        assertEquals("s3.amazonaws.com", legacyForm("s3", "cn-north-1"));
    }

    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.testing.PartitionMatrix#cases")
    void everyLegacyFormFoldsBackToTheUniversalOne(PartitionCase partition) {
        for (String service : Set.of("codedeploy", "logs", "states", "elasticmapreduce", "config", "dms", "lambda")) {
            String legacy = legacyForm(service, partition.region());
            assertEquals(service + ".amazonaws.com", ServicePrincipals.canonical(legacy), legacy);
        }
    }

    /**
     * The per-partition principal AWS used before the universal rule, from the CDK's retired
     * table: {@code codedeploy.cn-north-1.amazonaws.com.cn} in China and
     * {@code codedeploy.<region>.amazonaws.com} elsewhere, {@code logs.<region>.<suffix>},
     * {@code states.<region>.amazonaws.com}, {@code elasticmapreduce.amazonaws.com.cn} in China,
     * and the partitional {@code cloudhsm}, {@code config}, {@code workspaces} ({@code us-iso-*})
     * and {@code dms} ({@code us-isob-*}); {@code states} is universal in the ISO partitions.
     * Every other service is universal there too.
     */
    private static String legacyForm(String service, String region) {
        String lower = region.toLowerCase(Locale.ROOT);
        String suffix = AwsPartitions.forRegionOrCommercial(lower).dnsSuffix();
        if (lower.startsWith("us-iso-") && Set.of("cloudhsm", "config", "workspaces").contains(service)) {
            return service + "." + suffix;
        }
        if (lower.startsWith("us-isob-") && "dms".equals(service)) {
            return service + "." + suffix;
        }
        if (lower.startsWith("us-iso") && "states".equals(service)) {
            return service + ".amazonaws.com";
        }
        return switch (service) {
            case "codedeploy" -> lower.startsWith("cn-") ? service + "." + lower + "." + suffix
                    : lower.startsWith("us-iso") ? service + ".amazonaws.com"
                    : service + "." + lower + ".amazonaws.com";
            case "logs" -> service + "." + lower + "." + suffix;
            case "states" -> service + "." + lower + ".amazonaws.com";
            case "elasticmapreduce" -> lower.startsWith("cn-") ? service + "." + suffix : service + ".amazonaws.com";
            default -> service + ".amazonaws.com";
        };
    }
}
