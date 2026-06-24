package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElbV2TargetResolverTest {

    @Test
    void instanceTargetResolvesToBackingContainerBridgeIp() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Instance instance = new Instance();
        instance.setInstanceId("i-1234567890abcdef0");
        instance.setContainerBridgeIp("172.18.0.42");
        when(ec2Service.findInstanceById("i-1234567890abcdef0")).thenReturn(instance);

        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetType("instance");
        TargetDescription target = new TargetDescription();
        target.setId("i-1234567890abcdef0");

        assertEquals("172.18.0.42", ElbV2TargetResolver.resolveHost(ec2Service, targetGroup, target));
    }

    @Test
    void ipTargetKeepsRegisteredAddress() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        TargetGroup targetGroup = new TargetGroup();
        targetGroup.setTargetType("ip");
        TargetDescription target = new TargetDescription();
        target.setId("10.0.0.12");

        assertEquals("10.0.0.12", ElbV2TargetResolver.resolveHost(ec2Service, targetGroup, target));
    }
}
