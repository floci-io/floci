package io.github.hectorvent.floci.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ElbV2ServiceTest {

    private static final String REGION = "us-west-2";

    // Application Load Balancers require subnets in at least two Availability Zones.
    private static final List<String> ALB_SUBNETS = List.of("subnet-a", "subnet-b");

    @Mock
    ElbV2DataPlane dataPlane;

    @Mock
    ElbV2HealthChecker healthChecker;

    @Mock
    Ec2Service ec2Service;

    private ElbV2Service service;

    @BeforeEach
    void setUp() {
        service = new ElbV2Service();
        service.dataPlane = dataPlane;
        service.healthChecker = healthChecker;
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = ec2Service;
        stubAlbSubnets(ec2Service);
    }

    @Test
    void modifyListenerDefaultActionsRecompilesRulesWithoutRestartingListener() {
        String lbArn = service.createLoadBalancer(
                REGION, "sample-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
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
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
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

    @Test
    void initializeStorageReloadsPersistedResourcesAndRebuildsIndexes() {
        SharedStorageFactory storageFactory = new SharedStorageFactory();
        ElbV2DataPlane firstDataPlane = mock(ElbV2DataPlane.class);
        ElbV2HealthChecker firstHealthChecker = mock(ElbV2HealthChecker.class);
        ElbV2Service first = serviceWithStorage(storageFactory, firstDataPlane, firstHealthChecker);
        String lbArn = first.createLoadBalancer(
                REGION, "persisted-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of("owner", "platform")).getLoadBalancerArn();
        String tgArn = first.createTargetGroup(
                REGION, "persisted-tg", "HTTP", "HTTP1", 8080, "vpc-a", "instance",
                "HTTP", "traffic-port", true, "/health", 15, 5, 3, 2, "200",
                "ipv4", Map.of("tier", "web")).getTargetGroupArn();
        TargetDescription target = new TargetDescription();
        target.setId("i-1234567890abcdef0");
        target.setPort(8080);
        first.registerTargets(REGION, tgArn, List.of(target));
        String listenerArn = first.createListener(
                REGION, lbArn, "HTTP", 9080, null, List.of(),
                List.of(forwardAction(tgArn)), List.of(), Map.of("listener", "frontdoor")).getListenerArn();
        Rule rule = first.createRule(
                REGION, listenerArn, List.of(pathPattern("/api/*")),
                10, List.of(forwardAction(tgArn)), Map.of("rule", "api"));
        first.addTags(List.of(lbArn, tgArn, listenerArn, rule.getRuleArn()), Map.of("env", "test"));

        ElbV2DataPlane reloadedDataPlane = mock(ElbV2DataPlane.class);
        ElbV2HealthChecker reloadedHealthChecker = mock(ElbV2HealthChecker.class);
        ElbV2Service reloaded = serviceWithStorage(storageFactory, reloadedDataPlane, reloadedHealthChecker);

        assertEquals(lbArn, reloaded.describeLoadBalancers(REGION, null, List.of("persisted-lb"), null, null)
                .getFirst().getLoadBalancerArn());
        TargetGroup reloadedTargetGroup = reloaded.describeTargetGroups(REGION, lbArn, null, null).getFirst();
        assertEquals(tgArn, reloadedTargetGroup.getTargetGroupArn());
        assertEquals(List.of(lbArn), reloadedTargetGroup.getLoadBalancerArns());
        assertEquals("i-1234567890abcdef0", reloadedTargetGroup.getTargets().getFirst().getId());
        Listener reloadedListener = reloaded.describeListeners(REGION, lbArn, null).getFirst();
        assertEquals(listenerArn, reloadedListener.getListenerArn());
        List<Rule> reloadedRules = reloaded.describeRules(REGION, listenerArn, null);
        assertEquals(2, reloadedRules.size());
        assertTrue(reloadedRules.stream().anyMatch(Rule::isDefault));
        assertTrue(reloadedRules.stream().anyMatch(candidate -> "10".equals(candidate.getPriority())));
        assertEquals("test", reloaded.describeTags(List.of(lbArn)).get(lbArn).get("env"));
        assertThrows(AwsException.class, () -> reloaded.deleteTargetGroup(REGION, tgArn));
        verify(reloadedHealthChecker).startMonitoring(any(TargetGroup.class));
        ArgumentCaptor<List<TargetDescription>> targetsCaptor = ArgumentCaptor.captor();
        verify(reloadedHealthChecker).addTargets(eq(tgArn), targetsCaptor.capture(), any(TargetGroup.class));
        assertEquals("i-1234567890abcdef0", targetsCaptor.getValue().getFirst().getId());
        assertEquals(8080, targetsCaptor.getValue().getFirst().getPort());
        verify(reloadedDataPlane).startListener(any(Listener.class), eq(REGION), anyList());

        reloaded.removeTags(List.of(rule.getRuleArn()), List.of("env"));
        ElbV2Service updatedReload = serviceWithStorage(storageFactory, mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        assertFalse(updatedReload.describeTags(List.of(rule.getRuleArn()))
                .get(rule.getRuleArn()).containsKey("env"));
    }

    @Test
    void initializeStorageStartsPersistedListenersWithoutTargetGroups() {
        SharedStorageFactory storageFactory = new SharedStorageFactory();
        ElbV2Service first = serviceWithStorage(storageFactory, mock(ElbV2DataPlane.class), mock(ElbV2HealthChecker.class));
        String lbArn = first.createLoadBalancer(
                REGION, "listener-only-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        String listenerArn = first.createListener(
                REGION, lbArn, "HTTP", 8080, null, List.of(),
                List.of(), List.of(), Map.of()).getListenerArn();

        ElbV2DataPlane reloadedDataPlane = mock(ElbV2DataPlane.class);
        serviceWithStorage(storageFactory, reloadedDataPlane, mock(ElbV2HealthChecker.class));

        ArgumentCaptor<Listener> listenerCaptor = ArgumentCaptor.captor();
        verify(reloadedDataPlane).startListener(listenerCaptor.capture(), eq(REGION), anyList());
        assertEquals(listenerArn, listenerCaptor.getValue().getListenerArn());
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

    private static RuleCondition pathPattern(String value) {
        RuleCondition condition = new RuleCondition();
        condition.setField("path-pattern");
        condition.setValues(List.of(value));
        condition.setPathPatternValues(List.of(value));
        return condition;
    }

    private static ElbV2Service serviceWithStorage(StorageFactory storageFactory,
                                                   ElbV2DataPlane dataPlane,
                                                   ElbV2HealthChecker healthChecker) {
        Ec2Service ec2Service = mock(Ec2Service.class);
        stubAlbSubnets(ec2Service);
        return serviceWithStorage(storageFactory, dataPlane, healthChecker, ec2Service);
    }

    private static ElbV2Service serviceWithStorage(StorageFactory storageFactory,
                                                   ElbV2DataPlane dataPlane,
                                                   ElbV2HealthChecker healthChecker,
                                                   Ec2Service ec2Service) {
        ElbV2Service service = new ElbV2Service();
        service.dataPlane = dataPlane;
        service.healthChecker = healthChecker;
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = ec2Service;
        service.storageFactory = storageFactory;
        service.initializeStorage();
        return service;
    }

    private static void stubAlbSubnets(Ec2Service ec2Service) {
        Subnet subnetA = subnet("subnet-a", REGION + "a");
        Subnet subnetB = subnet("subnet-b", REGION + "b");
        lenient().when(ec2Service.requireSubnet(REGION, "subnet-a")).thenReturn(subnetA);
        lenient().when(ec2Service.requireSubnet(REGION, "subnet-b")).thenReturn(subnetB);
        lenient().when(ec2Service.describeSubnets(eq(REGION), eq(ALB_SUBNETS), eq(Map.of())))
                .thenReturn(List.of(subnetA, subnetB));
    }

    private static Subnet subnet(String subnetId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setAvailabilityZone(availabilityZone);
        subnet.setVpcId("vpc-a");
        return subnet;
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> StorageBackend<String, V> create(String serviceName,
                                                     String fileName,
                                                     TypeReference<Map<String, V>> typeReference) {
            return (StorageBackend<String, V>) stores.computeIfAbsent(fileName, ignored -> new InMemoryStorage<>());
        }
    }
}
