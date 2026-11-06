package io.floci.aws.services.cloudformation.provisioners;

import io.floci.aws.core.common.AwsException;

/**
 * Exceptions shared by extracted CloudFormation resource provisioners.
 */
public final class CfnExceptions {

    private CfnExceptions() {
    }

    /**
     * Thrown when an update requires replacing a custom-named resource that kept its name.
     */
    public static AwsException customNamedReplacement(String name) {
        return new AwsException("ValidationError",
                "CloudFormation cannot update a stack when a custom-named resource requires replacing. "
                        + "Rename " + name + " and update the stack again.", 400);
    }
}
