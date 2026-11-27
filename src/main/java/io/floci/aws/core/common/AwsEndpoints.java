package io.floci.aws.core.common;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The AWS hostnames the emulator hands back for a region: every one is
 * {@code <service>.<region>.<dnsSuffix>} with the region's partition suffix, plus the handful of
 * shapes that break that rule (the S3 website endpoint's two forms, the EC2 public DNS name,
 * the region-less S3 bucket host). Floci itself serves everything on its own base URL; these are
 * the values AWS-shaped fields carry ({@code ApiEndpoint}, {@code RegionalDomainName},
 * {@code PublicDnsName}, OIDC issuers), which clients compare against or parse.
 */
public final class AwsEndpoints {

    private static final String RDS_ENDPOINT_IDENTIFIER_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int RDS_ENDPOINT_IDENTIFIER_LENGTH = 12;

    private AwsEndpoints() {
    }

    /** {@code <service>.<region>.<dnsSuffix>}. */
    public static String host(String service, String region) {
        return AwsPartitions.forRegionOrCommercial(region).regionalHostname(service, region);
    }

    /**
     * The identifier every RDS endpoint of an account in a region carries, {@code abcdefghijkl} in
     * {@code db1.abcdefghijkl.us-east-1.rds.amazonaws.com}. The RDS user guide calls it "a unique identifier
     * for a specific combination of AWS Region and AWS account" that "doesn't change" for it, so all of an
     * account's DB instances in a region share it. Floci derives its twelve letters and digits from the two, a
     * letter first, so it stays the same across restarts without being stored.
     */
    public static String rdsEndpointIdentifier(String accountId, String region) {
        byte[] digest = Digests.sha256((accountId + ":" + region).getBytes(StandardCharsets.UTF_8));
        StringBuilder identifier = new StringBuilder(RDS_ENDPOINT_IDENTIFIER_LENGTH);
        identifier.append(RDS_ENDPOINT_IDENTIFIER_CHARACTERS.charAt(Math.floorMod(digest[0], 26)));
        for (int i = 1; i < RDS_ENDPOINT_IDENTIFIER_LENGTH; i++) {
            identifier.append(RDS_ENDPOINT_IDENTIFIER_CHARACTERS.charAt(
                    Math.floorMod(digest[i], RDS_ENDPOINT_IDENTIFIER_CHARACTERS.length())));
        }
        return identifier.toString();
    }

    /**
     * A DB instance's endpoint address, {@code <instance>.<identifier>.<region>.rds.<dnsSuffix>}, with the
     * {@link #rdsEndpointIdentifier} of its account and region.
     */
    public static String rdsInstanceHost(String dbInstanceIdentifier, String accountId, String region) {
        return dbInstanceIdentifier.toLowerCase(Locale.ROOT) + "." + rdsEndpointIdentifier(accountId, region) + "."
                + region + ".rds." + AwsRegions.dnsSuffixFor(region);
    }

    /**
     * A DB cluster endpoint's address, {@code <name>.<type>-<identifier>.<region>.rds.<dnsSuffix>}, with the
     * {@link #rdsEndpointIdentifier} of its account and region. The Aurora user guide gives the three types: a
     * cluster's writer endpoint is {@code mydbcluster.cluster-c7tj4example.us-east-1.rds.amazonaws.com}, its reader
     * endpoint {@code mydbcluster.cluster-ro-...} and a custom endpoint {@code myendpoint.cluster-custom-...}.
     *
     * @param name the cluster's identifier, or the custom endpoint's
     * @param type {@code cluster}, {@code cluster-ro} or {@code cluster-custom}
     */
    public static String rdsClusterEndpointHost(String name, String type, String accountId, String region) {
        return name.toLowerCase(Locale.ROOT) + "." + type + "-" + rdsEndpointIdentifier(accountId, region) + "."
                + region + ".rds." + AwsRegions.dnsSuffixFor(region);
    }

    /**
     * A DB proxy's default endpoint address, {@code <proxy>.proxy-<identifier>.<region>.rds.<dnsSuffix>}, as the CLI
     * reference's {@code proxyExample.proxy-ab0cd1efghij.us-west-2.rds.amazonaws.com}.
     */
    public static String rdsProxyHost(String dbProxyName, String accountId, String region) {
        return rdsClusterEndpointHost(dbProxyName, "proxy", accountId, region);
    }

    /**
     * The address of an endpoint added to a DB proxy, {@code <endpoint>.endpoint.proxy-<identifier>...}, as the CLI
     * reference's {@code proxyep1.endpoint.proxy-ab0cd1efghij.us-west-2.rds.amazonaws.com}.
     */
    public static String rdsProxyEndpointHost(String endpointName, String accountId, String region) {
        return rdsClusterEndpointHost(endpointName + ".endpoint", "proxy", accountId, region);
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
        if ("us-east-1".equals(region)) { // partition-literal: EC2 public DNS keeps compute-1 in us-east-1 alone
            return dashed + ".compute-1." + AwsRegions.DEFAULT_DNS_SUFFIX;
        }
        return dashed + "." + region + ".compute." + AwsRegions.dnsSuffixFor(region);
    }
}
