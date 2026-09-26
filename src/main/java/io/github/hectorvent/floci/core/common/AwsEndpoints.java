package io.github.hectorvent.floci.core.common;

/**
 * The AWS hostnames the emulator hands back for a region: every one is
 * {@code <service>.<region>.<dnsSuffix>} with the region's partition suffix, plus the handful of
 * shapes that break that rule (the S3 website endpoint's two forms, the EC2 public DNS name,
 * the region-less S3 bucket host). Floci itself serves everything on its own base URL; these are
 * the values AWS-shaped fields carry ({@code ApiEndpoint}, {@code RegionalDomainName},
 * {@code PublicDnsName}, OIDC issuers), which clients compare against or parse.
 */
public final class AwsEndpoints {

    private AwsEndpoints() {
    }

    /** {@code <service>.<region>.<dnsSuffix>}. */
    public static String host(String service, String region) {
        return AwsPartitions.forRegionOrCommercial(region).regionalHostname(service, region);
    }

    /** {@code <apiId>.execute-api.<region>.<dnsSuffix>}. */
    public static String executeApiHost(String apiId, String region) {
        return apiId + "." + host("execute-api", region);
    }

    /** The region-less bucket host, {@code <bucket>.s3.<dnsSuffix>} (CloudFormation's {@code DomainName}). */
    public static String s3Host(String bucket, String region) {
        return bucket + ".s3." + AwsRegions.dnsSuffixFor(region);
    }

    /** {@code <bucket>.s3.<region>.<dnsSuffix>} (CloudFormation's {@code RegionalDomainName}). */
    public static String s3RegionalHost(String bucket, String region) {
        return bucket + "." + host("s3", region);
    }

    /**
     * {@code <bucket>.s3.dualstack.<region>.<dnsSuffix>}; only meaningful where
     * {@link AwsPartition#supportsS3DualStack} holds.
     */
    public static String s3DualStackHost(String bucket, String region) {
        return bucket + ".s3.dualstack." + region + "." + AwsRegions.dnsSuffixFor(region);
    }

    /**
     * The S3 static website host. The nine regions that predate the regional-subdomain rule
     * ({@code us-east-1}, {@code us-west-1}, {@code us-west-2}, {@code eu-west-1},
     * {@code ap-southeast-1}, {@code ap-southeast-2}, {@code ap-northeast-1}, {@code sa-east-1},
     * {@code us-gov-west-1}) use the dash form {@code s3-website-<region>}; every later region
     * uses {@code s3-website.<region>} (the CDK's {@code RULE_S3_WEBSITE_REGIONAL_SUBDOMAIN}).
     */
    public static String s3WebsiteHost(String bucket, String region) {
        AwsPartition partition = AwsPartitions.forRegionOrCommercial(region);
        boolean dashForm = partition.region(region).map(AwsPartition.Region::s3WebsiteDashForm).orElse(false);
        return bucket + (dashForm ? ".s3-website-" : ".s3-website.") + region + "." + partition.dnsSuffix();
    }

    /** CloudFormation's {@code WebsiteURL}: {@code http://} + the website host + a trailing slash. */
    public static String s3WebsiteUrl(String bucket, String region) {
        return "http://" + s3WebsiteHost(bucket, region) + "/";
    }

    /**
     * The EC2 public DNS name for {@code ip}: {@code ec2-<ip>.compute-1.amazonaws.com} in
     * {@code us-east-1} and {@code ec2-<ip>.<region>.compute.<dnsSuffix>} everywhere else, the
     * rule the AWS Terraform provider applies.
     */
    public static String ec2PublicDns(String ip, String region) {
        String dashed = "ec2-" + ip.replace('.', '-');
        if ("us-east-1".equals(region)) {
            return dashed + ".compute-1." + AwsRegions.DEFAULT_DNS_SUFFIX;
        }
        return dashed + "." + region + ".compute." + AwsRegions.dnsSuffixFor(region);
    }
}
