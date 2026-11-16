package io.floci.aws.services.bedrockagentcorecontrol;

import io.floci.aws.core.common.AwsArnUtils;

/**
 * Reads the resource out of an AgentCore ARN for the service classes under this package that look
 * a runtime, endpoint, gateway or memory up by ARN.
 */
final class BedrockAgentCoreArns {

    private BedrockAgentCoreArns() {
    }

    /**
     * The resource of an AgentCore ARN without a colon qualifier, including nested runtime endpoint
     * paths. Null when the value is not an ARN.
     */
    static String resource(String arn) {
        if (!AwsArnUtils.isArn(arn)) {
            return null;
        }
        String resource = AwsArnUtils.parse(arn).resource();
        int qualifier = resource.indexOf(':');
        return qualifier < 0 ? resource : resource.substring(0, qualifier);
    }
}
