package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ElbV2ServiceTest {

    private static final String REGION = "us-west-2";

    @Mock
    ElbV2DataPlane dataPlane;

    @Mock
    ElbV2HealthChecker healthChecker;

    private ElbV2Service service;

    @BeforeEach
    void setUp() {
        service = new ElbV2Service();
        service.dataPlane = dataPlane;
        service.healthChecker = healthChecker;
        service.regionResolver = new RegionResolver(REGION, "000000000000");
    }

    @Test
    void modifyListenerDefaultActionsRecompilesRulesWithoutRestartingListener() {
        String lbArn = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                List.of("subnet-a"), List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String oldTgArn = createTargetGroup("sample-old-tg");
        String newTgArn = createTargetGroup("sample-new-tg");
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTP", 9999, null, List.of(),
                List.of(forwardAction(oldTgArn)), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);

        service.modifyListener(
                REGION, listenerArn, null, null, null, null,
                List.of(forwardAction(newTgArn)), null);

        ArgumentCaptor<List<Rule>> rulesCaptor = ArgumentCaptor.captor();
        verify(dataPlane).recompileRules(eq(listenerArn), rulesCaptor.capture());
        verify(dataPlane, never()).stopListener(anyString());
        verify(dataPlane, never()).startListener(any(Listener.class), anyString(), anyList());
        verify(dataPlane, never()).restartListener(any(Listener.class), anyString(), anyList());

        Rule defaultRule = rulesCaptor.getValue().stream()
                .filter(Rule::isDefault)
                .findFirst()
                .orElseThrow();
        assertEquals(newTgArn, defaultRule.getActions().getFirst().getTargetGroupArn());

        TargetGroup oldTargetGroup = service.describeTargetGroups(REGION, null, List.of(oldTgArn), null).getFirst();
        TargetGroup newTargetGroup = service.describeTargetGroups(REGION, null, List.of(newTgArn), null).getFirst();
        assertFalse(oldTargetGroup.getLoadBalancerArns().contains(lbArn));
        assertTrue(newTargetGroup.getLoadBalancerArns().contains(lbArn));
    }

    @Test
    void modifyListenerPortRestartsListener() {
        String lbArn = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                List.of("subnet-a"), List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String tgArn = createTargetGroup("sample-tg");
        String listenerArn = service.createListener(
                REGION, lbArn, "HTTP", 9999, null, List.of(),
                List.of(forwardAction(tgArn)), List.of(), Map.of()).getListenerArn();
        clearInvocations(dataPlane);

        service.modifyListener(REGION, listenerArn, null, 10000, null, null, null, null);

        verify(dataPlane).restartListener(any(Listener.class), eq(REGION), anyList());
        verify(dataPlane, never()).stopListener(anyString());
        verify(dataPlane, never()).startListener(any(Listener.class), anyString(), anyList());
    }

    private String createTargetGroup(String name) {
        return service.createTargetGroup(
                REGION, name, "HTTP", "HTTP1", 9999, "vpc-a", "instance",
                "HTTP", "traffic-port", true, "/v1/ready", 30, 5, 5, 2, "200",
                "ipv4", Map.of()).getTargetGroupArn();
    }

    private static Action forwardAction(String targetGroupArn) {
        Action action = new Action();
        action.setType("forward");
        action.setTargetGroupArn(targetGroupArn);
        return action;
    }
}
