package io.github.hectorvent.floci.core.common;

import java.util.List;

/**
 * Catalog of AWS regions the emulator advertises. Single source of truth for
 * every feature that enumerates regions (EC2 DescribeRegions, EKS ECR-mirror
 * hostnames, ...).
 */
public final class AwsRegions {

    public static final List<String> ALL = List.of(
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1",
            "ap-northeast-1", "ap-northeast-2", "ap-southeast-1", "ap-southeast-2",
            "ap-south-1", "sa-east-1", "ca-central-1");

    private AwsRegions() {
    }
}
