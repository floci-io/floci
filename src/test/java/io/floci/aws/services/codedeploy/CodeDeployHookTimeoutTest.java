package io.floci.aws.services.codedeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.ssm.SsmCommandService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeDeployHookTimeoutTest {

    @ParameterizedTest
    @MethodSource("serverTimeouts")
    void serverHookUsesAppSpecTimeoutOrOneHourDefault(String timeoutLine, int expectedTimeout) {
        String region = "us-east-1";
        SsmCommandService ssm = mock(SsmCommandService.class);
        when(ssm.isInstanceRegistered("onprem", region)).thenReturn(true);
        when(ssm.sendCommandToInstance(eq("onprem"), eq("AWS-RunShellScript"),
                anyMap(), eq(expectedTimeout), eq(region))).thenReturn("command");
        when(ssm.getCommandInvocationStatus("command", "onprem", region)).thenReturn("Success");

        CodeDeployService service = serverService(ssm, region);
        service.registerOnPremisesInstance(region, "onprem", null, null);

        service.createDeployment(region, "app", "group", null,
                Map.of("appSpecContent", Map.of("content", """
                        os: linux
                        hooks:
                          ApplicationStart:
                            - location: scripts/start.sh
                              %s
                        """.formatted(timeoutLine))), null);

        verify(ssm, timeout(3000)).sendCommandToInstance(eq("onprem"),
                eq("AWS-RunShellScript"), anyMap(), eq(expectedTimeout), eq(region));
    }

    static Stream<Arguments> serverTimeouts() {
        return Stream.of(
                Arguments.of("", 3600),
                Arguments.of("timeout: 900", 900),
                Arguments.of("timeout: \"900\"", 900),
                Arguments.of("timeout:", 3600),
                Arguments.of("timeout: ~", 3600));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "true"})
    void serverHookRejectsInvalidTimeout(String timeoutValue) {
        String region = "us-east-1";
        CodeDeployService service = serverService(mock(SsmCommandService.class), region);

        AwsException error = assertThrows(AwsException.class, () -> service.createDeployment(
                region, "app", "group", null,
                Map.of("appSpecContent", Map.of("content", """
                        os: linux
                        hooks:
                          ApplicationStart:
                            - location: scripts/start.sh
                              timeout: %s
                        """.formatted(timeoutValue))), null));
        assertEquals("InvalidRevisionException", error.getErrorCode());
    }

    private CodeDeployService serverService(SsmCommandService ssm, String region) {
        CodeDeployService service = new CodeDeployService(
                mock(LambdaService.class), mock(EcsService.class), mock(ElbV2Service.class),
                ssm, mock(Ec2Service.class), new ObjectMapper(),
                new RegionResolver(region, "000000000000"), null);
        service.createApplication(region, "app", "Server", null);
        service.createDeploymentGroup(region, "app", "group", null, "role", null, null);
        return service;
    }
}
