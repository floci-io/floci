package io.floci.aws.services.iam;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;

import java.util.List;
import java.util.Map;

/**
 * The simulation types {@code ResourceHandlingOption} names, and the resources each one has to be
 * given.
 *
 * <p>The member's reference says what it is for: "Specifies the type of simulation to run.
 * Different API operations that support resource-based policies require different combinations of
 * resources. By specifying the type of simulation to run, you enable the policy simulator to
 * enforce the presence of the required resources to ensure reliable simulation results." It then
 * publishes the four scenarios and what each requires, which is what this holds: "Each of the
 * Amazon EC2 scenarios requires that you specify instance, image, and security group resources. If
 * your scenario includes an EBS volume, then you must specify that volume as a resource. If the
 * Amazon EC2 scenario includes VPC, then you must supply the network interface resource. If it
 * includes an IP subnet, then you must specify the subnet resource."
 *
 * <p>Two refusals here are this emulator's own wording. AWS publishes no sentence for a scenario
 * whose resources are missing, nor for a value that names no scenario at all, and both are
 * {@code InvalidInput}, the error both simulators model for "an invalid or out-of-range value
 * supplied for an input parameter". The member's own length is held to the model ahead of this,
 * as every declared constraint of these operations is.
 */
final class SimulationScenarios {

    /** Each published scenario and the EC2 resource types a simulation of it has to name. */
    private static final Map<String, List<String>> REQUIRED = Map.of(
            "EC2-VPC-InstanceStore", List.of("instance", "image", "security-group",
                    "network-interface"),
            "EC2-VPC-InstanceStore-Subnet", List.of("instance", "image", "security-group",
                    "network-interface", "subnet"),
            "EC2-VPC-EBS", List.of("instance", "image", "security-group", "network-interface",
                    "volume"),
            "EC2-VPC-EBS-Subnet", List.of("instance", "image", "security-group",
                    "network-interface", "subnet", "volume"));

    private SimulationScenarios() {
    }

    /**
     * Holds a simulation to the resources its scenario requires, or passes a request that names no
     * scenario: "If your simulation does not match one of the following scenarios, then you can
     * omit this parameter." Omitting the member is what passes, not emptying it: a member the
     * request carries is a value rather than a member it left out, so a value of nothing but
     * spaces names no published scenario and is refused like any other value that names none. A
     * member carried empty never reaches here, the declared minimum of one answering it ahead of
     * this operation.
     */
    static void requireScenarioResources(String option, List<String> resourceArns) {
        if (option == null) {
            return;
        }
        List<String> required = REQUIRED.get(option);
        if (required == null) {
            throw new AwsException("InvalidInput", "ResourceHandlingOption " + option
                    + " is not one of the simulation types this operation runs.", 400);
        }
        for (String type : required) {
            if (!namesResourceOfType(resourceArns, type)) {
                throw new AwsException("InvalidInput", "ResourceHandlingOption " + option
                        + " requires a resource of type " + type + " among the ResourceArns.", 400);
            }
        }
    }

    /** Whether any resource ARN names an EC2 resource of {@code type}. */
    private static boolean namesResourceOfType(List<String> resourceArns, String type) {
        if (resourceArns == null) {
            return false;
        }
        String prefix = type + "/";
        for (String arn : resourceArns) {
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(arn);
            } catch (IllegalArgumentException ignored) {
                // A value the parser refuses names no resource of any type, whether it was meant
                // as an ARN or not, and the member that carried it is held to its shape elsewhere.
                continue;
            }
            if ("ec2".equals(parsed.service()) && parsed.resource() != null
                    && parsed.resource().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
