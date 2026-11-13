package io.floci.aws.services.cloudformation;

import software.amazon.awssdk.services.cloudformation.CloudFormationClient;

import java.util.List;

/**
 * What the replacement integration tests share: a resource's physical id after a stack operation,
 * and what a change set says about replacing one resource.
 */
final class CfnStackTestSupport {

    private CfnStackTestSupport() {
    }

    static String physicalId(CloudFormationClient client, String stack, String logicalId) {
        return client.describeStackResource(request -> request.stackName(stack).logicalResourceId(logicalId))
                .stackResourceDetail().physicalResourceId();
    }

    /** The {@code Replacement} value of every change the change set lists for {@code logicalId}. */
    static List<String> replacement(CloudFormationClient client, String stack, String changeSet,
                                    String logicalId) {
        return client.describeChangeSet(request -> request.stackName(stack).changeSetName(changeSet)).changes()
                .stream().filter(change -> logicalId.equals(change.resourceChange().logicalResourceId()))
                .map(change -> change.resourceChange().replacementAsString()).toList();
    }
}
