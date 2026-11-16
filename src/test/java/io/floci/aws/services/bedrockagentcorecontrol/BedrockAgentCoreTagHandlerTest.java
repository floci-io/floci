package io.floci.aws.services.bedrockagentcorecontrol;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BedrockAgentCoreTagHandlerTest {

    private static final String CUSTOM_REGION = "xx-custom-1";
    private static final String ACCOUNT = "000000000000";

    private final BedrockAgentCoreControlService runtimeService = mock(BedrockAgentCoreControlService.class);
    private final BedrockAgentCoreTagHandler handler = new BedrockAgentCoreTagHandler(runtimeService,
            mock(BedrockAgentCoreGatewayService.class), mock(BedrockAgentCoreMemoryService.class),
            mock(BedrockAgentCoreHarnessRegistry.class),
            new RegionResolver(CUSTOM_REGION, ACCOUNT, "aws-cn"));

    private static String endpointArn(String partition) {
        return "arn:" + partition + ":bedrock-agentcore:" + CUSTOM_REGION + ":" + ACCOUNT
                + ":runtime/test-AbCdEfGhIj/runtime-endpoint/prod";
    }

    @Test
    void customRegionOnChinaDeploymentResolvesItsOwnArn() {
        String arn = endpointArn("aws-cn");
        when(runtimeService.getEndpointTagsByArn(CUSTOM_REGION, arn)).thenReturn(Map.of("env", "prod"));

        assertEquals(Map.of("env", "prod"), handler.listTags(CUSTOM_REGION, arn));
    }

    @Test
    void customRegionRejectsAnArnOfAnotherPartition() {
        AwsException e = assertThrows(AwsException.class,
                () -> handler.listTags(CUSTOM_REGION, endpointArn("aws")));

        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }
}
