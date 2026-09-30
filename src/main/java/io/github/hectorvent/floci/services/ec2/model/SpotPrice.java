package io.github.hectorvent.floci.services.ec2.model;

import java.time.Instant;

/**
 * A spot price history entry.
 *
 * @param availabilityZone   the availability zone name
 * @param availabilityZoneId the availability zone id
 * @param instanceType       the instance type
 * @param productDescription the product description
 * @param spotPrice          the synthetic spot price formatted to six decimal places
 * @param timestamp          the effective timestamp
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_SpotPrice.html">AWS EC2 SpotPrice</a>
 */
public record SpotPrice(
        String availabilityZone,
        String availabilityZoneId,
        String instanceType,
        String productDescription,
        String spotPrice,
        Instant timestamp
) {}
