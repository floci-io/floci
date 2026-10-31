package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * What a resource policy's condition has to say for an {@code Allow} to everyone not to count as
 * public access, as the services that refuse or report public policies read it: S3's Block Public
 * Access, Lambda's function URL and resource policies, and Aurora DSQL's cluster policies. Each
 * service keeps the operators and the extra keys it was measured to accept; what they read the
 * same way lives here.
 */
public final class PublicAccessConditions {

    private static final Logger LOG = Logger.getLogger(PublicAccessConditions.class);

    /**
     * The trusted condition keys of IAM's block public access definition that Lambda and DSQL both
     * accept: pinned to a fixed value, one of these keeps a wildcard principal from making a
     * statement public.
     */
    public static final Set<String> TRUSTED_KEYS = Set.of(
            "aws:PrincipalAccount", "aws:PrincipalArn", "aws:PrincipalOrgID", "aws:PrincipalOrgPaths",
            "aws:SourceAccount", "aws:SourceArn", "aws:SourceOrgID", "aws:SourceOrgPaths", "aws:SourceVpc",
            "aws:SourceVpcArn", "aws:SourceVpce", "aws:VpceAccount", "aws:VpceOrgID", "aws:VpceOrgPaths",
            "aws:Ec2InstanceSourceVpc", "aws:UserId", "aws:PrincipalServiceName", "aws:PrincipalServiceNamesList",
            "aws:PrincipalIsAWSService");

    /** The trusted keys whose values are ARNs, which a pattern pins only through their account. */
    public static final Set<String> ARN_KEYS = Set.of("aws:PrincipalArn", "aws:SourceArn", "aws:SourceVpcArn");

    private PublicAccessConditions() {
    }

    /** {@code keys} and one more, for a service that accepts a key of its own on top of the shared ones. */
    public static Set<String> with(Set<String> keys, String key) {
        Set<String> all = new HashSet<>(keys);
        all.add(key);
        return Set.copyOf(all);
    }

    /** A value that names one thing: not empty, and with no wildcard or policy variable in it. */
    public static boolean isFixedValue(String value) {
        return !value.isEmpty() && !hasWildcard(value) && !value.contains("${");
    }

    public static boolean hasWildcard(String value) {
        return value.indexOf('*') >= 0 || value.indexOf('?') >= 0;
    }

    /** An ARN of six fields whose account has no wildcard. */
    public static boolean pinsAccount(String arn) {
        return AwsArnUtils.isArn(arn) && !hasWildcard(AwsArnUtils.parse(arn).accountId());
    }

    /**
     * An {@code aws:SourceIp} value narrow enough to keep a statement private: one address, or a
     * range of /8 or narrower for IPv4 and /32 or narrower for IPv6. AWS still calls a broader
     * range public.
     */
    public static boolean isNarrowSourceIp(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0) {
            return isFixedValue(cidr);
        }
        String address = cidr.substring(0, slash);
        if (!isFixedValue(address)) {
            return false;
        }
        try {
            int prefixLength = Integer.parseInt(cidr.substring(slash + 1).trim());
            return prefixLength >= (address.indexOf(':') >= 0 ? 32 : 8);
        } catch (NumberFormatException e) {
            LOG.debugv("Treating aws:SourceIp value with an unparseable prefix as public: {0}", cidr);
            return false;
        }
    }
}
