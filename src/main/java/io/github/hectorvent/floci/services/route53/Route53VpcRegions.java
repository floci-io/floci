package io.github.hectorvent.floci.services.route53;

import java.util.Set;

/**
 * The {@code VPCRegion} enum of the Route 53 model, kept verbatim. Deliberately not
 * {@code AwsRegions.KNOWN_IDS}: the two lists are maintained on different schedules, and the
 * model is what AWS validates a VPC association against.
 */
final class Route53VpcRegions {

    static final Set<String> VPC_REGIONS = Set.of(
            "us-east-1", "us-east-2", "us-west-1", "us-west-2",
            "eu-west-1", "eu-west-2", "eu-west-3", "eu-central-1", "eu-central-2",
            "ap-east-1", "ap-east-2", "me-south-1", "me-central-1",
            "us-gov-west-1", "us-gov-east-1",
            "us-iso-east-1", "us-iso-west-1", "us-isob-east-1", "us-isob-west-1",
            "us-isof-south-1", "us-isof-east-1", "eu-isoe-west-1", "eusc-de-east-1",
            "ap-southeast-1", "ap-southeast-2", "ap-southeast-3", "ap-southeast-4",
            "ap-southeast-5", "ap-southeast-6", "ap-southeast-7",
            "ap-south-1", "ap-south-2",
            "ap-northeast-1", "ap-northeast-2", "ap-northeast-3",
            "eu-north-1", "sa-east-1", "ca-central-1", "ca-west-1",
            "cn-north-1", "cn-northwest-1",
            "af-south-1", "eu-south-1", "eu-south-2",
            "il-central-1", "mx-central-1");

    private Route53VpcRegions() {
    }
}
