package io.github.hectorvent.floci.core.common;

import java.util.Locale;
import java.util.Optional;

/**
 * AWS service principals across partitions. The principal a service uses today is
 * {@code <service>.amazonaws.com} in every partition, commercial, China, GovCloud or ISO: the CDK
 * derives it that way for every region ({@code region-info/lib/region-info.ts:130-132},
 * {@code aws-iam/lib/principals.ts:990-1003}) and rewrites a legacy partition form such as
 * {@code elasticmapreduce.amazonaws.com.cn} back to it. Everything Floci emits (trust policies it
 * generates, CloudTrail's {@code eventSource}, service-linked role paths) uses {@link #of}.
 *
 * <p>The older forms AWS accepted per partition still appear in customer trust policies, and AWS
 * still honours them, so anything Floci matches against a policy goes through {@link #canonical}:
 * a regional label and any published DNS suffix are folded away, which covers every form in the
 * CDK's retired per-partition table ({@code region-info/lib/default.ts:53-117}).
 */
public final class ServicePrincipals {

    private static final String UNIVERSAL_SUFFIX = ".amazonaws.com"; // partition-literal: the universal service-principal suffix in every partition (CDK region-info.ts:130-132)

    private ServicePrincipals() {
    }

    /**
     * The principal for a service in any partition: {@code of("s3")} and
     * {@code of("s3.amazonaws.com.cn")} both give {@code s3.amazonaws.com}. A name with no known
     * suffix is taken as a bare service name, so {@code rds.application-autoscaling} keeps its
     * dotted form.
     */
    public static String of(String service) {
        String canonical = canonical(service);
        return canonical.endsWith(UNIVERSAL_SUFFIX) ? canonical : canonical + UNIVERSAL_SUFFIX;
    }

    /**
     * Folds any partition or regional form of a service principal to the universal one:
     * {@code logs.cn-north-1.amazonaws.com.cn} and {@code config.c2s.ic.gov} become
     * {@code logs.amazonaws.com} and {@code config.amazonaws.com}. A string that ends with no
     * published DNS suffix is returned trimmed and lower-cased but otherwise unchanged, so a
     * wildcard such as {@code *} or a non-principal value passes through.
     */
    public static String canonical(String principal) {
        if (principal == null) {
            return null;
        }
        String lower = principal.trim().toLowerCase(Locale.ROOT);
        Optional<AwsPartitions.DnsSuffixMatch> match = AwsPartitions.stripKnownDnsSuffix(lower);
        if (match.isEmpty()) {
            return lower;
        }
        String labels = match.get().prefix();
        int lastDot = labels.lastIndexOf('.');
        if (lastDot > 0 && AwsRegions.isRegionId(labels.substring(lastDot + 1))) {
            labels = labels.substring(0, lastDot);
        }
        return labels + UNIVERSAL_SUFFIX;
    }

    /** The service part of a principal in any form: {@code es} for {@code es.amazonaws.com.cn}. */
    public static String serviceName(String principal) {
        String canonical = canonical(principal);
        return canonical.endsWith(UNIVERSAL_SUFFIX)
                ? canonical.substring(0, canonical.length() - UNIVERSAL_SUFFIX.length())
                : canonical;
    }
}
