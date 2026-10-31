package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.github.hectorvent.floci.core.common.AwsArnUtils;

/**
 * Reads the resource out of an AgentCore ARN for the service classes under this package that look
 * a runtime, endpoint, gateway or memory up by ARN.
 */
final class BedrockAgentCoreArns {

    private BedrockAgentCoreArns() {
    }

    /**
     * The resource of an AgentCore ARN without its qualifier: {@code agent/<uuid>} for
     * {@code arn:aws:bedrock-agentcore:<region>:<account>:agent/<uuid>:<version>}. Null when the value
     * is not an ARN.
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
