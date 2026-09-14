package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeDeployHookTimeoutTest {

    @Test
    void serverHookWithoutTimeoutUsesOneHourForSsmCommand() {
        String region = "us-east-1";
        SsmCommandService ssm = mock(SsmCommandService.class);
        when(ssm.isInstanceRegistered("onprem", region)).thenReturn(true);
        when(ssm.sendCommandToInstance(eq("onprem"), eq("AWS-RunShellScript"),
                anyMap(), eq(3600), eq(region))).thenReturn("command");
        when(ssm.getCommandInvocationStatus("command", "onprem", region)).thenReturn("Success");

        CodeDeployService service = new CodeDeployService(
                mock(LambdaService.class), mock(EcsService.class), mock(ElbV2Service.class),
                ssm, mock(Ec2Service.class), new ObjectMapper(),
                new RegionResolver(region, "000000000000"), null);
        service.createApplication(region, "app", "Server", null);
        service.createDeploymentGroup(region, "app", "group", null, "role", null);
        service.registerOnPremisesInstance(region, "onprem", null, null);

        service.createDeployment(region, "app", "group", null,
                Map.of("appSpecContent", Map.of("content", """
                        os: linux
                        hooks:
                          ApplicationStart:
                            - location: scripts/start.sh
                        """)), null);

        verify(ssm, timeout(3000)).sendCommandToInstance(eq("onprem"),
                eq("AWS-RunShellScript"), anyMap(), eq(3600), eq(region));
    }
}
