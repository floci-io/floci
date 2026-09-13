package io.github.hectorvent.floci.services.ec2.model;

/**
 * The credit option for CPU usage of one burstable performance instance.
 *
 * @param instanceId the instance
 * @param cpuCredits the credit option, either {@code standard} or {@code unlimited}
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_InstanceCreditSpecification.html">AWS EC2 InstanceCreditSpecification</a>
 */
public record InstanceCreditSpecification(
    String instanceId,
    String cpuCredits
) {}
