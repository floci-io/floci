package io.github.hectorvent.floci.services.ec2.model;

import java.util.List;

/**
 * Result of a paginated DescribeInstanceCreditSpecifications call.
 *
 * @param instanceCreditSpecifications the credit options for this page
 * @param nextToken                    token for the next page, or {@code null} when the page is the last
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeInstanceCreditSpecifications.html">AWS EC2 DescribeInstanceCreditSpecifications</a>
 */
public record InstanceCreditSpecificationListResult(
    List<InstanceCreditSpecification> instanceCreditSpecifications,
    String nextToken
) {}
